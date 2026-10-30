package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.backend.{VectorCommitRequest, VectorTextureRequest}
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse}

/** CUSTOM-1 vector image operations. Parameter pairs stay on chip; a load
  * obtains all four components once, then component reads use that snapshot.
  */
object ShaderImageOpcode {
  private def function(i: UInt, n: Int): Bool = i(6, 0) === "h2b".U && i(31, 26) === n.U
  def parameters(i: UInt): Bool = function(i, 8) && i(14, 12) < 4.U && i(11, 7) === 0.U
  def load(i: UInt): Bool = function(i, 9) && i(14, 12) === 0.U
  def store(i: UInt): Bool = function(i, 10) && i(14, 12) === 0.U && i(11, 7) === 0.U
  def component(i: UInt): Bool = function(i, 11) && i(14, 12) > 0.U && i(14, 12) < 4.U && i(24, 15) === 0.U
  def legal(i: UInt): Bool = parameters(i) || load(i) || store(i) || component(i)
}

class ShaderImageExecuteRequest(config: GpuConfig) extends VectorTextureRequest(config) {
  /** Trusted invocation policy, captured with the instruction. Only stores
    * intersect this mask; helper lanes may still compute/load image values.
    */
  val sideEffectMask = UInt(config.lanes.W)
}

/** One vector image instruction through checked GPU descriptors and ACK drain.
  * The task parent supplies the immutable table and retains its publication,
  * translation and coherence lease through instruction retirement. It must
  * clear parameters on every task launch, even if the identity is reused.
  * This module does not independently install an opcode in the CU decoder.
  */
