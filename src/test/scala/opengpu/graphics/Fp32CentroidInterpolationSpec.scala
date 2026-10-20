package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class Fp32CentroidInterpolationSpec extends AnyFlatSpec {
  import Fp32ViewportReference._
  import FragmentInterpolationReference.{Sample, lane}
  import TriangleRasterReference.{Triangle, Quad, base, primitive, zero}
  private val p=VertexShaderStageConfig(VertexInputFetchConfig(1,1),2)
  private val g=GraphicsConfig(screenWidth=8,screenHeight=8)
  private val qualifiers=BigInt(0x5d) // Mix smooth center, centroid and raw flat components.

  /** Evaluate at the selected absolute sample position, not center-edge deltas. */
  private def atSample(t: Triangle,kind: Int,s: Sample,mode: Int): Sample={
    if(mode==0 || s.mask==0 || kind==0)return s
    val index=Integer.numberOfTrailingZeros(s.mask)
    val x=BigInt(s.x)*256+128+(if((index&1)==0)-64 else 64)
    val row=if(mode==1)index else index>>1
    val y=BigInt(s.y)*256+128+(if((row&1)==0)-64 else 64)
    val edges=if(kind==2)Vector.tabulate(3)(TriangleRasterReference.edgeAt(t,_,x,y)) else {
      val a=t.coords(0);val b=t.coords(1);val dx=b._1-a._1;val dy=b._2-a._2
      val length=dx*dx+dy*dy;val dot=(x-a._1)*dx+(y-a._2)*dy
      Vector(length-dot,dot,BigInt(0))
    }
    s.copy(edges=edges,area=edges.sum)
  }
  private class Driver(val d: Fp32FragmentInterpolator) {
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    var sequence=0;var differences=0;var helperChecks=0;var lastOfferCycle=0
    def prepare(t: Triangle,q: Quad,kind: Int,mode: Int,mask: BigInt=qualifiers): Unit={
      zero(d.io.in.bits);sequence+=1;val in=d.io.in.bits
      primitive(in.primitive,t,sequence);val a=in.primitive.clipped.primitive
      a.kind.poke(kind.U);a.vertexCount.poke((kind+1).U);a.topology.poke((if(kind==0)0 else if(kind==1)1 else 4).U);a.provokingVertex.poke(kind.U)
      in.pointSize.poke(fp(1).U);in.sampleDepthEnable.poke(true.B);in.sampleMode.poke(mode.U);in.centroidMask.poke(mask.U)
      if(kind==0){for((target,value)<-Seq(in.primitive.vertices(0).localX->t.coords.head._1,in.primitive.vertices(0).localY->t.coords.head._2)){
        val e=scale(FragmentInterpolationReference.edge(value),-8)
        target.negative.poke(e.sign.B);target.exponent.poke(e.exponent.S);target.significand.poke(e.sig.U)}}
      for((s,i)<-q.samples.zipWithIndex){val v=in.quad.lanes(i)
        v.x.poke(s.x.S);v.y.poke(s.y.S);v.e0.poke(s.edges(0).S);v.e1.poke(s.edges(1).S);v.e2.poke(s.edges(2).S);v.area.poke(s.area.S)
        v.covered.poke((s.mask!=0).B);v.coverageMask.poke(s.mask.U)}
    }
    def submit(): Unit={
      d.io.in.valid.poke(true.B);d.io.in.ready.expect(true.B);d.clock.step();d.io.in.valid.poke(false.B)
      // Mutating unoffered inputs cannot replace the accepted qualifier/sample state.
      d.io.in.bits.centroidMask.poke(0.U);d.io.in.bits.sampleMode.poke(3.U)
    }
    def check(t: Triangle,q: Quad,kind: Int,mode: Int,mask: BigInt): Unit={
      d.io.out.bits.sequence.expect(sequence.U)
      val sampleDepth=SampleDepthReference.depths(t,kind,q.samples,mode,g)
      SampleDepthReference.check(d.io.out.bits,sampleDepth)
      for((s,i)<-q.samples.zipWithIndex){
        val center=lane(t.values,s);val centroid=lane(t.values,atSample(t,kind,s,mode));val out=d.io.out.bits.lanes(i)
        out.x.expect(s.x.S);out.y.expect(s.y.S);out.coverageMask.expect(s.mask.U);out.covered.expect((s.mask!=0).B)
        out.windowDepth.expect(center.depth.U);out.fragCoordW.expect(pack(center.reciprocal).U)
        out.sampleLocationValid.expect(false.B)
        Fp32ViewportReference.expect(out.reciprocalW,center.reciprocal);out.perspectiveValid.expect(center.valid.B)
        for(c<-t.values.values.head.indices){
          val selected=if(mask.testBit(c) && !t.values.flat.testBit(c))centroid else center
          out.varyings(c/4)(c%4).expect(selected.values(c).U)
          if(selected.values(c)!=center.values(c))differences+=1
          if(s.mask==0)helperChecks+=1
        }
      }
    }
    def retire(status: Int,output: Boolean): Unit={
      var n=0;while(!d.io.completion.valid.peek().litToBoolean && n<150000){d.clock.step();n+=1};assert(n<150000)
      d.io.completion.bits.status.expect(status.U);d.io.completion.bits.output.expect(output.B)
      d.io.completion.bits.sequence.expect(sequence.U)
      val held=d.io.completion.bits.peek().litValue;d.clock.step(5);assert(held==d.io.completion.bits.peek().litValue)
      d.io.completion.ready.poke(true.B);d.clock.step();d.io.completion.ready.poke(false.B);d.io.busy.expect(false.B)
    }
    def run(t: Triangle,q: Quad,kind: Int,mode: Int,mask: BigInt=qualifiers,cancelHeld: Boolean=false): Unit={
      prepare(t,q,kind,mode,mask);submit();d.io.out.ready.poke(false.B)
      var n=0;while(!d.io.out.valid.peek().litToBoolean && !d.io.completion.valid.peek().litToBoolean && n<150000){d.clock.step();n+=1}
      assert(n<150000);d.io.out.valid.expect(true.B);check(t,q,kind,mode,mask)
      lastOfferCycle=n
      val held=d.io.out.bits.peek().litValue
      if(cancelHeld)d.io.cancel.poke(true.B)
      for(_<-0 until 5){d.clock.step();d.io.out.valid.expect(true.B);assert(held==d.io.out.bits.peek().litValue)}
      d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)
      retire(if(cancelHeld)4 else 0,true);d.io.cancel.poke(false.B)
    }
  }

  it should "evaluate mixed centroid center and flat inputs at every covered sample choice without changing depth or helpers" in {
    simulate(new Fp32FragmentInterpolator(p,g)){d=>val driver=new Driver(d)
      var cases=0
      for(reverse<-Seq(false,true);mode<-0 to 2){
        val t=if(reverse)base.copy(coords=Vector(base.coords(0),base.coords(2),base.coords(1)))else base
        val q=TriangleRasterReference.raster(t,TriangleRasterReference.Settings(mode=mode),g).find(q=>q.x==2 && q.y==2).get
        for(mask<-0 until (1<<(1<<mode))){
          val pixels=q.samples.zipWithIndex.map{case(s,i)=>s.copy(mask=if(i==3)0 else mask)}
          driver.run(t,q.copy(samples=pixels),2,mode);cases+=1
        }
      }
      for(kind<-0 to 1;mode<-0 to 2){
        val t=base.copy(coords=Vector((BigInt(896),BigInt(896)),(BigInt(1792),BigInt(1280)),(BigInt(0),BigInt(0))))
        val q=PointLineReference.raster(t,kind,TriangleRasterReference.Settings(mode=mode),g).head
        driver.run(t,q,kind,mode);cases+=1
      }
      assert(driver.differences>0 && driver.helperChecks>0)
      info(s"CENTROID_INTERPOLATION quads=$cases modes=3 allCoverageMasks=true bothWindings=true primitiveKinds=3 directAbsoluteSampleReference=true mixedQualifiers=true flatNaNBits=true centerReciprocalAndDepth=true coveredSampleDepth=true helpersAtCenter=true snapshot=true heldOutput=true changedValues=${driver.differences}")
    }
  }

  it should "reject invalid qualifier masks and sample modes and drain cancelled centroid arithmetic before exact reuse" in {
    simulate(new Fp32FragmentInterpolator(p,g)){d=>val driver=new Driver(d)
      val q=TriangleRasterReference.raster(base,TriangleRasterReference.Settings(mode=2),g).find(q=>q.x==2 && q.y==2).get
      for(which<-0 until 3){
        driver.prepare(base,q,2,2)
        if(which==0){d.io.in.bits.primitive.clipped.primitive.varyingSlots.poke(1.U);d.io.in.bits.primitive.clipped.primitive.flatMask.poke(0.U)}
        if(which==1)d.io.in.bits.sampleMode.poke(3.U)
        if(which==2){d.io.in.bits.sampleDepthEnable.poke(false.B);d.io.in.bits.sampleMode.poke(0.U)}
        driver.submit();driver.retire(1,false)
      }
      driver.run(base,q,2,2)
      val latency=driver.lastOfferCycle
      for(cut<-Seq(latency/5,latency/2,latency*3/4,latency*9/10)){
        driver.prepare(base,q,2,2);driver.submit();d.clock.step(cut);d.io.cancel.poke(true.B)
        driver.retire(4,false);d.io.cancel.poke(false.B)
      }
      driver.run(base,q,2,2,cancelHeld=true);driver.run(base,q,2,2)
      info("CENTROID_OWNERSHIP invalidRequests=3 cancelCuts=4 heldQuadCancel=true immutableCompletion=true noPartialOutput=true exactHealthyReuse=true")
    }
  }

  it should "retain the highest qualifier bit and every value in the sixty four component interface" in {
    val full=VertexShaderStageConfig(VertexInputFetchConfig(1,1),16)
    simulate(new Fp32FragmentInterpolator(full,g)){d=>val driver=new Driver(d)
      val t=base.copy(values=base.values.copy(slots=16,flat=BigInt(1)<<63,
        values=Vector.tabulate(3,64)((v,c)=>if(c==63)BigInt("ffc01234",16)else fp((v+1)*(c+0.25)))))
      val raw=TriangleRasterReference.raster(t,TriangleRasterReference.Settings(mode=2),g).find(q=>q.x==2 && q.y==2).get
      val q=raw.copy(samples=raw.samples.zipWithIndex.map{case(s,i)=>s.copy(mask=Vector(8,4,2,0)(i))})
      driver.run(t,q,2,2,(BigInt(1)<<63)|(BigInt(1)<<62)|1)
      assert(driver.differences>0)
      info("CENTROID_CAPACITY components=64 highestQualifierBit=true highCentroidAndFlat=true exactRawFlatNaN=true noTruncatedValues=true")
    }
  }

  it should "retain partial coverage when the center crosses a perspective pole but a covered sample is valid" in {
    simulate(new Fp32FragmentInterpolator(p,g)){d=>val driver=new Driver(d)
      for(inv<-Seq(Vector(2.0,0.25,0.25),Vector(12.0,7.0,7.0));mode<-1 to 2;reverse<-Seq(false,true);mask<-Seq(BigInt(0),qualifiers)){
        val small=base.copy(coords=Vector((32,32),(112,32),(32,112)).map{case(x,y)=>(BigInt(x),BigInt(y))},
          values=base.values.copy(inv=inv.map(v=>decode(fp(v)))))
        val t=if(reverse)small.copy(coords=Vector(small.coords(0),small.coords(2),small.coords(1)))else small
        val q=TriangleRasterReference.raster(t,TriangleRasterReference.Settings(mode=mode),g).head
        assert(q.samples.head.mask==1 && !lane(t.values,q.samples.head).valid)
        val selected=lane(t.values,atSample(t,2,q.samples.head,mode));assert(selected.valid)
        driver.prepare(t,q,2,mode,mask);driver.submit();d.io.out.ready.poke(true.B)
        var cycles=0;var outputs=0
        while(!d.io.completion.valid.peek().litToBoolean && cycles<150000){
          if(d.io.out.valid.peek().litToBoolean){
            val value=d.io.out.bits.lanes(0);value.fragCoordW.expect(pack(selected.reciprocal).U)
            value.sampleLocationValid.expect(true.B);value.sampleLocation.expect(0.U)
            value.windowDepth.expect(selected.depth.U);value.perspectiveValid.expect(true.B)
            for(c<-0 until 8)value.varyings(c/4)(c%4).expect(selected.values(c).U)
            SampleDepthReference.check(d.io.out.bits,SampleDepthReference.depths(t,2,q.samples,mode,g));outputs+=1
          }
          d.clock.step();cycles+=1
        }
        assert(cycles<150000);driver.retire(0,true);assert(outputs==1);d.io.out.ready.poke(false.B)
      }
      info("CENTROID_PARTIAL_PERSPECTIVE draws=16 modes=2 windings=2 qualifierMasks=2 negativeAndZeroCenter=true coveredSamplePositive=true noDroppedFragment=true exactFallbackValues=true")
    }
  }
}
