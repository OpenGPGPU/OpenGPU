package opengpu.graphics

import chisel3._
import opengpu.system.GeometryReplayRasterClient
import opengpu.testutil.GpuSim._
import opengpu.testutil.GraphicsTestResolution
import org.scalatest.flatspec.AnyFlatSpec

class ControlledIsolineReplayRasterClientSpec extends AnyFlatSpec {
  import TriangleRasterReference._
  private val p=VertexShaderStageConfig(VertexInputFetchConfig(1,1),2)
  private val g=GraphicsConfig(screenWidth=math.min(8,GraphicsTestResolution.width),screenHeight=math.min(8,GraphicsTestResolution.height))
  it should "rasterize isoline window-bin replay without geometry metadata" in {
    simulate(new GeometryReplayRasterClient(p,g,interpolationContexts=2,requireGeometry=false)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      var cycle=0;var hold=false;var held=Option.empty[BigInt];var count=0
      var expected=Vector.empty[Quad];var settings=Settings();var checkOutput=true
      def tick():Unit={d.io.out.ready.poke((!hold && cycle%5!=0).B)
        if(d.io.out.valid.peek().litToBoolean){val bits=d.io.out.bits.peek().litValue
          held.foreach(v=>assert(v==bits,"isoline quad changed while held"))
          if(d.io.out.ready.peek().litToBoolean){
            d.io.out.bits.inputPrimitiveId.expect(0.U);d.io.out.bits.invocationId.expect(0.U);d.io.out.bits.layer.expect(0.U)
            if(checkOutput){assert(count<expected.size);check(d.io.out.bits.quad,expected(count),settings)}
            count+=1;held=None
          }else held=Some(bits)
        }else assert(held.isEmpty,"isoline quad withdrawn")
        d.clock.step();cycle+=1
      }
      def until(label:String)(condition: =>Boolean):Unit={var n=0;while(!condition && n<150000){tick();n+=1};assert(condition,s"$label stalled at $cycle")}
      def prepare(x:Int,y:Int,w:Int,h:Int,s:Settings):Unit={
        zero(d.io.in.bits);val q=d.io.in.bits;val r=q.replay
        primitive(r.record.primitive,base);state(r.record.raster,s);r.record.state.poke(5.U);r.record.token.poke(9.U);r.record.sequence.poke(3.U)
        r.record.geometry.valid.poke(false.B)
        r.source.identity.context.poke(5.U);r.source.identity.pass.poke(6.U);r.source.identity.generation.poke(7.U)
        r.source.reference.state.poke(5.U);r.source.reference.token.poke(9.U);r.source.reference.sequence.poke(3.U)
        q.originX.poke(x.S);q.originY.poke(y.S);q.tileWidth.poke(w.U);q.tileHeight.poke(h.U);q.depthFormat.poke(TypedDepthFormat.d24.U)
        count=0;held=None;settings=s;checkOutput=true
      }
      def submit():Unit={d.io.in.valid.poke(true.B);until("input")(d.io.in.ready.peek().litToBoolean);tick();d.io.in.valid.poke(false.B)}
      def finish(code:Int):Unit={until("completion")(d.io.completion.valid.peek().litToBoolean)
        d.io.completion.bits.status.expect(code.U);d.io.completion.bits.outputQuads.expect(count.U)
        if(code==0)assert(count==expected.size)
        val bits=d.io.completion.bits.peek().litValue;for(_<-0 until 3){tick();assert(d.io.completion.bits.peek().litValue==bits)}
        d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B)
      }
      for((x,y,w,h)<-Seq((0,0,4,8),(4,0,4,8),(-2,-2,4,4))){
        val s=Settings(scissor=true,minX=math.max(0,x),minY=math.max(0,y),maxX=math.max(0,x+w),maxY=math.max(0,y+h))
        prepare(x,y,w,h,Settings());settings=s;expected=raster(base,s,g);submit();finish(0)
      }
      for(corrupt<-0 until 2){prepare(0,0,8,8,Settings());corrupt match{
        case 0=>d.io.in.bits.replay.source.reference.token.poke(10.U)
        case 1=>d.io.in.bits.tileWidth.poke(0.U)}
        expected=Vector.empty;submit();finish(1)}
      prepare(0,0,8,8,Settings());expected=Vector.empty;checkOutput=false;hold=true;submit()
      until("held output")(d.io.out.valid.peek().litToBoolean);d.io.cancel.poke(true.B);for(_<-0 until 4)tick()
      hold=false;finish(4);d.io.cancel.poke(false.B)
      prepare(0,0,8,8,Settings());expected=raster(base,settings,g);submit();finish(0)
    }
  }
}
