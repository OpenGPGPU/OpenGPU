package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.system.{AxiTrafficObserver, GpuHostSystemAxi, GpuHostTestSupport}
import opengpu.testutil.{AxiTrafficCounters, GraphicsTestResolution}
import opengpu.testutil.GpuSim._
import org.scalatest.DoNotDiscover
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** Explicit local coherence qualification. The GPU renders the texture and
  * consumes it again with unchanged descriptors, identity and physical backing.
  * This bounded profile does not qualify product capacity, clock or frame rate.
  */
@DoNotDiscover
class NativeRenderTextureVisibilityQualification extends AnyFlatSpec with GpuHostTestSupport {
  private val gpu = GpuConfig(lanes=4, warps=2, l2Sets=8, l2Ways=2)
  private val vertex = VertexShaderStageConfig(VertexInputFetchConfig(3, 1), 2)
  private val fragment = FragmentShaderConfig(2, 4)
  private val graphics = GraphicsConfig(screenWidth=GraphicsTestResolution.width,
    screenHeight=GraphicsTestResolution.height)
  private val native = NativeBinnedGraphicsConfig(vertex, fragment, engines=2,
    pageBytes=128, writeCredits=2, tileSlots=1, pagedCapacity=8,
    payload=Some(ShaderPayloadConfig(capacityBytes=4096, maxPayloadBytes=1024, owners=3, ports=8)),
    translateImageViews=true, groupedTexture=true)
  private def model = new GpuHostSystemAxi(graphics, gpu, numComputeUnits=4,
    fragCore=true, sharedGraphics=true, parallelGraphics=true, enableFpuBackend=true, memoryAxiDataBytes=32,
    instructionCacheSets=4, instructionCacheWays=1, vectorCacheSets=4, vectorCacheWays=1,
    exposePerformance=true, nativeBinnedGraphics=Some(native))

  private class Inputs(mode: Int) {
    val width=9; val height=4; val samples=1<<mode
    require(width<=GraphicsTestResolution.width && height<=GraphicsTestResolution.height)
    val memory=mutable.Map.empty[BigInt, Int]
    val mapped=mutable.Set.empty[BigInt]
    def pa(va: BigInt): BigInt = va+0x400000
    def map(base: BigInt, bytes: Int): Unit =
      for(a <- base until base+bytes by 64) mapped += a
    def put(a: BigInt, value: BigInt): Unit =
      for(b <- 0 until 4) memory(a+b)=((value>>(8*b))&255).toInt
    def word(a: BigInt): BigInt =
      (0 until 4).foldLeft(BigInt(0))((v,b)=>v|(BigInt(memory.getOrElse(a+b,0))<<(8*b)))
    def upload(a: BigInt, value: BigInt): Unit = put(pa(a), value)
    def line(address: BigInt): BigInt = {
      val base=address & ~BigInt(63)
      (0 until 64).foldLeft(BigInt(0))((v,b)=>v|(BigInt(memory.getOrElse(base+b,0))<<(8*b)))
    }
    map(0x30000, 4096); put(0x30004, ((BigInt(0x800000)>>12)<<10)|0xcf)

