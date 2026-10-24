package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import java.nio.file.{Files,Paths}
import scala.collection.mutable

private class CoalescedMacroResources extends BlackBox with HasBlackBoxInline {
  val io=IO(new Bundle {val present=Output(Bool())})
  setInline("CoalescedMacroResources.sv",
    "module CoalescedMacroResources(output present); assign present=1'b1; endmodule\n"+
      Files.readString(Paths.get("depends/asap7_sram_0p0/generated/verilog/srambank_64x4x64_6t122.v")))
}
private class CoalescedMemoryHarness(macros: Boolean) extends CoalescedTypedTileMemory(useBlackBoxes=macros) {
  val physicalIssue=IO(Output(UInt(3.W)))
  val partialIssue=IO(Output(UInt(3.W)))
  physicalIssue:=PopCount(memory.io.request.map(_.fire))
  partialIssue:=PopCount(memory.io.request.map(r=>r.fire && r.bits.write && !r.bits.mask.andR))
  val macroModelPresent=IO(Output(Bool()))
  if(macros){val resources=Module(new CoalescedMacroResources);macroModelPresent:=resources.io.present}
  else macroModelPresent:=true.B
}

class TypedTileWordCoalescerSpec extends AnyFlatSpec {
  private val full=(BigInt(1)<<64)-1
  private case class Op(address: Int,tag: Int,write: Boolean=false,data: BigInt=0,mask: BigInt=full)
  private case class Reply(tag: Int,data: BigInt,fault: Boolean)
  private class Driver(d: CoalescedMemoryHarness){
    val model=mutable.Map.empty[Int,BigInt]
    val accepted=Array.fill(4)(mutable.ArrayBuffer.empty[Int])
    val retired=Array.fill(4)(mutable.ArrayBuffer.empty[Int])
    var cycle=0;var physical=0;var partial=0
    for(p<-0 until 4){d.io.request(p).valid.poke(false.B);d.io.request(p).bits.poke(0.U.asTypeOf(d.io.request(p).bits));d.io.response(p).ready.poke(true.B)}
    d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B);d.io.drained.expect(true.B)
    def run(streams: Vector[Vector[Op]],ready: (Int,Int)=>Boolean=(_,_)=>true): Unit={
      val next=Array.fill(4)(0);val expected=Array.fill(4)(mutable.Queue.empty[Reply])
      val held=Array.fill[Option[BigInt]](4)(None);var steps=0
      while((next.indices.exists(p=>next(p)<streams(p).size) || expected.exists(_.nonEmpty)) && steps<20000){
        for(p<-0 until 4){val in=d.io.request(p);in.valid.poke((next(p)<streams(p).size).B)
          if(next(p)<streams(p).size){val op=streams(p)(next(p));in.bits.address.poke(op.address.U);in.bits.tag.poke(op.tag.U)
            in.bits.write.poke(op.write.B);in.bits.data.poke(op.data.U);in.bits.mask.poke(op.mask.U)}
          d.io.response(p).ready.poke(ready(p,cycle).B)
        }
        for(p<-0 until 4 if next(p)<streams(p).size && d.io.request(p).ready.peek().litToBoolean){
          val op=streams(p)(next(p));val fault=op.address>=4096
          expected(p).enqueue(Reply(op.tag,if(!fault && !op.write)model(op.address)else BigInt(0),fault))
          if(!fault && op.write && op.mask!=0){
            val old=if(op.mask==full)BigInt(0)else model.getOrElse(op.address,BigInt(0))
            model(op.address)=(old&(full^op.mask))|(op.data&op.mask)
          }
          accepted(p)+=cycle;next(p)+=1
        }
        for(p<-0 until 4){val out=d.io.response(p)
          held(p).foreach(x=>{out.valid.expect(true.B);assert(out.bits.peek().litValue==x,"held coalesced reply changed")})
          if(out.valid.peek().litToBoolean){
            assert(expected(p).nonEmpty);val e=expected(p).front
            out.bits.tag.expect(e.tag.U);out.bits.data.expect(e.data.U);out.bits.fault.expect(e.fault.B)
            if(ready(p,cycle)){expected(p).dequeue();retired(p)+=cycle;held(p)=None}else held(p)=Some(out.bits.peek().litValue)
          }
        }
        physical+=d.physicalIssue.peek().litValue.toInt;partial+=d.partialIssue.peek().litValue.toInt
        d.clock.step();cycle+=1;steps+=1
      }
      assert(steps<20000,"coalesced memory did not drain")
      d.io.request.foreach(_.valid.poke(false.B));d.io.drained.expect(true.B)
    }
    def stream(f: (Int,Int)=>Op,n: Int=128): Vector[Vector[Op]]=Vector.tabulate(4)(p=>Vector.tabulate(n)(i=>f(p,i)))
    def consecutive(start: Vector[Int],n: Int): Unit=for(p<-0 until 4){
      for(xs<-Seq(accepted(p).drop(start(p)),retired(p).drop(start(p)))){
        assert(xs.size==n);assert(xs.sliding(2).forall(x=>x(1)==x(0)+1),s"client $p lost consecutive service")
      }
    }
    def position: Vector[Int]=accepted.map(_.size).toVector
  }
  for(macros<-Seq(false,true)){
    it should s"coalesce neighboring samples into full words at four logical writes and reads every clock macros=$macros" in {
      simulate(new CoalescedMemoryHarness(macros)){dut=>
        val d=new Driver(dut);val start=d.position
        d.run(d.stream((p,i)=>Op(i*8+p/2,i,write=true,data=BigInt(i*19+p+1)<<(32*(p%2)),mask=BigInt("ffffffff",16)<<(32*(p%2)))))
        d.consecutive(start,128);assert(d.physical==256 && d.partial==0)
        val before=d.physical;val reads=d.position
        d.run(d.stream((p,i)=>Op(i*8+p/2,1000+i)));d.consecutive(reads,128)
        assert(d.physical-before==256)
        info(s"COALESCED_TILE_WORDS macros=$macros logicalClients=4 consecutiveClocks=128 logicalWrites=512 physicalFullWrites=256 partialWrites=0 logicalReads=512 physicalReads=256 exactStorage=true")
      }
    }
    it should s"serialize overlapping masks before a coalesced read without dropping logical acknowledgements macros=$macros" in {
      simulate(new CoalescedMemoryHarness(macros)){dut=>
        val d=new Driver(dut)
        d.run(Vector(Vector(Op(0,0,write=true,data=BigInt("1122334455667700",16))),Vector.empty,Vector.empty,Vector.empty))
        val before=d.physical;val partial=d.partial
        d.run(Vector.tabulate(4)(p=>Vector(Op(0,10+p,write=true,data=0x5a,mask=255),Op(0,20+p))),
          (p,c)=>(c+p)%7>=2)
        assert(d.physical-before==5,"overlapping writes must remain four operations, followed by one shared read")
        assert(d.partial-partial==4)
        assert(d.model(0)==BigInt("112233445566775a",16))
      }
    }
    it should s"preserve masked aliases, faults, original tags and independent held completions macros=$macros" in {
      simulate(new CoalescedMemoryHarness(macros)){dut=>
        val d=new Driver(dut)
        d.run(Vector(Vector(Op(0,0,write=true,data=full)),Vector.empty,Vector.empty,Vector.empty))
        d.run(d.stream((p,i)=>Op(0,i%5,write=true,data=BigInt((i*31+p)&255)<<(p*8),mask=BigInt(255)<<(p*8)),32))
        d.run(d.stream((p,_)=>Op(0,100+p),1))
        val first=d.cycle
        d.run(d.stream((p,i)=>Op(i*8+p,2000+i,write=true,data=BigInt(i*17+p)),64),
          (p,c)=>p!=0 || c>=first+100)
        for(p<-1 until 4)assert(d.retired(p).exists(c=>c>first+20 && c<first+100),"held client blocked unrelated words")
        val readStart=d.cycle
        d.run(d.stream((p,i)=>Op(i*8+p,3000+i),64),(p,c)=>p!=0 || c>=readStart+100)
        for(p<-1 until 4)assert(d.retired(p).exists(c=>c>readStart+20 && c<readStart+100))
        d.run(d.stream((p,i)=>Op(4096+i,4000+i,write=i%2==0,data=BigInt(p),mask=0),8),(p,c)=>(c+p)%5>=2)
        d.run(d.stream((p,_)=>Op(0,5000+p,write=true,mask=0),1))
        d.run(d.stream((p,_)=>Op(0,6000+p),1))
      }
    }
  }
  it should "reorder physical replies by reserved slot while preserving repeated logical tags and offered payloads" in {
    simulate(new TypedTileWordCoalescer(4,4)){d=>
      val pending=Array.fill(4)(mutable.ArrayBuffer.empty[Reply]);val active=Array.fill[Option[Reply]](4)(None)
      val expected=Array.fill(4)(mutable.Queue.empty[Reply]);val held=Array.fill[Option[BigInt]](4)(None)
      val offered=Array.fill[Option[BigInt]](4)(None);val sent=Array.fill(4)(0)
      for(p<-0 until 4){d.io.request(p).valid.poke(false.B);d.io.response(p).ready.poke(false.B)
        d.io.memoryRequest(p).ready.poke(false.B);d.io.memoryResponse(p).valid.poke(false.B)
        d.io.memoryResponse(p).bits.poke(0.U.asTypeOf(d.io.memoryResponse(p).bits))}
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      var cycle=0;var received=0;var reversed=0
      while(received<128 && cycle<5000){
        for(p<-0 until 4){
          val n=sent(p);val a=if(n%9==0)5000+p*32+n else p*32+n
          val in=d.io.request(p);in.valid.poke((n<32).B);in.bits.address.poke(a.U);in.bits.tag.poke((n%3).U)
          in.bits.write.poke((n%2==0).B);in.bits.data.poke(n.U);in.bits.mask.poke(full.U)
          d.io.response(p).ready.poke(((cycle+p)%7>=3).B)
          d.io.memoryRequest(p).ready.poke(((cycle+p)%5!=0).B)
          if(active(p).isEmpty && pending(p).nonEmpty && cycle%3!=0){
            val at=if(cycle%11==0)0 else pending(p).size-1
            if(at>0)reversed+=1;active(p)=Some(pending(p).remove(at))
          }
          val reply=d.io.memoryResponse(p);reply.valid.poke(active(p).nonEmpty.B)
          active(p).foreach(e=>{reply.bits.tag.poke(e.tag.U);reply.bits.data.poke(e.data.U);reply.bits.fault.poke(e.fault.B)})
        }
        for(p<-0 until 4){
          val in=d.io.request(p)
          if(in.valid.peek().litToBoolean && in.ready.peek().litToBoolean){
            val a=in.bits.address.peek().litValue;val write=in.bits.write.peek().litToBoolean;val fault=a>=4096
            expected(p).enqueue(Reply(sent(p)%3,if(write || fault)0 else a^BigInt("123456789abcdef0",16),fault));sent(p)+=1
          }
          val physical=d.io.memoryRequest(p)
          offered(p).foreach(x=>{physical.valid.expect(true.B);assert(physical.bits.peek().litValue==x)})
          if(physical.valid.peek().litToBoolean){
            if(physical.ready.peek().litToBoolean){
              val a=physical.bits.address.peek().litValue;val write=physical.bits.write.peek().litToBoolean;val fault=a>=4096
              pending(p)+=Reply(physical.bits.tag.peek().litValue.toInt,if(write || fault)0 else a^BigInt("123456789abcdef0",16),fault);offered(p)=None
            }else offered(p)=Some(physical.bits.peek().litValue)
          }
          val physicalReply=d.io.memoryResponse(p)
          if(physicalReply.valid.peek().litToBoolean && physicalReply.ready.peek().litToBoolean)active(p)=None
          val out=d.io.response(p)
          held(p).foreach(x=>{out.valid.expect(true.B);assert(out.bits.peek().litValue==x)})
          if(out.valid.peek().litToBoolean){
            assert(expected(p).nonEmpty);val e=expected(p).front
            out.bits.tag.expect(e.tag.U);out.bits.data.expect(e.data.U);out.bits.fault.expect(e.fault.B)
            if(out.ready.peek().litToBoolean){expected(p).dequeue();received+=1;held(p)=None}else held(p)=Some(out.bits.peek().litValue)
          }
        }
        d.clock.step();cycle+=1
      }
      assert(received==128 && reversed>0);d.io.request.foreach(_.valid.poke(false.B));d.io.drained.expect(true.B)
    }
  }
}
