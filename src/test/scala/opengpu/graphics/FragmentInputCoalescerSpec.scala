package opengpu.graphics

import chisel3._
import chisel3.util.HasBlackBoxInline
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class FragmentInputNextFieldSramModels extends BlackBox with HasBlackBoxInline {
  val io=IO(new Bundle {val present=Output(Bool())})
  setInline("FragmentInputNextFieldSramModels.sv",
    "module FragmentInputNextFieldSramModels(output present); assign present=1'b1; endmodule\n"+
      Seq(32,64).map(width=>java.nio.file.Files.readString(java.nio.file.Paths.get(
        s"depends/asap7_sram_0p0/generated/verilog/srambank_64x4x${width}_6t122.v"))).mkString("\n"))
}
private class FragmentInputNextFieldNative extends FragmentInputCoalescer(128,74,useSramBlackBoxes=true) {
  private val models=Module(new FragmentInputNextFieldSramModels)
  dontTouch(models.io.present)
}

class FragmentInputCoalescerSpec extends AnyFlatSpec {
  private case class Verification(requests: Int,clocks: Int,operations: Vector[(BigInt,BigInt,BigInt)])
  private def word(tag: Int,field: Int,quad: Int): BigInt =
    (0 until 4).foldLeft(BigInt(0))((value,lane)=>value|
      ((BigInt("81000000",16)|(BigInt(tag)<<20)|(BigInt(field)<<8)|(quad*4+lane))<<(lane*32)))

  private def initialize(d: FragmentInputCoalescer): Unit = {
    d.io.cancel.poke(false.B);d.io.store.valid.poke(false.B);d.io.flush.valid.poke(false.B)
    d.io.store.bits.quad.poke(0.U);d.io.store.bits.field.poke(0.U)
    d.io.store.bits.mask.poke(1.U);d.io.store.bits.data.poke(0.U)
    d.io.flush.bits.base.poke(0.U);d.io.flush.bits.quads.poke(1.U)
    d.io.flush.bits.inputWords.poke(6.U);d.io.flush.bits.colorWords.poke(0.U)
    d.io.out.ready.poke(false.B)
    d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
  }

