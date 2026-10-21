package opengpu.dispatch

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class JobDispatcherSliceSpec extends AnyFlatSpec {
  it should "capture arbitrary uint32 groups, retain original builtins and reject out of range slices" in {
    simulate(new JobDispatcher(GpuConfig(),gridSlices=true)){d=>
      d.io.launch.valid.poke(false.B);d.io.workgroup.ready.poke(false.B)
      d.io.workgroupCompletion.valid.poke(false.B);d.io.completion.ready.poke(false.B)
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def launch(single: Boolean,xyz: Seq[BigInt],grid: Seq[BigInt]): Unit={
        d.io.launch.bits.poke(0.U.asTypeOf(d.io.launch.bits))
        d.io.launch.bits.kernelPc.poke(0x1000.U);d.io.launch.bits.kernargAddress.poke(0x8000.U)
        for(i<-0 until 3){d.io.launch.bits.gridSize(i).poke(grid(i).U);d.io.launch.bits.localSize(i).poke(Seq(4,4,8)(i).U);d.io.gridSlice.get.groupId(i).poke(xyz(i).U)}
        d.io.gridSlice.get.singleGroup.poke(single.B)
        d.io.launch.valid.poke(true.B);d.io.launch.ready.expect(true.B);d.clock.step();d.io.launch.valid.poke(false.B)
        d.io.gridSlice.get.poke(0.U.asTypeOf(d.io.gridSlice.get));d.io.launch.bits.poke(0.U.asTypeOf(d.io.launch.bits))
      }
      def result(success: Boolean): Unit={
        d.io.completion.valid.expect(true.B);d.io.completion.bits.success.expect(success.B)
        for(_<-0 until 4){d.io.workgroup.valid.expect(false.B);d.clock.step()}
        d.io.completion.ready.poke(true.B);d.clock.step();d.io.completion.ready.poke(false.B)
      }
      val xyz=Seq(BigInt("80000000",16),BigInt(7),BigInt("fffffffd",16))
      val grid=Seq(BigInt("80000001",16),BigInt(9),BigInt("ffffffff",16))
      launch(true,xyz,grid)
      for(_<-0 until 6){d.io.workgroup.valid.expect(true.B);d.io.workgroup.bits.kernelPc.expect(0x1000.U)
        for(i<-0 until 3){d.io.workgroup.bits.groupId(i).expect(xyz(i).U);d.io.workgroup.bits.gridSize(i).expect(grid(i).U);d.io.workgroup.bits.localSize(i).expect(Seq(4,4,8)(i).U)};d.clock.step()}
      d.io.workgroup.ready.poke(true.B);d.clock.step();d.io.workgroupCompletion.valid.poke(true.B)
      d.io.workgroupCompletion.bits.success.poke(true.B);d.clock.step();d.io.workgroupCompletion.valid.poke(false.B);result(true)
      launch(true,Seq(2,0,0).map(BigInt(_)),Seq(2,1,1).map(BigInt(_)));result(false)
      launch(false,Seq.fill(3)(BigInt("ffffffff",16)),Seq(2,1,1).map(BigInt(_)))
      for(x<-0 until 2){d.io.workgroup.valid.expect(true.B);d.io.workgroup.bits.groupId(0).expect(x.U);d.clock.step()
        d.io.workgroupCompletion.valid.poke(true.B);d.io.workgroupCompletion.bits.success.poke(true.B);d.clock.step();d.io.workgroupCompletion.valid.poke(false.B)}
      result(true)
      info("COMPUTE_SLICE fullUint32Group=true originalGridAndLocal=true capturedSideband=true invalidNoDispatch=true legacyFullGrid=true")
    }
  }
}
