#define main alignment_sequence_entry
#include "native_alignment_sequence_driver.cpp"
#undef main

template<class F> static void rejects(F action) {
  bool rejected=false;
  try { action(); } catch(const std::runtime_error &) { rejected=true; }
  require(rejected,"corrupted sequence fixture was accepted");
}
int main() {
  using namespace alignment_sequence;
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
