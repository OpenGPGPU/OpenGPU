package opengpu.graphics.tessellation

import opengpu.graphics._

import chisel3._
import opengpu.config.GpuConfig
import opengpu.system.{GpuHostSystemAxi,GpuHostTestSupport,TessellationNativeQuadSequenceLayout,TessellationQuadPatchSequenceDescriptor}
import opengpu.testutil.GraphicsTestResolution
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class TessellationNativeQuadHostDescriptorFixture extends Module {
  private val cfg=GpuConfig(lanes=4,warps=2);private val p=VertexShaderStageConfig(VertexInputFetchConfig(2,2),2)
  val io=IO(new Bundle {
    val frame=Input(new TypedVertexBinImageDescriptor);val template=Input(new TessellationQuadPatchSequenceDescriptor(cfg,p,4))
    val frameEcho=Output(new TypedVertexBinImageDescriptor);val templateEcho=Output(new TessellationQuadPatchSequenceDescriptor(cfg,p,4))
  })
  io.frameEcho:=io.frame;io.templateEcho:=io.template
}

object TessellationNativeQuadHostFixture {
  import TypedColorReference.fp
  import WindowRecordTest.zero
  private def vector(fn:Int,form:Int,rd:Int,vs1:Int):BigInt=(BigInt(fn)<<26)|(BigInt(1)<<25)|(BigInt(vs1)<<15)|(BigInt(form)<<12)|(BigInt(rd)<<7)|0x57
  private def patch(fn:Int,rd:Int=0,vs1:Int=0,vs2:Int=0):BigInt=(BigInt(fn)<<26)|(BigInt(1)<<25)|(BigInt(vs2)<<20)|(BigInt(vs1)<<15)|(BigInt(rd)<<7)|0x2b
  private def geometry(fn:Int,rd:Int=0,rs1:Int=0,rs2:Int=0):BigInt=(BigInt(fn)<<25)|(BigInt(rs2)<<20)|(BigInt(rs1)<<15)|(BigInt(rd)<<7)|0x0b
  private def addi(rd:Int,rs:Int,n:Int):BigInt=(BigInt(n&4095)<<20)|(BigInt(rs)<<15)|(BigInt(rd)<<7)|0x13
  private def add(rd:Int,a:Int,b:Int):BigInt=(BigInt(b)<<20)|(BigInt(a)<<15)|(BigInt(rd)<<7)|0x33
  private def vm(base:Int,v:Int,write:Boolean):BigInt=(BigInt(1)<<25)|(BigInt(base)<<15)|(BigInt(6)<<12)|(BigInt(v)<<7)|(if(write)0x27 else 7)
  private val controlProgram=Vector(BigInt("0104f057",16),vector(23,3,2,0),vector(23,3,4,0),
    patch(21,rd=10,vs1=2,vs2=4),patch(20,vs1=10)) ++
    Vector(7,15,23,31,39,47,3).flatMap { address => Vector(
      BigInt((address << 20) | (5 << 7) | 0x13),vector(23,4,4,5),patch(22,vs1=2,vs2=4)) } ++
    Vector(BigInt("0000000b",16),BigInt("30500073",16))
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
  /** Serialize the same fixed descriptor values without an Echo simulator.
    * The default fixture path below still checks values through that Echo.
    */
  private def serializeDescriptors(word:(BigInt,BigInt)=>Unit):Unit={
    val frame=mutable.Map.empty[String,BigInt];val draw=mutable.Map.empty[String,BigInt]
    def put(values:mutable.Map[String,BigInt],prefix:String,fields:(String,BigInt)*):Unit=
      fields.foreach{case(name,value)=>values(prefix+name)=value}
    def identity(values:mutable.Map[String,BigInt],prefix:String):Unit=
      put(values,prefix,"context"->7,"pass"->8,"generation"->9)
    put(frame,"frame.","version"->1,"scratchStride"->4096)
    put(frame,"frame.bins.producer.","version"->1,"arena.version"->1,"arena.base"->0x200000,
      "arena.limit"->0x210000,"screenWidth"->4,"screenHeight"->4,"tileLog2"->3)
    identity(frame,"frame.bins.producer.arena.identity.")
    put(frame,"frame.bins.bins.","version"->3,"tileCount"->1,"directoryBase"->0x500000,
      "directoryLimit"->0x500080,"pool.version"->1,"pool.base"->0x400000,"pool.limit"->0x410000,"pool.pageSizeLog2"->7)
    identity(frame,"frame.bins.bins.pool.identity.")
    put(frame,"frame.states.","version"->1,"base"->0x8000,"limit"->0x8400)
    identity(frame,"frame.states.identity.")
    for(e<-0 until 2)put(frame,s"frame.scratch[$e].","base"->(0x100000+e*0x10000),"limit"->(0x102000+e*0x10000))
    put(frame,"frame.image.","version"->1,"pixels.version"->1)
    put(frame,"frame.image.pixels.colors.","version"->1,"tileLog2"->3,"tileWidth"->4,"tileHeight"->4,"reservedBytes"->4096)
    identity(frame,"frame.image.pixels.colors.identity.")
    put(frame,"frame.image.pixels.colors.colors[0].","enabled"->1,"format"->TypedColorFormat.id("RGBA8"),
      "offset"->4096,"channelMask"->15,"loadOp"->AttachmentLoadOp.clear)
    Vector(0.1,0.2,0.3,0.4).map(fp).zipWithIndex.foreach{case(value,i)=>frame(s"frame.image.pixels.colors.colors[0].clear[$i]")=value}
    put(frame,"frame.image.views[0].","base"->0x600000,"limit"->0x600040,"allocationBase"->0x600000,
      "allocationLimit"->0x600040,"imageWidth"->4,"imageHeight"->4,"rowPitch"->16,"readable"->1,"writable"->1,"store"->1)
    put(draw,"","count"->2,"patchStride"->32,"template.stride"->4)
    put(draw,"template.input.","allocationBase"->0x4f7000,"allocationLimit"->0x4f7040,"base"->0x4f7000,"limit"->0x4f7040)
    val viewport="template.source.viewport."
    identity(draw,viewport+"identity.")
    put(draw,viewport+"viewport.","version"->1,"extentX"->4,"extentY"->4,"farDepth"->BigInt(0x3f800000L))
    put(draw,viewport,"varyingSlots"->1)
    for(i<-Seq(0,1,3,4))put(draw,s"${viewport}componentSources[$i].","valid"->1,"bits"->i)
    val patch=viewport+"system.patch."
    put(draw,patch,"evaluationKernel.kernelPc"->0x3000,"outputComponents"->5,"patchInputCount"->1,"patchInputs[0].perPatch"->1)
    put(draw,patch+"control.patch.context.","taskId"->19,"layout.inputVertices"->1,"layout.inputComponents"->1,
      "layout.outputVertices"->4,"layout.patchComponents"->1)
    put(draw,patch+"control.","kernel.kernelPc"->0x1000,"bases[1]"->4)
    for(i<-0 until 3)put(draw,patch+"control.kernel.",s"gridSize[$i]"->1,s"localSize[$i]"->(if(i==0)4 else 1))
    put(draw,"template.source.raster.","version"->1,"pointSize"->BigInt(0x3f800000L),"screenW"->4,"screenH"->4)
    put(draw,"template.source.","token"->219)
    val header=Map[String,BigInt]("header.attributes"->16,"header.bindings"->16,"header.varyings"->16,"header.colors"->4,"header.engineViews"->4)
    val job=header++Map[String,BigInt]("job.identity.context"->7,"job.identity.pass"->8,"job.identity.generation"->9,
      "job.frameBase"->0x4d1000,"job.frameLimit"->0x4d1800,"job.drawBase"->0x4e0000,"job.drawLimit"->0x4e0800,"job.drawCount"->1,"job.drawStride"->2048)
    def record(base:Int,words:Int,magic:BigInt,version:Int,fields:Seq[(String,Int,Int)],values:Map[String,BigInt]):Unit={
      require(values.keySet.subsetOf(fields.map(_._1).toSet),s"unknown pure fixture fields: ${values.keySet--fields.map(_._1).toSet}")
      for(i<-0 until words)word(base+i*4,0)
      word(base,magic);word(base+4,words*4);word(base+8,version)
      for((name,offset,width)<-fields){val raw=values.getOrElse(name,BigInt(0));require(raw>=0 && raw<(BigInt(1)<<width),name)
        for(w<-0 until ((width+31)/32))word(base+(offset+w)*4,(raw>>(32*w))&0xffffffffL)}
    }
    record(0x8e0000,TessellationNativeQuadSequenceLayout.words,TessellationNativeQuadSequenceLayout.magic,
      TessellationNativeQuadSequenceLayout.version,TessellationNativeQuadSequenceLayout.fields(new TessellationQuadPatchSequenceDescriptor(GpuConfig(),VertexShaderStageConfig(),12)).map(f=>(f.name,f.word,f.width)),draw.toMap)
    record(0x8d0000,NativeBinnedGraphicsLayout.jobWords,opengpu.system.TessellationNativeQuadJobLayout.magic,1,
      NativeBinnedGraphicsLayout.jobFields(new NativeBinnedGraphicsJobRecord).map(f=>(f.name,f.word,f.width)),job)
    record(0x8d1000,NativeBinnedGraphicsLayout.frameWords,NativeBinnedGraphicsLayout.frameMagic,1,
      NativeBinnedGraphicsLayout.frameFields(new NativeBinnedGraphicsFrameRecord).map(f=>(f.name,f.word,f.width)),header++frame)
  }
  def build(withoutSimulation:Boolean=false): mutable.Map[BigInt,Int] = {
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
    if(withoutSimulation)serializeDescriptors(word) else simulate(new TessellationNativeQuadHostDescriptorFixture){d=>
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
      val drawFields=TessellationNativeQuadSequenceLayout.fields(new TessellationQuadPatchSequenceDescriptor(GpuConfig(),VertexShaderStageConfig(),12))
      val drawValues=WindowPrimitiveRecordLayout.leaves(desc).toMap
      for(i<-0 until TessellationNativeQuadSequenceLayout.words)word(0x8e0000+i*4,0)
      for((v,i)<-Seq(TessellationNativeQuadSequenceLayout.magic,BigInt(TessellationNativeQuadSequenceLayout.bytes),BigInt(TessellationNativeQuadSequenceLayout.version)).zipWithIndex)word(0x8e0000+i*4,v)
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
      record(0x8d0000,NativeBinnedGraphicsLayout.jobWords,opengpu.system.TessellationNativeQuadJobLayout.magic,NativeBinnedGraphicsLayout.jobFields(new NativeBinnedGraphicsJobRecord),job)
      record(0x8d1000,NativeBinnedGraphicsLayout.frameWords,NativeBinnedGraphicsLayout.frameMagic,NativeBinnedGraphicsLayout.frameFields(new NativeBinnedGraphicsFrameRecord),frameValues)
    }
    bytes
  }
}

