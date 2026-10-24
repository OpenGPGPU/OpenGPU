package opengpu.graphics

import chisel3._
import chisel3.util.HasBlackBoxInline
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import java.nio.file.{Files,Paths}

private class ParallelDepthMacroResources extends BlackBox with HasBlackBoxInline {
  val io=IO(new Bundle {val present=Output(Bool())})
  setInline("ParallelDepthMacroResources.sv",
    "module ParallelDepthMacroResources(output present); assign present=1'b1; endmodule\n"+
      Files.readString(Paths.get("depends/asap7_sram_0p0/generated/verilog/srambank_64x4x64_6t122.v")))
}
private class MacroParallelDepthMemory(p: TypedTileColorConfig) extends TypedTileColorStore(p,true) {
  val macroModelPresent=IO(Output(Bool()))
  private val resources=Module(new ParallelDepthMacroResources)
  macroModelPresent:=resources.io.present
}

class ParallelTypedDepthMemorySpec extends AnyFlatSpec {
  private val p=TypedTileColorConfig(1024,parallelLanes=true,parallelDepth=true)
  private val full=(BigInt(1)<<64)-1
  private val clear=BigInt("0580000005800000",16)
  private case class Op(address: Int,write: Boolean=false,data: BigInt=0,mask: BigInt=full)
  private case class Reply(data: BigInt,fault: Boolean,initialized: Boolean)
  private class Driver(d: TypedTileColorStore){
    val depth=d.io.depth.get
    var cycle=0;var refills=0;var blocked=false;var refillFault=false
    private var pending: Option[(Int,BigInt,Boolean)]=None
    private var response: Option[(BigInt,Boolean)]=None
    def value(address: Int): BigInt=BigInt("0123456789abcdef",16)^BigInt(address)
    d.io.begin.valid.poke(false.B);d.io.begin.bits.poke(0.U.asTypeOf(d.io.begin.bits))
    d.io.end.valid.poke(false.B);d.io.end.bits.poke(true.B)
    d.io.in.valid.poke(false.B);d.io.in.bits.poke(0.U.asTypeOf(d.io.in.bits));d.io.out.ready.poke(true.B)
    d.io.completion.ready.poke(false.B);d.io.cancel.poke(false.B);d.io.maintenance.poke(false.B)
    d.io.memory.valid.poke(false.B);d.io.memory.bits.poke(0.U.asTypeOf(d.io.memory.bits));d.io.reply.ready.poke(true.B)
    depth.active.poke(false.B);depth.views.poke(0.U.asTypeOf(depth.views))
    for(port<-depth.request){port.valid.poke(false.B);port.bits.poke(0.U.asTypeOf(port.bits))}
    depth.response.foreach(_.ready.poke(true.B))
    d.io.refill.ready.poke(false.B);d.io.refillReply.valid.poke(false.B);d.io.refillReply.bits.poke(0.U.asTypeOf(d.io.refillReply.bits))
    d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    def tick(): Unit={
      if(response.isEmpty && pending.exists(_._1<=cycle)){response=pending.map(x=>(x._2,x._3));pending=None}
      d.io.refill.ready.poke((!blocked && cycle%3!=0).B)
      d.io.refillReply.valid.poke(response.nonEmpty.B)
      response.foreach{case(data,fault)=>d.io.refillReply.bits.data.poke(data.U);d.io.refillReply.bits.fault.poke(fault.B)}
      if(response.nonEmpty && d.io.refillReply.ready.peek().litToBoolean)response=None
      if(d.io.refill.valid.peek().litToBoolean && d.io.refill.ready.peek().litToBoolean){
        assert(pending.isEmpty && response.isEmpty)
        val address=d.io.refill.bits.address.peek().litValue.toInt
        pending=Some((cycle+5,value(address),refillFault));refills+=1
      }
      d.clock.step();cycle+=1
    }
    def until(condition: => Boolean): Unit={var n=0;while(!condition && n<2000){tick();n+=1};assert(condition,"parallel depth words stalled")}
    def begin(load: Int=1,generation: Int=9): Unit={
      depth.active.poke(false.B);depth.views.poke(0.U.asTypeOf(depth.views))
      val v=depth.views(0);v.format.poke(TypedDepthFormat.d24s8.U);v.offset.poke(0.U)
      v.loadOp.poke(load.U);v.clearDepth.poke(BigInt("3f000000",16).U);v.clearStencil.poke(5.U)
      val b=d.io.begin.bits;b.poke(0.U.asTypeOf(b));b.version.poke(1.U)
      b.identity.context.poke(7.U);b.identity.pass.poke(8.U);b.identity.generation.poke(generation.U)
      b.tileLog2.poke(3.U);b.tileWidth.poke(8.U);b.tileHeight.poke(8.U);b.reservedBytes.poke(256.U)
      b.colors(0).enabled.poke(true.B);b.colors(0).format.poke(TypedColorFormat.id("RGBA8").U)
      b.colors(0).offset.poke(256.U);b.colors(0).channelMask.poke(15.U);b.colors(0).loadOp.poke(2.U)
      d.io.begin.valid.poke(true.B);until(d.io.begin.ready.peek().litToBoolean);tick();d.io.begin.valid.poke(false.B)
      until(d.io.in.ready.peek().litToBoolean);depth.active.poke(true.B)
    }
    def exchange(ops: Vector[Op],stallPort0: Int=0,onCycle: ()=>Unit=()=>()): Vector[Reply]={
      val accepted=Array.fill(4)(false);val replies=Array.fill[Option[Reply]](4)(None)
      val held=Array.fill[Option[BigInt]](4)(None);val first=cycle
      val admissions=Array.fill(4)(-1);var n=0
      while(replies.exists(_.isEmpty) && n<2000){
        for(lane<-0 until 4){val in=depth.request(lane);val op=ops(lane)
          in.valid.poke((!accepted(lane)).B);in.bits.address.poke(op.address.U);in.bits.tag.poke((lane+100).U)
          in.bits.write.poke(op.write.B);in.bits.data.poke(op.data.U);in.bits.mask.poke(op.mask.U)
          depth.response(lane).ready.poke((lane!=0 || cycle-first>=stallPort0).B)}
        onCycle()
        for(lane<-0 until 4){
          if(!accepted(lane) && depth.request(lane).ready.peek().litToBoolean){accepted(lane)=true;admissions(lane)=cycle}
          val out=depth.response(lane)
          held(lane).foreach(x=>{out.valid.expect(true.B);assert(out.bits.peek().litValue==x,"held depth word reply changed")})
          if(out.valid.peek().litToBoolean){
            out.bits.tag.expect((lane+100).U)
            if(out.ready.peek().litToBoolean){assert(replies(lane).isEmpty)
              replies(lane)=Some(Reply(out.bits.data.peek().litValue,out.bits.fault.peek().litToBoolean,out.bits.initialized.peek().litToBoolean));held(lane)=None
            }else held(lane)=Some(out.bits.peek().litValue)
          }
        }
        tick();n+=1
      }
      assert(n<2000 && accepted.forall(identity))
      depth.request.foreach(_.valid.poke(false.B));depth.response.foreach(_.ready.poke(true.B))
      until(depth.drained.peek().litToBoolean)
      lastAdmissions=admissions.toVector
      replies.map(_.get).toVector
    }
    var lastAdmissions=Vector.empty[Int]
    def dirtyWord(address: Int): Boolean={
      depth.active.poke(false.B);d.io.maintenance.poke(true.B);d.io.reply.ready.poke(false.B)
      d.io.memory.bits.address.poke(address.U);d.io.memory.bits.write.poke(false.B);d.io.memory.bits.fill.poke(false.B)
      d.io.memory.valid.poke(true.B);until(d.io.memory.ready.peek().litToBoolean);tick();d.io.memory.valid.poke(false.B)
      until(d.io.reply.valid.peek().litToBoolean);d.io.reply.bits.fault.expect(false.B);d.io.reply.bits.available.expect(true.B)
      val dirty=d.io.reply.bits.dirty.peek().litToBoolean
      d.io.reply.ready.poke(true.B);tick();d.io.maintenance.poke(false.B);depth.active.poke(true.B);dirty
    }
    def finish(status: Int=0): Unit={
      depth.active.poke(false.B)
      if(status==0){d.io.end.valid.poke(true.B);until(d.io.end.ready.peek().litToBoolean);tick();d.io.end.valid.poke(false.B)}
      until(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.status.expect(status.U)
      val held=d.io.completion.bits.peek().litValue
      for(_<-0 until 3){tick();assert(d.io.completion.bits.peek().litValue==held)}
      assert(pending.isEmpty && response.isEmpty)
      d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B);d.io.busy.expect(false.B)
    }
  }
  for(macros<-Seq(false,true)){
    def model: TypedTileColorStore=if(macros)new MacroParallelDepthMemory(p)else new TypedTileColorStore(p)
    it should s"initialize four depth banks together and merge adjacent packed depth stencil fields macros=$macros" in {
      simulate(model){d=>val t=new Driver(d);t.begin()
        // Attachment bindings are snapshotted at pass admission.
        d.io.depth.get.views.foreach(_.format.poke(0.U))
        val cold=t.exchange(Vector.tabulate(4)(i=>Op(i)))
        assert(cold.forall(r=>r.data==clear && r.initialized && !r.fault))
        assert(t.lastAdmissions.distinct.size==1,"independent depth banks did not admit four lanes together")
        assert(t.refills==0)
        val masks=Vector(BigInt("ffffff",16),BigInt("ff000000",16),BigInt("ffffff00000000",16),BigInt("ff00000000000000",16))
        val data=Vector(BigInt("123456",16),BigInt("ab000000",16),BigInt("1abcde00000000",16),BigInt("cd00000000000000",16))
        val writes=t.exchange(Vector.tabulate(4)(i=>Op(0,write=true,data=data(i),mask=masks(i))),stallPort0=20)
        assert(writes.forall(r=> !r.fault && !r.initialized))
        val checked=t.exchange(Vector.fill(4)(Op(0)))
        assert(checked.forall(r=>r.data==BigInt("cd1abcdeab123456",16) && !r.fault && !r.initialized))
        val foreign=t.exchange(Vector.fill(4)(Op(32)))
        assert(foreign.forall(r=>r.fault && !r.initialized),"depth words escaped into the color allocation")
        assert(t.dirtyWord(0))
        t.finish()
      }
    }
    it should s"join cold depth loads and drain held refill cancellation before healthy reuse macros=$macros" in {
      simulate(model){d=>val t=new Driver(d);t.begin(load=0)
        val read=t.exchange(Vector.fill(4)(Op(4)),stallPort0=25)
        assert(t.refills==1 && read.forall(r=>r.data==t.value(4) && !r.fault) && read.count(_.initialized)==1)
        assert(t.exchange(Vector.fill(4)(Op(4))).forall(r=> !r.initialized && !r.fault));assert(t.refills==1)
        assert(!t.dirtyWord(4));t.finish()
        t.begin(load=0,generation=10);t.refillFault=true
        assert(t.exchange(Vector.fill(4)(Op(5))).forall(r=>r.fault && !r.initialized));t.finish(3);t.refillFault=false
        t.begin(load=0,generation=11);t.blocked=true;var held: Option[BigInt]=None;var clocks=0
        val canceled=t.exchange(Vector.fill(4)(Op(6)),onCycle=()=>{
          if(d.io.refill.valid.peek().litToBoolean && clocks<7){
            val bits=d.io.refill.bits.peek().litValue;held.foreach(x=>assert(bits==x));held=Some(bits)
            d.io.cancel.poke((clocks==0).B);d.io.completion.valid.expect(false.B);clocks+=1
          }else if(clocks==7){d.io.cancel.poke(false.B);t.blocked=false}
        })
        assert(clocks==7 && canceled.forall(r=>r.fault && !r.initialized));t.finish(4)
        t.begin(generation=12);assert(t.exchange(Vector.fill(4)(Op(6))).count(_.initialized)==1);t.finish()
      }
    }
  }
}
