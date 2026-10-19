package opengpu.graphics

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.util.Random

private[graphics] object Fp32ViewportReference {
  case class E(sign: Boolean=false,exponent: Int=0,sig: BigInt=0)
  case class View(x: Int=0,y: Int=0,width: Long=1280,height: Long=720,near: BigInt=0,far: BigInt=0x3f800000)
  case class Prepared(localX: E,localY: E,x: BigInt,y: BigInt,valid: Boolean,inexact: Boolean,reciprocal: E,depth: BigInt)
  def fp(value: Double): BigInt=BigInt(java.lang.Float.floatToRawIntBits(value.toFloat).toLong&0xffffffffL)
  def number(bits: BigInt): Double=java.lang.Float.intBitsToFloat(bits.toInt).toDouble
  def decode(bits: BigInt): E={
    val sign=bits.testBit(31);val exponent=((bits>>23)&255).toInt;val fraction=bits&0x7fffff
    require(exponent!=255)
    if(exponent==0){if(fraction==0)E(sign)else {val shift=24-fraction.bitLength;E(sign,-126-shift,fraction<<shift)}}
    else E(sign,exponent-127,fraction|0x800000)
  }
  private def rounded(n: BigInt,d: BigInt): (BigInt,Boolean)={
    val(q,r)=n /% d;(q+(if(r*2>d || (r*2==d && q.testBit(0)))1 else 0),r!=0)
  }
  private def rational(v: E): (BigInt,BigInt)={val e=v.exponent-23;val n=v.sig*(if(v.sign)-1 else 1);if(e>=0)(n<<e,BigInt(1))else(n,BigInt(1)<<(-e))}
  private def normalize(n: BigInt,d: BigInt,negativeZero: Boolean): E={
    if(n==0)return E(negativeZero)
    val a=n.abs;val b=d.abs;var e=a.bitLength-b.bitLength
    if(if(e>=0)a<(b<<e)else(a<<(-e))<b)e-=1
    val q=(if(23-e>=0)rounded(a<<(23-e),b)else rounded(a,b<<(e-23)))._1
    if(q.bitLength>24)E(n.signum!=d.signum,e+1,q>>1)else E(n.signum!=d.signum,e,q)
  }
  def calc(a: E,b: E,operation: Int): E={
    val(an,ad)=rational(a);val(bn,bd)=rational(b)
    operation match {
      case 0=>normalize(an*bd+bn*ad,ad*bd,a.sig==0 && b.sig==0 && a.sign && b.sign)
      case 1=>normalize(an*bd-bn*ad,ad*bd,a.sig==0 && b.sig==0 && a.sign && !b.sign)
      case 2=>normalize(an*bn,ad*bd,a.sign^b.sign)
      case 3=>normalize(an*bd,ad*bn,a.sign^b.sign)
    }
  }
  def pack(v: E): BigInt={
    val sign=if(v.sign)BigInt(1)<<31 else BigInt(0)
    if(v.sig==0)sign
    else if(v.exponent>127)sign|BigInt("7f800000",16)
    else if(v.exponent>= -126)sign|(BigInt(v.exponent+127)<<23)|(v.sig&0x7fffff)
    else sign|rounded(v.sig,BigInt(1)<<(-126-v.exponent))._1
  }
  def scale(v: E,shift: Int): E=if(v.sig==0)v else v.copy(exponent=v.exponent+shift)
  def clamp(bits: BigInt): BigInt=if(number(bits)<0)BigInt(0)else if(number(bits)>1)fp(1)else bits
  def normalized(v: View,c: Fp32ViewportConfig): View=v.copy(width=v.width min c.maxWidth,height=v.height min c.maxHeight,near=clamp(v.near),far=clamp(v.far))
  def fixed(local: E,origin: Int,subpixels: Int): (BigInt,Boolean,Boolean)={
    val v=scale(local,subpixels);val(n,d)=rational(v);val(q,inexact)=rounded(n.abs,d)
    val value=q*(if(v.sign)-1 else 1)+(BigInt(origin)<<subpixels)
    val valid=value>= -(BigInt(1)<<63) && value<(BigInt(1)<<63)
    (if(valid)value else BigInt(0),valid,inexact)
  }
  def project(position: Vector[BigInt],view: View,c: Fp32ViewportConfig): Prepared={
    val v=normalized(view,c);val w=decode(position(3));val reciprocal=calc(decode(fp(1)),w,3)
    def coordinate(bits: BigInt,extent: Long): E={
      val sum=calc(decode(bits),w,0);val quotient=calc(sum,w,3)
      calc(quotient,scale(decode(fp(extent.toDouble)),-1),2)
    }
    val lx=coordinate(position(0),v.width);val ly=coordinate(position(1),v.height)
    val(x,xValid,xInexact)=fixed(lx,v.x,c.subPixelBits);val(y,yValid,yInexact)=fixed(ly,v.y,c.subPixelBits)
    val nz=number(position(2));val wz=number(position(3))
    val depth=if(number(v.near)==number(v.far) || nz== -wz)v.near else if(nz==wz)v.far else {
      val span=calc(decode(v.far),decode(v.near),1)
      val sum=calc(decode(position(2)),w,0);val fraction=scale(calc(sum,w,3),-1)
      val result=pack(calc(calc(fraction,span,2),decode(v.near),0))
      val low=if(number(v.near)<number(v.far))v.near else v.far;val high=if(number(v.near)<number(v.far))v.far else v.near
      if(number(result)<number(low))low else if(number(result)>number(high))high else result
    }
    Prepared(lx,ly,x,y,xValid&&yValid,xInexact||yInexact,reciprocal,depth)
  }
  def expect(v: ClipFloat,e: E): Unit={v.negative.expect(e.sign.B);v.exponent.expect(e.exponent.S);v.significand.expect(e.sig.U);v.special.expect(0.U)}
}

