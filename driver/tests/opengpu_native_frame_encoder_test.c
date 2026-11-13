/* CPU metadata/ownership checks, not an RTL rendering qualification. */
#include "opengpu_native_frame.h"
#include "opengpu_texture_image_format_abi.h"
#include <assert.h>
#include <errno.h>
#include <stdio.h>
#include <string.h>

#define W(name) OPENGPU_NATIVE_BINNED_FRAME_##name##_WORD
static const struct opengpu_native_binned_host_caps caps = {{
    0x474e4303, 8, 0x474e4a01, 0x08000200, 0xc2100800,
    0x08000800, 0xe4908000, 0x8813d902
}};
static const unsigned sizes[36] = {
    1,2,3,4,2,2,2,4,4,1,1,2,2,4,4,2,2,4,4,8,8,4,4,8,8,16,16,4,2,4,4,8,6,12,8,16
};
static struct opengpu_native_frame_request request(void)
{
    struct opengpu_native_frame_request r;
    memset(&r, 0, sizeof(r));
    r.caps=&caps; r.width=17; r.height=9; r.samples=4; r.color_mask=15;
    r.context=7; r.pass=8; r.generation=9; r.state_count=3; r.scratch_stride=16384;
    const unsigned formats[6] = {3,13,32,26,1,6}, bytes[6] = {4,4,6,16,2,1};
    for (unsigned i=0; i<6; ++i) {
        struct opengpu_native_frame_attachment *a=&r.attachments[i];
        a->format=formats[i]; a->load_op=0; a->channel_mask=i<4?15:0;
        a->clear[0]=i<4?0x10203040u+i:0x3f800000u; a->clear[1]=i<4?0x50607080u+i:0x5a;
        a->clear[2]=i<4?0x90a0b0c0u+i:0; a->clear[3]=i<4?0xd0e0f000u+i:0;
        a->image.row_pitch=17*4*bytes[i]+13; a->image.store=1;
        a->image.gem=(struct opengpu_native_frame_gem){.handle=10+i,.offset=65+4*i,
            .bytes=8*a->image.row_pitch+17*4*bytes[i]};
        a->resolve.row_pitch=17*bytes[i]+7; a->resolve.store=1;
        a->resolve.gem=(struct opengpu_native_frame_gem){.handle=20+i,.offset=4097+3*i,
            .bytes=8*a->resolve.row_pitch+17*bytes[i]};
    }
    r.primitives=(struct opengpu_native_frame_gem){.handle=51,.bytes=65536};
    r.directory=(struct opengpu_native_frame_gem){.handle=50,.bytes=4096};
    r.spill_count=8;
    for(unsigned i=0;i<9;++i) r.pool[i]=(struct opengpu_native_frame_gem){.handle=40+i,.bytes=4096*(i+1)};
    for(unsigned i=0;i<4;++i) r.scratch[i]=(struct opengpu_native_frame_gem){.handle=60+i,.bytes=3*16384};
    return r;
}
static void reject(const struct opengpu_native_frame_request *r, int error)
{
    struct opengpu_native_frame_encoding e, before;
    memset(&e,0xa7,sizeof(e)); before=e;
    assert(opengpu_native_frame_encode(r,&e)==error);
    assert(!memcmp(&e,&before,sizeof(e)));
}
int main(int argc, char **argv)
{
    struct opengpu_native_frame_request r=request(), q;
    struct opengpu_native_frame_encoding e;
    assert(!opengpu_native_frame_encode(&r,&e));
    assert(e.resource_count==27 && e.tile.tile_log2==3 && e.tile.tile_count==6);
    assert(e.tile.reserved_bytes==768 && e.tile.used_bytes==8448);
    assert(e.frame.words[W(FRAME_BINS_BINS_POOL_VERSION)]==2);
    assert(e.frame.words[W(FRAME_BINS_BINS_POOL_SPILLCOUNT)]==8);
    if(argc==2 && !strcmp(argv[1],"--emit")) {
        assert(fwrite(&e.frame,1,sizeof(e.frame),stdout)==sizeof(e.frame)); return 0;
    }
    assert(argc==1);
    /* Exact surface endpoints, source/resolve independence and transient tile
     * attachments, while preserving all six typed attachment slots.
     */
    q=r; q.attachments[0].image.gem.bytes--; reject(&q,-EINVAL);
    q=r; q.attachments[0].image.row_pitch=1; reject(&q,-EINVAL);
    q=r; q.attachments[5].resolve.store=0; reject(&q,-EINVAL);
    q=r; q.attachments[0].image.store=2; reject(&q,-EINVAL);
    q=r; q.attachments[0].load_op=3; reject(&q,-EINVAL);
    q=r; q.attachments[0].channel_mask=16; reject(&q,-EINVAL);
    q=r; q.attachments[4].clear[1]=256; reject(&q,-EINVAL);
    q=r; q.attachments[4].load_op=1; q.attachments[4].clear[0]=0x7fc00001; reject(&q,-EINVAL);
    q=r; q.attachments[4].load_op=2; q.attachments[4].image.store=0;
    memset(&q.attachments[4].image,0,sizeof(q.attachments[4].image));
    assert(!opengpu_native_frame_encode(&q,&e));
    assert(e.resource_count==26 && e.frame.words[W(FRAME_IMAGE_RESOLVES4_ENABLE)]==1);
    q.attachments[4].image.row_pitch=1; reject(&q,-EINVAL);
    /* Resource aliases use original GEM identity, not proposed device VAs.
     * Sharing a read-only source is legal; resolve/write aliases are not.
     */
    q=r; q.attachments[1].image.gem=q.attachments[0].image.gem; reject(&q,-EINVAL);
    q.attachments[0].image.store=q.attachments[1].image.store=0;
    assert(!opengpu_native_frame_encode(&q,&e));
    q.attachments[1].resolve.gem=q.attachments[0].image.gem; reject(&q,-EINVAL);
    q=r; q.attachments[0].resolve.gem.handle=q.attachments[0].image.gem.handle;
    q.attachments[0].resolve.gem.offset=q.attachments[0].image.gem.offset+
        q.attachments[0].image.gem.bytes; /* Payload-disjoint but shared bus line. */
    reject(&q,-EINVAL);
    q=r; q.pool[1]=q.pool[0]; reject(&q,-EINVAL);
    q=r; memset(&q.pool[0],0,sizeof(q.pool[0]));
    assert(!opengpu_native_frame_encode(&q,&e) && e.resource_count==26);
    for(unsigned i=0;i<e.resource_count;++i)
        assert(e.resources[i].role!=OPENGPU_NATIVE_FRAME_RESOURCE_POOL || e.resources[i].index>0);
    memset(q.pool,0,sizeof(q.pool)); q.spill_count=0; reject(&q,-EINVAL);
    q=r; q.pool[0].bytes=UINT64_C(1)<<32; reject(&q,-ENOSPC);
    q=r; q.scratch[1]=q.scratch[0]; reject(&q,-EINVAL);
    q=r; q.attachments[0].image.gem.offset=UINT64_MAX-8; reject(&q,-EINVAL);
    q=r; q.attachments[0].image.gem.bytes=UINT64_C(0x100000001); reject(&q,-EINVAL);
    q=r; q.directory.bytes=320; reject(&q,-ENOSPC);
    q=r; q.scratch[3].bytes=32768; reject(&q,-ENOSPC);
    q=r; q.pool[8].offset=64; reject(&q,-EINVAL);
    q=r; q.primitives.offset=4; reject(&q,-EINVAL);
    q=r; q.state_count=0; reject(&q,-EINVAL);
    q=r; q.state_count=0x800001; reject(&q,-EINVAL);
    q=r; q.scratch_stride=63; reject(&q,-EINVAL);
    q=r; q.spill_count=9; reject(&q,-EINVAL);
    q=r; q.spill_count=7; reject(&q,-EINVAL); /* Unused eighth spill must be canonical. */
    q=r; q.color_mask=7; reject(&q,-EINVAL);
    q=r; q.attachments[4].format=4; reject(&q,-EINVAL); /* Packed+separate stencil. */
    /* Every color format and sample mode, without reducing the runtime API
     * capacities. Required allocations are sized from independent byte sizes.
     */
    for(unsigned f=0;f<36;++f) for(unsigned samples=1;samples<=4;samples*=2) {
        q=r; q.samples=samples; q.color_mask=1;
        memset(q.attachments,0,sizeof(q.attachments));
        q.attachments[0].format=f; q.attachments[0].load_op=1;
        q.attachments[0].channel_mask=15;
        q.attachments[0].image=(struct opengpu_native_frame_image){
            .gem={.handle=1,.offset=1,.bytes=17*9*samples*sizes[f]},
            .row_pitch=17*samples*sizes[f],.store=1};
        assert(!opengpu_native_frame_encode(&q,&e));
        assert(e.tile.used_bytes==(1u<<e.tile.tile_log2)*(1u<<e.tile.tile_log2)*samples*sizes[f]);
        assert(e.frame.words[W(FRAME_IMAGE_PIXELS_COLORS_COLORS0_FORMAT)]==f);
        assert(e.frame.words[W(FRAME_IMAGE_PIXELS_COLORS_SAMPLEMODE)]==(samples==4?2:samples==2?1:0));
    }
    /* Wide 65536 grid uses the existing word; fixed headers stay full-size. */
    q=r; q.width=q.height=2048; q.tile_log2=3; q.color_mask=0;
    memset(q.attachments,0,sizeof(q.attachments)); q.directory.bytes=UINT64_C(65536)*64;
    assert(!opengpu_native_frame_encode(&q,&e));
    assert(e.frame.words[W(FRAME_BINS_BINS_TILECOUNT)]==65536 && e.frame.words[W(FRAME_VERSION)]==2);
    struct opengpu_native_binned_host_caps legacy=caps; legacy.words[0]=0x474e4302;
    q.caps=&legacy; reject(&q,-ENOSPC);
    q.width=17; q.height=9;
    assert(!opengpu_native_frame_encode(&q,&e) && e.frame.words[W(FRAME_VERSION)]==1);
    puts("native frame encoder: PASS");
    return 0;
}
