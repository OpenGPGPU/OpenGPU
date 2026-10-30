package opengpu.graphics

import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import java.nio.file.{Files, Paths}
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse, VectorTlbFlush}
import opengpu.command.{GpuCommand, GpuCommandResult}
import opengpu.system.{GpuSystem, SharedCuShaderConfig}

/** Use the same characterized synchronous SRAM models as the physical pool. */
private[graphics] class NativeBankedTextureSramModels extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle { val present = Output(Bool()) })
  setInline("NativeBankedTextureSramModels.sv",
    "module NativeBankedTextureSramModels(output present); assign present=1'b1; endmodule\n" +
      Seq(32,64).map(width => Files.readString(Paths.get(
        s"depends/asap7_sram_0p0/generated/verilog/srambank_64x4x${width}_6t122.v"))).mkString("\n"))
}

/** Full-thread native stages sharing the production two-cluster texture pool.
  * A test compute task can occupy CU0 so the real dispatcher places the two
  * graphics owners on CU1/CU2, exercising both physical cluster destinations.
  */
private[graphics] class NativeBankedTextureBindingHarness extends Module {
  val config = GpuConfig(lanes=16, warps=8, l2Sets=8, l2Ways=2)
  val vertex = VertexShaderStageConfig()
  val fragment = FragmentShaderConfig()
  val graphics = GraphicsConfig()
  val payload = ShaderPayloadConfig(maxPayloadBytes=16384, owners=2, ports=6)
  private val hostTransactions = 64
  private val transactions = GpuSystem.totalMemoryTransactions(4, 4, hostTransactions)
  val io = IO(new Bundle {
    val vertexDraw = Flipped(Decoupled(new VertexShaderDraw(vertex)))
    val fragmentDraw = Flipped(Decoupled(new FragmentShaderDraw(fragment)))
    val in = Flipped(Decoupled(new Fp32RasterizedQuad(fragment.vertices, graphics)))
    val inputEnd = Flipped(Decoupled(new FragmentInputCompletion))
    val vertexOut = Decoupled(new ShadedVertexRecord(vertex))
    val fragmentOut = Decoupled(new FragmentShaderQuad(fragment, graphics))
    val vertexCompletion = Decoupled(new VertexShaderCompletion)
    val fragmentCompletion = Decoupled(new FragmentShaderCompletion)
    val memoryRequest = Decoupled(new ComputeMemoryRequest(config, 64, transactions))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, transactions)))
    val start = Input(Bool())
    val queued = Output(Vec(2, Bool()))
    val owned = Output(Vec(2, UInt(4.W)))
    val idle = Output(Bool())
    val graphicsIdle = Output(Bool())
    val reservedBytes = Output(UInt(32.W))
    val poolDrained = Output(Bool())
    val gpuCommand = Flipped(Decoupled(new GpuCommand(config, 8)))
    val gpuCompletion = Decoupled(new GpuCommandResult(8))
    val satp = Input(UInt(32.W))
    val flush = Flipped(Valid(new VectorTlbFlush(config)))
    val blockerBusy = Output(Bool())
    val trapped = Output(Bool())
    val sramModelsPresent = Output(Bool())
    // Observe only public pool handshakes, including the fallback flag. Merely
    // enabling groupedTexture would also allow an entirely unbanked success.
    val accepted = Output(Vec(2, Valid(new TextureRgba8GroupRequest)))
    val retired = Output(Vec(2, Valid(new NativeRgba8TextureResult)))
    val clusterRead = Output(Vec(2, Bool()))
  })
  private val v = Module(new VertexShaderStage(config, vertex, SharedCuShaderConfig(4),
    payload=Some(payload), payloadOwner=0, groupedTexture=true))
  private val f = Module(new FragmentShaderStage(config, fragment, SharedCuShaderConfig(4),
    payload=Some(payload), payloadOwner=1, groupedTexture=true))
  private val router = Module(new SharedShaderTaskRouter(config, 4, 2, shaderPayload=Some(payload)))
  private val system = Module(new GpuSystem(config, numComputeUnits=4, enableGraphicsKernels=true,
    enableFpuBackend=false, enableUnifiedCommands=true, graphicsHostTransactions=hostTransactions,
    instructionCacheSets=4, instructionCacheWays=1, vectorCacheSets=4, vectorCacheWays=1,
    shaderPayload=Some(payload), producerPayloadPins=4, nativeExecutionProfile=true))
  private val texture = Module(new NativeRgba8TextureSharedPool(config, numComputeUnits=4,
    clients=5, useBlackBoxes=true, rowsPerCluster=128))
  io.sramModelsPresent := Module(new NativeBankedTextureSramModels).io.present
  private def tie(data: Data): Unit = data match {
    case r: Record => r.elements.values.foreach(tie)
    case a: Vec[_] => a.foreach(tie)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input => b := false.B
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input => s := 0.S
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input => u := 0.U
    case _ => ()
  }
  tie(system.io)
  system.io.gpuCommand <> io.gpuCommand
  io.gpuCompletion <> system.io.gpuCompletion
  system.io.vectorSatp := io.satp
  system.io.instructionSatp := io.satp
  system.io.vectorTlbFlush := io.flush
  system.io.instructionTlbFlush := io.flush
  system.io.invalidateInstructionCache := io.flush.valid
  texture.io.satp := io.satp
  texture.io.flush := io.flush
  texture.io.invalidate := false.B
  texture.io.cancel := false.B
  v.io.draw <> io.vertexDraw
  f.io.draw <> io.fragmentDraw
  f.io.in <> io.in
  f.io.inputEnd <> io.inputEnd
  io.vertexOut <> v.io.out
  io.fragmentOut <> f.io.out
  io.vertexCompletion <> v.io.completion
  io.fragmentCompletion <> f.io.completion
  v.io.cancel := false.B
  f.io.cancel := false.B
  router.io.clients(0) <> v.io.shared
  router.io.clients(1) <> f.io.shared
  system.io.graphicsKernels.get <> router.io.pool
  system.io.graphicsKernels.get.launch.valid := router.io.pool.launch.valid && io.start
  router.io.pool.launch.ready := system.io.graphicsKernels.get.launch.ready && io.start
  system.io.graphicsPayload.get := router.io.poolPayload.get
  system.io.payloadProducerPins.get := router.io.payloadPins.get
  for ((port, i) <- Seq(v.io.payload.get, f.io.payload.get).zipWithIndex) {
    val m = system.io.payloadManagement.get
    m.control(i) <> port.control
    port.controlResponse <> m.controlResponse(i)
    m.request(i) <> port.request
    port.response <> m.response(i)
    port.context := 101.U
    port.cancel := false.B
    router.io.clientPayload.get(i) := port.task
    io.owned(i) := router.io.clients(i).owned
    val queued = RegInit(false.B)
    when(if(i==0) io.vertexDraw.fire else io.fragmentDraw.fire) { queued := false.B }
    when(router.io.clients(i).launch.fire) { queued := true.B }
    io.queued(i) := queued
  }
  for ((client, i) <- Seq(v.io.grouped.get, f.io.grouped.get).zipWithIndex) {
    texture.io.client(i) <> client
    texture.io.cuMask(i) := router.io.clients(i).owned
    io.accepted(i).valid := client.request.fire
    io.accepted(i).bits := client.request.bits
    io.retired(i).valid := client.response.fire
    io.retired(i).bits := client.response.bits
  }
  for (i <- 2 until 5) {
    texture.io.client(i).request.valid := false.B
    texture.io.client(i).request.bits := 0.U.asTypeOf(texture.io.client(i).request.bits)
    texture.io.client(i).response.ready := true.B
    texture.io.client(i).cancelGroup.valid := false.B
    texture.io.client(i).cancelGroup.bits := 0.U.asTypeOf(texture.io.client(i).cancelGroup.bits)
    texture.io.client(i).invalidate := false.B
    texture.io.cuMask(i) := 0.U
  }
  // Stage descriptors, both cluster data ports and both walkers have disjoint
  // ranges; local transaction IDs survive arbitrary interleaved replies.
  private val requests = Seq(v.io.memoryRequest, f.io.memoryRequest) ++
    texture.io.memoryRequest.toSeq ++ texture.io.pageWalk.toSeq
  private val replies = Seq(v.io.memoryResponse, f.io.memoryResponse) ++
    texture.io.memoryResponse.toSeq ++ texture.io.pageWalkResponse.toSeq
  private val ranges = Seq((0,4), (4,4), (8,16), (24,16), (40,4), (44,4))
  private val arbiter = Module(new RRArbiter(new ComputeMemoryRequest(config,64,hostTransactions),6))
  private val queue = Module(new Queue(new ComputeMemoryRequest(config,64,hostTransactions),2,pipe=true))
  private val response = system.io.graphicsHostResponse
  private val selected = Wire(Vec(6,Bool()))
  for (i <- requests.indices) {
    val (base, count) = ranges(i)
    arbiter.io.in(i).valid := requests(i).valid
    arbiter.io.in(i).bits := requests(i).bits
    arbiter.io.in(i).bits.transactionId := requests(i).bits.transactionId.pad(6) + base.U
    requests(i).ready := arbiter.io.in(i).ready
    selected(i) := response.bits.transactionId >= base.U && response.bits.transactionId < (base+count).U
    replies(i).valid := response.valid && selected(i)
    replies(i).bits := response.bits
    replies(i).bits.transactionId := response.bits.transactionId - base.U
    when(requests(i).fire) { assert(requests(i).bits.transactionId < count.U) }
  }
  queue.io.enq <> arbiter.io.out
  system.io.graphicsHostRequest <> queue.io.deq
  response.ready := (selected.zip(replies).map { case (s,r) => s && r.ready }).reduce(_ || _)
  when(response.valid) { assert(PopCount(selected) === 1.U) }
  for (i <- 0 until 2) {
    io.clusterRead(i) := texture.io.memoryRequest(i).fire && !texture.io.memoryRequest(i).bits.isWrite
  }
  io.graphicsIdle := !v.io.busy && !f.io.busy && router.io.drained && !texture.io.busy
  system.io.graphicsDrained := io.graphicsIdle
  system.io.graphicsShaderL1Invalidate.ready := true.B
  io.memoryRequest <> system.io.memoryRequest
  system.io.memoryResponse <> io.memoryResponse
  io.idle := system.io.vmContextIdle && io.graphicsIdle
  io.reservedBytes := system.io.payloadManagement.get.reservedBytes
  io.poolDrained := system.io.payloadManagement.get.drained
  io.blockerBusy := system.io.busyComputeUnits(0)
  io.trapped := system.io.trap.map(_.valid).reduce(_ || _)
}
