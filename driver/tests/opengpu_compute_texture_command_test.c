#include <assert.h>
#include <string.h>
#include "../opengpu_compute_texture_command.h"
struct device { unsigned reads; u32 magic; };
struct writes { u32 address[5], value[5]; unsigned count; };
static void write_reg(void *ctx, u32 address, u32 value) {
    struct writes *w = ctx; assert(w->count < 5);
    w->address[w->count] = address; w->value[w->count++] = value;
}
static u32 read_reg(void *ctx, u32 address) {
    struct device *d=ctx; assert(address==OPENGPU_COMPUTE_TEXTURE_COMMAND_REG_MAGIC); ++d->reads; return d->magic;
}
int main(void) {
    struct device d={0,OPENGPU_COMPUTE_TEXTURE_COMMAND_MAGIC_VALUE}; bool supported=true;
    const u32 flag=OPENGPU_COMPUTE_RESOURCE_TEXTURE_COMMANDS; u32 words[5],saved[5];
    struct opengpu_compute_texture_command_context t={.version=1,.root=0xffffff80u,.context=7,.pass=11,.generation=13};
    assert(opengpu_read_compute_texture_command_support(127,0,&d,read_reg,&supported) && !supported && d.reads==0);
    assert(!opengpu_read_compute_texture_command_support(flag,0x498,&d,0,&supported) && !supported && d.reads==0);
    assert(!opengpu_read_compute_texture_command_support(flag,0x498,&d,read_reg,0) && d.reads==0);
    assert(!opengpu_read_compute_texture_command_support(flag,0x497,&d,read_reg,&supported) && d.reads==0);
    d.magic=0; assert(!opengpu_read_compute_texture_command_support(flag,0x498,&d,read_reg,&supported));
    d.magic=OPENGPU_COMPUTE_TEXTURE_COMMAND_MAGIC_VALUE;
    assert(opengpu_read_compute_texture_command_support(flag,0x498,&d,read_reg,&supported) && supported);
    assert(opengpu_encode_compute_texture_command_context(words,&t));
    assert(words[0]==0x101 && words[1]==t.root && words[2]==7 && words[3]==11 && words[4]==13);
    {
        struct writes writes = {0};
        opengpu_write_compute_texture_command(&writes, write_reg, words);
        assert(writes.count == 5);
        for (u32 i = 1; i < 5; ++i) {
            assert(writes.address[i - 1] == OPENGPU_COMPUTE_TEXTURE_COMMAND_BASE + i * 4);
            assert(writes.value[i - 1] == words[i]);
        }
        assert(writes.address[4] == OPENGPU_COMPUTE_TEXTURE_COMMAND_REG_CONTROL);
        assert(writes.value[4] == words[0]);
        writes.count = 0;
        opengpu_write_compute_texture_command(&writes, write_reg, NULL);
        assert(writes.count == 1 && writes.address[0] == OPENGPU_COMPUTE_TEXTURE_COMMAND_REG_CONTROL);
        assert(writes.value[0] == 0);
    }
    memcpy(saved,words,sizeof(words));
    for(unsigned i=0;i<4;++i){struct opengpu_compute_texture_command_context bad=t;
        if(i==0)bad.version=0; else if(i==1)bad.root++; else if(i==2)bad.root=0xffffffc0u; else bad.version=2;
        assert(!opengpu_encode_compute_texture_command_context(words,&bad) && !memcmp(words,saved,sizeof(words)));
    }
    assert(!opengpu_encode_compute_texture_command_context(0,&t));
    assert(!opengpu_encode_compute_texture_command_context(words,0));
    return 0;
}
