package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class StreamingRgba8EncoderSpec extends AnyFlatSpec {
  import TypedColorReference._
  private case class Work(name: String,values: Vector[BigInt],mask: Int,tag: Int,invalid: Boolean=false)
  private val special=Vector(0L,0x80000000L,1L,0x7fffffL,0x00800000L,0x3f000000L,0x3f7fffffL,0x3f800000L,0x7f800000L,0xff800000L,0x7fc00001L).map(BigInt(_))
  private val boundaries=(0 until 255).toVector.flatMap{n=>val raw=fp((n+0.5)/255.0);Vector(raw-1,raw,raw+1)}
  private val values=boundaries++special++Vector.tabulate(512)(n=>fp(n/511.0))
  private def work(i: Int,name: String="RGBA8"): Work=Work(name,Vector.tabulate(4)(c=>values((i+c*127)%values.size)),if(i<values.size)15 else i&15,i&255)
  private def offer(io: TypedColorEncoderIO,w: Work): Unit={
    io.in.valid.poke(true.B);io.in.bits.format.poke(TypedColorFormat.id(w.name).U)
    io.in.bits.colorType.poke((if(w.invalid)3 else format(w.name).kind).U)
    io.in.bits.mask.poke(w.mask.U);io.in.bits.tag.poke(w.tag.U)
    io.in.bits.rgba.zip(w.values).foreach{case(a,b)=>a.poke(b.U)}
  }
  private def check(io: TypedColorEncoderIO,w: Work): Unit={
    val(v,m)=if(w.invalid)(BigInt(0),BigInt(0))else encode(w.name,w.values,w.mask)
    io.out.bits.value.expect(v.U);io.out.bits.mask.expect(m.U);io.out.bits.tag.expect(w.tag.U)
    io.out.bits.bytes.expect((if(w.invalid)0 else format(w.name).bytes).U)
    io.out.bits.status.expect((if(w.invalid)1 else 0).U)
  }
  it should "pack a complete pixel every clock across all UNORM8 boundaries and channel masks" in {
    simulate(new StreamingRgba8Encoder){d=>
      d.io.in.valid.poke(false.B);d.io.out.ready.poke(true.B);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val n=4096;var received=0;var previous= -1
      for(cycle<-0 until n+4){
        if(cycle<n){offer(d.io,work(cycle));d.io.in.ready.expect(true.B)}else d.io.in.valid.poke(false.B)
        if(d.io.out.valid.peek().litToBoolean){
          check(d.io,work(received));if(previous>=0)assert(cycle==previous+1)
          previous=cycle;received+=1
        }
        d.clock.step()
      }
      assert(received==n);d.io.busy.expect(false.B)
      info(s"STREAMING_RGBA8_PACK pixels=$n admissionII=1 responseII=1 channels=4 allUNORM8Boundaries=true masks=16 exactRNE=true")
    }
  }
  it should "hold four owned pixels under backpressure and preserve invalid requests and reuse" in {
    simulate(new StreamingRgba8Encoder){d=>
      d.io.in.valid.poke(false.B);d.io.out.ready.poke(false.B);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      for(i<-0 until 4){offer(d.io,work(i));d.io.in.ready.expect(true.B);d.clock.step()}
      offer(d.io,work(4));d.io.in.ready.expect(false.B);d.io.out.valid.expect(true.B)
      val held=d.io.out.bits.peek().litValue
      for(_<-0 until 8){d.clock.step();assert(d.io.out.bits.peek().litValue==held);d.io.in.ready.expect(false.B)}
      var sent=4;var received=0;var cycles=0;var last: Option[BigInt]=None
      while(received<512 && cycles<2048){
        val ready=cycles%5<3;d.io.out.ready.poke(ready.B)
        if(sent<512)offer(d.io,work(sent).copy(invalid=sent%17==0))else d.io.in.valid.poke(false.B)
        if(d.io.out.valid.peek().litToBoolean){
          val bits=d.io.out.bits.peek().litValue;last.foreach(x=>assert(x==bits))
          check(d.io,work(received).copy(invalid=received>=4 && received%17==0))
          if(ready){received+=1;last=None}else last=Some(bits)
        }
        if(sent<512 && d.io.in.ready.peek().litToBoolean)sent+=1
        d.clock.step();cycles+=1
      }
      assert(received==512);d.io.busy.expect(false.B)
    }
  }
  it should "route ordered streaming cohorts and every general format through held replies" in {
    simulate(new RoutedTypedColorEncoder){d=>
      d.io.in.valid.poke(false.B);d.io.out.ready.poke(false.B);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val tasks=Vector.tabulate(128)(i=>work(i))++names.zipWithIndex.flatMap{case(name,i)=>Vector(work(i+128,name),work(i+200),work(i+300,name))}
      var sent=0;var received=0;var cycles=0;var held: Option[BigInt]=None
      while(received<tasks.size && cycles<20000){
        val ready=cycles%7<4;d.io.out.ready.poke(ready.B)
        if(sent<tasks.size)offer(d.io,tasks(sent))else d.io.in.valid.poke(false.B)
        if(d.io.out.valid.peek().litToBoolean){
          val bits=d.io.out.bits.peek().litValue;held.foreach(x=>assert(bits==x))
          check(d.io,tasks(received))
          if(ready){received+=1;held=None}else held=Some(bits)
        }
        if(sent<tasks.size && d.io.in.ready.peek().litToBoolean)sent+=1
        d.clock.step();cycles+=1
      }
      assert(received==tasks.size);d.io.busy.expect(false.B)
    }
  }
}
