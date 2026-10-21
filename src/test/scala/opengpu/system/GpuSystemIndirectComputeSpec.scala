package opengpu.system

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.command.GpuCommandOpcode
import opengpu.config.GpuConfig
import opengpu.dispatch.DmaEventSource
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class GpuSystemIndirectComputeSpec extends AnyFlatSpec {
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
    var faultReads=Set.empty[Long]
    val readAddresses=mutable.ArrayBuffer.empty[Long]
    val traps=mutable.ArrayBuffer.empty[BigInt]
    var acknowledgedWrites=0
    def word(address: Long): BigInt=(0 until 4).map(i=>BigInt(bytes.getOrElse(address+i,0))<<(i*8)).sum
    def put(address: Long,value: BigInt): Unit=for(i<-0 until 4)bytes(address+i)=((value>>(i*8))&255).toInt
    def program(address: Long,words: Seq[BigInt]): Unit=words.zipWithIndex.foreach{case(v,i)=>put(address+4*i,v)}
    def line(address: Long): BigInt=(0 until 64).map(i=>BigInt(bytes.getOrElse(address+i,0))<<(i*8)).sum
    def step(): Unit = {
      dut.io.memoryRequest.ready.poke((cycle%7<5).B)
      if(held.isEmpty && cycle%11>=6) {
        val i=pending.lastIndexWhere(_.due<=cycle)
        if(i>=0)held=Some(pending.remove(i))
      }
      dut.io.memoryResponse.valid.poke(held.nonEmpty.B)
      held.foreach { reply =>
        dut.io.memoryResponse.bits.transactionId.poke(reply.id.U)
        dut.io.memoryResponse.bits.readData.poke(reply.data.U)
        dut.io.memoryResponse.bits.fault.poke(reply.fault.B)
      }
      val acceptedReply=held.nonEmpty && dut.io.memoryResponse.ready.peek().litToBoolean
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

  for((cus,defaultProfile)<-Seq((2,false),(4,true)))
  it should s"consume actual GPU-produced arguments after event and DMA publication and preserve precise fault/no-work ownership CUs=$cus defaultProfile=$defaultProfile" in {
    val config=if(defaultProfile)GpuConfig()else GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2)
    simulate(new GpuSystem(config,numComputeUnits=cus,enableUnifiedCommands=true,
      enableIndirectCompute=true,instructionCacheSets=if(defaultProfile)64 else 4,
      instructionCacheWays=if(defaultProfile)2 else 1,
      vectorCacheSets=if(defaultProfile)64 else 4,vectorCacheWays=if(defaultProfile)2 else 1)) { d =>
      zero(d.io);d.io.graphicsDrained.poke(true.B)
      d.io.graphicsHostResponse.ready.poke(true.B);d.io.graphicsShaderResponse.ready.poke(true.B)
      d.io.graphicsShaderL1Invalidate.ready.poke(true.B);d.io.graphicsShaderAtomicResponse.ready.poke(true.B)
      d.io.trap.foreach(_.ready.poke(true.B))
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val t=new Memory(d)
      val producer=Seq.fill(48)(addi(0,0,0))++Seq(addi(5,0,2),sw(5,1,0),addi(5,0,1),sw(5,1,4),sw(5,1,8),cease)
      val slli=BigInt((2<<20)|(2<<15)|(1<<12)|(10<<7)|0x13)
      val add=BigInt((10<<20)|(1<<15)|(11<<7)|0x33)
      t.program(0x1000,producer);t.program(0x2000,Seq(slli,add,addi(12,2,0x60),sw(12,11,0),cease))
      val guard=BigInt("a5d72c91",16)
      for(a<-Seq(0x5ffcL,0x6008L,0x6104L,0x6200L,0x6300L,0x6400L,0x6504L))t.put(a,guard)
      t.put(0x303c,0);t.put(0x3040,7);t.put(0x3044,9)
      t.send(1,GpuCommandOpcode.kernel) {
        t.launch(0x1000,0x303c);d.io.gpuCommand.bits.signalEvent.poke(true.B)
        d.io.gpuCommand.bits.signalEventId.poke(9.U);d.io.gpuCommand.bits.signalEventGeneration.poke(3.U)
      }
      t.indirect(2,0x3000,60,0x6000,event=true)
      t.until("producer publication")(d.io.gpuCompletion.valid.peek().litToBoolean)
      assert(t.word(0x303c)==2 && t.word(0x3040)==1 && t.word(0x3044)==1,
        "producer completion must follow all three real store acknowledgements")
      d.io.gpuCommand.bits.commandId.poke(2.U);d.io.gpuCommand.valid.poke(true.B)
      for(_<-0 until 7){d.io.gpuCommand.ready.expect(false.B);d.io.duplicateUnifiedCommandId.expect(true.B);t.step()}
      d.io.gpuCommand.valid.poke(false.B)
      t.completion(1,opcode=GpuCommandOpcode.kernel);t.completion(2);t.idle()
      assert(t.word(0x6000)==0x60 && t.word(0x6004)==0x61)
      info("INDIRECT_GPU_PRODUCER producerKernel=true argumentCounts2x1x1=true eventOrderedConsumer=true exactGroupXStores2=true lowerStoreAcksObserved=true")

      // The existing fill engine, not a testbench argument overwrite, is the
      // second producer. Its DMA token must precede the indirect read.
      t.send(3,GpuCommandOpcode.fill) {
        d.io.gpuCommand.bits.destinationAddress.poke(0x4000.U)
        d.io.gpuCommand.bits.bytes.poke(64.U);d.io.gpuCommand.bits.pattern.poke(1.U)
      }
      t.indirect(4,0x4000,0,0x6100,dma=Some(3))
      t.completion(3,opcode=GpuCommandOpcode.fill);t.completion(4);t.idle()
      assert(t.word(0x6100)==0x60)

      val activeBefore=d.io.performance.activeCuCycles.peek().litValue
      val readStart=t.readAddresses.size
      t.put(0x5000,0);t.put(0x5004,BigInt("ffffffff",16));t.put(0x5008,1)
      t.indirect(5,0x5000,0,0x6200,pc=0x8800);t.noCuUntilCompletion();t.completion(5);t.idle()
      assert(d.io.performance.activeCuCycles.peek().litValue==activeBefore)
      assert(!t.readAddresses.drop(readStart).contains(0x8800L))

      t.put(0x703c,3);t.put(0x7040,1);t.put(0x7044,1);t.faultReads=Set(0x7040L)
      t.indirect(6,0x7000,60,0x6300);t.noCuUntilCompletion();t.completion(6,status=13,success=false);t.idle()
      assert(t.readAddresses.contains(0x7000L) && t.readAddresses.contains(0x7040L))
      t.faultReads=Set(0x8000L);t.indirect(7,0x8000,0,0x6400)
      t.noCuUntilCompletion();t.completion(7,status=13,success=false);t.idle()

      t.faultReads=Set.empty;t.put(0x9000,1);t.put(0x9004,1);t.put(0x9008,1)
      t.indirect(6,0x9000,0,0x6500);t.completion(6);t.idle()
      assert(t.word(0x6500)==0x60)
      for(a<-Seq(0x5ffcL,0x6008L,0x6104L,0x6200L,0x6300L,0x6400L,0x6504L))assert(t.word(a)==guard,s"guard changed at$a")
      assert(t.traps.isEmpty && t.pending.isEmpty && t.held.isEmpty)
      info(s"INDIRECT_GPU_EXECUTION CUs=$cus lanes=${config.lanes} warps=${config.warps} defaultCaches=$defaultProfile L2sets=${config.l2Sets} L2ways=${config.l2Ways} commands=8 realProducerKernel=true producerCounts2x1x1=true crossedArgumentLines=true eventOrdered=true realFillProducer=true DMAOrdered=true zeroNoCU=true firstAndSecondLineFault=true healthyIDReuse=true heldCompletionsPinVM=true acknowledgedWrites=${t.acknowledgedWrites} exactOutputWords4=true")
    }
  }
}
