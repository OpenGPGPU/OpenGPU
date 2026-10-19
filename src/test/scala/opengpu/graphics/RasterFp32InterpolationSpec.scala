package opengpu.graphics

import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** Integration harness; production native bin/fragment dispatch ownership is
  * deliberately outside this stream-level test. Source window state stays
  * immutable until raster and interpolation both drain.
  */
private class RasterFp32InterpolationHarness(p: VertexShaderStageConfig,g: GraphicsConfig) extends Module {
  val io=IO(new Bundle {
    val draw=Flipped(Decoupled(new TriangleVertices(g)));val primitive=Input(new WindowPrimitive(p))
    val sampleMode=Input(UInt(2.W));val out=Decoupled(new Fp32InterpolatedQuad(p,g))
    val completion=Decoupled(new FragmentInterpolationCompletion)
    val admitted=Output(Bool());val sample=Output(new RasterQuad(g));val busy=Output(Bool())
  })
  private val raster=Module(new TriangleRasterizer(g,quadMode=true,pixelCenter=true))
  private val interpolation=Module(new Fp32FragmentInterpolator(p,g))
  private val primitive=RegEnable(io.primitive,io.draw.fire)
  raster.io.draw<>io.draw;raster.io.cullMode:=0.U;raster.io.sampleMode:=io.sampleMode
  raster.io.scissorEnable:=false.B;raster.io.scissorMinX:=0.U;raster.io.scissorMinY:=0.U
  raster.io.scissorMaxX:=g.screenWidth.U;raster.io.scissorMaxY:=g.screenHeight.U
  raster.io.screenW:=g.screenWidth.U;raster.io.screenH:=g.screenHeight.U;raster.io.pixel.ready:=true.B
  interpolation.io.in.valid:=raster.io.quad.valid;interpolation.io.in.bits.primitive:=primitive
  interpolation.io.in.bits.quad:=raster.io.quad.bits;interpolation.io.in.bits.pointSize:=0.U
  interpolation.io.in.bits.sampleDepthEnable:=true.B;interpolation.io.in.bits.sampleMode:=io.sampleMode;raster.io.quad.ready:=interpolation.io.in.ready
  interpolation.io.cancel:=false.B;io.out<>interpolation.io.out;io.completion<>interpolation.io.completion
  io.admitted:=interpolation.io.in.fire;io.sample:=interpolation.io.in.bits.quad
  io.busy:= !raster.io.draw.ready || interpolation.io.busy
}

