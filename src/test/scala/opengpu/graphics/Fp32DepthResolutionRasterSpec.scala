package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class Fp32DepthResolutionRasterSpec extends AnyFlatSpec {
  private val p=VertexShaderStageConfig(VertexInputFetchConfig(1,1),2)
  private val g=GraphicsConfig(screenWidth=8,screenHeight=8)
  private val geometries=Vector(("viewport786x393",BigInt(67072),BigInt(33536)),
    ("largeGuardBand",BigInt(1239639),BigInt(17329276)))
  private case class Depths(center: Vector[BigInt],samples: Vector[Vector[BigInt]])

  it should "retain distinct default depths for constant triangles separated by one D32F resolution step" in {
    simulate(new Fp32PrimitiveRasterStage(p,g)){d=>
      PointLineReference.zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def until(condition: =>Boolean): Unit={var n=0;while(!condition && n<100000){d.clock.step();n+=1};assert(condition,s"resolution raster timeout after $n cycles")}
      def run(z: BigInt,mode: Int,reverse: Boolean,coordinates: Vector[(BigInt,BigInt)]): Depths={
        val coords=if(reverse)coordinates.reverse else coordinates
        val triangle=TriangleRasterReference.base.copy(coords=coords,values=FragmentInterpolationReference.Data(
          Vector.fill(3)(Fp32ViewportReference.decode(Fp32ViewportReference.fp(1))),Vector.fill(3)(z),Vector.fill(3)(Vector.fill(8)(BigInt(0))),0,1))
        val settings=TriangleRasterReference.Settings(width=8,height=8,mode=mode,scissor=true,minX=0,minY=0,maxX=1,maxY=1)
        val quads=TriangleRasterReference.raster(triangle,settings,g);assert(quads.size==1 && quads.head.samples.head.mask==(1<<(1<<mode))-1)
        PointLineReference.zero(d.io.in.bits);TriangleRasterReference.primitive(d.io.in.bits.primitive,triangle)
        TriangleRasterReference.state(d.io.in.bits.state,settings)
        d.io.in.valid.poke(true.B);until(d.io.in.ready.peek().litToBoolean);d.clock.step();d.io.in.valid.poke(false.B)
        until(d.io.out.valid.peek().litToBoolean)
        val q=d.io.out.bits.values
        q.lanes.head.coverageMask.expect(((1<<(1<<mode))-1).U);q.lanes.head.sampleDepthValid.expect(true.B)
        val result=Depths(q.lanes.map(_.windowDepth.peek().litValue).toVector,q.lanes.map(_.sampleDepth.map(_.peek().litValue).toVector).toVector)
        val held=d.io.out.bits.peek().litValue
        for(_<-0 until 5){d.clock.step();d.io.out.valid.expect(true.B);assert(d.io.out.bits.peek().litValue==held)}
        d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)
        until(d.io.completion.valid.peek().litToBoolean)
        d.io.completion.bits.status.expect(0.U);d.io.completion.bits.outputQuads.expect(1.U)
        d.io.completion.bits.coveredSamples.expect((1<<mode).U)
        d.io.completion.ready.poke(true.B);d.clock.step();d.io.completion.ready.poke(false.B);d.io.busy.expect(false.B)
        result
      }
      // The source depths differ by exactly 2^-24, with the same binary32
      // exponent. No clamping or offset is involved in this raster precision
      // check. All integer coordinates, spans and signed-64 areas are legal.
      val low=BigInt("3f000001",16);val high=BigInt("3f000002",16)
      for((name,a,b)<-geometries;reverse<-Seq(false,true);mode<-0 to 2){
        val coordinates=Vector((-a,-b),(a*2,-b),(-a,b*2))
        val x=run(low,mode,reverse,coordinates);val y=run(high,mode,reverse,coordinates)
        info(s"DEPTH_RESOLUTION_PAIR geometry=$name mode=$mode reverse=$reverse sourceLow=${low.toString(16)} sourceHigh=${high.toString(16)} centerLow=${x.center.head.toString(16)} centerHigh=${y.center.head.toString(16)}")
        assert(x.center.head!=y.center.head,s"one D32F resolution step collapsed through raster interpolation: ${x.center.head.toString(16)} == ${y.center.head.toString(16)}")
        assert(x.center.forall(_==low) && y.center.forall(_==high),"constant vertex depth must survive center/helper interpolation exactly")
        for(s<-0 until(1<<mode)){
          assert(x.samples.head(s)==low && y.samples.head(s)==high,"covered-sample depth lost the constant-depth resolution step")
        }
      }
      info("DEPTH_RESOLUTION_RASTER constantDepth=true sameExponent=true oneBinary32ULP=true geometries=2 viewportSizedWitness=true fullWidthIntegerEdges=true actualCoverage=true windings=2 sampleModes=3 exactCenterHelpersSamples=true heldQuads=true healthyReuse=true")
    }
  }
}
