package opengpu.core.memory

import chisel3._
import chisel3.util._
import opengpu.config.{CachePolicy, GpuConfig}
import opengpu.graphics.{GraphicsWorkIdentity, NativeTypedGraphicsLayout}

/** A task-owned table of up to four 64-byte storage binding records. The
  * publisher pins the table, referenced allocations and VM context until the
  * task and all accepted memory traffic have drained.
  */
class StorageDescriptorTable extends Bundle {
  val version = UInt(2.W)
  val base = UInt(32.W)
  val limit = UInt(33.W)
  val identity = new GraphicsWorkIdentity
}

class StorageDescriptorRecord extends Bundle {
  val identity = new GraphicsWorkIdentity
  val bindingIndex = UInt(32.W)
  val view = new StorageWordView
}

object StorageDescriptorLayout {
  val magic = BigInt("47534201", 16)
  val words = 16
  val bytes = 64
  def fields(record: StorageDescriptorRecord): Seq[NativeTypedGraphicsLayout.Field] = {
    import NativeTypedGraphicsLayout.Field
    Seq(Field("identity.context", 3, record.identity.context),
      Field("identity.pass", 4, record.identity.pass),
      Field("identity.generation", 5, record.identity.generation),
      Field("bindingIndex", 6, record.bindingIndex),
      Field("view.bound", 7, record.view.bound),
      Field("view.readable", 8, record.view.readable),
      Field("view.writable", 9, record.view.writable),
      Field("view.base", 10, record.view.base),
      Field("view.limit", 11, record.view.limit),
      Field("view.allocationBase", 13, record.view.allocationBase),
      Field("view.allocationLimit", 14, record.view.allocationLimit))
  }
}

class StorageDescriptorRequest extends Bundle {
  val table = new StorageDescriptorTable
  val bindingIndex = UInt(32.W)
  val tag = UInt(32.W)
}

class StorageDescriptorResult extends Bundle {
  val tag = UInt(32.W)
  val bindingIndex = UInt(32.W)
  val view = new StorageWordView
  val status = UInt(3.W)
  val faultAddressValid = Bool()
  val faultAddress = UInt(32.W)
}

object StorageDescriptorStatus {
  val ok = 0
  val invalid = 1
  val bounds = 2
  val memoryFault = 3
  val cancelled = 4
  val identity = 5
}

/** Reads one immutable GPU-resident storage record. Once offered, a line
  * request remains stable and drains before cancellation or error is reported.
  * The returned view still requires per-access bounds and permission checks.
  */
class StorageDescriptorReader(config: GpuConfig = GpuConfig()) extends Module {
  require(config.xLen == 32)
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new StorageDescriptorRequest))
    val out = Decoupled(new StorageDescriptorResult)
    val memoryRequest = Decoupled(new ComputeMemoryRequest(config, 64, 1))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, 1)))
    val cancel = Input(Bool())
    val busy = Output(Bool())
  })
  private object State extends ChiselEnum { val idle, check, offer, waitReply, verify, complete = Value }
  private val state = RegInit(State.idle)
  private val request = Reg(new StorageDescriptorRequest)
  private val snapshot = Reg(new StorageDescriptorRecord)
  private val result = RegInit(0.U.asTypeOf(new StorageDescriptorResult))
  private val memory = Reg(new ComputeMemoryRequest(config, 64, 1))
  private val cancelled = RegInit(false.B)
  private val fields = StorageDescriptorLayout.fields(snapshot)
  NativeTypedGraphicsLayout.check(snapshot, fields, StorageDescriptorLayout.words)
  private val malformed = NativeTypedGraphicsLayout.receive(fields, StorageDescriptorLayout.words,
    StorageDescriptorLayout.magic, 0.U, io.memoryResponse.bits.readData,
    io.memoryResponse.fire && !io.memoryResponse.bits.fault && !cancelled && !io.cancel)
  private val begin = request.table.base.pad(64) + (request.bindingIndex.pad(64) << 6)
  private val end = begin + StorageDescriptorLayout.bytes.U
  private val addressSpace = (BigInt(1) << 32).U(64.W)
  private val table = request.table
  private val tableLegal = table.base(5, 0) === 0.U && table.limit(5, 0) === 0.U &&
    table.limit > table.base && table.limit <= addressSpace && end <= table.limit

  io.in.ready := state === State.idle && !io.cancel
  io.out.valid := state === State.complete
  io.out.bits := result
  io.busy := state =/= State.idle
  io.memoryRequest.valid := state === State.offer
  io.memoryRequest.bits := memory
  io.memoryResponse.ready := state === State.waitReply && io.memoryResponse.bits.transactionId === 0.U
  when(state =/= State.idle && state =/= State.complete && io.cancel) { cancelled := true.B }
  switch(state) {
    is(State.idle) {
      when(io.in.fire) {
        request := io.in.bits
        result := 0.U.asTypeOf(result)
        result.tag := io.in.bits.tag
        result.bindingIndex := io.in.bits.bindingIndex
        snapshot := 0.U.asTypeOf(snapshot)
        cancelled := false.B
        state := State.check
      }
    }
    is(State.check) {
      when(io.cancel || cancelled) {
        result.status := StorageDescriptorStatus.cancelled.U; state := State.complete
      }.elsewhen(table.version =/= 1.U || request.bindingIndex >= 4.U) {
        result.status := StorageDescriptorStatus.invalid.U; state := State.complete
      }.elsewhen(!tableLegal) {
        result.status := StorageDescriptorStatus.bounds.U; state := State.complete
      }.otherwise {
        memory := 0.U.asTypeOf(memory)
        memory.address := begin(31, 0)
        memory.sizeLog2 := 6.U
        memory.byteMask := Fill(64, 1.U(1.W))
        memory.cachePolicy := CachePolicy.uncached
        state := State.offer
      }
    }
    is(State.offer) { when(io.memoryRequest.fire) { state := State.waitReply } }
    is(State.waitReply) {
      when(io.memoryResponse.fire) {
        when(io.cancel || cancelled) {
          result.status := StorageDescriptorStatus.cancelled.U; state := State.complete
        }.elsewhen(io.memoryResponse.bits.fault) {
          result.status := StorageDescriptorStatus.memoryFault.U
          result.faultAddressValid := true.B; result.faultAddress := memory.address
          state := State.complete
        }.elsewhen(malformed) {
          result.status := StorageDescriptorStatus.invalid.U; state := State.complete
        }.otherwise { state := State.verify }
      }
    }
    is(State.verify) {
      when(io.cancel || cancelled) { result.status := StorageDescriptorStatus.cancelled.U }
        .elsewhen(snapshot.identity.asUInt =/= table.identity.asUInt || snapshot.bindingIndex =/= request.bindingIndex) {
          result.status := StorageDescriptorStatus.identity.U
        }.otherwise { result.view := snapshot.view; result.status := StorageDescriptorStatus.ok.U }
      state := State.complete
    }
    is(State.complete) { when(io.out.fire) { state := State.idle } }
  }
}
