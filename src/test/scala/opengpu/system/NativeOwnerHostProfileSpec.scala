package opengpu.system

import opengpu.config.GpuConfig
import opengpu.graphics._
import org.scalatest.flatspec.AnyFlatSpec

class NativeOwnerHostProfileSpec extends AnyFlatSpec {
  it should "compose the full sample owner under a distinct 112-word identity and retain every capacity" in {
    val before=OwnerResourceProfile.target
    val sample=OwnerResourceProfile.sampleStage
    assert(OwnerResourceProfile.sampleStageId=="two-cu-vga60-owner-v2-sample-stage")
    assert(OwnerResourceProfile.sampleStageName=="geometry-compute-sample")
    assert(!before.native.sampleShading && !before.nativeSampleTransport)
    assert(sample.tessellationFragmentImages && sample.tessellationSampleShading)
    assert(!before.tessellationFragmentImages && !before.tessellationSampleShading)
    assert(sample.copy(native=before.native,tessellationFragmentImages=false,tessellationSampleShading=false)==before)
    assert(sample.nativeSampleTransport && sample.native.sampleShading)
    assert(sample.nativeGeometryTransport && sample.native.transformFeedback && sample.execution.enableSharedVectorAtomics)
    assert(sample.computeUnits==2 && sample.execution.lanes==16 && sample.execution.warps==8)
    assert(sample.native.vertex.varyings==16 && sample.native.fragment.colors==4 && sample.native.engines==4)
    assert(sample.native.payload==before.native.payload && sample.native.payload.get.capacityBytes==524288)
    assert(sample.native.fragment.prefixBytes(128,16,4,7)==48640)
    assert(sample.requiredDiscovery.size==112 && before.requiredDiscovery.size==104)
    assert(sample.requiredDiscovery.take(104).filterNot(_._1==0x368)==before.requiredDiscovery.filterNot(_._1==0x368))
    assert(sample.requiredDiscovery.toMap.apply(0x368)==0x1fffff && before.requiredDiscovery.toMap.apply(0x368)==0xfffff)
    assert(sample.requiredDiscovery.takeRight(8)==Seq[BigInt](0x47535301,8,1,31,192,0x4d041004,0x7ff,7)
      .zipWithIndex.map{case(value,index)=>(0x520+4*index,value)})
    intercept[IllegalArgumentException] { sample.copy(nativeGeometryTransport=false) }
    intercept[IllegalArgumentException] { before.copy(tessellationSampleShading=true) }
    assert(!OwnerResourceProfile.computeStage.tessellationFragmentImages && !OwnerResourceProfile.computeStage.tessellationSampleShading)
  }

  it should "compose complete protected geometry, shared atomics and feedback without relabeling either frozen owner" in {
    val p = OwnerResourceProfile.target
    assert(p.nativeGeometryTransport && p.native.nativeGeometry && p.nativeProtectedSources)
    assert(p.native.transformFeedback && p.execution.enableSharedVectorAtomics)
    assert(p.computeUnits == 2 && p.execution.lanes == 16 && p.execution.warps == 8)
    assert(p.graphics.screenWidth == 640 && p.graphics.screenHeight == 480)
    assert(p.transactionsPerCu == 16 && p.axiDataBytes == 32)
    assert(p.native.engines == 4 && p.native.pagedCapacity == 65536)
    assert(p.native.renderTargetExtent.contains((2048,2048)))
    assert(p.native.payload == NativeOwnerHostProfile.target.native.payload)
    assert(p.native.vertex == NativeOwnerHostProfile.target.native.vertex)
    assert(p.native.fragment == NativeOwnerHostProfile.target.native.fragment)
    assert(p.requiredDiscovery.size == 104)
    val words = p.requiredDiscovery.toMap
    assert(words(0x368) == 0xfffff && words(0x160) == 0x474e4305 && words(0x168) == 0x474e4a02)
    assert(words(0x17c) == BigInt("8813db02",16))
    assert(MemoryNativeBinnedGraphicsPipeline.transactions(p.native) == 2048)
    assert(p.requiredDiscovery.takeRight(8) == NativeGeometryTransportCapabilities.words.zipWithIndex.map {
      case (word,index) => (NativeGeometryTransportCapabilities.base+4*index,word)
    })
    assert(OwnerResourceProfile.computeStage.requiredDiscovery.size == 96)
    assert(!OwnerResourceProfile.computeStage.nativeGeometryTransport && !NativeOwnerHostProfile.target.native.nativeGeometry)
    assert(OwnerResourceProfile.computeStage.requiredDiscovery.toMap.apply(0x368) == 0x7ffff)
    intercept[IllegalArgumentException] {
      NativeOwnerHostProfile.target.copy(nativeGeometryTransport = true)
    }
    intercept[IllegalArgumentException] {
      p.copy(nativeProtectedSources = false)
    }
    assert(PrepareOwnerResourceFullHost.manifest(false).contains("\"resourceDiscoveryWords\":104"))
    assert(PrepareOwnerResourceFullHost.manifest(false).contains("\"fullResourceQualification\":false"))
  }

