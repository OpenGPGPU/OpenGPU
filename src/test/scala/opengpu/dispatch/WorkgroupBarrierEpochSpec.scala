package opengpu.dispatch

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.backend.register.{ScalarRegisterWrite, VectorRegisterWrite}
import opengpu.core.frontend.warp.WarpLaunch
import opengpu.core.execute.control.SimtPath
import opengpu.core.system.WorkgroupBarrierController
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

private class WorkgroupBarrierEpochHarness(gpu: GpuConfig) extends Module {
  val io = IO(new Bundle {
    val kernel = Flipped(Decoupled(new KernelLaunch(gpu)))
    val completion = Decoupled(new KernelCompletion)
    val scalar = Decoupled(new ScalarRegisterWrite(gpu))
    val vector = Decoupled(new VectorRegisterWrite(gpu))
    val launch = Decoupled(new WarpLaunch(gpu))
    val finish = Flipped(Valid(UInt(gpu.warpIdWidth.W)))
    val active = Input(UInt(gpu.warps.W))
    val arrive = Flipped(Decoupled(new SimtPath(gpu)))
    val release = Decoupled(new SimtPath(gpu))
    val waiting = Output(UInt(gpu.warps.W))
  })
  val controller = Module(new SingleCuKernelController(gpu))
  val barrier = Module(new WorkgroupBarrierController(gpu))
  controller.io.kernel <> io.kernel; io.completion <> controller.io.completion
  io.scalar <> controller.io.scalarInitialize; io.vector <> controller.io.vectorInitialize
  io.launch <> controller.io.launch; controller.io.finish := io.finish
  controller.io.activeWarps := io.active
  barrier.io.arrive <> io.arrive; io.release <> barrier.io.release
  barrier.io.residentWarps := controller.io.residentWarps
  barrier.io.dispatchComplete := controller.io.workgroupDispatchComplete
  barrier.io.memoryIdle := true.B; io.waiting := barrier.io.waiting
}

class WorkgroupBarrierEpochSpec extends AnyFlatSpec {
  for ((lanes, warps) <- Seq(4 -> 2, 16 -> 8)) {
    it should s"retain every group's barrier while peer contexts initialize lanes=$lanes warps=$warps" in {
      val gpu = GpuConfig(lanes = lanes, warps = warps)
      simulate(new WorkgroupBarrierEpochHarness(gpu)) { d =>
        d.io.kernel.valid.poke(false.B); d.io.kernel.bits.poke(0.U.asTypeOf(d.io.kernel.bits))
        d.io.completion.ready.poke(false.B); d.io.scalar.ready.poke(true.B); d.io.vector.ready.poke(true.B)
        d.io.launch.ready.poke(false.B); d.io.finish.valid.poke(false.B); d.io.finish.bits.poke(0.U)
        d.io.active.poke(0.U); d.io.arrive.valid.poke(false.B); d.io.arrive.bits.poke(0.U.asTypeOf(d.io.arrive.bits))
        d.io.release.ready.poke(false.B)
        d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
        d.io.kernel.bits.kernelPc.poke(0x1000.U); d.io.kernel.bits.kernargAddress.poke(0x8000.U)
        for (i <- 0 until 3) {
          d.io.kernel.bits.gridSize(i).poke((if (i == 0) 3 else 1).U)
          d.io.kernel.bits.localSize(i).poke((if (i == 0) lanes * warps else 1).U)
        }
        d.io.kernel.valid.poke(true.B); d.io.kernel.ready.expect(true.B); d.clock.step(); d.io.kernel.valid.poke(false.B)
        def until(condition: => Boolean): Unit = {
          var n = 0; while (!condition && n < 200) { d.clock.step(); n += 1 }
          assert(condition, "workgroup context initialization failed to progress")
        }
        for (group <- 0 until 3) {
          var active = BigInt(0)
          d.io.scalar.ready.poke(true.B)
          for (warp <- 0 until warps) {
            until(d.io.launch.valid.peek().litToBoolean)
            d.io.launch.bits.warpId.expect(warp.U)
            d.io.launch.bits.activeMask.expect(((BigInt(1) << lanes) - 1).U)
            d.io.scalar.ready.poke((warp != 0).B)
            d.io.launch.ready.poke(true.B); d.clock.step(); d.io.launch.ready.poke(false.B)
            active = active.setBit(warp); d.io.active.poke(active.U)
            d.io.arrive.valid.poke(true.B); d.io.arrive.bits.warpId.poke(warp.U)
            d.io.arrive.bits.pc.poke((0x104 + group * 0x1000 + warp * 4).U)
            d.io.arrive.bits.activeMask.poke(((BigInt(1) << lanes) - 1).U)
            d.io.arrive.ready.expect(true.B); d.clock.step(); d.io.arrive.valid.poke(false.B)
            if (warp == 0) {
              // The next warp's register initialization is a legal stalled
              // consumer. No barrier generation may use the preceding group.
              for (_ <- 0 until 11) {
                assert(!d.io.release.valid.peek().litToBoolean, s"group=$group released before its peer warps launched")
                d.clock.step()
              }
              d.io.scalar.ready.poke(true.B)
            }
            if (warp < warps - 1) d.io.release.valid.expect(false.B)
          }
          until(d.io.release.valid.peek().litToBoolean)
          val held = d.io.release.bits.peek().litValue
          for (_ <- 0 until 7) { assert(d.io.release.bits.peek().litValue == held); d.io.release.valid.expect(true.B); d.clock.step() }
          d.io.release.ready.poke(true.B)
          for (warp <- 0 until warps) {
            d.io.release.valid.expect(true.B); d.io.release.bits.warpId.expect(warp.U)
            d.io.release.bits.pc.expect((0x104 + group * 0x1000 + warp * 4).U)
            d.io.release.bits.activeMask.expect(((BigInt(1) << lanes) - 1).U)
            d.clock.step()
          }
          d.io.release.ready.poke(false.B); d.io.waiting.expect(0.U)
          for (warp <- 0 until warps) {
            d.io.finish.valid.poke(true.B); d.io.finish.bits.poke(warp.U); d.clock.step()
            active = active.clearBit(warp); d.io.active.poke(active.U); d.io.finish.valid.poke(false.B)
          }
        }
        until(d.io.completion.valid.peek().litToBoolean); d.io.completion.bits.success.expect(true.B)
        for (_ <- 0 until 7) { d.io.completion.valid.expect(true.B); d.io.waiting.expect(0.U); d.clock.step() }
        d.io.completion.ready.poke(true.B); d.clock.step()
        info(s"WORKGROUP_BARRIER_EPOCH lanes=$lanes warps=$warps groups=3 fullCapacity=${lanes * warps} stalledPeerInitialization=true exactReleasePCs=true heldRelease=true heldCompletion=true")
      }
    }
  }
}
