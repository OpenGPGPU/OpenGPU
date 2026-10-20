/* SPDX-License-Identifier: MIT */
/* Fragment program the Gallium menu does not classify:
 * gl_FragColor = CONST[0][0] * CONST[0][1]. create_fs_state lowers it
 * through opengpu_fs_emit. These are the emitter words, before the runtime
 * ABI lowerer. CONST[0][0] is (1, 0, 0, 1) and CONST[0][1] is (1, 1, 1, 1),
 * so the packed colour is 0xff0000ff. */
#ifndef OPENGPU_FRAGMENT_ALU_H
#define OPENGPU_FRAGMENT_ALU_H

#include <stdint.h>

#define OPENGPU_FRAGMENT_ALU_EXPECTED 0xff0000ffu

static const uint32_t opengpu_fragment_alu_uniform[] = {
    0x3f800000u, 0x00000000u, 0x00000000u, 0x3f800000u,
    0x3f800000u, 0x3f800000u, 0x3f800000u, 0x3f800000u,
};

static const uint32_t opengpu_fragment_alu[] = {
    0x1200a087u, 0x1240a107u, 0x1280a187u, 0x12c0a207u,
    0x1300a287u, 0x1340a307u, 0x1380a387u, 0x13c0a407u,
    0xd00074d3u, 0x00100393u, 0xd003f553u, 0x0ff00393u,
    0xd003fed3u, 0x00241293u, 0x005082b3u, 0xc1027057u,
    0x00028313u, 0x02036287u, 0x4a5192d7u, 0x02028313u,
    0x02036207u, 0x5e04df57u, 0x5e055fd7u, 0x5e00d157u,
    0x5e02d0d7u, 0x92209357u, 0x5e015157u, 0x5e0350d7u,
    0x922093d7u, 0x5e01d157u, 0x5e03d0d7u, 0x92209457u,
    0x5e025157u, 0x5e0450d7u, 0x922094d7u, 0x5e030557u,
    0x5e038357u, 0x5e0403d7u, 0x5e048457u, 0x92aed557u,
    0x4aa09557u, 0x926ed357u, 0x4a609357u, 0x927ed3d7u,
    0x4a7093d7u, 0x928ed457u, 0x4a809457u, 0x96ac3557u,
    0x96683357u, 0x967433d7u, 0x2aa30557u, 0x2aa38557u,
    0x2aa40557u, 0x0c028313u, 0x02036527u, 0x30500073u,
};

#define OPENGPU_FRAGMENT_ALU_WORDS \
    (sizeof(opengpu_fragment_alu) / sizeof(opengpu_fragment_alu[0]))

#endif
