/* Private bridge CPU admission checks; synthetic observations, no Mesa/GPU. */
#include "graphics_sample_kernel.c"
int main(int argc,char **argv)
{
    assert(argc==3);struct opengpu_native_sample_runtime runtime,before;
    memset(&runtime,0xa5,sizeof(runtime));before=runtime;
    if(strcmp(argv[2],"valid")){
        assert(graphics_sample_profile_read(argv[1],&runtime)<0 && !memcmp(&runtime,&before,sizeof(runtime)));return 0;
    }
    assert(!graphics_sample_profile_read(argv[1],&runtime));
    struct native_kernel_profile_word words[112],aux[5];
    for(unsigned i=0;i<112;i++)words[i]=(struct native_kernel_profile_word){runtime.observed[i].address,runtime.observed[i].value};
    for(unsigned i=0;i<5;i++)aux[i]=(struct native_kernel_profile_word){runtime.observed[112+i].address,runtime.observed[112+i].value};
    assert(!native_kernel_profile_create(words,112,aux,5));
    assert(!native_kernel_profile_create(words,96,aux,5));
    struct native_kernel_session *s=graphics_sample_session_create(17,&runtime);assert(s);
    assert(s->gpu.hw.native_layered_transport && s->gpu.hw.native_sample_transport &&
        s->gpu.hw.native_sources.capacity==128 && s->gpu.hw.native_sources.engines==4 &&
        s->gpu.hw.native_sources.frame.words[0]==0x474e4305);
    u64 value=0x1234;
    /* The old query is not an escape hatch for the new profile. Its constructor
     * remains exact96 and no new public GET_PARAM cache has been installed. */
    assert(!s->profile->missing && native_kernel_profile_query(NULL,OPENGPU_PARAM_NATIVE_LAYOUT,&value)==-EINVAL && value==0x1234);
    assert(!native_kernel_session_destroy(s));assert(!fixture_buffers && !live_allocations);
    puts("strict112 profile bootstrap/old96 isolation CPU checks PASS");return 0;
}
