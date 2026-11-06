package opengpu.graphics

import chisel3._
import chisel3.util.HasBlackBoxInline
import opengpu.config.GpuConfig
import opengpu.system.{GpuHostSystemAxi,GpuHostTestSupport,AxiTrafficObserver}
import opengpu.testutil.AxiTrafficCounters
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** Private qualification only: no product Main source is changed. */
private class H6WholeHostSramModels extends BlackBox with HasBlackBoxInline {
  val io=IO(new Bundle {val present=Output(Bool())})
  setInline("H6WholeHostSramModels.sv",
    "module H6WholeHostSramModels(output present); assign present=1'b1; endmodule\n"+
      Seq(32,64).map(width=>java.nio.file.Files.readString(java.nio.file.Paths.get(
        s"depends/asap7_sram_0p0/generated/verilog/srambank_64x4x${width}_6t122.v"))).mkString("\n"))
}
private class H6TypedWholeHostPerfTop extends GpuHostSystemAxi(
  GraphicsConfig(),GpuConfig(),numComputeUnits=2,transactionsPerCu=16,
  fragCore=true,sharedGraphics=true,parallelGraphics=true,
  useBlackBoxes=true,enableFpuBackend=true,memoryAxiDataBytes=32,exposePerformance=true,
  typedGraphics=Some(NativeTypedGraphicsConfig()),enableIndirectCompute=true,
  imageAvailable=true,storageAvailable=true,computeUniformAvailable=true,
  computeTextureAvailable=true,atomicCounterAvailable=true,
  imageAtomicAvailable=true,storageAtomicAvailable=true) {
  private val models=Module(new H6WholeHostSramModels)
  dontTouch(models.io.present)
}

