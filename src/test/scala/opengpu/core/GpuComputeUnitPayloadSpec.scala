package opengpu.core

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory._
import opengpu.core.trap.CoreTrapEvent
import opengpu.dispatch.{KernelLaunch,KernelCompletion}
import opengpu.graphics._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class PayloadCuHarness(config: GpuConfig,p: ShaderPayloadConfig) extends Module {
  val io=IO(new Bundle {
    val kernel=Flipped(Decoupled(new KernelLaunch(config)))
    val completion=Decoupled(new KernelCompletion)
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse()))
    val task=Input(Valid(new ShaderPayloadBinding(config,p)))
    val cancel=Input(Bool())
    val vectorSatp=Input(UInt(32.W))
    val control=Flipped(Decoupled(new ShaderPayloadControlRequest(p)))
    val controlResponse=Decoupled(new ShaderPayloadControlResponse(p))
    val upload=Flipped(Decoupled(new ShaderPayloadRequest(p)))
    val uploaded=Decoupled(new ShaderPayloadResponse(p))
    val trap=Decoupled(new CoreTrapEvent(config))
    val localDrained=Output(Bool())
    val poolDrained=Output(Bool())
  })
  private val unit=Module(new GpuComputeUnit(config,instructionCacheSets=4,instructionCacheWays=1,
    vectorCacheSets=4,vectorCacheWays=1,finishOnTrap=true,shaderPayload=Some(p)))
  private val store=Module(new BankedShaderPayloadStore(p))
  unit.io.kernel<>io.kernel;io.completion<>unit.io.completion
  io.memoryRequest<>unit.io.memoryRequest;unit.io.memoryResponse<>io.memoryResponse
  io.trap<>unit.io.trap
  unit.io.payload.get.task:=io.task;unit.io.payload.get.cancel:=io.cancel
  store.io.request(1)<>unit.io.payload.get.request
  unit.io.payload.get.response<>store.io.response(1)
  store.io.request(0)<>io.upload;io.uploaded<>store.io.response(0)
  store.io.control(0)<>io.control;io.controlResponse<>store.io.controlResponse(0)
  io.localDrained:=unit.io.payload.get.drained;io.poolDrained:=store.io.drained
  unit.io.vectorSatp:=io.vectorSatp;unit.io.instructionSatp:=0.U
  unit.io.invalidateInstructionCache:=false.B
  unit.io.instructionTlbFlush.valid:=false.B;unit.io.instructionTlbFlush.bits:=0.U.asTypeOf(unit.io.instructionTlbFlush.bits)
  unit.io.vectorTlbFlush.valid:=false.B;unit.io.vectorTlbFlush.bits:=0.U.asTypeOf(unit.io.vectorTlbFlush.bits)
  unit.io.l1Invalidate.valid:=false.B;unit.io.l1Invalidate.bits:=0.U.asTypeOf(unit.io.l1Invalidate.bits)
  unit.io.l1InvalidateDone.ready:=true.B
  unit.io.globalAtomicRequest.ready:=true.B
  unit.io.globalAtomicResponse.valid:=false.B;unit.io.globalAtomicResponse.bits:=0.U.asTypeOf(unit.io.globalAtomicResponse.bits)
  unit.io.fpu.ready:=false.B;unit.io.vector.ready:=false.B;unit.io.memory.ready:=false.B
  unit.io.simtBranch.valid:=false.B;unit.io.simtBranch.bits:=0.U.asTypeOf(unit.io.simtBranch.bits)
  unit.io.texSample.ready:=false.B;unit.io.texWriteback.valid:=false.B
  unit.io.texWriteback.bits:=0.U.asTypeOf(unit.io.texWriteback.bits)
  unit.io.vectorTexSample.ready:=false.B;unit.io.vectorTexWriteback.valid:=false.B
  unit.io.vectorTexWriteback.bits:=0.U.asTypeOf(unit.io.vectorTexWriteback.bits)
}

