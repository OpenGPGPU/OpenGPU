package opengpu.dispatch

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig

/** Two independent task producers sharing the same physical CUs.
  *
  * Tags belong to their producer's namespace: compute command 7 and graphics
  * batch 7 may execute together. Ownership is retained until the CU's
  * completion is stored in its owner's queue. The two completion queues
  * prevent a stalled graphics consumer from blocking compute retirement.
  * This dispatches already-partitioned tasks; it does not split a draw or
  * distribute the workgroups of one KernelLaunch between CUs.
  */
class SharedCuKernelDispatcher(
  config: GpuConfig = GpuConfig(),
  numComputeUnits: Int = 2,
  commandIdWidth: Int = 8
) extends Module {
  require(numComputeUnits > 0)
  require(commandIdWidth > 0)

  val io = IO(new Bundle {
    val launch = Flipped(Decoupled(new TaggedKernelLaunch(config, commandIdWidth)))
    val completion = Decoupled(new TaggedKernelCompletion(commandIdWidth))
    val graphicsLaunch = Flipped(Decoupled(new TaggedKernelLaunch(config, commandIdWidth)))
    val graphicsCompletion = Decoupled(new TaggedKernelCompletion(commandIdWidth))
    val cuLaunch = Vec(numComputeUnits, Decoupled(new KernelLaunch(config)))
    val cuCompletion = Vec(numComputeUnits, Flipped(Decoupled(new KernelCompletion)))
    val busy = Output(UInt(numComputeUnits.W))
    val graphicsOwner = Output(UInt(numComputeUnits.W))
    val taskIds = Output(Vec(numComputeUnits, UInt(commandIdWidth.W)))
  })

  private val occupied = RegInit(VecInit(Seq.fill(numComputeUnits)(false.B)))
  private val graphics = RegInit(VecInit(Seq.fill(numComputeUnits)(false.B)))
  private val tags = Reg(Vec(numComputeUnits, UInt(commandIdWidth.W)))
  private val launches = Module(new RRArbiter(
    new TaggedKernelLaunch(config, commandIdWidth), 2))
  // Reserve completion storage before admitting work. Without these credits,
  // a producer with a full completion queue could occupy every CU with tasks
  // that cannot retire, indirectly starving the other producer.
  private val outstanding = RegInit(VecInit(Seq.fill(2)(0.U(log2Ceil(numComputeUnits + 1).W))))
  for ((port, owner) <- Seq(io.launch -> 0, io.graphicsLaunch -> 1)) {
    val credit = outstanding(owner) < numComputeUnits.U
    launches.io.in(owner).valid := port.valid && credit
    launches.io.in(owner).bits := port.bits
    port.ready := launches.io.in(owner).ready && credit
  }

  // Check the launch receiver's readiness as well as its allocation bit, so
  // a CU finishing its local retirement cannot block another free CU.
  private val available = VecInit((0 until numComputeUnits).map { cu =>
    !occupied(cu) && io.cuLaunch(cu).ready
  }).asUInt
  private val selected = PriorityEncoder(available)
  launches.io.out.ready := available.orR
  for (cu <- 0 until numComputeUnits) {
    io.cuLaunch(cu).valid := launches.io.out.valid && available.orR && selected === cu.U
    io.cuLaunch(cu).bits := launches.io.out.bits.launch
    when(io.cuLaunch(cu).fire) {
      occupied(cu) := true.B
      graphics(cu) := launches.io.chosen === 1.U
      tags(cu) := launches.io.out.bits.commandId
    }
  }

  private val completions = Seq.fill(2) {
    Module(new RRArbiter(new TaggedKernelCompletion(commandIdWidth), numComputeUnits))
  }
  private val queues = Seq.fill(2) {
    Module(new Queue(new TaggedKernelCompletion(commandIdWidth), numComputeUnits))
  }
  for (owner <- 0 until 2) {
    queues(owner).io.enq <> completions(owner).io.out
    for (cu <- 0 until numComputeUnits) {
      val matches = occupied(cu) && graphics(cu) === (owner == 1).B
      completions(owner).io.in(cu).valid := io.cuCompletion(cu).valid && matches
      completions(owner).io.in(cu).bits.commandId := tags(cu)
      completions(owner).io.in(cu).bits.success := io.cuCompletion(cu).bits.success
    }
  }
  io.completion <> queues(0).io.deq
  io.graphicsCompletion <> queues(1).io.deq
  for ((port, owner) <- Seq(io.completion -> 0, io.graphicsCompletion -> 1)) {
    val admitted = launches.io.in(owner).fire
    when(admitted =/= port.fire) {
      outstanding(owner) := Mux(admitted, outstanding(owner) + 1.U, outstanding(owner) - 1.U)
    }
    assert(outstanding(owner) <= numComputeUnits.U,
      "shared-CU completion reservations exceeded queue capacity")
  }

  for (cu <- 0 until numComputeUnits) {
    io.cuCompletion(cu).ready := occupied(cu) && Mux(graphics(cu),
      completions(1).io.in(cu).ready, completions(0).io.in(cu).ready)
    when(io.cuCompletion(cu).fire) { occupied(cu) := false.B }
    when(io.cuCompletion(cu).valid) {
      assert(occupied(cu), "an idle shared CU cannot complete a task")
    }
  }
  io.busy := occupied.asUInt
  io.graphicsOwner := occupied.asUInt & graphics.asUInt
  io.taskIds := tags
}
