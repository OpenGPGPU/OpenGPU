package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.system._
import opengpu.graphics.tessellation._
import opengpu.testutil.GpuSim._
import opengpu.graphics.WindowRecordTest.zero
import org.scalatest.flatspec.AnyFlatSpec

private class TessellationVertexInputTupleHarness extends Module {
  private val p=VertexShaderStageConfig()
  val io=IO(new Bundle {
    val vertex=Input(new TessellationVertexPatchTask(p));val context=Input(new TessellationPatchContextTask)
    val identity=Input(new GraphicsWorkIdentity);val frame=Input(new GraphicsWorkIdentity)
    val instance=Input(UInt(32.W));val baseInstance=Input(UInt(32.W))
    val scope=Input(new TessellationUniformTaskResources);val oldUniforms=Input(Bool())
    val selectionCount=Input(UInt(6.W));val selections=Input(Vec(61,new IsolinePatchInputSelection))
    val legal=Output(Bool())
  })
  io.legal:=TessellationVertexInputImageTuple.legal(io.vertex,io.context,io.identity,io.instance,io.baseInstance,
    io.scope,io.oldUniforms,io.selectionCount,io.selections,io.frame,tessellationUniforms=true,evaluationPatchAvailable=true)
}

/** Actual common glue and Owner; UInt peers deliberately do not execute an
  * image, VS, TCS or TES shader. Full typed parents require a separate build. */
