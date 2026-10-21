/* SPDX-License-Identifier: MIT */
/* Fragment programs the Gallium menu does not classify. create_fs_state
 * lowers each one through opengpu_fs_emit. These are the emitter words,
 * before the runtime ABI lowerer.
 *
 * sqrt:  gl_FragColor = sqrt(CONST[0]) with (0.25, 1, 0.25, 1).
 *        The uniform is fsqrt.s, then splatted. Exact roots pack
 *        to 0x80ff80ff.
 * clamp: gl_FragColor = min(max(CONST[0], 0.5), 1). CONST[0] is
 *        (2, 0.25, 0.5, 1.5) and CONST[1] is (0.5, 1, 0, 0), so the
 *        colour is (1, 0.5, 0.5, 1) and packs to 0xff8080ff.
 * coord: gl_FragColor.x = (gl_FragCoord.y < 32), and .yzw stays
 *        (0, 1, 1). Below is 0xff00ffff and at or above is 0x0000ffff.
 *        The 64x64 triangle crosses y=32.
 * branch: if (CONST[0].x) (1,0,0,1) else (0,1,0,1). CONST[0].yzw stays
 *        (1, 0, 1); only .x selects the side.
 * kill:  discard when gl_FragCoord.y < 32. Survivors are 0xff0000ff. */
#ifndef OPENGPU_FRAGMENT_COVER_H
#define OPENGPU_FRAGMENT_COVER_H

#include <stdint.h>

#define OPENGPU_FRAGMENT_SQRT_EXPECTED 0x80ff80ffu
#define OPENGPU_FRAGMENT_CLAMP_EXPECTED 0xff8080ffu
#define OPENGPU_FRAGMENT_COORD_BELOW 0xff00ffffu
#define OPENGPU_FRAGMENT_COORD_ABOVE 0x0000ffffu
#define OPENGPU_FRAGMENT_IF_THEN 0xff0000ffu
#define OPENGPU_FRAGMENT_IF_ELSE 0x00ff00ffu
#define OPENGPU_FRAGMENT_KILL_EXPECTED 0xff0000ffu

#define OPENGPU_FRAGMENT_SQRT_SCALAR 0x5800ffd3u
#define OPENGPU_FRAGMENT_COORD_YCVT 0x4a419357u
#define OPENGPU_FRAGMENT_IF_BNE 0x02039463u
#define OPENGPU_FRAGMENT_KILL_VALIDITY 0x10028313u

static const uint32_t opengpu_fragment_sqrt_uniform[] = {
    0x3e800000u, 0x3f800000u, 0x3e800000u, 0x3f800000u,
};

static const uint32_t opengpu_fragment_sqrt[] = {
    0x1200a087u, 0x1240a107u, 0x1280a187u, 0x12c0a207u,
    0xd00072d3u, 0x00100393u, 0xd003f353u, 0x0ff00393u,
    0xd003fed3u, 0x00241293u, 0x005082b3u, 0xc1027057u,
    0x00028313u, 0x02036287u, 0x4a5192d7u, 0x02028313u,
    0x02036207u, 0x5e02df57u, 0x5e035fd7u, 0x5800ffd3u,
    0x5e0fd357u, 0x58017fd3u, 0x5e0fd3d7u, 0x5801ffd3u,
    0x5e0fd457u, 0x58027fd3u, 0x5e0fd4d7u, 0x5e030557u,
    0x5e038357u, 0x5e0403d7u, 0x5e048457u, 0x92aed557u,
    0x4aa09557u, 0x926ed357u, 0x4a609357u, 0x927ed3d7u,
    0x4a7093d7u, 0x928ed457u, 0x4a809457u, 0x96ac3557u,
    0x96683357u, 0x967433d7u, 0x2aa30557u, 0x2aa38557u,
    0x2aa40557u, 0x0c028313u, 0x02036527u, 0x30500073u,
};

#define OPENGPU_FRAGMENT_SQRT_WORDS \
    (sizeof(opengpu_fragment_sqrt) / sizeof(opengpu_fragment_sqrt[0]))

static const uint32_t opengpu_fragment_clamp_uniform[] = {
    0x40000000u, 0x3e800000u, 0x3f000000u, 0x3fc00000u,
    0x3f000000u, 0x3f800000u, 0x00000000u, 0x00000000u,
};

static const uint32_t opengpu_fragment_clamp[] = {
    0x1200a087u, 0x1240a107u, 0x1280a187u, 0x12c0a207u,
    0x1300a287u, 0x1340a307u, 0x1380a387u, 0x13c0a407u,
    0xd00074d3u, 0x00100393u, 0xd003f553u, 0x0ff00393u,
    0xd003fed3u, 0x00241293u, 0x005082b3u, 0xc1027057u,
    0x00028313u, 0x02036287u, 0x4a5192d7u, 0x02028313u,
    0x02036207u, 0x5e04df57u, 0x5e055fd7u, 0x5e00d157u,
    0x5e02d0d7u, 0x1a209357u, 0x5e015157u, 0x5e02d0d7u,
    0x1a2093d7u, 0x5e01d157u, 0x5e02d0d7u, 0x1a209457u,
    0x5e025157u, 0x5e02d0d7u, 0x1a2094d7u, 0x12635557u,
    0x12735357u, 0x128353d7u, 0x12935457u, 0x5e0504d7u,
    0x5e030557u, 0x5e038357u, 0x5e0403d7u, 0x929ed4d7u,
    0x4a9094d7u, 0x92aed557u, 0x4aa09557u, 0x926ed357u,
    0x4a609357u, 0x927ed3d7u, 0x4a7093d7u, 0x969c34d7u,
    0x96a83557u, 0x96643357u, 0x2a9504d7u, 0x2a9304d7u,
    0x2a9384d7u, 0x0c028313u, 0x020364a7u, 0x30500073u,
};