    case class Plane(base: BigInt, pitch: Int, count: Int) {
      map(pa(base),4096)
      for(a <- pa(base) until pa(base)+4096) memory(a)=0xa7
      def address(x: Int,y: Int,s: Int): BigInt = pa(base)+y*pitch+(x*count+s)*4
      def fill(raw: BigInt): Unit = for(y<-0 until height;x<-0 until width;s<-0 until count) put(address(x,y,s),raw)
      def verify(raw: BigInt): Unit = {
        for(y<-0 until height;x<-0 until width;s<-0 until count)
          assert(word(address(x,y,s))==raw,s"render/texture mismatch base=$base x=$x y=$y sample=$s")
        for(offset<-0 until 4096) {
          val row=offset/pitch; val col=offset%pitch
          if(row>=height || col>=width*count*4)
            assert(memory(pa(base)+offset)==0xa7,s"pitched image guard changed base=$base offset=$offset")
        }
      }
    }
    val pitch=((width*samples*4+63)/64)*64
    val produced=Plane(0x500000,pitch,samples); val producedResolve=Plane(0x508000,64,1)
    val consumed=Plane(0x510000,pitch,samples); val consumedResolve=Plane(0x518000,64,1)
    val texture=if(mode==0)produced else producedResolve
    val oldRaw=BigInt(0xff123456L)
    produced.fill(oldRaw); producedResolve.fill(oldRaw)
    val newColor=Vector(0.25f,0.5f,0.75f,1f)
    val newRaw=TypedColorReference.encode("RGBA8",newColor.map(x=>TypedColorReference.fp(x.toDouble)))._1
    val identity=Seq[BigInt](7,8,9)
    def ids(prefix: String): Map[String,BigInt] =
      Seq("context","pass","generation").zip(identity).map{case(k,v)=>(prefix+k)->v}.toMap
    def view(prefix: String, base: BigInt, bytes: Int): Map[String,BigInt] = Map(
      prefix+"base"->base,prefix+"limit"->(base+bytes),prefix+"allocationBase"->base,
      prefix+"allocationLimit"->(base+((bytes+63)& ~63)))
    def record(base: BigInt, words: Int, magic: BigInt,
      fields: Seq[NativeTypedGraphicsLayout.Field], values: Map[String,BigInt]): Unit = {
      require(values.keySet.subsetOf(fields.map(_.name).toSet),s"unknown record fields ${values.keySet--fields.map(_.name)}")
      for(i<-0 until words)upload(base+i*4,0)
      upload(base,magic); upload(base+4,words*4); upload(base+8,1)
      for(f<-fields) {
        val value=values.getOrElse(f.name,BigInt(0))
        require(value>=0 && value.bitLength<=f.width,s"${f.name} exceeds its field")
        for(i<-0 until f.words)upload(base+(f.word+i)*4,(value>>(i*32))&0xffffffffL)
      }
    }
    val header=Map[String,BigInt]("header.attributes"->16,"header.bindings"->16,
      "header.varyings"->16,"header.colors"->4,"header.engineViews"->4)
    val sourceRoot=BigInt(0x470000)
    map(pa(sourceRoot),0x30000)
    NativeTextureFetchTestData.roots(upload,stage=1,id=identity,offset=sourceRoot)
    NativeTextureFetchTestData.install(upload,(a,b)=>memory(pa(a))=b,0,
      format=ImageFormatReference.names.indexOf("RGBA8"),stage=1,id=identity,offset=sourceRoot)
    val image=sourceRoot+NativeTextureFetchTestData.images
    val level=sourceRoot+NativeTextureFetchTestData.levels
    upload(image+11*4,width); upload(image+12*4,height)
    upload(image+15*4,texture.base); upload(image+16*4,texture.base+4096)
    upload(level+9*4,texture.base); upload(level+10*4,texture.base+4096)
    upload(level+12*4,texture.base); upload(level+13*4,width); upload(level+14*4,height); upload(level+17*4,texture.pitch)