class TessellationVertexInputImageSpec extends AnyFlatSpec {
  it should "retire actual child receipts and the upstream range before a stable frame report" in {
    simulate(new TessellationVertexInputImageControl(VertexShaderStageConfig(),UInt(32.W),UInt(32.W),UInt(64.W))){d=>
      val io=d.io;zero(io);io.imageBegin.ready.poke(true.B)
      io.localTransportDrained.poke(true.B);io.frameMemoryDrained.poke(true.B)
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      io.begin.valid.poke(true.B);io.frameMemoryDrained.poke(false.B)
      assert(!io.begin.ready.peek().litToBoolean,"idle begin must wait for complete frame memory drain")
      io.imageBegin.valid.expect(false.B);d.clock.step()
      io.frameMemoryDrained.poke(true.B);io.localTransportDrained.poke(false.B)
      io.begin.ready.expect(false.B);d.clock.step()
      io.localTransportDrained.poke(true.B);io.sourceBusy.poke(true.B)
      io.begin.ready.expect(false.B);d.clock.step()
      io.sourceBusy.poke(false.B);io.begin.valid.poke(false.B)
      def until(p: =>Boolean,why:String):Unit={var n=0;while(!p && n<30){d.clock.step();n+=1};assert(p,why)}
      def begin(context:Int):Unit={
        io.cancel.poke(false.B);io.localTransportDrained.poke(true.B);io.frameMemoryDrained.poke(true.B)
        io.begin.bits.states.identity.context.poke(context.U);io.begin.valid.poke(true.B)
        io.begin.ready.expect(true.B);io.imageBegin.valid.expect(true.B);d.clock.step();io.begin.valid.poke(false.B)
        io.frameIdentity.context.expect(context.U);io.busy.expect(true.B)
      }
      def task(id:Int,legal:Boolean=true):Unit={
        io.tupleLegal.poke(legal.B);io.task.bits.vertex.taskId.poke(id.U)
        io.task.bits.source.poke((0x1000+id).U);io.task.valid.poke(true.B)
        io.task.ready.expect(true.B);d.clock.step();io.task.valid.poke(false.B)
      }
      def taskReport(id:Int,status:Int,raw:Boolean,owner:Boolean,rejected:Boolean=false):Unit={
        until(io.taskCompletion.valid.peek().litToBoolean,"task report")
        val r=io.taskCompletion.bits;r.taskId.expect(id.U);r.status.expect(status.U)
        r.rawSourceValid.expect(raw.B);r.ownerValid.expect(owner.B);r.tupleRejected.expect(rejected.B)
        io.taskDrained.expect(true.B)
        val bits=r.peek().litValue
        for(_<-0 until 3){io.taskCompletion.valid.expect(true.B);assert(r.peek().litValue==bits);d.clock.step()}
        // A later local flush retains the already-created report, while the
        // live per-patch barrier and admission still reflect its drain debt.
        io.localTransportDrained.poke(false.B)
        for(_<-0 until 3){
          io.taskCompletion.valid.expect(true.B);assert(r.peek().litValue==bits)
          io.taskDrained.expect(false.B);io.task.ready.expect(false.B);d.clock.step()
        }
        io.localTransportDrained.poke(true.B);io.taskDrained.expect(true.B)
        io.taskCompletion.ready.poke(true.B);d.clock.step();io.taskCompletion.ready.poke(false.B)
      }
      def imageFrame(status:Int,value:Int):Unit={
        io.imageStatus.poke(status.U);io.imageCompletion.bits.poke(value.U);io.imageCompletion.valid.poke(true.B)
        io.imageCompletion.ready.expect(true.B);d.clock.step();io.imageCompletion.valid.poke(false.B)
      }
      def end(local:Boolean):Unit={
        io.end.valid.poke(true.B);io.end.bits.poke(true.B)
        until(io.end.ready.peek().litToBoolean,"upstream end barrier")
        d.clock.step();io.end.valid.poke(false.B)
        if(local){io.imageEnd.valid.expect(false.B)}else{
          io.imageEnd.valid.expect(true.B);io.imageEnd.bits.expect(true.B)
          for(_<-0 until 3){io.imageEnd.valid.expect(true.B);d.clock.step()}
          io.imageEnd.ready.poke(true.B);d.clock.step();io.imageEnd.ready.poke(false.B)
        }
      }
      def frameReport(status:Int,raw:Int,fault:Boolean=false):Unit={
        until(io.completion.valid.peek().litToBoolean,"frame report")
        io.completion.bits.status.expect(status.U);io.completion.bits.img.expect(raw.U)
        io.completion.bits.taskFaultValid.expect(fault.B)
        val bits=io.completion.bits.peek().litValue
        // A late cancel/valid-only flush changes drain inputs, not an already
        // offered report or its immutable receipt snapshot.
        io.cancel.poke(true.B);io.frameMemoryDrained.poke(false.B);io.localTransportDrained.poke(false.B)
        for(_<-0 until 4){io.completion.valid.expect(true.B);assert(io.completion.bits.peek().litValue==bits);d.clock.step()}
        io.completion.ready.poke(true.B);d.clock.step();io.completion.ready.poke(false.B)
        io.cancel.poke(false.B);io.begin.valid.poke(true.B)
        assert(!io.begin.ready.peek().litToBoolean,"next frame cannot cross a retained flush/drain")
        io.imageBegin.valid.expect(false.B);d.clock.step();io.begin.valid.poke(false.B)
        io.frameMemoryDrained.poke(true.B);io.localTransportDrained.poke(true.B)
        io.busy.expect(false.B)
      }

      // End and the first range task compete in the same cycle: only task
      // transfers, and neither an offered end nor an idle source seals it.
      begin(9);io.frameMemoryDrained.poke(false.B);io.end.valid.poke(true.B);io.end.bits.poke(true.B)
      io.tupleLegal.poke(true.B);io.task.bits.vertex.taskId.poke(41.U);io.task.bits.source.poke(0x1041.U)
      io.task.valid.poke(true.B)
      assert(io.task.ready.peek().litToBoolean,"per-patch task must not wait for frame memory drain")
      io.end.ready.expect(false.B)
      d.clock.step();io.task.valid.poke(false.B);io.end.valid.poke(false.B)
      io.imageTask.valid.expect(true.B);io.imageTask.bits.expect(0x1041.U);io.sourceTask.valid.expect(false.B)
      io.imageTask.ready.poke(true.B);d.clock.step();io.imageTask.ready.poke(false.B)
      io.opened.valid.poke(true.B);io.opened.bits.poke(0.U);io.opened.ready.expect(true.B)
      d.clock.step();io.opened.valid.poke(false.B)
      io.sourceTask.valid.expect(true.B);io.sourceTask.bits.taskId.expect(41.U)
      io.sourceTask.ready.poke(true.B);d.clock.step();io.sourceTask.ready.poke(false.B);io.sourceBusy.poke(true.B)
      io.sourceOwned.expect(true.B);io.sourceMemoryLive.expect(true.B)
      io.sourceCompletion.valid.poke(true.B);io.sourceCompletion.bits.taskId.poke(41.U)
      io.sourceCompletion.bits.status.poke(0.U);io.sourceCompletion.ready.expect(true.B)
      d.clock.step();io.sourceCompletion.valid.poke(false.B);io.sourceBusy.poke(false.B)
      io.imageTaskCompletion.valid.poke(true.B);io.imageTaskCompletion.bits.poke(0xa1.U)
      io.imageTaskStatus.poke(0.U);io.published.poke(13.U);io.imageTaskCompletion.ready.expect(true.B)
      d.clock.step();io.imageTaskCompletion.valid.poke(false.B)
      until(io.taskCompletion.valid.peek().litToBoolean,"healthy task report")
      io.taskCompletion.bits.source.expect(0xa1.U);io.taskCompletion.bits.owner.image.published.expect(13.U)
      io.end.valid.poke(true.B);io.end.bits.poke(true.B);io.end.ready.expect(false.B);io.end.valid.poke(false.B)
      taskReport(41,0,raw=true,owner=true)
      end(local=false);io.frameMemoryDrained.poke(false.B);imageFrame(0,0xabcd)
      for(_<-0 until 4){io.completion.valid.expect(false.B);d.clock.step()}
      io.frameMemoryDrained.poke(true.B);frameReport(0,0xabcd)

      // Terminal invalid begin is an actual image receipt, not a fabricated
      // opened event. A subsequently held range tuple still retires locally.
      begin(10);imageFrame(1,0xbad)
      for(_<-0 until 3){io.completion.valid.expect(false.B);d.clock.step()}
      task(42);io.imageTask.valid.expect(false.B);io.sourceTask.valid.expect(false.B)
      taskReport(42,4,raw=false,owner=true)
      end(local=true);frameReport(1,0xbad)

      // A malformed tuple is a real wrapper admission failure even when cancel
      // is simultaneous. No Owner/raw child report or publication is invented.
      begin(11);io.cancel.poke(true.B);task(43,legal=false)
      io.imageTask.valid.expect(false.B);io.sourceTask.valid.expect(false.B)
      until(io.cancelImage.peek().litToBoolean,"local admission cancels image")
      imageFrame(4,0xcafe);taskReport(43,5,raw=false,owner=false,rejected=true)
      io.completion.valid.expect(false.B);end(local=true)
      until(io.completion.valid.peek().litToBoolean,"admission frame report")
      io.completion.bits.taskFault.taskId.expect(43.U);io.completion.bits.taskFault.tupleRejected.expect(true.B)
      frameReport(5,0xcafe,fault=true)

      // Actual task admission can fail with no opened. Preserve its raw packet
      // and original vertex ID, without waiting for a nonexistent VS launch.
      begin(12);task(44);io.imageTask.ready.poke(true.B);d.clock.step();io.imageTask.ready.poke(false.B)
      io.imageTaskCompletion.valid.poke(true.B);io.imageTaskCompletion.bits.poke(0xdead.U)
      io.imageTaskStatus.poke(1.U);io.published.poke(0.U)
      d.clock.step();io.imageTaskCompletion.valid.poke(false.B)
      taskReport(44,1,raw=true,owner=true);io.sourceTask.valid.expect(false.B)
      imageFrame(4,0xd00d);end(local=true);frameReport(1,0xd00d,fault=true)

      // A held image offer survives stop until a real terminal frame receipt;
      // cancel or !ready alone cannot abandon it. The held range task before
      // that receipt was accepted above, and another after it still retires.
      begin(13);task(45);io.imageTask.valid.expect(true.B)
      io.cancel.poke(true.B)
      for(_<-0 until 4){io.imageTask.valid.expect(true.B);io.taskDrained.expect(false.B);d.clock.step()}
      imageFrame(4,0xe00d);taskReport(45,4,raw=false,owner=true)
      task(46);taskReport(46,4,raw=false,owner=true)
      end(local=true);frameReport(4,0xe00d)
    }
  }

