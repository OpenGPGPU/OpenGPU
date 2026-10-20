package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class PolygonDepthOffsetRasterSpec extends AnyFlatSpec {
  import Fp32ViewportReference.{E,fp,decode,calc,pack,clamp,rational}
  import FragmentInterpolationReference.{Sample,edge}
  import TriangleRasterReference.{Triangle,Quad,Settings,primitive,state}
  private val p=VertexShaderStageConfig(VertexInputFetchConfig(1,1),2)
  private val g=GraphicsConfig(screenWidth=8,screenHeight=8)
  private val source=TriangleRasterReference.base.copy(values=FragmentInterpolationReference.Data(
    Vector.fill(3)(decode(fp(1))),Vector(0.25,0.5,0.75).map(fp),Vector.fill(3)(Vector.fill(8)(BigInt(0))),0,1))
  private def linear(t: Triangle,s: Sample): E={
    val terms=s.edges.zip(t.values.depth).map{case(e,z)=>calc(edge(e),decode(z),2)}
    calc(calc(calc(terms(0),terms(1),0),terms(2),0),edge(s.area),3)
  }
  private def offset(t: Triangle,factor: BigInt,units: BigInt,resolution: BigInt): E={
    val area=TriangleRasterReference.edgeAt(t,0,t.coords(0)._1,t.coords(0)._2)
    val slopes=Vector.tabulate(2){axis=>
      val terms=Vector.tabulate(3){i=>val a=t.coords((i+1)%3);val b=t.coords((i+2)%3)
        calc(edge((if(axis==0)a._2-b._2 else b._1-a._1)*256),decode(t.values.depth(i)),2)}
      calc(calc(calc(terms(0),terms(1),0),terms(2),0),edge(area),3).copy(sign=false)
    }
    val(xn,xd)=rational(slopes(0));val(yn,yd)=rational(slopes(1))
    val maximum=if(xn*yd>yn*xd)slopes(0)else slopes(1)
    calc(calc(maximum,decode(factor),2),calc(decode(resolution),decode(units),2),0)
  }
  private class Driver(val d: Fp32PrimitiveRasterStage){
    PointLineReference.zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    var cycle=0;var accepted=0;var expected=Vector.empty[Quad];var current=source;var mode=0;var kind=2
    var bias=E();var hold=false;var held: Option[BigInt]=None;var unclampedWitness=0
    def biased(value: E): BigInt=clamp(pack(if(bias.sig==0)value else calc(value,bias,0)))
    def check(q: Quad): Unit={
      val o=d.io.out.bits.values
      for(i<-0 until 4){val a=o.lanes(i);val s=q.samples(i)
        a.x.expect(s.x.S);a.y.expect(s.y.S);a.coverageMask.expect(s.mask.U);a.covered.expect((s.mask!=0).B)
        val raw=linear(current,s);a.windowDepth.expect(biased(raw).U);a.sampleDepthValid.expect(true.B)
        if(bias.sig!=0 && biased(raw)!=clamp(pack(calc(decode(clamp(pack(raw))),bias,0))))unclampedWitness+=1
        for(c<-0 until 8)a.varyings(c/4)(c%4).expect(q.lanes(i).values(c).U)
        for(sample<-0 until 4){val want=if((s.mask&(1<<sample))==0)BigInt(0)
          else if(mode==0)biased(raw)
          else if(kind==2)biased(linear(current,CentroidRasterReference.sample(current,kind,s.copy(mask=1<<sample),mode,g)))
          else SampleDepthReference.depths(current,kind,q.samples,mode,g)(i)(sample)
          a.sampleDepth(sample).expect(want.U)
        }
      }
    }
    def tick(): Unit={
      d.io.out.ready.poke((!hold && cycle%7<4).B)
      if(d.io.out.valid.peek().litToBoolean){val bits=d.io.out.bits.peek().litValue;held.foreach(v=>assert(v==bits,"held biased quad changed"))
        if(d.io.out.ready.peek().litToBoolean){assert(accepted<expected.size);check(expected(accepted));accepted+=1;held=None}
        else held=Some(bits)
      }else assert(held.isEmpty)
      d.clock.step();cycle+=1
    }
    def until(condition: =>Boolean): Unit={var n=0;while(!condition && n<250000){tick();n+=1};assert(condition,s"offset raster timeout cycle=$cycle accepted=$accepted")}
    def prepare(t: Triangle=source,sampleMode: Int=0,enabled: Boolean=true,factor: BigInt=fp(2),units: BigInt=fp(3),
        resolution: BigInt=fp(1.0/256),primitiveKind: Int=2,cull: Int=0,model: Boolean=true): Unit={
      current=t;mode=sampleMode;kind=primitiveKind;accepted=0;held=None
      val settings=Settings(mode=mode,cull=cull)
      expected=if(!model)Vector.empty else if(kind==2)TriangleRasterReference.raster(t,settings,g)else PointLineReference.raster(t,kind,settings,g)
      bias=if(model && enabled && kind==2)offset(t,factor,units,resolution)else E()
      PointLineReference.zero(d.io.in.bits);primitive(d.io.in.bits.primitive,t);state(d.io.in.bits.state,settings)
      val a=d.io.in.bits.primitive.clipped.primitive;a.kind.poke(kind.U);a.vertexCount.poke((kind+1).U)
      a.topology.poke((if(kind==1)1 else 4).U);a.provokingVertex.poke(kind.U)
      val b=d.io.in.bits.depthOffset;b.enabled.poke(enabled.B);b.factor.poke(factor.U);b.units.poke(units.U);b.resolution.poke(resolution.U)
    }
    def submit(): Unit={d.io.in.valid.poke(true.B);until(d.io.in.ready.peek().litToBoolean);tick();d.io.in.valid.poke(false.B)
      d.io.in.bits.depthOffset.factor.poke("h7fc00000".U);d.io.in.bits.depthOffset.resolution.poke(0.U)}
    def finish(status: Int=0): BigInt={
      until(d.io.completion.valid.peek().litToBoolean);val r=d.io.completion.bits;r.status.expect(status.U)
      if(status==0){assert(accepted==expected.size);r.outputQuads.expect(accepted.U)}
      val operations=r.arithmeticOperations.peek().litValue;val held=r.peek().litValue
      for(_<-0 until 5){tick();d.io.busy.expect(true.B);assert(r.peek().litValue==held)}
      d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B);d.io.busy.expect(false.B);operations
    }
    def run(t: Triangle=source,sampleMode: Int=0,enabled: Boolean=true,factor: BigInt=fp(2),units: BigInt=fp(3)): BigInt={
      prepare(t,sampleMode,enabled,factor,units);submit();finish()
    }
  }
  it should "offset default center and covered sample depths with one setup per primitive and both windings" in {
    simulate(new Fp32PrimitiveRasterStage(p,g)){d=>val t=new Driver(d);var draws=0
      for(reverse<-Seq(false,true);mode<-0 to 2){
        val triangle=if(!reverse)source else source.copy(coords=source.coords.reverse,values=source.values.copy(depth=source.values.depth.reverse))
        val baseline=t.run(triangle,mode,enabled=false)
        val quads=TriangleRasterReference.raster(triangle,Settings(mode=mode),g)
        val additions=quads.size*4+(if(mode==0)0 else quads.flatMap(_.samples).map(s=>Integer.bitCount(s.mask)).sum)
        for(sign<-Seq(-1,1)){val operations=t.run(triangle,mode,factor=fp(sign*2),units=fp(sign*3))
          assert(operations-baseline==15+additions,s"offset was not prepared once: delta=${operations-baseline} expected=${15+additions}");draws+=1}
        val zero=t.run(triangle,mode,factor=0,units=0);assert(zero==baseline)
      }
      info(s"POLYGON_OFFSET_RASTER biasedDraws=$draws modes=3 windings=2 exactCenterAndSamples=true unchangedVaryings=true preparedOnce=true fifteenSetupOperations=true zeroNoExtraMath=true descriptorSnapshot=true heldQuads=true")
    }
  }
  it should "apply offset before clamping extrapolated helper depth and preserve culling and line bypass" in {
    simulate(new Fp32PrimitiveRasterStage(p,g)){d=>val t=new Driver(d)
      val tiny=source.copy(coords=Vector((0,0),(256,0),(0,256)).map{case(x,y)=>(BigInt(x),BigInt(y))},values=source.values.copy(depth=Vector(0.0,1.0,1.0).map(fp)))
      for(mode<-0 to 2)t.run(tiny,mode,factor=fp(-0.5),units=0)
      assert(t.unclampedWitness>0,"fixture failed to distinguish offset-before-clamp")
      t.prepare(cull=2);t.submit();t.finish()
      val line=source.copy(coords=Vector((256,1024),(1792,1024),(0,0)).map{case(x,y)=>(BigInt(x),BigInt(y))})
      t.prepare(line,primitiveKind=1,enabled=false);t.submit();val baseline=t.finish()
      t.prepare(line,primitiveKind=1,factor=BigInt("7fc00000",16),resolution=0);t.submit();assert(t.finish()==baseline)
      info("POLYGON_OFFSET_ORDER extrapolatedHelpers=true biasBeforeClamp=true culledNoQuads=true linesIgnoreOffset=true sharedArithmetic=true exactReuse=true")
    }
  }
  it should "reject bad bindings and drain setup arithmetic or held biased quads before cancellation retires" in {
    simulate(new Fp32PrimitiveRasterStage(p,g)){d=>val t=new Driver(d)
      for((factor,resolution)<-Seq((BigInt("7fc00000",16),fp(1.0/256)),(fp(2),BigInt(0)))){
        t.prepare(factor=factor,resolution=resolution,model=false);t.submit();t.finish(1)
      }
      for(delay<-Seq(8,50,100)){
        t.prepare();t.submit();for(_<-0 until delay)t.tick();d.io.cancel.poke(true.B)
        t.finish(4);d.io.cancel.poke(false.B);t.run()
      }
      t.prepare(sampleMode=2);t.hold=true;t.submit();t.until(d.io.out.valid.peek().litToBoolean)
      d.io.cancel.poke(true.B);for(_<-0 until 8)t.tick();d.io.completion.valid.expect(false.B)
      t.hold=false;t.finish(4);d.io.cancel.poke(false.B);t.run(sampleMode=2)
      info("POLYGON_OFFSET_RASTER_DRAIN malformedBindings=true setupCancelOffsets=3 heldBiasedQuad=true noEarlyCompletion=true scalarOwnershipDrains=true immutableCompletion=true healthyReuse=true")
    }
  }
}
