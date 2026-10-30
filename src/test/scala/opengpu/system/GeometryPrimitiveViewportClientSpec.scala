package opengpu.system

import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.core.memory.CacheLineInvalidate
import opengpu.graphics._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class GeometryViewportGpuHarness(config:GpuConfig) extends Module {
  val gpu=Module(new GpuSystem(config,numComputeUnits=1,commandIdWidth=4,transactionsPerCu=4,
    enableGraphicsKernels=true,geometryAvailable=true,instructionCacheSets=4,instructionCacheWays=1,
    vectorCacheSets=4,vectorCacheWays=1))
  val client=Module(new GeometryPrimitiveViewportClient(config,VertexShaderStageConfig(),4,1))
  val io=IO(new Bundle {
    val system=chiselTypeOf(gpu.io)
    val task=Flipped(Decoupled(new GeometryViewportTask(config,VertexShaderStageConfig(),4)))
    val out=Decoupled(new GeometryWindowPrimitive(VertexShaderStageConfig()))
    val completion=Decoupled(new GeometryPrimitiveViewportResult)
    val cancel=Input(Bool())
    val texture=Vec(1,new SharedCuTexturePort(config))
    val trap=Decoupled(new TaggedGraphicsTrap(config,4,1))
    val invalidate=Flipped(Decoupled(new CacheLineInvalidate(config)))
    val invalidateDone=Decoupled(new CacheLineInvalidate(config))
  })
  io.system<>gpu.io;client.io.shared<>gpu.io.graphicsKernels.get
  client.io.task<>io.task;io.out<>client.io.out;io.completion<>client.io.completion;client.io.cancel:=io.cancel
  io.texture<>client.io.texture;io.trap<>client.io.trap;client.io.invalidate<>io.invalidate;io.invalidateDone<>client.io.invalidateDone
}
class GeometryPrimitiveViewportClientSpec extends AnyFlatSpec {
  private def zero(data:Data):Unit=data match {
    case r:Record=>r.elements.values.foreach(zero)
    case v:Vec[_]=>v.foreach(zero)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case x:SInt if DataMirror.directionOf(x)==ActualDirection.Input=>x.poke(0.S)
    case _=>()
  }
  private def geometry(fn:Int,rd:Int=0,rs1:Int=0,rs2:Int=0):BigInt =
    (BigInt(fn)<<25)|(BigInt(rs2)<<20)|(BigInt(rs1)<<15)|(BigInt(rd)<<7)|0x0b
  it should "prepare real CU geometry through viewport and drain a held output through cancellation" in {
    val config=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2)
    simulate(new GeometryViewportGpuHarness(config)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
      val sys=d.io.system;sys.graphicsDrained.poke(true.B);sys.graphicsShaderL1Invalidate.ready.poke(true.B)
      d.io.trap.ready.poke(true.B);d.io.invalidateDone.ready.poke(true.B)
      val program=Vector(BigInt("00200093",16),BigInt("00000113",16),geometry(32,3,1,2),
        BigInt("00000213",16),geometry(33,0,4,0),BigInt("00100213",16),geometry(33,0,4,0),
        BigInt("00200213",16),geometry(33,0,4,3),BigInt("00300213",16),BigInt("3f800337",16),
        geometry(33,0,4,6),BigInt("05d00293",16),geometry(38,0,5),BigInt("00900393",16),geometry(34,0,7),geometry(35),geometry(36),BigInt("30500073",16))
      val code=program.zipWithIndex.map{case(word,i)=>word<<(32*i)}.sum
      val pending=mutable.Queue.empty[(BigInt,BigInt,Int)];var cycle=0;var round=0;var abortIssued=false;var cancellationTraps=0
      def tick():Unit={
        sys.memoryRequest.ready.poke((cycle%3!=0).B);val reply=pending.headOption.filter(_._3<=cycle)
        sys.memoryResponse.valid.poke(reply.nonEmpty.B);reply.foreach(r=>{sys.memoryResponse.bits.transactionId.poke(r._1.U);sys.memoryResponse.bits.readData.poke(r._2.U)})
        if(reply.nonEmpty && sys.memoryResponse.ready.peek().litToBoolean)pending.dequeue()
        if(sys.memoryRequest.valid.peek().litToBoolean && sys.memoryRequest.ready.peek().litToBoolean){
          val addr=sys.memoryRequest.bits.address.peek().litValue;assert(addr==0x1000 || addr==0x1040)
          val data=(code>>((addr-0x1000).toInt*8)) & ((BigInt(1)<<512)-1)
          pending.enqueue((sys.memoryRequest.bits.transactionId.peek().litValue,data,cycle+3))}
        if(d.io.trap.valid.peek().litToBoolean) {
          val e=d.io.trap.bits.event
          assert(round==1 && abortIssued, s"unexpected trap pc=0x${e.pc.peek().litValue.toString(16)} cause=${e.cause.peek().litValue} cycle=$cycle")
          e.cause.expect(5.U);e.tval.expect(0.U);cancellationTraps+=1;assert(cancellationTraps<=1)
        }
        d.clock.step();cycle+=1
      }
      def until(label:String)(p: => Boolean):Unit={var n=0;while(!p && n<30000){tick();n+=1};assert(p,s"$label stalled at $cycle")}
      def submit(outside:Boolean):Unit={val x=d.io.task.bits.geometry.primitive;x.commandId.poke(3.U);x.kernel.kernelPc.poke(0x1000.U);x.firstSequence.poke(20.U)
        val q=x.input;q.taskId.poke(19.U);q.primitive.primitiveId.poke(27.U);q.primitive.kind.poke(2.U)
        q.primitive.vertexCount.poke(3.U);q.primitive.varyingSlots.poke(1.U);q.primitive.vertices(2).position(0).poke((if(outside)BigInt("40000000",16) else BigInt("3f000000",16)).U)
        q.inputComponents.poke(1.U);q.invocations.poke(1.U);q.outputTopology.poke(0.U);q.maxVertices.poke(1.U)
        q.outputComponents.poke(4.U);q.builtinOutputMask.poke(3.U)
        for(c<-0 until 4){x.outputSources(c).valid.poke(true.B);x.outputSources(c).bits.poke(c.U)}
        val view=d.io.task.bits.viewport;view.version.poke(1.U);view.extentX.poke(4.U);view.extentY.poke(4.U)
        view.nearDepth.poke(0.U);view.farDepth.poke("h3f800000".U)
        d.io.task.valid.poke(true.B);until("task")(d.io.task.ready.peek().litToBoolean);tick();d.io.task.valid.poke(false.B)
        q.primitive.vertices(2).position(0).poke(0.U)
      }
      for((cancel,outside)<-Seq((false,false),(true,false),(false,true))){
        round=if(cancel)1 else 0
        submit(outside)
        if(!outside){
          until("clipped output")(d.io.out.valid.peek().litToBoolean)
          val o=d.io.out.bits;o.inputPrimitiveId.expect(27.U);o.invocationId.expect(0.U);o.layer.expect(9.U)
          o.window.clipped.primitive.sequence.expect(20.U);o.window.clipped.primitive.primitiveId.expect(93.U)
          o.window.clipped.primitive.vertices(0).position(0).expect(0.U)
          o.window.clipped.primitive.vertices(0).position(2).expect("h3f000000".U)
          o.window.clipped.primitive.vertices(0).position(3).expect("h3f800000".U)
          o.window.vertices(0).screenX.expect(512.S);o.window.vertices(0).screenY.expect(512.S)
          o.window.vertices(0).windowDepth.expect("h3f400000".U);o.window.vertices(0).fixedValid.expect(true.B)
          val held=o.peek().litValue
          if(cancel){abortIssued=true;d.io.cancel.poke(true.B);tick();d.io.cancel.poke(false.B)}
          for(_<-0 until 3){d.io.out.valid.expect(true.B);assert(o.peek().litValue==held);d.io.completion.valid.expect(false.B);tick()}
          d.io.out.ready.poke(true.B);tick();d.io.out.ready.poke(false.B)
        }
        until("completion")(d.io.completion.valid.peek().litToBoolean);val r=d.io.completion.bits
        r.status.expect((if(cancel)4 else 0).U);r.source.clipInputs.expect(1.U)
        r.source.clipOutputs.expect((if(outside)0 else 1).U);r.source.culled.expect((if(outside)1 else 0).U)
        r.viewportInputs.expect((if(outside)0 else 1).U);r.viewportOutputs.expect((if(outside)0 else 1).U)
        if(outside)d.io.out.valid.expect(false.B)
        r.source.geometry.producedPrimitives.expect(1.U);val result=r.peek().litValue
        for(_<-0 until 2){d.io.task.ready.expect(false.B);assert(r.peek().litValue==result);tick()}
        d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B)
      }
      assert(pending.isEmpty)
      d.io.task.bits.viewport.version.poke(0.U);d.io.task.valid.poke(true.B)
      until("invalid viewport admission")(d.io.task.ready.peek().litToBoolean);tick();d.io.task.valid.poke(false.B)
      until("invalid viewport completion")(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.status.expect(1.U);d.io.out.valid.expect(false.B)
    }
  }
}
