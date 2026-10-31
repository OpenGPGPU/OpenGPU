package opengpu.system

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.graphics._
import opengpu.testutil.GpuSim._
import opengpu.testutil.GraphicsTestResolution
import org.scalatest.flatspec.AnyFlatSpec

class IsolineGeometryCuRasterSpec extends AnyFlatSpec {
  private def zero(data:Data):Unit=data match {
    case r:Record=>r.elements.values.foreach(zero)
    case v:Vec[_]=>v.foreach(zero)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case _=>()
  }
  it should "rasterize tessellated isolines and drain failures" in {
    val p=VertexShaderStageConfig(varyings=1)
    simulate(new IsolineGeometryCuRaster(GpuConfig(lanes=4,warps=2),p,4,1)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def run(mode:Int):Unit={val cfg=d.io.task.bits;val raster=cfg.raster;raster.version.poke((if(mode==4)0 else 1).U);raster.screenW.poke(math.min(4,GraphicsTestResolution.width).U);raster.screenH.poke(math.min(4,GraphicsTestResolution.height).U);cfg.depthFormat.poke(TypedDepthFormat.d24.U);val task=cfg.source;val view=task.viewport;view.version.poke((if(mode==3)0 else 1).U);view.extentX.poke(4.U);view.extentY.poke(4.U);view.nearDepth.poke(0.U);view.farDepth.poke("h3f800000".U);val t=task.source;t.commandId.poke(3.U);t.kernel.kernelPc.poke(0x1000.U)
        val r=t.renderer;r.tessellation.taskId.poke(19.U);r.tessellation.spacing.poke(TessellationSpacing.equal.U)
        r.tessellation.outer(0).poke("h3f800000".U);r.tessellation.outer(1).poke("h3f800000".U)
        r.tessellation.pointMode.poke(false.B);r.firstSequence.poke(10.U);r.firstPrimitiveId.poke(12.U)
        r.identity.context.poke(5.U);r.outputComponents.poke(4.U);r.varyingSlots.poke(0.U)
        for(i<-0 until 4){r.componentSources(i).valid.poke(true.B);r.componentSources(i).bits.poke(i.U)}
        d.io.task.valid.poke(true.B);d.io.task.ready.expect(true.B);d.clock.step();d.io.task.valid.poke(false.B)
        var phase=0;var cancelled=false;var u=BigInt(0);var outputs=0;var launches=0;var cycles=0
        while(!d.io.completion.valid.peek().litToBoolean && cycles<20000){
          val pulse=mode==2 && phase==1 && !cancelled
          d.io.cancel.poke(pulse.B);if(pulse)cancelled=true
          d.io.launch.ready.poke((phase==0).B);d.io.owner.poke((if(phase==0)0 else 1).U);d.io.taskIds(0).poke(3.U)
          val e=d.io.event(0);e.valid.poke((phase==1 && !cancelled).B);e.bits.operation.poke(0.U)
          e.bits.vertex.components(0).poke((if(mode==1)BigInt("7f800000",16) else u).U)
          e.bits.vertex.components(1).poke(0.U);e.bits.vertex.components(2).poke(0.U)
          e.bits.vertex.components(3).poke("h3f800000".U)
          val c=d.io.kernelCompletion;c.valid.poke((phase==2 || (phase==1 && cancelled)).B)
          c.bits.commandId.poke(3.U);c.bits.success.poke((!cancelled).B)
          val launch=d.io.launch.valid.peek().litToBoolean && phase==0
          val event=e.valid.peek().litToBoolean && e.ready.peek().litToBoolean
          val done=c.valid.peek().litToBoolean && c.ready.peek().litToBoolean
          val nextU=if(launch)d.io.geometryTask.bits.vertices(0).components(0).peek().litValue else u
          d.io.out.ready.poke((cycles%5!=0).B)
          if(d.io.out.valid.peek().litToBoolean && d.io.out.ready.peek().litToBoolean){
            val o=d.io.out.bits.values;o.identity.context.expect(5.U);o.sequence.expect(10.U);o.primitiveId.expect(12.U)
            val lanes=o.lanes;assert((0 until 4).exists(i=>lanes(i).covered.peek().litToBoolean))
            for(i<-0 until 4 if lanes(i).covered.peek().litToBoolean){
              assert(lanes(i).x.peek().litValue>=0 && lanes(i).x.peek().litValue<4 && lanes(i).y.peek().litValue>=0 && lanes(i).y.peek().litValue<4)
              lanes(i).coverageMask.expect(1.U)}
            outputs+=1
          }
          d.clock.step();if(launch){phase=1;u=nextU;launches+=1};if(event)phase=2;if(done)phase=0;cycles+=1
        }
        assert(cycles<20000);d.io.completion.bits.source.source.source.taskId.expect(19.U)
        d.io.completion.bits.status.expect((if(mode==0)0 else if(mode==1)7 else if(mode==2)4 else 1).U)
        if(mode==0){assert(launches==2 && outputs>0);d.io.completion.bits.inputs.expect(1.U);d.io.completion.bits.outputQuads.expect(outputs.U)}
        else assert(outputs==0)
        val held=d.io.completion.bits.peek().litValue;d.clock.step(2);assert(d.io.completion.bits.peek().litValue==held)
        d.io.completion.ready.poke(true.B);d.clock.step();d.io.completion.ready.poke(false.B);d.io.cancel.poke(false.B)
        d.io.busy.expect(false.B)
      }
      for(mode<-0 to 4)run(mode)
    }
  }
}
