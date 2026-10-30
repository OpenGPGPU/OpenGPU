#include <assert.h>
#include <string.h>
#include "../opengpu_compute_uniform_command.h"
struct device { unsigned reads; u32 magic; };
static u32 read_reg(void *ctx, u32 address) {
    struct device *d=ctx;assert(address==OPENGPU_COMPUTE_UNIFORM_COMMAND_REG_MAGIC);++d->reads;return d->magic;
}
int main(void) {
    struct device d={0,OPENGPU_COMPUTE_UNIFORM_COMMAND_MAGIC_VALUE};bool supported=true;
    const u32 flag=OPENGPU_COMPUTE_RESOURCE_UNIFORM_COMMANDS;u32 words[8],saved[8];
    struct opengpu_compute_uniform_command_context u={.version=1,.root=0xffffffc0u,
        .context=7,.pass=11,.generation=13,.private_base=0xffffff00u,.private_limit=1ULL<<32};
    assert(opengpu_read_compute_uniform_command_support(63,0,&d,read_reg,&supported));
    assert(!supported && d.reads==0);
    assert(!opengpu_read_compute_uniform_command_support(flag,0x463,&d,read_reg,&supported));
    assert(d.reads==0);d.magic=0;
    assert(!opengpu_read_compute_uniform_command_support(flag,0x464,&d,read_reg,&supported));
    d.magic=OPENGPU_COMPUTE_UNIFORM_COMMAND_MAGIC_VALUE;
    assert(opengpu_read_compute_uniform_command_support(flag,0x464,&d,read_reg,&supported) && supported);
    assert(opengpu_encode_compute_uniform_command_context(words,&u));
    assert(words[0]==0x101 && words[1]==u.root && words[2]==7 && words[3]==11 && words[4]==13);
    assert(words[5]==u.private_base && words[6]==0 && words[7]==1);
    memcpy(saved,words,sizeof(words));
    for(unsigned i=0;i<6;++i){struct opengpu_compute_uniform_command_context b=u;
        switch(i){case 0:b.version=0;break;case 1:b.root+=1;break;case 2:b.private_limit+=1;break;
        case 3:b.private_limit=b.private_base;break;case 4:b.private_limit=0;break;default:b.version=2;break;}
        assert(!opengpu_encode_compute_uniform_command_context(words,&b));assert(!memcmp(words,saved,sizeof(words)));
    }
    u.private_base=0;u.private_limit=0;assert(opengpu_encode_compute_uniform_command_context(words,&u));
    assert(!opengpu_encode_compute_uniform_command_context(0,&u));
    assert(!opengpu_encode_compute_uniform_command_context(words,0));return 0;
}
