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
  def host():GpuHostSystemAxi=new GpuHostSystemAxi(graphics,gpu,numComputeUnits=2,
    fragCore=true,sharedGraphics=true,parallelGraphics=true,memoryAxiDataBytes=32,
    useBlackBoxes=true,enableFpuBackend=true,nativeBinnedGraphics=Some(native),tessellationNative=true)

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
      else if(index==4){val bits=if(covered)0L else 0x3f800000L;Vector.tabulate(4)(i=>((bits>>(8*i))&255).toInt)}
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

  private def walkLines(p:TessellationRootedHostFixture.Prepared,va:BigInt):Vector[BigInt]={
    val root=BigInt(p.descriptor("rootDma").num.toLong)+(va>>22)*4
    val leaf=(p.wordAtPhysical(root)>>10)*4096+((va>>12)&1023)*4
    Vector(root,leaf).map(_ & ~BigInt(63))
  }
  private def instructions(p:TessellationRootedHostFixture.Prepared,pc:BigInt):Vector[BigInt]={
    val end=(0 until 1024).find(i=>p.wordAtVirtual(pc+4*i)==BigInt(0x30500073L)).getOrElse(
      throw new AssertionError(s"fixture shader at $pc has no bounded mret"))
    (0 to end).map(i=>pc+4*i).filter(a=>(a&63)==0).toVector
  }
  private def required(p:TessellationRootedHostFixture.Prepared):Vector[BigInt]={
    val descriptor=Vector("job","frame","draws","states","patch-data","code").map(region(p,_).va)
    val shader=Vector(BigInt(0x1000),BigInt(0x2000),BigInt(0x3000)).flatMap(instructions(p,_))
    val resource=Vector(p.textureRootVa,p.textureRootVa+64,p.textureBindingVa,p.textureImageVa,p.textureImageVa+64,
      p.textureSamplerVa,p.textureLevelsVa,p.textureLevelsVa+64,p.textureLevelVa,p.textureLevelVa+64,
      region(p,"root-data").va+0x2c0,region(p,"root-data").va+0x300,p.textureDataVa)
    val virtuals=descriptor++shader++resource
    (virtuals.map(p.physical)++virtuals.flatMap(walkLines(p,_))++p.requiredResourceReadLines).map(_ & ~BigInt(63)).distinct
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
    assert(p.textureHost && p.uniformOwned && p.textureRootVa==p.jobVa+2176)
    assert(gpu.lanes==16 && gpu.warps==8 && native.vertex.input.attributes==16 && native.vertex.input.bindings==16)
    assert(native.vertex.varyings==16 && native.fragment.varyings==16 && native.fragment.colors==4)
    assert(frame(p,"frame.scratchStride")==16384 && region(p,"states").bytes.length==512)
    assert(region(p,"job").bytes.length==76032 && p.textureDataDma==region(p,"root-data").dma+0xa40)
    assert(p.baseTextureDataDma==region(p,"root-data").dma+0xa00)
    assert(required(p).forall(p.mappedLine))
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
    for((id,p,bounds)<-Vector((5,healthy,false),(8,healthy,true),(9,fresh,false))){
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
      serviceMemoryMaster(d,a=>{val line=p.readLine(a)
        if(bounds && (a & ~BigInt(63))==p.textureLevelDma){overlays+=1;assert(line==p.readLine(p.textureLevelDma));changed}else line},
        readFault=a=>{assert(p.mappedLine(a),s"unmapped physical read $a");false},writeAckDelay=5,readResponseDelay=11,
        maxCycles=600000,onReadAccepted=a=>reads+=a,onCycle=()=>monitor.tick())({(address,data,mask)=>
          writable(p,address,mask)
          if((0 until 6).exists(i=>address==(p.physical(frame(p,s"frame.image.views[$i].base")) & ~BigInt(63))))colorStores+=1
          p.write(address,data,mask)
        }){d.io.m_irq.peek().litToBoolean}
      monitor.drained()
      val completion=axiRead(d,GpuCommandMmioRegs.COMPLETION);val flags=axiRead(d,0x184)
      val opcode=((completion>>8)&7)|(((completion>>16)&1)<<3)
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
        assert(required(p).forall(a=>reads.exists(x=>(x & ~BigInt(63))==a)),"required descriptor/resource/page-table PA missing")
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
        "attachments"->ujson.Arr.from((0 until 6).map(i=>ujson.Arr.from(attachment(p,i).map(_ & 255))))),indent=2))}
      axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0,expectedResponse=2)
      axiWrite(d,GpuCommandMmioRegs.INSTRUCTION_SATP,0,expectedResponse=2)
      axiWrite(d,GpuCommandMmioRegs.COMPLETION_POP,1);axiWrite(d,RenderHostRegs.IRQ,3)
    }
  }
}
