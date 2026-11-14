package opengpu.system

import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.graphics._
import opengpu.graphics.tessellation._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** Actual CU instruction fixture: TCS produces every tested patch word. The
  * test supplies instruction memory only, never a TES patch result/snapshot.
  * Original Mesa and protected native admission remain separate qualification.
  */
private class DynamicPatchSystemHarness extends Module {
  val config=GpuConfig(lanes=16,warps=8,l2Sets=8,l2Ways=2)
  private val gpu=Module(new GpuSystem(config,numComputeUnits=2,enableGraphicsKernels=true,
    geometryAvailable=true,tessellationAvailable=true,evaluationPatchAvailable=true,
    instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1))
  private val client=Module(new TessellationCuIsolineSystemClient(config,8,2,evaluationPatchAvailable=true))
  val io=IO(new Bundle {
    val system=chiselTypeOf(gpu.io)
    val task=Flipped(Decoupled(new TessellationCuIsolineSystemTask(config,8)))
    val opened=Decoupled(UInt(2.W));val seal=Flipped(Decoupled(Bool()))
    val out=Decoupled(new EvaluatedIsolinePrimitive);val completion=Decoupled(new TessellationCuIsolineCompletion)
    val cancel=Input(Bool());val holdReadResult=Input(Bool());val heldRead=Output(Bool())
    val busy=Output(Bool());val trap=Decoupled(new TaggedGraphicsTrap(config,8,2))
  })
  private def tie(d:Data):Unit=d match {
    case r:Record=>r.elements.values.foreach(tie);case v:Vec[_]=>v.foreach(tie)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b:=false.B
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u:=0.U
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s:=0.S
    case _=>()
  }
  tie(client.io);io.system<>gpu.io;client.io.shared<>gpu.io.graphicsKernels.get
  io.task<>client.io.task;io.opened<>client.io.opened;client.io.seal<>io.seal
  io.out<>client.io.out;io.completion<>client.io.completion;client.io.cancel:=io.cancel
  io.busy:=client.io.busy;io.trap<>client.io.trap
  val ports=client.io.shared.evaluationPatch.get;val peers=gpu.io.graphicsKernels.get.evaluationPatch.get
  io.heldRead:=ports.map(_.result.valid).reduce(_ || _) && io.holdReadResult
  for(i<-0 until 2){
    peers(i).result.valid:=ports(i).result.valid && !io.holdReadResult
    ports(i).result.ready:=peers(i).result.ready && !io.holdReadResult
  }
}

