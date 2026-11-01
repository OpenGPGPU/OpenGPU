package opengpu.system

import chisel3._
import chisel3.reflect.DataMirror
import chisel3.util._
import opengpu.graphics.{BankedShaderPayloadStore, ShaderPayloadConfig, ShaderPayloadControl, ShaderPayloadProducerPort}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

private class TessellationPayloadOwnerStoreHarness(p: ShaderPayloadConfig) extends Module {
  val io=IO(new Bundle {
    val acquire=Flipped(Decoupled(Bool()));val end=Flipped(Decoupled(Bool()))
    val legacy=Vec(p.owners,Flipped(new ShaderPayloadProducerPort(p)))
    val tessellation=Vec(p.owners,Flipped(new ShaderPayloadProducerPort(p)))
    val drained=Output(Bool());val poolDrained=Output(Bool())
  })
  private val mux=Module(new TessellationHostPayloadOwner(p))
  private val store=Module(new BankedShaderPayloadStore(p,lazyInitialization=true))
  mux.io.acquire<>io.acquire;mux.io.end<>io.end;mux.io.legacy<>io.legacy;mux.io.tessellation<>io.tessellation
  for(i<-0 until p.owners){
    store.io.control(i)<>mux.io.pool(i).control;mux.io.pool(i).controlResponse<>store.io.controlResponse(i)
    store.io.request(i)<>mux.io.pool(i).request;store.io.request(i).bits.owner:=i.U
    mux.io.pool(i).response<>store.io.response(i)
  }
  for(i<-p.owners until p.ports){store.io.request(i).valid:=false.B;store.io.request(i).bits:=0.U.asTypeOf(store.io.request(i).bits);store.io.response(i).ready:=true.B}
  io.drained:=mux.io.drained;io.poolDrained:=store.io.drained
}

