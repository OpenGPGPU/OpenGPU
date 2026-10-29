package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse}

class ShaderImageResourceAccessRequest extends Bundle {
  val table = new TextureImageLevelTable
  val tag = UInt(32.W)
  val unitIndex = UInt(32.W)
  val stage = UInt(GraphicsStage.width.W)
  val isWrite = Bool()
  val shaderTarget = UInt(3.W)
  val colorType = UInt(2.W)
  val hasFormatQualifier = Bool()
  val shaderFormat = UInt(TextureImageFormat.width.W)
  val x = UInt(32.W); val y = UInt(32.W); val z = UInt(32.W)
  val rgba = Vec(4, UInt(32.W))
}

class ShaderImageResourceAccessResult extends ShaderImageAccessResult {
  val descriptorReadLines = UInt(3.W)
  val descriptorHit = Bool()
}

/** Immutable GPU image-unit admission through acknowledged typed texel access.
  * Descriptor and data transactions occupy disjoint reply IDs. A task parent
  * retains publication, VM and coherence ownership through public retirement
  * and invalidates the descriptor cache on rebinding or VM identity reuse.
  */
class ShaderImageResourceAccess(config: GpuConfig = GpuConfig(),
    p: TextureImageAddressConfig = TextureImageAddressConfig(), imageUnits: Int = 4) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ShaderImageResourceAccessRequest))
    val out = Decoupled(new ShaderImageResourceAccessResult)
    val memoryRequest = Decoupled(new ComputeMemoryRequest(config, 64, 2))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, 2)))
    val invalidate = Input(Bool())
    val cancel = Input(Bool())
    val busy = Output(Bool())
  })
  private object State extends ChiselEnum { val idle, offerDescriptor, waitDescriptor, offerAccess, waitAccess, finish, complete = Value }
  private val phase = RegInit(State.idle)
  private val request = Reg(new ShaderImageResourceAccessRequest)
  private val snapshot = Reg(new ShaderImageBindingSnapshot)
  private val result = Reg(new ShaderImageResourceAccessResult)
  private val status = RegInit(0.U(3.W))
  private val descriptorOwned = RegInit(false.B)
  private val accessOwned = RegInit(false.B)
  private val descriptor = Module(new ShaderImageDescriptorReader(config, imageUnits))
  private val access = Module(new ShaderImageAccess(config, p, imageUnits))
  private val active = phase =/= State.idle && phase =/= State.complete
  private val stop = status =/= 0.U || io.cancel
  private val cancelled = status === ShaderImageAccessStatus.cancelled.U || io.cancel

  io.in.ready := phase === State.idle && !io.cancel
  io.out.valid := phase === State.complete; io.out.bits := result
  io.busy := phase =/= State.idle
  descriptor.io.in.valid := phase === State.offerDescriptor
  descriptor.io.in.bits.table := request.table
  descriptor.io.in.bits.tag := request.tag; descriptor.io.in.bits.unitIndex := request.unitIndex
  descriptor.io.out.ready := phase === State.waitDescriptor
  descriptor.io.cancel := descriptorOwned && cancelled
  descriptor.io.invalidate := io.invalidate
  access.io.in.valid := phase === State.offerAccess
  access.io.in.bits.access.identity := request.table.identity
  access.io.in.bits.access.tag := request.tag; access.io.in.bits.access.unitIndex := request.unitIndex
  access.io.in.bits.access.stage := request.stage; access.io.in.bits.access.isWrite := request.isWrite
  access.io.in.bits.access.shaderTarget := request.shaderTarget; access.io.in.bits.access.colorType := request.colorType
  access.io.in.bits.access.hasFormatQualifier := request.hasFormatQualifier
  access.io.in.bits.access.shaderFormat := request.shaderFormat
  access.io.in.bits.access.x := request.x; access.io.in.bits.access.y := request.y; access.io.in.bits.access.z := request.z
  access.io.in.bits.access.imageBinding := snapshot; access.io.in.bits.rgba := request.rgba
  access.io.out.ready := phase === State.waitAccess
  access.io.cancel := accessOwned && cancelled

  assert(!(descriptorOwned && accessOwned), "image descriptor and data owners overlap")
  io.memoryRequest.valid := (descriptorOwned && descriptor.io.memoryRequest.valid) || (accessOwned && access.io.memoryRequest.valid)
  io.memoryRequest.bits := Mux(descriptorOwned, descriptor.io.memoryRequest.bits, access.io.memoryRequest.bits)
  io.memoryRequest.bits.transactionId := Mux(descriptorOwned, 0.U, 1.U)
  descriptor.io.memoryRequest.ready := descriptorOwned && io.memoryRequest.ready
  access.io.memoryRequest.ready := accessOwned && io.memoryRequest.ready
  val descriptorReply = descriptorOwned && io.memoryResponse.bits.transactionId === 0.U
  val accessReply = accessOwned && io.memoryResponse.bits.transactionId === 1.U
  descriptor.io.memoryResponse.valid := io.memoryResponse.valid && descriptorReply
  descriptor.io.memoryResponse.bits := io.memoryResponse.bits; descriptor.io.memoryResponse.bits.transactionId := 0.U
  access.io.memoryResponse.valid := io.memoryResponse.valid && accessReply
  access.io.memoryResponse.bits := io.memoryResponse.bits; access.io.memoryResponse.bits.transactionId := 0.U
  io.memoryResponse.ready := (descriptorReply && descriptor.io.memoryResponse.ready) || (accessReply && access.io.memoryResponse.ready)

  when(active && io.cancel && status === 0.U) { status := ShaderImageAccessStatus.cancelled.U }
  switch(phase) {
    is(State.idle) {
      when(io.in.fire) {
        assert(!descriptorOwned && !accessOwned && !descriptor.io.busy && !access.io.busy)
        request := io.in.bits; result := 0.U.asTypeOf(result); status := 0.U
        result.identity := io.in.bits.table.identity; result.tag := io.in.bits.tag
        result.unitIndex := io.in.bits.unitIndex; result.isWrite := io.in.bits.isWrite
        phase := State.offerDescriptor
      }
    }
    is(State.offerDescriptor) { when(descriptor.io.in.fire) { descriptorOwned := true.B; phase := State.waitDescriptor } }
    is(State.waitDescriptor) {
      when(descriptor.io.out.fire) {
        val r = descriptor.io.out.bits
        assert(r.identity.asUInt === request.table.identity.asUInt && r.tag === request.tag && r.unitIndex === request.unitIndex)
        descriptorOwned := false.B; result.descriptorReadLines := r.readLines; result.descriptorHit := r.hit
        result.faultAddressValid := r.faultAddressValid; result.faultAddress := r.faultAddress
        when(r.status =/= 0.U) { status := r.status; phase := State.finish }
          .elsewhen(stop) { phase := State.finish }
          .otherwise { assert(r.validSnapshot); snapshot := r.imageBinding; phase := State.offerAccess }
      }
    }
    is(State.offerAccess) { when(access.io.in.fire) { accessOwned := true.B; phase := State.waitAccess } }
    is(State.waitAccess) {
      when(access.io.out.fire) {
        val r = access.io.out.bits
        assert(r.identity.asUInt === request.table.identity.asUInt && r.tag === request.tag && r.unitIndex === request.unitIndex)
        assert(r.isWrite === request.isWrite)
        accessOwned := false.B
        when(r.status =/= 0.U) { status := r.status }.elsewhen(!stop) { result.rgba := r.rgba }
        result.faultAddressValid := r.faultAddressValid; result.faultAddress := r.faultAddress
        phase := State.finish
      }
    }
    is(State.finish) {
      assert(!descriptorOwned && !accessOwned && !descriptor.io.busy && !access.io.busy,
        "image operation retired before descriptor and data ownership drained")
      result.status := Mux(io.cancel && status === 0.U, ShaderImageAccessStatus.cancelled.U, status)
      when(stop) { result.rgba := 0.U.asTypeOf(result.rgba) }
      phase := State.complete
    }
    is(State.complete) { when(io.out.fire) { phase := State.idle } }
  }
}
