// Three bounded native LINE_LOOP submissions on the complete API-capacity host.
// The fixed CPU diamond-exit oracle is independent of the generated RTL.
#include "native_host_simulation.hpp"
#include "native_api_frame_fixture.hpp"
#include "opengpu_native_binned_line_loop_fixture.h"
#include "gpu_abi.h"
#include <filesystem>
#include <iostream>
#include <set>
#include <tuple>

using namespace native_frame;
namespace fs = std::filesystem;

int main(int argc, char **argv) {
  try {
    require(argc == 3, "usage: native_binned_line_loop_driver API_FIXTURES OUTPUT_DIR");
    const fs::path fixtures(argv[1]), output(argv[2]);
    require(!fs::exists(output), "output exists"); fs::create_directories(output);
    const auto contract=ApiFixture::read((fixtures/"native-api-extent-1x.commands").string(),true);
    Simulation sim(argc,argv); sim.reset();
    std::map<uint32_t,uint32_t> discovery;
    for(const auto &r:contract.resources){
      const uint32_t actual=sim.read(r.address);
      require(actual==r.value,"host discovery differs");
      require(discovery.emplace(r.address,actual).second,"duplicate discovery address");
    }
    // Check the capacities independently of the supplied fixture values.
    require(discovery.at(0x360)==0x47435201u && discovery.at(0x364)==16 &&
      discovery.at(0x36c)==4 && discovery.at(0x370)==16 && discovery.at(0x374)==8 &&
      discovery.at(0x378)==128 && discovery.at(0x398)==131072,"full compute profile required");
    require(discovery.at(0x160)==0x474e4303u && discovery.at(0x164)==8 &&
      discovery.at(0x174)==0x08000800u,"2048-axis API-capacity host required");
    const uint32_t layout=discovery.at(0x170), storage=discovery.at(0x178), payload=discovery.at(0x17c);
    require(((layout>>16)&31)==16 && ((layout>>21)&31)==16 && ((layout>>26)&31)==16 &&
      (storage&0x1ffff)==32768 && ((storage>>21)&7)==4 && ((storage>>24)&7)==4 &&
      ((storage>>27)&7)==4 && (payload&255)==4 && ((payload>>16)&31)==19 &&
      ((payload>>12)&7)==5 && ((payload>>15)&1),"full native storage and interface profile required");
    sim.write(GPU_REG_IRQ,1);
    for(unsigned index=0;index<3;++index){
      opengpu_ll_fixture f{};require(!opengpu_ll_build(&f,index),"fixture allocation failed");
      // Retain the full four-engine host and provide all four disjoint scratch views.
      auto *frame=f.memory+OPENGPU_LL_FRAME,*state=f.memory+OPENGPU_LL_STATE;
      opengpu_ll_put(state,OPENGPU_TYPED_FRAGMENT_STATE_ENGINECOUNT_WORD,4);
      const unsigned fw[2][2]={{OPENGPU_NATIVE_BINNED_FRAME_FRAME_SCRATCH2_BASE_WORD,OPENGPU_NATIVE_BINNED_FRAME_FRAME_SCRATCH2_LIMIT_WORD},
        {OPENGPU_NATIVE_BINNED_FRAME_FRAME_SCRATCH3_BASE_WORD,OPENGPU_NATIVE_BINNED_FRAME_FRAME_SCRATCH3_LIMIT_WORD}};
      const unsigned sw[2][4]={{OPENGPU_TYPED_FRAGMENT_STATE_SCRATCH1_BASE_WORD,OPENGPU_TYPED_FRAGMENT_STATE_SCRATCH1_LIMIT_WORD,
        OPENGPU_TYPED_FRAGMENT_STATE_SCRATCH1_ALLOCATIONBASE_WORD,OPENGPU_TYPED_FRAGMENT_STATE_SCRATCH1_ALLOCATIONLIMIT_WORD},
        {OPENGPU_TYPED_FRAGMENT_STATE_SCRATCH2_BASE_WORD,OPENGPU_TYPED_FRAGMENT_STATE_SCRATCH2_LIMIT_WORD,
        OPENGPU_TYPED_FRAGMENT_STATE_SCRATCH2_ALLOCATIONBASE_WORD,OPENGPU_TYPED_FRAGMENT_STATE_SCRATCH2_ALLOCATIONLIMIT_WORD}};
      Memory memory;
      std::vector<std::pair<uint64_t,uint64_t>> writable;
      for(const auto &r:opengpu_ll_regions){
        memory.ranges.push_back({f.base+r.offset,f.base+r.offset+r.bytes,0});
        if(r.writable)writable.push_back({f.base+r.offset,f.base+r.offset+r.bytes});
      }
      for(unsigned n=0;n<2;++n){
        const uint32_t start=f.base+0x60000+n*0x10000,end=start+0x10000;
        opengpu_ll_put(frame,fw[n][0],start);opengpu_ll_put(frame,fw[n][1],end);
        for(unsigned k=0;k<4;++k)opengpu_ll_put(state,sw[n][k],k%2?end:start);
        memory.ranges.push_back({start,end,0});writable.push_back({start,end});
      }
      std::sort(memory.ranges.begin(),memory.ranges.end(),[](const Memory::Range&a,const Memory::Range&b){return a.start<b.start;});
      for(size_t n=1;n<memory.ranges.size();++n)require(memory.ranges[n-1].end<=memory.ranges[n].start,"overlapping fixture allocation");
      for(unsigned n=0;n<OPENGPU_LL_MEMORY_BYTES;++n)if(f.memory[n])memory.byte(f.base+n,f.memory[n]);
      memory.write((output/("input-"+std::to_string(index)+".memory")).string());
      Agent agent(memory);std::set<uint64_t> reads;
      bool have_aw=false;uint64_t aw=0;unsigned beats=0;std::vector<Requests> writes;
      auto monitor=[&](const Requests &r,const Responses &s){
        if(r.arvalid&&s.arready)reads.insert(r.araddr);
        if(r.awvalid&&s.awready){require(!have_aw,"duplicate AW");have_aw=true;aw=r.awaddr;beats=r.awlen+1;}
        if(r.wvalid&&s.wready)writes.push_back(r);
        if(have_aw&&!writes.empty()&&writes.back().wlast){
          require(writes.size()==beats,"write beat count differs");
          for(unsigned n=0;n<writes.size();++n)for(unsigned b=0;b<32;++b)if((writes[n].wstrb>>b)&1){
            const uint64_t a=aw+n*32+b;
            require(std::any_of(writable.begin(),writable.end(),[&](const auto &span){return a>=span.first&&a<span.second;}),"write outside writable spans");
          }
          have_aw=false;writes.clear();
        }
      };
      sim.write(GPU_REG_UCMD_ID,f.command_id);sim.write(GPU_REG_UCMD_OPCODE,7);
      sim.write(GPU_REG_UCMD_SOURCE,f.base);sim.write(GPU_REG_UCMD_DESTINATION,f.base+512);
      sim.write(GPU_REG_UCMD_BYTES,512);sim.write(GPU_REG_UCMD_PATTERN,0);sim.write(GPU_REG_UCMD_FLAGS,0);sim.write(GPU_REG_UCMD_SUBMIT,1);
      uint64_t cycles=0;
      while(!sim.model->io_m_irq&&cycles<600000){
        const auto response=agent.drive(cycles);sim.drive(response);
        monitor(sim.requests(),response);sim.step(agent,cycles++);
      }
      sim.pause_memory();memory.write((output/("returned-"+std::to_string(index)+".memory")).string());
      const uint32_t completion=sim.read(GPU_REG_UCMD_COMPLETION);
      std::array<uint32_t,12> details{};for(unsigned n=0;n<12;++n)details[n]=sim.read(0x180+4*n);
      std::ofstream diag(output/("observations-"+std::to_string(index)+".txt"));
      diag<<"cycles "<<cycles<<" completion "<<std::hex<<completion<<"\n";
      for(const auto word:details)diag<<word<<' ';diag<<"\nreads\n";for(const auto address:reads)diag<<address<<'\n';diag.close();
      require(cycles<600000,"cycle limit");require(agent.drained()&&!have_aw&&writes.empty(),"traffic did not drain");
      require(!agent.traffic.read_faults&&!agent.traffic.write_faults,"unmapped traffic");
      require((completion&255)==f.command_id&&GPU_UCMD_COMPLETION_OPCODE(completion)==7,"command identity differs");
      require(GPU_UCMD_COMPLETION_STATUS(completion)==f.expected_status,"unexpected status");
      require(GPU_UCMD_COMPLETION_SUCCESS(completion)==(f.expected_status==0),"success bit differs");
      require(details[0]==0x474e5801u&&(details[1]&7)==f.expected_status&&details[11]==0,"native detail status differs");
      require(details[6]==7&&details[7]==8&&details[8]==f.generation,"native identity differs");
      if(index==1){
        require(((details[1]>>3)&3)==3&&details[2]==f.base+OPENGPU_LL_INDICES&&details[3]==0,"bounds attribution differs");
        for(const auto a:reads)require(!(a>=f.base+0x4000&&a<f.base+0x5080),"invalid index view reached source data");
      }else{
        for(const unsigned offset:{0x4000u,0x6000u,0x7000u})require(std::any_of(reads.begin(),reads.end(),[&](uint64_t a){return a>=f.base+offset&&a<f.base+offset+64;}),"missing actual index or shader reads");
        require(agent.traffic.writes>0,"healthy draw wrote nothing");
      }
      for(const auto &r:opengpu_ll_regions)if(!r.writable)for(unsigned n=0;n<r.bytes;++n)
        require(memory.byte(f.base+r.offset+n)==f.memory[r.offset+n],"read-only input changed");
      for(unsigned n=0;n<OPENGPU_LL_IMAGE_BYTES;++n)require(memory.byte(f.base+OPENGPU_LL_IMAGE+n)==f.expected_image[n],"pixel or guard differs");
      std::cout<<"{\"submission\":"<<index<<",\"serviceCycles\":"<<cycles<<",\"status\":"<<f.expected_status<<",\"exactImageAndGuards\":true,\"trafficDrained\":true,\"computeUnits\":4,\"nativeEngines\":4}"<<std::endl;
      sim.write(GPU_REG_UCMD_COMPLETION_POP,1);sim.write(GPU_REG_IRQ,3);
      for(unsigned n=0;n<12;++n)require(sim.read(0x180+4*n)==0,"completion detail not released");
      opengpu_ll_destroy(&f);
    }
    sim.model->final();return 0;
  }catch(const std::exception&e){std::cerr<<"LINE_LOOP probe failed: "<<e.what()<<'\n';return 1;}
}
