package opengpu.graphics

import chisel3._

object ShaderUniformLimits {
  val blocksPerStage=12;val bindings=24;val blockBytes=16384;val offsetAlignment=256
  val defaultComponents=Seq(1024,896)
  val defaultBlock=31
}

/** Optional immutable GUR1 root. Version zero is canonical disabled state. */
class ShaderUniformResources extends Bundle {val version=UInt(2.W);val address=UInt(32.W)}
object ShaderUniformResources {
  def legal(r: ShaderUniformResources): Bool=(r.version===0.U && r.address===0.U) ||
    (r.version===1.U && r.address(5,0)===0.U && r.address.pad(33)+64.U<=(BigInt(1)<<32).U)
}

/** Twelve program-block mappings select a shared twenty-four-binding table.
  * Six five-bit indices occupy each map word; inactive indices must be zero.
  * Default uniforms use a separate view and do not consume an API binding.
  */
class ShaderUniformRootRecord extends Bundle {
  val identity=new GraphicsWorkIdentity;val stage=UInt(1.W);val activeMask=UInt(12.W)
  val mapLow=UInt(30.W);val mapHigh=UInt(30.W)
  val bindingsBase=UInt(32.W);val bindingsLimit=UInt(33.W)
  val defaultView=UInt(32.W);val defaultPresent=Bool()
}
object ShaderUniformRootLayout {
  val magic=BigInt("47555201",16);val words=16
  def fields(d: ShaderUniformRootRecord): Seq[NativeTypedGraphicsLayout.Field]={
    import NativeTypedGraphicsLayout.Field
    Seq(Field("identity.context",3,d.identity.context),Field("identity.pass",4,d.identity.pass),Field("identity.generation",5,d.identity.generation),
      Field("stage",6,d.stage),Field("activeMask",7,d.activeMask),Field("mapLow",8,d.mapLow),Field("mapHigh",9,d.mapHigh),
      Field("bindingsBase",10,d.bindingsBase),Field("bindingsLimit",11,d.bindingsLimit),
      Field("defaultView",13,d.defaultView),Field("defaultPresent",14,d.defaultPresent))
  }
}

/** Read-only byte view. Allocation bounds authorize the complete line reads;
  * logical bounds authorize only the requested four-byte uniform component.
  */
class ShaderUniformBindingRecord extends Bundle {
  val identity=new GraphicsWorkIdentity;val bindingIndex=UInt(5.W);val stageMask=UInt(2.W)
  val base=UInt(32.W);val limit=UInt(33.W)
  val allocationBase=UInt(32.W);val allocationLimit=UInt(33.W);val readOnly=Bool()
}
object ShaderUniformBindingLayout {
  val magic=BigInt("47554201",16);val words=16
  def fields(d: ShaderUniformBindingRecord): Seq[NativeTypedGraphicsLayout.Field]={
    import NativeTypedGraphicsLayout.Field
    Seq(Field("identity.context",3,d.identity.context),Field("identity.pass",4,d.identity.pass),Field("identity.generation",5,d.identity.generation),
      Field("bindingIndex",6,d.bindingIndex),Field("stageMask",7,d.stageMask),Field("base",8,d.base),Field("limit",9,d.limit),
      Field("allocationBase",11,d.allocationBase),Field("allocationLimit",12,d.allocationLimit),Field("readOnly",14,d.readOnly))
  }
}
