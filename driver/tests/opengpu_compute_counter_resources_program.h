/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_COMPUTE_COUNTER_RESOURCES_PROGRAM_H
#define OPENGPU_COMPUTE_COUNTER_RESOURCES_PROGRAM_H
#include <stdint.h>
/* One lane fetches texture data, stores/loads storage and image words, then
 * increments counter zero. Results are written to four kernarg words. */
#define OPENGPU_COUNTER_RESOURCE_INDEX_WORD 37u
static const uint32_t opengpu_counter_resources_program[] = {
    0x00100313u, 0x0663000bu, 0x0600100bu, 0x0600200bu,
    0x0600300bu, 0x0600400bu, 0x0600500bu, 0x0600600bu,
    0x0600700bu, 0x0a00038bu, 0x0070a023u, 0xc100f057u,
    0x5e003157u, 0x5e04b1d7u, 0x5e003257u, 0x3a01802bu,
    0x4241002bu, 0x0330000fu, 0x3e4102abu, 0x00808493u,
    0x0204e2a7u, 0x0330000fu, 0x5e003157u, 0x3a800513u,
    0x5e0541d7u, 0x2231002bu, 0x2221102bu, 0x5e03b257u,
    0x2222202bu, 0x2221302bu, 0x2a21002bu, 0x0330000fu,
    0x262102abu, 0x0330000fu, 0x00408493u, 0x0204e2a7u,
    0x0330000fu, 0x5e003157u, 0x4a01032bu, 0x00c08493u,
    0x0204e327u, 0x30500073u,
};
#endif
