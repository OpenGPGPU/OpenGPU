package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class GeometryScalarWorkerSpec extends AnyFlatSpec {
  private def instruction(fn:Int,rd:Int=5):BigInt=(BigInt(fn)<<25)|(BigInt(rd)<<7)|0x0b
  private def init(d:GeometryScalarWorker):Unit={
    d.io.bind.valid.poke(false.B);d.io.bind.bits.poke(0.U.asTypeOf(d.io.bind.bits))
    d.io.release.valid.poke(false.B);d.io.release.bits.poke(false.B)
    d.io.in.valid.poke(false.B);d.io.in.bits.poke(0.U.asTypeOf(d.io.in.bits))
    d.io.commit.ready.poke(false.B);d.io.event.ready.poke(false.B);d.io.cancel.poke(false.B)
    d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
  }
  private def bind(d:GeometryScalarWorker):Unit={
    val b=d.io.bind.bits;b.warpId.poke(1.U);b.invocation.taskId.poke(19.U);b.invocation.primitiveId.poke(27.U)
    b.invocation.invocationId.poke(31.U);b.invocation.inputVertices.poke(6.U);b.invocation.inputComponents.poke(64.U)
    b.invocation.maxVertices.poke(16.U);b.invocation.outputComponents.poke(64.U)
    for(v<-0 until 6){b.invocation.vertices(v).layer.poke((100+v).U)
      for(c<-0 until 64)b.invocation.vertices(v).components(c).poke((v*100+c).U)}
    d.io.bind.valid.poke(true.B);d.io.bind.ready.expect(true.B);d.clock.step();d.io.bind.valid.poke(false.B)
    b.invocation.vertices(5).components(63).poke(9999.U)
  }
  private def issue(d:GeometryScalarWorker,fn:Int,a:BigInt=0,b:BigInt=0,warp:Int=1):Unit={
    val q=d.io.in.bits;q.decode.instruction.poke(instruction(fn).U);q.decode.warpId.poke(warp.U)
    q.decode.pc.poke(0x1000.U);q.decode.activeMask.poke(15.U);q.rs1Data.poke(a.U);q.rs2Data.poke(b.U)
    d.io.in.valid.poke(true.B);d.io.in.ready.expect(true.B);d.clock.step();d.io.in.valid.poke(false.B)
  }
  private def commit(d:GeometryScalarWorker,success:Boolean,value:BigInt=0):Unit={
    d.io.commit.valid.expect(true.B);d.io.commit.bits.writeRd.expect(success.B);d.io.commit.bits.data.expect(value.U)
    d.io.commit.bits.warpId.expect(1.U);d.io.commit.bits.nextPc.expect(0x1004.U);d.io.commit.bits.rd.expect(5.U)
    val held=d.io.commit.bits.peek().litValue;for(_<-0 until 2){d.io.release.ready.expect(false.B);d.clock.step();assert(d.io.commit.bits.peek().litValue==held)}
    d.io.commit.ready.poke(true.B);d.clock.step();d.io.commit.ready.poke(false.B)
  }
  it should "read captured inputs and snapshot staged outputs into held emit/end events" in {
    simulate(new GeometryScalarWorker(GpuConfig(lanes=4,warps=2))){d=>init(d);bind(d)
      issue(d,32,5,63);commit(d,true,563)
      for((selector,value)<-Seq[(Int,BigInt)](0->31,1->27,2->6,3->64,4->64,5->19)){issue(d,37,selector);commit(d,true,value)}
      issue(d,33,0,123);commit(d,true);issue(d,33,63,456);commit(d,true)
      issue(d,34,77);commit(d,true);issue(d,35)
      d.io.event.valid.expect(true.B);d.io.event.bits.operation.expect(GeometryOutputEvent.emit)
      d.io.event.bits.vertex.components(0).expect(123.U);d.io.event.bits.vertex.components(63).expect(456.U)
      d.io.event.bits.vertex.layer.expect(77.U);val held=d.io.event.bits.peek().litValue
      for(_<-0 until 4){d.io.in.ready.expect(false.B);d.io.commit.valid.expect(false.B);d.clock.step();assert(d.io.event.bits.peek().litValue==held)}
      d.io.event.ready.poke(true.B);d.clock.step();d.io.event.ready.poke(false.B);commit(d,true)
      issue(d,35);d.io.event.bits.vertex.components(0).expect(0.U);d.io.event.bits.vertex.layer.expect(0.U)
      d.io.event.ready.poke(true.B);d.clock.step();d.io.event.ready.poke(false.B);commit(d,true)
      issue(d,36);d.io.event.bits.operation.expect(GeometryOutputEvent.endPrimitive)
      d.io.event.ready.poke(true.B);d.clock.step();d.io.event.ready.poke(false.B);commit(d,true)
      d.io.release.valid.poke(true.B);d.io.release.ready.expect(true.B);d.clock.step();d.io.release.valid.poke(false.B)
      d.io.active.expect(false.B);d.io.in.ready.expect(false.B)
    }
  }
  it should "reject invalid indices and drain a held event during cancellation" in {
    simulate(new GeometryScalarWorker(GpuConfig(lanes=4,warps=2))){d=>init(d);bind(d)
      for((fn,a,b)<-Seq[(Int,BigInt,BigInt)]((32,6,0),(32,5,64),(33,64,1),(37,6,0),(38,0,0))){issue(d,fn,a,b);commit(d,false)}
      issue(d,32,0,0,warp=0);d.io.commit.valid.expect(true.B);d.io.commit.bits.writeRd.expect(false.B)
      d.io.commit.bits.warpId.expect(0.U);d.io.commit.ready.poke(true.B);d.clock.step();d.io.commit.ready.poke(false.B)
      issue(d,33,2,42);commit(d,true);issue(d,35)
      val held=d.io.event.bits.peek().litValue;d.io.cancel.poke(true.B)
      for(_<-0 until 3){d.io.event.valid.expect(true.B);d.io.release.ready.expect(false.B);assert(d.io.event.bits.peek().litValue==held);d.clock.step()}
      d.io.cancel.poke(false.B);d.io.event.ready.poke(true.B);d.clock.step();d.io.event.ready.poke(false.B);commit(d,false)
      issue(d,32,5,63);commit(d,false)
      d.io.release.valid.poke(true.B);d.clock.step();d.io.release.valid.poke(false.B)
      d.io.cancel.poke(true.B);bind(d);d.io.cancel.poke(false.B)
      issue(d,32,5,63);commit(d,false)
      d.io.release.valid.poke(true.B);d.clock.step();d.io.release.valid.poke(false.B)
      bind(d);issue(d,32,5,63);commit(d,true,563)
    }
  }
}
