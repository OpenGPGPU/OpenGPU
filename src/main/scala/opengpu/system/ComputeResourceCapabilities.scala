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
  val flags=7 | wordAtomics
  val fields=Seq("MAGIC","WORDS","FLAGS","COMPUTE_UNITS","LANES","WARPS",
    "LOCAL_ITEMS","LOCAL_AXIS","GRID_AXIS","SHARED_BYTES","SHARED_BANKS",
    "SIMT_STACK_ENTRIES","SHARED_BASE","AXI_DATA_BYTES","L2_DATA_BYTES","L2_BANKS")
  def words(p: ComputeResourceProfile, imageAvailable: Boolean=false, storageAvailable: Boolean=false, computeUniformAvailable: Boolean=false, computeTextureAvailable: Boolean=false, atomicCounterAvailable: Boolean=false): Seq[BigInt]={
    val values=Seq[BigInt](magic,wordCount,flags | (if(imageAvailable)imageCommands else 0) | (if(storageAvailable)storageCommands else 0) | (if(computeUniformAvailable)uniformCommands else 0) | (if(computeTextureAvailable)textureCommands else 0) | (if(atomicCounterAvailable)atomicCounterCommands else 0),p.computeUnits,p.gpu.lanes,p.gpu.warps,
      p.localItems,p.localAxis,BigInt("ffffffff",16),p.gpu.sharedMemoryBytes,p.gpu.sharedMemoryBanks,
      p.gpu.simtStackDepth,BigInt(p.gpu.sharedMemoryBase),p.axiDataBytes,p.l2DataBytes,p.gpu.l2Banks)
    require(values.size==wordCount && values.forall(v=>v>=0 && v<(BigInt(1)<<32)))
    values
  }
}
