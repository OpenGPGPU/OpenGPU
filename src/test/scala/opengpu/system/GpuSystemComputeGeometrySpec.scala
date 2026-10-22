package opengpu.system

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.command.GpuCommandOpcode
import opengpu.config.GpuConfig
import opengpu.dispatch.DmaEventSource
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class GpuSystemComputeGeometrySpec extends AnyFlatSpec {
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
    var maxBusy=0
    def word(address: Long): BigInt=(0 until 4).map(i=>BigInt(bytes.getOrElse(address+i,0))<<(i*8)).sum
    def put(address: Long,value: BigInt): Unit=for(i<-0 until 4)bytes(address+i)=((value>>(i*8))&255).toInt
    def program(address: Long,words: Seq[BigInt]): Unit=words.zipWithIndex.foreach{case(v,i)=>put(address+4*i,v)}
    def line(address: Long): BigInt=(0 until 64).map(i=>BigInt(bytes.getOrElse(address+i,0))<<(i*8)).sum
    def step(): Unit = {
      maxBusy=math.max(maxBusy,dut.io.busyComputeUnits.peek().litValue.bitCount)
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

  for((cus,full)<-Seq((2,false),(4,true)))
  it should s"execute a three dimensional grid at full resident workgroup capacity and preserve exact built-ins CUs=$cus full=$full" in {
    val config=if(full)GpuConfig()else GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2)
    val local=if(full)Seq(4,4,8)else Seq(2,2,2)
    assert(local.product==config.lanes*config.warps)
    simulate(new GpuSystem(config,numComputeUnits=cus,enableUnifiedCommands=true,
      enableIndirectCompute=true,instructionCacheSets=if(full)64 else 4,
      instructionCacheWays=if(full)2 else 1,vectorCacheSets=if(full)64 else 4,
      vectorCacheWays=if(full)2 else 1)) { d=>
      zero(d.io);d.io.graphicsDrained.poke(true.B)
      d.io.graphicsHostResponse.ready.poke(true.B);d.io.graphicsShaderResponse.ready.poke(true.B)
      d.io.graphicsShaderL1Invalidate.ready.poke(true.B);d.io.graphicsShaderAtomicResponse.ready.poke(true.B)
      d.io.trap.foreach(_.ready.poke(true.B))
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val t=new Memory(d)
      def sh(rd: Int,rs: Int,bits: Int): BigInt=BigInt((bits<<20)|(rs<<15)|(1<<12)|(rd<<7)|0x13)
      def add(rd: Int,rs1: Int,rs2: Int): BigInt=BigInt((rs2<<20)|(rs1<<15)|(rd<<7)|0x33)
      // Address: kernarg + linear(groupXYZ)*512 + localLinearBase*4.
      // Value independently packs all group axes, local shape and warp base.
      val program=Seq(sh(10,3,1),add(10,10,2),sh(11,4,2),add(10,10,11),sh(10,10,9),
        sh(11,8,2),add(10,10,11),add(10,10,1),sh(12,8,8),sh(13,3,3),add(12,12,13),
        add(12,12,2),sh(13,4,6),add(12,12,13),sh(13,5,16),add(12,12,13),
        sh(13,6,21),add(12,12,13),sh(13,7,26),add(12,12,13),sw(12,10,0),cease)
      t.program(0x1000,program)
      val output=0x20000;val guard=BigInt("a5d72c91",16)
      for(i<- -1 to 1024)t.put(output+4*i,guard)
      val expected=(for(z<-0 until 2;y<-0 until 2;x<-0 until 2;warp<-0 until config.warps)yield{
        val localBase=warp*config.lanes
        val address=output+(x+2*y+4*z)*512+localBase*4
        val value=BigInt(x)|(BigInt(y)<<3)|(BigInt(z)<<6)|(BigInt(localBase)<<8)|
          (BigInt(local(0))<<16)|(BigInt(local(1))<<21)|(BigInt(local(2))<<26)
        address->value
      }).toMap
      t.send(1,GpuCommandOpcode.kernel){
        t.launch(0x1000,output)
        for(i<-0 until 3){d.io.gpuCommand.bits.launch.gridSize(i).poke(2.U);d.io.gpuCommand.bits.launch.localSize(i).poke(local(i).U)}
      }
      t.completion(1,opcode=GpuCommandOpcode.kernel);t.idle()
      for(i<- -1 to 1024){val address=output+4*i;assert(t.word(address)==expected.getOrElse(address,guard),s"3D built-in output mismatch address=${address.toHexString}")}
      assert(t.acknowledgedWrites==expected.size && t.pending.isEmpty && t.held.isEmpty)
      assert(t.traps.isEmpty && t.maxBusy==cus,s"not every CU acquired work: maxBusy=${t.maxBusy} expected=$cus")
      info(s"COMPUTE_GEOMETRY CUs=$cus lanes=${config.lanes} warps=${config.warps} defaultCaches=$full grid2x2x2=true localItems=${local.product} localShape=${local.mkString("x")} allCUsUsed=true exactGroupXYZLocalShapeAndWarpBase=true exactStores=${expected.size} untouchedGuards=true realWriteReplies=true heldCompletion=true")
    }
  }
}
