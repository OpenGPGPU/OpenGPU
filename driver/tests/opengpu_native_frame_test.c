/* CPU planner checks. No RTL, backing allocation or job submission. */
#include "opengpu_native_frame.h"
#include "opengpu_texture_image_format_abi.h"
#include <assert.h>
#include <errno.h>
#include <stdio.h>
#include <string.h>

static struct opengpu_native_binned_host_caps caps(void)
{
    const struct opengpu_native_binned_host_caps c = {{
        0x474e4303, 8, 0x474e4a01, 0x08000200, 0xc2100800,
        0x08000800, 0xe4908000, 0x8813d902
    }};
    return c;
}
static void reject(struct opengpu_native_tile_request *r, int error)
{
    struct opengpu_native_tile_layout out, before;
    memset(&out, 0xa7, sizeof(out)); before = out;
    assert(opengpu_native_tile_plan(r, &out) == error);
    assert(!memcmp(&before, &out, sizeof(out)));
}
static void invalid_caps(const struct opengpu_native_binned_host_caps *c, int error)
{
    assert(opengpu_native_frame_caps_validate(c)==error);
    struct opengpu_native_tile_request r={.caps=c,.width=1,.height=1,.samples=1};
    reject(&r,error);
    struct opengpu_native_frame_request request={.caps=c};
    struct opengpu_native_frame_encoding e,before;
    memset(&e,0xa7,sizeof(e));before=e;
    assert(opengpu_native_frame_encode(&request,&e)==error);
    assert(!memcmp(&e,&before,sizeof(e)));
}
static void discovery(void)
{
    struct opengpu_native_binned_host_caps c=caps();
    assert(opengpu_native_frame_caps_validate(NULL)==-EINVAL);
    assert(!opengpu_native_frame_caps_validate(&c));
#define SET(field,value) do { c.words[OPENGPU_NATIVE_BINNED_HOST_##field##_WORD] = \
    (c.words[OPENGPU_NATIVE_BINNED_HOST_##field##_WORD]&~OPENGPU_NATIVE_BINNED_HOST_##field##_MASK) | \
    ((uint32_t)(value)<<OPENGPU_NATIVE_BINNED_HOST_##field##_SHIFT); } while(0)
#define BAD(field,value) do { c=caps(); SET(field,value); invalid_caps(&c,-EINVAL); } while(0)
    BAD(WORD_COUNT,7); BAD(WORD_COUNT,9); BAD(JOB_MAGIC,0); BAD(JOB_BYTES,256);
    BAD(FRAME_BYTES,1024); BAD(DRAW_BYTES,1024);
    BAD(ATTRIBUTES,0); BAD(ATTRIBUTES,17); BAD(BINDINGS,0); BAD(BINDINGS,17);
    BAD(VARYINGS,0); BAD(VARYINGS,17); BAD(COLORS,0); BAD(COLORS,5);
    BAD(ENGINES,0); BAD(ENGINES,5); BAD(FRAME_WIDTH,0); BAD(FRAME_HEIGHT,0);
    BAD(CUS,0); BAD(NATIVE_ID_BITS,0); BAD(STORAGE_BYTES,512); BAD(STORAGE_BYTES,32769);
    BAD(SAMPLES,0); BAD(SAMPLES,3); BAD(SAMPLES,7);
    BAD(LOCAL_PAYLOAD,0); BAD(PAYLOAD_OWNERS,0); BAD(PAYLOAD_OWNERS,4);
    BAD(PAYLOAD_CAPACITY_LOG2,5); BAD(PAYLOAD_CAPACITY_LOG2,31); BAD(PAYLOAD_SLOT_LINES,0);
    c=caps(); SET(PAYLOAD_CAPACITY_LOG2,6); invalid_caps(&c,-EINVAL);
    c=caps(); SET(TILE8,0); SET(TILE16,0); invalid_caps(&c,-EINVAL);
    c=caps(); SET(MAGIC,OPENGPU_NATIVE_BINNED_HOST_PHYSICAL_MAGIC); invalid_caps(&c,-EOPNOTSUPP);
    c=caps(); SET(MAGIC,OPENGPU_NATIVE_BINNED_HOST_WIDE_PHYSICAL_MAGIC); invalid_caps(&c,-EOPNOTSUPP);
    c=caps(); SET(MAGIC,0); invalid_caps(&c,-EOPNOTSUPP);
    /* Legacy and smaller valid runtime configurations are not rewritten into
     * a particular product profile. Absent payload must clear every subfield.
     */
    c=caps(); SET(MAGIC,OPENGPU_NATIVE_BINNED_HOST_MAGIC);
    assert(!opengpu_native_frame_caps_validate(&c));
    c=caps(); SET(LOCAL_PAYLOAD,0); SET(PAYLOAD_OWNERS,0);
    SET(PAYLOAD_CAPACITY_LOG2,0); SET(PAYLOAD_SLOT_LINES,0);
    assert(!opengpu_native_frame_caps_validate(&c));
    struct opengpu_native_binned_host_caps no_payload=c;
    SET(PAYLOAD_OWNERS,1); invalid_caps(&c,-EINVAL);
    c=no_payload;SET(PAYLOAD_CAPACITY_LOG2,6);invalid_caps(&c,-EINVAL);
    c=no_payload;SET(PAYLOAD_SLOT_LINES,1);invalid_caps(&c,-EINVAL);
    c=no_payload;SET(ATTRIBUTES,2);SET(BINDINGS,2);SET(VARYINGS,2);SET(COLORS,1);
    SET(ENGINES,1);SET(CUS,4);SET(NATIVE_ID_BITS,5);SET(FRAME_WIDTH,640);SET(FRAME_HEIGHT,480);
    SET(STORAGE_BYTES,1024);SET(SAMPLES,1);SET(TILE16,0);SET(COMPLETION_INFO,0);
    assert(!opengpu_native_frame_caps_validate(&c));
    SET(LOCAL_PAYLOAD,1);SET(PAYLOAD_OWNERS,2);SET(PAYLOAD_CAPACITY_LOG2,6);SET(PAYLOAD_SLOT_LINES,1);
    assert(!opengpu_native_frame_caps_validate(&c));
    SET(PAYLOAD_CAPACITY_LOG2,30);SET(PAYLOAD_OWNERS,7);SET(PAYLOAD_SLOT_LINES,2047);SET(NATIVE_ID_BITS,15);
    assert(!opengpu_native_frame_caps_validate(&c));
#undef BAD
#undef SET
}
int main(void)
{
    discovery();
    struct opengpu_native_binned_host_caps c = caps();
    struct opengpu_native_tile_request r = { .caps=&c, .width=640, .height=480,
        .samples=4, .color_mask=1, .color_format={OPENGPU_TEXTURE_FORMAT_RGBA8}, .depth_format=4 };
    struct opengpu_native_tile_layout p;
    assert(!opengpu_native_tile_plan(&r, &p));
    assert(p.tile_log2==4 && p.sample_mode==2 && p.tile_count==1200);
    assert(p.depth_offset==0 && p.stencil_offset==0 && p.reserved_bytes==4096);
    assert(p.color_offset[0]==4096 && p.used_bytes==8192 && p.storage_bytes==32768);
    r.tile_log2=3;
    assert(!opengpu_native_tile_plan(&r, &p));
    assert(p.tile_count==4800 && p.used_bytes==2048 && p.color_offset[0]==1024);

    r.tile_log2=0; r.color_mask=15;
    for(unsigned i=0;i<4;++i) r.color_format[i]=OPENGPU_TEXTURE_FORMAT_RGBA32UI;
    assert(!opengpu_native_tile_plan(&r, &p));
    assert(p.tile_log2==3 && p.used_bytes==17408 && p.reserved_bytes==1024);
    for(unsigned i=0;i<4;++i) assert(p.color_offset[i]==1024+4096*i);
    r.tile_log2=4; reject(&r,-ENOSPC);
    r.tile_log2=0; c.words[6]&=~0x40000000u; reject(&r,-ENOSPC);
    c=caps(); r.color_mask=1; r.color_format[0]=OPENGPU_TEXTURE_FORMAT_RGBA32UI;
    r.depth_format=0; r.samples=1;
    assert(!opengpu_native_tile_plan(&r,&p) && p.tile_log2==4 && p.used_bytes==4096);
    r.color_format[0]=OPENGPU_TEXTURE_FORMAT_RGB8; r.depth_format=1; r.stencil_format=6;
    assert(!opengpu_native_tile_plan(&r,&p));
    assert(p.depth_offset==0 && p.stencil_offset==512 && p.reserved_bytes==768 && p.used_bytes==1536);
    assert(p.color_offset[0]==768 && !(p.color_offset[0]&63));

    r.width=r.height=2048; r.depth_format=0; r.stencil_format=0; r.tile_log2=3;
    assert(!opengpu_native_tile_plan(&r,&p) && p.tile_count==65536);
    c.words[0]=0x474e4302; reject(&r,-ENOSPC);
    r.tile_log2=0;
    assert(!opengpu_native_tile_plan(&r,&p) && p.tile_count==16384 && p.tile_log2==4);

    c=caps(); r.width=2049; reject(&r,-EINVAL); r.width=2048;
    r.samples=3; reject(&r,-EINVAL); r.samples=4;
    r.color_format[0]=36; reject(&r,-EOPNOTSUPP); r.color_format[0]=3;
    r.depth_format=4; r.stencil_format=6; reject(&r,-EINVAL); r.stencil_format=0;
    r.tile_log2=5; reject(&r,-EINVAL); r.tile_log2=0;
    c.words[6]=(c.words[6]&~0x7000000u)|0x1000000u; reject(&r,-EOPNOTSUPP);
    c=caps(); c.words[6]&=0x3fffffffu; reject(&r,-EINVAL);
    c=caps(); c.words[6]=(c.words[6]&~0x1ffffu)|12345; reject(&r,-EINVAL);
    c=caps(); c.words[3]^=1; reject(&r,-EINVAL);
    c=caps(); c.words[0]=0x474e4304; reject(&r,-EOPNOTSUPP);
    puts("native frame tile layout: PASS");
    return 0;
}
