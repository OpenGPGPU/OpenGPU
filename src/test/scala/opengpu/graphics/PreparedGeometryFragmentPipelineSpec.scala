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

private class PreparedGeometryFragmentGpuHarness extends Module {
  val cfg=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2)
  val p=FragmentShaderConfig(2,1)
  val g=GraphicsConfig(screenWidth=math.min(4,GraphicsTestResolution.width),screenHeight=math.min(4,GraphicsTestResolution.height),maxSampleCount=1)
  private val system=Module(new GpuSystem(cfg,numComputeUnits=1,commandIdWidth=4,transactionsPerCu=4,
    enableGraphicsKernels=true,graphicsHostTransactions=8,instructionCacheSets=4,instructionCacheWays=1,
    vectorCacheSets=4,vectorCacheWays=1))
  private val client=Module(new VertexFragmentPipeline(cfg,p.vertices,p,SharedCuShaderConfig(1,taskIdWidth=4),g,
    preparedGeometry=true,geometryInput=true))
  val io=IO(new Bundle {
    val draw=Flipped(Decoupled(new VertexFragmentDraw(p.vertices,p)));val primitive=Input(new WindowPrimitive(p.vertices))
    val geometry=Input(Valid(UInt(32.W)));val out=Decoupled(new FragmentShaderQuad(p,g))
    val completion=Decoupled(new VertexFragmentCompletion)
    val memoryRequest=Decoupled(new ComputeMemoryRequest(cfg,64,GpuSystem.totalMemoryTransactions(1,4,8)))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,GpuSystem.totalMemoryTransactions(1,4,8))))
    val cancel=Input(Bool());val busy=Output(Bool())
  })
  private def tie(d:Data):Unit=d match {
    case r:Record=>r.elements.values.foreach(tie);case v:Vec[_]=>v.foreach(tie)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b:=false.B
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u:=0.U
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s:=0.S
    case _=>()
  }
  tie(system.io);tie(client.io)
  client.io.draw<>io.draw;io.out<>client.io.out;io.completion<>client.io.completion;client.io.cancel:=io.cancel
  client.io.primitive.get:=io.primitive;client.io.geometry.get:=io.geometry
  client.io.shared<>system.io.graphicsKernels.get
  system.io.graphicsHostRequest<>client.io.memoryRequest;client.io.memoryResponse<>system.io.graphicsHostResponse
  system.io.graphicsDrained:= !client.io.busy;system.io.graphicsShaderL1Invalidate.ready:=true.B
  io.memoryRequest<>system.io.memoryRequest;system.io.memoryResponse<>io.memoryResponse;io.busy:=client.io.busy
}

