package opengpu.graphics

import chisel3.util.log2Ceil
import opengpu.config.GpuConfig

/** Resident storage for full native shader interfaces at the default CU capacity.
  * A vertex batch includes sixteen vec4 inputs, built-ins, position, sixteen
  * vec4 outputs and point size. Fragment reservations include point coordinates
  * and four color targets. Runtime bindings still reserve their actual byte count.
  */
object NativeShaderPayloadSizing {
  private val execution=GpuConfig()
  private val capacity=execution.lanes*execution.warps
  private val vertex=VertexShaderStageConfig()
  private val fragment=FragmentShaderConfig()
  val maxPayloadBytes: Int=math.max(
    vertex.prefixBytes(capacity,vertex.input.attributes,vertex.varyings,version=2),
    fragment.prefixBytes(capacity,fragment.varyings,fragment.colors,version=2))

  /** Keep one complete reservation per native producer/fragment owner. */
  def pool(owners: Int,ports: Int): ShaderPayloadConfig={
    require(owners>0 && ports>0)
    val slotBytes=((maxPayloadBytes+63)/64)*64
    val required=owners.toLong*slotBytes
    require(required<=(1L<<30),"native payload storage exceeds supported byte capacity")
    ShaderPayloadConfig(capacityBytes=1<<log2Ceil(required.toInt),
      maxPayloadBytes=maxPayloadBytes,owners=owners,ports=ports)
  }
}
