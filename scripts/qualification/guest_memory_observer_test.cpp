/* SPDX-License-Identifier: MIT */
/* Synthetic pins/RAM exercise the observer, not an RTL model. */
#include "guest_memory_observer.h"
#include <cassert>
#include <cstdio>
#include <memory>
#include <utility>

using namespace opengpu_qualification;
static constexpr uint64_t root_pa = 0x80000000, l1_pa = root_pa+4096, data_pa = 0x90000000;
static constexpr uint32_t satp = 0x80480000;
static unsigned checks = 0;
template<class F> static void rejects(F f, const char *why) {
    bool failed = false;
    try { f(); } catch (const std::runtime_error &e) { failed = std::string(e.what()).find(why) != std::string::npos; }
    assert(failed); checks++;
}
static void put(std::array<uint8_t,4096> &p, unsigned n, uint32_t value) {
    for (unsigned i=0;i<4;i++) p[n*4+i]=uint8_t(value>>(8*i));
}
static uint32_t link(uint64_t physical) { return uint32_t(physical>>12)<<10|1; }
static uint32_t leaf(uint64_t physical) { return uint32_t(physical>>12)<<10|0x2cf; }
struct Fixture {
    std::map<uint64_t,std::array<uint8_t,4096>> ram;
    std::vector<Snapshot> snapshots;
    std::vector<Event> events;
    uint64_t calls = 0;
    std::unique_ptr<Observer> o;
    Fixture() {
        put(ram[root_pa],0,l1_pa>>12<<10|1);
        put(ram[l1_pa],1,leaf(data_pa));
        o.reset(new Observer(0x40000000,UINT64_C(1)<<32,
            [this](uint64_t address,uint8_t *bytes,unsigned size) {
                calls++; auto p=ram.find(address&~UINT64_C(4095));
                if (p==ram.end() || size>4096-(address&4095)) return false;
                for (unsigned i=0;i<size;i++) bytes[i]=p->second[(address&4095)+i];
                return true;
            },[this](const Snapshot &s) { snapshots.push_back(s); },
            [this](const Event &e) { events.push_back(e); }));
    }
    void edge(Bus m={},Bus d={}) { o->edge(m,d); }
    void write(uint32_t address,uint32_t value,bool w_first=false) {
        Bus b; b.aw={true,true,address,0,0,2}; b.w.valid=b.w.ready=true; b.w.strobe=15;
        for(unsigned i=0;i<4;i++) b.w.data[i]=uint8_t(value>>(8*i));
        Bus part=b;
        if(w_first) part.aw.valid=false; else part.w.valid=false;
        edge(part);
        if(w_first) b.w.valid=false; else b.aw.valid=false;
        edge(b);
        b={}; b.b.valid=b.b.ready=true; edge(b); edge();
    }
    void submit() { write(0x14c,satp); write(0x150,satp,true); write(0xc8,0); write(0x11c,1); }
};