    import NativeProductWorkloads.{Vertex,Triangle,Draw}
    val a=Vertex(-1,-1,0,1,0.875f,0.875f); val b=Vertex(1,-1,0,1,0.875f,0.875f)
    val c=Vertex(-1,1,0,1,0.875f,0.875f); val e=Vertex(1,1,0,1,0.875f,0.875f)
    val triangles=Vector(Triangle(a,b,c),Triangle(e,c,b))
    def frame(region: BigInt,target: Plane,resolve: Plane,textured: Boolean): BigInt = {
      map(pa(region),65536)
      val draw=Draw(triangles,if(textured)Vector.fill(4)(1f)else newColor,textured=textured)
      val vertices=NativeProductVertexInputs.encode(draw,region+0x4000)
      vertices.bytes.zipWithIndex.foreach{case(v,i)=>memory(pa(vertices.base)+i)=v&255}
      val vp=region+0x6000; val fp=region+0x7000
      NativeProductShaderPrograms.vertex(4,2).zipWithIndex.foreach{case(v,i)=>upload(vp+i*4,v)}
      NativeProductShaderPrograms.fragment(4,2,textured).zipWithIndex.foreach{case(v,i)=>upload(fp+i*4,v)}
      val vertexBytes=vertex.prefixBytes(8,3,2); val fragmentBytes=fragment.prefixBytes(8,2,1)
      val sf=Map[String,BigInt]("version"->1,"engineCount"->2,"fragment.version"->1,
        "fragment.shaderPc"->fp,"fragment.varyingSlots"->2,"fragment.colorSlots"->1,"fragment.colorMask"->1,
        "fragment.sampleMode"->mode,"fragment.sampler.texWidth"->1,"fragment.sampler.texHeight"->1,
        "fragment.resources.version"->(if(textured)1 else 0),
        "fragment.resources.address"->(if(textured)sourceRoot+NativeTextureFetchTestData.root else BigInt(0)),
        "tests.version"->1,"blend.version"->1)++ids("fragment.identity.")++
        view("fragment.scratch.",region+0x8000,fragmentBytes)++view("scratch[0].",region+0x9000,fragmentBytes)
      record(region+0x3000,128,TypedFragmentStateLayout.magic,
        TypedFragmentStateLayout.fields(new TypedFragmentState(fragment)),sf)
      Seq[BigInt](4,7,8,9,0).zipWithIndex.foreach{case(v,i)=>upload(region+0x3000+(i+3)*4,v)}
      val fv=header++ids("frame.bins.producer.arena.identity.")++ids("frame.bins.bins.pool.identity.")++
        ids("frame.states.identity.")++ids("frame.image.pixels.colors.identity.")++Map[String,BigInt](
        "frame.version"->1,"frame.bins.producer.version"->1,"frame.bins.producer.arena.version"->1,
        "frame.bins.producer.arena.base"->(region+0xa000),"frame.bins.producer.arena.limit"->(region+0xc000),
        "frame.bins.producer.screenWidth"->width,"frame.bins.producer.screenHeight"->height,"frame.bins.producer.tileLog2"->3,
        "frame.bins.bins.version"->3,"frame.bins.bins.tileCount"->2,
        "frame.bins.bins.directoryBase"->(region+0xe000),"frame.bins.bins.directoryLimit"->(region+0xe080),
        "frame.bins.bins.pool.version"->1,"frame.bins.bins.pool.base"->(region+0xc000),
        "frame.bins.bins.pool.limit"->(region+0xd000),"frame.bins.bins.pool.pageSizeLog2"->7,
        "frame.states.version"->1,"frame.states.base"->(region+0x3000),"frame.states.limit"->(region+0x3200),
        "frame.scratchStride"->4096,"frame.scratch[0].base"->(region+0x8000),"frame.scratch[0].limit"->(region+0x9000),
        "frame.scratch[1].base"->(region+0x9000),"frame.scratch[1].limit"->(region+0xa000),
        "frame.image.version"->1,"frame.image.pixels.version"->1,"frame.image.pixels.colors.version"->1,
        "frame.image.pixels.colors.tileLog2"->3,"frame.image.pixels.colors.tileWidth"->8,"frame.image.pixels.colors.tileHeight"->8,
        "frame.image.pixels.colors.sampleMode"->mode,"frame.image.pixels.colors.colors[0].enabled"->1,
        "frame.image.pixels.colors.colors[0].format"->TypedColorFormat.id("RGBA8"),
        "frame.image.pixels.colors.colors[0].channelMask"->15,"frame.image.pixels.colors.colors[0].loadOp"->1)
      def imageView(prefix: String,p: Plane,readable: Boolean): Map[String,BigInt] = view(prefix,p.base,(height-1)*p.pitch+width*p.count*4)++Map(
        prefix+"allocationLimit"->(p.base+4096),prefix+"imageWidth"->BigInt(width),prefix+"imageHeight"->BigInt(height),
        prefix+"rowPitch"->BigInt(p.pitch),prefix+"readable"->BigInt(if(readable)1 else 0),prefix+"writable"->BigInt(1),prefix+"store"->BigInt(1))
      val images=fv++imageView("frame.image.views[0].",target,readable=true)++
        (if(mode==2)imageView("frame.image.resolves[0].destination.",resolve,readable=false)+("frame.image.resolves[0].enable"->BigInt(1))else Map.empty[String,BigInt])
      record(region+0x1000,512,NativeBinnedGraphicsLayout.frameMagic,
        NativeBinnedGraphicsLayout.frameFields(new NativeBinnedGraphicsFrameRecord),images)
      val dv=header++vertices.fields++ids("draw.vertex.geometry.vertex.input.identity.")++Map[String,BigInt](
        "draw.version"->1,"draw.vertex.viewport.version"->1,"draw.vertex.viewport.extentX"->width,
        "draw.vertex.viewport.extentY"->height,"draw.vertex.viewport.farDepth"->TypedColorReference.fp(1),
        "draw.vertex.geometry.topology"->4,"draw.vertex.geometry.vertex.version"->1,
        "draw.vertex.geometry.vertex.shaderPc"->vp,"draw.vertex.geometry.vertex.attributeSlots"->3,
        "draw.vertex.geometry.vertex.varyingSlots"->2,"draw.vertex.geometry.vertex.varyingMask"->255,
        "draw.vertex.geometry.vertex.sampler.texWidth"->1,"draw.vertex.geometry.vertex.sampler.texHeight"->1,
        "draw.raster.version"->1,"draw.raster.screenW"->width,"draw.raster.screenH"->height,
        "draw.raster.sampleMode"->mode,"draw.raster.frontFaceCCW"->1)++view("draw.vertex.geometry.vertex.scratch.",region+0x5000,vertexBytes)
      record(region+0x2000,512,NativeBinnedGraphicsLayout.drawMagic,
        NativeBinnedGraphicsLayout.drawFields(new NativeBinnedGraphicsDrawRecord),dv)
      record(region,128,NativeBinnedGraphicsLayout.jobMagic,
        NativeBinnedGraphicsLayout.jobFields(new NativeBinnedGraphicsJobRecord),header++ids("job.identity.")++Map[String,BigInt](
        "job.frameBase"->(region+0x1000),"job.frameLimit"->(region+0x1800),"job.drawBase"->(region+0x2000),
        "job.drawLimit"->(region+0x2800),"job.drawCount"->1,"job.drawStride"->2048))
      region
    }
    val producer=frame(0x410000,produced,producedResolve,textured=false)
    val consumer=frame(0x430000,consumed,consumedResolve,textured=true)
  }
  def validateInputs(): Unit = {
    for(mode<-Seq(0,2)) {
      val input=new Inputs(mode)
      assert(input.word(input.texture.address(7,3,0))==input.oldRaw)
      assert(input.mapped(input.pa(input.producer)) && input.mapped(input.pa(input.consumer)))
    }
  }

