package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class Fp32StreamingFragmentInterpolatorSpec extends AnyFlatSpec {
  import Fp32ViewportReference.{decode,fp,pack}
  import FragmentInterpolationReference.{Data => Values,Sample,lane}
  import TriangleRasterReference.{base,primitive,zero}
  private val p=VertexShaderStageConfig()
  // Explicit A/B selection changes only pure numerical setup reuse. Both
  // variants retain the same raw owners and physical component bank.
  private val setupQualification=sys.props.get("opengpu.fragmentSetupQualification")
  require(setupQualification.forall(Set("control","cached")), "unknown fragment setup qualification mode")
  private val zeroQualification=sys.props.get("opengpu.fragmentZeroQualification")
  require(zeroQualification.forall(Set("control","fast")), "unknown fragment finite-zero qualification mode")
  require(setupQualification.isEmpty || zeroQualification.isEmpty, "select one fragment qualification experiment")
  private val reuseSetup= !setupQualification.contains("control")
  private val finiteZeroShortcut= !zeroQualification.contains("control")
  private def owner(contexts: Int=4)=new Fp32StreamingFragmentInterpolator(p,g,contexts,reuseSetup,finiteZeroShortcut)
  private val g=GraphicsConfig(screenWidth=8,screenHeight=8)
  private val samples=Vector.tabulate(4)(i=>Sample(Vector(3-i,1+i,2).map(BigInt(_)),6,
    if(i==3)0 else 1,i&1,i>>1))
  private def values(id: Int)=Values(Vector(1.0,0.5,0.25).map(v=>decode(fp(v))),
    Vector(0.25,0.5,0.75).map(fp),Vector.tabulate(3,64)((v,c)=>fp((id+v*5+c-23)/64.0)),0,16)
  private class Driver(val d: Fp32StreamingFragmentInterpolator) {
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    var cycle=0;val outputs=mutable.ArrayBuffer.empty[Int];val completions=mutable.ArrayBuffer.empty[Int]
    var activeSlots=16;var activeFlat=BigInt(0);var sampleMode=0;var centroidQualifiers=BigInt(0)
    var rasterSamples=false;var orthographicPlane=false
    private def terms(id: Int)={val v=values(id)
      v.copy(slots=activeSlots,flat=activeFlat,
        inv=if(orthographicPlane)Vector.fill(3)(decode(fp(1.0)))else v.inv,
        depth=if(orthographicPlane)Vector.fill(3)(fp(0.5))else v.depth)}
    private def triangle(id: Int)=base.copy(values=terms(id))
    private def pixels(id: Int)=if(rasterSamples)
      TriangleRasterReference.raster(triangle(id),TriangleRasterReference.Settings(mode=sampleMode),g).head.samples else samples
    val outputCycles=mutable.ArrayBuffer.empty[Int]
    val completionCycles=mutable.ArrayBuffer.empty[Int]
    private val quadDigest=java.security.MessageDigest.getInstance("SHA-256")
    private val completionDigest=java.security.MessageDigest.getInstance("SHA-256")
    private def record(digest: java.security.MessageDigest,bits: BigInt): Unit=
      digest.update((bits.toString(16)+"\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII))
    def qualification(profile: String="full64"): Unit=if(setupQualification.nonEmpty || zeroQualification.nonEmpty){
      def hex(digest: java.security.MessageDigest)=digest.digest().map(b=>f"${b&255}%02x").mkString
      val prefix=if(zeroQualification.nonEmpty)"ZERO_STREAM"else"SETUP_STREAM"
      val mode=zeroQualification.orElse(setupQualification).get
      info(s"$prefix mode=$mode totalClocks=$cycle outputClocks=${outputCycles.mkString(",")} completionClocks=${completionCycles.mkString(",")} quadSha256=${hex(quadDigest)} completionSha256=${hex(completionDigest)} profile=$profile activeSlots=$activeSlots sampleMode=$sampleMode flatMask=${activeFlat.toString(16)} centroidMask=${centroidQualifiers.toString(16)} orthographicPlane=$orthographicPlane")
    }
    var consume=false;var consumeCompletion=false;var cancelled=false
    val rejected=mutable.Set.empty[Int]
    var heldQuad=Option.empty[BigInt];var heldCompletion=Option.empty[BigInt]
    def tick(): Unit={
      d.io.out.ready.poke(consume.B);d.io.completion.ready.poke(consumeCompletion.B)
      if(d.io.out.valid.peek().litToBoolean){
        val bits=d.io.out.bits.peek().litValue;heldQuad.foreach(v=>assert(v==bits,"held quad changed"))
        if(consume){
          val id=d.io.out.bits.sequence.peek().litValue.toInt;outputs+=id;outputCycles+=cycle
          record(quadDigest,bits)
          val pp=pixels(id);val tt=triangle(id)
          val center=pp.map(lane(terms(id),_))
          val raw=TriangleRasterReference.Quad(0,0,true,pp,center)
          val reference=CentroidRasterReference(tt,2,raw,sampleMode,centroidQualifiers,g).lanes
          for(i<-0 until 4){val o=d.io.out.bits.lanes(i);val e=reference(i)
            o.x.expect(pp(i).x.S);o.y.expect(pp(i).y.S);o.windowDepth.expect(e.depth.U)
            o.fragCoordW.expect(pack(e.reciprocal).U);o.perspectiveValid.expect(e.valid.B)
            for(c<-0 until 64)o.varyings(c/4)(c%4).expect(e.values(c).U)
          }
          if(sampleMode!=0)SampleDepthReference.check(d.io.out.bits,
            SampleDepthReference.depths(tt,2,pp,sampleMode,g))
          heldQuad=None
        }else heldQuad=Some(bits)
      }else assert(heldQuad.isEmpty,"held quad withdrawn")
      if(d.io.completion.valid.peek().litToBoolean){
        val bits=d.io.completion.bits.peek().litValue;heldCompletion.foreach(v=>assert(v==bits,"held completion changed"))
        if(consumeCompletion){
          val r=d.io.completion.bits;val id=r.sequence.peek().litValue.toInt
          completions+=id;completionCycles+=cycle;record(completionDigest,bits);r.status.expect((if(cancelled)4 else if(rejected.contains(id))1 else 0).U)
          r.output.expect(outputs.contains(id).B);heldCompletion=None
        }else heldCompletion=Some(bits)
      }else assert(heldCompletion.isEmpty,"held completion withdrawn")
      d.clock.step();cycle+=1
    }
    def until(q: => Boolean): Unit={var n=0;while(!q && n<20000){tick();n+=1};assert(q,s"streaming owner stalled at $cycle")}
    def offer(id: Int,reject: Boolean=false): Unit={
      zero(d.io.in.bits);primitive(d.io.in.bits.primitive,triangle(id),id)
      d.io.in.bits.sampleMode.poke(sampleMode.U);d.io.in.bits.sampleDepthEnable.poke((sampleMode!=0).B)
      d.io.in.bits.centroidMask.poke(centroidQualifiers.U)
      if(reject){d.io.in.bits.primitive.clipped.partCount.poke(0.U);rejected+=id}
      for((s,i)<-pixels(id).zipWithIndex){val q=d.io.in.bits.quad.lanes(i)
        q.x.poke(s.x.S);q.y.poke(s.y.S);q.e0.poke(s.edges(0).S);q.e1.poke(s.edges(1).S);q.e2.poke(s.edges(2).S)
        q.area.poke(s.area.S);q.covered.poke((s.mask!=0).B);q.coverageMask.poke(s.mask.U)
      }
      d.io.in.valid.poke(true.B);until(d.io.in.ready.peek().litToBoolean);tick();d.io.in.valid.poke(false.B)
      d.io.in.bits.primitive.clipped.primitive.sequence.poke(0xdead.U)
    }
  }
  it should "own four raw quads before output and recycle credits in order under independent stalls" in {
    simulate(owner()){d=>val t=new Driver(d)
      for(id<-1 to 4)t.offer(id)
      assert(t.cycle==4,"four preparation owners did not admit successive raw quads")
      d.io.in.ready.expect(false.B);t.until(d.io.out.valid.peek().litToBoolean)
      for(_<-0 until 150)t.tick()
      t.consume=true;t.until(d.io.completion.valid.peek().litToBoolean)
      for(_<-0 until 37)t.tick()
      t.consumeCompletion=true
      for(id<-5 to 16)t.offer(id)
      t.until(t.completions.size==16);d.io.busy.expect(false.B)
      assert(t.outputs.toVector==(1 to 16).toVector && t.completions.toVector==(1 to 16).toVector)
      // Budget the registered geometry/normalization stages as well as issue.
      val groupPeriods=(4 until 12).map(i=>t.outputCycles(i+4)-t.outputCycles(i))
      assert(groupPeriods.forall(_<=400),s"four-lane preparation rate regressed: $groupPeriods")
      info(s"STREAMING_FRAGMENT quads=16 componentsPerQuad=64 consecutiveAdmission=4 preparationServices=16 orderedRetirement=true steadyFourQuadPeriodMax=${groupPeriods.max} rateBound=400")
      t.qualification()
    }
  }
  it should "retain full hardware capacity while sweeping legal active slots and native sample qualifiers" in {
    // All cases elaborate one identical maximum16/4context DUT. The product
    // profiles match the six frame recipes' active2 slots, smooth mask zero,
    // centroid mask zero and single/four-sample modes, not their full images.
    val profiles=(Seq(1,2,4,8,16).map(n=>(s"slots$n",n,0,BigInt(0),BigInt(0))) ++ Seq(
      ("product2x4",2,2,BigInt(0),BigInt(0)),
      ("centroidLow",1,2,BigInt(0),BigInt(1)),
      ("centroidHigh",16,2,BigInt(8),(BigInt(1)<<63)|1),
      ("centroidMixedFlat",2,2,BigInt(0x84),BigInt(0xff))))
      .map{case(name,slots,mode,flat,centroid)=>(name,slots,mode,flat,centroid,false)} ++ Seq(
        ("plane2",2,0,BigInt(0),BigInt(0),true),
        ("plane2x4",2,2,BigInt(0),BigInt(0),true))
    simulate(owner()){d=>
      for((name,slots,mode,flat,centroid,plane)<-profiles){
        val t=new Driver(d) // identical cold reset before each independent A/B
        t.activeSlots=slots;t.activeFlat=flat;t.sampleMode=mode;t.centroidQualifiers=centroid;t.rasterSamples=true;t.orthographicPlane=plane
        for(id<-1 to 4)t.offer(id)
        assert(t.cycle==4,"active counts changed raw-owner capacity")
        d.io.in.ready.expect(false.B);t.until(d.io.out.valid.peek().litToBoolean)
        for(_<-0 until 150)t.tick()
        t.consume=true;t.until(d.io.completion.valid.peek().litToBoolean)
        for(_<-0 until 37)t.tick()
        t.consumeCompletion=true
        for(id<-5 to 16)t.offer(id)
        t.until(t.completions.size==16);d.io.busy.expect(false.B)
        assert(t.outputs.toVector==(1 to 16).toVector && t.completions.toVector==(1 to 16).toVector)
        val periods=(4 until 12).map(i=>t.outputCycles(i+4)-t.outputCycles(i))
        info(s"SETUP_ACTIVE profile=$name maximumSlots=16 contexts=4 activeSlots=$slots sampleMode=$mode orthographicPlane=$plane steadyFourQuadPeriodMax=${periods.max}")
        t.qualification(name)
      }
    }
  }
  it should "sustain the component bank with eight raw quad contexts and thirty-two preparation services" in {
    simulate(owner(contexts=8)){d=>val t=new Driver(d)
      for(id<-1 to 8)t.offer(id)
      assert(t.cycle==8,"eight preparation owners did not admit successive raw quads")
      d.io.in.ready.expect(false.B);t.until(d.io.out.valid.peek().litToBoolean)
      for(_<-0 until 31)t.tick()
      t.consume=true;t.consumeCompletion=true
      for(id<-9 to 32)t.offer(id)
      t.until(t.completions.size==32);d.io.busy.expect(false.B)
      assert(t.outputs.toVector==(1 to 32).toVector && t.completions.toVector==(1 to 32).toVector)
      val periods=(8 until 28).map(i=>t.outputCycles(i+4)-t.outputCycles(i))
      assert(periods.forall(_<=272),s"eight-context preparation starved the component bank: $periods")
      info(s"STREAMING_FRAGMENT quads=32 componentsPerQuad=64 consecutiveAdmission=8 preparationServices=32 steadyFourQuadPeriodMax=${periods.max} rateBound=272")
    }
  }
  it should "retire rejected raw quads around healthy work in the eight-context profile" in {
    simulate(owner(contexts=8)){d=>val t=new Driver(d)
      for(id<-1 to 8)t.offer(id,reject=id==3 || id==7)
      t.until(d.io.out.valid.peek().litToBoolean);for(_<-0 until 31)t.tick()
      t.consume=true;t.consumeCompletion=true;t.until(t.completions.size==8)
      assert(t.outputs.toVector==Vector(1,2,4,5,6,8) && t.completions.toVector==(1 to 8).toVector)
      d.io.busy.expect(false.B);t.offer(9);t.until(t.completions.size==9)
      assert(t.outputs.last==9 && t.completions.last==9);d.io.busy.expect(false.B)
    }
  }
  it should "drain cancellation and reset every owner in the eight-context native profile" in {
    simulate(owner(contexts=8)){d=>val t=new Driver(d)
      for(id<-1 to 8)t.offer(id)
      t.cancelled=true;d.io.cancel.poke(true.B);t.tick();d.io.cancel.poke(false.B)
      t.consume=true;t.consumeCompletion=true;t.until(t.completions.size==8)
      assert(t.outputs.isEmpty && t.completions.toVector==(1 to 8).toVector);d.io.busy.expect(false.B)
      t.tick();t.cancelled=false;t.offer(9);t.until(t.completions.size==9)
      assert(t.outputs.toVector==Vector(9));d.io.busy.expect(false.B)
      t.consume=false;t.consumeCompletion=false
      for(id<-10 to 17)t.offer(id)
      t.until(d.io.out.valid.peek().litToBoolean);t.tick()
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B);t.heldQuad=None;t.heldCompletion=None
      for(_<-0 until 100){d.io.out.valid.expect(false.B);d.io.completion.valid.expect(false.B);d.io.busy.expect(false.B);t.tick()}
      t.consume=true;t.consumeCompletion=true;t.offer(18);t.until(t.completions.size==10)
      assert(t.outputs.toVector==Vector(9,18) && t.completions.last==18);d.io.busy.expect(false.B)
    }
  }
  it should "drain a one-cycle cancellation across all reserved preparations and permit clean reuse" in {
    simulate(owner()){d=>val t=new Driver(d)
      for(id<-1 to 4)t.offer(id)
      t.cancelled=true;d.io.cancel.poke(true.B);t.tick();d.io.cancel.poke(false.B)
      t.consume=true;t.consumeCompletion=true;t.until(t.completions.size==4)
      assert(t.outputs.isEmpty && t.completions.toVector==Vector(1,2,3,4));d.io.busy.expect(false.B)
      t.tick();t.cancelled=false;t.offer(5);t.until(t.completions.size==5)
      assert(t.outputs.toVector==Vector(5));d.io.busy.expect(false.B)
    }
  }
  it should "retain an offered head quad while cancellation drains later reserved quads" in {
    simulate(owner()){d=>val t=new Driver(d)
      for(id<-1 to 4)t.offer(id)
      t.until(d.io.out.valid.peek().litToBoolean);t.tick()
      t.cancelled=true;d.io.cancel.poke(true.B);t.tick();d.io.cancel.poke(false.B)
      for(_<-0 until 23)t.tick()
      t.consume=true;t.consumeCompletion=true;t.until(t.completions.size==4)
      assert(t.outputs.toVector==Vector(1) && t.completions.toVector==Vector(1,2,3,4));d.io.busy.expect(false.B)
      // Every preparation has completed pure setup by the first held head.
      // Cancellation must drain current owners while retaining no task binding
      // in those numerical entries. Wrap all four with fresh healthy payload.
      t.tick();t.cancelled=false
      for(id<-5 to 8)t.offer(id)
      t.until(t.completions.size==8);d.io.busy.expect(false.B)
      assert(t.outputs.toVector==Vector(1,5,6,7,8) && t.completions.toVector==(1 to 8).toVector)
    }
  }
  it should "reset all raw and prepared owners without ghost output before clean reuse" in {
    simulate(owner()){d=>val t=new Driver(d)
      for(id<-1 to 4)t.offer(id)
      for(_<-0 until 970)t.tick()
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      t.heldQuad=None;t.heldCompletion=None
      for(_<-0 until 100){
        d.io.out.valid.expect(false.B);d.io.completion.valid.expect(false.B);d.io.busy.expect(false.B)
        t.tick()
      }
      t.consume=true;t.consumeCompletion=true;t.offer(5);t.until(t.completions.size==1)
      assert(t.outputs.toVector==Vector(5) && t.completions.toVector==Vector(5));d.io.busy.expect(false.B)
    }
  }
}
