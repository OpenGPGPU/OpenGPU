package opengpu.system

import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.core.memory._
import opengpu.graphics._
import opengpu.testutil.GpuSim._
import opengpu.testutil.GraphicsTestResolution
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class IsolineFragmentGpuHarness extends Module {
  val cfg=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2);val p=FragmentShaderConfig(1,1)
  val g=GraphicsConfig(screenWidth=math.min(4,GraphicsTestResolution.width),screenHeight=math.min(4,GraphicsTestResolution.height),maxSampleCount=1)
  private val system=Module(new GpuSystem(cfg,numComputeUnits=1,commandIdWidth=4,transactionsPerCu=4,
    enableGraphicsKernels=true,geometryAvailable=true,graphicsHostTransactions=4,
    instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1))
  private val client=Module(new IsolineFragmentPipeline(cfg,p,SharedCuShaderConfig(1,taskIdWidth=4),g,interpolationContexts=2))
  val io=IO(new Bundle {
    val draw=Flipped(Decoupled(new IsolineFragmentDraw(cfg,p,4,None)))
    val out=Decoupled(new FragmentShaderQuad(p,g));val completion=Decoupled(new IsolineFragmentCompletion)
    val memoryRequest=Decoupled(new ComputeMemoryRequest(cfg,64,GpuSystem.totalMemoryTransactions(1,4,4)))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,GpuSystem.totalMemoryTransactions(1,4,4))))
    val cancel=Input(Bool());val trap=Output(Bool());val busy=Output(Bool())
  })
  private def tie(d:Data):Unit=d match {
    case r:Record=>r.elements.values.foreach(tie)
    case v:Vec[_]=>v.foreach(tie)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b:=false.B
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u:=0.U
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s:=0.S
    case _=>()
  }
  tie(system.io);tie(client.io)
  client.io.draw<>io.draw;io.out<>client.io.out;io.completion<>client.io.completion;client.io.cancel:=io.cancel
  client.io.shared<>system.io.graphicsKernels.get
  system.io.graphicsHostRequest<>client.io.memoryRequest;client.io.memoryResponse<>system.io.graphicsHostResponse
  system.io.graphicsDrained:= !client.io.busy;system.io.graphicsShaderL1Invalidate.ready:=true.B
  client.io.geometryTrap.ready:=true.B;io.trap:=client.io.geometryTrap.valid
  client.io.geometryInvalidateDone.ready:=true.B
  io.memoryRequest<>system.io.memoryRequest;system.io.memoryResponse<>io.memoryResponse;io.busy:=client.io.busy
}