#define OPENGPU_FRAGMENT_CLAMP_WORDS \
    (sizeof(opengpu_fragment_clamp) / sizeof(opengpu_fragment_clamp[0]))

static const uint32_t opengpu_fragment_coord_uniform[] = {
    0x42000000u, 0x00000000u, 0x3f800000u, 0x3f800000u,
};

static const uint32_t opengpu_fragment_coord[] = {
    0x1200a087u, 0x1240a107u, 0x1280a187u, 0x12c0a207u,
    0xd00072d3u, 0x00100393u, 0xd003f353u, 0x0ff00393u,
    0xd003fed3u, 0x00241293u, 0x005082b3u, 0xc1027057u,
    0x00028313u, 0x02036287u, 0x4a5192d7u, 0x02028313u,
    0x02036207u, 0x4a419357u, 0x5e02df57u, 0x5e035fd7u,
    0x6e60d057u, 0x5def80d7u, 0x5e0083d7u, 0x5e015157u,
    0x5e010357u, 0x5e01d157u, 0x5e010457u, 0x5e025157u,
    0x5e0104d7u, 0x5e038557u, 0x5e0303d7u, 0x5e040357u,
    0x5e048457u, 0x92aed557u, 0x4aa09557u, 0x927ed3d7u,
    0x4a7093d7u, 0x926ed357u, 0x4a609357u, 0x928ed457u,
    0x4a809457u, 0x96ac3557u, 0x967833d7u, 0x96643357u,
    0x2aa38557u, 0x2aa30557u, 0x2aa40557u, 0x0c028313u,
    0x02036527u, 0x30500073u,
};

#define OPENGPU_FRAGMENT_COORD_WORDS \
    (sizeof(opengpu_fragment_coord) / sizeof(opengpu_fragment_coord[0]))

static const uint32_t opengpu_fragment_if_then_uniform[] = {
    0x3f800000u, 0x3f800000u, 0x00000000u, 0x3f800000u,
};

static const uint32_t opengpu_fragment_if_else_uniform[] = {
    0x00000000u, 0x3f800000u, 0x00000000u, 0x3f800000u,
};

static const uint32_t opengpu_fragment_if[] = {
    0x1200a087u, 0x1240a107u, 0x1280a187u, 0x12c0a207u,
    0xd00072d3u, 0x00100393u, 0xd003f353u, 0x0ff00393u,
    0xd003fed3u, 0x00241293u, 0x005082b3u, 0xc1027057u,
    0x00028313u, 0x02036287u, 0x4a5192d7u, 0x02028313u,
    0x02036207u, 0x5e02df57u, 0x5e035fd7u, 0xa050a3d3u,
    0x02039463u, 0x5e015157u, 0x5e010357u, 0x5e01d157u,
    0x5e0103d7u, 0x5e01d157u, 0x5e010457u, 0x5e025157u,
    0x5e0104d7u, 0x02000263u, 0x5e01d157u, 0x5e010357u,
    0x5e015157u, 0x5e0103d7u, 0x5e01d157u, 0x5e010457u,
    0x5e025157u, 0x5e0104d7u, 0x5e030557u, 0x5e038357u,
    0x5e0403d7u, 0x5e048457u, 0x92aed557u, 0x4aa09557u,
    0x926ed357u, 0x4a609357u, 0x927ed3d7u, 0x4a7093d7u,
    0x928ed457u, 0x4a809457u, 0x96ac3557u, 0x96683357u,
    0x967433d7u, 0x2aa30557u, 0x2aa38557u, 0x2aa40557u,
    0x0c028313u, 0x02036527u, 0x30500073u,
};

#define OPENGPU_FRAGMENT_IF_WORDS \
    (sizeof(opengpu_fragment_if) / sizeof(opengpu_fragment_if[0]))

static const uint32_t opengpu_fragment_kill_uniform[] = {
    0x42000000u, 0x3f800000u, 0x00000000u, 0x3f800000u,
};

static const uint32_t opengpu_fragment_kill[] = {
    0x1200a087u, 0x1240a107u, 0x1280a187u, 0x12c0a207u,
    0xd00072d3u, 0x00100393u, 0xd003f353u, 0x0ff00393u,
    0xd003fed3u, 0x00241293u, 0x005082b3u, 0xc1027057u,
    0x00028313u, 0x02036287u, 0x4a5192d7u, 0x02028313u,
    0x02036207u, 0x4a419357u, 0x5e02df57u, 0x5e035fd7u,
    0x20109f53u, 0x5e0f50d7u, 0x026093d7u, 0x5e0f8357u,
    0x6e72d057u, 0x5c6f0357u, 0x6e72d057u, 0x5c6f0357u,
    0x6e72d057u, 0x5c6f0357u, 0x6e72d057u, 0x5c6f0357u,
    0x10028313u, 0x02036327u, 0x5e015157u, 0x5e010357u,
    0x5e01d157u, 0x5e010457u, 0x5e01d157u, 0x5e0104d7u,
    0x5e025157u, 0x5e010557u, 0x926ed357u, 0x4a609357u,
    0x928ed457u, 0x4a809457u, 0x929ed4d7u, 0x4a9094d7u,
    0x92aed557u, 0x4aa09557u, 0x966c3357u, 0x96883457u,
    0x969434d7u, 0x2a640357u, 0x2a648357u, 0x2a650357u,
    0x0c028313u, 0x02036327u, 0x30500073u,
};

#define OPENGPU_FRAGMENT_KILL_WORDS \
    (sizeof(opengpu_fragment_kill) / sizeof(opengpu_fragment_kill[0]))

#endif
