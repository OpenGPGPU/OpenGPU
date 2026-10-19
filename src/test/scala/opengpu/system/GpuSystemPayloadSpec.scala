package opengpu.system

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.core.trap.TrapCause
import opengpu.graphics._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class GpuSystemPayloadSpec extends AnyFlatSpec {
  private case class Lease(handle: BigInt,generation: BigInt,context: BigInt,owner: Int,id: Int)
  private def zero(data: Data): Unit=data match {
    case r: Record=>r.elements.values.foreach(zero)
    case v: Vec[_]=>v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case _=>()
  }
  private class Driver(val d: GpuSystem,val p: ShaderPayloadConfig,val count: Int) {
    zero(d.io)
    d.io.graphicsDrained.poke(true.B)
    d.io.commandCompletion.ready.poke(true.B)
    d.io.graphicsKernels.get.trap.ready.poke(true.B)
    d.io.graphicsKernels.get.invalidateDone.ready.poke(true.B)
    d.io.graphicsShaderL1Invalidate.ready.poke(true.B)
    d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    val memory=mutable.Map.empty[BigInt,BigInt]
    val expected=mutable.Map.empty[Int,Boolean]
    val expectedCause=mutable.Map.empty[Int,Int]
    val completed=mutable.ArrayBuffer.empty[Int]
    val traps=mutable.ArrayBuffer.empty[(Int,Int,Int)]
    val computed=mutable.Set.empty[Int]
    var cycle=0;var peakOwned=0;var computeWhileGraphicsHeld=false
    var lowerReads=0;var lowerWrites=0
    private case class Reply(id: BigInt,data: BigInt,due: Int)
    private val pending=mutable.ArrayBuffer.empty[Reply]
    private var offered: Option[Reply]=None
    private var heldCompletion: Option[BigInt]=None
    def putWord(address: BigInt,value: BigInt): Unit=
      for(b <- 0 until 4)memory(address+b)=(value>>(8*b))&255
    def word(address: BigInt): BigInt=(0 until 4).foldLeft(BigInt(0))((v,b)=>v|(memory.getOrElse(address+b,BigInt(0))<<(8*b)))
    def program(address: Int,code: Seq[BigInt]): Unit=code.zipWithIndex.foreach{case(v,i)=>putWord(address+4*i,v)}
    def tick(): Unit={
      val graphics=d.io.graphicsKernels.get
      peakOwned=math.max(peakOwned,graphics.owned.peek().litValue.bitCount)
      for(cu <- 0 until count)d.io.trap(cu).valid.expect(false.B)
      if(graphics.trap.valid.peek().litToBoolean) {
        val id=graphics.trap.bits.taskId.peek().litValue.toInt
        assert(expected.get(id).contains(false),s"unexpected graphics trap task=$id")
        graphics.trap.bits.event.cause.expect(expectedCause(id).U)
        traps+=((id,graphics.trap.bits.cu.peek().litValue.toInt,graphics.trap.bits.event.cause.peek().litValue.toInt))
      }
      val completion=graphics.completion
      heldCompletion.foreach(v=>{completion.valid.expect(true.B);assert(completion.bits.peek().litValue==v,"held graphics completion changed")})
      if(completion.valid.peek().litToBoolean) {
        if(completion.ready.peek().litToBoolean) {
          val id=completion.bits.commandId.peek().litValue.toInt
          val success=expected.remove(id).getOrElse(sys.error(s"unexpected graphics completion $id"))
          completion.bits.success.expect(success.B);completed+=id;heldCompletion=None
        }else heldCompletion=Some(completion.bits.peek().litValue)
      }
      if(d.io.commandCompletion.valid.peek().litToBoolean && d.io.commandCompletion.ready.peek().litToBoolean) {
        d.io.commandCompletion.bits.success.expect(true.B)
        computed+=d.io.commandCompletion.bits.commandId.peek().litValue.toInt
        if(completion.valid.peek().litToBoolean && !completion.ready.peek().litToBoolean)computeWhileGraphicsHeld=true
      }
      d.io.memoryRequest.ready.poke((cycle%7<5).B)
      if(offered.isEmpty) {
        val due=pending.indices.filter(i=>pending(i).due<=cycle)
        if(due.nonEmpty)offered=Some(pending.remove(if(cycle%2==0)due.last else due.head))
      }
      d.io.memoryResponse.valid.poke(offered.nonEmpty.B)
      offered.foreach(r=>{d.io.memoryResponse.bits.transactionId.poke(r.id.U);d.io.memoryResponse.bits.readData.poke(r.data.U)})
      if(offered.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean)offered=None
      if(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean) {
        val req=d.io.memoryRequest.bits
        val address=req.address.peek().litValue;val write=req.isWrite.peek().litToBoolean
        if(write) {
          lowerWrites+=1
          val mask=req.byteMask.peek().litValue;val data=req.writeData.peek().litValue
          for(b <- 0 until 64 if mask.testBit(b))memory(address+b)=(data>>(8*b))&255
        }else lowerReads+=1
        val data=if(write)BigInt(0)else(0 until 64).foldLeft(BigInt(0))((v,b)=>v|(memory.getOrElse(address+b,BigInt(0))<<(8*b)))
        pending+=Reply(req.transactionId.peek().litValue,data,cycle+5+(req.transactionId.peek().litValue.toInt%3))
      }
      d.clock.step();cycle+=1
    }
    def until(condition: => Boolean): Unit={var n=0;while(!condition && n<15000){tick();n+=1};assert(condition,s"payload pool did not progress at cycle=$cycle")}
    def control(owner: Int,operation: Int,lease: Lease): Lease={
      val port=d.io.payloadManagement.get.control(owner);val b=port.bits
      b.operation.poke(operation.U);b.handle.poke(lease.handle.U);b.generation.poke(lease.generation.U)
      b.context.poke(lease.context.U);b.bytes.poke(288.U);b.allowSpill.poke(false.B)
      port.valid.poke(true.B);until(port.ready.peek().litToBoolean);tick();port.valid.poke(false.B)
      val response=d.io.payloadManagement.get.controlResponse(owner)
      until(response.valid.peek().litToBoolean)
      response.bits.fault.expect(false.B);response.bits.spill.expect(false.B)
      val result=Lease(response.bits.handle.peek().litValue,response.bits.generation.peek().litValue,
        response.bits.context.peek().litValue,owner,lease.id)
      response.ready.poke(true.B);tick();response.ready.poke(false.B);result
    }
    def allocate(owner: Int,id: Int): Lease=control(owner,ShaderPayloadControl.allocate,Lease(0,0,0x120+id,owner,id))
    def offer(ownerPort: Int,lease: Lease,write: Boolean,value: BigInt=0,mask: BigInt=15): Unit={
      val port=d.io.payloadManagement.get.request(ownerPort);val b=port.bits
      b.handle.poke(lease.handle.U);b.generation.poke(lease.generation.U);b.context.poke(lease.context.U)
      b.owner.poke((if(ownerPort==lease.owner)1-lease.owner else lease.owner).U)
      b.offset.poke(0.U);b.write.poke(write.B);b.data.poke(value.U);b.mask.poke(mask.U)
      port.valid.poke(true.B);until(port.ready.peek().litToBoolean);tick();port.valid.poke(false.B)
    }
    def access(lease: Lease,write: Boolean,value: BigInt=0,mask: BigInt=15,expected: BigInt=0): Unit={
      offer(lease.owner,lease,write,value,mask)
      val response=d.io.payloadManagement.get.response(lease.owner)
      until(response.valid.peek().litToBoolean)
      response.bits.fault.expect(false.B);response.bits.data.expect(expected.U)
      response.ready.poke(true.B);tick();response.ready.poke(false.B)
    }
    def launch(id: Int,lease: Option[Lease],pc: Int=0x1000,success: Boolean=true,badGeneration: Boolean=false,
        cause: Int=TrapCause.loadAccessFault,threads: Int=4): Unit={
      val graphics=d.io.graphicsKernels.get
      val b=d.io.graphicsPayload.get;b.valid.poke(lease.nonEmpty.B)
      lease.foreach { l=>
        b.bits.handle.poke(l.handle.U);b.bits.generation.poke((l.generation+(if(badGeneration)1 else 0)).U)
        b.bits.context.poke(l.context.U);b.bits.owner.poke(l.owner.U);b.bits.base.poke(0x8000.U);b.bits.bytes.poke(288.U)
      }
      graphics.launch.bits.commandId.poke(id.U);graphics.launch.bits.launch.kernelPc.poke(pc.U)
      graphics.launch.bits.launch.kernargAddress.poke(0x8000.U)
      for(i <- 0 until 3){graphics.launch.bits.launch.gridSize(i).poke(1.U);graphics.launch.bits.launch.localSize(i).poke((if(i==0)threads else 1).U)}
      expected(id)=success
      if(!success)expectedCause(id)=cause
      graphics.launch.valid.poke(true.B);until(graphics.launch.ready.peek().litToBoolean);tick()
      graphics.launch.valid.poke(false.B);b.valid.poke(false.B)
    }
  }

  for((count,lanes,warps) <- Seq((1,4,2),(2,4,2),(4,4,2),(4,16,8)))
  it should s"share payload leases across actual CUs, isolate compute and fallback, and recover faults CUs=$count lanes=$lanes warps=$warps" in {
    val config=GpuConfig(lanes=lanes,warps=warps,l2Sets=8,l2Ways=2)
    val p=ShaderPayloadConfig(capacityBytes=4096,maxPayloadBytes=288,owners=2,ports=2+count)
    simulate(new GpuSystem(config,numComputeUnits=count,enableGraphicsKernels=true,
      instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1,shaderPayload=Some(p))) { dut=>
      val t=new Driver(dut,p,count)
      val copy=Seq(BigInt("0000a483",16),BigInt("0090a223",16),BigInt("30500073",16))
      t.program(0x1000,copy);t.program(0x2000,copy)
      t.program(0x3000,Seq(BigInt("45600493",16),BigInt("0090a223",16),BigInt("30500073",16)))
      val add=(BigInt(10)<<20)|(BigInt(1)<<15)|(BigInt(2)<<12)|(BigInt(9)<<7)|0x2f
      val addOutside=(BigInt(10)<<20)|(BigInt(11)<<15)|(BigInt(2)<<12)|(BigInt(9)<<7)|0x2f
      t.program(0x4000,Seq(BigInt("00100513",16),add,BigInt("30500073",16)))
      t.program(0x5000,Seq(BigInt("00100513",16),BigInt("20008593",16),addOutside,
        BigInt("0090a423",16),BigInt("30500073",16)))
      val global=BigInt("cafeabcd",16);t.putWord(0x8000,global)
      val atomicValue=BigInt("33445566",16);t.putWord(0x8200,atomicValue)
      val leases=Vector.tabulate(count)(i=>t.allocate(i%2,7+i))
      val all=leases++(if(count==1)Vector(t.allocate(1,33))else Vector.empty)
      def value(l: Lease): BigInt=BigInt(0x12340000L+l.id)
      all.foreach(l=>t.access(l,true,value=value(l)))
      dut.io.vmContextIdle.expect(false.B)
      dut.io.payloadManagement.get.reservedBytes.expect((all.size*p.slotBytes).U)
      val foreign=all.find(_.owner==1).get
      t.offer(0,foreign,true,value=BigInt("badbadba",16)<<32,mask=240)
      val rejected=dut.io.payloadManagement.get.response(0)
      t.until(rejected.valid.peek().litToBoolean);rejected.bits.fault.expect(true.B)
      val held=rejected.bits.peek().litValue
      t.access(foreign,false,mask=240,expected=0)
      assert(rejected.bits.peek().litValue==held,"another owner changed a held staging fault")
      rejected.ready.poke(true.B);t.tick();rejected.ready.poke(false.B)
      leases.foreach(l=>t.launch(l.id,Some(l)))
      val command=dut.io.command
      command.bits.commandId.poke(7.U);command.bits.launch.kernelPc.poke(0x2000.U)
      command.bits.launch.kernargAddress.poke(0x8000.U)
      for(i <- 0 until 3){command.bits.launch.gridSize(i).poke(1.U);command.bits.launch.localSize(i).poke((if(i==0)4 else 1).U)}
      command.valid.poke(true.B);t.until(command.ready.peek().litToBoolean);t.tick();command.valid.poke(false.B)
      t.until(t.computed.contains(7))
      assert(t.computeWhileGraphicsHeld,"held graphics completions prevented compute progress")
      assert(t.peakOwned==count,s"only${t.peakOwned}/$count CUs received graphics tasks")
      t.until(t.word(0x8004)==global)
      dut.io.graphicsKernels.get.completion.ready.poke(true.B)
      t.until(leases.forall(l=>t.completed.contains(l.id)))
      t.launch(90,None,pc=0x3000)
      t.until(t.completed.contains(90));t.until(t.word(0x8004)==0x456)
      t.launch(101,Some(leases.head),success=false,badGeneration=true,threads=lanes*warps)
      t.until(t.completed.contains(101))
      t.launch(103,Some(leases.head),pc=0x4000,success=false,cause=TrapCause.storeAccessFault,threads=lanes*warps)
      t.until(t.completed.contains(103));assert(t.traps.map(_._1).toSet==Set(101,103))
      assert(t.word(0x8000)==global,"a local payload atomic modified the global alias")
      t.launch(104,Some(leases.head),pc=0x5000);t.until(t.completed.contains(104))
      t.until(t.word(0x8200)==atomicValue+1)
      t.access(leases.head,false,mask=3840,expected=atomicValue<<64)
      t.launch(102,Some(leases.head),threads=lanes*warps);t.until(t.completed.contains(102))
      all.foreach(l=>t.access(l,false,mask=240,expected=(if(leases.contains(l))value(l)else BigInt(0))<<32))
      dut.io.vmContextIdle.expect(false.B)
      all.foreach(l=>t.control(l.owner,ShaderPayloadControl.release,l))
      t.until(dut.io.vmContextIdle.peek().litToBoolean)
      dut.io.payloadManagement.get.drained.expect(true.B)
      info(s"PAYLOAD_POOL CUs=$count lanes=$lanes warps=$warps graphics=${count+5} compute=1 peakOwned=${t.peakOwned} faultThreads=${lanes*warps} faultTasks=2 trapped=${t.traps.size} reuse=true foreignStagingRejected=true localAtomicRejected=true normalAtomic=true cycles=${t.cycle}")
    }
  }

  it should "retain a completed task lease until its producer consumes completion, then recycle and zero it" in {
    val config=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2)
    val p=ShaderPayloadConfig(capacityBytes=1024,maxPayloadBytes=288,owners=2,ports=3)
    simulate(new GpuSystem(config,numComputeUnits=1,enableGraphicsKernels=true,
      instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1,shaderPayload=Some(p))) { dut=>
      val t=new Driver(dut,p,1)
      t.program(0x1000,Seq(BigInt("0000a483",16),BigInt("0090a223",16),BigInt("30500073",16)))
      val lease=t.allocate(0,8);t.access(lease,true,value=0x11223344)
      t.launch(8,Some(lease))
      val completion=dut.io.graphicsKernels.get.completion
      t.until(completion.valid.peek().litToBoolean)
      dut.io.graphicsKernels.get.owned.expect(0.U)
      val release=dut.io.payloadManagement.get.control(0)
      release.bits.operation.poke(ShaderPayloadControl.release.U)
      release.bits.handle.poke(lease.handle.U);release.bits.generation.poke(lease.generation.U)
      release.bits.context.poke(lease.context.U);release.valid.poke(true.B)
      for(_ <- 0 until 12){release.ready.expect(false.B);completion.valid.expect(true.B);t.tick()}
      completion.ready.poke(true.B);t.tick();assert(t.completed.contains(8))
      t.until(release.ready.peek().litToBoolean);t.tick();release.valid.poke(false.B)
      val response=dut.io.payloadManagement.get.controlResponse(0)
      t.until(response.valid.peek().litToBoolean);response.bits.fault.expect(false.B)
      response.ready.poke(true.B);t.tick();response.ready.poke(false.B)
      t.until(dut.io.vmContextIdle.peek().litToBoolean)
      val next=t.allocate(1,9)
      assert(next.handle==lease.handle && next.generation!=lease.generation,"lease did not recycle with a fresh generation")
      t.access(next,false,mask=255,expected=0)
      t.access(next,true,value=0x55667788)
      t.launch(9,Some(next));t.until(t.completed.contains(9))
      t.access(next,false,mask=240,expected=BigInt(0x55667788L)<<32)
      t.control(1,ShaderPayloadControl.release,next);t.until(dut.io.vmContextIdle.peek().litToBoolean)
      info("PAYLOAD_POOL_PINS heldCompletionBlocksRelease=true completionConsumptionUnpins=true generationRecycled=true zeroed=true healthyReuse=true")
    }
  }

  it should "drain accepted leases through reset while holding new allocation and staging offers" in {
    val config=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2)
    val p=ShaderPayloadConfig(capacityBytes=1024,maxPayloadBytes=288,owners=2,ports=3)
    simulate(new GpuSystem(config,numComputeUnits=1,enableGraphicsKernels=true,enableUnifiedCommands=true,
      instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1,shaderPayload=Some(p))) { dut=>
      val t=new Driver(dut,p,1);val lease=t.allocate(0,4)
      dut.io.commandResetActive.poke(true.B)
      val pending=dut.io.payloadManagement.get.control(1)
      pending.bits.operation.poke(ShaderPayloadControl.allocate.U);pending.bits.bytes.poke(288.U)
      pending.valid.poke(true.B)
      val request=dut.io.payloadManagement.get.request(1)
      request.bits.handle.poke(lease.handle.U);request.bits.generation.poke(lease.generation.U)
      request.bits.context.poke(lease.context.U);request.bits.mask.poke(15.U);request.valid.poke(true.B)
      for(_ <- 0 until 10) {
        pending.ready.expect(false.B);request.ready.expect(false.B)
        dut.io.commandResetDone.expect(false.B);t.tick()
      }
      t.control(0,ShaderPayloadControl.release,lease)
      t.until(dut.io.commandResetDone.peek().litToBoolean)
      pending.ready.expect(false.B);request.ready.expect(false.B)
      dut.io.payloadManagement.get.drained.expect(true.B)
      pending.valid.poke(false.B);request.valid.poke(false.B);dut.io.commandResetActive.poke(false.B)
      t.until(dut.io.vmContextIdle.peek().litToBoolean)
      val next=t.allocate(1,5);t.access(next,false,expected=0)
      t.control(1,ShaderPayloadControl.release,next);t.until(dut.io.vmContextIdle.peek().litToBoolean)
      info("PAYLOAD_POOL_RESET acceptedLeaseDrained=true newOffersBlocked=true ackWhileOffersHeld=true healthyReuse=true")
    }
  }
}
