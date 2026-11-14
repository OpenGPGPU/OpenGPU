#pragma once
#include "native_sample_owner_runtime.hpp"

namespace native_owner {
inline void sample_graphics_receipt_json(std::ostream &out,const RecordResult &result,
    size_t record,uint32_t command_id,uint64_t cycles,bool retired=false) {
  require(record<2,"ordinary112 receipt record must be zero or one");
  std::ostringstream base;
  if(retired)report_record(result,record,command_id,cycles,base);
  else held_json(base,result,record,command_id);
  auto text=base.str();
  require(text.size()>=2 && text.substr(text.size()-2)=="}\n","invalid ordinary112 receipt encoding");
  text.resize(text.size()-2);
  out<<text<<",\"schema\":\"opengpu-native-sample-graphics-"<<(retired?"retired":"held")
    <<"-v1\",\"ownerProfile\":\"two-cu-vga60-owner-v2-sample-stage\","
      "\"recordCount\":2,\"resetCount\":1";
  if(!retired)out<<",\"clockQualified\":false,\"fpsQualified\":false";
  out<<"}\n";
}

// A new fixed two-owner entry, rather than relabeling active_records (old96)
// or active_sample_owner (one point/sample owner). The same RAM, bus, and reset
// survive both records. Source/image/GNY2 admission belongs to the controller.
template<class Sim>
void active_sample_graphics_records(Sim &sim,Agent &agent,ReplayBus<Sim> &bus,Memory &memory,
    const SampleCapture &first,const std::filesystem::path &output,
    const std::filesystem::path &session,PtePages &pte,std::ostream &report=std::cout,
    std::chrono::milliseconds timeout=std::chrono::minutes(5)) {
  first.validate(true);
  const auto profile=probe_sample_profile(sim,agent,bus);
  std::set<uint32_t> commands,sources;
  uint32_t context=0;
  for(size_t record=0;record<2;record++) {
    const auto stem="record-"+std::to_string(record);
    if(record)wait_input(session/(stem+".capture"),session,timeout);
    const auto capture=record?SampleCapture::read((session/(stem+".capture")).string(),true):first;
    match_profile(capture,profile);
    std::map<uint32_t,uint32_t> staged;
    for(const auto &action:capture.trace)if(action.kind==Kind::write)staged[action.address]=action.value;
    const auto satp=staged.at(GPU_REG_UCMD_VECTOR_SATP);
    if(!record)context=satp;
    require(satp==context,"ordinary112 records changed their shared protected context");
    require(commands.insert(capture.command_id).second,"ordinary112 reused a command identity");
    require(sources.insert(staged.at(GPU_REG_UCMD_SOURCE)).second,"ordinary112 reused a private job owner");
    require(staged.at(GPU_REG_UCMD_FLAGS)==0,"ordinary112 triangle cannot discard raster or capture feedback");
    verify_readonly(memory,memory,capture.writable);
    pte.observe(memory,capture);
    auto held=hold_record(sim,agent,bus,memory,capture,true);
    atomic_json(session/(stem+".held.json"),[&](auto &out){
      sample_graphics_receipt_json(out,held.result,record,capture.command_id,bus.cycles);
    });
    wait_input(session/(stem+".retire"),session,timeout);
    const auto retirement=Retirement::read((session/(stem+".retire")).string());
    const auto result=retire_record(sim,agent,bus,memory,capture,held,&retirement);
    memory.write((output/(stem+".memory")).string());
    atomic_json(session/(stem+".retired.json"),[&](auto &out){
      sample_graphics_receipt_json(out,result,record,capture.command_id,bus.cycles,true);
    });
    sample_graphics_receipt_json(report,result,record,capture.command_id,bus.cycles,true);
  }
}
} // namespace native_owner
