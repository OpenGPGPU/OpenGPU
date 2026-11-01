package opengpu.system

import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.graphics._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class TessellationInputSystemHarness(config: GpuConfig) extends Module {
  val gpu = Module(new GpuSystem(config, numComputeUnits = 1, commandIdWidth = 4, transactionsPerCu = 4,
    enableGraphicsKernels = true, geometryAvailable = true, tessellationAvailable = true,
    instructionCacheSets = 4, instructionCacheWays = 1, vectorCacheSets = 4, vectorCacheWays = 1))
  val client = Module(new TessellationSystemIsolineInput(config, 4, 1))
  val io = IO(new Bundle {
    val system = chiselTypeOf(gpu.io); val task = Flipped(Decoupled(new TessellationSystemIsolineInputTask(config, 4)))
    val memoryRequest = chiselTypeOf(client.io.memoryRequest); val memoryResponse = chiselTypeOf(client.io.memoryResponse)
    val out = Decoupled(new EvaluatedIsolinePrimitive); val completion = Decoupled(new TessellationSystemIsolineInputCompletion)
    val cancel = Input(Bool()); val trap = Decoupled(new TaggedGraphicsTrap(config, 4, 1))
    val evaluationOwned = Output(Bool())
  })
  private def tie(d: Data): Unit = d match {
    case r: Record => r.elements.values.foreach(tie); case v: Vec[_] => v.foreach(tie)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b := false.B
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u := 0.U
    case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s := 0.S
    case _ => ()
  }
  tie(client.io); io.system <> gpu.io; client.io.shared <> gpu.io.graphicsKernels.get
  client.io.task <> io.task; io.memoryRequest <> client.io.memoryRequest; client.io.memoryResponse <> io.memoryResponse
  io.out <> client.io.out; io.completion <> client.io.completion; client.io.cancel := io.cancel
  io.trap <> client.io.trap
  val evaluation = RegInit(false.B)
  when(client.io.shared.launch.fire) { evaluation := client.io.shared.geometryTask.get.valid }
  when(client.io.shared.completion.fire) { evaluation := false.B }
  io.evaluationOwned := evaluation && client.io.shared.owned.orR
}

