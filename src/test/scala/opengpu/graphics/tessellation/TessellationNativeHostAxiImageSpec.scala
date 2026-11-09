package opengpu.graphics.tessellation

import opengpu.graphics._

import chisel3._
import opengpu.config.GpuConfig
import opengpu.system.{GpuHostSystemAxi,GpuHostTestSupport,TessellationNativeSequenceLayout,TessellationPatchSequenceDescriptor}
import opengpu.testutil.GraphicsTestResolution
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class TessellationNativeHostDescriptorFixture extends Module {
  private val cfg=GpuConfig(lanes=4,warps=2);private val p=VertexShaderStageConfig(VertexInputFetchConfig(2,2),2)
  val io=IO(new Bundle {
    val frame=Input(new TypedVertexBinImageDescriptor);val template=Input(new TessellationPatchSequenceDescriptor(cfg,p,4))
    val frameEcho=Output(new TypedVertexBinImageDescriptor);val templateEcho=Output(new TessellationPatchSequenceDescriptor(cfg,p,4))
  })
  io.frameEcho:=io.frame;io.templateEcho:=io.template
}

object TessellationNativeHostFixture {
  import TypedColorReference.fp
  import WindowRecordTest.zero
  private def vector(fn:Int,form:Int,rd:Int,vs1:Int):BigInt=(BigInt(fn)<<26)|(BigInt(1)<<25)|(BigInt(vs1)<<15)|(BigInt(form)<<12)|(BigInt(rd)<<7)|0x57
  private def patch(fn:Int,rd:Int=0,vs1:Int=0,vs2:Int=0):BigInt=(BigInt(fn)<<26)|(BigInt(1)<<25)|(BigInt(vs2)<<20)|(BigInt(vs1)<<15)|(BigInt(rd)<<7)|0x2b
  private def geometry(fn:Int,rd:Int=0,rs1:Int=0,rs2:Int=0):BigInt=(BigInt(fn)<<25)|(BigInt(rs2)<<20)|(BigInt(rs1)<<15)|(BigInt(rd)<<7)|0x0b
  private def addi(rd:Int,rs:Int,n:Int):BigInt=(BigInt(n&4095)<<20)|(BigInt(rs)<<15)|(BigInt(rd)<<7)|0x13
  private def add(rd:Int,a:Int,b:Int):BigInt=(BigInt(b)<<20)|(BigInt(a)<<15)|(BigInt(rd)<<7)|0x33
  private def vm(base:Int,v:Int,write:Boolean):BigInt=(BigInt(1)<<25)|(BigInt(base)<<15)|(BigInt(6)<<12)|(BigInt(v)<<7)|(if(write)0x27 else 7)
  private val controlProgram=Vector(BigInt("0104f057",16),vector(23,3,2,0),vector(23,3,4,0),
    patch(21,rd=10,vs1=2,vs2=4),patch(20,vs1=10),vector(23,3,4,7),patch(22,vs1=2,vs2=4),
    vector(23,3,4,15),patch(22,vs1=2,vs2=4),vector(23,3,4,3),patch(22,vs1=2,vs2=4),BigInt("0000000b",16),BigInt("30500073",16))
  private val isolineProgram=Vector(BigInt("00000093",16),BigInt("00000113",16),geometry(32,3,1,2),
    BigInt("00000213",16),geometry(33,0,4,3),BigInt("00100113",16),geometry(32,3,1,2),
    BigInt("00100213",16),geometry(33,0,4,3),BigInt("3f8002b7",16),BigInt("00300213",16),
    geometry(33,0,4,5),BigInt("00300113",16),geometry(32,3,1,2),BigInt("00400213",16),
    geometry(33,0,4,3),geometry(35),geometry(36),BigInt("30500073",16))
  private val fragmentProgram={
    val code=mutable.ArrayBuffer[BigInt]((BigInt(2)<<20)|(BigInt(8)<<15)|(BigInt(1)<<12)|(BigInt(7)<<7)|0x13,
      add(7,1,7),(BigInt(3)<<30)|(BigInt(0x10)<<20)|(BigInt(4)<<15)|(BigInt(7)<<12)|0x57)
    code++=Seq(addi(5,7,6*32),vm(5,1,false),addi(6,7,10*32),vm(6,1,true),BigInt("30500073",16));code.toVector
  }
  def build(): mutable.Map[BigInt,Int] = {
    val bytes=mutable.Map.empty[BigInt,Int]
    def word(address:BigInt,value:BigInt):Unit=for(i<-0 until 4)bytes(address+i)=((value>>(8*i))&255).toInt
    word(0x30000,0xc7);word(0x30004,(BigInt(0x800)<<10)|0xc7)
    for(base<-Seq(0x8f7000,0x8f7100,0x8f7200,0x8f7300,0x8f7400)){word(base,BigInt("3f800000",16));word(base+0x20,BigInt("3f800000",16))}
    controlProgram.zipWithIndex.foreach{case(v,i)=>word(0x1000+i*4,v)}
    isolineProgram.zipWithIndex.foreach{case(v,i)=>word(0x3000+i*4,v)}
    fragmentProgram.zipWithIndex.foreach{case(v,i)=>word(0x2000+i*4,v)}
    val prefix=FragmentShaderConfig(2,4).prefixBytes(8,1,1,FragmentInputAbi.center)
    val values=Map[String,BigInt]("version"->1,"engineCount"->2,"fragment.version"->FragmentInputAbi.center,
      "fragment.identity.context"->7,"fragment.identity.pass"->8,"fragment.identity.generation"->9,
      "fragment.shaderPc"->0x2000,"fragment.varyingSlots"->1,"fragment.colorSlots"->1,"fragment.colorMask"->1,"fragment.sampleMode"->0,
      "fragment.sampler.texWidth"->1,"fragment.sampler.texHeight"->1,
      "fragment.scratch.base"->0x100000,"fragment.scratch.limit"->(0x100000+prefix),"fragment.scratch.allocationBase"->0x100000,"fragment.scratch.allocationLimit"->(0x100000+prefix),
      "scratch[0].base"->0x110000,"scratch[0].limit"->(0x110000+prefix),"scratch[0].allocationBase"->0x110000,"scratch[0].allocationLimit"->(0x110000+prefix),
      "tests.version"->1,"tests.depthEnable"->0,"tests.front.function"->6,"tests.back.function"->6,"blend.version"->1)
    for(i<-0 until 128)word(0x8000+i*4,0)
    for((v,i)<-Vector[BigInt](TypedFragmentStateLayout.magic,512,1,4,7,8,9,0).zipWithIndex)word(0x8000+i*4,v)
    for(field<-TypedFragmentStateLayout.fields(new TypedFragmentState(FragmentShaderConfig(2,4)))){
      val raw=values.getOrElse(field.name,BigInt(0))&((BigInt(1)<<(field.words*32))-1)
      for(w<-0 until field.words)word(0x8000+(field.word+w)*4,(raw>>(w*32))&0xffffffffL)
    }
    simulate(new TessellationNativeHostDescriptorFixture){d=>
      zero(d.io.frame);zero(d.io.template)
      def identity(i:GraphicsWorkIdentity):Unit={i.context.poke(7.U);i.pass.poke(8.U);i.generation.poke(9.U)}
      val frame=d.io.frame;frame.version.poke(1.U)
      val a=frame.bins.producer;a.version.poke(1.U);a.arena.version.poke(1.U);identity(a.arena.identity)
      a.arena.base.poke(0x200000.U);a.arena.limit.poke(0x210000.U);a.screenWidth.poke(4.U);a.screenHeight.poke(4.U);a.tileLog2.poke(3.U)
      val b=frame.bins.bins;b.version.poke(3.U);b.tileCount.poke(1.U);b.directoryBase.poke(0x500000.U);b.directoryLimit.poke(0x500080.U)
      b.pool.version.poke(1.U);identity(b.pool.identity);b.pool.base.poke(0x400000.U);b.pool.limit.poke(0x410000.U);b.pool.pageSizeLog2.poke(7.U)
      frame.states.version.poke(1.U);identity(frame.states.identity);frame.states.base.poke(0x8000.U);frame.states.limit.poke(0x8400.U);frame.scratchStride.poke(4096.U)
      for(e<-0 until 2){frame.scratch(e).base.poke((0x100000+e*0x10000).U);frame.scratch(e).limit.poke((0x102000+e*0x10000).U)}
      val im=frame.image;im.version.poke(1.U);im.pixels.version.poke(1.U);val colors=im.pixels.colors
      colors.version.poke(1.U);identity(colors.identity);colors.tileLog2.poke(3.U);colors.tileWidth.poke(4.U);colors.tileHeight.poke(4.U);colors.reservedBytes.poke(4096.U)
      val color=colors.colors(0);color.enabled.poke(true.B);color.format.poke(TypedColorFormat.id("RGBA8").U);color.offset.poke(4096.U)
      color.channelMask.poke(15.U);color.loadOp.poke(AttachmentLoadOp.clear.U);color.clear.zip(Vector(0.1,0.2,0.3,0.4).map(fp)).foreach{case(x,y)=>x.poke(y.U)}
      val view=im.views(0);view.base.poke(0x600000.U);view.limit.poke(0x600040.U);view.allocationBase.poke(0x600000.U);view.allocationLimit.poke(0x600040.U)
      view.imageWidth.poke(4.U);view.imageHeight.poke(4.U);view.rowPitch.poke(16.U);view.readable.poke(true.B);view.writable.poke(true.B);view.store.poke(true.B)
      val desc=d.io.template;desc.count.poke(2.U);desc.patchStride.poke(32.U)
      val task=desc.template;val t=task.source;val viewport=t.viewport;val src=viewport.system.patch;val ctl=src.control
      identity(viewport.identity);viewport.viewport.version.poke(1.U);viewport.viewport.extentX.poke(4.U);viewport.viewport.extentY.poke(4.U);viewport.viewport.farDepth.poke("h3f800000".U)
      src.commandId.poke(0.U);src.evaluationKernel.kernelPc.poke(0x3000.U);src.outputComponents.poke(5.U);src.patchInputCount.poke(1.U);src.patchInputs(0).perPatch.poke(true.B)
      for(i<-Seq(0,1,3)){viewport.componentSources(i).valid.poke(true.B);viewport.componentSources(i).bits.poke(i.U)}
      viewport.varyingSlots.poke(1.U);viewport.componentSources(4).valid.poke(true.B);viewport.componentSources(4).bits.poke(4.U)
      ctl.patch.context.taskId.poke(19.U);ctl.patch.context.layout.inputVertices.poke(1.U);ctl.patch.context.layout.inputComponents.poke(1.U)
      ctl.patch.context.layout.outputVertices.poke(4.U);ctl.patch.context.layout.patchComponents.poke(1.U);ctl.kernel.kernelPc.poke(0x1000.U)
      for(i<-0 until 3){ctl.kernel.gridSize(i).poke(1.U);ctl.kernel.localSize(i).poke((if(i==0)4 else 1).U)}
      ctl.bases(0).poke(0.U);ctl.bases(1).poke(4.U);task.input.allocationBase.poke(0x4f7000.U);task.input.allocationLimit.poke(0x4f7040.U)
      task.input.base.poke(0x4f7000.U);task.input.limit.poke(0x4f7040.U);task.stride.poke(4.U)
      t.raster.version.poke(1.U);t.raster.pointSize.poke("h3f800000".U);t.raster.screenW.poke(4.U);t.raster.screenH.poke(4.U);t.state.poke(0.U);t.token.poke(219.U)
      def value(x:Data):BigInt=x match {case b:Bool=>b.peek().litValue;case u:UInt=>u.peek().litValue;case s:SInt=>s.peek().litValue}
      val drawFields=TessellationNativeSequenceLayout.fields(new TessellationPatchSequenceDescriptor(GpuConfig(),VertexShaderStageConfig(),12))
      val drawValues=WindowPrimitiveRecordLayout.leaves(desc).toMap
      for(i<-0 until TessellationNativeSequenceLayout.words)word(0x8e0000+i*4,0)
      for((v,i)<-Seq(TessellationNativeSequenceLayout.magic,BigInt(TessellationNativeSequenceLayout.bytes),BigInt(1)).zipWithIndex)word(0x8e0000+i*4,v)
      for(field<-drawFields){val raw=drawValues.get(field.name).map(value).getOrElse(BigInt(0))&((BigInt(1)<<(field.words*32))-1)
        for(w<-0 until field.words)word(0x8e0000+(field.word+w)*4,(raw>>(32*w))&0xffffffffL)}
      val header=Map[String,BigInt]("header.attributes"->16,"header.bindings"->16,"header.varyings"->16,"header.colors"->4,"header.engineViews"->4)
      val job=header++Map[String,BigInt]("job.identity.context"->7,"job.identity.pass"->8,"job.identity.generation"->9,
        "job.frameBase"->0x4d1000,"job.frameLimit"->0x4d1800,"job.drawBase"->0x4e0000,"job.drawLimit"->0x4e0800,"job.drawCount"->1,"job.drawStride"->2048)
      val frameValues=header++WindowPrimitiveRecordLayout.leaves(frame).map{case(n,v)=>("frame."+n)->value(v)}.toMap
      def record(base:Int,words:Int,magic:BigInt,fields:Seq[NativeTypedGraphicsLayout.Field],v:Map[String,BigInt]):Unit={
        for(i<-0 until words)word(base+i*4,0);word(base,magic);word(base+4,words*4);word(base+8,1)
        for(field<-fields){val raw=v.getOrElse(field.name,BigInt(0))&((BigInt(1)<<(field.words*32))-1)
          for(w<-0 until field.words)word(base+(field.word+w)*4,(raw>>(w*32))&0xffffffffL)}
      }
      record(0x8d0000,NativeBinnedGraphicsLayout.jobWords,opengpu.system.TessellationNativeJobLayout.magic,NativeBinnedGraphicsLayout.jobFields(new NativeBinnedGraphicsJobRecord),job)
      record(0x8d1000,NativeBinnedGraphicsLayout.frameWords,NativeBinnedGraphicsLayout.frameMagic,NativeBinnedGraphicsLayout.frameFields(new NativeBinnedGraphicsFrameRecord),frameValues)
    }
    bytes
  }
}

