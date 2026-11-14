package opengpu.graphics

import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.core.memory.ShaderPayloadBinding
import opengpu.system.{GeometryFragmentShaderRouter,SharedCuGraphicsPort,TessellationVertexShaderRouter}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

/** Transport only: two real router tiers, no CU or shader execution. */
private class TessellationVertexShaderRouterHarness extends Module {
  private val config=GpuConfig(lanes=16,warps=8)
  private val payload=ShaderPayloadConfig(capacityBytes=8192,maxPayloadBytes=1024,owners=3,ports=3)
  val io=IO(new Bundle {
    val patch=new SharedCuGraphicsPort(config,8,2,geometryAvailable=true,tessellationAvailable=true,evaluationPatchAvailable=true)
    val vertex=new SharedCuGraphicsPort(config,8,2)
    val fragment=new SharedCuGraphicsPort(config,8,2)
    val pool=Flipped(new SharedCuGraphicsPort(config,8,2,geometryAvailable=true,tessellationAvailable=true,evaluationPatchAvailable=true))
    val bindings=Input(Vec(3,Valid(new ShaderPayloadBinding(config,payload))))
    val poolPayload=Output(Valid(new ShaderPayloadBinding(config,payload)))
    val innerPins=Output(Vec(4,Valid(new ShaderPayloadPin(payload))))
    val outerPins=Output(Vec(4,Valid(new ShaderPayloadPin(payload))))
    val drained=Output(Bool())
  })
  private val inner=Module(new TessellationVertexShaderRouter(config,2,8,Some(payload),
    tessellationAvailable=true,evaluationPatchAvailable=true))
  private val outer=Module(new GeometryFragmentShaderRouter(config,2,8,Some(payload),
    tessellationAvailable=true,evaluationPatchAvailable=true))
  inner.io.patch<>io.patch;inner.io.vertex<>io.vertex
  outer.io.geometry<>inner.io.pool;outer.io.fragment<>io.fragment;io.pool<>outer.io.pool
  inner.io.clientPayload.get(0):=io.bindings(0);inner.io.clientPayload.get(1):=io.bindings(1)
  outer.io.clientPayload.get(0):=inner.io.poolPayload.get;outer.io.clientPayload.get(1):=io.bindings(2)
  io.poolPayload:=outer.io.poolPayload.get
  io.innerPins:=inner.io.payloadPins.get;io.outerPins:=outer.io.payloadPins.get
  io.drained:=inner.io.drained && outer.io.drained
}

class TessellationVertexShaderRouterSpec extends AnyFlatSpec {
  private def zero(d:Data):Unit=d match {
    case r:Record=>r.elements.values.foreach(zero)
    case v:Vec[_]=>v.foreach(zero)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case _=>()
  }
  private def defaultReady(p:SharedCuGraphicsPort):Unit={
    p.completion.ready.poke(true.B);p.trap.ready.poke(true.B)
    p.invalidateDone.ready.poke(true.B)
    p.texture.foreach{t=>t.scalar.ready.poke(true.B);t.vector.ready.poke(true.B)}
  }

