package opengpu.system

import opengpu.config.ComputeResourceProfile

/** GCR1 reports implemented compute quantities, not GLES/API conformance.
  * It occupies a separate read-only block and consumes no legacy feature bit.
  */
object ComputeResourceCapabilities {
  val base=0x360
  val wordCount=16
  val end=base+wordCount*4
  val magic=BigInt("47435201",16)
  // Exact checked local product, uint32 grid axes, one resident group per CU.
  // Word AMOs including CAS, without a claim of cross-domain acquire/release.
  val wordAtomics=1 << 3
  val imageCommands=1 << 4
  val storageCommands=1 << 5
  val uniformCommands=1 << 6
  val textureCommands=1 << 7
  val atomicCounterCommands=1 << 8
  val imageAtomics=1 << 9
  val storageAtomics=1 << 10
  // Internal native command transport, not API conformance or driver admission.
  val tessellationCommands=1 << 11
  val tessellationFeedback=1 << 12
  // Versioned target5 transports, not GL feature advertisement. Image support
  // includes the direct metadata-only function27 size instruction.
  val bufferTextures=1 << 13
  val bufferImages=1 << 14
  // Safe presence bit for GAB1 and opt-in native advanced payload admission.
  // This does not advertise KHR/GL frontend support.
  val nativeAdvancedBlend=1 << 15
  val nativeDescriptorPreflight=1 << 16
  // Presence contract for GNP1, GUC1 and GSL1 on protected native routing.
  // Kernel-private copies and userspace submission remain driver contracts.
  val nativeProtectedSources=1 << 17
  val nativeGeometryTransport=1 << 19
  val flags=7 | wordAtomics
  val fields=Seq("MAGIC","WORDS","FLAGS","COMPUTE_UNITS","LANES","WARPS",
    "LOCAL_ITEMS","LOCAL_AXIS","GRID_AXIS","SHARED_BYTES","SHARED_BANKS",
    "SIMT_STACK_ENTRIES","SHARED_BASE","AXI_DATA_BYTES","L2_DATA_BYTES","L2_BANKS")
  def words(p: ComputeResourceProfile, imageAvailable: Boolean=false, storageAvailable: Boolean=false, computeUniformAvailable: Boolean=false, computeTextureAvailable: Boolean=false, atomicCounterAvailable: Boolean=false, imageAtomicAvailable: Boolean=false, storageAtomicAvailable: Boolean=false, tessellationAvailable: Boolean=false, tessellationFeedbackAvailable: Boolean=false,nativeAdvancedBlendAvailable: Boolean=false,nativeDescriptorPreflightAvailable: Boolean=false,nativeProtectedSourcesAvailable: Boolean=false,nativeGeometryTransportAvailable: Boolean=false): Seq[BigInt]={
    require(!imageAtomicAvailable || imageAvailable, "image atomics require images")
    require(!storageAtomicAvailable || storageAvailable, "storage atomics require storage")
    require(!tessellationFeedbackAvailable || tessellationAvailable, "tessellation feedback requires tessellation")
    require(!nativeProtectedSourcesAvailable || nativeDescriptorPreflightAvailable,
      "native protected source discovery requires complete descriptor preflight")
    require(!nativeGeometryTransportAvailable || nativeProtectedSourcesAvailable,
      "native geometry transport discovery requires protected sources")
    val values=Seq[BigInt](magic,wordCount,flags | (if(imageAvailable)imageCommands | bufferImages else 0) | (if(storageAvailable)storageCommands else 0) | (if(computeUniformAvailable)uniformCommands else 0) | (if(computeTextureAvailable)textureCommands | bufferTextures else 0) | (if(atomicCounterAvailable)atomicCounterCommands else 0) | (if(imageAtomicAvailable)imageAtomics else 0) | (if(storageAtomicAvailable)storageAtomics else 0) | (if(tessellationAvailable)tessellationCommands else 0) | (if(tessellationFeedbackAvailable)tessellationFeedback else 0) | (if(nativeAdvancedBlendAvailable)nativeAdvancedBlend else 0) | (if(nativeDescriptorPreflightAvailable)nativeDescriptorPreflight else 0) | (if(nativeProtectedSourcesAvailable)nativeProtectedSources else 0) | (if(nativeGeometryTransportAvailable)nativeGeometryTransport else 0),p.computeUnits,p.gpu.lanes,p.gpu.warps,
      p.localItems,p.localAxis,BigInt("ffffffff",16),p.gpu.sharedMemoryBytes,p.gpu.sharedMemoryBanks,
      p.gpu.simtStackDepth,BigInt(p.gpu.sharedMemoryBase),p.axiDataBytes,p.l2DataBytes,p.gpu.l2Banks)
    require(values.size==wordCount && values.forall(v=>v>=0 && v<(BigInt(1)<<32)))
    values
  }
}
