package opengpu.core

import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.core.memory._
import opengpu.core.trap.CoreTrapEvent
import opengpu.dispatch.{KernelLaunch,KernelCompletion}
import opengpu.graphics._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class HelperInvocationCuHarness(local: Boolean) extends Module {
  val config=GpuConfig(lanes=4,warps=2)
  val payload=ShaderPayloadConfig(capacityBytes=1024,maxPayloadBytes=96,owners=1,ports=2)
  val io=IO(new Bundle {
    val kernel=Flipped(Decoupled(new KernelLaunch(config)));val completion=Decoupled(new KernelCompletion)
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config));val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse()))
    val atomicRequest=Decoupled(new SharedAtomicRequest(config));val atomicResponse=Flipped(Decoupled(new SharedAtomicResponse(config)))
    val trap=Decoupled(new CoreTrapEvent(config))
    val management=if(local)Some(Flipped(new ShaderPayloadProducerPort(payload)))else None
    val payloadTask=if(local)Some(Input(Valid(new ShaderPayloadBinding(config,payload))))else None
    val drained=Output(Bool())
  })
  private val cu=Module(new GpuComputeUnit(config,enableFpuBackend=true,finishOnTrap=true,
    instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1,shaderPayload=if(local)Some(payload)else None))
  cu.io.kernel<>io.kernel;io.completion<>cu.io.completion;io.memoryRequest<>cu.io.memoryRequest;cu.io.memoryResponse<>io.memoryResponse
  io.atomicRequest<>cu.io.globalAtomicRequest;cu.io.globalAtomicResponse<>io.atomicResponse;io.trap<>cu.io.trap
  io.drained:=true.B
  if(local){
    val store=Module(new BankedShaderPayloadStore(payload))
    val bus=io.management.get;store.io.control(0)<>bus.control;bus.controlResponse<>store.io.controlResponse(0)
    store.io.request(0)<>bus.request;bus.response<>store.io.response(0)
    val port=cu.io.payload.get;port.task:=io.payloadTask.get;port.cancel:=false.B
    store.io.request(1)<>port.request;port.response<>store.io.response(1);io.drained:=port.drained
  }
  cu.io.vectorSatp:=0.U;cu.io.instructionSatp:=0.U;cu.io.invalidateInstructionCache:=false.B
  cu.io.instructionTlbFlush.valid:=false.B;cu.io.instructionTlbFlush.bits:=0.U.asTypeOf(cu.io.instructionTlbFlush.bits)
  cu.io.vectorTlbFlush.valid:=false.B;cu.io.vectorTlbFlush.bits:=0.U.asTypeOf(cu.io.vectorTlbFlush.bits)
  cu.io.l1Invalidate.valid:=false.B;cu.io.l1Invalidate.bits:=0.U.asTypeOf(cu.io.l1Invalidate.bits);cu.io.l1InvalidateDone.ready:=true.B
  cu.io.fpu.ready:=false.B;cu.io.vector.ready:=false.B;cu.io.memory.ready:=false.B
  cu.io.simtBranch.valid:=false.B;cu.io.simtBranch.bits:=0.U.asTypeOf(cu.io.simtBranch.bits)
  cu.io.texSample.ready:=false.B;cu.io.texWriteback.valid:=false.B;cu.io.texWriteback.bits:=0.U.asTypeOf(cu.io.texWriteback.bits)
  cu.io.vectorTexSample.ready:=false.B;cu.io.vectorTexWriteback.valid:=false.B;cu.io.vectorTexWriteback.bits:=0.U.asTypeOf(cu.io.vectorTexWriteback.bits)
}

class HelperInvocationCuSpec extends AnyFlatSpec {
  private def zero(data: Data): Unit=data match {
    case r: Record=>r.elements.values.foreach(zero)
    case v: Vec[_]=>v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case _=>()
  }
  private def addi(rd: Int,rs: Int,n: Int): BigInt=(BigInt(n&4095)<<20)|(BigInt(rs)<<15)|(BigInt(rd)<<7)|0x13
  private def add(rd: Int,a: Int,b: Int): BigInt=(BigInt(b)<<20)|(BigInt(a)<<15)|(BigInt(rd)<<7)|0x33
  private def lui(rd: Int,n: Int): BigInt=(BigInt(n)<<12)|(BigInt(rd)<<7)|0x37
  private def vm(base: Int,v: Int,write: Boolean): BigInt=(BigInt(1)<<25)|(BigInt(base)<<15)|(BigInt(6)<<12)|(BigInt(v)<<7)|(if(write)0x27 else 7)
  private def quad(op: Int,vd: Int,src: Int): BigInt=(BigInt(op)<<26)|(BigInt(1)<<25)|(BigInt(src)<<20)|(BigInt(vd)<<7)|0x2b
  private val code=Seq(
    (BigInt(2)<<20)|(BigInt(8)<<15)|(BigInt(1)<<12)|(BigInt(10)<<7)|0x13,
    add(7,1,10),BigInt("c1027057",16),vm(7,2,false),quad(12,3,2),quad(13,4,2),
    addi(5,7,32),vm(5,3,true),addi(5,7,64),vm(5,4,true),
    lui(6,9),add(6,6,10),vm(6,3,true),
    lui(5,10),addi(6,0,1),(BigInt(6)<<20)|(BigInt(5)<<15)|(BigInt(2)<<12)|(BigInt(13)<<7)|0x2f,
    lui(5,11),add(5,5,10),(BigInt(6)<<20)|(BigInt(5)<<15)|(BigInt(2)<<12)|0x23,
    (BigInt(7)<<15)|(BigInt(2)<<12)|(BigInt(1)<<7)|7,
    lui(5,12),add(5,5,10),(BigInt(1)<<20)|(BigInt(5)<<15)|(BigInt(2)<<12)|0x27,
    BigInt("30500073",16))

