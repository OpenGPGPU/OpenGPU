package opengpu.graphics
import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
class OutputUsageCommandSpec extends AnyFlatSpec {
 for(vertex <- Seq(false,true)) it should s"decode independent input usage on vertex=$vertex records under stalls" in {
  simulate(new CommandBufferStage(GraphicsConfig(),vertCore=vertex)) { d =>
   d.io.base.poke(0x1000.U);d.io.count.poke(4.U);d.io.start.poke(false.B)
   d.io.mem.req.ready.poke(true.B);d.io.mem.resp.valid.poke(false.B);d.io.mem.resp.bits.addr.poke(0.U);d.io.mem.resp.bits.data.poke(0.U);d.io.draw.ready.poke(false.B)
   d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
   d.io.start.poke(true.B);d.clock.step();d.io.start.poke(false.B)
   val masks=Seq(0,1,21,63);val outputs=Seq(15,5,10,0);var pending:Option[(Int,Int)]=None;var seen=0;var cycle=0
   while(seen<4 && cycle<1000){
    d.io.draw.ready.poke((cycle%11>4).B)
    d.io.mem.resp.valid.poke(pending.nonEmpty.B)
    pending.foreach{case(a,v)=>d.io.mem.resp.bits.addr.poke(a.U);d.io.mem.resp.bits.data.poke(v.U)}
    pending=None
    if(d.io.mem.req.valid.peek().litToBoolean){
     val a=d.io.mem.req.bits.addr.peek().litValue.toInt;val pos=(a-0x1000)/4
     val value=if(pos%40==32) (masks(pos/40)<<19)|(outputs(pos/40)<<25)|(pos/40&1) else 0
     pending=Some((a,value))
    }
    if(d.io.draw.valid.peek().litToBoolean){
     if(vertex)d.io.draw.bits.asInstanceOf[VertexDrawCommand].fragInputSkip.expect(masks(seen).U)
     else d.io.draw.bits.asInstanceOf[SceneTriangle].fragInputSkip.expect(masks(seen).U)
     if(vertex)d.io.draw.bits.asInstanceOf[VertexDrawCommand].fragOutputSkip.expect(outputs(seen).U)
     else d.io.draw.bits.asInstanceOf[SceneTriangle].fragOutputSkip.expect(outputs(seen).U)
     if(d.io.draw.ready.peek().litToBoolean)seen+=1
    }
    d.clock.step();cycle+=1
   }
   assert(seen==4);d.io.mem.resp.valid.poke(false.B);d.clock.step();d.io.done.expect(true.B)
  }
 }
}
