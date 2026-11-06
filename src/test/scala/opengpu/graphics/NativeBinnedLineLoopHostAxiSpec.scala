package opengpu.graphics

import chisel3._
import java.nio.file.{Files,Path,Paths,StandardOpenOption}
import opengpu.system.{GpuHostSystemAxi,GpuHostTestSupport}
import opengpu.testutil.GpuSim._
import org.scalatest.DoNotDiscover
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** Software-only observer/driver. The factory is deliberately owned by the
  * separately qualified H8 fixture; this file adds no Module, IO or DUT option.
  * A root-reviewed external compiler-rejecting gate must prove the natural HIT.
  * The opt-in property below does not itself prevent a GpuSim cache miss/build.
  */
@DoNotDiscover
class NativeBinnedLineLoopHostAxiSpec extends AnyFlatSpec with GpuHostTestSupport {
  private case class Region(name:String,address:BigInt,offset:Int,bytes:Int,writable:Boolean)
  private class Fixture(directory:Path,val entry:ujson.Value) {
    val index=entry("index").num.toInt
    val base=BigInt(entry("base").num.toLong)
    val bytes=Files.readAllBytes(directory.resolve(entry("memoryFile").str))
    val original=bytes.clone()
    val expected=Files.readAllBytes(directory.resolve(entry("expectedImageFile").str))
    val regions=entry("regions").arr.map{r=>Region(r("name").str,BigInt(r("address").num.toLong),
      r("offset").num.toInt,r("bytes").num.toInt,r("writable").bool)}.toVector
    require(index>=0 && index<3 && base==0x100000+index*0x200000)
    require(bytes.length==0xd1000 && expected.length==128 && entry("memoryBytes").num.toInt==bytes.length)
    require(entry("generation").num.toInt==9+index && entry("commandId").num.toInt==41+index)
    require(entry("opcode").num.toInt==7 && entry("jobBase").num.toLong==base && entry("jobLimit").num.toLong==base+512)
    require(entry("expectedStatus").num.toInt==(if(index==1)2 else 0))
    require(entry("expectedFaultStage").num.toInt==(if(index==1)3 else 0))
    require(entry("expectedFaultAddress").num.toLong==(if(index==1)base+0x4000 else BigInt(0)))
    require(entry("redQuantum").num.toInt==(if(index==2)64 else 32))
    private val spans=Vector((0,512),(0x1000,2048),(0x2000,2048),(0x3000,512),(0x4000,64),
      (0x5000,128),(0x6000,1024),(0x7000,1024),(0x10000,0x20000),(0x40000,0x10000),
      (0x50000,0x10000),(0x80000,0x10000),(0xa0000,0x10000),(0xc0000,64),(0xd0000,128))
    require(regions.map(r=>(r.offset,r.bytes))==spans)
    require(regions.zipWithIndex.forall{case(r,i)=>r.address==base+r.offset && r.writable==(i>=8)})
    require(bytes.slice(0xd0000,0xd0080).forall(_==0xa7.toByte))
    val literal=Vector(1,1,1,1,1,1,0,1,1,1,1,1,1,1,1,1)
    val oracle=Array.fill[Byte](128)(0xa7.toByte)
    if(index!=1)for(pixel<-0 until 16){val offset=3+pixel/4*21+pixel%4*4
      oracle(offset)=(literal(pixel)*(if(index==2)64 else 32)).toByte
      oracle(offset+1)=0;oracle(offset+2)=0;oracle(offset+3)=(if(literal(pixel)==0)0 else 255).toByte}
    require(java.util.Arrays.equals(expected,oracle),"C image differs from independent literal oracle")
    def mappedLine(address:BigInt):Boolean={val a=address & ~BigInt(63)
      regions.exists(r=>a>=r.address && a+64<=r.address+r.bytes)}
    def readLine(address:BigInt):BigInt={val a=address & ~BigInt(63);assert(mappedLine(a),s"unmapped read $a")
      (0 until 64).foldLeft(BigInt(0))((v,i)=>v|(BigInt(bytes((a-base).toInt+i)&255)<<(8*i)))}
    def write(address:BigInt,data:BigInt,mask:BigInt):Unit={
      for(i<-0 until 64 if mask.testBit(i)){
        val a=address+i;assert(regions.exists(r=>r.writable && a>=r.address && a<r.address+r.bytes),s"store outside current owned spans $a")
        bytes((a-base).toInt)=((data>>(8*i))&255).toByte}}
    def verify():Unit={
      for(r<-regions if !r.writable)assert(java.util.Arrays.equals(bytes.slice(r.offset,r.offset+r.bytes),
        original.slice(r.offset,r.offset+r.bytes)),s"read-only ${r.name} changed")
      assert(java.util.Arrays.equals(bytes.slice(0xd0000,0xd0080),expected),"exact pixel or pitched guard mismatch")}
  }
  private final class Monitor(d:GpuHostSystemAxi) {
    private val reads=mutable.Map.empty[BigInt,mutable.ArrayBuffer[Int]]
    private val held=mutable.Map.empty[String,BigInt]
    var cycles=0;var ar=0;var rlast=0;var aw=0;var wlast=0;var b=0
    private def offer(name:String,valid:Boolean,ready:Boolean,bits:BigInt):Unit={
      held.get(name).foreach(old=>assert(valid && old==bits,s"held $name changed"))
      if(valid && !ready)held(name)=bits else held.remove(name)}
    private def snapshot(values:UInt*):BigInt=values.foldLeft(BigInt(0))((bits,value)=>
      (bits<<value.getWidth)|value.peek().litValue)
    def tick():Unit={
      cycles+=1
      offer("AR",d.io.m_axi_arvalid.peek().litToBoolean,d.io.m_axi_arready.peek().litToBoolean,
        snapshot(d.io.m_axi_araddr,d.io.m_axi_arid,d.io.m_axi_arlen,d.io.m_axi_arsize,d.io.m_axi_arburst))
      offer("AW",d.io.m_axi_awvalid.peek().litToBoolean,d.io.m_axi_awready.peek().litToBoolean,
        snapshot(d.io.m_axi_awaddr,d.io.m_axi_awid,d.io.m_axi_awlen,d.io.m_axi_awsize,d.io.m_axi_awburst))
      offer("W",d.io.m_axi_wvalid.peek().litToBoolean,d.io.m_axi_wready.peek().litToBoolean,
        snapshot(d.io.m_axi_wdata,d.io.m_axi_wstrb,d.io.m_axi_wlast))
      if(d.io.m_axi_arvalid.peek().litToBoolean && d.io.m_axi_arready.peek().litToBoolean){
        val id=d.io.m_axi_arid.peek().litValue;reads.getOrElseUpdate(id,mutable.ArrayBuffer.empty)+=d.io.m_axi_arlen.peek().litValue.toInt+1;ar+=1}
      if(d.io.m_axi_rvalid.peek().litToBoolean && d.io.m_axi_rready.peek().litToBoolean){
        val id=d.io.m_axi_rid.peek().litValue;val queue=reads.getOrElse(id,throw new AssertionError("unowned R"))
        assert(queue.nonEmpty && d.io.m_axi_rresp.peek().litValue==0)
        val remaining=queue.head;assert(d.io.m_axi_rlast.peek().litToBoolean==(remaining==1))
        if(remaining==1){queue.remove(0);rlast+=1}else queue(0)=remaining-1}
      if(d.io.m_axi_awvalid.peek().litToBoolean && d.io.m_axi_awready.peek().litToBoolean)aw+=1
      if(d.io.m_axi_wvalid.peek().litToBoolean && d.io.m_axi_wready.peek().litToBoolean && d.io.m_axi_wlast.peek().litToBoolean)wlast+=1
      if(d.io.m_axi_bvalid.peek().litToBoolean && d.io.m_axi_bready.peek().litToBoolean){assert(d.io.m_axi_bresp.peek().litValue==0);b+=1;assert(b<=aw && b<=wlast)}
    }
    def drained():Unit=assert(reads.values.forall(_.isEmpty) && held.isEmpty && ar==rlast && aw==wlast && aw==b)
  }
  behavior of "Native binned LINE_LOOP legacy admission"
  it should "retain both loop-closing edges across restart and reuse after a bounded index failure" in {
    require(sys.props.get("opengpu.nativeLineLoopQualification").contains("1"),"local qualification must be explicitly selected")
    val directory=Paths.get(sys.props("opengpu.nativeLineLoopInputs"))
    val output=Paths.get(sys.props("opengpu.nativeLineLoopOutput"));require(!Files.exists(output));Files.createDirectories(output)
    val manifest=ujson.read(Files.readString(directory.resolve("fixture.json")))
    require(manifest("schema").str=="opengpu-native-line-loop-v1" && manifest("cpuPreparedOnly").bool)
    require(Vector("width","height","samples","lanes","warps","computeUnits","nativeEngines","attributes","bindings","varyings","colors").map(n=>manifest(n).num.toInt)==Vector(4,4,1,16,8,2,2,16,16,16,4))
    require(manifest("maxServiceCycles").num.toInt==600000)
    val fixtures=manifest("submissions").arr.map(new Fixture(directory,_)).toVector;require(fixtures.map(_.index)==Vector(0,1,2))
    // This exact pre-existing factory must be bridged/pinned from the H8 commit.
    // Do not copy its hardware expression into a second factory or add probes.
    simulate(TessellationOwnedTextureHostFixture.host()){d=>
      initialize(d);d.sramModelsPresent.expect(true.B);axiWrite(d,RenderHostRegs.IRQ,1)
      assert(axiRead(d,GpuCommandMmioRegs.VECTOR_SATP)==0 && axiRead(d,GpuCommandMmioRegs.INSTRUCTION_SATP)==0)
      for(f<-fixtures){
        val reads=mutable.ArrayBuffer.empty[BigInt];val monitor=new Monitor(d)
        var acceptedWrites=0
        var observedCompletion=Option.empty[BigInt]
        val observedDetails=mutable.ArrayBuffer.empty[BigInt]
        val observation=output.resolve(s"observations-${f.index}.json")
        def saveObservation():Unit=Files.writeString(observation,ujson.write(ujson.Obj(
          "index"->f.index,"completion"->observedCompletion.map(v=>ujson.Str(v.toString(16))).getOrElse(ujson.Null),
          "details"->ujson.Arr.from(observedDetails.map(v=>ujson.Str(v.toString(16)))),
          "acceptedReadAddresses"->ujson.Arr.from(reads.map(v=>ujson.Str(v.toString(16)))),
          "acceptedWrites"->acceptedWrites,"cycles"->monitor.cycles,"ar"->monitor.ar,"rlast"->monitor.rlast,
          "aw"->monitor.aw,"wlast"->monitor.wlast,"b"->monitor.b),indent=2),StandardOpenOption.CREATE_NEW):Unit
        try {
          axiWrite(d,GpuCommandMmioRegs.COMMAND_ID,f.entry("commandId").num.toInt)
          axiWrite(d,GpuCommandMmioRegs.OPCODE,7);axiWrite(d,GpuCommandMmioRegs.SOURCE,f.base.toInt)
          axiWrite(d,GpuCommandMmioRegs.DESTINATION,(f.base+512).toInt);axiWrite(d,GpuCommandMmioRegs.BYTES,512)
          axiWrite(d,GpuCommandMmioRegs.PATTERN,0);axiWrite(d,GpuCommandMmioRegs.FLAGS,0);axiWrite(d,GpuCommandMmioRegs.SUBMIT,1)
          serviceMemoryMaster(d,f.readLine,readFault=a=>{assert(f.mappedLine(a));false},
            writeAckDelay=5,readResponseDelay=11,maxCycles=600000,onReadAccepted=a=>reads+=a,
            onCycle=()=>monitor.tick())({(address,data,mask)=>acceptedWrites+=1;f.write(address,data,mask)}){d.io.m_irq.peek().litToBoolean}
          val result=axiRead(d,GpuCommandMmioRegs.COMPLETION);observedCompletion=Some(result)
          for(i<-0 until 12)observedDetails+=axiRead(d,0x180+4*i)
          val details=observedDetails.toVector
          saveObservation()
          monitor.drained()
          val status=if(f.index==1)2 else 0
          assert((result&255)==41+f.index && ((result>>8)&7)==7 && !result.testBit(16))
          assert(((result>>11)&15)==status && result.testBit(15)==(status==0))
          assert(details.head==BigInt("474e5801",16) && (details(1)&7)==status && details(11)==0)
          assert(details.slice(6,9)==Seq[BigInt](7,8,9+f.index))
          if(f.index==1){
            assert(((details(1)>>3)&3)==3 && details(2)==f.base+0x4000 && details(3)==0)
            assert(!reads.exists(a=>a>=f.base+0x4000 && a<f.base+0x5080),"invalid index span reached source data")
          }else{
            assert(details(1)==0 && details(5)==0 && acceptedWrites>0)
            assert(reads.exists(a=>a>=f.base+0x4000 && a<f.base+0x4040),"no actual index access")
            assert(reads.exists(a=>a>=f.base+0x6000 && a<f.base+0x6400),"vertex shader did not execute")
            assert(reads.exists(a=>a>=f.base+0x7000 && a<f.base+0x7400),"fragment shader did not execute")
          }
          f.verify()
          val held=result
          axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0x80000034L.toInt,expectedResponse=2)
          axiWrite(d,GpuCommandMmioRegs.INSTRUCTION_SATP,0x80000034L.toInt,expectedResponse=2)
          assert(axiRead(d,GpuCommandMmioRegs.COMPLETION)==held)
          assert(axiRead(d,GpuCommandMmioRegs.VECTOR_SATP)==0 && axiRead(d,GpuCommandMmioRegs.INSTRUCTION_SATP)==0)
          axiWrite(d,GpuCommandMmioRegs.COMPLETION_POP,1);axiWrite(d,RenderHostRegs.IRQ,3)
          assert((0 until 12).forall(i=>axiRead(d,0x180+4*i)==0))
          Files.writeString(output.resolve(s"submission-${f.index}.json"),ujson.write(ujson.Obj(
            "index"->f.index,"status"->status,"cycles"->monitor.cycles,"ar"->monitor.ar,"rlast"->monitor.rlast,
            "aw"->monitor.aw,"wlast"->monitor.wlast,"b"->monitor.b,"ownedDrain"->true,
            "exactImageAndGuards"->true,"immutableInputs"->true,"commandOpcode"->7),indent=2),StandardOpenOption.CREATE_NEW)
        } finally {
          try Files.write(output.resolve(s"returned-memory-${f.index}.bin"),f.bytes,StandardOpenOption.CREATE_NEW)
          finally if(!Files.exists(observation))saveObservation()
        }
      }
    }
  }
}
