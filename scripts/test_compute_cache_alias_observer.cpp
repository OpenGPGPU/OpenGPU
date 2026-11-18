// Explicit CPU model/handshake fixtures. This does not warm an RTL model.
#include "compute_cache_alias_observer.hpp"
#include <cassert>
#include "cache_observer_fixture.inc"
#include <functional>
#include <iostream>
#include <sstream>
#include <vector>
using namespace compute_cache_alias;
constexpr uint32_t target=0x20000;

template<class F> void rejects(F action) {
  bool failed=false;try {action();}catch(const std::runtime_error &) {failed=true;}
  assert(failed);
}
void complete_geometry() {
  Fixture f;View<FakeTop,FakeCu> view(f.top);
  assert(!view.read(target).l1_mask && !view.read(target).l2);
  for(unsigned cu=0;cu<2;++cu)for(unsigned way=0;way<2;++way)for(unsigned index=0;index<64;++index) {
    const uint32_t line=0x12345000+index*64,tag=0x12345;
    auto &r=f.roots[cu];r={};set_l1(r,way,index,tag,true);
    const auto before=r;
    assert(view.read(line).l1_mask==(1u<<cu));
    assert(!std::memcmp(&before,&r,sizeof(r)));
    set_l1(r,way,index,tag,false);assert(!view.read(line).l1_mask);
    set_l1(r,way,index,tag^1,true);assert(!view.read(line).l1_mask);
    r={};set_l1(r,way,(index+1)%64,tag,true);assert(!view.read(line).l1_mask);r={};
  }
  const unsigned banks[4][4]={{0,1,2,3},{1,0,3,2},{2,3,0,1},{3,2,1,0}};
  for(unsigned page=0;page<4;++page)for(unsigned index=0;index<128;++index)for(unsigned way=0;way<4;++way) {
    const uint32_t line=0x24680000+page*8192+index*64,tag=0x12340+page,bank=banks[page][index%4];
    f.root={};set_handles(f);set_l2(f.root,bank,way,index,tag,true);
    const auto before=f.root;assert(view.read(line).l2);assert(!std::memcmp(&before,&f.root,sizeof(f.root)));
    set_l2(f.root,bank,way,index,tag,false);assert(!view.read(line).l2);
    set_l2(f.root,bank,way,index,tag^1,true);assert(!view.read(line).l2);
    f.root={};set_handles(f);set_l2(f.root,(bank+1)%4,way,index,tag,true);assert(!view.read(line).l2);
    f.root={};set_handles(f);set_l2(f.root,bank,way,(index+1)%128,tag,true);assert(!view.read(line).l2);
    f.root={};set_handles(f);set_l2(f.root,bank,way,index,tag|0xfff80000u,true);assert(view.read(line).l2);
  }
  f.root={};set_handles(f);
  for(unsigned cu=0;cu<2;++cu) {
    f.cus[cu].io_l1Invalidate_valid=f.cus[cu].io_l1Invalidate_ready=1;
    f.cus[cu].io_l1Invalidate_bits_lineAddress=target;
    f.cus[cu].io_l1InvalidateDone_valid=f.cus[cu].io_l1InvalidateDone_ready=1;
    f.cus[cu].io_l1InvalidateDone_bits_lineAddress=target;
  }
  assert(view.read(target).invalidate_mask==3 && view.read(target).ack_mask==3);
  f.cus[1].io_l1Invalidate_ready=f.cus[0].io_l1InvalidateDone_ready=0;
  assert(view.read(target).invalidate_mask==1 && view.read(target).ack_mask==2);
  f.cus[0].io_l1Invalidate_bits_lineAddress=target+64;
  assert(!view.read(target).invalidate_mask);
  f.cus[1].io_l1InvalidateDone_bits_lineAddress=target+64;
  assert(!view.read(target).ack_mask);
  f.top.io_m_axi_awvalid=f.top.io_m_axi_awready=1;f.top.io_m_axi_awaddr=target+64;
  f.top.io_m_axi_awid=4;f.top.io_m_axi_awlen=1;f.top.io_m_axi_awsize=5;
  f.top.io_m_axi_arvalid=f.top.io_m_axi_arready=1;f.top.io_m_axi_araddr=target;
  f.top.io_m_axi_arid=9;f.top.io_m_axi_arlen=1;f.top.io_m_axi_arsize=5;
  f.top.io_m_axi_bvalid=f.top.io_m_axi_bready=1;f.top.io_m_axi_bid=4;f.top.io_m_axi_bresp=2;
  f.top.io_m_axi_rvalid=f.top.io_m_axi_rready=f.top.io_m_axi_rlast=1;f.top.io_m_axi_rid=9;
  const auto ports=view.read(target);
  assert(ports.aw.fire && ports.aw.id==4 && ports.aw.address==target+64 && ports.aw.length==1 && ports.aw.size==5);
  assert(ports.ar.fire && ports.ar.id==9 && ports.ar.address==target && ports.ar.length==1 && ports.ar.size==5);
  assert(ports.b.fire && ports.b.id==4 && ports.b.response==2);
  assert(ports.r.fire && ports.r.id==9 && !ports.r.response && ports.r.last);
  f.root.GpuHostSystemAxi__DOT__system__DOT__computeUnits_1__DOT__handle___05FV=
    f.root.GpuHostSystemAxi__DOT__system__DOT__computeUnits_0__DOT__handle___05FV;
  rejects([&]{View<FakeTop,FakeCu> bad(f.top);});
}

