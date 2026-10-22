package opengpu.graphics

import chisel3._
import chisel3.util._

/** GTJ1/GTI1/GTD1 are explicit little-endian word layouts. Offsets below do
  * not depend on Bundle packing, shader lane count or implemented capacities.
  * Every scalar occupies a32-bit word (two for33/64-bit fields). Narrow signed
  * fields sign-extend; other padding and all reserved words must be zero.
  * Each multiword field is contained in one64-byte line, allowing independently
  * tagged descriptor replies to populate a private snapshot in any order.
  */
object NativeTypedGraphicsLayout {
  val version=1
  val jobMagic=BigInt("47544a01",16);val passMagic=BigInt("47544901",16);val drawMagic=BigInt("47544401",16)
  val jobWords=128;val passWords=320;val drawWords=640
  val jobBytes=jobWords*4;val passBytes=passWords*4;val drawBytes=drawWords*4
  val vertex=VertexShaderStageConfig();val fragment=FragmentShaderConfig()
  case class Field(name: String,word: Int,value: Data){
    val width=value.getWidth;val words=(width+31)/32
    require(width>0 && width<=64 && word%16+words<=16)
  }
  private def fields(base: Int,prefix: String,values: (String,Data)*): Seq[Field]={
    var word=base
    values.map{case(name,value)=>val f=Field(prefix+name,word,value);word+=f.words;f}
  }
  private def identity(base: Int,prefix: String,d: GraphicsWorkIdentity): Seq[Field]=
    fields(base,prefix,"context"->d.context,"pass"->d.pass,"generation"->d.generation)
  private def view(base: Int,prefix: String,d: VertexInputView): Seq[Field]=
    fields(base,prefix,"base"->d.base,"limit"->d.limit,"allocationBase"->d.allocationBase,"allocationLimit"->d.allocationLimit)
  private def sampler(base: Int,prefix: String,d: VertexShaderSampler): Seq[Field]=
    fields(base,prefix,"format"->d.format,"base"->d.base,"width"->d.texWidth,"height"->d.texHeight,
      "clamp"->d.clamp,"maxLevel"->d.maxLevel,"minLevel"->d.minLevel,"bias"->d.bias,
      "policyVersion"->d.policyVersion,"wrapU"->d.wrapU,"wrapV"->d.wrapV,"signedCoordinates"->d.signedCoordinates)
  private def image(base: Int,prefix: String,d: TypedImageView): Seq[Field]=
    fields(base,prefix,"base"->d.base,"limit"->d.limit,"allocationBase"->d.allocationBase,"allocationLimit"->d.allocationLimit,
      "width"->d.imageWidth,"height"->d.imageHeight,"rowPitch"->d.rowPitch,"readable"->d.readable,"writable"->d.writable,"store"->d.store)
  private def depth(base: Int,prefix: String,d: TypedDepthView): Seq[Field]=
    fields(base,prefix,"format"->d.format,"offset"->d.offset,"loadOp"->d.loadOp,"clearDepth"->d.clearDepth,"clearStencil"->d.clearStencil)
  private def face(base: Int,prefix: String,d: TypedStencilFace): Seq[Field]=
    fields(base,prefix,"function"->d.function,"reference"->d.reference,"readMask"->d.readMask,"writeMask"->d.writeMask,
      "fail"->d.fail,"depthFail"->d.depthFail,"depthPass"->d.depthPass)

  def jobFields(d: NativeTypedGraphicsJob): Seq[Field]=
    identity(3,"identity.",d.identity)++fields(6,"","passBase"->d.passBase,"passLimit"->d.passLimit,
      "drawBase"->d.drawBase,"drawLimit"->d.drawLimit,"drawCount"->d.drawCount,"drawStride"->d.drawStride)