class TessellationDynamicPatchSystemSpec extends AnyFlatSpec {
  import opengpu.graphics.WindowRecordTest._
  private def addi(rd:Int,rs:Int,n:Int):BigInt=(BigInt(n&4095)<<20)|(BigInt(rs)<<15)|(BigInt(rd)<<7)|0x13
  private def vec(f:Int,form:Int,d:Int,a:Int,b:Int):BigInt=(BigInt(f)<<26)|(BigInt(1)<<25)|(BigInt(b)<<20)|(BigInt(a)<<15)|(BigInt(form)<<12)|(BigInt(d)<<7)|0x57
  private def custom(f:Int,d:Int,a:Int,b:Int):BigInt=(BigInt(f)<<26)|(BigInt(1)<<25)|(BigInt(b)<<20)|(BigInt(a)<<15)|(BigInt(d)<<7)|0x2b
  private def geo(f:Int,d:Int=0,a:Int=0,b:Int=0):BigInt=(BigInt(f)<<25)|(BigInt(b)<<20)|(BigInt(a)<<15)|(BigInt(d)<<7)|0x0b
  private def splat(d:Int,n:Int):Seq[BigInt]=Seq(addi(10,0,n),vec(23,4,d,10,0))
  private val control={val p=mutable.ArrayBuffer[BigInt](BigInt("0104f057",16),vec(37,3,3,8,1),BigInt("00001537",16),vec(0,4,3,10,3))
    for(c<-0 until 4){p+=custom(20,0,3,0);p++=splat(4,(c<<3)|1);p+=custom(22,0,1,4);p+=vec(0,3,3,1,3)}
    p+=BigInt("3f800537",16);p+=vec(23,4,3,10,0);p++=splat(2,0)
    for(c<-0 until 6){p+=custom(20,0,3,0);p++=splat(4,(c<<3)|7);p+=custom(22,0,2,4)}
    p+=BigInt("0000000b",16);p+=BigInt("30500073",16);p.toVector}
  private val evaluation=Vector[BigInt](BigInt("0104f057",16),addi(11,0,0),geo(32,10,0,11),
    // Runtime vertex selection comes from the actual generated TessCoord.x.
    (BigInt(23)<<20)|(BigInt(10)<<15)|(BigInt(5)<<12)|(BigInt(10)<<7)|0x13,
    (BigInt(1)<<20)|(BigInt(10)<<15)|(BigInt(7)<<12)|(BigInt(10)<<7)|0x13,
    vec(23,4,2,10,0)) ++ splat(4,(3<<3)|1) ++ Vector(custom(33,5,2,4),
      vec(16,2,12,0,5),addi(10,0,0),geo(33,0,10,12),
      addi(10,0,1),geo(33,0,10,0),addi(10,0,2),geo(33,0,10,0),
      BigInt("3f800637",16),addi(10,0,3),geo(33,0,10,12),geo(35),geo(36),BigInt("30500073",16))
  it should "read real TCS outputs dynamically through the actual CU and PatchContext, drain held results and reuse without reset" in {
    simulate(new DynamicPatchSystemHarness){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
      d.io.system.graphicsDrained.poke(true.B);d.io.system.graphicsShaderL1Invalidate.ready.poke(true.B);d.io.trap.ready.poke(true.B)
      val memory=new Memory(d.io.system.memoryRequest.bits,d.io.system.memoryResponse.bits,
        d.io.system.memoryRequest.valid,d.io.system.memoryRequest.ready,d.io.system.memoryResponse.valid,d.io.system.memoryResponse.ready)
      for((pc,code)<-Seq(0x1000->control,0x2000->evaluation);(word,i)<-code.zipWithIndex)memory.putWord(pc+4*i,word)
      var cancel=false;var outputs=0
      def tick():Unit={memory.drive();memory.capture()
        if(d.io.trap.valid.peek().litToBoolean)assert(cancel,"unexpected actual CU trap")
        if(d.io.out.valid.peek().litToBoolean && d.io.out.ready.peek().litToBoolean){
          d.io.out.bits.vertices(0)(0).expect(0x1003.U);d.io.out.bits.vertices(1)(0).expect(0x1103.U);outputs+=1}
        d.clock.step()}
      def until(p: =>Boolean):Unit={var n=0;while(!p && n<80000){tick();n+=1};assert(p,s"dynamic patch stalled $n")}
      def start():Unit={zero(d.io.task.bits);val t=d.io.task.bits.patch
        t.commandId.poke(7.U);val c=t.control
        c.patch.context.taskId.poke(19.U);c.patch.context.layout.inputVertices.poke(2.U)
        c.patch.context.layout.outputVertices.poke(2.U);c.patch.context.layout.outputComponents.poke(4.U)
        c.kernel.kernelPc.poke(0x1000.U);t.evaluationKernel.kernelPc.poke(0x2000.U);t.outputComponents.poke(4.U)
        for(i<-0 until 3){c.kernel.gridSize(i).poke(1.U);c.kernel.localSize(i).poke((if(i==0)2 else 1).U)
          t.evaluationKernel.gridSize(i).poke(1.U);t.evaluationKernel.localSize(i).poke(1.U)}
        for(w<-0 until 8)c.bases(w).poke((if(w<2)w*16 else 0).U)
        d.io.task.valid.poke(true.B);until(d.io.task.ready.peek().litToBoolean);tick();d.io.task.valid.poke(false.B);zero(d.io.task.bits)
        d.io.opened.ready.poke(true.B);until(d.io.opened.valid.peek().litToBoolean);d.io.opened.bits.expect(0.U);tick();d.io.opened.ready.poke(false.B)
        d.io.seal.valid.poke(true.B);until(d.io.seal.ready.peek().litToBoolean);tick();d.io.seal.valid.poke(false.B)}
      def done(status:Int):Unit={until(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.status.expect(status.U)
        val held=d.io.completion.bits.peek().litValue
        for(_<-0 until 3){tick();assert(d.io.completion.bits.peek().litValue==held);d.io.task.ready.expect(false.B)}
        d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B);d.io.busy.expect(false.B)
        assert(memory.pending.isEmpty && memory.reply.isEmpty)}
      d.io.out.ready.poke(true.B);start();done(0);assert(outputs==1)
      d.io.holdReadResult.poke(true.B);start();until(d.io.heldRead.peek().litToBoolean)
      cancel=true;d.io.cancel.poke(true.B)
      for(_<-0 until 6){tick();d.io.completion.valid.expect(false.B);d.io.busy.expect(true.B)}
      d.io.holdReadResult.poke(false.B);done(4);d.io.cancel.poke(false.B);cancel=false;assert(outputs==1)
      start();done(0);assert(outputs==2)
    }
  }
}
