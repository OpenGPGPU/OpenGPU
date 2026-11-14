// CPU MMIO peer only. Injected receipts cannot qualify GPU shader execution.
#include "native_sample_owner_runtime.hpp"
#include <sstream>

using namespace native_owner;
template<class F> static void rejects(F f) {
  bool bad=false;try{f();}catch(const std::runtime_error &){bad=true;}
  require(bad,"invalid sample owner transport accepted");
}
static std::vector<Command> discovery() {
  auto words=SampleCapture::profile();
  for(auto word:std::array<Command,5>{{{0,0x47550002},{0x60,required_owner_caps|(128u<<8)},
      {0x68,0x160},{0x320,1048576},{0x158,0x02040810}}})words.push_back(word);
  return words;
}
static SampleCapture fixture() {
  SampleCapture c;c.max_cycles=1000000;c.hold_cycles=16;c.command_id=41;c.discovery=discovery();
  c.writable={{0x2000,0x2040}};
  auto write=[&](uint32_t a,uint32_t v){c.trace.push_back({Kind::write,a,v,0});};
  write(GPU_REG_UCMD_VECTOR_SATP,0x80400400);write(GPU_REG_UCMD_INSTRUCTION_SATP,0x80400400);
  write(GPU_REG_UCMD_ID,41);write(GPU_REG_UCMD_OPCODE,GPU_UCMD_OP_NATIVE_BINNED_RENDER);
  write(GPU_REG_UCMD_SOURCE,0x1000000);write(GPU_REG_UCMD_BYTES,512);
  write(GPU_REG_UCMD_DESTINATION,0x1000200);write(GPU_REG_UCMD_PATTERN,0);
  write(GPU_REG_UCMD_FLAGS,0);write(GPU_REG_UCMD_SUBMIT,1);
  c.checks.push_back({GPU_REG_UCMD_COMPLETION,0xffffffffu,0x8729});
  for(unsigned n=0;n<12;n++)c.checks.push_back({0x180+4*n,n==2||n==3?0u:0xffffffffu,
    n==0?0x474e5801u:n==6?7u:n==7?8u:n==8?9u:0u});
  c.validate(true);return c;
}
static std::string encode(const SampleCapture &c) {
  std::ostringstream out;out.write("OGCAP002",8);write_le(out,c.max_cycles,8);
  write_le(out,c.hold_cycles,4);write_le(out,c.command_id,4);
  auto commands=[&](const auto &v){write_le(out,v.size(),4);for(const auto &w:v){write_le(out,w.address,4);write_le(out,w.value,4);}};
  commands(c.discovery);write_le(out,c.writable.size(),4);
  for(const auto &w:c.writable){write_le(out,w.first,8);write_le(out,w.end,8);}
  write_le(out,c.trace.size(),4);
  for(const auto &a:c.trace)for(uint32_t word:{uint32_t(a.kind),a.address,a.value,a.mask})write_le(out,word,4);
  write_le(out,c.checks.size(),4);
  for(const auto &check:c.checks)for(uint32_t word:{check.address,check.mask,check.expected})write_le(out,word,4);
  commands(c.finish);return out.str();
}
class SamplePeer:public Simulation {
public:
  std::vector<uint32_t> reads;
  SamplePeer(int argc,char **argv):Simulation(argc,argv) {}
  void reset() {
    Simulation::reset();model->mmio_enabled=true;model->issue_read=false;
    for(const auto &w:discovery())model->registers[w.address]=w.value;
  }
  template<class Observe> void step(Agent &agent,uint64_t cycle,Observe observe) {
    Command write{};bool wrote=false;
    Simulation::step(agent,cycle,[&](const auto &m){
      if(m.io_s_axi_arvalid && m.io_s_axi_arready)reads.push_back(m.io_s_axi_araddr);
      if(m.io_s_axi_awvalid && m.io_s_axi_awready && m.io_s_axi_wvalid && m.io_s_axi_wready) {
        write={m.io_s_axi_awaddr,m.io_s_axi_wdata};wrote=true;
      }
      observe(m);
    });
    if(!wrote)return;
    auto &r=model->registers;
    if(write.address==GPU_REG_UCMD_SUBMIT) {
      r[GPU_REG_UCMD_STATUS]=GPU_UCMD_STATUS_READY|GPU_UCMD_STATUS_COMPLETION;
      r[GPU_REG_UCMD_COMPLETION]=0x8700u|r[GPU_REG_UCMD_ID];
      r[GPU_REG_UCMD_COMPLETION_BYTES_LO]=64;r[GPU_REG_UCMD_COMPLETION_BYTES_HI]=1;
      for(const auto &check:fixture().checks)r[check.address]=check.expected;
      r[0x188]=0x12345678;r[0x18c]=0x89abcdef;r[GPU_REG_IRQ]=3;model->io_m_irq=1;
    }else if(write.address==GPU_REG_UCMD_COMPLETION_POP) {
      r[GPU_REG_UCMD_STATUS]=1;model->io_m_irq=0;
      r[GPU_REG_UCMD_COMPLETION_BYTES_LO]=r[GPU_REG_UCMD_COMPLETION_BYTES_HI]=0;
      for(unsigned n=0;n<12;n++)r[0x180+4*n]=0;
    }else if(write.address==GPU_REG_IRQ){r[GPU_REG_IRQ]=write.value&1;model->io_m_irq=0;}
  }
};
int main(int argc,char **argv) {
  auto c=fixture();const auto bytes=encode(c);
  {std::istringstream in(bytes);SampleCapture::read(in,true);}
  for(size_t n=0;n<bytes.size();n++) {
    std::istringstream in(bytes.substr(0,n));rejects([&]{SampleCapture::read(in,true);});
  }
  {std::istringstream in(bytes+"x");rejects([&]{SampleCapture::read(in,true);});}
  {std::istringstream in(bytes);rejects([&]{Capture::read(in,true);});}
  {auto old=bytes;old[7]='1';std::istringstream in(old);rejects([&]{SampleCapture::read(in,true);});
    std::istringstream legacy(old);rejects([&]{Capture::read(legacy,true);});}
  for(unsigned n=0;n<112;n++) {
    auto bad=c;bad.discovery[n].value^=1;rejects([&]{bad.validate(true);});
    Memory m;SamplePeer peer(argc,argv);peer.reset();peer.model->registers[c.discovery[n].address]^=1;
    Agent agent(m);ReplayBus<SamplePeer> bus(peer,agent,100000);rejects([&]{probe_sample_profile(peer,agent,bus);});
    if(n==2)require(peer.reads==std::vector<uint32_t>({0,0x60,0x360,0x364,0x368}),
      "sample probe read dependent windows after presence rejection");
  }
  for(unsigned n=1;n<c.discovery.size();n++) {
    auto bad=c;std::swap(bad.discovery[n-1],bad.discovery[n]);rejects([&]{bad.validate(true);});
  }
  {auto bad=c;bad.discovery.pop_back();rejects([&]{bad.validate(true);});}
  {auto bad=c;bad.checks[0].expected&=~0x8000u;rejects([&]{bad.validate(true);});}
  {auto bad=c;bad.checks[2].expected=1;rejects([&]{bad.validate(true);});}
  {auto bad=c;bad.command_id=0;rejects([&]{bad.validate(true);});}
  {auto bad=c;bad.checks[5].mask=1;rejects([&]{bad.validate(true);});}
  Memory memory;memory.ranges={{0x1000,0x4000,0x57}};
  SamplePeer peer(argc,argv);peer.reset();Agent agent(memory);ReplayBus<SamplePeer> bus(peer,agent,100000);
  const auto profile=probe_sample_profile(peer,agent,bus);
  auto held=hold_record(peer,agent,bus,memory,c,true);
  require(held.result.gnx[2]==0x12345678 && held.result.gnx[3]==0x89abcdef &&
    held.result.append_read_bytes==UINT64_C(0x100000040),"sample receipt substituted expected raw reader/telemetry values");
  Retirement retirement;retirement.actions.push_back({Kind::poll,GPU_REG_UCMD_STATUS,2,2});
  for(unsigned n=0;n<12;n++)retirement.actions.push_back({Kind::check,0x180+4*n,held.result.gnx[n],0xffffffffu});
  for(auto item:std::array<Command,3>{{{GPU_REG_UCMD_COMPLETION,0x8729},
      {GPU_REG_UCMD_COMPLETION_BYTES_LO,64},{GPU_REG_UCMD_COMPLETION_BYTES_HI,1}}})
    retirement.actions.push_back({Kind::check,item.address,item.value,0xffffffffu});
  retirement.actions.push_back({Kind::write,GPU_REG_UCMD_COMPLETION_POP,1,0});
  retirement.actions.push_back({Kind::write,GPU_REG_IRQ,3,0});
  const auto result=retire_record(peer,agent,bus,memory,c,held,&retirement);
  require(result.retired_status==1 && result.retired_irq==1 && !result.retired_irq_asserted,
    "sample retirement lost actual MMIO status");
  if(argc==2 && std::string(argv[1])=="--json") {
    sample_profile_json(std::cout,profile);sample_receipt_json(std::cout,result,c.command_id,bus.cycles);
    sample_receipt_json(std::cout,result,c.command_id,bus.cycles,true);
  }else std::cout<<"native sample owner CPU transport guards: PASS\n";
  return 0;
}