  it should "remap patch and VS tasks through one outer geometry window beside fragments" in {
    simulate(new TessellationVertexShaderRouterHarness){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val p=d.io.patch;val v=d.io.vertex;val f=d.io.fragment;val pool=d.io.pool
      Seq(p,v,f).foreach(defaultReady)
      def until(test: =>Boolean):Unit={var n=0;while(!test && n<100){d.clock.step();n+=1};assert(test)}
      def launch(port:SharedCuGraphicsPort,tag:Int,pc:Int):Unit={
        port.launch.bits.commandId.poke(tag.U);port.launch.bits.launch.kernelPc.poke(pc.U)
        port.launch.valid.poke(true.B)
        if(port eq p){d.io.innerPins(2).valid.expect(true.B);d.io.innerPins(2).bits.context.expect(0x20.U)}
        if(port eq v){d.io.innerPins(3).valid.expect(true.B);d.io.innerPins(3).bits.context.expect(0x21.U)}
        until(port.launch.ready.peek().litToBoolean)
        d.clock.step();port.launch.valid.poke(false.B)
      }
      for(i<-0 until 3){val b=d.io.bindings(i)
        b.valid.poke(true.B);b.bits.handle.poke((i+1).U);b.bits.context.poke((0x20+i).U)
        b.bits.generation.poke((i+1).U);b.bits.owner.poke(i.U)
        b.bits.base.poke((0x10000+0x1000*i).U);b.bits.bytes.poke(512.U)}
      p.nativeResources.poke(true.B);p.uniformResources.poke(true.B)
      p.geometryTask.get.valid.poke(true.B);p.geometryTask.get.bits.taskId.poke(0x123.U)
      p.evaluationPatchBinding.get.valid.poke(true.B)
      p.evaluationPatchBinding.get.bits.version.poke(1.U);p.evaluationPatchBinding.get.bits.stage.poke(5.U)
      p.evaluationPatchBinding.get.bits.taskId.poke(0x456.U)
      launch(p,0x63,0x4000)
      // Changes after acceptance cannot alter queued scope/provider/lease.
      p.nativeResources.poke(false.B);p.uniformResources.poke(false.B)
      p.geometryTask.get.valid.poke(false.B);p.geometryTask.get.bits.taskId.poke(0xbad.U)
      p.evaluationPatchBinding.get.valid.poke(false.B);p.evaluationPatchBinding.get.bits.taskId.poke(0xbad.U)
      d.io.bindings(0).bits.context.poke(0xbad.U)
      v.nativeResources.poke(true.B);v.uniformResources.poke(true.B)
      launch(v,0,0x5000)
      v.nativeResources.poke(false.B);v.uniformResources.poke(false.B)
      d.io.bindings(1).bits.context.poke(0xbad.U)
      launch(f,0x77,0x6000)
      d.io.bindings(2).bits.context.poke(0xbad.U)
      d.io.innerPins(0).valid.expect(true.B);d.io.innerPins(0).bits.context.expect(0x20.U)
      d.io.innerPins(1).valid.expect(true.B);d.io.innerPins(1).bits.context.expect(0x21.U)
      d.io.innerPins(2).valid.expect(false.B);d.io.innerPins(3).valid.expect(false.B)
      until(pool.launch.valid.peek().litToBoolean)
      val held=pool.launch.bits.peek().litValue
      for(_<-0 until 4){
        assert(pool.launch.bits.peek().litValue==held);pool.launch.bits.commandId.expect(0.U)
        pool.launch.bits.launch.kernelPc.expect(0x4000.U)
        pool.nativeResources.expect(true.B);pool.uniformResources.expect(true.B)
        pool.geometryTask.get.valid.expect(true.B);pool.geometryTask.get.bits.taskId.expect(0x123.U)
        pool.evaluationPatchBinding.get.valid.expect(true.B);pool.evaluationPatchBinding.get.bits.taskId.expect(0x456.U)
        d.io.poolPayload.bits.context.expect(0x20.U);d.io.drained.expect(false.B);d.clock.step()
      }
      pool.launch.ready.poke(true.B);d.clock.step()
      pool.launch.valid.expect(true.B);pool.launch.bits.commandId.expect(1.U)
      pool.launch.bits.launch.kernelPc.expect(0x6000.U);pool.geometryTask.get.valid.expect(false.B)
      pool.evaluationPatchBinding.get.valid.expect(false.B);d.io.poolPayload.bits.context.expect(0x22.U)
      d.clock.step();pool.launch.ready.poke(false.B)
      // Both physical CUs can belong to patch and FS. VS remains reserved,
      // without adding a second live outer geometry slot.
      pool.owned.poke(3.U);pool.taskIds(0).poke(0.U);pool.taskIds(1).poke(1.U)
      p.owned.expect(1.U);p.taskIds(0).expect(0x63.U);f.owned.expect(2.U);f.taskIds(1).expect(0x77.U)
      v.owned.expect(0.U);pool.launch.valid.expect(false.B)
      pool.texture(0).scalar.valid.poke(true.B)
      pool.texture(1).vector.valid.poke(true.B)
      p.texture(0).scalar.ready.poke(false.B);f.texture(1).vector.ready.poke(false.B)
      p.texture(0).scalar.valid.expect(true.B);v.texture(0).scalar.valid.expect(false.B)
      f.texture(1).vector.valid.expect(true.B);p.texture(1).vector.valid.expect(false.B)
      pool.texture(0).scalar.ready.expect(false.B);pool.texture(1).vector.ready.expect(false.B)
      p.texture(0).scalarWriteback.valid.poke(true.B);p.texture(0).scalarWriteback.bits.data.poke(0x1234.U)
      v.texture(0).scalarWriteback.valid.poke(true.B);v.texture(0).scalarWriteback.bits.data.poke(0xbad.U)
      f.texture(1).vectorWriteback.valid.poke(true.B);f.texture(1).vectorWriteback.bits.writeback.data(15).poke(0xabcd.U)
      pool.texture(0).scalarWriteback.valid.expect(true.B);pool.texture(0).scalarWriteback.bits.data.expect(0x1234.U)
      pool.texture(1).vectorWriteback.valid.expect(true.B);pool.texture(1).vectorWriteback.bits.writeback.data(15).expect(0xabcd.U)
      v.texture(0).scalarWriteback.ready.expect(false.B)
      for(_<-0 until 3){pool.texture(0).scalarWriteback.bits.data.expect(0x1234.U);d.clock.step()}
      p.texture(0).scalar.ready.poke(true.B);f.texture(1).vector.ready.poke(true.B)
      pool.texture.foreach{t=>t.scalarWriteback.ready.poke(true.B);t.vectorWriteback.ready.poke(true.B)}
      d.clock.step();pool.texture(0).scalar.valid.poke(false.B);pool.texture(1).vector.valid.poke(false.B)
      Seq(p,v,f).foreach(_.texture.foreach{t=>t.scalarWriteback.valid.poke(false.B);t.vectorWriteback.valid.poke(false.B)})
      // These requests belong exclusively to the patch client.
      pool.geometryEvent.get(0).valid.poke(true.B);p.geometryEvent.get(0).ready.poke(true.B)
      p.geometryCancel.get(0).poke(true.B);pool.geometryCancel.get(0).expect(true.B)
      val patch=pool.tessellation.get(0);val local=p.tessellation.get(0)
      patch.request.valid.poke(true.B);local.request.ready.poke(true.B);patch.request.ready.expect(true.B)
      patch.park.valid.poke(true.B);local.park.ready.poke(true.B);patch.park.ready.expect(true.B)
      patch.finish.valid.poke(true.B);local.finish.ready.poke(true.B);patch.finish.ready.expect(true.B)
      local.result.valid.poke(true.B);patch.result.ready.poke(true.B);patch.result.valid.expect(true.B)
      local.resume.valid.poke(true.B);patch.resume.ready.poke(true.B);patch.resume.valid.expect(true.B)
      local.cancel.poke(true.B);local.terminal.poke(true.B);patch.cancel.expect(true.B);patch.terminal.expect(true.B)
      val eval=pool.evaluationPatch.get(0);val le=p.evaluationPatch.get(0)
      eval.request.valid.poke(true.B);le.request.ready.poke(true.B);eval.request.ready.expect(true.B)
      le.result.valid.poke(true.B);eval.result.ready.poke(true.B);eval.result.valid.expect(true.B)
      le.cancel.poke(true.B);eval.cancel.expect(true.B)
      d.clock.step()
      pool.geometryEvent.get(0).valid.poke(false.B);p.geometryCancel.get(0).poke(false.B)
      patch.request.valid.poke(false.B);patch.park.valid.poke(false.B);patch.finish.valid.poke(false.B)
      local.result.valid.poke(false.B);local.resume.valid.poke(false.B);local.cancel.poke(false.B);local.terminal.poke(false.B)
      eval.request.valid.poke(false.B);le.result.valid.poke(false.B);le.cancel.poke(false.B)
      pool.trap.valid.poke(true.B);pool.trap.bits.taskId.poke(0.U);pool.trap.bits.cu.poke(0.U)
      pool.trap.bits.event.pc.poke(0x4110.U);p.trap.ready.poke(false.B)
      for(_<-0 until 3){p.trap.valid.expect(true.B);p.trap.bits.taskId.expect(0x63.U)
        p.trap.bits.event.pc.expect(0x4110.U);v.trap.valid.expect(false.B);f.trap.valid.expect(false.B)
        pool.trap.ready.expect(false.B);d.clock.step()}
      p.trap.ready.poke(true.B);d.clock.step();pool.trap.valid.poke(false.B)
      // Retire FS first, while the patch and reserved VS remain live.
      pool.owned.poke(1.U);pool.completion.valid.poke(true.B);pool.completion.bits.commandId.poke(1.U)
      pool.completion.bits.success.poke(true.B);f.completion.ready.poke(false.B)
      for(_<-0 until 2){f.completion.valid.expect(true.B);f.completion.bits.commandId.expect(0x77.U)
        pool.completion.ready.expect(false.B);d.clock.step()}
      f.completion.ready.poke(true.B);d.clock.step();pool.completion.valid.poke(false.B)
      pool.owned.poke(0.U);pool.completion.valid.poke(true.B);pool.completion.bits.commandId.poke(0.U)
      p.completion.ready.poke(false.B)
      for(_<-0 until 3){p.completion.bits.commandId.expect(0x63.U);v.completion.valid.expect(false.B)
        pool.completion.ready.expect(false.B);pool.launch.valid.expect(false.B)
        d.io.innerPins(0).valid.expect(true.B);d.io.innerPins(1).valid.expect(true.B);d.clock.step()}
      p.completion.ready.poke(true.B);d.clock.step();pool.completion.valid.poke(false.B)
      until(pool.launch.valid.peek().litToBoolean)
      pool.launch.bits.commandId.expect(0.U);pool.launch.bits.launch.kernelPc.expect(0x5000.U)
      pool.nativeResources.expect(true.B);pool.uniformResources.expect(true.B)
      pool.geometryTask.get.valid.expect(false.B);pool.tessellationBases.get.valid.expect(false.B)
      pool.evaluationPatchBinding.get.valid.expect(false.B)
      assert(pool.geometryTask.get.bits.peek().litValue==0 && pool.tessellationBases.get.bits.peek().litValue==0 &&
        pool.evaluationPatchBinding.get.bits.peek().litValue==0)
      d.io.poolPayload.valid.expect(true.B);d.io.poolPayload.bits.context.expect(0x21.U)
      d.io.innerPins(0).valid.expect(false.B);d.io.innerPins(1).valid.expect(true.B)
      pool.launch.ready.poke(true.B);d.clock.step();pool.launch.ready.poke(false.B)
      pool.owned.poke(1.U);pool.taskIds(0).poke(0.U)
      v.owned.expect(1.U);v.taskIds(0).expect(0.U);p.owned.expect(0.U)
      pool.texture(0).vector.valid.poke(true.B);v.texture(0).vector.ready.poke(true.B)
      v.texture(0).vectorWriteback.valid.poke(true.B);v.texture(0).vectorWriteback.bits.writeback.data(7).poke(0xdead.U)
      pool.texture(0).vector.ready.expect(true.B);pool.texture(0).vectorWriteback.bits.writeback.data(7).expect(0xdead.U)
      p.texture(0).vector.valid.expect(false.B);p.texture(0).vectorWriteback.ready.expect(false.B)
      d.clock.step();pool.texture(0).vector.valid.poke(false.B);v.texture(0).vectorWriteback.valid.poke(false.B)
      pool.trap.valid.poke(true.B);pool.trap.bits.taskId.poke(0.U);pool.trap.bits.event.pc.poke(0x5110.U)
      v.trap.valid.expect(true.B);v.trap.bits.taskId.expect(0.U);p.trap.valid.expect(false.B)
      d.clock.step();pool.trap.valid.poke(false.B);pool.owned.poke(0.U)
      pool.completion.valid.poke(true.B);pool.completion.bits.commandId.poke(0.U);pool.completion.bits.success.poke(false.B)
      v.completion.ready.poke(false.B)
      for(_<-0 until 2){v.completion.valid.expect(true.B);v.completion.bits.commandId.expect(0.U)
        v.completion.bits.success.expect(false.B);p.completion.valid.expect(false.B);pool.completion.ready.expect(false.B);d.clock.step()}
      v.completion.ready.poke(true.B);d.clock.step();pool.completion.valid.poke(false.B)
      d.io.drained.expect(true.B);d.io.innerPins.foreach(_.valid.expect(false.B));d.io.outerPins.foreach(_.valid.expect(false.B))
    }
  }

