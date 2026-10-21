package opengpu.graphics

import chisel3._
import opengpu.command.GpuCommandOpcode
import opengpu.config.GpuConfig
import opengpu.system.{GpuHostSystemAxi,GpuHostTestSupport}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class NativeTypedGraphicsHostAxiSpec extends AnyFlatSpec with GpuHostTestSupport {
  protected def visibilityQueries: Boolean=false
  protected def transformFeedback: Boolean=false
  protected def primitiveQueries: Boolean=false
  protected def primitiveQueryLifetimes: Boolean=false
  protected def shaderExecutionLimits: Boolean=false
  protected def shaderSystemFaults: Boolean=false
  protected def transformFeedbackRecovery: Boolean=false
  protected def groupedTexture: Boolean=false
  protected def nativeComputeUnits: Int=2
  protected def capturedFloatingFormats: Seq[Int]=0 until 64
  protected def capturedDefaultFormat: Int=35
  private val gpu=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2)
  private val graphics=GraphicsConfig(screenWidth=8,screenHeight=8)
  private val p=VertexShaderStageConfig(VertexInputFetchConfig(2,2),2);private val f=FragmentShaderConfig(2,4)
  private def profile(local: Boolean): NativeTypedGraphicsConfig=NativeTypedGraphicsConfig(p,f,
    payload=if(local)Some(ShaderPayloadConfig(capacityBytes=4096,maxPayloadBytes=1024,owners=2,ports=2+nativeComputeUnits))else None,groupedTexture=groupedTexture,transformFeedback=transformFeedback)
  private def addi(rd: Int,rs: Int,n: Int): BigInt=(BigInt(n&4095)<<20)|(BigInt(rs)<<15)|(BigInt(rd)<<7)|0x13
  private def vm(base: Int,v: Int,write: Boolean): BigInt=(BigInt(1)<<25)|(BigInt(base)<<15)|(BigInt(6)<<12)|(BigInt(v)<<7)|(if(write)0x27 else 7)
  private val vertexProgram={
    val code=mutable.ArrayBuffer[BigInt](BigInt("00241393",16),BigInt("007083b3",16),BigInt("c1027057",16))
    for((input,output)<-((0 until 4).map(i=>(i,11+i))++(0 until 4).map(i=>(4+i,15+i))))
      code++=Seq(addi(5,7,input*32),vm(5,1,false),addi(6,7,output*32),vm(6,1,true))
    code+=BigInt("30500073",16);code.toVector
  }
  private def fragmentProgram(version: Int=1,depthColor: Boolean=false,facing: Boolean=false)={
    val inputWords=if(version==4)12 else 10;val prefix=(inputWords+18)*32
    val code=mutable.ArrayBuffer[BigInt](BigInt("00241393",16),BigInt("007083b3",16),BigInt("c1027057",16))
    for(c<-0 until 4;i<-0 until 4){
      code++=Seq(addi(5,7,(if(facing && i==0)4 else if(depthColor && i==0)2 else 6+i)*32),vm(5,1,false))
      if(facing && i==0){
        // Export bit0 of the trusted flags as an exactly representable float:
        // back=0.5, front=1.0. Keep every existing shader ABI array offset.
        def valu(funct6: Int,source: Int,kind: Int): BigInt=(BigInt(funct6)<<26)|(BigInt(1)<<25)|(BigInt(1)<<20)|(BigInt(source)<<15)|(BigInt(kind)<<12)|(BigInt(1)<<7)|0x57
        code++=Seq(valu(0x09,1,3),valu(0x25,23,3),BigInt("3f000737",16),valu(0x00,14,4))
      }
      code++=Seq(addi(6,7,(inputWords+c*4+i)*32),vm(6,1,true))
    }
    code++=Seq(addi(13,1,prefix),(BigInt(13)<<15)|(BigInt(2)<<12)|(BigInt(13)<<7)|3,
      (BigInt(0x17)<<26)|(BigInt(1)<<25)|(BigInt(13)<<15)|(BigInt(4)<<12)|(BigInt(1)<<7)|0x57,
      addi(6,7,(inputWords+17)*32),vm(6,1,true),BigInt("30500073",16));code.toVector
  }
  private class Image(val d: GpuHostSystemAxi){
    val memory=mutable.Map.empty[BigInt,Int];val initial=mutable.Map.empty[BigInt,Int]
    val reads=mutable.ArrayBuffer.empty[BigInt];val writes=mutable.ArrayBuffer.empty[BigInt]
    var expectedImage=Map.empty[BigInt,Int]
    def pa(va: BigInt): BigInt=va+0x400000
    def word(a: BigInt,v: BigInt): Unit=for(b<-0 until 4)memory(a+b)=((v>>(b*8))&255).toInt
    def record(va: Int,words: Int,magic: BigInt,fields: Map[Int,BigInt]): Unit={
      for(w<-0 until words)word(pa(va+w*4),fields.getOrElse(w,BigInt(0)))
      word(pa(va),magic);word(pa(va+4),words*4);word(pa(va+8),1)
    }
    val names=Vector("RGBA8","RGB8","RGBA16F","SRGB8_ALPHA8")
    val clear=Vector(0.25,0.5,0.75,1.0).map(Fp32ViewportReference.fp)
    case class View(base: BigInt,pitch: Int,samples: Int,bytes: Int,kind: Int,color: Int)
    var views=Vector.empty[View];var imageOffset=0;var sourceOffset=0;var boundDepthFormat=TypedDepthFormat.d24s8
    var lastPrimitiveQuads=Vector(0,0,0)
    def viewFields(start: Int,region: Int,mode: Int,kind: Int,color: Int=0,bytes: Int=4): Map[Int,BigInt]={
      val base=BigInt(region)+59;val samples=1<<mode;val pitch=7*samples*bytes+5
      val limit=base+4*pitch+7*samples*bytes;val allocationLimit=(limit+63)& ~BigInt(63)
      for(a<-BigInt(region) until allocationLimit){memory(a)=0xa7;initial(a)=0xa7}
      views :+=View(base,pitch,samples,bytes,kind,color)
      Map(start->base,(start+1)->limit,(start+2)->0,(start+3)->BigInt(region),(start+4)->allocationLimit,
        (start+5)->0,(start+6)->7,(start+7)->5,(start+8)->pitch,(start+9)->0,(start+10)->1,(start+11)->1)
    }
    def setup(mode: Int,count: Int=0,mrt: Boolean=false,depthFormat: Int=TypedDepthFormat.d24s8,allocation: Int=0): Unit={
      require(depthFormat>=TypedDepthFormat.d16 && depthFormat<=TypedDepthFormat.d32fs8)
      boundDepthFormat=depthFormat;val depthBytes=if(depthFormat==TypedDepthFormat.d16)2 else if(depthFormat==TypedDepthFormat.d32fs8)8 else 4
      val depthReserve=1024.max(64*(1<<mode)*depthBytes)
      memory.clear();initial.clear();reads.clear();writes.clear();views=Vector.empty
      imageOffset=(if(mode==0)0 else 0x400000)+(if(allocation==0)0 else 0x2000000+allocation*0x800000)
      word(0x30004,((BigInt(0x800000)>>12)<<10)|0xcf)
      var pass=Map[Int,BigInt](16->1,17->1,18->1,19->7,20->8,21->9,24->3,25->4,26->4,27->mode,28->depthReserve,
        32->depthFormat,34->1,35->BigInt("3f800000",16),36->5,64->1,65->TypedColorFormat.id("RGBA8"),66->1024,67->15,68->1,
        69->BigInt("3e800000",16),70->BigInt("3f000000",16),71->BigInt("3f400000",16),72->BigInt("3f800000",16),224->1,288->1)
      val colors=if(mrt)4 else 1
      for(c<-0 until colors){val start=64+c*16;val bytes=TypedColorReference.format(names(c)).bytes
        pass++=Map[Int,BigInt](start->1,(start+1)->TypedColorFormat.id(names(c)),(start+2)->(Vector(1024,2048,3072,5120)(c)+depthReserve-1024),
          (start+3)->15,(start+4)->1,(start+5)->clear(0),(start+6)->clear(1),(start+7)->clear(2),(start+8)->clear(3),(224+c*16)->1)
        pass++=viewFields(128+c*16,0x1000000+imageOffset+c*0x10000,mode,0,c,bytes)
      }
      pass++=viewFields(192,0x1040000+imageOffset,mode,1,bytes=depthBytes)
      for(c<-0 until colors)pass++=viewFields(225+c*16,0x1080000+imageOffset+c*0x10000,0,2,c,TypedColorReference.format(names(c)).bytes)
      pass++=viewFields(289,0x10c0000+imageOffset,0,3,bytes=depthBytes)
      record(0x411000,320,BigInt("47544901",16),pass)
      record(0x410000,128,BigInt("47544a01",16),Map[Int,BigInt](3->7,4->8,5->9,6->0x411000,7->(0x411000+1280),
        9->0x412000,10->(0x412000+(count-1).max(0)*4096+2560),12->count,13->4096))
    }
    private val positionSets=Vector(Vector(Vector(-1.0,-1.0,-0.5,1.0),Vector(1.0,-1.0,0.5,1.0),Vector(-1.0,1.0,0.0,1.0)),
      Vector(Vector(-0.5,0.0,0.0,1.0),Vector(0.5,0.0,0.0,1.0)),Vector(Vector(-0.5,-0.5,-0.5,1.0)))
    var sampledRed: Option[BigInt]=None
    var sampledGreen: Option[BigInt]=None
    def colorWords(primitive: Int,vertex: Int,centroid: Boolean,expected: Boolean=false): Vector[BigInt]=Vector.tabulate(4){c=>
      if(expected && c==0 && sampledRed.nonEmpty)sampledRed.get
      else if(expected && c==1 && sampledGreen.nonEmpty)sampledGreen.get
      else Fp32ViewportReference.fp(if(centroid){if(c==3)1.0 else ((vertex+1)*(c+1)+primitive)/16.0}else if(c==primitive || c==3)1.0 else 0.0)}
    def shaderDraws(mode: Int,centroid: Boolean=false,version: Int=1,polygon: Boolean=false,sign: Int=1,
        depthFormat: Int=TypedDepthFormat.d24s8,allocation: Int=0,generation: Int=0,facing: Boolean=false,ccw: Boolean=true,reverse: Boolean=false,cull: Int=0,sourceBias: Int=0,freshTextureSources: Boolean=false): Unit={
      require(!facing || !polygon)
      setup(mode,3,mrt=true,depthFormat=depthFormat,allocation=allocation)
      sourceOffset=(if(centroid)0x20000+version*0x1000+mode*0x10000 else 0)+(if(polygon)0x50000 else 0)+allocation*0x10000+generation*0x1000+sourceBias
      if(freshTextureSources){
        // Each frame owns two complete source pages. Skip the fragment operand
        // packet page, which query/captured-operand tests must never read.
        val first=0x640000+generation*0x2000
        val base=first+(if(first>=0x680000)0x2000 else 0)
        require(base>=0x640000 && base+0x2000<=0x700000)
        require(base+0x2000<=0x680000 || base>=0x682000)
        sourceOffset=base-0x420000
      }
      val fragmentPc=if(polygon)0x406000+(if(version==4)0x2000 else 0)else if(version==4)0x404000 else 0x402000
      val prefix=(if(version==4)30 else 28)*32
      word(0x3000c,(((BigInt(0x1000000)+imageOffset)>>12)<<10)|0xcf)
      vertexProgram.zipWithIndex.foreach{case(w,i)=>word(pa(0x401000+4*i),w)}
      fragmentProgram(version,depthColor=polygon,facing=facing).zipWithIndex.foreach{case(w,i)=>word(pa(fragmentPc+4*i),w)}
      // Ordinary scalar kernel reads a prewarmed image cache line and stores
      // its aligned word through the same CUs after native image publication.
      val consumer=Seq(BigInt("0000a283",16),BigInt("0040a303",16),BigInt("0002a383",16),BigInt("00732023",16),BigInt("30500073",16))
      consumer.zipWithIndex.foreach{case(w,i)=>word(pa(0x403000+4*i),w)}
      word(pa(0x450000),0xc0003c);word(pa(0x450004),0x450040)
      for(n<-0 until 3){
        val positions=positionSets(n);val positionBase=0x420000+sourceOffset+n*0x100;val colorBase=0x421000+sourceOffset+n*0x100
        for(i<-positions.indices;c<-0 until 4){val source=if(n==0 && reverse)positions.size-1-i else i
          word(pa(positionBase+i*16+c*4),Fp32ViewportReference.fp(positions(source)(c)))
          word(pa(colorBase+i*16+c*4),colorWords(n,source,centroid)(c))}
        val vs=0x430000+n*0x1000;val fs=0x440000+n*0x1000;word(pa(fs+prefix),61)
        var draw=Map[Int,BigInt](16->1,17->1,18->Seq(4,1,0)(n),32->1,33->0x401000,34->2,35->1,36->15,
          40->vs,41->(vs+608),43->vs,44->(vs+640),64->1,65->7,66->8,67->9,68->positions.size,69->1,
          96->1,99->4,112->1,113->1,115->4,
          480->1,483->4,484->4,486->BigInt("3f800000",16),496->1,497->4,498->4,499->cull,500->(if(ccw)1 else 0),501->mode,
          502->Fp32ViewportReference.fp(2.0),503->BigInt("3f800000",16),
          512->version,513->7,514->8,515->9,516->fragmentPc,520->fs,521->(fs+prefix+4),523->fs,524->(fs+((prefix+4+63)& ~63)),
          528->1,529->4,530->15,536->mode,538->(if(centroid)5 else 0),19->(if(centroid)8 else 0),560->1,561->1,562->1,563->1)
        if(facing)draw++=Map[Int,BigInt](564->1,568->6,569->7,570->255,571->255,574->2,
          576->6,577->11,578->255,579->255,582->2)
        if(polygon)draw++=Map[Int,BigInt](496->2,540->1,541->Fp32ViewportReference.fp(sign*2),542->Fp32ViewportReference.fp(sign*3))
        for((base,slot)<-Seq((positionBase,352),(colorBase,360)))draw++=Map[Int,BigInt](slot->base,(slot+1)->(base+positions.size*16),
          (slot+3)->base,(slot+4)->(base+64),(slot+6)->16)
        record(0x412000+n*4096,640,BigInt("47544401",16),draw)
      }
    }
    def verifyDraws(mode: Int,centroid: Boolean=false,polygon: Boolean=false,sign: Int=1,
        facing: Boolean=false,ccw: Boolean=true,reverse: Boolean=false,cull: Int=0,primitiveCount: Int=3): Unit={
      val count=1<<mode;val colors=Array.fill(4,4,count)(Vector.empty[BigInt])
      val depths=Array.fill(4,4,count)(TypedDepthReference.encode(Fp32ViewportReference.fp(1),boundDepthFormat))
      val stencils=Array.fill(4,4,count)(5)
      val coords=Vector(Vector((0,0),(1024,0),(0,1024)),Vector((256,512),(768,512),(0,0)),Vector((256,256),(0,0),(0,0)))
      val settings=TriangleRasterReference.Settings(width=4,height=4,mode=mode,maxX=4,maxY=4,ccw=ccw,cull=cull)
      val primitiveQuads=Array.fill(3)(0)
      for(n<-0 until primitiveCount){val kind=Seq(2,1,0)(n)
        val originalDepth=positionSets(n).map(v=>Fp32ViewportReference.fp(v(2)*0.5+0.5)).padTo(3,BigInt(0))
        val zs=if(n==0 && reverse)originalDepth.reverse else originalDepth
        val coordinates=if(n==0 && reverse)coords(n).reverse else coords(n)
        val t=TriangleRasterReference.base.copy(coords=coordinates.map{case(x,y)=>(BigInt(x),BigInt(y))},values=TriangleRasterReference.base.values.copy(depth=zs,inv=Vector.fill(3)(Fp32ViewportReference.decode(Fp32ViewportReference.fp(1.0))),
          flat=(if(centroid)8 else 0)|(if(sampledRed.nonEmpty)1 else 0)|(if(sampledGreen.nonEmpty)2 else 0),values=Vector.tabulate(3)(i=>if(i<positionSets(n).size)colorWords(n,if(n==0 && reverse)2-i else i,centroid,expected=true).padTo(8,BigInt(0))else Vector.fill(8)(BigInt(0)))))
        val quads=if(kind==2)TriangleRasterReference.raster(t,settings,graphics)else PointLineReference.raster(t,kind,settings,graphics,PointLineReference.Sizes(point=2))
        primitiveQuads(n)=quads.size
        import Fp32ViewportReference.{E,fp,decode,calc,pack,clamp,rational}
        import FragmentInterpolationReference.edge
        val bias=if(!polygon || kind!=2)E()else{
          val m=PolygonDepthOffsetReference.maximum(t.coords.map(_._1),t.coords.map(_._2),t.values.depth)
          val resolution=boundDepthFormat match {
            case TypedDepthFormat.d16=>BigInt("37804001",16)
            case TypedDepthFormat.d24|TypedDepthFormat.d24s8=>BigInt("33c00000",16)
            case _=>fp(Math.ulp(0.75f).toDouble)
          }
          calc(calc(m,decode(fp(sign*2)),2),calc(decode(resolution),decode(fp(sign*3)),2),0)
        }
        val floating=boundDepthFormat==TypedDepthFormat.d32f || boundDepthFormat==TypedDepthFormat.d32fs8
        def shifted(z: BigInt): BigInt=if(bias.sig==0)z else clamp(pack(calc(decode(z),bias,0)))
        for(raw<-quads){val q=CentroidRasterReference(t,kind,raw,mode,if(centroid)5 else 0,graphics)
          val values=SampleDepthReference.depths(t,kind,q.samples,mode,graphics)
          for(l<-0 until 4;s<-0 until count if(q.samples(l).mask&(1<<s))!=0){val x=q.samples(l).x;val y=q.samples(l).y;val z=TypedDepthReference.encode(if(floating)FloatingDepthReference.sample(t,kind,q.samples(l),s,mode,graphics,bias)else shifted(values(l)(s)),boundDepthFormat)
            val originalColor=q.lanes(l).values.take(4)
            val color=if(facing)originalColor.updated(0,fp(if(q.front)1.0 else 0.5))else originalColor
            if(z<=depths(y)(x)(s)){colors(y)(x)(s)=if(polygon)color.updated(0,if(floating)FloatingDepthReference.center(t,kind,q.samples(l),mode,graphics,bias)else shifted(q.lanes(l).depth))else color;depths(y)(x)(s)=z;if(facing)stencils(y)(x)(s)=if(q.front)7 else 11}}}
      }
      def color(c: Int,x: Int,y: Int,s: Int): BigInt={val n=colors(y)(x)(s)
        val values=if(n.isEmpty)clear else n
        TypedColorReference.encode(names(c),values)._1}
      val expected=mutable.Map.empty[BigInt,Int]++initial
      def depth(z: BigInt,stencil: Int): BigInt=boundDepthFormat match {
        case TypedDepthFormat.d24s8=>(BigInt(stencil)<<24)|z
        case TypedDepthFormat.d32fs8=>(BigInt(stencil)<<32)|z
        case _=>z
      }
      for(v<-views;y<-0 until 4;x<-0 until 4;s<-0 until v.samples){
        val raw=if(v.kind==0)color(v.color,x,y,s)else if(v.kind==1)depth(depths(y)(x)(s),stencils(y)(x)(s))
          else if(v.kind==3)depth(depths(y)(x)(0),stencils(y)(x)(0))
          else TypedResolveReference.resolve(names(v.color),Vector.tabulate(count)(s=>color(v.color,x,y,s)),mode)
        val address=v.base+y*v.pitch+(x*v.samples+s)*v.bytes
        for(b<-0 until v.bytes)expected(address+b)=((raw>>(8*b))&255).toInt}
      lastPrimitiveQuads=primitiveQuads.toVector
      expectedImage=expected.toMap
      if(sys.props.contains("opengpu.textureSize.frames") && expected.exists{case(a,v)=>memory(a)!=v}){
        for((view,index)<-views.zipWithIndex){
          val bad=for(y<-0 until 4;x<-0 until 4;sample<-0 until view.samples)yield{
            val address=view.base+y*view.pitch+(x*view.samples+sample)*view.bytes
            def packed(values: collection.Map[BigInt,Int]): BigInt=(0 until view.bytes).foldLeft(BigInt(0))((v,b)=>v|(BigInt(values(address+b))<<(8*b)))
            val actual=packed(memory);val wanted=packed(expected)
            if(actual!=wanted)Some(s"($x,$y,$sample):${actual.toString(16)}/${wanted.toString(16)}")else None
          }
          println(s"NATIVE_SIZE_RECOVERY_IMAGE view=$index kind=${view.kind} color=${view.color} mismatches=${bad.flatten.mkString(",")}")
        }
      }
      for((a,v)<-expected)assert(memory(a)==v,s"native shader image byte${a.toString(16)} observed=${memory(a)} expected=$v mode=$mode")
      assert(reads.exists(a=>a>=pa(0x420000+sourceOffset) && a<pa(0x422000+sourceOffset)))
      assert(!reads.exists(a=>a>=0x400000 && a<0x800000),"shader and descriptor VAs must be translated before physical issue")
    }
    def consumer(id: Int): BigInt={
      axiWrite(d,GpuCommandMmioRegs.COMMAND_ID,id);axiWrite(d,GpuCommandMmioRegs.OPCODE,0)
      axiWrite(d,GpuCommandMmioRegs.KERNEL_PC,0x403000);axiWrite(d,GpuCommandMmioRegs.KERNARG,0x450000)
      for(r<-Seq(GpuCommandMmioRegs.GRID_X,GpuCommandMmioRegs.GRID_Y,GpuCommandMmioRegs.GRID_Z,GpuCommandMmioRegs.LOCAL_X,GpuCommandMmioRegs.LOCAL_Y,GpuCommandMmioRegs.LOCAL_Z))axiWrite(d,r,1)
      axiWrite(d,GpuCommandMmioRegs.SUBMIT,1)
      serviceMemoryMaster(d,read,writeAckDelay=3,maxCycles=50000)(write){d.io.m_irq.peek().litToBoolean}
      val result=axiRead(d,GpuCommandMmioRegs.COMPLETION);assert((result&255)==id && ((result>>15)&1)==1,s"native consumer failed=${result.toString(16)}")
      val value=(0 until 4).foldLeft(BigInt(0))((v,b)=>v|(BigInt(memory.getOrElse(pa(0x450040)+b,0))<<(8*b)))
      pop();value
    }
    def read(a: BigInt): BigInt={reads+=a;val base=a& ~BigInt(63)
      (0 until 64).foldLeft(BigInt(0))((v,b)=>v|(BigInt(memory.getOrElse(base+b,0))<<(8*b)))}
    def write(a: BigInt,data: BigInt,mask: BigInt): Unit={writes+=a
      for(b<-0 until 64 if mask.testBit(b))memory(a+b)=((data>>(8*b))&255).toInt}
    val queryProbe=if(visibilityQueries)Some(new NativeVisibilityQueryHostProbe(d,memory,read,write))else None
    def submit(id: Int=23,feedback: Option[(NativeTransformFeedbackHostProbe,Boolean)]=None,feedbackScope: ()=>Unit=()=>()): Unit={
      queryProbe.foreach(_.beforeFrame())
      axiWrite(d,GpuCommandMmioRegs.COMMAND_ID,id);axiWrite(d,GpuCommandMmioRegs.OPCODE,7)
      axiWrite(d,GpuCommandMmioRegs.SOURCE,0x410000);axiWrite(d,GpuCommandMmioRegs.DESTINATION,0x410000+512)
      axiWrite(d,GpuCommandMmioRegs.BYTES,512);axiWrite(d,GpuCommandMmioRegs.PATTERN,0);feedback.foreach{case(p,discard)=>p.stageRender(discard)};feedbackScope();axiWrite(d,GpuCommandMmioRegs.SUBMIT,1)
    }
    def run(status: Int=0,id: Int=23,readFault: BigInt=>Boolean=_=>false,
      writeFault: (BigInt,BigInt,BigInt)=>Boolean=(_,_,_)=>false): Unit={
      serviceMemoryMaster(d,read,readFault,writeAckDelay=7,readResponseDelay=3,maxCycles=300000,writeFault=writeFault)(write){d.io.m_irq.peek().litToBoolean}
      val result=axiRead(d,GpuCommandMmioRegs.COMPLETION)
      assert((result&255)==id && ((result>>8)&7)==7 && ((result>>11)&15)==status && ((result>>15)&1)==(if(status==0)1 else 0),s"native AXI result=${result.toString(16)} sharedGroups=${d.io.performance.get.nativeTextureGroups.peek().litValue} sharedReads=${d.io.performance.get.nativeTextureDataReads.map(_.peek().litValue)} sharedWalks=${d.io.performance.get.nativeTexturePageWalks.map(_.peek().litValue)} recentReads=${reads.takeRight(24).map(_.toString(16)).mkString(",")}")
      queryProbe.foreach(_.completed(status))
    }
    def pop(): Unit={axiWrite(d,GpuCommandMmioRegs.COMPLETION_POP,1);axiWrite(d,RenderHostRegs.IRQ,3);queryProbe.foreach(_.afterFrame())}
    def verifyClear(): Unit={
      val expected=mutable.Map.empty[BigInt,Int]++initial
      for(v<-views;y<-0 until 4;x<-0 until 4;s<-0 until v.samples){val raw=if(v.kind%2==0)TypedColorReference.encode(names(v.color),clear)._1 else BigInt("05ffffff",16)
        val address=v.base+y*v.pitch+(x*v.samples+s)*v.bytes
        for(b<-0 until v.bytes)expected(address+b)=((raw>>(8*b))&255).toInt}
      for((a,v)<-expected)assert(memory(a)==v,s"native image byte${a.toString(16)} observed=${memory(a)} expected=$v")
      assert(writes.exists(_>=0x1000000) && !reads.exists(a=>a>=0x400000 && a<0x800000),"virtual descriptors must not escape onto physical AXI")
    }
  }
  if(shaderExecutionLimits)for(local<-Seq(true,false))
  it should s"bound both native shader stages and drain reset-owned instruction fetches local=$local" in {
    simulate(new GpuHostSystemAxi(graphics,gpu,numComputeUnits=nativeComputeUnits,fragCore=true,sharedGraphics=true,parallelGraphics=true,
      memoryAxiDataBytes=32,instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1,
      exposePerformance=true,typedGraphics=Some(profile(local)))){d=>
      initialize(d);val image=new Image(d);val perf=d.io.performance.get
      axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0x80000030);axiWrite(d,GpuCommandMmioRegs.INSTRUCTION_SATP,0x80000030)
      axiWrite(d,RenderHostRegs.IRQ,1)
      for((value,i)<-opengpu.system.NativeShaderExecutionCapabilities.words.zipWithIndex)
        assert(axiRead(d,0x300+4*i)==value)
      def released(): Unit={perf.payloadReservedBytes.expect(0.U);perf.payloadPinsActive.expect(0.U)
        if(local)assert(perf.payloadAllocations.peek().litValue==perf.payloadReleases.peek().litValue)}
      for((stage,frame)<-Seq("vertex","fragment").zipWithIndex){
        image.shaderDraws(0,freshTextureSources=true,generation=frame+1)
        val pc=0x4c0000+frame*0x1000;image.word(image.pa(pc),BigInt("0000006f",16))
        for(draw<-0 until 3)image.word(image.pa(0x412000+draw*4096+(if(stage=="vertex")33 else 516)*4),pc)
        axiWrite(d,GpuCommandMmioRegs.TLB_FLUSH,1);axiWrite(d,0x320,128)
        image.submit(180+frame);axiWrite(d,0x320,17,expectedResponse=2)
        image.run(status=5,id=180+frame)
        assert(image.reads.contains(image.pa(pc)),s"$stage loop never reached instruction memory")
        assert(!image.writes.exists(_>=0x1000000),s"$stage loop published an image")
        axiWrite(d,0x320,17,expectedResponse=2);axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0,expectedResponse=2)
        image.pop();released()
      }
      image.shaderDraws(0,freshTextureSources=true,generation=3)
      val pc=0x4c2000;image.word(image.pa(pc),BigInt("0000006f",16))
      for(draw<-0 until 3)image.word(image.pa(0x412000+draw*4096+33*4),pc)
      axiWrite(d,0x320,-1);axiWrite(d,GpuCommandMmioRegs.TLB_FLUSH,1);image.submit(182)
      var requested=false
      serviceMemoryMaster(d,image.read,readResponseDelay=31,writeAckDelay=29,maxCycles=300000,
        onReadAccepted=a=>if(!requested && a==image.pa(pc)){
          requested=true;if(local)assert(perf.payloadReservedBytes.peek().litValue>0)
          axiWrite(d,GpuCommandMmioRegs.RESET,1)
          axiWrite(d,0x320,17,expectedResponse=2);axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0,expectedResponse=2)
        })(image.write){requested && d.io.m_irq.peek().litToBoolean}
      assert(requested && (axiRead(d,GpuCommandMmioRegs.STATUS)&10)==0)
      assert(!image.writes.exists(_>=0x1000000));released()
      assert(axiRead(d,0x320)==BigInt("ffffffff",16),"command reset must not silently replace quota policy")
      axiWrite(d,RenderHostRegs.IRQ,3);axiWrite(d,0x320,4096)
      image.shaderDraws(0,freshTextureSources=true,generation=4);axiWrite(d,GpuCommandMmioRegs.TLB_FLUSH,1)
      image.submit(183);image.run(id=183);image.verifyDraws(0);image.pop();released()
      info(s"NATIVE_SHADER_EXECUTION_TYPED local=$local CUs=$nativeComputeUnits frames=4 vertexLoop=true fragmentLoop=true quota128=true resetDuringOwnedFetch=true delayedReplyDrained=true maxQuota=true zeroRejectedByPolicy=true queuedAndCompletionPolicyPinned=true exactHealthyImages=true payloadReleased=true")
    }
  }
  if(shaderSystemFaults)for(local<-Seq(true,false))
  it should s"reject unsupported native shader system operations and reuse their destinations through AXI local=$local" in {
    simulate(new GpuHostSystemAxi(graphics,gpu,numComputeUnits=nativeComputeUnits,fragCore=true,sharedGraphics=true,parallelGraphics=true,
      memoryAxiDataBytes=32,instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1,
      exposePerformance=true,typedGraphics=Some(profile(local)))){d=>
      initialize(d);val image=new Image(d);val perf=d.io.performance.get
      axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0x80000030);axiWrite(d,GpuCommandMmioRegs.INSTRUCTION_SATP,0x80000030)
      axiWrite(d,RenderHostRegs.IRQ,1)
      var frames=0;var failures=0
      for((instruction,index)<-Seq(BigInt("00100073",16),BigInt("00000073",16),BigInt("00101573",16)).zipWithIndex;
          healthy<-Seq(false,true)){
        image.shaderDraws(0)
        // A fresh instruction allocation per frame avoids assuming CPU code
        // writes invalidate already cached instruction lines.
        val pc=0x480000+index*0x2000+(if(healthy)0x1000 else 0)
        val code=if(healthy)addi(10,0,7)+:vertexProgram else Vector(instruction)
        code.zipWithIndex.foreach{case(word,i)=>image.word(image.pa(pc+4*i),word)}
        for(draw<-0 until 3)image.word(image.pa(0x412000+draw*4096+33*4),pc)
        axiWrite(d,GpuCommandMmioRegs.TLB_FLUSH,1)
        val id=160+frames;image.submit(id);image.run(status=if(healthy)0 else 5,id=id)
        axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0,expectedResponse=2)
        if(healthy)image.verifyDraws(0)
        else{assert(!image.writes.exists(_>=0x1000000),"faulted system shader published an image");failures+=1}
        image.pop();perf.payloadReservedBytes.expect(0.U);perf.payloadPinsActive.expect(0.U)
        if(local)assert(perf.payloadAllocations.peek().litValue==perf.payloadReleases.peek().litValue)
        frames+=1
      }
      assert(frames==6 && failures==3)
      info(s"NATIVE_SHADER_SYSTEM_TRAP_AXI local=$local CUs=$nativeComputeUnits frames=$frames shaderFaults=$failures ebreak=true ecall=true CSRrd10=true exactHealthyImages=true destinationReuse=true freshCodeAllocations=true noFalsePublication=true heldCompletionPinsVM=true payloadReleased=true")
    }
  }
  if(primitiveQueryLifetimes)for(local<-Seq(true,false);lifetime<-Seq(false,true))
  it should s"preserve native primitive-query ${if(lifetime)"continuous capture lifetime"else"record-memory fault ownership"} local=$local" in {
    simulate(new GpuHostSystemAxi(graphics,gpu,numComputeUnits=nativeComputeUnits,fragCore=true,sharedGraphics=true,parallelGraphics=true,
      memoryAxiDataBytes=32,instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1,
      exposePerformance=true,typedGraphics=Some(profile(local)),visibilityQueries=visibilityQueries,primitiveQueries=primitiveQueries)){d=>
      initialize(d);val image=new Image(d)
      axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0x80000030);axiWrite(d,GpuCommandMmioRegs.INSTRUCTION_SATP,0x80000030)
      axiWrite(d,GpuCommandMmioRegs.TLB_FLUSH,1);axiWrite(d,RenderHostRegs.IRQ,1)
      def frame(preserve: Boolean=false): Unit={
        val saved=if(preserve)image.memory.filter{case(address,_)=>
          (address>=0x18400000 && address<0x18401000) || (address>=0x18800000 && address<0x18810000) ||
          (address>=0x18c00000 && address<0x18c000c0) || (address>=0x30304 && address<0x30310)}.toMap
          else Map.empty[BigInt,Int]
        image.shaderDraws(0,centroid=true,version=3);image.word(image.pa(0x410000+12*4),1);image.memory++=saved
      }
      frame();image.queryProbe.foreach{q=>q.beforeFrame();q.completed(0,false);q.afterFrame()}
      val query=new NativePrimitiveQueryHostProbe(d,image.memory,image.read,image.write);query.prepare()
      if(!lifetime)query.memoryFaults(local)
      else{
        val feedback=new NativeTransformFeedbackRecoveryProbe(d,image.memory,image.read,image.write,0)
        def expected: Seq[BigInt]={
          val positions=Vector(Vector(-1.0,-1.0,-0.5,1.0),Vector(1.0,-1.0,0.5,1.0),Vector(-1.0,1.0,0.0,1.0))
          positions.indices.flatMap(i=>positions(i).map(Fp32ViewportReference.fp)++image.colorWords(0,i,true))
        }
        def draw(id: Int,capture: Boolean): Unit={
          image.submit(id,feedbackScope=()=>feedback.stageRender(capture));image.run(id=id)
          image.verifyDraws(0,centroid=true,primitiveCount=1);image.pop()
          if(local){val perf=d.io.performance.get
            perf.payloadReservedBytes.expect(0.U);perf.payloadPinsActive.expect(0.U)
            assert(perf.payloadAllocations.peek().litValue==perf.payloadReleases.peek().litValue)
          }
        }
        feedback.begin();query.beforeFrame();draw(160,true);query.assertOpen()
        feedback.command(2);frame(true);draw(161,false);query.assertOpen()
        feedback.command(3);frame(true);draw(162,true);query.assertOpen()
        feedback.end(expected++expected,primitives=2)
        frame(true);draw(163,false);query.afterFrame(2)
        // A real capture after EndQuery cannot change the already completed record.
        feedback.begin();frame(true);draw(164,true);feedback.end(expected);query.completed(2)
        query.reportLifetime(local)
      }
    }
  }
  if(transformFeedback && !transformFeedbackRecovery && !primitiveQueryLifetimes)for(local<-Seq(true,false))
  it should s"capture raw native triangle output through translated feedback stores with exact raster and discard images local=$local" in {
    simulate(new GpuHostSystemAxi(graphics,gpu,numComputeUnits=nativeComputeUnits,fragCore=true,sharedGraphics=true,parallelGraphics=true,
      memoryAxiDataBytes=32,instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1,
      exposePerformance=true,typedGraphics=Some(profile(local)),visibilityQueries=visibilityQueries,primitiveQueries=primitiveQueries)){d=>
      initialize(d);val image=new Image(d)
      axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0x80000030);axiWrite(d,GpuCommandMmioRegs.INSTRUCTION_SATP,0x80000030)
      axiWrite(d,GpuCommandMmioRegs.TLB_FLUSH,1);axiWrite(d,RenderHostRegs.IRQ,1)
      val feedback=new NativeTransformFeedbackHostProbe(d,image.memory,image.read,image.write)
      val primitive=if(primitiveQueries)Some(new NativePrimitiveQueryHostProbe(d,image.memory,image.read,image.write))else None
      for(discard<-Seq(false,true)){
        image.shaderDraws(0,centroid=true,version=3);image.word(image.pa(0x410000+12*4),1)
        if(!discard)image.queryProbe.foreach{q=>q.beforeFrame();q.completed(0,false);q.afterFrame()}
        primitive.foreach(_.prepare());feedback.begin();primitive.foreach(_.beforeFrame());image.submit(if(discard)122 else 121,Some(feedback->discard))
        image.run(id=if(discard)122 else 121)
        image.queryProbe.foreach(_.completed(0,!discard))
        if(discard)image.verifyClear()else image.verifyDraws(0,centroid=true,primitiveCount=1)
        image.pop()
        val positions=Vector(Vector(-1.0,-1.0,-0.5,1.0),Vector(1.0,-1.0,0.5,1.0),Vector(-1.0,1.0,0.0,1.0))
        val expected=positions.indices.flatMap(i=>positions(i).map(Fp32ViewportReference.fp)++image.colorWords(0,i,true))
        feedback.end(expected,1,3)
        primitive.foreach(_.afterFrame(1))
      }
      feedback.report("TYPED",local)
      primitive.foreach(_.report("TYPED",local,1))
    }
  }
  if(transformFeedbackRecovery)for(local<-Seq(true,false))
  it should s"drain native feedback permission and store faults without losing peer objects and recover from accepted-store reset local=$local" in {
    simulate(new GpuHostSystemAxi(graphics,gpu,numComputeUnits=nativeComputeUnits,fragCore=true,sharedGraphics=true,parallelGraphics=true,
      memoryAxiDataBytes=32,instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1,
      exposePerformance=true,typedGraphics=Some(profile(local)),visibilityQueries=visibilityQueries,primitiveQueries=primitiveQueries)){d=>
      initialize(d);val image=new Image(d)
      axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0x80000030);axiWrite(d,GpuCommandMmioRegs.INSTRUCTION_SATP,0x80000030)
      axiWrite(d,GpuCommandMmioRegs.TLB_FLUSH,1);axiWrite(d,RenderHostRegs.IRQ,1)
      val a=new NativeTransformFeedbackRecoveryProbe(d,image.memory,image.read,image.write,0)
      val b=new NativeTransformFeedbackRecoveryProbe(d,image.memory,image.read,image.write,1)
      val primitive=if(primitiveQueries)Some(new NativePrimitiveQueryHostProbe(d,image.memory,image.read,image.write))else None
      var frames=0;var resetCuts=0
      def frame(preserve: Boolean=false): Unit={
        val saved=if(preserve)image.memory.filter{case(address,_)=>
          (address>=0x18400000 && address<0x18401000) || (address>=0x18800000 && address<0x18810000) ||
          (address>=0x30304 && address<0x3030c)}.toMap else Map.empty[BigInt,Int]
        image.shaderDraws(0,centroid=true,version=3);image.word(image.pa(0x410000+12*4),1)
        image.memory++=saved
      }
      def flush(): Unit=axiWrite(d,GpuCommandMmioRegs.TLB_FLUSH,1)
      def payloadIdle(): Unit=if(local){val perf=d.io.performance.get
        perf.payloadReservedBytes.expect(0.U);perf.payloadPinsActive.expect(0.U)
        assert(perf.payloadAllocations.peek().litValue==perf.payloadReleases.peek().litValue)
      }
      def expected: Seq[BigInt]={
        val positions=Vector(Vector(-1.0,-1.0,-0.5,1.0),Vector(1.0,-1.0,0.5,1.0),Vector(-1.0,1.0,0.0,1.0))
        positions.indices.flatMap(i=>positions(i).map(Fp32ViewportReference.fp)++image.colorWords(0,i,true))
      }
      def finishQuery(status: Int,active: NativeTransformFeedbackRecoveryProbe): Unit={
        // Queries own contextA. A healthy peer-context draw contributes no samples.
        image.queryProbe.foreach(_.completed(status,status==0 && active.context==a.context))
      }
      def healthyPeer(id: Int): Unit={
        frame(true);primitive.foreach(_.beforeFrame());image.submit(id,feedbackScope=()=>b.stageRender());image.run(id=id)
        finishQuery(0,b);image.verifyDraws(0,centroid=true,primitiveCount=1);image.pop()
        b.end(expected);primitive.foreach(_.afterFrame(0,peerCount=1));payloadIdle();frames+=1
      }
      frame();image.queryProbe.foreach{q=>q.beforeFrame();q.completed(0,false);q.afterFrame()}
      primitive.foreach(_.prepare())
      for(kind<-Seq("checkpoint-permission","capture-permission","capture-bus","capture-reset")){
        frame();a.begin();b.begin();primitive.foreach(_.beforeFrame())
        if(kind=="checkpoint-permission"){
          a.permission(false,false);flush();val start=image.writes.size
          a.command(2,status=3,flags=0)
          assert(!image.writes.drop(start).exists(x=>x>=0x18400000 && x<0x18401000),"denied checkpoint store reached physical AXI")
          a.permission(false,true);flush();a.command(1,status=5,flags=2)
          image.submit(150,feedbackScope=()=>a.stageRender());image.run(status=5,id=150)
          finishQuery(5,a);image.pop();payloadIdle();frames+=1
          primitive.foreach(_.afterFrame(0,status=3))
          assert(!image.writes.contains(a.firstDataLine),"missing capture lease wrote feedback data")
          healthyPeer(151)
        }else if(kind=="capture-permission" || kind=="capture-bus"){
          if(kind=="capture-permission"){a.permission(true,false);flush()}
          var injected=false;val start=image.writes.size
          image.submit(152,feedbackScope=()=>a.stageRender())
          image.run(status=3,id=152,writeFault=(address,_,_)=>{
            // In the query matrix, acknowledge the first two lines before
            // failing the final line. Partial stores must never count a primitive.
            val fault=kind=="capture-bus" && !injected && address==a.firstDataLine+(if(primitiveQueries)128 else 0)
            if(fault && primitiveQueries)assert(Seq(a.firstDataLine,a.firstDataLine+64).forall(image.writes.drop(start).contains),
              "final capture-line fault preceded the acknowledged prefix stores")
            if(fault)injected=true;fault
          })
          finishQuery(3,a);image.pop();payloadIdle();frames+=1
          if(kind=="capture-permission")assert(!image.writes.drop(start).contains(a.firstDataLine),"denied capture store reached physical AXI")
          else assert(injected,"capture bus error was not injected on the owned store")
          a.end(Seq.empty,status=3,allowedPartialBytes=if(kind=="capture-bus")96 else 0)
          primitive.foreach(_.afterFrame(0,status=3))
          a.permission(true,true);flush();healthyPeer(153)
        }else{
          image.submit(154,feedbackScope=()=>a.stageRender());var cut=false
          serviceMemoryMaster(d,image.read,writeAckDelay=53,readResponseDelay=3,maxCycles=300000,
            onWriteAccepted=address=>if(!cut && (address & ~BigInt(63))==a.firstDataLine){
              cut=true;axiWrite(d,GpuCommandMmioRegs.RESET,1)
              axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0,expectedResponse=2)
            })(image.write){cut && d.io.m_irq.peek().litToBoolean}
          assert(cut && (axiRead(d,GpuCommandMmioRegs.STATUS)&10)==0,"accepted capture-store reset did not drain")
          image.queryProbe.foreach(_.aborted());axiWrite(d,RenderHostRegs.IRQ,3);payloadIdle();resetCuts+=1
          primitive.foreach(_.abortedFrame())
          a.command(1,status=5,flags=2);b.command(1,status=5,flags=2)
          frame();b.begin();flush();healthyPeer(155)
        }
        info(s"NATIVE_TRANSFORM_FEEDBACK_RECOVERY_CASE local=$local kind=$kind peerHealthy=true payloadReleased=true")
      }
      assert(frames==7 && resetCuts==1)
      primitive.foreach { q=>
        // Native non-capture draws remain legal with a paused or absent object.
        // Resume must recover the same object's exact first acknowledged count.
        frame();a.begin();a.command(2);q.beforeFrame()
        image.submit(156,feedbackScope=()=>a.stageRender(capture=false));image.run(id=156)
        finishQuery(0,a);image.verifyDraws(0,centroid=true,primitiveCount=1);image.pop();q.afterFrame(0)
        a.command(3);frame(true);q.beforeFrame()
        image.submit(157,feedbackScope=()=>a.stageRender());image.run(id=157)
        finishQuery(0,a);image.verifyDraws(0,centroid=true,primitiveCount=1);image.pop()
        a.end(expected);q.afterFrame(1);payloadIdle()
        frame();q.beforeFrame()
        image.submit(158,feedbackScope=()=>a.stageRender(capture=false));image.run(id=158)
        finishQuery(0,a);image.verifyDraws(0,centroid=true,primitiveCount=1);image.pop();q.afterFrame(0);payloadIdle()
        q.reportRecovery(local)
      }
      info(s"NATIVE_TRANSFORM_FEEDBACK_RECOVERY_AXI local=$local CUs=4 completedFrames=$frames resetCuts=$resetCuts checkpointPermissionFaults=1 capturePermissionFaults=1 captureBusFaults=1 missingLeaseDraws=1 peerHealthyDraws=4 scopedRecovery=true countersAfterFinalAck=true noDeniedPhysicalStores=true partialFaultStoresBounded=true staleResetObjectsRejected=true exactRawCaptureAndImages=true queryContextIsolation=true")
    }
  }
  if(!transformFeedback && !shaderSystemFaults && !shaderExecutionLimits){
  // Shared-path subclasses register the focused capture matrix below.
  if(!groupedTexture){
  it should "submit a translated native clear pass and publish trusted physical source and resolve views over the production AXI host" in {
    simulate(new GpuHostSystemAxi(graphics,gpu,numComputeUnits=nativeComputeUnits,fragCore=true,sharedGraphics=true,parallelGraphics=true,
      memoryAxiDataBytes=32,instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1,
      exposePerformance=true,typedGraphics=Some(profile(false)),visibilityQueries=visibilityQueries,primitiveQueries=primitiveQueries)){d=>
      initialize(d);val image=new Image(d)
      assert(axiRead(d,TileBinHostRegs.DISCOVERY)==0x160)
      assert(axiRead(d,0x160)==BigInt("47544301",16));assert(axiRead(d,0x168)==BigInt("47544a01",16))
      for((word,i)<-Vector[BigInt](BigInt("47564901",16),4,0x502,0x1ffffff).zipWithIndex)
        assert(axiRead(d,0x1b0+i*4)==word,s"typed GVI1 word $i differs")
      axiWrite(d,0x1b0,0,expectedResponse=2)
      axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0x80000030);axiWrite(d,GpuCommandMmioRegs.TLB_FLUSH,1);axiWrite(d,RenderHostRegs.IRQ,1)
      for(mode<-Seq(0,2)){image.setup(mode);image.submit();image.run();image.verifyClear()
        // A software-held completion still pins the native job's VM.
        axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0,expectedResponse=2)
        assert(axiRead(d,GpuCommandMmioRegs.VECTOR_SATP)==BigInt("80000030",16));image.pop()
        axiWrite(d,GpuCommandMmioRegs.TLB_FLUSH,1)
      }
      info("NATIVE_TYPED_AXI CUs=2 nativeSubmit=true GTJ1=true translatedDescriptors=true physicalImageViews=true clearPass=true modes1x4x=true sourceStores=true resolve=true guardedPitches=true heldCompletionPinsVM=true")
    }
  }
  for(local<-Seq(true,false))
  it should s"execute GPU-memory triangle line and point shader draws with translated resources and cached image consumers local=$local" in {
    simulate(new GpuHostSystemAxi(graphics,gpu,numComputeUnits=nativeComputeUnits,fragCore=true,sharedGraphics=true,parallelGraphics=true,
      memoryAxiDataBytes=32,instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1,
      exposePerformance=true,typedGraphics=Some(profile(local)),visibilityQueries=visibilityQueries,primitiveQueries=primitiveQueries)){d=>
      initialize(d);val image=new Image(d)
      axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0x80000030);axiWrite(d,GpuCommandMmioRegs.INSTRUCTION_SATP,0x80000030)
      axiWrite(d,GpuCommandMmioRegs.TLB_FLUSH,1);axiWrite(d,RenderHostRegs.IRQ,1)
      for(mode<-Seq(0,2)){
        image.shaderDraws(mode);axiWrite(d,GpuCommandMmioRegs.TLB_FLUSH,1)
        assert(image.consumer(30+mode)==BigInt("a7a7a7a7",16))
        image.submit();image.run();image.verifyDraws(mode)
        assert(axiRead(d,GpuCommandMmioRegs.COMPLETION_BYTES_LO)==3*2560);image.pop()
        val sourceWord=image.views.head.base+1;val sourceLine=sourceWord& ~BigInt(63)
        val expected=(0 until 4).foldLeft(BigInt(0))((v,b)=>v|(BigInt(image.memory(sourceWord+b))<<(8*b)))
        assert(image.consumer(40+mode)==expected)
        val sourceReads=image.reads.count(a=>a>=sourceLine && a<sourceLine+64)
        assert(image.consumer(50+mode)==expected)
        assert(image.reads.count(a=>a>=sourceLine && a<sourceLine+64)==sourceReads)
      }
      val perf=d.io.performance.get
      assert(perf.maxGraphicsLocalX.peek().litValue>0 && perf.maxGraphicsLocalX.peek().litValue<=8)
      assert((perf.stagingWriteBytes.peek().litValue==0)==local)
      assert((perf.payloadAllocations.peek().litValue>0)==local)
      info(s"NATIVE_TYPED_AXI local=$local CUs=2 shaderDraws=6 modes1x4x=true primitiveKinds=3 GPUDescriptorABI=true virtualVertexAndScratch=true instructionPermissions=true slopedSampleDepth=true exactPhysicalMRTAndDepth=true typedResolve=true prewarmedCachedConsumer=true sharedPayloadPool=true")
    }
  }
  for(local<-Seq(true,false))
  it should s"publish native centroid gradients through version three and four fragment descriptors local=$local" in {
    simulate(new GpuHostSystemAxi(graphics,gpu,numComputeUnits=nativeComputeUnits,fragCore=true,sharedGraphics=true,parallelGraphics=true,
      memoryAxiDataBytes=32,instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1,
      exposePerformance=true,typedGraphics=Some(profile(local)),visibilityQueries=visibilityQueries,primitiveQueries=primitiveQueries)){d=>
      initialize(d);val image=new Image(d)
      for((value,i)<-Vector[BigInt](BigInt("47464901",16),4,0x1e,0x70f).zipWithIndex)
        assert(axiRead(d,0x1c0+i*4)==value)
      axiWrite(d,0x1c0,0,expectedResponse=2)
      axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0x80000030);axiWrite(d,GpuCommandMmioRegs.INSTRUCTION_SATP,0x80000030)
      axiWrite(d,GpuCommandMmioRegs.TLB_FLUSH,1);axiWrite(d,RenderHostRegs.IRQ,1)
      for((mode,version)<-Seq((1,3),(2,4))){
        image.shaderDraws(mode,centroid=true,version=version);axiWrite(d,GpuCommandMmioRegs.TLB_FLUSH,1)
        image.submit(60+version);image.run(id=60+version);image.verifyDraws(mode,centroid=true)
        assert(axiRead(d,GpuCommandMmioRegs.COMPLETION_BYTES_LO)==3*2560)
        val prefix=(if(version==4)30 else 28)*32
        for(n<-0 until 3){val address=image.pa(0x440000+n*0x1000+prefix)
          val uniform=(0 until 4).foldLeft(BigInt(0))((v,b)=>v|(BigInt(image.memory(address+b))<<(8*b)))
          assert(uniform==61,"centroid draw overwrote its uniform tail")}
        axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0,expectedResponse=2);image.pop()
      }
      val perf=d.io.performance.get
      assert((perf.stagingWriteBytes.peek().litValue==0)==local)
      info(s"NATIVE_CENTROID local=$local CUs=2 GFI1=true versions3And4=true modes2x4x=true primitiveKinds=3 mixedCenterCentroidFlat=true gradientMRTs=4 pointCoordPrefix=true freshVertexAndProgramAllocations=true exactSourceAndResolve=true uniformTail=true heldCompletionPinsVM=true")
    }
  }
  for(local<-Seq(true,false))
  it should s"publish native facing flags and enforce culling across every sample mode local=$local" in {
    simulate(new GpuHostSystemAxi(graphics,gpu,numComputeUnits=nativeComputeUnits,fragCore=true,sharedGraphics=true,parallelGraphics=true,
      memoryAxiDataBytes=32,instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1,
      exposePerformance=true,typedGraphics=Some(profile(local)),visibilityQueries=visibilityQueries,primitiveQueries=primitiveQueries)){d=>
      initialize(d);val image=new Image(d)
      axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0x80000030);axiWrite(d,GpuCommandMmioRegs.INSTRUCTION_SATP,0x80000030)
      axiWrite(d,GpuCommandMmioRegs.TLB_FLUSH,1);axiWrite(d,RenderHostRegs.IRQ,1)
      var frames=0;var visible=0;var culled=0
      for(ccw<-Seq(true,false);reverse<-Seq(false,true);cull<-0 to 2;mode<-0 to 2){
        frames+=1;val version=if(mode==2)4 else 3
        image.shaderDraws(mode,centroid=true,version=version,allocation=frames,facing=true,ccw=ccw,reverse=reverse,cull=cull)
        axiWrite(d,GpuCommandMmioRegs.TLB_FLUSH,1)
        assert(image.consumer(150)==BigInt("a7a7a7a7",16))
        image.submit(151);image.run(id=151);image.verifyDraws(mode,centroid=true,facing=true,ccw=ccw,reverse=reverse,cull=cull)
        val front=(!reverse)==ccw;val expectedCull=(cull==1 && !front) || (cull==2 && front)
        assert((image.lastPrimitiveQuads.head==0)==expectedCull)
        assert(image.lastPrimitiveQuads(1)>0 && image.lastPrimitiveQuads(2)>0,"line/point culling must be bypassed")
        if(expectedCull)culled+=1 else visible+=1
        assert(axiRead(d,GpuCommandMmioRegs.COMPLETION_BYTES_LO)==3*2560)
        val prefix=(if(version==4)30 else 28)*32
        for(n<-0 until 3){val a=image.pa(0x440000+n*0x1000+prefix)
          val value=(0 until 4).foldLeft(BigInt(0))((v,b)=>v|(BigInt(image.memory(a+b))<<(b*8)));assert(value==61)}
        axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0,expectedResponse=2);image.pop()
        val expected=(0 until 4).foldLeft(BigInt(0))((v,b)=>v|(BigInt(image.expectedImage(BigInt(0x1000000+image.imageOffset+60+b)))<<(b*8)))
        assert(image.consumer(152)==expected)
        info(s"NATIVE_FACING_FRAME local=$local ccw=$ccw reverse=$reverse cull=$cull mode=$mode triangleCulled=$expectedCull shaderFacingInMRT=true frontBackStencil=true exactImageDepthResolveGuards=true")
      }
      assert(frames==36 && visible==24 && culled==12)
      val perf=d.io.performance.get;assert((perf.stagingWriteBytes.peek().litValue==0)==local);perf.payloadReservedBytes.expect(0.U)
      info(s"NATIVE_FACING_MATRIX local=$local CUs=2 frames=$frames submittedDraws=${frames*3} visibleTriangleFrames=$visible culledTriangleFrames=$culled frontConventions=2 windings=2 cullModes=3 sampleModes=3 versions3And4=true linePointAlwaysFront=true linePointCullBypass=true frontBackStencilReplace=true exactMRTDepthStencilResolve=true cachedConsumer=true freshAllocations=true uniformTails=true heldCompletionPinsVM=true")
    }
  }
  for(local<-Seq(true,false))
  it should s"publish signed native polygon offsets across all depth formats and sample modes local=$local" in {
    simulate(new GpuHostSystemAxi(graphics,gpu,numComputeUnits=nativeComputeUnits,fragCore=true,sharedGraphics=true,parallelGraphics=true,
      memoryAxiDataBytes=32,instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1,
      exposePerformance=true,typedGraphics=Some(profile(local)),visibilityQueries=visibilityQueries,primitiveQueries=primitiveQueries)){d=>
      initialize(d);val image=new Image(d)
      axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0x80000030);axiWrite(d,GpuCommandMmioRegs.INSTRUCTION_SATP,0x80000030)
      axiWrite(d,GpuCommandMmioRegs.TLB_FLUSH,1);axiWrite(d,RenderHostRegs.IRQ,1)
      var allocation=0
      for(format<-TypedDepthFormat.d16 to TypedDepthFormat.d32fs8;mode<-0 to 2;sign<-Seq(1,-1)){
        allocation+=1;val version=if(mode==2)4 else 3
        image.shaderDraws(mode,centroid=true,version=version,polygon=true,sign=sign,depthFormat=format,allocation=allocation)
        axiWrite(d,GpuCommandMmioRegs.TLB_FLUSH,1)
        assert(image.consumer(140)==BigInt("a7a7a7a7",16))
        image.submit(141);image.run(id=141);image.verifyDraws(mode,centroid=true,polygon=true,sign=sign)
        assert(axiRead(d,GpuCommandMmioRegs.COMPLETION_BYTES_LO)==3*2560)
        val prefix=(if(version==4)30 else 28)*32
        for(n<-0 until 3){val a=image.pa(0x440000+n*0x1000+prefix)
          val value=(0 until 4).foldLeft(BigInt(0))((v,b)=>v|(BigInt(image.memory(a+b))<<(b*8)));assert(value==61)}
        axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0,expectedResponse=2);image.pop()
        val expected=(0 until 4).foldLeft(BigInt(0))((v,b)=>v|(BigInt(image.expectedImage(BigInt(0x1000000+image.imageOffset+60+b)))<<(b*8)))
        assert(image.consumer(142)==expected)
        info(s"NATIVE_POLYGON_DEPTH_FORMAT local=$local format=$format mode=$mode sign=$sign exactBackingResolveStencilPaddingGuards=true")
      }
      assert(allocation==30);val perf=d.io.performance.get
      assert((perf.stagingWriteBytes.peek().litValue==0)==local);perf.payloadReservedBytes.expect(0.U)
      info(s"NATIVE_POLYGON_DEPTH_MATRIX local=$local CUs=2 formats=5 sampleModes=3 signs=2 frames=30 draws=90 GTD1=true Sv32=true fragmentVersions3And4=true shaderFragCoordZInMRT=true linePointBypass=true cachedConsumer=true freshAllocations=true uniformTails=true heldCompletionPinsVM=true")
    }
  }
  for(local<-Seq(true,false))
  it should s"publish native polygon offset depth and shader fragCoord through immutable attachment binding local=$local" in {
    simulate(new GpuHostSystemAxi(graphics,gpu,numComputeUnits=nativeComputeUnits,fragCore=true,sharedGraphics=true,parallelGraphics=true,
      memoryAxiDataBytes=32,instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1,
      exposePerformance=true,typedGraphics=Some(profile(local)),visibilityQueries=visibilityQueries,primitiveQueries=primitiveQueries)){d=>
      initialize(d);val image=new Image(d)
      axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0x80000030);axiWrite(d,GpuCommandMmioRegs.INSTRUCTION_SATP,0x80000030)
      axiWrite(d,GpuCommandMmioRegs.TLB_FLUSH,1);axiWrite(d,RenderHostRegs.IRQ,1)
      for((mode,version,sign)<-Seq((0,3,1),(2,4,-1))){
        image.shaderDraws(mode,centroid=true,version=version,polygon=true,sign=sign);axiWrite(d,GpuCommandMmioRegs.TLB_FLUSH,1)
        assert(image.consumer(80+mode)==BigInt("a7a7a7a7",16))
        image.submit(70+mode);image.run(id=70+mode);image.verifyDraws(mode,centroid=true,polygon=true,sign=sign)
        assert(axiRead(d,GpuCommandMmioRegs.COMPLETION_BYTES_LO)==3*2560)
        val prefix=(if(version==4)30 else 28)*32
        for(n<-0 until 3){val a=image.pa(0x440000+n*0x1000+prefix)
          val value=(0 until 4).foldLeft(BigInt(0))((v,b)=>v|(BigInt(image.memory(a+b))<<(b*8)));assert(value==61)}
        axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0,expectedResponse=2);image.pop()
        val expected=(0 until 4).foldLeft(BigInt(0))((v,b)=>v|(BigInt(image.expectedImage(BigInt(0x1000000+image.imageOffset+60+b)))<<(b*8)))
        assert(image.consumer(90+mode)==expected)
      }
      val perf=d.io.performance.get;assert((perf.stagingWriteBytes.peek().litValue==0)==local)
      perf.payloadReservedBytes.expect(0.U)
      info(s"NATIVE_POLYGON_OFFSET local=$local CUs=2 GTD1=true rasterVersion2=true boundD24S8=true signedFactorUnits=true modes1x4x=true fragmentVersions3And4=true shaderFragCoordZInMRT=true exactDefaultSampleDepth=true centroidGradients=true linePointBypass=true exactMRTDepthResolveGuards=true prewarmedCachedConsumer=true uniformTails=true heldCompletionPinsVM=true")
    }
  }
  for(polygon<-Seq(false,true))
  it should s"reject native envelope descriptor permission shader and physical store faults without publishing a successful image polygon=$polygon" in {
    simulate(new GpuHostSystemAxi(graphics,gpu,numComputeUnits=nativeComputeUnits,fragCore=true,sharedGraphics=true,parallelGraphics=true,
      memoryAxiDataBytes=32,instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1,
      exposePerformance=true,typedGraphics=Some(profile(false)),visibilityQueries=visibilityQueries,primitiveQueries=primitiveQueries)){d=>
      initialize(d);val image=new Image(d);axiWrite(d,RenderHostRegs.IRQ,1)
      val rasterFaults=Set("legacy-offset-fields","reserved-raster-version","nonfinite-offset-factor","nonfinite-offset-units","noncanonical-offset-enable")
      val cases=Seq(("envelope",1),("header",1),("descriptor-read",3),("draw-read",3),("scratch-alias",1),("write-permission",3),("execute-permission",5),("physical-store",3))++
        (if(polygon)rasterFaults.toVector.sorted.map(_->1)else Vector.empty)++Seq(("reuse",0))
      for(((name,status),index)<-cases.zipWithIndex){
        val sign=if(index%2==0)1 else -1
        image.shaderDraws(0,centroid=polygon,version=if(polygon)3 else 1,polygon=polygon,sign=sign)
        if(name=="header")image.word(image.pa(0x410000+127*4),1)
        if(name=="scratch-alias")image.word(image.pa(0x412000+40*4),0x412000)
        if(name=="write-permission")image.word(0x30004,(BigInt(0x800)<<10)|0x43)
        if(name=="execute-permission")image.word(0x31004,(BigInt(0x800)<<10)|0xc7)
        if(name=="legacy-offset-fields")image.word(image.pa(0x412000+496*4),1)
        if(name=="reserved-raster-version")image.word(image.pa(0x412000+496*4),7)
        if(name=="nonfinite-offset-factor")image.word(image.pa(0x412000+541*4),BigInt("7fc00000",16))
        if(name=="nonfinite-offset-units")image.word(image.pa(0x412000+542*4),BigInt("ff800000",16))
        if(name=="noncanonical-offset-enable")image.word(image.pa(0x412000+540*4),2)
        axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0x80000030)
        axiWrite(d,GpuCommandMmioRegs.INSTRUCTION_SATP,if(name=="execute-permission")0x80000031 else 0x80000030)
        axiWrite(d,GpuCommandMmioRegs.TLB_FLUSH,1)
        val id=70+index
        if(name=="envelope"){
          axiWrite(d,GpuCommandMmioRegs.COMMAND_ID,id);axiWrite(d,GpuCommandMmioRegs.OPCODE,7)
          axiWrite(d,GpuCommandMmioRegs.SOURCE,0x410000);axiWrite(d,GpuCommandMmioRegs.DESTINATION,0x410200)
          axiWrite(d,GpuCommandMmioRegs.BYTES,256);axiWrite(d,GpuCommandMmioRegs.SUBMIT,1)
        }else image.submit(id)
        val readFaults=if(name=="descriptor-read")Set(image.pa(0x410000))else if(name=="draw-read")Set(image.pa(0x413000))else Set.empty[BigInt]
        var readFailures=0;var writeFailures=0
        val activeBefore=d.io.performance.get.system.activeCuCycles.peek().litValue
        image.run(status,id,a=>{val fail=readFaults(a);if(fail)readFailures+=1;fail},(a,_,_)=>{
          val fail=name=="physical-store" && a>=0x1000000;if(fail)writeFailures+=1;fail})
        if(name=="envelope")assert(image.reads.isEmpty && image.writes.isEmpty)
        if(readFaults.nonEmpty)assert(readFailures>0)
        if(name=="physical-store")assert(writeFailures>0)
        else if(status!=0)assert(!image.writes.exists(_>=0x1000000),s"failed native job published backing bytes: $name")
        if(name=="execute-permission")assert(!image.reads.contains(image.pa(0x401000)),"execute-denied instruction must not reach physical AXI")
        if(rasterFaults(name))d.io.performance.get.system.activeCuCycles.expect(activeBefore.U)
        if(status==0)image.verifyDraws(0,centroid=polygon,polygon=polygon,sign=sign)
        axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0,expectedResponse=2);image.pop()
      }
      if(polygon)info("NATIVE_POLYGON_FAULTS faultScenarios=13 malformedRasterStates=5 noMalformedStateCUWork=true signedBiasedHealthyReuse=true envelopeDescriptorPermissionShaderStoreFaults=true noFalsePublication=true heldCompletionPinsVM=true")
      else info("NATIVE_TYPED_AXI faultScenarios=8 healthyReuse=true envelopeBeforeAccess=true reservedBeforeShaders=true lateDrawReadFault=true scratchDescriptorAlias=true Sv32WritePermission=true instructionExecutePermission=true physicalStoreFault=true noFalsePublication=true attributedCommand=true")
    }
  }
  for(polygon<-Seq(false,true))
  it should s"drain live native shader payloads queued DMA and physical publication before reset releases the VM polygon=$polygon" in {
    simulate(new GpuHostSystemAxi(graphics,gpu,numComputeUnits=nativeComputeUnits,fragCore=true,sharedGraphics=true,parallelGraphics=true,
      memoryAxiDataBytes=32,instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1,
      exposePerformance=true,typedGraphics=Some(profile(true)))){d=>
      initialize(d);val image=new Image(d);val perf=d.io.performance.get
      axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0x80000030);axiWrite(d,GpuCommandMmioRegs.INSTRUCTION_SATP,0x80000030)
      axiWrite(d,GpuCommandMmioRegs.TLB_FLUSH,1);axiWrite(d,RenderHostRegs.IRQ,1)
      for(phase<-0 until(if(polygon)3 else 2)){
        val mode=if(phase==0 || (polygon && phase==1))0 else 2
        val version=if(polygon && phase==1)4 else if(polygon)3 else 1
        val sign=if(phase==1)-1 else 1
        if(polygon)image.shaderDraws(mode,centroid=true,version=version,polygon=true,sign=sign)
        else if(phase==0)image.shaderDraws(0)else image.setup(2)
        image.submit(100+phase)
        if(phase==0 || polygon){
          axiWrite(d,GpuCommandMmioRegs.COMMAND_ID,120);axiWrite(d,GpuCommandMmioRegs.OPCODE,GpuCommandOpcode.fill.litValue.toInt)
          axiWrite(d,GpuCommandMmioRegs.DESTINATION,0x460000);axiWrite(d,GpuCommandMmioRegs.BYTES,64)
          axiWrite(d,GpuCommandMmioRegs.PATTERN,0xdeadbeef);axiWrite(d,GpuCommandMmioRegs.SUBMIT,1)
        }
        var requested=false;var live=BigInt(0)
        def resetAtBoundary(): Unit={
          requested=true;live=perf.payloadReservedBytes.peek().litValue
          if(phase==0 || (polygon && phase==1))assert(live>0,"a native shader fetch must own its payload reservation")
          axiWrite(d,GpuCommandMmioRegs.RESET,1)
          axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0,expectedResponse=2)
        }
        serviceMemoryMaster(d,image.read,writeAckDelay=29,readResponseDelay=17,maxCycles=300000,
          onReadAccepted=a=>if(!requested && ((phase==0 && a==image.pa(0x401000)) ||
            (polygon && phase==1 && a==image.pa(0x408000))))resetAtBoundary(),
          onWriteAccepted=a=>if((if(polygon)phase==2 else phase==1) && !requested && a>=0x1000000)resetAtBoundary())(image.write){requested && d.io.m_irq.peek().litToBoolean}
        assert(requested && (axiRead(d,GpuCommandMmioRegs.STATUS)&10)==0)
        assert(!image.writes.exists(a=>a>=image.pa(0x460000) && a<image.pa(0x460040)),"reset must discard the queued DMA command")
        perf.payloadReservedBytes.expect(0.U);perf.payloadPinsActive.expect(0.U)
        assert(perf.payloadAllocations.peek().litValue==perf.payloadReleases.peek().litValue)
        axiWrite(d,RenderHostRegs.IRQ,3);axiWrite(d,GpuCommandMmioRegs.TLB_FLUSH,1)
        image.shaderDraws(mode,centroid=polygon,version=version,polygon=polygon,sign=sign)
        image.submit(140+phase);image.run(id=140+phase);image.verifyDraws(mode,centroid=polygon,polygon=polygon,sign=sign);image.pop()
        perf.payloadReservedBytes.expect(0.U)
        if(polygon)info(s"NATIVE_POLYGON_RESET phase=$phase livePayloadBytes=$live vertexFragmentPublicationCuts=true queuedDMAAborted=true oldCompletionsDiscarded=true physicalAckDrain=true VMWriteRejected=true reservationsReleased=true exactBiasedHealthyReuse=true")
        else info(s"NATIVE_TYPED_RESET phase=$phase livePayloadBytes=$live queuedDMAAborted=true oldCompletionsDiscarded=true physicalAckDrain=true VMWriteRejected=true reservationsReleased=true exactHealthyReuse=true")
      }
    }
  }

  for(local<-Seq(true,false))
  it should s"publish native sampler policies through actual vertex and fragment texture instructions local=$local" in {
    simulate(new GpuHostSystemAxi(graphics,gpu,numComputeUnits=nativeComputeUnits,fragCore=true,sharedGraphics=true,parallelGraphics=true,
      memoryAxiDataBytes=32,instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1,
      exposePerformance=true,typedGraphics=Some(profile(local)),visibilityQueries=visibilityQueries,primitiveQueries=primitiveQueries)){d=>
      initialize(d);val image=new Image(d)
      val cap=Vector[BigInt](BigInt("47545801",16),8,3,0x3ff,7,16,16383,0x1f)
      for((value,i)<-cap.zipWithIndex)assert(axiRead(d,0x200+i*4)==value)
      val formatCap=Vector[BigInt](BigInt("47544601",16),8,1,6,64,0xffffffffL,0xffffffffL,1)
      for((value,i)<-formatCap.zipWithIndex)assert(axiRead(d,0x220+i*4)==value)
      axiWrite(d,0x220,0,expectedResponse=2)
      axiWrite(d,0x200,0,expectedResponse=2)
      axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0x80000030);axiWrite(d,GpuCommandMmioRegs.INSTRUCTION_SATP,0x80000030)
      axiWrite(d,GpuCommandMmioRegs.TLB_FLUSH,1);axiWrite(d,RenderHostRegs.IRQ,1)
      val broadcast=(BigInt(0x17)<<26)|(BigInt(1)<<25)|(BigInt(4)<<12)
      val sample=(BigInt(1)<<26)|(BigInt(1)<<25)|(BigInt(6)<<20)|(BigInt(5)<<15)|(BigInt(7)<<7)|0x2b
      val texture=Seq((BigInt(0xffffc)<<12)|(BigInt(12)<<7)|0x37,(BigInt(0x14)<<12)|(BigInt(13)<<7)|0x37,
        broadcast|(BigInt(12)<<15)|(BigInt(5)<<7)|0x57,broadcast|(BigInt(13)<<15)|(BigInt(6)<<7)|0x57,sample)
      var frames=0;var faultFrames=0
      def run(u: Int,v: Int,signed: Boolean,vertex: Boolean,mode: Int,bad: Int=0): Unit={
        val expected=NativeTexturePolicyReference.sample(3,5,0xffffc000L,0x14000L,u,v,signed)
        image.sampledRed=Some(expected);image.shaderDraws(mode,generation=frames+1)
        val base=0x460000+frames*0x1000
        for(y<-0 until 5;x<-0 until 3)image.word(image.pa(base+(y*3+x)*4),NativeTexturePolicyReference.word(x,y))
        val vp=0x470000+frames*0x2000;val fp=vp+0x1000
        val vertexCode=if(vertex)vertexProgram.dropRight(1)++texture++Seq(addi(6,7,15*32),vm(6,7,true),BigInt("30500073",16))else vertexProgram
        val fragmentCode=if(!vertex)fragmentProgram().dropRight(1)++texture++
          (0 until 4).flatMap(c=>Seq(addi(6,7,(10+c*4)*32),vm(6,7,true)))++Seq(BigInt("30500073",16))else fragmentProgram()
        vertexCode.zipWithIndex.foreach{case(w,i)=>image.word(image.pa(vp+i*4),w)}
        fragmentCode.zipWithIndex.foreach{case(w,i)=>image.word(image.pa(fp+i*4),w)}
        for(n<-0 until 3){val record=image.pa(0x412000+n*4096)
          image.word(record+33*4,vp);image.word(record+516*4,fp);image.word(record+19*4,1)
          val first=if(vertex)48 else 544
          image.word(record+(first+1)*4,base);image.word(record+(first+2)*4,if(bad==1)0 else 3)
          image.word(record+(first+3)*4,5);image.word(record+(first+8)*4,1)
          image.word(record+(first+9)*4,if(bad==2)3 else u);image.word(record+(first+10)*4,v)
          image.word(record+(first+11)*4,if(signed)1 else 0)
        }
        axiWrite(d,GpuCommandMmioRegs.TLB_FLUSH,1)
        val id=180+frames;image.submit(id);image.run(if(bad==1)3 else if(bad==2)1 else 0,id)
        if(bad==0)image.verifyDraws(mode)
        else{assert(!image.reads.exists(a=>a>=image.pa(base) && a<image.pa(base+64)));faultFrames+=1}
        axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0,expectedResponse=2);image.pop();frames+=1
      }
      run(0,2,true,vertex=true,mode=0)
      run(1,0,true,vertex=false,mode=0)
      run(2,1,true,vertex=true,mode=2)
      run(1,2,false,vertex=false,mode=2)
      run(2,0,true,vertex=false,mode=0,bad=1)
      run(0,1,true,vertex=true,mode=0,bad=2)
      run(2,0,true,vertex=true,mode=0)
      assert(frames==7 && faultFrames==2)
      info(s"NATIVE_TEXTURE_POLICY_IMAGE local=$local CUs=2 frames=$frames submittedDraws=21 faultFrames=$faultFrames vertexAndFragment=true signedAndUnsigned=true mixedAxes=true modes1x4x=true GTX1=true noInvalidTextureReads=true exactMRTDepthResolveGuards=true heldCompletionPinsVM=true freshCodeAndTextureAllocations=true healthyReuse=true")
    }
  }
  for(local<-Seq(true,false))
  it should s"execute GPU bound typed fetches in both native shader stages with translated descriptor ownership local=$local" in {
    simulate(new GpuHostSystemAxi(graphics,gpu,numComputeUnits=nativeComputeUnits,fragCore=true,sharedGraphics=true,parallelGraphics=true,
      memoryAxiDataBytes=32,instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1,
      exposePerformance=true,typedGraphics=Some(profile(local)),visibilityQueries=visibilityQueries,primitiveQueries=primitiveQueries)){d=>
      initialize(d);val image=new Image(d);val texData=NativeTextureFetchTestData
      assert((axiRead(d,0x20c)&512)==512)
      axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0x80000030);axiWrite(d,GpuCommandMmioRegs.INSTRUCTION_SATP,0x80000030)
      axiWrite(d,GpuCommandMmioRegs.TLB_FLUSH,1);axiWrite(d,RenderHostRegs.IRQ,1)
      val broadcast=(BigInt(0x17)<<26)|(BigInt(1)<<25)|(BigInt(4)<<12)
      val id=Seq[BigInt](7,8,9);val vo=BigInt(0x500000);val fo=BigInt(0x600000)
      val rgba=Vector(0.125,0.25,0.5,1.0).map(Fp32ViewportReference.fp)
      val raw=rgba.zipWithIndex.map{case(v,c)=>v<<(32*c)}.reduce(_|_)
      var frames=0
      def fetch(c: Int,unit: Int,packet: BigInt,scalar: Boolean): Seq[BigInt]={
        val hi=(packet+2048)>>12;val lo=packet-(hi<<12)
        val constants=Seq((hi<<12)|(BigInt(12)<<7)|0x37,addi(12,12,lo.toInt),addi(13,0,unit))
        if(scalar)constants++Seq(texData.scalar(c,15,12,13),broadcast|(BigInt(15)<<15)|(BigInt(7)<<7)|0x57)
        else constants++Seq(broadcast|(BigInt(12)<<15)|(BigInt(5)<<7)|0x57,
          broadcast|(BigInt(13)<<15)|(BigInt(6)<<7)|0x57,texData.vector(c,7,5,6))
      }
      def run(c: Int,bad: Boolean=false,format: Int=35): Unit={
        val mode=if(c%2==0)0 else 2;val vc=(c+1)%4;val target=if(format>=49)0 else 3
        val decoded=if(format>=54)CompressedImageReference.decode(format,raw)else ImageFormatReference.decode(ImageFormatReference.names(format),raw)
        val swizzle=if(format>=36)Seq(3,2,5,4)else Seq.empty[Int]
        val kind=if(format>=54)0 else ImageFormatReference.format(ImageFormatReference.names(format)).kind
        val options=decoded++Seq(BigInt(0),if(kind==0)BigInt(0x3f800000L)else BigInt(1))
        val mapped=if(swizzle.isEmpty)decoded else swizzle.map(options)
        image.sampledRed=Some(mapped(c));image.sampledGreen=Some(mapped(vc));image.shaderDraws(mode,generation=frames+1,freshTextureSources=true)
        // Thirty-four frames must not advance source attributes into native
        // scratch at0x430000..0x443000 or the growing shader code; all selected frame input ranges stay disjoint.
        assert(0x420000+image.sourceOffset>=0x640000 && 0x422000+image.sourceOffset<=0x700000)
        def put(a: BigInt,v: BigInt): Unit=image.word(image.pa(a),v)
        def byte(a: BigInt,v: Int): Unit={image.memory(image.pa(a))=v}
        texData.roots(put,stage=0,id=id,offset=vo);texData.roots(put,stage=1,id=id,offset=fo)
        texData.install(put,byte,31,format,target,0,id,raw,vo,swizzle);texData.install(put,byte,17,format,2,1,id,raw,fo,swizzle)
        texData.coordinates(put,target=target,id=id,offset=vo);texData.coordinates(put,target=2,id=id,offset=fo)
        val vp=0x470000+frames*0x2000;val fp=vp+0x1000
        val vcode=vertexProgram.dropRight(1)++fetch(vc,31,vo+texData.packet,scalar=c%2==1)++Seq(addi(6,7,16*32),vm(6,7,true),BigInt("30500073",16))
        val fcode=fragmentProgram().dropRight(1)++fetch(c,17,fo+texData.packet,scalar=c%2==0)++
          (0 until 4).flatMap(a=>Seq(addi(6,7,(10+a*4)*32),vm(6,7,true)))++Seq(BigInt("30500073",16))
        vcode.zipWithIndex.foreach{case(w,n)=>put(vp+n*4,w)};fcode.zipWithIndex.foreach{case(w,n)=>put(fp+n*4,w)}
        for(n<-0 until 3){val record=image.pa(0x412000+n*4096)
          image.word(record+33*4,vp);image.word(record+516*4,fp);image.word(record+19*4,3)
          image.word(record+608*4,1);image.word(record+609*4,vo+texData.root)
          image.word(record+624*4,1);image.word(record+625*4,fo+texData.root)
        }
        if(bad){
          // Replace only this superpage with ordinary Sv32 pages, then deny
          // the fragment sampler page while retaining instruction/other reads.
          val pt=BigInt(0x31000);image.word(0x30004,(pt>>12)<<10|1)
          for(page<-0 until 1024)image.word(pt+page*4,((BigInt(0x800000+page*4096)>>12)<<10)|0xcf)
          val denied=(fo+texData.samplers+17*64)>>12
          image.word(pt+(denied&1023)*4,((image.pa(denied<<12)>>12)<<10)|0x49)
        }
        axiWrite(d,GpuCommandMmioRegs.TLB_FLUSH,1);image.submit();withClue(s"format=$format frame=$frames component=$c mode=$mode local=$local "){image.run(if(bad)3 else 0)}
        if(bad){val page=image.pa((fo+texData.samplers)& ~BigInt(4095));assert(!image.reads.exists(a=>a>=page && a<page+4096))}
        else{
          image.verifyDraws(mode)
          for(base<-Seq(vo,fo);address<-Seq(texData.root,texData.bindings,texData.images,texData.levels,texData.samplers,texData.packet)){
            val page=image.pa((base+address)& ~BigInt(4095));assert(image.reads.exists(a=>a>=page && a<page+4096),s"native typed descriptor page not read: ${page.toString(16)}")
          }
        }
        axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0,expectedResponse=2);image.pop();frames+=1
      }
      for(c<-0 until 4)run(c);run(0,bad=true);run(3)
      for(format<-36 until 64)run(format%4,format=format)
      assert(frames==34)
      info(s"NATIVE_TYPED_FETCH_AXI local=$local CUs=2 frames=$frames draws=102 newUncompressed18AndCompressed10=true imageSwizzle=true GTD1RootReferences=true bothStages=true scalarVector=true components4=true bindings31And17=true volumeAndCube=true realSv32=true deniedSamplerNoPhysicalRead=true exactMRTDepthResolveGuards=true heldCompletionPinsVM=true healthyReuse=true")
    }
  }

  }

  for(local<-Seq(true,false))
  it should s"execute captured floating samples through native AXI frames and translated descriptor ownership local=$local" in {
    simulate(new GpuHostSystemAxi(graphics,gpu,numComputeUnits=nativeComputeUnits,fragCore=true,sharedGraphics=true,parallelGraphics=true,
      memoryAxiDataBytes=32,instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1,
      exposePerformance=true,typedGraphics=Some(profile(local)),visibilityQueries=visibilityQueries,primitiveQueries=primitiveQueries)){d=>
      initialize(d);val image=new Image(d);val texData=NativeTextureFetchTestData
      assert((axiRead(d,0x20c)&512)==512)
      axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0x80000030);axiWrite(d,GpuCommandMmioRegs.INSTRUCTION_SATP,0x80000030)
      axiWrite(d,GpuCommandMmioRegs.TLB_FLUSH,1);axiWrite(d,RenderHostRegs.IRQ,1)
      val broadcast=(BigInt(0x17)<<26)|(BigInt(1)<<25)|(BigInt(4)<<12)
      val id=Seq[BigInt](7,8,9);val vo=BigInt(0x500000);val fo=BigInt(0x600000)
      val rgba=Vector(0.125,0.25,0.5,1.0).map(Fp32ViewportReference.fp)
      val raw=rgba.zipWithIndex.map{case(v,c)=>v<<(32*c)}.reduce(_|_)
      var frames=0
      def sample(c: Int,unit: Int,scalar: Boolean,target: Int,mode: Int,missing: Boolean): Seq[BigInt]={
        def constant(rd: Int,value: BigInt): Seq[BigInt]={
          val hi=(value+2048)>>12;val lo=(value-(hi<<12)).toInt
          Seq((hi<<12)|(BigInt(rd)<<7)|0x37,addi(rd,rd,lo))
        }
        val coords=if(target==2)Vector(-0.75,-0.75,-1.0)else Vector(0.75,0.75,if(target==3)0.75 else 0.0)
        val words=coords.map(Fp32ViewportReference.fp)++Vector(Fp32ViewportReference.fp(if(target==1)2 else 0))++Vector.fill(12)(BigInt(0))
        val params=if(missing)Seq.empty else (0 until 8).flatMap{pair=>
          val load=constant(12,words(pair*2))++constant(13,words(pair*2+1))
          val instruction=(BigInt(3)<<(if(scalar)25 else 26))|(if(scalar)BigInt(0)else BigInt(1)<<25)|
            (BigInt(if(scalar)13 else 11)<<20)|(BigInt(if(scalar)12 else 10)<<15)|(BigInt(pair)<<12)|(if(scalar)0x0b else 0x2b)
          load++(if(scalar)Seq.empty else Seq(broadcast|(BigInt(12)<<15)|(BigInt(10)<<7)|0x57,
            broadcast|(BigInt(13)<<15)|(BigInt(11)<<7)|0x57))++Seq(instruction)
        }
        val constants=Seq(addi(13,0,unit),addi(14,0,mode))
        params++constants++(if(scalar)Seq((BigInt(4)<<25)|(BigInt(14)<<20)|(BigInt(13)<<15)|(BigInt(c)<<12)|(BigInt(15)<<7)|0x0b,
          broadcast|(BigInt(15)<<15)|(BigInt(7)<<7)|0x57)
        else Seq(broadcast|(BigInt(13)<<15)|(BigInt(5)<<7)|0x57,broadcast|(BigInt(14)<<15)|(BigInt(6)<<7)|0x57,
          (BigInt(4)<<26)|(BigInt(1)<<25)|(BigInt(6)<<20)|(BigInt(5)<<15)|(BigInt(c)<<12)|(BigInt(7)<<7)|0x2b))
      }
      def run(c: Int,bad: Boolean=false,format: Int=capturedDefaultFormat,target: Int=0,modeControl: Int=1,missing: Boolean=false): Unit={
        val mode=if(c%2==0)0 else 2;val vc=(c+1)%4
        val decoded=if(format>=54)CompressedImageReference.decode(format,raw)else ImageFormatReference.decode(ImageFormatReference.names(format),raw)
        val swizzle=if(format>=36 || (groupedTexture && format==3))Seq(3,2,5,4)else Seq.empty[Int]
        val kind=if(format>=54)0 else ImageFormatReference.format(ImageFormatReference.names(format)).kind
        val options=decoded++Seq(BigInt(0),if(kind==0)BigInt(0x3f800000L)else BigInt(1))
        val mapped=if(swizzle.isEmpty)decoded else swizzle.map(options)
        image.sampledRed=Some(mapped(c));image.sampledGreen=Some(mapped(vc));image.shaderDraws(mode,generation=frames+1,freshTextureSources=true)
        // Keep disjoint source page pairs outside texture/packet data,private
        // scratch and shader code. Each frame also has fresh code addresses.
        assert(0x420000+image.sourceOffset>=0x640000 && 0x422000+image.sourceOffset<=0x700000)
        def put(a: BigInt,v: BigInt): Unit=image.word(image.pa(a),v)
        def byte(a: BigInt,v: Int): Unit={image.memory(image.pa(a))=v}
        texData.roots(put,stage=0,id=id,offset=vo);texData.roots(put,stage=1,id=id,offset=fo)
        texData.install(put,byte,31,format,target,0,id,raw,vo,swizzle);texData.install(put,byte,17,format,target,1,id,raw,fo,swizzle)
        if(groupedTexture && format==3 && target!=3)for((base,unit)<-Seq((vo,31),(fo,17))){
          // Exercise the aligned shared path, retaining the unaligned/default
          // fixtures for every legacy qualification and non-RGBA8 format.
          val data=base+0x20000+unit*4096
          val level=base+texData.levels+unit*128
          put(level+12*4,data);put(level+17*4,64);put(level+21*4,if(target==1 || target==2)128 else 0)
          val selected=data+64+4+(if(target==1)256 else if(target==2)640 else 0)
          for(n<-0 until 4)byte(selected+n,((raw>>(8*n))&255).toInt)
        }
        if(format>=54)for(base<-Seq(vo,fo);unit<-Seq(if(base==vo)31 else 17)){
          put(base+texData.images+unit*128+11*4,2);put(base+texData.images+unit*128+12*4,2)
          put(base+texData.levels+unit*128+13*4,2);put(base+texData.levels+unit*128+14*4,2)
        }
        val vp=0x700000+frames*0x2000;val fp=vp+0x1000
        val vcode=vertexProgram.dropRight(1)++sample(vc,31,scalar=c%2==1,target=target,mode=modeControl,missing=false)++Seq(addi(6,7,16*32),vm(6,7,true),BigInt("30500073",16))
        val fcode=fragmentProgram().dropRight(1)++sample(c,17,scalar=c%2==0,target=target,mode=modeControl,missing=missing)++
          (0 until 4).flatMap(a=>Seq(addi(6,7,(10+a*4)*32),vm(6,7,true)))++Seq(BigInt("30500073",16))
        vcode.zipWithIndex.foreach{case(w,n)=>put(vp+n*4,w)};fcode.zipWithIndex.foreach{case(w,n)=>put(fp+n*4,w)}
        for(n<-0 until 3){val record=image.pa(0x412000+n*4096)
          image.word(record+33*4,vp);image.word(record+516*4,fp);image.word(record+19*4,3)
          image.word(record+608*4,1);image.word(record+609*4,vo+texData.root)
          image.word(record+624*4,1);image.word(record+625*4,fo+texData.root)
        }
        if(bad){
          // Replace only this superpage with ordinary Sv32 pages, then deny
          // the fragment sampler page while retaining instruction/other reads.
          val pt=BigInt(0x31000);image.word(0x30004,(pt>>12)<<10|1)
          for(page<-0 until 1024)image.word(pt+page*4,((BigInt(0x800000+page*4096)>>12)<<10)|0xcf)
          val denied=(fo+texData.samplers+17*64)>>12
          image.word(pt+(denied&1023)*4,((image.pa(denied<<12)>>12)<<10)|0x49)
        }
        axiWrite(d,GpuCommandMmioRegs.TLB_FLUSH,1);image.submit();withClue(s"format=$format frame=$frames component=$c mode=$mode local=$local "){image.run(if(bad || missing)3 else 0)}
        if(bad){val page=image.pa((fo+texData.samplers)& ~BigInt(4095));assert(!image.reads.exists(a=>a>=page && a<page+4096))}
        else if(!missing){
          image.verifyDraws(mode)
          for(base<-Seq(vo,fo);address<-Seq(texData.root,texData.bindings,texData.images,texData.levels,texData.samplers)){
            val page=image.pa((base+address)& ~BigInt(4095));assert(image.reads.exists(a=>a>=page && a<page+4096),s"native typed descriptor page not read: ${page.toString(16)}")
          }
        }
        if(missing){val page=image.pa(fo+texData.root)& ~BigInt(4095)
          assert(!image.reads.exists(a=>a>=page && a<page+4096),"new frame reused a previous task's operand bank")}
        for(base<-Seq(vo,fo)){val page=image.pa(base+texData.packet)& ~BigInt(4095)
          assert(!image.reads.exists(a=>a>=page && a<page+4096),"captured operands performed a GPU-memory packet read")}
        axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0,expectedResponse=2);image.pop();frames+=1
      }
      for(format<-capturedFloatingFormats)run(format%4,format=format)
      for(target<-1 to 3)run(target,target=target)
      for(mode<-Seq(0,2))run(mode,modeControl=mode)
      run(0,bad=true);run(1,missing=true);run(3)
      assert(frames==capturedFloatingFormats.size+8)
      if(groupedTexture){
        val perf=d.io.performance.get
        assert(perf.nativeTextureGroups.peek().litValue>0,"AXI frames never entered the shared texture pool")
        assert(perf.nativeTextureDataReads.map(_.peek().litValue).sum>0,"shared pool performed no data reads")
        assert(perf.nativeTexturePageWalks.map(_.peek().litValue).sum>0,"shared pool bypassed real Sv32")
      }
      image.queryProbe.foreach(_.report("TYPED",local,frames))
      info(s"NATIVE_FLOAT_SAMPLE_AXI local=$local CUs=$nativeComputeUnits frames=$frames submittedDraws=${frames*3} formats=${capturedFloatingFormats.size} grouped=$groupedTexture imageSwizzle=true GTD1RootReferences=true bothStages=true scalarVector=true components4=true targets4=true automaticExplicitGradient=true noOperandPacketReads=true realSv32=true deniedSamplerNoPhysicalRead=true taskResetRejectsStaleTuple=true exactMRTDepthResolveGuards=true heldCompletionPinsVM=true healthyReuse=true")
    }
  }

  if(!groupedTexture){
  for(local<-Seq(true,false))
  it should s"execute captured integer fetches through native AXI frames and translated descriptor ownership local=$local" in {
    simulate(new GpuHostSystemAxi(graphics,gpu,numComputeUnits=nativeComputeUnits,fragCore=true,sharedGraphics=true,parallelGraphics=true,
      memoryAxiDataBytes=32,instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1,
      exposePerformance=true,typedGraphics=Some(profile(local)),visibilityQueries=visibilityQueries,primitiveQueries=primitiveQueries)){d=>
      initialize(d);val image=new Image(d);val texData=NativeTextureFetchTestData
      assert((axiRead(d,0x20c)&512)==512)
      axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0x80000030);axiWrite(d,GpuCommandMmioRegs.INSTRUCTION_SATP,0x80000030)
      axiWrite(d,GpuCommandMmioRegs.TLB_FLUSH,1);axiWrite(d,RenderHostRegs.IRQ,1)
      val broadcast=(BigInt(0x17)<<26)|(BigInt(1)<<25)|(BigInt(4)<<12)
      val id=Seq[BigInt](7,8,9);val vo=BigInt(0x500000);val fo=BigInt(0x600000)
      val rgba=Vector(0.125,0.25,0.5,1.0).map(Fp32ViewportReference.fp)
      val raw=rgba.zipWithIndex.map{case(v,c)=>v<<(32*c)}.reduce(_|_)
      var frames=0
      def sample(c: Int,unit: Int,scalar: Boolean,target: Int,mode: Int,missing: Boolean,offset: Int): Seq[BigInt]={
        def constant(rd: Int,value: BigInt): Seq[BigInt]={
          val hi=(value+2048)>>12;val lo=(value-(hi<<12)).toInt
          Seq((hi<<12)|(BigInt(rd)<<7)|0x37,addi(rd,rd,lo))
        }
        val words=Vector[BigInt](if(offset==15)2 else 1,1,if(target==3)1 else 0,
          if(target==1)2 else 0,if(target==2)5 else 0,0)++Vector.fill(7)(BigInt(0))++Vector(BigInt(offset),BigInt(0),BigInt(0))
        val params=if(missing)Seq.empty else (0 until 8).flatMap{pair=>
          val load=constant(12,words(pair*2))++constant(13,words(pair*2+1))
          val instruction=(BigInt(3)<<(if(scalar)25 else 26))|(if(scalar)BigInt(0)else BigInt(1)<<25)|
            (BigInt(if(scalar)13 else 11)<<20)|(BigInt(if(scalar)12 else 10)<<15)|(BigInt(pair)<<12)|(if(scalar)0x0b else 0x2b)
          load++(if(scalar)Seq.empty else Seq(broadcast|(BigInt(12)<<15)|(BigInt(10)<<7)|0x57,
            broadcast|(BigInt(13)<<15)|(BigInt(11)<<7)|0x57))++Seq(instruction)
        }
        val constants=Seq(addi(13,0,unit),addi(14,0,mode))
        params++constants++(if(scalar)Seq((BigInt(5)<<25)|(BigInt(14)<<20)|(BigInt(13)<<15)|(BigInt(c)<<12)|(BigInt(15)<<7)|0x0b,
          broadcast|(BigInt(15)<<15)|(BigInt(7)<<7)|0x57)
        else Seq(broadcast|(BigInt(13)<<15)|(BigInt(5)<<7)|0x57,broadcast|(BigInt(14)<<15)|(BigInt(6)<<7)|0x57,
          (BigInt(5)<<26)|(BigInt(1)<<25)|(BigInt(6)<<20)|(BigInt(5)<<15)|(BigInt(c)<<12)|(BigInt(7)<<7)|0x2b))
      }
      def run(c: Int,bad: Boolean=false,format: Int=35,target: Int=0,modeControl: Int=0,missing: Boolean=false,integerOffset: Int=0,baseLevel: Int=0): Unit={
        val mode=if(c%2==0)0 else 2;val vc=(c+1)%4
        val decoded=if(format>=54)CompressedImageReference.decode(format,raw)else ImageFormatReference.decode(ImageFormatReference.names(format),raw)
        val swizzle=if(format>=36)Seq(3,2,5,4)else Seq.empty[Int]
        val kind=if(format>=54)0 else ImageFormatReference.format(ImageFormatReference.names(format)).kind
        val options=decoded++Seq(BigInt(0),if(kind==0)BigInt(0x3f800000L)else BigInt(1))
        val mapped=if(swizzle.isEmpty)decoded else swizzle.map(options)
        image.sampledRed=Some(mapped(c));image.sampledGreen=Some(mapped(vc));image.shaderDraws(mode,generation=frames+1,freshTextureSources=true)
        // Complete source page pairs are disjoint and avoid texture/packet,
        // private scratch and shader code allocations.
        assert(0x420000+image.sourceOffset>=0x640000 && 0x422000+image.sourceOffset<=0x700000)
        def put(a: BigInt,v: BigInt): Unit=image.word(image.pa(a),v)
        def byte(a: BigInt,v: Int): Unit={image.memory(image.pa(a))=v}
        texData.roots(put,stage=0,id=id,offset=vo);texData.roots(put,stage=1,id=id,offset=fo)
        texData.install(put,byte,31,format,target,0,id,raw,vo,swizzle);texData.install(put,byte,17,format,target,1,id,raw,fo,swizzle)
        if(format>=54)for(base<-Seq(vo,fo);unit<-Seq(if(base==vo)31 else 17)){
          put(base+texData.images+unit*128+11*4,2);put(base+texData.images+unit*128+12*4,2)
          put(base+texData.levels+unit*128+13*4,2);put(base+texData.levels+unit*128+14*4,2)
        }
        if(baseLevel>0)for(base<-Seq(vo,fo);unit<-Seq(if(base==vo)31 else 17)){
          val from=base+texData.levels+unit*128;val to=from+baseLevel*128
          val words=(0 until 32).map(w=>(0 until 4).foldLeft(BigInt(0))((v,b)=>v|(BigInt(image.memory.getOrElse(image.pa(from+w*4+b),0))<<(8*b))))
          words.zipWithIndex.foreach{case(v,w)=>put(to+w*4,v)};put(to+6*4,baseLevel);put(to+8*4,baseLevel)
          put(base+texData.images+unit*128+9*4,baseLevel);put(base+texData.images+unit*128+10*4,baseLevel)
          put(base+texData.images+unit*128+11*4,2<<baseLevel);put(base+texData.images+unit*128+12*4,2<<baseLevel)
          put(base+texData.images+unit*128+20*4,from+(baseLevel+1)*128)
        }
        val vp=0x700000+frames*0x2000;val fp=vp+0x1000
        val vcode=vertexProgram.dropRight(1)++sample(vc,31,scalar=c%2==1,target=target,mode=modeControl,missing=false,offset=integerOffset)++Seq(addi(6,7,16*32),vm(6,7,true),BigInt("30500073",16))
        val fcode=fragmentProgram().dropRight(1)++sample(c,17,scalar=c%2==0,target=target,mode=modeControl,missing=missing,offset=integerOffset)++
          (0 until 4).flatMap(a=>Seq(addi(6,7,(10+a*4)*32),vm(6,7,true)))++Seq(BigInt("30500073",16))
        vcode.zipWithIndex.foreach{case(w,n)=>put(vp+n*4,w)};fcode.zipWithIndex.foreach{case(w,n)=>put(fp+n*4,w)}
        for(n<-0 until 3){val record=image.pa(0x412000+n*4096)
          image.word(record+33*4,vp);image.word(record+516*4,fp);image.word(record+19*4,3)
          image.word(record+608*4,1);image.word(record+609*4,vo+texData.root)
          image.word(record+624*4,1);image.word(record+625*4,fo+texData.root)
        }
        if(bad){
          // Replace only this superpage with ordinary Sv32 pages, then deny
          // the fragment sampler page while retaining instruction/other reads.
          val pt=BigInt(0x31000);image.word(0x30004,(pt>>12)<<10|1)
          for(page<-0 until 1024)image.word(pt+page*4,((BigInt(0x800000+page*4096)>>12)<<10)|0xcf)
          val denied=(fo+texData.samplers+17*64)>>12
          image.word(pt+(denied&1023)*4,((image.pa(denied<<12)>>12)<<10)|0x49)
        }
        val reportFrame=frames%8==0 || bad || missing || baseLevel>0 || integerOffset!=0
        val frameStart=System.nanoTime()
        if(reportFrame)println(s"NATIVE_INTEGER_AXI_PROGRESS begin local=$local frame=$frames format=$format target=$target imageBase=$baseLevel offset=$integerOffset bad=$bad missing=$missing")
        axiWrite(d,GpuCommandMmioRegs.TLB_FLUSH,1);image.submit();withClue(s"format=$format frame=$frames component=$c mode=$mode local=$local "){image.run(if(bad || missing)3 else 0)}
        if(reportFrame)println(s"NATIVE_INTEGER_AXI_PROGRESS complete local=$local frame=$frames wallMillis=${(System.nanoTime()-frameStart)/1000000} expectedStatus=${if(bad || missing)3 else 0}")
        if(bad){val page=image.pa((fo+texData.samplers)& ~BigInt(4095));assert(!image.reads.exists(a=>a>=page && a<page+4096))}
        else if(!missing){
          image.verifyDraws(mode)
          for(base<-Seq(vo,fo);address<-Seq(texData.root,texData.bindings,texData.images,texData.levels+(if(java.lang.Boolean.getBoolean("opengpu.nativeIntegerOldMipPageAssertion"))0 else (if(base==vo)31 else 17)*128+baseLevel*128),texData.samplers)){
            val page=image.pa((base+address)& ~BigInt(4095));assert(image.reads.exists(a=>a>=page && a<page+4096),s"native typed descriptor page not read: ${page.toString(16)}")
          }
        }
        if(missing){val page=image.pa(fo+texData.root)& ~BigInt(4095)
          assert(!image.reads.exists(a=>a>=page && a<page+4096),"new frame reused a previous task's operand bank")}
        for(base<-Seq(vo,fo)){val page=image.pa(base+texData.packet)& ~BigInt(4095)
          assert(!image.reads.exists(a=>a>=page && a<page+4096),"captured operands performed a GPU-memory packet read")}
        axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0,expectedResponse=2);image.pop();frames+=1
      }
      val baseLevelsOnly=java.lang.Boolean.getBoolean("opengpu.nativeIntegerBaseLevelsOnly")
      if(!baseLevelsOnly){
        for(format<-0 until 64)run(format%4,format=format)
        for(target<-1 to 3)run(target,target=target)
        for(offset<-Seq(0,15))run(offset%4,integerOffset=offset)
      }
      for(base<-Seq(1,2))run(base,baseLevel=base)
      if(!baseLevelsOnly){run(0,bad=true);run(1,missing=true);run(3)}
      assert(frames==(if(baseLevelsOnly)2 else 74))
      info(s"NATIVE_INTEGER_FETCH_AXI local=$local CUs=2 frames=$frames submittedDescriptors=${frames*3} directedBaseOnly=$baseLevelsOnly imageBaseLevels0And1And2=true formats64=true imageSwizzle=true GTD1RootReferences=true bothStages=true scalarVector=true components4=true targets4=true signedOffsetBeforeBounds=true noOperandPacketReads=true realSv32=true deniedSamplerNoPhysicalRead=true taskResetRejectsStaleTuple=true exactMRTDepthResolveGuards=true heldCompletionPinsVM=true healthyReuse=true")
    }
  }

  for(local<-Seq(true,false))
  it should s"execute native texture size queries through native AXI frames and translated descriptor ownership local=$local" in {
    simulate(new GpuHostSystemAxi(graphics,gpu,numComputeUnits=nativeComputeUnits,fragCore=true,sharedGraphics=true,parallelGraphics=true,
      memoryAxiDataBytes=32,instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1,
      exposePerformance=true,typedGraphics=Some(profile(local)),visibilityQueries=visibilityQueries,primitiveQueries=primitiveQueries)){d=>
      initialize(d);val image=new Image(d);val texData=NativeTextureFetchTestData
      assert((axiRead(d,0x20c)&512)==512)
      axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0x80000030);axiWrite(d,GpuCommandMmioRegs.INSTRUCTION_SATP,0x80000030)
      axiWrite(d,GpuCommandMmioRegs.TLB_FLUSH,1);axiWrite(d,RenderHostRegs.IRQ,1)
      val broadcast=(BigInt(0x17)<<26)|(BigInt(1)<<25)|(BigInt(4)<<12)
      val id=Seq[BigInt](7,8,9);val vo=BigInt(0x500000);val fo=BigInt(0x600000)
      val rgba=Vector(0.125,0.25,0.5,1.0).map(Fp32ViewportReference.fp)
      val raw=rgba.zipWithIndex.map{case(v,c)=>v<<(32*c)}.reduce(_|_)
      var frames=0;var executed=0
      val reproduceSourceAlias=java.lang.Boolean.getBoolean("opengpu.textureSize.sourceAlias")
      val sourceLeases=mutable.ArrayBuffer.empty[(Int,Int)]
      def sample(c: Int,unit: Int,scalar: Boolean,target: Int,mode: Int,missing: Boolean,offset: Int): Seq[BigInt]={
        val constants=Seq(addi(13,0,unit),addi(14,0,mode))
        constants++(if(scalar)Seq((BigInt(6)<<25)|(BigInt(14)<<20)|(BigInt(13)<<15)|(BigInt(c)<<12)|(BigInt(15)<<7)|0x0b,
          broadcast|(BigInt(15)<<15)|(BigInt(7)<<7)|0x57)
        else Seq(broadcast|(BigInt(13)<<15)|(BigInt(5)<<7)|0x57,broadcast|(BigInt(14)<<15)|(BigInt(6)<<7)|0x57,
          (BigInt(6)<<26)|(BigInt(1)<<25)|(BigInt(6)<<20)|(BigInt(5)<<15)|(BigInt(c)<<12)|(BigInt(7)<<7)|0x2b))++Seq(BigInt("4a7193d7",16)) // vfcvt.f.x.v v7,v7: float export of the integer size
      }
      def run(c: Int,bad: Boolean=false,format: Int=35,target: Int=0,modeControl: Int=0,missing: Boolean=false,integerOffset: Int=0,baseLevel: Int=0,denySampler: Boolean=false): Unit={
        val selected=sys.props.get("opengpu.textureSize.frames").map(_.split(",").map(_.toInt).toSet)
        if(selected.exists(!_.contains(frames))){frames+=1;return}
        executed+=1
        val mode=if(c%2==0)0 else 2;val vc=(c+1)%3
        val dims=Vector(if(format>=54)7 else 2,if(format>=54){if(target==2)7 else 5}else 2,
          if(target==1)3 else if(target==3)2 else 0)
        val values=if(modeControl==1)dims.zipWithIndex.map{case(v,n)=>if(n<2)math.max(1,v>>1)else v}else dims
        val swizzle=if(format>=36)Seq(3,2,5,4)else Seq.empty[Int]
        image.sampledRed=Some(Fp32ViewportReference.fp(values(c)));image.sampledGreen=Some(Fp32ViewportReference.fp(values(vc)));image.shaderDraws(mode,generation=frames+1,sourceBias=0x190000,freshTextureSources= !reproduceSourceAlias)
        // Keep disjoint source page pairs outside texture/packet data,private
        // scratch and shader code. Each frame also has fresh code addresses.
        val sourceBegin=0x420000+image.sourceOffset;val sourceEnd=0x422000+image.sourceOffset
        if(!reproduceSourceAlias){
          assert(sourceBegin>=0x640000 && sourceEnd<=0x700000)
          assert(sourceLeases.forall{case(begin,end)=>sourceEnd<=begin || sourceBegin>=end},"texture frame source allocations overlap")
          sourceLeases+=((sourceBegin,sourceEnd))
        }
        def put(a: BigInt,v: BigInt): Unit=image.word(image.pa(a),v)
        def byte(a: BigInt,v: Int): Unit={image.memory(image.pa(a))=v}
        texData.roots(put,stage=0,id=id,offset=vo);texData.roots(put,stage=1,id=id,offset=fo)
        texData.install(put,byte,31,format,target,0,id,raw,vo,swizzle);texData.install(put,byte,17,format,target,1,id,raw,fo,swizzle)
        if(baseLevel>0)for(base<-Seq(vo,fo);unit<-Seq(if(base==vo)31 else 17)){
          val from=base+texData.levels+unit*128;val to=from+baseLevel*128
          val words=(0 until 32).map(w=>(0 until 4).foldLeft(BigInt(0))((v,b)=>v|(BigInt(image.memory.getOrElse(image.pa(from+w*4+b),0))<<(8*b))))
          words.zipWithIndex.foreach{case(v,w)=>put(to+w*4,v)};put(to+6*4,baseLevel);put(to+8*4,baseLevel)
          put(base+texData.images+unit*128+9*4,baseLevel);put(base+texData.images+unit*128+10*4,baseLevel+(if(modeControl==1)1 else 0))
          put(base+texData.images+unit*128+11*4,2<<baseLevel);put(base+texData.images+unit*128+12*4,2<<baseLevel)
          put(base+texData.images+unit*128+20*4,from+(baseLevel+1+(if(modeControl==1)1 else 0))*128)
          if(modeControl==1){val next=to+128
            words.zipWithIndex.foreach{case(v,w)=>put(next+w*4,v)};put(next+6*4,baseLevel+1);put(next+8*4,baseLevel+1)
            put(next+12*4,words(12)+256);put(next+13*4,1);put(next+14*4,1)
          }
        }
        val vp=0x700000+frames*0x2000;val fp=vp+0x1000
        val vcode=vertexProgram.dropRight(1)++sample(vc,31,scalar=c%2==1,target=target,mode=modeControl,missing=false,offset=integerOffset)++Seq(addi(6,7,16*32),vm(6,7,true),BigInt("30500073",16))
        val fcode=fragmentProgram().dropRight(1)++sample(c,17,scalar=c%2==0,target=target,mode=modeControl,missing=missing,offset=integerOffset)++
          (0 until 4).flatMap(a=>Seq(addi(6,7,(10+a*4)*32),vm(6,7,true)))++Seq(BigInt("30500073",16))
        vcode.zipWithIndex.foreach{case(w,n)=>put(vp+n*4,w)};fcode.zipWithIndex.foreach{case(w,n)=>put(fp+n*4,w)}
        for(n<-0 until 3){val record=image.pa(0x412000+n*4096)
          image.word(record+33*4,vp);image.word(record+516*4,fp);image.word(record+19*4,3)
          image.word(record+608*4,1);image.word(record+609*4,vo+texData.root)
          image.word(record+624*4,1);image.word(record+625*4,fo+texData.root)
        }
        if(bad || denySampler){
          // A denied image page faults before a physical read. A denied sampler
          // page must have no effect on this metadata-only operation.
          val pt=BigInt(0x31000);image.word(0x30004,(pt>>12)<<10|1)
          for(page<-0 until 1024)image.word(pt+page*4,((BigInt(0x800000+page*4096)>>12)<<10)|0xcf)
          val denied=(fo+(if(bad)texData.images+17*128 else texData.samplers+17*64))>>12
          image.word(pt+(denied&1023)*4,((image.pa(denied<<12)>>12)<<10)|0x49)
        }
        val reportFrame=true
        val frameStart=System.nanoTime()
        if(reportFrame)println(s"NATIVE_TEXTURE_SIZE_AXI_PROGRESS begin local=$local frame=$frames format=$format target=$target imageBase=$baseLevel offset=$integerOffset bad=$bad missing=$missing")
        axiWrite(d,GpuCommandMmioRegs.TLB_FLUSH,1);image.submit();withClue(s"format=$format frame=$frames component=$c mode=$mode local=$local "){image.run(if(bad || missing)3 else 0)}
        if(reportFrame)println(s"NATIVE_TEXTURE_SIZE_AXI_PROGRESS complete local=$local frame=$frames wallMillis=${(System.nanoTime()-frameStart)/1000000} expectedStatus=${if(bad || missing)3 else 0}")
        if(bad){val page=image.pa((fo+texData.images)& ~BigInt(4095));assert(!image.reads.exists(a=>a>=page && a<page+4096))}
        else if(!missing){
          withClue(s"query image local=$local frame=$frames component=$c format=$format target=$target base=$baseLevel lod=$modeControl denySampler=$denySampler "){image.verifyDraws(mode)}
          for(base<-Seq(vo,fo);address<-Seq(texData.root,texData.bindings,texData.images,texData.levels+(if(base==vo)31 else 17)*128+(baseLevel+math.max(0,modeControl))*128)){
            val page=image.pa((base+address)& ~BigInt(4095));assert(image.reads.exists(a=>a>=page && a<page+4096),s"native typed descriptor page not read: ${page.toString(16)}")
          }
        }
        for(base<-Seq(vo,fo)){val page=image.pa(base+texData.packet)& ~BigInt(4095)
          assert(!image.reads.exists(a=>a>=page && a<page+4096),"size query performed a GPU-memory packet read")
          val samplerPage=image.pa(base+texData.samplers)& ~BigInt(4095)
          assert(!image.reads.exists(a=>a>=samplerPage && a<samplerPage+4096),"size query read sampler state")
          val dataPage=image.pa(base+0x20000+(if(base==vo)31 else 17)*4096)& ~BigInt(4095)
          assert(!image.reads.exists(a=>a>=dataPage && a<dataPage+4096),"size query read texel data")}
        axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0,expectedResponse=2);image.pop();frames+=1
      }
      for(format<-0 until 64)run(format%3,format=format)
      for(target<-1 to 3)run(target%3,target=target)
      for(base<-Seq(1,2))run(base%3,baseLevel=base)
      run(0,baseLevel=1,modeControl=1)
      run(0,bad=true);run(1,modeControl= -1,missing=true);run(2,denySampler=true);run(0)
      assert(frames==74)
      if(sys.props.contains("opengpu.textureSize.frames"))info(s"NATIVE_TEXTURE_SIZE_AXI_DIRECTED local=$local originalFrames=${sys.props("opengpu.textureSize.frames")} executed=$executed exactImages=true")
      else info(s"NATIVE_TEXTURE_SIZE_AXI local=$local CUs=2 frames=$frames submittedDescriptors=${frames*3} formats64=true imageSwizzleIgnored=true bothStages=true scalarVector=true components3=true targets4=true imageBases0And1And2=true positiveRelativeLOD=true noParameterOrTexelOrSamplerReads=true realSv32=true deniedImageNoPhysicalRead=true deniedSamplerHealthy=true exactMRTDepthResolveGuards=true heldCompletionPinsVM=true healthyReuse=true")
    }
  }


  }
  }
}