class GpuComputeUnitPayloadSpec extends AnyFlatSpec {
  private val config=GpuConfig(lanes=4,warps=2)
  private val p=ShaderPayloadConfig(capacityBytes=1024,maxPayloadBytes=288,owners=1,ports=2)
  for(mode <- Seq("local-sv32-bypass","uniform-bare","mixed-vector-bare"))
  it should s"execute $mode on an actual CU and reuse local addresses without invalidation" in {
    simulate(new PayloadCuHarness(config,p)) { d=>
      d.io.kernel.valid.poke(false.B);d.io.kernel.bits.poke(0.U.asTypeOf(d.io.kernel.bits))
      d.io.completion.ready.poke(false.B);d.io.memoryRequest.ready.poke(false.B)
      d.io.memoryResponse.valid.poke(false.B);d.io.memoryResponse.bits.poke(0.U.asTypeOf(d.io.memoryResponse.bits))
      d.io.task.valid.poke(false.B);d.io.task.bits.poke(0.U.asTypeOf(d.io.task.bits))
      d.io.cancel.poke(false.B);d.io.vectorSatp.poke((if(mode=="local-sv32-bypass")BigInt("80000000",16)else BigInt(0)).U)
      d.io.control.valid.poke(false.B);d.io.control.bits.poke(0.U.asTypeOf(d.io.control.bits))
      d.io.controlResponse.ready.poke(false.B)
      d.io.upload.valid.poke(false.B);d.io.upload.bits.poke(0.U.asTypeOf(d.io.upload.bits))
      d.io.uploaded.ready.poke(false.B);d.io.trap.ready.poke(true.B)
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val normalA=BigInt("4ac00123",16);val normalB=BigInt("7bad4567",16)
      val code=if(mode=="mixed-vector-bare")Seq(
        (BigInt(280)<<20)|(BigInt(1)<<15)|(BigInt(10)<<7)|0x13,
        (BigInt(3)<<30)|(BigInt(0x10)<<20)|(BigInt(4)<<15)|(BigInt(7)<<12)|0x57,
        (BigInt(1)<<25)|(BigInt(10)<<15)|(BigInt(6)<<12)|(BigInt(1)<<7)|0x07,
        (BigInt(1)<<25)|(BigInt(1)<<15)|(BigInt(6)<<12)|(BigInt(1)<<7)|0x27,
        BigInt("30500073",16))
      else Seq((BigInt(if(mode=="uniform-bare")288 else 0)<<20)|BigInt("0000a483",16),
        BigInt("0090a223",16),BigInt("30500073",16))
      val program=code.zipWithIndex.foldLeft(BigInt(0)){case(v,(word,i))=>v|(word<<(32*i))}
      val externalLine=(normalA<<256)|(normalB<<288)|(BigInt("dead1111",16)<<192)|(BigInt("dead2222",16)<<224)
      case class Reply(id: BigInt,data: BigInt,due: Int)
      val pending=mutable.Queue.empty[Reply]
      var cycle=0;var instructionReads=0;var dataReads=0
      def tick(): Unit={
        d.io.trap.valid.expect(false.B)
        d.io.memoryRequest.ready.poke((cycle%7<4).B)
        val returning=pending.headOption.filter(_.due<=cycle)
        d.io.memoryResponse.valid.poke(returning.nonEmpty.B)
        returning.foreach(r=>{d.io.memoryResponse.bits.transactionId.poke(r.id.U);d.io.memoryResponse.bits.readData.poke(r.data.U)})
        if(d.io.memoryResponse.valid.peek().litToBoolean && d.io.memoryResponse.ready.peek().litToBoolean)pending.dequeue()
        if(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean) {
          val req=d.io.memoryRequest.bits;req.isWrite.expect(false.B)
          val address=req.address.peek().litValue
          assert(address==0x1000 || (mode!="local-sv32-bypass" && address==0x8100),f"unexpected external address 0x$address%x")
          if(address==0x1000)instructionReads+=1 else dataReads+=1
          pending.enqueue(Reply(req.transactionId.peek().litValue,if(address==0x1000)program else externalLine,cycle+5))
        }
        d.clock.step();cycle+=1
      }
      def until(condition: => Boolean): Unit={var n=0;while(!condition && n<10000){tick();n+=1};assert(condition,"CU payload work failed to progress")}
      var generation=BigInt(0);var handle=BigInt(0)
      def control(op: Int): Unit={
        val b=d.io.control.bits;b.operation.poke(op.U);b.handle.poke(handle.U)
        b.generation.poke(generation.U);b.context.poke(42.U);b.bytes.poke(288.U);b.allowSpill.poke(false.B)
        d.io.control.valid.poke(true.B);until(d.io.control.ready.peek().litToBoolean);tick();d.io.control.valid.poke(false.B)
        until(d.io.controlResponse.valid.peek().litToBoolean)
        d.io.controlResponse.bits.fault.expect(false.B);d.io.controlResponse.bits.spill.expect(false.B)
        if(op==ShaderPayloadControl.allocate) {
          val next=d.io.controlResponse.bits.generation.peek().litValue
          assert(next!=generation);generation=next;handle=d.io.controlResponse.bits.handle.peek().litValue
        }
        d.io.controlResponse.ready.poke(true.B);tick();d.io.controlResponse.ready.poke(false.B)
      }
      def access(offset: Int,mask: BigInt,write: Boolean,data: BigInt=0,expected: BigInt=0): Unit={
        val b=d.io.upload.bits;b.handle.poke(handle.U);b.generation.poke(generation.U);b.context.poke(42.U)
        b.owner.poke(0.U);b.offset.poke(offset.U);b.mask.poke(mask.U);b.write.poke(write.B);b.data.poke(data.U)
        d.io.upload.valid.poke(true.B);until(d.io.upload.ready.peek().litToBoolean);tick();d.io.upload.valid.poke(false.B)
        until(d.io.uploaded.valid.peek().litToBoolean)
        d.io.uploaded.bits.fault.expect(false.B);d.io.uploaded.bits.data.expect(expected.U)
        d.io.uploaded.ready.poke(true.B);tick();d.io.uploaded.ready.poke(false.B)
      }
      for(round <- 0 until 2) {
        control(ShaderPayloadControl.allocate)
        val a=BigInt(0x12340000L+round);val b=BigInt(0x56780000L+round)
        access(0,15,true,data=a)
        if(mode=="mixed-vector-bare")access(256,BigInt(255)<<24,true,data=(a<<192)|(b<<224))
        val task=d.io.task.bits;task.handle.poke(handle.U);task.generation.poke(generation.U)
        task.context.poke(42.U);task.owner.poke(0.U);task.base.poke(0x8000.U);task.bytes.poke(288.U)
        d.io.task.valid.poke(true.B)
        val kernel=d.io.kernel.bits;kernel.kernelPc.poke(0x1000.U);kernel.kernargAddress.poke(0x8000.U)
        for(i <- 0 until 3){kernel.gridSize(i).poke(1.U);kernel.localSize(i).poke((if(i==0)4 else 1).U)}
        d.io.kernel.valid.poke(true.B);until(d.io.kernel.ready.peek().litToBoolean);tick();d.io.kernel.valid.poke(false.B)
        until(d.io.completion.valid.peek().litToBoolean)
        d.io.completion.bits.success.expect(true.B);d.io.localDrained.expect(true.B)
        val held=d.io.completion.bits.peek().litValue
        for(_ <- 0 until 6){tick();assert(d.io.completion.bits.peek().litValue==held)}
        d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B)
        if(mode=="mixed-vector-bare")access(0,65535,false,expected=a|(b<<32)|(normalA<<64)|(normalB<<96))
        else access(0,240,false,expected=(if(mode=="uniform-bare")normalA else a)<<32)
        d.io.task.valid.poke(false.B)
        control(ShaderPayloadControl.release);d.io.poolDrained.expect(true.B)
      }
      assert(instructionReads>=1 && (if(mode=="local-sv32-bypass")dataReads==0 else dataReads==1))
      assert(pending.isEmpty)
      info(s"CU_PAYLOAD mode=$mode submissions=2 instructionReads=$instructionReads externalDataReads=$dataReads externalDataWrites=0 cycles=$cycle")
    }
  }
}
