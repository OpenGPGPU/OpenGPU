package opengpu.graphics

import chisel3._
import opengpu.testutil.{GraphicsTestResolution, GpuSim}
import GpuSim._
import org.scalatest.DoNotDiscover
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private[graphics] object NativeTextureRateFixtures {
  val profiles=Vector("trilinear","misses","bank-conflicts","dependent")
  def fp(f: Float): BigInt=BigInt(java.lang.Float.floatToRawIntBits(f).toLong & 0xffffffffL)
  def width(p: String): Int=if(p=="misses")128 else if(p=="bank-conflicts")3 else if(p=="trilinear")4 else 2
  def height(p: String): Int=width(p)
  def pitch(p: String): Int=if(p=="misses")512 else 64
  def base(c: Int): BigInt=BigInt(0x20000+c*0x40000)
  def limit(p: String,c: Int): BigInt=base(c)+(if(p=="misses")65536 else 4096)
  def raw(p: String,c: Int,level: Int,x: Int,y: Int): BigInt={
    val rgb=p match {
      case "dependent"=>Vector(if((x==0)==(c==0))255 else 0,if(y==0)0 else 255,if((x^y)==0)0 else 255)
      case "trilinear"=>Vector(32,64,96).map(_+level*128+c*8)
      case "bank-conflicts"=>Vector((17+x*x*29+y*13+c*11)&255,(31+x*17+y*y*37+c*19)&255,(241-x*19-y*23-c*13)&255)
      case "misses"=>
        val index=(y/4)*16+((y%4)/2)*8+x/16
        Vector((1+index+c*64)&255,(255-index-c*32)&255,(index*3+c*17)&255)
    }
    BigInt(rgb(0))|(BigInt(rgb(1))<<8)|(BigInt(rgb(2))<<16)|(BigInt(255)<<24)
  }
  def coordinates(p: String,w: Int,lane: Int): (Float,Float)=p match {
    case "dependent"=>(0.375f,0.5f)
    case "bank-conflicts"=>(1f,1f)
    case "misses"=>(((lane%8)*16+1).toFloat/128f,(w*4+(lane/8)*2+1).toFloat/128f)
    case _=>(0.5f,0.5f)
  }
  // These measured coordinates reduce to exact binary fractions. Integer
  // wrapping and the independent FP32 filter oracle determine all outputs.
  private def axis(value: Float,size: Int): (Int,Int,BigInt)={
    val position=value*size-0.5f;val lower=math.floor(position.toDouble).toInt
    def wrap(x: Int): Int=((x%size)+size)%size
    (wrap(lower),wrap(lower+1),fp(position-lower))
  }
  def expected(p: String,c: Int,w: Int,lane: Int,u: Float=0.375f): Vector[BigInt]={
    val initial=coordinates(p,w,lane);val uv=if(p=="dependent")(u,initial._2)else initial
    val levels=if(p=="trilinear")2 else 1
    val values=(0 until levels).flatMap { level =>
      val size=width(p)>>level
      val(x0,x1,_)=axis(uv._1,size);val(y0,y1,_)=axis(uv._2,size)
      Seq((x0,y0),(x1,y0),(x0,y1),(x1,y1)).map{case(x,y)=>ImageFormatReference.decode("RGBA8",raw(p,c,level,x,y))}
    }
    val(_,_,wx)=axis(uv._1,width(p));val(_,_,wy)=axis(uv._2,height(p))
    TextureFilterReference.filter(values,Seq(wx,wy)++(if(levels==2)Seq(fp(0.5f))else Nil),if(levels==2)3 else 2).toVector
  }
  def install(p: String,c: Int,put: (BigInt,BigInt)=>Unit,byte: (BigInt,Int)=>Unit): Unit={
    import NativeTextureFetchTestData._
    val offset=BigInt(c*0x40000)
    require(width(p)<=GraphicsTestResolution.width && height(p)<=GraphicsTestResolution.height)
    roots(put,stage=c,offset=offset)
    NativeTextureFetchTestData.install(put,byte,0,format=ImageFormatReference.names.indexOf("RGBA8"),stage=c,offset=offset)
    put(images+offset+10*4,if(p=="trilinear")1 else 0)
    put(images+offset+11*4,width(p));put(images+offset+12*4,height(p))
    put(images+offset+15*4,base(c));put(images+offset+16*4,limit(p,c))
    val count=if(p=="trilinear")2 else 1
    put(images+offset+20*4,levels+offset+count*128)
    put(samplers+offset+7*4,if(p=="trilinear")5 else 1)
    put(samplers+offset+8*4,1)
    for(level<-0 until count){
      val w=width(p)>>level;val h=height(p)>>level
      val data=base(c)+level*1024
      words(put,levels+offset+level*128,Seq[BigInt](BigInt("47584c01",16),128,1,7,11,13,
        level,0,level,base(c),limit(p,c),0,data,w,h,1,1,pitch(p),0,0,0,0,0,0,4),32)
      for(y<-0 until h;x<-0 until w)put(data+y*pitch(p)+x*4,raw(p,c,level,x,y))
    }
  }
  def validate(): Unit=for(p<-profiles;c<-0 until 2){
    val bytes=mutable.Map.empty[BigInt,Int]
    def put(a: BigInt,v: BigInt): Unit=for(b<-0 until 4)bytes(a+b)=((v>>(8*b))&255).toInt
    install(p,c,put,(a,v)=>bytes(a)=v)
    for(w<-0 until 8;l<-0 until 16){
      val value=expected(p,c,w,l);require(value.size==4 && value.forall(x=>x>=0 && x.bitLength<=32))
    }
    if(p=="bank-conflicts"){
      val taps=Seq((2,2),(0,2),(2,0),(0,0))
      require(taps.map{case(x,y)=>((y&1)<<1)|(((base(c)+y*64+x*4)>>2).toInt&1)}.toSet==Set(0))
    }
  }
}

