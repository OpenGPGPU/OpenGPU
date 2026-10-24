package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class StreamingTypedColorDecoderSpec extends AnyFlatSpec {
  private case class Request(format: Int,value: BigInt,tag: Int)
  private def run(d: StreamingTypedColorDecoder,requests: Seq[Request],stalls: Boolean): Unit={
    d.io.in.valid.poke(false.B);d.io.out.ready.poke(false.B)
    d.io.in.bits.format.poke(0.U);d.io.in.bits.value.poke(0.U);d.io.in.bits.tag.poke(0.U);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    val expected=mutable.Queue.empty[(Request,Int)]
    var sent=0;var retired=0;var cycle=0;var held: Option[BigInt]=None
    var offer: Option[Request]=None;var inputStalls=0;var heldClocks=0;var peak=0
    var lastReply= -1;var consecutive=0;var longest=0
    while(retired<requests.size && cycle<requests.size*12+100){
      if(offer.isEmpty && sent<requests.size && (!stalls || cycle%5!=2))offer=Some(requests(sent))
      d.io.in.valid.poke(offer.nonEmpty.B)
      val input=offer.getOrElse(Request(63,(BigInt(1)<<128)-1,255))
      d.io.in.bits.format.poke(input.format.U);d.io.in.bits.value.poke(input.value.U);d.io.in.bits.tag.poke(input.tag.U)
      val ready= !stalls || cycle%17>=7
      d.io.out.ready.poke(ready.B)
      if(held.nonEmpty){d.io.out.valid.expect(true.B);assert(d.io.out.bits.peek().litValue==held.get)}
      if(d.io.out.valid.peek().litToBoolean){
        assert(expected.nonEmpty,"decoder produced an unowned result")
        val(request,accepted)=expected.front;val valid=request.format<TypedColorFormat.formats.size
        d.io.out.bits.tag.expect(request.tag.U);d.io.out.bits.status.expect((if(valid)0 else 1).U)
        val values=if(valid)TypedBlendReference.decode(TypedColorFormat.formats(request.format).name,request.value)else Vector.fill(4)(BigInt(0))
        d.io.out.bits.colorType.expect((if(valid)TypedColorFormat.formats(request.format).kind else 0).U)
        d.io.out.bits.rgba.zip(values).foreach{case(actual,want)=>actual.expect(want.U)}
        if(!stalls)assert(cycle-accepted==2,s"unexpected dense latency ${cycle-accepted}")
        if(ready){
          expected.dequeue();retired+=1;consecutive=if(lastReply==cycle-1)consecutive+1 else 1
          longest=longest.max(consecutive);lastReply=cycle;held=None
        }else{held=Some(d.io.out.bits.peek().litValue);heldClocks+=1}
      }else held=None
      if(offer.nonEmpty){
        if(d.io.in.ready.peek().litToBoolean){expected.enqueue((offer.get,cycle));offer=None;sent+=1}
        else{inputStalls+=1;assert(stalls,"dense decoder did not sustain one vector per clock")}
      }
      peak=peak.max(expected.size);assert(expected.size<=2,"decoder exceeded its two owned stages")
      d.clock.step();cycle+=1
    }
    assert(sent==requests.size && retired==sent && expected.isEmpty)
    if(stalls)assert(inputStalls>0 && heldClocks>0)else assert(longest==requests.size)
    d.io.in.valid.poke(false.B);d.io.out.ready.poke(true.B);d.clock.step(3)
    d.io.out.valid.expect(false.B);d.io.busy.expect(false.B)
    info(s"STREAMING_TYPED_DECODE requests=$sent stalls=$stalls clocks=$cycle consecutiveReplies=$longest peakOwned=$peak heldClocks=$heldClocks exactValuesAndTags=true")
  }
  it should "stream every half pattern normalized code and all typed formats with exact two-clock latency" in {
    val random=new scala.util.Random(0xdec0deL)
    val packed=for((format,index)<-TypedColorFormat.formats.zipWithIndex;i<-0 until 16)yield(index,BigInt(128,random))
    val half=(0 until 65536).map{h=>
      val value=BigInt(h)|(BigInt(h^0x8000)<<16)|(BigInt((h+1)&65535)<<32)|(BigInt(h^0x3c00)<<48)|(BigInt(64,random)<<64)
      (TypedColorFormat.id("RGBA16F"),value)
    }
    val unorm=for((name,c,bits)<-Seq(("RGB5_A1",3,1),("RGB10_A2",3,2),("RGBA4",0,4),
      ("RGB565",2,5),("RGB565",1,6),("RGBA8",0,8),("RGB10_A2",0,10));raw<-0 until(1<<bits))
      yield(TypedColorFormat.id(name),BigInt(raw)<<TypedColorReference.format(name).offsets(c))
    val srgb=(0 until 256).map(i=>(TypedColorFormat.id("SRGB8_ALPHA8"),
      (0 until 4).foldLeft(BigInt(0))((v,c)=>v|(BigInt((i+17*c)%256)<<(8*c)))))
    val invalid=(36 until 64).map(id=>(id,BigInt(128,random)))
    val requests=(packed++half++unorm++srgb++invalid).zipWithIndex.map{case((format,value),tag)=>Request(format,value,tag%256)}
    simulate(new StreamingTypedColorDecoder)(d=>run(d,requests,stalls=false))
  }
  it should "retain independent mixed-format owners through backpressure input gaps and invalid offers" in {
    val random=new scala.util.Random(0x57a11L)
    val requests=(0 until 768).map(i=>Request(i%64,BigInt(128,random),(i*37)%256))
    simulate(new StreamingTypedColorDecoder)(d=>run(d,requests,stalls=true))
  }
}
