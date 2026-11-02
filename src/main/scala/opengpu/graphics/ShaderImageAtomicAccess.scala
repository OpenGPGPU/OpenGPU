package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.{CachePolicy, GpuConfig}
import opengpu.core.memory.{AtomicMemoryOp, SharedAtomicRequest, SharedAtomicResponse}

/** Internal word RMW request. The caller must keep the checked binding and VM
  * immutable until the result drains and translate/arbitrate the atomic port.
  */
class ShaderImageAtomicRequest(config: GpuConfig) extends Bundle {
  val access = new ShaderImageBindingAddressRequest
  val warpId = UInt(config.warpIdWidth.W)
  val operation = UInt(3.W)
  val operand = UInt(32.W)
  val compareValue = UInt(32.W)
}

object ShaderImageAtomicOperation {
  val exchange = 0; val add = 1; val xor = 2; val or = 3
  val and = 4; val min = 5; val max = 6; val compareExchange = 7
}

class ShaderImageAtomicResult extends Bundle {
  val identity = new GraphicsWorkIdentity
  val tag = UInt(32.W)
  val unitIndex = UInt(32.W)
  val value = UInt(32.W)
  val status = UInt(3.W)
  val faultAddressValid = Bool()
  val faultAddress = UInt(32.W)
  val pageFault = Bool()
}

/** Checked image word RMW against a shared atomic owner. This component does
  * not fetch GPU descriptors or provide VM, CU instruction or API integration.
  * A request offered to the atomic owner is irrevocable and must drain even if
  * cancellation arrives; acknowledged writes cannot be rolled back.
  */
class ShaderImageAtomicAccess(config: GpuConfig = GpuConfig(),
    p: TextureImageAddressConfig = TextureImageAddressConfig(), imageUnits: Int = 4) extends Module {
  require(config.xLen == 32)
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ShaderImageAtomicRequest(config)))
    val out = Decoupled(new ShaderImageAtomicResult)
    val atomicRequest = Decoupled(new SharedAtomicRequest(config))
    val atomicResponse = Flipped(Decoupled(new SharedAtomicResponse(config)))
    val cancel = Input(Bool())
    val busy = Output(Bool())
  })
  private object State extends ChiselEnum { val idle, check, offerAddress, waitAddress, offerAtomic, waitAtomic, complete = Value }
  private val state = RegInit(State.idle)
  private val request = Reg(new ShaderImageAtomicRequest(config))
  private val result = RegInit(0.U.asTypeOf(new ShaderImageAtomicResult))
  private val address = Reg(UInt(32.W))
  private val cancelled = RegInit(false.B)
  private val resolver = Module(new ShaderImageBindingAddress(p, imageUnits))
  private val unsigned = request.access.imageBinding.imageFormat === TextureImageFormat.id("R32UI").U
  private val signed = request.access.imageBinding.imageFormat === TextureImageFormat.id("R32I").U
  private val op = MuxLookup(request.operation, AtomicMemoryOp.swap)(Seq(
    ShaderImageAtomicOperation.exchange.U -> AtomicMemoryOp.swap,
    ShaderImageAtomicOperation.add.U -> AtomicMemoryOp.add,
    ShaderImageAtomicOperation.xor.U -> AtomicMemoryOp.xor,
    ShaderImageAtomicOperation.or.U -> AtomicMemoryOp.or,
    ShaderImageAtomicOperation.and.U -> AtomicMemoryOp.and,
    ShaderImageAtomicOperation.min.U -> Mux(signed, AtomicMemoryOp.min, AtomicMemoryOp.minu),
    ShaderImageAtomicOperation.max.U -> Mux(signed, AtomicMemoryOp.max, AtomicMemoryOp.maxu),
    ShaderImageAtomicOperation.compareExchange.U -> AtomicMemoryOp.compareSwap))

  io.in.ready := state === State.idle && !io.cancel
  io.out.valid := state === State.complete
  io.out.bits := result
  io.busy := state =/= State.idle
  resolver.io.in.valid := state === State.offerAddress
  resolver.io.in.bits := request.access
  resolver.io.in.bits.isWrite := true.B
  resolver.io.in.bits.requireReadWrite := true.B
  resolver.io.out.ready := state === State.waitAddress
  resolver.io.cancel := state === State.waitAddress && (io.cancel || cancelled)
  io.atomicRequest.valid := state === State.offerAtomic
  io.atomicRequest.bits := 0.U.asTypeOf(io.atomicRequest.bits)
  io.atomicRequest.bits.warpId := request.warpId
  io.atomicRequest.bits.address := address
  io.atomicRequest.bits.operation := op
  io.atomicRequest.bits.operand := request.operand
  io.atomicRequest.bits.compareValue := request.compareValue
  io.atomicRequest.bits.cachePolicy := CachePolicy.cached
  io.atomicResponse.ready := state === State.waitAtomic && io.atomicResponse.bits.warpId === request.warpId
  when(state =/= State.idle && state =/= State.complete && io.cancel) { cancelled := true.B }
  switch(state) {
    is(State.idle) {
      when(io.in.fire) {
        request := io.in.bits; result := 0.U.asTypeOf(result)
        result.identity := io.in.bits.access.identity
        result.tag := io.in.bits.access.tag; result.unitIndex := io.in.bits.access.unitIndex
        cancelled := false.B; state := State.check
      }
    }
    is(State.check) {
      when(io.cancel || cancelled) { result.status := ShaderImageAccessStatus.cancelled.U; state := State.complete }
        .otherwise { state := State.offerAddress }
    }
    is(State.offerAddress) { when(resolver.io.in.fire) { state := State.waitAddress } }
    is(State.waitAddress) {
      when(resolver.io.out.fire) {
        val r = resolver.io.out.bits
        assert(r.tag === request.access.tag && r.unitIndex === request.access.unitIndex)
        assert(r.identity.asUInt === request.access.identity.asUInt)
        val line = Cat(r.address(31, 6), 0.U(6.W))
        when(r.status =/= 0.U && r.status =/= ShaderImageBindingAddressStatus.cancelled.U) {
          result.status := r.status; state := State.complete
        }.elsewhen(io.cancel || cancelled || r.status === ShaderImageBindingAddressStatus.cancelled.U) {
          result.status := ShaderImageAccessStatus.cancelled.U; state := State.complete
        }.elsewhen(!r.validTexel) { state := State.complete }
        .elsewhen(!(signed || unsigned) || !request.access.hasFormatQualifier) {
          result.status := ShaderImageAccessStatus.shaderType.U; state := State.complete
        }
        .elsewhen(r.address(1, 0).orR || line < r.allocationBase || line.pad(33) + 64.U > r.allocationLimit) {
          result.status := ShaderImageAccessStatus.bounds.U; state := State.complete
        }.otherwise { address := r.address; state := State.offerAtomic }
      }
    }
    is(State.offerAtomic) { when(io.atomicRequest.fire) { state := State.waitAtomic } }
    is(State.waitAtomic) {
      when(io.atomicResponse.fire) {
        when(io.atomicResponse.bits.fault) {
          result.status := ShaderImageAccessStatus.memory.U; result.faultAddressValid := true.B
          result.faultAddress := address; result.pageFault := io.atomicResponse.bits.pageFault
        }.elsewhen(io.cancel || cancelled) { result.status := ShaderImageAccessStatus.cancelled.U }
          .otherwise { result.value := io.atomicResponse.bits.oldValue }
        state := State.complete
      }
    }
    is(State.complete) { when(io.out.fire) { state := State.idle } }
  }
}
