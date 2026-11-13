package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

private class IndependentFragmentPreparations(p: VertexShaderStageConfig,g: GraphicsConfig) extends Module {
  val io=IO(new Bundle {
    val in=Vec(2,Flipped(Decoupled(new FragmentInterpolationRequest(p,g))))
    val out=Vec(2,Decoupled(new PreparedFragmentInterpolation(p,g)))
    val cancel=Input(Vec(2,Bool()));val busy=Output(Vec(2,Bool()))
  })
  for(i<-0 until 2){
    val owner=Module(new Fp32FragmentPreparation(p,g))
    owner.io.in<>io.in(i);io.out(i)<>owner.io.out
    owner.io.cancel:=io.cancel(i);io.busy(i):=owner.io.busy
  }
}

class FragmentPreparationInstanceSpec extends AnyFlatSpec {
  it should "keep cached preparation definitions independent under different inputs stalls cancellation and reuse" in {
    import TriangleRasterReference._
    import Fp32ViewportReference._
    val p=VertexShaderStageConfig(VertexInputFetchConfig(1,1),1)
    val g=GraphicsConfig(screenWidth=4,screenHeight=4)
    simulate(new IndependentFragmentPreparations(p,g)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def submit(owner: Int,sequence: Int,z: Double): Unit={
        val t=base.copy(values=base.values.copy(slots=1,flat=0,
          depth=Vector.fill(3)(fp(z)),values=base.values.values.map(_.take(4))))
        val q=d.io.in(owner).bits;zero(q);primitive(q.primitive,t,sequence)
        for((s,i)<-raster(t,Settings(width=4,height=4),g).head.samples.zipWithIndex){
          val lane=q.quad.lanes(i)
          lane.x.poke(s.x.S);lane.y.poke(s.y.S);lane.e0.poke(s.edges(0).S)
          lane.e1.poke(s.edges(1).S);lane.e2.poke(s.edges(2).S);lane.area.poke(s.area.S)
          lane.covered.poke((s.mask!=0).B);lane.coverageMask.poke(s.mask.U)
        }
        d.io.in(owner).ready.expect(true.B);d.io.in(owner).valid.poke(true.B)
        d.clock.step();d.io.in(owner).valid.poke(false.B);zero(q)
      }
      def await(owner: Int): Unit={
        var cycles=0
        while(!d.io.out(owner).valid.peek().litToBoolean && cycles<3000){d.clock.step();cycles+=1}
        d.io.out(owner).valid.expect(true.B)
      }
      def retire(owner: Int): Unit={
        d.io.out(owner).ready.poke(true.B);d.clock.step();d.io.out(owner).ready.poke(false.B)
        d.io.busy(owner).expect(false.B)
      }
      submit(0,41,0.25);submit(1,82,0.75)
      await(0);await(1)
      d.io.out(0).bits.quad.sequence.expect(41.U);d.io.out(1).bits.quad.sequence.expect(82.U)
      for(lane<-0 until 4){
        d.io.out(0).bits.quad.lanes(lane).windowDepth.expect(fp(0.25).U)
        d.io.out(1).bits.quad.lanes(lane).windowDepth.expect(fp(0.75).U)
      }
      val held=d.io.out(0).bits.peek().litValue
      retire(1);submit(1,83,0.5);d.clock.step(4);d.io.cancel(1).poke(true.B)
      await(1);d.io.out(1).bits.preparationStatus.expect(4.U)
      d.io.out(1).bits.quad.sequence.expect(83.U)
      assert(d.io.out(0).bits.peek().litValue==held,"a peer changed the stalled owner's snapshot")
      d.io.out(0).valid.expect(true.B);d.io.out(0).bits.preparationStatus.expect(0.U)
      retire(1);d.io.cancel(1).poke(false.B)
      submit(1,84,0.125);await(1)
      d.io.out(1).bits.preparationStatus.expect(0.U);d.io.out(1).bits.quad.sequence.expect(84.U)
      assert(d.io.out(0).bits.peek().litValue==held)
      retire(1);submit(1,85,0.125);await(1) // exact numerical key hit
      d.io.out(1).bits.quad.sequence.expect(85.U)
      for(lane<-0 until 4)d.io.out(1).bits.quad.lanes(lane).windowDepth.expect(fp(0.125).U)
      assert(d.io.out(0).bits.peek().litValue==held)
      val peer=d.io.out(1).bits.peek().litValue
      retire(0);submit(0,42,0.25);await(0)
      d.io.out(0).bits.quad.sequence.expect(42.U)
      for(lane<-0 until 4)d.io.out(0).bits.quad.lanes(lane).windowDepth.expect(fp(0.25).U)
      assert(d.io.out(1).bits.peek().litValue==peer)
      retire(0);retire(1)
    }
  }
}