/** Explicit local characterization, separate from the resident II1 gate. */
@DoNotDiscover
class NativeTextureOtherRatesQualification extends AnyFlatSpec {
  import WindowRecordTest._
  import NativeTextureRateFixtures._
  private val selected=sys.props.get("opengpu.textureRateProfile").map { value =>
    require(profiles.contains(value));Vector(value)
  }.getOrElse(profiles)
  private def instruction(parameter: Boolean,field: Int): BigInt=
    (BigInt(if(parameter)3 else 4)<<26)|(BigInt(1)<<25)|(BigInt(6)<<20)|(BigInt(5)<<15)|
      (BigInt(field)<<12)|(if(parameter)BigInt(0)else BigInt(10)<<7)|0x2b

  for(profile<-selected)
  it should s"measure native texture characteristics with exact results profile=$profile" in {
    require(!GraphicsTestResolution.productQualification,"bounded local characterization only")
    simulate(new NativeTextureDualClusterHarness) { d =>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val shared=mutable.Map.empty[BigInt,Int]
      val metadata=(0 until 2).map { c =>
        val n=d.io.native(c)
        new Memory(n.memoryRequest.bits,n.memoryResponse.bits,n.memoryRequest.valid,n.memoryRequest.ready,n.memoryResponse.valid,n.memoryResponse.ready){override val bytes=shared}
      }
      val data=(0 until 2).map { c =>
        new Memory(d.io.dataRequest(c).bits,d.io.dataResponse(c).bits,d.io.dataRequest(c).valid,d.io.dataRequest(c).ready,d.io.dataResponse(c).valid,d.io.dataResponse(c).ready){override val bytes=shared}
      }
      val walks=(0 until 2).map { c =>
        new Memory(d.io.pageWalk(c).bits,d.io.pageWalkResponse(c).bits,d.io.pageWalk(c).valid,d.io.pageWalk(c).ready,d.io.pageWalkResponse(c).valid,d.io.pageWalkResponse(c).ready){override val bytes=shared}
      }
      val memories=metadata++data++walks
      val acks=Array(0,0);val currentU=Array(0.375f,0.375f)
      val retired=Seq.fill(2)(mutable.ArrayBuffer.empty[(Int,Int,Vector[BigInt])])
      val offered=Seq.fill(2)(mutable.ArrayBuffer.empty[Int])
      val admitted=Seq.fill(2)(mutable.ArrayBuffer.empty[(Int,BigInt)])
      val replied=Seq.fill(2)(mutable.ArrayBuffer.empty[(Int,BigInt,Int,Int)])
      var cycle=0;var measuring=false;var parameterOffer=false
      for(c<-0 until 2){
        install(profile,c,metadata(c).putWord,(a,b)=>metadata(c).bytes(a)=b)
        val n=d.io.native(c);n.resources.version.poke(1.U)
        n.resources.address.poke((NativeTextureFetchTestData.root+c*0x40000).U)
        identity(n.identity);n.vectorCommit.ready.poke(true.B);n.commit.ready.poke(true.B)
        data(c).delay=if(profile=="misses")17 else 4
        metadata(c).beforeRequest=r=>assert(!r.write && r.tag<2 && r.address%64==0)
        data(c).beforeRequest=r=>assert(!r.write && r.tag<16 && r.address%64==0 && r.address>=base(c) && r.address<limit(profile,c))
        walks(c).beforeRequest=_=>assert(false,"bare characterization unexpectedly requested a page walk")
      }
      def drained: Boolean=memories.forall(m=>m.pending.isEmpty && m.reply.isEmpty && m.held.isEmpty)
      def tick(): Unit={
        memories.foreach(_.drive())
        for(c<-0 until 2){
          val n=d.io.native(c)
          if(measuring && n.vectorIn.valid.peek().litToBoolean && n.vectorIn.ready.peek().litToBoolean && !parameterOffer)offered(c)+=cycle
          if(measuring && d.io.groupFire(c).peek().litToBoolean)admitted(c)+=((cycle,d.io.groupTag(c).peek().litValue))
          if(measuring && d.io.replyFire(c).peek().litToBoolean)replied(c)+=((cycle,d.io.replyTag(c).peek().litValue,d.io.replyTaps(c).peek().litValue.toInt,d.io.replyReads(c).peek().litValue.toInt))
          if(n.vectorCommit.valid.peek().litToBoolean && n.vectorCommit.ready.peek().litToBoolean){
            val q=n.vectorCommit.bits;val warp=q.writeback.warpId.peek().litValue.toInt
            assert(warp>=0 && warp<8);n.responseFault.expect(false.B)
            if(q.writesVd.peek().litToBoolean){
              q.writeback.vd.expect(10.U)
              val values=q.writeback.data.map(_.peek().litValue).toVector
              val component=if(profile=="dependent")0 else warp%4
              val want=Vector.tabulate(16)(lane=>expected(profile,c,warp,lane,currentU(c))(component))
              assert(values==want,s"$profile cluster=$c warp=$warp result=$values expected=$want")
              retired(c)+=((cycle,warp,values))
            }else{
              q.writesScalar.expect(true.B);q.scalarData.expect(0.U);acks(c)+=1
            }
          }
        }
        memories.foreach(_.capture());d.clock.step();cycle+=1
      }
      def until(condition: =>Boolean): Unit={
        var n=0;while(!condition && n<500000){tick();n+=1}
        assert(condition,s"native $profile stalled cycle=$cycle commits=${retired.map(_.size)}")
      }
      def issue(warp: Int,parameter: Boolean,field: Int,left: Seq[Seq[BigInt]],right: Seq[Seq[BigInt]]): Unit={
        parameterOffer=parameter
        for(c<-0 until 2){
          val n=d.io.native(c);val q=n.vectorIn.bits;zero(q)
          q.issued.decode.instruction.poke(instruction(parameter,field).U);q.issued.decode.warpId.poke(warp.U)
          q.issued.decode.pc.poke(0x200.U);q.issued.decode.activeMask.poke(65535.U);q.executionMask.poke(65535.U)
          q.issued.vs1Data.zip(left(c)).foreach{case(a,b)=>a.poke(b.U)}
          q.issued.vs2Data.zip(right(c)).foreach{case(a,b)=>a.poke(b.U)}
          n.vectorIn.valid.poke(true.B)
        }
        val pending=mutable.Set(0,1);var n=0
        while(pending.nonEmpty && n<500000){
          memories.foreach(_.drive())
          val fires=pending.filter(c=>d.io.native(c).vectorIn.ready.peek().litToBoolean).toVector
          tick();for(c<-fires){d.io.native(c).vectorIn.valid.poke(false.B);pending-=c};n+=1
        }
        assert(pending.isEmpty)
      }
      def parameters(warp: Int,pair: Int): Unit={
        val before=acks.toVector;val reads=metadata.map(_.seen.size)
        def value(c: Int,l: Int,index: Int): BigInt=index match {
          case 0=>fp(if(profile=="dependent")currentU(c)else coordinates(profile,warp,l)._1)
          case 1=>fp(coordinates(profile,warp,l)._2)
          case 5 if profile=="trilinear"=>fp(0.5f)
          case _=>BigInt(0)
        }
        issue(warp,true,pair,(0 until 2).map(c=>Vector.tabulate(16)(l=>value(c,l,pair*2))),
          (0 until 2).map(c=>Vector.tabulate(16)(l=>value(c,l,pair*2+1))))
        until((0 until 2).forall(c=>acks(c)==before(c)+1))
        assert(metadata.map(_.seen.size)==reads,"parameter update issued memory traffic")
      }
      def sample(warp: Int): Unit=issue(warp,false,if(profile=="dependent")0 else warp%4,
        Seq.fill(2)(Vector.fill(16)(BigInt(0))),Seq.fill(2)(Vector.fill(16)(BigInt(1))))
      d.io.native.foreach(_.clearParameters.poke(true.B));tick();d.io.native.foreach(_.clearParameters.poke(false.B))
      for(warp<- (if(profile=="dependent")Seq(0)else 0 until 8);pair<-0 until 8)parameters(warp,pair)
      sample(0);until(retired.forall(_.size==1))
      until(d.io.native.forall(n=> !n.busy.peek().litToBoolean) && !d.io.busy.peek().litToBoolean && drained)
      retired.foreach(_.clear())
      val metadataBefore=metadata.map(_.seen.size);val dataBefore=data.map(_.seen.size)
      measuring=true
      val instructions=if(profile=="dependent")16 else 32
      if(profile=="dependent"){
        for(i<-0 until instructions){
          sample(0);until(retired.forall(_.size==i+1))
          if(i+1<instructions){
            for(c<-0 until 2)currentU(c)=java.lang.Float.intBitsToFloat(retired(c).last._3.head.toInt)
            // Pair zero begins a fresh tuple; republish every captured pair.
            for(pair<-0 until 8)parameters(0,pair)
          }
        }
      }else for(i<-0 until instructions)sample(if(profile=="misses")(i+2)%8 else i%8)
      until(retired.forall(_.size==instructions))
      until(d.io.native.forall(n=> !n.busy.peek().litToBoolean) && !d.io.busy.peek().litToBoolean && drained)
      measuring=false
      for(c<-0 until 2){
        assert(offered(c).size==instructions)
        val samples=instructions*16;val span=retired(c).last._1-offered(c).head+1
        val nativeReads=metadata(c).seen.drop(metadataBefore(c))
        val genericData=nativeReads.count(r=>r.address>=base(c) && r.address<limit(profile,c))
        val descriptorReads=nativeReads.size-genericData
        val readLines=data(c).seen.size-dataBefore(c)
        assert(walks(c).seen.isEmpty)
        // Trilinear uses the ordinary native owner and two banked bilinear
        // groups per lane, one for each mip, before mixing their FP32 results.
        val groupsPerSample=if(profile=="trilinear")2 else 1
        assert(admitted(c).size==samples*groupsPerSample && replied(c).size==samples*groupsPerSample,
          s"$profile cluster=$c groups=${admitted(c).size}/${replied(c).size} expected=${samples*groupsPerSample}")
        assert(admitted(c).map(_._2)==replied(c).map(_._2),"private group tags reordered")
        assert(replied(c).map(_._4).sum==readLines,"physical read charges do not match actual traffic")
        if(profile!="dependent")assert(replied(c).forall(_._3==4))
        if(profile=="misses")assert(readLines>=224,"the working set did not exceed the resident data cache")
        else assert(readLines==0,"resident measurement performed external data reads")
        assert(genericData==0,"RGBA8 bilinear groups unexpectedly bypassed the banked data service")
        if(profile=="dependent")assert(retired(c).map(_._3.head).distinct.size>=3,"dependent chain never changed coordinates")
        val middle=if(replied(c).size>=480)replied(c).slice(32,480)else replied(c).toSeq
        val steadyCycles=if(middle.nonEmpty)middle.last._1-middle.head._1+1 else 0
        info(s"NATIVE_TEXTURE_CHARACTERISTIC profile=$profile cluster=$c lanes=16 warps=8 instructions=$instructions samples=$samples nativeCycles=$span groupSamples=${replied(c).size} steadyGroupSamples=${middle.size} steadyGroupCycles=$steadyCycles descriptorReadLines=$descriptorReads genericDataReadLines=$genericData cacheDataReadLines=$readLines readReplyDelay=${data(c).delay} dependentParameterUpdates=${profile=="dependent"} exactVectorWords=true bareTranslation=true clockQualified=false fpsQualified=false")
      }
    }
  }
}