class TessellationNativeQuadHostAxiImageSpec extends AnyFlatSpec with GpuHostTestSupport {
  private val ownedTextures=sys.props.get("opengpu.tessellationOwnedTexture").contains("1")
  private val ownedCode=sys.props.get("opengpu.tessellationOwnedCode").contains("1")
  require(!(ownedTextures && ownedCode), "select either the full texture profile or the tiny owned-code profile")
  if(ownedTextures) it should TessellationOwnedTextureHostFixture.caseName in {
    val healthy=TessellationOwnedTextureHostFixture.prepare(fresh=false)
    val fresh=TessellationOwnedTextureHostFixture.prepare(fresh=true)
    simulate(TessellationOwnedTextureHostFixture.host()){d=>
      d.sramModelsPresent.expect(true.B)
      TessellationOwnedTextureHostFixture.run(d,healthy,fresh)
    }
  }
  private val ownedUniforms=ownedCode || sys.props.get("opengpu.tessellationOwnedUniform").contains("1")
  private val witness=if(ownedCode)"execute driver-owned RX fragment code through three translated quad jobs"
    else if(ownedUniforms)"recover relocated driver-owned uniforms through three translated quad jobs"
    else "recover and alternate translated quad, isoline and triangle jobs"
  for(local<-Seq(true) if !ownedTextures) it should s"$witness through the real host local=$local" in {
    val bytes=TessellationNativeQuadHostFixture.build(withoutSimulation=ownedUniforms)
    val rootedQuad=TessellationRootedHostFixture.prepare(bytes,BigInt(0x3f000000L),BigInt(0x3e800000L),ownedUniforms,
      ownedFragmentCode=ownedCode)
    val rootedIsoline=if(ownedUniforms)None else Some(TessellationRootedHostFixture.prepare(
      TessellationNativeHostFixture.build(),BigInt(0x3f400000L),BigInt(0x3e800000L)))
    val rootedTriangle=if(ownedUniforms)None else Some(TessellationRootedHostFixture.prepare(
      TessellationNativeTriangleHostFixture.build(),BigInt(0x3f400000L),BigInt(0x3f000000L)))
    val rootedReuse=TessellationRootedHostFixture.prepare(bytes,BigInt(0x3e800000L),BigInt(0x3f400000L),ownedUniforms,
      ownedFragmentCode=ownedCode)
    val gpu=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2)
    val graphics=GraphicsConfig(screenWidth=math.min(4,GraphicsTestResolution.width),screenHeight=math.min(4,GraphicsTestResolution.height))
    val isolineCoverage=TessellationRootedHostFixture.isolineCoverage(graphics)
    val p=VertexShaderStageConfig(VertexInputFetchConfig(2,2),2);val f=FragmentShaderConfig(2,4)
    val n=NativeBinnedGraphicsConfig(p,f,engines=2,pageBytes=128,writeCredits=2,tileSlots=1,pagedCapacity=8,
      payload=if(local)Some(ShaderPayloadConfig(capacityBytes=4096,maxPayloadBytes=1024,owners=3,ports=6))else None)
    simulate(new GpuHostSystemAxi(graphics,gpu,numComputeUnits=2,fragCore=true,sharedGraphics=true,parallelGraphics=true,
      memoryAxiDataBytes=32,instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1,
      nativeBinnedGraphics=Some(n),tessellationNative=true)){d=>
      initialize(d);axiWrite(d,RenderHostRegs.IRQ,1);axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0x80000030L.toInt)
      val reads=mutable.ArrayBuffer.empty[BigInt]
      var previousPrepared=Option.empty[TessellationRootedHostFixture.Prepared]
      def line(address:BigInt):BigInt={val base=address & ~BigInt(63);(0 until 64).foldLeft(BigInt(0))((v,i)=>v|(BigInt(bytes.getOrElse(base+i,0))<<(8*i)))}
      def runLegacyDescriptorFailure(id:Int,reset:Boolean):Unit={
        d.io.m_axi_arready.poke(false.B);d.io.m_axi_awready.poke(false.B);d.io.m_axi_wready.poke(false.B)
        d.io.m_axi_rvalid.poke(false.B);d.io.m_axi_bvalid.poke(false.B)
        axiWrite(d,GpuCommandMmioRegs.COMMAND_ID,id);axiWrite(d,GpuCommandMmioRegs.OPCODE,15)
        axiWrite(d,GpuCommandMmioRegs.SOURCE,0x4d0000);axiWrite(d,GpuCommandMmioRegs.DESTINATION,0x4d0200)
        axiWrite(d,GpuCommandMmioRegs.PATTERN,0);axiWrite(d,GpuCommandMmioRegs.BYTES,512);axiWrite(d,GpuCommandMmioRegs.SUBMIT,1)
        var injected=0;var resetRequested=false
        serviceMemoryMaster(d,line,readFault=a=>{val hit= !reset && (a & ~BigInt(63))==0x8d0000;if(hit)injected+=1;hit},
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
        assert((meta&255)==id && opcode==15 && ((meta>>11)&15)==3 && !meta.testBit(15),s"unexpected tessellation completion $meta")
        assert(axiRead(d,0x180)==BigInt("474e5a01",16))
        val flags=axiRead(d,0x184)
        assert(injected>0,"descriptor memory fault was not injected")
        assert((flags&31)==19 && axiRead(d,0x188)==0x4d0000)
        axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0,expectedResponse=2)
        axiWrite(d,GpuCommandMmioRegs.COMPLETION_POP,1);axiWrite(d,RenderHostRegs.IRQ,3)
        }
      }
      def runPrepared(id:Int,kind:String,prepared:TessellationRootedHostFixture.Prepared,uniformFault:Boolean=false):Unit={
        // Drain publication against the previous backing before replacing its
        // physical bytes. Snapshot spans are observed at the C helper boundary;
        // the CPU-written fixture frame/patch spans are explicit publications.
        val publicationMemory=previousPrepared.getOrElse(prepared)
        for(((address,size),index)<-(prepared.snapshotInvalidations++prepared.backingPublications).zipWithIndex){
          d.io.m_axi_arready.poke(false.B);d.io.m_axi_awready.poke(false.B);d.io.m_axi_wready.poke(false.B)
          d.io.m_axi_rvalid.poke(false.B);d.io.m_axi_bvalid.poke(false.B)
          val publicationId=32+id*8+index
          axiWrite(d,GpuCommandMmioRegs.COMMAND_ID,publicationId)
          axiWrite(d,GpuCommandMmioRegs.OPCODE,opengpu.command.GpuCommandOpcode.invalidate.litValue.toInt)
          axiWrite(d,GpuCommandMmioRegs.SOURCE,address.toInt);axiWrite(d,GpuCommandMmioRegs.BYTES,size.toInt)
          axiWrite(d,GpuCommandMmioRegs.FLAGS,0);axiWrite(d,GpuCommandMmioRegs.SUBMIT,1)
          serviceMemoryMaster(d,publicationMemory.readLine,readFault=a=> !publicationMemory.mappedLine(a),
            maxCycles=100000,writeAckDelay=5)(publicationMemory.write){d.io.m_irq.peek().litToBoolean}
          val completion=axiRead(d,GpuCommandMmioRegs.COMPLETION)
          val opcode=((completion>>8)&7)|(((completion>>16)&1)<<3)
          assert((completion&255)==publicationId && opcode==5 && ((completion>>11)&15)==0 && completion.testBit(15),
            s"rooted fixture publication failed: $completion")
          axiWrite(d,GpuCommandMmioRegs.COMPLETION_POP,1);axiWrite(d,RenderHostRegs.IRQ,3)
        }
        d.io.m_axi_arready.poke(false.B);d.io.m_axi_awready.poke(false.B);d.io.m_axi_wready.poke(false.B)
        d.io.m_axi_rvalid.poke(false.B);d.io.m_axi_bvalid.poke(false.B)
        for((register,value)<-prepared.tlbWrites)axiWrite(d,register,value)
        for((register,value)<-prepared.satpWrites)axiWrite(d,register,value)
        previousPrepared=Some(prepared)
        val evidence=prepared.recordStart(id,kind,uniformFault)
        axiWrite(d,GpuCommandMmioRegs.COMMAND_ID,id)
        for((register,value)<-prepared.commandWrites)axiWrite(d,register,value)
        axiWrite(d,GpuCommandMmioRegs.SUBMIT,1)
        val accepted=mutable.ArrayBuffer.empty[BigInt];var injected=0
        serviceMemoryMaster(d,prepared.readLine,readFault=a=>{
          val resource=uniformFault && (a & ~BigInt(63))==prepared.uniformRootDma
          if(resource)injected+=1
          resource || !prepared.mappedLine(a)
        },maxCycles=400000,writeAckDelay=5,readResponseDelay=11,onReadAccepted=a=>accepted+=a)(prepared.write){
          d.io.m_irq.peek().litToBoolean
        }
        val meta=axiRead(d,GpuCommandMmioRegs.COMPLETION)
        val opcode=((meta>>8)&7)|(((meta>>16)&1)<<3)
        assert((meta&255)==id && opcode==15 && ((meta>>11)&15)==(if(uniformFault)3 else 0) &&
          meta.testBit(15)==(!uniformFault),s"rooted $kind completion $meta")
        assert(axiRead(d,0x180)==BigInt("474e5a01",16))
        val flags=axiRead(d,0x184)
        prepared.recordResult(evidence,accepted.toVector,meta,flags)
        if(uniformFault){
          assert(injected>0,"GUR1 physical read fault was not accepted")
          assert(accepted.exists(a=>(a & ~BigInt(63))==prepared.textureDataDma),
            "uniform fault did not follow the actual texture data instruction")
          // Job child/image stage3 and a valid fault tile. GNZ1 does not expose
          // the fragment adapter's resource address in its reader-address word.
          assert((flags&0x1ff)==0x17b,s"wrong rooted resource fault attribution: $flags")
        }else{
          assert(flags==0 && Seq(0x198,0x19c,0x1a0).map(a=>axiRead(d,a))==Seq[BigInt](7,8,9))
          if(ownedCode)prepared.checkFragmentCodeReads(accepted.toVector)
          val pixels=prepared.pixels
          val expected=(0 until 16).map{i=>
            val x=i%4;val y=i/4
            val covered=kind match{
              case "quad"=>x>=2 && y>=2
              case "triangle"=>x>=2 && y>=2 && x+y<=5
              case "isoline"=>isolineCoverage((x,y))
            }
            if(covered)prepared.expectedPixel else prepared.clearPixel
          }
          assert(pixels==expected,s"rooted $kind pixels differ: $pixels expected=$expected")
          val required=Seq("job","frame","draws","states","patch-data","code").map(prepared.regionDma) ++
            Seq(prepared.regionDma("code")+0x2000,prepared.regionDma("vm-root")) ++
            (if(ownedCode)prepared.fragmentCodeReadLines else Seq(prepared.regionDma("code")+0x1000)) ++
            prepared.requiredResourceReadLines
          assert(required.forall(a=>accepted.exists(x=>(x & ~BigInt(63))==a)),
            s"C-prepared descriptor, shader, patch or resource physical reads missing: ${required.filterNot(a=>accepted.exists(x=>(x & ~BigInt(63))==a))}")
        }
        assert(!accepted.exists(a=>prepared.virtualBackingSpans.exists{case(base,limit)=>a>=base && a<limit}),
          "C-exported descriptor, resource or shader VA escaped translation as a physical read")
        axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0,expectedResponse=2)
        axiWrite(d,GpuCommandMmioRegs.INSTRUCTION_SATP,0,expectedResponse=2)
        axiWrite(d,GpuCommandMmioRegs.COMPLETION_POP,1);axiWrite(d,RenderHostRegs.IRQ,3)
      }
      if(ownedUniforms){
        runPrepared(5,"quad",rootedQuad)
        runPrepared(8,"quad",rootedQuad,uniformFault=true)
        runPrepared(9,"quad",rootedReuse)
      }else{
        runLegacyDescriptorFailure(3,reset=false);runLegacyDescriptorFailure(4,reset=true)
        runPrepared(5,"quad",rootedQuad);runPrepared(6,"isoline",rootedIsoline.get)
        runPrepared(7,"triangle",rootedTriangle.get);runPrepared(8,"quad",rootedQuad,uniformFault=true)
        runPrepared(9,"quad",rootedReuse)
        assert(reads.exists(x=>(x & ~BigInt(63))==0x8d0000))
      }
    }
  }
}
