package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

private class PolygonDepthUnitRasterHarness(p: VertexShaderStageConfig,g: GraphicsConfig) extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new Bundle {val draw=new Fp32TriangleRasterRequest(p);val format=UInt(3.W)}))
    val out=Decoupled(new Bundle {
      val raw=new Fp32RasterizedQuad(p,g)
      val centers=Vec(4,UInt(32.W));val samples=Vec(4,Vec(g.maxSampleCount,UInt(32.W)))
    })
    val completion=Decoupled(new Fp32TriangleRasterCompletion)
  })
  val raster=Module(new Fp32PrimitiveRasterStage(p,g));raster.io.cancel:=false.B
  raster.io.in.valid:=io.in.valid;io.in.ready:=raster.io.in.ready;raster.io.in.bits:=io.in.bits.draw
  val resolution=PolygonDepthResolution.evaluate(io.in.bits.format,io.in.bits.draw.primitive.vertices.map(_.windowDepth))
  raster.io.in.bits.depthRoundTowardZero:=TypedDepthFormat.floating(io.in.bits.format)
  raster.io.in.bits.depthOffset.enabled:=io.in.bits.draw.state.polygonOffsetEnable && resolution.available
  raster.io.in.bits.depthOffset.factor:=io.in.bits.draw.state.polygonOffsetFactor
  raster.io.in.bits.depthOffset.units:=io.in.bits.draw.state.polygonOffsetUnits
  raster.io.in.bits.depthOffset.resolution:=resolution.value
  val format=Reg(UInt(3.W));when(io.in.fire){assert(resolution.valid);format:=io.in.bits.format}
  io.out.valid:=raster.io.out.valid;raster.io.out.ready:=io.out.ready;io.out.bits.raw:=raster.io.out.bits
  for(i<-0 until 4){
    io.out.bits.centers(i):=TypedDepthFormat.encode(raster.io.out.bits.values.lanes(i).windowDepth,format)
    for(s<-0 until g.maxSampleCount)io.out.bits.samples(i)(s):=TypedDepthFormat.encode(raster.io.out.bits.values.lanes(i).sampleDepth(s),format)
  }
  io.completion<>raster.io.completion
}

