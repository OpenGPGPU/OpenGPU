package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class FragmentInputCoalescerSpec extends AnyFlatSpec {
  private def word(tag: Int,field: Int,quad: Int): BigInt =
    (0 until 4).foldLeft(BigInt(0))((value,lane)=>value|
      ((BigInt("81000000",16)|(BigInt(tag)<<20)|(BigInt(field)<<8)|(quad*4+lane))<<(lane*32)))

  private def initialize(d: FragmentInputCoalescer): Unit = {
    d.io.cancel.poke(false.B);d.io.store.valid.poke(false.B);d.io.flush.valid.poke(false.B)
    d.io.store.bits.quad.poke(0.U);d.io.store.bits.field.poke(0.U);d.io.store.bits.data.poke(0.U)
    d.io.flush.bits.base.poke(0.U);d.io.flush.bits.quads.poke(1.U)
    d.io.flush.bits.inputWords.poke(6.U);d.io.flush.bits.colorWords.poke(0.U)
    d.io.out.ready.poke(false.B);d.clock.step()
  }

  private def fill(d: FragmentInputCoalescer,quads: Int,inputs: Int,tag: Int): Unit = {
    d.io.store.valid.poke(true.B)
    for(q<-0 until quads;field<-0 until inputs+2) {
      d.io.store.ready.expect(true.B)
      d.io.store.bits.quad.poke(q.U);d.io.store.bits.field.poke(field.U)
      d.io.store.bits.data.poke(word(tag,field,q).U);d.clock.step()
    }
    d.io.store.valid.poke(false.B)
  }

  private def start(d: FragmentInputCoalescer,base: BigInt,quads: Int,inputs: Int,colors: Int): Unit = {
    d.io.flush.bits.base.poke(base.U);d.io.flush.bits.quads.poke(quads.U)
    d.io.flush.bits.inputWords.poke(inputs.U);d.io.flush.bits.colorWords.poke(colors.U)
    d.io.flush.valid.poke(true.B);d.io.flush.ready.expect(true.B);d.clock.step()
    d.io.flush.valid.poke(false.B)
  }

  private def verify(d: FragmentInputCoalescer,capacity: Int,base: BigInt,
      quads: Int,inputs: Int,colors: Int,tag: Int): Int = {
    val expected=mutable.Map.empty[BigInt,Int]
    for(field<-0 until inputs+2;q<-0 until quads;b<-0 until 16) {
      val destination=base+(if(field<inputs)field else field+colors)*capacity*4+q*16+b
      assert(!expected.contains(destination))
      expected(destination)=((word(tag,field,q)>>(b*8))&255).toInt
    }
    val actual=mutable.Map.empty[BigInt,Int]
    var held: Option[BigInt]=None;var cycles=0;var requests=0
    while(!d.io.done.peek().litToBoolean && cycles<(inputs+2)*30) {
      val ready=cycles%11>=5
      d.io.out.ready.poke(ready.B)
      d.io.store.ready.expect(false.B);d.io.flush.ready.expect(false.B)
      if(d.io.out.valid.peek().litToBoolean) {
        val bits=d.io.out.bits.peek().litValue
        held.foreach(old=>assert(old==bits,"stalled coalesced request changed"))
        if(ready) {
          val address=d.io.out.bits.address.peek().litValue
          val data=d.io.out.bits.data.peek().litValue
          val mask=d.io.out.bits.mask.peek().litValue
          assert((address&63)==0 && mask!=0)
          for(b<-0 until 64 if mask.testBit(b)) {
            assert(!actual.contains(address+b),s"duplicate byte ${address+b}")
            assert(expected.contains(address+b),s"write outside captured lanes: ${address+b}")
            actual(address+b)=((data>>(b*8))&255).toInt
          }
          requests+=1;held=None
        } else held=Some(bits)
      } else assert(held.isEmpty,"stalled request was withdrawn")
      d.clock.step();cycles+=1
    }
    d.io.done.expect(true.B);assert(held.isEmpty && actual==expected)
    d.io.out.ready.poke(false.B);d.clock.step();d.io.busy.expect(false.B)
    requests
  }

  for(capacity<-Seq(4,8,12,16,20,128)) {
    it should s"preserve every field and partial-group byte with held split writes at capacity=$capacity" in {
      simulate(new FragmentInputCoalescer(capacity,74)) { d=>
        initialize(d)
        val maxQuads=math.min(4,capacity/4)
        // Reuse the same SRAM with shrinking groups; stale quad banks must
        // never escape through the final partial group's byte masks.
        for((quads,index)<-(maxQuads to 1 by -1).zipWithIndex) {
          val inputs=if(index==0)74 else 6+4*index
          val colors=if(index%2==0)16 else 0
          val base=BigInt("1000",16)+16*(index%4)
          fill(d,quads,inputs,index+1);start(d,base,quads,inputs,colors)
          val requests=verify(d,capacity,base,quads,inputs,colors,index+1)
          val expected=(0 until inputs+2).map { f=>
            val address=base+(if(f<inputs)f else f+colors)*capacity*4
            if((address&63)+quads*16>64)2 else 1
          }.sum
          assert(requests==expected)
          if(capacity==128 && quads==4)assert(requests==inputs+2,
            "aligned full group must use one request per field, not four quad requests")
        }
      }
    }
  }

  it should "discard canceled buffers at every flush phase and reuse them with exact partial masks" in {
    simulate(new FragmentInputCoalescer(20,14)) { d=>
      initialize(d)
      for(offset<-0 until 14) {
        fill(d,4,14,1);start(d,0x1030,4,14,16)
        d.io.out.ready.poke((offset%2==0).B);d.clock.step(offset)
        d.io.cancel.poke(true.B);d.io.out.valid.expect(false.B);d.clock.step()
        d.io.cancel.poke(false.B);d.io.busy.expect(false.B);d.io.out.valid.expect(false.B)
        fill(d,1,6,2);start(d,0x1030,1,6,0)
        verify(d,20,0x1030,1,6,0,2)
      }
    }
  }

  it should "retain the final addressable byte without wrapping a split request" in {
    simulate(new FragmentInputCoalescer(4,6)) { d=>
      initialize(d)
      val base=(BigInt(1)<<32)-128
      fill(d,1,6,3);start(d,base,1,6,0)
      assert(verify(d,4,base,1,6,0,3)==8)
    }
  }
}
