#include <assert.h>
#include <string.h>
#include "../opengpu_shader_image_command.h"

struct device { unsigned reads; u32 magic; };
static u32 read_reg(void *context, u32 address)
{
    struct device *d = context;
    assert(address == OPENGPU_IMAGE_COMMAND_REG_MAGIC);
    ++d->reads;
    return d->magic;
}

int main(void)
{
    struct device d = { 0, OPENGPU_IMAGE_COMMAND_MAGIC_VALUE };
    bool supported = true;
    const u32 flag = OPENGPU_COMPUTE_RESOURCE_IMAGE_COMMANDS;
    u32 words[7], saved[7];
    struct opengpu_image_command_context image = {
        .version = 1, .base = 0xffffff00u, .limit = 1ULL << 32,
        .context = 0xffffffffu, .pass = 0x81234567u, .generation = 0xfedcba98u
    };
    assert(opengpu_read_image_command_support(15, 0, &d, read_reg, &supported));
    assert(!supported && d.reads == 0);
    assert(!opengpu_read_image_command_support(flag, 0x3ff, &d, read_reg, &supported));
    assert(!supported && d.reads == 0);
    d.magic = 0;
    assert(!opengpu_read_image_command_support(flag, 0x400, &d, read_reg, &supported));
    assert(!supported && d.reads == 1);
    d.magic = OPENGPU_IMAGE_COMMAND_MAGIC_VALUE;
    assert(opengpu_read_image_command_support(flag, 0x400, &d, read_reg, &supported));
    assert(supported && d.reads == 2);
    assert(!opengpu_read_image_command_support(flag, 0x400, &d, 0, &supported));
    assert(!supported && d.reads == 2);
    assert(!opengpu_read_image_command_support(flag, 0x400, &d, read_reg, 0));
    assert(opengpu_encode_image_command_context(words, &image));
    assert(words[0] == 0x101 && words[1] == 0xffffff00u && words[2] == 0 && words[3] == 1);
    assert(words[4] == image.context && words[5] == image.pass && words[6] == image.generation);
    memcpy(saved, words, sizeof(words));
    assert(!opengpu_encode_image_command_context(0, &image));
    assert(!opengpu_encode_image_command_context(words, 0));
    for (unsigned i = 0; i < 7; ++i) {
        struct opengpu_image_command_context bad = image;
        switch (i) {
        case 0: bad.version = 0; break;
        case 1: bad.version = 257; break;
        case 2: bad.base += 1; break;
        case 3: bad.limit -= 1; break;
        case 4: bad.limit = bad.base; break;
        case 5: bad.limit += 64; break;
        default: bad.limit -= 64; break;
        }
        assert(!opengpu_encode_image_command_context(words, &bad));
        assert(memcmp(words, saved, sizeof(words)) == 0);
    }
    return 0;
}
