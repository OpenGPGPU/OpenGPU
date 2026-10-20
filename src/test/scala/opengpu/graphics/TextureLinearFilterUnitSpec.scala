package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

private[graphics] object TextureFilterReference {
  def bits(f: Float): BigInt=BigInt(java.lang.Float.floatToRawIntBits(f).toLong&0xffffffffL)
  def value(bits: BigInt): Float=java.lang.Float.intBitsToFloat(bits.toInt)
  private def computed(f: Float): BigInt=if(f.isNaN)BigInt(0x7fc00000L)else bits(f)
  def pair(a: BigInt,b: BigInt,w: BigInt): BigInt={
    if((w&0x7fffffffL)==0 || a==b)a else if(w==0x3f800000L)b else{
      val weight=value(w);val inverse=1.0f-weight;val product=value(a)*inverse
      computed(java.lang.Math.fma(weight,value(b),product))
    }
  }
  def filter(colors: Seq[Seq[BigInt]],weights: Seq[BigInt],axes: Int): Seq[BigInt]={
    var current=colors
    for(axis<-0 until axes)current=current.grouped(2).map(p=>p.head.zip(p(1)).map{case(a,b)=>pair(a,b,weights(axis))}).toVector
    current.head
  }
}
class TextureLinearFilterUnitSpec extends AnyFlatSpec {
  import TextureFilterReference._
  private class Driver(val d: TextureLinearFilterUnit) {
    WindowRecordTest.zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    var tag=BigInt(0);var last=Seq.empty[BigInt]
    def tick(): Unit=d.clock.step()
    def until(p: =>Boolean): Unit={var n=0;while(!p && n<10000){tick();n+=1};assert(p,"filter timeout")}
    def send(colors: Seq[Seq[BigInt]],weights: Seq[BigInt],axes: Int,t: BigInt=0xfedcba98L): Unit={
      val q=d.io.in.bits;WindowRecordTest.zero(q);tag=t
      for((color,i)<-colors.zipWithIndex;(v,c)<-color.zipWithIndex)q.values(i)(c).poke(v.U)
      weights.zipWithIndex.foreach{case(v,i)=>q.weights(i).poke(v.U)};q.axes.poke(axes.U);q.tag.poke(tag.U)
      d.io.in.valid.poke(true.B);until(d.io.in.ready.peek().litToBoolean);tick();d.io.in.valid.poke(false.B);WindowRecordTest.zero(q)
      last=if(axes<=4)filter(colors,weights,axes)else Seq.fill(4)(BigInt(0))
    }
    def result(status: Int=0): Unit={
      until(d.io.out.valid.peek().litToBoolean);val r=d.io.out.bits;r.tag.expect(tag.U);r.status.expect(status.U)
      r.rgba.zip(if(status==0)last else Seq.fill(4)(BigInt(0))).foreach{case(v,e)=>v.expect(e.U)}
      val held=r.peek().litValue;d.io.cancel.poke(true.B)
      for(_<-0 until 5){tick();assert(r.peek().litValue==held);d.io.in.ready.expect(false.B)}
      d.io.out.ready.poke(true.B);tick();d.io.out.ready.poke(false.B);d.io.cancel.poke(false.B);d.io.busy.expect(false.B)
    }
  }
  private def pad(colors: Seq[Seq[BigInt]]): Seq[Seq[BigInt]]=colors.padTo(16,Seq.fill(4)(BigInt(0)))
  it should "filter all axis counts against independent FP32 multiply and fused-add arithmetic" in {
    simulate(new TextureLinearFilterUnit()){d=>val t=new Driver(d);val random=new scala.util.Random(0x46494c54);var cases=0
      for(axes<-0 to 4;trial<-0 until 40){
        val colors=Vector.fill(16)(Vector.fill(4)(bits((random.nextInt(131009)-65504).toFloat/16.0f)))
        val weights=Vector.tabulate(4)(i=>if(trial%7==i)BigInt(0)else if(trial%11==i)bits(1.0f)else bits(random.nextFloat()))
        t.send(colors,weights,axes,0x80000000L+cases);t.result();cases+=1
      }
      info(s"TEXTURE_LINEAR_FILTER queries=$cases axes0to4=true colorsRGBA=true exactFP32ProductAndFMA=true nearestBilinearVolumeAndMipTrees=true poisonedInputAfterAccept=true heldResults5=true")
    }
  }
  it should "retain endpoint and duplicate bits while rejecting invalid active weights before arithmetic" in {
    simulate(new TextureLinearFilterUnit()){d=>val t=new Driver(d);var cases=0
      val special=Seq[BigInt](0,0x80000000L,1,0x80000001L,0x7f800000L,0xff800000L,0x7fc12345L,0xffc54321L)
      for(a<-special;b<-special;w<-Seq[BigInt](0,0x80000000L,0x3f800000L,0x3f000000L)){
        t.send(pad(Seq(Seq.fill(4)(a),Seq.fill(4)(b))),Seq(w,0,0,0),1);t.result();cases+=1
      }
      val colors=Vector.fill(16)(Vector[BigInt](0x7fc12345L,0x80000000L,0x7f800000L,0xff800000L))
      for(axis<-0 until 4;invalid<-Seq[BigInt](0xbf000000L,0x3f800001L,0x7f800000L,0x7fc12345L)){
        val weights=Vector.fill(4)(bits(0.25f)).updated(axis,invalid)
        // Invalid inputs have no numeric result oracle.
        val q=d.io.in.bits;WindowRecordTest.zero(q);q.axes.poke((axis+1).U);q.tag.poke(9.U)
        weights.zipWithIndex.foreach{case(v,i)=>q.weights(i).poke(v.U)};d.io.in.valid.poke(true.B)
        t.until(d.io.in.ready.peek().litToBoolean);t.tick();d.io.in.valid.poke(false.B);t.tag=9;t.result(6)
      }
      t.send(colors,Seq.fill(4)(BigInt(0x7fc12345L)),0);t.result()
      t.send(colors,Seq.fill(4)(BigInt(0)),7);t.result(6)
      info(s"TEXTURE_FILTER_SPECIAL queries=$cases endpointNaNPayloadAndSignedZero=true identicalInfinityBypass=true computedNaNCanonical=true invalidActiveWeights16=true unusedWeightsIgnored=true invalidAxisCount=true")
    }
  }
  it should "drain accepted arithmetic at cancellation cuts and preserve already held completion" in {
    simulate(new TextureLinearFilterUnit()){d=>val t=new Driver(d)
      val colors=Vector.tabulate(16)(i=>Vector.tabulate(4)(c=>bits((i*11-c*7-63).toFloat/64.0f)))
      val weights=Seq(bits(0.37f),bits(0.61f),bits(0.23f),bits(0.71f));var cancelled=0;var held=0
      for(cut<-(0 until 64)++Seq(96,128,256,512,1024,2048)){
        t.send(colors,weights,4,cut);for(_<-0 until cut if !d.io.out.valid.peek().litToBoolean)t.tick()
        val complete=d.io.out.valid.peek().litToBoolean;d.io.cancel.poke(true.B)
        t.result(if(complete)0 else 4);if(complete)held+=1 else cancelled+=1
        t.send(colors,weights,0,0x10000+cut);t.result()
      }
      assert(cancelled>0 && held>0)
      info(s"TEXTURE_FILTER_CANCEL cuts70=true cancelled=$cancelled held=$held acceptedFMANeverFlushed=true noPartialColor=true healthyReuse=true")
    }
  }
}
