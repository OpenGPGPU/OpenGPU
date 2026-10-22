package opengpu.graphics

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.util.Random

private[graphics] object TriangleRasterReference {
  import Fp32ViewportReference._
  import FragmentInterpolationReference.{Data=>Values,_}
  case class Settings(width: Int=8,height: Int=8,mode: Int=0,cull: Int=0,ccw: Boolean=true,
    scissor: Boolean=false,minX: Int=0,minY: Int=0,maxX: Int=8,maxY: Int=8)
  case class Triangle(coords: Vector[(BigInt,BigInt)],values: Values)
  case class Quad(x: Int,y: Int,front: Boolean,samples: Vector[Sample],lanes: Vector[Lane])
  def base: Triangle=Triangle(Vector((0,0),(8*256,0),(0,8*256)).map{case(x,y)=>(BigInt(x),BigInt(y))},
    Values(Vector(1.0,0.5,2.0).map(v=>decode(fp(v))),Vector(0.1,0.7,0.9).map(fp),
      Vector.tabulate(3,8)((v,c)=>if(c==3)BigInt("ffc01234",16)else fp((v+1)*(c+0.25))),8,2))
  def edgeAt(t: Triangle,j: Int,x: BigInt,y: BigInt): BigInt={
    val(a,b)=(t.coords((j+1)%3),t.coords((j+2)%3));(a._2-b._2)*x+(b._1-a._1)*y+a._1*b._2-b._1*a._2
  }
  def raster(t: Triangle,s: Settings,g: GraphicsConfig): Vector[Quad]={
    val w=if(s.width==0 || s.width>g.screenWidth)g.screenWidth else s.width
    val h=if(s.height==0 || s.height>g.screenHeight)g.screenHeight else s.height
    val area=edgeAt(t,0,t.coords(0)._1,t.coords(0)._2)
    val front=(area>0)==s.ccw
    if(area==0 || (s.cull==1 && !front) || (s.cull==2 && front))return Vector.empty
    val sign=if(area>0)1 else -1
    val topLeft=Vector.tabulate(3) { j=>val(a,b)=(t.coords((j+1)%3),t.coords((j+2)%3));
      val aa=(a._2-b._2)*sign;val bb=(b._1-a._1)*sign;aa<0 || (aa==0 && bb<0)
    }
    val center=BigInt(1)<<(g.subPixelBits-1);val quarter=BigInt(1)<<(g.subPixelBits-2)
    val offsets=s.mode match {case 0=>Vector((0,0));case 1=>Vector((-1,-1),(1,1));case 2=>Vector((-1,-1),(1,-1),(-1,1),(1,1))}
    def sample(x: Int,y: Int): Sample={
      val px=(BigInt(x)<<g.subPixelBits)+center;val py=(BigInt(y)<<g.subPixelBits)+center
      val visible=x<w && y<h && (!s.scissor || (x>=s.minX && x<s.maxX && y>=s.minY && y<s.maxY))
      val mask=if(!visible)0 else offsets.zipWithIndex.foldLeft(0) {case(m,((dx,dy),bit))=>
        val inside=(0 until 3).forall{j=>val e=edgeAt(t,j,px+dx*quarter,py+dy*quarter)*sign;e>0 || (e==0 && topLeft(j))}
        if(inside)m|(1<<bit)else m
      }
      Sample(Vector.tabulate(3)(edgeAt(t,_,px,py)),area,mask,x,y)
    }
    (for(y<-0 until h by 2;x<-0 until w by 2)yield {
      val ss=Vector.tabulate(4)(i=>sample(x+(i&1),y+(i>>1)))
      if(ss.exists(_.mask!=0))Some(Quad(x,y,front,ss,ss.map(lane(t.values,_))))else None
    }).flatten.toVector
  }
  def zero(data: chisel3.Data): Unit=data match {
    case r: Record=>r.elements.values.foreach(zero)
    case v: Vec[_]=>v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case _=>()
  }
  def primitive(q: WindowPrimitive,t: Triangle,sequence: Int=11): Unit={
    zero(q);val a=q.clipped.primitive
    a.identity.context.poke(5.U);a.identity.pass.poke(6.U);a.identity.generation.poke(7.U)
    a.sequence.poke(sequence.U);a.primitiveId.poke(17.U);a.instanceId.poke(2.U);a.kind.poke(2.U);a.topology.poke(4.U)
    a.vertexCount.poke(3.U);a.provokingVertex.poke(2.U);a.varyingSlots.poke(t.values.slots.U);a.flatMask.poke(t.values.flat.U);q.clipped.partCount.poke(1.U)
    for(i<-0 until 3) {
      val r=a.vertices(i);r.identity.context.poke(5.U);r.identity.pass.poke(6.U);r.identity.generation.poke(7.U);r.instanceId.poke(2.U)
      for(c<-t.values.values(i).indices)r.varyings(c/4)(c%4).poke(t.values.values(i)(c).U)
      val v=q.vertices(i);val e=t.values.inv(i)
      v.reciprocalW.negative.poke(e.sign.B);v.reciprocalW.exponent.poke(e.exponent.S);v.reciprocalW.significand.poke(e.sig.U)
      v.windowDepth.poke(t.values.depth(i).U);v.screenX.poke(t.coords(i)._1.S);v.screenY.poke(t.coords(i)._2.S);v.fixedValid.poke(true.B)
    }
  }
  def state(q: Fp32RasterState,s: Settings): Unit={
    q.version.poke(1.U);q.screenW.poke(s.width.U);q.screenH.poke(s.height.U);q.sampleMode.poke(s.mode.U);q.cullMode.poke(s.cull.U);q.frontFaceCCW.poke(s.ccw.B)
    q.scissorEnable.poke(s.scissor.B);q.scissorMinX.poke(s.minX.U);q.scissorMinY.poke(s.minY.U);q.scissorMaxX.poke(s.maxX.U);q.scissorMaxY.poke(s.maxY.U)
  }
  def check(o: Fp32RasterizedQuad,e: Quad,s: Settings,sequence: Int=11,primitiveId: Int=17,instance: Int=2): Unit={
    o.frontFacing.expect(e.front.B);o.sampleMode.expect(s.mode.U);o.values.sequence.expect(sequence.U)
    o.values.primitiveId.expect(primitiveId.U);o.values.instanceId.expect(instance.U)
    for(i<-0 until 4){val v=o.values.lanes(i);val a=e.lanes(i);val p=e.samples(i)
      v.x.expect(p.x.S);v.y.expect(p.y.S);v.covered.expect((p.mask!=0).B);v.coverageMask.expect(p.mask.U)
      v.windowDepth.expect(a.depth.U);v.fragCoordW.expect(pack(a.reciprocal).U);Fp32ViewportReference.expect(v.reciprocalW,a.reciprocal)
      v.perspectiveValid.expect(a.valid.B);for(c<-a.values.indices)v.varyings(c/4)(c%4).expect(a.values(c).U)
    }
  }
}

