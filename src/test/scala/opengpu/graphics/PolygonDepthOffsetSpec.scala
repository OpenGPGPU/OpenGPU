package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.util.Random

private class PolygonDepthOffsetHarness extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new PolygonDepthOffsetRequest));val out=Decoupled(new PolygonDepthOffsetResponse)
    val cancel=Input(Bool());val busy=Output(Bool())
    val blockRequest=Input(Bool());val blockResponse=Input(Bool())
    val requestOffered=Output(Bool());val responseOffered=Output(Bool())
    val requestBits=Output(new ClipFloatRequest(8));val accepted=Output(UInt(32.W))
  })
  val owner=Module(new PolygonDepthOffset());val math=Module(new ClipFloatArithmetic())
  owner.io.in<>io.in;io.out<>owner.io.out;owner.io.cancel:=io.cancel;io.busy:=owner.io.busy
  math.io.in.valid:=owner.io.mathRequest.valid && !io.blockRequest;math.io.in.bits:=owner.io.mathRequest.bits
  owner.io.mathRequest.ready:=math.io.in.ready && !io.blockRequest
  owner.io.mathResponse.valid:=math.io.out.valid && !io.blockResponse;owner.io.mathResponse.bits:=math.io.out.bits
  math.io.out.ready:=owner.io.mathResponse.ready && !io.blockResponse;math.io.cancel:=owner.io.mathCancel
  val accepted=RegInit(0.U(32.W));when(math.io.in.fire){accepted:=accepted+1.U}
  io.accepted:=accepted;io.requestOffered:=owner.io.mathRequest.valid;io.requestBits:=owner.io.mathRequest.bits
  io.responseOffered:=math.io.out.valid
}

private[graphics] object PolygonDepthOffsetReference {
  import Fp32ViewportReference.{E,decode,calc,rational}
  def maximum(x: Vector[BigInt],y: Vector[BigInt],z: Vector[BigInt],subPixelBits: Int=8): E={
    val area=(x(1)-x(0))*(y(2)-y(0))-(y(1)-y(0))*(x(2)-x(0))
    val delta=(1 to 2).map(i=>calc(decode(z(i)),decode(z(0)),1))
    val slopes=Vector.tabulate(2){axis=>
      val terms=(1 to 2).map{i=>val a=(i+1)%3;val b=(i+2)%3
        val coefficient=(if(axis==0)y(a)-y(b)else x(b)-x(a))*(BigInt(1)<<subPixelBits)
        calc(FragmentInterpolationReference.edge(coefficient),delta(i-1),2)}
      calc(calc(terms(0),terms(1),0),FragmentInterpolationReference.edge(area),3).copy(sign=false)
    }
    val(xn,xd)=rational(slopes(0));val(yn,yd)=rational(slopes(1))
    if(xn*yd>yn*xd)slopes(0)else slopes(1)
  }
}

