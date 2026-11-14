package opengpu.graphics

import chisel3._
import opengpu.graphics.WindowRecordTest._
import opengpu.system.TessellationVertexPatchPublisher
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

/** Protocol-peer tests for the publisher, not original-shader execution. */
class TessellationVertexPatchPublisherSpec extends AnyFlatSpec {
  private val p=VertexShaderStageConfig()
  private class Driver(val d:TessellationVertexPatchPublisher){
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    def until(done: =>Boolean):Unit={var n=0;while(!done && n<200){d.clock.step();n+=1};assert(done,"vertex patch publisher stalled")}
    def descriptor(vertices:Int=1,components:Int=2):Unit={
      zero(d.io.task.bits);val t=d.io.task.bits
      t.version.poke(1.U);t.taskId.poke(55.U)
      t.layout.inputVertices.poke(vertices.U);t.layout.inputComponents.poke(components.U)
      t.layout.outputVertices.poke(32.U);t.layout.outputComponents.poke(64.U)
      t.draw.version.poke(1.U);t.draw.shaderPc.poke(0x1000.U)
      t.draw.input.version.poke(1.U);identity(t.draw.input.identity)
      t.draw.input.count.poke(40.U);t.draw.input.instances.poke(8.U);t.draw.input.baseInstance.poke(2.U)
      t.draw.varyingSlots.poke(15.U);t.draw.varyingMask.poke(((BigInt(1)<<60)-1).U)
      t.slice.version.poke(1.U);t.slice.firstOrdinal.poke(5.U);t.slice.count.poke(vertices.U)
      t.slice.firstInstance.poke(3.U);t.slice.instances.poke(1.U)
      for(c<-0 until components){t.components(c).kind.poke((if(c<4)0 else 1).U)
        t.components(c).component.poke((if(c<4)c else c-4).U)}
    }
    def accept():Unit={d.io.task.valid.poke(true.B);until(d.io.task.ready.peek().litToBoolean)
      d.clock.step();d.io.task.valid.poke(false.B);zero(d.io.task.bits)}
    def launch():Unit={
      until(d.io.sourceDraw.valid.peek().litToBoolean)
      d.io.sourceSlice.firstOrdinal.expect(5.U);d.io.sourceSlice.firstInstance.expect(3.U)
      val held=d.io.sourceDraw.bits.peek().litValue;val slice=d.io.sourceSlice.peek().litValue
      for(_<-0 until 3){d.clock.step();d.io.sourceDraw.valid.expect(true.B)
        assert(d.io.sourceDraw.bits.peek().litValue==held && d.io.sourceSlice.peek().litValue==slice)}
      d.io.sourceDraw.ready.poke(true.B);d.clock.step();d.io.sourceDraw.ready.poke(false.B);d.io.sourceBusy.poke(true.B)
    }
    def output(v:Int,restart:Boolean=false,ordinalOffset:Int=0):Unit={
      val r=d.io.sourceOut.bits;zero(r);identity(r.identity)
      r.ordinal.poke((5+v+ordinalOffset).U);r.instanceId.poke(3.U);r.baseInstance.poke(2.U);r.restart.poke(restart.B)
      for(c<-0 until 4)r.position(c).poke((0x10000000L+256*v+c).U)
      for(slot<-0 until 16;c<-0 until 4)r.varyings(slot)(c).poke((0x20000000L+256*v+4*slot+c).U)
      d.io.sourceOut.valid.poke(true.B);until(d.io.sourceOut.ready.peek().litToBoolean)
      d.clock.step();d.io.sourceOut.valid.poke(false.B);zero(r)
    }
    def write(v:Int,c:Int):Unit={
      until(d.io.load.valid.peek().litToBoolean)
      val a=d.io.load.bits;a.write.expect(true.B);a.output.expect(false.B);a.perPatch.expect(false.B);a.tessLevel.expect(false.B)
      a.vertex.expect(v.U);a.component.expect(c.U)
      a.data.expect((if(c<4)0x10000000L+256*v+c else 0x20000000L+256*v+c-4).U)
      val held=a.peek().litValue
      for(_<-0 until 2){d.clock.step();d.io.load.valid.expect(true.B);assert(a.peek().litValue==held)}
      d.io.load.ready.poke(true.B);d.clock.step();d.io.load.ready.poke(false.B)
    }
    def reply(status:Int=0,source:Int=0):Unit={
      zero(d.io.reply.bits);d.io.reply.bits.source.poke(source.U);d.io.reply.bits.result.status.poke(status.U)
      d.io.reply.valid.poke(true.B);until(d.io.reply.ready.peek().litToBoolean)
      d.clock.step();d.io.reply.valid.poke(false.B)
    }
    def completed(status:Int,vertices:Int):Unit={
      val r=d.io.sourceCompletion.bits;zero(r);identity(r.identity)
      r.status.poke(status.U);r.vertices.poke(vertices.U)
      d.io.sourceCompletion.valid.poke(true.B);until(d.io.sourceCompletion.ready.peek().litToBoolean)
      d.clock.step();d.io.sourceCompletion.valid.poke(false.B);d.io.sourceBusy.poke(false.B);zero(r)
    }
    def result(status:Int,words:Int):Unit={
      until(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.taskId.expect(55.U);d.io.completion.bits.status.expect(status.U)
      d.io.completion.bits.words.expect(words.U)
      val held=d.io.completion.bits.peek().litValue
      for(_<-0 until 4){d.clock.step();d.io.task.ready.expect(false.B)
        assert(d.io.completion.bits.peek().litValue==held)}
      d.io.completion.ready.poke(true.B);d.clock.step();d.io.completion.ready.poke(false.B)
      d.io.busy.expect(false.B)
    }
  }
  it should "publish all 32 by 64 linked words only after replies and actual source completion" in {
    simulate(new TessellationVertexPatchPublisher(p)){d=>
      val t=new Driver(d);t.descriptor(32,64);t.accept();t.launch()
      for(v<-0 until 32){t.output(v);for(c<-0 until 64){t.write(v,c);t.reply()}}
      for(_<-0 until 5){d.clock.step();d.io.seal.valid.expect(false.B);d.io.completion.valid.expect(false.B)}
      t.completed(0,32);t.until(d.io.seal.valid.peek().litToBoolean)
      for(_<-0 until 5){d.clock.step();d.io.seal.valid.expect(true.B);d.io.completion.valid.expect(false.B)}
      d.io.seal.ready.poke(true.B);d.clock.step();d.io.seal.ready.poke(false.B)
      t.result(0,2048)
      // A zero-component patch still executes and retires its original VS.
      t.descriptor(2,0);t.accept();t.launch();t.output(0);t.output(1)
      d.io.load.valid.expect(false.B);t.completed(0,2);t.until(d.io.seal.valid.peek().litToBoolean)
      d.io.seal.ready.poke(true.B);d.clock.step();d.io.seal.ready.poke(false.B);t.result(0,0)
    }
  }
  it should "drain held patch writes and late replies after canceled or failed source execution before reuse" in {
    simulate(new TessellationVertexPatchPublisher(p)){d=>
      val t=new Driver(d)
      t.descriptor();t.accept();t.until(d.io.sourceDraw.valid.peek().litToBoolean)
      d.io.cancel.poke(true.B)
      for(_<-0 until 3){d.clock.step();d.io.sourceDraw.valid.expect(true.B);d.io.cancelSource.expect(false.B)}
      t.launch();d.io.cancelSource.expect(true.B);t.completed(4,0);t.result(4,0);d.io.cancel.poke(false.B)
      for(mode<-Seq("cancel","patch","protocol","cancel-protocol")){
        t.descriptor();t.accept();t.launch();t.output(0)
        t.until(d.io.load.valid.peek().litToBoolean);val held=d.io.load.bits.peek().litValue
        if(mode.startsWith("cancel"))d.io.cancel.poke(true.B)
        for(_<-0 until 3){d.clock.step();d.io.load.valid.expect(true.B);assert(d.io.load.bits.peek().litValue==held)}
        t.write(0,0)
        if(mode=="cancel"){
          t.completed(4,1)
          for(_<-0 until 5){d.clock.step();d.io.busy.expect(true.B);d.io.completion.valid.expect(false.B);d.io.seal.valid.expect(false.B)}
          t.reply(1);t.result(4,0);d.io.cancel.poke(false.B)
        }else{
          t.reply(1,if(mode.endsWith("protocol"))1 else 0);d.io.cancel.poke(true.B)
          t.completed(4,1);t.result(3,0);d.io.cancel.poke(false.B)
        }
      }
      for(bad<-Seq("restart","ordinal")){
        t.descriptor();t.accept();t.launch()
        t.output(0,restart=bad=="restart",ordinalOffset=if(bad=="ordinal")1 else 0)
        t.completed(4,1);d.io.load.valid.expect(false.B);d.io.seal.valid.expect(false.B);t.result(1,0)
      }
      t.descriptor();t.accept();t.launch();t.output(0)
      for(c<-0 until 2){t.write(0,c);t.reply()};t.completed(0,1)
      t.until(d.io.seal.valid.peek().litToBoolean)
      d.io.cancel.poke(true.B)
      for(_<-0 until 3){d.clock.step();d.io.seal.valid.expect(true.B)}
      d.io.seal.ready.poke(true.B);d.clock.step();d.io.seal.ready.poke(false.B)
      t.result(4,2);d.io.cancel.poke(false.B)
      // Healthy reuse after all cancellation/error paths, without reset.
      t.descriptor();t.accept();t.launch();t.output(0)
      for(c<-0 until 2){t.write(0,c);t.reply()};t.completed(0,1)
      t.until(d.io.seal.valid.peek().litToBoolean)
      d.io.seal.ready.poke(true.B);d.clock.step();d.io.seal.ready.poke(false.B);t.result(0,2)
    }
  }
  it should "reject invalid source layout and inactive mappings without launching a VS or writing a patch" in {
    simulate(new TessellationVertexPatchPublisher(p)){d=>
      val t=new Driver(d)
      for(mode<-Seq("version","slice","total","mapping","disabled-varying","point-size")){
        t.descriptor();val q=d.io.task.bits
        if(mode=="version")q.version.poke(2.U)
        if(mode=="slice")q.slice.instances.poke(2.U)
        if(mode=="total")q.layout.patchComponents.poke(1.U)
        if(mode=="mapping")q.components(2).component.poke(1.U)
        if(mode=="disabled-varying"){q.components(0).kind.poke(1.U);q.draw.varyingMask.poke(0.U)}
        if(mode=="point-size"){q.components(0).kind.poke(2.U);q.components(0).component.poke(0.U)}
        t.accept();t.until(d.io.completion.valid.peek().litToBoolean)
        d.io.sourceDraw.valid.expect(false.B);d.io.load.valid.expect(false.B);d.io.seal.valid.expect(false.B)
        t.result(1,0)
      }
    }
  }
}
