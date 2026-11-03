/* Run the same bounded workgroup program admitted by the compute validator. */
#include "VGpuComputeUnit.h"
#include "verilated.h"
#include "opengpu_shader_validator.h"
#include <array>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <deque>
#include <map>
#include <stdexcept>

struct Reply { unsigned id, due; std::array<uint32_t, 16> data; };

int main(int argc, char **argv)
{
    if (argc != 4 && argc != 5) return 2;
    const unsigned x = std::strtoul(argv[1], nullptr, 10);
    const unsigned y = std::strtoul(argv[2], nullptr, 10);
    const unsigned z = std::strtoul(argv[3], nullptr, 10);
    const bool wrong_expected = argc == 5;
    const unsigned items = x * y * z;
    if (!x || !y || !z || !items || items > 128) return 2;
    const uint32_t program[] = {
        0xc1087057u, /* vsetivli x0,16,e32,m1 */
        0x00241293u, /* slli x5,x8,2 */
        0x005082b3u, /* add x5,x1,x5 */
        0x0202e0a7u, /* vse32.v v1,(x5) */
        OPENGPU_SHADER_CEASE,
    };
    if (!opengpu_compute_shader_validate_words_features(program, 5, items * 4, items, false, false) ||
        opengpu_compute_shader_validate_words_features(program, 5, items * 4 - 1, items, false, false))
        throw std::runtime_error("compute validator accepted an incorrect workgroup bound");
    VerilatedContext context;
    context.commandArgs(argc, argv);
    VGpuComputeUnit d(&context);
    std::map<uint32_t, uint8_t> memory;
    auto put = [&](uint32_t at, uint32_t value) {
        for (unsigned i = 0; i < 4; i++) memory[at + i] = value >> (8 * i);
    };
    auto word = [&](uint32_t at) {
        uint32_t value = 0;
        for (unsigned i = 0; i < 4; i++) value |= uint32_t(memory[at + i]) << (8 * i);
        return value;
    };
    for (unsigned i = 0; i < 5; i++) put(0x1000 + 4 * i, program[i]);
    for (uint32_t at = 0x7fc0; at < 0x8240; at += 4) put(at, 0xdeadbeefu);
    std::deque<Reply> pending;
    unsigned cycle = 0, reads = 0, writes = 0;
    d.clock = 0; d.reset = 1; d.io_completion_ready = 0;
    d.io_trap_ready = 0; d.io_l1InvalidateDone_ready = 1;
    d.io_kernel_bits_kernelPc = 0x1000; d.io_kernel_bits_kernargAddress = 0x8000;
    d.io_kernel_bits_gridSize_0 = d.io_kernel_bits_gridSize_1 = d.io_kernel_bits_gridSize_2 = 1;
    d.io_kernel_bits_localSize_0 = x; d.io_kernel_bits_localSize_1 = y; d.io_kernel_bits_localSize_2 = z;
    auto tick = [&]() {
        d.clock = 0; d.io_memoryRequest_ready = cycle % 7 < 5;
        const bool offered = !pending.empty() && pending.front().due <= cycle;
        d.io_memoryResponse_valid = offered;
        if (offered) {
            d.io_memoryResponse_bits_transactionId = pending.front().id;
            for (unsigned i = 0; i < 16; i++) d.io_memoryResponse_bits_readData[i] = pending.front().data[i];
        }
        d.eval();
        const bool accept_response = offered && d.io_memoryResponse_ready;
        const bool request = d.io_memoryRequest_valid && d.io_memoryRequest_ready;
        Reply incoming{};
        if (request) {
            const uint32_t at = d.io_memoryRequest_bits_address;
            if ((at & 63) || !(at == 0x1000 || (at >= 0x7fc0 && at < 0x8240)))
                throw std::runtime_error("unexpected memory request");
            incoming.id = d.io_memoryRequest_bits_transactionId; incoming.due = cycle + 3;
            for (unsigned i = 0; i < 16; i++) incoming.data[i] = word(at + i * 4);
            if (d.io_memoryRequest_bits_isWrite) {
                if (at < 0x8000 || at >= 0x8200) throw std::runtime_error("write outside workgroup");
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
        while (!d.io_completion_valid && cycle < 30000) {
            if (d.io_trap_valid) throw std::runtime_error("shader trapped");
            tick();
        }
        if (!d.io_completion_valid || !d.io_completion_bits_success || !pending.empty())
            throw std::runtime_error("workgroup did not finish cleanly");
        for (unsigned i = 0; i < 128; i++) {
            const uint32_t expected = i < items ? i + (wrong_expected ? 1u : 0u) : 0xdeadbeefu;
            if (word(0x8000 + 4 * i) != expected) {
                std::fprintf(stderr, "workgroup mismatch lane=%u got=%08x expected=%08x\n",
                             i, word(0x8000 + 4 * i), expected);
                return 1;
            }
        }
        for (uint32_t at = 0x7fc0; at < 0x8000; at += 4)
            if (word(at) != 0xdeadbeefu) throw std::runtime_error("lower guard changed");
        for (uint32_t at = 0x8200; at < 0x8240; at += 4)
            if (word(at) != 0xdeadbeefu) throw std::runtime_error("upper guard changed");
        if (!reads || !writes) throw std::runtime_error("missing memory traffic");
        std::printf("CU workgroup %ux%ux%u PASS cycles=%u reads=%u writes=%u\n",
                    x, y, z, cycle, reads, writes);
    } catch (const std::exception &error) {
        std::fprintf(stderr, "CU failed cycle=%u: %s\n", cycle, error.what());
        return 1;
    }
}
