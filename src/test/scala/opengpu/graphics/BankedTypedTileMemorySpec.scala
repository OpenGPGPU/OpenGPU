package opengpu.graphics

import chisel3._
import chisel3.util.HasBlackBoxInline
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import java.nio.file.{Files,Paths}
import scala.collection.mutable

private class TypedTileMemoryMacroResources extends BlackBox with HasBlackBoxInline {
  val io=IO(new Bundle {val present=Output(Bool())})
  setInline("TypedTileMemoryMacroResources.sv",
    "module TypedTileMemoryMacroResources(output present); assign present=1'b1; endmodule\n"+
      Files.readString(Paths.get("depends/asap7_sram_0p0/generated/verilog/srambank_64x4x64_6t122.v")))
}
private class MacroBankedTypedTileMemory(ports: Int=4,banks: Int=8,xorBanks: Boolean=false)
    extends BankedTypedTileMemory(ports=ports,banks=banks,useBlackBoxes=true,xorBanks=xorBanks) {
  val macroModelPresent=IO(Output(Bool()))
  private val resources=Module(new TypedTileMemoryMacroResources)
  macroModelPresent:=resources.io.present
}

class BankedTypedTileMemorySpec extends AnyFlatSpec {
  private val full=(BigInt(1)<<64)-1
  private case class Op(address: Int,tag: Int,write: Boolean=false,data: BigInt=0,mask: BigInt=full)
  private class Driver(d: BankedTypedTileMemory){
    private val ports=d.io.request.size
    val memory=mutable.Map.empty[Int,BigInt]
    val expected=mutable.Map.empty[(Int,Int),(BigInt,Boolean)]
    val admissions=Array.fill(ports)(mutable.ArrayBuffer.empty[Int])
    val completions=Array.fill(ports)(mutable.ArrayBuffer.empty[Int])
    private val held=Array.fill[Option[BigInt]](ports)(None)
    var cycle=0
    for(p<-0 until ports){d.io.request(p).valid.poke(false.B);d.io.request(p).bits.poke(0.U.asTypeOf(d.io.request(p).bits));d.io.response(p).ready.poke(true.B)}
    d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B);d.io.drained.expect(true.B)
    def run(streams: Vector[Vector[Op]],ready: (Int,Int)=>Boolean=(_,_)=>true): Unit={
      val next=Array.fill(ports)(0);var steps=0
      while((next.indices.exists(p=>next(p)<streams(p).size) || expected.nonEmpty) && steps<10000){
        for(p<-0 until ports){
          val r=d.io.request(p);r.valid.poke((next(p)<streams(p).size).B)
          if(next(p)<streams(p).size){val op=streams(p)(next(p));r.bits.address.poke(op.address.U);r.bits.tag.poke(op.tag.U)
            r.bits.write.poke(op.write.B);r.bits.data.poke(op.data.U);r.bits.mask.poke(op.mask.U)}
          d.io.response(p).ready.poke(ready(p,cycle).B)
        }
        for(p<-0 until ports if next(p)<streams(p).size && d.io.request(p).ready.peek().litToBoolean){
          val op=streams(p)(next(p));val fault=op.address>=4096
          val data=if(!fault && !op.write)memory(op.address)else BigInt(0)
          assert(!expected.contains((p,op.tag)));expected((p,op.tag))=(data,fault)
          if(!fault && op.write && op.mask!=0){
            val old=if(op.mask==full)BigInt(0)else memory(op.address)
            memory(op.address)=(old&(full^op.mask))|(op.data&op.mask)
          }
          admissions(p)+=cycle;next(p)+=1
        }
        for(p<-0 until ports){val out=d.io.response(p)
          held(p).foreach(v=>{out.valid.expect(true.B);assert(out.bits.peek().litValue==v,"held tile reply changed")})
          if(out.valid.peek().litToBoolean){
            val tag=out.bits.tag.peek().litValue.toInt
            assert(expected.contains((p,tag)),s"unexpected reply port=$p tag=$tag")
            val (data,fault)=expected((p,tag));out.bits.data.expect(data.U);out.bits.fault.expect(fault.B)
            if(ready(p,cycle)){expected.remove((p,tag));completions(p)+=cycle;held(p)=None}
            else held(p)=Some(out.bits.peek().litValue)
          }
        }
        d.clock.step();cycle+=1;steps+=1
        if(expected.nonEmpty)d.io.drained.expect(false.B)
      }
      assert(steps<10000,"banked tile memory did not drain")
      for(p<-0 until ports)d.io.request(p).valid.poke(false.B)
      d.io.drained.expect(true.B)
    }
    def stream(make: (Int,Int)=>Op,count: Int=128): Vector[Vector[Op]]=
      Vector.tabulate(ports)(p=>Vector.tabulate(count)(i=>make(p,i)))
    def consecutive(start: Vector[Int],count: Int): Unit={
      for(p<-0 until ports){val xs=admissions(p).drop(start(p));assert(xs.size==count)
        assert(xs.sliding(2).forall(x=>x(1)==x(0)+1),s"port $p lost conflict-free issue bandwidth")
        val replies=completions(p).drop(start(p));assert(replies.size==count)
        assert(replies.sliding(2).forall(x=>x(1)==x(0)+1),s"port $p lost conflict-free reply bandwidth")}
    }
    def position: Vector[Int]=admissions.map(_.size).toVector
  }
  for(macros<-Seq(false,true)){
    def model: BankedTypedTileMemory=if(macros)new MacroBankedTypedTileMemory else new BankedTypedTileMemory
    it should s"sustain four typed word operations per clock including masked writes across independent banks macros=$macros" in {
      simulate(model){dut=>
        val d=new Driver(dut)
        val start=d.position
        d.run(d.stream((p,i)=>Op(1984+i*4+p,i,write=true,data=full^(BigInt(i+1)<<(p*8)))))
        d.consecutive(start,128)
        val reads=d.position;d.run(d.stream((p,i)=>Op(1984+i*4+p,1000+i)));d.consecutive(reads,128)
        val masks=d.position
        d.run(d.stream((p,i)=>Op(1984+i*4+p,2000+i,write=true,data=BigInt(i*97+p),mask=BigInt("f0ff0f00ff00ffff",16))))
        d.consecutive(masks,128)
        d.run(d.stream((p,i)=>Op(1984+i*4+p,3000+i)))
        println(s"TYPED_TILE_WORD_BANDWIDTH macros=$macros ports=4 banks=8 consecutiveIssueClocks=128 wordsPerClock=4 maskedWordsPerClock=4")
      }
    }
    it should s"serialize conflicting masks fairly and preserve tagged replies under independent stalls macros=$macros" in {
      simulate(model){dut=>
        val d=new Driver(dut)
        d.run(Vector(Vector(Op(0,0,write=true,data=full)),Vector.empty,Vector.empty,Vector.empty))
        val conflict=d.position
        d.run(d.stream((p,i)=>Op(0,100+i,write=true,data=BigInt((i*31+p)&255)<<(p*8),mask=BigInt(255)<<(p*8)),32))
        for(p<-0 until 4){val xs=d.admissions(p).drop(conflict(p));assert(xs.size==32)
          assert(xs.sliding(2).forall(x=>x(1)-x(0)<=8),s"same-bank requester $p starved")}
        d.run(Vector(Vector(Op(0,1000)),Vector.empty,Vector.empty,Vector.empty))
        val stallStart=d.cycle
        d.run(d.stream((p,i)=>Op(i*8+p,2000+i,write=true,data=BigInt(i*17+p)),64),
          (p,c)=>p!=0 || c>=stallStart+80)
        for(p<-1 until 4)assert(d.completions(p).exists(c=>c>stallStart+10 && c<stallStart+80),"a blocked bank stopped an independent bank")
        val random=new scala.util.Random(0x54494c45)
        val patterns=Vector.tabulate(4)(p=>Vector.tabulate(96)(i=>{
          val a=(i%16)*8+(p+i)%4
          if(i%3==0)Op(a,3000+i)else Op(a,3000+i,write=true,data=BigInt(64,random),mask=BigInt(64,random))
        }))
        d.run(patterns,(p,c)=>(c+p*3)%7>=3)
        d.run(d.stream((p,i)=>Op(i*8+p,4000+i),16))
        val readStall=d.cycle
        d.run(d.stream((p,i)=>Op(i*8+p,4500+i),64),(p,c)=>p!=0 || c>=readStall+80)
        for(p<-1 until 4)assert(d.completions(p).exists(c=>c>readStall+10 && c<readStall+80),"a held read stopped an independent bank")
        d.run(Vector(Vector(Op(4096,5000),Op(4096,5001,write=true,data=0),Op(Int.MaxValue,5002),Op(0,5003,write=true,mask=0),Op(0,5004)),Vector.empty,Vector.empty,Vector.empty))
      }
    }
  }

  for(macros<-Seq(false,true)){
    def wide: BankedTypedTileMemory=if(macros)new MacroBankedTypedTileMemory(8,16,true)
      else new BankedTypedTileMemory(ports=8,banks=16,xorBanks=true)
    it should s"sustain simultaneous paired color and depth reads and full writebacks on eight ports macros=$macros" in {
      simulate(wide){dut=>
        val d=new Driver(dut)
        // Four words read from one cohort while four full merged words from
        // an independent older cohort are written. Planes are 4096 bytes apart.
        val bases=Vector(0,1,512,513,1024,1025,1536,1537)
        d.run(d.stream((p,i)=>Op(bases(p)+2*i,i,write=true,data=(BigInt(p+1)<<56)|BigInt(i)),128))
        val start=d.position
        d.run(d.stream((p,i)=>Op(bases(p)+2*i,1000+i,write=p>=4,
          data=(BigInt(p+17)<<52)|BigInt(i*71+3)),128))
        d.consecutive(start,128)
        d.run(d.stream((p,i)=>Op(bases(p)+2*i,2000+i),128))
        // Check every physical row/bank location. A broken row hash must not
        // alias two canonical word addresses, including segment boundaries.
        d.run(d.stream((p,i)=>Op(i*8+p,3000+i,write=true,data=(BigInt(i)<<32)|BigInt(p)),512))
        d.run(d.stream((p,i)=>Op(i*8+p,4000+i),512))
        info(s"WIDE_TYPED_TILE_MEMORY macros=$macros ports=8 banks=16 bytes=32768 mixedIssueClocks=128 readsPerClock=4 fullWritesPerClock=4 canonicalWordsChecked=4096")
      }
    }
    it should s"retain atomic masked updates, fairness and held replies with row hashing macros=$macros" in {
      simulate(wide){dut=>
        val d=new Driver(dut)
        d.run(Vector(Vector(Op(1024,0,write=true,data=full)))++Vector.fill(7)(Vector.empty))
        val start=d.position
        d.run(d.stream((p,i)=>Op(1024,100+i,write=true,data=BigInt((i*31+p)&255)<<(p*8),mask=BigInt(255)<<(p*8)),24))
        for(p<-0 until 8){val xs=d.admissions(p).drop(start(p))
          assert(xs.size==24 && xs.sliding(2).forall(x=>x(1)-x(0)<=16),s"hashed bank requester $p starved")}
        d.run(Vector(Vector(Op(1024,1000)))++Vector.fill(7)(Vector.empty))
        val bases=Vector(0,1,512,513,1024,1025,1536,1537)
        val stalled=d.cycle
        // Keep each requester on one independent bank during the stall.
        d.run(d.stream((p,i)=>Op(bases(p),2000+i,write=true,data=BigInt(i*17+p)),64),
          (p,c)=>p!=0 || c>=stalled+80)
        for(p<-1 until 8)assert(d.completions(p).exists(c=>c>stalled+10 && c<stalled+80),"held hashed reply blocked independent banks")
        d.run(d.stream((p,i)=>Op(bases(p),3000+i),64),(p,c)=>(c+p*3)%9>=4)
        d.run(Vector(Vector(Op(4096,5000),Op(Int.MaxValue,5001),Op(1024,5002,write=true,mask=0),Op(1024,5003)))++Vector.fill(7)(Vector.empty))
      }
    }
  }
}
