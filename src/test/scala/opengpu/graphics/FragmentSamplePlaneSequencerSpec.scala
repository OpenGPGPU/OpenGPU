package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class FragmentSamplePlaneSequencerSpec extends AnyFlatSpec {
  it should "retain original four-pixel quads and drain a held plane before canceled reuse" in {
    simulate(new FragmentSamplePlaneSequencer(GraphicsConfig())){d=>
      WindowRecordTest.zero(d.io);d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
      def until(label:String)(p: =>Boolean):Unit={var n=0;while(!p && n<100){d.clock.step();n+=1};assert(p,label)}
      def send(mode:Int,enable:Boolean):Vector[Int]={
        val low=(1<<(1<<mode))-1;val masks=Vector(1,low,low&10,0)
        val q=d.io.in.bits;WindowRecordTest.zero(q);q.sampleMode.poke(mode.U);q.enable.poke(enable.B)
        for(i<-0 until 4){val lane=q.quad.lanes(i)
          lane.x.poke((10+i%2).S);lane.y.poke((20+i/2).S);lane.area.poke(4096.S)
          lane.e0.poke((500+i).S);lane.e1.poke((1000+i).S);lane.e2.poke((2596-2*i).S)
          lane.coverageMask.poke(masks(i).U);lane.covered.poke((masks(i)!=0).B)}
        d.io.in.valid.poke(true.B);until("original quad admission")(d.io.in.ready.peek().litToBoolean)
        d.clock.step();d.io.in.valid.poke(false.B);WindowRecordTest.zero(q);masks
      }
      for(mode<-0 to 2;enabled<-Seq(false,true)){
        val masks=send(mode,enabled)
        val ids=if(enabled)(0 until (1<<mode)).filter(s=>masks.exists(m=>(m&(1<<s))!=0))else Seq(0)
        for(id<-ids){until("owned sample plane")(d.io.out.valid.peek().litToBoolean)
          d.io.out.bits.perSample.expect(enabled.B);d.io.out.bits.sampleId.expect(id.U)
          for(i<-0 until 4){val lane=d.io.out.bits.quad.lanes(i);val mask=if(enabled)masks(i)&(1<<id)else masks(i)
            lane.x.expect((10+i%2).S);lane.y.expect((20+i/2).S);lane.area.expect(4096.S)
            lane.e0.expect((500+i).S);lane.e1.expect((1000+i).S);lane.e2.expect((2596-2*i).S)
            lane.coverageMask.expect(mask.U);lane.covered.expect((mask!=0).B)}
          val held=d.io.out.bits.peek().litValue
          for(_<-0 until 9){d.clock.step();d.io.out.valid.expect(true.B);assert(d.io.out.bits.peek().litValue==held)}
          d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)
        }
        until("complete original quad drain")(!d.io.busy.peek().litToBoolean)
      }
      send(2,true);until("held cancellation plane")(d.io.out.valid.peek().litToBoolean)
      val held=d.io.out.bits.peek().litValue;d.io.cancel.poke(true.B);d.clock.step();d.io.cancel.poke(false.B)
      for(_<-0 until 9){d.io.out.valid.expect(true.B);assert(d.io.out.bits.peek().litValue==held);d.io.in.ready.expect(false.B);d.clock.step()}
      d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)
      until("owned plane drained on cancel")(!d.io.busy.peek().litToBoolean);d.io.out.valid.expect(false.B)
      send(0,true);until("healthy no-reset plane")(d.io.out.valid.peek().litToBoolean)
      d.io.out.bits.sampleId.expect(0.U);d.io.out.ready.poke(true.B);d.clock.step()
      until("healthy no-reset drain")(!d.io.busy.peek().litToBoolean)
    }
  }
}
