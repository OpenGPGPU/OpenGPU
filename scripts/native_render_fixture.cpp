#include "arti_rtl_model.h"
#include "gpu_abi.h"
#include <cassert>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <vector>

static std::vector<uint8_t> memory(1 << 20);
static uint64_t reads, writes, read_bytes, write_bytes;
static int faults;
static int rd(uint64_t a, uint8_t *d, unsigned n, uint64_t) {
  if (a > memory.size() || n > memory.size()-a) { ++faults; return -1; }
  memcpy(d, memory.data()+a, n); ++reads; read_bytes += n; return 0;
}
static int wr(uint64_t a, const uint8_t *d, unsigned n, uint64_t mask, uint64_t) {
  if (a > memory.size() || n > memory.size()-a || n > 64) { ++faults; return -1; }
  for (unsigned i=0; i<n; ++i) if ((mask >> i) & 1) { memory[a+i]=d[i]; ++write_bytes; }
  ++writes; return 0;
}
static void regw(uint64_t a, uint64_t v) { assert(!arti_rtl_model_write(a,v,4)); }
static uint64_t regr(uint64_t a) { uint64_t v=0; assert(!arti_rtl_model_read(a,&v,4)); return v; }

int main(int argc, char **argv) {
  assert(argc==4);
  // IRQ observation must not advance a variable number of model cycles.
  setenv("ARTI_MODEL_IRQ_PUMP","0",1);
  setenv("ARTI_MODEL_HOLD_BOOST","0",1);
  std::ifstream input(argv[1],std::ios::binary); assert(input);
  input.read(reinterpret_cast<char *>(memory.data()),memory.size());
  assert(input.gcount()==static_cast<std::streamsize>(memory.size()));
  arti_rtl_model_set_memory_callbacks(rd,wr); arti_rtl_model_init();
  const uint64_t shader_config=regr(GPU_REG_SHADER_CONFIG);
  const uint64_t expected_config=strtoull(argv[3],nullptr,0);
  if (shader_config != expected_config) {
    fprintf(stderr,"Shader resource mismatch: model=%llx fixture=%llx\n",
      (unsigned long long)shader_config,(unsigned long long)expected_config);
    return 2;
  }
  regw(GPU_REG_IRQ,3); regw(GPU_REG_UCMD_ID,91);
  regw(GPU_REG_UCMD_OPCODE,GPU_UCMD_OP_RENDER);
  regw(GPU_REG_UCMD_SOURCE,0x1000); regw(GPU_REG_UCMD_BYTES,64);
  uint64_t begin=0,end=0;
  arti_rtl_model_skip_stats(&begin,nullptr,nullptr,nullptr);
  const auto start=std::chrono::steady_clock::now();
  regw(GPU_REG_UCMD_SUBMIT,1);
  unsigned ticks=0;
  while (!arti_rtl_model_check_irq(0) && ticks<2000000) {
    arti_rtl_model_tick_n(1); ++ticks;
  }
  const double seconds=std::chrono::duration<double>(std::chrono::steady_clock::now()-start).count();
  arti_rtl_model_skip_stats(&end,nullptr,nullptr,nullptr);
  const uint64_t completion=regr(GPU_REG_UCMD_COMPLETION);
  std::ofstream output(argv[2],std::ios::binary); assert(output);
  output.write(reinterpret_cast<const char *>(memory.data()),memory.size());
  const bool passed=ticks<2000000 && !faults && GPU_UCMD_COMPLETION_SUCCESS(completion) && (completion&255)==91;
  printf("{\"passed\":%s,\"ticks\":%llu,\"explicit_ticks\":%u,\"seconds\":%.9f,\"reads\":%llu,\"writes\":%llu,\"read_bytes\":%llu,\"write_bytes\":%llu,\"faults\":%d,\"completion\":%llu,\"shader_config\":%llu}\n",
    passed?"true":"false",(unsigned long long)(end-begin),ticks,seconds,
    (unsigned long long)reads,(unsigned long long)writes,(unsigned long long)read_bytes,
    (unsigned long long)write_bytes,faults,(unsigned long long)completion,(unsigned long long)shader_config);
  return passed?0:1;
}