class ShaderImageExecuteUnit(config: GpuConfig = GpuConfig(), stage: Int = GraphicsStage.compute,
    imageUnits: Int = 4) extends Module {
  require(stage >= GraphicsStage.vertex && stage <= GraphicsStage.tessEvaluation)
  require(TextureImageFormat.width <= 8)
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ShaderImageExecuteRequest(config)))
    val commit = Decoupled(new VectorCommitRequest(config))
    val table = Input(new TextureImageLevelTable)
    val enabled = Input(Bool())
    val clearParameters = Input(Bool())
    val invalidate = Input(Bool())
    val cancel = Input(Bool())
    val memoryRequest = Decoupled(new ComputeMemoryRequest(config, 64, 2))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, 2)))
    val busy = Output(Bool())
    val status = Output(UInt(3.W))
    val faultAddressValid = Output(Bool())
    val faultAddress = Output(UInt(32.W))
  })
  private object State extends ChiselEnum { val idle, check, parameters, next, offer, waitResult, finish, commit = Value }
  private val phase = RegInit(State.idle)
  private val values = Reg(Vec(config.warps, Vec(config.lanes, Vec(8, UInt(32.W)))))
  private val valid = RegInit(VecInit(Seq.fill(config.warps)(VecInit(Seq.fill(config.lanes)(0.U(4.W))))))
  private val loaded = Reg(Vec(config.warps, Vec(config.lanes, Vec(4, UInt(32.W)))))
  private val loadedValid = RegInit(VecInit(Seq.fill(config.warps)(0.U(config.lanes.W))))
  private val instruction = Reg(UInt(32.W))
  private val warp = Reg(UInt(config.warpIdWidth.W)); private val pc = Reg(UInt(32.W))
  private val active = Reg(UInt(config.lanes.W)); private val mask = Reg(UInt(config.lanes.W))
  private val remaining = Reg(UInt(config.lanes.W))
  private val lane = Reg(UInt(math.max(1, log2Ceil(config.lanes)).W))
  private val left = Reg(Vec(config.lanes, UInt(32.W))); private val right = Reg(Vec(config.lanes, UInt(32.W)))
  private val data = Reg(Vec(config.lanes, UInt(32.W)))
  private val pendingLoad = Reg(Vec(config.lanes, Vec(4, UInt(32.W))))
  private val table = Reg(new TextureImageLevelTable)
  private val status = RegInit(0.U(3.W)); private val faultValid = RegInit(false.B)
  private val faultAddress = Reg(UInt(32.W)); private val owned = RegInit(false.B)
  private val request = Reg(new ShaderImageResourceAccessRequest)
  private val access = Module(new ShaderImageResourceAccess(config, imageUnits = imageUnits))
  private val parameters = ShaderImageOpcode.parameters(instruction)
  private val load = ShaderImageOpcode.load(instruction)
  private val store = ShaderImageOpcode.store(instruction)
  private val component = ShaderImageOpcode.component(instruction)
  private val pair = instruction(13, 12)
  private val stopped = status =/= 0.U || io.cancel

  io.in.ready := phase === State.idle && !io.clearParameters
  io.busy := phase =/= State.idle
  io.status := status; io.faultAddressValid := faultValid
  io.faultAddress := Mux(faultValid, faultAddress, 0.U)
  io.memoryRequest <> access.io.memoryRequest; access.io.memoryResponse <> io.memoryResponse
  access.io.in.valid := phase === State.offer; access.io.in.bits := request
  access.io.out.ready := phase === State.waitResult
  access.io.cancel := owned && (io.cancel || status === ShaderImageAccessStatus.cancelled.U)
  access.io.invalidate := io.invalidate || io.clearParameters
  when(io.clearParameters) {
    assert(phase === State.idle && !access.io.busy, "image task reset escaped instruction ownership")
    valid.foreach(_.foreach(_ := 0.U)); loadedValid.foreach(_ := 0.U)
  }
  when(phase =/= State.idle && phase =/= State.commit && io.cancel && status === 0.U) {
    status := ShaderImageAccessStatus.cancelled.U
  }
  io.commit.valid := phase === State.commit
  io.commit.bits := 0.U.asTypeOf(io.commit.bits)
  io.commit.bits.writeback.warpId := warp
  io.commit.bits.writeback.vd := Mux(parameters || store, 0.U, instruction(11, 7))
  io.commit.bits.writeback.data := Mux(status === 0.U, data, 0.U.asTypeOf(data))
  io.commit.bits.writesVd := status === 0.U && (load || component)
  // A successful non-writing operation retires through the existing x0 ACK.
  // Failure emits neither kind of write, using ordinary texture fault retirement.
  io.commit.bits.writesScalar := status === 0.U && (parameters || store)
  io.commit.bits.scalarData := 0.U
  io.commit.bits.pc := pc; io.commit.bits.warpActiveMask := active

  switch(phase) {
    is(State.idle) {
      when(io.in.fire) {
        val q = io.in.bits; val d = q.issued.decode
        assert(d.warpId < config.warps.U)
        assert(!access.io.busy && !owned, "image instruction abandoned an access")
        instruction := d.instruction; warp := d.warpId; pc := d.pc; active := d.activeMask
        val execution = q.executionMask & d.activeMask
        val selected = Mux(ShaderImageOpcode.store(d.instruction), execution & q.sideEffectMask, execution)
        mask := selected; remaining := selected
        left := q.issued.vs1Data; right := q.issued.vs2Data; data := q.issued.oldVdData
        table := io.table; faultValid := false.B; faultAddress := 0.U; owned := false.B
        status := Mux(!io.enabled, ShaderImageAccessStatus.permission.U,
          Mux(io.cancel, ShaderImageAccessStatus.cancelled.U, 0.U))
        when(ShaderImageOpcode.load(d.instruction)) {
          loadedValid(d.warpId) := loadedValid(d.warpId) & ~selected
        }
        phase := State.check
      }
    }
    is(State.check) {
      // Validate every participating tuple before the first lane can store.
      val bad = VecInit((0 until config.lanes).map { l =>
        val w = values(warp)(l); val required = Mux(store, 15.U, 3.U)
        mask(l) && ((valid(warp)(l) & required) =/= required || w(3).orR ||
          w(1)(31, 14).orR || w(1)(13, 6) >= TextureImageFormat.formats.size.U)
      }).asUInt.orR
      when(stopped) { phase := State.finish }
        .elsewhen(!ShaderImageOpcode.legal(instruction)) { status := ShaderImageAccessStatus.shaderType.U; phase := State.finish }
        .elsewhen(parameters) { phase := State.parameters }
        .elsewhen(component) {
          when((mask & ~loadedValid(warp)).orR) { status := ShaderImageAccessStatus.shaderType.U }
            .otherwise { for(l <- 0 until config.lanes) { when(mask(l)) { data(l) := loaded(warp)(l)(pair) } } }
          phase := State.finish
        }.elsewhen(bad) { status := ShaderImageAccessStatus.shaderType.U; phase := State.finish }
        .otherwise { phase := State.next }
    }
    is(State.parameters) {
      when(!stopped) {
        for(l <- 0 until config.lanes) {
          when(mask(l)) {
            values(warp)(l)(Cat(pair, 0.U(1.W))) := left(l)
            values(warp)(l)(Cat(pair, 1.U(1.W))) := right(l)
            valid(warp)(l) := Mux(pair === 0.U, 1.U, valid(warp)(l) | UIntToOH(pair, 4))
          }
        }
        when(pair === 0.U) { loadedValid(warp) := loadedValid(warp) & ~mask }
      }
      phase := State.finish
    }
    is(State.next) {
      when(stopped || !remaining.orR) { phase := State.finish }
        .otherwise {
          val selected = PriorityEncoder(remaining); val w = values(warp)(selected)
          lane := selected; request := 0.U.asTypeOf(request)
          request.table := table; request.tag := selected; request.unitIndex := w(0)
          request.stage := stage.U; request.isWrite := store
          request.shaderTarget := w(1)(2, 0); request.colorType := w(1)(4, 3)
          request.hasFormatQualifier := w(1)(5); request.shaderFormat := w(1)(6 + TextureImageFormat.width - 1, 6)
          request.x := left(selected); request.y := right(selected); request.z := w(2)
          request.rgba := VecInit(w.slice(4, 8)); phase := State.offer
        }
    }
    // Once offered, accept the child before propagating cancellation; never
    // withdraw an irrevocable offer or retire while a store ACK is outstanding.
    is(State.offer) { when(access.io.in.fire) { owned := true.B; phase := State.waitResult } }
    is(State.waitResult) {
      when(access.io.out.fire) {
        val r = access.io.out.bits
        assert(r.tag === lane && r.unitIndex === request.unitIndex && r.isWrite === store)
        assert(r.identity.asUInt === table.identity.asUInt)
        owned := false.B
        when(r.status =/= 0.U && r.status =/= ShaderImageAccessStatus.cancelled.U) {
          status := r.status; faultValid := r.faultAddressValid; faultAddress := r.faultAddress
        }.elsewhen(status === 0.U && !io.cancel) { status := r.status }
        when(r.status === 0.U && !stopped) {
          when(load) { pendingLoad(lane) := r.rgba; data(lane) := r.rgba(0) }
          remaining := remaining & ~UIntToOH(lane, config.lanes); phase := State.next
        }.otherwise { phase := State.finish }
      }
    }
    is(State.finish) {
      assert(!owned && !access.io.busy && !io.memoryRequest.valid, "image instruction escaped memory drain")
      when(load && !stopped) {
        for(l <- 0 until config.lanes) { when(mask(l)) { loaded(warp)(l) := pendingLoad(l) } }
        loadedValid(warp) := loadedValid(warp) | mask
      }
      phase := State.commit
    }
    is(State.commit) { when(io.commit.fire) { phase := State.idle } }
  }
}
