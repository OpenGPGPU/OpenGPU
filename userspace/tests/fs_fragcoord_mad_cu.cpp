/* Execute an emitted fragment shader on a prebuilt product-profile CU model. */
#include "VGpuComputeUnit.h"
#include "verilated.h"
#include <array>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <deque>
#include <fstream>
#include <map>
#include <stdexcept>
#include <vector>

struct Reply { unsigned id, due; std::array<uint32_t, 16> data; };

int main(int argc, char **argv)
{
    if (argc != 1 && argc != 3) return 2;
    const bool shader = argc == 3;
    const int scenario = shader ? std::atoi(argv[2]) : 0;
    if (scenario < 0 || scenario > 9) return 2;
    VerilatedContext context;
    context.commandArgs(argc, argv);
    VGpuComputeUnit d(&context);
    std::map<uint32_t, uint8_t> memory;
    auto put = [&](uint32_t at, uint32_t x) {
        for (unsigned i = 0; i < 4; i++) memory[at + i] = x >> (8 * i);
    };
    auto word = [&](uint32_t at) {
        uint32_t x = 0;
        for (unsigned i = 0; i < 4; i++) x |= uint32_t(memory[at + i]) << (8 * i);
        return x;
    };
    std::vector<uint32_t> code = {0x000092b7, 0x00700313, 0x0062a023, 0x30500073};
    if (shader) {
        std::ifstream file(argv[1], std::ios::binary);
        if (!file) return 2;
        code.clear();
        uint32_t w;
        while (file.read(reinterpret_cast<char *>(&w), 4)) code.push_back(w);
        if (!file.eof() || code.empty() || code.size() > 512) return 2;
        for (unsigned i = 0; i < 128; i++) {
            put(0x8000 + 4 * i, 0); put(0x8200 + 4 * i, i % 4);
            put(0x8c00 + 4 * i, 0xdeadbeef);
        }
        /* Three operands and the final colour scale. */
        const uint32_t constants[5][4] = {
            {0x3e000000, 0x3e000000, 0, 0x3f800000}, /* y / 8 + 1 / 8 */
            {0x3f800000, 0x00000000, 0, 0x3e000000}, /* identity */
            {0xbf800000, 0x40800000, 0, 0x3e000000}, /* flip: 4 - y */
            {0x3f800001, 0x3f7ffffe, 0xbf800000, 0xd6000000}, /* fused */
            {0x3f800001, 0x3f7ffffe, 0xbf800000, 0xd6000000}, /* separate */
        };
        if (scenario < 5) {
            put(0x9200, constants[scenario][0]); put(0x9204, constants[scenario][1]);
            put(0x9208, constants[scenario][2]); put(0x9210, constants[scenario][3]);
        } else {
            put(0x9200, 0x3e000000); put(0x9204, 0x3ec00000); put(0x9208, 0);
            put(0x9210, 0x3f000000); put(0x9214, 0x3e000000);
        }
        put(0x920c, 0x3f800000); put(0x921c, 0x3f800000);
    }
    for (unsigned i = 0; i < code.size(); i++) put(0x1000 + 4 * i, code[i]);
    std::deque<Reply> pending;
    unsigned cycle = 0, reads = 0, writes = 0;
    d.clock = 0; d.reset = 1; d.io_memoryRequest_ready = 1; d.io_completion_ready = 0;
    d.io_trap_ready = 0; d.io_l1InvalidateDone_ready = 1;
    d.io_kernel_bits_kernelPc = 0x1000; d.io_kernel_bits_kernargAddress = 0x8000;
    d.io_kernel_bits_gridSize_0 = d.io_kernel_bits_gridSize_1 = d.io_kernel_bits_gridSize_2 = 1;
    d.io_kernel_bits_localSize_0 = shader ? 4 : 1;
    d.io_kernel_bits_localSize_1 = d.io_kernel_bits_localSize_2 = 1;
    auto tick = [&]() {
        d.clock = 0;
        bool offered = !pending.empty() && pending.front().due <= cycle;
        d.io_memoryResponse_valid = offered;
        if (offered) {
            d.io_memoryResponse_bits_transactionId = pending.front().id;
            for (unsigned i = 0; i < 16; i++) d.io_memoryResponse_bits_readData[i] = pending.front().data[i];
        }
        d.eval();
        bool accept_response = offered && d.io_memoryResponse_ready;
        bool request = d.io_memoryRequest_valid && d.io_memoryRequest_ready;
        Reply incoming{};
        if (request) {
            uint32_t at = d.io_memoryRequest_bits_address;
            if ((at & 63) || !((at >= 0x1000 && at < 0x1800) || (at >= 0x8000 && at < 0x9400)))
                throw std::runtime_error("unexpected memory request");
            incoming.id = d.io_memoryRequest_bits_transactionId; incoming.due = cycle + 3;
            for (unsigned i = 0; i < 16; i++) incoming.data[i] = word(at + i * 4);
            if (d.io_memoryRequest_bits_isWrite) {
                if (at != (shader ? 0x8c00u : 0x9000u)) throw std::runtime_error("unexpected write");
                writes++;
                for (unsigned i = 0; i < 64; i++) if ((d.io_memoryRequest_bits_byteMask >> i) & 1)
                    memory[at + i] = d.io_memoryRequest_bits_writeData[i / 4] >> (8 * (i % 4));
            } else reads++;
        }
        context.timeInc(5); d.clock = 1; d.eval();
        context.timeInc(5); d.clock = 0; d.eval();
        if (accept_response) pending.pop_front();
        if (request) pending.push_back(incoming);
        cycle++;
    };
    try {
        tick(); d.reset = 0; d.io_kernel_valid = 1;
        for (unsigned i = 0; i < 100 && !d.io_kernel_ready; i++) tick();
        if (!d.io_kernel_ready) throw std::runtime_error("kernel not ready");
        tick(); d.io_kernel_valid = 0;
        while (!d.io_completion_valid && cycle < 20000) {
            if (d.io_trap_valid) throw std::runtime_error("shader trapped");
            tick();
        }
        if (!d.io_completion_valid || !d.io_completion_bits_success || !pending.empty())
            throw std::runtime_error("kernel did not finish cleanly");
        if (shader) {
            const uint32_t expected[10][4] = {
                {0x200000ff, 0x400000ff, 0x600000ff, 0x800000ff},
                {0x000000ff, 0x200000ff, 0x400000ff, 0x600000ff},
                {0x800000ff, 0x600000ff, 0x400000ff, 0x200000ff},
                {0x800000ff, 0x800000ff, 0x800000ff, 0x800000ff},
                {0x000000ff, 0x000000ff, 0x000000ff, 0x000000ff},
                {0x503000ff, 0x503000ff, 0x503000ff, 0x503000ff},
                {0x703000ff, 0x703000ff, 0x703000ff, 0x703000ff},
                {0x504800ff, 0x504800ff, 0x504800ff, 0x504800ff},
                {0x384800ff, 0x384800ff, 0x384800ff, 0x384800ff},
                {0x9f305020, 0x9f305020, 0x9f305020, 0x9f305020},
            };
            for (unsigned i = 0; i < 4; i++) if (word(0x8c00 + 4 * i) != expected[scenario][i]) {
                std::fprintf(stderr, "scenario=%d lane=%u got=%08x expected=%08x\n", scenario, i,
                             word(0x8c00 + 4 * i), expected[scenario][i]);
                throw std::runtime_error("incorrect fragment result");
            }
            for (unsigned i = 4; i < 128; i++) if (word(0x8c00 + 4 * i) != 0xdeadbeef)
                throw std::runtime_error("inactive lane or output guard changed");
        } else if (word(0x9000) != 7) throw std::runtime_error("incorrect control output");
        if (!reads || writes != 1) throw std::runtime_error("unexpected request counts");
        std::printf("CU %s scenario=%d PASS cycles=%u reads=%u writes=%u\n",
                    shader ? "fragment" : "control", scenario, cycle, reads, writes);
    } catch (const std::exception &e) {
        std::fprintf(stderr, "CU failed cycle=%u: %s address=%08x trap=%u\n",
                     cycle, e.what(), d.io_memoryRequest_bits_address, d.io_trap_valid);
        return 1;
    }
}
