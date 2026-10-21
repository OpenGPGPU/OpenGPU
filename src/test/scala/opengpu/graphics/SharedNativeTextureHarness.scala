package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse}

private[graphics] class SharedNativeTextureHarness(config: GpuConfig, stage: Int) extends Module {
  val io = IO(new Bundle {
    val native = new NativeShaderTextureIO(config)
    val dataRequest = Decoupled(new ComputeMemoryRequest(config, 64, 16))
    val dataResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, 16)))
    val pageWalk = Decoupled(new ComputeMemoryRequest(config, 64, 4))
    val pageWalkResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, 4)))
    val groupRequests = Output(UInt(32.W)); val dataReads = Output(UInt(32.W)); val sharedBusy = Output(Bool())
  })
  private val endpoint = Module(new NativeShaderTextureUnit(config, stage, groupedTexture = true))
  private val cluster = Module(new NativeRgba8TextureCluster(config, clients = 1))
  io.native.in <> endpoint.io.in; io.native.vectorIn <> endpoint.io.vectorIn
  io.native.commit <> endpoint.io.commit; io.native.vectorCommit <> endpoint.io.vectorCommit
  io.native.memoryRequest <> endpoint.io.memoryRequest; endpoint.io.memoryResponse <> io.native.memoryResponse
  endpoint.io.resources := io.native.resources; endpoint.io.identity := io.native.identity
  endpoint.io.texFormat := io.native.texFormat; endpoint.io.texBase := io.native.texBase
  endpoint.io.texWidth := io.native.texWidth; endpoint.io.texHeight := io.native.texHeight
  endpoint.io.wrapClamp := io.native.wrapClamp; endpoint.io.coordinatePolicy := io.native.coordinatePolicy
  endpoint.io.texMaxLevel := io.native.texMaxLevel; endpoint.io.lodBias := io.native.lodBias; endpoint.io.minLevel := io.native.minLevel
  endpoint.io.clearParameters := io.native.clearParameters; endpoint.io.cancel := io.native.cancel; endpoint.io.invalidate := io.native.invalidate
  io.native.busy := endpoint.io.busy; io.native.coordinateFault := endpoint.io.coordinateFault
  io.native.responseFault := endpoint.io.responseFault; io.native.faultAddress := endpoint.io.faultAddress
  cluster.io.client(0) <> endpoint.io.grouped.get
  cluster.io.satp := 0.U; cluster.io.flush.valid := false.B; cluster.io.flush.bits := 0.U.asTypeOf(cluster.io.flush.bits)
  cluster.io.invalidate := false.B; cluster.io.cancel := false.B
  io.dataRequest <> cluster.io.memoryRequest; cluster.io.memoryResponse <> io.dataResponse
  io.pageWalk <> cluster.io.pageWalk; cluster.io.pageWalkResponse <> io.pageWalkResponse
  val groups = RegInit(0.U(32.W)); val reads = RegInit(0.U(32.W))
  when(endpoint.io.grouped.get.request.fire) { groups := groups + 1.U }
  when(io.dataRequest.fire) { reads := reads + 1.U }
  io.groupRequests := groups; io.dataReads := reads; io.sharedBusy := cluster.io.busy
}
