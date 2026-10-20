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

  it should "retain distinct interpolated depths for sloped triangles with an exact common D32F step" in {
    val large=GraphicsConfig(screenWidth=416,screenHeight=320)
    val coordinates=Vector((0,0),(104394,46434),(31737,80300)).map{case(x,y)=>(BigInt(x),BigInt(y))}
    val low=Vector("3ee9df5d","3e79575c","3e7f3778").map(BigInt(_,16))
    val high=Vector("3ee9df5e","3e79575e","3e7f377a").map(BigInt(_,16))
    simulate(new Fp32PrimitiveRasterStage(p,large)){d=>
      PointLineReference.zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def until(condition: =>Boolean): Unit={var n=0;while(!condition && n<100000){d.clock.step();n+=1};assert(condition,s"sloped depth timeout after $n cycles")}
      def run(depths: Vector[BigInt],mode: Int,reverse: Boolean,directed: Boolean): Depths={
        val triangle=TriangleRasterReference.base.copy(coords=if(reverse)coordinates.reverse else coordinates,values=FragmentInterpolationReference.Data(
          Vector.fill(3)(Fp32ViewportReference.decode(Fp32ViewportReference.fp(1))),depths,Vector.fill(3)(Vector.fill(8)(BigInt(0))),0,1))
        val settings=TriangleRasterReference.Settings(width=416,height=320,mode=mode,scissor=true,minX=225,minY=186,maxX=226,maxY=187)
        PointLineReference.zero(d.io.in.bits);TriangleRasterReference.primitive(d.io.in.bits.primitive,triangle)
        TriangleRasterReference.state(d.io.in.bits.state,settings)
        d.io.in.bits.depthRoundTowardZero.poke(directed.B)
        d.io.in.valid.poke(true.B);until(d.io.in.ready.peek().litToBoolean);d.clock.step();d.io.in.valid.poke(false.B)
        d.io.in.bits.depthRoundTowardZero.poke((!directed).B)
        until(d.io.out.valid.peek().litToBoolean)
        val values=d.io.out.bits.values
        val covered=values.lanes.filter(_.covered.peek().litToBoolean);assert(covered.size==1)
        val lane=covered.head;lane.x.expect(225.S);lane.y.expect(186.S);lane.coverageMask.expect(((1<<(1<<mode))-1).U)
        val observed=Depths(values.lanes.map(_.windowDepth.peek().litValue).toVector,values.lanes.map(_.sampleDepth.map(_.peek().litValue).toVector).toVector)
        lane.sampleDepthValid.expect(true.B);if(mode==0)lane.sampleDepth(0).expect(lane.windowDepth.peek().litValue.U)
        val held=d.io.out.bits.peek().litValue
        for(_<-0 until 3){d.clock.step();d.io.out.valid.expect(true.B);assert(d.io.out.bits.peek().litValue==held)}
        d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)
        until(d.io.completion.valid.peek().litToBoolean)
        d.io.completion.bits.status.expect(0.U);d.io.completion.bits.outputQuads.expect(1.U);d.io.completion.bits.coveredSamples.expect((1<<mode).U)
        d.io.completion.ready.poke(true.B);d.clock.step();d.io.completion.ready.poke(false.B);d.io.busy.expect(false.B)
        observed
      }
      val legacyBefore=run(low,0,false,false);val legacyAfter=run(high,0,false,false)
      assert(legacyBefore.center(1)==legacyAfter.center(1),"retained RNE control must reproduce the known collapse")
      val cases=Vector(
        ("normal",low,high,BigInt("33000000",16)),
        ("subnormal",Vector(1000001,200002,300003).map(BigInt(_)),Vector(1000002,200003,300004).map(BigInt(_)),BigInt(1)),
        ("binadeNeighbor",Vector("3e000001","3efffffe","3efffffd").map(BigInt(_,16)),Vector("3e000003","3effffff","3efffffe").map(BigInt(_,16)),BigInt("33000000",16)))
      for((name,lo,hi,r)<-cases;reverse<-Seq(false,true);mode<-0 to 2){
        val(rn,rd)=Fp32ViewportReference.rational(Fp32ViewportReference.decode(r))
        for((a,b)<-lo.zip(hi)){
          val(an,ad)=Fp32ViewportReference.rational(Fp32ViewportReference.decode(a))
          val(bn,bd)=Fp32ViewportReference.rational(Fp32ViewportReference.decode(b))
          assert((bn*ad-an*bd)*rd==rn*ad*bd,"source vertices must differ by exactly one common step")
        }
        assert((lo.max>>23)==(hi.max>>23),"uniform source shift must preserve the maximum exponent")
        val before=run(lo,mode,reverse,true);val after=run(hi,mode,reverse,true)
        assert(before.center.zip(after.center).forall{case(a,b)=>a!=b},s"one common D32F step collapsed at center/helper: $name reverse=$reverse mode=$mode")
        for(sample<-0 until(1<<mode))assert(before.samples(1)(sample)!=after.samples(1)(sample),s"covered sample collapsed: $name sample=$sample")
        info(s"DEPTH_TRANSLATION_PAIR case=$name mode=$mode reverse=$reverse actualCoverage=true exactVertexShift=true before=${before.center(1).toString(16)} after=${after.center(1).toString(16)}")
      }
      info("DEPTH_DIRECTED_RASTER pairs=18 legacyRNENegativeControl=true normalSubnormalBinadeNeighbor=true modes=3 windings=2 centerHelperSampleDistinct=true policySnapshot=true heldQuads=true healthyReuse=true")
    }
  }

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
        d.io.in.bits.depthRoundTowardZero.poke(true.B)
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
  it should "preserve one floating depth step for an interior pixel next to a binade boundary" in {
    val coordinates=Vector((-90880,-58597),(90880,-58597),(-60947,39297)).map{case(x,y)=>(BigInt(x+128),BigInt(y+128))}
    val low=Vector("3e000001","3efffffe","3efffffe").map(BigInt(_,16))
    val high=Vector("3e000003","3effffff","3effffff").map(BigInt(_,16))
    simulate(new Fp32PrimitiveRasterStage(p,g)){d=>
      PointLineReference.zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def until(condition: =>Boolean): Unit={var n=0;while(!condition && n<100000){d.clock.step();n+=1};assert(condition)}
      def run(depths: Vector[BigInt]): BigInt={
        val triangle=TriangleRasterReference.base.copy(coords=coordinates,values=FragmentInterpolationReference.Data(
          Vector.fill(3)(Fp32ViewportReference.decode(Fp32ViewportReference.fp(1))),depths,Vector.fill(3)(Vector.fill(8)(BigInt(0))),0,1))
        val settings=TriangleRasterReference.Settings(width=8,height=8,mode=0,scissor=true,minX=0,minY=0,maxX=1,maxY=1)
        val quads=TriangleRasterReference.raster(triangle,settings,g)
        assert(quads.size==1 && quads.head.samples.head.mask==1)
        assert(quads.head.samples.head.edges==Vector(1L,7142622719L,10650590720L).map(BigInt(_)))
        PointLineReference.zero(d.io.in.bits);TriangleRasterReference.primitive(d.io.in.bits.primitive,triangle)
        TriangleRasterReference.state(d.io.in.bits.state,settings);d.io.in.bits.depthRoundTowardZero.poke(true.B)
        d.io.in.valid.poke(true.B);until(d.io.in.ready.peek().litToBoolean);d.clock.step();d.io.in.valid.poke(false.B)
        until(d.io.out.valid.peek().litToBoolean)
        val lane=d.io.out.bits.values.lanes.head;lane.covered.expect(true.B);lane.coverageMask.expect(1.U)
        val result=lane.windowDepth.peek().litValue;lane.sampleDepth(0).expect(result.U)
        d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)
        until(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.status.expect(0.U)
        d.io.completion.ready.poke(true.B);d.clock.step();d.io.completion.ready.poke(false.B);d.io.busy.expect(false.B)
        result
      }
      val before=run(low);val after=run(high)
      info(s"DEPTH_DIRECTED_BOUNDARY e0=1 actualCoverage=true resolution33000000=true before=${before.toString(16)} after=${after.toString(16)}")
      assert(before!=after,s"directed final addition still collapses an interior binade-boundary step: ${before.toString(16)} == ${after.toString(16)}")
      assert(before==low.max && after==high.max,"inside-depth bounds must restore the exact upper vertex depths before bias")
    }
  }

}
