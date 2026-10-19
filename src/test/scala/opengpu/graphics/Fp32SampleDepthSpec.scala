package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

/** Direct sample-position plane evaluation, independently of the RTL's center
  * edge plus derivative increment. Retain the existing scalar RNE sequence.
  */
private[graphics] object SampleDepthReference {
  import Fp32ViewportReference._
  import FragmentInterpolationReference.{Sample,edge}
  def linear(edges: Vector[BigInt],area: BigInt,depth: Vector[BigInt]): BigInt={
    val terms=edges.zip(depth).map{case(e,z)=>calc(edge(e),decode(z),2)}
    clamp(pack(calc(calc(calc(terms(0),terms(1),0),terms(2),0),edge(area),3)))
  }
  def depths(t: TriangleRasterReference.Triangle,kind: Int,pixels: Vector[Sample],mode: Int,g: GraphicsConfig): Vector[Vector[BigInt]]={
    val unit=BigInt(1)<<g.subPixelBits;val half=unit/2;val quarter=unit/4
    val offsets=mode match{case 0=>Vector((0,0));case 1=>Vector((-1,-1),(1,1));case _=>Vector((-1,-1),(1,-1),(-1,1),(1,1))}
    pixels.map { p=>Vector.tabulate(g.maxSampleCount){s=>
      if(s>=offsets.size || (p.mask&(1<<s))==0)BigInt(0)
      else if(kind==0)t.values.depth.head
      else {val(ox,oy)=offsets(s);val x=BigInt(p.x)*unit+half+ox*quarter;val y=BigInt(p.y)*unit+half+oy*quarter
        if(kind==2){val e=Vector.tabulate(3)(j=>TriangleRasterReference.edgeAt(t,j,x,y));linear(e,e.sum,t.values.depth)}
        else {val a=t.coords(0);val b=t.coords(1);val dx=b._1-a._1;val dy=b._2-a._2
          val length=dx*dx+dy*dy;val dot=(x-a._1)*dx+(y-a._2)*dy
          linear(Vector(length-dot,dot,BigInt(0)),length,t.values.depth.take(2).padTo(3,BigInt(0)))}
      }
    }}
  }
  def check(q: Fp32InterpolatedQuad,expected: Vector[Vector[BigInt]]): Unit={
    for((lane,values)<-q.lanes.zip(expected)){lane.sampleDepthValid.expect(true.B);lane.sampleDepth.zip(values).foreach{case(a,b)=>a.expect(b.U)}}
  }
}
class Fp32SampleDepthSpec extends AnyFlatSpec {
  import TriangleRasterReference.{base,Settings,primitive,state}
  private val p=VertexShaderStageConfig(VertexInputFetchConfig(1,1),2);private val g=GraphicsConfig(screenWidth=8,screenHeight=8)
  private def run(d: Fp32PrimitiveRasterStage,t: TriangleRasterReference.Triangle,kind: Int,mode: Int,expect: Boolean=true,cancelAfter: Option[Int]=None): Unit={
    PointLineReference.zero(d.io.in.bits);primitive(d.io.in.bits.primitive,t);state(d.io.in.bits.state,Settings(mode=mode))
    val a=d.io.in.bits.primitive.clipped.primitive;a.kind.poke(kind.U);a.vertexCount.poke((kind+1).U);a.provokingVertex.poke(kind.U);a.topology.poke((if(kind==0)0 else if(kind==1)1 else 4).U)
    if(kind==0){for((target,n)<-Seq(d.io.in.bits.primitive.vertices(0).localX->t.coords.head._1,d.io.in.bits.primitive.vertices(0).localY->t.coords.head._2)){
      val e=Fp32ViewportReference.scale(FragmentInterpolationReference.edge(n),-g.subPixelBits)
      target.negative.poke(e.sign.B);target.exponent.poke(e.exponent.S);target.significand.poke(e.sig.U)}}
    val quads=if(kind==2)TriangleRasterReference.raster(t,Settings(mode=mode),g)else PointLineReference.raster(t,kind,Settings(mode=mode),g)
    d.io.in.valid.poke(true.B);d.io.in.ready.expect(true.B);d.clock.step();d.io.in.valid.poke(false.B)
    var cycles=0;var accepted=0;var differences=0;var held: Option[BigInt]=None
    while(!d.io.completion.valid.peek().litToBoolean && cycles<150000){
      d.io.out.ready.poke((cycles%7<4).B)
      if(cancelAfter.contains(cycles))d.io.cancel.poke(true.B)
      if(d.io.out.valid.peek().litToBoolean){val bits=d.io.out.bits.peek().litValue;held.foreach(v=>assert(v==bits))
        if(d.io.out.ready.peek().litToBoolean){assert(accepted<quads.size);val q=quads(accepted)
          val depths=SampleDepthReference.depths(t,kind,q.samples,mode,g);SampleDepthReference.check(d.io.out.bits.values,depths)
          for(i<-0 until 4;s<-depths(i).indices if (q.samples(i).mask&(1<<s))!=0)if(depths(i)(s)!=q.lanes(i).depth)differences+=1
          accepted+=1;held=None}else held=Some(bits)}
      d.clock.step();cycles+=1
    }
    assert(cycles<150000);d.io.cancel.poke(false.B);d.io.completion.bits.status.expect((if(cancelAfter.nonEmpty)4 else 0).U)
    if(expect){assert(accepted==quads.size);if(kind!=0 && mode!=0)assert(differences>0,"non-flat sample depth collapsed to center")}
    val heldResult=d.io.completion.bits.peek().litValue;d.clock.step(4);assert(d.io.completion.bits.peek().litValue==heldResult)
    d.io.completion.ready.poke(true.B);d.clock.step();d.io.completion.ready.poke(false.B)
  }
  it should "evaluate sloped triangle line and point depth at every covered sample without changing center data" in {
    simulate(new Fp32PrimitiveRasterStage(p,g)){d=>PointLineReference.zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      for(kind<-0 to 2;mode<-0 to 2){val t=base.copy(coords=Vector((BigInt(128),BigInt(128)),(BigInt(1792),BigInt(512)),(BigInt(256),BigInt(1792))))
        run(d,t,kind,mode)}
      val reversed=base.copy(coords=Vector(base.coords(0),base.coords(2),base.coords(1)),values=base.values.copy(depth=base.values.depth.reverse))
      run(d,reversed,2,2)
      info("SAMPLE_DEPTH primitiveKinds=3 modes=3 signedArea=true directSamplePlane=true defaultSamplesDifferFromCenter=true pointsConstant=true uncoveredSamplesZero=true heldResult=true")
    }
  }
  it should "retain arithmetic and quad ownership when cancellation interrupts multisample depth work" in {
    simulate(new Fp32PrimitiveRasterStage(p,g)){d=>PointLineReference.zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      for(offset<-Seq(40,75,120,200))run(d,base,2,2,expect=false,cancelAfter=Some(offset))
      run(d,base,2,2)
      info("SAMPLE_DEPTH cancelOffsets=4 sampleArithmeticDrains=true noWithdrawnQuad=true immutableCompletion=true healthyReuse=true")
    }
  }
}
