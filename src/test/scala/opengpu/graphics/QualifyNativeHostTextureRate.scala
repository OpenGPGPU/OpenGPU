package opengpu.graphics

import chisel3._
import chisel3.util.HasBlackBoxPath
import opengpu.testutil.{GpuSim,GraphicsTestResolution}
import GpuSim._
import org.scalatest.DoNotDiscover
import org.scalatest.flatspec.AnyFlatSpec
import java.nio.file.{Files,Path,Paths}
import java.security.MessageDigest
import scala.collection.immutable.ListMap
import scala.collection.mutable
import scala.sys.process._

private[graphics] object NativeHostProductionModel {
  case class Port(name: String,direction: String,width: Int)
  def sha(path: Path): String=MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).map(b=>f"${b&255}%02x").mkString
  lazy val config: ujson.Value={
    val selected=Paths.get(sys.props.getOrElse("opengpu.nativeHostTextureModel",
      throw new IllegalArgumentException("prepare the production RTL and set -Dopengpu.nativeHostTextureModel=DIR"))).toAbsolutePath
    val file=if(Files.isDirectory(selected))selected.resolve("manifest.json")else selected
    val c=ujson.read(Files.readString(file))
    require(c("production_RTL_copied_byte_exact").bool && c("no_DUT_ports_or_logic_modified").bool)
    require(c("configured_clients").num==5 && c("real_SRAMs").num==16)
    require(c("components").arr.map(_("definitions").num.toInt).toVector==Vector(101,101,96))
    require(sha(Paths.get(c("provenance").str))==c("provenance_sha256").str)
    for((name,digest)<-c("source_sha256").obj){
      require(!Paths.get(name).isAbsolute && !name.split('/').contains(".."))
      require(sha(Paths.get(name))==digest.str,s"production source changed: $name")
    }
    require(Process(Seq("git","rev-parse","HEAD"),Paths.get("depends/asap7_sram_0p0").toFile).!!.trim==c("dependency_pin").str)
    for((name,digest)<-c("rtl_sha256").obj)require(sha(Paths.get(c("rtl").str).resolve(name))==digest.str,s"RTL changed: $name")
    require(sha(Paths.get(c("wrapper").str))==c("wrapper_sha256").str)
    require(sha(Paths.get(c("vendor").str))==c("vendor_SRAM_sha256").str)
    c
  }
  lazy val ports: Vector[Port]={
    val result=config("ports").arr.map(p=>Port(p("name").str,p("direction").str,p("width").num.toInt)).toVector
    val required=mutable.ArrayBuffer.empty[String]
    for(c<-0 until 2){
      for(name<-Seq("vectorIn_valid","vectorIn_ready","vectorIn_bits_issued_decode_instruction","vectorIn_bits_issued_decode_warpId",
        "vectorIn_bits_issued_decode_pc","vectorIn_bits_issued_decode_activeMask","vectorIn_bits_executionMask",
        "vectorCommit_valid","vectorCommit_ready","vectorCommit_bits_writeback_warpId","vectorCommit_bits_writeback_vd",
        "vectorCommit_bits_writesVd","vectorCommit_bits_writesScalar","responseFault","resources_version","resources_address",
        "identity_context","identity_pass","identity_generation","clearParameters"))required+=s"n${c}_io_$name"
      for(lane<-0 until 16;name<-Seq(s"vectorIn_bits_issued_vs1Data_$lane",s"vectorIn_bits_issued_vs2Data_$lane",s"vectorCommit_bits_writeback_data_$lane"))
        required+=s"n${c}_io_$name"
      for(name<-Seq("requestFire","replyFire","requestTag","replyTag"))required+=s"monitor${c}_$name"
      for((request,response)<-Seq((s"n${c}_io_memoryRequest_",s"n${c}_io_memoryResponse_"),
        (s"pool_io_memoryRequest_${c}_",s"pool_io_memoryResponse_${c}_"),(s"pool_io_pageWalk_${c}_",s"pool_io_pageWalkResponse_${c}_"))){
        for(name<-Seq("valid","ready","bits_address","bits_transactionId","bits_isWrite"))required+=request+name
        for(name<-Seq("valid","ready","bits_transactionId","bits_readData","bits_fault"))required+=response+name
      }
    }
    for(c<-0 until 5)required+=s"pool_io_cuMask_$c"
    for(c<-2 until 5)required+=s"pool_io_client_${c}_response_ready"
    val missing=required.toSet--result.map(_.name).toSet
    require(missing.isEmpty,s"required production interface is absent: ${missing.toVector.sorted.mkString(",")}")
    result
  }
}

