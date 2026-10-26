package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.system.{AxiTrafficObserver,GpuHostSystemAxi,GpuHostTestSupport}
import opengpu.testutil.{AxiTrafficCounters,GraphicsTestResolution}
import opengpu.testutil.GpuSim._
import org.scalatest.DoNotDiscover
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** Explicit local native-host integration; never an ordinary CI model build. */
@DoNotDiscover
class IndexedDrawBufferBlendHostQualification extends AnyFlatSpec with GpuHostTestSupport {
  import TypedColorReference.{fp,encode}
  private val gpu=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2)
  private val graphics=GraphicsConfig(screenWidth=8.min(GraphicsTestResolution.width),screenHeight=8.min(GraphicsTestResolution.height))
  private val vertex=VertexShaderStageConfig(VertexInputFetchConfig(1,1),1)
  private val fragment=FragmentShaderConfig(1,4)
  private val storage=TypedTileColorConfig(parallelLanes=true,parallelDepth=true,nativeSamples=true,streamingWordPorts=8)
  private val native=NativeTypedGraphicsConfig(vertex,fragment,storage,payload=None)
  private val stride=32
  private val clear=Vector(0.25,0.5,0.75,1.0).map(fp)
  private val constants=Vector(0.25,0.5,0.75,1.0).map(fp)
  private val sources=Vector(Vector(0.75,0.25,0.5,0.5),Vector(0.5,0.75,0.25,1.0)).map(_.map(fp))
  private val states=Vector(TypedBlendReference.State(enable=false),TypedBlendReference.State(1,1,1,1,3,4),
    TypedBlendReference.State(4,5,1,0),TypedBlendReference.State(11,12,1,1,0,1)).map(_.copy(constant=constants))
  private def masks(draw: Int): Vector[Int]=if(draw==0)Vector.fill(4)(15)else Vector(5,10,8,0)
  private def control(draw: Int,color: Int): BigInt={
    val s=states((color+draw)%4)
    BigInt(if(s.enable)1 else 0)|(BigInt(s.src)<<1)|(BigInt(s.dst)<<5)|(BigInt(s.sa)<<9)|
      (BigInt(s.da)<<13)|(BigInt(s.eq)<<17)|(BigInt(s.ea)<<20)|(BigInt(masks(draw)(color))<<23)
  }
  private def addi(rd: Int,rs: Int,n: Int): BigInt=(BigInt(n&4095)<<20)|(BigInt(rs)<<15)|(BigInt(rd)<<7)|0x13
  private def vm(base: Int,write: Boolean): BigInt=(BigInt(1)<<25)|(BigInt(base)<<15)|(BigInt(6)<<12)|(BigInt(1)<<7)|(if(write)0x27 else 7)
  private def broadcast(rd: Int): BigInt=(BigInt(0x17)<<26)|(BigInt(1)<<25)|(BigInt(rd)<<15)|(BigInt(4)<<12)|(BigInt(1)<<7)|0x57
  private val vertexCode={
    val b=mutable.ArrayBuffer[BigInt](BigInt("00241393",16),BigInt("007083b3",16),BigInt("c1027057",16))
    for(i<-0 until 4)b++=Seq(addi(5,7,i*stride),vm(5,false),addi(6,7,(7+i)*stride),vm(6,true))
    b++=Seq(addi(13,0,0),broadcast(13))
    for(i<-0 until 4)b++=Seq(addi(6,7,(11+i)*stride),vm(6,true))
    (b+=BigInt("30500073",16)).toVector
  }
  private def fragmentCode(draw: Int): Vector[BigInt]={
    val b=mutable.ArrayBuffer[BigInt](BigInt("00241393",16),BigInt("007083b3",16),BigInt("c1027057",16))
    for(c<-0 until 4;i<-0 until 4){val value=sources(draw)(i);require((value&4095)==0)
      b++=Seq((value&BigInt("fffff000",16))|(BigInt(13)<<7)|0x37,broadcast(13),addi(6,7,(10+c*4+i)*stride),vm(6,true))}
    b++=Seq(addi(13,0,61),broadcast(13),addi(6,7,27*stride),vm(6,true),BigInt("30500073",16));b.toVector // enable=1, depth override=0, RGBA mask=15
  }

  private class Image(mode: Int,colors: Int) {
    val bytes=mutable.Map.empty[BigInt,Int];private val lines=mutable.Set.empty[BigInt]
    val initial=mutable.Map.empty[BigInt,Int]
    private val imageViews=mutable.ArrayBuffer.empty[(BigInt,Int,Int,Int)] // base,pitch,samples,color (-1 = depth)
    private def allocate(base: Int,size: Int,fill: Int=0,guarded: Boolean=false): Unit={
      val first=BigInt(base)& ~BigInt(63);val end=(BigInt(base)+size+63)& ~BigInt(63)
      for(a<-first until end){bytes(a)=fill;if(guarded)initial(a)=fill}
      for(a<-first until end by 64)lines+=a
    }
    private def word(address: Int,value: BigInt): Unit=for(i<-0 until 4){require(bytes.contains(BigInt(address)+i));bytes(BigInt(address)+i)=((value>>(8*i))&255).toInt}
    private def record(base: Int,size: Int,magic: BigInt,fields: Map[Int,BigInt]): Unit={
      allocate(base,size);for((w,v)<-fields)word(base+w*4,v)
      word(base,magic);word(base+4,size);word(base+8,1)
    }
    private def view(start: Int,region: Int,color: Int): Map[Int,BigInt]={
      val samples=1<<mode;val base=region+59;val pitch=4*samples*4+5;val end=base+3*pitch+4*samples*4
      val limit=(end+63)& ~63;allocate(region,limit-region,0xa7,guarded=true)
      imageViews+=((BigInt(base),pitch,samples,color))
      Map[Int,BigInt](start->base,(start+1)->end,(start+3)->region,(start+4)->limit,
        (start+6)->4,(start+7)->4,(start+8)->pitch,(start+10)->1,(start+11)->1)
    }
    record(0x10000,512,BigInt("47544a01",16),Map[Int,BigInt](3->7,4->8,5->9,
      6->0x11000,7->(0x11000+1280),9->0x12000,10->(0x13000+2560),12->2,13->4096))
    allocate(0x10200,64)
    private var pass=Map[Int,BigInt](16->1,17->1,18->1,19->7,20->8,21->9,
      24->3,25->4,26->4,27->mode,28->1024,32->TypedDepthFormat.d24s8,34->1,
      35->fp(1.0),36->5)
    for(c<-0 until colors){val start=64+c*16
      pass++=Map[Int,BigInt](start->1,(start+1)->TypedColorFormat.id("RGBA8"),(start+2)->(1024+c*1024),
        (start+3)->15,(start+4)->1,(start+5)->clear(0),(start+6)->clear(1),(start+7)->clear(2),(start+8)->clear(3))
      pass++=view(128+c*16,0x50000+c*0x1000,c)
    }
    pass++=view(192,0x55000,-1)
    record(0x11000,1280,BigInt("47544901",16),pass)
    allocate(0x20000,512);vertexCode.zipWithIndex.foreach{case(w,i)=>word(0x20000+i*4,w)}
    for(draw<-0 until 2){val pc=0x21000+draw*4096;val code=fragmentCode(draw);allocate(pc,code.size*4)
      code.zipWithIndex.foreach{case(w,i)=>word(pc+i*4,w)}
      val vs=0x30000+draw*4096;val fs=0x32000+draw*4096;allocate(vs,512);allocate(fs,896)
      val fields=Map[Int,BigInt](16->2,17->1,18->4,32->1,33->0x20000,34->1,35->1,36->15,
        40->vs,41->(vs+480),43->vs,44->(vs+512),64->1,65->7,66->8,67->9,68->3,69->1,
        96->1,99->4,352->0x40000,353->(0x40000+48),355->0x40000,356->(0x40000+64),358->16,
        480->1,483->4,484->4,486->fp(1.0),496->1,497->4,498->4,500->1,501->mode,
        512->1,513->7,514->8,515->9,516->pc,520->fs,521->(fs+896),523->fs,524->(fs+896),
        528->1,529->4,530->15,536->mode,560->1,593->2,
        600->constants(0),601->constants(1),602->constants(2),603->constants(3))++
        (0 until 4).map(c=>(604+c)->control(draw,c))
      record(0x12000+draw*4096,2560,BigInt("47544401",16),fields)
    }
    allocate(0x40000,64)
    for((p,v)<-Vector(Vector(-1.0,-1.0,0.0,1.0),Vector(1.0,-1.0,0.0,1.0),Vector(-1.0,1.0,0.0,1.0)).zipWithIndex;
        (x,c)<-p.zipWithIndex)word(0x40000+v*16+c*4,fp(x))
    def contains(a: BigInt): Boolean=(a&63)==0 && lines(a)
    def read(a: BigInt): BigInt={val base=a& ~BigInt(63);require(contains(base))
      (0 until 64).foldLeft(BigInt(0))((v,i)=>v|(BigInt(bytes(base+i))<<(8*i)))}
    def write(a: BigInt,value: BigInt,mask: BigInt): Unit={require(contains(a))
      for(i<-0 until 64 if mask.testBit(i))bytes(a+i)=((value>>(8*i))&255).toInt}
    def verify(): Unit={
      val triangle=TriangleRasterReference.base.copy(coords=Vector((BigInt(0),BigInt(0)),(BigInt(1024),BigInt(0)),(BigInt(0),BigInt(1024))))
      val quads=TriangleRasterReference.raster(triangle,TriangleRasterReference.Settings(width=4,height=4,mode=mode,maxX=4,maxY=4),graphics)
      val covered=(for(q<-quads;l<-q.samples;s<-0 until (1<<mode) if(l.mask&(1<<s))!=0)yield(l.x,l.y,s)).toSet
      require(covered.nonEmpty)
      val expected=mutable.Map.empty[BigInt,Int]++initial
      for((base,pitch,samples,color)<-imageViews;y<-0 until 4;x<-0 until 4;s<-0 until samples){
        val packed=if(color<0)BigInt("05ffffff",16)else{
          var old=encode("RGBA8",clear)._1
          if(covered((x,y,s)))for(draw<-0 until 2){
            val value=TypedBlendReference.blend("RGBA8",sources(draw),old,states((color+draw)%4))
            val (bits,mask)=encode("RGBA8",value,masks(draw)(color));old=(old& ~mask)|(bits&mask)
          }
          old
        }
        for(i<-0 until 4)expected(base+y*pitch+(x*samples+s)*4+i)=((packed>>(8*i))&255).toInt
      }
      for((a,v)<-expected)assert(bytes(a)==v,s"indexed native image/guard ${a.toString(16)} got=${bytes(a)} expected=$v colors=$colors mode=$mode")
    }
  }

  it should "execute indexed native draw state and verify complete MRT and native single-target images" in {
    require(sys.props.get("opengpu.fullHostQualification").contains("1"))
    require(!GraphicsTestResolution.productQualification,"this bounded qualifier is not a product-rate test")
    simulate(new GpuHostSystemAxi(graphics,gpu,numComputeUnits=2,fragCore=true,sharedGraphics=true,parallelGraphics=true,
      memoryAxiDataBytes=32,instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1,
      exposePerformance=true,typedGraphics=Some(native))){d=>
      for((mode,colors)<-Seq((0,4),(2,4),(0,1),(2,1))){
        initialize(d);val image=new Image(mode,colors)
        assert(axiRead(d,0x400)==BigInt("47444201",16));assert(axiRead(d,0x408)==6 && axiRead(d,0x40c)==4)
        axiWrite(d,RenderHostRegs.IRQ,1);axiWrite(d,GpuCommandMmioRegs.COMMAND_ID,23)
        axiWrite(d,GpuCommandMmioRegs.OPCODE,7);axiWrite(d,GpuCommandMmioRegs.SOURCE,0x10000)
        axiWrite(d,GpuCommandMmioRegs.DESTINATION,0x10200);axiWrite(d,GpuCommandMmioRegs.BYTES,512)
        axiWrite(d,GpuCommandMmioRegs.SUBMIT,1)
        val traffic=new AxiTrafficCounters;val observer=new AxiTrafficObserver(d.io,traffic)
        serviceMemoryMaster(d,image.read,readFault=a=> !image.contains(a& ~BigInt(63)),writeAckDelay=7,readResponseDelay=3,
          maxCycles=500000,writeFault=(a,_,_)=> !image.contains(a),onCycle=()=>observer.sample())(
          (a,v,m)=>if(image.contains(a))image.write(a,v,m)){d.io.m_irq.peek().litToBoolean}
        traffic.assertDrained();val result=axiRead(d,GpuCommandMmioRegs.COMPLETION)
        assert((result&255)==23 && ((result>>8)&7)==7 && ((result>>11)&15)==0 && result.testBit(15),s"indexed native completion ${result.toString(16)}")
        assert(axiRead(d,GpuCommandMmioRegs.COMPLETION_BYTES_LO)==5120)
        image.verify()
        info(s"INDEXED_NATIVE_HOST frame=4x4 samples=${1<<mode} buffers=$colors draws=2 strictBacking=true exactStoredImagesAndGuards=true trafficDrained=true nativeSamples=true clockQualified=false fpsQualified=false")
      }
    }
  }
}

object QualifyIndexedDrawBufferBlend {
  def main(args: Array[String]): Unit={
    require(sys.props.get("opengpu.fullHostQualification").contains("1"))
    require(args.length==2 && args(0)=="--reports","usage: --reports DIR")
    require(new IndexedDrawBufferBlendHostQualification().testNames.size==1)
    val passed=org.scalatest.tools.Runner.run(Array("-s",classOf[IndexedDrawBufferBlendHostQualification].getName,"-oD","-u",args(1)))
    require(passed,"indexed native blend qualification failed")
    println("INDEXED_NATIVE_BLEND PASS frames=4 draws=8 exactStoredImagesAndGuards=true clockQualified=false fpsQualified=false")
  }
}
