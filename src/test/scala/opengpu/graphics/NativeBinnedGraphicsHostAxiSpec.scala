package opengpu.graphics

import chisel3._
import opengpu.command.GpuCommandOpcode
import opengpu.config.GpuConfig
import opengpu.system.{GpuHostSystemAxi,GpuHostTestSupport}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

abstract class NativeBinnedGraphicsHostBaseSpec extends AnyFlatSpec with GpuHostTestSupport {
  protected def visibilityQueries: Boolean=false
  protected def transformFeedback: Boolean=false
  protected def virtualImages: Boolean=false
  protected def groupedTexture: Boolean=false
  protected def sharedTextureReset: Boolean=false
  import TypedColorReference.{fp,format,encode}
  private val gpu=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2)
  private val graphics=GraphicsConfig(screenWidth=17,screenHeight=9)
  private val p=VertexShaderStageConfig(VertexInputFetchConfig(2,2),2);private val f=FragmentShaderConfig(2,4)
  private def profile(local: Boolean)=NativeBinnedGraphicsConfig(p,f,engines=2,pageBytes=128,writeCredits=2,tileSlots=1,pagedCapacity=8,
    payload=if(local)Some(ShaderPayloadConfig(capacityBytes=4096,maxPayloadBytes=1024,owners=3,ports=if(groupedTexture)8 else 6))else None,
    translateImageViews=virtualImages,groupedTexture=groupedTexture,transformFeedback=transformFeedback)
  private def host(local: Boolean)=new GpuHostSystemAxi(graphics,gpu,numComputeUnits=if(groupedTexture)4 else 2,fragCore=true,sharedGraphics=true,parallelGraphics=true,
    memoryAxiDataBytes=32,instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1,
    exposePerformance=true,nativeBinnedGraphics=Some(profile(local)),visibilityQueries=visibilityQueries)
  private def parkMemory(d: GpuHostSystemAxi): Unit={
    // MMIO can advance the DUT between driver calls. Accept no memory request
    // until serviceMemoryMaster owns and records every AXI handshake.
    d.io.m_axi_arready.poke(false.B);d.io.m_axi_awready.poke(false.B);d.io.m_axi_wready.poke(false.B)
    d.io.m_axi_rvalid.poke(false.B);d.io.m_axi_bvalid.poke(false.B)
  }
  private def addi(rd: Int,rs: Int,n: Int): BigInt=(BigInt(n&4095)<<20)|(BigInt(rs)<<15)|(BigInt(rd)<<7)|0x13
  private def vm(base: Int,v: Int,write: Boolean): BigInt=(BigInt(1)<<25)|(BigInt(base)<<15)|(BigInt(6)<<12)|(BigInt(v)<<7)|(if(write)0x27 else 7)
  private val startup=Vector[BigInt](BigInt("00241393",16),BigInt("007083b3",16),BigInt("c1027057",16))
  private val vertexProgram=startup++(0 until 4).flatMap(i=>Seq(addi(5,7,i*32),vm(5,1,false),addi(6,7,(7+i)*32),vm(6,1,true)))++Seq(BigInt("30500073",16))
  private val fragmentProgram={val code=mutable.ArrayBuffer.empty[BigInt];code++=startup
    def scalar(offset: Int,array: Int): Unit=code++=Seq(addi(13,1,offset),(BigInt(13)<<15)|(BigInt(2)<<12)|(BigInt(13)<<7)|3,
      (BigInt(0x17)<<26)|(BigInt(1)<<25)|(BigInt(13)<<15)|(BigInt(4)<<12)|(BigInt(1)<<7)|0x57,addi(6,7,array*32),vm(6,1,true))
    for(i<-0 until 4;c<-0 until 4)scalar(896+i*16+c*4,10+i*4+c)
    scalar(960,27);scalar(964,26);code+=BigInt("30500073",16);code.toVector}
  private val centroidVertexProgram=startup++
    ((0 until 4).map(i=>(i,11+i))++(0 until 4).map(i=>(4+i,15+i))).flatMap{case(in,out)=>
      Seq(addi(5,7,in*32),vm(5,1,false),addi(6,7,out*32),vm(6,1,true))}++Seq(BigInt("30500073",16))
  private def centroidFragmentProgram(version: Int,depthColor: Boolean=false): Vector[BigInt]={
    val inputs=if(version==4)12 else 10;val prefix=(inputs+18)*32
    val code=mutable.ArrayBuffer.empty[BigInt];code++=startup
    for(location<-0 until 4;c<-0 until 4)
      code++=Seq(addi(5,7,(if(depthColor && c==0)2 else 6+c)*32),vm(5,1,false),addi(6,7,(inputs+4*location+c)*32),vm(6,1,true))
    for((offset,array)<-Seq((prefix+64,inputs+17),(prefix+68,inputs+16)))
      code++=Seq(addi(13,1,offset),(BigInt(13)<<15)|(BigInt(2)<<12)|(BigInt(13)<<7)|3,
        (BigInt(0x17)<<26)|(BigInt(1)<<25)|(BigInt(13)<<15)|(BigInt(4)<<12)|(BigInt(1)<<7)|0x57,
        addi(6,7,array*32),vm(6,1,true))
    (code.toVector:+BigInt("30500073",16))
  }
  private class Image(val d: GpuHostSystemAxi){
    val memory=mutable.Map.empty[BigInt,Int];val initial=mutable.Map.empty[BigInt,Int]
    val reads=mutable.ArrayBuffer.empty[BigInt];val writes=mutable.ArrayBuffer.empty[BigInt]
    var serial=0;var region=BigInt(0x400000);var imageBase=BigInt(0x4000000);var imageVaBase=BigInt(0x40000000)
    var generation=9;var mode=0;var instances=1;var drawCount=2;var centroidVersion=0
    var polygonOffset=false;var polygonSign=1;var overrideSecond=false
    var expectedSampledRed: Option[BigInt]=None
    def expectedRgba(h: Int): Vector[BigInt]=expectedSampledRed.map(red=>rgba(h).updated(0,red)).getOrElse(rgba(h))
    def centroid: Boolean=centroidVersion!=0
    def fragmentPrefix: Int=if(centroidVersion==4)960 else 896
    def centroidMask(h: Int): BigInt=if(h==0)5 else 3
    def gradient(h: Int,vertex: Int): Vector[BigInt]=Vector.tabulate(4){c=>
      fp(if(c==3){if(h==0)1.0 else 0.5}else ((vertex+1)*(c+1)+h)/16.0)}
    val names=Vector("RGBA8","RGB8","RGBA16F","SRGB8_ALPHA8");val clear=Vector(0.1,0.2,0.3,0.4).map(fp)
    case class Backing(location: Int,base: BigInt,pitch: Int,bytes: Int,resolve: Boolean)
    val backings=mutable.ArrayBuffer.empty[Backing]
    def pa(va: BigInt): BigInt=va+0x400000
    def word(a: BigInt,v: BigInt): Unit=for(b<-0 until 4)memory(a+b)=((v>>(b*8))&255).toInt
    private val header=Map[String,BigInt]("header.attributes"->16,"header.bindings"->16,"header.varyings"->16,"header.colors"->4,"header.engineViews"->4)
    def identity(prefix: String): Map[String,BigInt]=Map(prefix+"context"->BigInt(7),prefix+"pass"->BigInt(8),prefix+"generation"->BigInt(generation))
    def record(base: BigInt,words: Int,magic: BigInt,fields: Seq[NativeTypedGraphicsLayout.Field],values: Map[String,BigInt]): Unit={
      if(centroid)require(values.keySet.subsetOf(fields.map(_.name).toSet),s"unknown centroid record fields: ${values.keySet--fields.map(_.name)}")
      for(i<-0 until words)word(pa(base+i*4),0)
      word(pa(base),magic);word(pa(base+4),words*4);word(pa(base+8),1)
      val all=header++values
      for(field<-fields){val raw=all.getOrElse(field.name,BigInt(0))&((BigInt(1)<<(field.words*32))-1)
        for(i<-0 until field.words)word(pa(base+(field.word+i)*4),(raw>>(i*32))&0xffffffffL)}
    }
    vertexProgram.zipWithIndex.foreach{case(w,i)=>word(pa(0x401000+4*i),w)}
    fragmentProgram.zipWithIndex.foreach{case(w,i)=>word(pa(0x402000+4*i),w)}
    Seq(BigInt("0000a283",16),BigInt("0040a303",16),BigInt("0002a383",16),BigInt("00732023",16),BigInt("30500073",16))
      .zipWithIndex.foreach{case(w,i)=>word(pa(0x403000+4*i),w)}
    for((v,i)<-Vector(Vector(-1.0,-1.0,0.0,1.0),Vector(1.0,-1.0,0.0,1.0),Vector(-1.0,1.0,0.0,1.0)).zipWithIndex;c<-0 until 4)
      word(pa(0x421000+i*16+c*4),fp(v(c)))
    word(0x30004,((BigInt(0x800000)>>12)<<10)|0xcf)
    def rgba(h: Int): Vector[BigInt]=(if(h==0)Vector(0.25,0.5,0.75,1.0)else Vector(0.75,0.25,0.5,0.5)).map(fp)
    def patchRecord(va: BigInt,fields: Seq[NativeTypedGraphicsLayout.Field],name: String,value: BigInt): Unit={
      val f=fields.find(_.name==name).get
      for(i<-0 until f.words)word(pa(va+(f.word+i)*4),(value>>(i*32))&0xffffffffL)
    }
    def patchFrame(name: String,value: BigInt): Unit=patchRecord(region+0x11000,
      NativeBinnedGraphicsLayout.frameFields(new NativeBinnedGraphicsFrameRecord),name,value)
    def patchDraw(name: String,value: BigInt): Unit=patchRecord(region+0x12000,
      NativeBinnedGraphicsLayout.drawFields(new NativeBinnedGraphicsDrawRecord),name,value)
    def setup(sampleMode: Int=0,indexedInstances: Int=1,empty: Boolean=false,badShader: Boolean=false,loadImages: Boolean=false,
        vertexFormat: Int=VertexInputFormat.float32,fragmentVersion: Int=0,
        polygon: Boolean=false,sign: Int=1,shaderOverride: Boolean=false): Unit={
      require(fragmentVersion==0 || fragmentVersion==3 || fragmentVersion==4)
      centroidVersion=fragmentVersion
      require(!polygon || (centroid && Set(-1,1)(sign)))
      polygonOffset=polygon;polygonSign=sign;overrideSecond=shaderOverride
      region=BigInt(0x400000)+BigInt(serial)*0x400000;imageBase=BigInt(0x4000000)+BigInt(serial)*0x1000000
      imageVaBase=BigInt(0x40000000)+BigInt(serial)*0x400000
      generation=9+serial;serial+=1;mode=sampleMode;instances=indexedInstances;drawCount=if(empty)0 else 2
      initial.clear();reads.clear();writes.clear();backings.clear()
      word(0x30000+(region>>22)*4,((pa(region)>>12)<<10)|0xcf)
      val converted=vertexFormat!=VertexInputFormat.float32
      val vertexBase=if(converted || centroid)region+0x23000 else BigInt(0x421000)
      val vertexStride=if(!converted)16 else if(vertexFormat==VertexInputFormat.float16)8 else 4
      if(centroid){
        require(!converted && !badShader && !empty && instances==1)
        centroidVertexProgram.zipWithIndex.foreach{case(w,i)=>word(pa(region+0x24000+4*i),w)}
        centroidFragmentProgram(centroidVersion,depthColor=polygonOffset).zipWithIndex.foreach{case(w,i)=>word(pa(region+0x25000+4*i),w)}
        val zs=if(polygonOffset)Vector(-0.5,0.5,0.0)else Vector.fill(3)(0.0)
        for((v,i)<-Vector(Vector(-1.0,-1.0,zs(0),1.0),Vector(1.0,-1.0,zs(1),1.0),Vector(-1.0,1.0,zs(2),1.0)).zipWithIndex;c<-0 until 4)
          word(pa(vertexBase+i*16+c*4),fp(v(c)))
        for(h<-0 until 2;i<-0 until 3;c<-0 until 4)word(pa(region+0x26000+h*64+i*16+c*4),gradient(h,i)(c))
      }
      if(converted){
        require(Set(VertexInputFormat.float16,VertexInputFormat.snorm2101010)(vertexFormat))
        for((v,i)<-Vector(Vector(-1,-1,0,1),Vector(1,-1,0,1),Vector(-1,1,0,1)).zipWithIndex){
          val raw=v.zipWithIndex.foldLeft(BigInt(0)){case(bits,(n,c))=>
            val value=if(vertexFormat==VertexInputFormat.float16){if(n<0)0xbc00 else if(n>0)0x3c00 else 0}
              else if(c==3)1 else if(n<0)512 else if(n>0)511 else 0
            bits|(BigInt(value)<<(c*(if(vertexFormat==VertexInputFormat.float16)16 else 10)))}
          for(b<-0 until vertexStride)memory(pa(vertexBase)+i*vertexStride+b)=((raw>>(b*8))&255).toInt
        }
      }
      word(0x30400,((imageBase>>12)<<10)|0xcf)
      if(virtualImages)word(0x30000+(imageVaBase>>22)*4,((imageBase>>12)<<10)|0xcf)
      word(pa(region+0x130000),0x4000003c);word(pa(region+0x130004),region+0x130040)
      val sf=TypedFragmentStateLayout.fields(new TypedFragmentState(f))
      for(h<-0 until 2){val base=region+0x20000+h*512;for(i<-0 until 128)word(pa(base+i*4),0)
        for((x,i)<-Vector[BigInt](TypedFragmentStateLayout.magic,512,1,4,7,8,generation,h).zipWithIndex)word(pa(base+i*4),x)
        val a=region+0x40000+h*4096;val b=region+0x50000+h*4096
        val used=fragmentPrefix+72;val allocation=(used+63)& ~63
        val v=Map[String,BigInt]("version"->1,"engineCount"->2,"fragment.version"->(if(centroid)centroidVersion else 1),
          "fragment.centroidMask"->(if(centroid)centroidMask(h)else BigInt(0)),
          "fragment.shaderPc"->(if(centroid)region+0x25000 else if(badShader && h==1)BigInt(0x404000)else BigInt(0x402000)),
          "fragment.varyingSlots"->1,"fragment.colorSlots"->4,"fragment.colorMask"->15,"fragment.sampleMode"->mode,
          "fragment.allowDepthOverride"->(if(polygonOffset && overrideSecond && h==1)1 else 0),
          "fragment.sampler.texWidth"->1,"fragment.sampler.texHeight"->1,"fragment.scratch.base"->a,"fragment.scratch.limit"->(a+used),
          "fragment.scratch.allocationBase"->a,"fragment.scratch.allocationLimit"->(a+allocation),"scratch[0].base"->b,"scratch[0].limit"->(b+used),
          "scratch[0].allocationBase"->b,"scratch[0].allocationLimit"->(b+allocation),"tests.version"->1,"tests.depthEnable"->1,"tests.depthWrite"->1,
          "tests.depthFunction"->1,"tests.front.function"->6,"tests.back.function"->6,"blend.version"->1,"blend.enable"->h,
          "blend.srcRGB"->4,"blend.dstRGB"->5,"blend.srcAlpha"->1)++identity("fragment.identity.")
        if(centroid)require(v.keySet.subsetOf(sf.map(_.name).toSet),s"unknown centroid state fields: ${v.keySet--sf.map(_.name)}")
        for(field<-sf){val raw=v.getOrElse(field.name,BigInt(0))&((BigInt(1)<<(field.words*32))-1)
          for(i<-0 until field.words)word(pa(base+(field.word+i)*4),(raw>>(i*32))&0xffffffffL)}
        for(s<-Seq(a,b)){for(i<-0 until 4;c<-0 until 4)word(pa(s+fragmentPrefix+i*16+c*4),rgba(h)(c))
          word(pa(s+fragmentPrefix+64),if(polygonOffset && overrideSecond && h==1)63 else 61)
          word(pa(s+fragmentPrefix+68),fp(if(polygonOffset)0.125 else 0.5))}
      }
      word(pa(0x404000),0)
      var frame=Map[String,BigInt]("frame.version"->1,"frame.bins.producer.version"->1,"frame.bins.producer.arena.version"->1,
        "frame.bins.producer.arena.base"->(region+0x60000),"frame.bins.producer.arena.limit"->(region+0x80000),
        "frame.bins.producer.screenWidth"->17,"frame.bins.producer.screenHeight"->9,"frame.bins.producer.tileLog2"->3,
        "frame.bins.bins.version"->3,"frame.bins.bins.tileCount"->6,"frame.bins.bins.directoryBase"->(region+0x120000),
        "frame.bins.bins.directoryLimit"->(region+0x120180),"frame.bins.bins.pool.version"->1,"frame.bins.bins.pool.base"->(region+0x100000),
        "frame.bins.bins.pool.limit"->(region+0x110000),"frame.bins.bins.pool.pageSizeLog2"->7,"frame.states.version"->1,
        "frame.states.base"->(region+0x20000),"frame.states.limit"->(region+0x20400),"frame.scratchStride"->4096,
        "frame.scratch[0].base"->(region+0x40000),"frame.scratch[0].limit"->(region+0x42000),
        "frame.scratch[1].base"->(region+0x50000),"frame.scratch[1].limit"->(region+0x52000),
        "frame.image.version"->1,"frame.image.pixels.version"->1,"frame.image.pixels.colors.version"->1,
        "frame.image.pixels.colors.tileLog2"->3,"frame.image.pixels.colors.tileWidth"->8,"frame.image.pixels.colors.tileHeight"->8,
        "frame.image.pixels.colors.sampleMode"->mode,"frame.image.pixels.colors.reservedBytes"->4096,
        "frame.image.pixels.depth.format"->TypedDepthFormat.d32f,"frame.image.pixels.depth.loadOp"->1,"frame.image.pixels.depth.clearDepth"->fp(1))++
        identity("frame.bins.producer.arena.identity.")++identity("frame.bins.bins.pool.identity.")++identity("frame.states.identity.")++identity("frame.image.pixels.colors.identity.")
      var offset=4096
      for(i<-0 until 4){val k=s"frame.image.pixels.colors.colors[$i].";frame++=Map[String,BigInt](k+"enabled"->1,k+"format"->TypedColorFormat.id(names(i)),
        k+"offset"->offset,k+"channelMask"->15,k+"loadOp"->(if(loadImages)0 else 1))++clear.zipWithIndex.map{case(v,c)=>(k+s"clear[$c]",v)}
        offset+=((64*(1<<mode)*format(names(i)).bytes+63)& ~63)}
      for(resolve<-Seq(false,true);i<-0 until 5){val bytes=if(i==4)4 else format(names(i)).bytes;val samples=if(resolve)1 else 1<<mode
        val alloc=imageBase+(if(resolve)0x80000 else 0)+i*0x10000;val base=alloc+(if(resolve)59 else 61);val pitch=17*samples*bytes+7
        val end=(base+9*pitch+63)& ~BigInt(63);val k=if(resolve)s"frame.image.resolves[$i].destination."else s"frame.image.views[$i]."
        if(resolve)frame+=(s"frame.image.resolves[$i].enable"->BigInt(1))
        val delta=if(virtualImages)imageVaBase-imageBase else BigInt(0)
        frame++=Map[String,BigInt](k+"base"->(base+delta),k+"limit"->(base+delta+8*pitch+17*samples*bytes),k+"allocationBase"->(alloc+delta),k+"allocationLimit"->(end+delta),
          k+"imageWidth"->17,k+"imageHeight"->9,k+"rowPitch"->pitch,k+"readable"->(if(resolve)0 else 1),k+"writable"->1,k+"store"->1)
        for(a<-alloc until end){memory(a)=0xa7;initial(a)=0xa7};backings+=Backing(i,base,pitch,bytes,resolve)}
      record(region+0x11000,NativeBinnedGraphicsLayout.frameWords,NativeBinnedGraphicsLayout.frameMagic,
        NativeBinnedGraphicsLayout.frameFields(new NativeBinnedGraphicsFrameRecord),frame)
      for(h<-0 until drawCount){var draw=Map[String,BigInt]("draw.version"->1,"draw.state"->h,"draw.token"->(0xa0000000L+h),
        "draw.vertex.viewport.version"->1,"draw.vertex.viewport.extentX"->17,"draw.vertex.viewport.extentY"->9,"draw.vertex.viewport.farDepth"->fp(1),
        "draw.vertex.geometry.topology"->4,"draw.vertex.geometry.vertex.version"->1,
        "draw.vertex.geometry.vertex.shaderPc"->(if(centroid)region+0x24000 else BigInt(0x401000)),
        "draw.vertex.geometry.vertex.attributeSlots"->(if(centroid)2 else 1),"draw.vertex.geometry.vertex.varyingSlots"->1,
        "draw.vertex.geometry.vertex.varyingMask"->(if(centroid)15 else 0),
        "draw.vertex.geometry.vertex.sampler.texWidth"->1,"draw.vertex.geometry.vertex.sampler.texHeight"->1,
        "draw.vertex.geometry.vertex.scratch.base"->(region+0x30000),"draw.vertex.geometry.vertex.scratch.limit"->(region+(if(centroid)0x30260 else 0x301e0)),
        "draw.vertex.geometry.vertex.scratch.allocationBase"->(region+0x30000),"draw.vertex.geometry.vertex.scratch.allocationLimit"->(region+(if(centroid)0x30280 else 0x30200)),
        "draw.vertex.geometry.vertex.input.version"->(if(converted)2 else 1),"draw.vertex.geometry.vertex.input.count"->3,"draw.vertex.geometry.vertex.input.instances"->instances,
        "draw.vertex.geometry.vertex.input.attributes[0].enabled"->1,"draw.vertex.geometry.vertex.input.attributes[0].components"->4,
        "draw.vertex.geometry.vertex.input.attributes[0].format"->vertexFormat,
        "draw.vertex.geometry.vertex.input.bindings[0].base"->vertexBase,"draw.vertex.geometry.vertex.input.bindings[0].limit"->(vertexBase+3*vertexStride),
        "draw.vertex.geometry.vertex.input.bindings[0].allocationBase"->vertexBase,"draw.vertex.geometry.vertex.input.bindings[0].allocationLimit"->(vertexBase+64),
        "draw.vertex.geometry.vertex.input.bindings[0].stride"->vertexStride,"draw.raster.version"->1,"draw.raster.screenW"->17,"draw.raster.screenH"->9,
        "draw.raster.sampleMode"->mode,"draw.raster.frontFaceCCW"->1,"draw.vertex.geometry.flatMask"->(if(centroid)8 else 0))++identity("draw.vertex.geometry.vertex.input.identity.")
        if(polygonOffset){val sign=polygonSign*(if(h==0)1 else -1)
          draw++=Map[String,BigInt]("draw.raster.version"->2,"draw.raster.polygonOffsetEnable"->1,
            "draw.raster.polygonOffsetFactor"->fp(sign*2),"draw.raster.polygonOffsetUnits"->fp(sign*3))}
        if(centroid){val k="draw.vertex.geometry.vertex.input.";val colorBase=region+0x26000+h*64
          draw++=Map[String,BigInt](k+"attributes[1].enabled"->1,k+"attributes[1].components"->4,k+"attributes[1].bindingIndex"->1,
            k+"bindings[1].base"->colorBase,k+"bindings[1].limit"->(colorBase+48),k+"bindings[1].allocationBase"->colorBase,
            k+"bindings[1].allocationLimit"->(colorBase+64),k+"bindings[1].stride"->16)}
        if(instances>1){for(i<-0 until 3)memory(pa(region+0x22000+i))=i
          val k="draw.vertex.geometry.vertex.input."
          draw++=Map[String,BigInt](k+"indexFormat"->1,k+"indices.base"->(region+0x22000),k+"indices.limit"->(region+0x22003),
            k+"indices.allocationBase"->(region+0x22000),k+"indices.allocationLimit"->(region+0x22040))}
        record(region+0x12000+h*4096,NativeBinnedGraphicsLayout.drawWords,NativeBinnedGraphicsLayout.drawMagic,
          NativeBinnedGraphicsLayout.drawFields(new NativeBinnedGraphicsDrawRecord),draw)}
      record(region+0x10000,NativeBinnedGraphicsLayout.jobWords,NativeBinnedGraphicsLayout.jobMagic,
        NativeBinnedGraphicsLayout.jobFields(new NativeBinnedGraphicsJobRecord),identity("job.identity.")++Map[String,BigInt](
          "job.frameBase"->(region+0x11000),"job.frameLimit"->(region+0x11800),"job.drawBase"->(region+0x12000),
          "job.drawLimit"->(region+0x12000+(drawCount-1).max(0)*4096+2048),"job.drawCount"->drawCount,"job.drawStride"->4096))
    }
    def expectedBytes: Map[BigInt,Int]={
      val data=FragmentInterpolationReference.Data(Vector.fill(3)(Fp32ViewportReference.decode(fp(1))),
        if(polygonOffset)Vector(0.25,0.75,0.5).map(fp)else Vector.fill(3)(fp(0.5)),Vector.fill(3)(Vector.fill(8)(BigInt(0))),0,1)
      val tri=TriangleRasterReference.Triangle(Vector((BigInt(0),BigInt(0)),(BigInt(17*256),BigInt(0)),(BigInt(0),BigInt(9*256))),data)
      val coverage=TriangleRasterReference.raster(tri,TriangleRasterReference.Settings(width=17,height=9,mode=mode),graphics)
        .flatMap(_.samples.flatMap(p=>(0 until(1<<mode)).filter(s=>(p.mask&(1<<s))!=0).map(s=>(p.x,p.y,s)))).toSet
      val shades=Vector.tabulate(2){h=>
        val values=data.copy(flat=8,values=Vector.tabulate(3)(i=>gradient(h,i).padTo(8,BigInt(0))))
        val triangle=tri.copy(values=values)
        val pixels=mutable.Map.empty[(Int,Int),Vector[BigInt]]
        if(centroid)for(raw<-TriangleRasterReference.raster(triangle,TriangleRasterReference.Settings(width=17,height=9,mode=mode),graphics)){
          val q=CentroidRasterReference(triangle,2,raw,mode,centroidMask(h),graphics)
          for((p,l)<-q.samples.zip(q.lanes) if p.mask!=0)pixels((p.x,p.y))=l.values.take(4)
        }
        pixels.toMap
      }
      val expected=mutable.Map.empty[BigInt,Int]++initial
      val polygonSamples=mutable.Map.empty[(Int,Int,Int,Int),BigInt]
      if(polygonOffset){
        import Fp32ViewportReference.{E,decode,calc,pack,clamp,rational}
        import FragmentInterpolationReference.edge
        val slope=PolygonDepthOffsetReference.maximum(tri.coords.map(_._1),tri.coords.map(_._2),data.depth)
        val resolution=decode(fp(Math.ulp(0.75f).toDouble))
        def depth(edges: Vector[BigInt],a: BigInt,bias: E): BigInt={
          FloatingDepthReference.at(edges,a,data.depth,3,bias)
        }
        val offsets=if(mode==0)Vector((0,0))else if(mode==1)Vector((-1,-1),(1,1))else Vector((-1,-1),(1,-1),(-1,1),(1,1))
        for(h<-0 until 2){val sign=polygonSign*(if(h==0)1 else -1)
          val bias=calc(calc(slope,decode(fp(sign*2)),2),calc(resolution,decode(fp(sign*3)),2),0)
          assert(bias.sig!=0)
          val triangle=tri.copy(values=data.copy(flat=8,values=Vector.tabulate(3)(i=>gradient(h,i).padTo(8,BigInt(0)))))
          for(raw<-TriangleRasterReference.raster(triangle,TriangleRasterReference.Settings(width=17,height=9,mode=mode),graphics)){
            val q=CentroidRasterReference(triangle,2,raw,mode,centroidMask(h),graphics)
            for((p,lane)<-q.samples.zipWithIndex;s<-0 until(1<<mode) if(p.mask&(1<<s))!=0){
              val(ox,oy)=offsets(s);val x=BigInt(p.x)*256+128+ox*64;val y=BigInt(p.y)*256+128+oy*64
              val edges=Vector.tabulate(3)(TriangleRasterReference.edgeAt(triangle,_,x,y))
              val z=if(overrideSecond && h==1)fp(0.125)else depth(edges,edges.sum,bias)
              val key=(4,p.x,p.y,s);val previous=polygonSamples.getOrElse(key,fp(1))
              if(TypedColorReference.value(z)<=TypedColorReference.value(previous)){
                polygonSamples(key)=z
                val color=q.lanes(lane).values.take(4).updated(0,depth(p.edges,p.area,bias))
                for(i<-0 until 4){val k=(i,p.x,p.y,s);val old=polygonSamples.getOrElse(k,encode(names(i),clear)._1)
                  polygonSamples(k)=encode(names(i),TypedBlendReference.blend(names(i),color,old,TypedBlendReference.State(enable=h==1)))._1}
              }
            }
          }
        }
      }
      def sample(i: Int,x: Int,y: Int,s: Int): BigInt={val covered=drawCount>0 && coverage((x,y,s))
        if(polygonOffset)polygonSamples.getOrElse((i,x,y,s),if(i==4)fp(1)else encode(names(i),clear)._1)
        else if(i==4)fp(if(covered)0.5 else 1)else{var old=encode(names(i),clear)._1
          if(covered){old=encode(names(i),if(centroid)shades(0)((x,y))else expectedRgba(0))._1
            for(_<-0 until instances)old=encode(names(i),TypedBlendReference.blend(names(i),if(centroid)shades(1)((x,y))else expectedRgba(1),old,TypedBlendReference.State(enable=true)))._1};old}}
      for(b<-backings;y<-0 until 9;x<-0 until 17){
        if(b.resolve){val raw=TypedResolveReference.resolve(if(b.location==4)"R32F"else names(b.location),Vector.tabulate(1<<mode)(s=>sample(b.location,x,y,s)),mode,depth=b.location==4)
          for(j<-0 until b.bytes)expected(b.base+y*b.pitch+x*b.bytes+j)=((raw>>(j*8))&255).toInt}
        else for(s<-0 until(1<<mode);j<-0 until b.bytes)expected(b.base+y*b.pitch+(x*(1<<mode)+s)*b.bytes+j)=((sample(b.location,x,y,s)>>(j*8))&255).toInt}
      expected.toMap
    }
    def verify(): Unit={for((a,v)<-expectedBytes)assert(memory(a)==v,s"native binned byte mismatch ${a.toString(16)} got=${memory(a)} expected=$v")
      assert(!reads.exists(a=>a>=0x400000 && a<0x800000),"VAs must not reach physical AXI")
      if(virtualImages)assert(!(reads++writes).exists(a=>a>=imageVaBase && a<imageVaBase+0x100000),"image VAs must be translated before physical AXI")}
    def read(a: BigInt): BigInt={reads+=a;val base=a& ~BigInt(63);(0 until 64).foldLeft(BigInt(0))((v,b)=>v|(BigInt(memory.getOrElse(base+b,0))<<(b*8)))}
    def write(a: BigInt,data: BigInt,mask: BigInt): Unit={writes+=a;for(b<-0 until 64 if mask.testBit(b))memory(a+b)=((data>>(b*8))&255).toInt}
    val queryProbe=if(visibilityQueries)Some(new NativeVisibilityQueryHostProbe(d,memory,read,write))else None
    def submit(id: Int=23,bytes: Int=512,feedback: Option[(NativeTransformFeedbackHostProbe,Boolean)]=None): Unit={queryProbe.foreach(_.beforeFrame());parkMemory(d);axiWrite(d,GpuCommandMmioRegs.COMMAND_ID,id);axiWrite(d,GpuCommandMmioRegs.OPCODE,7)
      axiWrite(d,GpuCommandMmioRegs.SOURCE,(region+0x10000).toInt);axiWrite(d,GpuCommandMmioRegs.DESTINATION,(region+0x10200).toInt)
      axiWrite(d,GpuCommandMmioRegs.BYTES,bytes);axiWrite(d,GpuCommandMmioRegs.PATTERN,0);feedback.foreach{case(p,discard)=>p.stageRender(discard)};axiWrite(d,GpuCommandMmioRegs.SUBMIT,1)}
    def run(status: Int=0,id: Int=23,readFault: BigInt=>Boolean=_=>false,writeFault: (BigInt,BigInt,BigInt)=>Boolean=(_,_,_)=>false): Unit={
      serviceMemoryMaster(d,read,readFault,writeAckDelay=7,readResponseDelay=3,maxCycles=1000000,writeFault=writeFault)(write){d.io.m_irq.peek().litToBoolean}
      val result=axiRead(d,GpuCommandMmioRegs.COMPLETION)
      val details=(0 until 12).map(i=>axiRead(d,0x180+i*4))
      assert((result&255)==id && ((result>>8)&7)==7 && ((result>>11)&15)==status && ((result>>15)&1)==(if(status==0)1 else 0),
        s"native binned completion=${result.toString(16)} expectedStatus=$status GNX1=${details.map(_.toString(16)).mkString(",")}")
      assert(details.head==BigInt("474e5801",16) && (details(1)&7)==status && details(11)==0)
      queryProbe.foreach(_.completed(status))
      if(status==0)assert(details(1)==0 && details(5)==0 && details.slice(6,9)==Seq[BigInt](7,8,generation))
    }
    def pop(): Unit={axiWrite(d,GpuCommandMmioRegs.COMPLETION_POP,1);axiWrite(d,RenderHostRegs.IRQ,3)
      for(i<-0 until 12)assert(axiRead(d,0x180+i*4)==0,"popped native details must not remain visible");queryProbe.foreach(_.afterFrame())}
    def consumer(id: Int): BigInt={parkMemory(d);axiWrite(d,GpuCommandMmioRegs.COMMAND_ID,id);axiWrite(d,GpuCommandMmioRegs.OPCODE,0)
      axiWrite(d,GpuCommandMmioRegs.KERNEL_PC,0x403000);axiWrite(d,GpuCommandMmioRegs.KERNARG,(region+0x130000).toInt)
      for(a<-Seq(GpuCommandMmioRegs.GRID_X,GpuCommandMmioRegs.GRID_Y,GpuCommandMmioRegs.GRID_Z,GpuCommandMmioRegs.LOCAL_X,GpuCommandMmioRegs.LOCAL_Y,GpuCommandMmioRegs.LOCAL_Z))axiWrite(d,a,1)
      axiWrite(d,GpuCommandMmioRegs.SUBMIT,1);serviceMemoryMaster(d,read,maxCycles=100000)(write){d.io.m_irq.peek().litToBoolean}
      val result=axiRead(d,GpuCommandMmioRegs.COMPLETION);assert((result&255)==id && ((result>>15)&1)==1)
      for(i<-0 until 12)assert(axiRead(d,0x180+i*4)==0,"ordinary compute must not inherit native completion details")
      val v=(0 until 4).foldLeft(BigInt(0))((v,b)=>v|(BigInt(memory.getOrElse(pa(region+0x130040)+b,0))<<(b*8)));pop();v}
  }
  private def vm(d: GpuHostSystemAxi): Unit={axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0x80000030);axiWrite(d,GpuCommandMmioRegs.INSTRUCTION_SATP,0x80000030)
    axiWrite(d,GpuCommandMmioRegs.TLB_FLUSH,1);axiWrite(d,RenderHostRegs.IRQ,1)}
  if(transformFeedback)for(local<-Seq(true,false))
  it should s"capture each native binned primitive once before tile fanout and preserve exact images with raster discard local=$local" in {
    simulate(host(local)){d=>initialize(d);val image=new Image(d);vm(d)
      val feedback=new NativeTransformFeedbackHostProbe(d,image.memory,image.read,image.write)
      for(discard<-Seq(false,true)){
        image.setup(fragmentVersion=3)
        if(!discard)image.queryProbe.foreach{q=>q.beforeFrame();q.completed(0,false);q.afterFrame()}
        feedback.begin();image.submit(if(discard)132 else 131,feedback=Some(feedback->discard))
        image.run(id=if(discard)132 else 131);image.queryProbe.foreach(_.completed(0,!discard))
        if(discard){val count=image.drawCount;image.drawCount=0;image.verify();image.drawCount=count}else image.verify()
        image.pop()
        val positions=Vector(Vector(-1.0,-1.0,0.0,1.0),Vector(1.0,-1.0,0.0,1.0),Vector(-1.0,1.0,0.0,1.0))
        val expected=(0 until 2).flatMap(h=>positions.indices.flatMap(i=>positions(i).map(fp)++image.gradient(h,i)))
        feedback.end(expected,2,6)
      }
      feedback.report("BINNED",local)
    }
  }
  if(!transformFeedback){
  if(groupedTexture)for(local<-Seq(true,false))
  it should (if(sharedTextureReset)s"drain shared native texture reads across AXI command reset and republish both binned image engines local=$local"
    else s"sample shared RGBA8 in both native binned stages and observe coherent DMA publication across two image engines local=$local") in {
    simulate(host(local)){d=>initialize(d);val image=new Image(d);vm(d)
      val vo=BigInt(0x580000);val fo=BigInt(0x680000)
      val broadcast=(BigInt(0x17)<<26)|(BigInt(1)<<25)|(BigInt(4)<<12)
      def constant(rd: Int,value: BigInt): Vector[BigInt]={
        val hi=(value+2048)>>12;val lo=(value-(hi<<12)).toInt
        Vector((hi<<12)|(BigInt(rd)<<7)|0x37,addi(rd,rd,lo))
      }
      def sampler(unit: Int): Vector[BigInt]={
        val values=Vector(fp(0.75),fp(0.75))++Vector.fill(14)(BigInt(0))
        val params=(0 until 8).flatMap{pair=>
          constant(12,values(pair*2))++constant(13,values(pair*2+1))++Vector(
            broadcast|(BigInt(12)<<15)|(BigInt(10)<<7)|0x57,
            broadcast|(BigInt(13)<<15)|(BigInt(11)<<7)|0x57,
            (BigInt(3)<<26)|(BigInt(1)<<25)|(BigInt(11)<<20)|(BigInt(10)<<15)|(BigInt(pair)<<12)|0x2b)
        }
        (params++Vector(addi(13,0,unit),addi(14,0,1),
          broadcast|(BigInt(13)<<15)|(BigInt(5)<<7)|0x57,
          broadcast|(BigInt(14)<<15)|(BigInt(6)<<7)|0x57,
          (BigInt(4)<<26)|(BigInt(1)<<25)|(BigInt(6)<<20)|(BigInt(5)<<15)|(BigInt(7)<<7)|0x2b)).toVector
      }
      def fill(id: Int,address: BigInt,raw: BigInt): Unit={
        parkMemory(d);axiWrite(d,GpuCommandMmioRegs.COMMAND_ID,id)
        axiWrite(d,GpuCommandMmioRegs.OPCODE,GpuCommandOpcode.fill.litValue.toInt)
        axiWrite(d,GpuCommandMmioRegs.DESTINATION,address.toInt);axiWrite(d,GpuCommandMmioRegs.BYTES,128)
        axiWrite(d,GpuCommandMmioRegs.PATTERN,raw.toLong.toInt);axiWrite(d,GpuCommandMmioRegs.SUBMIT,1)
        serviceMemoryMaster(d,image.read,writeAckDelay=11,readResponseDelay=5,maxCycles=100000)(image.write){d.io.m_irq.peek().litToBoolean}
        val result=axiRead(d,GpuCommandMmioRegs.COMPLETION)
        assert((result&255)==id && ((result>>8)&7)==GpuCommandOpcode.fill.litValue && ((result>>11)&15)==0 && result.testBit(15))
        image.pop()
      }
      var frames=0
      for(mode<-Seq(0,2)){
        image.setup(mode)
        val id=Seq[BigInt](7,8,image.generation)
        val raw=if(mode==0)BigInt(0xff804020L)else BigInt(0xff20b4c0L)
        image.expectedSampledRed=Some(ImageFormatReference.decode("RGBA8",raw)(0))
        def put(a: BigInt,v: BigInt): Unit=image.word(image.pa(a),v)
        def byte(a: BigInt,v: Int): Unit={image.memory(image.pa(a))=v}
        val data=for((base,stage,unit)<-Seq((vo,0,31),(fo,1,17)))yield {
          NativeTextureFetchTestData.roots(put,stage=stage,id=id,offset=base)
          NativeTextureFetchTestData.install(put,byte,unit,id=id,stage=stage,offset=base,raw=0)
          val address=base+0x20000+unit*4096+(if(sharedTextureReset)(frames+1)*0x2000 else 0)
          if(sharedTextureReset){
            // Cold distinct backing guarantees an external AR for the reset
            // cut; the healthy rerun then republishes these same addresses.
            put(base+NativeTextureFetchTestData.images+unit*128+15*4,address)
            put(base+NativeTextureFetchTestData.images+unit*128+16*4,address+4096)
            put(base+NativeTextureFetchTestData.levels+unit*128+9*4,address)
            put(base+NativeTextureFetchTestData.levels+unit*128+10*4,address+4096)
          }
          put(base+NativeTextureFetchTestData.levels+unit*128+12*4,address)
          put(base+NativeTextureFetchTestData.levels+unit*128+17*4,64)
          address
        }
        val vp=image.region+0x24000;val fp=image.region+0x25000
        val vertexCode=vertexProgram.dropRight(1)++sampler(31)++Vector(BigInt("30500073",16))
        val fragmentCode=fragmentProgram.dropRight(1)++sampler(17)++
          (0 until 4).flatMap(location=>Vector(addi(6,7,(10+location*4)*32),vm(6,7,true)))++Vector(BigInt("30500073",16))
        vertexCode.zipWithIndex.foreach{case(w,i)=>put(vp+i*4,w)}
        fragmentCode.zipWithIndex.foreach{case(w,i)=>put(fp+i*4,w)}
        for(h<-0 until 2){
          val draw=image.region+0x12000+h*4096
          val fields=NativeBinnedGraphicsLayout.drawFields(new NativeBinnedGraphicsDrawRecord)
          image.patchRecord(draw,fields,"draw.vertex.geometry.vertex.shaderPc",vp)
          image.patchRecord(draw,fields,"draw.vertex.geometry.vertex.resources.version",1)
          image.patchRecord(draw,fields,"draw.vertex.geometry.vertex.resources.address",vo+NativeTextureFetchTestData.root)
          val state=image.region+0x20000+h*512
          val sf=TypedFragmentStateLayout.fields(new TypedFragmentState(f))
          image.patchRecord(state,sf,"fragment.shaderPc",fp)
          image.patchRecord(state,sf,"fragment.resources.version",1)
          image.patchRecord(state,sf,"fragment.resources.address",fo+NativeTextureFetchTestData.root)
        }
        // Repeat the same physical texture lines with a different GPU-written
        // pattern; a new frame may not reuse old shared-cache texels.
        axiWrite(d,GpuCommandMmioRegs.TLB_FLUSH,1)
        val perf=d.io.performance.get
        if(sharedTextureReset){
          for(address<-data;offset<-0 until 128 by 4)put(address+offset,raw)
          val oldGroups=perf.nativeTextureGroups.peek().litValue
          val oldReads=perf.nativeTextureDataReads.map(_.peek().litValue).sum
          val oldWalks=perf.nativeTexturePageWalks.map(_.peek().litValue).sum
          image.submit(100+frames)
          val dma=image.region+0x140000
          parkMemory(d);axiWrite(d,GpuCommandMmioRegs.COMMAND_ID,120+frames)
          axiWrite(d,GpuCommandMmioRegs.OPCODE,GpuCommandOpcode.fill.litValue.toInt)
          axiWrite(d,GpuCommandMmioRegs.DESTINATION,dma.toInt);axiWrite(d,GpuCommandMmioRegs.BYTES,64)
          axiWrite(d,GpuCommandMmioRegs.PATTERN,0xdeadbeef);axiWrite(d,GpuCommandMmioRegs.SUBMIT,1)
          var requested=false;var live=BigInt(0)
          val target=image.pa(data(if(mode==0)0 else 1))
          serviceMemoryMaster(d,image.read,writeAckDelay=31,readResponseDelay=53,maxCycles=1000000,
            onReadAccepted=a=>if(!requested && a>=target && a<target+128){
              requested=true;live=perf.payloadReservedBytes.peek().litValue
              if(local)assert(live>0,"a live shared texture instruction lost its local payload reservation")
              assert(perf.nativeTextureGroups.peek().litValue>oldGroups && perf.nativeTextureDataReads.map(_.peek().litValue).sum>oldReads)
              assert(perf.nativeTexturePageWalks.map(_.peek().litValue).sum>oldWalks,"shared data cut bypassed Sv32")
              axiWrite(d,GpuCommandMmioRegs.RESET,1)
              axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0,expectedResponse=2)
            })(image.write){requested && d.io.m_irq.peek().litToBoolean}
          assert(requested,s"shared texture reset never reached ${target.toString(16)} mode=$mode local=$local")
          assert((axiRead(d,GpuCommandMmioRegs.STATUS)&10)==0)
          assert(axiRead(d,GpuCommandMmioRegs.VECTOR_SATP)==0x80000030L)
          assert(!image.writes.exists(a=>a>=image.pa(dma) && a<image.pa(dma+64)),"reset executed the queued DMA")
          perf.payloadReservedBytes.expect(0.U);perf.payloadPinsActive.expect(0.U)
          assert(perf.payloadAllocations.peek().litValue==perf.payloadReleases.peek().litValue)
          for(i<-0 until 12)assert(axiRead(d,0x180+i*4)==0,"reset retained aborted native details")
          axiWrite(d,RenderHostRegs.IRQ,3);axiWrite(d,GpuCommandMmioRegs.TLB_FLUSH,1)
          image.queryProbe.foreach(_.aborted())
          info(s"NATIVE_SHARED_BINNED_RESET_CUT local=$local CUs=4 mode=$mode stage=${if(mode==0)"vertex"else"fragment"} payloadBytes=$live actualSharedDataWalks=true outstandingPhysicalRead=true queuedDMAAborted=true VMWriteRejectedUntilDrain=true abortedCompletionDiscarded=true")
        }
        val published=if(sharedTextureReset)raw ^ BigInt(0x00ffffffL)else raw
        image.expectedSampledRed=Some(ImageFormatReference.decode("RGBA8",published)(0))
        data.zipWithIndex.foreach{case(address,n)=>fill(40+frames*2+n,address,published)}
        val groups=perf.nativeTextureGroups.peek().litValue
        val reads=perf.nativeTextureDataReads.map(_.peek().litValue).sum
        image.submit(60+frames);image.run(id=60+frames);image.verify()
        assert(perf.nativeTextureGroups.peek().litValue>groups,"binned frame never entered shared sampling")
        assert(perf.nativeTextureDataReads.map(_.peek().litValue).sum>reads,"new binned frame retained stale shared lines")
        for(base<-Seq(vo,fo))assert(image.reads.contains(image.pa(base+NativeTextureFetchTestData.root)),"both shader stages must read their typed texture root")
        assert(perf.nativeTexturePageWalks.map(_.peek().litValue).sum>0)
        axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0,expectedResponse=2)
        image.pop();frames+=1
      }
      assert(frames==2)
      image.queryProbe.foreach(_.report(if(sharedTextureReset)"RESET"else"BINNED",local,frames,if(sharedTextureReset)frames else 0))
      if(sharedTextureReset)info(s"NATIVE_SHARED_BINNED_RESET_AXI local=$local CUs=4 frames=$frames resets=$frames engines=2 exactMRTDepthResolve=true vertexAndFragmentResetCuts=true actualSharedDataWalks=true sameAddressNewPatternAfterReset=true modes1x4x=true queuedDMAAborted=true payloadAndPhysicalDrain=true")
      else info(s"NATIVE_SHARED_BINNED_AXI local=$local CUs=4 frames=$frames engines=2 exactMRTDepthResolve=true bothStages=true realSv32=true actualSharedDataWalks=true sameAddressDMAPublication=true modes1x4x=true heldCompletionPinsVM=true")
    }
  }
  if(!groupedTexture){
  it should "expose exact GNX1 diagnostics for rejected envelopes through the production AXI registers" in {
    simulate(host(false)){d=>initialize(d);val image=new Image(d);vm(d);image.setup(empty=true)
      image.submit(201,bytes=256);image.run(status=1,id=201)
      assert(image.reads.isEmpty && image.writes.isEmpty,"a rejected envelope must precede every memory access")
      val expected=Vector[BigInt](BigInt("474e5801",16),9,image.region+0x10000,0,0,0,0,0,0,0,0,0)
      for((v,i)<-expected.zipWithIndex)assert(axiRead(d,0x180+i*4)==v,s"rejected envelope GNX1 word $i differs")
      axiWrite(d,0x180,0,expectedResponse=2);axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0,expectedResponse=2)
      for((v,i)<-expected.zipWithIndex)assert(axiRead(d,0x180+i*4)==v)
      image.pop()
      info("NATIVE_GNX1_AXI envelopeBeforeMemory=true exactRecordedAddress=true stage1=true heldImmutable=true readOnly=true VMProtected=true popClearsAllWords=true")
    }
  }
  for(local<-Seq(true,false))
  it should s"discover GNC${if(virtualImages)2 else 1} submit translated multi-tile shaders and publish exact cached source and resolve images local=$local" in {
    simulate(host(local)){d=>initialize(d);val image=new Image(d);vm(d)
      assert(axiRead(d,TileBinHostRegs.DISCOVERY)==0x160)
      val expected=Vector[BigInt](BigInt(if(virtualImages)"474e4302"else"474e4301",16),8,BigInt("474e4a01",16),BigInt("08000200",16),
        BigInt("88420800",16),BigInt("00090011",16),BigInt("d4908000",16),if(local)BigInt("020cb802",16)else BigInt("00000802",16))
      for((word,i)<-expected.zipWithIndex)assert(axiRead(d,0x160+i*4)==word,s"GNC1 word $i differs")
      for((word,i)<-Vector[BigInt](BigInt("47564901",16),4,0x502,0x1ffffff).zipWithIndex)
        assert(axiRead(d,0x1b0+i*4)==word,s"GVI1 word $i differs")
      axiWrite(d,0x1b0,0,expectedResponse=2)
      for(i<-0 until 12)assert(axiRead(d,0x180+i*4)==0)
      axiWrite(d,0x180,0,expectedResponse=2)
      for(mode<-Seq(0,2)){image.setup(mode,vertexFormat=if(mode==0)VertexInputFormat.float16 else VertexInputFormat.snorm2101010)
        axiWrite(d,GpuCommandMmioRegs.TLB_FLUSH,1)
        assert(image.consumer(10+mode)==BigInt("a7a7a7a7",16));image.submit();image.run();image.verify()
        axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0,expectedResponse=2);assert(axiRead(d,GpuCommandMmioRegs.VECTOR_SATP)==BigInt("80000030",16));image.pop()
        val expected=(0 until 4).foldLeft(BigInt(0))((v,b)=>v|(BigInt(image.expectedBytes(image.imageBase+60+b))<<(b*8)))
        assert(image.consumer(30+mode)==expected);d.io.performance.get.payloadReservedBytes.expect(0.U)}
      info(s"NATIVE_BINNED_AXI local=$local CUs=2 GNJ1=true GVI1=true convertedVertexInputs=half,snorm2101010 Sv32=true virtualImages=$virtualImages draws=4 tiles=12 modes1x4x=true exactFourMRTDepthResolveGuards=true cachedConsumer=true heldCompletionPinsVM=true")}
  }
  for(local<-Seq(true,false))
  it should s"publish native binned polygon offset depth and shader fragCoord from GPU-memory records local=$local" in {
    simulate(host(local)){d=>initialize(d);val image=new Image(d);vm(d)
      for((mode,version,sign,overrideDepth)<-Seq((1,3,1,false),(2,4,-1,true))){
        image.setup(mode,fragmentVersion=version,polygon=true,sign=sign,shaderOverride=overrideDepth)
        axiWrite(d,GpuCommandMmioRegs.TLB_FLUSH,1)
        assert(image.consumer(100+version)==BigInt("a7a7a7a7",16))
        image.submit(110+version);image.run(id=110+version);image.verify()
        axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0,expectedResponse=2)
        assert(axiRead(d,GpuCommandMmioRegs.VECTOR_SATP)==BigInt("80000030",16));image.pop()
        val expected=(0 until 4).foldLeft(BigInt(0))((v,b)=>v|(BigInt(image.expectedBytes(image.imageBase+60+b))<<(b*8)))
        assert(image.consumer(120+version)==expected)
        def word(a: BigInt): BigInt=(0 until 4).foldLeft(BigInt(0))((v,b)=>v|(BigInt(image.memory(image.pa(a)+b))<<(b*8)))
        for(h<-0 until 2;e<-0 until 2){val base=image.region+(if(e==0)0x40000 else 0x50000)+h*4096+image.fragmentPrefix
          for(i<-0 until 4;c<-0 until 4)assert(word(base+i*16+c*4)==image.rgba(h)(c),"native polygon replay changed a uniform tail")
          assert(word(base+64)==(if(overrideDepth && h==1)63 else 61) && word(base+68)==fp(0.125))}
        assert(image.reads.exists(a=>a>=image.pa(image.region+0x100000) && a<image.pa(image.region+0x110000)),"prepared polygon records must be fetched from GPU-memory bins")
        assert(image.reads.exists(a=>a>=image.pa(image.region+0x20000) && a<image.pa(image.region+0x20400)),"polygon shaders must fetch GFS1 state")
        d.io.performance.get.payloadReservedBytes.expect(0.U)
      }
      info(s"NATIVE_BINNED_POLYGON_OFFSET local=$local CUs=2 GND1=true GFS1=true GPUResidentBins=true virtualImages=$virtualImages boundD32F=true rasterVersion2=true signedFactorUnits=true versions3And4=true modes2x4x=true shaderFragCoordZInMRT=true exactDefaultSampleDepth=true shaderDepthOverrideBypassesBias=true gradientMRTBlend=true exactSourceResolveGuards=true cachedConsumer=true uniformTails=true heldCompletionPinsVM=true")
    }
  }
  for(local<-Seq(true,false))
  it should s"replay native centroid gradients from GPU-memory bins and fragment states local=$local" in {
    simulate(host(local)){d=>initialize(d);val image=new Image(d);vm(d)
      for((word,i)<-Vector[BigInt](BigInt("47464901",16),4,0x1e,0x70f).zipWithIndex)
        assert(axiRead(d,0x1c0+i*4)==word)
      for((mode,version)<-Seq((1,3),(2,4))){
        image.setup(mode,fragmentVersion=version);axiWrite(d,GpuCommandMmioRegs.TLB_FLUSH,1)
        assert(image.consumer(40+version)==BigInt("a7a7a7a7",16))
        image.submit(50+version);image.run(id=50+version);image.verify()
        axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0,expectedResponse=2)
        assert(axiRead(d,GpuCommandMmioRegs.VECTOR_SATP)==BigInt("80000030",16))
        image.pop()
        val expected=(0 until 4).foldLeft(BigInt(0))((v,b)=>v|(BigInt(image.expectedBytes(image.imageBase+60+b))<<(b*8)))
        assert(image.consumer(60+version)==expected)
        def word(a: BigInt): BigInt=(0 until 4).foldLeft(BigInt(0))((v,b)=>v|(BigInt(image.memory(image.pa(a)+b))<<(b*8)))
        for(h<-0 until 2;e<-0 until 2){
          val base=image.region+(if(e==0)0x40000 else 0x50000)+h*4096+image.fragmentPrefix
          for(i<-0 until 4;c<-0 until 4)assert(word(base+i*16+c*4)==image.rgba(h)(c),"native replay changed a uniform tail")
          assert(word(base+64)==61 && word(base+68)==fp(0.5))
        }
        assert(image.reads.exists(a=>a>=image.pa(image.region+0x100000) && a<image.pa(image.region+0x110000)),"prepared records must be fetched from the GPU-memory bin pool")
        assert(image.reads.exists(a=>a>=image.pa(image.region+0x20000) && a<image.pa(image.region+0x20400)),"fragment states must be fetched from GFS1")
        d.io.performance.get.payloadReservedBytes.expect(0.U)
      }
      info(s"NATIVE_BINNED_CENTROID local=$local CUs=2 GNJ1=true GFS1=true GPUResidentBins=true virtualImages=$virtualImages versions3And4=true modes2x4x=true stateMasks5And3=true mixedCenterCentroidFlat=true gradientMRTs=4 blendedSecondDraw=true exactSourceResolveGuards=true cachedConsumer=true freshProgramAndVertexAllocations=true uniformTails=true heldCompletionPinsVM=true")
    }
  }
  it should "publish every empty tile and execute indexed instanced native draws through Sv32" in {
    simulate(host(false)){d=>initialize(d);val image=new Image(d);vm(d)
      for(mode<-Seq(0,2)){
        image.setup(mode,empty=true);axiWrite(d,GpuCommandMmioRegs.TLB_FLUSH,1)
        image.submit();image.run();image.verify();image.pop()
        assert(!image.reads.contains(image.pa(0x401000)),"an empty frame must not execute a vertex shader")
      }
      image.setup(2,indexedInstances=2);axiWrite(d,GpuCommandMmioRegs.TLB_FLUSH,1)
      image.submit();image.run();image.verify();image.pop()
      assert(image.reads.exists(a=>a>=image.pa(image.region+0x22000) && a<image.pa(image.region+0x22040)),"indexed instances must fetch translated indices")
      d.io.performance.get.payloadReservedBytes.expect(0.U)
      info("NATIVE_BINNED_AXI emptyFrames=2 allSixTiles=true modes1x4x=true indexedInstances=2 exactMRTDepthResolveGuards=true")
    }
  }
  it should "attribute envelope descriptor Sv32 shader and physical publication faults and permit exact healthy reuse" in {
    simulate(host(false)){d=>initialize(d);val image=new Image(d);vm(d)
      val cases=Seq(("envelope",1),("header",1),("descriptor-read",3),("draw-read",3),
        ("write-permission",3),("execute-permission",5),("shader-trap",5),("physical-store",3))++
        (if(virtualImages)Seq(("image-write-permission",3),("image-read-permission",3),
          ("image-alias-job",1),("image-alias-state",1),("image-alias-bin",1),("scratch-image-alias",1))else Seq.empty)
      val selected=sys.props.get("opengpu.nativeFaultCase").map(_.split(",").toSet)
      require(selected.forall(_.subsetOf(cases.map(_._1).toSet)),"unknown native fault-case selection")
      val work=cases.zipWithIndex.filter{case((name,_),_)=>selected.forall(_(name))}
      require(work.nonEmpty)
      info(s"NATIVE_BINNED_FAULT_SELECTION virtualImages=$virtualImages cases=${work.map(_._1._1).mkString(",")} filtered=${selected.nonEmpty}")
      for(((name,status),index)<-work){
        image.setup(badShader=name=="shader-trap",loadImages=name=="image-read-permission")
        image.word(0x30004,((BigInt(0x800000)>>12)<<10)|(if(name=="execute-permission")0xc7 else 0xcf))
        if(name=="header")image.word(image.pa(image.region+0x10000+508),1)
        if(name=="write-permission"){
          // Deny only the vertex staging page. Bins, descriptors and states
          // stay writable, so a bin failure cannot win the first-fault race.
          val table=BigInt(0x50000)+index*4096
          image.word(0x30000+(image.region>>22)*4,((table>>12)<<10)|1)
          for(page<-0 until 1024)image.word(table+page*4,((image.pa(image.region+page*4096)>>12)<<10)|(if(page==0x30)0x4b else 0xcf))
        }
        if(name=="image-write-permission" || name=="image-read-permission")image.word(0x30000+(image.imageVaBase>>22)*4,
          ((image.imageBase>>12)<<10)|(if(name=="image-write-permission")0x4b else 0xc9))
        if(name.startsWith("image-alias-")){
          val resolve=name=="image-alias-bin"
          val alloc=image.region+(if(name=="image-alias-job")0x10000 else if(name=="image-alias-state")0x20000 else 0x100000)
          val base=alloc+(if(resolve)59 else 61);val pitch=75;val limit=base+8*pitch+68;val end=(limit+63)& ~BigInt(63)
          val k=if(resolve)"frame.image.resolves[0].destination."else"frame.image.views[0]."
          for((n,v)<-Seq("base"->base,"limit"->limit,"allocationBase"->alloc,"allocationLimit"->end))image.patchFrame(k+n,v)
        }
        if(name=="scratch-image-alias"){
          val base=image.imageVaBase+128;val k="draw.vertex.geometry.vertex.scratch."
          for((n,v)<-Seq("base"->base,"limit"->(base+480),"allocationBase"->image.imageVaBase,"allocationLimit"->(image.imageVaBase+768)))image.patchDraw(k+n,v)
        }
        axiWrite(d,GpuCommandMmioRegs.TLB_FLUSH,1)
        val id=70+index;image.submit(id,if(name=="envelope")256 else 512)
        val denied=if(name=="descriptor-read")Set(image.pa(image.region+0x10040))
          else if(name=="draw-read")Set(image.pa(image.region+0x13000))else Set.empty[BigInt]
        var failedReads=0;var failedWrites=0
        image.run(status,id,a=>{val fail=denied(a);if(fail)failedReads+=1;fail},(a,_,_)=>{
          val fail=name=="physical-store" && a>=image.imageBase && a<image.imageBase+0x100000
          if(fail)failedWrites+=1;fail})
        val flags=axiRead(d,0x184);val statuses=axiRead(d,0x194)
        assert(((flags>>3)&3)==(if(Set("envelope","header")(name) || name.startsWith("image-alias-") || name=="scratch-image-alias")1 else if(denied.nonEmpty)2 else 3),s"native reader fault stage differs: $name")
        if(name=="write-permission" || name=="execute-permission"){
          assert(((flags>>7)&3)==2 && ((flags>>11)&1)==1,"vertex faults must preserve their source stage")
          assert(((statuses>>24)&7)==status,"vertex fault status must not be confused with bin rejection")
        }
        if(Set("shader-trap","physical-store","image-write-permission","image-read-permission")(name))assert(((flags>>5)&3)==3 && ((flags>>9)&1)==1,
          "image execution/publication faults must retain the fault tile")
        if(name=="image-read-permission")assert(!image.reads.exists(a=>a>=image.imageBase && a<image.imageBase+0x100000),"read-denied image data must not reach physical AXI")
        if(name.startsWith("image-alias-"))assert(image.writes.isEmpty,"frame image-role aliases must be rejected before child writes")
        if(name=="envelope")assert(image.reads.isEmpty && image.writes.isEmpty)
        if(denied.nonEmpty)assert(failedReads>0,s"fault injection was not reached: $name")
        if(name=="physical-store")assert(failedWrites>0)
        if(name=="execute-permission")assert(!image.reads.contains(image.pa(0x401000)),"execute-denied code must not reach physical AXI")
        if(name=="write-permission")assert(!image.writes.exists(a=>a>=image.pa(image.region+0x30000) && a<image.pa(image.region+0x31000)),
          "write-denied vertex staging must not reach physical AXI")
        if(!Set("shader-trap","physical-store")(name))assert(!image.writes.exists(a=>a>=image.imageBase),s"early fault published an image: $name")
        axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0,expectedResponse=2);image.pop()
        image.word(0x30004,((BigInt(0x800000)>>12)<<10)|0xcf)
        image.setup();axiWrite(d,GpuCommandMmioRegs.TLB_FLUSH,1)
        image.submit(90+index);image.run(id=90+index);image.verify();image.pop()
        d.io.performance.get.payloadReservedBytes.expect(0.U)
        info(s"NATIVE_BINNED_FAULT case=$name status=$status attributedCommand=true exactHealthyReuse=true")
      }
    }
  }
  it should "retain shader and physical acknowledgement ownership through reset and discard queued DMA" in {
    simulate(host(true)){d=>initialize(d);val image=new Image(d);val perf=d.io.performance.get;vm(d)
      for(phase<-0 until 2){
        image.setup(if(phase==0)0 else 2,empty=phase==1);axiWrite(d,GpuCommandMmioRegs.TLB_FLUSH,1)
        image.submit(110+phase)
        val dma=image.region+0x140000
        axiWrite(d,GpuCommandMmioRegs.COMMAND_ID,120+phase);axiWrite(d,GpuCommandMmioRegs.OPCODE,GpuCommandOpcode.fill.litValue.toInt)
        axiWrite(d,GpuCommandMmioRegs.DESTINATION,dma.toInt);axiWrite(d,GpuCommandMmioRegs.BYTES,64)
        axiWrite(d,GpuCommandMmioRegs.PATTERN,0xdeadbeef);axiWrite(d,GpuCommandMmioRegs.SUBMIT,1)
        var requested=false;var live=BigInt(0)
        def resetAtBoundary(): Unit={requested=true;live=perf.payloadReservedBytes.peek().litValue
          if(phase==0)assert(live>0,"the live vertex shader must retain its payload reservation")
          else assert(live==0,"empty-frame publication must not depend on a shader payload")
          axiWrite(d,GpuCommandMmioRegs.RESET,1)
          axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0,expectedResponse=2)
        }
        serviceMemoryMaster(d,image.read,writeAckDelay=29,readResponseDelay=17,maxCycles=1000000,
          onReadAccepted=a=>if(phase==0 && !requested && a==image.pa(0x401000))resetAtBoundary(),
          onWriteAccepted=a=>if(phase==1 && !requested && a>=image.imageBase)resetAtBoundary())(image.write){d.io.m_irq.peek().litToBoolean}
        assert(requested,s"native reset phase=$phase completed before the required shader/publication boundary; completion=${axiRead(d,GpuCommandMmioRegs.COMPLETION).toString(16)}")
        assert((axiRead(d,GpuCommandMmioRegs.STATUS)&10)==0)
        assert(!image.writes.exists(a=>a>=image.pa(dma) && a<image.pa(dma+64)),"reset must discard the queued DMA command")
        perf.payloadReservedBytes.expect(0.U);perf.payloadPinsActive.expect(0.U)
        assert(perf.payloadAllocations.peek().litValue==perf.payloadReleases.peek().litValue)
        for(i<-0 until 12)assert(axiRead(d,0x180+i*4)==0,"reset must discard aborted native details")
        axiWrite(d,RenderHostRegs.IRQ,3)
        image.setup(if(phase==0)0 else 2);axiWrite(d,GpuCommandMmioRegs.TLB_FLUSH,1)
        image.submit(140+phase);image.run(id=140+phase);image.verify();image.pop()
        perf.payloadReservedBytes.expect(0.U)
        info(s"NATIVE_BINNED_RESET phase=$phase livePayloadBytes=$live queuedDMAAborted=true oldCompletionsDiscarded=true physicalAckDrain=true VMWriteRejected=true exactHealthyReuse=true")
      }
    }
  }
  }

  }
}

class NativeBinnedGraphicsHostAxiSpec extends NativeBinnedGraphicsHostBaseSpec
