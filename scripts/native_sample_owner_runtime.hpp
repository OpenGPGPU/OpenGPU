#pragma once
#include "native_sample_owner_capture.hpp"
#include "native_owner_runtime.hpp"
#include <sstream>

namespace native_owner {
template<class Sim>
ProfileObservation probe_sample_profile(Sim &sim,Agent &agent,ReplayBus<Sim> &bus) {
  ProfileObservation result;result.factory=OwnerFactory::geometry_compute_sample;
  const auto id=bus.read(GPU_REG_ID),caps=bus.read(GPU_REG_CAPABILITIES);
  require((id&0xffff0000u)==0x47550000u && (id&0xffffu)>=GPU_ABI_COMPUTE_RESOURCE_VERSION &&
    (caps&required_owner_caps)==required_owner_caps,"sample profile requires a protected command ABI");
  for(const auto &word:SampleCapture::profile()) {
    const auto actual=bus.read(word.address);
    require(actual==word.value,"actual DUT lacks the exact complete sample owner");
    result.words.push_back({word.address,actual});
  }
  result.registers={{GPU_REG_ID,id},{GPU_REG_CAPABILITIES,caps}};
  for(uint32_t address:std::array<uint32_t,3>{{GPU_REG_BIN_CAPABILITY_PTR,0x320,GPU_REG_SHADER_CONFIG}})
    result.registers.push_back({address,bus.read(address)});
  auto discovery=result.words;discovery.insert(discovery.end(),result.registers.begin(),result.registers.end());
  SampleCapture::validate_discovery(discovery);
  require(!(bus.read(GPU_REG_UCMD_STATUS)&GPU_UCMD_STATUS_COMPLETION) && !sim.model->io_m_irq &&
    agent.drained() && !agent.traffic.reads && !agent.traffic.writes,"sample probe encountered active work");
  return result;
}
inline void sample_profile_json(std::ostream &out,const ProfileObservation &profile) {
  require(profile.factory==OwnerFactory::geometry_compute_sample,"sample profile factory changed");
  auto discovery=profile.words;discovery.insert(discovery.end(),profile.registers.begin(),profile.registers.end());
  SampleCapture::validate_discovery(discovery);
  out<<"{\"schema\":\"opengpu-native-sample-owner-profile-v1\",\"ownerProfile\":\""
    <<owner_factory_id(profile.factory)<<"\",\"ownerResourceStage\":\"geometry-compute-sample\","
    "\"ownerResourceVersion\":2,\"fullResourceQualification\":false,\"words\":[";
  for(size_t n=0;n<profile.words.size();++n)out<<(n?",":"")<<"{\"address\":"
    <<profile.words[n].address<<",\"value\":"<<profile.words[n].value<<"}";
  out<<"],\"registers\":[";
  for(size_t n=0;n<profile.registers.size();++n)out<<(n?",":"")<<"{\"address\":"
    <<profile.registers[n].address<<",\"value\":"<<profile.registers[n].value<<"}";
  out<<"],\"resetCount\":1,\"sourceQualificationRequired\":true,\"gpuExecution\":false,"
    "\"clockQualified\":false,\"fpsQualified\":false}\n";
}
inline void sample_receipt_json(std::ostream &out,const RecordResult &result,uint32_t command_id,
    uint64_t cycles,bool retired=false) {
  std::ostringstream base;
  if(retired)report_record(result,0,command_id,cycles,base);
  else held_json(base,result,0,command_id);
  auto text=base.str();require(text.size()>=2 && text.substr(text.size()-2)=="}\n","invalid native receipt encoding");
  text.resize(text.size()-2);
  out<<text<<",\"schema\":\"opengpu-native-sample-owner-"<<(retired?"retired":"held")<<"-v1\","
    "\"ownerProfile\":\"two-cu-vga60-owner-v2-sample-stage\",\"resetCount\":1";
  if(!retired)out<<",\"clockQualified\":false,\"fpsQualified\":false";
  out<<"}\n";
}
template<class Sim>
void active_sample_owner(Sim &sim,Agent &agent,ReplayBus<Sim> &bus,Memory &memory,
    const SampleCapture &capture,const std::filesystem::path &output,const std::filesystem::path &session,
    std::chrono::milliseconds timeout) {
  capture.validate(true);
  const auto profile=probe_sample_profile(sim,agent,bus);match_profile(capture,profile);
  PtePages pte;pte.observe(memory,capture);
  auto held=hold_record(sim,agent,bus,memory,capture,true);
  atomic_json(session/"record-0.held.json",[&](auto &out){sample_receipt_json(out,held.result,capture.command_id,bus.cycles);});
  wait_input(session/"record-0.retire",session,timeout);
  const auto retirement=Retirement::read((session/"record-0.retire").string());
  const auto result=retire_record(sim,agent,bus,memory,capture,held,&retirement);
  memory.write((output/"record-0.memory").string());
  atomic_json(session/"record-0.retired.json",[&](auto &out){sample_receipt_json(out,result,capture.command_id,bus.cycles,true);});
  // The live producer may request PTE release only after the controller has
  // independently checked stored samples, images, GNY2 and all original bytes.
  cleanup_records(sim,agent,bus,memory,session,pte,1,timeout);
}
} // namespace native_owner
