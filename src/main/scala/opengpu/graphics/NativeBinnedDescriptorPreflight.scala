package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig

/** Static GNJ metadata contract. This never executes a shader, walks a vertex
  * or indirect argument buffer, or proves physical/CPU immutability. The raw
  * caller must publish immutable records/resources through complete drain;
  * the protected driver entry enforces descriptor/code/uniform ownership.
  */
object NativeBinnedDescriptorPreflight {
  def vertex(draw: VertexBinDraw,p: VertexShaderStageConfig,c: GpuConfig,
      frame: TypedVertexBinImageDescriptor,g: GraphicsConfig,geometry: Option[Bool]=None): Bool={
    val v=draw.vertex.geometry.vertex
    val stride=(c.lanes*c.warps*4).U
    val end=v.scratch.base+&(7.U+&(v.attributeSlots<<2)+&(v.varyingSlots<<2)+&Mux(v.version===2.U,1.U,0.U))*stride
    val (shaderInvalid,shaderBounds)=VertexShaderDescriptor.errors(v,p,c,end)
    val (inputInvalid,inputBounds)=VertexInputDescriptor.errors(v.input,p.input)
    // An indirect record changes counts at execution. Its metadata, modes and
    // views are still checked by NativeBinnedGraphicsLayout.fit; live argument
    // contents are not presented as a preflight guarantee.
    VertexBinDescriptor.legal(draw,frame.bins,g,geometry) && !shaderInvalid && !shaderBounds && !inputInvalid && !inputBounds
  }

  def fragment(state: TypedFragmentState,draw: VertexBinDraw,
      frame: TypedVertexBinImageDescriptor,engines: Int,nativeAdvanced: Boolean,geometry: Option[Bool]=None): Bool={
    val f=state.fragment;val colors=frame.image.pixels.colors
    val slots=(0 until 4).map{e=>
      val v=TypedFragmentStateLayout.scratch(state,e)
      val start=frame.scratch(e).base.pad(64)+draw.state.pad(64)*frame.scratchStride.pad(64)
      e.U>=state.engineCount || (v.base>=start && v.limit<=start+frame.scratchStride.pad(64) &&
        v.limit<=frame.scratch(e).limit)
    }.reduce(_ && _)
    val types=colors.colors.zipWithIndex.map{case(v,i)=>
      !v.enabled || i.U>=f.colorSlots || f.colorTypes(i)===TypedColorFormat.kind(v.format)
    }.reduce(_ && _)
    val advanced=if(nativeAdvanced)TypedBlendState.advancedOutputTypes(state.blend,f.colorTypes) &&
      colors.colors.zipWithIndex.map{case(v,i)=>
        !v.enabled || !TypedBlendState.advanced(state.blend,i.U) || TypedColorFormat.kind(v.format)===0.U
      }.reduce(_ && _)else true.B
    // A GND2 caller must subsequently compare both counts with its accepted
    // GNG link metadata; ordinary draws retain exact VS-to-FS equality.
    val varyingLink=geometry.map(gs=>gs || f.varyingSlots===draw.vertex.geometry.vertex.varyingSlots)
      .getOrElse(f.varyingSlots===draw.vertex.geometry.vertex.varyingSlots)
    state.engineCount===engines.U && varyingLink &&
      f.sampleMode===draw.raster.sampleMode && f.sampleMode===colors.sampleMode && slots && types && advanced
  }
}
