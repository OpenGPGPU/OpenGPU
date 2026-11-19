"""Bind original buffer-texture publication sources to captured mappings."""
import struct
from compute_owner_protocol import required_flags, uint, profile_bytes
from compute_texture_publication_cases import CASES, parameters
from compute_indirect_oracle import capture, executable_physical
from compute_counter_source import translate
from owner_resource_contract import IMAGE_FLOAT_PROFILE

SCHEMA = 'opengpu-compute-texture-publication-oracle-v1'
IMAGE_FORMAT = 14
TEXTURE_FORMAT = 14
VIEW_BYTES = 8192
TEXELS = VIEW_BYTES // 4
TEXTURE_VA = 0xa0000000 + 3 * (4 * 1024 * 1024)
TEXTURE_TABLE_BYTES = 128 + (64 + 128 + 64 + 128 * 16)


def require(ok, message):
    if not ok:
        raise ValueError('texture publication source: ' + message)


def recipe(memory, description):
    p = parameters(description.get('sourceCase'))
    expected = dict(
        schema=SCHEMA, sourceCase=p['case'], role=p['role'], epoch=p['epoch'],
        groups=1, local=1, viewBytes=VIEW_BYTES, guard=0xdeadbeef,
        expectedData=p['expected_data'], expectedFlag=p['expected_flag'])
    required_flags(description, expected)
    common = {
        'context', 'output', 'storageBinding', 'storagePhysical', 'storageRoot',
        'kernargPhysical', 'kernargBytes', 'codePhysical', 'codeBytes'}
    if p['role'] == 'producer':
        require(set(description) == set(expected) | common | {
            'image', 'imageBinding', 'imagePhysical', 'imageRoot'},
            'foreign or missing texture publication producer recipe fields')
        require(uint(description['storageBinding']) == 0 and uint(description['imageBinding']) == 0,
                'original image/output bindings differ')
        published = description['image']
        require(type(published) is dict and set(published) == {
            'apiBinding', 'nativeUnit', 'handle', 'allocationPhysical', 'allocationBytes',
            'bindingBytes', 'view'}, 'original image fields differ')
        required_flags(published, dict(apiBinding=0, nativeUnit=0, allocationBytes=VIEW_BYTES,
                                       bindingBytes=VIEW_BYTES))
        view = published['view']
        require(type(view) is dict and view == dict(
            offset=0, rowPitch=VIEW_BYTES, slicePitch=0, layerPitch=0, slot=39, flags=3,
            target=5, storageFormat=IMAGE_FORMAT, imageFormat=IMAGE_FORMAT, level=0,
            width=TEXELS, height=1, depth=1, layers=1, layer=0),
            'original coherent r32ui buffer-image view differs')
    else:
        require(set(description) == set(expected) | common | {
            'texture', 'textureBinding', 'texturePhysical', 'textureRoot'},
            'foreign or missing texture publication consumer recipe fields')
        require(uint(description['storageBinding']) == 0 and uint(description['textureBinding']) == 0,
                'original texture/output bindings differ')
        published = description['texture']
        require(type(published) is dict and set(published) == {
            'apiBinding', 'nativeUnit', 'handle', 'allocationPhysical', 'allocationBytes',
            'bindingBytes', 'view'}, 'original texture fields differ')
        required_flags(published, dict(apiBinding=0, nativeUnit=0, allocationBytes=VIEW_BYTES,
                                       bindingBytes=VIEW_BYTES))
        view = published['view']
        require(type(view) is dict and view == dict(
            slot=3, target=5, format=TEXTURE_FORMAT, bufferTexels=TEXELS,
            height=1, depth=1, layers=1, levelCount=1, offset=0, bufferBytes=VIEW_BYTES),
            'original usamplerBuffer view differs')
    target = uint(published['allocationPhysical'])
    require(target and not target & 4095 and
            any((a, b) == (target, target + VIEW_BYTES) for a, b, _ in memory.ranges),
            'published GEM lacks its exact original physical allocation')
    output_view = description['output']
    required_flags(output_view, dict(globalBinding=0, allocationBytes=VIEW_BYTES, offset=0,
                                     bytes=VIEW_BYTES))
    require(set(output_view) == {
        'globalBinding', 'allocationPhysical', 'allocationBytes', 'offset', 'bytes'},
        'foreign original output view fields')
    output = uint(output_view['allocationPhysical'])
    require(output and not output & 4095 and
            any((a, b) == (output, output + VIEW_BYTES) for a, b, _ in memory.ranges),
            'output lacks its exact original physical allocation')
    require(target + VIEW_BYTES <= output or output + VIEW_BYTES <= target,
            'original published GEM/output alias')
    if p['role'] == 'warm':
        initial = bytearray(b'\x6b' * VIEW_BYTES)
        struct.pack_into('<I', initial, 4, 0)
        struct.pack_into('<I', initial, 17 * 4, 0)
        require(memory.read(target, VIEW_BYTES) == initial and
                memory.read(output, VIEW_BYTES) == b'\xa7' * VIEW_BYTES,
                'warm phase lost its original zero data/flag seed')
    return p, target, output


