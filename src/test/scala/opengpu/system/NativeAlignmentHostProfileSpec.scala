package opengpu.system

import opengpu.config.GpuConfig
import opengpu.graphics._
import opengpu.testutil.GraphicsTestResolution
import org.scalatest.flatspec.AnyFlatSpec

class NativeAlignmentHostProfileSpec extends AnyFlatSpec {
  it should "bind the product dimensions, full capacities and all integrated discovery blocks" in {
    val p = NativeAlignmentHostProfile.target
    assert(NativeAlignmentHostProfile.id == "two-cu-vga60-v2")
    assert(p.computeUnits == 2 && p.execution.lanes == 16 && p.execution.warps == 8)
    assert(p.graphics.screenWidth == 640 && p.graphics.screenHeight == 480)
    assert(p.transactionsPerCu == 16 && p.axiDataBytes == 32)
    assert(p.execution.l2Sets * p.execution.l2Ways * 64 == 131072)
    assert(p.native.engines == 4 && p.native.pagedCapacity == 65536)
    assert(p.native.renderTargetExtent.contains((2048,2048)))
    assert(p.native.vertex.input.attributes == 16 && p.native.vertex.input.bindings == 16)
    assert(p.native.vertex.varyings == 16 && p.native.fragment.colors == 4)
    assert(p.native.payload.get.capacityBytes == 524288 && p.native.transformFeedback)
    val words = p.requiredDiscovery.toMap
    assert(p.requiredDiscovery.size == 72 && words.size == 72)
    assert(words(0x368) == 0x7fff && words(0x36c) == 2)
    assert(words(0x160) == BigInt("474e4303",16) && words(0x174) == BigInt("08000800",16))
    for ((base, magic, count) <- Seq((0x340,"47434901",8),(0x240,"47515601",8),
        (0x260,"47464301",8),(0x280,"47515001",8),(0x3a0,"47554301",16))) {
      assert(words(base) == BigInt(magic,16) && words(base+4) == count)
      assert((0 until count).forall(n => words.contains(base+4*n)))
    }
    assert(!NativeAlignmentHostProfile.nativeGeometryDraw && !NativeAlignmentHostProfile.nativeAdvancedBlend
      && !NativeAlignmentHostProfile.nativePatchShaderImages)
  }

  it should "reject missing resource transaction capacity, feedback and translated image ownership" in {
    val p = NativeAlignmentHostProfile.target
    intercept[IllegalArgumentException](p.copy(transactionsPerCu = 8))
    intercept[IllegalArgumentException](p.copy(native = p.native.copy(transformFeedback = false)))
    intercept[IllegalArgumentException](p.copy(native = p.native.copy(translateImageViews = false)))
    intercept[IllegalArgumentException](p.copy(native = p.native.copy(payload = None)))
  }

  it should "elaborate the complete feature connections together in a bounded host" in {
    val gpu = GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2)
    val g = GraphicsConfig(screenWidth=math.min(4,GraphicsTestResolution.width),
      screenHeight=math.min(4,GraphicsTestResolution.height))
    val p = VertexShaderStageConfig(VertexInputFetchConfig(2,2),2)
    val f = FragmentShaderConfig(2,4)
    val n = NativeBinnedGraphicsConfig(p,f,engines=1,pageBytes=128,writeCredits=2,
      tileSlots=1,pagedCapacity=8,transformFeedback=true,
      payload=Some(ShaderPayloadConfig(capacityBytes=4096,maxPayloadBytes=1024,owners=2,ports=4)))
    val profile = NativeAlignmentHostConfig(g,gpu,computeUnits=2,native=n)
    val circuit = circt.stage.ChiselStage.elaborate(new NativeAlignmentHostSystemAxi(profile))
    val required = Set("TessellationNativeQuadJobImage", "TessellationNativeThreeDomainJobImage",
      "TessellationHostShaderRouter",
      "NativeVisibilityQueryEngine", "NativePrimitiveQueryEngine", "TransformFeedbackOwnerTransfer",
      "TypedFragmentImageStateReader", "ShaderImageCuService", "ShaderImageAtomicCuService", "StorageVectorCuService",
      "StorageAtomicVectorCuService", "ComputeUniformCuService", "ComputeTextureCuService",
      "AtomicCounterVectorCuService", "IndirectDispatchReader")
    val found = circuit.lazilySerialize.iterator.foldLeft(Set.empty[String]) { (seen, part) =>
      seen ++ required.filter(part.contains)
    }
    assert(found == required)
  }
}