static void snapshots() {
    Fixture f; f.submit(); f.o->finish();
    assert(f.snapshots.size()==1 && f.snapshots[0].tables.size()==1 && f.calls==128);
    assert(Observer::translate(f.snapshots[0],0x1234)==data_pa+0x234);
    assert(f.o->counts().observer_read_bytes==8192 && !f.o->counts().dut_read_bytes && !f.o->counts().ar);
    rejects([&] { (void)Observer::translate(f.snapshots[0],0x4000); },"unmapped");
    rejects([&] { (void)Observer::translate(f.snapshots[0],0x400000); },"private root");
    f.write(0x14c,0x80080000); f.write(0x150,0x80080000); f.write(0x11c,1);
    assert(f.o->counts().global_submits==1 && f.snapshots.size()==1 && f.calls==128); checks++;
    { Fixture bad; put(bad.ram[root_pa],1,link(root_pa)); rejects([&] { bad.submit(); },"duplicate/cyclic"); }
    { Fixture bad; put(bad.ram[root_pa],0,leaf(l1_pa)); rejects([&] { bad.submit(); },"private root non-link"); }
    { Fixture bad; put(bad.ram[root_pa],0,link(0xb000000)); rejects([&] { bad.submit(); },"outside actual RAM"); assert(bad.calls==64); }
    { Fixture bad; put(bad.ram[l1_pa],1,leaf(l1_pa)); rejects([&] { bad.submit(); },"table page exposed"); }
    { Fixture bad; put(bad.ram[l1_pa],1,leaf(0xb000000)); rejects([&] { bad.submit(); },"out-of-RAM"); }
    { Fixture bad; put(bad.ram[l1_pa],1,leaf(data_pa)|0x40000000); rejects([&] { bad.submit(); },"invalid"); }
    { Fixture bad; put(bad.ram[l1_pa],1,(leaf(data_pa)&~2u)); rejects([&] { bad.submit(); },"invalid"); }
    { Fixture bad; bad.ram.erase(l1_pa); rejects([&] { bad.submit(); },"RAM read failed"); }
    { Fixture bad; bad.write(0x14c,satp); bad.write(0x150,satp+1); rejects([&] { bad.write(0x11c,1); },"SATP pair"); }
    { Fixture bad; bad.write(0x14c,satp|(1u<<20)); bad.write(0x150,satp|(1u<<20)); rejects([&] { bad.write(0x11c,1); },"reserved SATP"); }
    { Fixture top; put(top.ram[root_pa],1023,link(l1_pa+4096)); put(top.ram[l1_pa+4096],1023,leaf(0xfffff000)); top.submit();
      assert(Observer::translate(top.snapshots[0],0xffffffff)==0xffffffff); checks++; }
    { Fixture full; full.ram.clear();
      for(unsigned r=0;r<384;r++) { uint64_t pa=l1_pa+4096*r; put(full.ram[root_pa],r,link(pa)); full.ram[pa]={}; }
      full.submit(); assert(full.snapshots[0].tables.size()==384 && full.calls==24640 && full.o->counts().observer_read_bytes==1576960);
      put(full.ram[root_pa],384,link(l1_pa+4096*384)); full.ram[l1_pa+4096*384]={};
      rejects([&] { full.write(0x11c,1); },"excessive private tables"); checks++; }
}

