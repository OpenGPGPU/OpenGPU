/* Real indexed-draw helpers, exercised with reference-counted fake buffers. */
#include <assert.h>
#include <limits.h>
#include <stdio.h>
#include "../gallium/opengpu_context.c"

struct pipe_opengpu_resource { uint8_t *map; uint64_t size; };
static unsigned maps, destroyed, calls;
struct opengpu_resource *opengpu_resource(struct pipe_resource *resource)
{ return (struct opengpu_resource *)resource; }
uint64_t pipe_opengpu_resource_size(const struct pipe_opengpu_resource *resource)
{ return resource ? resource->size : 0; }
void *pipe_opengpu_resource_map(struct pipe_opengpu_resource *resource)
{ maps++; return resource ? resource->map : NULL; }
static void resource_destroyed(struct pipe_screen *screen, struct pipe_resource *resource)
{ (void)screen; (void)resource; destroyed++; }
static void draw_body(struct pipe_context *pipe, const struct pipe_draw_info *info,
                      unsigned id, const struct pipe_draw_indirect_info *indirect,
                      const struct pipe_draw_start_count_bias *draws, unsigned count)
{
    (void)pipe; (void)indirect; (void)draws; (void)count;
    calls++;
    if (!info->has_user_indices && info->index_size && info->index.resource)
        assert(info->index.resource->reference.count>0 && !destroyed);
    if (id == 7) return; /* Simulate an early rejected draw. */
}

int main(void)
{
    struct pipe_screen screen={0}, foreign={0};
    struct pipe_context pipe={0};
    struct opengpu_resource resource={0};
    struct pipe_opengpu_resource gpu={0};
    struct pipe_draw_info info={0};
    struct opengpu_index_source source;
    uint8_t bytes[20]={0};
    uint16_t short_index=513;
    uint32_t long_index=UINT32_MAX;
    unsigned vertex, before;

    memcpy(bytes+1,&short_index,sizeof(short_index));
    memcpy(bytes+5,&long_index,sizeof(long_index));
    screen.resource_destroy=resource_destroyed; pipe.screen=&screen;
    resource.base.screen=&screen; resource.base.target=PIPE_BUFFER;
    resource.base.reference.count=1; resource.base.width0=7;
    resource.gpu=&gpu; gpu.map=bytes+1; gpu.size=sizeof(bytes)-1;
    info.index_size=2; info.index.resource=&resource.base;
    assert(!index_source_init(&pipe,&info,&source));
    assert(source.bounded && source.bytes==7);
    assert(!index_source_read(&source,2,0,-1,&vertex) && vertex==512);
    assert(index_source_read(&source,2,3,0,&vertex)==-1);
    /* A padded backing allocation cannot extend the logical buffer. */
    assert(index_source_read(&source,4,1,0,&vertex)==-1);
    resource.base.width0=19; gpu.size=6;
    assert(!index_source_init(&pipe,&info,&source) && source.bytes==6);
    assert(index_source_read(&source,4,1,0,&vertex)==-1);
    gpu.size=0;
    assert(!index_source_init(&pipe,&info,&source) && source.bounded && !source.bytes);
    assert(index_source_read(&source,1,0,0,&vertex)==-1);

    gpu.size=19; before=maps; resource.base.screen=&foreign;
    assert(index_source_init(&pipe,&info,&source)==-1 && maps==before);
    resource.base.screen=&screen; resource.base.target=PIPE_TEXTURE_2D;
    assert(index_source_init(&pipe,&info,&source)==-1 && maps==before);
    resource.base.target=PIPE_BUFFER; gpu.map=NULL;
    assert(index_source_init(&pipe,&info,&source)==-1 && maps==before+1);
    gpu.map=bytes+1;

    /* Unaligned user indices use memcpy and signed base-vertex arithmetic. */
    info.has_user_indices=1; info.index.user=bytes+1;
    assert(!index_source_init(&pipe,&info,&source) && !source.bounded);
    assert(!index_source_read(&source,2,0,-513,&vertex) && vertex==0);
    assert(index_source_read(&source,2,0,-514,&vertex)==-2);
    assert(!index_source_read(&source,4,1,0,&vertex) && vertex==UINT32_MAX);
    assert(index_source_read(&source,4,1,1,&vertex)==-2);
    assert(index_source_read(&source,3,0,0,&vertex)==-1);
    assert(index_source_read(&source,4,UINT64_MAX,0,&vertex)==-1);
    source.data=(void *)(UINTPTR_MAX-1);
    assert(index_source_read(&source,4,0,0,&vertex)==-1);

    /* Transferred references stay alive through the body and are released
     * exactly once after both accepted and rejected body paths. */
    info.has_user_indices=0; info.index.resource=&resource.base;
    info.take_index_buffer_ownership=1;
    draw_with_index_ownership(&pipe,&info,7,NULL,NULL,0,draw_body);
    assert(calls==1 && destroyed==1);
    resource.base.reference.count=1; destroyed=0;
    info.take_index_buffer_ownership=0;
    draw_with_index_ownership(&pipe,&info,0,NULL,NULL,0,draw_body);
    assert(calls==2 && !destroyed && resource.base.reference.count==1);
    info.has_user_indices=1; info.take_index_buffer_ownership=1; info.index.user=bytes;
    draw_with_index_ownership(&pipe,&info,0,NULL,NULL,0,draw_body);
    assert(calls==3 && !destroyed);
    puts("Gallium indexed-buffer bounds, alignment and ownership: PASS");
    return 0;
}
