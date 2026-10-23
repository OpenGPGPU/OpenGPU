package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

/** Differential composition against the unchanged scalar implementation. */
private class FragmentPreparationPair(p: VertexShaderStageConfig, g: GraphicsConfig) extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new FragmentInterpolationRequest(p,g)))
    val original=Decoupled(new Fp32InterpolatedQuad(p,g))
    val spatial=Decoupled(new Fp32InterpolatedQuad(p,g))
    val originalCompletion=Decoupled(new FragmentInterpolationCompletion)
    val spatialCompletion=Decoupled(new FragmentInterpolationCompletion)
    val cancel=Input(Bool());val busy=Output(Vec(2,Bool()))
    val transferred=Output(Bool())
  })
  private val original=Module(new Fp32FragmentInterpolator(p,g))
  private val preparation=Module(new Fp32FragmentPreparation(p,g))
  private val spatial=Module(new Fp32PreparedQuadInterpolator(p,g,reservedInputs=true))
  private val owned=RegInit(false.B);private val cancelling=RegInit(false.B)
  when(io.in.fire){owned:=true.B}
  when(spatial.io.completion.fire){owned:=false.B}
  when(io.cancel && owned){cancelling:=true.B}
  when(!owned && !preparation.io.busy && !spatial.io.busy){cancelling:=false.B}
  io.in.ready:=original.io.in.ready && preparation.io.in.ready && !io.cancel && !cancelling
  original.io.in.valid:=io.in.valid && preparation.io.in.ready && !io.cancel && !cancelling
  preparation.io.in.valid:=io.in.valid && original.io.in.ready && !io.cancel && !cancelling
  original.io.in.bits:=io.in.bits;preparation.io.in.bits:=io.in.bits
  spatial.io.in<>preparation.io.out
  original.io.cancel:=io.cancel;preparation.io.cancel:=io.cancel;spatial.io.cancel:=io.cancel
  io.original<>original.io.out;io.spatial<>spatial.io.out
  io.originalCompletion<>original.io.completion;io.spatialCompletion<>spatial.io.completion
  io.busy(0):=original.io.busy;io.busy(1):=preparation.io.busy || spatial.io.busy
  io.transferred:=preparation.io.out.fire
  assert(original.io.in.fire===preparation.io.in.fire)
}