class TessellationSystemIsolineInputSpec extends AnyFlatSpec {
  private def zero(d: Data): Unit = d match {
    case r: Record => r.elements.values.foreach(zero); case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s.poke(0.S)
    case _ => ()
  }
  private def vector(fn: Int, form: Int, rd: Int, vs1: Int): BigInt =
    (BigInt(fn)<<26)|(BigInt(1)<<25)|(BigInt(vs1)<<15)|(BigInt(form)<<12)|(BigInt(rd)<<7)|0x57
  private def patch(fn: Int, rd: Int = 0, vs1: Int = 0, vs2: Int = 0): BigInt =
    (BigInt(fn)<<26)|(BigInt(1)<<25)|(BigInt(vs2)<<20)|(BigInt(vs1)<<15)|(BigInt(rd)<<7)|0x2b
  private def geo(fn: Int, rd: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt =
    (BigInt(fn)<<25)|(BigInt(rs2)<<20)|(BigInt(rs1)<<15)|(BigInt(rd)<<7)|0x0b
  it should "load GPU patch inputs before executing control and evaluation on the shared system" in {
    val config = GpuConfig(lanes = 4, warps = 2, l2Sets = 8, l2Ways = 2)
    simulate(new TessellationInputSystemHarness(config)) { d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      val sys = d.io.system; sys.graphicsDrained.poke(true.B); sys.graphicsShaderL1Invalidate.ready.poke(true.B)
      d.io.trap.ready.poke(true.B)
      // Read input vertex 0/component 0 and store it to both outer levels and the patch word.
      val control = Vector(BigInt("0104f057",16), vector(23,3,2,0), vector(23,3,4,0),
        patch(21,rd=10,vs1=2,vs2=4), patch(20,vs1=10), vector(23,3,4,7), patch(22,vs1=2,vs2=4),
        vector(23,3,4,15), patch(22,vs1=2,vs2=4), vector(23,3,4,3), patch(22,vs1=2,vs2=4),
        BigInt("0000000b",16), BigInt("30500073",16))
      val eval = Vector(BigInt("00000093",16), BigInt("00000113",16), geo(32,3,1,2), BigInt("00000213",16),
        geo(33,0,4,3), BigInt("00300113",16), geo(32,3,1,2), BigInt("00100213",16), geo(33,0,4,3),
        geo(35), geo(36), BigInt("30500073",16))
      def line(p: Vector[BigInt]): BigInt = p.zipWithIndex.map { case (w,i) => w << (32*i) }.sum
      val pending = mutable.Queue.empty[(BigInt, BigInt, Int)]; val inputs = mutable.Queue.empty[Int]
      var cycle = 0; var outputs = 0; var inputReads = 0; var faultInput = false; var holdInput = false
      def tick(): Unit = {
        sys.memoryRequest.ready.poke((cycle%3 != 0).B)
        val response = pending.headOption.filter(_._3 <= cycle); sys.memoryResponse.valid.poke(response.nonEmpty.B)
        response.foreach { case (id,data,_) => sys.memoryResponse.bits.transactionId.poke(id.U); sys.memoryResponse.bits.readData.poke(data.U) }
        if (response.nonEmpty && sys.memoryResponse.ready.peek().litToBoolean) pending.dequeue()
        if (sys.memoryRequest.valid.peek().litToBoolean && sys.memoryRequest.ready.peek().litToBoolean) {
          val addr = sys.memoryRequest.bits.address.peek().litValue; assert(addr == 0x1000 || addr == 0x2000, s"fetch $addr")
          pending.enqueue((sys.memoryRequest.bits.transactionId.peek().litValue, line(if (addr == 0x1000) control else eval), cycle+3))
        }
        d.io.memoryRequest.ready.poke((!holdInput && cycle%3 != 0).B)
        if(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean){
          d.io.memoryRequest.bits.address.expect(0x3000.U);inputs.enqueue(cycle+2);inputReads+=1}
        val inputReply=inputs.headOption.exists(_<=cycle);d.io.memoryResponse.valid.poke(inputReply.B)
        d.io.memoryResponse.bits.fault.poke((inputReply && faultInput).B)
        d.io.memoryResponse.bits.readData.poke("h3f800000".U)
        if(inputReply && d.io.memoryResponse.ready.peek().litToBoolean)inputs.dequeue()
        d.io.trap.valid.expect(false.B)
        d.io.out.ready.poke((cycle%4 != 0).B)
        if (d.io.out.valid.peek().litToBoolean && d.io.out.ready.peek().litToBoolean) {
          d.io.out.bits.vertices(0)(0).expect(0.U); d.io.out.bits.vertices(1)(0).expect("h3f800000".U)
          d.io.out.bits.vertices(0)(1).expect("h3f800000".U); d.io.out.bits.vertices(1)(1).expect("h3f800000".U); outputs += 1
        }
        d.clock.step(); cycle += 1
      }
      def until(label: String)(p: => Boolean): Unit = { var n = 0; while (!p && n < 50000) { tick(); n += 1 }; assert(p, s"$label timeout at $cycle") }
      val outer = d.io.task.bits; val t = outer.source.patch; val ctl = t.control; ctl.patch.context.taskId.poke(31.U)
      ctl.patch.context.layout.inputVertices.poke(1.U); ctl.patch.context.layout.inputComponents.poke(1.U); ctl.patch.context.layout.outputVertices.poke(4.U)
      ctl.patch.context.layout.patchComponents.poke(1.U); ctl.kernel.kernelPc.poke(0x1000.U)
      for (i <- 0 until 3) { ctl.kernel.gridSize(i).poke(1.U); ctl.kernel.localSize(i).poke((if(i==0) 4 else 1).U) }
      ctl.bases(0).poke(0.U); ctl.bases(1).poke(4.U)
      t.commandId.poke(3.U); t.evaluationKernel.kernelPc.poke(0x2000.U); t.outputComponents.poke(2.U)
      t.patchInputCount.poke(1.U); t.patchInputs(0).perPatch.poke(true.B)
      outer.input.allocationBase.poke(0x3000.U);outer.input.allocationLimit.poke(0x3040.U)
      outer.input.base.poke(0x3000.U);outer.input.limit.poke(0x3004.U);outer.stride.poke(4.U)
      def start(): Unit = {
        d.io.task.valid.poke(true.B);until("task")(d.io.task.ready.peek().litToBoolean);tick();d.io.task.valid.poke(false.B)
      }
      def done(status:Int,expectedOutputs:Int):Unit={
        until("completion")(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.status.expect(status.U)
        assert(outputs==expectedOutputs && pending.isEmpty && inputs.isEmpty);d.io.evaluationOwned.expect(false.B)
        d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B)
      }
      start();done(0,1);assert(inputReads==1)
      outer.input.base.poke(0x3001.U);start();done(5,1);assert(inputReads==1);outer.input.base.poke(0x3000.U)
      faultInput=true;start();done(6,1);faultInput=false;assert(inputReads==2)
      holdInput=true;start();until("held input request")(d.io.memoryRequest.valid.peek().litToBoolean)
      val held=d.io.memoryRequest.bits.peek().litValue;d.io.cancel.poke(true.B);tick();d.io.cancel.poke(false.B)
      for(_<-0 until 4){d.io.memoryRequest.valid.expect(true.B);assert(d.io.memoryRequest.bits.peek().litValue==held)
        d.io.completion.valid.expect(false.B);tick()}
      holdInput=false;done(4,1);assert(inputReads==3)
      start();done(0,2);assert(inputReads==4)

    }
  }
}