def _check_code(memory, description, code_words, satp, staged):
    import re
    require(code_words and all(re.fullmatch('[0-9a-f]{8}', word) for word in code_words),
            'invalid actual code words')
    code = b''.join(struct.pack('<I', int(word, 16)) for word in code_words)
    require(len(code) == uint(description['codeBytes'], 64) and
            memory.read(uint(description['codePhysical']), len(code)) == code,
            'compiler code snapshot differs')
    for offset in range(0, len(code), 4):
        address = executable_physical(memory, satp, staged.get(0xcc, 0) + offset)
        require(memory.read(address, 4) == code[offset:offset + 4],
                'staged executable differs from actual Mesa code')


def _check_storage(memory, description, satp, staged, output, context):
    root, physical = uint(description['storageRoot']), uint(description['storagePhysical'])
    require([staged.get(0x420 + 4 * n) for n in range(7)] == [257, root, root + 256, 0, context, 0, 0],
            'original storage command/root identity differs')
    require(translate(memory, satp, root, 15) == physical, 'storage descriptor private mapping differs')
    storage_base = 0xcfffc000
    expected = (0x47534201, 64, 1, context, 0, 0, 0, 1, 1, 1,
                storage_base, storage_base + VIEW_BYTES, 0, storage_base, storage_base + VIEW_BYTES, 0)
    require(memory.read(physical, 64) == struct.pack('<16I', *expected) and
            translate(memory, satp, storage_base, 15) == output and
            translate(memory, satp, storage_base + VIEW_BYTES - 1, 15) == output + VIEW_BYTES - 1,
            'actual output descriptor or GEM mapping differs')
    require(memory.read(physical + 64, 192) == bytes(192), 'unused storage descriptors are enabled')