std::vector<Snapshot> trace(bool failed,unsigned attempts) {
  std::vector<Snapshot> rows(4);
  rows[0].invalidate_mask=3; // cold prefix maintenance cannot qualify warmth
  rows[1].ack_mask=3;
  rows[2].l1_mask=3;rows[2].l2=true;
  rows[3]=rows[2];rows[3].invalidate_mask=3;
  Snapshot ack;ack.ack_mask=3;ack.l2=true;rows.push_back(ack);
  for(unsigned n=0;n<attempts;++n) {
    Snapshot write;write.l2=true;write.aw={true,7,target,0,false,1,5};rows.push_back(write);
    Snapshot response;response.l2=true;response.b={true,7,0,uint8_t(failed && !n?2:0),false};rows.push_back(response);
    if(failed && !n) {
      rows.push_back({});Snapshot read;read.ar={true,9,target,0,false,1,5};rows.push_back(read);
      Snapshot data;data.r={true,9,0,0,true};rows.push_back(data);
    }
    Snapshot refill;refill.l1_mask=uint8_t(failed?2:n%2?2:1);refill.l2=true;rows.push_back(refill);
  }
  return rows;
}
Tracker replay(const std::vector<Snapshot> &rows,bool failed) {
  Tracker tracker(target,failed);
  for(unsigned n=0;n<rows.size();++n)tracker.observe(n+1,rows[n]);
  return tracker;
}
void histories() {
  for(unsigned attempts:{3u,5u,7u}) {
    const auto rows=trace(true,attempts);const auto good=replay(rows,true);good.finish(attempts);
    rejects([&]{good.finish(attempts+1);});
    for(unsigned n=2;n<rows.size();++n) {
      auto bad=rows;bool changed=false;
      if(n==2) {bad[2].l1_mask=1;bad[3].l1_mask=1;changed=true;}
      else if(n==3) {bad[n].invalidate_mask=1;changed=true;}
      else if(n==4) {bad[n].ack_mask=1;changed=true;}
      else if(rows[n].aw.fire && n==5) {bad[n].l1_mask=1;changed=true;}
      else if(rows[n].b.response) {bad[n].b.response=0;changed=true;}
      else if(rows[n].ar.fire) {bad[n].l2=true;changed=true;}
      else if(rows[n].r.fire) {bad[n].r.last=false;changed=true;}
      if(changed)rejects([&]{replay(bad,true).finish(attempts);});
    }
    auto no_refill=rows;for(unsigned n=5;n<no_refill.size();++n)no_refill[n].l1_mask=0;
    rejects([&]{replay(no_refill,true).finish(attempts);});
    auto missing_b=rows;missing_b[6].b.fire=false;
    rejects([&]{replay(missing_b,true).finish(attempts);});
    auto short_read=rows;short_read[8].ar.length=0;
    rejects([&]{replay(short_read,true).finish(attempts);});
    auto short_write=rows;short_write[5].aw.size=2;
    rejects([&]{replay(short_write,true).finish(attempts);});
  }
  auto two_beats=trace(true,3);auto beat=two_beats[9];beat.r.last=false;
  two_beats.insert(two_beats.begin()+9,beat);replay(two_beats,true).finish(3);
  auto reordered=trace(true,3);
  for(unsigned n=9;n<reordered.size();++n)reordered[n].l1_mask=0;
  auto extra=reordered[8];extra.ar.id=10;
  Snapshot earlier;earlier.r={true,10,0,0,true};Snapshot early_fill;early_fill.l1_mask=2;early_fill.l2=true;
  reordered.insert(reordered.begin()+9,{extra,earlier,early_fill});
  rejects([&]{replay(reordered,true).finish(3);});
  const auto healthy=trace(false,8);replay(healthy,false).finish(8);
  auto one_cu=healthy;for(unsigned n=5;n<one_cu.size();++n)if(one_cu[n].l1_mask)one_cu[n].l1_mask=1;
  rejects([&]{replay(one_cu,false).finish(8);});
  auto foreign_error=healthy;foreign_error[6].b.response=2;
  rejects([&]{replay(foreign_error,false).finish(8);});
  auto same_ack=healthy;same_ack[4].ack_mask=0;same_ack[5].ack_mask=3;
  rejects([&]{replay(same_ack,false).finish(8);});
  Tracker repeated(target,false);repeated.observe(7,{});rejects([&]{repeated.observe(7,{});});
  for(uint64_t line:{uint64_t(0),uint64_t(1),uint64_t(0x100000000)})rejects([&]{Tracker bad(line,false);});
  replay(trace(true,3),true).json(std::cout,42,0,3);
  replay(healthy,false).json(std::cout,42,1,8);
}

struct HookModel {Snapshot snapshot;};
struct HookBase {
  std::unique_ptr<HookModel> model=std::make_unique<HookModel>();
  unsigned edges=0;
  template<class Agent,class Control> void step(Agent &agent,uint64_t,Control control) {
    control(*model);++agent;++edges;model->snapshot={};
  }
};
struct HookView {
  const HookModel &model;
  explicit HookView(const HookModel &m):model(m){}
  Snapshot read(uint32_t)const{return model.snapshot;}
};
void preedge_hook() {
  ObservedSimulation<HookBase,HookView> simulation;Tracker tracker(target,true);simulation.attach(tracker);
  rejects([&]{simulation.attach(tracker);});unsigned agent=0,controls=0;
  const auto rows=trace(true,3);
  for(unsigned index=0;index<rows.size();++index) {
    simulation.model->snapshot=rows[index];
    if(index%2)simulation.step(agent,index+1,[&](const auto &){assert(agent==index);++controls;});
    else simulation.step(agent,index+1);
  }
  assert(agent==rows.size() && simulation.edges==rows.size() && controls==rows.size()/2);
  tracker.finish(3);simulation.detach();simulation.step(agent,rows.size()+1);
}
int main() {complete_geometry();histories();preedge_hook();}
