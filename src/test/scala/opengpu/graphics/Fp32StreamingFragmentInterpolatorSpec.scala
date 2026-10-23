package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class Fp32StreamingFragmentInterpolatorSpec extends AnyFlatSpec {
  import Fp32ViewportReference.{decode,fp,pack}
  import FragmentInterpolationReference.{Data => Values,Sample,lane}
  import TriangleRasterReference.{base,primitive,zero}
  private val p=VertexShaderStageConfig()
  private val g=GraphicsConfig(screenWidth=8,screenHeight=8)
  private val samples=Vector.tabulate(4)(i=>Sample(Vector(3-i,1+i,2).map(BigInt(_)),6,
    if(i==3)0 else 1,i&1,i>>1))
  private def values(id: Int)=Values(Vector(1.0,0.5,0.25).map(v=>decode(fp(v))),
    Vector(0.25,0.5,0.75).map(fp),Vector.tabulate(3,64)((v,c)=>fp((id+v*5+c-23)/64.0)),0,16)
  private class Driver(val d: Fp32StreamingFragmentInterpolator) {
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    var cycle=0;val outputs=mutable.ArrayBuffer.empty[Int];val completions=mutable.ArrayBuffer.empty[Int]
    val outputCycles=mutable.ArrayBuffer.empty[Int]
    var consume=false;var consumeCompletion=false;var cancelled=false
    var heldQuad=Option.empty[BigInt];var heldCompletion=Option.empty[BigInt]
    def tick(): Unit={
      d.io.out.ready.poke(consume.B);d.io.completion.ready.poke(consumeCompletion.B)
      if(d.io.out.valid.peek().litToBoolean){
        val bits=d.io.out.bits.peek().litValue;heldQuad.foreach(v=>assert(v==bits,"held quad changed"))
        if(consume){
          val id=d.io.out.bits.sequence.peek().litValue.toInt;outputs+=id;outputCycles+=cycle
          val reference=samples.map(lane(values(id),_))
          for(i<-0 until 4){val o=d.io.out.bits.lanes(i);val e=reference(i)
            o.x.expect(samples(i).x.S);o.y.expect(samples(i).y.S);o.windowDepth.expect(e.depth.U)
            o.fragCoordW.expect(pack(e.reciprocal).U);o.perspectiveValid.expect(e.valid.B)
            for(c<-0 until 64)o.varyings(c/4)(c%4).expect(e.values(c).U)
          }
          heldQuad=None
        }else heldQuad=Some(bits)
      }else assert(heldQuad.isEmpty,"held quad withdrawn")
      if(d.io.completion.valid.peek().litToBoolean){
        val bits=d.io.completion.bits.peek().litValue;heldCompletion.foreach(v=>assert(v==bits,"held completion changed"))
        if(consumeCompletion){
          val r=d.io.completion.bits;val id=r.sequence.peek().litValue.toInt
          completions+=id;r.status.expect((if(cancelled)4 else 0).U)
          r.output.expect(outputs.contains(id).B);heldCompletion=None
        }else heldCompletion=Some(bits)
      }else assert(heldCompletion.isEmpty,"held completion withdrawn")
      d.clock.step();cycle+=1
    }
    def until(q: => Boolean): Unit={var n=0;while(!q && n<20000){tick();n+=1};assert(q,s"streaming owner stalled at $cycle")}
    def offer(id: Int): Unit={
      zero(d.io.in.bits);primitive(d.io.in.bits.primitive,base.copy(values=values(id)),id)
      for((s,i)<-samples.zipWithIndex){val q=d.io.in.bits.quad.lanes(i)
        q.x.poke(s.x.S);q.y.poke(s.y.S);q.e0.poke(s.edges(0).S);q.e1.poke(s.edges(1).S);q.e2.poke(s.edges(2).S)
        q.area.poke(s.area.S);q.covered.poke((s.mask!=0).B);q.coverageMask.poke(s.mask.U)
      }
      d.io.in.valid.poke(true.B);until(d.io.in.ready.peek().litToBoolean);tick();d.io.in.valid.poke(false.B)
      d.io.in.bits.primitive.clipped.primitive.sequence.poke(0xdead.U)
    }
  }
  it should "own four raw quads before output and recycle credits in order under independent stalls" in {
    simulate(new Fp32StreamingFragmentInterpolator(p,g)){d=>val t=new Driver(d)
      for(id<-1 to 4)t.offer(id)
      assert(t.cycle==4,"four preparation owners did not admit successive raw quads")
      d.io.in.ready.expect(false.B);t.until(d.io.out.valid.peek().litToBoolean)
      for(_<-0 until 150)t.tick()
      t.consume=true;t.until(d.io.completion.valid.peek().litToBoolean)
      for(_<-0 until 37)t.tick()
      t.consumeCompletion=true
      for(id<-5 to 16)t.offer(id)
      t.until(t.completions.size==16);d.io.busy.expect(false.B)
      assert(t.outputs.toVector==(1 to 16).toVector && t.completions.toVector==(1 to 16).toVector)
      // Budget the registered geometry/normalization stages as well as issue.
      val groupPeriods=(4 until 12).map(i=>t.outputCycles(i+4)-t.outputCycles(i))
      assert(groupPeriods.forall(_<=1040),s"four-owner preparation rate regressed: $groupPeriods")
      info(s"STREAMING_FRAGMENT quads=16 componentsPerQuad=64 consecutiveAdmission=4 orderedRetirement=true steadyFourQuadPeriodMax=${groupPeriods.max} rateBound=1040")
    }
  }
  it should "drain a one-cycle cancellation across all reserved preparations and permit clean reuse" in {
    simulate(new Fp32StreamingFragmentInterpolator(p,g)){d=>val t=new Driver(d)
      for(id<-1 to 4)t.offer(id)
      t.cancelled=true;d.io.cancel.poke(true.B);t.tick();d.io.cancel.poke(false.B)
      t.consume=true;t.consumeCompletion=true;t.until(t.completions.size==4)
      assert(t.outputs.isEmpty && t.completions.toVector==Vector(1,2,3,4));d.io.busy.expect(false.B)
      t.tick();t.cancelled=false;t.offer(5);t.until(t.completions.size==5)
      assert(t.outputs.toVector==Vector(5));d.io.busy.expect(false.B)
    }
  }
  it should "retain an offered head quad while cancellation drains later reserved quads" in {
    simulate(new Fp32StreamingFragmentInterpolator(p,g)){d=>val t=new Driver(d)
      for(id<-1 to 4)t.offer(id)
      t.until(d.io.out.valid.peek().litToBoolean);t.tick()
      t.cancelled=true;d.io.cancel.poke(true.B);t.tick();d.io.cancel.poke(false.B)
      for(_<-0 until 23)t.tick()
      t.consume=true;t.consumeCompletion=true;t.until(t.completions.size==4)
      assert(t.outputs.toVector==Vector(1) && t.completions.toVector==Vector(1,2,3,4));d.io.busy.expect(false.B)
    }
  }
}
