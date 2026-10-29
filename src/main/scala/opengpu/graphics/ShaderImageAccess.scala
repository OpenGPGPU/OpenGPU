package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse}

class ShaderImageAccessRequest extends Bundle {
  val access = new ShaderImageBindingAddressRequest
  val rgba = Vec(4, UInt(32.W))
}

object ShaderImageAccessStatus {
  // Binding/address statuses retain their values. Memory failures add code 7.
  val ok = 0; val descriptor = 1; val identity = 2; val permission = 3
  val bounds = 4; val cancelled = 5; val shaderType = 6; val memory = 7
}

class ShaderImageAccessResult extends Bundle {
  val identity = new GraphicsWorkIdentity
  val tag = UInt(32.W)
  val unitIndex = UInt(32.W)
  val isWrite = Bool()
  val rgba = Vec(4, UInt(32.W))
  val status = UInt(3.W)
  val faultAddressValid = Bool()
  val faultAddress = UInt(32.W)
}

/** One immutable image operation from binding/coordinate validation through
  * typed memory acknowledgement. The task parent still owns GPU descriptor
  * provenance, VM and coherence leases until this public result retires.
  * No memory request can bypass permission, format or full-width address checks.
  */
class ShaderImageAccess(config: GpuConfig = GpuConfig(),
    p: TextureImageAddressConfig = TextureImageAddressConfig(), imageUnits: Int = 4) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ShaderImageAccessRequest))
    val out = Decoupled(new ShaderImageAccessResult)
    val memoryRequest = Decoupled(new ComputeMemoryRequest(config, 64, 1))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, 1)))
    val cancel = Input(Bool())
    val busy = Output(Bool())
  })
  private object State extends ChiselEnum {
    val idle, offerAddress, waitAddress, offerMemory, waitMemory, finish, complete = Value
  }
  private val phase = RegInit(State.idle)
  private val request = Reg(new ShaderImageAccessRequest)
  private val resolved = Reg(new ShaderImageBindingAddressResult)
  private val result = Reg(new ShaderImageAccessResult)
  private val status = RegInit(0.U(3.W))
  private val addressOwned = RegInit(false.B)
  private val memoryOwned = RegInit(false.B)
  private val address = Module(new ShaderImageBindingAddress(p, imageUnits))
  private val memory = Module(new ShaderImageMemoryAccess(config))
  private val active = phase =/= State.idle && phase =/= State.complete
  private val stop = status =/= 0.U || io.cancel
  private val cancel = io.cancel || status === ShaderImageAccessStatus.cancelled.U

  io.in.ready := phase === State.idle && !io.cancel
  io.out.valid := phase === State.complete
  io.out.bits := result
  io.busy := phase =/= State.idle
  io.memoryRequest <> memory.io.memoryRequest
  memory.io.memoryResponse <> io.memoryResponse

  address.io.in.valid := phase === State.offerAddress
  address.io.in.bits := request.access
  address.io.out.ready := phase === State.waitAddress
  address.io.cancel := addressOwned && cancel
  memory.io.in.valid := phase === State.offerMemory
  memory.io.in.bits.identity := request.access.identity
  memory.io.in.bits.tag := request.access.tag
  memory.io.in.bits.validTexel := resolved.validTexel
  memory.io.in.bits.address := resolved.address
  memory.io.in.bits.allocationBase := resolved.allocationBase
  memory.io.in.bits.allocationLimit := resolved.allocationLimit
  memory.io.in.bits.format := resolved.format
  memory.io.in.bits.colorType := resolved.colorType
  memory.io.in.bits.isWrite := request.access.isWrite
  memory.io.in.bits.rgba := request.rgba
  memory.io.out.ready := phase === State.waitMemory
  memory.io.cancel := memoryOwned && cancel

  when(active && io.cancel && status === 0.U) { status := ShaderImageAccessStatus.cancelled.U }
  switch(phase) {
    is(State.idle) {
      when(io.in.fire) {
        assert(!addressOwned && !memoryOwned && !address.io.busy && !memory.io.busy)
        request := io.in.bits; result := 0.U.asTypeOf(result)
        result.identity := io.in.bits.access.identity; result.tag := io.in.bits.access.tag
        result.unitIndex := io.in.bits.access.unitIndex; result.isWrite := io.in.bits.access.isWrite
        status := 0.U; phase := State.offerAddress
      }
    }
    is(State.offerAddress) {
      when(address.io.in.fire) { addressOwned := true.B; phase := State.waitAddress }
    }
    is(State.waitAddress) {
      when(address.io.out.fire) {
        assert(address.io.out.bits.identity.asUInt === request.access.identity.asUInt)
        assert(address.io.out.bits.tag === request.access.tag && address.io.out.bits.unitIndex === request.access.unitIndex)
        addressOwned := false.B; resolved := address.io.out.bits
        when(address.io.out.bits.status =/= 0.U) {
          status := address.io.out.bits.status; phase := State.finish
        }.elsewhen(stop || !address.io.out.bits.validTexel) { phase := State.finish }
          .otherwise { phase := State.offerMemory }
      }
    }
    is(State.offerMemory) {
      when(memory.io.in.fire) { memoryOwned := true.B; phase := State.waitMemory }
    }
    is(State.waitMemory) {
      when(memory.io.out.fire) {
        assert(memory.io.out.bits.identity.asUInt === request.access.identity.asUInt)
        assert(memory.io.out.bits.tag === request.access.tag && memory.io.out.bits.isWrite === request.access.isWrite)
        memoryOwned := false.B
        val childStatus = memory.io.out.bits.status
        when(childStatus =/= 0.U) {
          status := MuxLookup(childStatus, ShaderImageAccessStatus.descriptor.U)(Seq(
            ShaderImageMemoryStatus.invalid.U -> ShaderImageAccessStatus.descriptor.U,
            ShaderImageMemoryStatus.bounds.U -> ShaderImageAccessStatus.bounds.U,
            ShaderImageMemoryStatus.memory.U -> ShaderImageAccessStatus.memory.U,
            ShaderImageMemoryStatus.cancelled.U -> ShaderImageAccessStatus.cancelled.U,
            ShaderImageMemoryStatus.componentType.U -> ShaderImageAccessStatus.shaderType.U))
        }.elsewhen(!stop) { result.rgba := memory.io.out.bits.rgba }
        result.faultAddressValid := memory.io.out.bits.faultAddressValid
        result.faultAddress := memory.io.out.bits.faultAddress
        phase := State.finish
      }
    }
    is(State.finish) {
      assert(!addressOwned && !memoryOwned && !address.io.busy && !memory.io.busy,
        "image operation retired before its address and memory children drained")
      result.status := Mux(io.cancel && status === 0.U, ShaderImageAccessStatus.cancelled.U, status)
      when(stop) { result.rgba := 0.U.asTypeOf(result.rgba) }
      phase := State.complete
    }
    is(State.complete) { when(io.out.fire) { phase := State.idle } }
  }
}
