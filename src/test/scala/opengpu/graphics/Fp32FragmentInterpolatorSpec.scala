package opengpu.graphics

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.util.Random

private class FragmentEdgeFloatHarness extends Module {
  val io=IO(new Bundle {val edge=Input(SInt(64.W));val out=Output(new ClipFloat)})
  io.out:=FragmentEdgeFloat(io.edge)
}
private[graphics] object FragmentInterpolationReference {
  import Fp32ViewportReference._
  case class Sample(edges: Vector[BigInt],area: BigInt,mask: Int=1,x: Int=0,y: Int=0)
  case class Data(inv: Vector[E],depth: Vector[BigInt],values: Vector[Vector[BigInt]],flat: BigInt,slots: Int)
  case class Lane(reciprocal: E,depth: BigInt,values: Vector[BigInt],valid: Boolean)
  def edge(n: BigInt): E={
    if(n==0)return E()
    val a=n.abs;var exponent=a.bitLength-1
    val sig=if(exponent<=23)a<<(23-exponent)else {
      val shift=exponent-23;val q=a>>shift;val r=a- (q<<shift);val half=BigInt(1)<<(shift-1)
      q+(if(r>half || (r==half && q.testBit(0)))1 else 0)
    }
    if(sig.bitLength>24)E(n<0,exponent+1,sig>>1)else E(n<0,exponent,sig)
  }
  def linearDepth(edges: Vector[BigInt],area: BigInt,depth: Vector[BigInt]): E={
    val base=decode(depth.head)
    val terms=(1 to 2).map(i=>calc(edge(edges(i)),calc(decode(depth(i)),base,1),2))
    calc(base,calc(calc(terms(0),terms(1),0),edge(area),3),0)
  }
  def lane(d: Data,s: Sample): Lane={
    def sum(v: Vector[E]): E=calc(calc(v(0),v(1),0),v(2),0)
    val weights=s.edges.zip(d.inv).map{case(e,w)=>calc(edge(e),w,2)};val denominator=sum(weights)
    val inv=calc(denominator,edge(s.area),3);val valid=inv.sig!=0 && !inv.sign
    val z=clamp(pack(linearDepth(s.edges,s.area,d.depth)))
    val values=Vector.tabulate(d.values.head.size) { c=>
      if(c>=4*d.slots)BigInt(0)
      else if(d.flat.testBit(c))d.values(0)(c)
      else if(!valid)BigInt(0)
      else if(d.values.exists(v=>((v(c)>>23)&255)==255))BigInt("7fc00000",16)
      else pack(calc(sum(weights.zip(d.values).map{case(w,v)=>calc(w,decode(v(c)),2)}),denominator,3))
    }
    Lane(inv,z,values,valid)
  }
}

