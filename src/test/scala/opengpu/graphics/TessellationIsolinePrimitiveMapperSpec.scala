package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TessellationIsolinePrimitiveMapperSpec extends AnyFlatSpec {
  it should "map evaluated isolines to held point and line primitives and reject invalid links" in {
    val p = VertexShaderStageConfig(varyings = 2)
    simulate(new TessellationIsolinePrimitiveMapper(p)) { d =>
      d.io.in.valid.poke(false.B); d.io.out.ready.poke(false.B); d.io.fault.ready.poke(false.B)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      def begin(point: Boolean, invalid: Int): Unit = {
        val x=d.io.in.bits
        x.evaluated.taskId.poke(17.U); x.evaluated.sequence.poke(3.U); x.evaluated.point.poke(point.B)
        for (v <- 0 until 2; i <- 0 until 64) x.evaluated.vertices(v)(i).poke((100 + v*100 + i).U)
        x.identity.context.poke(11.U); x.identity.pass.poke(12.U); x.identity.generation.poke(13.U)
        x.sequence.poke(47.U); x.primitiveId.poke(23.U); x.instanceId.poke(4.U); x.baseInstance.poke(6.U)
        x.outputComponents.poke((if (invalid==4) 64 else 9).U); x.varyingSlots.poke(1.U); x.flatMask.poke((if (invalid==3) 16 else 1).U)
        for (i <- 0 until 12) { x.componentSources(i).valid.poke((i<8).B); x.componentSources(i).bits.poke((if (invalid==1 && i==2) 9 else i).U) }
        x.pointSizeSource.valid.poke(true.B); x.pointSizeSource.bits.poke((if (invalid==2) 9 else if (invalid==4) 63 else 8).U)
        d.io.in.valid.poke(true.B); d.io.in.ready.expect(true.B); d.clock.step(); d.io.in.valid.poke(false.B)
      }
      def waitDone(): Unit = {
        var cycles=0
        while (!d.io.out.valid.peek().litToBoolean && !d.io.fault.valid.peek().litToBoolean && cycles<100) {
          d.clock.step(); cycles += 1
        }
        assert(cycles<100)
      }
      for (point <- Seq(false,true)) {
        begin(point,0); waitDone(); d.io.fault.valid.expect(false.B); d.io.out.valid.expect(true.B)
        val o=d.io.out.bits
        o.identity.context.expect(11.U); o.identity.pass.expect(12.U); o.identity.generation.expect(13.U)
        o.sequence.expect(47.U); o.primitiveId.expect(23.U); o.instanceId.expect(4.U); o.baseInstance.expect(6.U)
        o.vertexCount.expect((if(point)1 else 2).U); o.kind.expect((if(point)0 else 1).U)
        o.topology.expect((if(point)0 else 1).U); o.provokingVertex.expect((if(point)0 else 1).U)
        for (i <- 0 until 4) o.vertices(0).position(i).expect((100+i).U)
        o.vertices(0).varyings(0)(0).expect((if(point)104 else 204).U)
        for (i <- 1 until 4) o.vertices(0).varyings(0)(i).expect((104+i).U)
        o.vertices(0).pointSizeWritten.expect(true.B); o.vertices(0).pointSize.expect(108.U)
        if (!point) {
          for (i <- 0 until 4) o.vertices(1).position(i).expect((200+i).U)
          for (i <- 0 until 4) o.vertices(1).varyings(0)(i).expect((204+i).U)
          o.vertices(1).pointSize.expect(208.U)
        } else assert(o.vertices(1).peek().litValue == 0)
        assert(o.vertices(2).peek().litValue == 0)
        val held=o.peek().litValue; d.clock.step(3); assert(o.peek().litValue==held)
        d.io.out.ready.poke(true.B); d.clock.step(); d.io.out.ready.poke(false.B)
      }
      for (invalid <- 1 to 3) {
        begin(false,invalid); waitDone(); d.io.out.valid.expect(false.B); d.io.fault.valid.expect(true.B)
        d.io.fault.bits.taskId.expect(17.U); d.io.fault.bits.sequence.expect(3.U)
        d.clock.step(2); d.io.fault.valid.expect(true.B); d.io.fault.ready.poke(true.B); d.clock.step(); d.io.fault.ready.poke(false.B)
      }
      begin(true,4); waitDone(); d.io.out.valid.expect(true.B)
      d.io.out.bits.vertices(0).pointSize.expect(163.U)
      d.io.out.ready.poke(true.B); d.clock.step(); d.io.out.ready.poke(false.B)
      d.io.in.ready.expect(true.B)
    }
  }
}