def ready(memory, description, data, code_words, profile):
    require(profile.get('ownerProfile') == IMAGE_FLOAT_PROFILE, 'exact complete float factory required')
    profile_bytes(profile)
    p, target, output = recipe(memory, description)
    _, args, writable, inactive, actions, staged = capture(data, 0, profile)
    require(args is None and [staged.get(0xd4 + 4 * n) for n in range(3)] == [1, 1, 1] and
            [staged.get(0xe0 + 4 * n) for n in range(3)] == [1, 1, 1],
            'original publication group/local shape differs')
    satp = staged.get(0x14c, 0)
    require(satp == staged.get(0x150), 'instruction/data namespace differs')
    arg = uint(description['kernargPhysical'])
    argbytes = uint(description['kernargBytes'])
    require(argbytes and translate(memory, satp, staged.get(0xd0, 0), 15) == arg and
            any(a <= arg < arg + argbytes <= b for a, b, _ in memory.ranges),
            'original kernarg mapping differs')
    context = description['context']
    if p['role'] == 'producer':
        root, physical = uint(description['imageRoot']), uint(description['imagePhysical'])
        require([staged.get(0x3e0 + 4 * n) for n in range(7)] == [257, root, root + 1024, 0, context, 0, 0] and
                translate(memory, satp, root, 15) == physical and
                translate(memory, satp, root + 1023, 15) == physical + 1023,
                'original private GIU1 table/context differs')
        base = 0x87ffd000
        wanted = [0] * 64
        wanted[:7] = [0x47495501, 256, 1, context, 0, 0, 0]
        wanted[7:26] = [1, 1, 4, 1, 1, IMAGE_FORMAT, IMAGE_FORMAT, 0, 0, 0, 0, 0, 0, 5, 0,
                        base, base + VIEW_BYTES, 0, base]
        wanted[26:30] = [1, 1, 1, 1]
        wanted[37:40] = [4, TEXELS, VIEW_BYTES]
        require(memory.read(physical, 256) == struct.pack('<64I', *wanted) and
                translate(memory, satp, base, 15) == target and
                translate(memory, satp, base + VIEW_BYTES - 1, 15) == target + VIEW_BYTES - 1,
                'actual image descriptor or GEM mapping differs')
        for unit in range(1, 4):
            empty = [0] * 64
            empty[:7] = [0x47495501, 256, 1, context, 0, 0, unit]
            require(memory.read(physical + unit * 256, 256) == struct.pack('<64I', *empty),
                    'unused image descriptors are enabled')
        require([staged.get(0x480 + 4 * n) for n in range(5)] == [0, 0, 0, 0, 0] or
                staged.get(0x480, 0) == 0,
                'producer staged an unexpected texture command')
    else:
        root, physical = uint(description['textureRoot']), uint(description['texturePhysical'])
        require([staged.get(0x480 + 4 * n) for n in range(5)] == [257, root, context, 0, 0] and
                translate(memory, satp, root, 15) == physical and
                translate(memory, satp, root + TEXTURE_TABLE_BYTES - 1, 15) ==
                physical + TEXTURE_TABLE_BYTES - 1,
                'original private texture command/root differs')
        words = struct.unpack('<%dI' % (TEXTURE_TABLE_BYTES // 4),
                              memory.read(physical, TEXTURE_TABLE_BYTES))
        require(words[0] == 0x47585201 and words[1] == 128 and words[2] == 1 and
                words[3] == context and words[6] == 2 and words[7] == 1,
                'texture root record differs')
        binding = words[32:48]
        require(binding[0] == 0x47584201 and binding[1] == 64 and binding[7] == 0 and
                binding[8] == 0 and binding[10] == 1 and binding[11] == 5,
                'texture binding record differs')
        image = words[48:80]
        require(image[0] == 0x47584901 and image[1] == 128 and image[7] == 5 and
                image[8] == TEXTURE_FORMAT and image[15] == TEXTURE_VA and
                image[16] == TEXTURE_VA + VIEW_BYTES and image[28] == TEXELS and
                image[29] == VIEW_BYTES,
                'texture image record differs')
        level = words[96:128]
        require(level[0] == 0x47584c01 and level[1] == 128 and level[7] == 5 and
                level[9] == TEXTURE_VA and level[10] == TEXTURE_VA + VIEW_BYTES and
                level[12] == TEXTURE_VA and level[24] == 4 and level[25] == TEXELS and
                level[26] == VIEW_BYTES,
                'texture level record differs')
        require(translate(memory, satp, TEXTURE_VA, 15) == target and
                translate(memory, satp, TEXTURE_VA + VIEW_BYTES - 1, 15) == target + VIEW_BYTES - 1,
                'actual texture GEM mapping differs')
        require(staged.get(0x3e0, 0) == 0, 'consumer staged an unexpected image command')
    _check_storage(memory, description, satp, staged, output, context)
    _check_code(memory, description, code_words, satp, staged)
    return target, output


def returned_outputs(initial, returned, description):
    p, target, output = recipe(initial, description)
    data_off = target + 4
    flag_off = target + 17 * 4
    if p['role'] == 'warm':
        require(returned.read(data_off, 4) == struct.pack('<I', 0) and
                returned.read(flag_off, 4) == struct.pack('<I', 0) and
                returned.read(output, 8) == struct.pack('<II', 0, 0),
                'warm consumer observed non-zero publication words')
        return [(output, struct.pack('<II', 0, 0))]
    expected = struct.pack('<II', p['expected_data'], p['expected_flag'])
    require(returned.read(data_off, 4) + returned.read(flag_off, 4) == expected,
            'published data/flag words differ')
    require(returned.read(output, 8) == expected, 'publication output mirror differs')
    return [
        (data_off, struct.pack('<I', p['expected_data'])),
        (flag_off, struct.pack('<I', p['expected_flag'])),
        (output, expected),
    ]