class PolygonDepthUnitRasterSpec extends AnyFlatSpec {
  private val p=VertexShaderStageConfig(VertexInputFetchConfig(1,1),2)
  private val g=GraphicsConfig(screenWidth=416,screenHeight=320)
  private case class Result(center: BigInt,samples: Vector[BigInt],raw: BigInt,rawSamples: Vector[BigInt],mask: Int,operations: BigInt)
  it should "resolve signed unit offsets through actual raster setup sample depth and every depth encoder" in {
    val coordinates=Vector((0,0),(104394,46434),(31737,80300)).map{case(x,y)=>(BigInt(x),BigInt(y))}
    val boundary=Vector((-90880,-58597),(90880,-58597),(-60947,39297)).map{case(x,y)=>(BigInt(x+128),BigInt(y+128))}
    val cases=Vector(
      ("sloped",coordinates,Vector("3ee9df5d","3e79575c","3e7f3778").map(BigInt(_,16)),225,186),
      ("subnormal",coordinates,Vector(1000001,200002,300003).map(BigInt(_)),225,186),
      ("boundary",boundary,Vector("3e000001","3efffffe","3efffffe").map(BigInt(_,16)),0,0))
    simulate(new PolygonDepthUnitRasterHarness(p,g)){d=>
      PointLineReference.zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      var draws=0;var distinctCenters=0;var distinctSamples=0;var clampedEndpoints=0
      def until(condition: =>Boolean): Unit={var n=0;while(!condition && n<100000){d.clock.step();n+=1};assert(condition)}
      def run(coords: Vector[(BigInt,BigInt)],depth: Vector[BigInt],x: Int,y: Int,format: Int,mode: Int,units: Int): Result={
        val triangle=TriangleRasterReference.base.copy(coords=coords,values=FragmentInterpolationReference.Data(
          Vector.fill(3)(Fp32ViewportReference.decode(Fp32ViewportReference.fp(1))),depth,Vector.fill(3)(Vector.fill(8)(BigInt(0))),0,0))
        val settings=TriangleRasterReference.Settings(width=416,height=320,mode=mode,scissor=true,minX=x,minY=y,maxX=x+1,maxY=y+1)
        val quads=TriangleRasterReference.raster(triangle,settings,g);assert(quads.size==1)
        val laneIndex=quads.head.samples.indexWhere(_.mask!=0);assert(laneIndex>=0)
        PointLineReference.zero(d.io.in.bits);TriangleRasterReference.primitive(d.io.in.bits.draw.primitive,triangle)
        TriangleRasterReference.state(d.io.in.bits.draw.state,settings)
        val state=d.io.in.bits.draw.state;state.version.poke(2.U);state.polygonOffsetEnable.poke((units!=0).B)
        state.polygonOffsetFactor.poke(0.U);state.polygonOffsetUnits.poke(Fp32ViewportReference.fp(units).U)
        d.io.in.bits.format.poke(format.U);d.io.in.valid.poke(true.B);until(d.io.in.ready.peek().litToBoolean)
        d.clock.step();d.io.in.valid.poke(false.B);d.io.in.bits.format.poke(7.U);state.polygonOffsetUnits.poke("h7fc00000".U)
        until(d.io.out.valid.peek().litToBoolean)
        val q=d.io.out.bits;val lane=q.raw.values.lanes(laneIndex)
        lane.x.expect(x.S);lane.y.expect(y.S);lane.covered.expect(true.B)
        val mask=lane.coverageMask.peek().litValue.toInt;assert(mask!=0)
        val result=Result(q.centers(laneIndex).peek().litValue,q.samples(laneIndex).map(_.peek().litValue).toVector,
          lane.windowDepth.peek().litValue,lane.sampleDepth.map(_.peek().litValue).toVector,mask,0)
        val held=q.peek().litValue;for(_<-0 until 3){d.clock.step();assert(q.peek().litValue==held)}
        d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)
        until(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.status.expect(0.U)
        d.io.completion.bits.outputQuads.expect(1.U);d.io.completion.bits.coveredSamples.expect(Integer.bitCount(mask).U)
        val operations=d.io.completion.bits.arithmeticOperations.peek().litValue
        d.io.completion.ready.poke(true.B);d.clock.step();d.io.completion.ready.poke(false.B);draws+=1
        result.copy(operations=operations)
      }
      for((name,coords,depth,x,y)<-cases;reverse<-Seq(false,true);format<-1 to 5;mode<-0 to 2){
        val positions=if(reverse)coords.reverse else coords;val zs=if(reverse)depth.reverse else depth
        val baseline=run(positions,zs,x,y,format,mode,0)
        val r=if(format==1)BigInt("37804001",16)else if(format==2 || format==4)BigInt("33c00000",16)
          else BigInt(java.lang.Float.floatToRawIntBits(java.lang.Math.ulp(java.lang.Float.intBitsToFloat(depth.max.toInt))).toLong&0xffffffffL)
        def check(before: BigInt,after: BigInt,raw: BigInt,sign: Int): Boolean={
          val z=Fp32ViewportReference.number(raw);val unit=Fp32ViewportReference.number(r)
          if((sign<0 && z<unit) || (sign>0 && z+unit>1)){
            val endpoint=if(sign<0)BigInt(0)else TypedDepthReference.encode(Fp32ViewportReference.fp(1),format)
            assert(after==endpoint,s"offset outside the depth range must clamp to its encoded endpoint: $name format=$format sign=$sign")
            clampedEndpoints+=1;false
          }
          else {assert(if(sign<0)after<before else after>before,s"offset unit collapsed case=$name format=$format mode=$mode reverse=$reverse sign=$sign before=${before.toString(16)} after=${after.toString(16)}");true}
        }
        for(sign<-Seq(-1,1)){
          val biased=run(positions,zs,x,y,format,mode,sign);assert(biased.mask==baseline.mask)
          assert(biased.operations-baseline.operations==13+4+(if(mode==0)0 else Integer.bitCount(baseline.mask)))
          if(check(baseline.center,biased.center,baseline.raw,sign))distinctCenters+=1
          for(s<-0 until (1<<mode) if (baseline.mask&(1<<s))!=0)
            if(check(baseline.samples(s),biased.samples(s),baseline.rawSamples(s),sign))distinctSamples+=1
        }
      }
      assert(draws==270 && distinctCenters>100 && distinctSamples>100)
      info(s"POLYGON_DEPTH_UNITS draws=$draws distinctCenters=$distinctCenters distinctSamples=$distinctSamples clampedEndpoints=$clampedEndpoints formats=5 modes=3 windings=2 geometries=3 signedUnits=true factorZero=true actualSetupAndInterpolation=true actualDepthEncode=true immutableFormat=true heldQuads=true healthyReuse=true")
    }
  }
}
