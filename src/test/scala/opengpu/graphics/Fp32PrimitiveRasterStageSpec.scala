package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.util.Random

class Fp32PrimitiveRasterStageSpec extends AnyFlatSpec {
  import TriangleRasterReference.{Triangle,Quad,Settings,base,primitive,state,check}
  import PointLineReference._
  import Fp32ViewportReference.fp
  private val p=VertexShaderStageConfig(VertexInputFetchConfig(1,1),2);private val g=GraphicsConfig(screenWidth=8,screenHeight=8)
  private class Driver(val d: Fp32PrimitiveRasterStage){
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B);var cycle=0;var accepted=0;var kind=0
    var expected=Vector.empty[Quad];var settings=Settings();var sizes=Sizes();var source=base;var exactLocal: Option[(Fp32ViewportReference.E,Fp32ViewportReference.E)]=None;var origin=(0,0);var hold=false;var held: Option[BigInt]=None
    def tick(): Unit={d.io.out.ready.poke((!hold && cycle%7<5).B)
      if(d.io.out.valid.peek().litToBoolean){val bits=d.io.out.bits.peek().litValue;held.foreach(v=>assert(v==bits))
        if(d.io.out.ready.peek().litToBoolean){assert(accepted<expected.size);val e=expected(accepted)
          assert(d.io.out.bits.values.lanes(0).x.peek().litValue==e.samples.head.x && d.io.out.bits.values.lanes(0).y.peek().litValue==e.samples.head.y,
            s"quad mismatch kind=$kind sampleMode=${settings.mode} sizes=$sizes coords=${source.coords} index=$accepted actual=${d.io.out.bits.values.lanes.map(v=>(v.x.peek().litValue,v.y.peek().litValue,v.coverageMask.peek().litValue))} expected=${e.samples}")
          check(d.io.out.bits,e,settings)
          SampleDepthReference.check(d.io.out.bits.values,SampleDepthReference.depths(source,kind,e.samples,settings.mode,g))
          d.io.out.bits.values.primitiveKind.expect(kind.U)
          for(i<-0 until 4;c<-0 until 2){val want=if(kind==0)exactLocal.map(v=>exactPointCoord(v,origin,e.samples(i).x,e.samples(i).y,sizes)(c)).getOrElse(pointCoord(source.coords.head,e.samples(i).x,e.samples(i).y,sizes,g)(c))else BigInt(0)
            d.io.out.bits.values.lanes(i).pointCoord(c).expect(want.U)}
          accepted+=1;held=None}else held=Some(bits)
      }else assert(held.isEmpty)
      d.clock.step();cycle+=1}
    def until(q: => Boolean): Unit={var n=0;while(!q && n<200000){tick();n+=1};assert(q,s"primitive owner stalled cycle=$cycle accepted=$accepted")}
    def prepare(k: Int,t: Triangle=base,s: Settings=Settings(),z: Sizes=Sizes(),model: Boolean=true): Unit={
      exactLocal=None;origin=(0,0);kind=k;settings=s;sizes=z;source=t;accepted=0;expected=if(model)raster(t,k,s,g,z)else Vector.empty
      zero(d.io.in.bits);primitive(d.io.in.bits.primitive,t);state(d.io.in.bits.state,s)
      d.io.in.bits.state.pointSize.poke(fp(z.point).U);d.io.in.bits.state.lineWidth.poke(fp(z.line).U)
      val a=d.io.in.bits.primitive.clipped.primitive;a.kind.poke(k.U);a.topology.poke((if(k==0)0 else if(k==1)1 else 4).U)
      a.vertexCount.poke((k+1).U);a.provokingVertex.poke(k.U)
      for(i<-0 to k){val v=d.io.in.bits.primitive.vertices(i)
        for((target,n)<-Seq(v.localX->t.coords(i)._1,v.localY->t.coords(i)._2)){
          val e=Fp32ViewportReference.scale(FragmentInterpolationReference.edge(n),-g.subPixelBits)
          target.negative.poke(e.sign.B);target.exponent.poke(e.exponent.S);target.significand.poke(e.sig.U);target.special.poke(0.U)
        }}
    }
    def submit(): Unit={d.io.in.valid.poke(true.B);until(d.io.in.ready.peek().litToBoolean);tick();d.io.in.valid.poke(false.B)}
    def finish(status: Int=0): Unit={until(d.io.completion.valid.peek().litToBoolean);val r=d.io.completion.bits;r.status.expect(status.U);r.outputQuads.expect(accepted.U)
      if(status==0){assert(accepted==expected.size);val pixels=expected.flatMap(_.samples);r.coveredLanes.expect(pixels.count(_.mask!=0).U);r.coveredSamples.expect(pixels.map(x=>Integer.bitCount(x.mask)).sum.U)}
      val held=r.peek().litValue;for(_<-0 until 6){tick();d.io.busy.expect(true.B);assert(r.peek().litValue==held)}
      d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B);d.io.busy.expect(false.B)}
    def run(k: Int,t: Triangle=base,s: Settings=Settings(),z: Sizes=Sizes()): Unit={prepare(k,t,s,z);submit();finish()}
  }
  it should "interpolate point data and coordinates exactly and perspective line data at all sample modes with helpers and raw flat values" in {
    simulate(new Fp32PrimitiveRasterStage(p,g)){d=>val t=new Driver(d);val rng=new Random(0x1ee8);var draws=0
      for(k<-0 to 1;mode<-0 to 2;i<-0 until 5){val coords=Vector.fill(3)((BigInt(rng.nextInt(10*256)-256),BigInt(rng.nextInt(10*256)-256)))
        val tri=base.copy(coords=coords);t.run(k,tri,Settings(mode=mode,cull=2),Sizes(point=1.5+i,line=1.0+i));draws+=1}
      // Shader point size overrides the draw state after primitive/viewport transport.
      val point=base.copy(coords=Vector.fill(3)((BigInt(896),BigInt(1024))))
      t.prepare(0,point,z=Sizes(point=3.25));d.io.in.bits.state.pointSize.poke(fp(1).U)
      d.io.in.bits.primitive.clipped.primitive.vertices(0).pointSizeWritten.poke(true.B)
      d.io.in.bits.primitive.clipped.primitive.vertices(0).pointSize.poke(fp(3.25).U);t.submit();t.finish()
      for(topology<-Seq(2,3)){t.prepare(1,base.copy(coords=Vector((BigInt(256),BigInt(512)),(BigInt(1536),BigInt(1024)),(BigInt(0),BigInt(0)))))
        d.io.in.bits.primitive.clipped.primitive.topology.poke(topology.U);t.submit();t.finish()}
      info(s"PRIMITIVE_RASTER pointLineDraws=$draws modes=3 exactPointValues=true pointCoord=true shaderPointSize=true perspectiveLine=true lineTopologies=3 rawFlatNaN=true helpers=true facingAlwaysFront=true cullIgnored=true")
    }
  }
  it should "derive point coordinates from unrounded window positions without losing exact large viewport origins" in {
    simulate(new Fp32PrimitiveRasterStage(p,g)){d=>val t=new Driver(d)
      val cases=Vector((Vector.fill(3)((BigInt(341),BigInt(683))),Vector(1.3333333,2.6666667),(0,0)),
        (Vector.fill(3)((BigInt(0),BigInt(0))),Vector(math.scalb(1.0,31),math.scalb(1.0,31)),(Int.MinValue,Int.MinValue)))
      for((coords,values,origin)<-cases){t.prepare(0,base.copy(coords=coords),z=Sizes(point=4))
        val local=values.map(v=>Fp32ViewportReference.decode(fp(v)));t.exactLocal=Some((local(0),local(1)));t.origin=origin
        val q=d.io.in.bits.primitive;q.viewport.x.poke(origin._1.S);q.viewport.y.poke(origin._2.S)
        for((target,e)<-Seq(q.vertices(0).localX->local(0),q.vertices(0).localY->local(1))){target.negative.poke(e.sign.B);target.exponent.poke(e.exponent.S);target.significand.poke(e.sig.U)}
        t.submit();t.finish()
      }
      info("PRIMITIVE_RASTER unroundedPointCoord=true fractionalWindow=true exactLargeIntegerOrigin=true noOriginCancellationLoss=true")
    }
  }
  it should "reject invalid sizes metadata and unsafe numeric overlap while culling signed64 outside bounds without narrowing" in {
    simulate(new Fp32PrimitiveRasterStage(p,g)){d=>val t=new Driver(d)
      for(change<-Seq[()=>Unit](()=>d.io.in.bits.state.lineWidth.poke(fp(-1).U),()=>d.io.in.bits.state.pointSize.poke(BigInt("7fc00000",16).U),
        ()=>d.io.in.bits.primitive.clipped.primitive.vertexCount.poke(3.U),()=>d.io.in.bits.primitive.clipped.primitive.topology.poke(6.U))){t.prepare(1,model=false);change();t.submit();t.finish(1)}
      val far=base.copy(coords=base.coords.map{case(x,y)=>(x+(BigInt(1)<<40),y+(BigInt(1)<<40))})
      t.prepare(1,far,model=false);t.submit();t.finish()
      val unsafe=base.copy(coords=Vector((-(BigInt(1)<<40),BigInt(0)),(BigInt(1)<<40,BigInt(256)),(BigInt(0),BigInt(0))))
      t.prepare(1,unsafe,model=false);t.submit();t.finish(7)
      t.run(0)
      info("PRIMITIVE_RASTER invalidSizes=true invalidMetadata=true signed64BoundsCull=true checkedNarrowing=true numericFault=true healthyReuse=true")
    }
  }
  it should "retain offered quads and drain point-coordinate arithmetic or line coverage on cancellation" in {
    simulate(new Fp32PrimitiveRasterStage(p,g)){d=>val t=new Driver(d)
      for(k<-0 to 1){t.prepare(k,base.copy(coords=Vector((BigInt(256),BigInt(256)),(BigInt(1536),BigInt(1536)),(BigInt(0),BigInt(0)))),Settings(mode=2),Sizes(point=3))
        t.hold=true;t.submit();t.until(d.io.out.valid.peek().litToBoolean);d.io.cancel.poke(true.B);for(_<-0 until 9)t.tick();t.hold=false;t.finish(4);d.io.cancel.poke(false.B)
        t.prepare(k);t.submit();for(_<-0 until 7)t.tick();d.io.cancel.poke(true.B);t.tick();d.io.cancel.poke(false.B);t.finish(4)}
      t.run(0)
      info("PRIMITIVE_RASTER heldPointLineQuads=true cancelledCoordinateMath=true cancelledCoverage=true immutableCompletion=true drainBeforeReuse=true")
    }
  }
}
