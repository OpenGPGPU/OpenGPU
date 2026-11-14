"""Independent GNY2 expectations for the fixed ordinary112 ABI1 triangle.

This is not an executor or source-provenance check. The caller must first bind
the original compiler/reflection inputs, canonical descriptors, successful held
receipt and original command/context identity. One ordinary clipping triangle,
one layer/tile, 4x4 RGBA32UI, 1x, clear/store, no GS/TF/resolve/resume/spill.
No point-recipe counter constants or returned fields supply expected values.
"""
from fractions import Fraction
from functools import lru_cache
import json
from pathlib import Path
import struct

CATALOG = Path(__file__).resolve().parents[1] / 'docs/abi/native-layered-graphics-v2.json'

# These are entire zero-initialized fault/restart snapshots, not summaries of
# successful work. Their only nonzero assignment branches cannot run on this
# successful non-exhausted recipe. Exact counts reject a new/unclassified tree.
ZERO_SNAPSHOTS = {
    'frame.sourceFault.': 108,  # NativeLayeredVertexBinImagePipeline: sourceFailure only
    'frame.images.producer.restart.': 336,  # WindowPrimitiveBinProducer: status==2 only
    'frame.images.images.fault.': 111,  # LayeredTileChainScheduler: source/childFault only
}


def require(ok, message):
    if not ok:
        raise ValueError('ordinary112 GNY2: ' + message)


@lru_cache(maxsize=1)
def clipped_polygon():
    """Exact geometric half-space clipping with duplicate boundary removal.

    This uses rational geometry, not the RTL state machine or shader words.
    All intersections in this particular triangle are exactly representable.
    PrimitiveClipStage's boundary-endpoint rule implements the same polygon.
    """
    polygon = [tuple(map(Fraction, v)) for v in
               [(-1, -1, 0, 1), (3, -1, 0, 1), (-1, 3, 0, 1)]]
    for axis in range(3):
        for side in (-1, 1):
            result = []

            def append(vertex):
                if not result or result[-1] != vertex:
                    result.append(vertex)

            previous = polygon[-1]
            da = previous[3] - side * previous[axis]
            for current in polygon:
                db = current[3] - side * current[axis]
                if (da >= 0) != (db >= 0):
                    weight = da / (da - db)
                    append(tuple(a + weight * (b - a) for a, b in zip(previous, current)))
                if db >= 0:
                    append(current)
                previous, da = current, db
            if len(result) > 1 and result[0] == result[-1]:
                result.pop()
            polygon = result
    return tuple(polygon)


@lru_cache(maxsize=1)
def record_layout():
    """Independent scalar-width census of the full-p16 GWP1 RTL format.

    WindowPrimitiveRecordLayout gives each leaf ceil(width/32) words in sorted
    Record/numeric Vec order, never crossing a 16-word line; polygon-offset
    leaves are appended and geometry.* uses the header instead of leaf words.
    Widths below come from the actual Bundle definitions, not the point oracle.
    """
    identity = dict(context=32, generation=32, **{'pass': 32})
    shaded = dict(identity=identity, ordinal=32, vertexId=32, instanceId=32,
        baseInstance=32, restart=1, pointSize=32, pointSizeWritten=1,
        position=[32]*4, varyings=[[32]*4 for _ in range(16)])
    assembled = dict(identity=identity, sequence=64, primitiveId=32, instanceId=32,
        baseInstance=32, topology=4, kind=2, vertexCount=2, provokingVertex=2,
        loopClosing=1, varyingSlots=5, flatMask=64, vertices=[shaded]*3)
    clipped = dict(primitive=assembled, partIndex=3, partCount=4, generated=3,
        clipped=1, sourceProvokingOrdinal=32, sourceProvokingVertexId=32)
    clip_float = dict(negative=1, exponent=12, significand=24, special=2)
    window = dict(localX=clip_float, localY=clip_float, screenX=64, screenY=64,
        fixedValid=1, coordinateInexact=1, reciprocalW=clip_float, windowDepth=32)
    viewport = dict(version=8, x=32, y=32, extentX=32, extentY=32, nearDepth=32, farDepth=32)
    primitive = dict(clipped=clipped, viewport=viewport, vertices=[window]*3, guardBandRequired=1)
    raster = dict(version=8, screenW=16, screenH=16, cullMode=2, frontFaceCCW=1,
        sampleMode=2, pointSize=32, lineWidth=32, scissorEnable=1, scissorMinX=16,
        scissorMinY=16, scissorMaxX=16, scissorMaxY=16, polygonOffsetEnable=1,
        polygonOffsetFactor=32, polygonOffsetUnits=32)
    tree = dict(primitive=primitive, raster=raster, state=32, sequence=32, token=32)

    def leaves(tree, prefix=''):
        if isinstance(tree, dict):
            return [(n, w) for name in sorted(tree) for n, w in
                    leaves(tree[name], prefix + ('.' if prefix else '') + name)]
        if isinstance(tree, list):
            return [(n, w) for i, item in enumerate(tree) for n, w in leaves(item, prefix+'[%d]'%i)]
        return [(prefix, tree)]

    fields = leaves(tree)
    fields = [f for f in fields if not f[0].startswith('raster.polygonOffset')] + \
             [f for f in fields if f[0].startswith('raster.polygonOffset')]
    word, layout = 16, []
    for name, bits in fields:
        size = (bits + 31)//32
        if word % 16 + size > 16:
            word = (word + 15)//16*16
        layout.append((name, word, bits))
        word += size
    return tuple(layout), (word + 15)//16


