package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class FragmentPayloadTransportSpec extends AnyFlatSpec {
  private val config=GpuConfig(lanes=4,warps=2)
  private val payload=ShaderPayloadConfig(capacityBytes=1024,maxPayloadBytes=256,owners=2,ports=3)
  private class Driver(val d: FragmentPayloadTransport,local: Boolean) {
    TriangleRasterReference.zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    var cycle=0;var endpointReady=true
    var controlReply=Option.empty[(Int,BigInt)]
    val issued=mutable.ArrayBuffer.empty[(Int,BigInt,Int)]
    val results=mutable.ArrayBuffer.empty[(Int,BigInt,Boolean)]
    val decisions=mutable.ArrayBuffer.empty[Boolean];val completions=mutable.ArrayBuffer.empty[Boolean]
    private var held=Option.empty[BigInt]
    def tick(): Unit={
      d.io.pool.control.ready.poke(true.B)
      d.io.pool.controlResponse.valid.poke(controlReply.nonEmpty.B)
      controlReply.foreach{case(op,bytes)=>
        val r=d.io.pool.controlResponse.bits
        r.operation.poke(op.U);r.bytes.poke(bytes.U);r.context.poke(101.U)
        r.handle.poke(0.U);r.generation.poke(7.U);r.resident.poke((local && op==0).B)
        r.spill.poke((!local && op==0).B);r.fault.poke(false.B)
      }
      d.io.pool.request.ready.poke(endpointReady.B);d.io.memoryRequest.ready.poke(endpointReady.B)
      if(d.io.pool.controlResponse.valid.peek().litToBoolean && d.io.pool.controlResponse.ready.peek().litToBoolean)controlReply=None
      if(d.io.pool.control.valid.peek().litToBoolean){
        assert(controlReply.isEmpty)
        controlReply=Some((d.io.pool.control.bits.operation.peek().litValue.toInt,d.io.pool.control.bits.bytes.peek().litValue))
      }
      if(local && endpointReady && d.io.pool.request.valid.peek().litToBoolean){
        val q=d.io.pool.request.bits
        q.context.expect(101.U);q.generation.expect(7.U);q.handle.expect(0.U);q.owner.expect(0.U)
        issued+=((q.tag.peek().litValue.toInt,q.offset.peek().litValue,cycle))
      }
      if(!local && endpointReady && d.io.memoryRequest.valid.peek().litToBoolean){
        val q=d.io.memoryRequest.bits
        issued+=((q.transactionId.peek().litValue.toInt,q.address.peek().litValue-0x4000,cycle))
      }
      if(d.io.stageResponse.valid.peek().litToBoolean){
        val q=d.io.stageResponse.bits;val bits=q.peek().litValue
        held.foreach(x=>assert(x==bits,"held staging reply changed"))
        if(d.io.stageResponse.ready.peek().litToBoolean){
          results+=((q.transactionId.peek().litValue.toInt,q.readData.peek().litValue,q.fault.peek().litToBoolean));held=None
        }else held=Some(bits)
      }else assert(held.isEmpty,"held staging reply was withdrawn")
      if(d.io.prepared.valid.peek().litToBoolean && d.io.prepared.ready.peek().litToBoolean){
        d.io.prepared.bits.fault.expect(false.B);decisions+=d.io.prepared.bits.resident.peek().litToBoolean
      }
      if(d.io.done.valid.peek().litToBoolean && d.io.done.ready.peek().litToBoolean)completions+=d.io.done.bits.peek().litToBoolean
      d.clock.step();cycle+=1
    }
    def until(condition: => Boolean): Unit={var n=0;while(!condition && n<100){tick();n+=1};assert(condition,s"payload transport stalled at $cycle")}
    def begin(): Unit={
      val count=decisions.size
      d.io.begin.bits.base.poke(0x4000.U);d.io.begin.bits.bytes.poke(192.U);d.io.begin.bits.context.poke(101.U)
      d.io.prepared.ready.poke(true.B);d.io.done.ready.poke(true.B)
      d.io.begin.valid.poke(true.B);until(d.io.begin.ready.peek().litToBoolean);tick();d.io.begin.valid.poke(false.B)
      until(decisions.size==count+1);assert(decisions.last==local)
    }
    def offer(id: Int,offset: Int,write: Boolean=false,mask: BigInt=(BigInt(1)<<64)-1): Unit={
      val q=d.io.stageRequest.bits
      q.transactionId.poke(id.U);q.address.poke((0x4000+offset).U);q.isWrite.poke(write.B)
      q.sizeLog2.poke(6.U);q.byteMask.poke(mask.U);q.writeData.poke(0x1234.U)
      d.io.stageRequest.valid.poke(true.B);until(d.io.stageRequest.ready.peek().litToBoolean);tick();d.io.stageRequest.valid.poke(false.B)
    }
    def reply(id: Int,data: Int,fault: Boolean=false): Unit={
      if(local){
        val r=d.io.pool.response.bits;r.tag.poke(id.U);r.handle.poke(0.U);r.generation.poke(7.U)
        r.context.poke(101.U);r.data.poke(data.U);r.fault.poke(fault.B);d.io.pool.response.valid.poke(true.B)
        until(d.io.pool.response.ready.peek().litToBoolean);tick();d.io.pool.response.valid.poke(false.B)
      }else{
        val r=d.io.memoryResponse.bits;r.transactionId.poke(id.U);r.readData.poke(data.U);r.fault.poke(fault.B)
        d.io.memoryResponse.valid.poke(true.B);until(d.io.memoryResponse.ready.peek().litToBoolean);tick();d.io.memoryResponse.valid.poke(false.B)
      }
    }
    def finish(fault: Boolean): Unit={
      val count=completions.size
      d.io.finish.valid.poke(true.B);d.io.finish.bits.poke(false.B)
      until(d.io.finish.ready.peek().litToBoolean);tick();d.io.finish.valid.poke(false.B)
      until(completions.size==count+1);assert(completions.last==fault);d.io.drained.expect(true.B)
    }
  }
  for(local<-Seq(true,false)) {
    it should s"overlap two owned lines and route reversed replies without changing held results local=$local" in {
      simulate(new FragmentPayloadTransport(config,payload,0,2,maxInFlight=2)){d=>val t=new Driver(d,local)
        t.begin();t.endpointReady=false;t.offer(0,0);t.offer(1,64)
        d.io.stageRequest.ready.expect(false.B)
        t.endpointReady=true;t.until(t.issued.size==2)
        assert(t.issued.map(_._1).toVector==Vector(0,1));assert(t.issued(1)._3-t.issued(0)._3==1,"line issue inserted a bubble")
        t.reply(1,111);for(_<-0 until 4)t.tick();t.reply(0,222)
        d.io.stageRequest.ready.expect(false.B);d.io.finish.ready.expect(false.B)
        d.io.stageResponse.ready.poke(true.B);t.until(t.results.size==2)
        assert(t.results.toVector==Vector((1,BigInt(111),false),(0,BigInt(222),false)))
        t.offer(0,128,true,65535);t.until(t.issued.size==3);t.reply(0,333)
        t.until(t.results.size==3);assert(t.results.last==(0,BigInt(0),false));t.finish(false)
      }
    }
    it should s"retain a posted line during cancellation and drain rejected younger work before reuse local=$local" in {
      simulate(new FragmentPayloadTransport(config,payload,0,2,maxInFlight=2)){d=>val t=new Driver(d,local)
        t.begin();t.endpointReady=false;t.offer(0,0)
        if(local)t.until(d.io.pool.request.valid.peek().litToBoolean)else t.until(d.io.memoryRequest.valid.peek().litToBoolean)
        val held=if(local)d.io.pool.request.bits.peek().litValue else d.io.memoryRequest.bits.peek().litValue
        d.io.pool.cancel.poke(true.B);t.tick();d.io.pool.cancel.poke(false.B)
        t.offer(1,64)
        for(_<-0 until 4){
          if(local){d.io.pool.request.valid.expect(true.B);assert(d.io.pool.request.bits.peek().litValue==held)}
          else{d.io.memoryRequest.valid.expect(true.B);assert(d.io.memoryRequest.bits.peek().litValue==held)}
          t.tick()
        }
        t.endpointReady=true;d.io.stageResponse.ready.poke(true.B);t.until(t.issued.size==1);t.reply(0,444)
        t.until(t.results.size==2);assert(t.results.forall(r=>r._2==0 && r._3));assert(t.issued.size==1)
        t.finish(true);t.begin();t.offer(0,0);t.until(t.issued.size==2);t.reply(0,555)
        t.until(t.results.size==3);assert(t.results.last==(0,BigInt(555),false));t.finish(false)
      }
    }
    it should s"keep an already offered reply immutable when its lease is cancelled local=$local" in {
      simulate(new FragmentPayloadTransport(config,payload,0,2,maxInFlight=2)){d=>val t=new Driver(d,local)
        t.begin();t.offer(0,0);t.until(t.issued.size==1);t.reply(0,123)
        d.io.stageResponse.valid.expect(true.B);val held=d.io.stageResponse.bits.peek().litValue
        d.io.pool.cancel.poke(true.B);t.tick();d.io.pool.cancel.poke(false.B)
        for(_<-0 until 4){t.tick();assert(d.io.stageResponse.bits.peek().litValue==held)}
        d.io.stageResponse.ready.poke(true.B);t.until(t.results.size==1)
        assert(t.results.last==(0,BigInt(123),false));t.finish(true)
      }
    }
    it should s"fault invalid lines locally and preserve a later independent reply fault local=$local" in {
      simulate(new FragmentPayloadTransport(config,payload,0,2,maxInFlight=2)){d=>val t=new Driver(d,local)
        t.begin();d.io.stageResponse.ready.poke(true.B)
        t.offer(0,192);t.offer(1,64);t.until(t.issued.size==1);t.reply(1,999,true)
        t.until(t.results.size==2);assert(t.results.map(_._1).toSet==Set(0,1));assert(t.results.forall(r=>r._2==0 && r._3))
        assert(t.issued.head._1==1);t.finish(false)
      }
    }
  }
}