  it should "compose mandatory shared atomics without changing the frozen owner or any other resource" in {
    val before = NativeOwnerHostProfile.target
    val after = OwnerResourceProfile.computeStage
    assert(OwnerResourceProfile.version == 2 && OwnerResourceProfile.id == "two-cu-vga60-owner-v2")
    assert(OwnerResourceProfile.computeStageId.endsWith("-compute-stage"))
    assert(!before.execution.enableSharedVectorAtomics && after.execution.enableSharedVectorAtomics)
    assert(after.execution == before.execution.copy(enableSharedVectorAtomics = true))
    assert(after.copy(execution = before.execution) == before)
    assert(after.requiredDiscovery.toMap.apply(0x368) == 0x7ffff)
    assert(before.requiredDiscovery.toMap.apply(0x368) == 0x3ffff)
    assert(after.requiredDiscovery.filterNot(_._1 == 0x368) == before.requiredDiscovery.filterNot(_._1 == 0x368))
    intercept[IllegalArgumentException] {
      OwnerResourceProfile.withComputeAtomics(before.copy(computeUnits = 1))
    }
    intercept[IllegalArgumentException] {
      OwnerResourceProfile.withComputeAtomics(before.copy(execution = before.execution.copy(sharedMemoryBytes = 8192)))
    }
  }

  it should "retain the complete two-CU VGA capacities and exact 96-word owner contract" in {
    val p = NativeOwnerHostProfile.target
    assert(NativeOwnerHostProfile.id == "two-cu-vga60-owner-v1" && NativeOwnerHostProfile.targetFps == 60)
    assert(p.computeUnits == 2 && p.execution.lanes == 16 && p.execution.warps == 8)
    assert(p.graphics.screenWidth == 640 && p.graphics.screenHeight == 480)
    assert(p.transactionsPerCu == 16 && p.axiDataBytes == 32 && p.nativeProtectedSources)
    assert(p.execution.l2Sets*p.execution.l2Ways*64 == 131072)
    assert(p.native.engines == 4 && p.native.pagedCapacity == 65536)
    assert(p.native.renderTargetExtent.contains((2048,2048)))
    assert(p.native.vertex.input.attributes == 16 && p.native.vertex.input.bindings == 16)
    assert(p.native.vertex.varyings == 16 && p.native.fragment.colors == 4)
    assert(p.native.payload.get.capacityBytes == 524288 && p.native.payload.get.owners == 5)
    assert(p.native.transformFeedback && p.native.translateImageViews && p.native.descriptorPreflight)
    assert(p.native.storage.nativeAdvancedDraws)
    val expected = Vector(
      0x360 -> Vector[BigInt](0x47435201,16,0x3ffff,2,16,8,128,128,BigInt("ffffffff",16),16384,16,8,0x10000000,32,131072,4),
      0x160 -> Vector[BigInt](0x474e4303,8,0x474e4a01,0x08000200,BigInt("c2100800",16),0x08000800,BigInt("e4908000",16),BigInt("8813d902",16)),
      0x340 -> Vector[BigInt](0x47434901,8,0x7f,14,12,4,BigInt("ffffffff",16),128),
      0x240 -> Vector[BigInt](0x47515601,8,0x0b0a0908,0x47514f01,64,8,0x01001001,31),
      0x260 -> Vector[BigInt](0x47464301,8,0x1003010c,0x47544601,512,0x04400408,69,255),
      0x280 -> Vector[BigInt](0x47515001,8,0x0010040d,0x47504f01,64,0x00204008,0x8c88,63),
      0x3a0 -> Vector[BigInt](0x47554301,16,1,0x7f,3,12,24,16384,1024,1024,256,64,0x0e00000b,0x1c00002b,0x1e00002b,31),
      0x4c0 -> Vector[BigInt](0x47414201,8,8,0x7fff,BigInt("f00001ff",16),15,7,0),
      0x4e0 -> Vector[BigInt](0x474e5001,8,512,2048,2048,512,15,0),
      0x300 -> Vector[BigInt](0x47534c01,8,31,32,1048576,0x04051918,0x320,0x138)
    ).flatMap { case (base, words) => words.zipWithIndex.map { case (v,i) => (base+4*i,v) } }
    assert(p.requiredDiscovery == expected)
    assert(p.requiredDiscovery.map(_._1).distinct.size == 96)
    assert(NativeAlignmentHostProfile.target.requiredDiscovery.size == 72)
    assert(!NativeAlignmentHostProfile.target.nativeProtectedSources)
    assert(!NativeAlignmentHostProfile.nativeAdvancedBlend)
  }

  it should "reject protected source discovery without native descriptor preflight" in {
    intercept[IllegalArgumentException](NativeAlignmentHostProfile.target.copy(nativeProtectedSources = true))
  }

  it should "connect protected discovery, native preflight, advanced execution and shared atomics in the actual bounded host" in {
    val base = OwnerResourceProfile.computeStage
    val native = NativeBinnedGraphicsConfig(
      VertexShaderStageConfig(VertexInputFetchConfig(2,2),2), FragmentShaderConfig(2,4),
      storage=base.native.storage, engines=1,
      pageBytes=128,writeCredits=2,tileSlots=1,pagedCapacity=8,transformFeedback=true,
      payload=Some(ShaderPayloadConfig(capacityBytes=4096,maxPayloadBytes=1024,owners=2,ports=4)))
    val p=base.copy(graphics=GraphicsConfig(screenWidth=4,screenHeight=4),
      execution=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2,enableSharedVectorAtomics=true),native=native)
    val circuit=circt.stage.ChiselStage.elaborate(new NativeAlignmentHostSystemAxi(p))
    val required=Set("NativeBinnedGraphicsJobReader","TessellationNativeThreeDomainJobImage",
      "NativeVisibilityQueryEngine","NativePrimitiveQueryEngine","ComputeUniformCuService",
      "SharedAtomicVectorExecuteUnit")
    val found=circuit.lazilySerialize.iterator.foldLeft(Set.empty[String]) { (seen,part) =>
      seen ++ required.filter(part.contains)
    }
    assert(found == required)
  }
}