  for (mode <- Seq(0, 2))
  it should s"publish a rendered texture before a queued native consumer with delayed physical stores sampleMode=$mode" in {
    require(!GraphicsTestResolution.productQualification, "this is a bounded development qualification")
    val input=new Inputs(mode)
    simulate(model) { d =>
      initialize(d)
      import input._
      def park(): Unit = {
        d.io.m_axi_arready.poke(false.B); d.io.m_axi_awready.poke(false.B); d.io.m_axi_wready.poke(false.B)
        d.io.m_axi_rvalid.poke(false.B); d.io.m_axi_bvalid.poke(false.B)
      }
      axiWrite(d, GpuCommandMmioRegs.VECTOR_SATP, 0x80000030)
      axiWrite(d, GpuCommandMmioRegs.INSTRUCTION_SATP, 0x80000030)
      axiWrite(d, GpuCommandMmioRegs.TLB_FLUSH, 1)
      axiWrite(d, RenderHostRegs.IRQ, 1)
      def submit(job: BigInt,id: Int): Unit = {
        park(); axiWrite(d,GpuCommandMmioRegs.COMMAND_ID,id); axiWrite(d,GpuCommandMmioRegs.OPCODE,7)
        axiWrite(d,GpuCommandMmioRegs.SOURCE,job.toInt); axiWrite(d,GpuCommandMmioRegs.DESTINATION,(job+512).toInt)
        axiWrite(d,GpuCommandMmioRegs.BYTES,512); axiWrite(d,GpuCommandMmioRegs.PATTERN,0); axiWrite(d,GpuCommandMmioRegs.SUBMIT,1)
      }
      val perf=d.io.performance.get
      def run(ids: Vector[Int],queueConsumer: Boolean): Unit = {
        val physical=mutable.Queue.empty[(BigInt,BigInt,BigInt)]
        val traffic=new AxiTrafficCounters; val observer=new AxiTrafficObserver(d.io,traffic)
        var completed=0; var queued=false
        val groups=perf.nativeTextureGroups.peek().litValue
        serviceMemoryMaster(d,line,readFault=a=> !mapped(a & ~BigInt(63)),
          writeFault=(a,_,_)=> !mapped(a),writeAckDelay=47,readResponseDelay=5,maxCycles=1000000,
          onCycle=()=>{
            observer.sample()
            if(d.io.m_axi_bvalid.peek().litToBoolean && d.io.m_axi_bready.peek().litToBoolean) {
              val(address,data,mask)=physical.dequeue()
              for(b<-0 until 64 if mask.testBit(b))memory(address+b)=((data>>(b*8))&255).toInt
            }
          },onWriteAccepted=address=>{
            if(queueConsumer && !queued && address>=pa(texture.base) && address<pa(texture.base)+4096) {
              queued=true
              assert(word(texture.address(7,3,0))==oldRaw,"fixture published a store before its delayed B acknowledgement")
              submit(consumer,ids(1))
              axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0,expectedResponse=2)
              assert(axiRead(d,GpuCommandMmioRegs.VECTOR_SATP)==0x80000030L)
              d.io.s_axi_aclk.step(11)
              d.io.m_irq.expect(false.B)
              assert(perf.nativeTextureGroups.peek().litValue==groups,"consumer sampled before producer publication")
            }
          },onPausedCycle=Some(()=>{
            if(d.io.m_irq.peek().litToBoolean) {
              val result=axiRead(d,GpuCommandMmioRegs.COMPLETION)
              assert((result&0xffff)==(BigInt(0x8700)|ids(completed)),s"native publication completion $result at $completed")
              val details=(0 until 12).map(i=>axiRead(d,0x180+i*4))
              assert(details.head==BigInt("474e5801",16) && details(1)==0 && details(11)==0)
              assert(physical.isEmpty,"completion crossed unacknowledged physical writes")
              axiWrite(d,GpuCommandMmioRegs.COMPLETION_POP,1); axiWrite(d,RenderHostRegs.IRQ,3); completed+=1
            }
            completed<ids.size
          }))((a,data,mask)=>physical.enqueue((a,data,mask))) { completed==ids.size }
        traffic.assertDrained(); assert(physical.isEmpty)
        assert(!queueConsumer || queued,"no rendered texture store reached the delayed publication cut")
        perf.payloadReservedBytes.expect(0.U); perf.payloadPinsActive.expect(0.U)
        assert(perf.payloadAllocations.peek().litValue==perf.payloadReleases.peek().litValue)
      }
      submit(consumer,10); run(Vector(10),queueConsumer=false)
      consumed.verify(oldRaw); if(mode==2)consumedResolve.verify(oldRaw)
      val before=perf.nativeTextureDataReads.map(_.peek().litValue).toVector
      assert(before.take(2).forall(_>0),"warm consumer did not use both physical texture clusters")
      submit(producer,20); run(Vector(20,21),queueConsumer=true)
      produced.verify(newRaw); consumed.verify(newRaw)
      if(mode==2){producedResolve.verify(newRaw); consumedResolve.verify(newRaw)}
      val after=perf.nativeTextureDataReads.map(_.peek().litValue).toVector
      assert(after.take(2).zip(before).forall{case(a,b)=>a>b},"a cluster retained the old rendered texture")
      info(s"NATIVE_RENDER_TEXTURE mode=$mode frame=${width}x$height frames=3 CUs=4 clusters=2 engines=2 sameDescriptorsAndIdentity=true GPUProducer=true queuedConsumer=true delayedPhysicalPublication=true VMHeldThroughStores=true exactStoredImagesAndGuards=true trafficDrained=true")
    }
  }
}

