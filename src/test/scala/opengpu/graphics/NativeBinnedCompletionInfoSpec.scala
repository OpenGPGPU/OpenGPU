package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.command.GpuCommandResult
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class NativeBinnedCompletionInfoEncoder extends Module {
  val io=IO(new Bundle {
    val in=Input(new NativeBinnedGraphicsCompletion(8,VertexShaderStageConfig()))
    val words=Output(Vec(12,UInt(32.W)))
  })
  io.words:=VecInit(NativeBinnedCompletionInfo.words(io.in))
}
class NativeBinnedCompletionHost extends Module {
  val io=IO(new Bundle {
    val reg=new Bundle {val req=Flipped(Decoupled(new RenderHostRegRequest));val resp=Decoupled(new RenderHostRegResponse)}
    val native=Flipped(Decoupled(new TileBinJobCompletion(8)))
    val details=Input(Vec(12,UInt(32.W)))
    val other=Flipped(Decoupled(new GpuCommandResult(8)))
    val resetDone=Input(Bool());val resetActive=Output(Bool());val drained=Output(Bool());val event=Output(Bool())
  })
  val config=GpuConfig(lanes=4,warps=2)
  val bridge=Module(new GpuCommandMmio(config,queueDepth=2,protectVmState=true,nativeCompletionInfo=true))
  val port=Module(new TileBinHostCommandPort(config,nativeCompletionInfo=true))
  bridge.io.reg<>io.reg;port.io.command<>bridge.io.command
  port.io.renderCompletion<>io.native;port.io.renderCompletionDetails.get:=io.details
  port.io.otherCompletion<>io.other;bridge.io.completion<>port.io.completion
  bridge.io.completionDetails.get:=port.io.completionDetails.get
  port.io.otherCommand.ready:=true.B;port.io.submit.ready:=true.B
  port.io.dependencyIdle:=true.B;port.io.renderIdle:=true.B;port.io.resetActive:=bridge.io.resetActive
  bridge.io.resetDone:=io.resetDone;bridge.io.vmContextIdle.get:=port.io.drained
  io.resetActive:=bridge.io.resetActive;io.drained:=port.io.drained;io.event:=bridge.io.completionEvent
}
class NativeBinnedCompletionInfoSpec extends AnyFlatSpec {
  it should "discard a reset-edge completion in the legacy MMIO profile without adding native registers" in {
    simulate(new GpuCommandMmio(GpuConfig(lanes=4,warps=2))){d=>
      d.io.reg.req.valid.poke(false.B);d.io.reg.resp.ready.poke(true.B);d.io.command.ready.poke(false.B)
      d.io.completion.valid.poke(false.B);d.io.completion.bits.poke(0.U.asTypeOf(d.io.completion.bits));d.io.resetDone.poke(false.B)
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      d.io.completion.valid.poke(true.B);d.io.completion.bits.commandId.poke(61.U)
      d.io.reg.req.bits.addr.poke(GpuCommandMmioRegs.RESET.U);d.io.reg.req.bits.data.poke(1.U)
      d.io.reg.req.bits.strb.poke(15.U);d.io.reg.req.bits.isWrite.poke(true.B);d.io.reg.req.valid.poke(true.B)
      d.io.completion.ready.expect(true.B);d.io.completionEvent.expect(false.B);d.clock.step()
      d.io.completion.valid.poke(false.B);d.io.reg.req.valid.poke(false.B)
      d.io.resetDone.poke(true.B);d.clock.step();d.io.resetDone.poke(false.B)
      d.io.reg.req.bits.addr.poke(GpuCommandMmioRegs.STATUS.U);d.io.reg.req.bits.isWrite.poke(false.B)
      d.io.reg.req.valid.poke(true.B);assert((d.io.reg.resp.bits.data.peek().litValue&2)==0)
      d.io.reg.req.bits.addr.poke(0x180.U);d.io.reg.resp.bits.ok.expect(false.B)
    }
  }
  private def initialize(d: NativeBinnedCompletionHost): Unit={
    d.io.reg.req.valid.poke(false.B);d.io.reg.resp.ready.poke(true.B);d.io.resetDone.poke(false.B)
    d.io.native.valid.poke(false.B);d.io.native.bits.poke(0.U.asTypeOf(d.io.native.bits))
    d.io.other.valid.poke(false.B);d.io.other.bits.poke(0.U.asTypeOf(d.io.other.bits))
    d.io.details.foreach(_.poke(0.U));d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
  }
  private def request(d: NativeBinnedCompletionHost,a: Int,v: BigInt=0,write: Boolean=false,ok: Boolean=true): BigInt={
    d.io.reg.req.bits.addr.poke(a.U);d.io.reg.req.bits.data.poke(v.U);d.io.reg.req.bits.strb.poke(15.U)
    d.io.reg.req.bits.isWrite.poke(write.B);d.io.reg.req.valid.poke(true.B)
    d.io.reg.resp.bits.ok.expect(ok.B);val result=d.io.reg.resp.bits.data.peek().litValue
    d.clock.step();d.io.reg.req.valid.poke(false.B);result
  }
  private def packet(d: NativeBinnedCompletionHost,id: Int,words: Seq[BigInt]): Unit={
    d.io.native.bits.commandId.poke(id.U);d.io.native.bits.status.poke(5.U)
    d.io.native.bits.appendReadBytes.poke((id*64).U);d.io.details.zip(words).foreach{case(a,b)=>a.poke(b.U)}
    d.io.native.valid.poke(true.B);var n=0
    while(!d.io.native.ready.peek().litToBoolean && n<20){d.clock.step();n+=1};assert(n<20)
    d.clock.step();d.io.native.valid.poke(false.B)
  }
  private def check(d: NativeBinnedCompletionHost,id: Int,words: Seq[BigInt]): Unit={
    assert((request(d,GpuCommandMmioRegs.COMPLETION)&255)==id)
    for((v,i)<-words.zipWithIndex)assert(request(d,0x180+i*4)==v,s"GNX1 word $i differs")
  }
  it should "encode independent reader frame geometry bin and vertex fault snapshots without losing identity or 64-bit ordinals" in {
    simulate(new NativeBinnedCompletionInfoEncoder){d=>
      val c=d.io.in;c.poke(0.U.asTypeOf(c));c.status.poke(5.U);c.faultStage.poke(3.U)
      c.faultAddress.poke(0xfffffff0L.U);c.faultDraw.poke(1234.U);c.identity.context.poke(7.U);c.identity.pass.poke(8.U);c.identity.generation.poke(9.U)
      val f=c.frame;val g=f.source;val vp=g.sourceFault;val clip=vp.source;val assembly=clip.source;val vs=assembly.source
      f.faultStage.poke(2.U);g.faultStage.poke(3.U);f.faultTileValid.poke(true.B);f.faultTile.poke(56.U)
      g.faultDrawValid.poke(true.B);g.faultDraw.poke(BigInt("0123456789abcdef",16).U);g.sourceFaultValid.poke(true.B)
      vp.viewportFaultValid.poke(true.B);clip.clipFaultValid.poke(true.B)
      val status=Seq(1,2,3,4,5,6,7,1,2)
      Seq(f.status,g.status,g.bins.status,g.bins.producer.status,g.bins.producer.binStatus,vp.status,clip.status,assembly.status,vs.status)
        .zip(status).foreach{case(a,b)=>a.poke(b.U)}
      val chain=status.zipWithIndex.map{case(v,i)=>BigInt(v)<<(i*3)}.reduce(_|_)
      val expected=Seq[BigInt](BigInt("474e5801",16),5|(3<<3)|(2<<5)|(3<<7)|(31<<9),0xfffffff0L,1234,56,chain,7,8,9,0x89abcdefL,0x01234567,0)
      d.io.words.zip(expected).foreach{case(a,b)=>a.expect(b.U)}
      g.sourceFaultValid.poke(false.B);g.faultDrawValid.poke(false.B);f.faultTileValid.poke(false.B)
      assert((d.io.words(1).peek().litValue&0x3e00)==0)
      assert((d.io.words(5).peek().litValue& ~BigInt(0x7fff))==0)
      for(i<-Seq(4,9,10))d.io.words(i).expect(0.U)
    }
  }
  it should "keep completion details paired through two queued sources held reads and simultaneous pop replacement" in {
    simulate(new NativeBinnedCompletionHost){d=>initialize(d)
      val a=Vector.tabulate[BigInt](12)(i=>if(i==0)BigInt("474e5801",16)else BigInt(0x80000000L+i))
      val b=Vector.tabulate[BigInt](12)(i=>if(i==0)BigInt("474e5801",16)else BigInt(0xf0000000L+i))
      packet(d,41,a);d.clock.step();packet(d,42,b)
      d.io.other.valid.poke(true.B);d.io.other.bits.commandId.poke(43.U);d.io.other.bits.success.poke(true.B)
      var n=0;while(!d.io.other.ready.peek().litToBoolean && n<20){d.clock.step();n+=1};assert(n<20)
      d.clock.step();d.io.other.valid.poke(false.B);d.io.details.foreach(_.poke(0xdeadbeefL.U))
      check(d,41,a);request(d,GpuCommandMmioRegs.VECTOR_SATP,1,write=true,ok=false)
      for(i<-0 until 12)request(d,0x180+i*4,0,write=true,ok=false)
      check(d,41,a);request(d,GpuCommandMmioRegs.COMPLETION_POP,1,write=true);check(d,42,b)
      request(d,GpuCommandMmioRegs.COMPLETION_POP,1,write=true);check(d,43,Vector.fill(12)(BigInt(0)))
      request(d,GpuCommandMmioRegs.COMPLETION_POP,1,write=true)
      for(i<-0 until 12)assert(request(d,0x180+i*4)==0)
      request(d,GpuCommandMmioRegs.VECTOR_SATP,1,write=true)
      info("NATIVE_GNX1 queuedNativeResults=2 computeDetailsZero=true heldWordsImmutable=true simultaneousPopReplacement=true readOnly=true emptySlotZero=true VMProtected=true")
    }
  }
  it should "discard a completion on the reset-request edge and all late details until reset drain finishes" in {
    simulate(new NativeBinnedCompletionHost){d=>initialize(d)
      packet(d,51,Vector.fill(12)(BigInt("a5a5a5a5",16)))
      d.io.reg.req.bits.addr.poke(GpuCommandMmioRegs.RESET.U);d.io.reg.req.bits.data.poke(1.U)
      d.io.reg.req.bits.strb.poke(15.U);d.io.reg.req.bits.isWrite.poke(true.B);d.io.reg.req.valid.poke(true.B)
      d.io.event.expect(false.B);d.clock.step();d.io.reg.req.valid.poke(false.B);d.io.resetActive.expect(true.B)
      packet(d,52,Vector.fill(12)(BigInt("ffffffff",16)));d.clock.step(4);d.io.drained.expect(true.B)
      for(i<-0 until 12)assert(request(d,0x180+i*4)==0)
      d.io.resetDone.poke(true.B);d.clock.step();d.io.resetDone.poke(false.B)
      assert((request(d,GpuCommandMmioRegs.STATUS)&2)==0)
      for(i<-0 until 12)assert(request(d,0x180+i*4)==0)
      val fresh=Vector.tabulate[BigInt](12)(i=>if(i==0)BigInt("474e5801",16)else BigInt(i))
      packet(d,53,fresh);d.clock.step();check(d,53,fresh)
      info("NATIVE_GNX1 resetRequestCompletionRace=true lateResultsDiscarded=true noStaleDetails=true exactFreshReuse=true")
    }
  }
}