class Fp32FragmentPreparationSpec extends AnyFlatSpec {
  import Fp32ViewportReference.{E,decode,fp}
  import FragmentInterpolationReference.{Data=>Values,Sample}
  import TriangleRasterReference.{Triangle,Quad,Settings,base,primitive,raster,zero}
  private val p=VertexShaderStageConfig()
  private val g=GraphicsConfig(screenWidth=8,screenHeight=8)
  private val flat=(BigInt(1)<<5)|(BigInt(1)<<60)
  private def triangle(seed: Int=0): Triangle=base.copy(values=Values(
    Vector(1.0,0.5,0.25).map(x=>decode(fp(x))),Vector(0.25,0.5,0.75).map(fp),
    Vector.tabulate(3,64)((v,c)=>if(c==60)BigInt("ffc12345",16)
      else fp((c-19+(if(flat.testBit(c))0 else v*11)+seed)/64.0)),flat,16))
  private class Driver(val d: FragmentPreparationPair) {
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    var cycles=0;var transfers=0;var sequence=0
    var heldOriginal=Option.empty[BigInt];var heldSpatial=Option.empty[BigInt]
    def tick(): Unit={
      if(d.io.transferred.peek().litToBoolean)transfers+=1
      def held(valid: Bool,bits: Fp32InterpolatedQuad,previous: Option[BigInt]): Option[BigInt]={
        val offered=valid.peek().litToBoolean
        previous.foreach(_=>assert(offered,"held quad offer withdrawn"))
        // The scalar reference can trail by thousands of clocks. Bound wide
        // snapshots; dedicated owner tests check every stalled cycle.
        if(offered && (previous.isEmpty || cycles%257==0)){
          val value=bits.peek().litValue;previous.foreach(x=>assert(value==x,"held quad changed"));Some(value)
        }else if(offered)previous else None
      }
      heldOriginal=held(d.io.original.valid,d.io.original.bits,heldOriginal)
      heldSpatial=held(d.io.spatial.valid,d.io.spatial.bits,heldSpatial)
      d.clock.step();cycles+=1
    }
    def until(condition: => Boolean): Unit={var n=0;while(!condition && n<150000){tick();n+=1};assert(condition,s"preparation pair stalled at cycle=$cycles")}
    def prepare(t: Triangle=triangle(),kind: Int=2,mode: Int=0,centroid: BigInt=0,
        samples: Option[Vector[Sample]]=None,offset: Option[E]=None,towardZero: Boolean=false): Unit={
      zero(d.io.in.bits);sequence+=1;transfers=0
      val in=d.io.in.bits;primitive(in.primitive,t,sequence)
      val geometry=in.primitive.clipped.primitive
      geometry.kind.poke(kind.U);geometry.vertexCount.poke((kind+1).U)
      geometry.topology.poke((if(kind==0)0 else if(kind==1)1 else 4).U);geometry.provokingVertex.poke(kind.U)
      in.pointSize.poke(fp(2).U);in.sampleMode.poke(mode.U);in.sampleDepthEnable.poke((mode!=0).B)
      in.centroidMask.poke(centroid.U);in.depthRoundTowardZero.poke(towardZero.B)
      offset.foreach(e=>{in.depthOffset.negative.poke(e.sign.B);in.depthOffset.exponent.poke(e.exponent.S);in.depthOffset.significand.poke(e.sig.U)})
      val ss=samples.getOrElse {
        if(kind==1){
          val dx=t.coords(1)._1-t.coords(0)._1;val dy=t.coords(1)._2-t.coords(0)._2
          val area=dx*dx+dy*dy
          Vector.tabulate(4){i=>val x=i&1;val y=i>>1
            val dot=(BigInt(x)*256+128-t.coords(0)._1)*dx+(BigInt(y)*256+128-t.coords(0)._2)*dy
            Sample(Vector(area-dot,dot,BigInt(0)),area,if(i==3)0 else if(mode==0)1 else 7,x,y)
          }
        }else raster(t,Settings(mode=mode),g).head.samples
      }
      for((s,i)<-ss.zipWithIndex){val q=in.quad.lanes(i)
        q.x.poke(s.x.S);q.y.poke(s.y.S);q.e0.poke(s.edges(0).S);q.e1.poke(s.edges(1).S);q.e2.poke(s.edges(2).S);q.area.poke(s.area.S)
        q.covered.poke((s.mask!=0).B);q.coverageMask.poke(s.mask.U)
      }
    }
    def submit(): Unit={
      d.io.in.valid.poke(true.B);until(d.io.in.ready.peek().litToBoolean);tick();d.io.in.valid.poke(false.B)
      d.io.in.bits.primitive.clipped.primitive.sequence.poke(0xdead.U);d.io.in.bits.centroidMask.poke(0.U)
    }
    def finish(status: Int=0): Unit={
      if(status==0){
        until(d.io.original.valid.peek().litToBoolean && d.io.spatial.valid.peek().litToBoolean)
        val old=d.io.original.bits.peek().litValue;val fresh=d.io.spatial.bits.peek().litValue
        assert(old==fresh,s"prepared/spatial quad differs from scalar at sequence=$sequence")
        for(_<-0 until 5)tick()
        assert(d.io.original.bits.peek().litValue==old && d.io.spatial.bits.peek().litValue==fresh)
        d.io.original.ready.poke(true.B);d.io.spatial.ready.poke(true.B);tick()
        d.io.original.ready.poke(false.B);d.io.spatial.ready.poke(false.B)
        heldOriginal=None;heldSpatial=None
      }
      until(d.io.originalCompletion.valid.peek().litToBoolean && d.io.spatialCompletion.valid.peek().litToBoolean)
      val a=d.io.originalCompletion.bits;val b=d.io.spatialCompletion.bits
      a.status.expect(status.U);b.status.expect(status.U)
      a.sequence.expect(sequence.U);b.sequence.expect(sequence.U)
      a.output.expect((status==0).B);b.output.expect((status==0).B)
      a.arithmeticOperations.expect(b.arithmeticOperations.peek())
      a.arithmeticStatus.expect(b.arithmeticStatus.peek());a.packingStatus.expect(b.packingStatus.peek())
      assert(a.peek().litValue==b.peek().litValue,s"prepared completion differs at sequence=$sequence: old=${a.peek().litValue} new=${b.peek().litValue}")
      assert(transfers==1,s"accepted quad transferred $transfers preparation results")
      val old=a.peek().litValue;val fresh=b.peek().litValue
      for(_<-0 until 5){tick();assert(a.peek().litValue==old && b.peek().litValue==fresh)}
      d.io.originalCompletion.ready.poke(true.B);d.io.spatialCompletion.ready.poke(true.B);tick()
      d.io.originalCompletion.ready.poke(false.B);d.io.spatialCompletion.ready.poke(false.B)
      d.io.cancel.poke(false.B);tick();tick();d.io.busy.foreach(_.expect(false.B))
    }
  }
  behavior of "Fp32FragmentPreparation"
  it should "compose all sixty-four perspective components and exact scalar accounting" in {
    simulate(new FragmentPreparationPair(p,g)){d=>val t=new Driver(d)
      t.prepare();t.submit();t.finish()
      val reversed=triangle(1).copy(coords=Vector(triangle(1).coords(0),triangle(1).coords(2),triangle(1).coords(1)))
      t.prepare(reversed);t.submit();t.finish()
      t.prepare(triangle(2).copy(values=triangle(2).values.copy(slots=0,flat=0)));t.submit();t.finish()
      info("FRAGMENT_PREPARATION fullComponents=64 nonUnitW=true windings=2 exactAllFields=true exactOperationAndFlagAccounting=true emptyVaryings=true")
    }
  }
  it should "retain centroid masks covered-sample depths and directed polygon offsets" in {
    simulate(new FragmentPreparationPair(p,g)){d=>val t=new Driver(d)
      val qualifiers=flat|(BigInt(1)<<3)|(BigInt(1)<<63)
      t.prepare(mode=2,centroid=qualifiers);t.submit();t.finish()
      t.prepare(triangle(3),mode=1,centroid=qualifiers,offset=Some(E(exponent = -12,sig=0x800000)),towardZero=true)
      t.submit();t.finish()
    }
  }
  it should "preserve coherent covered-sample fallback across a perspective pole" in {
    simulate(new FragmentPreparationPair(p,g)){d=>val t=new Driver(d)
      val small=triangle().copy(coords=Vector((32,32),(112,32),(32,112)).map{case(x,y)=>(BigInt(x),BigInt(y))},
        values=triangle().values.copy(inv=Vector(2.0,0.25,0.25).map(x=>decode(fp(x)))))
      t.prepare(small,mode=2);t.submit();t.finish()
    }
  }
  it should "copy point values and ignore the unused line vertex" in {
    simulate(new FragmentPreparationPair(p,g)){d=>val t=new Driver(d)
      t.prepare(kind=0,mode=2);t.submit();t.finish()
      val line=triangle().copy(values=triangle().values.copy(values=triangle().values.values.updated(2,Vector.fill(64)(BigInt("7fc00001",16)))))
      t.prepare(line,kind=1,mode=2,centroid=BigInt(1)<<63);t.submit();t.finish()
    }
  }
  it should "retain earlier component failure ahead of later preparation failure" in {
    simulate(new FragmentPreparationPair(p,g)){d=>val t=new Driver(d)
      val values=triangle().values.copy(inv=Vector(E(exponent=400,sig=0x800000),E(sig=0x800000),E(sig=0x800000)),
        flat=0,values=Vector.tabulate(3,64)((v,c)=>if(v==0 && c==0)BigInt("7f7fffff",16)else fp(1)))
      val samples=Vector.tabulate(4)(i=>Sample(if(i==1)Vector(-1,2,2).map(BigInt(_))else Vector.fill(3)(BigInt(1)),3,1,i&1,i>>1))
      t.prepare(triangle().copy(values=values),samples=Some(samples));t.submit();t.finish(7)
      t.prepare();t.submit();t.finish()
    }
  }
  it should "retain rejected and cancelled request identity without inventing helper failures" in {
    simulate(new FragmentPreparationPair(p,g)){d=>val t=new Driver(d)
      t.prepare();d.io.in.bits.primitive.clipped.partCount.poke(0.U);t.submit();t.finish(1)
      t.prepare();t.submit();for(_<-0 until 12)t.tick();d.io.cancel.poke(true.B);t.finish(4)
      t.prepare();t.submit();t.finish()
    }
  }
}
