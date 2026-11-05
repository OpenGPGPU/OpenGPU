package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class VertexInputCoalescerSpec extends AnyFlatSpec {
  private def word(tag: Int,field: Int,vertex: Int): BigInt=
    BigInt("a0000000",16)|(BigInt(tag)<<20)|(BigInt(field)<<8)|vertex

  private def initialize(d: VertexInputCoalescer): Unit={
    d.io.cancel.poke(false.B);d.io.store.valid.poke(false.B);d.io.flush.valid.poke(false.B)
    d.io.store.bits.vertex.poke(0.U);d.io.store.bits.field.poke(0.U);d.io.store.bits.data.poke(0.U)
    d.io.flush.bits.base.poke(0.U);d.io.flush.bits.vertices.poke(1.U);d.io.flush.bits.inputWords.poke(3.U)
    d.io.out.ready.poke(false.B);d.clock.step()
  }
  private def fill(d: VertexInputCoalescer,vertices: Int,inputs: Int,tag: Int): Unit={
    d.io.store.valid.poke(true.B)
    for(v<-0 until vertices;field<-0 until inputs) {
      d.io.store.ready.expect(true.B)
      d.io.store.bits.vertex.poke(v.U);d.io.store.bits.field.poke(field.U)
      d.io.store.bits.data.poke(word(tag,field,v).U);d.clock.step()
    }
    d.io.store.valid.poke(false.B)
  }
  private def start(d: VertexInputCoalescer,base: BigInt,vertices: Int,inputs: Int): Unit={
    d.io.flush.bits.base.poke(base.U);d.io.flush.bits.vertices.poke(vertices.U)
    d.io.flush.bits.inputWords.poke(inputs.U);d.io.flush.valid.poke(true.B)
    d.io.flush.ready.expect(true.B);d.clock.step();d.io.flush.valid.poke(false.B)
  }
  private def verify(d: VertexInputCoalescer,capacity: Int,base: BigInt,
      vertices: Int,inputs: Int,tag: Int): Int={
    val expected=mutable.Map.empty[BigInt,Int]
    for(field<-0 until inputs;v<-0 until vertices;b<-0 until 4) {
      val address=base+field*capacity*4+v*4+b
      assert(!expected.contains(address))
      expected(address)=((word(tag,field,v)>>(8*b))&255).toInt
    }
    val actual=mutable.Map.empty[BigInt,Int]
    var held: Option[BigInt]=None;var cycles=0;var requests=0
    while(!d.io.done.peek().litToBoolean && cycles<inputs*30) {
      val ready=cycles%11>=5
      d.io.out.ready.poke(ready.B)
      d.io.store.ready.expect(false.B);d.io.flush.ready.expect(false.B)
      if(d.io.out.valid.peek().litToBoolean) {
        val bits=d.io.out.bits.peek().litValue
        held.foreach(old=>assert(old==bits,"held vertex input write changed"))
        if(ready) {
          val address=d.io.out.bits.address.peek().litValue
          val data=d.io.out.bits.data.peek().litValue;val mask=d.io.out.bits.mask.peek().litValue
          assert((address&63)==0 && mask!=0)
          for(b<-0 until 64 if mask.testBit(b)) {
            assert(expected.contains(address+b),s"write outside captured vertices: ${address+b}")
            assert(!actual.contains(address+b),s"duplicate enabled byte: ${address+b}")
            actual(address+b)=((data>>(8*b))&255).toInt
          }
          requests+=1;held=None
        } else held=Some(bits)
      } else assert(held.isEmpty,"held vertex input write was withdrawn")
      d.clock.step();cycles+=1
    }
    d.io.done.expect(true.B);assert(held.isEmpty && actual==expected)
    d.io.out.ready.poke(false.B);d.clock.step();d.io.busy.expect(false.B)
    requests
  }

  for(capacity<-Seq(4,8,12,16,20,128)) {
    it should s"transpose all input fields with exact held and partial split writes at capacity=$capacity" in {
      simulate(new VertexInputCoalescer(capacity,67)){d=>
        initialize(d)
        val maximum=math.min(16,capacity)
        // Reuse shrinking groups so uninitialized/stale banks cannot escape.
        for((vertices,index)<-(maximum to 1 by -1).zipWithIndex) {
          val inputs=if(index==0)67 else 3+4*(index%5)
          val base=BigInt(0x1000)+4*(index%16)
          fill(d,vertices,inputs,index+1);start(d,base,vertices,inputs)
          val requests=verify(d,capacity,base,vertices,inputs,index+1)
          val expected=(0 until inputs).map { field=>
            if(((base+field*capacity*4)&63)+vertices*4>64)2 else 1
          }.sum
          assert(requests==expected)
          if(capacity==128 && vertices==16)assert(requests==inputs,
            "aligned group must issue one line per field, not sixteen word writes")
        }
      }
    }
  }

  it should "cancel every flush prefix and reuse a smaller input group without stale bytes" in {
    simulate(new VertexInputCoalescer(20,7)){d=>
      initialize(d)
      for(ready<-Seq(true,false);prefix<-0 until (if(ready)30 else 5)) {
        fill(d,16,7,1);start(d,0x103c,16,7)
        d.io.out.ready.poke(ready.B);d.clock.step(prefix)
        d.io.cancel.poke(true.B);d.io.out.valid.expect(false.B);d.clock.step()
        d.io.cancel.poke(false.B);d.io.busy.expect(false.B);d.io.out.valid.expect(false.B)
        fill(d,1,3,2);start(d,0x103c,1,3);verify(d,20,0x103c,1,3,2)
      }
    }
  }

  it should "preserve the final addressable byte across split input rows" in {
    simulate(new VertexInputCoalescer(12,3)){d=>
      initialize(d)
      val base=(BigInt(1)<<32)-144
      fill(d,12,3,3);start(d,base,12,3)
      assert(verify(d,12,base,12,3,3)==5)
    }
  }
}
