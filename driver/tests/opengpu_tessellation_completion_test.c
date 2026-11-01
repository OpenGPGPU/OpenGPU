#include "../opengpu_tessellation_completion.h"
#include "../opengpu_native_binned_host_abi.h"
#include <assert.h>
#include <string.h>

_Static_assert(OPENGPU_NATIVE_TESSELLATION_COMPLETION_MAGIC != OPENGPU_NATIVE_BINNED_COMPLETION_MAGIC, "distinct completion formats");
_Static_assert(OPENGPU_NATIVE_TESSELLATION_COMPLETION_BASE == OPENGPU_NATIVE_BINNED_COMPLETION_BASE, "shared completion window");
_Static_assert(sizeof(struct opengpu_native_tessellation_completion_info) == 48, "twelve MMIO words");
struct fake_completion_window { const uint32_t *words; unsigned reads; int popped; };
static uint32_t read_window(void *opaque, uint32_t address)
{
    struct fake_completion_window *window=opaque;
    assert(!window->popped && window->reads<12);
    assert(address==OPENGPU_NATIVE_TESSELLATION_COMPLETION_BASE+4u*window->reads);
    return window->words[window->reads++];
}
int main(void)
{
    uint32_t w[12]={OPENGPU_NATIVE_TESSELLATION_COMPLETION_MAGIC, 3u|(2u<<3)|(2u<<5)|(1u<<7)|(1u<<8),
        0x12345640u,7u,19u, 6u|(3u<<3)|(2u<<6)|(1u<<9)|(4u<<12)|(5u<<15),11u,12u,13u,0x44444440u,23u,0u};
    struct opengpu_tessellation_completion got, sentinel;
    memset(&got,0xa5,sizeof(got));memcpy(&sentinel,&got,sizeof(got));
    assert(opengpu_tessellation_completion_decode(w,12,&got));
    assert(got.status==3 && got.reader_stage==2 && got.image_stage==2 && got.input_fault_valid && got.tile_valid);
    assert(got.reader_address==0x12345640u && got.reader_draw==7 && got.tile==19);
    assert(got.frame_status==6 && got.image_status==3 && got.source_status==2 && got.bins_status==1);
    assert(got.producer_status==4 && got.bin_producer_status==5 && got.context==11 && got.pass==12 && got.generation==13);
    assert(got.input_address==0x44444440u && got.input_task==23);
    {
        struct fake_completion_window window={w,0,0};
        struct opengpu_tessellation_completion captured;
        memcpy(&captured,&sentinel,sizeof captured);
        assert(!opengpu_tessellation_completion_capture(0,0x1b0,&window,read_window,&captured));
        assert(!opengpu_tessellation_completion_capture(8,0x1b0,&window,read_window,&captured));
        assert(!opengpu_tessellation_completion_capture(3,0x1af,&window,read_window,&captured));
        assert(!opengpu_tessellation_completion_capture(3,0x1b0,&window,NULL,&captured));
        assert(!opengpu_tessellation_completion_capture(3,0x1b0,&window,read_window,NULL));
        assert(window.reads==0 && !memcmp(&captured,&sentinel,sizeof captured));
        assert(opengpu_tessellation_completion_capture(3,0x1b0,&window,read_window,&captured));
        assert(window.reads==12 && captured.context==11 && captured.input_address==0x44444440u);
        window.popped=1; /* The caller may pop only after every read. */
        window.popped=0;window.reads=0;memcpy(&captured,&sentinel,sizeof captured);
        assert(!opengpu_tessellation_completion_capture(4,0x1b0,&window,read_window,&captured));
        assert(window.reads==12 && !memcmp(&captured,&sentinel,sizeof captured));
        window.reads=0;w[0]=OPENGPU_NATIVE_BINNED_COMPLETION_MAGIC;
        assert(!opengpu_tessellation_completion_capture(3,0x1b0,&window,read_window,&captured));
        assert(window.reads==1 && !memcmp(&captured,&sentinel,sizeof captured));
        w[0]=OPENGPU_NATIVE_TESSELLATION_COMPLETION_MAGIC;
    }
    memcpy(&got,&sentinel,sizeof(got));assert(!opengpu_tessellation_completion_decode(w,11,&got));assert(!memcmp(&got,&sentinel,sizeof got));
    assert(!opengpu_tessellation_completion_decode(NULL,12,&got));assert(!opengpu_tessellation_completion_decode(w,12,NULL));
    w[0]=OPENGPU_NATIVE_BINNED_COMPLETION_MAGIC;assert(!opengpu_tessellation_completion_decode(w,12,&got));w[0]=OPENGPU_NATIVE_TESSELLATION_COMPLETION_MAGIC;
    w[1]|=0x200u;assert(!opengpu_tessellation_completion_decode(w,12,&got));w[1]&=~0x200u;
    w[5]|=0x40000u;assert(!opengpu_tessellation_completion_decode(w,12,&got));w[5]&=~0x40000u;
    w[11]=1;assert(!opengpu_tessellation_completion_decode(w,12,&got));w[11]=0;
    w[1]&=~0x100u;assert(!opengpu_tessellation_completion_decode(w,12,&got));w[4]=0;
    w[1]&=~0x80u;assert(!opengpu_tessellation_completion_decode(w,12,&got));w[9]=0;w[10]=0;
    assert(opengpu_tessellation_completion_decode(w,12,&got));assert(!got.input_fault_valid && !got.tile_valid);
    return 0;
}
