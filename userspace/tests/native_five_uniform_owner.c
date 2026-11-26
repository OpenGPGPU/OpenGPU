#define OPENGPU_PRIVATE_SNAPSHOT_TEST_MAIN five_owner_existing_regressions
#include "../driver/tests/opengpu_private_snapshot_test.c"
#undef OPENGPU_PRIVATE_SNAPSHOT_TEST_MAIN
#include "../driver/opengpu_tessellation_uniform_snapshot.c"
#include "../driver/opengpu_geometry_uniform_snapshot.c"

struct five_source {struct drm_opengpu_compute_uniform_view view;u8 *data;unsigned copies;};
static struct five_source five_sources[5][13];
static u32 five_get(FILE *f)
{u32 v=0;for(unsigned n=0;n<4;n++){int c=fgetc(f);assert(c!=EOF);v|=(u32)c<<(8*n);}return v;}
static void five_put(FILE *f,u32 v)
{for(unsigned n=0;n<4;n++)assert(fputc((v>>(8*n))&255,f)!=EOF);}
static int five_copy(void *opaque,const struct drm_opengpu_compute_uniform_view *v,void *out)
{
    assert(opaque==five_sources);
    for(unsigned s=0;s<5;s++)for(unsigned i=0;i<13;i++){
        struct five_source *p=&five_sources[s][i];
        if(p->view.handle==v->handle&&p->view.offset==v->offset&&p->view.bytes==v->bytes){
            assert(!p->copies++);memcpy(out,p->data,v->bytes);return 0;
        }
    }
    return -EINVAL;
}
static void five_check_root(struct opengpu_private_snapshot *owner,u32 segment,u32 root_offset,
    u32 root_magic,u32 version,u32 stage,u32 stage_bit,unsigned source_stage)
{
    struct snapshot_segment *seg=snapshot_segment_find(owner,segment);assert(seg);
    u32 base;assert(!opengpu_private_snapshot_address(owner,segment,&base));
    const u32 *r=(const u32 *)((u8 *)seg->backing.cpu+root_offset);
    assert(r[0]==root_magic&&r[1]==64&&r[2]==version&&r[3]==7&&r[4]==8&&r[5]==9);
    assert(r[6]==stage&&r[7]==4095&&!r[12]&&r[14]==1&&!r[15]);
    for(unsigned i=0;i<13;i++){
        u32 address=i?r[10]+64*((r[(i-1)<6?8:9]>>(5*((i-1)%6)))&31):r[13];
        assert(address>=base&&(u64)address-base+64<=seg->bytes);
        const u32 *b=(const u32 *)((u8 *)seg->backing.cpu+address-base);
        assert(b[0]==root_magic-0x1000&&b[1]==64&&b[2]==version&&b[3]==7&&b[4]==8&&b[5]==9);
        assert(b[6]==(i?(address-r[10])/64:31)&&b[7]==stage_bit);
        assert(!(b[8]&255)&&b[8]>=base&&b[9]>=b[8]&&!b[10]&&!b[13]&&b[14]==1&&!b[15]);
        struct five_source *source=&five_sources[source_stage][i];
        assert(b[9]-b[8]==source->view.bytes&&b[11]==b[8]&&b[12]==b[9]);
        assert((u64)b[9]-base<=seg->bytes&&source->copies==1);
        assert(!memcmp((u8 *)seg->backing.cpu+b[8]-base,source->data,source->view.bytes));
    }
}
int main(int argc,char **argv)
{
    assert(argc==3);FILE *in=fopen(argv[1],"rb");assert(in);u8 magic[8];
    assert(fread(magic,1,8,in)==8&&!memcmp(magic,"OG5SRC01",8));
    assert(five_get(in)==1&&five_get(in)==5&&five_get(in)==13&&!five_get(in));
    struct drm_opengpu_native_uniform_stage stages[5]={0};
    for(unsigned s=0;s<5;s++){
        stages[s].active_mask=4095;
        for(unsigned i=0;i<13;i++){
            struct five_source *p=&five_sources[s][i];p->view.handle=five_get(in);p->view.bytes=five_get(in);
            u64 low=five_get(in),high=five_get(in);p->view.offset=low|(high<<32);
            assert(p->view.handle&&p->view.bytes==(i?16384u:4096u)&&!(p->view.offset&3));
            assert(p->view.offset<=~(u64)0-p->view.bytes);
            p->data=malloc(p->view.bytes);assert(p->data&&fread(p->data,1,p->view.bytes,in)==p->view.bytes);
            if(i)stages[s].blocks[i-1]=p->view;else stages[s].defaults=p->view;
        }
    }
    assert(fgetc(in)==EOF&&!ferror(in)&&!fclose(in));
    for(unsigned s=0;s<5;s++)for(unsigned i=1;i<13;i++)assert(five_sources[s][i].view.handle==five_sources[0][1].view.handle);
    for(unsigned s=0;s<5;s++)for(unsigned t=0;t<s;t++)assert(five_sources[s][0].view.handle!=five_sources[t][0].view.handle);
    struct opengpu_device gpu={0};struct opengpu_render_context ctx={.gpu=&gpu,.references=1,.id=7};
    gpu.hw.capabilities=GPU_CAP_UNIFIED_COMMANDS;
    assert(!opengpu_mmu_init(&gpu)&&!opengpu_mmu_vm_create(&gpu,&ctx.vm));copy_gpu=&gpu;
    struct opengpu_private_snapshot *owner=opengpu_private_snapshot_create(&gpu,&ctx);assert(!IS_ERR(owner));
    struct drm_opengpu_native_uniform_stage native[2]={stages[0],stages[4]},tess[2]={stages[1],stages[2]};
    struct opengpu_native_uniform_roots nr;struct opengpu_tessellation_uniform_roots tr;
    struct opengpu_geometry_uniform_snapshot gr;
    assert(!opengpu_native_uniform_snapshot_append(owner,native,7,8,9,five_copy,five_sources,&nr));
    assert(!opengpu_tessellation_uniform_snapshot_append(owner,tess,7,8,9,five_copy,five_sources,&tr));
    assert(!opengpu_geometry_uniform_snapshot_append(owner,&stages[3],7,8,9,five_copy,five_sources,&gr));
    assert(nr.present[0]&&nr.present[1]&&tr.present[0]&&tr.present[1]&&gr.present);
    assert(!opengpu_private_snapshot_prepare(owner)&&!opengpu_private_snapshot_publish(owner));
    const u32 segments[3]={nr.segment,tr.segment,gr.segment};u32 bases[3];
    for(unsigned s=0;s<3;s++)assert(!opengpu_private_snapshot_address(owner,segments[s],&bases[s]));
    five_check_root(owner,nr.segment,nr.root_offset[0],0x47555201,1,0,1,0);
    five_check_root(owner,nr.segment,nr.root_offset[1],0x47555201,1,1,2,4);
    five_check_root(owner,tr.segment,tr.root_offset[0],0x47555203,3,4,16,1);
    five_check_root(owner,tr.segment,tr.root_offset[1],0x47555203,3,5,32,2);
    five_check_root(owner,gr.segment,gr.root_offset,0x47555202,2,3,8,3);
    unsigned count=0;
    for(struct snapshot_segment *seg=owner->segments;seg;seg=seg->next){
        count++;assert(!seg->executable&&seg->mapped&&seg->lease.active);
        for(struct snapshot_segment *b=owner->segments;b!=seg;b=b->next)
            assert((u64)seg->va+seg->allocation_bytes<=b->va||(u64)b->va+b->allocation_bytes<=seg->va);
        for(u64 va=seg->va;va<(u64)seg->va+seg->allocation_bytes;va+=4096){
            const struct opengpu_buffer *l1=vm_live_l1(&ctx.vm,va/MMU_SUPERPAGE_SIZE);assert(l1);
            u32 pte=((u32 *)l1->cpu)[(va%MMU_SUPERPAGE_SIZE)/MMU_PAGE_SIZE];
            assert((pte&15)==(MMU_PTE_V|MMU_PTE_R));
        }
    }
    assert(count==3);
    FILE *out=fopen(argv[2],"wb");assert(out&&fwrite("OG5PRV01",1,8,out)==8);
    five_put(out,1);five_put(out,3);five_put(out,5);five_put(out,0);
    const u32 roots[5]={bases[0]+nr.root_offset[0],bases[1]+tr.root_offset[0],bases[1]+tr.root_offset[1],bases[2]+gr.root_offset,bases[0]+nr.root_offset[1]};
    for(unsigned i=0;i<5;i++)five_put(out,roots[i]);
    for(unsigned s=0;s<3;s++){
        struct snapshot_segment *seg=snapshot_segment_find(owner,segments[s]);
        assert(seg->bytes<=0xffffffffu);five_put(out,bases[s]);five_put(out,(u32)seg->bytes);
        assert(fwrite(seg->backing.cpu,1,seg->bytes,out)==seg->bytes);
    }
    assert(!fclose(out)&&!opengpu_private_snapshot_retire(owner));
    opengpu_mmu_vm_destroy(&gpu,&ctx.vm);opengpu_mmu_fini(&gpu);
    assert(ctx.references==1&&!live_allocations&&!fixture_buffers);
    for(unsigned s=0;s<5;s++)for(unsigned i=0;i<13;i++)free(five_sources[s][i].data);
    puts("original65 source views -> actual GUR1/GUR3/GUR2 + one private owner + RO/NX MMU + retirement PASS; CPU only");
    return 0;
}