object QualifyNativeRenderTextureVisibility {
  def main(args: Array[String]): Unit = {
    require(!GraphicsTestResolution.productQualification,"this command uses bounded development frames")
    val suite=new NativeRenderTextureVisibilityQualification()
    if(args.sameElements(Array("--inputs-only"))) {
      suite.validateInputs()
      println("NATIVE_RENDER_TEXTURE_INPUTS PASS modes=2 frame=9x4 hardwareExecuted=false")
      return
    }
    require(args.length==2 && args(0)=="--reports","usage: --reports DIR | --inputs-only")
    require(suite.testNames.size==2)
    val passed=org.scalatest.tools.Runner.run(Array("-s",classOf[NativeRenderTextureVisibilityQualification].getName,"-oD","-u",args(1)))
    require(passed,"native render-to-texture visibility qualification failed")
    val report=scala.xml.XML.loadFile(new java.io.File(args(1),
      "TEST-"+classOf[NativeRenderTextureVisibilityQualification].getName+".xml"))
    val cases=report \\ "testcase"
    require((report \ "@tests").text=="2" && cases.size==2 &&
      cases.map(n=>(n \ "@name").text).toSet==suite.testNames.toSet &&
      Seq("errors","failures","skipped").forall(a=>report.attribute(a).forall(_.text=="0")) &&
      Seq("failure","error","skipped").forall(name=>(report \\ name).isEmpty),
      "both registered hardware cases must actually pass without skips or cancellation")
    println("NATIVE_RENDER_TEXTURE PASS cases=2 frames=6 exactStoredImagesAndGuards=true clockQualified=false fpsQualified=false")
  }
}
