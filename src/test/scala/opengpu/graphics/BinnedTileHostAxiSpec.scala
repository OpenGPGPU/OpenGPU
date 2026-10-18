package opengpu.graphics

import chisel3._
import opengpu.config.{GpuConfig,MobileGpuConfig}
import opengpu.system.{GpuHostSystemAxi,GpuHostTestSupport}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class BinnedTileHostAxiSpec extends AnyFlatSpec with GpuHostTestSupport {
  import TileBinRenderTestData._
  private val screenW=16; private val screenH=8
  private val cfg=GraphicsConfig(screenWidth=screenW,screenHeight=screenH,tileSize=16,tileAttachments=true,omInflight=2)
  private def reference(mode: Int,extentW: Int,extentH: Int,sequence: Vector[Draw]=draws,
    width: Int=screenW,height: Int=screenH,
    colorAddress: Long=TileBinRenderTestData.colorBase,depthAddress: Long=TileBinRenderTestData.depthBase): Vector[Long] = {
    val colorBase=colorAddress; val depthBase=depthAddress
    val samples=1<<mode; val pitch=width*samples*4
    val memory=mutable.LongMap.empty[Long]
    def addr(x: Int,y: Int,s: Int,depth: Boolean): Long =
      (if (depth) depthBase else colorBase)+y*pitch+(x*samples+s)*4L
    for (depth <- Seq(false,true); y <- 0 until height; x <- 0 until width; s <- 0 until samples)
      memory(addr(x,y,s,depth))=if (x<extentW && y<extentH) (if (depth) depthClear else colorClear) else 0xdeadbeefL
    simulate(new RenderPipeline(cfg.copy(screenWidth=width,screenHeight=height,tileSize=0,tileAttachments=false),GpuConfig(lanes=4,warps=2))) { d =>
      d.io.draw.valid.poke(false.B); d.io.mem.req.ready.poke(false.B); d.io.mem.resp.valid.poke(false.B)
      d.io.mem.resp.bits.poke(0.U.asTypeOf(d.io.mem.resp.bits))
      d.io.colorFormat.poke(0.U); d.io.colorBase.poke(colorBase.U); d.io.depthBase.poke(depthBase.U)
      d.io.stride.poke(pitch.U); d.io.screenW.poke(width.U); d.io.screenH.poke(height.U)
      d.io.sampleMode.poke(mode.U); d.io.depthTestEnable.poke(false.B); d.io.depthFunc.poke(0.U)
      d.io.depthWriteEnable.poke(false.B); d.io.blendCfgEnable.poke(false.B)
      d.io.blendSrcFactor.poke(0.U); d.io.blendDstFactor.poke(0.U); d.io.blendEquation.poke(0.U)
      d.io.stencilTestEnable.poke(false.B); d.io.stencilFunc.poke(0.U); d.io.stencilRef.poke(0.U)
      d.io.stencilReadMask.poke(0.U); d.io.stencilWriteMask.poke(0.U)
      d.io.stencilFailOp.poke(0.U); d.io.stencilZFailOp.poke(0.U); d.io.stencilZPassOp.poke(0.U)
      d.io.cullMode.poke(0.U); d.io.texEnable.poke(false.B); d.io.texFormat.poke(0.U)
      d.io.texBase.poke(0.U); d.io.texWidth.poke(1.U); d.io.texHeight.poke(1.U)
      d.io.texWrapClamp.poke(false.B); d.io.texMaxLevel.poke(0.U)
      d.io.texMem.req.ready.poke(true.B); d.io.texMem.resp.valid.poke(false.B)
      d.io.texMem.resp.bits.poke(0.U.asTypeOf(d.io.texMem.resp.bits))
      d.io.kernelMemReq.ready.poke(true.B); d.io.kernelMemResp.valid.poke(false.B)
      d.io.kernelMemResp.bits.poke(0.U.asTypeOf(d.io.kernelMemResp.bits))
      d.io.kernelWordMemReq.ready.poke(true.B); d.io.kernelWordMemResp.valid.poke(false.B)
      d.io.kernelWordMemResp.bits.poke(0.U.asTypeOf(d.io.kernelWordMemResp.bits))
      d.io.kernelL1Invalidate.valid.poke(false.B); d.io.kernelL1Invalidate.bits.poke(0.U.asTypeOf(d.io.kernelL1Invalidate.bits))
      d.io.kernelL1InvalidateDone.ready.poke(true.B); d.io.kernelGlobalAtomicRequest.ready.poke(true.B)
      d.io.kernelGlobalAtomicResponse.valid.poke(false.B)
      d.io.kernelGlobalAtomicResponse.bits.poke(0.U.asTypeOf(d.io.kernelGlobalAtomicResponse.bits))
      d.io.instructionSatp.poke(0.U); d.io.vectorSatp.poke(0.U)
      d.io.instructionTlbFlush.valid.poke(false.B); d.io.vectorTlbFlush.valid.poke(false.B)
      d.io.instructionTlbFlush.bits.poke(0.U.asTypeOf(d.io.instructionTlbFlush.bits))
      d.io.vectorTlbFlush.bits.poke(0.U.asTypeOf(d.io.vectorTlbFlush.bits))
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      val queue=mutable.ArrayBuffer.empty[(Boolean,Long,Long,Int)]
      var response=Option.empty[(Boolean,Long,Long,Int)]
      var cycle=0
      def tick(): Unit = {
        d.io.mem.req.ready.poke((cycle%5!=2).B)
        if (response.isEmpty) {
          val i=queue.lastIndexWhere(_._4<=cycle)
          if (i>=0) response=Some(queue.remove(i))
        }
        d.io.mem.resp.valid.poke(response.nonEmpty.B)
        response.foreach { r =>
          d.io.mem.resp.bits.write.poke(r._1.B); d.io.mem.resp.bits.addr.poke(r._2.U)
          d.io.mem.resp.bits.data.poke(memory.getOrElse(r._2,0xdeadbeefL).U)
        }
        if (response.nonEmpty && d.io.mem.resp.ready.peek().litToBoolean) {
          val r=response.get; if (r._1) memory(r._2)=r._3
          response=None
        }
        if (d.io.mem.req.valid.peek().litToBoolean && d.io.mem.req.ready.peek().litToBoolean) {
          val b=d.io.mem.req.bits
          queue+=((b.write.peek().litToBoolean,b.addr.peek().litValue.toLong,b.data.peek().litValue.toLong,cycle+3))
        }
        d.clock.step(); cycle+=1
      }
      def until(condition: => Boolean): Unit = {
        val start=cycle
        while (!condition && cycle-start<100000) tick()
        assert(condition,s"ordered reference stalled at $cycle")
      }
      for (draw <- sequence) {
        pokeDraw(d.io.draw.bits.asInstanceOf[SceneTriangle],draw,extentW,extentH)
        d.io.draw.valid.poke(true.B); until(d.io.draw.ready.peek().litToBoolean)
        tick(); d.io.draw.valid.poke(false.B); until(d.io.done.peek().litToBoolean)
      }
      assert(queue.isEmpty && response.isEmpty)
    }
    (for (depth <- Vector(false,true); y <- 0 until height; x <- 0 until width;
      s <- 0 until samples) yield memory(addr(x,y,s,depth))).toVector
  }

  private class Fixture(val d: GpuHostSystemAxi,val shared: Boolean,val lanes: Int,val warps: Int,
    val width: Int,val height: Int,val edge: Int,val pageBytes: Int) {
    val memory=mutable.Map.empty[BigInt,Long]
    var physicalOffset=0L; var phase=""; var copied=0; var firstResult=Option.empty[BigInt]
    val reads=mutable.ArrayBuffer.empty[BigInt]; val writes=mutable.ArrayBuffer.empty[BigInt]
    def physical(a: Long): BigInt=BigInt(a+physicalOffset)
    def put(a: Long,words: Seq[Long]): Unit=words.zipWithIndex.foreach { case (w,i)=>memory(physical(a+i*4L))=w&0xffffffffL }
    def image: Vector[Long]=(for (base <- Vector(colorBase,depthBase); y <- 0 until height; x <- 0 until width)
      yield memory.getOrElse(physical(base+(y*width+x)*4L),0L)).toVector
    def prepare(count: Int=18): Unit = {
      val h=TileBinJobTestData.descriptor(count,programmable=shared).toArray
      h(10)=width.toLong|(height.toLong<<16);h(11)=h(10);h(13)=if(edge==16)4 else 3
      h(14)=((width+edge-1)/edge)*((height+edge-1)/edge); h(17)=binBase+2*pageBytes;h(22)=Integer.numberOfTrailingZeros(pageBytes)
      val pixelBytes=width*height*4L
      for ((i,base) <- Seq((33,colorBase),(39,depthBase))) { h(i+1)=base+pixelBytes;h(i+3)=width*4 }
      if (shared) { h(29)=0x100000;h(30)=0x200000;h(32)=0x80000 }
      put(TileBinJobTestData.descriptorBase,h.toSeq)
      for ((r,i) <- TileBinJobTestData.references(count).zipWithIndex) put(TileBinJobTestData.appendBase+i*32L,r.words)
      for ((draw,i) <- draws.zipWithIndex) put(recordBase+i*128L,primitive(draw))
      val st=state.toArray; if(shared) { st(6)=0x3000;st(8)=0x10000 }
      put(recordBase+32*64L,st.toSeq)
      for (base <- Seq(colorBase,depthBase,0x70000L); i <- 0 until width*height) memory(physical(base+i*4L))=0xdeadbeefL
      def addi(rd: Int,rs: Int,imm: Int): Long=((imm&4095).toLong<<20)|(rs<<15)|(rd<<7)|0x13
      def add(rd: Int,a: Int,b: Int): Long=(b<<20)|(a<<15)|(rd<<7)|0x33
      def slli(rd: Int,rs: Int,n: Int): Long=(n<<20)|(rs<<15)|(1<<12)|(rd<<7)|0x13
      def vle(base: Int,dst: Int): Long=(1<<25)|(base<<15)|(6<<12)|(dst<<7)|7
      def vse(base: Int,src: Int): Long=(1<<25)|(base<<15)|(6<<12)|(src<<7)|0x27
      val capacity=lanes*warps
      def offset(n: Int): Seq[Long]=Seq(addi(7,0,n/16),slli(7,7,4),add(6,5,7))
      val vset=(3L<<30)|(0x10L<<20)|(lanes.toLong<<15)|(7<<12)|0x57
      val program=Seq(slli(5,8,2),add(5,1,5),vset)++offset(12*capacity)++Seq(vle(6,2))++
        offset(24*capacity)++Seq(vse(6,2))++offset(8*capacity)++Seq(vle(6,3))++offset(28*capacity)++Seq(vse(6,3),0x30500073L)
      put(0x3000,program)
      reads.clear();writes.clear();copied=0;firstResult=None
    }
    def pause(): Unit = {
      d.io.m_axi_arready.poke(false.B);d.io.m_axi_awready.poke(false.B);d.io.m_axi_wready.poke(false.B)
      d.io.m_axi_rvalid.poke(false.B);d.io.m_axi_rlast.poke(false.B);d.io.m_axi_bvalid.poke(false.B)
    }
    def read(a: BigInt): BigInt = {
      reads+=a;val base=a & ~BigInt(63)
      (0 until 16).map(i=>BigInt(memory.getOrElse(base+i*4L,0L))<<(i*32)).foldLeft(BigInt(0))(_|_)
    }
    def write(a: BigInt,value: BigInt,mask: BigInt): Unit = {
      val virtual=(a-physicalOffset).toLong
      assert(!(virtual>=TileBinJobTestData.descriptorBase && virtual<TileBinJobTestData.descriptorBase+512) &&
        !(virtual>=TileBinJobTestData.appendBase && virtual<TileBinJobTestData.appendBase+4096) &&
        !(virtual>=recordBase && virtual<recordBase+4096),"host wrote immutable input")
      writes+=a
      if(virtual>=0x70000 && virtual<0x70000+width*height*4L) copied+=mask.bitCount
      for(i <- 0 until 16) {
        val addr=a+i*4L;var word=memory.getOrElse(addr,0L)
        for(b <- 0 until 4 if mask.testBit(4*i+b)) word=(word & ~(255L<<(8*b)))|(((value>>(32*i+8*b)).toLong&255L)<<(8*b))
        memory(addr)=word&0xffffffffL
      }
    }
    def submit(id: Int,opcode: Int=7,bytes: Int=512,flags: Int=0): Unit = {
      axiWrite(d,GpuCommandMmioRegs.COMMAND_ID,id);axiWrite(d,GpuCommandMmioRegs.OPCODE,opcode)
      axiWrite(d,GpuCommandMmioRegs.SOURCE,TileBinJobTestData.descriptorBase.toInt)
      axiWrite(d,GpuCommandMmioRegs.DESTINATION,(TileBinJobTestData.descriptorBase+512).toInt)
      axiWrite(d,GpuCommandMmioRegs.PATTERN,0);axiWrite(d,GpuCommandMmioRegs.BYTES,bytes)
      axiWrite(d,GpuCommandMmioRegs.FLAGS,flags);axiWrite(d,GpuCommandMmioRegs.SUBMIT,1)
    }
    def copy(id: Int): Unit = {
      axiWrite(d,GpuCommandMmioRegs.COMMAND_ID,id);axiWrite(d,GpuCommandMmioRegs.OPCODE,1)
      axiWrite(d,GpuCommandMmioRegs.SOURCE,colorBase.toInt);axiWrite(d,GpuCommandMmioRegs.DESTINATION,0x70000)
      axiWrite(d,GpuCommandMmioRegs.BYTES,width*height*4);axiWrite(d,GpuCommandMmioRegs.FLAGS,0)
      axiWrite(d,GpuCommandMmioRegs.SUBMIT,1)
    }
    def pop(): Unit={axiWrite(d,GpuCommandMmioRegs.COMPLETION_POP,1);axiWrite(d,RenderHostRegs.IRQ,3)}
    def checkResult(id: Int,status: Int,opcode: Int=7): BigInt = {
      val r=axiRead(d,GpuCommandMmioRegs.COMPLETION)
      assert((r&255)==id && ((r>>8)&7)==opcode && ((r>>11)&15)==status && r.testBit(15)==(status==0),s"completion $r")
      r
    }
    def rejectVm(): Unit=for (reg <- Seq(GpuCommandMmioRegs.VECTOR_SATP,GpuCommandMmioRegs.INSTRUCTION_SATP,GpuCommandMmioRegs.TLB_FLUSH))
      axiWrite(d,reg,if(reg==GpuCommandMmioRegs.TLB_FLUSH)1 else 0x80000300,expectedResponse=2)
    def awaitRead(): Unit={var i=0;while(!d.io.m_axi_arvalid.peek().litToBoolean && i<10000){d.io.s_axi_aclk.step();i+=1};d.io.m_axi_arvalid.expect(true.B)}
  }
  for ((shared,count,lanes,warps) <- Seq((false,1,4,2),(true,2,4,2),(true,4,16,8))) {
    it should s"expose GBJ1 on AXI with exact images, queued DMA and VM ownership shared=$shared CUs=$count lanes=$lanes warps=$warps" in {
      val width=if(lanes==16)32 else 16;val height=if(lanes==16)16 else 8;val edge=if(lanes==16)16 else 8
      val gpu=GpuConfig(lanes=lanes,warps=warps,l2Sets=8,l2Ways=2,l2StoreEntries=4)
      val graphics=cfg.copy(screenWidth=width,screenHeight=height,fragmentBatchLimit=math.max(8,lanes))
      val m=MobileGpuConfig(execution=gpu,computeUnits=count,axiBytes=32,binPageBytes=if(lanes==16)4096 else 128,
        binWriteCredits=if(lanes==16)8 else 2,attachmentStoreCredits=if(lanes==16)8 else 2,binTileSlots=if(lanes==16)8 else 4)
      val scene=TileBinJobTestData.references(18).map(r=>draws(((r.primitive-firstHandle)/2).toInt)
        .copy(scissor=Some((r.tile.toInt*edge,0,r.tile.toInt*edge+edge,height))))
      val expected=reference(0,width,height,scene,width=width,height=height)
      simulate(new GpuHostSystemAxi(graphics,gpu,numComputeUnits=count,fragCore=shared,sharedGraphics=shared,
        parallelGraphics=shared,enableFpuBackend=shared,memoryAxiDataBytes=32,exposePerformance=true,binnedRendering=Some(m))) { d=>
        initialize(d);val f=new Fixture(d,shared,lanes,warps,width,height,edge,m.binPageBytes)
        val caps=axiRead(d,RenderHostRegs.CAPABILITIES)
        assert(!caps.testBit(GpuCapabilities.UnifiedRender) && !caps.testBit(GpuCapabilities.PersistentTileJobs) && !caps.testBit(GpuCapabilities.TypedTileCommands))
        assert(caps.testBit(GpuCapabilities.ProtectedVmState) && caps.testBit(GpuCapabilities.FragmentCore)==shared)
        assert(axiRead(d,TileBinHostRegs.DISCOVERY)==TileBinHostRegs.CAPABILITIES)
        axiWrite(d,TileBinHostRegs.DISCOVERY,0,expectedResponse=2)
        assert(axiRead(d,TileBinHostRegs.CAPABILITIES)==0x47424301L)
        assert(axiRead(d,TileBinHostRegs.CAPABILITIES+8)==0x47424a01L)
        assert(axiRead(d,TileBinHostRegs.CAPABILITIES+12)==0x00200200L)
        axiWrite(d,TileBinHostRegs.CAPABILITIES,0,expectedResponse=2)
        assert(axiRead(d,TileBinHostRegs.CAPABILITIES)==0x47424301L)
        axiWrite(d,RenderHostRegs.IRQ,1)
        for ((offset,run) <- Seq(0L,0x400000L).zipWithIndex) {
          f.physicalOffset=offset
          if(offset!=0) {
            f.memory(BigInt(0x300000))=(0x400L<<10)|0xcfL
            axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0x80000300)
            axiWrite(d,GpuCommandMmioRegs.INSTRUCTION_SATP,0x80000300);axiWrite(d,GpuCommandMmioRegs.TLB_FLUSH,1)
          }
          f.prepare();f.submit(37+run*2);f.awaitRead()
          val held=d.io.m_axi_araddr.peek().litValue;f.rejectVm();d.io.m_axi_araddr.expect(held.U)
          // A legacy DMA register kick must also be rejected during residency.
          axiWrite(d,RenderHostRegs.CLEAR_START,1,expectedResponse=2)
          f.copy(38+run*2)
          var observed=false
          serviceMemoryMaster(d,f.read,writeAckDelay=23,readResponseDelay=5,maxCycles=200000,
            onWriteAccepted=a=>if(a-offset>=0x70000 && a-offset<0x70000+width*height*4L && !observed) {
              assert(f.image==expected,"queued DMA began before the final image was exported")
              f.checkResult(37+run*2,0);assert(axiRead(d,GpuCommandMmioRegs.COMPLETION_BYTES_LO)==576)
              f.rejectVm();f.pop();observed=true
            })(f.write){observed && d.io.m_irq.peek().litToBoolean}
          f.pause();f.checkResult(38+run*2,0,1)
          assert(f.image==expected && f.copied==width*height*4)
          assert((0 until width*height).forall(i=>f.memory(f.physical(0x70000+i*4L))==expected(i)))
          val stable=axiRead(d,GpuCommandMmioRegs.COMPLETION);f.rejectVm()
          assert(axiRead(d,GpuCommandMmioRegs.COMPLETION)==stable);f.pop()
        }
        if(shared)assert(d.io.performance.get.system.maxActiveComputeUnits.peek().litValue==count)
        info(s"BIN_HOST_AXI shared=$shared CUs=$count lanes=$lanes warps=$warps exactWords=${expected.size} submissions=2 queuedCopies=2")
      }
    }
  }

  it should "publish GPU-produced bin inputs and shader code through physical aliases after acknowledged completion" in {
    val gpu=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2,l2StoreEntries=4)
    val mobile=MobileGpuConfig(execution=gpu,computeUnits=2,axiBytes=32,binPageBytes=128,
      binWriteCredits=2,attachmentStoreCredits=2,binTileSlots=4)
    val newColor=0x88442280L
    val changed=draws.zipWithIndex.map { case (draw,i)=>draw.copy(color=newColor,depth=0x200000L+i*0x100000L,
      clip=draw.clip.map { case (x,y,z,w)=>(-x,y,z,w) }) }
    def scene(ds: Vector[Draw],count: Int)=TileBinJobTestData.references(count).map(r=>
      ds(((r.primitive-firstHandle)/2).toInt).copy(scissor=Some((r.tile.toInt*8,0,r.tile.toInt*8+8,8))))
    val oldExpected=reference(0,16,8,scene(draws,18))
    val newExpected=reference(0,16,8,scene(changed,20))
    assert(newExpected!=oldExpected,"publication must change the complete image")
    simulate(new GpuHostSystemAxi(cfg,gpu,numComputeUnits=2,fragCore=true,sharedGraphics=true,
      parallelGraphics=true,enableFpuBackend=true,memoryAxiDataBytes=32,exposePerformance=true,
      binnedRendering=Some(mobile))) { d=>
      initialize(d);val f=new Fixture(d,true,4,2,16,8,8,128);f.physicalOffset=0x400000L;f.prepare()
      // Two distinct VA superpages name the same physical backing. The GPU
      // producer writes the upper alias; descriptor/record/shader readers use
      // the lower alias. These are deliberate aliases of the SAME resource,
      // not overlapping simultaneously owned writable render allocations.
      for(offset <- Seq(0,4))f.memory(BigInt(0x300000+offset))=(0x400L<<10)|0xcfL
      axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0x80000300)
      axiWrite(d,GpuCommandMmioRegs.INSTRUCTION_SATP,0x80000300);axiWrite(d,GpuCommandMmioRegs.TLB_FLUSH,1)
      axiWrite(d,RenderHostRegs.IRQ,1)
      f.submit(61)
      serviceMemoryMaster(d,f.read,writeAckDelay=19,readResponseDelay=3,maxCycles=100000)(f.write){d.io.m_irq.peek().litToBoolean}
      f.pause();f.checkResult(61,0);assert(f.image==oldExpected);f.pop()
      val oldMemory=f.memory.toMap
      // Build immutable source packets off-line, then restore every original
      // target byte. Only the actual CU program below updates the live inputs.
      f.prepare(20)
      for((draw,i) <- changed.zipWithIndex)f.put(recordBase+i*128L,primitive(draw))
      def addi(rd: Int,rs: Int,n: Int): Long=((n&4095).toLong<<20)|(rs<<15)|(rd<<7)|0x13L
      def lui(rd: Int,n: Long): Long=(n&0xfffff000L)|(rd<<7)|0x37L
      def li(rd: Int,n: Long): Seq[Long]={val hi=(n+2048)&0xfffff000L;Seq(lui(rd,hi),addi(rd,rd,(n-hi).toInt))}
      def add(rd: Int,a: Int,b: Int): Long=(b<<20)|(a<<15)|(rd<<7)|0x33L
      def slli(rd: Int,rs: Int,n: Int): Long=(n<<20)|(rs<<15)|(1<<12)|(rd<<7)|0x13L
      def vle(base: Int,dst: Int): Long=(1<<25)|(base<<15)|(6<<12)|(dst<<7)|7L
      def vse(base: Int,src: Int): Long=(1<<25)|(base<<15)|(6<<12)|(src<<7)|0x27L
      val vset=0xc1027057L
      def shaderOffset(n: Int): Seq[Long]=Seq(addi(7,0,n/16),slli(7,7,4),add(6,5,7))
      val shader=Seq(slli(5,8,2),add(5,1,5),vset)++li(9,newColor)++
        Seq(0x5e04c157L)++shaderOffset(24*8)++Seq(vse(6,2))++
        shaderOffset(8*8)++Seq(vle(6,3))++shaderOffset(28*8)++Seq(vse(6,3),0x30500073L)
      f.put(0x3000,(Seq(addi(0,0,0))++shader).padTo(32,0L))
      val newState=state.toArray;newState(6)=0x3004;newState(8)=0x10000
      f.put(recordBase+32*64L,newState.toSeq)
      val regions=Vector((TileBinJobTestData.descriptorBase,512),(TileBinJobTestData.appendBase,640),
        (recordBase,384),(recordBase+32*64L,64),(0x3000L,128))
      val packets=regions.map { case (base,bytes)=>(base,Vector.tabulate(bytes/4)(i=>f.memory.getOrElse(f.physical(base+4L*i),0L))) }
      f.memory.clear();f.memory++=oldMemory
      val sourceBase=0x200000L;val producerPc=0x5000L
      val copy=Vector.newBuilder[Long];copy+=vset
      for(((target,words),i) <- packets.zipWithIndex) {
        val source=sourceBase+i*4096L;f.put(source,words)
        copy++=li(5,source);copy++=li(6,target+0x400000L)
        for(_ <- words.indices by 4)copy++=Seq(vle(5,2),vse(6,2),addi(5,5,16),addi(6,6,16))
      }
      copy+=0x30500073L;val producer=copy.result();assert(producer.size<=512)
      f.put(producerPc,producer)
      for((reg,value) <- Seq(GpuCommandMmioRegs.COMMAND_ID->62,GpuCommandMmioRegs.OPCODE->0,
        GpuCommandMmioRegs.KERNEL_PC->producerPc.toInt,GpuCommandMmioRegs.KERNARG->0,
        GpuCommandMmioRegs.GRID_X->1,GpuCommandMmioRegs.GRID_Y->1,GpuCommandMmioRegs.GRID_Z->1,
        GpuCommandMmioRegs.LOCAL_X->4,GpuCommandMmioRegs.LOCAL_Y->1,GpuCommandMmioRegs.LOCAL_Z->1,
        GpuCommandMmioRegs.FLAGS->0,GpuCommandMmioRegs.SUBMIT->1))axiWrite(d,reg,value)
      var written=0;var offered=0;var acknowledged=0;var inFlight=0;var checkedPending=false
      val totalBytes=regions.map(_._2).sum
      def producerWrite(a: BigInt,value: BigInt,mask: BigInt): Unit={
        assert(regions.exists { case(base,bytes)=>a>=f.physical(base) && a<f.physical(base+bytes) },s"unexpected producer write $a")
        written+=mask.bitCount
        for(i <- 0 until 16) {
          val address=a+i*4L;var word=f.memory.getOrElse(address,0L)
          for(b <- 0 until 4 if mask.testBit(i*4+b))word=(word & ~(255L<<(8*b)))|(((value>>(32*i+8*b)).toLong&255L)<<(8*b))
          f.memory(address)=word&0xffffffffL
        }
      }
      serviceMemoryMaster(d,f.read,writeAckDelay=97,readResponseDelay=5,maxCycles=100000,
        onCycle=()=>{
          if(d.io.m_axi_awvalid.peek().litToBoolean && d.io.m_axi_awready.peek().litToBoolean){offered+=1;inFlight+=1}
          if(d.io.m_axi_bvalid.peek().litToBoolean && d.io.m_axi_bready.peek().litToBoolean){acknowledged+=1;inFlight-=1}
          assert(inFlight>=0)
        },onWriteAccepted=_=>if(!checkedPending){
          assert(inFlight>0);f.rejectVm();d.io.s_axi_aclk.step(41)
          assert(!d.io.m_irq.peek().litToBoolean,"producer completed before its first lower-memory acknowledgement")
          checkedPending=true
        })(producerWrite){d.io.m_irq.peek().litToBoolean}
      f.pause();f.checkResult(62,0,0)
      assert(checkedPending && written==totalBytes && inFlight==0 && offered==acknowledged)
      for((base,words) <- packets)assert(words.indices.forall(i=>f.memory(f.physical(base+i*4L))==words(i)),s"GPU packet mismatch at $base")
      f.pop()
      // Physical publication ranges bypass the current VA context. Neither
      // a rejected wrap nor a valid two-line invalidate may issue DRAM I/O.
      val publicationReads=f.reads.size;val publicationWrites=f.writes.size
      // Keep the valid range outside every input and executable packet: an
      // invalidate of the replaced code could mask stale L2 publication.
      for((id,address,bytes,status,alignedBytes) <- Seq((64,0xffffffc0L,65,1,0),(65,0x47803fL,2,0,128))) {
        for((reg,value) <- Seq(GpuCommandMmioRegs.COMMAND_ID->id,GpuCommandMmioRegs.OPCODE->5,
          GpuCommandMmioRegs.SOURCE->address.toInt,GpuCommandMmioRegs.BYTES->bytes,
          GpuCommandMmioRegs.FLAGS->0,GpuCommandMmioRegs.SUBMIT->1))axiWrite(d,reg,value)
        serviceMemoryMaster(d,f.read,maxCycles=10000)(f.write){d.io.m_irq.peek().litToBoolean}
        f.pause();f.checkResult(id,status,5)
        assert(axiRead(d,GpuCommandMmioRegs.COMPLETION_BYTES_LO)==alignedBytes)
        assert(f.reads.size==publicationReads && f.writes.size==publicationWrites,"physical publication issued lower-memory traffic")
        f.pop()
      }
      // Kernel completion publishes write-through data. Replaced executable
      // bytes additionally require the idle-only instruction/TLB flush, since
      // I-caches are not snooped by data stores. No L2 invalidate is needed for
      // these coherent GPU stores; CPU/noncoherent publication is separate.
      axiWrite(d,GpuCommandMmioRegs.TLB_FLUSH,1)
      val codeReadsBefore=f.reads.count(a=>a>=f.physical(0x3000) && a<f.physical(0x3080))
      f.submit(63)
      serviceMemoryMaster(d,f.read,writeAckDelay=23,readResponseDelay=5,maxCycles=100000)(f.write){d.io.m_irq.peek().litToBoolean}
      f.pause();f.checkResult(63,0);assert(axiRead(d,GpuCommandMmioRegs.COMPLETION_BYTES_LO)==640)
      assert(f.image==newExpected,"dependent renderer used stale GPU-produced inputs or shader instructions")
      assert(f.reads.count(a=>a>=f.physical(0x3000) && a<f.physical(0x3080))>codeReadsBefore)
      f.pop();axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0);axiWrite(d,GpuCommandMmioRegs.INSTRUCTION_SATP,0)
      info(s"BIN_GPU_PUBLICATION Sv32=true alias=true bytes=$written writes=$offered acknowledgements=$acknowledged producerWords=${producer.size} exactWords=${newExpected.size} replacedCode=true physicalInvalidate=true wrapRejected=true")
    }
  }

  it should "attribute input and shader faults on the unified queue and recover in fresh VMs" in {
    val gpu=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2,l2StoreEntries=4)
    val m=MobileGpuConfig(execution=gpu,computeUnits=2,axiBytes=32,binPageBytes=128,binWriteCredits=2,
      attachmentStoreCredits=2,binTileSlots=4)
    val expected=reference(0,16,8,TileBinJobTestData.references(18).map(r=>draws(((r.primitive-firstHandle)/2).toInt)
      .copy(scissor=Some((r.tile.toInt*8,0,r.tile.toInt*8+8,8)))))
    simulate(new GpuHostSystemAxi(cfg.copy(fragmentBatchLimit=8),gpu,numComputeUnits=2,fragCore=true,
      sharedGraphics=true,parallelGraphics=true,enableFpuBackend=true,memoryAxiDataBytes=32,binnedRendering=Some(m))) { d=>
      initialize(d);val f=new Fixture(d,true,4,2,16,8,8,128);axiWrite(d,RenderHostRegs.IRQ,1)
      val scenarios=Seq(("old-size",1,7,256,0),("flags",1,7,512,1),("old-opcode",15,6,512,0),
        ("descriptor-fault",4,7,512,0),("append-fault",4,7,512,0),("shader-fault",11,7,512,0),("healthy-reuse",0,7,512,0))
      for (((name,status,opcode,bytes,flags),run) <- scenarios.zipWithIndex) {
        f.physicalOffset=(run+1L)*0x400000;val root=0x300000L+run*4096L
        f.memory(BigInt(root))=((f.physicalOffset>>12)<<10)|0xcfL
        val satp=0x80000000L|((run+1L)<<22)|(root>>12)
        axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,satp.toInt);axiWrite(d,GpuCommandMmioRegs.INSTRUCTION_SATP,satp.toInt)
        axiWrite(d,GpuCommandMmioRegs.TLB_FLUSH,1)
        f.prepare();if(name=="shader-fault")f.put(0x3000,Seq(0xffffffffL))
        f.submit(64+run,opcode,bytes,flags)
        val bad=if(name=="descriptor-fault")Some(f.physical(TileBinJobTestData.descriptorBase))
          else if(name=="append-fault")Some(f.physical(TileBinJobTestData.appendBase+64)) else None
        var faults=0
        serviceMemoryMaster(d,f.read,a=>{val hit=bad.contains(a);if(hit)faults+=1;hit},
          readResponseDelay=7,writeAckDelay=19,maxCycles=200000)(f.write){d.io.m_irq.peek().litToBoolean}
        f.pause();val result=f.checkResult(64+run,status,opcode)
        if(bad.nonEmpty)assert(faults>0)
        if(Set("old-size","flags","old-opcode")(name))assert(f.reads.isEmpty && f.writes.isEmpty)
        if(name=="healthy-reuse")assert(f.image==expected)
        f.rejectVm();d.io.s_axi_aclk.step(5);assert(axiRead(d,GpuCommandMmioRegs.COMPLETION)==result);f.pop()
      }
      f.put(0x3500,Seq(0x30500073L));axiWrite(d,GpuCommandMmioRegs.KERNEL_PC,0x3500)
      f.submit(110,opcode=0)
      serviceMemoryMaster(d,f.read,maxCycles=200000)(f.write){d.io.m_irq.peek().litToBoolean}
      f.pause();f.checkResult(110,0,0);f.pop()
      info("BIN_HOST_FAULTS scenarios=7 freshVMs=true inputFault=4 shaderFault=11 healthyReuse=true computeCommand=true")
    }
  }

  it should "drain descriptor and attachment ownership before safe reset and discard old completions" in {
    val gpu=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2,l2StoreEntries=4)
    val m=MobileGpuConfig(execution=gpu,computeUnits=2,axiBytes=32,binPageBytes=128,binWriteCredits=2,
      attachmentStoreCredits=2,binTileSlots=4)
    simulate(new GpuHostSystemAxi(cfg.copy(fragmentBatchLimit=8),gpu,numComputeUnits=2,fragCore=true,
      sharedGraphics=true,parallelGraphics=true,enableFpuBackend=true,memoryAxiDataBytes=32,binnedRendering=Some(m))) { d=>
      initialize(d);val f=new Fixture(d,true,4,2,16,8,8,128);axiWrite(d,RenderHostRegs.IRQ,1)
      for ((stage,run) <- Seq("descriptor","attachment").zipWithIndex) {
        f.prepare();f.submit(80+run);f.copy(90+run)
        var requested=false;var faulted=false
        def resetNow(): Unit=if(!requested) {requested=true;axiWrite(d,GpuCommandMmioRegs.RESET,1);f.rejectVm()}
        serviceMemoryMaster(d,f.read,
          a=>if(stage=="descriptor" && a==f.physical(TileBinJobTestData.descriptorBase)){faulted=true;true}else false,
          readResponseDelay=29,writeAckDelay=43,maxCycles=200000,
          onReadAccepted=a=>if(stage=="descriptor" && a==f.physical(TileBinJobTestData.descriptorBase))resetNow(),
          onWriteAccepted=a=>if(stage=="attachment" && a>=colorBase && a<colorBase+512)resetNow(),
          writeFault=(a,_,_)=>if(stage=="attachment" && a>=colorBase && a<colorBase+512){faulted=true;true}else false
        )(f.write){requested && d.io.m_irq.peek().litToBoolean}
        f.pause();assert(requested && faulted)
        val status=axiRead(d,GpuCommandMmioRegs.STATUS)
        assert((status&10)==0,"reset must drain the old engine and clear its software result")
        assert(f.copied==0,"queued DMA escaped the reset stream")
        axiWrite(d,RenderHostRegs.IRQ,3)
        axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0);axiWrite(d,GpuCommandMmioRegs.INSTRUCTION_SATP,0)
        axiWrite(d,GpuCommandMmioRegs.TLB_FLUSH,1)
      }
      f.prepare(0);f.submit(99)
      serviceMemoryMaster(d,f.read,writeAckDelay=11,maxCycles=200000)(f.write){d.io.m_irq.peek().litToBoolean}
      f.pause();f.checkResult(99,0);f.pop()
      assert(f.image.take(128).forall(_==colorClear) && f.image.drop(128).forall(_==depthClear))
      info("BIN_HOST_RESET descriptorAndAttachment=true lateFaultsDrained=true queuedDMAAborted=true healthyReuse=true")
    }
  }
}