  def passFields(d: TypedTileImageDescriptor): Seq[Field]={
    val c=d.pixels.colors
    fields(16,"","version"->d.version,"pixelVersion"->d.pixels.version,"colorVersion"->c.version)++
      identity(19,"identity.",c.identity)++fields(22,"","originX"->c.originX,"originY"->c.originY,"tileLog2"->c.tileLog2,
        "tileWidth"->c.tileWidth,"tileHeight"->c.tileHeight,"sampleMode"->c.sampleMode,"reservedBytes"->c.reservedBytes)++
      depth(32,"depth.",d.pixels.depth)++depth(40,"stencil.",d.pixels.stencil)++
      c.colors.zipWithIndex.flatMap{case(v,i)=>fields(64+i*16,s"color$i.","enabled"->v.enabled,"format"->v.format,
        "offset"->v.offset,"channelMask"->v.channelMask,"loadOp"->v.loadOp,"clearR"->v.clear(0),"clearG"->v.clear(1),"clearB"->v.clear(2),"clearA"->v.clear(3))}++
      d.views.zipWithIndex.flatMap{case(v,i)=>image(128+i*16,s"view$i.",v)}++
      d.resolves.zipWithIndex.flatMap{case(r,i)=>fields(224+i*16,s"resolve$i.","enabled"->r.enable)++image(225+i*16,s"resolve$i.",r.destination)}
  }
  def drawFields(d: TypedTileRenderDraw): Seq[Field]={
    val geometry=d.shaders.vertex.vertex.geometry;val v=geometry.vertex;val input=v.input
    val vp=d.shaders.vertex.vertex.viewport;val r=d.shaders.vertex.raster;val f=d.shaders.fragment;val t=d.tests;val b=d.blend
    fields(16,"","version"->d.version,"shaderVersion"->d.shaders.version,"topology"->geometry.topology,"flatMask"->geometry.flatMask)++
      fields(32,"vertex.","version"->v.version,"pc"->v.shaderPc,"attributeSlots"->v.attributeSlots,"varyingSlots"->v.varyingSlots,"varyingMask"->v.varyingMask)++
      view(40,"vertex.scratch.",v.scratch)++sampler(48,"vertex.sampler.",v.sampler)++
      fields(64,"input.","version"->input.version)++identity(65,"input.identity.",input.identity)++
      fields(68,"input.","count"->input.count,"instances"->input.instances,"firstVertex"->input.firstVertex,"firstIndex"->input.firstIndex,
        "baseVertex"->input.baseVertex,"baseInstance"->input.baseInstance,"indexFormat"->input.indexFormat,"restart"->input.restart)++
      view(80,"input.indices.",input.indices)++
      input.attributes.zipWithIndex.flatMap{case(a,i)=>fields(96+i*16,s"attribute$i.","enabled"->a.enabled,"binding"->a.bindingIndex,
        "offset"->a.offset,"components"->a.components,"format"->a.format,"constantX"->a.constant(0),"constantY"->a.constant(1),"constantZ"->a.constant(2),"constantW"->a.constant(3))}++
      input.bindings.zipWithIndex.flatMap{case(b,i)=>view(352+i*8,s"binding$i.",b)++fields(358+i*8,s"binding$i.","stride"->b.stride,"divisor"->b.divisor)}++
      fields(480,"viewport.","version"->vp.version,"x"->vp.x,"y"->vp.y,"extentX"->vp.extentX,"extentY"->vp.extentY,"nearDepth"->vp.nearDepth,"farDepth"->vp.farDepth)++
      fields(496,"raster.","version"->r.version,"screenW"->r.screenW,"screenH"->r.screenH,"cullMode"->r.cullMode,"frontCCW"->r.frontFaceCCW,
        "sampleMode"->r.sampleMode,"pointSize"->r.pointSize,"lineWidth"->r.lineWidth,"scissorEnable"->r.scissorEnable,"scissorMinX"->r.scissorMinX,
        "scissorMinY"->r.scissorMinY,"scissorMaxX"->r.scissorMaxX,"scissorMaxY"->r.scissorMaxY)++
      fields(512,"fragment.","version"->f.version)++identity(513,"fragment.identity.",f.identity)++fields(516,"fragment.","pc"->f.shaderPc)++
      view(520,"fragment.scratch.",f.scratch)++fields(528,"fragment.","varyingSlots"->f.varyingSlots,"colorSlots"->f.colorSlots,"colorMask"->f.colorMask,
        "colorType0"->f.colorTypes(0),"colorType1"->f.colorTypes(1),"colorType2"->f.colorTypes(2),"colorType3"->f.colorTypes(3),
        "allowDepthOverride"->f.allowDepthOverride,"sampleMode"->f.sampleMode)++
      fields(538,"fragment.","centroidMask"->f.centroidMask)++
      fields(540,"raster.","polygonOffsetEnable"->r.polygonOffsetEnable,"polygonOffsetFactor"->r.polygonOffsetFactor,"polygonOffsetUnits"->r.polygonOffsetUnits)++
      sampler(544,"fragment.sampler.",f.sampler)++
      fields(560,"tests.","version"->t.version,"depthEnable"->t.depthEnable,"depthWrite"->t.depthWrite,"depthFunction"->t.depthFunction,"stencilEnable"->t.stencilEnable)++
      face(568,"tests.front.",t.front)++face(576,"tests.back.",t.back)++
      fields(592,"blend.","enable"->b.enable,"version"->b.version,"srcRGB"->b.srcRGB,"dstRGB"->b.dstRGB,"srcAlpha"->b.srcAlpha,"dstAlpha"->b.dstAlpha,
        "equationRGB"->b.equationRGB,"equationAlpha"->b.equationAlpha,"constantR"->b.constant(0),"constantG"->b.constant(1),"constantB"->b.constant(2),"constantA"->b.constant(3))++
      fields(610,"vertex.","reuseOutputs"->v.reuseOutputs)++
      ShaderTextureResources.fields(608,"vertex.resources.",v.resources)++ShaderTextureResources.fields(624,"fragment.resources.",f.resources)
  }
  def leaves(d: Data): Seq[Data]=d match{case r: Record=>r.elements.values.toSeq.flatMap(leaves);case v: Vec[_]=>v.toSeq.flatMap(leaves);case a=>Seq(a)}
  def check(d: Data,fs: Seq[Field],words: Int): Unit={
    require(fs.map(_.name).distinct.size==fs.size)
    require(fs.size==leaves(d).size && fs.map(_.value).toSet==leaves(d).toSet,"native ABI must map every descriptor field exactly once")
    val occupied=fs.flatMap(f=>f.word until f.word+f.words)
    require(occupied.distinct.size==occupied.size && occupied.forall(w=>w>=3 && w<words))
  }
  /** Called only on a successful owned reply; invalid encoding is latched by
    * the caller before any descriptor can become a child offer.
    */
  def receive(fs: Seq[Field],words: Int,magic: BigInt,line: UInt,data: UInt,write: Bool): Bool={
    val tests=scala.collection.mutable.ArrayBuffer.empty[Bool]
    val occupied=fs.flatMap(f=>f.word until f.word+f.words).toSet++Set(0,1,2)
    for((value,word)<-Seq(magic,BigInt(words*4),BigInt(version)).zipWithIndex)
      tests+=((line===(word/16).U) && data((word%16)*32+31,(word%16)*32)=/=value.U)
    for(word<-0 until words if !occupied(word))
      tests+=((line===(word/16).U) && data((word%16)*32+31,(word%16)*32).orR)
    for(f<-fs){
      val low=(f.word%16)*32;val raw=data(low+f.words*32-1,low);val selected=line===(f.word/16).U
      val bits=raw(f.width-1,0)
      val packed=f.value match{case _: SInt=>bits.asSInt.pad(f.words*32).asUInt;case _=>bits.pad(f.words*32)}
      tests+=selected && raw=/=packed
      when(write && selected){f.value match{case s: SInt=>s:=bits.asSInt;case b: Bool=>b:=bits(0);case u: UInt=>u:=bits;case _=>throw new IllegalArgumentException(f.name)}}
    }
    tests.reduce(_ || _)
  }
  /** Adapt a fixed16-slot ABI to a smaller advertised hardware capacity only
    * after fit() has rejected every active out-of-capacity field.
    */
  def adapt(source: Data,target: Data): Unit=(source,target) match{
    case(a: Record,b: Record)=>b.elements.foreach{case(k,v)=>adapt(a.elements(k),v)}
    case(a: Vec[_],b: Vec[_])=>b.zipWithIndex.foreach{case(v,i)=>adapt(a(i),v)}
    case(a: SInt,b: SInt)=>b:=a
    case(a: UInt,b: UInt)=>b:=a
    case _=>throw new IllegalArgumentException("incompatible native descriptor")
  }
  def fit(d: TypedTileRenderDraw,p: VertexShaderStageConfig,f: FragmentShaderConfig): Bool={
    val g=d.shaders.vertex.vertex.geometry;val v=g.vertex;val i=v.input
    val active=i.attributes.zipWithIndex.map{case(a,n)=> !a.enabled || (n<p.input.attributes).B && a.bindingIndex<p.input.bindings.U}.reduce(_ && _)
    ShaderTextureResources.legal(v.resources) && ShaderTextureResources.legal(d.shaders.fragment.resources) &&
      VertexShaderSampler.legal(v.sampler) && VertexShaderSampler.legal(d.shaders.fragment.sampler) &&
      (!v.reuseOutputs || (p.postTransformEntries>0).B) && active && v.attributeSlots<=p.input.attributes.U && v.varyingSlots<=p.varyings.U && d.shaders.fragment.varyingSlots<=f.varyings.U &&
      !(v.varyingMask>>(v.varyingSlots<<2)).orR && !(g.flatMask>>(v.varyingSlots<<2)).orR &&
      !(d.shaders.fragment.centroidMask>>(d.shaders.fragment.varyingSlots<<2)).orR &&
      FragmentInputAbi.legal(d.shaders.fragment.version) &&
      (FragmentInputAbi.hasCentroid(d.shaders.fragment.version) || !d.shaders.fragment.centroidMask.orR)
  }
}

class NativeTypedGraphicsJob extends Bundle {
  val identity=new GraphicsWorkIdentity
  val passBase=UInt(32.W);val passLimit=UInt(33.W)
  val drawBase=UInt(32.W);val drawLimit=UInt(33.W)
  val drawCount=UInt(32.W);val drawStride=UInt(32.W)
}
