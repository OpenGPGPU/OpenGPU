package opengpu.graphics

import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.core.GpuComputeUnit
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class CuIsolinePatchHarness(config:GpuConfig) extends Module {
  val computeUnit=Module(new GpuComputeUnit(config,finishOnTrap=true,textureAvailable=false,nativeResourcesAvailable=false,
    tessellationAvailable=true,instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1))
  val owner=Module(new TessellationCuIsolinePatch(config))
  val io=IO(new Bundle {
    val cu=chiselTypeOf(computeUnit.io);val task=Flipped(Decoupled(new TessellationCuIsolineTask(config)));val opened=Decoupled(UInt(2.W))
    val seal=Flipped(Decoupled(Bool()));val request=Decoupled(new IsolineEvaluationRequest)
    val response=Flipped(Decoupled(new IsolineEvaluationResponse));val out=Decoupled(new EvaluatedIsolinePrimitive)
    val completion=Decoupled(new TessellationCuIsolineCompletion);val cancel=Input(Bool())
  })
  io.cu<>computeUnit.io;owner.io.task<>io.task;io.opened<>owner.io.opened;owner.io.seal<>io.seal
  owner.io.load.valid:=false.B;owner.io.load.bits:=0.U.asTypeOf(owner.io.load.bits)
  owner.io.inspect.valid:=false.B;owner.io.inspect.bits:=0.U.asTypeOf(owner.io.inspect.bits);owner.io.reply.ready:=true.B
  owner.io.request<>io.request;owner.io.response<>io.response;io.out<>owner.io.out;io.completion<>owner.io.completion;owner.io.cancel:=io.cancel
  computeUnit.io.kernel<>owner.io.kernel;owner.io.kernelCompletion<>computeUnit.io.completion
  computeUnit.io.tessellationEnabled.get:=true.B;computeUnit.io.tessellationBases.get:=owner.io.bases
  computeUnit.io.tessellationCancel.get:=owner.io.cancelCu;computeUnit.io.tessellationTerminal.get:=owner.io.terminalCu
  owner.io.access<>computeUnit.io.tessellationRequest.get;computeUnit.io.tessellationResult.get<>owner.io.accessResult
  owner.io.park<>computeUnit.io.tessellationPark.get;computeUnit.io.tessellationResume.get<>owner.io.resume
  owner.io.finish<>computeUnit.io.tessellationFinish.get
}