object QualifyNativeTextureOtherRates {
  def main(args: Array[String]): Unit={
    require(!GraphicsTestResolution.productQualification)
    if(args.sameElements(Array("--inputs-only"))){
      NativeTextureRateFixtures.validate()
      println("NATIVE_TEXTURE_CHARACTERISTIC_INPUTS PASS profiles=4 hardwareExecuted=false");return
    }
    require(args.length==2 && args(0)=="--reports","usage: --reports DIR | --inputs-only")
    val suite=new NativeTextureOtherRatesQualification
    val count=if(sys.props.contains("opengpu.textureRateProfile"))1 else 4
    require(suite.testNames.size==count)
    require(org.scalatest.tools.Runner.run(Array("-s",classOf[NativeTextureOtherRatesQualification].getName,"-oD","-u",args(1))))
    val report=scala.xml.XML.loadFile(new java.io.File(args(1),"TEST-"+classOf[NativeTextureOtherRatesQualification].getName+".xml"))
    val cases=report \\ "testcase"
    require((report \ "@tests").text==count.toString && cases.size==count &&
      cases.map(n=>(n \ "@name").text).toSet==suite.testNames.toSet &&
      Seq("errors","failures","skipped").forall(a=>report.attribute(a).forall(_.text=="0")) &&
      Seq("failure","error","skipped").forall(n=>(report \\ n).isEmpty))
    println(s"NATIVE_TEXTURE_CHARACTERISTIC PASS cases=$count clockQualified=false fpsQualified=false")
  }
}