  it should "retain the two-layer invalidate owner through held offers and acknowledgements" in {
    simulate(new TessellationVertexShaderRouterHarness){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val v=d.io.vertex;val p=d.io.patch;val pool=d.io.pool
      def until(test: =>Boolean):Unit={var n=0;while(!test && n<100){d.clock.step();n+=1};assert(test)}
      v.invalidate.bits.lineAddress.poke(0x100.U);v.invalidate.valid.poke(true.B)
      until(v.invalidate.ready.peek().litToBoolean);d.clock.step();v.invalidate.valid.poke(false.B)
      until(pool.invalidate.valid.peek().litToBoolean)
      // A newly offered competing request cannot replace the captured VS.
      p.invalidate.valid.poke(true.B);p.invalidate.bits.lineAddress.poke(0x200.U)
      for(_<-0 until 4){pool.invalidate.valid.expect(true.B);pool.invalidate.bits.lineAddress.expect(0x100.U)
        d.io.drained.expect(false.B);d.clock.step()}
      pool.invalidate.ready.poke(true.B);d.clock.step();pool.invalidate.ready.poke(false.B)
      pool.invalidateDone.valid.poke(true.B);pool.invalidateDone.bits.lineAddress.poke(0x100.U)
      v.invalidateDone.ready.poke(false.B)
      for(_<-0 until 4){v.invalidateDone.valid.expect(true.B);v.invalidateDone.bits.lineAddress.expect(0x100.U)
        p.invalidateDone.valid.expect(false.B);pool.invalidateDone.ready.expect(false.B);d.io.drained.expect(false.B);d.clock.step()}
      v.invalidateDone.ready.poke(true.B);d.clock.step();pool.invalidateDone.valid.poke(false.B)
      // Keep the competing offer until its real source handshake.
      until(p.invalidate.ready.peek().litToBoolean);d.clock.step();p.invalidate.valid.poke(false.B)
      until(pool.invalidate.valid.peek().litToBoolean);pool.invalidate.bits.lineAddress.expect(0x200.U)
      pool.invalidate.ready.poke(true.B);d.clock.step();pool.invalidate.ready.poke(false.B)
      pool.invalidateDone.valid.poke(true.B);pool.invalidateDone.bits.lineAddress.poke(0x200.U)
      p.invalidateDone.ready.poke(true.B);until(pool.invalidateDone.ready.peek().litToBoolean)
      p.invalidateDone.valid.expect(true.B);v.invalidateDone.valid.expect(false.B)
      d.clock.step();pool.invalidateDone.valid.poke(false.B);d.io.drained.expect(true.B)
    }
  }