class IsolineFragmentPipelineQualificationSpec extends AnyFlatSpec {
  private def zero(d:Data):Unit=d match {
    case r:Record=>r.elements.values.foreach(zero)
    case v:Vec[_]=>v.foreach(zero)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case _=>()
  }
  private def geometry(fn:Int,rd:Int=0,rs1:Int=0,rs2:Int=0):BigInt =
    (BigInt(fn)<<25)|(BigInt(rs2)<<20)|(BigInt(rs1)<<15)|(BigInt(rd)<<7)|0x0b
  private def addi(rd:Int,rs:Int,imm:Int):BigInt=(BigInt(imm&4095)<<20)|(BigInt(rs)<<15)|(BigInt(rd)<<7)|0x13
  private def add(rd:Int,a:Int,b:Int):BigInt=(BigInt(b)<<20)|(BigInt(a)<<15)|(BigInt(rd)<<7)|0x33
  private def vmem(base:Int,reg:Int,write:Boolean):BigInt=(BigInt(1)<<25)|(BigInt(base)<<15)|(BigInt(6)<<12)|(BigInt(reg)<<7)|(if(write)0x27 else 7)
  it should "shade a generated isoline on the shared CU pool" in {
    simulate(new IsolineFragmentGpuHarness){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
      val mem=mutable.Map.empty[BigInt,BigInt]
      def put(a:BigInt,v:BigInt):Unit=for(i<-0 until 4)mem(a+i)=(v>>(8*i))&255
      val gs=Vector(BigInt("00000093",16),BigInt("00000113",16),geometry(32,3,1,2),
        BigInt("00000213",16),geometry(33,0,4,3),BigInt("00100113",16),geometry(32,3,1,2),
        BigInt("00100213",16),geometry(33,0,4,3),BigInt("3f8002b7",16),BigInt("00300213",16),
        geometry(33,0,4,5),geometry(35),geometry(36),BigInt("30500073",16))
      gs.zipWithIndex.foreach{case(w,i)=>put(0x1000+4*i,w)}
      // Center ABI has six input arrays; depth and control are output arrays.
      val stride=32;val inputs=6
      val fs=mutable.ArrayBuffer[BigInt]((BigInt(2)<<20)|(BigInt(8)<<15)|(BigInt(1)<<12)|(BigInt(7)<<7)|0x13,
        add(7,1,7),(BigInt(3)<<30)|(BigInt(0x10)<<20)|(BigInt(4)<<15)|(BigInt(7)<<12)|0x57)
      for(i<-0 until 2){fs+=addi(5,7,i*stride);fs+=vmem(5,1,false);fs+=addi(6,7,(inputs+i)*stride);fs+=vmem(6,1,true)}
      fs+=BigInt("30500073",16);fs.zipWithIndex.foreach{case(w,i)=>put(0x2000+4*i,w)}
      val pending=mutable.ArrayBuffer.empty[(BigInt,BigInt,Int)];var cycle=0;var covered=0
      def tick():Unit={
        d.io.memoryRequest.ready.poke((cycle%3!=0).B);val ready=pending.indexWhere(_._3<=cycle)
        d.io.memoryResponse.valid.poke((ready>=0).B)
        if(ready>=0){val r=pending(ready);d.io.memoryResponse.bits.transactionId.poke(r._1.U);d.io.memoryResponse.bits.readData.poke(r._2.U)}
        if(ready>=0 && d.io.memoryResponse.ready.peek().litToBoolean)pending.remove(ready)
        if(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean){
          val r=d.io.memoryRequest.bits;val a=r.address.peek().litValue;val write=r.isWrite.peek().litToBoolean
          if(write){val mask=r.byteMask.peek().litValue;val bits=r.writeData.peek().litValue
            for(i<-0 until 64 if mask.testBit(i))mem(a+i)=(bits>>(8*i))&255}
          val data=(0 until 64).foldLeft(BigInt(0))((v,i)=>v|(mem.getOrElse(a+i,BigInt(0))<<(8*i)))
          pending+=((r.transactionId.peek().litValue,if(write)BigInt(0)else data,cycle+3))
        }
        if(d.io.out.valid.peek().litToBoolean && d.io.out.ready.peek().litToBoolean){val q=d.io.out.bits
          q.primitiveId.expect(12.U);q.sequence.expect(10.U)
          for(l<-q.lanes if l.coverageMask.peek().litValue!=0){
            val x=l.x.peek().litValue.toInt;val y=l.y.peek().litValue.toInt
            assert(x>=0 && x<4 && y>=0 && y<4)
            l.colors(0)(0).expect((java.lang.Float.floatToRawIntBits(x.toFloat+0.5f).toLong&0xffffffffL).U)
            l.colors(0)(1).expect((java.lang.Float.floatToRawIntBits(y.toFloat+0.5f).toLong&0xffffffffL).U)
            covered+=1
          }}
        d.io.trap.expect(false.B);d.clock.step();cycle+=1
      }
      def until(label:String)(p: => Boolean):Unit={var n=0;while(!p && n<100000){tick();n+=1};assert(p,s"$label stalled at $cycle")}
      val draw=d.io.draw.bits;val raster=draw.raster;val viewport=raster.source.viewport
      viewport.version.poke(1.U);viewport.extentX.poke(4.U);viewport.extentY.poke(4.U);viewport.farDepth.poke("h3f800000".U)
      raster.raster.version.poke(1.U);raster.raster.screenW.poke(4.U);raster.raster.screenH.poke(4.U);raster.depthFormat.poke(TypedDepthFormat.d24.U)
      val task=raster.source.source;task.commandId.poke(3.U);task.kernel.kernelPc.poke(0x1000.U)
      val r=task.renderer;r.tessellation.taskId.poke(19.U);r.tessellation.spacing.poke(TessellationSpacing.equal.U)
      r.tessellation.outer(0).poke("h3f800000".U);r.tessellation.outer(1).poke("h3f800000".U)
      r.firstSequence.poke(10.U);r.firstPrimitiveId.poke(12.U);r.outputComponents.poke(4.U);r.varyingSlots.poke(0.U)
      for(i<-Seq(0,1,3)){r.componentSources(i).valid.poke(true.B);r.componentSources(i).bits.poke(i.U)}
      val f=draw.fragment;f.version.poke(FragmentInputAbi.center.U);f.shaderPc.poke(0x2000.U)
      f.colorSlots.poke(1.U);f.colorMask.poke(1.U);val prefix=d.p.prefixBytes(8,0,1,FragmentInputAbi.center)
      f.scratch.base.poke(0x100000.U);f.scratch.limit.poke((0x100000+prefix).U)
      f.scratch.allocationBase.poke(0x100000.U);f.scratch.allocationLimit.poke((0x100000+prefix).U)
      f.sampler.texWidth.poke(1.U);f.sampler.texHeight.poke(1.U)
      d.io.out.ready.poke(true.B);d.io.draw.valid.poke(true.B);until("draw")(d.io.draw.ready.peek().litToBoolean)
      tick();d.io.draw.valid.poke(false.B);until("completion")(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.status.expect(0.U);assert(covered>0 && pending.isEmpty)
      d.io.completion.bits.raster.inputs.expect(1.U);assert(d.io.completion.bits.fragment.outputQuads.peek().litValue>0)
      d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B)
      d.io.out.ready.poke(false.B);d.io.draw.valid.poke(true.B);until("second draw")(d.io.draw.ready.peek().litToBoolean)
      tick();d.io.draw.valid.poke(false.B);until("held fragment")(d.io.out.valid.peek().litToBoolean)
      val held=d.io.out.bits.peek().litValue;d.io.cancel.poke(true.B)
      for(_<-0 until 3){d.io.out.valid.expect(true.B);assert(d.io.out.bits.peek().litValue==held);tick()}
      d.io.out.ready.poke(true.B);tick();d.io.out.ready.poke(false.B);d.io.cancel.poke(false.B)
      until("cancel completion")(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.status.expect(4.U)
      d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B)
      f.shaderPc.poke(0.U);d.io.draw.valid.poke(true.B);until("invalid draw")(d.io.draw.ready.peek().litToBoolean)
      tick();d.io.draw.valid.poke(false.B);until("invalid completion")(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.status.expect(1.U);d.io.out.valid.expect(false.B);assert(pending.isEmpty)
    }
  }
}
