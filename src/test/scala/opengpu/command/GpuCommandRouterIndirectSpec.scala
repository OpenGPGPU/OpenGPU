package opengpu.command

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.dispatch.{DmaEventSource,KernelCommandStatus}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class GpuCommandRouterIndirectSpec extends AnyFlatSpec {
  private def zero(data: Data): Unit = data match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input => u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input => s.poke(0.S)
    case _ => ()
  }
  private class Driver(val dut: GpuCommandRouter) {
    zero(dut.io);dut.reset.poke(true.B);dut.clock.step();dut.reset.poke(false.B)
    def until(p: => Boolean): Unit = {var n=0;while(!p && n<150){dut.clock.step();n+=1};assert(p)}
    def send(id: Int, opcode: UInt)(fields: => Unit): Unit = {
      dut.io.command.bits.poke(0.U.asTypeOf(dut.io.command.bits))
      dut.io.command.bits.commandId.poke(id.U);dut.io.command.bits.opcode.poke(opcode)
      fields;dut.io.command.valid.poke(true.B);until(dut.io.command.ready.peek().litToBoolean)
      dut.clock.step();dut.io.command.valid.poke(false.B)
      dut.io.command.bits.poke(0.U.asTypeOf(dut.io.command.bits))
    }
    def indirect(id: Int, bytes: Int=128, offset: Int=0, dma: Option[Int]=None,
                 eventGeneration: Option[Int]=None): Unit = send(id,GpuCommandOpcode.kernelIndirect) {
      dut.io.command.bits.sourceAddress.poke(0x1000.U);dut.io.command.bits.bytes.poke(bytes.U)
      dut.io.command.bits.widthBytes.poke(offset.U)
      dut.io.command.bits.launch.kernelPc.poke(0x2000.U)
      dut.io.command.bits.launch.kernargAddress.poke(0x6000.U)
      for(k<-0 until 3){dut.io.command.bits.launch.localSize(k).poke(1.U);dut.io.command.bits.launch.gridSize(k).poke(0xdead.U)}
      dma.foreach { id =>dut.io.command.bits.waitForDma.poke(true.B);dut.io.command.bits.dmaSource.poke(DmaEventSource.fill);dut.io.command.bits.dmaDescriptorId.poke(id.U) }
      eventGeneration.foreach { g =>dut.io.command.bits.waitForEvent.poke(true.B);dut.io.command.bits.waitEventId.poke(3.U);dut.io.command.bits.waitEventGeneration.poke(g.U) }
    }
    def fill(id: Int, generation: Int): Unit = {
      send(id,GpuCommandOpcode.fill) {
        dut.io.command.bits.destinationAddress.poke(0x1000.U);dut.io.command.bits.bytes.poke(64.U)
        dut.io.command.bits.signalEvent.poke(true.B);dut.io.command.bits.signalEventId.poke(3.U)
        dut.io.command.bits.signalEventGeneration.poke(generation.U)
      }
      until(dut.io.fill.valid.peek().litToBoolean);dut.io.fill.ready.poke(true.B)
      dut.clock.step();dut.io.fill.ready.poke(false.B)
    }
    def fillDone(id: Int, success: Boolean=true): Unit = {
      dut.io.fillCompletion.bits.descriptorId.poke(id.U);dut.io.fillCompletion.bits.status.poke(0.U)
      dut.io.fillCompletion.bits.success.poke(success.B);dut.io.fillCompletion.bits.bytesFilled.poke(64.U)
      dut.io.fillCompletion.valid.poke(true.B);until(dut.io.fillCompletion.ready.peek().litToBoolean)
      dut.clock.step();dut.io.fillCompletion.valid.poke(false.B)
    }
    def dma(id: Int, source: Int=1, success: Boolean=true): Unit = {
      val e=dut.io.dmaCompletion.get(source);e.bits.source.poke(source.U)
      e.bits.descriptorId.poke(id.U);e.bits.success.poke(success.B);e.valid.poke(true.B)
      dut.clock.step();e.valid.poke(false.B)
    }
    def noRead(n: Int=12): Unit = for(_<-0 until n){dut.io.indirectMemoryRequest.get.valid.expect(false.B);dut.io.kernel.valid.expect(false.B);dut.clock.step()}
    def read(data: BigInt, fault: Boolean=false, address: Int=0x1000): Unit = {
      val req=dut.io.indirectMemoryRequest.get;until(req.valid.peek().litToBoolean)
      req.bits.address.expect(address.U);req.bits.isWrite.expect(false.B);req.bits.transactionId.expect(0.U)
      val held=req.bits.peek().litValue
      for(_<-0 until 5){req.valid.expect(true.B);assert(req.bits.peek().litValue==held);dut.clock.step()}
      req.ready.poke(true.B);dut.clock.step();req.ready.poke(false.B)
      dut.clock.step(9)
      val resp=dut.io.indirectMemoryResponse.get
      resp.bits.readData.poke(data.U);resp.bits.fault.poke(fault.B);resp.bits.transactionId.poke(0.U)
      resp.valid.poke(true.B);resp.ready.expect(true.B);dut.clock.step();resp.valid.poke(false.B)
    }
    def kernelDone(id: Int): Unit = {
      dut.io.kernelCompletion.bits.commandId.poke(id.U)
      dut.io.kernelCompletion.bits.status.poke(KernelCommandStatus.success)
      dut.io.kernelCompletion.bits.success.poke(true.B);dut.io.kernelCompletion.valid.poke(true.B)
      until(dut.io.kernelCompletion.ready.peek().litToBoolean);dut.clock.step();dut.io.kernelCompletion.valid.poke(false.B)
    }
    def pop(id: Int, status: Int=0, success: Boolean=true, opcode: UInt=GpuCommandOpcode.kernelIndirect): Unit = {
      until(dut.io.completion.valid.peek().litToBoolean)
      dut.io.completion.bits.commandId.expect(id.U);dut.io.completion.bits.opcode.expect(opcode)
      dut.io.completion.bits.status.expect(status.U);dut.io.completion.bits.success.expect(success.B)
      val held=dut.io.completion.bits.peek().litValue
      for(_<-0 until 5){dut.clock.step();assert(dut.io.completion.bits.peek().litValue==held)}
      dut.io.completion.ready.poke(true.B);dut.clock.step();dut.io.completion.ready.poke(false.B)
    }
  }
  private def module = new GpuCommandRouter(GpuConfig(lanes=4,warps=2),commandIdWidth=4,
    enableIndirectCompute=true)

  it should "wait for matching event generation and DMA before reading and retain the original command through dispatch and held completion" in {
    simulate(module) { d =>
      val t=new Driver(d)
      t.fill(1,7);t.fill(4,8);t.indirect(2,dma=Some(4),eventGeneration=Some(8))
      t.noRead();t.fillDone(1);t.dma(1);t.noRead()
      t.fillDone(4);t.dma(4,source=0);t.noRead()
      t.pop(1,opcode=GpuCommandOpcode.fill);t.pop(4,opcode=GpuCommandOpcode.fill)
      t.noRead();t.dma(4)
      val groups=Vector(BigInt("ffffffff",16),BigInt(2),BigInt(3))
      t.read(groups.zipWithIndex.map{case(v,i)=>v<<(i*32)}.sum)
      t.until(d.io.kernel.valid.peek().litToBoolean)
      d.io.kernel.bits.commandId.expect(2.U);d.io.kernel.bits.launch.kernelPc.expect(0x2000.U)
      d.io.kernel.bits.launch.kernargAddress.expect(0x6000.U);d.io.kernel.bits.waitForDma.expect(false.B)
      for(k<-0 until 3){d.io.kernel.bits.launch.gridSize(k).expect(groups(k).U);d.io.kernel.bits.launch.localSize(k).expect(1.U)}
      val held=d.io.kernel.bits.peek().litValue
      d.io.command.bits.commandId.poke(2.U);d.io.command.valid.poke(true.B)
      for(_<-0 until 8){d.io.command.ready.expect(false.B);d.io.duplicateCommandId.expect(true.B);assert(d.io.kernel.bits.peek().litValue==held);d.clock.step()}
      d.io.command.valid.poke(false.B)
      d.io.blockDispatch.poke(true.B);d.clock.step(7);d.io.indirectBusy.expect(true.B)
      d.io.blockDispatch.poke(false.B);d.io.kernel.valid.expect(true.B)
      d.io.kernel.ready.poke(true.B);d.clock.step();d.io.kernel.ready.poke(false.B)
      d.io.busy.expect(true.B);d.io.memoryEngineBusy.expect(false.B)
      t.kernelDone(2);t.pop(2);d.io.busy.expect(false.B)
      info("INDIRECT_ROUTER_OWNER eventGeneration=true DMAOrderedBeforeRead=true wrongSourceIgnored=true fullUint32=true capturedLaunch=true heldKernel=true duplicateIDPinned=true pausePreservesResult=true noExtraDMAOwner=true completionOpcode14=true")
    }
  }

  it should "retire empty, malformed and faulting argument commands without any partial kernel dispatch" in {
    simulate(module) { d =>
      val t=new Driver(d)
      t.indirect(1);t.read(BigInt("ffffffff",16)<<32);t.pop(1)
      d.io.kernel.valid.expect(false.B)
      t.indirect(2,bytes=11);t.pop(2,status=12,success=false);d.io.indirectMemoryRequest.get.valid.expect(false.B)
      t.indirect(3);t.read(0,fault=true);t.pop(3,status=13,success=false)
      t.indirect(4,offset=60);t.read(BigInt(2)<<(15*32))
      d.io.kernel.valid.expect(false.B);t.read(0,fault=true,address=0x1040);t.pop(4,status=13,success=false)
      t.dma(7,success=false);t.indirect(5,dma=Some(7));t.pop(5,status=6,success=false)
      d.io.indirectMemoryRequest.get.valid.expect(false.B)
      t.fill(6,11);t.indirect(8,eventGeneration=Some(11));t.noRead()
      t.fillDone(6,success=false);t.pop(6,success=false,opcode=GpuCommandOpcode.fill)
      t.pop(8,status=14,success=false)
      d.io.kernel.valid.expect(false.B);d.io.busy.expect(false.B)
      info("INDIRECT_ROUTER_FAILURE zeroNoop=true boundsNoRead=true firstAndSecondLineFault=true noPartialLaunch=true DMAFailure=true eventFailure=true publicOwnersRetired=true")
    }
  }

  it should "expire reused producer tokens and drain an irrevocable argument request before reset and healthy ID reuse" in {
    simulate(module) { d =>
      val t=new Driver(d)
      t.fill(1,1);t.fillDone(1);t.dma(1);t.pop(1,opcode=GpuCommandOpcode.fill)
      t.fill(1,2);t.indirect(2,dma=Some(1));t.noRead(20)
      t.fillDone(1,success=false);t.dma(1,success=false)
      t.pop(1,success=false,opcode=GpuCommandOpcode.fill);t.pop(2,status=6,success=false)
      t.indirect(3,offset=60)
      val req=d.io.indirectMemoryRequest.get;t.until(req.valid.peek().litToBoolean)
      val held=req.bits.peek().litValue
      d.io.blockDispatch.poke(true.B);d.io.cancelIndirect.get.poke(true.B)
      for(_<-0 until 8){req.valid.expect(true.B);assert(req.bits.peek().litValue==held);d.io.indirectBusy.expect(true.B);d.clock.step()}
      req.ready.poke(true.B);d.clock.step();req.ready.poke(false.B)
      for(_<-0 until 19){d.io.indirectBusy.expect(true.B);d.io.kernel.valid.expect(false.B);d.clock.step()}
      val resp=d.io.indirectMemoryResponse.get;resp.bits.fault.poke(true.B);resp.valid.poke(true.B)
      resp.ready.expect(true.B);d.clock.step();resp.valid.poke(false.B)
      d.io.indirectBusy.expect(false.B);req.valid.expect(false.B);d.io.completion.valid.expect(false.B)
      d.io.pathReset.poke(true.B);d.clock.step();d.io.pathReset.poke(false.B)
      d.io.cancelIndirect.get.poke(false.B);d.io.blockDispatch.poke(false.B);d.io.busy.expect(false.B)
      t.indirect(3);t.read(BigInt(1)|(BigInt(1)<<32)|(BigInt(1)<<64))
      t.until(d.io.kernel.valid.peek().litToBoolean);d.io.kernel.bits.commandId.expect(3.U)
      d.io.kernel.ready.poke(true.B);d.clock.step();d.io.kernel.ready.poke(false.B)
      t.kernelDone(3);t.pop(3);d.io.busy.expect(false.B)
      info("INDIRECT_ROUTER_RESET reusedDMAIdExpiresOldToken=true offeredReadHeld=true realReplyDrained=true noCanceledSecondRead=true noKernelDuringReset=true healthyCommandIdReuse=true")
    }
  }
}
