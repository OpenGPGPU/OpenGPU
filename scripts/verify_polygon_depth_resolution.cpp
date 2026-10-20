#include <cmath>
#include <cstdint>
#include <cstring>
#include <cfenv>
#include <iostream>

static float number(uint32_t b) { float v; std::memcpy(&v,&b,4); return v; }
static uint32_t bits(float v) { uint32_t b; std::memcpy(&b,&v,4); return b; }
static uint32_t quant(float v, uint32_t n) {
  if(v<=0) return 0;
  if(v>=1) return n;
  return static_cast<uint32_t>(std::nearbyint(static_cast<double>(v)*n));
}
static float first(uint32_t code,uint32_t n) {
  if(code==0) return 0;
  uint32_t b=bits(static_cast<float>((static_cast<double>(code)-0.5)/n));
  while(quant(number(b),n)<code) ++b;
  while(b && quant(number(b-1),n)==code) --b;
  return number(b);
}
static float last(uint32_t code,uint32_t n) {
  if(code==n) return 1;
  uint32_t b=bits(static_cast<float>((static_cast<double>(code)+0.5)/n));
  while(quant(number(b),n)>code) --b;
  while(b<bits(1.f) && quant(number(b+1),n)==code) ++b;
  return number(b);
}
static bool verify(unsigned precision,uint32_t resolution) {
  const uint32_t n=(uint32_t(1)<<precision)-1; const float r=number(resolution);
  uint64_t checks=0;
  for(uint32_t code=0;code<=n;++code) {
    const float lo=first(code,n),hi=last(code,n);
    if(quant(lo,n)!=code || quant(hi,n)!=code) std::abort();
    if(static_cast<double>(lo)+r<=1) {
      volatile float shifted=lo+r;
      ++checks;
      if(quant(shifted,n)==code) {
        std::cout<<"FAIL bits="<<precision<<" r=0x"<<std::hex<<resolution<<" z=0x"<<bits(lo)<<std::dec<<" code="<<code<<" direction=positive\n";
        return false;
      }
    }
    if(static_cast<double>(hi)-r>=0) {
      volatile float shifted=hi-r;
      ++checks;
      if(quant(shifted,n)==code) {
        std::cout<<"FAIL bits="<<precision<<" r=0x"<<std::hex<<resolution<<" z=0x"<<bits(hi)<<std::dec<<" code="<<code<<" direction=negative\n";
        return false;
      }
    }
  }
  std::cout<<"PASS bits="<<precision<<" r=0x"<<std::hex<<resolution<<std::dec<<" cells="<<(uint64_t(n)+1)<<" checks="<<checks<<" positiveAndNegative=true\n";
  return true;
}
int main() {
  if(std::fesetround(FE_TONEAREST)!=0) return 2;
  const bool a=verify(16,0x37804001);
  const bool b=verify(16,0x37804000);
  const bool c=verify(24,0x33c00001);
  const bool d=verify(24,0x33c00000);
  const bool e=verify(24,0x33bfffff);
  return a && !b && c && d && !e ? 0 : 1;
}