static Address addr(uint64_t physical,unsigned id=8,unsigned length=1,unsigned size=5) {
    return {true,true,physical,id,length,size};
}
static Reply response(unsigned id,bool last=true) { Reply r; r.valid=r.ready=true; r.id=id; r.last=last; return r; }
static void channels() {
    Fixture f;
    Bus d; d.ar=addr(data_pa); f.edge({},d);
    d={}; d.ar=addr(data_pa+64,9,0); f.edge({},d);
    d={}; d.r=response(9); f.edge({},d); // Reordering across IDs is legal.
    d.r=response(8,false); d.r.ready=false;
    for(unsigned i=0;i<13;i++) f.edge({},d);
    d.r.ready=true; f.edge({},d); d.r=response(8); f.edge({},d);
    // W can arrive before AW. The real32B line uses two beats and one B.
    d={}; d.w.valid=d.w.ready=true; d.w.last=false; d.w.strobe=UINT32_MAX; f.edge({},d);
    d.aw=addr(data_pa,10); d.w.last=true; f.edge({},d);
    d={}; d.b=response(10); d.b.ready=false; f.edge({},d); d.b.ready=true; f.edge({},d); f.edge();
    f.o->finish();
    assert(f.o->counts().ar==2 && f.o->counts().r==3 && f.o->counts().aw==1 && f.o->counts().w==2 && f.o->counts().b==1);
    assert(f.o->counts().dut_read_bytes==96 && f.o->counts().dut_write_bytes==64 && f.o->counts().stalled_lower==14);
    assert(!f.calls && !f.o->counts().observer_read_bytes); checks++;
    // FIXED unaligned transfers retain their narrow size on every beat.
    Fixture fixed; d={}; d.ar=addr(data_pa+3,8,1,2); d.ar.burst=0; fixed.edge({},d);
    d={}; d.r=response(8,false); fixed.edge({},d); d.r.last=true; fixed.edge({},d); fixed.o->finish();
    assert(fixed.o->counts().dut_read_bytes==2); checks++;
    { Fixture bad; d={}; d.ar=addr(data_pa); d.ar.ready=false; bad.edge({},d);
      assert(bad.o->drained()); rejects([&] { bad.o->finish(); },"unaccepted");
      d.ar.address++; rejects([&] { bad.edge({},d); },"irrevocable"); }
    { Fixture bad; d={}; d.ar=addr(data_pa); bad.edge({},d); rejects([&] { bad.write(0x14c,satp); },"SATP changed"); }
    { Fixture bad; d={}; d.w.valid=d.w.ready=true; d.w.strobe=1; bad.edge({},d);
      rejects([&] { bad.write(0x14c,satp); },"SATP changed"); }
    { Fixture bad; d={}; d.ar=addr(UINT64_MAX); rejects([&] { bad.edge({},d); },"unsupported"); }
    { Fixture bad; d={}; d.ar=addr(0xffffffe0); rejects([&] { bad.edge({},d); },"outside actual RAM"); }
    { Fixture bad; d={}; d.ar=addr(data_pa); bad.edge({},d); d={}; d.r=response(8,true); rejects([&] { bad.edge({},d); },"RLAST"); }
    { Fixture bad; d={}; d.r=response(8); rejects([&] { bad.edge({},d); },"unsolicited"); }
    { Fixture bad; d={}; d.aw=addr(data_pa,8,0,0); d.w.valid=d.w.ready=true; d.w.strobe=2;
      rejects([&] { bad.edge({},d); },"WSTRB"); }
    { Fixture bad; d={}; d.aw=addr(data_pa); d.w.valid=d.w.ready=true; d.w.strobe=1;
      rejects([&] { bad.edge({},d); },"WLAST"); }
    { Fixture bad; d={}; d.aw=addr(data_pa,8,0); d.w.valid=d.w.ready=true; d.w.strobe=1; bad.edge({},d);
      d={}; d.b=response(8); d.b.response=2; rejects([&] { bad.edge({},d); },"failed lower B"); }
    { Fixture bad; d={}; d.ar=addr(data_pa,8,0); for(unsigned i=0;i<256;i++) bad.edge({},d);
      rejects([&] { bad.edge({},d); },"queue bound"); }
}
static void mmio() {
    Fixture f; Bus m; m.ar=addr(0x124,0,0,2); f.edge(m);
    m={}; m.r=response(0); m.r.data[3]=128; f.edge(m); f.o->finish();
    assert(f.o->counts().mmio_r==1 && f.events.back().address.address==0x124 && Observer::word(f.events.back().reply.data.data())==0x80000000); checks++;
    { Fixture bad; m={}; m.aw=addr(0x14c,0,0,2); m.aw.ready=false; bad.edge(m);
      assert(!bad.o->counts().mmio_writes); rejects([&] { bad.o->finish(); },"unaccepted"); }
    { Fixture bad; m={}; m.aw=addr(0xc4,0,0,2); m.w.valid=m.w.ready=true; m.w.strobe=15; bad.edge(m);
      m={}; m.b=response(0); m.b.response=2; rejects([&] { bad.edge(m); },"failed MMIO B");
      assert(std::string(bad.events.back().kind)=="mmio_b" && bad.events.back().reply.response==2); }
    { Fixture bad; m={}; m.ar=addr(0x1000,0,0,2); rejects([&] { bad.edge(m); },"MMIO read address"); }
    { Fixture bad; m={}; m.ar=addr(0x124,0,0,2); bad.edge(m); m={}; m.r=response(0); m.r.ready=false; bad.edge(m);
      m.r.data[0]=1; rejects([&] { bad.edge(m); },"irrevocable"); }
}
int main() {
    snapshots(); channels(); mmio();
    std::printf("MEMORY_OBSERVER_CPU PASS checks=%u RTL=UNRUN guest=UNRUN\n",checks);
}
