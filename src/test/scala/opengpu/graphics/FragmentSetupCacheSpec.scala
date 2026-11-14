package opengpu.graphics

import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

/** Both paths retain four private lane services and all sixteen varying slots. */
private class FragmentSetupCachePair extends Module {
  private val p=VertexShaderStageConfig()
  private val g=GraphicsConfig(screenWidth=8,screenHeight=8)
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new FragmentInterpolationRequest(p,g)))
    val out=Vec(2,Decoupled(new PreparedFragmentInterpolation(p,g)))
    val cancel=Input(Bool());val busy=Output(Vec(2,Bool()))
  })
  private val paths=Seq(false,true).map(reuse=>Module(new Fp32FragmentPreparation(p,g,reuseSetup=reuse)))
  io.in.ready:=paths.map(_.io.in.ready).reduce(_ && _)
  for((path,i)<-paths.zipWithIndex){
    path.io.in.valid:=io.in.valid && io.in.ready;path.io.in.bits:=io.in.bits
    io.out(i)<>path.io.out;path.io.cancel:=io.cancel;io.busy(i):=path.io.busy
    assert(path.io.in.fire===io.in.fire)
  }
}

class FragmentSetupCacheSpec extends AnyFlatSpec {
  private def zero(data: Data): Unit=data match {
    case r: Record=>r.elements.values.foreach(zero)
    case v: Vec[_]=>v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case _=>()
  }
  private def fp(x: Double): BigInt=BigInt(java.lang.Float.floatToRawIntBits(x.toFloat).toLong&0xffffffffL)
  private val normalDepths=Vector(0.25,0.5,0.75).map(fp)
  private class Driver(val d: FragmentSetupCachePair) {
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    var sequence=0
    def input(kind: Int=2,area: BigInt=BigInt(1)<<22,depths: Vector[BigInt]=normalDepths,
        mode: Int=0,shift: Int=0,seed: Int=0,centroid: Boolean=false,towardZero: Boolean=false): Unit={
      zero(d.io.in.bits);sequence+=1
      val q=d.io.in.bits;val primitive=q.primitive.clipped.primitive
      def identity(id: GraphicsWorkIdentity): Unit={
        id.context.poke((sequence%31).U);id.pass.poke((sequence+3).U);id.generation.poke((sequence+9).U)
      }
      identity(primitive.identity);primitive.sequence.poke(sequence.U);primitive.primitiveId.poke((sequence+100).U)
      primitive.instanceId.poke((sequence+5).U);primitive.baseInstance.poke((sequence+7).U)
      primitive.kind.poke(kind.U);primitive.vertexCount.poke((kind+1).U)
      primitive.topology.poke((if(kind==0)0 else if(kind==1)1 else 4).U)
      primitive.provokingVertex.poke(kind.U);primitive.varyingSlots.poke(16.U)
      primitive.flatMask.poke(((BigInt(1)<<60)|8).U);q.primitive.clipped.partCount.poke(1.U)
      for(i<-0 until 3){
        val r=primitive.vertices(i);identity(r.identity)
        r.instanceId.poke((sequence+5).U);r.baseInstance.poke((sequence+7).U)
        for(c<-0 until 64)r.varyings(c/4)(c%4).poke((if(c==60)BigInt("ffc12345",16)else fp((seed+c+i*5)/64.0)).U)
        val v=q.primitive.vertices(i)
        v.reciprocalW.significand.poke("h800000".U);v.reciprocalW.exponent.poke((-i).S)
        v.windowDepth.poke(depths(i).U)
        v.screenX.poke((if(i==1)2048 else 0).S);v.screenY.poke((if(i==2)2048 else 0).S)
        v.fixedValid.poke(true.B)
      }
      q.pointSize.poke(fp(2).U);q.sampleMode.poke(mode.U);q.sampleDepthEnable.poke((mode!=0).B)
      q.centroidMask.poke((if(centroid)(BigInt(1)<<63)|1 else BigInt(0)).U)
      q.depthRoundTowardZero.poke(towardZero.B)
      for(i<-0 until 4){val lane=q.quad.lanes(i)
        lane.x.poke((shift+(i&1)).S);lane.y.poke((i>>1).S)
        // Exact conservation is independent of area rounding, including the
        // full signed64 endpoints. Ordinary sample cases use real gradients.
        val a=if(area==(BigInt(1)<<22) && kind==2)BigInt(2048)*(128+(shift+(i&1))*256)else BigInt(0)
        val b=if(area==(BigInt(1)<<22) && kind==2)BigInt(2048)*(128+(i>>1)*256)else BigInt(0)
        lane.e0.poke((area-a-b).S);lane.e1.poke(a.S);lane.e2.poke(b.S);lane.area.poke(area.S)
        lane.covered.poke(true.B);lane.coverageMask.poke((if(mode==0)1 else if(mode==1)3 else 15).U)
      }
    }
    def submit(): Unit={
      d.io.in.ready.expect(true.B);d.io.in.valid.poke(true.B);d.clock.step();d.io.in.valid.poke(false.B)
      zero(d.io.in.bits) // Both private snapshots must retain every live field.
    }
    def finish(status: Int=0,exact: Boolean=true): (Int,Int)={
      val clocks=Array.fill(2)(-1);val held=Array.fill[Option[BigInt]](2)(None)
      var n=0
      while(clocks.contains(-1) && n<3000){
        for(i<-0 until 2)if(d.io.out(i).valid.peek().litToBoolean){
          val bits=d.io.out(i).bits.peek().litValue
          held(i).foreach(x=>assert(bits==x,"prepared result changed while its peer was running"))
          held(i)=Some(bits);if(clocks(i)<0)clocks(i)=n
        }
        if(clocks.contains(-1)){d.clock.step();n+=1}
      }
      assert(!clocks.contains(-1),s"setup pair did not drain: ${clocks.toVector}")
      for(i<-0 until 2){
        d.io.out(i).bits.preparationStatus.expect(status.U)
        d.io.out(i).bits.quad.sequence.expect(sequence.U)
      }
      if(exact){
        def same(a: Data,b: Data,path: String): Unit={
          if(a.peek().litValue!=b.peek().litValue)(a,b) match {
            case (x: Record,y: Record)=>x.elements.foreach{case(name,v)=>same(v,y.elements(name),s"$path.$name")}
            case (x: Vec[_],y: Vec[_])=>x.zip(y).zipWithIndex.foreach{case((v,w),i)=>same(v,w,s"$path[$i]")}
            case _=>fail(s"cached defined field differs at sequence=$sequence status=$status $path: ${a.peek().litValue} != ${b.peek().litValue}")
          }
        }
        val a=d.io.out(0).bits;val b=d.io.out(1).bits
        a.elements.foreach{case(name,value)=>name match {
          // Existing preparation exports private scratch even when inactive.
          // Consumers use it only under centroidActive. Raw offers, including
          // these scratch bits, are still checked for held-offer stability.
          case "centroidWeights"|"centroidDenominators"=>
            for(i<-0 until 4 if a.centroidActive.peek().litValue.testBit(i))
              same(value.asInstanceOf[Vec[Data]](i),b.elements(name).asInstanceOf[Vec[Data]](i),s"$name[$i]")
          case _=>same(value,b.elements(name),name)
        }}
      }
      val snapshots=d.io.out.map(_.bits.peek().litValue)
      for(_<-0 until 5){d.clock.step();for(i<-0 until 2){
        d.io.out(i).valid.expect(true.B);assert(d.io.out(i).bits.peek().litValue==snapshots(i))
      }}
      d.io.out.foreach(_.ready.poke(true.B));d.clock.step();d.io.out.foreach(_.ready.poke(false.B))
      d.io.cancel.poke(false.B);d.clock.step();d.io.busy.foreach(_.expect(false.B))
      (clocks(0),clocks(1))
    }
    def run(expectedHit: Boolean,kind: Int=2,area: BigInt=BigInt(1)<<22,
        depths: Vector[BigInt]=normalDepths,mode: Int=0,shift: Int=0,seed: Int=0,
        centroid: Boolean=false,towardZero: Boolean=false): (Int,Int)={
      input(kind,area,depths,mode,shift,seed,centroid,towardZero);submit();val clocks=finish()
      if(expectedHit)assert(clocks._2<clocks._1,s"expected exact-key hit: $clocks")
      else assert(clocks._1==clocks._2,s"unexpected cache hit: $clocks")
      info(s"FRAGMENT_SETUP kind=$kind hit=$expectedHit uncached=${clocks._1} cached=${clocks._2} fullVaryings=16")
      clocks
    }
  }
  behavior of "primitive numerical setup reuse"
  it should "match defined prepared fields while distinguishing raw keys and current owned metadata" in {
    simulate(new FragmentSetupCachePair){d=>val t=new Driver(d)
      t.run(false);t.run(true,seed=7,shift=1)
      t.run(true,mode=2,centroid=true,seed=17)
      // Reciprocal W, flat qualifiers and all varying payload remain current
      // request state even when the pure depth/area setup key matches.
      t.input(seed=23);d.io.in.bits.primitive.vertices(1).reciprocalW.significand.poke("hc00000".U)
      d.io.in.bits.primitive.clipped.primitive.flatMask.poke((BigInt(1)<<63).U)
      t.submit();val current=t.finish();assert(current._2<current._1)
      t.input(mode=1,centroid=true,towardZero=true)
      d.io.in.bits.depthOffset.exponent.poke((-12).S);d.io.in.bits.depthOffset.significand.poke("h800000".U)
      t.submit();val offset=t.finish();assert(offset._2<offset._1)
      // A change in any raw depth word misses, even if the arithmetic result
      // could coincide. Repetition then hits with the new exact key.
      for(i<-0 until 3){val values=normalDepths.updated(i,fp((i+1)/8.0))
        t.run(false,depths=values);t.run(true,depths=values,seed=i)
      }
      for(i<-0 until 3){val positive=Vector.fill(3)(BigInt(0));val negative=positive.updated(i,BigInt(1)<<31)
        t.run(false,depths=positive);t.run(false,depths=negative);t.run(true,depths=negative)
      }
      for(area<-Seq((BigInt(1)<<53)+1,(BigInt(1)<<53)+2,BigInt(1)<<62,-(BigInt(1)<<62),-(BigInt(1)<<63))){
        t.run(false,area=area);t.run(true,area=area)
      }
      val inexact=Vector(fp(1),BigInt(1),fp(0.3))
      t.run(false,depths=inexact);t.run(true,depths=inexact,towardZero=true)
      t.run(false,kind=1);t.run(true,kind=1)
      val unused=normalDepths.updated(2,BigInt("7fc12345",16))
      t.run(false,kind=1,depths=unused);t.run(true,kind=1,depths=unused)
      t.run(false,kind=0);t.run(false,kind=0)
      t.run(true,kind=1,depths=unused) // points do not bind or invalidate a pure line entry
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      t.run(false);t.run(true)
    }
  }
  it should "validate every current quad before a hit and reuse cleanly after faults and cancellation" in {
    simulate(new FragmentSetupCachePair){d=>val t=new Driver(d)
      t.run(false);t.run(true)
      val rejects=Seq[()=>Unit](
        ()=>d.io.in.bits.primitive.clipped.partCount.poke(0.U),
        ()=>d.io.in.bits.primitive.vertices(1).reciprocalW.negative.poke(true.B),
        ()=>d.io.in.bits.quad.lanes(2).e0.poke(1.S),
        ()=>d.io.in.bits.quad.lanes(3).area.poke(1.S),
        ()=>d.io.in.bits.quad.lanes(0).covered.poke(false.B),
        ()=>d.io.in.bits.primitive.clipped.primitive.vertices(0).identity.context.poke(99.U),
        ()=>{d.io.in.bits.sampleDepthEnable.poke(true.B);d.io.in.bits.sampleMode.poke(3.U)},
        ()=>d.io.in.bits.depthOffset.special.poke(1.U))
      for(reject<-rejects){t.input();reject();t.submit();t.finish(1);t.run(true)}
      // Valid metadata and the same setup key, but current weights yield a
      // covered nonpositive denominator. Reuse must preserve the FP failure.
      t.input();val q=d.io.in.bits.quad.lanes(0);val area=BigInt(1)<<22
      q.e0.poke((-2*area).S);q.e1.poke((3*area).S);q.e2.poke(0.S)
      t.submit();t.finish(7);t.run(true)
      // Cache hits still cancel/drain current ownership. Operation counters
      // record reused logical setup once; same-clock cancellation can occur
      // after different progress on the two paths, so compare status/identity.
      for(cut<-Seq(0,1,3,4,5,6,9,15,31,64)){
        t.input(mode=2,centroid=true);t.submit();d.clock.step(cut)
        d.io.out.foreach(_.valid.expect(false.B))
        d.io.cancel.poke(true.B);d.clock.step();d.io.cancel.poke(false.B)
        t.finish(4,exact=false);t.run(true)
      }
      // A published immutable result stays owned and stable under cancel.
      t.input();t.submit();var n=0
      while(!d.io.out.forall(_.valid.peek().litToBoolean) && n<3000){d.clock.step();n+=1}
      assert(n<3000);d.io.cancel.poke(true.B);d.clock.step(4);t.finish();t.run(true)
    }
  }
}
