package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.{CachePolicy, GpuConfig}
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse}

/** Version zero is canonical disabled state; version one addresses a GIR1 root. */
class ShaderImagePublication extends Bundle {
  val version = UInt(2.W)
  val address = UInt(32.W)
}
object ShaderImagePublication {
  def legal(p: ShaderImagePublication): Bool =
    (p.version === 0.U && p.address === 0.U) ||
      (p.version === 1.U && p.address(5, 0) === 0.U && p.address.pad(33) + 64.U <= (BigInt(1) << 32).U)
}
class ShaderImagePublicationRoot extends Bundle {
  val identity = new GraphicsWorkIdentity
  val stage = UInt(GraphicsStage.width.W)
  val tableVersion = UInt(8.W)
  val tableBase = UInt(32.W)
  val tableLimit = UInt(33.W)
}
object ShaderImagePublicationLayout {
  val magic = BigInt("47495201", 16); val words = 16; val bytes = 64
  def fields(root: ShaderImagePublicationRoot): Seq[NativeTypedGraphicsLayout.Field] = {
    import NativeTypedGraphicsLayout.Field
    Seq(Field("identity.context", 3, root.identity.context), Field("identity.pass", 4, root.identity.pass),
      Field("identity.generation", 5, root.identity.generation), Field("stage", 6, root.stage),
      Field("table.version", 7, root.tableVersion), Field("table.base", 8, root.tableBase),
      Field("table.limit", 9, root.tableLimit))
  }
}
class ShaderImagePublicationRequest extends Bundle {
  val publication = new ShaderImagePublication
  val identity = new GraphicsWorkIdentity
}
class ShaderImagePublicationResult extends Bundle {
  /** 0 success, 1 malformed descriptor, 2 bounds, 3 memory fault, 4 cancelled, 5 identity/stage. */
  val status = UInt(3.W)
  val task = new ShaderImageTask
}

/** Reads one immutable fragment image root. The enclosing owner must pin the
  * root, table, backing allocations and VM until all tasks using it drain; this
  * module does not prove physical alias separation or API visibility.
  */
class ShaderImagePublicationReader(config: GpuConfig = GpuConfig()) extends Module {
  require(config.xLen == 32)
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ShaderImagePublicationRequest))
    val out = Decoupled(new ShaderImagePublicationResult)
    val memoryRequest = Decoupled(new ComputeMemoryRequest(config, 64, 1))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, 1)))
    val cancel = Input(Bool())
    val busy = Output(Bool())
  })
  private object State extends ChiselEnum { val idle, check, offer, waitReply, verify, finish, complete = Value }
  private val phase = RegInit(State.idle)
  private val request = Reg(new ShaderImagePublicationRequest)
  private val root = Reg(new ShaderImagePublicationRoot)
  private val result = Reg(new ShaderImagePublicationResult)
  private val status = RegInit(0.U(3.W))
  private val malformed = RegInit(false.B)
  private val active = phase =/= State.idle && phase =/= State.complete
  private val stop = status =/= 0.U || io.cancel
  io.in.ready := phase === State.idle && !io.cancel
  io.out.valid := phase === State.complete; io.out.bits := result
  io.busy := phase =/= State.idle
  io.memoryRequest.valid := phase === State.offer
  io.memoryRequest.bits := 0.U.asTypeOf(io.memoryRequest.bits)
  io.memoryRequest.bits.address := request.publication.address
  io.memoryRequest.bits.sizeLog2 := 6.U; io.memoryRequest.bits.byteMask := Fill(64, 1.U(1.W))
  io.memoryRequest.bits.cachePolicy := CachePolicy.uncached
  io.memoryResponse.ready := phase === State.waitReply && io.memoryResponse.bits.transactionId === 0.U
  private val fields = ShaderImagePublicationLayout.fields(root)
  NativeTypedGraphicsLayout.check(root, fields, ShaderImagePublicationLayout.words)
  private val bad = NativeTypedGraphicsLayout.receive(fields, ShaderImagePublicationLayout.words,
    ShaderImagePublicationLayout.magic, 0.U, io.memoryResponse.bits.readData,
    io.memoryResponse.fire && !io.memoryResponse.bits.fault && !stop)
  when(active && io.cancel && status === 0.U) { status := 4.U }
  switch(phase) {
    is(State.idle) { when(io.in.fire) {
      request := io.in.bits; root := 0.U.asTypeOf(root); status := 0.U; malformed := false.B; phase := State.check
    }}
    is(State.check) {
      when(stop) { phase := State.finish }
        .elsewhen(!ShaderImagePublication.legal(request.publication)) { status := 1.U; phase := State.finish }
        .elsewhen(request.publication.version === 0.U) { phase := State.finish }
        .otherwise { phase := State.offer }
    }
    // Once offered, a request remains valid even if cancellation arrives.
    is(State.offer) { when(io.memoryRequest.fire) { phase := State.waitReply } }
    is(State.waitReply) { when(io.memoryResponse.fire) {
      when(io.memoryResponse.bits.fault && !stop) { status := 3.U }
      when(!io.memoryResponse.bits.fault && !stop) { malformed := bad }
      phase := State.verify
    }}
    is(State.verify) {
      val limit = root.tableLimit; val base = root.tableBase
      val bounds = base(5, 0) === 0.U && limit(5, 0) === 0.U && limit > base &&
        limit <= (BigInt(1) << 32).U && (limit - base)(7, 0) === 0.U
      when(!stop) {
        when(malformed || root.tableVersion =/= 1.U) { status := 1.U }
          .elsewhen(root.identity.asUInt =/= request.identity.asUInt || root.stage =/= GraphicsStage.fragment.U) { status := 5.U }
          .elsewhen(!bounds) { status := 2.U }
      }
      phase := State.finish
    }
    is(State.finish) {
      result := 0.U.asTypeOf(result); result.status := Mux(io.cancel && status === 0.U, 4.U, status)
      result.task.stage := GraphicsStage.fragment.U
      when(!stop && request.publication.version === 1.U) {
        result.task.enabled := true.B; result.task.table.version := root.tableVersion
        result.task.table.identity := root.identity; result.task.table.base := root.tableBase; result.task.table.limit := root.tableLimit
      }
      phase := State.complete
    }
    is(State.complete) { when(io.out.fire) { phase := State.idle } }
  }
}
