package opengpu.system

import chisel3._
import chisel3.reflect.DataMirror
import chisel3.util._
import opengpu.graphics.{BankedShaderPayloadStore, ShaderPayloadConfig, ShaderPayloadControl, ShaderPayloadProducerPort}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

private class ThreeDomainPayloadStoreHarness(p: ShaderPayloadConfig) extends Module {
  val io = IO(new Bundle {
    val acquire = Flipped(Decoupled(UInt(2.W))); val end = Flipped(Decoupled(Bool()))
    val clients = Vec(3, Vec(p.owners, Flipped(new ShaderPayloadProducerPort(p))))
    val active = Output(Bool()); val drained = Output(Bool()); val poolDrained = Output(Bool())
  })
  private val mux = Module(new TessellationThreeDomainPayloadOwner(p))
  private val store = Module(new BankedShaderPayloadStore(p, lazyInitialization=true))
  mux.io.acquire <> io.acquire; mux.io.end <> io.end; mux.io.clients <> io.clients
  for (i <- 0 until p.owners) {
    store.io.control(i) <> mux.io.pool(i).control; mux.io.pool(i).controlResponse <> store.io.controlResponse(i)
    store.io.request(i) <> mux.io.pool(i).request; store.io.request(i).bits.owner := i.U
    mux.io.pool(i).response <> store.io.response(i)
  }
  for (i <- p.owners until p.ports) {
    store.io.request(i).valid := false.B; store.io.request(i).bits := 0.U.asTypeOf(store.io.request(i).bits)
    store.io.response(i).ready := true.B
  }
  io.active := mux.io.active; io.drained := mux.io.drained; io.poolDrained := store.io.drained
}

class TessellationThreeDomainPayloadOwnerSpec extends AnyFlatSpec {
  private def zero(d: Data): Unit = d match {
    case r: Record => r.elements.values.foreach(zero); case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input => u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input => s.poke(0.S)
    case _ => ()
  }
  it should "retain each domain until payload replies and leases drain" in {
    val p=ShaderPayloadConfig(capacityBytes=256,maxPayloadBytes=256,owners=2,ports=4,responseDepth=2)
    simulate(new ThreeDomainPayloadStoreHarness(p)){ d =>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def until(label:String)(condition: => Boolean):Unit={var n=0;while(!condition && n<1000){d.clock.step();n+=1};assert(condition,s"$label timeout")}
      d.io.acquire.bits.poke(3.U);d.io.acquire.valid.poke(true.B);d.io.acquire.ready.expect(false.B)
      d.clock.step();d.io.acquire.valid.poke(false.B);d.io.active.expect(false.B)
      for(domain<-Seq(0,1,2,0)){
        d.io.acquire.bits.poke(domain.U);d.io.acquire.valid.poke(true.B);until("acquire")(d.io.acquire.ready.peek().litToBoolean)
        d.clock.step();d.io.acquire.valid.poke(false.B);d.io.active.expect(true.B)
        val port=d.io.clients(domain)(0)
        port.control.bits.operation.poke(ShaderPayloadControl.allocate.U);port.control.bits.bytes.poke(64.U)
        port.control.bits.context.poke((7+domain).U);port.control.valid.poke(true.B)
        until("allocation request")(port.control.ready.peek().litToBoolean);d.clock.step();port.control.valid.poke(false.B)
        until("allocation reply")(port.controlResponse.valid.peek().litToBoolean);port.controlResponse.bits.resident.expect(true.B)
        for(other<-0 until 3 if other!=domain)d.io.clients(other)(0).controlResponse.valid.expect(false.B)
        val handle=port.controlResponse.bits.handle.peek().litValue;val generation=port.controlResponse.bits.generation.peek().litValue
        d.io.end.valid.poke(true.B);d.io.end.bits.poke(true.B)
        for(_<-0 until 3){d.io.end.ready.expect(false.B);d.clock.step()}
        port.controlResponse.ready.poke(true.B);d.clock.step();port.controlResponse.ready.poke(false.B)
        d.io.end.ready.expect(false.B)
        port.request.bits.handle.poke(handle.U);port.request.bits.generation.poke(generation.U)
        port.request.bits.context.poke((7+domain).U);port.request.bits.write.poke(true.B)
        port.request.bits.data.poke((0x12340000L+domain).U);port.request.bits.mask.poke(15.U)
        port.request.bits.tag.poke((9+domain).U);port.request.valid.poke(true.B)
        until("write request")(port.request.ready.peek().litToBoolean);d.clock.step();port.request.valid.poke(false.B)
        until("write reply")(port.response.valid.peek().litToBoolean);port.response.bits.fault.expect(false.B)
        port.response.bits.tag.expect((9+domain).U)
        for(other<-0 until 3 if other!=domain)d.io.clients(other)(0).response.valid.expect(false.B)
        d.io.end.ready.expect(false.B);d.clock.step(2);port.response.ready.poke(true.B);d.clock.step();port.response.ready.poke(false.B)
        port.control.bits.operation.poke(ShaderPayloadControl.release.U);port.control.bits.handle.poke(handle.U)
        port.control.bits.generation.poke(generation.U);port.control.bits.context.poke((7+domain).U);port.control.valid.poke(true.B)
        until("release request")(port.control.ready.peek().litToBoolean);d.clock.step();port.control.valid.poke(false.B)
        until("release reply")(port.controlResponse.valid.peek().litToBoolean);port.controlResponse.bits.fault.expect(false.B)
        d.io.end.ready.expect(false.B);port.controlResponse.ready.poke(true.B);d.clock.step();port.controlResponse.ready.poke(false.B)
        until("retire")(d.io.end.ready.peek().litToBoolean);d.clock.step();d.io.end.valid.poke(false.B)
        d.io.active.expect(false.B);until("drain")(d.io.drained.peek().litToBoolean && d.io.poolDrained.peek().litToBoolean)
      }
    }
  }
}
