// SPDX-License-Identifier: MIT
// Direct FILL validation against an already compiled ARTI model.
#include "arti_rtl_model.h"
#include "gpu_abi.h"
#include <cassert>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>
static std::vector<uint8_t> memory(4u<<20,0xa5);
static uint64_t reads,writes,read_bytes,write_bytes;
static constexpr uint32_t destination=0x100000;
static int rd(uint64_t a,uint8_t*d,unsigned n,uint64_t){if(a+n>memory.size())return -1;memcpy(d,memory.data()+a,n);reads++;read_bytes+=n;return 0;}
static int wr(uint64_t a,const uint8_t*d,unsigned n,uint64_t mask,uint64_t){if(a+n>memory.size())return -1;for(unsigned i=0;i<n;i++)if(mask>>i&1){memory[a+i]=d[i];write_bytes++;}writes++;return 0;}
static void regw(unsigned a,unsigned v){assert(!arti_rtl_model_write(a,v,4));}
static uint64_t regr(unsigned a){uint64_t v=0;assert(!arti_rtl_model_read(a,&v,4));return v;}
#ifdef FILL_PROBE
extern "C" void fill_probe_reset();
extern "C" void fill_probe_get(uint64_t*,unsigned);
extern "C" unsigned fill_probe_count();
#endif
int main(int n,char**v){
 assert(n==3);unsigned bytes=strtoul(v[1],nullptr,0),vm=atoi(v[2]);assert(bytes && !(bytes&63) && uint64_t(destination)+bytes+64<memory.size() && vm<=1);
 setenv("ARTI_MODEL_MMIO_ADVANCE","0",1);setenv("ARTI_MODEL_SETTLE_NS","0",1);
 arti_rtl_model_set_memory_callbacks(rd,wr);arti_rtl_model_init();
 const auto config=regr(GPU_REG_SHADER_CONFIG);
 if(vm){
  std::memset(memory.data()+0x4000,0,4096);std::memset(memory.data()+0x8000,0,4096);
  uint32_t root_entry=(8u<<10)|1;memcpy(memory.data()+0x4004,&root_entry,4);
  for(unsigned p=0;p<(bytes+4095)/4096;p++){uint32_t leaf=((destination/4096+p)<<10)|0xcf;memcpy(memory.data()+0x8000+4*p,&leaf,4);}
  regw(GPU_REG_UCMD_VECTOR_SATP,0x80400004);regw(GPU_REG_UCMD_TLB_FLUSH,1);
 }
 regw(GPU_REG_IRQ,3);regw(GPU_REG_UCMD_ID,17);regw(GPU_REG_UCMD_OPCODE,GPU_UCMD_OP_FILL);
 regw(GPU_REG_UCMD_DESTINATION,vm?0x400000:destination);regw(GPU_REG_UCMD_BYTES,bytes);regw(GPU_REG_UCMD_PATTERN,0x12345678);
 uint64_t before=0,after=0;arti_rtl_model_skip_stats(&before,nullptr,nullptr,nullptr);
#ifdef FILL_PROBE
 fill_probe_reset();
#endif
 regw(GPU_REG_UCMD_SUBMIT,1);
 unsigned polls=0;while(!(regr(GPU_REG_UCMD_STATUS)&GPU_UCMD_STATUS_COMPLETION)&&polls++<500000)arti_rtl_model_tick_n(1);
 assert(polls<500000);auto result=regr(GPU_REG_UCMD_COMPLETION);assert(GPU_UCMD_COMPLETION_SUCCESS(result)&&GPU_UCMD_COMPLETION_ID(result)==17);
 assert(regr(GPU_REG_UCMD_COMPLETION_BYTES_LO)==bytes);arti_rtl_model_skip_stats(&after,nullptr,nullptr,nullptr);
 for(unsigned i=0;i<bytes/4;i++){uint32_t word;memcpy(&word,memory.data()+destination+4*i,4);assert(word==0x12345678);}
 for(unsigned i=0;i<64;i++){assert(memory[destination-64+i]==0xa5);assert(memory[destination+bytes+i]==0xa5);}
 assert(write_bytes==bytes);
 printf("{\"shader_config\":%llu,\"bytes\":%u,\"vm\":%u,\"ticks\":%llu,\"reads\":%llu,\"writes\":%llu,\"read_bytes\":%llu,\"write_bytes\":%llu,\"pixels_and_canaries\":true",(unsigned long long)config,bytes,vm,(unsigned long long)(after-before),(unsigned long long)reads,(unsigned long long)writes,(unsigned long long)read_bytes,(unsigned long long)write_bytes);
#ifdef FILL_PROBE
 std::vector<uint64_t> c(fill_probe_count());fill_probe_get(c.data(),c.size());printf(",\"probe\":[");for(unsigned i=0;i<c.size();i++)printf("%s%llu",i?",":"",(unsigned long long)c[i]);printf("]");
#endif
 puts("}");
}