  for(local<-Seq(false,true))it should s"execute helpers and quad exchange while suppressing external side effects on a real CU with local=$local" in {
    simulate(new HelperInvocationCuHarness(local)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B);d.io.trap.ready.poke(true.B)
      val memory=mutable.Map.empty[BigInt,BigInt]
      def put(address: BigInt,value: BigInt): Unit=for(b<-0 until 4)memory(address+b)=(value>>(8*b))&255
      def word(address: BigInt): BigInt=(0 until 4).foldLeft(BigInt(0))((v,b)=>v|(memory.getOrElse(address+b,BigInt(0))<<(b*8)))
      code.zipWithIndex.foreach{case(w,i)=>put(0x1000+4*i,w)}
      case class Reply(id: BigInt,data: BigInt,due: Int)
      case class Atomic(warp: BigInt,old: BigInt,due: Int)
      val pending=mutable.Queue.empty[Reply];var atomic: Option[Atomic]=None
      var cycle=0;var requests=0;var atomicValue=BigInt(0);var atomics=0;var privateRequests=0
      var base=BigInt(0);var handle=BigInt(0);var generation=BigInt(0)
      def tick(): Unit={
        d.io.trap.valid.expect(false.B)
        d.io.memoryRequest.ready.poke((cycle%7<5).B);d.io.atomicRequest.ready.poke((cycle%5<3 && atomic.isEmpty).B)
        val r=pending.headOption.filter(_.due<=cycle)
        d.io.memoryResponse.valid.poke(r.nonEmpty.B);r.foreach(v=>{d.io.memoryResponse.bits.transactionId.poke(v.id.U);d.io.memoryResponse.bits.readData.poke(v.data.U)})
        if(r.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean)pending.dequeue()
        d.io.atomicResponse.valid.poke(atomic.exists(_.due<=cycle).B)
        atomic.foreach(v=>{d.io.atomicResponse.bits.warpId.poke(v.warp.U);d.io.atomicResponse.bits.oldValue.poke(v.old.U)})
        if(d.io.atomicResponse.valid.peek().litToBoolean && d.io.atomicResponse.ready.peek().litToBoolean)atomic=None
        if(d.io.atomicRequest.valid.peek().litToBoolean && d.io.atomicRequest.ready.peek().litToBoolean){
          val q=d.io.atomicRequest.bits;q.address.expect(0xa000.U);q.operation.expect(AtomicMemoryOp.add)
          atomic=Some(Atomic(q.warpId.peek().litValue,atomicValue,cycle+7));atomicValue+=q.operand.peek().litValue;atomics+=1
        }
        if(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean){
          requests+=1;val q=d.io.memoryRequest.bits;val a=q.address.peek().litValue
          if(a>=base && a<base+96){privateRequests+=1;assert(!local,"local scratch leaked to VM memory")}
          if(q.isWrite.peek().litToBoolean){val mask=q.byteMask.peek().litValue;val v=q.writeData.peek().litValue;for(b<-0 until 64 if mask.testBit(b))memory(a+b)=(v>>(8*b))&255}
          val value=(0 until 64).foldLeft(BigInt(0))((v,b)=>v|(memory.getOrElse(a+b,BigInt(0))<<(8*b)))
          pending.enqueue(Reply(q.transactionId.peek().litValue,value,cycle+5))
        }
        d.clock.step();cycle+=1
      }
      def until(condition: => Boolean): Unit={var n=0;while(!condition && n<20000){tick();n+=1};assert(condition,s"helper CU stalled local=$local cycle=$cycle")}
      def control(op: Int): Unit={val bus=d.io.management.get;val q=bus.control.bits
        q.operation.poke(op.U);q.handle.poke(handle.U);q.generation.poke(generation.U);q.context.poke(42.U);q.bytes.poke(96.U);q.allowSpill.poke(false.B)
        bus.control.valid.poke(true.B);until(bus.control.ready.peek().litToBoolean);tick();bus.control.valid.poke(false.B)
        until(bus.controlResponse.valid.peek().litToBoolean);bus.controlResponse.bits.fault.expect(false.B)
        if(op==0){handle=bus.controlResponse.bits.handle.peek().litValue;generation=bus.controlResponse.bits.generation.peek().litValue;bus.controlResponse.bits.resident.expect(true.B)}
        bus.controlResponse.ready.poke(true.B);tick();bus.controlResponse.ready.poke(false.B)
      }
      def localLine(offset: Int,write: Boolean,data: BigInt=0,mask: BigInt=(BigInt(1)<<64)-1): BigInt={
        val bus=d.io.management.get;val q=bus.request.bits;q.handle.poke(handle.U);q.generation.poke(generation.U);q.context.poke(42.U);q.owner.poke(0.U)
        q.offset.poke(offset.U);q.write.poke(write.B);q.data.poke(data.U);q.mask.poke(mask.U)
        bus.request.valid.poke(true.B);until(bus.request.ready.peek().litToBoolean);tick();bus.request.valid.poke(false.B)
        until(bus.response.valid.peek().litToBoolean);bus.response.bits.fault.expect(false.B);val value=bus.response.bits.data.peek().litValue
        bus.response.ready.poke(true.B);tick();bus.response.ready.poke(false.B);value
      }
      for((helpers,index)<-Seq(0xa5,0xff,0x0f,0).zipWithIndex){
        base=BigInt(0x20000+index*0x1000);atomicValue=0;atomics=0;privateRequests=0
        val inputs=Vector(11,29,50,83,101,128,155,191).map(v=>BigInt(v+index))
        val dx=Vector(18,18,33,33,27,27,36,36).map(BigInt(_));val dy=Vector(39,54,39,54,54,63,54,63).map(BigInt(_))
        for(i<-0 until 8){put(base+4*i,inputs(i));put(base+32+4*i,0);put(base+64+4*i,0)}
        for(a<-Seq(0x9000,0xb000,0xc000);i<-0 until 8)put(a+4*i,BigInt("deadbeef",16))
        if(local){
          control(0);val input=inputs.zipWithIndex.foldLeft(BigInt(0)){case(v,(word,i))=>v|(word<<(32*i))}
          localLine(0,true,input,(BigInt(1)<<32)-1)
          val binding=d.io.payloadTask.get;binding.valid.poke(true.B);binding.bits.handle.poke(handle.U);binding.bits.generation.poke(generation.U)
          binding.bits.context.poke(42.U);binding.bits.owner.poke(0.U);binding.bits.base.poke(base.U);binding.bits.bytes.poke(96.U)
        }
        val k=d.io.kernel.bits;k.kernelPc.poke(0x1000.U);k.kernargAddress.poke(base.U);k.invocations.helpers.poke(helpers.U)
        k.invocations.privateBase.poke(base.U);k.invocations.privateBytes.poke(96.U)
        for(i<-0 until 3){k.gridSize(i).poke(1.U);k.localSize(i).poke((if(i==0)8 else 1).U)}
        d.io.kernel.valid.poke(true.B);until(d.io.kernel.ready.peek().litToBoolean);tick();d.io.kernel.valid.poke(false.B)
        // The policy survives host edits and remains specific to this kernel.
        k.invocations.helpers.poke(0.U);k.invocations.privateBytes.poke(0.U)
        until(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.success.expect(true.B)
        val first=if(local)localLine(0,false)else BigInt(0);val second=if(local)localLine(64,false,mask=(BigInt(1)<<32)-1)else BigInt(0)
        for(i<-0 until 8){
          val gotDx=if(local)(first>>(256+32*i))&0xffffffffL else word(base+32+4*i)
          val gotDy=if(local)(second>>(32*i))&0xffffffffL else word(base+64+4*i)
          assert(gotDx==dx(i) && gotDy==dy(i),s"helper computation missing i=$i dx=$gotDx dy=$gotDy")
          assert(word(0x9000+4*i)==(if((helpers&(1<<i))==0)dx(i)else BigInt("deadbeef",16)))
        }
        val realWarps=(0 until 2).count(w=>((helpers>>(4*w))&15)!=15);assert(atomics==realWarps && atomicValue==realWarps)
        for(w<-0 until 2){val real=((helpers>>(4*w))&15)!=15
          assert(word(0xb000+16*w)==(if(real)BigInt(1)else BigInt("deadbeef",16)))
          assert(word(0xc000+16*w)==(if(real)inputs(4*w)else BigInt("deadbeef",16)))
        }
        if(!local)assert(privateRequests>0)
        for(_<-0 until 7){tick();d.io.kernel.ready.expect(false.B);d.io.completion.valid.expect(true.B)}
        d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B);assert(pending.isEmpty && atomic.isEmpty)
        if(local){d.io.payloadTask.get.valid.poke(false.B);control(2)}
      }
      info(s"HELPER_CU local=$local kernels=4 lanes=4 warps=2 helperMasks=mixed,all,wholeWarp,none quadExchange=true helperPrivateOutputs=true vectorExternalMasks=true scalarAndFpStores=true atomicSuppression=true kernelReuse=true noLocalVMLeak=true heldCompletion=true")
    }
  }
}
