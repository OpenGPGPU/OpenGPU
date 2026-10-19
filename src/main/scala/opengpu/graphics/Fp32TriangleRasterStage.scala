package opengpu.graphics

import chisel3._
import chisel3.util._

class Fp32RasterState extends Bundle {
  val version=UInt(8.W);val screenW=UInt(16.W);val screenH=UInt(16.W)
  /** 0=none, 1=back, 2=front; frontFaceCCW selects the front winding. */
  val cullMode=UInt(2.W);val frontFaceCCW=Bool();val sampleMode=UInt(2.W)
  /** Optional draw size/width in FP32; zero selects1. Clamp to[1,64]. */
  val pointSize=UInt(32.W);val lineWidth=UInt(32.W)
  val scissorEnable=Bool();val scissorMinX=UInt(16.W);val scissorMinY=UInt(16.W)
  val scissorMaxX=UInt(16.W);val scissorMaxY=UInt(16.W)
}
object Fp32RasterState {
  def legal(s: Fp32RasterState,g: GraphicsConfig): Bool=
    s.version===1.U && s.cullMode<=2.U && s.sampleMode<=log2Ceil(g.maxSampleCount).U &&
      sizeLegal(s.pointSize) && sizeLegal(s.lineWidth)
  def sizeLegal(raw: UInt): Bool=raw===0.U || (!raw(31) && !Fp32ViewportState.nan(raw))
  def size(raw: UInt): UInt=Mux(raw===0.U || raw<"h3f800000".U,"h3f800000".U,Mux(raw>"h42800000".U,"h42800000".U,raw))
  def fixedSize(raw: UInt,bits: Int): UInt=ViewportCoordinate.fixed(ClipFloat.fromFp32(size(raw)),0.S(32.W),bits).value.asUInt(23,0)
}
class Fp32TriangleRasterRequest(p: VertexShaderStageConfig) extends Bundle {
  val primitive=new WindowPrimitive(p);val state=new Fp32RasterState
}
class Fp32RasterizedQuad(p: VertexShaderStageConfig,g: GraphicsConfig) extends Bundle {
  val values=new Fp32InterpolatedQuad(p,g);val frontFacing=Bool();val sampleMode=UInt(2.W)
}
class Fp32TriangleRasterCompletion extends Bundle {
  val identity=new GraphicsWorkIdentity;val sequence=UInt(64.W);val primitiveId=UInt(32.W);val instanceId=UInt(32.W);val partIndex=UInt(3.W)
  val status=UInt(3.W);val launched=Bool();val culled=Bool();val emptyBounds=Bool()
  val rasterQuads=UInt(32.W);val discardedQuads=UInt(32.W);val outputQuads=UInt(32.W)
  val coveredLanes=UInt(34.W);val helperLanes=UInt(34.W);val coveredSamples=UInt(36.W)
  val arithmeticOperations=UInt(48.W);val arithmeticStatus=UInt(5.W);val packingStatus=UInt(5.W)
  val interpolationFault=Bool();val faultX=SInt(32.W);val faultY=SInt(32.W);val faultLane=UInt(2.W)
}

/** Compatibility triangle-only owner; the general draw path uses
  * Fp32PrimitiveRasterStage with the same interpolation engine. */
class Fp32TriangleRasterStage(p: VertexShaderStageConfig=VertexShaderStageConfig(),g: GraphicsConfig=GraphicsConfig())
    extends Fp32PrimitiveRasterStage(p,g,trianglesOnly=true)