class TessellationNativeHostAxiImageSpec extends AnyFlatSpec with GpuHostTestSupport {
  for(local<-Seq(true,false)) it should s"recover and shade a translated native tessellation job through the real host local=$local" in {
    val bytes=TessellationNativeHostFixture.build()
    val gpu=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2)
    val graphics=GraphicsConfig(screenWidth=math.min(4,GraphicsTestResolution.width),screenHeight=math.min(4,GraphicsTestResolution.height))
    val p=VertexShaderStageConfig(VertexInputFetchConfig(2,2),2);val f=FragmentShaderConfig(2,4)
    val n=NativeBinnedGraphicsConfig(p,f,engines=2,pageBytes=128,writeCredits=2,tileSlots=1,pagedCapacity=8,
      payload=if(local)Some(ShaderPayloadConfig(capacityBytes=4096,maxPayloadBytes=1024,owners=3,ports=6))else None)
    simulate(new GpuHostSystemAxi(graphics,gpu,numComputeUnits=2,fragCore=true,sharedGraphics=true,parallelGraphics=true,
      memoryAxiDataBytes=32,instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1,
      nativeBinnedGraphics=Some(n),tessellationNative=true)){d=>
      initialize(d);axiWrite(d,RenderHostRegs.IRQ,1);axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0x80000030L.toInt)
      val reads=mutable.ArrayBuffer.empty[BigInt]
      def line(address:BigInt):BigInt={val base=address & ~BigInt(63);(0 until 64).foldLeft(BigInt(0))((v,i)=>v|(BigInt(bytes.getOrElse(base+i,0))<<(8*i)))}
      def run(id:Int,fault:Boolean,reset:Boolean=false):Unit={
        d.io.m_axi_arready.poke(false.B);d.io.m_axi_awready.poke(false.B);d.io.m_axi_wready.poke(false.B)
        d.io.m_axi_rvalid.poke(false.B);d.io.m_axi_bvalid.poke(false.B)
        axiWrite(d,GpuCommandMmioRegs.COMMAND_ID,id);axiWrite(d,GpuCommandMmioRegs.OPCODE,15)
        axiWrite(d,GpuCommandMmioRegs.SOURCE,0x4d0000);axiWrite(d,GpuCommandMmioRegs.DESTINATION,0x4d0200)
        axiWrite(d,GpuCommandMmioRegs.PATTERN,0);axiWrite(d,GpuCommandMmioRegs.BYTES,512);axiWrite(d,GpuCommandMmioRegs.SUBMIT,1)
        var injected=0;var resetRequested=false
        serviceMemoryMaster(d,line,readFault=a=>{val hit=fault && (a & ~BigInt(63))==0x8d0000;if(hit)injected+=1;hit},
          maxCycles=400000,writeAckDelay=5,readResponseDelay=if(reset)37 else 3,onReadAccepted=a=>{
            reads+=a
            if(reset && !resetRequested && (a & ~BigInt(63))==0x8d0000){
              resetRequested=true;axiWrite(d,GpuCommandMmioRegs.RESET,1)
              axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0,expectedResponse=2)
              assert((axiRead(d,GpuCommandMmioRegs.STATUS)&8)!=0,"reset did not retain the accepted memory reply")
            }
          }){(address,data,mask)=>
          for(i<-0 until 64)if(mask.testBit(i))bytes(address+i)=((data>>(8*i))&255).toInt
        }{(!reset || resetRequested) && d.io.m_irq.peek().litToBoolean}
        if(reset){
          assert(resetRequested && (axiRead(d,GpuCommandMmioRegs.STATUS)&8)==0,"reset did not drain")
          axiWrite(d,RenderHostRegs.IRQ,3)
        }else{
        val meta=axiRead(d,GpuCommandMmioRegs.COMPLETION);val opcode=((meta>>8)&7)|(((meta>>16)&1)<<3)
        val status=if(fault)3 else 0
        assert((meta&255)==id && opcode==15 && ((meta>>11)&15)==status && meta.testBit(15)==(!fault),s"unexpected tessellation completion $meta")
        assert(axiRead(d,0x180)==BigInt("474e5a01",16))
        val flags=axiRead(d,0x184)
        if(fault){
          assert(injected>0,"descriptor memory fault was not injected")
          assert((flags&31)==19 && axiRead(d,0x188)==0x4d0000)
        }
        else {
          assert((flags&31)==0 && Seq(0x198,0x19c,0x1a0).map(a=>axiRead(d,a))==Seq[BigInt](7,8,9))
          val reds=(0 until 16).map(i=>bytes.getOrElse(BigInt(0xa00000+i*4),0))
          assert(reds.contains(255) && reds.contains(26),s"expected shaded and clear pixels, got $reds")
        }
        axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0,expectedResponse=2)
        axiWrite(d,GpuCommandMmioRegs.COMPLETION_POP,1);axiWrite(d,RenderHostRegs.IRQ,3)
        }
      }
      run(3,true);run(4,false,reset=true);run(5,false);run(6,false)
      assert(Seq(0x8d0000,0x8d1000,0x8e0000,0x8f7000).forall(a=>reads.exists(x=>(x & ~BigInt(63))==a)))
    }
  }
}