class H6TypedWholeHostPerfSpec extends AnyFlatSpec with GpuHostTestSupport {
  private val gpu=GpuConfig()
  private val capacity=gpu.lanes*gpu.warps
  private val stride=4*capacity
  // Active descriptor work is unchanged; physical capacities remain16 slots.
  private val attributeSlots=2
  private val varyingSlots=1
  private val vertexInputs=4*attributeSlots+3
  private val fragmentInputs=6+4*varyingSlots
  private val vertexBytes=(vertexInputs+4+4*varyingSlots)*stride
  private val fragmentPrefix=(fragmentInputs+18)*stride
  private val fragmentAllocation=(fragmentPrefix+4+63)& ~63
  private val graphics=GraphicsConfig()
  require(capacity==128 && stride==512 && vertexBytes==9728 && fragmentPrefix==14336 && fragmentAllocation==14400)
  private def addi(rd: Int,rs: Int,n: Int): BigInt=(BigInt(n&4095)<<20)|(BigInt(rs)<<15)|(BigInt(rd)<<7)|0x13
  private def vm(base: Int,v: Int,write: Boolean): BigInt=(BigInt(1)<<25)|(BigInt(base)<<15)|(BigInt(6)<<12)|(BigInt(v)<<7)|(if(write)0x27 else 7)
  private def address(rd: Int,rs: Int,offset: Int): Seq[BigInt]={
    if(offset<2048)Seq(addi(rd,rs,offset))else{
      val hi=(offset+2048)>>12;val lo=offset-(hi<<12)
      Seq((BigInt(hi)<<12)|(BigInt(rd)<<7)|0x37,
        (BigInt(rd)<<20)|(BigInt(rs)<<15)|(BigInt(rd)<<7)|0x33,addi(rd,rd,lo))
    }
  }
  private def vectorSetup: BigInt=BigInt("0104f057",16) // NativeDefaultProfile: vsetvli uses ABI x9 capacity
  private val vertexProgram={
    val code=mutable.ArrayBuffer[BigInt](BigInt("00241393",16),BigInt("007083b3",16),vectorSetup)
    val copies=(0 until 4).map(i=>(i,vertexInputs+i))++
      (0 until 4*varyingSlots).map(i=>(4+i%4,vertexInputs+4+i))
    for((input,output)<-copies)
      code++=address(5,7,input*stride)++Seq(vm(5,1,false))++address(6,7,output*stride)++Seq(vm(6,1,true))
    code+=BigInt("30500073",16);code.toVector
  }
  private def fragmentProgram(version: Int=1,depthColor: Boolean=false,facing: Boolean=false)={
    val inputWords=if(version==4)12 else fragmentInputs
    val prefix=(inputWords+18)*stride
    val code=mutable.ArrayBuffer[BigInt](BigInt("00241393",16),BigInt("007083b3",16),vectorSetup)
    for(c<-0 until 4;i<-0 until 4){
      code++=address(5,7,(if(facing && i==0)4 else if(depthColor && i==0)2 else 6+i)*stride)++Seq(vm(5,1,false))
      if(facing && i==0){
        // Export the trusted facing bit as back=0.5, front=1.0.
        def valu(funct6: Int,source: Int,kind: Int): BigInt=(BigInt(funct6)<<26)|(BigInt(1)<<25)|(BigInt(1)<<20)|(BigInt(source)<<15)|(BigInt(kind)<<12)|(BigInt(1)<<7)|0x57
        code++=Seq(valu(0x09,1,3),valu(0x25,23,3),BigInt("3f000737",16),valu(0x00,14,4))
      }
      code++=address(6,7,(inputWords+c*4+i)*stride)++Seq(vm(6,1,true))
    }
    code++=address(13,1,prefix)++Seq((BigInt(13)<<15)|(BigInt(2)<<12)|(BigInt(13)<<7)|3,
      (BigInt(0x17)<<26)|(BigInt(1)<<25)|(BigInt(13)<<15)|(BigInt(4)<<12)|(BigInt(1)<<7)|0x57)++
      address(6,7,(inputWords+17)*stride)++Seq(vm(6,1,true),BigInt("30500073",16));code.toVector
  }
  private class Image(val d: GpuHostSystemAxi){
    val memory=mutable.Map.empty[BigInt,Int];val initial=mutable.Map.empty[BigInt,Int]
    val reads=mutable.ArrayBuffer.empty[BigInt];val writes=mutable.ArrayBuffer.empty[BigInt]
    val writeEvents=mutable.ArrayBuffer.empty[(BigInt,BigInt,BigInt)]
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
      require(version==1 && !centroid && !polygon && !facing && allocation==0 && generation==0 && sourceBias==0 && !freshTextureSources)
      val prefix=fragmentPrefix
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
        val vs=0x500000+n*0x10000;val fs=0x540000+n*0x10000;word(pa(fs+prefix),61)
        assert(vs+vertexBytes<=0x540000 && fs+fragmentAllocation<0x580000)
        var draw=Map[Int,BigInt](16->1,17->1,18->Seq(4,1,0)(n),32->1,33->0x401000,34->2,35->1,36->15,
          40->vs,41->(vs+vertexBytes),43->vs,44->(vs+vertexBytes),64->1,65->7,66->8,67->9,68->positions.size,69->1,
          96->1,99->4,112->1,113->1,115->4,
          480->1,483->4,484->4,486->BigInt("3f800000",16),496->1,497->4,498->4,499->cull,500->(if(ccw)1 else 0),501->mode,
          502->Fp32ViewportReference.fp(2.0),503->BigInt("3f800000",16),
          512->version,513->7,514->8,515->9,516->fragmentPc,520->fs,521->(fs+prefix+4),523->fs,524->(fs+fragmentAllocation),
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
        facing: Boolean=false,ccw: Boolean=true,reverse: Boolean=false,cull: Int=0,primitiveCount: Int=3,primitiveStart: Int=0): Unit={
      val count=1<<mode;val colors=Array.fill(4,4,count)(Vector.empty[BigInt])
      val depths=Array.fill(4,4,count)(TypedDepthReference.encode(Fp32ViewportReference.fp(1),boundDepthFormat))
      val stencils=Array.fill(4,4,count)(5)
      val coords=Vector(Vector((0,0),(1024,0),(0,1024)),Vector((256,512),(768,512),(0,0)),Vector((256,256),(0,0),(0,0)))
      val settings=TriangleRasterReference.Settings(width=4,height=4,mode=mode,maxX=4,maxY=4,ccw=ccw,cull=cull)
      val primitiveQuads=Array.fill(3)(0)
      for(n<-primitiveStart until primitiveCount){val kind=Seq(2,1,0)(n)
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
    def write(a: BigInt,data: BigInt,mask: BigInt): Unit={writes+=a;writeEvents+=((a,data,mask))
      for(b<-0 until 64 if mask.testBit(b))memory(a+b)=((data>>(8*b))&255).toInt}
    private var submittedCycle=BigInt(0)
    def submit(id: Int=23): Unit={
      axiWrite(d,GpuCommandMmioRegs.COMMAND_ID,id);axiWrite(d,GpuCommandMmioRegs.OPCODE,7)
      axiWrite(d,GpuCommandMmioRegs.SOURCE,0x410000);axiWrite(d,GpuCommandMmioRegs.DESTINATION,0x410000+512)
      axiWrite(d,GpuCommandMmioRegs.BYTES,512);axiWrite(d,GpuCommandMmioRegs.PATTERN,0)
      // Fixed public boundary includes the final SUBMIT control write.
      submittedCycle=d.io.performance.get.system.cycles.peek().litValue
      axiWrite(d,GpuCommandMmioRegs.SUBMIT,1)
    }
    def runMeasured(shader: Boolean): ujson.Obj={
      val traffic=new AxiTrafficCounters;val observer=new AxiTrafficObserver(d.io,traffic)
      var serviceCycles=0
      serviceMemoryMaster(d,read,writeAckDelay=7,readResponseDelay=3,maxCycles=1200000,
        noProgressLimit=50000,onCycle=()=>{serviceCycles+=1;observer.sample()})(write){d.io.m_irq.peek().litToBoolean}
      val finishedCycle=d.io.performance.get.system.cycles.peek().litValue
      traffic.assertDrained()
      val t=traffic.snapshot
      val result=axiRead(d,GpuCommandMmioRegs.COMPLETION)
      assert((result&255)==23 && ((result>>8)&7)==7 && ((result>>11)&15)==0 && result.testBit(15))
      if(shader)assert(axiRead(d,GpuCommandMmioRegs.COMPLETION_BYTES_LO)==3*2560)
      // Completion retains VM ownership while held by software.
      axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0,expectedResponse=2)
      assert(axiRead(d,GpuCommandMmioRegs.VECTOR_SATP)==BigInt("80000030",16))
      var quiet=0
      serviceMemoryMaster(d,read,writeAckDelay=7,readResponseDelay=3,maxCycles=32,
        onCycle=()=>{quiet+=1;observer.sample()})(write){quiet==16}
      traffic.assertDrained();assert(traffic.snapshot==t,"accepted traffic after completed image publication")
      ujson.Obj("hostBoundary"->ujson.Str("before-axiWrite-SUBMIT-call to IRQ with accepted R/B drained"),
        "hostClocks"->ujson.Num((finishedCycle-submittedCycle).toDouble),
        "serviceClocks"->ujson.Num(serviceCycles),"readBursts"->ujson.Num(t.readBursts),
        "readBeats"->ujson.Num(t.readBeats),"writeBursts"->ujson.Num(t.writeBursts),
        "writeBeats"->ujson.Num(t.writeBeats),"writeReplies"->ujson.Num(t.writeReplies),
        "readBusBytes"->ujson.Num(t.readBusBytes),"writeBusBytes"->ujson.Num(t.writeBusBytes),
        "enabledWriteBytes"->ujson.Num(t.enabledWriteBytes),"drained"->ujson.Bool(true),
        "quietCycles"->ujson.Num(quiet),"heldCompletionPinsVM"->ujson.Bool(true))
    }
    def pop(): Unit={axiWrite(d,GpuCommandMmioRegs.COMPLETION_POP,1);axiWrite(d,RenderHostRegs.IRQ,3)}
    def verifyClear(): Unit={
      val expected=mutable.Map.empty[BigInt,Int]++initial
      for(v<-views;y<-0 until 4;x<-0 until 4;s<-0 until v.samples){val raw=if(v.kind%2==0)TypedColorReference.encode(names(v.color),clear)._1 else BigInt("05ffffff",16)
        val address=v.base+y*v.pitch+(x*v.samples+s)*v.bytes
        for(b<-0 until v.bytes)expected(address+b)=((raw>>(8*b))&255).toInt}
      expectedImage=expected.toMap
      for((a,v)<-expected)assert(memory(a)==v,s"native image byte${a.toString(16)} observed=${memory(a)} expected=$v")
      assert(writes.exists(_>=0x1000000) && !reads.exists(a=>a>=0x400000 && a<0x800000),"virtual descriptors must not escape onto physical AXI")
    }
  }
  private def memoryHash(values: collection.Iterable[(BigInt,Int)]): String={
    val raw=values.toVector.sortBy(_._1).map{case(a,b)=>s"${a.toString(16)}:$b\n"}.mkString
    java.security.MessageDigest.getInstance("SHA-256").digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)).map(b=>f"${b&255}%02x").mkString
  }
  // One constructor and simulate call site preserves one natural model per variant.
  private def probe(mode: Int,shader: Boolean): Unit={
    require(mode==0 || (mode==2 && shader))
    simulate(new H6TypedWholeHostPerfTop){d=>
      initialize(d);val image=new Image(d)
      assert(axiRead(d,0x160)==BigInt("47544301",16) && axiRead(d,0x168)==BigInt("47544a01",16))
      axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0x80000030)
      axiWrite(d,GpuCommandMmioRegs.INSTRUCTION_SATP,0x80000030)
      axiWrite(d,GpuCommandMmioRegs.TLB_FLUSH,1);axiWrite(d,RenderHostRegs.IRQ,1)
      if(shader)image.shaderDraws(mode)else image.setup(mode)
      val initialHash=memoryHash(image.memory)
      if(shader)assert(image.consumer(30+mode)==BigInt("a7a7a7a7",16))
      image.reads.clear();image.writes.clear();image.writeEvents.clear()
      image.submit();val metric=image.runMeasured(shader)
      if(shader)image.verifyDraws(mode)else image.verifyClear()
      val expectedHash=memoryHash(image.expectedImage)
      val actualHash=memoryHash(image.expectedImage.keys.map(a=>a->image.memory(a)))
      assert(actualHash==expectedHash)
      metric("case")=ujson.Str(if(shader)s"shader-7x5-${1<<mode}x"else"clear-7x5-1x")
      metric("initialMemorySha256")=ujson.Str(initialHash)
      metric("expectedImageSha256")=ujson.Str(expectedHash);metric("actualImageSha256")=ujson.Str(actualHash)
      metric("guardedBytes")=ujson.Num(image.expectedImage.size)
      metric("physicalReads")=ujson.Arr.from(image.reads.map(a=>ujson.Str(a.toString(16))))
      metric("physicalWrites")=ujson.Arr.from(image.writeEvents.map{case(a,v,m)=>ujson.Arr(a.toString(16),v.toString(16),m.toString(16))})
      metric("capacities")=ujson.Str("CUs2 lanes16 warps8 L2sets256 ways4 stores2 I/V64x2 TTI32768 payload65536 max16384 attributes16 varyings16 colors4 transactions16 fullComputeServices")
      metric("imageExtent")=ujson.Str("active4x4 physicalView7x5")
      println("H6_WHOLE_HOST_RESULT "+ujson.write(metric))
      image.pop()
      if(shader){
        val sourceWord=image.views.head.base+1;val sourceLine=sourceWord& ~BigInt(63)
        val expected=(0 until 4).foldLeft(BigInt(0))((v,b)=>v|(BigInt(image.memory(sourceWord+b))<<(8*b)))
        assert(image.consumer(40+mode)==expected)
        val reads=image.reads.count(a=>a>=sourceLine && a<sourceLine+64)
        assert(image.consumer(50+mode)==expected)
        assert(image.reads.count(a=>a>=sourceLine && a<sourceLine+64)==reads)
      }
    }
  }
  it should "measure complete host clear publication at 1x with exact 7x5 guards" in {probe(0,false)}
  it should "measure complete host shader publication at 1x with exact 7x5 guards" in {probe(0,true)}
  it should "measure complete host shader publication at 4x with exact 7x5 guards" in {probe(2,true)}
}
