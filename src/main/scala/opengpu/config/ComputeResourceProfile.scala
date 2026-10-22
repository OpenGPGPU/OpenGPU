package opengpu.config

/** Elaboration-owned compute quantities. The system supplies its executing
  * compute configuration, rather than the potentially separate graphics one.
  */
case class ComputeResourceProfile(gpu: GpuConfig, computeUnits: Int, axiDataBytes: Int) {
  require(computeUnits>0)
  require(Set(4,8,16,32,64)(axiDataBytes))
  val localItems: BigInt=BigInt(gpu.lanes)*gpu.warps
  val localAxis: BigInt=localItems.min(65535)
  val l2DataBytes: BigInt=BigInt(gpu.l2Sets)*gpu.l2Ways*64
  require(localItems>0 && localItems<(BigInt(1)<<32))
  require(l2DataBytes<(BigInt(1)<<32))
  require(gpu.sharedMemoryBase>=0 && BigInt(gpu.sharedMemoryBase)+gpu.sharedMemoryBytes<=(BigInt(1)<<32))
}