  private def fill(d: FragmentInputCoalescer,quads: Int,inputs: Int,tag: Int): Unit = {
    d.io.store.valid.poke(true.B)
    // Exercise both four-field writes and masked individual writes on the
    // same physical SRAM, including reuse after a larger canceled group.
    for(q<-0 until quads;base<-0 until inputs+2 by 4;
        selected<-(if(tag%2==1)Seq((0 until math.min(4,inputs+2-base)).toVector)
          else(0 until math.min(4,inputs+2-base)).map(i=>Vector(i)))) {
      d.io.store.ready.expect(true.B)
      d.io.store.bits.quad.poke(q.U);d.io.store.bits.field.poke(base.U)
      d.io.store.bits.mask.poke(selected.map(1<<_).sum.U)
      d.io.store.bits.data.poke(selected.foldLeft(BigInt(0))((data,i)=>data|(word(tag,base+i,q)<<(128*i))).U)
      d.clock.step()
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
      quads: Int,inputs: Int,colors: Int,tag: Int,fixedReady: Boolean=false): Verification = {
    val expected=mutable.Map.empty[BigInt,Int]
    for(field<-0 until inputs+2;q<-0 until quads;b<-0 until 16) {
      val destination=base+(if(field<inputs)field else field+colors)*capacity*4+q*16+b
      assert(!expected.contains(destination))
      expected(destination)=((word(tag,field,q)>>(b*8))&255).toInt
    }
    val actual=mutable.Map.empty[BigInt,Int]
    val operations=mutable.ArrayBuffer.empty[(BigInt,BigInt,BigInt)]
    var held: Option[BigInt]=None;var cycles=0;var requests=0
    while(!d.io.done.peek().litToBoolean && cycles<(inputs+2)*30) {
      val ready=fixedReady || cycles%11>=5
      d.io.out.ready.poke(ready.B)
      d.io.store.ready.expect(false.B);d.io.flush.ready.expect(false.B)
      if(d.io.out.valid.peek().litToBoolean) {
        val bits=d.io.out.bits.peek().litValue
        held.foreach(old=>assert(old==bits,"stalled coalesced request changed"))
        if(ready) {
          val address=d.io.out.bits.address.peek().litValue
          val data=d.io.out.bits.data.peek().litValue
          val mask=d.io.out.bits.mask.peek().litValue
          operations+=((address,data,mask))
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
    d.io.out.valid.expect(false.B)
    d.io.out.ready.poke(false.B);d.clock.step();d.io.busy.expect(false.B)
    Verification(requests,cycles,operations.toVector)
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
          val observed=verify(d,capacity,base,quads,inputs,colors,index+1)
          val expected=(0 until inputs+2).map { f=>
            val address=base+(if(f<inputs)f else f+colors)*capacity*4
            if((address&63)+quads*16>64)2 else 1
          }.sum
          assert(observed.requests==expected)
          if(capacity==128 && quads==4)assert(observed.requests==inputs+2,
            "aligned full group must use one request per field, not four quad requests")
        }
        // One, two and three final fields exercise partial four-field stores;
        // stale padding and other quad banks must remain invisible on flush.
        for(inputs<-7 to 9) {
          fill(d,maxQuads,inputs,5);start(d,0x1030,maxQuads,inputs,16)
          verify(d,capacity,0x1030,maxQuads,inputs,16,5)
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
      assert(verify(d,4,base,1,6,0,3).requests==8)
    }
  }

  // Local-only native A/B: one full-capacity factory per variant. Ordinary
  // PR/nightly runs retain the existing behavioral-SRAM test registrations.
  sys.props.get("opengpu.fragmentInputNextFieldQualification").foreach{variant=>
    require(Set("control","candidate")(variant),"qualification variant must be control or candidate")
    it should "qualify next-field input reads with exact native bytes split offers cancellation and final addresses" in {
      simulate(new FragmentInputNextFieldNative){d=>
        initialize(d)
        def requestCount(base: BigInt,quads: Int,inputs: Int,colors: Int): Int=
          (0 until inputs+2).map{f=>
            val address=base+(if(f<inputs)f else f+colors)*512
            if((address&63)+quads*16>64)2 else 1
          }.sum
        def operationDigest(operations: Vector[(BigInt,BigInt,BigInt)]): String={
          val rows=operations.map{case(address,data,mask)=>
            val enabled=(0 until 64).foldLeft(BigInt(0)){(v,b)=>
              if(mask.testBit(b))v|(BigInt(255)<<(8*b))else v}
            s"${address.toString(16)}:${mask.toString(16)}:${(data&enabled).toString(16)}"}
          java.security.MessageDigest.getInstance("SHA-256")
            .digest(rows.mkString("\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII))
            .map(b=>f"${b&255}%02x").mkString
        }
        var tag=1
        for(quads<-Seq(1,4);inputs<-Seq(6,10,70,74);base<-Seq(BigInt(0x1000),BigInt(0x1030))){
          fill(d,quads,inputs,tag);start(d,base,quads,inputs,16)
          val observed=verify(d,128,base,quads,inputs,16,tag,fixedReady=true)
          val fields=inputs+2;val requests=requestCount(base,quads,inputs,16)
          assert(observed.requests==requests)
          val controlClocks=3*fields+requests-fields
          val expectedSaved=fields-1
          val expectedClocks=controlClocks-(if(variant=="candidate")expectedSaved else 0)
          assert(observed.clocks==expectedClocks,
            s"next-field cycle gate failed: variant=$variant inputs=$inputs quads=$quads base=$base clocks=${observed.clocks} expected=$expectedClocks")
          info(s"FRAGMENT_INPUT_NEXT_FIELD variant=$variant capacity=128 maxInputWords=74 nativeSram=true quads=$quads inputs=$inputs colors=16 base=${base.toString(16)} clocks=${observed.clocks} expectedSaved=$expectedSaved requests=$requests enabledBytes=${quads*16*fields} operationSha256=${operationDigest(observed.operations)} exactBytes=true")
          tag=tag%7+1
        }
        // Original independent byte oracle under held offers, shrinking quad
        // groups, split lines and masked partial four-field stores.
        for((quads,index)<-(4 to 1 by -1).zipWithIndex){
          val inputs=if(index==0)74 else 6+4*index
          val colors=if(index%2==0)16 else 0
          val base=BigInt(0x1000)+16*(index%4)
          fill(d,quads,inputs,index+1);start(d,base,quads,inputs,colors)
          val observed=verify(d,128,base,quads,inputs,colors,index+1)
          assert(observed.requests==requestCount(base,quads,inputs,colors))
        }
        for(inputs<-7 to 9){
          fill(d,4,inputs,5);start(d,0x1030,4,inputs,16)
          verify(d,128,0x1030,4,inputs,16,5)
        }
        // Sweep through initial, split, next-field and terminal boundaries;
        // the same absolute offsets apply to both implementations.
        for(offset<-0 until 34){
          fill(d,4,6,1);start(d,0x1030,4,6,16)
          d.io.out.ready.poke((offset%2==0).B);d.clock.step(offset)
          d.io.cancel.poke(true.B);d.io.out.valid.expect(false.B);d.clock.step()
          d.io.cancel.poke(false.B);d.io.busy.expect(false.B);d.io.out.valid.expect(false.B)
          fill(d,1,6,2);start(d,0x1030,1,6,0)
          verify(d,128,0x1030,1,6,0,2)
        }
        val finalBase=(BigInt(1)<<32)-(7*512+16)
        fill(d,1,6,3);start(d,finalBase,1,6,0)
        assert(verify(d,128,finalBase,1,6,0,3).requests==8)
        for(_<-0 until 3){d.io.out.valid.expect(false.B);d.io.busy.expect(false.B);d.clock.step()}
        info("FRAGMENT_INPUT_NEXT_FIELD_GUARDS timingCases=16 nativeCapacity=128 maxInputWords=74 heldSplit=true partialStores=true cancelOffsets=34 finalExclusiveAddress=4294967296 finalFieldNoOffer=true healthyReuse=true")
      }
    }
  }
}
