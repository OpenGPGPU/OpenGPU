package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse}

class TypedFragmentImageStateRequest extends Bundle {
  val state = new TypedFragmentStateLookup
}
class TypedFragmentImageStateResult(f: FragmentShaderConfig) extends Bundle {
  val status = UInt(3.W)
  val stateValid = Bool()
  val state = new TypedFragmentState(f)
  val image = new ShaderImageTask
  val stateHit = Bool()
  val stateReadLines = UInt(4.W)
}

/** Atomically admit a fragment state and its immutable image publication.
  * The publication reference belongs to the validated state record.
  * State-cache hits still revalidate the image root. The caller
  * owns physical alias checks and pins both publications and their VM through
  * all resulting draws; native descriptor admission does not yet use this.
  */
class TypedFragmentImageStateReader(config: GpuConfig = GpuConfig(),
    f: FragmentShaderConfig = FragmentShaderConfig(), g: GraphicsConfig = GraphicsConfig(),nativeAdvanced: Boolean=false,geometryInput: Boolean=false) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new TypedFragmentImageStateRequest))
    val out = Decoupled(new TypedFragmentImageStateResult(f))
    val memoryRequest = Decoupled(new ComputeMemoryRequest(config, 64, 4))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, 4)))
    val invalidate = Input(Bool()); val cancel = Input(Bool()); val busy = Output(Bool())
  })
  private object State extends ChiselEnum { val idle, stateOffer, stateWait, imageOffer, imageWait, finish, complete = Value }
  private val phase = RegInit(State.idle)
  private val stateReader = Module(new TypedFragmentStateReader(config, f, g, imageAvailable = true,nativeAdvanced=nativeAdvanced,geometryInput=geometryInput))
  private val imageReader = Module(new ShaderImagePublicationReader(config))
  private val request = Reg(new TypedFragmentImageStateRequest)
  private val state = Reg(new TypedFragmentState(f))
  private val image = Reg(new ShaderImageTask)
  private val status = RegInit(0.U(3.W)); private val hit = RegInit(false.B); private val lines = RegInit(0.U(4.W))
  private val cancelled = RegInit(false.B)
  private val result = Reg(new TypedFragmentImageStateResult(f))
  private val active = phase =/= State.idle && phase =/= State.complete
  private val stop = cancelled || io.cancel
  when(active && io.cancel) { cancelled := true.B }
  io.in.ready := phase === State.idle && !io.cancel
  io.out.valid := phase === State.complete; io.out.bits := result
  io.busy := phase =/= State.idle

  stateReader.io.lookup.valid := phase === State.stateOffer
  stateReader.io.lookup.bits := request.state
  stateReader.io.result.ready := phase === State.stateWait
  stateReader.io.invalidate := io.invalidate
  // An offered child lookup remains irrevocable; cancellation follows its fire.
  stateReader.io.cancel := phase === State.stateWait && stop
  imageReader.io.in.valid := phase === State.imageOffer
  imageReader.io.in.bits.publication := state.image
  imageReader.io.in.bits.identity := request.state.arena.identity
  imageReader.io.out.ready := phase === State.imageWait
  imageReader.io.cancel := phase === State.imageWait && stop

  private val imageOwner = phase === State.imageOffer || phase === State.imageWait
  private val imageRequest = Wire(new ComputeMemoryRequest(config, 64, 4))
  imageRequest := imageReader.io.memoryRequest.bits
  io.memoryRequest.valid := Mux(imageOwner, imageReader.io.memoryRequest.valid, stateReader.io.memoryRequest.valid)
  io.memoryRequest.bits := Mux(imageOwner, imageRequest, stateReader.io.memoryRequest.bits)
  imageReader.io.memoryRequest.ready := imageOwner && io.memoryRequest.ready
  stateReader.io.memoryRequest.ready := !imageOwner && io.memoryRequest.ready
  imageReader.io.memoryResponse.valid := imageOwner && io.memoryResponse.valid && io.memoryResponse.bits.transactionId === 0.U
  imageReader.io.memoryResponse.bits := io.memoryResponse.bits
  stateReader.io.memoryResponse.valid := !imageOwner && io.memoryResponse.valid
  stateReader.io.memoryResponse.bits := io.memoryResponse.bits
  io.memoryResponse.ready := Mux(imageOwner,
    imageReader.io.memoryResponse.ready && io.memoryResponse.bits.transactionId === 0.U,
    stateReader.io.memoryResponse.ready)

  switch(phase) {
    is(State.idle) { when(io.in.fire) {
      request := io.in.bits; state := 0.U.asTypeOf(state); image := 0.U.asTypeOf(image)
      status := 0.U; hit := false.B; lines := 0.U; cancelled := false.B; phase := State.stateOffer
    }}
    is(State.stateOffer) { when(stateReader.io.lookup.fire) { phase := State.stateWait } }
    is(State.stateWait) { when(stateReader.io.result.fire) {
      val r = stateReader.io.result.bits
      lines := r.readLines; hit := r.hit
      when(stop) { status := 4.U; phase := State.finish }
        .elsewhen(r.status =/= 0.U || !r.stateValid) { status := Mux(r.status === 0.U, 1.U, r.status); phase := State.finish }
        .elsewhen(r.state.image.version =/= 0.U && r.state.fragment.earlyDepthSafe) { status := 1.U; phase := State.finish }
        .otherwise { state := r.state; phase := State.imageOffer }
    }}
    is(State.imageOffer) { when(imageReader.io.in.fire) { phase := State.imageWait } }
    is(State.imageWait) { when(imageReader.io.out.fire) {
      when(stop) { status := 4.U }
        .elsewhen(imageReader.io.out.bits.status =/= 0.U) { status := imageReader.io.out.bits.status }
        .otherwise { image := imageReader.io.out.bits.task }
      phase := State.finish
    }}
    is(State.finish) {
      result := 0.U.asTypeOf(result); result.status := Mux(stop && status === 0.U, 4.U, status)
      result.stateReadLines := lines; result.stateHit := hit
      when(status === 0.U && !stop) { result.stateValid := true.B; result.state := state; result.image := image }
      phase := State.complete
    }
    is(State.complete) { when(io.out.fire) { phase := State.idle } }
  }
}
