package opengpu.graphics

import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.system._
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}
import opengpu.testutil.GpuSim._
import opengpu.graphics.WindowRecordTest.zero
import org.scalatest.flatspec.AnyFlatSpec

/** Actual SystemClient/context/control ownership; the external CU only
  * accepts a launch and returns its actual protocol retirement receipt.
  * This fixture does not execute an ISA or manufacture geometry outputs.
  */
private class TessellationRetirementHarness(domain:Int,uniforms:Boolean) extends Module {
  private val config=GpuConfig(lanes=2,warps=2)
  private val cus=if(uniforms)2 else 1
  val io=IO(new Bundle {
    val start=Flipped(Decoupled(UInt(32.W)))
    val opened=Decoupled(UInt(2.W));val seal=Flipped(Decoupled(Bool()))
    val completion=Decoupled(new Bundle {val taskId=UInt(32.W);val status=UInt(3.W)})
    val shared=Flipped(new SharedCuGraphicsPort(config,4,cus,geometryAvailable=true,tessellationAvailable=true))
    val cancel=Input(Bool());val busy=Output(Bool())
    val memoryRequest=if(uniforms)Some(Decoupled(new ComputeMemoryRequest(config,64,2)))else None
    val memoryResponse=if(uniforms)Some(Flipped(Decoupled(new ComputeMemoryResponse(64,2))))else None
  })
  private def tie(d:Data):Unit=d match {
    case r:Record=>r.elements.values.foreach(tie)
    case v:Vec[_]=>v.foreach(tie)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b:=false.B
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u:=0.U
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s:=0.S
    case _=>()
  }
  private def bind[T <: Data,C <: Data](task:DecoupledIO[T],completion:DecoupledIO[C],status:UInt,id:UInt,
      opened:DecoupledIO[UInt],seal:DecoupledIO[Bool],shared:SharedCuGraphicsPort,cancel:Bool,busy:Bool,
      memoryRequest:Option[DecoupledIO[ComputeMemoryRequest]],memoryResponse:Option[DecoupledIO[ComputeMemoryResponse]]):Unit={
    task.valid:=io.start.valid;io.start.ready:=task.ready
    io.completion.valid:=completion.valid;completion.ready:=io.completion.ready
    io.completion.bits.status:=status;io.completion.bits.taskId:=id
    io.opened<>opened;seal<>io.seal;io.shared<>shared;cancel:=io.cancel;io.busy:=busy
    if(uniforms){io.memoryRequest.get<>memoryRequest.get;memoryResponse.get<>io.memoryResponse.get}
  }
  if(domain==0){
    val c=Module(new TessellationCuIsolineSystemClient(config,4,cus,uniforms));tie(c.io)
    val t=c.io.task.bits.patch;val ctl=t.control
    c.io.task.bits:=0.U.asTypeOf(c.io.task.bits)
    ctl.patch.context.taskId:=io.start.bits;ctl.patch.context.layout.inputVertices:=1.U
    ctl.patch.context.layout.outputVertices:=1.U;ctl.kernel.kernelPc:="h1000".U
    for(i<-0 until 3){ctl.kernel.gridSize(i):=1.U;ctl.kernel.localSize(i):=1.U}
    t.commandId:=3.U;t.evaluationKernel.kernelPc:="h2000".U;t.outputComponents:=4.U
    if(uniforms){val s=c.io.task.bits.uniforms
      s.identity.context:=7.U;s.identity.pass:=11.U;s.identity.generation:=13.U
      s.control.resources.version:=3.U;s.control.resources.address:="h3000".U
      s.evaluation.resources.version:=3.U;s.evaluation.resources.address:="h5000".U}
    bind(c.io.task,c.io.completion,c.io.completion.bits.status,c.io.completion.bits.taskId,
      c.io.opened,c.io.seal,c.io.shared,c.io.cancel,c.io.busy,c.io.uniformMemoryRequest,c.io.uniformMemoryResponse)
  }
  if(domain==1){
    val c=Module(new TessellationCuTriangleSystemClient(config,4,cus,uniforms));tie(c.io)
    val t=c.io.task.bits.patch;val ctl=t.control
    c.io.task.bits:=0.U.asTypeOf(c.io.task.bits)
    ctl.patch.context.taskId:=io.start.bits;ctl.patch.context.layout.inputVertices:=1.U
    ctl.patch.context.layout.outputVertices:=1.U;ctl.kernel.kernelPc:="h1000".U
    for(i<-0 until 3){ctl.kernel.gridSize(i):=1.U;ctl.kernel.localSize(i):=1.U}
    t.commandId:=3.U;t.evaluationKernel.kernelPc:="h2000".U;t.outputComponents:=4.U
    if(uniforms){val s=c.io.task.bits.uniforms
      s.identity.context:=7.U;s.identity.pass:=11.U;s.identity.generation:=13.U
      s.control.resources.version:=3.U;s.control.resources.address:="h3000".U
      s.evaluation.resources.version:=3.U;s.evaluation.resources.address:="h5000".U}
    bind(c.io.task,c.io.completion,c.io.completion.bits.status,c.io.completion.bits.taskId,
      c.io.opened,c.io.seal,c.io.shared,c.io.cancel,c.io.busy,c.io.uniformMemoryRequest,c.io.uniformMemoryResponse)
  }
  if(domain==2){
    val c=Module(new TessellationCuQuadSystemClient(config,4,cus,uniforms));tie(c.io)
    val t=c.io.task.bits.patch;val ctl=t.control
    c.io.task.bits:=0.U.asTypeOf(c.io.task.bits)
    ctl.patch.context.taskId:=io.start.bits;ctl.patch.context.layout.inputVertices:=1.U
    ctl.patch.context.layout.outputVertices:=1.U;ctl.kernel.kernelPc:="h1000".U
    for(i<-0 until 3){ctl.kernel.gridSize(i):=1.U;ctl.kernel.localSize(i):=1.U}
    t.commandId:=3.U;t.evaluationKernel.kernelPc:="h2000".U;t.outputComponents:=4.U
    if(uniforms){val s=c.io.task.bits.uniforms
      s.identity.context:=7.U;s.identity.pass:=11.U;s.identity.generation:=13.U
      s.control.resources.version:=3.U;s.control.resources.address:="h3000".U
      s.evaluation.resources.version:=3.U;s.evaluation.resources.address:="h5000".U}
    bind(c.io.task,c.io.completion,c.io.completion.bits.status,c.io.completion.bits.taskId,
      c.io.opened,c.io.seal,c.io.shared,c.io.cancel,c.io.busy,c.io.uniformMemoryRequest,c.io.uniformMemoryResponse)
  }
}

