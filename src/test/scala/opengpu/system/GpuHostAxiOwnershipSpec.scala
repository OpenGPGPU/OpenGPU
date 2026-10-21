package opengpu.system

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.graphics.{GpuCommandMmioRegs, RenderHostRegs}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class GpuHostAxiOwnershipSpec extends AnyFlatSpec {
  private def zero(data: Data): Unit = data match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s.poke(0.S)
    case _ => ()
  }
  private def init(d: GpuHostAxi): Unit = {
    zero(d.io); d.io.s_axi_aresetn.poke(false.B); d.clock.step(3)
    d.io.s_axi_aresetn.poke(true.B); d.clock.step()
    d.io.s_axi_awsize.poke(2.U); d.io.s_axi_awburst.poke(1.U)
    d.io.s_axi_arsize.poke(2.U); d.io.s_axi_arburst.poke(1.U)
  }
  private def until(d: GpuHostAxi)(condition: => Boolean): Unit = {
    var cycles = 0
    while (!condition && cycles < 64) { d.clock.step(); cycles += 1 }
    assert(condition, "AXI transaction did not make bounded progress")
  }
  private def write(d: GpuHostAxi, address: Int, value: Int): Unit = {
    d.io.s_axi_awaddr.poke(address.U); d.io.s_axi_awvalid.poke(true.B)
    until(d)(d.io.s_axi_awready.peek().litToBoolean); d.clock.step(); d.io.s_axi_awvalid.poke(false.B)
    d.io.s_axi_wdata.poke(value.U); d.io.s_axi_wstrb.poke(15.U)
    d.io.s_axi_wlast.poke(true.B); d.io.s_axi_wvalid.poke(true.B)
    until(d)(d.io.s_axi_wready.peek().litToBoolean); d.clock.step(); d.io.s_axi_wvalid.poke(false.B)
    until(d)(d.io.s_axi_bvalid.peek().litToBoolean); d.io.s_axi_bresp.expect(0.U)
    d.io.s_axi_bready.poke(true.B); d.clock.step(); d.io.s_axi_bready.poke(false.B)
  }
  private def offerRead(d: GpuHostAxi, address: Int, beats: Int = 1): Unit = {
    d.io.s_axi_araddr.poke(address.U); d.io.s_axi_arlen.poke((beats - 1).U)
    d.io.s_axi_arvalid.poke(true.B)
    until(d)(d.io.s_axi_arready.peek().litToBoolean); d.clock.step(); d.io.s_axi_arvalid.poke(false.B)
    until(d)(d.io.s_axi_rvalid.peek().litToBoolean)
  }
  private def consumeRead(d: GpuHostAxi): BigInt = {
    until(d)(d.io.s_axi_rvalid.peek().litToBoolean)
    val result = d.io.s_axi_rdata.peek().litValue
    d.io.s_axi_rresp.expect(0.U); d.io.s_axi_rready.poke(true.B)
    d.clock.step(); d.io.s_axi_rready.poke(false.B); result
  }

  it should "retain an offered status read while reset completion changes the live register" in {
    simulate(new GpuHostAxi(gpuConfig = GpuConfig(lanes = 4, warps = 2), unifiedCommandMmio = true)) { d =>
      init(d); write(d, GpuCommandMmioRegs.RESET, 1)
      d.io.commandResetActive.get.expect(true.B)
      offerRead(d, GpuCommandMmioRegs.STATUS)
      val offered = d.io.s_axi_rdata.peek().litValue
      assert((offered & 8) != 0, "status must expose the pending reset")
      d.clock.step(2)
      d.io.commandResetDone.get.poke(true.B); d.clock.step(); d.io.commandResetDone.get.poke(false.B)
      d.io.commandResetActive.get.expect(false.B)
      for (_ <- 0 until 7) {
        d.io.s_axi_rvalid.expect(true.B); d.io.s_axi_rdata.expect(offered.U)
        d.io.s_axi_rresp.expect(0.U); d.io.s_axi_rlast.expect(true.B); d.clock.step()
      }
      assert(consumeRead(d) == offered)
      offerRead(d, GpuCommandMmioRegs.STATUS)
      assert((consumeRead(d) & 8) == 0, "the next transaction must observe the completed reset")
      info("AXI_READ_OWNER liveResetCompletion=true heldPayloadImmutable=true freshReadUpdated=true")
    }
  }

  it should "accept simultaneous address offers exactly once and fairly serialize both channels" in {
    simulate(new GpuHostAxi(gpuConfig = GpuConfig(lanes = 4, warps = 2), unifiedCommandMmio = true)) { d =>
      init(d)
      var firstGrants = Set.empty[Boolean]
      for (round <- 0 until 8) {
        if (round % 2 == 0) write(d, RenderHostRegs.CMD_COUNT, round)
        val value = 0x4000 + round * 64
        d.io.s_axi_araddr.poke(RenderHostRegs.ID.U); d.io.s_axi_arlen.poke(0.U)
        d.io.s_axi_awaddr.poke(RenderHostRegs.CMD_BASE.U); d.io.s_axi_awlen.poke(0.U)
        d.io.s_axi_arvalid.poke(true.B); d.io.s_axi_awvalid.poke(true.B)
        val readFirst = d.io.s_axi_arready.peek().litToBoolean
        val writeFirst = d.io.s_axi_awready.peek().litToBoolean
        assert(readFirst != writeFirst, "single register owner must accept exactly one simultaneous address")
        firstGrants += readFirst
        d.clock.step()
        if (readFirst) d.io.s_axi_arvalid.poke(false.B) else d.io.s_axi_awvalid.poke(false.B)
        var readAccepted = readFirst; var writeAccepted = writeFirst
        var readDone = false; var writeDone = false; var dataDone = false
        var cycles = 0
        while ((!readDone || !writeDone) && cycles < 96) {
          d.io.s_axi_wvalid.poke((writeAccepted && !dataDone).B)
          d.io.s_axi_wdata.poke(value.U); d.io.s_axi_wstrb.poke(15.U); d.io.s_axi_wlast.poke(true.B)
          // Retain both response slots under backpressure before accepting them.
          d.io.s_axi_rready.poke((cycles % 7 == 6).B); d.io.s_axi_bready.poke((cycles % 5 == 4).B)
          val ar = d.io.s_axi_arvalid.peek().litToBoolean && d.io.s_axi_arready.peek().litToBoolean
          val aw = d.io.s_axi_awvalid.peek().litToBoolean && d.io.s_axi_awready.peek().litToBoolean
          assert(!(ar && aw))
          if (ar) { assert(!readAccepted); readAccepted = true }
          if (aw) { assert(!writeAccepted); writeAccepted = true }
          if (d.io.s_axi_wvalid.peek().litToBoolean && d.io.s_axi_wready.peek().litToBoolean) {
            assert(!dataDone); dataDone = true
          }
          if (d.io.s_axi_rvalid.peek().litToBoolean && d.io.s_axi_rready.peek().litToBoolean) {
            assert(readAccepted && !readDone); d.io.s_axi_rdata.expect(0x47550001L.U)
            d.io.s_axi_rresp.expect(0.U); d.io.s_axi_rlast.expect(true.B); readDone = true
          }
          if (d.io.s_axi_bvalid.peek().litToBoolean && d.io.s_axi_bready.peek().litToBoolean) {
            assert(writeAccepted && dataDone && !writeDone); d.io.s_axi_bresp.expect(0.U); writeDone = true
          }
          d.clock.step(); cycles += 1
          if (readAccepted) d.io.s_axi_arvalid.poke(false.B)
          if (writeAccepted) d.io.s_axi_awvalid.poke(false.B)
        }
        assert(readDone && writeDone && dataDone)
        d.io.s_axi_wvalid.poke(false.B); d.io.s_axi_rready.poke(false.B); d.io.s_axi_bready.poke(false.B)
        offerRead(d, RenderHostRegs.CMD_BASE); assert(consumeRead(d) == value)
      }
      assert(firstGrants == Set(true, false), "both channels must receive the first grant under contention")
      info("AXI_ADDRESS_OWNER simultaneousRounds=8 exactlyOnce=true heldResponses=true noLostPeer=true exactWriteReadback=true")
    }
  }

  it should "stream captured burst addresses without bubbles and retain invalid header rejection through stalls" in {
    simulate(new GpuHostAxi(gpuConfig = GpuConfig(lanes = 4, warps = 2), unifiedCommandMmio = true,
      protectVmState = true, externalBinnedRender = true, nativeVertexInput = true,
      transformFeedback = true, primitiveQueries = true, shaderExecutionLimits = true)) { d =>
      init(d)
      val words = Vector[BigInt](0x5000,0x8000,7,8,9,2,3,4)
      for ((v,i) <- words.zipWithIndex) write(d,GpuCommandMmioRegs.KERNEL_PC+i*4,v.toInt)
      var transfers = 0; var returned = 0
      def read(address: BigInt, count: Int, kind: Int, size: Int, values: Vector[BigInt], response: Int = 0): Unit = {
        d.io.s_axi_araddr.poke(address.U); d.io.s_axi_arlen.poke((count-1).U)
        d.io.s_axi_arsize.poke(size.U); d.io.s_axi_arburst.poke(kind.U); d.io.s_axi_arvalid.poke(true.B)
        until(d)(d.io.s_axi_arready.peek().litToBoolean); d.clock.step(); d.io.s_axi_arvalid.poke(false.B)
        // The accepted header owns validity and address; live pins may change.
        d.io.s_axi_araddr.poke(BigInt("ffffffff",16).U); d.io.s_axi_arlen.poke(255.U)
        d.io.s_axi_arsize.poke(7.U); d.io.s_axi_arburst.poke(3.U)
        for (beat <- 0 until count) {
          d.io.s_axi_rvalid.expect(true.B)
          val expected = if (response == 0) values(beat) else BigInt(0)
          def check(): Unit = {
            d.io.s_axi_rvalid.expect(true.B); d.io.s_axi_rdata.expect(expected.U)
            d.io.s_axi_rresp.expect(response.U); d.io.s_axi_rlast.expect((beat==count-1).B)
          }
          if (beat % 5 == 2) {
            d.io.s_axi_rready.poke(false.B)
            for (_ <- 0 until 3) { check(); d.clock.step() }
          }
          d.io.s_axi_rready.poke(true.B); check(); d.clock.step(); returned += 1
        }
        d.io.s_axi_rready.poke(false.B); d.io.s_axi_rvalid.expect(false.B); transfers += 1
      }
      read(GpuCommandMmioRegs.KERNEL_PC,words.size,1,2,words)
      read(NativeShaderExecutionCapabilities.base,8,1,2,NativeShaderExecutionCapabilities.words.toVector)
      read(NativeShaderExecutionCapabilities.base,16,0,2,Vector.fill(16)(NativeShaderExecutionCapabilities.words.head))
      for ((address,count,kind,size) <- Seq(
        (BigInt("fffffffc",16),2,1,2), (BigInt(0xffc),2,1,2), (BigInt(0x301),8,1,2),
        (BigInt(0x300),17,0,2), (BigInt(0x300),4,2,2), (BigInt(0x300),4,3,2),
        (BigInt(0),256,1,1), (BigInt(0x300),4,1,0), (BigInt(0x300),4,1,3)))
        read(address,count,kind,size,Vector.empty,2)
      read(GpuCommandMmioRegs.KERNEL_PC,words.size,1,2,words)
      info(s"AXI_REGISTERED_BEATS transfers=$transfers beats=$returned noResponseBubbles=true headerCaptured=true stallsStable=true max256=true fourKiBAndCarryRejected=true fixedAndIncrementing=true healthyReuse=true")
    }
  }

  it should "advance captured write addresses exactly once under W and B stalls" in {
    simulate(new GpuHostAxi(gpuConfig = GpuConfig(lanes = 4, warps = 2), unifiedCommandMmio = true)) { d =>
      init(d)
      for (kind <- Seq(1,0)) {
        val values = Vector(31,47,63)
        d.io.s_axi_awaddr.poke(GpuCommandMmioRegs.SOURCE.U); d.io.s_axi_awlen.poke(2.U)
        d.io.s_axi_awsize.poke(2.U); d.io.s_axi_awburst.poke(kind.U); d.io.s_axi_awvalid.poke(true.B)
        until(d)(d.io.s_axi_awready.peek().litToBoolean); d.clock.step(); d.io.s_axi_awvalid.poke(false.B)
        d.io.s_axi_awaddr.poke(BigInt("fffffffc",16).U); d.io.s_axi_awlen.poke(255.U)
        d.io.s_axi_awsize.poke(0.U); d.io.s_axi_awburst.poke(3.U)
        for ((value,i) <- values.zipWithIndex) {
          d.io.s_axi_wvalid.poke(false.B); d.clock.step(i+1)
          d.io.s_axi_wdata.poke(value.U); d.io.s_axi_wstrb.poke(15.U); d.io.s_axi_wlast.poke((i==2).B)
          d.io.s_axi_wvalid.poke(true.B); until(d)(d.io.s_axi_wready.peek().litToBoolean)
          d.clock.step(); d.io.s_axi_wvalid.poke(false.B)
        }
        for (_ <- 0 until 5) { d.io.s_axi_bvalid.expect(true.B); d.io.s_axi_bresp.expect(0.U); d.clock.step() }
        d.io.s_axi_bready.poke(true.B); d.clock.step(); d.io.s_axi_bready.poke(false.B)
        for ((value,i) <- values.zipWithIndex) {
          offerRead(d,GpuCommandMmioRegs.SOURCE+i*4)
          assert(consumeRead(d)==(if(kind==0){if(i==0)values.last else value}else value))
        }
      }
      info("AXI_REGISTERED_WRITE fixedAndIncrementing=true addressHeaderCaptured=true WStalls=true BStalls=true exactReadback=true")
    }
  }
}
