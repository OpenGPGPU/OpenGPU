#define main alignment_sequence_entry
#include "native_alignment_sequence_driver.cpp"
#undef main
#include "native_owner_capture.hpp"

template<class F> static void rejects(F action) {
  bool rejected=false;
  try { action(); } catch(const std::runtime_error &) { rejected=true; }
  require(rejected,"corrupted sequence fixture was accepted");
}
int main(int argc,char **argv) {
  if(argc>1) {
    for(int i=1;i<argc;i++) {
      const auto fixture=native_frame::Fixture::read(argv[i]);
      native_frame::require(fixture.owner_profile(),"not an owner command fixture");
      auto path=std::filesystem::path(argv[i]);path.replace_extension(".memory");
      const auto memory=native_frame::Memory::read(path.string());
      native_frame::require(!memory.ranges.empty(),"owner recipe has no backing");
    }
    std::cout << "owner command roundtrip: PASS\n";
    return 0;
  }
  using namespace alignment_sequence;
  native_frame::Fixture owner{};
  owner.resources=native_owner::Capture::profile(); owner.validate_resources();
  require(owner.owner_profile() && !owner.alignment_v2(),"owner and frozen v2 profiles were conflated");
  for(size_t i=0;i<owner.resources.size();i++)for(bool address:{false,true}) {
    auto changed=owner;
    (address?changed.resources[i].address:changed.resources[i].value)^=1;
    rejects([&] {changed.validate_resources();});
  }
  auto legacy=owner;legacy.resources.resize(72);legacy.resources[2].value=0x7fff;
  legacy.validate_resources();
  require(legacy.alignment_v2() && !legacy.owner_profile(),"legacy v2 identity changed");
  rejects([&] {auto bad=owner;bad.resources[2].value=0x7fff;bad.validate_resources();});
  rejects([&] {auto bad=legacy;bad.resources[2].value=0x3ffff;bad.validate_resources();});
  Memory memory; memory.ranges.push_back({0x1000000,0x1001000,0}); seed(memory);
  const Memory initial=memory;
  for(unsigned epoch=0;epoch<2;++epoch) {
    const auto code=program(epoch);
    require(code.size()==16 && word(memory,pc(epoch)+60)==0x30500073,"wrong compute program extent");
    require(word(memory,pc(epoch)+20)==(((3+8*epoch)<<20)|(2<<15)|(12<<7)|0x13),"wrong epoch operand");
    for(unsigned item=0;item<512;++item) {
      put(memory,output(epoch)+item*4,item%128+item/128+3+epoch*8);
      if(item%16==0)put(memory,output(epoch)+0x1000+item*4,
        float_bits(2.0f+float((item/128)*8+(item%128)/16+epoch*32)/8.0f));
    }
    verify(memory,initial,epoch+1);
    for(uint32_t address : {pc(epoch),output(epoch)-4,output(epoch),output(epoch)+0x1004,
        output(epoch)+2048,output(epoch)+0x20000,uint32_t(low_end-1)}) {
      Memory changed=memory; changed.byte(address,changed.byte(address)^1);
      rejects([&] { verify(changed,initial,epoch+1); });
    }
  }
  require(word(memory,0x20000)==3 && word(memory,0x207fc)==133,"first vector oracle differs");
  require(word(memory,0x30000)==11 && word(memory,0x307fc)==141,"second vector oracle differs");
  require(word(memory,0x21000)==0x40000000 && word(memory,0x31000)==0x40c00000,"FP oracle differs");
  rejects([&] { seed(memory); }); rejects([&] { program(2); }); rejects([&] { verify(memory,initial,0); });
  std::cout << "alignment sequence CPU fixture/guard checks: PASS\n";
}