class PreparedGeometryFragmentPipelineSpec extends AnyFlatSpec {
  import TriangleRasterReference._
  private def addi(rd:Int,rs:Int,imm:Int):BigInt=(BigInt(imm&4095)<<20)|(BigInt(rs)<<15)|(BigInt(rd)<<7)|0x13
  private def add(rd:Int,a:Int,b:Int):BigInt=(BigInt(b)<<20)|(BigInt(a)<<15)|(BigInt(rd)<<7)|0x33
  private def vmem(base:Int,reg:Int,write:Boolean):BigInt=(BigInt(1)<<25)|(BigInt(base)<<15)|(BigInt(6)<<12)|(BigInt(reg)<<7)|(if(write)0x27 else 7)
  it should "shade a retained prepared primitive with its captured geometry builtins" in {
    simulate(new PreparedGeometryFragmentGpuHarness){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
      val mem=mutable.Map.empty[BigInt,BigInt]
      def put(a:BigInt,v:BigInt):Unit=for(i<-0 until 4)mem(a+i)=(v>>(8*i))&255
      val stride=32;val inputs=18
      val fs=mutable.ArrayBuffer[BigInt]((BigInt(2)<<20)|(BigInt(8)<<15)|(BigInt(1)<<12)|(BigInt(7)<<7)|0x13,
        add(7,1,7),(BigInt(3)<<30)|(BigInt(0x10)<<20)|(BigInt(4)<<15)|(BigInt(7)<<12)|0x57)
      for(i<-0 until 2){fs+=addi(5,7,(inputs-2+i)*stride);fs+=vmem(5,1,false);fs+=addi(6,7,(inputs+i)*stride);fs+=vmem(6,1,true)}
      fs+=BigInt("30500073",16);fs.zipWithIndex.foreach{case(w,i)=>put(0x2000+4*i,w)}
      val pending=mutable.ArrayBuffer.empty[(BigInt,BigInt,Int)];var cycle=0;var covered=0;var requests=0;var lastRequest="none"
      def tick():Unit={d.io.memoryRequest.ready.poke((cycle%3!=0).B)
        val ready=pending.indexWhere(_._3<=cycle);d.io.memoryResponse.valid.poke((ready>=0).B)
        if(ready>=0){val r=pending(ready);d.io.memoryResponse.bits.transactionId.poke(r._1.U);d.io.memoryResponse.bits.readData.poke(r._2.U)}
        if(ready>=0 && d.io.memoryResponse.ready.peek().litToBoolean)pending.remove(ready)
        if(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean){
          requests+=1
          val r=d.io.memoryRequest.bits;val a=r.address.peek().litValue;val write=r.isWrite.peek().litToBoolean
          lastRequest=s"address=$a write=$write tag=${r.transactionId.peek().litValue}"
          if(write){val mask=r.byteMask.peek().litValue;val bits=r.writeData.peek().litValue
            for(i<-0 until 64 if mask.testBit(i))mem(a+i)=(bits>>(8*i))&255}
          val data=(0 until 64).foldLeft(BigInt(0))((v,i)=>v|(mem.getOrElse(a+i,BigInt(0))<<(8*i)))
          pending+=((r.transactionId.peek().litValue,if(write)BigInt(0)else data,cycle+3))}
        if(d.io.out.valid.peek().litToBoolean && d.io.out.ready.peek().litToBoolean){val q=d.io.out.bits
          q.primitiveId.expect(17.U)
          for(l<-q.lanes if l.coverageMask.peek().litValue!=0){l.colors(0)(0).expect(17.U);l.colors(0)(1).expect(9.U)
            assert(l.x.peek().litValue<2 && l.y.peek().litValue<4);covered+=1}}
        d.clock.step();cycle+=1
      }
      def until(label:String)(condition: =>Boolean):Unit={var n=0;while(!condition && n<10000){tick();n+=1};assert(condition,s"$label stalled at $cycle requests=$requests last=$lastRequest covered=$covered pending=${pending.size} busy=${d.io.busy.peek().litValue}")}
      val draw=d.io.draw.bits;draw.version.poke(1.U)
      primitive(d.io.primitive,base);state(draw.vertex.raster,Settings(width=4,height=4,scissor=true,maxX=2,maxY=4))
      d.io.geometry.valid.poke(true.B);d.io.geometry.bits.poke(9.U)
      val f=draw.fragment;f.version.poke(FragmentInputAbi.geometry.U);f.identity.context.poke(5.U);f.identity.pass.poke(6.U);f.identity.generation.poke(7.U)
      f.shaderPc.poke(0x2000.U);f.varyingSlots.poke(2.U);f.colorSlots.poke(1.U);f.colorMask.poke(1.U)
      val prefix=d.p.prefixBytes(8,2,1,FragmentInputAbi.geometry);f.scratch.base.poke(0x100000.U);f.scratch.limit.poke((0x100000+prefix).U)
      f.scratch.allocationBase.poke(0x100000.U);f.scratch.allocationLimit.poke((0x100000+prefix).U)
      f.sampler.texWidth.poke(1.U);f.sampler.texHeight.poke(1.U)
      d.io.out.ready.poke(true.B);d.io.draw.valid.poke(true.B);until("draw")(d.io.draw.ready.peek().litToBoolean);tick();d.io.draw.valid.poke(false.B)
      d.io.geometry.bits.poke(99.U)
      until("completion")(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.status.expect(0.U)
      assert(covered>0 && pending.isEmpty);d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B)
      f.identity.context.poke(8.U);d.io.draw.valid.poke(true.B);until("invalid draw")(d.io.draw.ready.peek().litToBoolean)
      tick();d.io.draw.valid.poke(false.B);until("invalid report")(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.status.expect(1.U)
      d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B);f.identity.context.poke(5.U)
      d.io.geometry.valid.poke(false.B);d.io.draw.valid.poke(true.B);until("missing geometry")(d.io.draw.ready.peek().litToBoolean)
      tick();d.io.draw.valid.poke(false.B);until("missing geometry report")(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.status.expect(1.U);d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B)
      d.io.geometry.valid.poke(true.B);d.io.geometry.bits.poke(9.U)
      d.io.out.ready.poke(false.B);d.io.draw.valid.poke(true.B);until("cancel draw")(d.io.draw.ready.peek().litToBoolean)
      tick();d.io.draw.valid.poke(false.B);until("held fragment")(d.io.out.valid.peek().litToBoolean)
      val held=d.io.out.bits.peek().litValue;d.io.cancel.poke(true.B)
      for(_<-0 until 5){d.io.out.valid.expect(true.B);assert(d.io.out.bits.peek().litValue==held);tick()}
      d.io.out.ready.poke(true.B);tick();d.io.out.ready.poke(false.B)
      until("cancel completion")(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.status.expect(4.U)
      assert(pending.isEmpty);d.io.cancel.poke(false.B)
    }
  }
}
