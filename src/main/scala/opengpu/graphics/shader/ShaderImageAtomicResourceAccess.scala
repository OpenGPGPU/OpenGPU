package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse, SharedAtomicRequest, SharedAtomicResponse}

class ShaderImageAtomicResourceRequest(config: GpuConfig) extends Bundle {
  val table = new TextureImageLevelTable
  val tag = UInt(32.W); val unitIndex = UInt(32.W); val stage = UInt(GraphicsStage.width.W)
  val shaderTarget = UInt(3.W); val colorType = UInt(2.W)
  val hasFormatQualifier = Bool(); val shaderFormat = UInt(TextureImageFormat.width.W)
  val x = UInt(32.W); val y = UInt(32.W); val z = UInt(32.W)
  val warpId = UInt(config.warpIdWidth.W); val operation = UInt(3.W)
  val operand = UInt(32.W); val compareValue = UInt(32.W)
}

class ShaderImageAtomicResourceResult extends ShaderImageAtomicResult {
  val descriptorReadLines = UInt(3.W)
  val descriptorHit = Bool()
}

/** Descriptor-backed atomic request. The parent owns the descriptor and VM
  * lifetime, translation and shared atomic arbitration until result retirement.
  */
class ShaderImageAtomicResourceAccess(config: GpuConfig = GpuConfig(),
    p: TextureImageAddressConfig = TextureImageAddressConfig(), imageUnits: Int = 4) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ShaderImageAtomicResourceRequest(config)))
    val out = Decoupled(new ShaderImageAtomicResourceResult)
    val descriptorRequest = Decoupled(new ComputeMemoryRequest(config, 64, 1))
    val descriptorResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, 1)))
    val atomicRequest = Decoupled(new SharedAtomicRequest(config))
    val atomicResponse = Flipped(Decoupled(new SharedAtomicResponse(config)))
    val invalidate = Input(Bool()); val cancel = Input(Bool()); val busy = Output(Bool())
  })
  private object State extends ChiselEnum { val idle, offerDescriptor, waitDescriptor, offerAtomic, waitAtomic, finish, complete = Value }
  private val state = RegInit(State.idle)
  private val request = Reg(new ShaderImageAtomicResourceRequest(config))
  private val snapshot = Reg(new ShaderImageBindingSnapshot)
  private val result = Reg(new ShaderImageAtomicResourceResult)
  private val status = RegInit(0.U(3.W))
  private val descriptorOwned = RegInit(false.B); private val atomicOwned = RegInit(false.B)
  private val descriptor = Module(new ShaderImageDescriptorReader(config, imageUnits))
  private val atomic = Module(new ShaderImageAtomicAccess(config, p, imageUnits))
  private val active = state =/= State.idle && state =/= State.complete
  private val stop = status =/= 0.U || io.cancel
  private val cancelled = status === ShaderImageAccessStatus.cancelled.U || io.cancel
  io.in.ready := state === State.idle && !io.cancel
  io.out.valid := state === State.complete; io.out.bits := result; io.busy := state =/= State.idle
  io.descriptorRequest <> descriptor.io.memoryRequest
  descriptor.io.memoryResponse <> io.descriptorResponse
  descriptor.io.invalidate := io.invalidate
  descriptor.io.cancel := descriptorOwned && cancelled
  descriptor.io.in.valid := state === State.offerDescriptor
  descriptor.io.in.bits.table := request.table
  descriptor.io.in.bits.unitIndex := request.unitIndex; descriptor.io.in.bits.tag := request.tag
  descriptor.io.out.ready := state === State.waitDescriptor
  atomic.io.in.valid := state === State.offerAtomic
  atomic.io.in.bits := 0.U.asTypeOf(atomic.io.in.bits)
  atomic.io.in.bits.access.identity := request.table.identity
  atomic.io.in.bits.access.tag := request.tag; atomic.io.in.bits.access.unitIndex := request.unitIndex
  atomic.io.in.bits.access.stage := request.stage; atomic.io.in.bits.access.shaderTarget := request.shaderTarget
  atomic.io.in.bits.access.colorType := request.colorType; atomic.io.in.bits.access.hasFormatQualifier := request.hasFormatQualifier
  atomic.io.in.bits.access.shaderFormat := request.shaderFormat
  atomic.io.in.bits.access.x := request.x; atomic.io.in.bits.access.y := request.y; atomic.io.in.bits.access.z := request.z
  atomic.io.in.bits.access.imageBinding := snapshot
  atomic.io.in.bits.warpId := request.warpId; atomic.io.in.bits.operation := request.operation
  atomic.io.in.bits.operand := request.operand; atomic.io.in.bits.compareValue := request.compareValue
  atomic.io.cancel := atomicOwned && cancelled
  atomic.io.out.ready := state === State.waitAtomic
  io.atomicRequest <> atomic.io.atomicRequest
  atomic.io.atomicResponse <> io.atomicResponse
  assert(!(descriptorOwned && atomicOwned), "descriptor and atomic ownership overlap")
  when(active && io.cancel && status === 0.U) { status := ShaderImageAccessStatus.cancelled.U }
  switch(state) {
    is(State.idle) {
      when(io.in.fire) {
        assert(!descriptor.io.busy && !atomic.io.busy && !descriptorOwned && !atomicOwned)
        request := io.in.bits; result := 0.U.asTypeOf(result); status := 0.U
        result.identity := io.in.bits.table.identity; result.tag := io.in.bits.tag; result.unitIndex := io.in.bits.unitIndex
        state := State.offerDescriptor
      }
    }
    is(State.offerDescriptor) { when(descriptor.io.in.fire) { descriptorOwned := true.B; state := State.waitDescriptor } }
    is(State.waitDescriptor) {
      when(descriptor.io.out.fire) {
        val r = descriptor.io.out.bits
        assert(r.identity.asUInt === request.table.identity.asUInt && r.tag === request.tag && r.unitIndex === request.unitIndex)
        descriptorOwned := false.B; result.descriptorReadLines := r.readLines; result.descriptorHit := r.hit
        result.faultAddressValid := r.faultAddressValid; result.faultAddress := r.faultAddress
        when(r.status =/= 0.U) { status := r.status; state := State.finish }
          .elsewhen(stop) { state := State.finish }
          .otherwise { assert(r.validSnapshot); snapshot := r.imageBinding; state := State.offerAtomic }
      }
    }
    is(State.offerAtomic) { when(atomic.io.in.fire) { atomicOwned := true.B; state := State.waitAtomic } }
    is(State.waitAtomic) {
      when(atomic.io.out.fire) {
        val r = atomic.io.out.bits
        assert(r.identity.asUInt === request.table.identity.asUInt && r.tag === request.tag && r.unitIndex === request.unitIndex)
        atomicOwned := false.B
        when(r.status =/= 0.U) { status := r.status }.elsewhen(!stop) { result.value := r.value }
        result.faultAddressValid := r.faultAddressValid; result.faultAddress := r.faultAddress; result.pageFault := r.pageFault
        state := State.finish
      }
    }
    is(State.finish) {
      assert(!descriptorOwned && !atomicOwned && !descriptor.io.busy && !atomic.io.busy,
        "image atomic retired before descriptor and atomic ownership drained")
      result.status := Mux(io.cancel && status === 0.U, ShaderImageAccessStatus.cancelled.U, status)
      when(stop) { result.value := 0.U }
      state := State.complete
    }
    is(State.complete) { when(io.out.fire) { state := State.idle } }
  }
}
