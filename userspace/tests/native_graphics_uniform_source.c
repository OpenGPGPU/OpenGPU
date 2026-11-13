/* SPDX-License-Identifier: MIT */
#include "native_graphics_uniform_source.h"
#include <errno.h>
#include <inttypes.h>
#include <string.h>

static uint32_t le_word(const uint8_t *bytes)
{
    return (uint32_t)bytes[0] | (uint32_t)bytes[1]<<8 |
        (uint32_t)bytes[2]<<16 | (uint32_t)bytes[3]<<24;
}
int native_graphics_source_observe(struct native_graphics_source_record *record,
    uint32_t handle, uint64_t offset, uint64_t bytes, const void *stable_source)
{
    if(!record || record->failed)return -EINVAL;
    for(unsigned stage=0;stage<2;stage++)for(unsigned source=0;source<13;source++) {
        struct native_graphics_source_window *window=&record->windows[stage][source];
        if(window->handle!=handle || window->offset!=offset)continue;
        if(!stable_source || bytes!=window->bytes || bytes<4 ||
            window->offset>window->allocation_bytes || bytes>window->allocation_bytes-window->offset) {
            record->failed=1;return -EINVAL;
        }
        const uint8_t *data=stable_source;
        uint32_t first=le_word(data),last=le_word(data+bytes-4);
        if(window->observed && (window->first!=first || window->last!=last)) {
            record->failed=1;return -EINVAL;
        }
        window->first=first;window->last=last;window->observed=1;
    }
    return 0;
}
int native_graphics_source_allocation(struct native_graphics_source_record *record,
    uint32_t handle, uint64_t physical, uint64_t allocation_bytes)
{
    if(!record || record->failed)return -EINVAL;
    if(!handle || handle!=record->color_handle || physical>=UINT64_C(0x100000000) ||
        !allocation_bytes || allocation_bytes>UINT64_C(0x100000000)-physical ||
        record->color_offset>allocation_bytes || record->color_bytes>allocation_bytes-record->color_offset ||
        (record->color_observed && (record->color_physical!=physical || record->color_allocation!=allocation_bytes))) {
        record->failed=1;return -EINVAL;
    }
    record->color_physical=physical;record->color_allocation=allocation_bytes;record->color_observed=1;
    return 0;
}
static int window_write(FILE *stream,const struct native_graphics_source_window *window,int block)
{
    if(block && fprintf(stream,"\"binding\":%u,",window->binding)<0)return -EIO;
    return fprintf(stream,"\"handle\":%u,\"allocation_bytes\":%" PRIu64
        ",\"offset\":%" PRIu64 ",\"bytes\":%u,\"first\":%u,\"last\":%u",
        window->handle,window->allocation_bytes,window->offset,window->bytes,window->first,window->last)<0?-EIO:0;
}
int native_graphics_source_write(FILE *stream,const struct native_graphics_source_record *record)
{
    if(!stream || !record || record->failed || !record->color_observed || !record->width ||
        record->width>640 || !record->height || record->height>480 ||
        record->row_pitch<(uint64_t)record->width*16 ||
        (uint64_t)(record->height-1)*record->row_pitch+(uint64_t)record->width*16>record->color_bytes)return -EINVAL;
    for(unsigned stage=0;stage<2;stage++)for(unsigned source=0;source<13;source++) {
        const struct native_graphics_source_window *window=&record->windows[stage][source];
        if(!window->handle || !window->observed || window->bytes!=(source?16384u:4096u) ||
            (source && window->binding!=stage*24+source-1))return -EINVAL;
    }
    if(fprintf(stream,"{\"version\":1,\"width\":%u,\"height\":%u,\"color\":{\"handle\":%u,"
        "\"physical_base\":%" PRIu64 ",\"allocation_bytes\":%" PRIu64 ",\"offset\":%" PRIu64
        ",\"bytes\":%" PRIu64 ",\"row_pitch\":%u},\"stages\":[",
        record->width,record->height,record->color_handle,record->color_physical,record->color_allocation,
        record->color_offset,record->color_bytes,record->row_pitch)<0)return -EIO;
    for(unsigned stage=0;stage<2;stage++) {
        if(fprintf(stream,"%s{\"defaults\":{",stage?",":"")<0 ||
            window_write(stream,&record->windows[stage][0],0) || fprintf(stream,"},\"blocks\":[")<0)return -EIO;
        for(unsigned source=1;source<13;source++)
            if(fprintf(stream,"%s{",source>1?",":"")<0 ||
                window_write(stream,&record->windows[stage][source],1) || fputc('}',stream)==EOF)return -EIO;
        if(fprintf(stream,"]}")<0)return -EIO;
    }
    return fprintf(stream,"]}\n")<0?-EIO:0;
}
