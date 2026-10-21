package opengpu.system

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.graphics.GraphicsConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class NativeShaderExecutionCapabilitiesAxiSpec extends AnyFlatSpec {
  private def zero(data: Data): Unit = data match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case _ => ()
  }
  for (enabled <- Seq(true, false)) {
    it should s"protect native quota programming and expose only implemented execution controls enabled=$enabled" in {
      simulate(new GpuHostAxi(config = GraphicsConfig(), gpuConfig = GpuConfig(lanes = 4, warps = 2),
        unifiedCommandMmio = true, protectVmState = true, externalBinnedRender = true,
        nativeVertexInput = true, shaderExecutionLimits = enabled)) { d =>
        zero(d.io); d.io.s_axi_aresetn.poke(false.B); d.clock.step(3); d.io.s_axi_aresetn.poke(true.B)
        def until(p: => Boolean): Unit = {
          var n = 0; while (!p && n < 100) { d.clock.step(); n += 1 }; assert(p)
        }
        def read(address: Int, count: Int = 1): Seq[(BigInt, BigInt)] = {
          d.io.s_axi_araddr.poke(address.U); d.io.s_axi_arlen.poke((count - 1).U)
          d.io.s_axi_arsize.poke(2.U); d.io.s_axi_arburst.poke(1.U); d.io.s_axi_arvalid.poke(true.B)
          until(d.io.s_axi_arready.peek().litToBoolean); d.clock.step(); d.io.s_axi_arvalid.poke(false.B)
          (0 until count).map { i =>
            until(d.io.s_axi_rvalid.peek().litToBoolean)
            val value = d.io.s_axi_rdata.peek().litValue; val response = d.io.s_axi_rresp.peek().litValue
            for (_ <- 0 until 5) {
              d.io.s_axi_rdata.expect(value.U); d.io.s_axi_rresp.expect(response.U)
              d.io.s_axi_rlast.expect((i == count - 1).B); d.clock.step()
            }
            d.io.s_axi_rready.poke(true.B); d.clock.step(); d.io.s_axi_rready.poke(false.B)
            value -> response
          }
        }
        def write(address: Int, value: BigInt, strobe: Int = 15, response: Int = 2): Unit = {
          d.io.s_axi_awaddr.poke(address.U); d.io.s_axi_awlen.poke(0.U)
          d.io.s_axi_awsize.poke(2.U); d.io.s_axi_awburst.poke(1.U); d.io.s_axi_awvalid.poke(true.B)
          until(d.io.s_axi_awready.peek().litToBoolean); d.clock.step(); d.io.s_axi_awvalid.poke(false.B)
          d.io.s_axi_wdata.poke(value.U); d.io.s_axi_wstrb.poke(strobe.U)
          d.io.s_axi_wlast.poke(true.B); d.io.s_axi_wvalid.poke(true.B)
          until(d.io.s_axi_wready.peek().litToBoolean); d.clock.step(); d.io.s_axi_wvalid.poke(false.B)
          until(d.io.s_axi_bvalid.peek().litToBoolean)
          for (_ <- 0 until 5) { d.io.s_axi_bvalid.expect(true.B); d.io.s_axi_bresp.expect(response.U); d.clock.step() }
          d.io.s_axi_bready.poke(true.B); d.clock.step(); d.io.s_axi_bready.poke(false.B)
        }
        val caps = Seq[BigInt](BigInt("47534c01", 16), 8, 31, 32, 1048576, BigInt("04051918", 16), 0x320, 0x138)
        val expected = if (enabled) (caps :+ BigInt(1048576)).map(_ -> BigInt(0))
          else Seq.fill(9)(BigInt(0) -> BigInt(2))
        assert(read(0x300, 10) == expected :+ (BigInt(0) -> BigInt(2)))
        for (i <- 0 until 8; strobe <- Seq(0, 1, 15)) write(0x300 + i * 4, 0xffffffffL, strobe)
        if (enabled) {
          d.io.binDrained.get.poke(true.B)
          write(0x320, 17) // VM is busy.
          assert(read(0x320).head == (BigInt(1048576) -> BigInt(0)))
          d.io.vmContextIdle.get.poke(true.B); d.io.binDrained.get.poke(false.B)
          write(0x320, 17) // Renderer is still an owner.
          d.io.binDrained.get.poke(true.B)
          write(0x320, 0); write(0x320, 0x55, strobe = 1, response = 0)
          assert(read(0x320).head._1 == BigInt(0x100055))
          write(0x320, 0, strobe = 4, response = 0)
          assert(read(0x320).head._1 == BigInt(0x55))
          write(0x320, 0, strobe = 1) // A partial write must not create an unlimited native quota.
          write(0x320, 0, strobe = 0, response = 0)
          assert(read(0x320).head._1 == BigInt(0x55))
          write(0x320, 0xffffffffL, response = 0)
          d.io.shaderInstructionLimit.get.expect(0xffffffffL.U)
          // The MMIO bridge is itself an owner before the system sees a command.
          // Hold its other-command consumer so a staged compute command stays queued.
          write(opengpu.graphics.GpuCommandMmioRegs.SUBMIT, 1, response = 0)
          write(0x320, 17)
          assert(read(0x320).head._1 == BigInt("ffffffff", 16))
          write(opengpu.graphics.GpuCommandMmioRegs.RESET, 1, response = 0)
          d.io.commandResetActive.get.expect(true.B)
          write(0x320, 17)
          d.clock.step(3)
          d.io.commandResetDone.get.poke(true.B); d.clock.step(); d.io.commandResetDone.get.poke(false.B)
          assert(read(0x320).head._1 == BigInt("ffffffff", 16), "command reset must retain quota policy")
          write(0x320, 17, response = 0)

        } else write(0x320, 17)
        for (address <- Seq(0x301, 0x324, 0x700, 0x1300)) assert(read(address) == Seq(BigInt(0) -> BigInt(2)))
        d.io.s_axi_aresetn.poke(false.B); d.clock.step(3); d.io.s_axi_aresetn.poke(true.B)
        assert(read(0x300, 9) == expected)
      }
    }
  }
}