class Fp32TriangleRasterStageSpec extends AnyFlatSpec {
  import TriangleRasterReference._
  import Fp32ViewportReference._
  private val p=VertexShaderStageConfig(VertexInputFetchConfig(1,1),2)
  private val g=GraphicsConfig(screenWidth=8,screenHeight=8)
  private class Driver(val d: Fp32TriangleRasterStage) {
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    var cycle=0;var accepted=0;var expected=Vector.empty[Quad];var settings=Settings();var triangle=base
    var holdAt: Option[Int]=None;var held: Option[BigInt]=None
    def tick(): Unit={
      d.io.out.ready.poke((!holdAt.contains(accepted) && cycle%7<5).B)
      if(d.io.out.valid.peek().litToBoolean){
        val bits=d.io.out.bits.peek().litValue;held.foreach(h=>assert(h==bits,"raster quad changed under stall/cancel"))
        if(d.io.out.ready.peek().litToBoolean){assert(accepted<expected.size);check(d.io.out.bits,expected(accepted),settings);SampleDepthReference.check(d.io.out.bits.values,SampleDepthReference.depths(triangle,2,expected(accepted).samples,settings.mode,g));accepted+=1;held=None}
        else held=Some(bits)
      }else assert(held.isEmpty,"raster quad withdrawn")
      d.clock.step();cycle+=1
    }
    def until(condition: => Boolean): Unit={var n=0;while(!condition && n<150000){tick();n+=1};assert(condition,s"triangle owner stalled cycle=$cycle outputs=$accepted")}
    def prepare(t: Triangle=base,s: Settings=Settings(),model: Boolean=true): Unit={
      triangle=t;settings=s;accepted=0;expected=if(model)raster(t,s,g)else Vector.empty
      primitive(d.io.in.bits.primitive,t);state(d.io.in.bits.state,s)
    }
    def submit(): Unit={d.io.in.valid.poke(true.B);until(d.io.in.ready.peek().litToBoolean);tick();d.io.in.valid.poke(false.B)
      d.io.in.bits.state.sampleMode.poke(3.U);d.io.in.bits.primitive.clipped.primitive.sequence.poke(999.U)}
    def finish(status: Int=0): Unit={
      until(d.io.completion.valid.peek().litToBoolean);val r=d.io.completion.bits
      r.status.expect(status.U);r.outputQuads.expect(accepted.U);r.sequence.expect(11.U);r.primitiveId.expect(17.U)
      val ss=expected.take(accepted).flatMap(_.samples)
      r.coveredLanes.expect(ss.count(_.mask!=0).U);r.helperLanes.expect(ss.count(_.mask==0).U);r.coveredSamples.expect(ss.map(s=>Integer.bitCount(s.mask)).sum.U)
      if(status==0){assert(accepted==expected.size);r.culled.expect((accepted==0).B);r.discardedQuads.expect(0.U);r.rasterQuads.expect(accepted.U)}
      val held=r.peek().litValue;for(_<-0 until 9){tick();d.io.busy.expect(true.B);d.io.in.ready.expect(false.B);assert(r.peek().litValue==held)}
      d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B);d.io.busy.expect(false.B)
    }
    def run(t: Triangle=base,s: Settings=Settings()): Unit={prepare(t,s);submit();finish()}
  }
  it should "own exact pixel-center quads through culling scissor sample modes winding and runtime extents" in {
    simulate(new Fp32TriangleRasterStage(p,g)){d=>val t=new Driver(d)
      for(mode<-0 to 2;reverse<-Seq(false,true);ccw<-Seq(false,true);cull<-0 to 2){
        val tri=if(reverse)base.copy(coords=Vector(base.coords(0),base.coords(2),base.coords(1)))else base
        t.run(tri,Settings(mode=mode,ccw=ccw,cull=cull))
      }
      t.run(s=Settings(width=5,height=7,mode=2,scissor=true,minX=1,minY=2,maxX=4,maxY=6))
      t.run(s=Settings(width=65535,height=65535,mode=1));t.run(s=Settings(width=0,height=0))
      t.run(s=Settings(scissor=true,minX=5,maxX=2));t.run(base.copy(coords=Vector.fill(3)((BigInt(0),BigInt(0)))))
      info("TRIANGLE_RASTER directedDraws=41 cullModes=3 frontConventions=2 windings=2 sampleModes=3 exactQuads=true facing=true scissor=true runtimeExtent=true snapshots=true")
    }
  }
  it should "match randomized rational interpolation and exact coverage without missing or duplicated quads" in {
    simulate(new Fp32TriangleRasterStage(p,g)){d=>val t=new Driver(d);val r=new Random(0x54524153L)
      for(i<-0 until 18){
        val tri=base.copy(coords=Vector.fill(3)((BigInt(r.nextInt(14*256)-3*256),BigInt(r.nextInt(14*256)-3*256))),values=base.values.copy(inv=Vector.fill(3)(decode(fp(1)))))
        t.run(tri,Settings(mode=i%3,ccw=i%2==0,scissor=i%4==0,minX=1,minY=1,maxX=7,maxY=7))
      }
      info("TRIANGLE_RASTER randomDraws=18 exactCoverage=true rationalValues=true orderedQuads=true helpersPreserved=true")
    }
  }
  it should "reject unsafe overlaps but cull far signed64 triangles before narrowing" in {
    simulate(new Fp32TriangleRasterStage(p,g)){d=>val t=new Driver(d)
      for(origin<-Seq(BigInt(Int.MinValue)<<8,BigInt(Int.MaxValue)<<8)){
        val tri=base.copy(coords=base.coords.map{case(x,y)=>(x+origin,y+origin)})
        t.prepare(tri,model=false);t.submit();t.until(d.io.completion.valid.peek().litToBoolean)
        d.io.completion.bits.emptyBounds.expect(true.B);d.io.completion.bits.launched.expect(false.B);t.finish()
      }
      val unsafe=base.copy(coords=Vector((-(BigInt(1)<<40),BigInt(0)),(BigInt(1)<<40,BigInt(0)),(BigInt(0),BigInt(8*256))))
      t.prepare(unsafe,model=false);t.submit();t.until(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.launched.expect(false.B);t.finish(7);t.run()
      info("TRIANGLE_RASTER farOrigins=2 signed64CullBeforeNarrow=true unsafeOverlapStatus7=true noWrappedGeometry=true healthyReuse=true")
    }
  }
  it should "preflight malformed state geometry and records without launching raster work" in {
    simulate(new Fp32TriangleRasterStage(p,g)){d=>val t=new Driver(d);val q=d.io.in.bits
      val changes=Seq[()=>Unit](()=>q.state.version.poke(0.U),()=>q.state.sampleMode.poke(3.U),()=>q.state.cullMode.poke(3.U),
        ()=>q.primitive.clipped.primitive.kind.poke(1.U),()=>q.primitive.clipped.partCount.poke(0.U),()=>q.primitive.vertices(0).fixedValid.poke(false.B),
        ()=>q.primitive.guardBandRequired.poke(true.B),()=>q.primitive.vertices(1).reciprocalW.negative.poke(true.B),
        ()=>q.primitive.clipped.primitive.vertices(2).identity.context.poke(99.U),()=>q.primitive.vertices(1).windowDepth.poke("h7fc00000".U))
      for(change<-changes){t.prepare(model=false);change();t.submit();t.until(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.launched.expect(false.B);t.finish(1)}
      t.run();info(s"TRIANGLE_RASTER invalidLayouts=${changes.size} beforeLaunch=true noPartialOutput=true healthyReuse=true")
    }
  }
  it should "drain cancellation before launch during scan and after a held final output" in {
    simulate(new Fp32TriangleRasterStage(p,g)){d=>val t=new Driver(d)
      for(offset<-Seq(0,2,15,40,130,800)){t.prepare();t.submit();for(_<-0 until offset)t.tick();d.io.cancel.poke(true.B);t.tick();d.io.cancel.poke(false.B);t.finish(4);t.run()}
      t.prepare();t.holdAt=Some(t.expected.size-1);t.submit();t.until(t.accepted==t.expected.size-1 && d.io.out.valid.peek().litToBoolean)
      val held=d.io.out.bits.peek().litValue;d.io.cancel.poke(true.B);for(_<-0 until 13){t.tick();assert(d.io.out.bits.peek().litValue==held);d.io.completion.valid.expect(false.B)}
      d.io.cancel.poke(false.B);t.holdAt=None;t.finish(4);t.run()
      info("TRIANGLE_RASTER cancelOffsets=6 rasterQueueDrain=true mathDrain=true heldFinalQuad=true heldCompletion=true healthyReuse=true")
    }
  }
  it should "attribute a later interpolation fault while retaining prior accepted quads and drain the raster" in {
    simulate(new Fp32TriangleRasterStage(p,g)){d=>val t=new Driver(d)
      val tri=base.copy(values=base.values.copy(flat=255,inv=base.values.inv.updated(1,E(false,492,0x800000))))
      t.prepare(tri);t.expected=t.expected.take(1);t.submit();t.until(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.interpolationFault.expect(true.B);d.io.completion.bits.faultX.expect(2.S);d.io.completion.bits.faultY.expect(0.S);d.io.completion.bits.faultLane.expect(0.U)
      assert(t.accepted==1);assert(d.io.completion.bits.discardedQuads.peek().litValue>0);t.finish(7);t.run()
      info("TRIANGLE_RASTER laterNumericFault=true priorQuads=1 exactFaultQuad=true youngerRasterDrained=true healthyReuse=true")
    }
  }
  it should "retain all sixty-four varying components through the owned raster stage" in {
    simulate(new Fp32TriangleRasterStage(VertexShaderStageConfig(),g)){d=>val t=new Driver(d)
      val triangle=base.copy(coords=Vector((BigInt(0),BigInt(0)),(BigInt(512),BigInt(0)),(BigInt(0),BigInt(512))),
        values=base.values.copy(slots=16,flat=0,values=Vector.tabulate(3,64)((v,c)=>fp((v+1)*(c+0.125)))))
      t.prepare(triangle);t.submit();t.until(d.io.completion.valid.peek().litToBoolean)
      // Two depth differences are shared by the quad. Each lane then uses
      // six weight/reciprocal operations, five origin-relative depth operations
      // and six operations for each of the64 smooth varying components.
      val operationsPerQuad=2+4*(6+5+64*6)
      d.io.completion.bits.arithmeticOperations.expect((operationsPerQuad*t.expected.size).U);t.finish()
      info("TRIANGLE_RASTER varyingComponents=64 allSmooth=true completeHelperQuads=true operationCount48=true operationsPerQuad1582=true")
    }
  }

}
