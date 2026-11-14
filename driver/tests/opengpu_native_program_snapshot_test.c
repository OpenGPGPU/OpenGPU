/* Actual validator -> actual opaque private owner -> actual MMU RX mapping.
 * Kernel allocation/locking primitives come from the existing owner fixture. */
#define OPENGPU_PRIVATE_SNAPSHOT_TEST_MAIN private_owner_regression_main
#include "opengpu_private_snapshot_test.c"
#undef OPENGPU_PRIVATE_SNAPSHOT_TEST_MAIN
#include "../opengpu_native_program_snapshot.c"

static unsigned char original_code[4096];
static unsigned code_copies;
static int code_copy_error;
static bool producer_complete;
static int copy_original_code(void *opaque,const struct drm_opengpu_compute_uniform_view *view,void *destination)
{
    assert(opaque==original_code && producer_complete && view->handle==71);
    assert(view->offset<=sizeof(original_code) && view->bytes<=sizeof(original_code)-view->offset);
    ++code_copies;if(code_copy_error)return code_copy_error;
    memcpy(destination,original_code+view->offset,view->bytes);return 0;
}
static void literal_word(unsigned offset,u32 word)
{
    original_code[offset]=(unsigned char)word;original_code[offset+1]=(unsigned char)(word>>8);
    original_code[offset+2]=(unsigned char)(word>>16);original_code[offset+3]=(unsigned char)(word>>24);
}
static void reject(struct opengpu_private_snapshot *owner,
    struct drm_opengpu_native_program_view view,struct opengpu_native_program_profile profile,int error)
{
    struct opengpu_native_program_snapshot result={0x35a7521d,0x91a5},saved=result;
    void *before=owner->segments;
    assert(opengpu_native_program_snapshot_append(owner,&view,&profile,copy_original_code,original_code,&result)==error);
    assert(!memcmp(&result,&saved,sizeof(result)) && owner->segments==before);
}
static void accepted(struct opengpu_device *gpu,struct opengpu_render_context *context,
    struct drm_opengpu_native_program_view view,struct opengpu_native_program_profile profile)
{
    struct opengpu_private_snapshot *owner=opengpu_private_snapshot_create(gpu,context);
    struct opengpu_native_program_snapshot result={0};u32 address;
    unsigned char expected[2048];memcpy(expected,original_code+view.offset,view.bytes);
    assert(!IS_ERR(owner));
    assert(!opengpu_native_program_snapshot_append(owner,&view,&profile,copy_original_code,original_code,&result));
    assert(result.entry_offset==view.entry_offset);
    memset(original_code+view.offset,0x99,view.bytes); /* original GEM reuse */
    for(unsigned i=0;i<view.bytes;i+=4) {
        u32 observed;assert(!opengpu_private_snapshot_read32(owner,result.segment,i,&observed));
        assert(observed==((u32)expected[i]|(u32)expected[i+1]<<8|(u32)expected[i+2]<<16|(u32)expected[i+3]<<24));
    }
    assert(!opengpu_private_snapshot_prepare(owner));
    assert(!opengpu_private_snapshot_address(owner,result.segment,&address));
    const struct opengpu_buffer *l1=vm_live_l1(&context->vm,address/MMU_SUPERPAGE_SIZE);assert(l1);
    u32 leaf=((u32 *)l1->cpu)[(address%MMU_SUPERPAGE_SIZE)/MMU_PAGE_SIZE];
    assert((leaf&0xfu)==(MMU_PTE_V|MMU_PTE_R|MMU_PTE_X));
    assert(!opengpu_private_snapshot_publish(owner));
    assert(!opengpu_private_snapshot_retire(owner));
}
int main(int argc,char **argv)
{
    struct opengpu_device gpu={0};
    struct opengpu_render_context context={.gpu=&gpu,.references=1,.id=7};
    struct drm_opengpu_native_program_view view={.offset=64,.handle=71,.bytes=8};
    struct opengpu_native_program_profile profile={.staging_bytes=15u*128*4,.capacity=128,
        .stage=0,.version=1,.attributes=1,.varyings=1,.owned_uniforms=true,.scalar_fpu=true};
    gpu.hw.capabilities=GPU_CAP_UNIFIED_COMMANDS;
    assert(!opengpu_mmu_init(&gpu));assert(!opengpu_mmu_vm_create(&gpu,&context.vm));copy_gpu=&gpu;
    producer_complete=true;
    literal_word(64,0x0330000fu);literal_word(68,OPENGPU_SHADER_CEASE);
    struct opengpu_private_snapshot *owner=opengpu_private_snapshot_create(&gpu,&context);assert(!IS_ERR(owner));
    struct drm_opengpu_native_program_view bad=view;
    bad.handle=0;reject(owner,bad,profile,-EINVAL);bad=view;bad.pad=1;reject(owner,bad,profile,-EINVAL);
    bad=view;bad.bytes=2052;reject(owner,bad,profile,-EINVAL);bad=view;bad.bytes=0;reject(owner,bad,profile,-EINVAL);
    bad=view;bad.offset=65;reject(owner,bad,profile,-EINVAL);bad=view;bad.offset=~(u64)3;reject(owner,bad,profile,-EINVAL);
    bad=view;bad.entry_offset=2;reject(owner,bad,profile,-EINVAL);bad=view;bad.entry_offset=8;reject(owner,bad,profile,-EINVAL);
    struct opengpu_native_program_profile p=profile;
    p.capacity=256;reject(owner,view,p,-EINVAL);p=profile;p.staging_bytes=UINT64_C(0x100000000);reject(owner,view,p,-EINVAL);
    p=profile;p.version=3;reject(owner,view,p,-EINVAL);
    p=profile;p.stage=1;p.version=5;p.attributes=0;p.colors=1;reject(owner,view,p,-EINVAL);
    assert(!code_copies); /* cheap metadata rejects never read an original GEM */
    for(unsigned version=6;version<=7;++version) {
        p=profile;p.stage=1;p.version=version;p.attributes=0;p.colors=1;
        p.geometry_inputs=version==7;
        reject(owner,view,p,-EINVAL);
        p.sample_inputs=true;p.geometry_inputs=false;
        if(version==7)reject(owner,view,p,-EINVAL);
    }
    p=profile;p.staging_bytes--;reject(owner,view,p,-EINVAL);
    code_copy_error=-EIO;reject(owner,view,profile,-EIO);code_copy_error=0;
    private_alloc_fail_after=0;reject(owner,view,profile,-ENOMEM);private_alloc_fail_after=-1;
    /* Fail both actual append allocations independently, after allocating the
     * analysis bytes. Neither a node nor copied staging may escape on error. */
    for(int allocation=1;allocation<=2;allocation++) {
        private_alloc_fail_after=allocation;reject(owner,view,profile,-ENOMEM);
        private_alloc_fail_after=-1;
    }
    literal_word(64,0xffffffffu);reject(owner,view,profile,-EINVAL);
    /* Branch from the executable suffix back into the unverified prefix. */
    literal_word(64,0xffffffffu);literal_word(68,0xfe000ee3u);literal_word(72,OPENGPU_SHADER_CEASE);
    bad=view;bad.bytes=12;bad.entry_offset=4;reject(owner,bad,profile,-EINVAL);
    assert(!opengpu_private_snapshot_retire(owner));
    literal_word(64,0xffffffffu);literal_word(68,0x0330000fu);literal_word(72,OPENGPU_SHADER_CEASE);
    bad=view;bad.bytes=12;bad.entry_offset=4;accepted(&gpu,&context,bad,profile);
    for(unsigned i=0;i<512;i++)literal_word(64+i*4,i==511?OPENGPU_SHADER_CEASE:0x00000013u);
    bad=view;bad.bytes=2048;accepted(&gpu,&context,bad,profile);
    for(unsigned version=6;version<=7;++version) {
        p=profile;p.stage=1;p.version=version;p.attributes=0;p.varyings=16;p.colors=4;
        p.geometry_inputs=version==7;p.sample_inputs=true;
        const unsigned first=version==7?77u:75u,last=first+18;
        p.staging_bytes=(u64)(last+4)*128*4;
        for(unsigned slice=0;slice<=last;++slice) {
            unsigned n=64;
            literal_word(n,0x01007557u);n+=4;
            literal_word(n,7u<<25|5u<<7|0x0bu);n+=4;
            literal_word(n,23u<<26|1u<<25|5u<<15|4u<<12|2u<<7|0x57u);n+=4;
            literal_word(n,1u<<15|6u<<7|0x13u);n+=4;
            unsigned bytes=slice*512;
            while(bytes){unsigned step=bytes>2047?2047:bytes;literal_word(n,step<<20|6u<<15|6u<<7|0x13u);n+=4;bytes-=step;}
            literal_word(n,1u<<25|6u<<15|6u<<12|2u<<7|0x27u);n+=4;
            literal_word(n,OPENGPU_SHADER_CEASE);n+=4;
            bad=view;bad.bytes=n-64;
            if(slice>=first&&slice<last)accepted(&gpu,&context,bad,p);
            else {owner=opengpu_private_snapshot_create(&gpu,&context);assert(!IS_ERR(owner));
                reject(owner,bad,p,-EINVAL);assert(!opengpu_private_snapshot_retire(owner));}
        }
        owner=opengpu_private_snapshot_create(&gpu,&context);assert(!IS_ERR(owner));
        p.staging_bytes=(u64)last*128*4-1;
        literal_word(64,0x0330000fu);literal_word(68,OPENGPU_SHADER_CEASE);bad=view;bad.bytes=8;
        reject(owner,bad,p,-EINVAL);assert(!opengpu_private_snapshot_retire(owner));
    }
    /* Actual full-prefix ranges: ordinary input and the first tail slice must
     * remain read-only. fn7 result is broadcast before the attempted store. */
    for(unsigned slice=0;slice<=15;slice++) {
        unsigned n=64;
        literal_word(n,0x01007557u);n+=4;
        literal_word(n,7u<<25|5u<<7|0x0bu);n+=4;
        literal_word(n,23u<<26|1u<<25|5u<<15|4u<<12|2u<<7|0x57u);n+=4;
        /* ADDI x6,x1,offset; split offsets into bounded steps. */
        literal_word(n,1u<<15|6u<<7|0x13u);n+=4;
        unsigned bytes=slice*512;
        while(bytes){unsigned step=bytes>2047?2047:bytes;literal_word(n,step<<20|6u<<15|6u<<7|0x13u);n+=4;bytes-=step;}
        literal_word(n,1u<<25|6u<<15|6u<<12|2u<<7|0x27u);n+=4;
        literal_word(n,OPENGPU_SHADER_CEASE);n+=4;
        view.bytes=n-64;
        if(slice>=7&&slice<15)accepted(&gpu,&context,view,profile);
        else {owner=opengpu_private_snapshot_create(&gpu,&context);assert(!IS_ERR(owner));
            reject(owner,view,profile,-EINVAL);assert(!opengpu_private_snapshot_retire(owner));}
    }
    if(argc==2)for(unsigned fixture=0;fixture<4;fixture++) {
        const char *names[]={"native-real-vs.words","native-real-fs.words","native-full-vs.words","native-full-fs.words"};
        char path[4096];assert(snprintf(path,sizeof(path),"%s/%s",argv[1],names[fixture])>0);
        FILE *f=fopen(path,"rb");assert(f);size_t bytes=fread(original_code+64,1,2049,f);
        assert(!ferror(f)&&!fclose(f)&&bytes&&bytes<=2048&&!(bytes&3));
        view.bytes=(u32)bytes;view.entry_offset=0;
        profile.stage=fixture&1;profile.attributes=profile.stage?0:(fixture<2?1:16);
        profile.varyings=fixture<2?1:16;profile.colors=profile.stage?(fixture<2?1:4):0;
        profile.staging_bytes=(fixture<2?(profile.stage?16u:15u):(profile.stage?88u:135u))*128*4;
        if(fixture<2)assert(bytes==(profile.stage?301u:308u)*4);
        if(fixture<2) {
            owner=opengpu_private_snapshot_create(&gpu,&context);assert(!IS_ERR(owner));
            p=profile;p.owned_uniforms=false;reject(owner,view,p,-EINVAL);
            p=profile;p.staging_bytes--;reject(owner,view,p,-EINVAL);
            assert(!opengpu_private_snapshot_retire(owner));
        }
        accepted(&gpu,&context,view,profile);
    }
    opengpu_mmu_vm_destroy(&gpu,&context.vm);opengpu_mmu_fini(&gpu);
    assert(context.references==1&&!live_allocations&&!fixture_buffers);
    puts("native program: actual validator/opaque RX owner/MMU, exact entry suffix, readonly inputs/tails, failure atomicity and reuse PASS (CPU only)");
    return 0;
}