class TessellationCuIsolinePatchSpec extends AnyFlatSpec {
  private def zero(d:Data):Unit=d match {
    case r:Record=>r.elements.values.foreach(zero);case v:Vec[_]=>v.foreach(zero)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case _=>()
  }
  private def vector(fn:Int,form:Int,rd:Int,vs1:Int):BigInt=(BigInt(fn)<<26)|(BigInt(1)<<25)|(BigInt(vs1)<<15)|(BigInt(form)<<12)|(BigInt(rd)<<7)|0x57
  private def patch(fn:Int,rd:Int=0,vs1:Int=0,vs2:Int=0):BigInt=(BigInt(fn)<<26)|(BigInt(1)<<25)|(BigInt(vs2)<<20)|(BigInt(vs1)<<15)|(BigInt(rd)<<7)|0x2b
  it should "generate an isoline from levels written by a real control CU" in {
    val config=GpuConfig(lanes=4,warps=2)
    simulate(new CuIsolinePatchHarness(config)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
      val c=d.io.cu;c.l1InvalidateDone.ready.poke(true.B);c.trap.ready.poke(true.B)
      val program=Vector(BigInt("0104f057",16),BigInt("3f800537",16),vector(23,4,3,10),vector(23,3,2,0),
        vector(23,3,4,7),patch(20,vs1=3),patch(22,vs1=2,vs2=4),vector(23,3,4,15),patch(22,vs1=2,vs2=4),
        BigInt("0000000b",16),BigInt("30500073",16))
      val code=program.zipWithIndex.map{case(w,i)=>w<<(32*i)}.sum;val pending=mutable.Queue.empty[(BigInt,BigInt)]
      var cycle=0;var requests=0;var outputs=0;var reply=Option.empty[(BigInt,BigInt,BigInt,Vector[BigInt])]
      def tick():Unit={c.memoryRequest.ready.poke((cycle%3!=0).B);c.memoryResponse.valid.poke(pending.nonEmpty.B)
        pending.headOption.foreach{case(id,data)=>c.memoryResponse.bits.transactionId.poke(id.U);c.memoryResponse.bits.readData.poke(data.U)}
        if(pending.nonEmpty && c.memoryResponse.ready.peek().litToBoolean)pending.dequeue()
        if(c.memoryRequest.valid.peek().litToBoolean && c.memoryRequest.ready.peek().litToBoolean){
          c.memoryRequest.bits.address.expect(0x1000.U);c.memoryRequest.bits.isWrite.expect(false.B)
          pending.enqueue((c.memoryRequest.bits.transactionId.peek().litValue,code))}
        c.trap.valid.expect(false.B);d.io.request.ready.poke((reply.isEmpty && cycle%3!=0).B)
        if(d.io.request.valid.peek().litToBoolean && d.io.request.ready.peek().litToBoolean){val q=d.io.request.bits
          reply=Some((q.taskId.peek().litValue,q.sequence.peek().litValue,q.vertex.peek().litValue,q.tessCoord.map(_.peek().litValue).toVector));requests+=1}
        d.io.response.valid.poke(reply.nonEmpty.B);reply.foreach{case(id,seq,vertex,coords)=>val r=d.io.response.bits
          r.taskId.poke(id.U);r.sequence.poke(seq.U);r.vertex.poke(vertex.U);r.failed.poke(false.B)
          for(i<-0 until 3)r.components(i).poke(coords(i).U)}
        if(reply.nonEmpty && d.io.response.ready.peek().litToBoolean)reply=None
        d.io.out.ready.poke((cycle%4!=0).B)
        if(d.io.out.valid.peek().litToBoolean && d.io.out.ready.peek().litToBoolean){
          d.io.out.bits.vertices(0)(0).expect(0.U);d.io.out.bits.vertices(1)(0).expect("h3f800000".U);outputs+=1}
        d.clock.step();cycle+=1}
      def until(label:String)(p: =>Boolean):Unit={var n=0;while(!p && n<40000){tick();n+=1};assert(p,s"$label timeout")}
      val t=d.io.task.bits;t.patch.context.taskId.poke(31.U);t.patch.context.layout.inputVertices.poke(1.U)
      t.patch.context.layout.outputVertices.poke(4.U);t.kernel.kernelPc.poke(0x1000.U)
      for(i<-0 until 3){t.kernel.gridSize(i).poke(1.U);t.kernel.localSize(i).poke((if(i==0)4 else 1).U)}
      t.bases(0).poke(0.U);t.bases(1).poke(4.U);d.io.task.valid.poke(true.B);until("task")(d.io.task.ready.peek().litToBoolean)
      tick();d.io.task.valid.poke(false.B);d.io.opened.ready.poke(true.B);until("open")(d.io.opened.valid.peek().litToBoolean)
      d.io.opened.bits.expect(0.U);tick();d.io.opened.ready.poke(false.B);d.io.seal.valid.poke(true.B)
      until("seal")(d.io.seal.ready.peek().litToBoolean);tick();d.io.seal.valid.poke(false.B)
      until("completion")(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.status.expect(0.U)
      d.io.completion.bits.control.barriers.expect(1.U);d.io.completion.bits.control.completed.expect(4.U)
      d.io.completion.bits.generated.emitted.expect(1.U);d.io.completion.bits.published.expect(1.U)
      assert(requests==2 && outputs==1 && pending.isEmpty && reply.isEmpty)
    }
  }
}
