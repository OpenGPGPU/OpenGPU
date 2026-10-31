package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class LayeredTileImageBindingSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  it should "bind layered tiles to bounded image and resolve views and drain cancellation" in {
    simulate(new LayeredTileImageBinding){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def prepare(tile:Int=14):Unit={
        zero(d.io.in.bits);val q=d.io.in.bits;q.screenWidth.poke(20.U);q.screenHeight.poke(17.U);q.layerCount.poke(3.U);q.tile.poke(tile.U)
        q.template.states.identity.context.poke(7.U);val image=q.template.image;val c=image.pixels.colors
        c.tileLog2.poke(3.U);c.sampleMode.poke(1.U);c.colors(0).enabled.poke(true.B);c.colors(0).format.poke(TypedColorFormat.id("RGBA8").U)
        val a=image.views(0);a.base.poke(0x10000.U);a.limit.poke((0x10000+2720).U);a.allocationBase.poke(0x10000.U);a.allocationLimit.poke(0x13000.U)
        a.imageWidth.poke(20.U);a.imageHeight.poke(17.U);a.rowPitch.poke(160.U);a.store.poke(true.B);a.writable.poke(true.B)
        q.viewStride(0).poke(4096.U);val rr=image.resolves(0);rr.enable.poke(true.B);val v=rr.destination
        v.base.poke(0x20000.U);v.limit.poke((0x20000+1360).U);v.allocationBase.poke(0x20000.U);v.allocationLimit.poke(0x21800.U)
        v.imageWidth.poke(20.U);v.imageHeight.poke(17.U);v.rowPitch.poke(80.U);v.store.poke(true.B);v.writable.poke(true.B);q.resolveStride(0).poke(2048.U)
      }
      def submit():Unit={d.io.in.valid.poke(true.B);d.io.in.ready.expect(true.B);d.clock.step();d.io.in.valid.poke(false.B)}
      def until():Unit={var n=0;while(!d.io.out.valid.peek().litToBoolean && n<300){d.clock.step();n+=1};assert(n<300,"layer binding stalled")}
      def consume(status:Int):Unit={until();d.io.out.bits.status.expect(status.U);d.io.out.bits.bindingValid.expect((status==0).B)
        val held=d.io.out.bits.peek().litValue;for(_<-0 until 4){d.clock.step();assert(d.io.out.bits.peek().litValue==held);d.io.in.ready.expect(false.B)}
        d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)}
      prepare();submit();d.io.in.bits.tile.poke(0.U);d.io.in.bits.viewStride(0).poke(0.U);until()
      val r=d.io.out.bits;r.status.expect(0.U);r.bindingValid.expect(true.B);r.layer.expect(1.U);r.descriptor.tile.expect(14.U)
      r.descriptor.states.identity.context.expect(7.U);val c=r.descriptor.image.pixels.colors
      c.originX.expect(16.S);c.originY.expect(8.S);c.tileWidth.expect(4.U);c.tileHeight.expect(8.U)
      r.descriptor.image.views(0).base.expect(0x11000.U);r.descriptor.image.views(0).limit.expect((0x11000+2720).U)
      r.descriptor.image.resolves(0).destination.base.expect(0x20800.U)
      val heldResult=r.peek().litValue;d.io.cancel.poke(true.B);d.clock.step();assert(r.peek().litValue==heldResult);d.io.cancel.poke(false.B);consume(0)
      prepare(tile=26);submit();until();d.io.out.bits.layer.expect(2.U)
      d.io.out.bits.descriptor.image.pixels.colors.originY.expect(16.S);d.io.out.bits.descriptor.image.pixels.colors.tileHeight.expect(1.U)
      d.io.out.bits.descriptor.image.views(0).base.expect(0x12000.U);consume(0)
      for(caseId<-0 until 6){prepare();caseId match{
        case 0=>d.io.in.bits.tile.poke(27.U)
        case 1=>d.io.in.bits.layerCount.poke(0.U)
        case 2=>d.io.in.bits.viewStride(0).poke(2719.U)
        case 3=>d.io.in.bits.template.image.views(0).allocationLimit.poke(0x12900.U)
        case 4=>d.io.in.bits.template.image.resolves(0).destination.rowPitch.poke(79.U)
        case 5=>d.io.in.bits.template.image.views(0).imageWidth.poke(19.U)}
        submit();consume(if(caseId<2)1 else 2)
      }
      prepare(tile=0);d.io.in.bits.layerCount.poke(1.U);d.io.in.bits.viewStride(0).poke(0.U)
      val top=d.io.in.bits.template.image.views(0);top.base.poke("hfffff000".U);top.limit.poke((BigInt(1)<<32).U)
      top.allocationBase.poke("hfffff000".U);top.allocationLimit.poke((BigInt(1)<<32).U)
      d.io.in.bits.template.image.resolves(0).enable.poke(false.B);submit();until()
      d.io.out.bits.descriptor.image.views(0).limit.expect((BigInt(1)<<32).U);consume(0)
      prepare(tile=0);d.io.in.bits.layerCount.poke(2.U);d.io.in.bits.viewStride(0).poke(4096.U)
      val overflow=d.io.in.bits.template.image.views(0);overflow.base.poke("hfffff000".U);overflow.limit.poke((BigInt(1)<<32).U)
      overflow.allocationBase.poke("hfffff000".U);overflow.allocationLimit.poke((BigInt(1)<<32).U)
      d.io.in.bits.template.image.resolves(0).enable.poke(false.B);submit();consume(2)
      prepare(tile=65534);d.io.in.bits.screenWidth.poke(8.U);d.io.in.bits.screenHeight.poke(8.U);d.io.in.bits.layerCount.poke(65535.U)
      d.io.in.bits.template.image.pixels.colors.colors(0).enabled.poke(false.B);d.io.in.bits.template.image.resolves(0).enable.poke(false.B)
      submit();until();d.io.out.bits.layer.expect(65534.U);consume(0)
      prepare(tile=0);d.io.in.bits.screenWidth.poke(16.U);d.io.in.bits.screenHeight.poke(8.U);d.io.in.bits.layerCount.poke(65535.U)
      submit();consume(1)
      prepare();submit();d.clock.step(3);d.io.cancel.poke(true.B);d.clock.step();d.io.cancel.poke(false.B);consume(4)
      prepare(tile=0);submit();until();d.io.out.bits.layer.expect(0.U);d.io.out.bits.descriptor.image.views(0).base.expect(0x10000.U);consume(0)
    }
  }
}
