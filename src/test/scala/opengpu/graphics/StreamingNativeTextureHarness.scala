package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse}

private[graphics] class StreamingNativeTextureHarness(config: GpuConfig, stage: Int, instructionSnapshots: Boolean=true) extends Module {
  val io = IO(new Bundle {
    val native = new NativeShaderTextureIO(config)
    val dataRequest = Decoupled(new ComputeMemoryRequest(config, 64, 16))
    val dataResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, 16)))
    val pageWalk = Decoupled(new ComputeMemoryRequest(config, 64, 4))
    val pageWalkResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, 4)))
    val globalCancel = Input(Bool()); val holdGroupReplies = Input(Bool()); val groupFire = Output(Bool()); val groupTag = Output(UInt(32.W)); val returnedGroups = Output(UInt(32.W)); val maximumGroups = Output(UInt(5.W))
    val groupRequests = Output(UInt(32.W)); val dataReads = Output(UInt(32.W)); val sharedBusy = Output(Bool())
  })
  private val endpoint = Module(new NativeShaderTextureUnit(config, stage, groupedTexture = true, instructionSnapshots = instructionSnapshots))
  private val cluster = Module(new NativeRgba8TexturePool(config, numComputeUnits = 2, clients = 1, maxOutstandingPerClient = 4))
  io.native.in <> endpoint.io.in; io.native.vectorIn <> endpoint.io.vectorIn
  io.native.commit <> endpoint.io.commit; io.native.vectorCommit <> endpoint.io.vectorCommit
  io.native.memoryRequest <> endpoint.io.memoryRequest; endpoint.io.memoryResponse <> io.native.memoryResponse
  endpoint.io.resources := io.native.resources; endpoint.io.identity := io.native.identity
  endpoint.io.texFormat := io.native.texFormat; endpoint.io.texBase := io.native.texBase
  endpoint.io.texWidth := io.native.texWidth; endpoint.io.texHeight := io.native.texHeight
  endpoint.io.wrapClamp := io.native.wrapClamp; endpoint.io.coordinatePolicy := io.native.coordinatePolicy
  endpoint.io.texMaxLevel := io.native.texMaxLevel; endpoint.io.lodBias := io.native.lodBias; endpoint.io.minLevel := io.native.minLevel
  endpoint.io.clearParameters := io.native.clearParameters; endpoint.io.cancel := io.native.cancel || io.globalCancel; endpoint.io.invalidate := io.native.invalidate
  io.native.busy := endpoint.io.busy; io.native.coordinateFault := endpoint.io.coordinateFault
  io.native.responseFault := endpoint.io.responseFault; io.native.faultAddress := endpoint.io.faultAddress
  val port=endpoint.io.grouped.get;val shared=cluster.io.client(0)
  shared.request <> port.request;shared.cancelGroup:=port.cancelGroup;shared.invalidate:=port.invalidate
  port.response.valid:=shared.response.valid && !io.holdGroupReplies;port.response.bits:=shared.response.bits
  shared.response.ready:=port.response.ready && !io.holdGroupReplies
  cluster.io.cuMask(0):=1.U
  io.groupFire:=port.request.fire;io.groupTag:=port.request.bits.tag
  val returned=RegInit(0.U(32.W));val maximum=RegInit(0.U(5.W))
  when(port.response.fire){returned:=returned+1.U}
  io.returnedGroups:=returned;io.maximumGroups:=maximum
  cluster.io.satp := 0.U; cluster.io.flush.valid := false.B; cluster.io.flush.bits := 0.U.asTypeOf(cluster.io.flush.bits)
  cluster.io.invalidate := false.B; cluster.io.cancel := io.globalCancel
  io.dataRequest <> cluster.io.memoryRequest(0); cluster.io.memoryResponse(0) <> io.dataResponse
  io.pageWalk <> cluster.io.pageWalk(0); cluster.io.pageWalkResponse(0) <> io.pageWalkResponse
  val groups = RegInit(0.U(32.W)); val reads = RegInit(0.U(32.W))
  when(endpoint.io.grouped.get.request.fire) { groups := groups + 1.U }
  when(io.dataRequest.fire) { reads := reads + 1.U }
  when(groups-returned>maximum){maximum:=groups-returned}
  assert(groups-returned<=4.U)
  io.groupRequests := groups; io.dataReads := reads; io.sharedBusy := cluster.io.busy
}