class TessellationHostPayloadOwnerSpec extends AnyFlatSpec {
  private def zero(d: Data): Unit = d match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input => u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input => s.poke(0.S)
    case _ => ()
  }
  it should "handoff only after real payload-store replies and leases retire" in {
    val p=ShaderPayloadConfig(capacityBytes=256,maxPayloadBytes=256,owners=2,ports=4,responseDepth=2)
    simulate(new TessellationPayloadOwnerStoreHarness(p)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def until(condition: => Boolean):Unit={var n=0;while(!condition && n<1000){d.clock.step();n+=1};assert(condition,"payload store handshake timed out")}
      def acquire(tess:Boolean):Unit={d.io.acquire.bits.poke(tess.B);d.io.acquire.valid.poke(true.B);until(d.io.acquire.ready.peek().litToBoolean);d.clock.step();d.io.acquire.valid.poke(false.B)}
      def end():Unit={d.io.end.bits.poke(true.B);d.io.end.valid.poke(true.B);until(d.io.end.ready.peek().litToBoolean);d.clock.step();d.io.end.valid.poke(false.B)}
      acquire(false);val a=d.io.legacy(0);val b=d.io.tessellation(0)
      a.control.bits.operation.poke(ShaderPayloadControl.allocate.U);a.control.bits.bytes.poke(64.U);a.control.bits.context.poke(7.U)
      a.control.valid.poke(true.B);until(a.control.ready.peek().litToBoolean);d.clock.step();a.control.valid.poke(false.B)
      until(a.controlResponse.valid.peek().litToBoolean);a.controlResponse.bits.resident.expect(true.B)
      val handle=a.controlResponse.bits.handle.peek().litValue;val generation=a.controlResponse.bits.generation.peek().litValue
      a.controlResponse.ready.poke(true.B);d.clock.step();a.controlResponse.ready.poke(false.B)
      a.request.bits.handle.poke(handle.U);a.request.bits.generation.poke(generation.U);a.request.bits.context.poke(7.U)
      a.request.bits.write.poke(true.B);a.request.bits.data.poke("h12345678".U);a.request.bits.mask.poke(15.U);a.request.bits.tag.poke(9.U)
      a.request.valid.poke(true.B);until(a.request.ready.peek().litToBoolean);d.clock.step();a.request.valid.poke(false.B)
      until(a.response.valid.peek().litToBoolean);a.response.bits.fault.expect(false.B);a.response.bits.tag.expect(9.U)
      d.io.end.valid.poke(true.B);d.io.end.bits.poke(true.B);d.io.end.ready.expect(false.B);d.clock.step(3)
      a.response.ready.poke(true.B);d.clock.step();a.response.ready.poke(false.B);d.io.end.ready.expect(false.B)
      a.control.bits.operation.poke(ShaderPayloadControl.release.U);a.control.bits.handle.poke(handle.U)
      a.control.bits.generation.poke(generation.U);a.control.bits.context.poke(7.U);a.control.valid.poke(true.B)
      until(a.control.ready.peek().litToBoolean);d.clock.step();a.control.valid.poke(false.B)
      until(a.controlResponse.valid.peek().litToBoolean);a.controlResponse.bits.fault.expect(false.B)
      // The pool can reuse the sole slot while the old release reply is held.
      val a1=d.io.legacy(1);a1.control.bits.operation.poke(ShaderPayloadControl.allocate.U)
      a1.control.bits.bytes.poke(64.U);a1.control.bits.context.poke(17.U);a1.control.valid.poke(true.B)
      until(a1.control.ready.peek().litToBoolean);d.clock.step();a1.control.valid.poke(false.B)
      until(a1.controlResponse.valid.peek().litToBoolean);a1.controlResponse.bits.resident.expect(true.B)
      val newerHandle=a1.controlResponse.bits.handle.peek().litValue;val newerGeneration=a1.controlResponse.bits.generation.peek().litValue
      assert(newerHandle==handle && newerGeneration!=generation)
      a1.controlResponse.ready.poke(true.B);d.clock.step();a1.controlResponse.ready.poke(false.B)
      d.io.end.ready.expect(false.B);a.controlResponse.ready.poke(true.B);d.clock.step();a.controlResponse.ready.poke(false.B)
      d.io.end.ready.expect(false.B)
      a1.control.bits.operation.poke(ShaderPayloadControl.release.U);a1.control.bits.handle.poke(newerHandle.U)
      a1.control.bits.generation.poke(newerGeneration.U);a1.control.valid.poke(true.B)
      until(a1.control.ready.peek().litToBoolean);d.clock.step();a1.control.valid.poke(false.B)
      until(a1.controlResponse.valid.peek().litToBoolean);a1.controlResponse.bits.fault.expect(false.B)
      a1.controlResponse.ready.poke(true.B);d.clock.step();a1.controlResponse.ready.poke(false.B)
      until(d.io.end.ready.peek().litToBoolean);d.clock.step();d.io.end.valid.poke(false.B)
      acquire(true);b.control.bits.operation.poke(ShaderPayloadControl.allocate.U);b.control.bits.bytes.poke(64.U);b.control.bits.context.poke(8.U)
      b.control.valid.poke(true.B);until(b.control.ready.peek().litToBoolean);d.clock.step();b.control.valid.poke(false.B)
      until(b.controlResponse.valid.peek().litToBoolean);b.controlResponse.bits.resident.expect(true.B)
      val bh=b.controlResponse.bits.handle.peek().litValue;val bg=b.controlResponse.bits.generation.peek().litValue
      b.controlResponse.ready.poke(true.B);d.clock.step();b.controlResponse.ready.poke(false.B)
      b.control.bits.operation.poke(ShaderPayloadControl.release.U);b.control.bits.handle.poke(bh.U);b.control.bits.generation.poke(bg.U)
      b.control.valid.poke(true.B);until(b.control.ready.peek().litToBoolean);d.clock.step();b.control.valid.poke(false.B)
      until(b.controlResponse.valid.peek().litToBoolean);b.controlResponse.bits.fault.expect(false.B)
      b.controlResponse.ready.poke(true.B);d.clock.step();b.controlResponse.ready.poke(false.B)
      end();until(d.io.poolDrained.peek().litToBoolean);d.io.drained.expect(true.B)
    }
  }
  it should "hold the producer owner across live leases, reordered release replies and data backpressure" in {
    val p=ShaderPayloadConfig(capacityBytes=256,maxPayloadBytes=256,owners=2,ports=4,responseDepth=2)
    simulate(new TessellationHostPayloadOwner(p)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val io=d.io
      for(i<-0 until 2){
        io.pool(i).control.ready.poke(true.B);io.pool(i).request.ready.poke(true.B)
        io.legacy(i).controlResponse.ready.poke(true.B);io.legacy(i).response.ready.poke(true.B)
        io.tessellation(i).controlResponse.ready.poke(true.B);io.tessellation(i).response.ready.poke(true.B)
      }
      io.acquire.valid.poke(true.B);io.acquire.bits.poke(false.B);io.acquire.ready.expect(true.B);d.clock.step();io.acquire.valid.poke(false.B)
      io.active.expect(true.B);io.drained.expect(false.B)
      def control(owner:Int,operation:Int,generation:Int):Unit={
        val port=io.legacy(owner);port.control.bits.operation.poke(operation.U);port.control.bits.generation.poke(generation.U)
        port.control.valid.poke(true.B);port.control.ready.expect(true.B)
        io.pool(owner).control.valid.expect(true.B);io.tessellation(owner).control.ready.expect(false.B)
        d.clock.step();port.control.valid.poke(false.B)
      }
      def answer(owner:Int,operation:Int,generation:Int,resident:Boolean=false,spill:Boolean=false):Unit={
        val out=io.pool(owner).controlResponse;out.bits.operation.poke(operation.U);out.bits.handle.poke(0.U)
        out.bits.generation.poke(generation.U);out.bits.resident.poke(resident.B);out.bits.spill.poke(spill.B);out.bits.fault.poke(false.B)
        out.valid.poke(true.B);io.legacy(owner).controlResponse.valid.expect(true.B)
        io.tessellation(owner).controlResponse.valid.expect(false.B);out.ready.expect(true.B);d.clock.step();out.valid.poke(false.B)
      }
      control(0,ShaderPayloadControl.allocate,0);answer(0,ShaderPayloadControl.allocate,1,resident=true)
      control(0,ShaderPayloadControl.release,1) // Its reply is held by the pool.
      io.end.valid.poke(true.B);io.end.bits.poke(true.B);io.end.ready.expect(false.B);io.end.valid.poke(false.B)
      control(1,ShaderPayloadControl.allocate,0);answer(1,ShaderPayloadControl.allocate,2,resident=true)
      control(1,ShaderPayloadControl.cancel,2);answer(1,ShaderPayloadControl.cancel,2,resident=true)
      answer(0,ShaderPayloadControl.release,1) // Old release must not erase the newer lease.
      control(0,ShaderPayloadControl.allocate,0);answer(0,ShaderPayloadControl.allocate,0,spill=true)
      io.end.valid.poke(true.B);io.end.bits.poke(true.B);io.end.ready.expect(false.B)
      control(1,ShaderPayloadControl.release,2);answer(1,ShaderPayloadControl.release,2)
      io.end.ready.expect(true.B);io.end.valid.poke(false.B)

      io.legacy(0).request.bits.tag.poke(9.U);io.legacy(0).request.valid.poke(true.B)
      io.legacy(0).request.ready.expect(true.B);d.clock.step();io.legacy(0).request.valid.poke(false.B)
      io.legacy(0).response.ready.poke(false.B);io.pool(0).response.bits.tag.poke(9.U)
      io.pool(0).response.bits.fault.poke(true.B);io.pool(0).response.valid.poke(true.B)
      io.end.valid.poke(true.B)
      for(_<-0 until 4){
        io.end.ready.expect(false.B);io.legacy(0).response.valid.expect(true.B);io.legacy(0).response.bits.fault.expect(true.B)
        io.tessellation(0).response.valid.expect(false.B);io.pool(0).response.ready.expect(false.B);d.clock.step()
      }
      io.legacy(0).response.ready.poke(true.B);d.clock.step();io.pool(0).response.valid.poke(false.B)
      io.end.ready.expect(true.B);d.clock.step();io.end.valid.poke(false.B);io.drained.expect(true.B)
      io.acquire.bits.poke(true.B);io.acquire.valid.poke(true.B);io.acquire.ready.expect(true.B);d.clock.step();io.acquire.valid.poke(false.B)
      io.tessellation(0).request.valid.poke(true.B);io.tessellation(0).request.bits.tag.poke(17.U)
      io.tessellation(0).request.ready.expect(true.B);io.legacy(0).request.ready.expect(false.B);d.clock.step()
      io.tessellation(0).request.valid.poke(false.B);io.pool(0).response.bits.tag.poke(17.U);io.pool(0).response.bits.fault.poke(false.B)
      io.pool(0).response.valid.poke(true.B);io.tessellation(0).response.valid.expect(true.B);io.legacy(0).response.valid.expect(false.B)
      d.clock.step();io.pool(0).response.valid.poke(false.B);io.end.valid.poke(true.B);io.end.ready.expect(true.B)
      d.clock.step();io.end.valid.poke(false.B);io.drained.expect(true.B)
    }
  }
}