  it should "check actual tuple cross links, full coupled layouts and independent uniform scopes" in {
    simulate(new TessellationVertexInputTupleHarness){d=>
      val io=d.io;zero(io);val v=io.vertex;val c=io.context
      def identity(i:GraphicsWorkIdentity):Unit={i.context.poke(9.U);i.pass.poke(10.U);i.generation.poke(11.U)}
      identity(v.draw.input.identity);identity(io.identity);identity(io.frame)
      v.version.poke(1.U);v.draw.version.poke(1.U);v.taskId.poke(17.U);c.taskId.poke(17.U)
      v.draw.input.count.poke(64.U);v.draw.input.instances.poke(8.U);v.draw.input.baseInstance.poke(7.U)
      v.slice.version.poke(1.U);v.slice.firstOrdinal.poke(3.U);v.slice.firstInstance.poke(4.U)
      v.slice.instances.poke(1.U);io.instance.poke(4.U);io.baseInstance.poke(7.U)
      def layout(inV:Int,inC:Int,outV:Int,outC:Int,pp:Int):Unit={
        for(l<-Seq(v.layout,c.layout)){
          l.inputVertices.poke(inV.U);l.inputComponents.poke(inC.U);l.outputVertices.poke(outV.U)
          l.outputComponents.poke(outC.U);l.patchComponents.poke(pp.U)
        };v.slice.count.poke(inV.U)
      }
      def legal():Unit={io.legal.expect(true.B);d.clock.step()}
      def bad(change: =>Unit,restore: =>Unit):Unit={change;io.legal.expect(false.B);d.clock.step();restore;legal()}
      // TCS output12/perpatch8 is legal independently of a TES's exported8.
      layout(2,4,2,12,8);legal()
      bad(c.taskId.poke(18.U),c.taskId.poke(17.U))
      for((field,value)<-Seq((c.layout.inputVertices,3),(c.layout.inputComponents,5),
          (c.layout.outputVertices,3),(c.layout.outputComponents,13),(c.layout.patchComponents,9))){
        val old=field.peek().litValue;bad(field.poke(value.U),field.poke(old.U))
      }
      bad(io.identity.context.poke(8.U),io.identity.context.poke(9.U))
      bad(v.draw.input.identity.pass.poke(8.U),v.draw.input.identity.pass.poke(10.U))
      bad(io.frame.generation.poke(8.U),io.frame.generation.poke(11.U))
      bad(io.instance.poke(5.U),io.instance.poke(4.U))
      bad(io.baseInstance.poke(8.U),io.baseInstance.poke(7.U))
      bad(v.slice.instances.poke(2.U),v.slice.instances.poke(1.U))
      bad(v.slice.count.poke(3.U),v.slice.count.poke(2.U))
      bad(v.draw.uniforms.version.poke(3.U),v.draw.uniforms.version.poke(0.U))
      io.scope.control.resources.version.poke(3.U);io.scope.control.resources.address.poke(0x1000.U)
      identity(io.scope.identity);legal()
      bad(io.scope.identity.context.poke(8.U),io.scope.identity.context.poke(9.U))
      bad(io.scope.control.resources.address.poke(0x1004.U),io.scope.control.resources.address.poke(0x1000.U))
      bad(io.scope.control.resources.version.poke(1.U),io.scope.control.resources.version.poke(3.U))
      bad(io.oldUniforms.poke(true.B),io.oldUniforms.poke(false.B))
      zero(io.scope);legal()
      bad(io.scope.identity.context.poke(9.U),io.scope.identity.context.poke(0.U))
      bad(io.selectionCount.poke(1.U),io.selectionCount.poke(0.U))
      io.selections(60).component.poke(1.U);io.legal.expect(false.B);zero(io.selections);legal()
      layout(32,64,32,64,0);legal()
      layout(32,64,32,64,1);io.legal.expect(false.B)
      layout(32,64,32,60,120);legal()
      layout(32,64,32,60,121);io.legal.expect(false.B)
      layout(33,64,32,60,120);io.legal.expect(false.B)
    }
  }
}
