package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class VertexMemoryResponseRouterSpec extends AnyFlatSpec {
  private case class Reply(tag: Int,data: BigInt,fault: Boolean)
  private def route(tag: Int): Int=if(tag<2)tag else 2
  private def initialize(d: VertexMemoryResponseRouter): Unit={
    d.io.in.valid.poke(false.B);d.io.in.bits.poke(0.U.asTypeOf(d.io.in.bits))
    Seq(d.io.inputResponse,d.io.stagingResponse,d.io.textureResponse).foreach(_.ready.poke(false.B))
    d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
  }
  private def offer(d: VertexMemoryResponseRouter,r: Reply): Unit={
    d.io.in.valid.poke(true.B);d.io.in.bits.transactionId.poke(r.tag.U)
    d.io.in.bits.readData.poke(r.data.U);d.io.in.bits.fault.poke(r.fault.B)
  }
  private def check(d: VertexMemoryResponseRouter,r: Reply): Unit={
    val outputs=Seq(d.io.inputResponse,d.io.stagingResponse,d.io.textureResponse)
    for((out,index)<-outputs.zipWithIndex)out.valid.expect((index==route(r.tag)).B)
    val out=outputs(route(r.tag));out.bits.readData.expect(r.data.U);out.bits.fault.expect(r.fault.B)
    out.bits.transactionId.expect((if(r.tag<2)0 else r.tag-2).U)
  }
  it should "route a ready stream at one reply per cycle after one registered handoff" in {
    simulate(new VertexMemoryResponseRouter){d=>initialize(d)
      val outputs=Seq(d.io.inputResponse,d.io.stagingResponse,d.io.textureResponse)
      outputs.foreach(_.ready.poke(true.B))
      val inputs=Vector.tabulate(128)(i=>Reply(i%4,(BigInt(1)<<511)|(BigInt(i)<<32),i%3==0))
      for(cycle<-0 to inputs.size){
        if(cycle<inputs.size){offer(d,inputs(cycle));d.io.in.ready.expect(true.B)}else d.io.in.valid.poke(false.B)
        if(cycle==0)outputs.foreach(_.valid.expect(false.B))else check(d,inputs(cycle-1))
        d.clock.step()
      }
      d.io.busy.expect(false.B)
    }
  }
  it should "retain two accepted replies and preserve routing and held fault payloads under backpressure" in {
    simulate(new VertexMemoryResponseRouter){d=>initialize(d)
      val first=Reply(0,(BigInt(1)<<511)|7,true);val second=Reply(3,(BigInt(1)<<510)|9,false)
      offer(d,first);d.io.in.ready.expect(true.B);d.clock.step()
      offer(d,second);d.io.in.ready.expect(true.B);check(d,first);d.clock.step()
      d.io.in.valid.poke(false.B);d.io.in.ready.expect(false.B);d.io.busy.expect(true.B)
      d.io.textureResponse.ready.poke(true.B)
      for(_<-0 until 7){check(d,first);d.clock.step();d.io.in.ready.expect(false.B)}
      d.io.inputResponse.ready.poke(true.B)
      d.io.in.ready.expect(false.B) // downstream readiness cannot reach the input combinationally.
      d.clock.step();check(d,second);d.io.in.ready.expect(true.B)
      d.clock.step();d.io.busy.expect(false.B)
      for(out<-Seq(d.io.inputResponse,d.io.stagingResponse,d.io.textureResponse))out.valid.expect(false.B)
    }
  }
  it should "deliver every traffic class exactly once with randomized stalls and registered admission" in {
    simulate(new VertexMemoryResponseRouter){d=>initialize(d)
      val random=new scala.util.Random(0x56525350L)
      val inputs=Vector.tabulate(512)(i=>Reply(i%4,BigInt(512,random),i%7==0))
      val expected=mutable.Queue.empty[Reply];val outputs=Seq(d.io.inputResponse,d.io.stagingResponse,d.io.textureResponse)
      var sent=0;var received=0;var cycles=0;var offered=false
      while(received<inputs.size && cycles<10000){
        if(!offered && sent<inputs.size && random.nextInt(5)>0)offered=true
        if(offered)offer(d,inputs(sent))else d.io.in.valid.poke(false.B)
        outputs.foreach(_.ready.poke((random.nextInt(4)>0).B))
        d.io.in.ready.expect((expected.size<2).B);d.io.busy.expect(expected.nonEmpty.B)
        if(expected.nonEmpty)check(d,expected.front)else outputs.foreach(_.valid.expect(false.B))
        val accepted=d.io.in.valid.peek().litToBoolean && d.io.in.ready.peek().litToBoolean
        if(expected.nonEmpty && outputs(route(expected.front.tag)).ready.peek().litToBoolean){expected.dequeue();received+=1}
        if(accepted){expected.enqueue(inputs(sent));sent+=1;offered=false}
        d.clock.step();cycles+=1
      }
      assert(sent==512 && received==512 && expected.isEmpty);d.io.busy.expect(false.B)
    }
  }
}
