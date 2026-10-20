package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse,TranslatedLineClient,VectorTlbFlush}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private[graphics] object TextureSamplingReference {
  import TextureFilterReference.{bits,value}
  case class Axis(first: Int,second: Int,weight: BigInt)
  def axis(raw: BigInt,n: Int,mode: Int,linear: Boolean): Axis={
    val e=((raw>>23)&255).toInt;require(e!=255 && n>0)
    val m=(raw&0x7fffff)+(if(e==0)BigInt(0)else BigInt(1)<<23);val shift=if(e==0)-149 else e-150
    val den=if(shift<0)BigInt(1)<<(-shift)else BigInt(1)
    val num=(if(raw.testBit(31))-m else m)*(if(shift>0)BigInt(1)<<shift else BigInt(1))
    val bounded=if(mode==1)num.max(0).min(den)else if(mode==3)num.max(-den).min(den*2)else num
    val numerator=bounded*n*2-(if(linear)den else BigInt(0));val denominator=den*2
    val div=numerator/denominator;val floor=if(numerator%denominator<0)div-1 else div
    def mod(i: BigInt,d: BigInt): BigInt=((i%d)+d)%d
    def wrapped(i: BigInt): Int=mode match {
      case 0=>mod(i,n).toInt
      case 1=>i.max(0).min(n-1).toInt
      case 2=>val j=mod(i,2*n);(if(j<n)j else 2*n-1-j).toInt
      case 3=>i.max(-1).min(n).toInt
    }
    val fraction=if(linear)numerator-floor*denominator else BigInt(0)
    val decimal=new java.math.BigDecimal(fraction.bigInteger).divide(new java.math.BigDecimal(denominator.bigInteger))
    Axis(wrapped(floor),wrapped(if(linear)floor+1 else floor),bits(java.lang.Float.parseFloat(decimal.toPlainString)))
  }
  // Independent direction-vector projection, with full integer division.
  def cube(face: Int,i: Int,j: Int,n: Int): (Int,Int,Int)={
    val bx=i<0 || i>=n;val by=j<0 || j>=n
    if(!bx && !by)return(face,i,j)
    if(bx && by)return(face,i.max(0).min(n-1),j.max(0).min(n-1))
    val sc=2*i+1-n;val tc=2*j+1-n
    val (x,y,z)=face match {
      case 0=>(n,-tc,-sc);case 1=>(-n,-tc,sc);case 2=>(sc,n,tc)
      case 3=>(sc,-n,-tc);case 4=>(sc,-tc,n);case 5=>(-sc,-tc,-n)
    }
    val major=Seq(x.abs,y.abs,z.abs).max
    val (f,s,t)=if(x.abs==major){if(x>0)(0,-z,-y)else(1,z,-y)}
      else if(y.abs==major){if(y>0)(2,x,z)else(3,x,-z)}else{if(z>0)(4,x,-y)else(5,-x,-y)}
    (f,((s.toLong+major)*n/(2L*major)).toInt,((t.toLong+major)*n/(2L*major)).toInt)
  }
  def comparison(ref: BigInt,depth: BigInt,function: Int,fixed: Boolean): BigInt={
    def clamp(v: Float): Float=math.max(0.0f,math.min(1.0f,v))
    val a=if(fixed)clamp(value(ref))else value(ref);val b=if(fixed)clamp(value(depth))else value(depth)
    val pass=function match {case 0=>false;case 1=>a<b;case 2=>a==b;case 3=>a<=b;case 4=>a>b;case 5=>a!=b;case 6=>a>=b;case 7=>true}
    if(pass)bits(1.0f)else BigInt(0)
  }
}
private class CubeTapHarness extends Module {
  val io=IO(new Bundle {val face=Input(UInt(3.W));val x=Input(SInt(16.W));val y=Input(SInt(16.W));val n=Input(UInt(14.W));val out=Output(new TextureCubeTap)})
  io.out:=TextureCubeTap(io.face,io.x,io.y,io.n)
}
private class LevelSampleHarness(translated: Boolean=false) extends Module {
  private val config=GpuConfig(lanes=4,warps=2)
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new TextureImageLevelSampleRequest));val out=Decoupled(new TextureImageLevelSampleResult)
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,8));val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,8)))
    val cancel=Input(Bool());val invalidate=Input(Bool());val busy=Output(Bool());val satp=Input(UInt(32.W));val flush=Flipped(Valid(new VectorTlbFlush(config)))
  })
  private val sample=Module(new TextureImageLevelSampleUnit(config));sample.io.in<>io.in;io.out<>sample.io.out
  sample.io.cancel:=io.cancel;sample.io.invalidate:=io.invalidate;io.busy:=sample.io.busy
  if(translated){val vm=Module(new TranslatedLineClient(config,4,preserveCachePolicy=true))
    vm.io.in<>sample.io.memoryRequest;sample.io.memoryResponse<>vm.io.out;vm.io.satp:=io.satp;vm.io.flush<>io.flush
    val arb=Module(new RRArbiter(new ComputeMemoryRequest(config,64,8),2))
    arb.io.in(0).valid:=vm.io.memReq.valid;arb.io.in(0).bits:=vm.io.memReq.bits;arb.io.in(0).bits.transactionId:=vm.io.memReq.bits.transactionId.pad(3)
    vm.io.memReq.ready:=arb.io.in(0).ready;arb.io.in(1).valid:=vm.io.pageWalk.valid;arb.io.in(1).bits:=vm.io.pageWalk.bits
    arb.io.in(1).bits.transactionId:=4.U;vm.io.pageWalk.ready:=arb.io.in(1).ready;io.memoryRequest<>arb.io.out
    val walk=io.memoryResponse.bits.transactionId===4.U
    vm.io.memResp.valid:=io.memoryResponse.valid && !walk;vm.io.memResp.bits:=io.memoryResponse.bits
    vm.io.pageWalkResp.valid:=io.memoryResponse.valid && walk;vm.io.pageWalkResp.bits:=io.memoryResponse.bits;vm.io.pageWalkResp.bits.transactionId:=0.U
    io.memoryResponse.ready:=Mux(walk,vm.io.pageWalkResp.ready,vm.io.memResp.ready)
  }else{io.memoryRequest.valid:=sample.io.memoryRequest.valid;io.memoryRequest.bits:=sample.io.memoryRequest.bits
    sample.io.memoryRequest.ready:=io.memoryRequest.ready;sample.io.memoryResponse.valid:=io.memoryResponse.valid
    sample.io.memoryResponse.bits:=io.memoryResponse.bits;io.memoryResponse.ready:=sample.io.memoryResponse.ready}
}
class TextureImageLevelSampleUnitSpec extends AnyFlatSpec {
  import WindowRecordTest._
  import TextureFilterReference.{bits,filter}
  import TextureSamplingReference._
  private case class Image(format: Int,target: Int=0,w: Int=7,h: Int=5,d: Int=3,layers: Int=3,base: BigInt=0x10003c,nanDepth: Boolean=false) {
    val compressed=format>=54;val info=if(compressed)None else Some(ImageFormatReference.format(ImageFormatReference.names(format)))
    val bytes=if(compressed)CompressedImageReference.format(format).bytes else info.get.bytes
    val width=w;val height=if(target==2)w else h;val depth=if(target==3)d else 1;val count=if(target==1)layers else 1
    val columns=if(compressed)(width+3)/4 else width;val rows=if(compressed)(height+3)/4 else height
    val row=columns*bytes+13;val plane=(rows-1)*row+columns*bytes
    val slice=if(target==3)plane+7 else 0;val layerPitch=if(target==1 || target==2)plane+11 else 0
    val planes=if(target==2)6 else count
    val limit=base+(planes-1)*layerPitch+(depth-1)*slice+plane
    val kind=if(compressed)0 else info.get.kind
    def address(x: Int,y: Int,z: Int=0,layer: Int=0,face: Int=0): BigInt=
      base+(if(compressed)x/4 else x)*bytes+(if(compressed)y/4 else y)*row+z*slice+(if(target==2)face else layer)*layerPitch
    def raw(x: Int,y: Int,z: Int,layer: Int,face: Int): BigInt={
      val bx=if(compressed)x/4 else x;val by=if(compressed)y/4 else y
      val random=new scala.util.Random(0x4c455645L+format*997+bx*31+by*173+z*453+layer*1559+face*17117)
      if(format>=49 && format<54){val p=(x+3*y+z+layer+face)%5
        if(format==51 || format==53)(BigInt(0xa7563412L)<<32)|(if(nanDepth && p==0)BigInt(0x7fc12345L)else bits(p.toFloat/2.0f-0.5f))
        else if(format==49)BigInt((65535L*p)/4)else(BigInt(0xa7)<<24)|BigInt((0xffffffL*p)/4)
      }else BigInt(bytes*8,random)
    }
    def rgba(x: Int,y: Int,z: Int,layer: Int,face: Int): Seq[BigInt]={
      val data=raw(x,y,z,layer,face)
      if(compressed)CompressedImageReference.decode(format,data,x&3,y&3)else ImageFormatReference.decode(ImageFormatReference.names(format),data)
    }
  }
  private case class Sample(u: BigInt=bits(0.4f),v: BigInt=bits(0.6f),w: BigInt=bits(0.3f),linear: Boolean=true,
    wraps: Seq[Int]=Seq(0,1,2),layer: Int=0,face: Int=0,shadow: Boolean=false,function: Int=3,reference: BigInt=bits(0.5f),
    swizzle: Seq[Int]=Seq(3,2,5,0),applySwizzle: Boolean=true)
  private class Driver(val d: LevelSampleHarness) {
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    val memory=new Memory(d.io.memoryRequest.bits,d.io.memoryResponse.bits,d.io.memoryRequest.valid,d.io.memoryRequest.ready,d.io.memoryResponse.valid,d.io.memoryResponse.ready)
    var tag=BigInt(0);var lastImage=Image(3);var lastSample=Sample();var checks=0
    memory.beforeRequest=r=>{assert(!r.write && (r.tag==2 || r.tag==3 || r.tag==4));if(r.tag!=4)assert(r.address%64==0 && r.mask==(BigInt(1)<<64)-1)}
    def tick(): Unit={memory.drive();memory.capture();d.clock.step()}
    def until(p: =>Boolean): Unit={var n=0;while(!p && n<20000){tick();n+=1};assert(p,s"level sample timeout tag=$tag reads=${memory.seen.takeRight(6)}")}
    def install(i: Image,translate: BigInt=>BigInt=(a: BigInt)=>a): Unit={
      for(plane<-0 until i.planes;z<-0 until i.depth;row<-0 until i.rows;col<-0 until i.columns){
        val x=col*(if(i.compressed)4 else 1);val y=row*(if(i.compressed)4 else 1)
        val layer=if(i.target==1)plane else 0;val face=if(i.target==2)plane else 0
        val address=i.address(x,y,z,layer,face);val raw=i.raw(x,y,z,layer,face)
        for(b<-0 until i.bytes)memory.bytes(translate(address+b))=((raw>>(8*b))&255).toInt
      }
    }
    def query(i: Image,s: Sample=Sample(),edit: TextureImageLevelSampleRequest=>Unit=(_: TextureImageLevelSampleRequest)=>()): Unit={
      val q=d.io.in.bits;zero(q);tag+=1;lastImage=i;lastSample=s
      identity(q.image.identity);identity(q.image.selected.table.identity);identity(q.sampler.identity)
      q.image.validBinding.poke(true.B);q.image.format.poke(i.format.U);q.image.selected.table.version.poke(1.U)
      q.image.selected.table.base.poke(0x2000.U);q.image.selected.table.limit.poke(0x2080.U)
      val v=q.image.view;v.target.poke(i.target.U);v.allocationBase.poke(i.base.U);v.allocationLimit.poke(i.limit.U);v.base.poke(i.base.U)
      v.texWidth.poke(i.width.U);v.texHeight.poke(i.height.U);v.texDepth.poke(i.depth.U);v.layers.poke(i.count.U)
      v.rowPitch.poke(i.row.U);v.slicePitch.poke(i.slice.U);v.layerPitch.poke(i.layerPitch.U)
      v.block4x4.poke(i.compressed.B);v.blockBytes.poke(i.bytes.U)
      q.image.swizzle.version.poke(1.U);Seq(q.image.swizzle.r,q.image.swizzle.g,q.image.swizzle.b,q.image.swizzle.a).zip(s.swizzle).foreach{case(a,b)=>a.poke(b.U)}
      q.sampler.validSampler.poke(true.B);val state=q.sampler.state
      state.minFilter.poke((if(s.linear)1 else 0).U);state.magFilter.poke((if(s.linear)1 else 0).U)
      state.wrapS.poke(s.wraps(0).U);state.wrapT.poke(s.wraps(1).U);state.wrapR.poke(s.wraps(2).U)
      state.compareEnable.poke(s.shadow.B);state.compareFunction.poke(s.function.U)
      q.coordinates.zip(Seq(s.u,s.v,s.w)).foreach{case(a,b)=>a.poke(b.U)};q.layer.poke(s.layer.U);q.face.poke(s.face.U)
      q.linear.poke(s.linear.B);q.shadow.poke(s.shadow.B);q.reference.poke(s.reference.U);q.applySwizzle.poke(s.applySwizzle.B);q.tag.poke(tag.U);edit(q)
      d.io.in.valid.poke(true.B);until(d.io.in.ready.peek().litToBoolean);tick();d.io.in.valid.poke(false.B);zero(q)
    }
    def reference(i: Image,s: Sample): (Seq[BigInt],Int)={
      val mode=if(i.target==2){if(s.linear)3 else 1}else -1
      val axes=Seq(axis(s.u,i.width,if(mode<0)s.wraps(0)else mode,s.linear),axis(s.v,i.height,if(mode<0)s.wraps(1)else mode,s.linear),
        if(i.target==3)axis(s.w,i.depth,s.wraps(2),s.linear)else Axis(0,0,0))
      val unique=mutable.Set.empty[(Int,Int,Int,Int)];val colors=(0 until 8).map{tap=>
        val selected=(0 until 3).forall{a=>a==2 && i.target!=3 || ((axes(a).weight!=0 || (tap&(1<<a))==0) && (axes(a).weight!=bits(1.0f) || (tap&(1<<a))!=0))}
        if((!s.linear && tap!=0) || (i.target!=3 && tap>=4) || !selected)Seq.fill(4)(BigInt(0))else{
          val xyz=axes.zipWithIndex.map{case(a,n)=>if((tap&(1<<n))==0)a.first else a.second}
          val (face,x,y)=if(i.target==2)cube(s.face,xyz(0),xyz(1),i.width)else(s.face,xyz(0),xyz(1))
          unique+=((face,x,y,xyz(2)));val color=i.rgba(x,y,xyz(2),s.layer,face)
          if(s.shadow)Seq(comparison(s.reference,color(0),s.function,i.format!=51 && i.format!=53),BigInt(0),BigInt(0),bits(1.0f))else color
        }
      }
      val result=filter(colors,axes.map(_.weight),if(!s.linear)0 else if(i.target==3)3 else 2)
      val options=result++Seq(BigInt(0),if(i.kind==0 || s.shadow)bits(1.0f)else BigInt(1))
      (if(s.applySwizzle)s.swizzle.map(options)else result,unique.size)
    }
    def check(status: Int=0,source: Int=0,fault: Option[BigInt]=None): Unit={
      until(d.io.out.valid.peek().litToBoolean);val out=d.io.out.bits
      withClue(s"tag=$tag format=${lastImage.format} target=${lastImage.target} sample=$lastSample "){
        out.status.expect(status.U);out.tag.expect(tag.U);out.faultSource.expect(source.U);out.faultAddressValid.expect(fault.nonEmpty.B);out.faultAddress.expect(fault.getOrElse(BigInt(0)).U)
        if(status==0){val (expected,taps)=reference(lastImage,lastSample)
          val actual=out.rgba.map(_.peek().litValue).toVector
          assert(actual==expected,s"RGBA mismatch tag=$tag image=$lastImage sample=$lastSample actual=${actual.map(_.toString(16))} expected=${expected.map(_.toString(16))}")
          out.uniqueTaps.expect(taps.U);out.colorType.expect((if(lastSample.shadow)0 else lastImage.kind).U)
        }else{out.rgba.foreach(_.expect(0.U));out.colorType.expect(0.U)}
      }
      assert(memory.pending.isEmpty && memory.reply.isEmpty);val held=out.peek().litValue
      d.io.cancel.poke(true.B);d.io.invalidate.poke(true.B)
      for(_<-0 until 3){tick();assert(out.peek().litValue==held);d.io.in.ready.expect(false.B)}
      d.io.out.ready.poke(true.B);tick();d.io.out.ready.poke(false.B);d.io.cancel.poke(false.B);d.io.invalidate.poke(false.B);d.io.busy.expect(false.B);checks+=1
    }
  }
  it should "map every cube edge orientation and corner deterministically across every extent" in {
    simulate(new CubeTapHarness){d=>var cases=0
      def check(face: Int,x: Int,y: Int,n: Int): Unit={
        d.io.face.poke(face.U);d.io.x.poke(x.S);d.io.y.poke(y.S);d.io.n.poke(n.U);d.clock.step()
        val (f,a,b)=cube(face,x,y,n);d.io.out.face.expect(f.U);d.io.out.x.expect(a.U);d.io.out.y.expect(b.U);cases+=1
      }
      for(n<-Seq(1,2,3,7,53,256,16383);face<-0 until 6;k<-Seq(0,n/2,n-1).distinct){
        for((x,y)<-Seq((-1,k),(n,k),(k,-1),(k,n),(-1,-1),(-1,n),(n,-1),(n,n)))check(face,x,y,n)
      }
      for(n<-1 until 16384){val k=n/3;val (x,y)=n%4 match{case 0=>(-1,k);case 1=>(n,k);case 2=>(k,-1);case _=>(k,n)};check(n%6,x,y,n)}
      info(s"CUBE_TAP_REMAP queries=$cases faces6=true edgeOrientations24=true everyExtent16383=true integerDirectionProjectionOracle=true cornersChooseAvailableCurrentFaceSample=true")
    }
  }
  it should "sample all storage kinds and filter every supported format before applying image swizzle" in {
    simulate(new LevelSampleHarness()){d=>val t=new Driver(d)
      for(format<-0 until 64){val i=Image(format,target=if(format>=49)format%3 else format%4);t.install(i)
        t.query(i,Sample(linear=false,layer=if(i.target==1)2 else 0,face=if(i.target==2)5 else 0));t.check()}
      for(format<-0 until 64 if (format>=54 || ImageFormatReference.format(ImageFormatReference.names(format)).filterable);
        target<-0 to 3 if !(format>=54 && target==3);mode<-0 to 2){
        val i=Image(format,target);t.install(i);val s=Sample(wraps=Seq(mode,(mode+1)%3,(mode+2)%3),
          u=bits(if(mode==0)-0.2f else if(mode==1)1.2f else 0.4f),layer=if(target==1)2 else 0,face=if(target==2)(format+mode)%6 else 0,
          applySwizzle=mode!=2)
        t.query(i,s);t.check()
      }
      info(s"LEVEL_SAMPLE_FORMATS queries=${t.checks} nearestFormats64=true everyFilterableFormat=true NPOT7x5=true targets4=true wraps3=true scalarFP32Weights=true decodedBeforeFilter=true postFilterOrDeferredSwizzle=true noDescriptorReread=true")
    }
  }
  it should "filter seamless cube edges and permitted corner samples across all faces including compressed sRGB" in {
    simulate(new LevelSampleHarness()){d=>val t=new Driver(d)
      for(format<-Seq(3,8,54,59);n<-Seq(1,7)){val i=Image(format,target=2,w=n);t.install(i)
        for(face<-0 until 6;(u,v)<-Seq((0f,0.37f),(1f,0.37f),(0.37f,0f),(0.37f,1f),(0f,0f),(0f,1f),(1f,0f),(1f,1f))){
          t.query(i,Sample(u=bits(u),v=bits(v),face=face,swizzle=Seq(0,1,2,3)));t.check()
        }
      }
      info(s"LEVEL_SAMPLE_CUBE queries=${t.checks} allFacesEdgesAndCorners=true sizes1And7=true sRGBAndETC2EACBeforeFilter=true uniqueTexelAliasing=true")
    }
  }
  it should "compare every depth format with every GXS1 function before filtering and swizzling" in {
    simulate(new LevelSampleHarness()){d=>val t=new Driver(d)
      for(format<-49 to 53;nan<- (if(format==51 || format==53)Seq(false,true)else Seq(false))){val i=Image(format,target=format%3,nanDepth=nan);t.install(i)
        for(function<-0 until 8;ref<-Seq(-2f,0f,0.5f,1f,2f,Float.NegativeInfinity,Float.PositiveInfinity,Float.NaN);linear<-Seq(false,true)){
          t.query(i,Sample(shadow=true,function=function,reference=bits(ref),linear=linear,
            layer=if(i.target==1)2 else 0,face=if(i.target==2)4 else 0,swizzle=Seq(0,5,4,3)));t.check()
        }
      }
      info(s"LEVEL_SAMPLE_SHADOW queries=${t.checks} depthFormats5=true GXS1Functions8=true fixedReferenceClamped=true floatingReferenceUnclamped=true NaNReferenceAndDepthUnordered=true compareBeforePCFFilter=true postFilterSwizzle=true")
    }
  }
  it should "prune zero-weight taps retain required faults and drain coordinate data and filtering work" in {
    simulate(new LevelSampleHarness()){d=>val t=new Driver(d);val i=Image(34);t.install(i)
      val s=Sample(swizzle=Seq.fill(4)(5));val badLine=i.address(2,3)& ~BigInt(63)
      t.memory.faultAt=Set(badLine);t.query(i,s);t.check(3,source=2,fault=Some(badLine));t.memory.faultAt=Set.empty
      t.memory.faultAt=Set(badLine);t.query(i,s.copy(u=bits(0.5f),v=bits(0.5f)));t.check();t.memory.faultAt=Set.empty
      for(edit<-Seq[TextureImageLevelSampleRequest=>Unit](_.coordinates(0).poke(0x7fc12345L.U),_.face.poke(0x10000000L.U),_.layer.poke(0xffffffffL.U))){
        val before=t.memory.seen.size;t.query(i,s,edit);t.check(5,source=4);assert(t.memory.seen.size==before)}
      for(invalid<-Seq(Image(26),Image(54,target=3),Image(49,target=3))){t.install(invalid);val before=t.memory.seen.size
        t.query(invalid,s);t.check(6,source=3);assert(t.memory.seen.size==before)}
      val beforeMismatch=t.memory.seen.size;t.query(i,s.copy(shadow=true));t.check(6,source=3);assert(t.memory.seen.size==beforeMismatch)
      t.install(i)
      t.memory.holdRequests=true;t.query(i,s);t.until(d.io.memoryRequest.valid.peek().litToBoolean)
      val offered=d.io.memoryRequest.bits.peek().litValue;d.io.cancel.poke(true.B)
      for(_<-0 until 13){t.tick();assert(d.io.memoryRequest.bits.peek().litValue==offered);d.io.out.valid.expect(false.B)}
      t.memory.holdRequests=false;t.check(4)
      var cancelled=0;var held=0
      for(cut<-(0 until 64)++Seq(96,128,256,512,1024)){
        t.query(i,s);for(_<-0 until cut if !d.io.out.valid.peek().litToBoolean)t.tick()
        val complete=d.io.out.valid.peek().litToBoolean;d.io.cancel.poke(true.B);t.check(if(complete)0 else 4)
        if(complete)held+=1 else cancelled+=1
      }
      assert(cancelled>0 && held>0);t.query(i,s);t.check()
      info(s"LEVEL_SAMPLE_DRAIN cuts69=true cancelled=$cancelled held=$held requiredLateTapFaultNotMaskedByConstantSwizzle=true zeroWeightNeighborNotRead=true fullWidthCoordinatesAndSelectors=true heldDataOffer13=true allChildrenDrained=true healthyReuse=true")
    }
  }
  it should "translate selected data pages and report a late denied page without descriptor memory access" in {
    simulate(new LevelSampleHarness(translated=true)){d=>val t=new Driver(d);val i=Image(34,w=7,h=5,base=0x100ffc)
      val root=BigInt(0x80000);val pt=BigInt(0x90000);t.memory.putWord(root,(pt>>12)<<10|1)
      def pa(a: BigInt): BigInt=if(a<0x101000)BigInt(0x60000)+(a&4095)else BigInt(0x70000)+(a&4095)
      def map(vpn: Int,physical: BigInt,flags: Int=0x43): Unit=t.memory.putWord(pt+vpn*4,(physical>>12)<<10|flags)
      map(0x100,0x60000);map(0x101,0x70000);d.io.satp.poke(((BigInt(1)<<31)|(root>>12)).U);t.install(i,pa)
      val s=Sample(u=bits(0.0f),v=bits(0.0f),linear=false,wraps=Seq(1,1,1));t.query(i,s);t.check()
      map(0x101,0x70000,0x49);zero(d.io.flush.bits);d.io.flush.valid.poke(true.B);t.tick();d.io.flush.valid.poke(false.B)
      val before=t.memory.seen.size;t.query(i,s);t.check(3,source=2,fault=Some(BigInt(0x101000)))
      assert(!t.memory.seen.drop(before).exists(r=>r.tag!=4 && r.address==0x70000))
      map(0x101,0x70000);d.io.flush.valid.poke(true.B);t.tick();d.io.flush.valid.poke(false.B);t.query(i,s);t.check()
      info("LEVEL_SAMPLE_VM realSv32=true unmappedDescriptors=true crossPageTexel=true noncontiguousPhysicalPages=true secondPageDeniedBeforePhysicalRead=true virtualFaultAddress=true flushRecovery=true")
    }
  }
}