private[graphics] class NativeHostProductionPorts(ports: Vector[NativeHostProductionModel.Port]) extends Record {
  override val elements: ListMap[String,Data]=ListMap(ports.map{p=>
    val value: Data=if(p.name=="clock")Input(Clock())else if(p.direction=="input")Input(UInt(p.width.W))else Output(UInt(p.width.W))
    p.name->value
  }:_*)
}

private[graphics] class NativeHostProductionBlackBox extends BlackBox with HasBlackBoxPath {
  override def desiredName: String=NativeHostProductionModel.config("top").str
  val io=IO(new NativeHostProductionPorts(NativeHostProductionModel.ports))
  private val c=NativeHostProductionModel.config
  for(name<-c("rtl_sha256").obj.keys)addPath(Paths.get(c("rtl").str).resolve(name).toString)
  addPath(c("wrapper").str);addPath(c("vendor").str)
}

/** The DUT definitions are unchanged production SV, not another elaboration. */
private[graphics] class NativeHostTextureRateHarness extends Module {
  val io=IO(new NativeHostProductionPorts(NativeHostProductionModel.ports.filterNot(p=>Set("clock","reset")(p.name))))
  private val dut=Module(new NativeHostProductionBlackBox)
  dut.io.elements("clock").asInstanceOf[Clock]:=clock
  dut.io.elements("reset").asInstanceOf[UInt]:=reset.asUInt
  for((name,field)<-io.elements)field<>dut.io.elements(name)
}

