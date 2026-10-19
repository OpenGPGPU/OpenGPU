package opengpu.graphics

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class SharedShaderPayloadRouterSpec extends AnyFlatSpec {
  private def zero(data: Data): Unit=data match {
    case r: Record=>r.elements.values.foreach(zero)
    case v: Vec[_]=>v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case _=>()
  }
  for(cus <- Seq(1,2,4))
  it should s"retain queued payload identities, prospective pins and local tags through reordered retirement with $cus CUs" in {
    val config=GpuConfig(lanes=4,warps=2)
    val p=ShaderPayloadConfig(owners=2,ports=2+cus)
    simulate(new SharedShaderTaskRouter(config,cus,2,tasksPerClient=3,shaderPayload=Some(p))) { d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val pool=d.io.pool
      def offer(client: Int,id: Int,generation: Int,local: Boolean=true): Unit={
        val port=d.io.clients(client);port.launch.bits.commandId.poke(id.U)
        port.launch.bits.launch.kernelPc.poke((0x1000+id*4).U)
        port.launch.bits.launch.kernargAddress.poke(0x8000.U)
        val b=d.io.clientPayload.get(client)
        b.valid.poke(local.B);b.bits.handle.poke(client.U);b.bits.owner.poke(client.U)
        b.bits.generation.poke(generation.U);b.bits.context.poke((generation+100).U)
        b.bits.base.poke(0x8000.U);b.bits.bytes.poke(288.U);port.launch.valid.poke(true.B)
      }
      def stop(client: Int): Unit={d.io.clients(client).launch.valid.poke(false.B);d.io.clientPayload.get(client).valid.poke(false.B)}
      def generations: Set[Int]=d.io.payloadPins.get.filter(_.valid.peek().litToBoolean)
        .map(_.bits.generation.peek().litValue.toInt).toSet
      def head(tag: Int,generation: Int,local: Boolean=true): Unit={
        pool.launch.valid.expect(true.B);pool.launch.bits.commandId.expect(tag.U)
        d.io.poolPayload.get.valid.expect(local.B)
        if(local){d.io.poolPayload.get.bits.generation.expect(generation.U);d.io.poolPayload.get.bits.context.expect((generation+100).U)}
      }
      offer(0,7,11);d.io.clients(0).launch.ready.expect(true.B);d.clock.step();stop(0)
      offer(1,7,22);d.io.clients(1).launch.ready.expect(true.B);d.clock.step();stop(1)
      head(0,11);offer(0,8,33)
      for(_ <- 0 until 6){
        d.io.clients(0).launch.ready.expect(false.B);head(0,11)
        assert(generations==Set(11,22,33),"offered or queued lease lost its pin")
        d.io.clientPayload.get(1).bits.generation.poke(99.U);d.clock.step()
      }
      pool.launch.ready.poke(true.B);d.io.clients(0).launch.ready.expect(true.B)
      d.clock.step();stop(0)
      head(3,22);d.clock.step();head(1,33);d.clock.step();pool.launch.valid.expect(false.B)
      assert(generations==Set(11,22,33))
      // The physical CU selected for a task is unrelated to its client or local tag.
      pool.owned.poke(1.U);pool.taskIds(0).poke(1.U)
      d.io.clients(0).taskIds(0).expect(8.U);d.io.clients(1).owned.expect(0.U)
      pool.trap.valid.poke(true.B);pool.trap.bits.taskId.poke(1.U)
      pool.trap.bits.event.cause.poke(5.U)
      for(_ <- 0 until 4){
        pool.trap.ready.expect(false.B);d.io.clients(0).trap.valid.expect(true.B)
        d.io.clients(0).trap.bits.taskId.expect(8.U);assert(generations==Set(11,22,33));d.clock.step()
      }
      d.io.clients(0).trap.ready.poke(true.B);d.clock.step();pool.trap.valid.poke(false.B);pool.owned.poke(0.U)
      var remaining=Set(11,22,33)
      for((tag,client,localTag,generation,success) <- Seq((1,0,8,33,false),(3,1,7,22,true),(0,0,7,11,true))) {
        pool.completion.valid.poke(true.B);pool.completion.bits.commandId.poke(tag.U)
        pool.completion.bits.success.poke(success.B)
        for(_ <- 0 until 4){
          pool.completion.ready.expect(false.B);d.io.clients(client).completion.bits.commandId.expect(localTag.U)
          d.io.clients(client).completion.bits.success.expect(success.B);assert(generations==remaining);d.clock.step()
        }
        d.io.clients(client).completion.ready.poke(true.B);d.clock.step()
        d.io.clients(client).completion.ready.poke(false.B);pool.completion.valid.poke(false.B)
        remaining-=generation;assert(generations==remaining)
      }
      d.io.drained.expect(true.B)
      offer(0,9,44,local=false);d.io.clients(0).launch.ready.expect(true.B);d.clock.step();stop(0)
      pool.launch.ready.poke(false.B);head(0,44,local=false)
      d.io.clientPayload.get(0).valid.poke(true.B)
      for(_ <- 0 until 4){head(0,44,local=false);assert(generations.isEmpty);d.clock.step()}
      d.io.clientPayload.get(0).valid.poke(false.B);pool.launch.ready.poke(true.B);d.clock.step()
      pool.completion.valid.poke(true.B);pool.completion.bits.commandId.poke(0.U)
      pool.completion.bits.success.poke(true.B);d.io.clients(0).completion.ready.poke(true.B)
      d.io.clients(0).completion.bits.commandId.expect(9.U);d.clock.step();pool.completion.valid.poke(false.B)
      d.io.drained.expect(true.B)
      info(s"PAYLOAD_CLIENT_ROUTER CUs=$cus clients=2 tasksPerClient=3 queued=3 sameLocalTag=7 prospectivePins=true heldCompletion=true reordered=true fallbackReuse=true")
    }
  }
}
