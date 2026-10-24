package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class StreamingTypedColorEncoderSpec extends AnyFlatSpec {
  import TypedColorReference._
  private case class Request(id: Int,kind: Int,rgba: Seq[BigInt],mask: Int,tag: Int)
  private def request(name: String,rgba: Seq[BigInt],mask: Int,index: Int): Request=
    Request(names.indexOf(name),format(name).kind,rgba,mask,index&255)
  private def reset(d: StreamingTypedColorEncoder): Unit={
    d.io.in.valid.poke(false.B);d.io.out.ready.poke(false.B)
    d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    d.io.out.valid.expect(false.B);d.io.busy.expect(false.B)
  }
  private def drive(d: StreamingTypedColorEncoder,r: Request): Unit={
    d.io.in.bits.format.poke(r.id.U);d.io.in.bits.colorType.poke(r.kind.U)
    d.io.in.bits.mask.poke(r.mask.U);d.io.in.bits.tag.poke(r.tag.U)
    d.io.in.bits.rgba.zip(r.rgba).foreach{case(a,b)=>a.poke(b.U)}
  }
  private def run(d: StreamingTypedColorEncoder,requests: Seq[Request],stalled: Boolean): Unit={
    val pending=mutable.Queue.empty[(Request,Int)]
    var sent=0;var received=0;var cycle=0;var offering=false
    var held=Option.empty[BigInt];var heldClocks=0;var inputStalls=0
    while(received<requests.size && cycle<requests.size*8+200){
      if(sent<requests.size && !offering && (!stalled || cycle%7!=0))offering=true
      if(offering)drive(d,requests(sent))
      else drive(d,Request(63,3,Seq.fill(4)(BigInt("deadbeef",16)),0,255))
      d.io.in.valid.poke(offering.B)
      val ready= !stalled || (cycle>=85 && cycle%11<7)
      d.io.out.ready.poke(ready.B)
      val valid=d.io.out.valid.peek().litToBoolean
      held.foreach{value=>assert(valid);assert(d.io.out.bits.peek().litValue==value)}
      if(valid){
        assert(pending.nonEmpty,"reply without accepted owner")
        val(r,accepted)=pending.front
        val invalid=r.id>=names.size || r.kind!=format(names(r.id)).kind
        val(value,mask)=if(invalid)(BigInt(0),BigInt(0))else encode(names(r.id),r.rgba,r.mask)
        d.io.out.bits.value.expect(value.U);d.io.out.bits.mask.expect(mask.U)
        d.io.out.bits.bytes.expect((if(invalid)0 else format(names(r.id)).bytes).U)
        d.io.out.bits.status.expect((if(invalid)1 else 0).U);d.io.out.bits.tag.expect(r.tag.U)
        if(!stalled)assert(cycle-accepted==10,s"latency ${cycle-accepted}, expected ten clocks")
        if(ready){pending.dequeue();received+=1}
      }
      held=if(valid && !ready){heldClocks+=1;Some(d.io.out.bits.peek().litValue)}else None
      if(offering && d.io.in.ready.peek().litToBoolean){pending.enqueue((requests(sent),cycle));sent+=1;offering=false}
      else if(offering)inputStalls+=1
      d.clock.step();cycle+=1
    }
    assert(received==requests.size && sent==requests.size && pending.isEmpty)
    d.io.in.valid.poke(false.B);d.io.out.ready.poke(true.B);d.clock.step(2)
    d.io.out.valid.expect(false.B);d.io.busy.expect(false.B)
    if(stalled)assert(heldClocks>60 && inputStalls>0)
    else assert(cycle==requests.size+10,"dense input/output must sustain II=1")
    info(s"STREAMING_TYPED_ENCODE requests=$sent cycles=$cycle latency=10 denseII1=${!stalled} heldClocks=$heldClocks inputStalls=$inputStalls exactMasksTagsAndBytes=true")
  }
  it should "encode all formats and every UNORM and sRGB transition with dense ordered admission" in {
    assert(names==TypedColorFormat.formats.map(_.name))
    val all=mutable.ArrayBuffer.empty[Request];val random=new scala.util.Random(0x5e764L)
    def add(name: String,values: Seq[BigInt],mask: Int=15): Unit=all+=request(name,values,mask,all.size)
    val directed=Seq(0L,0x80000000L,0x3f000000L,0x3f800000L,0xbf800000L,
      0x7f800000L,0xff800000L,0x7fc01234L,0xffffffffL,0x3effffffL,0x3f000001L,0x477ff000L)
    for(name<-names){
      for(i<-0 until 16)add(name,Seq.tabulate(4)(c=>BigInt(directed((i+c)%directed.size))),i)
      for(_<-0 until 24)add(name,Seq.fill(4)(BigInt(32,random)),random.nextInt(16))
    }
    for(name<-Seq("RGB5_A1","RGB10_A2","RGBA4","RGB565","RGBA8");
        bits<-format(name).widths.distinct.filter(_>0);q<-0 until ((1<<bits)-1)){
      val boundary=fp((q+0.5)/((1<<bits)-1))
      for(delta<- -1 to 1)add(name,Seq.fill(4)(boundary+delta))
    }
    for(i<-0 until 255){
      val y=(i+0.5)/255;val x=if(y<=0.04045)y/12.92 else math.pow((y+0.055)/1.055,2.4)
      for(delta<- -1 to 1)add("SRGB8_ALPHA8",Seq.fill(4)(fp(x)+delta))
    }
    for(id<-0 until 64;kind<-0 until 4 if id>=36 || kind!=format(names(id)).kind)
      all+=Request(id,kind,Seq.fill(4)(fp(0.5)),15,all.size&255)
    simulate(new StreamingTypedColorEncoder){d=>reset(d);run(d,all.toSeq,stalled=false)}
  }
  it should "retain queued colors through held output and input gaps and flush ownership on reset" in {
    val random=new scala.util.Random(0x5e765L)
    val requests=Seq.tabulate(768){i=>val name=names(i%names.size)
      request(name,Seq.fill(4)(BigInt(32,random)),i%16,i)}
    simulate(new StreamingTypedColorEncoder){d=>
      reset(d)
      for(i<-0 until 7){drive(d,requests(i));d.io.in.valid.poke(true.B);d.io.in.ready.expect(true.B);d.clock.step()}
      d.io.busy.expect(true.B);reset(d)
      run(d,requests,stalled=true)
    }
  }
}
