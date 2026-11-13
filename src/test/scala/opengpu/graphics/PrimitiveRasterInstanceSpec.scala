package opengpu.graphics

import chisel3._
import chisel3.util._
import chisel3.experimental.hierarchy.Instantiate
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

private class IndependentPrimitiveRasters(p: VertexShaderStageConfig,g: GraphicsConfig) extends Module {
  val io=IO(new Bundle {
    val in=Vec(2,Flipped(Decoupled(new Fp32TriangleRasterRequest(p))))
    val out=Vec(2,Decoupled(new Fp32RasterizedQuad(p,g)))
    val completion=Vec(2,Decoupled(new Fp32TriangleRasterCompletion))
    val cancel=Input(Vec(2,Bool()));val busy=Output(Vec(2,Bool()))
  })
  private val engines=Seq.fill(2)(Instantiate(new Fp32PrimitiveRasterEngine(p,g,false,8)))
  for((engine,i)<-engines.zipWithIndex){
    engine.io.in<>io.in(i);io.out(i)<>engine.io.out;io.completion(i)<>engine.io.completion
    engine.io.cancel:=io.cancel(i);io.busy(i):=engine.io.busy
  }
}

class PrimitiveRasterInstanceSpec extends AnyFlatSpec {
  it should "retain private raster identity arithmetic held offers cancellation and reuse across cached definitions" in {
    import TriangleRasterReference._
    import Fp32ViewportReference.fp
    val p=VertexShaderStageConfig(VertexInputFetchConfig(1,1),1)
    val g=GraphicsConfig(screenWidth=4,screenHeight=4)
    val settings=Settings(width=4,height=4)
    simulate(new IndependentPrimitiveRasters(p,g)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def submit(owner: Int,sequence: Int,z: Double): Quad={
        val t=base.copy(coords=Vector((0,0),(512,0),(0,512)).map { case(x,y)=>(BigInt(x),BigInt(y)) },
          values=base.values.copy(slots=1,flat=0,depth=Vector.fill(3)(fp(z)),
            values=base.values.values.map(_.take(4))))
        val expected=raster(t,settings,g);assert(expected.size==1)
        val in=d.io.in(owner);zero(in.bits);primitive(in.bits.primitive,t,sequence)
        state(in.bits.state,settings)
        in.ready.expect(true.B);in.valid.poke(true.B);d.clock.step();in.valid.poke(false.B)
        zero(in.bits);expected.head
      }
      def await(condition: => Boolean): Unit={
        var clocks=0
        while(!condition && clocks<20000){d.clock.step();clocks+=1}
        assert(condition,"independent primitive raster did not drain")
      }
      def retireQuad(owner: Int): Unit={
        d.io.out(owner).ready.poke(true.B);d.clock.step();d.io.out(owner).ready.poke(false.B)
      }
      def retireCompletion(owner: Int,sequence: Int,status: Int): Unit={
        await(d.io.completion(owner).valid.peek().litToBoolean)
        val result=d.io.completion(owner).bits
        result.sequence.expect(sequence.U);result.status.expect(status.U);result.outputQuads.expect(1.U)
        val held=result.peek().litValue;d.clock.step(3);assert(result.peek().litValue==held)
        d.io.completion(owner).ready.poke(true.B);d.clock.step()
        d.io.completion(owner).ready.poke(false.B);d.io.busy(owner).expect(false.B)
      }
      val first=submit(0,41,0.25);val second=submit(1,82,0.75)
      await(d.io.out(0).valid.peek().litToBoolean && d.io.out(1).valid.peek().litToBoolean)
      check(d.io.out(0).bits,first,settings,sequence=41)
      check(d.io.out(1).bits,second,settings,sequence=82)
      val held=d.io.out(0).bits.peek().litValue;val cancelled=d.io.out(1).bits.peek().litValue
      d.io.cancel(1).poke(true.B);d.clock.step(5)
      d.io.out(1).valid.expect(true.B);assert(d.io.out(1).bits.peek().litValue==cancelled)
      retireQuad(1);retireCompletion(1,82,4);d.io.cancel(1).poke(false.B)
      assert(d.io.out(0).bits.peek().litValue==held);d.io.out(0).valid.expect(true.B)
      val reused=submit(1,83,0.125)
      await(d.io.out(1).valid.peek().litToBoolean)
      check(d.io.out(1).bits,reused,settings,sequence=83)
      retireQuad(1);retireCompletion(1,83,0)
      assert(d.io.out(0).bits.peek().litValue==held);d.io.out(0).valid.expect(true.B)
      retireQuad(0);retireCompletion(0,41,0)
    }
  }
}