class Fp32FragmentInterpolatorSpec extends AnyFlatSpec {
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
  private def data(shader: VertexShaderStageConfig=p): Values=Values(
    Vector(1.0,0.25,2.0).map(x=>decode(fp(x))),Vector(0.1,0.6,0.9).map(fp),
    Vector.tabulate(3,shader.varyings*4)((v,c)=>if(c%3==0)BigInt("ffc01234",16)+c else fp((v+1)*(c-3.25))),
    (0 until shader.varyings*4).filter(_%3==0).foldLeft(BigInt(0))((m,c)=>m.setBit(c)),shader.varyings)
  private val samples=Vector(
    Sample(Vector(3,2,1).map(BigInt(_)),6,1,0,0),Sample(Vector(1,2,3).map(BigInt(_)),6,5,1,0),
    Sample(Vector(-1,3,4).map(BigInt(_)),6,0,0,1),Sample(Vector(0,0,6).map(BigInt(_)),6,8,1,1))
  private class Driver(val d: Fp32FragmentInterpolator,shader: VertexShaderStageConfig=p) {
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    var cycle=0;var accepted=0;var ready=true;var held: Option[BigInt]=None;var sequence=0
    var source=data(shader);var pixels=samples;var expected=Vector.empty[Lane]
    def tick(): Unit={
      d.io.out.ready.poke((ready && cycle%7<5).B)
      if(d.io.out.valid.peek().litToBoolean) {
        val bits=d.io.out.bits.peek().litValue;held.foreach(h=>assert(h==bits,"interpolated quad changed under stall"))
        if(d.io.out.ready.peek().litToBoolean) {
          assert(accepted==0);val o=d.io.out.bits
          o.sequence.expect(sequence.U);o.primitiveId.expect(17.U);o.instanceId.expect(2.U);o.partIndex.expect(1.U)
          o.identity.context.expect(7.U);o.identity.pass.expect(8.U);o.identity.generation.expect(9.U)
          o.varyingSlots.expect(source.slots.U);o.flatMask.expect(source.flat.U)
          for(i<-0 until 4) {
            val v=o.lanes(i);val e=expected(i);val s=pixels(i)
            v.x.expect(s.x.S);v.y.expect(s.y.S);v.covered.expect((s.mask!=0).B);v.coverageMask.expect(s.mask.U)
            Fp32ViewportReference.expect(v.reciprocalW,e.reciprocal);v.fragCoordW.expect(pack(e.reciprocal).U)
            v.windowDepth.expect(e.depth.U);v.perspectiveValid.expect(e.valid.B)
            for(c<-0 until shader.varyings*4) {
              val actual=v.varyings(c/4)(c%4).peek().litValue;val word=e.values(c)
              if(!source.flat.testBit(c) && ((word>>23)&255)==255)assert(((actual>>23)&255)==255 && (actual&0x7fffff)!=0)
              else assert(actual==word,s"lane=$i c=$c actual=$actual expected=$word")
            }
          }
          accepted+=1;held=None
        }else held=Some(bits)
      }else assert(held.isEmpty,"interpolated offer withdrawn")
      d.clock.step();cycle+=1
    }
    def until(condition: => Boolean): Unit={var n=0;while(!condition && n<100000){tick();n+=1};assert(condition,s"interpolation stalled cycle=$cycle")}
    def prepare(values: Values=data(shader),ss: Vector[Sample]=samples,model: Boolean=true): Unit={
      sequence+=1;accepted=0;source=values;pixels=ss;expected=if(model)ss.map(lane(values,_))else Vector.empty
      val q=d.io.in.bits;zero(q);val a=q.primitive.clipped.primitive
      a.identity.context.poke(7.U);a.identity.pass.poke(8.U);a.identity.generation.poke(9.U)
      a.sequence.poke(sequence.U);a.primitiveId.poke(17.U);a.instanceId.poke(2.U);a.baseInstance.poke(3.U)
      a.kind.poke(2.U);a.topology.poke(4.U);a.vertexCount.poke(3.U);a.provokingVertex.poke(2.U)
      a.varyingSlots.poke(values.slots.U);a.flatMask.poke(values.flat.U)
      q.primitive.clipped.partCount.poke(2.U);q.primitive.clipped.partIndex.poke(1.U)
      for(i<-0 until 3) {
        val r=a.vertices(i);r.identity.context.poke(7.U);r.identity.pass.poke(8.U);r.identity.generation.poke(9.U)
        r.instanceId.poke(2.U);r.baseInstance.poke(3.U)
        for(c<-0 until shader.varyings*4)r.varyings(c/4)(c%4).poke(values.values(i)(c).U)
        val e=values.inv(i);val w=q.primitive.vertices(i).reciprocalW
        w.negative.poke(e.sign.B);w.exponent.poke(e.exponent.S);w.significand.poke(e.sig.U);w.special.poke(0.U)
        q.primitive.vertices(i).windowDepth.poke(values.depth(i).U)
      }
      for(i<-0 until 4) {
        val s=ss(i);val r=q.quad.lanes(i)
        r.x.poke(s.x.S);r.y.poke(s.y.S);r.e0.poke(s.edges(0).S);r.e1.poke(s.edges(1).S);r.e2.poke(s.edges(2).S);r.area.poke(s.area.S)
        r.covered.poke((s.mask!=0).B);r.coverageMask.poke(s.mask.U)
      }
    }
    def submit(): Unit={d.io.in.valid.poke(true.B);until(d.io.in.ready.peek().litToBoolean);tick();d.io.in.valid.poke(false.B);d.io.in.bits.primitive.clipped.primitive.sequence.poke(999.U)}
    def finish(status: Int=0,outputs: Int=1): Unit={
      until(d.io.completion.valid.peek().litToBoolean);val r=d.io.completion.bits
      r.status.expect(status.U);r.sequence.expect(sequence.U);r.primitiveId.expect(17.U);r.instanceId.expect(2.U);r.partIndex.expect(d.io.in.bits.primitive.clipped.partIndex.peek())
      assert(r.output.peek().litToBoolean==(outputs==1),s"output flag=${r.output.peek().litValue} expected=$outputs status=$status seq=$sequence cycle=$cycle");assert(accepted==outputs,s"accepted=$accepted expected=$outputs status=$status seq=$sequence cycle=$cycle")
      if(status==0)r.invalidHelpers.expect(expected.zipWithIndex.filter(!_._1.valid).foldLeft(BigInt(0))((m,t)=>m.setBit(t._2)).U)
      val held=r.peek().litValue;for(_<-0 until 5){tick();d.io.in.ready.expect(false.B);assert(r.peek().litValue==held)}
      d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B);d.io.busy.expect(false.B)
    }
    def run(v: Values=data(shader),ss: Vector[Sample]=samples): Unit={prepare(v,ss);submit();finish()}
  }

  it should "convert full signed raster edges with exact RNE including ties and the signed minimum" in {
    simulate(new FragmentEdgeFloatHarness) { d=>
      val random=new Random(0x45444745L)
      val directed=Seq(BigInt(0),BigInt(1),BigInt(-1),-(BigInt(1)<<63),(BigInt(1)<<63)-1)++
        (24 until 63).flatMap(b=>Seq((BigInt(1)<<b)+(BigInt(1)<<(b-24)),(BigInt(1)<<b)+3*(BigInt(1)<<(b-24))))
      val values=directed++Vector.fill(1000)(BigInt(random.nextLong()))
      for(v<-values){d.io.edge.poke(v.S);Fp32ViewportReference.expect(d.io.out,edge(v))}
      info(s"FP32_INTERPOLATION edgeConversions=${values.size} signedMinimum=true tieEven=true")
    }
  }
  it should "interpolate perspective varyings linear depth raw flat values and all quad helper lanes" in {
    simulate(new Fp32FragmentInterpolator(p,g)) { d=>val t=new Driver(d)
      t.run();t.run(ss=samples.map(s=>s.copy(edges=s.edges.map(-_),area= -s.area)))
      t.run(data().copy(slots=1,flat=9));t.run(data().copy(slots=0,flat=0))
      val extreme=data().copy(inv=Vector(E(false,149,0x800000),E(false,-127,0x800000),E(false,20,0x800000)))
      t.run(extreme,samples.updated(2,samples(0).copy(mask=0)))
      for(area<-Seq(-(BigInt(1)<<63),(BigInt(1)<<63)-1))
        t.run(data(),Vector.tabulate(4)(i=>Sample(Vector(area,BigInt(0),BigInt(0)),area,if(i==3)0 else 1,i%2,i/2)))
      info("FP32_INTERPOLATION directedQuads=7 signed64Areas=true negativeArea=true helperExtrapolation=true flatNaNBits=true inactiveSlotsZero=true reciprocalExponent149=true")
    }
  }
  it should "match rational RNE evaluation of randomized covered and helper centers" in {
    simulate(new Fp32FragmentInterpolator(p,g)) { d=>val t=new Driver(d);val r=new Random(0x46524147L)
      for(_<-0 until 32) {
        val v=data().copy(inv=Vector.fill(3)(decode(fp(math.pow(2,r.nextInt(31)-15)*(1+r.nextDouble())))),
          values=Vector.tabulate(3,8)((_,_)=>fp((r.nextDouble()-0.5)*math.pow(2,r.nextInt(81)-40))),flat=BigInt(r.nextInt(256)))
        val ss=Vector.tabulate(4) { i=>val a=1+r.nextInt(1000000);val b=1+r.nextInt(1000000);val c=3000001-a-b
          Sample(Vector(a,b,c).map(BigInt(_)),BigInt(a+b+c),if(i==3)0 else 1<<i,i%2,i/2)
        };t.run(v,ss)
      }
      info("FP32_INTERPOLATION randomQuads=32 rationalRNE=true varyingW=true masksPreserved=true snapshots=true stalls=true")
    }
  }
  it should "preserve special smooth values and diagnose invalid helper perspective without losing quad layout" in {
    simulate(new Fp32FragmentInterpolator(p,g)) { d=>val t=new Driver(d)
      val v=data().copy(values=data().values.map(_.updated(1,BigInt("7fa12345",16))))
      t.run(v)
      val inv=Vector(1.0,2.0,1.0).map(x=>decode(fp(x)))
      val ss=samples.updated(2,Sample(Vector(18,-12,0).map(BigInt(_)),6,0,0,1))
      t.run(data().copy(inv=inv),ss)
      val zero=samples.updated(2,Sample(Vector(12,-6,0).map(BigInt(_)),6,0,0,1))
      t.run(data().copy(inv=inv),zero)
      t.prepare(data().copy(inv=inv),zero.updated(2,zero(2).copy(mask=1)),model=false);t.submit();t.finish(7,0)
      info("FP32_INTERPOLATION smoothNaN=true invalidHelperDenominators=2 helperLayoutPreserved=true coveredZeroDenominatorStatus7=true")
    }
  }
  it should "reject malformed primitive and sample layouts before output and permit healthy reuse" in {
    simulate(new Fp32FragmentInterpolator(p,g)) { d=>val t=new Driver(d)
      val q=d.io.in.bits
      val changes=Seq[()=>Unit](
        ()=>q.primitive.clipped.primitive.kind.poke(1.U),()=>q.primitive.clipped.primitive.vertexCount.poke(2.U),
        ()=>q.primitive.clipped.primitive.varyingSlots.poke(3.U),()=>{q.primitive.clipped.primitive.varyingSlots.poke(0.U)},
        ()=>q.primitive.clipped.partCount.poke(0.U),()=>q.primitive.clipped.partIndex.poke(2.U),
        ()=>q.primitive.clipped.primitive.vertices(2).identity.context.poke(99.U),
        ()=>q.primitive.vertices(0).reciprocalW.significand.poke(0.U),()=>q.primitive.vertices(1).reciprocalW.negative.poke(true.B),
        ()=>q.primitive.vertices(2).reciprocalW.special.poke(1.U),()=>q.primitive.vertices(0).windowDepth.poke("h7f800000".U),
        ()=>q.quad.lanes(0).area.poke(0.S),()=>q.quad.lanes(1).area.poke(7.S),()=>q.quad.lanes(2).e0.poke(8.S),
        ()=>q.quad.lanes(3).covered.poke(false.B))
      for(change<-changes){t.prepare(model=false);change();t.submit();t.finish(1,0);t.run()}
      info(s"FP32_INTERPOLATION invalidLayouts=${changes.size} preflight=true noPartialOutput=true healthyReuse=true")
    }
  }
  it should "drain accepted arithmetic on cancellation and retain offered quad and completion" in {
    simulate(new Fp32FragmentInterpolator(p,g)) { d=>val t=new Driver(d)
      for(offset<-Seq(0,5,17,60,200)) {
        t.prepare();t.submit();for(_<-0 until offset)t.tick();d.io.cancel.poke(true.B);t.tick();d.io.cancel.poke(false.B);t.finish(4,0);t.run()
      }
      t.prepare();t.ready=false;t.submit();t.until(d.io.out.valid.peek().litToBoolean)
      val held=d.io.out.bits.peek().litValue;d.io.cancel.poke(true.B)
      for(_<-0 until 10){t.tick();d.io.out.valid.expect(true.B);assert(d.io.out.bits.peek().litValue==held)}
      d.io.cancel.poke(false.B);t.ready=true;t.finish(4,1);t.run()
      info("FP32_INTERPOLATION cancelOffsets=5 arithmeticDrain=true heldQuad=true heldCompletion=true healthyReuse=true")
    }
  }
  it should "support the minimum one-slot one-sample interface" in {
    val shader=VertexShaderStageConfig(VertexInputFetchConfig(1,1),1)
    simulate(new Fp32FragmentInterpolator(shader,g.copy(maxSampleCount=1))) { d=>val t=new Driver(d,shader)
      t.run(data(shader),samples.zipWithIndex.map{case(s,i)=>s.copy(mask=if(i==0)1 else 0)})
      info("FP32_INTERPOLATION minimumVaryingSlots=1 sampleCapacity=1 helperLanes=3 noWideIndex=true")
    }
  }
  it should "interpolate the full sixty-four-component interface without truncating operation counts" in {
    val shader=VertexShaderStageConfig()
    simulate(new Fp32FragmentInterpolator(shader,g)) { d=>val t=new Driver(d,shader)
      val values=data(shader).copy(flat=0,values=Vector.tabulate(3,64)((v,c)=>fp((v+1)*(c+0.125))))
      t.prepare(values);t.submit();t.until(d.io.completion.valid.peek().litToBoolean)
      // Two shared differences plus four lanes of weight, depth and varying work.
      // 2 + 4*(6 + 5 + 64*6) = 1582; the counter must retain all sixteen bits.
      d.io.completion.bits.arithmeticOperations.expect(1582.U);t.finish()
      val positions=Vector(Vector(-1.0,-1.0,0.0,1.0),Vector(1.0,-1.0,0.0,1.0),Vector(-1.0,1.0,0.0,1.0))
      val colors=Vector(Vector(0.0,0.0,0.25,1.0),Vector(1.0,0.0,0.25,1.0),Vector(0.0,1.0,0.25,1.0))
      val affine=values.copy(inv=Vector.fill(3)(decode(fp(1.0))),depth=Vector.fill(3)(fp(0.5)),
        values=Vector.tabulate(3,64)((v,c)=>fp(if(c<4)positions(v)(c)else colors(v)(c%4)+(c/4)/64.0)))
      val centers=Vector.tabulate(4){i=>val x=i%2;val y=i/2
        Sample(Vector(64-(2*x+1)-(2*y+1),2*x+1,2*y+1).map(BigInt(_)),64,1,x,y)}
      for((s,expected)<-centers.map(s=>s->lane(affine,s));c<-0 until 64){val u=(s.x+0.5)/32;val v=(s.y+0.5)/32
        val exact=fp(if(c<4)Vector(-1+2*u,-1+2*v,0.0,1.0)(c)else Vector(u,v,0.25,1.0)(c%4)+(c/4)/64.0)
        assert(expected.values(c)==exact,s"affine reference c=$c x=${s.x} y=${s.y}")}
      t.prepare(affine,centers);val start=t.cycle;t.submit();t.until(d.io.out.valid.peek().litToBoolean)
      val outputLatency=t.cycle-start;t.finish()
      info(s"FP32_INTERPOLATION varyingComponents=64 lanes=4 arithmeticOperations=1582 fullInterface=true exactAffineRows=true affineOutputLatency=$outputLatency")
    }
  }
}
