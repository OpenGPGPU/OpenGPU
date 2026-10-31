package opengpu.graphics

import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.core.memory._
import opengpu.system._
import opengpu.testutil.GpuSim._
import opengpu.testutil.GraphicsTestResolution
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class GeometryFragmentGpuHarness extends Module {
  val cfg=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2)
  val p=FragmentShaderConfig(1,1)
  val g=GraphicsConfig(screenWidth=math.min(4,GraphicsTestResolution.width),screenHeight=math.min(4,GraphicsTestResolution.height),maxSampleCount=1)
  private val system=Module(new GpuSystem(cfg,numComputeUnits=1,commandIdWidth=4,transactionsPerCu=4,
    enableGraphicsKernels=true,geometryAvailable=true,graphicsHostTransactions=4,
    instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1))
  private val client=Module(new GeometryFragmentPipeline(cfg,p,SharedCuShaderConfig(1,taskIdWidth=4),g,interpolationContexts=2))
  val io=IO(new Bundle {
    val draw=Flipped(Decoupled(new GeometryFragmentDraw(cfg,p,4,None)))
    val out=Decoupled(new FragmentShaderQuad(p,g));val completion=Decoupled(new GeometryFragmentCompletion)
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

class GeometryFragmentPipelineSpec extends AnyFlatSpec {
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
  it should "execute geometry into a real fragment shader through one shared CU" in {
    simulate(new GeometryFragmentGpuHarness){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
      val mem=mutable.Map.empty[BigInt,BigInt]
      def put(a:BigInt,v:BigInt):Unit=for(i<-0 until 4)mem(a+i)=(v>>(8*i))&255
      val gs=Vector(BigInt("00200093",16),BigInt("00000113",16),geometry(32,3,1,2),
        BigInt("00000213",16),geometry(33,0,4,0),BigInt("00100213",16),geometry(33,0,4,0),
        BigInt("00200213",16),geometry(33,0,4,3),BigInt("00300213",16),BigInt("3f800337",16),
        geometry(33,0,4,6),BigInt("05d00293",16),geometry(38,0,5),BigInt("00900393",16),geometry(34,0,7),geometry(35),geometry(36),BigInt("30500073",16))
      gs.zipWithIndex.foreach{case(w,i)=>put(0x1000+4*i,w)}
      val stride=32;val inputs=14
      val fs=mutable.ArrayBuffer[BigInt]((BigInt(2)<<20)|(BigInt(8)<<15)|(BigInt(1)<<12)|(BigInt(7)<<7)|0x13,
        add(7,1,7),(BigInt(3)<<30)|(BigInt(0x10)<<20)|(BigInt(4)<<15)|(BigInt(7)<<12)|0x57)
      for(i<-0 until 2){fs+=addi(5,7,(inputs-2+i)*stride);fs+=vmem(5,1,false);fs+=addi(6,7,(inputs+i)*stride);fs+=vmem(6,1,true)}
      fs+=BigInt("30500073",16);fs.zipWithIndex.foreach{case(w,i)=>put(0x2000+4*i,w)}
      val pending=mutable.ArrayBuffer.empty[(BigInt,BigInt,Int)];var cycle=0;var outputs=0
      var expectedId=93;var expectedLayer=9;val covered=mutable.Set.empty[(Int,Int)]
      def tick():Unit={
        d.io.memoryRequest.ready.poke((cycle%3!=0).B)
        val ready=pending.indexWhere(_._3<=cycle);d.io.memoryResponse.valid.poke((ready>=0).B)
        if(ready>=0){val r=pending(ready);d.io.memoryResponse.bits.transactionId.poke(r._1.U);d.io.memoryResponse.bits.readData.poke(r._2.U)}
        if(ready>=0 && d.io.memoryResponse.ready.peek().litToBoolean)pending.remove(ready)
        if(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean){
          val r=d.io.memoryRequest.bits;val a=r.address.peek().litValue;val write=r.isWrite.peek().litToBoolean
          if(write){val mask=r.byteMask.peek().litValue;val bits=r.writeData.peek().litValue
            for(i<-0 until 64 if mask.testBit(i))mem(a+i)=(bits>>(8*i))&255}
          val data=(0 until 64).foldLeft(BigInt(0))((v,i)=>v|(mem.getOrElse(a+i,BigInt(0))<<(8*i)))
          pending+=((r.transactionId.peek().litValue,if(write)BigInt(0)else data,cycle+3))
        }
        if(d.io.out.valid.peek().litToBoolean && d.io.out.ready.peek().litToBoolean){
          val q=d.io.out.bits;q.primitiveId.expect(expectedId.U)
          for(l<-q.lanes if l.coverageMask.peek().litValue!=0){l.colors(0)(0).expect(expectedId.U);l.colors(0)(1).expect(expectedLayer.U)
            covered+=((l.x.peek().litValue.toInt,l.y.peek().litValue.toInt));outputs+=1}
        }
        d.io.trap.expect(false.B);d.clock.step();cycle+=1
      }
      def until(label:String)(p: => Boolean):Unit={var n=0;while(!p && n<60000){tick();n+=1};assert(p,s"$label stalled at $cycle")}
      val x=d.io.draw.bits.raster.geometry.geometry.primitive;x.commandId.poke(3.U);x.kernel.kernelPc.poke(0x1000.U);x.firstSequence.poke(20.U)
      val q=x.input;q.taskId.poke(19.U);q.primitive.primitiveId.poke(27.U);q.primitive.kind.poke(2.U);q.primitive.vertexCount.poke(3.U);q.primitive.varyingSlots.poke(1.U)
      q.primitive.vertices(2).position(0).poke("h3f000000".U);q.inputComponents.poke(1.U);q.invocations.poke(1.U)
      q.outputTopology.poke(0.U);q.maxVertices.poke(1.U);q.outputComponents.poke(4.U);q.builtinOutputMask.poke(3.U)
      x.varyingSlots.poke(1.U);for(c<-0 until 4){x.outputSources(c).valid.poke(true.B);x.outputSources(c).bits.poke(c.U)}
      val view=d.io.draw.bits.raster.geometry.viewport;view.version.poke(1.U);view.extentX.poke(4.U);view.extentY.poke(4.U);view.farDepth.poke("h3f800000".U)
      val raster=d.io.draw.bits.raster.raster;raster.version.poke(1.U);raster.screenW.poke(4.U);raster.screenH.poke(4.U)
      d.io.draw.bits.raster.depthFormat.poke(TypedDepthFormat.d24.U)
      val f=d.io.draw.bits.fragment;f.version.poke(FragmentInputAbi.geometry.U);f.shaderPc.poke(0x2000.U);f.varyingSlots.poke(1.U);f.colorSlots.poke(1.U);f.colorMask.poke(1.U)
      val prefix=d.p.prefixBytes(8,1,1,FragmentInputAbi.geometry);f.scratch.base.poke(0x100000.U);f.scratch.limit.poke((0x100000+prefix).U)
      f.scratch.allocationBase.poke(0x100000.U);f.scratch.allocationLimit.poke((0x100000+prefix).U);f.sampler.texWidth.poke(1.U);f.sampler.texHeight.poke(1.U)
      d.io.out.ready.poke(true.B);d.io.draw.valid.poke(true.B);until("draw")(d.io.draw.ready.peek().litToBoolean);tick();d.io.draw.valid.poke(false.B)
      until("completion")(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.status.expect(0.U)
      d.io.completion.bits.raster.outputQuads.expect(1.U);d.io.completion.bits.fragment.outputQuads.expect(1.U);assert(outputs==1)
      d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B);d.io.busy.expect(false.B)
      // A mismatched draw is rejected before either shader can launch.
      f.identity.context.poke(1.U);d.io.draw.valid.poke(true.B);until("invalid draw")(d.io.draw.ready.peek().litToBoolean)
      tick();d.io.draw.valid.poke(false.B);d.io.completion.valid.expect(true.B);d.io.completion.bits.status.expect(1.U)
      d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B);f.identity.context.poke(0.U)
      // A held fragment remains stable through cancellation and drains before completion.
      d.io.out.ready.poke(false.B);d.io.draw.valid.poke(true.B);until("second draw")(d.io.draw.ready.peek().litToBoolean)
      tick();d.io.draw.valid.poke(false.B);until("held output")(d.io.out.valid.peek().litToBoolean)
      val held=d.io.out.bits.peek().litValue;d.io.cancel.poke(true.B)
      for(_<-0 until 5){d.io.out.valid.expect(true.B);assert(d.io.out.bits.peek().litValue==held);tick()}
      d.io.out.ready.poke(true.B);tick();d.io.out.ready.poke(false.B)
      until("cancel completion")(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.status.expect(4.U)
      d.io.cancel.poke(false.B);d.io.completion.ready.poke(true.B);tick();d.io.busy.expect(false.B)
      d.io.completion.ready.poke(false.B)
      // Emit a line and a triangle with distinct per-vertex IDs and layers.
      // The fragment must observe the final (provoking) vertex's built-ins.
      def setReg6(bits:Long):BigInt=if(bits==0)addi(6,0,0)else (BigInt(bits>>>12)<<12)|(BigInt(6)<<7)|0x37
      def strip(vertices:Vector[(Long,Long)]):Vector[BigInt]={
        val code=mutable.ArrayBuffer.empty[BigInt]
        for(((vx,vy),i)<-vertices.zipWithIndex){
          for((component,value)<-Vector(vx,vy,0x3f000000L,0x3f800000L).zipWithIndex.map(_.swap)){
            code+=addi(4,0,component);code+=setReg6(value);code+=geometry(33,0,4,6)
          }
          code+=addi(5,0,91+i);code+=geometry(38,0,5)
          code+=addi(7,0,7+i);code+=geometry(34,0,7);code+=geometry(35)
        }
        code+=geometry(36);code+=BigInt("30500073",16);code.toVector
      }
      // The line reference uses diamond-exit with its specified subpixel perturbation.
      val cases=Seq(
        (1,0x3000,Vector((0xbf000000L,0xbe800000L),(0x3f000000L,0xbe800000L)),Set((0,1),(1,1))),
        (2,0x4000,Vector((0xbf000000L,0xbf000000L),(0x3f000000L,0xbf000000L),(0L,0x3f000000L)),Set((1,1),(2,1)))
      )
      for((topology,pc,vertices,expected)<-cases){
        strip(vertices).zipWithIndex.foreach{case(w,i)=>put(pc+4*i,w)}
        x.kernel.kernelPc.poke(pc.U);q.inputComponents.poke(0.U);q.outputTopology.poke(topology.U);q.maxVertices.poke(vertices.size.U)
        expectedId=90+vertices.size;expectedLayer=6+vertices.size;covered.clear();outputs=0
        d.io.out.ready.poke(true.B);d.io.draw.valid.poke(true.B);until("strip draw")(d.io.draw.ready.peek().litToBoolean)
        tick();d.io.draw.valid.poke(false.B);until("strip completion")(d.io.completion.valid.peek().litToBoolean)
        d.io.completion.bits.status.expect(0.U);d.io.completion.bits.raster.rasterInputs.expect(1.U)
        assert(covered.toSet==expected,s"topology=$topology coverage=$covered expected=$expected")
        assert(outputs==expected.size);d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B)
      }
    }
  }
}
