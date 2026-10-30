#include <assert.h>
#include <string.h>
#include "../opengpu_compute_texture_command.h"
struct device { unsigned reads; u32 magic; };
static u32 read_reg(void *ctx, u32 address) {
    struct device *d=ctx; assert(address==OPENGPU_COMPUTE_TEXTURE_COMMAND_REG_MAGIC); ++d->reads; return d->magic;
}
int main(void) {
    struct device d={0,OPENGPU_COMPUTE_TEXTURE_COMMAND_MAGIC_VALUE}; bool supported=true;
    const u32 flag=OPENGPU_COMPUTE_RESOURCE_TEXTURE_COMMANDS; u32 words[5],saved[5];
    struct opengpu_compute_texture_command_context t={.version=1,.root=0xffffff80u,.context=7,.pass=11,.generation=13};
    assert(opengpu_read_compute_texture_command_support(127,0,&d,read_reg,&supported) && !supported && d.reads==0);
    assert(!opengpu_read_compute_texture_command_support(flag,0x497,&d,read_reg,&supported) && d.reads==0);
    d.magic=0; assert(!opengpu_read_compute_texture_command_support(flag,0x498,&d,read_reg,&supported));
    d.magic=OPENGPU_COMPUTE_TEXTURE_COMMAND_MAGIC_VALUE;
    assert(opengpu_read_compute_texture_command_support(flag,0x498,&d,read_reg,&supported) && supported);
    assert(opengpu_encode_compute_texture_command_context(words,&t));
    assert(words[0]==0x101 && words[1]==t.root && words[2]==7 && words[3]==11 && words[4]==13);
    memcpy(saved,words,sizeof(words));
    for(unsigned i=0;i<3;++i){struct opengpu_compute_texture_command_context bad=t;
        if(i==0)bad.version=0; else if(i==1)bad.root++; else bad.root=0xffffffc0u;
        assert(!opengpu_encode_compute_texture_command_context(words,&bad) && !memcmp(words,saved,sizeof(words)));
    }
    assert(!opengpu_encode_compute_texture_command_context(0,&t));
    assert(!opengpu_encode_compute_texture_command_context(words,0));
    return 0;
}