def values(command_id, context):
    require(type(command_id) is int and 0 < command_id < 256, 'wrong original command identity')
    require(type(context) is int and 0 < context < 1 << 32, 'wrong original context identity')
    parts = len(clipped_polygon()) - 2
    layout, lines = record_layout()
    require(parts == 2 and len(layout) == 332 and lines == 23, 'unsupported fixed geometry/record layout')
    # NativeBinnedGraphicsJobReader: job+frame+one GFS preflight; one GND
    # scanned before begin and fetched again for execution. GS is absent.
    result = dict(commandId=command_id, descriptorReadBytes=512+2048+512,
        drawReadBytes=2*2048, issuedDraws=1, completedDraws=1,
        faultAddress=0, faultDraw=0, faultStage=0, status=0)
    result.update({
        # NativeLayeredVertexBinImagePipeline counters increment on accepted
        # source fan parts, not the one original input triangle or draw.
        'frame.version': 1, 'frame.draws': 1, 'frame.admittedParts': parts,
        'frame.receivedParts': parts, 'frame.droppedParts': 0,
        'frame.submittedGeometryPrimitives': 0, 'frame.completedGeometryInvocations': 0,
        'frame.emittedGeometryPrimitives': 0, 'frame.sourceFaultValid': 0,
        'frame.faultDrawValid': 0, 'frame.faultDraw': 0, 'frame.faultStage': 0, 'frame.status': 0,
        'frame.images.version': 1, 'frame.images.status': 0,
        # One nonempty tile, one pool page; fresh v3 initialization and final
        # dirty metadata flush write two lines, with one cache-miss read.
        'frame.images.directory.allocatedPages': 1,
        'frame.images.directory.committedCount': parts,
        'frame.images.directory.directoryMisses': 1,
        'frame.images.directory.directoryReadBytes': 64,
        'frame.images.directory.directoryWriteBytes': 2*64,
        'frame.images.directory.tilesOpened': 1,
        'frame.images.directory.spillTransitions': 0, 'frame.images.directory.status': 0,
        # Producer publishes two complete full-capacity records and appends
        # one reference for each to the same closed original tile chain.
        'frame.images.producer.accepted': parts, 'frame.images.producer.stored': parts,
        'frame.images.producer.references': parts, 'frame.images.producer.recordWriteLines': parts*lines,
        'frame.images.producer.restartValid': 0, 'frame.images.producer.binStatus': 0,
        'frame.images.producer.faultTile': 0, 'frame.images.producer.status': 0,
        # LayeredWindowPrimitiveReplay scans/reads/selects both ordinary
        # layer-zero records once. Header32 + two reference16 fits one line.
        'frame.images.images.version': 1, 'frame.images.images.tiles': 1,
        'frame.images.images.scanned': parts, 'frame.images.images.selected': parts,
        'frame.images.images.pageReadLines': (32 + 16*parts + 63)//64,
        'frame.images.images.recordReadLines': parts*lines,
        # TypedReplayTileImagePipeline: one accepted/issued/successful draw
        # for each record. Clear/store flushes the tile once after both.
        'frame.images.images.accepted': parts, 'frame.images.images.issued': parts,
        'frame.images.images.completed': parts,
        'frame.images.images.storedBytes': 4*4*16,
        'frame.images.images.loadBytes': 0, 'frame.images.images.resolvedBytes': 0,
        'frame.images.images.faultValid': 0, 'frame.images.images.overflow': 0,
        'frame.images.images.status': 0,
    })
    for prefix in ('identity.', 'frame.identity.', 'frame.images.identity.',
                   'frame.images.producer.identity.', 'frame.images.directory.identity.',
                   'frame.images.images.identity.'):
        result.update({prefix+'context': context, prefix+'pass': 11, prefix+'generation': 13})
    return result


