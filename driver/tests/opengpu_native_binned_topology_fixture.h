/* CPU-prepared seven-topology inputs. No GPU model is executed here. */
#ifndef OPENGPU_NATIVE_BINNED_TOPOLOGY_FIXTURE_H
#define OPENGPU_NATIVE_BINNED_TOPOLOGY_FIXTURE_H
#include "opengpu_native_binned_line_loop_fixture.h"

enum { OPENGPU_TOPOLOGY_SUBMISSIONS=9 };
/* Twice-window coordinates: (1,1),(7,1),(1,7),(7,7),
 * (1,3),(5,1),(3,7),(7,5). All map exactly through the 4x4 viewport. */
static const uint32_t opengpu_topology_positions[8][2]={
    {0xbf400000,0xbf400000},{0x3f400000,0xbf400000},
    {0xbf400000,0x3f400000},{0x3f400000,0x3f400000},
    {0xbf400000,0xbe800000},{0x3e800000,0xbf400000},
    {0xbe800000,0x3f400000},{0x3f400000,0x3e800000}
};
/* Independent integer/rational CPU oracles are checked in the Python suite.
 * ONE+ONE blending exposes overlap, missing edges and erroneous restart bridges.
 * LINES and TRIANGLE_STRIP split after three vertices; the other modes after four.
 * Back-face culling is enabled for TRIANGLE_STRIP to expose winding/parity errors. */
static const uint8_t opengpu_topology_coverage[7][16]={
    {1,0,1,1, 1,0,0,0, 0,0,0,1, 1,1,0,1},
    {1,1,2,0, 0,0,1,0, 0,2,1,0, 0,0,0,1},
    {1,2,2,1, 1,2,2,0, 0,2,3,1, 1,2,1,1},
    {1,2,2,1, 1,0,2,0, 0,2,1,0, 1,2,1,0},
    {0,0,0,0, 0,2,1,0, 0,2,0,0, 0,0,0,0},
    {0,0,0,0, 0,2,2,0, 0,1,1,0, 0,0,0,0},
    {0,0,0,0, 0,3,1,0, 0,4,2,1, 0,1,1,1}
};
static inline unsigned opengpu_topology_mode(unsigned submission)
{ return submission<7?submission:(submission==7?4:5); }
static inline unsigned opengpu_topology_index_format(unsigned submission)
{ return submission>=7?3:1+submission%3; }
static inline int opengpu_topology_build(struct opengpu_ll_fixture *f,unsigned submission)
{
    if(submission>=OPENGPU_TOPOLOGY_SUBMISSIONS) return -1;
    if(opengpu_ll_build_at(f,submission==7?1:(submission==8?2:0),submission+3)) return -1;
    const unsigned mode=opengpu_topology_mode(submission),format=opengpu_topology_index_format(submission);
    const unsigned bytes=1u<<(format-1),split=(mode==1 || mode==5)?3:4;
    uint8_t *d=f->memory+OPENGPU_LL_DRAW;
#define T(name,v) opengpu_ll_put(d,OPENGPU_NATIVE_BINNED_DRAW_##name##_WORD,(v))
    T(DRAW_VERTEX_GEOMETRY_TOPOLOGY,mode);
    T(DRAW_RASTER_CULLMODE,mode==5?1:0);
    T(DRAW_VERTEX_GEOMETRY_VERTEX_INPUT_COUNT,9);
    T(DRAW_VERTEX_GEOMETRY_VERTEX_INPUT_INDEXFORMAT,format);
    T(DRAW_VERTEX_GEOMETRY_VERTEX_INPUT_INDICES_LIMIT,f->base+OPENGPU_LL_INDICES+9*bytes-(submission==7));
    T(DRAW_VERTEX_GEOMETRY_VERTEX_INPUT_BINDINGS0_LIMIT,f->base+OPENGPU_LL_POSITIONS+128);
    memset(f->memory+OPENGPU_LL_INDICES,0,64);
    for(unsigned i=0;i<9;i++) {
        const uint32_t value=i==split?UINT32_MAX:(i<split?i:i-1);
        for(unsigned b=0;b<bytes;b++)f->memory[OPENGPU_LL_INDICES+i*bytes+b]=(uint8_t)(value>>(8*b));
    }
    for(unsigned i=0;i<8;i++)for(unsigned c=0;c<4;c++)
        opengpu_ll_put(f->memory+OPENGPU_LL_POSITIONS,4*i+c,c<2?opengpu_topology_positions[i][c]:(c==3?0x3f800000u:0));
    memset(f->expected_image,0xa7,OPENGPU_LL_IMAGE_BYTES);
    if(submission!=7)for(unsigned pixel=0;pixel<16;pixel++) {
        const unsigned count=opengpu_topology_coverage[mode][pixel],pos=3+(pixel/4)*21+(pixel%4)*4;
        f->expected_image[pos]=(uint8_t)(count*f->red_quantum);
        f->expected_image[pos+1]=f->expected_image[pos+2]=0;f->expected_image[pos+3]=count?255:0;
    }
#undef T
    return 0;
}
#endif
