#pragma once
// Original real Gallium/private-winsys inputs. Validation regenerates only a
// comparison program/map; execution must use the captured bytes unchanged.
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <stdexcept>
#include <string>
#include <vector>

namespace framebuffer_blit {
inline void require(bool value,const char *message) {if(!value)throw std::runtime_error(message);}
inline uint32_t read_word(std::istream &in) {
  uint32_t value=0;for(unsigned b=0;b<4;b++) {
    const auto byte=in.get();require(byte!=EOF,"truncated blit word");value|=uint32_t(uint8_t(byte))<<(8*b);
  }return value;
}
struct View { uint32_t handle,allocation,limit,access; };
// Versioned 24-word descriptor wire layout; no producer C header is needed
// by the runtime executor. The producer-checking subclass is CPU-only.
struct Descriptor {
  uint32_t version,bytes,flags,mask;
  uint32_t source_format,destination_format,source_width,source_height,source_pitch;
  uint32_t destination_width,destination_height,destination_pitch;
  int32_t source_x,source_y,source_w,source_h,destination_x,destination_y,destination_w,destination_h;
  int32_t scissor_x0,scissor_y0,scissor_x1,scissor_y1;
};
struct Packet {
  uint32_t context;
  std::array<uint32_t,3> grid,local;
  std::array<View,3> views;
  std::array<uint32_t,512> shader;
  std::vector<uint8_t> kernarg,source,destination,coordinates,expected;
  Descriptor descriptor{};

  static Packet read(const std::string &path) {
    std::ifstream in(path,std::ios::binary|std::ios::ate);
    require(bool(in),"cannot open actual-caller blit input");
    const auto size=in.tellg();require(size>=96 && size<=300000,"invalid blit input size");
    in.seekg(0);for(unsigned n=0;n<8;n++)require(in.get()=="OGBLT001"[n],"unknown blit capture magic");Packet f;
    auto word=[&](){return read_word(in);};
    require(word()==1,"unknown blit capture version");f.context=word();
    require(f.context && f.context<512 && word()==2048 && word()==64,"invalid blit context/program extent");
    for(auto &v:f.grid){v=word();}
    for(auto &v:f.local){v=word();}
    require(f.grid[0] && f.grid[0]<=8 && f.grid[1] && f.grid[1]<=8 && f.grid[2]==1 &&
      f.local==std::array<uint32_t,3>{1,1,1},"invalid blit launch dimensions");
    uint64_t bytes=96+2048+64;
    for(auto &v:f.views) {
      v={word(),word(),word(),word()};
      require(v.handle && v.handle<512 && v.limit && v.limit<=v.allocation &&
        v.allocation<=65536 && !(v.allocation%64) && !(v.limit%4),"invalid blit view extent");
      bytes+=v.allocation;
    }
    bytes+=f.views[1].allocation;
    require(bytes==uint64_t(size) && f.views[0].access==1 && f.views[1].access==3 && f.views[2].access==1,
      "blit payload length/permissions differ");
    require(f.views[2].handle!=f.views[0].handle && f.views[2].handle!=f.views[1].handle,
      "coordinate GEM aliases an image");
    for(auto &v:f.shader)v=word();
    auto take=[&](uint32_t n) {
      std::vector<uint8_t> data(n);in.read(reinterpret_cast<char *>(data.data()),n);
      require(bool(in),"short blit payload");return data;
    };
    f.kernarg=take(64);f.source=take(f.views[0].allocation);f.destination=take(f.views[1].allocation);
    f.coordinates=take(f.views[2].allocation);f.expected=take(f.views[1].allocation);
    require(in.peek()==EOF,"trailing blit input bytes");
    auto load=[](const auto &data,size_t offset) {
      require(offset+4<=data.size(),"short blit word");uint32_t value=0;
      for(unsigned b=0;b<4;b++){value|=uint32_t(data[offset+b])<<(8*b);}
      return value;
    };
    for(unsigned n=0;n<16;n++)require(load(f.kernarg,n*4)==(n<3?f.grid[n]:0),"captured kernarg differs");
    uint32_t fields[24];for(unsigned n=0;n<24;n++)fields[n]=load(f.coordinates,n*4);
    static_assert(sizeof(fields)==sizeof(f.descriptor),"descriptor ABI changed");
    std::memcpy(&f.descriptor,fields,sizeof(fields));
    f.admit();f.verify_oracle();return f;
  }

