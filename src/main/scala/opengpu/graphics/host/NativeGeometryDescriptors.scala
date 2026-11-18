package opengpu.graphics

import chisel3._
import chisel3.util._

/** GND2 has a separately bounded GNG1 reference. GND1 keeps these words zero. */
class NativeGeometryResources extends Bundle {
  val version=UInt(2.W);val base=UInt(32.W);val limit=UInt(33.W)
}
object NativeGeometryResources {
  val version=1
  def fields(r: NativeGeometryResources): Seq[NativeTypedGraphicsLayout.Field]={
    import NativeTypedGraphicsLayout.Field
    Seq(Field("geometry.version",508,r.version),Field("geometry.base",509,r.base),Field("geometry.limit",510,r.limit))
  }
  def disabled(r: NativeGeometryResources): Bool=r.version===0.U && r.base===0.U && r.limit===0.U
  def enabled(r: NativeGeometryResources): Bool=r.version===version.U && r.base(5,0)===0.U &&
    r.base.pad(33)+NativeGeometryProgramLayout.bytes.U<=r.limit && r.limit<=(BigInt(1)<<32).U
}
class NativeGeometryDrawRecord extends NativeBinnedGraphicsDrawRecord {
  val geometry=new NativeGeometryResources
}
object NativeGeometryDrawLayout {
  val magic=BigInt("474e4402",16);val version=2
  val words=NativeBinnedGraphicsLayout.drawWords;val bytes=words*4
  def fields(r: NativeGeometryDrawRecord): Seq[NativeTypedGraphicsLayout.Field]=
    NativeBinnedGraphicsLayout.drawFields(r)++NativeGeometryResources.fields(r.geometry)
}

/** Fixed-capacity program/link metadata, distinct from the GND payload and
  * from the task-owned six-vertex input captured after the VS completes.
  */
class NativeGeometryProgram extends Bundle {
  val identity=new GraphicsWorkIdentity
  val inputVertices=UInt(3.W);val invocations=UInt(6.W);val outputTopology=UInt(2.W)
  val maxVertices=UInt(9.W);val inputComponents=UInt(7.W);val outputComponents=UInt(7.W)
  val builtinOutputMask=UInt(2.W);val vertexVaryingSlots=UInt(5.W);val fragmentVaryingSlots=UInt(5.W)
  val programVersion=UInt(8.W);val pc=UInt(32.W);val code=new VertexInputView
  val uniforms=new ShaderUniformResources;val flatMask=UInt(64.W)
  val inputSources=Vec(64,UInt(7.W));val outputSources=Vec(68,Valid(UInt(7.W)))
}
object NativeGeometryProgramLayout {
  val magic=BigInt("474e4701",16);val version=1;val words=256;val bytes=words*4
  def fields(d: NativeGeometryProgram): Seq[NativeTypedGraphicsLayout.Field]={
    import NativeTypedGraphicsLayout.Field
    Seq(Field("identity.context",3,d.identity.context),Field("identity.pass",4,d.identity.pass),Field("identity.generation",5,d.identity.generation),
      Field("inputVertices",6,d.inputVertices),Field("invocations",7,d.invocations),Field("outputTopology",8,d.outputTopology),
      Field("maxVertices",9,d.maxVertices),Field("inputComponents",10,d.inputComponents),Field("outputComponents",11,d.outputComponents),
      Field("builtinOutputMask",12,d.builtinOutputMask),Field("vertexVaryingSlots",13,d.vertexVaryingSlots),
      Field("fragmentVaryingSlots",14,d.fragmentVaryingSlots),Field("programVersion",15,d.programVersion),Field("pc",16,d.pc),
      Field("code.base",17,d.code.base),Field("code.limit",18,d.code.limit),Field("code.allocationBase",20,d.code.allocationBase),
      Field("code.allocationLimit",21,d.code.allocationLimit),Field("uniforms.version",23,d.uniforms.version),
      Field("uniforms.address",24,d.uniforms.address),Field("flatMask",25,d.flatMask))++
      d.inputSources.zipWithIndex.map{case(v,i)=>Field(s"inputSources[$i]",32+i,v)}++
      d.outputSources.zipWithIndex.flatMap{case(v,i)=>Seq(Field(s"outputSources[$i].valid",96+i*2,v.valid),
        Field(s"outputSources[$i].source",97+i*2,v.bits))}
  }
  def legal(d: NativeGeometryProgram,p: VertexShaderStageConfig=VertexShaderStageConfig()): Bool={
    val code=d.code
    val codeLegal=code.base(1,0)===0.U && code.limit>code.base && code.limit-code.base<=2048.U &&
      code.limit<=(BigInt(1)<<32).U && code.allocationBase(5,0)===0.U && code.allocationLimit(5,0)===0.U &&
      code.allocationLimit<=(BigInt(1)<<32).U && code.base>=code.allocationBase && code.limit<=code.allocationLimit &&
      d.pc(1,0)===0.U && d.pc>=code.base && d.pc.pad(33)+4.U<=code.limit
    val inputCount=Seq(1,2,3,4,6).map(n=>d.inputVertices===n.U).reduce(_ || _)
    val total=d.outputComponents+&PopCount(d.builtinOutputMask)
    val product=d.maxVertices*total
    val inputs=d.inputSources.zipWithIndex.map{case(v,i)=>Mux(i.U<d.inputComponents,
      v<4.U+(d.vertexVaryingSlots<<2) && v<(4+4*p.varyings).U,v===0.U)}.reduce(_ && _)
    val outputs=d.outputSources.zipWithIndex.map{case(v,i)=>
      (!v.valid && v.bits===0.U) || (v.valid && i.U<4.U+(d.fragmentVaryingSlots<<2) &&
        i.U<(4+4*p.varyings).U && v.bits<d.outputComponents && v.bits<64.U)
    }.reduce(_ && _)
    inputCount && d.invocations>=1.U && d.invocations<=32.U && d.outputTopology<=2.U &&
      d.maxVertices>=1.U && d.maxVertices<=256.U && d.inputComponents<=64.U && total<=64.U && product<=1024.U &&
      d.vertexVaryingSlots<=p.varyings.U && d.fragmentVaryingSlots<=p.varyings.U &&
      !(d.flatMask>>(d.fragmentVaryingSlots<<2)).orR && d.programVersion===1.U &&
      GeometryUniformResources.legal(d.uniforms) && codeLegal && inputs && outputs
  }
  /** Primitive mode values follow Khronos GLES3/gl32.h. This preserves both
    * four-vertex line and six-vertex triangle adjacency input contracts.
    */
  def topologyMatches(d: NativeGeometryProgram,topology: UInt): Bool=
    (d.inputVertices===1.U && topology===0.U) ||
    (d.inputVertices===2.U && topology>=1.U && topology<=3.U) ||
    (d.inputVertices===3.U && topology>=4.U && topology<=6.U) ||
    (d.inputVertices===4.U && (topology===10.U || topology===11.U)) ||
    (d.inputVertices===6.U && (topology===12.U || topology===13.U))
}
