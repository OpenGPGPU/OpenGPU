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
  val flags=7
  val fields=Seq("MAGIC","WORDS","FLAGS","COMPUTE_UNITS","LANES","WARPS",
    "LOCAL_ITEMS","LOCAL_AXIS","GRID_AXIS","SHARED_BYTES","SHARED_BANKS",
    "SIMT_STACK_ENTRIES","SHARED_BASE","AXI_DATA_BYTES","L2_DATA_BYTES","L2_BANKS")
  def words(p: ComputeResourceProfile): Seq[BigInt]={
    val values=Seq[BigInt](magic,wordCount,flags,p.computeUnits,p.gpu.lanes,p.gpu.warps,
      p.localItems,p.localAxis,BigInt("ffffffff",16),p.gpu.sharedMemoryBytes,p.gpu.sharedMemoryBanks,
      p.gpu.simtStackDepth,BigInt(p.gpu.sharedMemoryBase),p.axiDataBytes,p.l2DataBytes,p.gpu.l2Banks)
    require(values.size==wordCount && values.forall(v=>v>=0 && v<(BigInt(1)<<32)))
    values
  }
}