  void admit() const {
    const auto &d=descriptor;
    require(d.version>=1 && d.version<=3 && d.bytes==96 && !(d.flags&~(d.version==3?3u:1u)),
      "blit wire descriptor version/flags differ");
    require(d.source_width && d.source_height && d.destination_width && d.destination_height &&
      d.source_width<=16384 && d.source_height<=16384 && d.destination_width<=16384 && d.destination_height<=16384 &&
      !(d.source_pitch&3) && !(d.destination_pitch&3) && uint64_t(d.source_width)*4<=d.source_pitch &&
      uint64_t(d.destination_width)*4<=d.destination_pitch && d.source_w && d.source_h &&
      d.destination_w && d.destination_h && (!(d.flags&1) ||
      (d.scissor_x0<=d.scissor_x1 && d.scissor_y0<=d.scissor_y1)),"blit wire geometry differs");
    require(uint64_t(d.source_height-1)*d.source_pitch+d.source_width*4==views[0].limit &&
      uint64_t(d.destination_height-1)*d.destination_pitch+d.destination_width*4==views[1].limit,
      "blit wire image bounds differ");
    if(d.source_format>=5)require(d.version>=2 && (d.source_format==5 || d.source_format==6) &&
      d.source_format==d.destination_format && !(d.flags&2) && !(d.mask&~48u) &&
      (d.source_format!=6 || !(d.mask&32)),"blit wire depth format/mask differs");
    else require(d.source_format>=1 && d.destination_format>=1 && d.destination_format<=4 &&
      !(d.mask&~15u),"blit wire color format/mask differs");
    require(views[2].limit==(32+(grid[0]+grid[1])*((d.flags&2)?4u:1u))*4 && coordinates.size()>=128 &&
      std::none_of(coordinates.begin()+96,coordinates.begin()+128,[](uint8_t b){return b!=0;}),
      "blit wire coordinate extent/reserved differs");
    require(shader[0]==0x01007557 && std::count(shader.begin(),shader.end(),0x30500073u)==1,
      "blit wire program extent differs");
    const auto end=std::find(shader.begin(),shader.end(),0x30500073u);
    require(end-shader.begin()>29 && std::none_of(end+1,shader.end(),[](uint32_t w){return w!=0;}),
      "blit wire program padding differs");
    if(views[0].handle==views[1].handle)require(d.version>=2 && source==destination,
      "same-GEM original bytes differ");
  }

  // Independent direct affine pixel oracle: no prepared maps or production
  // coordinate/emitter helper chooses a tap or computes a color here.
  void verify_oracle() const {
    const auto &d=descriptor;auto result=destination;
    auto clamp=[](int64_t v,int64_t lo,int64_t hi){return std::max(lo,std::min(v,hi-1));};
    for(uint32_t y=0;y<d.destination_height;y++)for(uint32_t x=0;x<d.destination_width;x++) {
      const long double u=(x+0.5L-d.destination_x)/d.destination_w;
      const long double v=(y+0.5L-d.destination_y)/d.destination_h;
      if(u<0 || u>=1 || v<0 || v>=1 || !d.mask)continue;
      if((d.flags&1) && (int64_t(x)<d.scissor_x0 || int64_t(x)>=d.scissor_x1 ||
        int64_t(y)<d.scissor_y0 || int64_t(y)>=d.scissor_y1))continue;
      const auto cx=d.source_x+u*d.source_w,cy=d.source_y+v*d.source_h;
      const auto sx=int64_t(std::floor(cx)),sy=int64_t(std::floor(cy));
      if(sx<0 || sx>=d.source_width || sy<0 || sy>=d.source_height)continue;
      const size_t target=size_t(y)*d.destination_pitch+x*4;
      if(d.source_format>=5) {
        for(unsigned b=0;b<4;b++)if(d.mask&(b<3?16:32))result.at(target+b)=source.at(sy*d.source_pitch+sx*4+b);
        continue;
      }
      const bool sb=d.source_format==2 || d.source_format==4,db=d.destination_format==2 || d.destination_format==4;
      for(unsigned c=0;c<4;c++)if((d.mask&(1u<<c)) && (c!=3 || d.destination_format<3)) {
        const auto from=c==0&&sb?2:c==2&&sb?0:c,to=c==0&&db?2:c==2&&db?0:c;
        uint8_t value;
        if(c==3 && d.source_format>=3)value=255;
        else if(!(d.flags&2))value=source.at(sy*d.source_pitch+sx*4+from);
        else {
          const auto ix=int64_t(std::floor(cx-0.5L)),iy=int64_t(std::floor(cy-0.5L));
          const auto wx=uint64_t(std::floor((cx-0.5L-ix)*65536+0.5L));
          const auto wy=uint64_t(std::floor((cy-0.5L-iy)*65536+0.5L));
          const auto lx=std::max<int64_t>(0,d.source_w<0?int64_t(d.source_x)+d.source_w:d.source_x);
          const auto hx=std::min<int64_t>(d.source_width,d.source_w<0?d.source_x:int64_t(d.source_x)+d.source_w);
          const auto ly=std::max<int64_t>(0,d.source_h<0?int64_t(d.source_y)+d.source_h:d.source_y);
          const auto hy=std::min<int64_t>(d.source_height,d.source_h<0?d.source_y:int64_t(d.source_y)+d.source_h);
          uint64_t sum=0;
          for(unsigned j=0;j<2;j++)for(unsigned k=0;k<2;k++)
            sum+=uint64_t(source.at(clamp(iy+j,ly,hy)*d.source_pitch+clamp(ix+k,lx,hx)*4+from))*
              (k?wx:65536-wx)*(j?wy:65536-wy);
          value=uint8_t((sum+UINT64_C(0x80000000))>>32);
        }
        result.at(target+to)=value;
      }
    }
    require(result==expected,"captured expected GEM differs from independent affine pixel oracle");
  }
};
} // namespace framebuffer_blit