class PolygonDepthOffsetSpec extends AnyFlatSpec {
  import Fp32ViewportReference.{E,fp,decode,calc,rational}
  private case class Case(x: Vector[BigInt],y: Vector[BigInt],z: Vector[BigInt],factor: BigInt=fp(2),units: BigInt=fp(3),resolution: BigInt=fp(math.pow(2,-24)))
  private val base=Case(Vector(0,2048,0).map(BigInt(_)),Vector(0,0,2048).map(BigInt(_)),Vector(0.25,0.5,0.75).map(fp))
  private def reference(c: Case): (E,E)={
    val m=PolygonDepthOffsetReference.maximum(c.x,c.y,c.z)
    (calc(calc(m,decode(c.factor),2),calc(decode(c.resolution),decode(c.units),2),0),m)
  }
  private def read(v: ClipFloat): E={v.special.expect(0.U);E(v.negative.peek().litToBoolean,v.exponent.peek().litValue.toInt,v.significand.peek().litValue)}
  private class Driver(val d: PolygonDepthOffsetHarness){
    PointLineReference.zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    var sequence=0
    def tick(): Unit=d.clock.step()
    def until(condition: =>Boolean): Unit={var n=0;while(!condition && n<4000){tick();n+=1};assert(condition,s"polygon offset stalled after $n cycles")}
    def offer(c: Case,enabled: Boolean=true,kind: Int=2): Unit={
      sequence+=1;val q=d.io.in.bits;PointLineReference.zero(q)
      q.identity.context.poke(7.U);q.identity.pass.poke(8.U);q.identity.generation.poke(9.U)
      q.sequence.poke(sequence.U);q.primitiveId.poke((100+sequence).U);q.enabled.poke(enabled.B);q.primitiveKind.poke(kind.U)
      for(i<-0 until 3){q.x(i).poke(c.x(i).S);q.y(i).poke(c.y(i).S);q.depth(i).poke(c.z(i).U)}
      q.factor.poke(c.factor.U);q.units.poke(c.units.U);q.resolution.poke(c.resolution.U)
      d.io.in.valid.poke(true.B);until(d.io.in.ready.peek().litToBoolean);tick();d.io.in.valid.poke(false.B)
      q.sequence.poke(0.U);q.factor.poke("h7fc00000".U);q.resolution.poke(0.U)
    }
    def finish(expected: E=E(),slope: E=E(),status: Int=0,operations: Int=13): Unit={
      until(d.io.out.valid.peek().litToBoolean);val r=d.io.out.bits
      r.identity.context.expect(7.U);r.identity.pass.expect(8.U);r.identity.generation.expect(9.U)
      r.sequence.expect(sequence.U);r.primitiveId.expect((100+sequence).U);r.status.expect(status.U)
      r.arithmeticOperations.expect(operations.U)
      assert(read(r.offset)==expected,s"offset ${read(r.offset)} != $expected")
      assert(read(r.maximumSlope)==slope,s"slope ${read(r.maximumSlope)} != $slope")
      val held=r.peek().litValue
      for(i<-0 until 5){d.io.cancel.poke((i%2==0).B);tick();d.io.busy.expect(true.B);d.io.in.ready.expect(false.B);assert(r.peek().litValue==held)}
      d.io.cancel.poke(false.B);d.io.out.ready.poke(true.B);tick();d.io.out.ready.poke(false.B);d.io.busy.expect(false.B)
    }
    def run(c: Case): Unit={val(offset,slope)=reference(c);offer(c);finish(offset,slope)}
  }
  it should "preserve zero slope for constant polygon depth with large factors and shared-service reuse" in {
    simulate(new PolygonDepthOffsetHarness){d=>val t=new Driver(d);var cases=0
      val x=Vector(0,128000,25600).map(BigInt(_));val y=Vector(0,25600,102400).map(BigInt(_))
      val depths=Vector(BigInt("3f000001",16),BigInt("3f000002",16),BigInt("3f400001",16),BigInt(1))
      for(z<-depths;reverse<-Seq(false,true);sign<-Seq(1,-1);units<-Seq(0,3)){
        val c=Case(if(reverse)x.reverse else x,if(reverse)y.reverse else y,Vector.fill(3)(z),
          factor=fp(sign*math.scalb(1.0,20)),units=fp(units))
        val expected=if(units==0)E()else calc(decode(c.resolution),decode(c.units),2)
        t.offer(c);t.finish(expected,E());cases+=1
      }
      info(s"POLYGON_CONSTANT_DEPTH cases=$cases levels=4 windings=2 signs=2 unitsModes=2 flatSlopeZero=true factorMagnitude1048576=true resolutionTermPreserved=true heldCompletion=true sharedScalarService=true healthyReuse=true")
    }
  }
  it should "compute signed winding-independent plane slopes and extended offsets with exact rounded arithmetic" in {
    simulate(new PolygonDepthOffsetHarness){d=>val t=new Driver(d);val random=new Random(0x504f4c59)
      val variants=Vector(base,base.copy(factor=fp(-2),units=fp(-3)),base.copy(z=Vector.fill(3)(fp(0.5))),
        base.copy(x=base.x.reverse,y=base.y.reverse,z=base.z.reverse),
        base.copy(x=base.x.map(_-0x70000000L),y=base.y.map(_+0x70000000L)),
        base.copy(factor=fp(0),units=fp(1),resolution=BigInt(1)),
        base.copy(x=Vector(0,1,0).map(BigInt(_)),y=Vector(0,0,1).map(BigInt(_)),factor=BigInt("7f7fffff",16)))
      val(expected,slope)=reference(base);assert(slope==decode(fp(0.0625)));assert(expected==calc(decode(fp(0.125)),decode(fp(3*math.pow(2,-24))),0))
      variants.foreach(t.run)
      for(_<-0 until 80){
        val x=Vector(BigInt(0),BigInt(64+random.nextInt(4096)),BigInt(random.nextInt(64)))
        val y=Vector(BigInt(0),BigInt(random.nextInt(64)),BigInt(64+random.nextInt(4096)))
        val c=Case(x,y,Vector.fill(3)(fp(random.nextInt(1025)/1024.0)),fp((random.nextInt(33)-16)/4.0),fp(random.nextInt(129)-64))
        t.run(c)
      }
      info("POLYGON_OFFSET cases=87 signedSlopes=true bothWindings=true translatedCoordinates=true mixedFactorUnits=true subnormalResolution=true extendedOverflowRetained=true exactRNE=true sharedScalarService=true")
    }
  }
  it should "reject malformed numeric state and wide areas before scalar work while bypassing non-polygons" in {
    simulate(new PolygonDepthOffsetHarness){d=>val t=new Driver(d)
      val bad=Vector(base.copy(factor=BigInt("7fc00000",16)),base.copy(factor=BigInt("7f800000",16)),
        base.copy(units=BigInt("ff800000",16)),base.copy(resolution=0),base.copy(resolution=fp(-1)),base.copy(resolution=fp(2)),
        base.copy(z=base.z.updated(0,fp(-0.125))),base.copy(z=base.z.updated(1,fp(1.125))),base.copy(z=base.z.updated(2,BigInt("7fc00000",16))))
      bad.foreach{c=>t.offer(c);t.finish(status=1,operations=0)}
      for(kind<-Seq(0,1)){t.offer(bad.head,kind=kind);t.finish(operations=0)}
      t.offer(bad.head,enabled=false);t.finish(operations=0)
      t.offer(base,kind=3);t.finish(status=1,operations=0)
      t.offer(base.copy(x=Vector.fill(3)(BigInt(0)),y=Vector.fill(3)(BigInt(0))));t.finish(operations=0)
      t.offer(base.copy(factor=0,units=BigInt("80000000",16)));t.finish(operations=0)
      val lo= -(BigInt(1)<<31);val hi=(BigInt(1)<<31)-1
      t.offer(base.copy(x=Vector(lo,hi,lo),y=Vector(lo,lo,hi)));t.finish(status=7,operations=0)
      t.run(base)
      info("POLYGON_OFFSET_VALIDATION malformed=9 nonPolygonBypass=true disabledBypass=true reservedKindRejected=true degenerateZero=true signedZeroFactors=true wideAreaRejected=true noInvalidMath=true healthyReuse=true")
    }
  }
  it should "retain offered and accepted scalar ownership through cancellation and immutable completion" in {
    simulate(new PolygonDepthOffsetHarness){d=>val t=new Driver(d)
      for(blockOffer<-Seq(true,false)){
        d.io.blockRequest.poke(blockOffer.B);d.io.blockResponse.poke(true.B)
        val count=d.io.accepted.peek().litValue;t.offer(base)
        if(blockOffer){
          t.until(d.io.requestOffered.peek().litToBoolean);val held=d.io.requestBits.peek().litValue
          d.io.cancel.poke(true.B)
          for(_<-0 until 7){t.tick();d.io.requestOffered.expect(true.B);assert(d.io.requestBits.peek().litValue==held);d.io.out.valid.expect(false.B)}
          d.io.blockRequest.poke(false.B)
        }
        t.until(d.io.accepted.peek().litValue==count+1);d.io.cancel.poke(true.B)
        t.until(d.io.responseOffered.peek().litToBoolean)
        for(_<-0 until 7){t.tick();d.io.busy.expect(true.B);d.io.out.valid.expect(false.B)}
        d.io.cancel.poke(false.B);d.io.blockResponse.poke(false.B);t.finish(status=4,operations=1)
        t.run(base)
      }
      info("POLYGON_OFFSET_DRAIN offeredAndAccepted=true noWithdrawnMath=true acceptedReplyDrains=true noEarlyCompletion=true requestSnapshot=true immutableCompletion=true exactHealthyReuse=true")
    }
  }
  it should "retain exact signed determinants and rounded coefficients at coordinate boundaries" in {
    simulate(new PolygonDepthOffsetHarness){d=>val t=new Driver(d)
      val lo= -(BigInt(1)<<31);val hi=(BigInt(1)<<31)-1
      val boundaries=Vector(
        base.copy(x=Vector(lo,hi,lo),y=Vector(lo,lo,lo+1)),
        base.copy(x=Vector(lo,0,lo),y=Vector(lo,lo,hi)),
        // Exact -2^63: positive 2^63 would be outside the signed64 contract.
        base.copy(x=Vector(-2,-4,hi-1),y=Vector(lo,hi-1,0)))
      for((c,i)<-boundaries.zipWithIndex){
        val area=(c.x(1)-c.x(0))*(c.y(2)-c.y(0))-(c.y(1)-c.y(0))*(c.x(2)-c.x(0))
        if(i==2)assert(area== -(BigInt(1)<<63))
        t.run(c)
        if(i!=2)t.run(c.copy(x=c.x.reverse,y=c.y.reverse,z=c.z.reverse))
      }
      t.offer(boundaries.last.copy(x=boundaries.last.x.reverse,y=boundaries.last.y.reverse));t.finish(status=7,operations=0)
      t.offer(base.copy(x=Vector(lo,hi,lo),y=Vector(lo,lo,hi),factor=0,units=0));t.finish(status=7,operations=0)
      t.run(base.copy(z=Vector(BigInt("80000000",16),fp(0.5),fp(1))))
      info("POLYGON_PIPELINED_GEOMETRY exactSigned64Minimum=true signed33Differences=true bothWindings=true roundedWideCoefficients=true overflowBeforeZeroFactor=true negativeZeroDepth=true")
    }
  }
  it should "cancel every geometry preparation boundary before scalar issue and reuse cleanly" in {
    simulate(new PolygonDepthOffsetHarness){d=>val t=new Driver(d)
      for(delay<-0 to 10){
        val count=d.io.accepted.peek().litValue;t.offer(base)
        for(_<-0 until delay)t.tick()
        d.io.cancel.poke(true.B);t.tick();d.io.cancel.poke(false.B)
        t.finish(status=4,operations=0);d.io.accepted.expect(count.U)
      }
      t.run(base)
    }
  }
}