  for((tess,eval)<-Seq((false,false),(true,false),(false,true)))
  it should s"preserve independently disabled optional interfaces tess=$tess eval=$eval" in {
    simulate(new TessellationVertexShaderRouter(GpuConfig(lanes=16,warps=8),2,8,
      tessellationAvailable=tess,evaluationPatchAvailable=eval)){d=>
      assert(d.io.patch.tessellation.nonEmpty==tess && d.io.pool.tessellation.nonEmpty==tess)
      assert(d.io.patch.evaluationPatch.nonEmpty==eval && d.io.pool.evaluationPatch.nonEmpty==eval)
      assert(d.io.vertex.geometryTask.isEmpty && d.io.vertex.tessellation.isEmpty && d.io.vertex.evaluationPatch.isEmpty)
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val p=d.io.patch;val pool=d.io.pool
      p.launch.valid.poke(true.B);p.launch.bits.commandId.poke(0x42.U)
      if(tess){p.tessellationBases.get.valid.poke(true.B);p.tessellationBases.get.bits(7).poke(31.U)}
      if(eval){p.evaluationPatchBinding.get.valid.poke(true.B);p.evaluationPatchBinding.get.bits.taskId.poke(0x123.U)}
      p.launch.ready.expect(true.B);d.clock.step();p.launch.valid.poke(false.B)
      pool.launch.valid.expect(true.B);pool.launch.bits.commandId.expect(0.U)
      if(tess){pool.tessellationBases.get.valid.expect(true.B);pool.tessellationBases.get.bits(7).expect(31.U)}
      if(eval){pool.evaluationPatchBinding.get.valid.expect(true.B);pool.evaluationPatchBinding.get.bits.taskId.expect(0x123.U)}
      pool.launch.ready.poke(true.B);d.clock.step();pool.completion.valid.poke(true.B)
      pool.completion.bits.commandId.poke(0.U);p.completion.ready.poke(true.B)
      p.completion.valid.expect(true.B);p.completion.bits.commandId.expect(0x42.U)
      d.clock.step();pool.completion.valid.poke(false.B);d.io.drained.expect(true.B)
    }
  }

