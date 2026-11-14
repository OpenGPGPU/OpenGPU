package opengpu.system
import opengpu.graphics.tessellation._

import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.graphics._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class TessellationSystemHarness(config: GpuConfig,tessellationUniforms:Boolean=false,cus:Int=1) extends Module {
  val gpu = Module(new GpuSystem(config, numComputeUnits = cus, commandIdWidth = 4, transactionsPerCu = 4,
    enableGraphicsKernels = true, geometryAvailable = true, tessellationAvailable = true,
    instructionCacheSets = 4, instructionCacheWays = 1, vectorCacheSets = 4, vectorCacheWays = 1))
  val client = Module(new TessellationCuIsolineSystemClient(config,4,cus,tessellationUniforms))
  val io = IO(new Bundle {
    val system = chiselTypeOf(gpu.io); val task = Flipped(Decoupled(new TessellationCuIsolineSystemTask(config, 4)))
    val opened = Decoupled(UInt(2.W)); val seal = Flipped(Decoupled(Bool()))
    val out = Decoupled(new EvaluatedIsolinePrimitive); val completion = Decoupled(new TessellationCuIsolineCompletion)
    val cancel = Input(Bool()); val trap = Decoupled(new TaggedGraphicsTrap(config,4,cus))
    val uniformMemoryRequest=if(tessellationUniforms)Some(Decoupled(new opengpu.core.memory.ComputeMemoryRequest(config,64,2)))else None
    val uniformMemoryResponse=if(tessellationUniforms)Some(Flipped(Decoupled(new opengpu.core.memory.ComputeMemoryResponse(64,2))))else None
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
  client.io.task <> io.task; io.opened <> client.io.opened; client.io.seal <> io.seal
  io.out <> client.io.out; io.completion <> client.io.completion; client.io.cancel := io.cancel
  io.trap <> client.io.trap
  if(tessellationUniforms){io.uniformMemoryRequest.get<>client.io.uniformMemoryRequest.get
    client.io.uniformMemoryResponse.get<>io.uniformMemoryResponse.get}
  val evaluation = RegInit(false.B)
  when(client.io.shared.launch.fire) { evaluation := client.io.shared.geometryTask.get.valid }
  when(client.io.shared.completion.fire) { evaluation := false.B }
  io.evaluationOwned := evaluation && client.io.shared.owned.orR
}

class TessellationCuIsolineSystemClientSpec extends AnyFlatSpec {
  private def zero(d: Data): Unit = d match {
    case r: Record => r.elements.values.foreach(zero); case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s.poke(0.S)
    case _ => ()
  }
  private def vector(fn: Int, form: Int, rd: Int, vs1: Int): BigInt =
    (BigInt(fn)<<26)|(BigInt(1)<<25)|(BigInt(vs1)<<15)|(BigInt(form)<<12)|(BigInt(rd)<<7)|0x57
  private def patch(fn: Int, vs1: Int = 0, vs2: Int = 0): BigInt =
    (BigInt(fn)<<26)|(BigInt(1)<<25)|(BigInt(vs2)<<20)|(BigInt(vs1)<<15)|0x2b
  private def geo(fn: Int, rd: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt =
    (BigInt(fn)<<25)|(BigInt(rs2)<<20)|(BigInt(rs1)<<15)|(BigInt(rd)<<7)|0x0b
  for(uniforms<-Seq(false,true))it should s"run actual shared CU control/evaluation with checked-uniform service=$uniforms" in {
    val config = GpuConfig(lanes = 4, warps = 2, l2Sets = 8, l2Ways = 2)
    simulate(new TessellationSystemHarness(config,uniforms,if(uniforms)2 else 1)) { d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      val sys = d.io.system; sys.graphicsDrained.poke(true.B); sys.graphicsShaderL1Invalidate.ready.poke(true.B)
      d.io.trap.ready.poke(true.B)
      val uniformPrefix=Vector(BigInt("0104f057",16),BigInt("01f00093",16),BigInt("00000113",16),
        (BigInt(7)<<25)|(BigInt(2)<<20)|(BigInt(1)<<15)|(BigInt(10)<<7)|0x0b,
        vector(23,4,5,10),vector(23,3,2,11),vector(23,3,4,0),
        (BigInt(7)<<26)|(BigInt(1)<<25)|(BigInt(4)<<20)|(BigInt(2)<<15)|(BigInt(3)<<7)|0x2b,
        vector(0,1,3,5)|(BigInt(3)<<20))
      val originalControl = Vector(BigInt("0104f057",16), BigInt("3f800537",16), vector(23,4,3,10), vector(23,3,2,0),
        vector(23,3,4,7), patch(20,vs1=3), patch(22,vs1=2,vs2=4), vector(23,3,4,15), patch(22,vs1=2,vs2=4),
        vector(23,3,4,3), patch(22,vs1=2,vs2=4), BigInt("0000000b",16), BigInt("30500073",16))
      val control=if(uniforms)uniformPrefix++originalControl.drop(3)else originalControl
      val originalEval = Vector(BigInt("00000093",16), BigInt("00000113",16), geo(32,3,1,2), BigInt("00000213",16),
        geo(33,0,4,3), BigInt("00300113",16), geo(32,3,1,2), BigInt("00100213",16), geo(33,0,4,3),
        geo(35), geo(36), BigInt("30500073",16))
      val eval=if(uniforms)originalEval.take(5)++uniformPrefix++Vector(
        (BigInt(16)<<26)|(BigInt(1)<<25)|(BigInt(3)<<20)|(BigInt(2)<<12)|(BigInt(3)<<7)|0x57,
        BigInt("00100213",16),geo(33,0,4,3),geo(35),geo(36),BigInt("30500073",16))else originalEval
      val uniformMemory=if(uniforms)Some(new WindowRecordTest.Memory(d.io.uniformMemoryRequest.get.bits,d.io.uniformMemoryResponse.get.bits,
        d.io.uniformMemoryRequest.get.valid,d.io.uniformMemoryRequest.get.ready,d.io.uniformMemoryResponse.get.valid,d.io.uniformMemoryResponse.get.ready))else None
      def put(address:Int,words:Seq[BigInt]):Unit=uniformMemory.foreach(m=>words.zipWithIndex.foreach{case(v,i)=>m.putWord(address+4*i,v)})
      for((stage,root,table,data,default,defaultData)<-Seq((4,0x3000,0x4000,0x10000,0x4800,0x19000),(5,0x5000,0x6000,0x20000,0x6800,0x29000))){
        val header=Vector[BigInt](BigInt("47555203",16),64,3,7,11,13,stage,1<<11,0,0,table,table+1536,0,default,1,0)
        put(root,header) // block11 maps to private slot0; defaults retain index31.
        def view(index:Int,base:Int)=Vector[BigInt](BigInt("47554203",16),64,3,7,11,13,index,1<<stage,base,base+4096,0,base,base+4096,0,1,0)
        put(table,view(0,data));put(default,view(31,defaultData))
        uniformMemory.foreach{m=>m.putWord(data,if(stage==4)BigInt("3f000000",16)else BigInt("3fc00000",16));m.putWord(defaultData,BigInt("3f000000",16))}
      }
      def line(p: Vector[BigInt]): BigInt = p.zipWithIndex.map { case (w,i) => w << (32*i) }.sum
      val pending = mutable.Queue.empty[(BigInt, BigInt, Int)]; var cycle = 0; var outputs = 0; var cancelling = false
      def tick(): Unit = {
        uniformMemory.foreach(m=>{m.drive();m.capture()})
        sys.memoryRequest.ready.poke((cycle%3 != 0).B)
        val response = pending.headOption.filter(_._3 <= cycle); sys.memoryResponse.valid.poke(response.nonEmpty.B)
        response.foreach { case (id,data,_) => sys.memoryResponse.bits.transactionId.poke(id.U); sys.memoryResponse.bits.readData.poke(data.U) }
        if (response.nonEmpty && sys.memoryResponse.ready.peek().litToBoolean) pending.dequeue()
        if (sys.memoryRequest.valid.peek().litToBoolean && sys.memoryRequest.ready.peek().litToBoolean) {
          val addr = sys.memoryRequest.bits.address.peek().litValue
          val (pc,program)=if(addr>=0x1000 && addr<0x1800)(BigInt(0x1000),control)else(BigInt(0x2000),eval)
          assert(addr>=pc && addr<pc+program.size*4+64 && (addr-pc)%64==0,s"fetch $addr")
          val offset=((addr-pc)/4).toInt
          pending.enqueue((sys.memoryRequest.bits.transactionId.peek().litValue,line(program.slice(offset,offset+16)),cycle+3))
        }
        if (d.io.trap.valid.peek().litToBoolean) assert(cancelling, s"unexpected trap at $cycle")
        d.io.out.ready.poke((cycle%4 != 0).B)
        if (d.io.out.valid.peek().litToBoolean && d.io.out.ready.peek().litToBoolean) {
          d.io.out.bits.vertices(0)(0).expect(0.U); d.io.out.bits.vertices(1)(0).expect("h3f800000".U)
          d.io.out.bits.vertices(0)(1).expect((if(uniforms)BigInt("40000000",16)else BigInt("3f800000",16)).U); d.io.out.bits.vertices(1)(1).expect((if(uniforms)BigInt("40000000",16)else BigInt("3f800000",16)).U); outputs += 1
        }
        d.clock.step(); cycle += 1
      }
      def until(label: String)(p: => Boolean): Unit = { var n = 0; while (!p && n < 50000) { tick(); n += 1 }; assert(p, s"$label timeout at $cycle") }
      def bindUniforms():Unit=if(uniforms){val scope=d.io.task.bits.uniforms
        scope.identity.context.poke(7.U);scope.identity.pass.poke(11.U);scope.identity.generation.poke(13.U)
        scope.control.resources.version.poke(3.U);scope.control.resources.address.poke(0x3000.U)
        scope.evaluation.resources.version.poke(3.U);scope.evaluation.resources.address.poke(0x5000.U)}
      val t = d.io.task.bits.patch; val ctl = t.control; ctl.patch.context.taskId.poke(31.U)
      ctl.patch.context.layout.inputVertices.poke(1.U); ctl.patch.context.layout.outputVertices.poke(4.U)
      ctl.patch.context.layout.patchComponents.poke(1.U); ctl.kernel.kernelPc.poke(0x1000.U)
      for (i <- 0 until 3) { ctl.kernel.gridSize(i).poke(1.U); ctl.kernel.localSize(i).poke((if(i==0) 4 else 1).U) }
      ctl.bases(0).poke(0.U); ctl.bases(1).poke(4.U)
      t.commandId.poke(3.U); t.evaluationKernel.kernelPc.poke(0x2000.U); t.outputComponents.poke(2.U)
      t.patchInputCount.poke(1.U); t.patchInputs(0).perPatch.poke(true.B)
      def start(): Unit = {
        bindUniforms()
        d.io.task.valid.poke(true.B); until("task")(d.io.task.ready.peek().litToBoolean); tick(); d.io.task.valid.poke(false.B)
        if(uniforms){ // Accepted resources must survive caller input changes.
          d.io.task.bits.uniforms.control.resources.address.poke(0xdeac.U)
          d.io.task.bits.uniforms.evaluation.resources.address.poke(0xbeec.U)
          d.io.task.bits.uniforms.identity.context.poke(99.U)
        }
        d.io.opened.ready.poke(true.B); until("open")(d.io.opened.valid.peek().litToBoolean); d.io.opened.bits.expect(0.U)
        tick(); d.io.opened.ready.poke(false.B); d.io.seal.valid.poke(true.B); until("seal")(d.io.seal.ready.peek().litToBoolean)
        tick(); d.io.seal.valid.poke(false.B)
      }
      def done(status: Int, expectedOutputs: Int): Unit = {
        until("completion")(d.io.completion.valid.peek().litToBoolean); d.io.completion.bits.status.expect(status.U)
        d.io.completion.bits.control.barriers.expect(1.U); d.io.completion.bits.published.expect((if (status == 0) 1 else 0).U)
        assert(outputs == expectedOutputs && pending.isEmpty); d.io.evaluationOwned.expect(false.B)
        d.io.completion.ready.poke(true.B); tick(); d.io.completion.ready.poke(false.B)
      }
      start(); done(0, 1)
      val before=uniformMemory.map(_.seen.size).getOrElse(0)
      if(uniforms)uniformMemory.get.beforeRequest=r=>{
        if(r.address==0x5000)uniformMemory.get.holdResponses=true
      }
      start()
      if(uniforms){
        until("accepted TES uniform root with response held")(
          uniformMemory.get.seen.drop(before).exists(_.address==0x5000) &&
          (uniformMemory.get.pending.nonEmpty || uniformMemory.get.reply.nonEmpty))
      }else until("evaluation ownership")(d.io.evaluationOwned.peek().litToBoolean)
      cancelling = true; d.io.cancel.poke(true.B); tick(); d.io.cancel.poke(false.B)
      if(uniforms){
        for(_<-0 until 8){tick();d.io.completion.valid.expect(false.B)}
        uniformMemory.get.holdResponses=false;uniformMemory.get.beforeRequest=_=>()
      }
      done(4, 1); cancelling = false
      assert(uniformMemory.forall(m=>m.pending.isEmpty && m.reply.isEmpty))
      start(); done(0, 2)
    }
  }
}
