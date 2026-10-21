package opengpu.system

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.command.GpuCommandOpcode
import opengpu.config.GpuConfig
import opengpu.dispatch.DmaEventSource
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class GpuSystemIndirectTranslationResetSpec extends AnyFlatSpec {
  private def zero(data: Data): Unit = data match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input => u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input => s.poke(0.S)
    case _ => ()
  }
  private def addi(rd: Int, rs: Int, imm: Int): BigInt =
    BigInt(((imm&0xfff)<<20)|(rs<<15)|(rd<<7)|0x13)
  private def sw(rs: Int, base: Int, imm: Int): BigInt =
    BigInt(((imm&0xfe0)<<20)|(rs<<20)|(base<<15)|(2<<12)|((imm&31)<<7)|0x23)
  private val cease=BigInt("30500073",16)

  private class Memory(val dut: GpuSystem) {
    val bytes=mutable.LongMap.empty[Int]
    case class Reply(id: BigInt,data: BigInt,fault: Boolean,due: Int,
                     write: Option[(Long,BigInt,BigInt)])
    val pending=mutable.ArrayBuffer.empty[Reply]
    var held=Option.empty[Reply]
    var cycle=0
    var requestEnabled=true
    var replyEnabled=true
    var faultReads=Set.empty[Long]
    val readAddresses=mutable.ArrayBuffer.empty[Long]
    val traps=mutable.ArrayBuffer.empty[BigInt]
    var acknowledgedWrites=0
    def word(address: Long): BigInt=(0 until 4).map(i=>BigInt(bytes.getOrElse(address+i,0))<<(i*8)).sum
    def put(address: Long,value: BigInt): Unit=for(i<-0 until 4)bytes(address+i)=((value>>(i*8))&255).toInt
    def program(address: Long,words: Seq[BigInt]): Unit=words.zipWithIndex.foreach{case(v,i)=>put(address+4*i,v)}
    def line(address: Long): BigInt=(0 until 64).map(i=>BigInt(bytes.getOrElse(address+i,0))<<(i*8)).sum
    def step(): Unit = {
      dut.io.memoryRequest.ready.poke((requestEnabled && cycle%7<5).B)
      if(replyEnabled && held.isEmpty && cycle%11>=6) {
        val i=pending.lastIndexWhere(_.due<=cycle)
        if(i>=0)held=Some(pending.remove(i))
      }
      dut.io.memoryResponse.valid.poke((replyEnabled && held.nonEmpty).B)
      held.foreach { reply =>
        dut.io.memoryResponse.bits.transactionId.poke(reply.id.U)
        dut.io.memoryResponse.bits.readData.poke(reply.data.U)
        dut.io.memoryResponse.bits.fault.poke(reply.fault.B)
      }
      val acceptedReply=replyEnabled && held.nonEmpty && dut.io.memoryResponse.ready.peek().litToBoolean
      val request=if(dut.io.memoryRequest.valid.peek().litToBoolean && dut.io.memoryRequest.ready.peek().litToBoolean) {
        val req=dut.io.memoryRequest.bits;val address=req.address.peek().litValue.toLong
        val writing=req.isWrite.peek().litToBoolean
        val id=req.transactionId.peek().litValue
        val write=if(writing)Some((address,req.writeData.peek().litValue,req.byteMask.peek().litValue))else None
        if(!writing)readAddresses+=address
        // Commit stores only when their actual reply is accepted. A held
        // request is not equivalent to a completed producer publication.
        Some(Reply(id,if(writing)BigInt(0)else line(address),!writing && faultReads(address),
          cycle+(if(writing && address>=0x3000 && address<0x4100)37 else if(writing)11 else 5),write))
      } else None
      for(port<-dut.io.trap)if(port.valid.peek().litToBoolean)traps+=port.bits.peek().litValue
      if(acceptedReply) {
        held.get.write.foreach { case(address,data,mask) =>
          for(i<-0 until 64 if mask.testBit(i))bytes(address+i)=((data>>(i*8))&255).toInt
          acknowledgedWrites+=1
        }
        held=None
      }
      dut.clock.step();cycle+=1
      request.foreach(pending+=_)
    }
    def until(label: String)(p: => Boolean): Unit = {
      val start=cycle
      while(!p && cycle-start<30000)step()
      assert(p,s"$label timed out cycle=$cycle pending=${pending.size} held=$held busyCU=${dut.io.busyComputeUnits.peek().litValue} inFlight=${dut.io.inFlightCommands.peek().litValue} commandBusy=${dut.io.commandProcessorBusy.peek().litValue} routerBusy=${dut.io.unifiedCommandRouterBusy.peek().litValue} reads=${readAddresses.takeRight(12)} traps=$traps")
    }
    def send(id: Int,opcode: UInt)(fields: => Unit): Unit = {
      dut.io.gpuCommand.bits.poke(0.U.asTypeOf(dut.io.gpuCommand.bits))
      dut.io.gpuCommand.bits.commandId.poke(id.U);dut.io.gpuCommand.bits.opcode.poke(opcode)
      fields;dut.io.gpuCommand.valid.poke(true.B)
      until(s"submit$id")(dut.io.gpuCommand.ready.peek().litToBoolean)
      step();dut.io.gpuCommand.valid.poke(false.B)
      dut.io.gpuCommand.bits.poke(0.U.asTypeOf(dut.io.gpuCommand.bits))
    }
    def launch(pc: Int,kernarg: Int): Unit = {
      dut.io.gpuCommand.bits.launch.kernelPc.poke(pc.U)
      dut.io.gpuCommand.bits.launch.kernargAddress.poke(kernarg.U)
      for(k<-0 until 3){dut.io.gpuCommand.bits.launch.gridSize(k).poke(1.U);dut.io.gpuCommand.bits.launch.localSize(k).poke(1.U)}
    }
    def indirect(id: Int,base: Int,offset: Int,kernarg: Int,pc: Int=0x2000,
                 event: Boolean=false,dma: Option[Int]=None): Unit = send(id,GpuCommandOpcode.kernelIndirect) {
      launch(pc,kernarg);dut.io.gpuCommand.bits.sourceAddress.poke(base.U)
      dut.io.gpuCommand.bits.bytes.poke(128.U);dut.io.gpuCommand.bits.widthBytes.poke(offset.U)
      if(event){dut.io.gpuCommand.bits.waitForEvent.poke(true.B);dut.io.gpuCommand.bits.waitEventId.poke(9.U);dut.io.gpuCommand.bits.waitEventGeneration.poke(3.U)}
      dma.foreach { producer =>dut.io.gpuCommand.bits.waitForDma.poke(true.B);dut.io.gpuCommand.bits.dmaSource.poke(DmaEventSource.fill);dut.io.gpuCommand.bits.dmaDescriptorId.poke(producer.U) }
    }
    def completion(id: Int,status: Int=0,success: Boolean=true,opcode: UInt=GpuCommandOpcode.kernelIndirect): Unit = {
      until(s"completion$id")(dut.io.gpuCompletion.valid.peek().litToBoolean)
      dut.io.gpuCompletion.bits.commandId.expect(id.U);dut.io.gpuCompletion.bits.opcode.expect(opcode)
      dut.io.gpuCompletion.bits.status.expect(status.U,s"command$id completion status at cycle$cycle")
      dut.io.gpuCompletion.bits.success.expect(success.B,s"command$id completion success at cycle$cycle")
      val heldResult=dut.io.gpuCompletion.bits.peek().litValue
      for(_<-0 until 9){dut.io.vmContextIdle.expect(false.B);step();assert(dut.io.gpuCompletion.bits.peek().litValue==heldResult)}
      dut.io.gpuCompletion.ready.poke(true.B);step();dut.io.gpuCompletion.ready.poke(false.B)
    }
    def idle(): Unit=until("VM retirement")(dut.io.vmContextIdle.peek().litToBoolean)
    def noCuUntilCompletion(): Unit = {
      until("local indirect result") {
        dut.io.busyComputeUnits.expect(0.U)
        dut.io.gpuCompletion.valid.peek().litToBoolean
      }
    }
  }

  private def system = new GpuSystem(GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2),
    numComputeUnits=2,enableUnifiedCommands=true,enableIndirectCompute=true,
    instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1)
  private def initialize(d: GpuSystem): Unit = {
    zero(d.io);d.io.graphicsDrained.poke(true.B)
    d.io.graphicsHostResponse.ready.poke(true.B);d.io.graphicsShaderResponse.ready.poke(true.B)
    d.io.graphicsShaderL1Invalidate.ready.poke(true.B);d.io.graphicsShaderAtomicResponse.ready.poke(true.B)
    d.io.trap.foreach(_.ready.poke(true.B))
    d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
  }

  it should "walk both Sv32 argument pages and suppress launch on unreadable PTEs and physical page-walk faults" in {
    simulate(system) { d =>
      initialize(d);val t=new Memory(d)
      // VA0x40000ffc crosses two separately mapped 4KiB pages. The root
      // and second-level PTEs use the actual reserved page-walk client.
      t.put(0x80400,(BigInt(0x81)<<10)|1)
      t.put(0x81000,(BigInt(0xa0)<<10)|0xcf);t.put(0x81004,(BigInt(0xb0)<<10)|0xcf)
      t.put(0xa0ffc,2);t.put(0xb0000,1);t.put(0xb0004,1)
      t.program(0x2000,Seq(cease))
      d.io.vectorSatp.poke(((BigInt(1)<<31)|0x80).U)
      val before=d.io.performance.activeCuCycles.peek().litValue
      t.indirect(1,0x40000fc0,60,0,pc=0x2000);t.completion(1);t.idle()
      assert(d.io.performance.activeCuCycles.peek().litValue>before)
      for(a<-Seq(0x80400L,0x81000L,0x81004L,0xa0fc0L,0xb0000L,0x2000L))
        assert(t.readAddresses.contains(a),s"missing actual translated read ${a.toHexString}: ${t.readAddresses}")
      assert(!t.readAddresses.exists(a=>a>=0x40000000L && a<0x41000000L),"virtual arguments must never escape as physical addresses")

      // A valid first page cannot cause partial launch if the second page
      // grants execute but denies data reads. No physical second-page fetch.
      t.put(0x80404,(BigInt(0x82)<<10)|1)
      t.put(0x82000,(BigInt(0xc0)<<10)|0xcf);t.put(0x82004,(BigInt(0xd0)<<10)|0x49)
      t.put(0xc0ffc,1);t.put(0xd0000,1);t.put(0xd0004,1)
      val faultCycles=d.io.performance.activeCuCycles.peek().litValue
      t.indirect(2,0x40400fc0,60,0,pc=0x8800);t.noCuUntilCompletion()
      t.completion(2,status=13,success=false);t.idle()
      assert(d.io.performance.activeCuCycles.peek().litValue==faultCycles)
      for(a<-Seq(0x80404L,0x82000L,0x82004L,0xc0fc0L))assert(t.readAddresses.contains(a))
      assert(!t.readAddresses.contains(0xd0000L));assert(!t.readAddresses.contains(0x8800L))

      // A physical fault returning from the page-table walk is routed back
      // to this argument owner and completes as a read fault, without a CU.
      t.put(0x80408,(BigInt(0x83)<<10)|1);t.faultReads=Set(0x83000L)
      t.indirect(3,0x40800000,0,0,pc=0x8800);t.noCuUntilCompletion()
      t.completion(3,status=13,success=false);t.idle()
      assert(t.readAddresses.contains(0x83000L));assert(!t.readAddresses.contains(0x8800L))
      assert(d.io.performance.activeCuCycles.peek().litValue==faultCycles)

      // Healthy reuse after translation faults uses a distinct mapping.
      t.faultReads=Set.empty;t.put(0x8040c,(BigInt(0x84)<<10)|1)
      t.put(0x84000,(BigInt(0xe0)<<10)|0xcf)
      t.put(0xe0000,1);t.put(0xe0004,1);t.put(0xe0008,1)
      t.indirect(3,0x40c00000,0,0,pc=0x2000);t.completion(3);t.idle()
      assert(t.readAddresses.contains(0xe0000L));assert(t.traps.isEmpty)
      assert(t.pending.isEmpty && t.held.isEmpty)
      info("INDIRECT_GPU_TRANSLATION Sv32TwoLevels=true crossedPages=true distinctPhysicalBacking=true actualCUExecution=true secondPageReadPermissionFault=true physicalPTWfault=true noPartialLaunch=true healthyIDreuse=true")
    }
  }

  for(mode<-Seq("offered-data","accepted-data","accepted-walk"))
  it should s"drain real lower ownership before controlled reset and permit healthy command ID reuse mode=$mode" in {
    simulate(system) { d =>
      initialize(d);val t=new Memory(d)
      t.program(0x2000,Seq(addi(5,0,0x66),sw(5,1,0),cease))
      t.put(0x303c,1);t.put(0x3040,1);t.put(0x3044,1)
      val walking=mode=="accepted-walk"
      if(walking) {
        t.put(0x80400,(BigInt(0x81)<<10)|1);t.put(0x81000,(BigInt(0xa0)<<10)|0xcf)
        t.put(0xa003c,1);t.put(0xa0040,1);t.put(0xa0044,1)
        d.io.vectorSatp.poke(((BigInt(1)<<31)|0x80).U)
      }
      t.replyEnabled=false;t.requestEnabled=mode!="offered-data"
      t.indirect(7,if(walking)0x40000000 else 0x3000,60,0x6500,pc=0x8800)
      if(mode=="offered-data")t.until("offered argument read")(d.io.memoryRequest.valid.peek().litToBoolean)
      else t.until("accepted data or PTW request")(t.pending.nonEmpty)
      val offered=if(mode=="offered-data")Some(d.io.memoryRequest.bits.peek().litValue)else None
      if(walking)assert(t.readAddresses.toSeq==Seq(0x80400L))
      else if(mode=="accepted-data")assert(t.readAddresses.toSeq==Seq(0x3000L))
      // Queue another command behind the read. Reset must discard it after
      // the old owner retires, rather than launch it during the reset edge.
      t.indirect(8,0x6000,0,0x6600,pc=0x8800)
      d.io.commandResetActive.poke(true.B)
      d.io.gpuCommand.bits.commandId.poke(7.U);d.io.gpuCommand.bits.opcode.poke(GpuCommandOpcode.kernelIndirect)
      d.io.gpuCommand.valid.poke(true.B)
      for(_<-0 until 15) {
        d.io.commandResetDone.expect(false.B);d.io.vmContextIdle.expect(false.B)
        d.io.busyComputeUnits.expect(0.U);d.io.gpuCommand.ready.expect(false.B)
        offered.foreach { bits=>d.io.memoryRequest.valid.expect(true.B);assert(d.io.memoryRequest.bits.peek().litValue==bits) }
        t.step()
      }
      d.io.gpuCommand.valid.poke(false.B)
      // An offered request must still be accepted, and an accepted one must
      // receive its actual reply. Neither silence nor cancellation fabricates one.
      t.requestEnabled=true
      t.until("accepted retained lower read")(t.pending.nonEmpty)
      for(_<-0 until 15){d.io.commandResetDone.expect(false.B);d.io.busyComputeUnits.expect(0.U);t.step()}
      t.replyEnabled=true;d.io.gpuCompletion.ready.poke(true.B)
      t.until("reset acknowledgement after physical drain")(d.io.commandResetDone.peek().litToBoolean)
      assert(t.pending.isEmpty && t.held.isEmpty)
      d.io.busyComputeUnits.expect(0.U)
      assert(!t.readAddresses.contains(0x3040L) && !t.readAddresses.contains(0xa0040L),"cancellation must not issue the second argument line")
      assert(!t.readAddresses.contains(0x6000L) && !t.readAddresses.contains(0x8800L),"reset must discard queued and unlaunched kernels")
      t.step();d.io.commandResetActive.poke(false.B);d.io.gpuCompletion.ready.poke(false.B)
      t.idle();d.io.vectorSatp.poke(0.U)
      for(a<-Seq(0x9000L,0x9004L,0x9008L))t.put(a,1)
      t.put(0x6500,0);t.indirect(7,0x9000,0,0x6500);t.completion(7);t.idle()
      assert(t.word(0x6500)==0x66);assert(t.traps.isEmpty)
      assert(t.pending.isEmpty && t.held.isEmpty)
      info(s"INDIRECT_GPU_RESET mode=$mode offeredPayloadRetained=true actualReplyRequired=true pendingWalkOrDataDrained=true noSecondArgumentLine=true noQueuedLaunch=true noEarlyIDreuse=true healthyCUStoreAfterReset=true")
    }
  }
}
