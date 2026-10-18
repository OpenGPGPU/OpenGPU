package opengpu.graphics

import chisel3._
import opengpu.config.{GpuConfig,MobileGpuConfig}
import opengpu.system.{GpuHostSystemAxi,GpuHostTestSupport}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class BinnedTileHostLegacyDmaSpec extends AnyFlatSpec with GpuHostTestSupport {
  it should "translate legacy fill blit and strided traffic and hold a queued bin job until its earlier fill acknowledges" in {
    val gpu=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2,l2StoreEntries=4)
    val mobile=MobileGpuConfig(execution=gpu,computeUnits=2,axiBytes=32,binPageBytes=128,
      binWriteCredits=2,attachmentStoreCredits=2,binTileSlots=4)
    simulate(new GpuHostSystemAxi(GraphicsConfig(screenWidth=16,screenHeight=8,tileSize=16,
      tileAttachments=true,fragmentBatchLimit=8),gpu,numComputeUnits=2,fragCore=true,
      sharedGraphics=true,parallelGraphics=true,enableFpuBackend=true,memoryAxiDataBytes=32,binnedRendering=Some(mobile))) { d=>
      initialize(d)
      val memory=mutable.Map.empty[BigInt,Long]
      val physicalOffset=0x400000L
      def put(a: Long,words: Seq[Long]): Unit=for((w,i) <- words.zipWithIndex)memory(BigInt(a+physicalOffset+i*4L))=w
      put(TileBinJobTestData.descriptorBase,TileBinJobTestData.descriptor(0,programmable=true))
      put(0x70080,Seq.fill(16)(0x87654321L))
      memory(BigInt(0x300000))=(0x400L<<10)|0xcfL
      axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0x80000300)
      axiWrite(d,GpuCommandMmioRegs.INSTRUCTION_SATP,0x80000300);axiWrite(d,GpuCommandMmioRegs.TLB_FLUSH,1)
      axiWrite(d,RenderHostRegs.IRQ,1)
      def read(a: BigInt): BigInt={val base=a & ~BigInt(63);(0 until 16).map(i=>BigInt(memory.getOrElse(base+i*4L,0L))<<(32*i)).foldLeft(BigInt(0))(_|_)}
      def write(a: BigInt,value: BigInt,mask: BigInt): Unit=for(i <- 0 until 16) {
        val address=a+i*4L;var word=memory.getOrElse(address,0L)
        for(b <- 0 until 4 if mask.testBit(i*4+b)) word=(word & ~(255L<<(8*b)))|(((value>>(32*i+8*b)).toLong&255L)<<(8*b))
        memory(address)=word&0xffffffffL
      }
      def pause(): Unit={d.io.m_axi_arready.poke(false.B);d.io.m_axi_awready.poke(false.B);d.io.m_axi_wready.poke(false.B)
        d.io.m_axi_rvalid.poke(false.B);d.io.m_axi_rlast.poke(false.B);d.io.m_axi_bvalid.poke(false.B)}
      def ack(): Unit=axiWrite(d,RenderHostRegs.IRQ,3)
      axiWrite(d,RenderHostRegs.CLEAR_BASE,0x70000);axiWrite(d,RenderHostRegs.CLEAR_BYTES,64)
      axiWrite(d,RenderHostRegs.CLEAR_PATTERN,0x12345678);axiWrite(d,RenderHostRegs.CLEAR_START,1)
      axiWrite(d,GpuCommandMmioRegs.COMMAND_ID,55);axiWrite(d,GpuCommandMmioRegs.OPCODE,7)
      axiWrite(d,GpuCommandMmioRegs.SOURCE,TileBinJobTestData.descriptorBase.toInt)
      axiWrite(d,GpuCommandMmioRegs.DESTINATION,(TileBinJobTestData.descriptorBase+512).toInt)
      axiWrite(d,GpuCommandMmioRegs.BYTES,512);axiWrite(d,GpuCommandMmioRegs.SUBMIT,1)
      axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0,expectedResponse=2)
      var fillId=Option.empty[BigInt];var fillAcknowledged=false;var descriptorSeen=false
      serviceMemoryMaster(d,a=>{
        if(a==BigInt(TileBinJobTestData.descriptorBase+physicalOffset))assert(fillAcknowledged,"bin descriptor fetched before legacy DMA B acknowledgement")
        read(a)
      },readResponseDelay=7,writeAckDelay=31,maxCycles=100000,
        onCycle=()=>{
          if(d.io.m_axi_awvalid.peek().litToBoolean && d.io.m_axi_awready.peek().litToBoolean && fillId.isEmpty)
            fillId=Some(d.io.m_axi_awid.peek().litValue)
          if(d.io.m_axi_bvalid.peek().litToBoolean && d.io.m_axi_bready.peek().litToBoolean && fillId.contains(d.io.m_axi_bid.peek().litValue))fillAcknowledged=true
        },onReadAccepted=a=>if(a==BigInt(TileBinJobTestData.descriptorBase+physicalOffset) && !descriptorSeen){descriptorSeen=true;ack()}
      )(write){descriptorSeen && d.io.m_irq.peek().litToBoolean}
      pause();assert(fillAcknowledged && descriptorSeen)
      val result=axiRead(d,GpuCommandMmioRegs.COMPLETION)
      assert((result&255)==55 && result.testBit(15) && ((result>>8)&7)==7)
      assert((0 until 16).forall(i=>memory(BigInt(0x470000+i*4L))==0x12345678L))
      axiWrite(d,GpuCommandMmioRegs.COMPLETION_POP,1);ack()
      // Legacy DMA completion is exposed by busy status, without an IRQ.
      // Drain the public AXI model before polling control registers so MMIO
      // stepping cannot consume an untracked memory handshake.
      var quiet=0
      def idleCycle(): Unit={
        val active=d.io.m_axi_arvalid.peek().litToBoolean || d.io.m_axi_awvalid.peek().litToBoolean ||
          d.io.m_axi_wvalid.peek().litToBoolean || d.io.m_axi_rvalid.peek().litToBoolean || d.io.m_axi_bvalid.peek().litToBoolean
        quiet=if(active)0 else quiet+1
      }
      def blitExact: Boolean=(0 until 16).forall(i=>memory.getOrElse(BigInt(0x471000+i*4L),0L)==0x12345678L)
      axiWrite(d,RenderHostRegs.BLIT_SRC_BASE,0x70000);axiWrite(d,RenderHostRegs.BLIT_DST_BASE,0x71000)
      axiWrite(d,RenderHostRegs.BLIT_BYTES,64);axiWrite(d,RenderHostRegs.BLIT_START,1)
      serviceMemoryMaster(d,read,writeAckDelay=13,maxCycles=20000,onCycle=()=>idleCycle())(write){blitExact && quiet>=64}
      pause();assert(blitExact);assert((axiRead(d,RenderHostRegs.STATUS)&0x14)==0)
      axiWrite(d,RenderHostRegs.STRIDED_SRC_BASE,0x70000);axiWrite(d,RenderHostRegs.STRIDED_DST_BASE,0x72000)
      axiWrite(d,RenderHostRegs.STRIDED_WIDTH,64);axiWrite(d,RenderHostRegs.STRIDED_HEIGHT,2)
      axiWrite(d,RenderHostRegs.STRIDED_SRC_STRIDE,128);axiWrite(d,RenderHostRegs.STRIDED_DST_STRIDE,192)
      axiWrite(d,RenderHostRegs.STRIDED_START,1)
      def stridedExact: Boolean=(0 until 2).forall(row=>(0 until 16).forall(i=>
        memory.getOrElse(BigInt(0x472000+row*192+i*4L),0L)==(if(row==0)0x12345678L else 0x87654321L)))
      quiet=0
      serviceMemoryMaster(d,read,writeAckDelay=17,maxCycles=20000,onCycle=()=>idleCycle())(write){stridedExact && quiet>=64}
      pause();assert(stridedExact);assert((axiRead(d,RenderHostRegs.STATUS)&0x24)==0)
      assert((16 until 48).forall(i=>memory.getOrElse(BigInt(0x472000+i*4L),0L)==0L),"strided copy must preserve row gaps")
      axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0);axiWrite(d,GpuCommandMmioRegs.INSTRUCTION_SATP,0)
      info("BIN_HOST_LEGACY_DMA Sv32=true fill=true blit=true strided=true queuedBinAfterAck=true")
    }
  }
}