class TessellationSystemClientRetirementSpec extends AnyFlatSpec {
  for(domain<-0 until 3;uniforms<-Seq(false,true))
  it should s"release task stop state only after report retirement domain=$domain uniforms=$uniforms" in {
    simulate(new TessellationRetirementHarness(domain,uniforms)){d=>
      val io=d.io;zero(io);d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
      def until(p: =>Boolean,why:String):Unit={var n=0;while(!p && n<1000){d.clock.step();n+=1};assert(p,why)}
      def start(id:Int,reuse:Boolean):Unit={
        io.start.bits.poke(id.U);io.start.valid.poke(true.B)
        var n=0;while(!io.start.ready.peek().litToBoolean && n<16){d.clock.step();n+=1}
        assert(io.start.ready.peek().litToBoolean,
          if(uniforms && reuse)"next task must be accepted after actual fault report retirement"
          else "next task must be accepted after actual cancel report retirement")
        d.clock.step();io.start.valid.poke(false.B)
        io.opened.ready.poke(true.B);until(io.opened.valid.peek().litToBoolean,"actual context opening")
        io.opened.bits.expect(0.U);d.clock.step();io.opened.ready.poke(false.B)
        io.seal.valid.poke(true.B);io.seal.bits.poke(true.B)
        until(io.seal.ready.peek().litToBoolean,"actual context seal")
        d.clock.step();io.seal.valid.poke(false.B)
        until(io.shared.launch.valid.peek().litToBoolean,"actual control kernel offer")
        io.shared.launch.bits.commandId.expect(3.U);io.shared.geometryTask.get.valid.expect(false.B)
        io.shared.launch.ready.poke(true.B);d.clock.step();io.shared.launch.ready.poke(false.B)
        io.shared.owned.poke(1.U);io.shared.taskIds(0).poke(3.U)
      }
      def physical(success:Boolean,cancel:Boolean):Unit={
        // An accepted real launch is still owned until this peer's retirement.
        if(cancel){io.cancel.poke(true.B);d.clock.step();io.cancel.poke(false.B)}
        for(_<-0 until 3){io.completion.valid.expect(false.B);d.clock.step()}
        io.shared.completion.valid.poke(true.B);io.shared.completion.bits.commandId.poke(3.U)
        io.shared.completion.bits.success.poke(success.B)
        until(io.shared.completion.ready.peek().litToBoolean,"physical CU retirement accepted")
        d.clock.step();io.shared.completion.valid.poke(false.B);io.shared.owned.poke(0.U)
      }
      def finish(id:Int,status:Int):Unit={
        until(io.completion.valid.peek().litToBoolean,"actual context/task completion")
        io.completion.bits.taskId.expect(id.U);io.completion.bits.status.expect(status.U)
        val bits=io.completion.bits.peek().litValue
        // A prospective next scope cannot clear a held task's cancellation or
        // fault state. Its acceptance is permitted only after the true report.
        io.start.bits.poke((id+1).U);io.start.valid.poke(true.B)
        for(_<-0 until 6){io.completion.valid.expect(true.B);assert(io.completion.bits.peek().litValue==bits)
          io.start.ready.expect(false.B);io.busy.expect(true.B);d.clock.step()}
        io.start.valid.poke(false.B);io.completion.ready.poke(true.B);d.clock.step()
        io.completion.ready.poke(false.B);io.busy.expect(false.B)
        if(uniforms)io.memoryRequest.get.valid.expect(false.B)
      }
      // False isolates latched cancellation; true independently isolates a
      // physical execution fault with the real checked service instantiated.
      // No uniform fetch or scalar/vector execution is claimed by this test.
      start(31,reuse=false)
      physical(success= !uniforms,cancel= !uniforms);finish(31,if(uniforms)2 else 4)
      start(32,reuse=true) // original client deadlocks exactly here
      physical(success=true,cancel=true);finish(32,4)
      start(33,reuse=true);physical(success=true,cancel=true);finish(33,4)
      io.start.ready.expect(true.B);io.busy.expect(false.B)
    }
  }
}
