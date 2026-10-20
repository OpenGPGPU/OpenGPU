package opengpu.graphics

import chisel3._
import chisel3.util._

/** Version0 is canonical disabled state. Version1 points to an immutable GXR1
  * root (128 bytes); address zero is legal when version1 is selected.
  */
class ShaderTextureResources extends Bundle {val version=UInt(2.W);val address=UInt(32.W)}
object ShaderTextureResources {
  def legal(r: ShaderTextureResources): Bool=(r.version===0.U && r.address===0.U) ||
    (r.version===1.U && r.address(5,0)===0.U && r.address.pad(33)+128.U<=(BigInt(1)<<32).U)
  def fields(base: Int,prefix: String,r: ShaderTextureResources): Seq[NativeTypedGraphicsLayout.Field]=
    Seq(NativeTypedGraphicsLayout.Field(prefix+"version",base,r.version),NativeTypedGraphicsLayout.Field(prefix+"address",base+1,r.address))
}
class ShaderTextureRootRecord extends Bundle {
  val identity=new GraphicsWorkIdentity;val stage=UInt(1.W);val bindingCount=UInt(32.W)
  val bindingsBase=UInt(32.W);val bindingsLimit=UInt(33.W)
  val imagesBase=UInt(32.W);val imagesLimit=UInt(33.W)
  val samplersBase=UInt(32.W);val samplersLimit=UInt(33.W)
}
object ShaderTextureRootLayout {
  val magic=BigInt("47585201",16);val words=32
  def fields(d: ShaderTextureRootRecord): Seq[NativeTypedGraphicsLayout.Field]={
    import NativeTypedGraphicsLayout.Field
    Seq(Field("identity.context",3,d.identity.context),Field("identity.pass",4,d.identity.pass),Field("identity.generation",5,d.identity.generation),
      Field("stage",6,d.stage),Field("bindingCount",7,d.bindingCount),Field("bindingsBase",8,d.bindingsBase),Field("bindingsLimit",9,d.bindingsLimit),
      Field("imagesBase",11,d.imagesBase),Field("imagesLimit",12,d.imagesLimit),Field("samplersBase",14,d.samplersBase),Field("samplersLimit",16,d.samplersLimit))
  }
}
class ShaderTextureBindingRecord extends Bundle {
  val identity=new GraphicsWorkIdentity;val stage=UInt(1.W);val unit=UInt(32.W)
  val imageHandle=UInt(32.W);val samplerHandle=UInt(32.W);val expectedType=UInt(2.W);val target=UInt(3.W)
}
object ShaderTextureBindingLayout {
  val magic=BigInt("47584201",16);val words=16
  def fields(d: ShaderTextureBindingRecord): Seq[NativeTypedGraphicsLayout.Field]={
    import NativeTypedGraphicsLayout.Field
    Seq(Field("identity.context",3,d.identity.context),Field("identity.pass",4,d.identity.pass),Field("identity.generation",5,d.identity.generation),
      Field("stage",6,d.stage),Field("unit",7,d.unit),Field("imageHandle",8,d.imageHandle),Field("samplerHandle",9,d.samplerHandle),
      Field("expectedType",10,d.expectedType),Field("target",11,d.target))
  }
}
/** One immutable instruction coordinate packet. All coordinates and level stay
  * full width until admission; component and binding unit are instruction operands.
  */
class ShaderTextureCoordinateRecord extends Bundle {
  val identity=new GraphicsWorkIdentity
  val x=UInt(32.W);val y=UInt(32.W);val z=UInt(32.W);val layer=UInt(32.W);val face=UInt(32.W);val level=UInt(32.W)
}
object ShaderTextureCoordinateLayout {
  val magic=BigInt("47585101",16);val words=16
  def fields(d: ShaderTextureCoordinateRecord): Seq[NativeTypedGraphicsLayout.Field]={
    import NativeTypedGraphicsLayout.Field
    Seq(Field("identity.context",3,d.identity.context),Field("identity.pass",4,d.identity.pass),Field("identity.generation",5,d.identity.generation),
      Field("x",6,d.x),Field("y",7,d.y),Field("z",8,d.z),Field("layer",9,d.layer),Field("face",10,d.face),Field("level",11,d.level))
  }
}
object ShaderTextureFetchOpcode {
  // tex.fetch.c rd,packet,unit: custom0,funct7=2,funct3=c (0..3).
  // vtex.fetch.c vd,packets,units: custom1,funct6=2,funct3=c; RVV vm retained.
  def scalar(i: UInt): Bool=i(6,0)==="h0b".U && i(31,25)===2.U && i(14,12)<4.U
  def vector(i: UInt): Bool=i(6,0)==="h2b".U && i(31,26)===2.U && i(14,12)<4.U
}