/** Explicit local rate gate for the actual emitted host texture components. */
@DoNotDiscover
class NativeHostTextureRateQualification extends AnyFlatSpec {
  private def fp(f: Float): BigInt=BigInt(java.lang.Float.floatToRawIntBits(f).toLong&0xffffffffL)
  private def instruction(parameter: Boolean,field: Int): BigInt=
    (BigInt(if(parameter)3 else 4)<<26)|(BigInt(1)<<25)|(BigInt(6)<<20)|(BigInt(5)<<15)|
      (BigInt(field)<<12)|(if(parameter)BigInt(0)else BigInt(10)<<7)|0x2b
  it should "sustain two samples per cycle through byte-exact production endpoints and SRAM clusters" in {
    require(!GraphicsTestResolution.productQualification)
    simulate(new NativeHostTextureRateHarness){d=>
      def field(name: String): UInt=d.io.elements.getOrElse(name,throw new IllegalArgumentException(s"production port missing: $name")).asInstanceOf[UInt]
      def set(name: String,value: BigInt): Unit=field(name).poke(value.U)
      def get(name: String): BigInt=field(name).peek().litValue
      def high(name: String): Boolean=get(name)!=0
      def n(c: Int,name: String): String=s"n${c}_io_$name"
      for(p<-NativeHostProductionModel.ports if p.direction=="input" && !Set("clock","reset")(p.name))set(p.name,0)
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      set("pool_io_cuMask_0",1);set("pool_io_cuMask_1",4)
      for(c<-2 until 5)set(s"pool_io_client_${c}_response_ready",1)
      val bytes=mutable.Map.empty[BigInt,Int]
      var cycle=0;var measuring=false
      case class Reply(id: BigInt,value: BigInt,due: Int)
      class Memory(request: String,response: String,ids: Int,walk: Boolean=false){
        val pending=mutable.ArrayBuffer.empty[Reply];var offered=Option.empty[Reply];var seen=0
        def put(a: BigInt,v: BigInt): Unit=for(b<-0 until 4)bytes(a+b)=((v>>(8*b))&255).toInt
        def drive(): Unit={
          set(request+"ready",if(cycle%4==0)0 else 1)
          if(offered.isEmpty){val due=pending.indices.filter(i=>pending(i).due<=cycle)
            if(due.nonEmpty)offered=Some(pending.remove(if(cycle%2==0)due.last else due.head))}
          set(response+"valid",if(offered.nonEmpty)1 else 0)
          offered.foreach{r=>set(response+"bits_transactionId",r.id);set(response+"bits_readData",r.value);set(response+"bits_fault",0)}
        }
        def capture(): Unit={
          if(offered.nonEmpty && high(response+"ready"))offered=None
          if(high(request+"valid") && high(request+"ready")){
            assert(!walk,"Bare production rate check unexpectedly walked a page")
            val address=get(request+"bits_address");val id=get(request+"bits_transactionId")
            assert(!high(request+"bits_isWrite") && address%64==0 && id>=0 && id<ids)
            val value=(0 until 64).foldLeft(BigInt(0))((v,b)=>v|(BigInt(bytes.getOrElse(address+b,0))<<(8*b)))
            pending+=Reply(id,value,cycle+4);seen+=1
          }
        }
        def drained: Boolean=pending.isEmpty && offered.isEmpty
      }
      val metadata=(0 until 2).map(c=>new Memory(n(c,"memoryRequest_"),n(c,"memoryResponse_"),2))
      val data=(0 until 2).map(c=>new Memory(s"pool_io_memoryRequest_${c}_",s"pool_io_memoryResponse_${c}_",16))
      val walks=(0 until 2).map(c=>new Memory(s"pool_io_pageWalk_${c}_",s"pool_io_pageWalkResponse_${c}_",4,true))
      val memories=metadata++data++walks
      val raw=Seq(Seq[BigInt](0x11233444L,0x55667788L,0x9aaabbcdL,0xddeeff01L),
        Seq[BigInt](0xfdeccab1L,0x98764321L,0x7654432aL,0x10203040L))
      val expected=raw.map(xs=>TextureFilterReference.filter(xs.map(x=>ImageFormatReference.decode("RGBA8",x)),Seq(fp(0.5f),fp(0.5f)),2))
      val admitted=Seq.fill(2)(mutable.ArrayBuffer.empty[(Int,BigInt)])
      val replied=Seq.fill(2)(mutable.ArrayBuffer.empty[(Int,BigInt)])
      val retired=Seq.fill(2)(mutable.ArrayBuffer.empty[Int])
      def tick(): Unit={
        memories.foreach(_.drive())
        if(measuring)for(c<-0 until 2){
          if(high(s"monitor${c}_requestFire"))admitted(c)+=((cycle,get(s"monitor${c}_requestTag")))
          if(high(s"monitor${c}_replyFire"))replied(c)+=((cycle,get(s"monitor${c}_replyTag")))
          if(high(n(c,"vectorCommit_valid")) && high(n(c,"vectorCommit_ready"))){
            val warp=get(n(c,"vectorCommit_bits_writeback_warpId")).toInt
            assert(warp>=0 && warp<8 && high(n(c,"vectorCommit_bits_writesVd")) && !high(n(c,"responseFault")))
            assert(get(n(c,"vectorCommit_bits_writeback_vd"))==10)
            for(lane<-0 until 16)assert(get(n(c,s"vectorCommit_bits_writeback_data_$lane"))==expected(c)(warp%4))
            retired(c)+=warp
          }
        }
        memories.foreach(_.capture());d.clock.step();cycle+=1
      }
      def until(condition: =>Boolean): Unit={var count=0;while(!condition && count<60000){tick();count+=1};assert(condition,s"production texture stalled cycle=$cycle")}
      def issue(warp: Int,parameter: Boolean,component: Int,left: BigInt=0,right: BigInt=1): Unit={
        for(c<-0 until 2){
          for(p<-NativeHostProductionModel.ports if p.direction=="input" && p.name.startsWith(n(c,"vectorIn_bits_")))set(p.name,0)
          set(n(c,"vectorIn_bits_issued_decode_instruction"),instruction(parameter,component))
          set(n(c,"vectorIn_bits_issued_decode_warpId"),warp);set(n(c,"vectorIn_bits_issued_decode_pc"),0x200)
          set(n(c,"vectorIn_bits_issued_decode_activeMask"),65535);set(n(c,"vectorIn_bits_executionMask"),65535)
          for(lane<-0 until 16){set(n(c,s"vectorIn_bits_issued_vs1Data_$lane"),left);set(n(c,s"vectorIn_bits_issued_vs2Data_$lane"),right)}
          set(n(c,"vectorIn_valid"),1)
        }
        val pending=mutable.Set(0,1);var count=0
        while(pending.nonEmpty && count<60000){
          val fires=pending.filter(c=>high(n(c,"vectorIn_ready"))).toVector
          tick();for(c<-fires){set(n(c,"vectorIn_valid"),0);pending-=c};count+=1
        };assert(pending.isEmpty)
      }
      def setupRetire(parameter: Boolean): Unit={
        until((0 until 2).forall(c=>high(n(c,"vectorCommit_valid"))))
        for(c<-0 until 2){
          assert(!high(n(c,"responseFault")))
          assert(high(n(c,"vectorCommit_bits_writesVd")) == !parameter)
          assert(high(n(c,"vectorCommit_bits_writesScalar")) == parameter)
          for(lane<-0 until 16)assert(get(n(c,s"vectorCommit_bits_writeback_data_$lane"))==(if(parameter)BigInt(0)else expected(c).head))
          set(n(c,"vectorCommit_ready"),1)
        };tick();for(c<-0 until 2)set(n(c,"vectorCommit_ready"),0)
      }
      for(c<-0 until 2){
        val m=metadata(c);val offset=BigInt(c*0x40000)
        NativeTextureFetchTestData.roots(m.put,stage=c,offset=offset)
        NativeTextureFetchTestData.install(m.put,(a,b)=>bytes(a)=b,0,stage=c,offset=offset,swizzle=Seq(0,1,2,3))
        m.put(NativeTextureFetchTestData.levels+offset+12*4,0x20000+offset)
        m.put(NativeTextureFetchTestData.levels+offset+17*4,8)
        m.put(NativeTextureFetchTestData.samplers+offset+7*4,1);m.put(NativeTextureFetchTestData.samplers+offset+8*4,1)
        for(i<-0 until 4)m.put(0x20000+offset+i*4,raw(c)(i))
        set(n(c,"resources_version"),1);set(n(c,"resources_address"),NativeTextureFetchTestData.root+offset)
        set(n(c,"identity_context"),7);set(n(c,"identity_pass"),11);set(n(c,"identity_generation"),13)
        set(n(c,"clearParameters"),1)
      };tick();for(c<-0 until 2)set(n(c,"clearParameters"),0)
      for(warp<-0 until 8;pair<-0 until 8){issue(warp,true,pair,if(pair==0)fp(0.5f)else BigInt(0),if(pair==0)fp(0.5f)else BigInt(0));setupRetire(true)}
      issue(0,false,0);setupRetire(false);until(memories.forall(_.drained))
      val readsBefore=memories.map(_.seen);val start=cycle;measuring=true
      for(c<-0 until 2)set(n(c,"vectorCommit_ready"),1)
      for(_<-0 until 4;warp<-0 until 8)issue(warp,false,warp%4)
      until(retired.forall(_.size==32) && memories.forall(_.drained));measuring=false
      assert(memories.map(_.seen)==readsBefore,"warm production benchmark performed external reads")
      for(c<-0 until 2){
        assert(admitted(c).size==512 && replied(c).size==512 && admitted(c).map(_._2)==replied(c).map(_._2))
        assert((0 until 8).forall(w=>retired(c).count(_==w)==4))
        val middle=admitted(c).slice(32,480);assert(middle.last._1-middle.head._1+1==448)
        assert(admitted(c).zip(replied(c)).forall{case(a,b)=>b._1-a._1==92})
      }
      val first=admitted.map(_(32)._1).max;val last=admitted.map(_(479)._1).min
      val accepted=admitted.flatten.groupBy(_._1).view.mapValues(_.size).toMap
      val replies=replied.flatten.groupBy(_._1).view.mapValues(_.size).toMap
      assert(last-first+1==448 && (first to last).forall(t=>accepted.getOrElse(t,0)==2))
      assert((first+92 to last+92).forall(t=>replies.getOrElse(t,0)==2))
      val checked=retired.map(_.size).sum*16;assert(checked==1024)
      info(s"NATIVE_PRODUCTION_TEXTURE_RATE byteExactRTL=true uniformAvailable=true realSRAMs=16 clients=5 activeClients=2 CUs=4 lanes=16 warps=8 instructions=64 samples=1024 taps=4 steadyCycles=448 aggregateSamplesPerCycle=2 replyLatency=92 exactLaneResults=$checked totalCycles=${cycle-start} warmExternalReads=0 publicOrder=true clockQualified=false FPSQualified=false")
    }
  }
}

