package opengpu.graphics

import chisel3._
import java.nio.file.{Files,Path,Paths}
import java.security.MessageDigest
import opengpu.config.GpuConfig
import opengpu.system.{GpuHostSystemAxi,GpuHostTestSupport}
import opengpu.testutil.GpuSim._
import scala.collection.mutable

/** One opt-in host/profile and three jobs. CPU preparation never elaborates it. */
private[graphics] object TessellationOwnedTextureHostFixture extends GpuHostTestSupport {
  val caseName="execute owned fragment texture mip chains and recover a GXL bounds fault at default capacity"
  private val gpu=GpuConfig()
  private val graphics=GraphicsConfig(screenWidth=4,screenHeight=4)
  private val native=NativeBinnedGraphicsConfig(payload=Some(NativeShaderPayloadSizing.pool(owners=3,ports=8)))
  final class OwnedTextureSramHost extends GpuHostSystemAxi(graphics,gpu,numComputeUnits=2,
    fragCore=true,sharedGraphics=true,parallelGraphics=true,memoryAxiDataBytes=32,
    useBlackBoxes=true,enableFpuBackend=true,nativeBinnedGraphics=Some(native),tessellationNative=true) {
    // Register the unchanged pinned vendor models with stock SVSIM. The
    // production blackboxes intentionally carry no global simulation provider.
    val sramModelsPresent=IO(Output(Bool()))
    withClockAndReset(io.s_axi_aclk,!io.s_axi_aresetn) {
      sramModelsPresent:=Module(new NativeBankedTextureSramModels).io.present
    }
  }
  def host():OwnedTextureSramHost=new OwnedTextureSramHost

  private def sha(bytes:Array[Byte]):String=MessageDigest.getInstance("SHA-256").digest(bytes).map(b=>f"${b & 255}%02x").mkString
  private def abi(name:String):ujson.Value=ujson.read(Files.readString(Paths.get("docs/abi",name)))
  private val frameLayout=abi("native-binned-graphics-v1.json")("frame")
  private val levelLayout=abi("texture-image-level-v1.json")
  private def region(p:TessellationRootedHostFixture.Prepared,name:String)=p.regions.find(_.name==name).get
  private def frame(p:TessellationRootedHostFixture.Prepared,name:String):BigInt=
    p.field(frameLayout,region(p,"frame").va,name)

  def prepare(fresh:Boolean):TessellationRootedHostFixture.Prepared={
    val p=TessellationRootedHostFixture.prepare(TessellationNativeQuadHostFixture.build(withoutSimulation=true),
      if(fresh)BigInt(0x3e800000L)else BigInt(0x3f000000L),
      if(fresh)BigInt(0x3f400000L)else BigInt(0x3e800000L),ownedUniforms=true,textureHost=true,
      baseTextureBits=if(fresh)BigInt(0x3f000000L)else BigInt(0x3f400000L),generation=if(fresh)10 else 9)
    checkCpu(p)
    p
  }

  private def attachment(p:TessellationRootedHostFixture.Prepared,index:Int):Array[Byte]={
    val bytes=if(index==5)16 else 64
    val va=frame(p,s"frame.image.views[$index].base")
    val memory=region(p,"frame-data");val offset=(p.physical(va)-memory.dma).toInt
    memory.bytes.slice(offset,offset+bytes)
  }
  private def rgba(values:Vector[BigInt]):Vector[Int]={
    val packed=TypedColorReference.encode("RGBA8",values)._1
    Vector.tabulate(4)(c=>((packed>>(8*c))&255).toInt)
  }
  private def expected(p:TessellationRootedHostFixture.Prepared,index:Int):Vector[Int]={
    val r=p.textureBits;val g=p.uniformBits;val b=p.baseTextureBits;val a=BigInt(0x3f800000L)
    val colors=Vector(Vector(r,g,b,a),Vector(b,r,g,a),Vector(g,b,r,a),Vector(r,r,b,a)).map(rgba)
    (0 until 16).flatMap { pixel=>
      // TES exports the unit quad at x/y0..1; the4x4 viewport maps it to2..4.
      // Centers2.5/3.5 form exactly this2x2 mask, including both triangles.
      val covered=pixel%4>=2 && pixel/4>=2
      if(index<4)if(covered)colors(index)else p.clearPixel
      // z=0,w=1 and near=0,far=1 give window depth (z+w)/(2*w)=0.5.
      else if(index==4){val bits=if(covered)0x3f000000L else 0x3f800000L;Vector.tabulate(4)(i=>((bits>>(8*i))&255).toInt)}
      else Vector(if(covered)42 else 17)
    }.toVector
  }

  private def guards(p:TessellationRootedHostFixture.Prepared):Unit={
    val memory=region(p,"frame-data")
    for(index<-0 until 6){
      val offset=(p.physical(frame(p,s"frame.image.views[$index].base"))-memory.dma).toInt
      val bytes=if(index==5)16 else 64
      assert(memory.bytes.slice(offset-64,offset).forall(_==0xa5.toByte))
      assert(memory.bytes.slice(offset+bytes,offset+4096).forall(_==0xa5.toByte),s"attachment$index guard modified")
    }
  }
  private def images(p:TessellationRootedHostFixture.Prepared):Unit={
    for(index<-0 until 6)assert(attachment(p,index).map(_ & 255).toVector==expected(p,index),s"full attachment$index differs")
    guards(p)
  }

  /** Persist observations before checking completion, images or guards. A file
    * here records bytes even on failure; only texture-result.json means PASS.
    */
  private def recordObservation(p:TessellationRootedHostFixture.Prepared,evidence:Option[Path],
      reads:Seq[BigInt],bounds:Boolean,monitor:Monitor,overlays:Int,colorStores:Int):Unit=evidence.foreach{path=>
    Files.write(path.resolve("frame-data-observed.bin"),region(p,"frame-data").bytes)
    Files.writeString(path.resolve("texture-observed.json"),ujson.write(ujson.Obj(
      "assertionsComplete"->false,"boundsResponseOverlay"->bounds,"serviceCycles"->monitor.cycles,
      "overlayHits"->overlays,"attachmentWriteLines"->colorStores,
      "acceptedPhysicalReads"->ujson.Arr.from(reads.distinct.sorted.map(a=>ujson.Num(a.toLong.toDouble))),
      "acceptedPhysicalReadCounts"->ujson.Obj.from(reads.groupBy(identity).toVector.sortBy(_._1).map{
        case(address,values)=>address.toString->ujson.Num(values.size)}),
      "attachments"->ujson.Arr.from((0 until 6).map(i=>ujson.Arr.from(attachment(p,i).map(_ & 255)))),
      "expectedAttachments"->ujson.Arr.from((0 until 6).map(i=>ujson.Arr.from(expected(p,i)))),
      "immutableRegionSha256"->ujson.Obj.from(p.regions.filterNot(_.name=="frame-data").map(r=>r.name->ujson.Str(sha(r.bytes))))),indent=2))
  }

  private def required(p:TessellationRootedHostFixture.Prepared):Vector[BigInt]={
    val descriptor=Vector("job","frame","draws","states","patch-data","code").map(region(p,_).va)
    val shader=Vector(BigInt(0x1000),p.fragmentShaderPc,BigInt(0x3000)).flatMap(p.instructionLines)
    val resource=Vector(p.textureRootVa,p.textureRootVa+64,p.textureBindingVa,p.textureImageVa,p.textureImageVa+64,
      p.textureSamplerVa,p.textureLevelsVa,p.textureLevelsVa+64,p.textureLevelVa,p.textureLevelVa+64,
      region(p,"root-data").va+0x2c0,region(p,"root-data").va+0x300,p.textureDataVa)
    val virtuals=descriptor++shader++resource
    (virtuals.map(p.physical)++virtuals.flatMap(p.walkLines)++p.requiredResourceReadLines).map(_ & ~BigInt(63)).distinct
  }

  private val coldReadLines=48
  private val warmReadLines=38
  private def codeRegions(p:TessellationRootedHostFixture.Prepared)=Vector("code","fragment-code").map(region(p,_))
  private final case class CodeIdentity(code:Vector[(String,BigInt,BigInt,Vector[Byte])],
      pages:Vector[(String,BigInt,BigInt,Vector[Byte])],
      context:BigInt,rootDma:BigInt,asid:BigInt,satp:Vector[(Int,Int)],tlb:Vector[(Int,Int)])
  private def codeIdentity(p:TessellationRootedHostFixture.Prepared):CodeIdentity={
    p.checkFragmentCode()
    val code=codeRegions(p)
    val pages=p.regions.filter(_.name.startsWith("vm-")).sortBy(_.name)
    assert(code.map(_.bytes.length)==Vector(12288,4096) && pages.length==10 && pages.forall(_.bytes.length==4096))
    CodeIdentity(code.map(r=>(r.name,r.va,r.dma,r.bytes.toVector)),
      pages.map(r=>(r.name,r.va,r.dma,r.bytes.toVector)),BigInt(p.descriptor("context").num.toLong),
      BigInt(p.descriptor("rootDma").num.toLong),BigInt(p.descriptor("asid").num.toLong),p.satpWrites,p.tlbWrites)
  }
  private def codeLines(p:TessellationRootedHostFixture.Prepared):Vector[BigInt]={
    val code=codeRegions(p)
    required(p).filter(a=>code.exists(r=>a>=r.dma && a<r.dma+r.bytes.length))
  }
  private[graphics] def readOracleCounts(p:TessellationRootedHostFixture.Prepared):(Int,Int,Int)=
    (required(p).length,codeLines(p).length,p.regions.count(_.name.startsWith("vm-")))
  private final case class CodeReadWitness(identity:CodeIdentity,lines:Vector[BigInt])
  private def checkPhysicalReads(p:TessellationRootedHostFixture.Prepared,reads:Seq[BigInt],
      warm:Option[CodeReadWitness]):CodeReadWitness={
    val identity=codeIdentity(p)
    warm.foreach(w=>assert(identity==w.identity,"warm executable bytes, mapping or context changed"))
    val code=codeLines(p)
    assert(code.length==10)
    warm.foreach(w=>assert(code==w.lines,"warm executable line set changed"))
    val requiredNow=required(p).filterNot(a=>warm.isDefined && code.contains(a))
    assert(requiredNow.length==(if(warm.isDefined)warmReadLines else coldReadLines))
    val observed=reads.map(_ & ~BigInt(63)).toSet
    assert(requiredNow.forall(observed.contains),"required descriptor/resource/page-table PA missing")
    warm.getOrElse(CodeReadWitness(identity,code))
  }

  private def overlay(p:TessellationRootedHostFixture.Prepared):BigInt={
    val entry=levelLayout("fields").arr.find(_("name").str=="view.allocationLimit").get
    assert(entry("bits").num.toInt==33 && entry("word").num.toInt==10)
    val original=p.readLine(p.textureLevelDma)
    val limit=p.field(levelLayout,p.textureLevelVa,"view.base")+3
    assert(limit<p.field(levelLayout,p.textureLevelVa,"view.allocationLimit"))
    val mask=((BigInt(1)<<33)-1)<<320
    (original & ~mask)|(limit<<320)
  }
  private def lineBytes(value:BigInt):Array[Byte]=Array.tabulate(64)(i=>((value>>(8*i))&255).toByte)

  def checkCpu(p:TessellationRootedHostFixture.Prepared):Unit={
    assert(p.textureHost && p.uniformOwned && p.fragmentCodeOwned && p.textureRootVa==p.jobVa+2176)
    assert(gpu.lanes==16 && gpu.warps==8 && native.vertex.input.attributes==16 && native.vertex.input.bindings==16)
    assert(native.vertex.varyings==16 && native.fragment.varyings==16 && native.fragment.colors==4)
    assert(frame(p,"frame.scratchStride")==16384 && region(p,"states").bytes.length==512)
    assert(region(p,"job").bytes.length==76032 && p.textureDataDma==region(p,"root-data").dma+0xa40)
    assert(p.baseTextureDataDma==region(p,"root-data").dma+0xa00)
    assert(required(p).forall(p.mappedLine))
    val drawLayout=abi("native-tessellation-quad-sequence-v3.json")
    def viewport(name:String):BigInt=p.field(drawLayout,region(p,"draws").va,"template.source.viewport."+name)
    assert(viewport("componentSources[2].valid")==0 && viewport("componentSources[3].valid")==1 &&
      viewport("componentSources[3].bits")==3)
    // The actual TES writes constant1.0 into output component3. Unmapped z is0.
    assert(p.wordAtVirtual(0x3024)==0x3f8002b7L && p.wordAtVirtual(0x3028)==0x00300213L &&
      p.wordAtVirtual(0x302c)==((BigInt(33)<<25)|(BigInt(5)<<20)|(BigInt(4)<<15)|0x0b))
    val view=Fp32ViewportReference.View(width=4,height=4,near=viewport("viewport.nearDepth"),far=viewport("viewport.farDepth"))
    assert(view.near==0 && view.far==0x3f800000L)
    for(x<-Vector(0,0x3f800000L);y<-Vector(0,0x3f800000L)){
      val depth=Fp32ViewportReference.project(Vector(BigInt(x),BigInt(y),BigInt(0),BigInt(0x3f800000L)),view,Fp32ViewportConfig()).depth
      assert(depth==0x3f000000L && expected(p,4).slice(40,44)==Vector(0,0,0,63))
    }
    val changed=overlay(p);val old=p.readLine(p.textureLevelDma)
    val fieldMask=((BigInt(1)<<33)-1)<<320
    assert(((changed^old)& ~fieldMask)==0 && changed!=old)
    assert(((changed>>320)&((BigInt(1)<<33)-1))==p.textureDataVa+3)
    assert(p.clearPixel==Vector(26,51,77,102))
    val literalColors=if(p.textureBits==0x3f000000L)Vector(
      Vector(128,64,191,255),Vector(191,128,64,255),Vector(64,191,128,255),Vector(128,128,191,255))else Vector(
      Vector(64,191,128,255),Vector(128,64,191,255),Vector(191,128,64,255),Vector(64,64,128,255))
    for(i<-0 until 4)assert(expected(p,i).slice(40,44)==literalColors(i))
    for(i<-0 until 6)assert(attachment(p,i).forall(_==0xc3.toByte))
    guards(p)
  }

  /** Exercise the complete image/guard oracle before paying for a host model. */
  def checkCpuOracles(p:TessellationRootedHostFixture.Prepared):Unit={
    val memory=region(p,"frame-data");val saved=memory.bytes.clone()
    try{
      for(index<-0 until 6){
        val offset=(p.physical(frame(p,s"frame.image.views[$index].base"))-memory.dma).toInt
        expected(p,index).zipWithIndex.foreach{case(value,i)=>memory.bytes(offset+i)=value.toByte}
      }
      images(p)
      for(index<-0 until 6){
        val offset=(p.physical(frame(p,s"frame.image.views[$index].base"))-memory.dma).toInt
        val bytesPerPixel=if(index==5)1 else 4
        val payload=bytesPerPixel*16
        for(relative<-Vector(0,10*bytesPerPixel, -1,payload)){
          val at=offset+relative;val old=memory.bytes(at)
          memory.bytes(at)=(old^1).toByte
          val rejected=try{images(p);false}catch{case _:AssertionError=>true}
          memory.bytes(at)=old
          assert(rejected,s"attachment$index byte$relative mutation escaped oracle")
        }
      }
    }finally{System.arraycopy(saved,0,memory.bytes,0,saved.length)}
    checkCpu(p)
  }

  /** Test the bus oracle with C-prepared inputs, without creating a DUT. The
    * actual run starts with no witness and can acquire one only from its own
    * first healthy submission's accepted physical reads.
    */
  def checkCpuPhysicalReadOracles(healthy:TessellationRootedHostFixture.Prepared,
      fresh:TessellationRootedHostFixture.Prepared):Unit={
    def rejects(body: => Unit):Unit={
      val rejected=try{body;false}catch{case _:AssertionError=>true}
      assert(rejected,"physical-read oracle mutation escaped")
    }
    val cold=required(healthy);val code=codeLines(healthy)
    val witness=checkPhysicalReads(healthy,cold,None)
    val eachJob=required(fresh).filterNot(code.contains)
    assert(codeIdentity(healthy)==codeIdentity(fresh) && eachJob.length==warmReadLines)
    checkPhysicalReads(fresh,eachJob,Some(witness))
    for(line<-code)rejects{checkPhysicalReads(healthy,cold.filterNot(_==line),None)}
    for(line<-eachJob)rejects{checkPhysicalReads(fresh,eachJob.filterNot(_==line),Some(witness))}
    def mutateByte(memory:Array[Byte],at:Int,mask:Int=1):Unit={
      val old=memory(at);memory(at)=(old^mask).toByte
      try rejects{checkPhysicalReads(fresh,eachJob,Some(witness))}finally{memory(at)=old}
    }
    for(at<-Vector(0,6144,12287))mutateByte(region(fresh,"code").bytes,at)
    for(at<-Vector(0,2048,4095))mutateByte(region(fresh,"fragment-code").bytes,at)
    for(page<-fresh.regions.filter(_.name.startsWith("vm-")))mutateByte(page.bytes,4095)
    val codeRoot=BigInt(fresh.descriptor("rootDma").num.toLong)+(fresh.fragmentShaderPc>>22)*4
    val codeTable=(fresh.wordAtPhysical(codeRoot)>>10)*4096
    val leaf=fresh.regions.find(_.dma==codeTable).get
    val leafOffset=(((fresh.fragmentShaderPc>>12)&1023)*4).toInt
    for(mask<-Vector(4,8,16,32))mutateByte(leaf.bytes,leafOffset,mask)
    val state=region(fresh,"states")
    val stateLayout=abi("typed-fragment-state-v1.json")
    val pcWord=stateLayout("fields").arr.find(_("name").str=="fragment.shaderPc").get("word").num.toInt
    mutateByte(state.bytes,pcWord*4)
    def changed(change:ujson.Value=>Unit,regions:Vector[TessellationRootedHostFixture.Region]=fresh.regions):Unit={
      val descriptor=ujson.read(ujson.write(fresh.descriptor));change(descriptor)
      rejects{
        val altered=new TessellationRootedHostFixture.Prepared(descriptor,regions,fresh.textureBits,fresh.uniformBits,
          fresh.inputHashes,fresh.baseTextureBits)
        checkPhysicalReads(altered,eachJob,Some(witness))
      }
    }
    for(index<-0 until 2)changed(d=>d("satpWrites")(index)(1)=d("satpWrites")(index)(1).num+1)
    for(name<-Vector("context","rootDma","asid"))changed(d=>d(name)=d(name).num+1)
    for(name<-Vector("code","fragment-code");
        change<-Vector[TessellationRootedHostFixture.Region=>TessellationRootedHostFixture.Region](
          r=>r.copy(va=r.va+4096),r=>r.copy(dma=r.dma+4096),r=>r.copy(bytes=r.bytes.dropRight(1))))
      changed(_=>(),fresh.regions.map(r=>if(r.name==name)change(r)else r))
    assert(codeIdentity(healthy)==codeIdentity(fresh))
  }

  private final class Monitor(d:GpuHostSystemAxi){
    private val pending=mutable.Map.empty[BigInt,Int].withDefaultValue(0)
    var cycles=0;var ar=0;var rlast=0;var aw=0;var wlast=0;var b=0
    def tick():Unit={
      cycles+=1
      if(d.io.m_axi_arvalid.peek().litToBoolean && d.io.m_axi_arready.peek().litToBoolean){
        val id=d.io.m_axi_arid.peek().litValue;pending(id)+=1;ar+=1}
      if(d.io.m_axi_rvalid.peek().litToBoolean && d.io.m_axi_rready.peek().litToBoolean){
        assert(d.io.m_axi_rresp.peek().litValue==0,"bounds-overlay must use AXI OKAY, never SLVERR")
        if(d.io.m_axi_rlast.peek().litToBoolean){val id=d.io.m_axi_rid.peek().litValue
          assert(pending(id)>0);pending(id)-=1;rlast+=1}}
      if(d.io.m_axi_awvalid.peek().litToBoolean && d.io.m_axi_awready.peek().litToBoolean)aw+=1
      if(d.io.m_axi_wvalid.peek().litToBoolean && d.io.m_axi_wready.peek().litToBoolean && d.io.m_axi_wlast.peek().litToBoolean)wlast+=1
      if(d.io.m_axi_bvalid.peek().litToBoolean && d.io.m_axi_bready.peek().litToBoolean){assert(d.io.m_axi_bresp.peek().litValue==0);b+=1}
    }
    def drained():Unit=assert(pending.values.forall(_==0) && ar==rlast && aw==wlast && aw==b,
      s"undrained memory ar=$ar rlast=$rlast aw=$aw wlast=$wlast b=$b")
  }

  private def writable(p:TessellationRootedHostFixture.Prepared,address:BigInt,mask:BigInt):Unit={
    val names=Vector("frame.bins.producer.arena.base"->"frame.bins.producer.arena.limit",
      "frame.bins.bins.pool.base"->"frame.bins.bins.pool.limit","frame.bins.bins.directoryBase"->"frame.bins.bins.directoryLimit")++
      (0 until 2).map(i=>s"frame.scratch[$i].base"->s"frame.scratch[$i].limit")++
      (0 until 6).map(i=>s"frame.image.views[$i].base"->s"frame.image.views[$i].limit")
    val ranges=names.map{case(a,b)=>val base=frame(p,a);val end=frame(p,b);val pa=p.physical(base);pa->(pa+end-base)}
    for(i<-0 until 64 if mask.testBit(i))assert(ranges.exists{case(a,b)=>address+i>=a && address+i<b},s"store byte outside declared writable frame spans ${address+i}")
  }

  def run(d:GpuHostSystemAxi,healthy:TessellationRootedHostFixture.Prepared,fresh:TessellationRootedHostFixture.Prepared):Unit={
    initialize(d);axiWrite(d,RenderHostRegs.IRQ,1)
    var previous=Option.empty[TessellationRootedHostFixture.Prepared]
    // Scoped to this initialized DUT only: no file or CPU fixture seeds it.
    var codeWitness=Option.empty[CodeReadWitness]
    for((id,p,bounds)<-Vector((5,healthy,false),(8,healthy,true),(9,fresh,false))){
      codeWitness.foreach(w=>assert(codeIdentity(p)==w.identity,"warm executable bytes, mapping or context changed"))
      val immutable=p.regions.filterNot(_.name=="frame-data").map(r=>r.name->sha(r.bytes)).toMap
      val before=(0 until 6).map(attachment(p,_).toVector).toVector
      val publication=previous.getOrElse(p)
      for(((address,size),index)<-(p.snapshotInvalidations++p.backingPublications).zipWithIndex){
        val monitor=new Monitor(d)
        axiWrite(d,GpuCommandMmioRegs.COMMAND_ID,32+id*8+index)
        axiWrite(d,GpuCommandMmioRegs.OPCODE,5);axiWrite(d,GpuCommandMmioRegs.SOURCE,address.toInt)
        axiWrite(d,GpuCommandMmioRegs.BYTES,size.toInt);axiWrite(d,GpuCommandMmioRegs.FLAGS,0);axiWrite(d,GpuCommandMmioRegs.SUBMIT,1)
        serviceMemoryMaster(d,publication.readLine,readFault=a=>{assert(publication.mappedLine(a));false},maxCycles=100000,
          writeAckDelay=5,onCycle=()=>monitor.tick())(publication.write){d.io.m_irq.peek().litToBoolean}
        monitor.drained()
        val completion=axiRead(d,GpuCommandMmioRegs.COMPLETION)
        val opcode=((completion>>8)&7)|(((completion>>16)&1)<<3)
        assert((completion&255)==32+id*8+index && opcode==5 && ((completion>>11)&15)==0 && completion.testBit(15))
        axiWrite(d,GpuCommandMmioRegs.COMPLETION_POP,1);axiWrite(d,RenderHostRegs.IRQ,3)
      }
      for((register,value)<-p.tlbWrites++p.satpWrites)axiWrite(d,register,value)
      previous=Some(p)
      val evidence=p.recordStart(id,"quad",uniformFault=false)
      evidence.foreach{path=>Files.writeString(path.resolve("texture-start.json"),ujson.write(ujson.Obj(
        "boundsResponseOverlay"->bounds,"baseTextureBits"->p.baseTextureBits.toLong.toDouble,
        "requiredPhysicalReadLines"->ujson.Arr.from(required(p).map(a=>ujson.Num(a.toLong.toDouble))),
        "expectedAttachments"->ujson.Arr.from((0 until 6).map(i=>ujson.Arr.from(expected(p,i))))),indent=2))}
      axiWrite(d,GpuCommandMmioRegs.COMMAND_ID,id)
      for((register,value)<-p.commandWrites)axiWrite(d,register,value)
      axiWrite(d,GpuCommandMmioRegs.SUBMIT,1)
      val reads=mutable.ArrayBuffer.empty[BigInt];val monitor=new Monitor(d);var overlays=0;var colorStores=0
      val changed=overlay(p)
      def observe():Unit=recordObservation(p,evidence,reads.toVector,bounds,monitor,overlays,colorStores)
      try serviceMemoryMaster(d,a=>{val line=p.readLine(a)
        if(bounds && (a & ~BigInt(63))==p.textureLevelDma){overlays+=1;assert(line==p.readLine(p.textureLevelDma));changed}else line},
        readFault=a=>{assert(p.mappedLine(a),s"unmapped physical read $a");false},writeAckDelay=5,readResponseDelay=11,
        maxCycles=600000,onReadAccepted=a=>reads+=a,onCycle=()=>monitor.tick())({(address,data,mask)=>
          writable(p,address,mask)
          if((0 until 6).exists(i=>address==(p.physical(frame(p,s"frame.image.views[$i].base")) & ~BigInt(63))))colorStores+=1
          p.write(address,data,mask)
        }){d.io.m_irq.peek().litToBoolean}
      catch{case scala.util.control.NonFatal(error)=>
        try observe() catch{case scala.util.control.NonFatal(captureError)=>error.addSuppressed(captureError)}
        throw error
      }
      observe()
      monitor.drained()
      val completion=axiRead(d,GpuCommandMmioRegs.COMPLETION);val flags=axiRead(d,0x184)
      val opcode=((completion>>8)&7)|(((completion>>16)&1)<<3)
      var nextCodeWitness=codeWitness
      assert((completion&255)==id && opcode==15 && ((completion>>11)&15)==(if(bounds)3 else 0) && completion.testBit(15)==(!bounds))
      assert(axiRead(d,0x180)==BigInt("474e5a01",16))
      assert(Seq(0x198,0x19c,0x1a0).map(a=>axiRead(d,a))==Seq[BigInt](7,8,if(id==9)10 else 9))
      assert(reads.count(a=>(a & ~BigInt(63))==p.textureLevelDma)>0,"no actual selected GXL1 read")
      if(bounds){
        assert((flags&0x1ff)==0x17b && overlays>0 && colorStores==0)
        assert(!reads.exists(a=>Set(p.textureDataDma,p.baseTextureDataDma).contains(a & ~BigInt(63))),"rejected GXL reached texels")
        assert((0 until 6).forall(i=>attachment(p,i).toVector==before(i)),"fault exported attachment bytes")
        images(p) // the preceding healthy literal images remain intact
      }else{
        assert(flags==0 && overlays==0 && colorStores>0);images(p)
        nextCodeWitness=Some(checkPhysicalReads(p,reads.toVector,codeWitness))
      }
      assert(!reads.exists(a=>p.virtualBackingSpans.exists{case(base,end)=>a>=base && a<end}),"VA escaped as physical traffic")
      assert(p.regions.filterNot(_.name=="frame-data").forall(r=>sha(r.bytes)==immutable(r.name)))
      p.recordResult(evidence,reads.toVector,completion,flags)
      evidence.foreach{path=>Files.writeString(path.resolve("texture-result.json"),ujson.write(ujson.Obj(
        "boundsResponseOverlay"->bounds,"overlayHits"->overlays,"gxl1PhysicalReadCount"->reads.count(a=>(a & ~BigInt(63))==p.textureLevelDma),
        "gxl1Pa"->p.textureLevelDma.toLong.toDouble,"originalLineSha256"->sha(lineBytes(p.readLine(p.textureLevelDma))),
        "overlayLineSha256"->sha(lineBytes(changed)),"field"->"view.allocationLimit","bits"->33,
        "originalAllocationLimit"->p.field(levelLayout,p.textureLevelVa,"view.allocationLimit").toLong.toDouble,
        "overlayAllocationLimit"->(p.textureDataVa+3).toLong.toDouble,
        "resourceBoundsStatusInferredFromSource"->2,"observedHostStatus"->(if(bounds)3 else 0),"allAxiResponsesOkay"->true,
        "serviceCycles"->monitor.cycles,"memoryDrained"->true,"attachmentWriteLines"->colorStores,
        "coldCodePhysicalReadLines"->ujson.Arr.from(nextCodeWitness.toVector.flatMap(_.lines).map(a=>ujson.Num(a.toLong.toDouble))),
        "codeWitnessReused"->codeWitness.isDefined,"perJobRequiredPhysicalReadLines"->(if(bounds)0 else if(codeWitness.isDefined)warmReadLines else coldReadLines),
        "attachments"->ujson.Arr.from((0 until 6).map(i=>ujson.Arr.from(attachment(p,i).map(_ & 255))))),indent=2))}
      axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0,expectedResponse=2)
      axiWrite(d,GpuCommandMmioRegs.INSTRUCTION_SATP,0,expectedResponse=2)
      axiWrite(d,GpuCommandMmioRegs.COMPLETION_POP,1);axiWrite(d,RenderHostRegs.IRQ,3)
      codeWitness=nextCodeWitness
    }
  }
}