class RasterFp32InterpolationSpec extends AnyFlatSpec {
  import Fp32ViewportReference._
  import FragmentInterpolationReference.{Data=>Values,_}
  private val p=VertexShaderStageConfig(VertexInputFetchConfig(1,1),2)
  private val g=GraphicsConfig(screenWidth=8,screenHeight=8)
  private def zero(data: chisel3.Data): Unit=data match {
    case r: Record=>r.elements.values.foreach(zero)
    case v: Vec[_]=>v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case _=>()
  }
  for(mode<-Seq(0,2);reverse<-Seq(false,true))it should s"interpolate actual raster quads with helper lanes sampleMode=$mode reverse=$reverse" in {
    simulate(new RasterFp32InterpolationHarness(p,g)) { d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val view=View(width=8,height=8)
      val originalPositions=Vector(Vector(-1.0,-1.0,0.0,1.0),Vector(2.0,-2.0,1.0,2.0),Vector(-0.5,0.5,-0.25,0.5)).map(_.map(fp))
      val positions=if(reverse)Vector(originalPositions(0),originalPositions(2),originalPositions(1))else originalPositions
      val prepared=positions.map(project(_,view,Fp32ViewportConfig()))
      val values=Values(prepared.map(_.reciprocal),prepared.map(_.depth),Vector.tabulate(3,8)((v,c)=>if(c==3)BigInt("ffa01234",16)else fp((v+1)*(c+0.25))),8,2)
      val q=d.io.primitive;val a=q.clipped.primitive
      a.identity.context.poke(5.U);a.identity.pass.poke(6.U);a.identity.generation.poke(7.U)
      a.sequence.poke(11.U);a.primitiveId.poke(12.U);a.instanceId.poke(2.U);a.kind.poke(2.U);a.topology.poke(4.U)
      a.vertexCount.poke(3.U);a.provokingVertex.poke(2.U);a.varyingSlots.poke(2.U);a.flatMask.poke(8.U);q.clipped.partCount.poke(1.U)
      val coords=prepared.map(v=>(v.x,v.y))
      for(i<-0 until 3) {
        val r=a.vertices(i);r.identity.context.poke(5.U);r.identity.pass.poke(6.U);r.identity.generation.poke(7.U);r.instanceId.poke(2.U)
        for(c<-0 until 8)r.varyings(c/4)(c%4).poke(values.values(i)(c).U)
        for(c<-0 until 4)r.position(c).poke(positions(i)(c).U)
        val v=q.vertices(i);val e=prepared(i).reciprocal
        v.reciprocalW.negative.poke(e.sign.B);v.reciprocalW.exponent.poke(e.exponent.S);v.reciprocalW.significand.poke(e.sig.U)
        v.windowDepth.poke(prepared(i).depth.U);v.screenX.poke(coords(i)._1.S);v.screenY.poke(coords(i)._2.S);v.fixedValid.poke(true.B)
        val dest=Seq(d.io.draw.bits.v0,d.io.draw.bits.v1,d.io.draw.bits.v2)(i);dest.x.poke(coords(i)._1.S);dest.y.poke(coords(i)._2.S)
      }
      d.io.sampleMode.poke(mode.U)
      val pending=mutable.Queue.empty[Vector[(Sample,Lane)]];var admitted=0;var outputs=0;var completions=0;var helpers=0;var covered=0;var cycles=0
      var held: Option[BigInt]=None;val seen=mutable.Map.empty[(Int,Int),Int]
      def edgeAt(j: Int,x: BigInt,y: BigInt): BigInt={val(a,b)= (coords((j+1)%3),coords((j+2)%3));(a._2-b._2)*x+(b._1-a._1)*y+a._1*b._2-b._1*a._2}
      val signedArea=edgeAt(0,coords(0)._1,coords(0)._2)
      def maskAt(x: Int,y: Int): Int={
        if(x<0 || y<0 || x>=8 || y>=8)return 0
        val offsets=if(mode==0)Vector((0,0))else Vector((-64,-64),(64,-64),(-64,64),(64,64))
        offsets.zipWithIndex.foldLeft(0) { case(mask,((dx,dy),bit))=>
          val inside=(0 until 3).forall { j=>
            val(a,b)=(coords((j+1)%3),coords((j+2)%3));val sign=if(signedArea>0)1 else -1
            val aa=(a._2-b._2)*sign;val bb=(b._1-a._1)*sign
            val e=edgeAt(j,(BigInt(x)<<8)+128+dx,(BigInt(y)<<8)+128+dy)*sign
            e>0 || (e==0 && (aa<0 || (aa==0 && bb<0)))
          };if(inside)mask|(1<<bit)else mask
        }
      }
      def tick(): Unit={
        d.io.out.ready.poke((cycles%11<7).B);d.io.completion.ready.poke((cycles%7<5).B)
        if(d.io.admitted.peek().litToBoolean) {
          val ss=Vector.tabulate(4) { i=>val s=d.io.sample.lanes(i)
            val sample=Sample(Vector(s.e0,s.e1,s.e2).map(_.peek().litValue),s.area.peek().litValue,s.coverageMask.peek().litValue.toInt,s.x.peek().litValue.toInt,s.y.peek().litValue.toInt)
            val x=(BigInt(sample.x)<<8)+128;val y=(BigInt(sample.y)<<8)+128
            assert(sample.edges==Vector.tabulate(3)(edgeAt(_,x,y)),s"center edges incorrect: $sample")
            assert(sample.edges.sum==sample.area)
            assert(sample.mask==maskAt(sample.x,sample.y),s"coverage mismatch: $sample")
            if(sample.mask!=0){assert(!seen.contains((sample.x,sample.y)));seen((sample.x,sample.y))=sample.mask}
            if(sample.mask==0)helpers+=1 else covered+=1
            (sample,lane(values,sample))
          };pending.enqueue(ss);admitted+=1
        }
        if(d.io.out.valid.peek().litToBoolean) {
          val bits=d.io.out.bits.peek().litValue;held.foreach(h=>assert(h==bits))
          if(d.io.out.ready.peek().litToBoolean) {
            assert(pending.nonEmpty);val ss=pending.dequeue();val o=d.io.out.bits
            SampleDepthReference.check(o,SampleDepthReference.depths(TriangleRasterReference.Triangle(coords,values),2,ss.map(_._1),mode,g))
            o.sequence.expect(11.U);o.primitiveId.expect(12.U);o.identity.context.expect(5.U)
            for(i<-0 until 4) {
              val(s,e)=ss(i);val v=o.lanes(i);v.x.expect(s.x.S);v.y.expect(s.y.S);v.coverageMask.expect(s.mask.U);v.covered.expect((s.mask!=0).B)
              v.windowDepth.expect(e.depth.U);v.fragCoordW.expect(pack(e.reciprocal).U);Fp32ViewportReference.expect(v.reciprocalW,e.reciprocal)
              for(c<-0 until 8)v.varyings(c/4)(c%4).expect(e.values(c).U)
            };outputs+=1;held=None
          }else held=Some(bits)
        }else assert(held.isEmpty)
        if(d.io.completion.valid.peek().litToBoolean && d.io.completion.ready.peek().litToBoolean){d.io.completion.bits.status.expect(0.U);completions+=1}
        d.clock.step();cycles+=1
      }
      d.io.draw.valid.poke(true.B);assert(d.io.draw.ready.peek().litToBoolean);tick();d.io.draw.valid.poke(false.B)
      // Mutate inactive host state after the source snapshot.
      d.io.primitive.clipped.primitive.sequence.poke(999.U)
      while((d.io.busy.peek().litToBoolean || admitted==0) && cycles<100000)tick()
      assert(cycles<100000 && pending.isEmpty && outputs==admitted && completions==outputs)
      assert(outputs>0 && helpers>0 && covered>0)
      val required=(for(y<-0 until 8;x<-0 until 8;if maskAt(x,y)!=0)yield (x,y)->maskAt(x,y)).toMap
      assert(seen.toMap==required,"raster missed or duplicated covered centers")
      info(s"RASTER_FP32_INTERPOLATION sampleMode=$mode reverse=$reverse quads=$outputs coveredLanes=$covered helpers=$helpers exactCenterEdges=true exactCoverage=true noMissingPixels=true viewportReference=true rationalValues=true sourceSnapshot=true backpressure=true")
    }
  }
}