object QualifyNativeHostTextureRate {
  def main(args: Array[String]): Unit={
    require(args.length==2 && !GraphicsTestResolution.productQualification)
    if(args(0)=="--check-model"){
      sys.props("opengpu.nativeHostTextureModel")=args(1)
      NativeHostProductionModel.config
      println(s"NATIVE_PRODUCTION_TEXTURE_MODEL PASS ports=${NativeHostProductionModel.ports.size} components=101/101/96 realSRAMs=16 hardwareExecuted=false");return
    }
    require(args(0)=="--reports")
    val suite=new NativeHostTextureRateQualification;require(suite.testNames.size==1)
    require(org.scalatest.tools.Runner.run(Array("-s",classOf[NativeHostTextureRateQualification].getName,"-oD","-u",args(1))))
    val report=scala.xml.XML.loadFile(new java.io.File(args(1),"TEST-"+classOf[NativeHostTextureRateQualification].getName+".xml"))
    val cases=report \\ "testcase"
    require((report \ "@tests").text=="1" && cases.size==1 && cases.map(n=>(n \ "@name").text).toSet==suite.testNames.toSet &&
      Seq("errors","failures","skipped").forall(a=>report.attribute(a).forall(_.text=="0")) && Seq("failure","error","skipped").forall(n=>(report \\ n).isEmpty))
    println("NATIVE_PRODUCTION_TEXTURE_RATE PASS cases=1 byteExactRTL=true clockQualified=false FPSQualified=false")
  }
}
