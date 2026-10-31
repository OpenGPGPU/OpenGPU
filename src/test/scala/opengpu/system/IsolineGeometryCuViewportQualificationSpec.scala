package opengpu.system

import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.graphics._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class IsolineGeometryCuViewportGpuHarness(config:GpuConfig,p:VertexShaderStageConfig) extends Module {
  val gpu=Module(new GpuSystem(config,numComputeUnits=1,commandIdWidth=4,transactionsPerCu=4,
    enableGraphicsKernels=true,geometryAvailable=true,instructionCacheSets=4,instructionCacheWays=1,
    vectorCacheSets=4,vectorCacheWays=1))
  val renderer=Module(new IsolineGeometryCuViewport(config,p,4,1))
  val io=IO(new Bundle {
    val system=chiselTypeOf(gpu.io)
    val task=Flipped(Decoupled(new IsolineGeometryCuViewportTask(config,p,4)))
    val primitive=Decoupled(new WindowPrimitive(p));val completion=Decoupled(new IsolineGeometryCuViewportCompletion)
    val cancel=Input(Bool())
  })
  io.system<>gpu.io;renderer.io.task<>io.task;io.primitive<>renderer.io.out
  io.completion<>renderer.io.completion;renderer.io.cancel:=io.cancel
  val g=gpu.io.graphicsKernels.get;g.launch<>renderer.io.launch;g.geometryTask.get:=renderer.io.geometryTask
  renderer.io.kernelCompletion<>g.completion;renderer.io.event<>g.geometryEvent.get
  renderer.io.owner:=g.owned;renderer.io.taskIds:=g.taskIds;g.geometryCancel.get:=renderer.io.cancelCu
}

class IsolineGeometryCuViewportQualificationSpec extends AnyFlatSpec {
  private def zero(data:Data):Unit=data match {
    case r:Record=>r.elements.values.foreach(zero)
    case v:Vec[_]=>v.foreach(zero)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case _=>()
  }
  private def geometry(fn:Int,rd:Int=0,rs1:Int=0,rs2:Int=0):BigInt =
    (BigInt(fn)<<25)|(BigInt(rs2)<<20)|(BigInt(rs1)<<15)|(BigInt(rd)<<7)|0x0b
  it should "generate, evaluate, clip and prepare an isoline on a real shared CU" in {
    val config=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2);val p=VertexShaderStageConfig(varyings=1)
    simulate(new IsolineGeometryCuViewportGpuHarness(config,p)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
      val sys=d.io.system;sys.graphicsDrained.poke(true.B);sys.graphicsShaderL1Invalidate.ready.poke(true.B)
      sys.graphicsKernels.get.trap.ready.poke(true.B)
      val program=Vector(BigInt("00000093",16),BigInt("00000113",16),geometry(32,3,1,2),
        BigInt("00000213",16),geometry(33,0,4,3),BigInt("00100113",16),geometry(32,3,1,2),
        BigInt("00100213",16),geometry(33,0,4,3),BigInt("3f8002b7",16),BigInt("00300213",16),geometry(33,0,4,5),geometry(35),geometry(36),BigInt("30500073",16))
      val code=program.zipWithIndex.map{case(word,i)=>word<<(32*i)}.sum
      val task=d.io.task.bits;val view=task.viewport;view.version.poke(1.U);view.extentX.poke(4.U);view.extentY.poke(4.U);view.nearDepth.poke(0.U);view.farDepth.poke("h3f800000".U);val t=task.source;t.commandId.poke(3.U);t.kernel.kernelPc.poke(0x1000.U)
      val r=t.renderer;r.tessellation.taskId.poke(19.U);r.tessellation.spacing.poke(TessellationSpacing.equal.U)
      r.tessellation.outer(0).poke("h3f800000".U);r.tessellation.outer(1).poke("h3f800000".U)
      r.tessellation.pointMode.poke(false.B);r.firstSequence.poke(10.U);r.firstPrimitiveId.poke(12.U)
      r.identity.context.poke(5.U);r.outputComponents.poke(4.U);r.varyingSlots.poke(0.U)
      for(i<-0 until 2){r.componentSources(i).valid.poke(true.B);r.componentSources(i).bits.poke(i.U)}
      r.componentSources(3).valid.poke(true.B);r.componentSources(3).bits.poke(3.U)
      d.io.task.valid.poke(true.B);d.io.task.ready.expect(true.B);d.clock.step();d.io.task.valid.poke(false.B)
      val pending=mutable.Queue.empty[(BigInt,Int)];var cycle=0;var outputs=0;var held:Option[BigInt]=None
      while(!d.io.completion.valid.peek().litToBoolean && cycle<30000){
        sys.memoryRequest.ready.poke((cycle%3!=0).B);val reply=pending.headOption.filter(_._2<=cycle)
        sys.memoryResponse.valid.poke(reply.nonEmpty.B)
        reply.foreach(x=>{sys.memoryResponse.bits.transactionId.poke(x._1.U);sys.memoryResponse.bits.readData.poke(code.U)})
        if(reply.nonEmpty && sys.memoryResponse.ready.peek().litToBoolean)pending.dequeue()
        if(sys.memoryRequest.valid.peek().litToBoolean && sys.memoryRequest.ready.peek().litToBoolean){
          sys.memoryRequest.bits.address.expect(0x1000.U);pending.enqueue((sys.memoryRequest.bits.transactionId.peek().litValue,cycle+3))}
        d.io.primitive.ready.poke((cycle%7<4).B)
        if(d.io.primitive.valid.peek().litToBoolean){val window=d.io.primitive.bits;window.vertices(0).screenX.expect(512.S);window.vertices(1).screenX.expect(1024.S);window.vertices(0).screenY.expect(512.S);val o=window.clipped.primitive
          o.identity.context.expect(5.U);o.sequence.expect(10.U);o.primitiveId.expect(12.U);o.vertexCount.expect(2.U)
          o.vertices(0).position(0).expect(0.U);o.vertices(1).position(0).expect("h3f800000".U)
          o.vertices(0).position(1).expect(0.U);o.vertices(1).position(1).expect(0.U)
          o.vertices(0).position(3).expect("h3f800000".U);o.vertices(1).position(3).expect("h3f800000".U)
          val value=o.peek().litValue;held.foreach(v=>assert(v==value))
          if(d.io.primitive.ready.peek().litToBoolean){outputs+=1;held=None}else held=Some(value)
        }else assert(held.isEmpty)
        sys.graphicsKernels.get.trap.valid.expect(false.B);d.clock.step();cycle+=1
      }
      assert(cycle<30000 && pending.isEmpty && outputs==1)
      d.io.completion.bits.status.expect(0.U);d.io.completion.bits.source.source.generated.expect(1.U)
      d.io.completion.bits.source.source.evaluated.expect(1.U);d.io.completion.bits.outputs.expect(1.U)
    }
  }
}
