package opengpu.graphics

import chisel3._

object ShaderUniformLimits {
  val blocksPerStage=12;val bindings=24;val blockBytes=16384;val offsetAlignment=256
  // VS, FS and compute keep their existing indices. GS is a distinct owner.
  val defaultComponents=Seq(1024,1024,1024,1024,1024,1024)
  val defaultBlock=31
}

/** Optional immutable GUR1 root. Version zero is canonical disabled state. */
class ShaderUniformResources extends Bundle {val version=UInt(2.W);val address=UInt(32.W)}
object ShaderUniformResources {
  def fields(base: Int,prefix: String,r: ShaderUniformResources): Seq[NativeTypedGraphicsLayout.Field]=
    Seq(NativeTypedGraphicsLayout.Field(prefix+"version",base,r.version),NativeTypedGraphicsLayout.Field(prefix+"address",base+1,r.address))
  def legal(r: ShaderUniformResources): Bool=(r.version===0.U && r.address===0.U) ||
    (r.version===1.U && r.address(5,0)===0.U && r.address.pad(33)+64.U<=(BigInt(1)<<32).U)
}

/** Explicit geometry-only resource contract. Old graphics/compute providers
  * continue to reject version two and its distinct root/binding encodings.
  * This does not advertise GUC1 stage support or enable a native GS draw.
  */
object GeometryUniformResources {
  val stage=3;val stageMask=8;val version=2
  val rootMagic=BigInt("47555202",16);val bindingMagic=BigInt("47554202",16)
  def legal(r: ShaderUniformResources): Bool=(r.version===0.U && r.address===0.U) ||
    (r.version===version.U && r.address(5,0)===0.U && r.address.pad(33)+64.U<=(BigInt(1)<<32).U)
}

/** GUR3/GUB3 is exclusive to the two tessellation stages. Private table
  * indices remain five bits; application binding numbers are not encoded here.
  * A shared immutable view may authorize TCS and TES, never another stage.
  */
object TessellationUniformResources {
  val controlStage=4;val evaluationStage=5;val stageMask=0x30;val version=3
  val rootMagic=BigInt("47555203",16);val bindingMagic=BigInt("47554203",16)
  def isStage(stage:Int):Boolean=stage==controlStage || stage==evaluationStage
  def legal(r:ShaderUniformResources):Bool=(r.version===0.U && r.address===0.U) ||
    (r.version===version.U && r.address(5,0)===0.U && r.address.pad(33)+64.U<=(BigInt(1)<<32).U)
}

/** Each stage owns its root and forbidden writable prefix. The enclosing task
  * captures both scopes and the identity before either physical CU launch.
  */
class TessellationUniformStageResources extends Bundle {
  val resources=new ShaderUniformResources
  val privateBase=UInt(32.W);val privateLimit=UInt(33.W)
}
class TessellationUniformTaskResources extends Bundle {
  val identity=new GraphicsWorkIdentity
  val control=new TessellationUniformStageResources
  val evaluation=new TessellationUniformStageResources
}
object TessellationUniformTaskResources {
  def legal(s:TessellationUniformTaskResources):Bool={
    def stage(v:TessellationUniformStageResources):Bool={
      val prefix=Mux(v.privateLimit===0.U,v.privateBase===0.U,
        v.privateLimit>v.privateBase && v.privateLimit<=(BigInt(1)<<32).U)
      TessellationUniformResources.legal(v.resources) && prefix &&
        (v.resources.version=/=0.U || (v.privateBase===0.U && v.privateLimit===0.U))
    }
    stage(s.control) && stage(s.evaluation) &&
      ((s.control.resources.version=/=0.U || s.evaluation.resources.version=/=0.U) || s.asUInt===0.U)
  }
}

/** Twelve program-block mappings select a shared twenty-four-binding table.
  * Six five-bit indices occupy each map word; inactive indices must be zero.
  * Default uniforms use a separate view and do not consume an API binding.
  */
class ShaderUniformRootRecord(stageWidth: Int = 1) extends Bundle {
  val identity=new GraphicsWorkIdentity;val stage=UInt(stageWidth.W);val activeMask=UInt(12.W)
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
class ShaderUniformBindingRecord(stageMaskWidth: Int = 2) extends Bundle {
  val identity=new GraphicsWorkIdentity;val bindingIndex=UInt(5.W);val stageMask=UInt(stageMaskWidth.W)
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