def classify_layout(catalog):
    require((catalog.get('magic'), catalog.get('version'), catalog.get('words'), catalog.get('bytes')) ==
            ('0x474e5902', 2, 4096, 16384), 'unsupported GNY2 envelope')
    fields = catalog.get('fields', [])
    require(len(fields) == 628, 'unsupported GNY2 field count')
    known = set(values(1, 1))
    names, occupied, layout = set(), {0, 1, 2}, []
    snapshots = dict.fromkeys(ZERO_SNAPSHOTS, 0)
    for field in fields:
        name, word, bits, signed = (field.get(k) for k in ('name', 'word', 'bits', 'signed'))
        require(type(name) is str and name not in names and type(word) is int and
                type(bits) is int and 0 < bits <= 512 and type(signed) is bool, 'invalid GNY2 field')
        size = (bits+31)//32
        slots = set(range(word, word+size))
        require(word >= 3 and word+size <= 4096 and word//16 == (word+size-1)//16 and
                not occupied.intersection(slots), 'overlapping/out-of-bounds GNY2 placement')
        matched = [prefix for prefix in snapshots if name.startswith(prefix)]
        require(len(matched) <= 1 and (matched or name in known), 'unclassified GNY2 field: '+name)
        if matched:
            snapshots[matched[0]] += 1
        names.add(name); occupied.update(slots); layout.append((name, word, bits, signed))
    require(snapshots == ZERO_SNAPSHOTS and not known.difference(names) and
            len(known) == 73 and len(occupied) == 766, 'incomplete GNY2 field/zero classification')
    # The completion's restart snapshot has the actual WindowPrimitiveRecord
    # type. Cross-check every independently censused full-p16 leaf width,
    # without using its completion placement as the GWP record placement.
    restart = 'frame.images.producer.restart.'
    actual_widths = {name[len(restart):]: bits for name, _, bits, _ in layout
                     if name.startswith(restart) and not name.startswith(restart+'geometry.')}
    expected_widths = {name: bits for name, _, bits in record_layout()[0]}
    require(actual_widths == expected_widths, 'catalog differs from the complete full-p16 record width census')
    return tuple(layout)


@lru_cache(maxsize=1)
def _layout():
    return classify_layout(json.loads(CATALOG.read_text())['completion'])


@lru_cache(maxsize=128)
def _expected_words(command_id, context):
    expected = values(command_id, context)
    words = [0]*4096
    words[:3] = [0x474e5902, 16384, 2]
    for name, word, bits, signed in _layout():
        value = 0 if any(name.startswith(prefix) for prefix in ZERO_SNAPSHOTS) else expected[name]
        require(0 <= value < 1 << (bits-int(signed)), 'expected field does not fit: '+name)
        for offset in range((bits+31)//32):
            words[word+offset] = value >> (32*offset) & 0xffffffff
    return tuple(words)


def verify(data, *, command_id, context):
    """Require all 4096 words; expected identities come from the live owner.

    No value in data authorizes expected context/command identity. Unknown
    fields, fault/restart snapshots, high padding and reserved words fail.
    """
    require(type(data) is bytes and len(data) == 16384, 'GNY2 must be exactly 16384 bytes')
    # Validate before the cache: Python bool/int equality must not alias keys.
    values(command_id, context)
    expected = _expected_words(command_id, context)
    for index, (got, want) in enumerate(zip(struct.unpack('<4096I', data), expected)):
        if got != want:
            raise ValueError('ordinary112 GNY2: word %d: %#x != %#x' % (index, got, want))