class Fp32ViewportStageSpec extends AnyFlatSpec {
  import Fp32ViewportReference._
  private val p=VertexShaderStageConfig(VertexInputFetchConfig(1,1),2)
  private val c=Fp32ViewportConfig()
  private def zero(data: Data): Unit=data match {
    case r: Record=>r.elements.values.foreach(zero)
    case v: Vec[_]=>v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case _=>()
  }
  private class Driver(val d: Fp32ViewportStage,config: Fp32ViewportConfig=c,shader: VertexShaderStageConfig=p) {
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    var cycle=0;var ready=true;var sequence=0;var accepted=0;var held: Option[BigInt]=None
    var expected=Vector.empty[Prepared];var positions=Vector.empty[Vector[BigInt]];var view=View();var kind=2
    var original=BigInt(0);var check=true
    def tick(): Unit={
      d.io.out.ready.poke((ready && cycle%7<5).B)
      if(d.io.out.valid.peek().litToBoolean) {
        val bits=d.io.out.bits.peek().litValue;held.foreach(value=>assert(value==bits,"offered viewport primitive changed"))
        if(d.io.out.ready.peek().litToBoolean) {
          assert(check && accepted==0);val o=d.io.out.bits
          assert(o.clipped.peek().litValue==original,"viewport changed clipped vertices or varying metadata")
          val normalizedView=normalized(view,config)
          o.viewport.x.expect(view.x.S);o.viewport.y.expect(view.y.S)
          o.viewport.extentX.expect(normalizedView.width.U);o.viewport.extentY.expect(normalizedView.height.U)
          o.viewport.nearDepth.expect(normalizedView.near.U);o.viewport.farDepth.expect(normalizedView.far.U)
          o.guardBandRequired.expect(expected.exists(!_.valid).B)
          for((e,i)<-expected.zipWithIndex) {
            val v=o.vertices(i);Fp32ViewportReference.expect(v.localX,e.localX);Fp32ViewportReference.expect(v.localY,e.localY)
            Fp32ViewportReference.expect(v.reciprocalW,e.reciprocal)
            v.screenX.expect(e.x.S);v.screenY.expect(e.y.S);v.fixedValid.expect(e.valid.B)
            v.coordinateInexact.expect(e.inexact.B);v.windowDepth.expect(e.depth.U)
          }
          for(i<-expected.size until 3)assert(o.vertices(i).peek().litValue==0)
          accepted+=1;held=None
        }else held=Some(bits)
      }else assert(held.isEmpty,"viewport offer withdrawn")
      d.clock.step();cycle+=1
    }
    def until(condition: => Boolean): Unit={var n=0;while(!condition && n<5000){tick();n+=1};assert(condition,s"viewport stalled sequence=$sequence cycle=$cycle")}
    def prepare(values: Vector[Vector[BigInt]],v: View=View(),primitiveKind: Int=2,model: Boolean=true): Unit={
      sequence+=1;accepted=0;positions=values;view=v;kind=primitiveKind;check=model
      expected=if(model && v.width!=0 && v.height!=0)values.map(project(_,view,config))else Vector.empty
      val q=d.io.in.bits;zero(q);val a=q.clipped.primitive
      a.identity.context.poke(7.U);a.identity.pass.poke(8.U);a.identity.generation.poke(9.U)
      a.sequence.poke(sequence.U);a.primitiveId.poke(17.U);a.instanceId.poke(2.U);a.baseInstance.poke(3.U)
      a.kind.poke(kind.U);a.topology.poke((if(kind==0)0 else if(kind==1)1 else 4).U);a.vertexCount.poke((kind+1).U)
      a.provokingVertex.poke(kind.U);a.varyingSlots.poke(shader.varyings.U);a.flatMask.poke(((BigInt(1)<<(4*shader.varyings))-1).U)
      q.clipped.partCount.poke(1.U);q.clipped.sourceProvokingOrdinal.poke((100+kind).U);q.clipped.sourceProvokingVertexId.poke((10+kind).U)
      for((position,i)<-values.zipWithIndex) {
        val r=a.vertices(i);r.identity.context.poke(7.U);r.identity.pass.poke(8.U);r.identity.generation.poke(9.U)
        r.ordinal.poke((100+i).U);r.vertexId.poke((10+i).U);r.instanceId.poke(2.U);r.baseInstance.poke(3.U)
        for(j<-0 until 4)r.position(j).poke(position(j).U)
        for(j<-0 until 4*shader.varyings)r.varyings(j/4)(j%4).poke((BigInt("ffc01234",16)+i*64+j).U)
      }
      q.viewport.version.poke(1.U);q.viewport.x.poke(v.x.S);q.viewport.y.poke(v.y.S)
      q.viewport.extentX.poke(v.width.U);q.viewport.extentY.poke(v.height.U);q.viewport.nearDepth.poke(v.near.U);q.viewport.farDepth.poke(v.far.U)
      original=q.clipped.peek().litValue
    }
    def submit(): Unit={
      d.io.in.valid.poke(true.B);until(d.io.in.ready.peek().litToBoolean);tick();d.io.in.valid.poke(false.B)
      d.io.in.bits.viewport.extentX.poke(0.U);d.io.in.bits.clipped.primitive.sequence.poke(999.U)
    }
    def finish(status: Int=0): Unit={
      until(d.io.completion.valid.peek().litToBoolean);val r=d.io.completion.bits
      r.status.expect(status.U);r.output.expect((accepted==1).B);r.sequence.expect(sequence.U);r.primitiveId.expect(17.U);r.instanceId.expect(2.U)
      if(status==0) {
        r.culled.expect((view.width==0 || view.height==0).B)
        r.guardBandRequired.expect(expected.exists(!_.valid).B);r.coordinateInexact.expect(expected.exists(_.inexact).B)
        assert(accepted==(if(view.width==0 || view.height==0)0 else 1))
      }
      val bits=r.peek().litValue
      for(_<-0 until 7){d.io.in.ready.expect(false.B);tick();assert(r.peek().litValue==bits)}
      d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B);d.io.busy.expect(false.B)
    }
    def run(values: Vector[Vector[BigInt]],v: View=View(),kind: Int=2): Unit={prepare(values,v,kind);submit();finish()}
  }
  private def position(x: Double,y: Double,z: Double,w: Double): Vector[BigInt]=Vector(x,y,z,w).map(fp)
  private val triangle=Vector(position(-1,-1,-1,1),position(1,-1,0,1),position(0,1,1,1))

  it should "map perspective coordinates depth ranges and exact integer origins while preserving varyings" in {
    simulate(new Fp32ViewportStage(p,c)) { d=>
      val t=new Driver(d)
      val views=Seq(View(),View(-31,17,1279,719,fp(0.1),fp(0.9)),View(Int.MaxValue,Int.MinValue,16384,16384,fp(0.75),fp(0.25)),
        View(123,-456,13,7,fp(0.12345),fp(0.12345)),View(0,0,2147483647L,65535,fp(-2),fp(2)),
        View(1,2,13,17,BigInt("ff800000",16),BigInt("7f800000",16)))
      for(view<-views)t.run(triangle,view)
      t.run(Vector(position(-0.5,0.25,-0.25,0.5),position(1,-1,0,2),position(0,3,2,4)),views(1))
      info("FP32_VIEWPORT directedCases=7 perspective=true reverseDepth=true endpointCopies=true depthClamp=true dimensionClamp=true exactOrigins=true flatRawBits=true snapshots=true")
    }
  }

  it should "retain extreme reciprocal W and distinguish guard-band coordinates without truncation" in {
    simulate(new Fp32ViewportStage(p,c)) { d=>
      val t=new Driver(d);val small=BigInt(1);val negSmall=BigInt("80000001",16);val maximum=BigInt("7f7fffff",16)
      t.run(Vector(Vector(negSmall,negSmall,negSmall,small),Vector(small,negSmall,BigInt(0),small),Vector(BigInt(0),small,small,small)))
      assert(t.expected.forall(_.reciprocal.exponent==149))
      t.run(Vector(Vector(maximum,BigInt(0),BigInt(0),small)),kind=0);assert(!t.expected.head.valid)
      val large=math.pow(2,55)
      for((x,origin,valid)<-Seq((large,0,false),(large,-1,true),(-large,0,true),(-large,-1,false),(-large,1,true))) {
        t.run(Vector(position(x,0,0,1)),View(origin,0,2,2),0);assert(t.expected.head.valid==valid)
      }
      t.run(Vector(Vector(maximum,BigInt(0),BigInt(0),maximum)),kind=0)
      info("FP32_VIEWPORT reciprocalExponent=149 extendedGuardCoordinates=true signed64Boundaries=true originRescuesRange=true noWrappedFixed=true")
    }
  }

  it should "match rational viewport preparation for finite randomized coordinates and RNE subpixels" in {
    simulate(new Fp32ViewportStage(p,c)) { d=>
      val t=new Driver(d);val random=new Random(0x56504f52L)
      for(i<-0 until 48) {
        val values=Vector.tabulate(3) { _=>val w=(random.nextInt(16)+1)/4.0
          position((random.nextInt(17)-8)*w/8.0,(random.nextInt(17)-8)*w/8.0,(random.nextInt(17)-8)*w/8.0,w)
        }
        t.run(values,View(random.nextInt(2001)-1000,random.nextInt(2001)-1000,random.nextInt(16384)+1,random.nextInt(16384)+1,
          fp((i%7)/8.0),fp(((i*3)%9)/8.0)))
      }
      info("FP32_VIEWPORT randomTriangles=48 rationalRNE=true fractionalCoordinates=true varyingW=true reversedAndConstantDepth=true")
    }
  }

  it should "cull empty views and reject invalid descriptors and perspective inputs before publishing output" in {
    simulate(new Fp32ViewportStage(p,c)) { d=>
      val t=new Driver(d)
      for(view<-Seq(View(width=0),View(height=0))) {t.prepare(triangle,view);t.submit();t.until(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.arithmeticOperations.expect(0.U);t.finish()}
      val q=d.io.in.bits
      val invalid=Seq[()=>Unit](()=>q.viewport.version.poke(0.U),()=>q.viewport.extentX.poke("h80000000".U),()=>q.viewport.extentY.poke("hffffffff".U),
        ()=>q.viewport.nearDepth.poke("h7fc00000".U),()=>q.viewport.farDepth.poke("hffc00000".U),()=>q.clipped.partCount.poke(0.U),
        ()=>q.clipped.partIndex.poke(1.U),()=>q.clipped.primitive.kind.poke(3.U),()=>q.clipped.primitive.vertexCount.poke(2.U),
        ()=>q.clipped.primitive.vertices(1).identity.context.poke(999.U),()=>q.clipped.generated.poke(1.U))
      for(adjust<-invalid){t.prepare(triangle,model=false);adjust();t.submit();t.finish(1);assert(t.accepted==0)}
      for((word,index)<-Seq(BigInt(0)->0,BigInt("80000000",16)->1,fp(-1)->2,BigInt("7f800000",16)->1)) {
        t.prepare(triangle,model=false);q.clipped.primitive.vertices(index).position(3).poke(word.U);t.submit()
        t.until(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.faultVertex.expect(index.U);t.finish(7);assert(t.accepted==0)
      }
      t.prepare(triangle,model=false);q.clipped.primitive.vertices(2).position(0).poke(fp(2).U);t.submit();t.finish(7)
      t.run(triangle)
      info("FP32_VIEWPORT emptyViews=2 invalidLayouts=11 invalidPerspective=5 noPartialOutput=true faultVertex=true healthyReuse=true")
    }
  }

  it should "drain accepted arithmetic through cancellation and retain an offered window primitive" in {
    simulate(new Fp32ViewportStage(p,c)) { d=>
      val t=new Driver(d)
      for(delay<-Seq(0,3,12,37,70)) {
        t.prepare(triangle,model=false);t.ready=false;t.submit();for(_<-0 until delay)t.tick()
        d.io.cancel.poke(true.B);t.until(d.io.completion.valid.peek().litToBoolean);d.io.cancel.poke(false.B);t.finish(4);assert(t.accepted==0)
      }
      t.prepare(triangle);t.ready=false;t.submit();t.until(d.io.out.valid.peek().litToBoolean);val bits=d.io.out.bits.peek().litValue
      d.io.cancel.poke(true.B);for(_<-0 until 13){t.tick();assert(d.io.out.bits.peek().litValue==bits);d.io.completion.valid.expect(false.B)}
      t.ready=true;t.until(d.io.completion.valid.peek().litToBoolean);d.io.cancel.poke(false.B);t.finish(4);assert(t.accepted==1)
      t.run(triangle)
      info("FP32_VIEWPORT cancelOffsets=5 mathDrain=true heldWindowPrimitive=true healthyReuse=true")
    }
  }

  it should "preserve all sixty-four varying components at default limits" in {
    val shader=VertexShaderStageConfig()
    simulate(new Fp32ViewportStage(shader,c)) { d=>
      val t=new Driver(d,c,shader);t.run(triangle,View(-17,31,1280,720,fp(0.25),fp(0.75)))
      info("FP32_VIEWPORT varyingComponents=64 metadataPreserved=true defaultLimits=true")
    }
  }
}