  for(channel<-Seq("geometry","access","park","finish","evaluation"))
  it should s"reject unsupported $channel requests from the actual VS owner" in {
    assertThrows[chisel3.simulator.Exceptions.AssertionFailed]{
      simulate(new TessellationVertexShaderRouter(GpuConfig(lanes=16,warps=8),2,8,
        tessellationAvailable=true,evaluationPatchAvailable=true)){d=>
        zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
        d.io.vertex.launch.valid.poke(true.B);d.io.vertex.launch.bits.commandId.poke(0.U)
        d.io.vertex.launch.ready.expect(true.B);d.clock.step();d.io.vertex.launch.valid.poke(false.B)
        d.io.pool.launch.valid.expect(true.B);d.io.pool.launch.bits.commandId.expect(1.U)
        d.io.pool.launch.ready.poke(true.B);d.clock.step()
        d.io.pool.owned.poke(1.U);d.io.pool.taskIds(0).poke(1.U)
        d.io.vertex.owned.expect(1.U);d.io.vertex.taskIds(0).expect(0.U)
        channel match {
          case "geometry"=>val q=d.io.pool.geometryEvent.get(0);q.valid.poke(true.B);q.ready.expect(false.B)
          case "access"=>val q=d.io.pool.tessellation.get(0).request;q.valid.poke(true.B);q.ready.expect(false.B)
          case "park"=>val q=d.io.pool.tessellation.get(0).park;q.valid.poke(true.B);q.ready.expect(false.B)
          case "finish"=>val q=d.io.pool.tessellation.get(0).finish;q.valid.poke(true.B);q.ready.expect(false.B)
          case "evaluation"=>val q=d.io.pool.evaluationPatch.get(0).request;q.valid.poke(true.B);q.ready.expect(false.B)
        }
        d.io.patch.owned.expect(0.U);d.clock.step()
      }
    }
  }
}
