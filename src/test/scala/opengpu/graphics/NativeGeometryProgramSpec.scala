package opengpu.graphics

import chisel3._
import circt.stage.ChiselStage
import opengpu.config.{CachePolicy,GpuConfig}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class NativeGeometryProgramSpec extends AnyFlatSpec {
  import WindowRecordTest._
  private val top=BigInt(1)<<32
  private val base=BigInt(0x1000)
  private def image: Vector[BigInt]={
    val w=Array.fill[BigInt](256)(0)
    w(0)=BigInt("474e4701",16);w(1)=1024;w(2)=1;w(3)=7;w(4)=11;w(5)=13
    w(6)=6;w(7)=32;w(8)=2;w(9)=16;w(10)=64;w(11)=64;w(13)=16;w(14)=16;w(15)=1
    w(16)=0x8004;w(17)=0x8000;w(18)=0x8800;w(20)=0x8000;w(21)=0x8800
    w(23)=2;w(24)=0x9000;w(25)=0x87654321L;w(26)=0x12345678
    for(i<-0 until 64)w(32+i)=(i*13)%68
    for(i<-0 until 68){w(96+i*2)=1;w(97+i*2)=(i*7)%64}
    w.toVector
  }
  private class Driver(val d: NativeGeometryProgramReader){
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    val memory=new Memory(d.io.memoryRequest.bits,d.io.memoryResponse.bits,d.io.memoryRequest.valid,d.io.memoryRequest.ready,
      d.io.memoryResponse.valid,d.io.memoryResponse.ready)
    val replies=mutable.ArrayBuffer.empty[BigInt];var peak=0;var tag=0
    memory.delay=16
    memory.beforeRequest=r=>{
      assert(!r.write && r.mask==(BigInt(1)<<64)-1 && r.address%64==0 && r.address+64<=top)
      d.io.memoryRequest.bits.sizeLog2.expect(6.U);d.io.memoryRequest.bits.cachePolicy.expect(CachePolicy.uncached)
    }
    def tick(): Unit={
      memory.drive()
      if(d.io.memoryResponse.valid.peek().litToBoolean && d.io.memoryResponse.ready.peek().litToBoolean)replies+=memory.reply.get.address
      memory.capture();peak=peak.max(memory.pending.size+memory.reply.size);assert(peak<=4);d.clock.step()
    }
    def until(p: =>Boolean): Unit={var n=0;while(!p && n<2000){tick();n+=1};assert(p,s"GNG reader stalled reads=${memory.seen.takeRight(5)}")}
    def fixture(words: Vector[BigInt]=image,address: BigInt=base): Unit=words.zipWithIndex.foreach{case(v,i)=>memory.putWord(address+i*4,v)}
    def start(address: BigInt=base,limit: BigInt=base+1024,version: Int=1,topology: Int=12): Unit={
      val b=d.io.in.bits;zero(b);identity(b.identity);b.tag.poke(tag.U);tag+=1
      b.resources.version.poke(version.U);b.resources.base.poke(address.U);b.resources.limit.poke(limit.U)
      b.topology.poke(topology.U);b.vertexVaryingSlots.poke(16.U);b.fragmentVaryingSlots.poke(16.U)
      d.io.in.valid.poke(true.B);until(d.io.in.ready.peek().litToBoolean);tick();d.io.in.valid.poke(false.B);zero(b)
    }
    def finish(code: Int,check: Boolean=false,fault: Option[BigInt]=None): Unit={
      until(d.io.out.valid.peek().litToBoolean);val r=d.io.out.bits
      r.status.expect(code.U);r.tag.expect((tag-1).U);r.identity.context.expect(7.U);r.identity.pass.expect(11.U);r.identity.generation.expect(13.U)
      r.faultAddressValid.expect(fault.nonEmpty.B);r.faultAddress.expect(fault.getOrElse(BigInt(0)).U)
      assert(memory.pending.isEmpty && memory.reply.isEmpty);d.io.memoryRequest.valid.expect(false.B)
      if(code!=0)assert(r.program.peek().litValue==0,"failed preflight exposed optimistic GS metadata")
      if(check){
        r.readBytes.expect(1024.U);r.program.inputVertices.expect(6.U);r.program.invocations.expect(32.U)
        r.program.maxVertices.expect(16.U);r.program.inputComponents.expect(64.U);r.program.outputComponents.expect(64.U)
        r.program.vertexVaryingSlots.expect(16.U);r.program.fragmentVaryingSlots.expect(16.U)
        r.program.pc.expect(0x8004.U);r.program.code.base.expect(0x8000.U);r.program.code.limit.expect(0x8800.U)
        r.program.uniforms.version.expect(2.U);r.program.uniforms.address.expect(0x9000.U)
        r.program.flatMask.expect(BigInt("1234567887654321",16).U)
        for(i<-0 until 64)r.program.inputSources(i).expect(((i*13)%68).U)
        for(i<-0 until 68){r.program.outputSources(i).valid.expect(true.B);r.program.outputSources(i).bits.expect(((i*7)%64).U)}
      }
      val held=r.peek().litValue;d.io.cancel.poke(true.B)
      for(_<-0 until 4){tick();d.io.out.valid.expect(true.B);assert(r.peek().litValue==held);d.io.in.ready.expect(false.B)}
      d.io.out.ready.poke(true.B);tick();d.io.out.ready.poke(false.B);d.io.cancel.poke(false.B);d.io.busy.expect(false.B)
    }
    def healthy(): Unit={fixture();start();finish(0,check=true)}
  }
  it should "keep GND1 frozen while publishing explicit GND2 and full GNG1 offsets" in {
    var pf=Map.empty[String,(Int,Int)];var df=Map.empty[String,(Int,Int)]
    class Describe extends RawModule {
      val p=Wire(new NativeGeometryProgram);p:=0.U.asTypeOf(p)
      val d=Wire(new NativeGeometryDrawRecord);d:=0.U.asTypeOf(d)
      val a=NativeGeometryProgramLayout.fields(p);val b=NativeGeometryDrawLayout.fields(d)
      NativeTypedGraphicsLayout.check(p,a,256);NativeTypedGraphicsLayout.check(d,b,512)
      pf=a.map(f=>f.name->(f.word,f.width)).toMap;df=b.map(f=>f.name->(f.word,f.width)).toMap
    }
    ChiselStage.emitCHIRRTL(new Describe)
    assert(pf("inputVertices")==(6,3) && pf("invocations")==((7,6)) && pf("maxVertices")==((9,9)))
    assert(pf("code.limit")==(18,33) && pf("code.allocationLimit")==((21,33)))
    assert(pf("inputSources[63]")==(95,7) && pf("outputSources[67].source")==((231,7)))
    assert(df("geometry.version")==(508,2) && df("geometry.base")==((509,32)) && df("geometry.limit")==((510,33)))
    opengpu.elaboration.EmitNativeGeometryAbi.main(Array("--check"))
    opengpu.elaboration.EmitNativeBinnedGraphicsAbi.main(Array("--check"))
    intercept[IllegalArgumentException]{ChiselStage.emitCHIRRTL(new NativeGeometryProgramReader())}
  }
  it should "snapshot full mappings with all four tags, reordered replies and bounded admission" in {
    simulate(new NativeGeometryProgramReader(GpuConfig(lanes=4,warps=2),geometryAvailable=true)){d=>
      val t=new Driver(d);t.fixture();t.memory.holdResponses=true;t.start()
      t.until(t.memory.pending.size==4);for(_<-0 until 24)t.tick();assert(t.peak==4)
      t.memory.holdResponses=false;t.finish(0,check=true)
      assert(t.replies.take(4)==Seq(3,2,1,0).map(i=>base+i*64),"four-tag out-of-order coverage did not occur")
      for((address,limit,version)<-Seq((base+4,base+1024,1),(base,base+1020,1),(top-960,top,1),(base,base+1024,0),(base,base+1024,2))){
        val before=t.memory.seen.size;t.start(address,limit,version);t.finish(2);assert(t.memory.seen.size==before)
      }
      t.fixture(address=top-1024);t.start(top-1024,top);t.finish(0,check=true);t.healthy()
    }
  }
  it should "check capacities and total components independently without narrowing adjacency or links" in {
    simulate(new NativeGeometryProgramReader(GpuConfig(lanes=4,warps=2),geometryAvailable=true)){d=>
      val t=new Driver(d)
      for((word,value,code)<-Seq((7,33,1),(7,0,1),(9,17,1),(9,257,1),(10,65,1),(11,65,1),
        (13,17,1),(14,17,1),(15,2,1),(6,5,1),(8,3,1),(3,8,7))){
        t.fixture(image.updated[BigInt](word,value));t.start();t.finish(code)
      }
      for((vertices,topology)<-Seq((1,0),(2,1),(2,2),(2,3),(3,4),(3,5),(3,6),(4,10),(4,11),(6,12),(6,13))){
        t.fixture(image.updated[BigInt](6,vertices));t.start(topology=topology);t.finish(0)
      }
      t.fixture();t.start(topology=4);t.finish(1)
      // 256*4=1024 remains legal; builtin outputs count independently.
      var small=image.updated[BigInt](9,256).updated[BigInt](11,4)
      for(i<-0 until 68)small=small.updated[BigInt](97+i*2,i%4)
      t.fixture(small);t.start();t.finish(0)
      t.fixture(small.updated[BigInt](12,3));t.start();t.finish(1)
      t.fixture(small.updated[BigInt](9,170).updated[BigInt](12,3));t.start();t.finish(0)
      t.fixture(small.updated[BigInt](9,171).updated[BigInt](12,3));t.start();t.finish(1)
      for((word,value)<-Seq((17,0x8001L),(18,0x8804L),(19,2L),(20,0x8004L),(22,2L),(16,0x8800L),
        (23,1L),(24,0x9004L),(32,68L),(97,64L),(98,0L))){
        t.fixture(image.updated[BigInt](word,value));t.start();t.finish(1)
      }
      t.healthy()
    }
  }
  it should "reject every reserved word and narrow padding bit before publishing metadata" in {
    simulate(new NativeGeometryProgramReader(GpuConfig(lanes=4,warps=2),geometryAvailable=true)){d=>
      val t=new Driver(d);val fields=NativeGeometryProgramLayout.fields(new NativeGeometryProgram)
      val used=fields.flatMap(f=>f.word until f.word+f.words).toSet++Set(0,1,2)
      for(word<-0 until 256 if !used(word)){
        t.fixture(image.updated[BigInt](word,1));t.start();t.finish(1)
      }
      for(f<-fields if f.width%32!=0){
        val word=f.word+f.words-1;val bit=f.width%32
        t.fixture(image.updated[BigInt](word,image(word)|(BigInt(1)<<bit)));t.start();t.finish(1)
      }
      for((word,value)<-Seq((0,BigInt("474e4702",16)),(1,BigInt(512)),(2,BigInt(2)))){
        t.fixture(image.updated[BigInt](word,value));t.start();t.finish(1)
      }
      t.healthy()
    }
  }
  it should "drain offered reads, held replies and first faults through cancellation before healthy reuse" in {
    simulate(new NativeGeometryProgramReader(GpuConfig(lanes=4,warps=2),geometryAvailable=true)){d=>
      val t=new Driver(d)
      t.fixture();t.memory.holdRequests=true;t.start();t.until(d.io.memoryRequest.valid.peek().litToBoolean)
      val held=d.io.memoryRequest.bits.peek().litValue;d.io.cancel.poke(true.B);t.tick();d.io.cancel.poke(false.B)
      for(_<-0 until 9){t.tick();d.io.memoryRequest.valid.expect(true.B);assert(d.io.memoryRequest.bits.peek().litValue==held);d.io.out.valid.expect(false.B)}
      t.memory.holdRequests=false;t.finish(4);t.healthy()
      t.fixture();t.memory.holdResponses=true;t.start();t.until(t.memory.pending.size==4)
      d.io.cancel.poke(true.B);t.tick();d.io.cancel.poke(false.B)
      for(_<-0 until 9){t.tick();d.io.busy.expect(true.B);d.io.out.valid.expect(false.B)}
      t.memory.holdResponses=false;t.finish(4);t.healthy()
      for(line<-0 until 16){
        t.fixture();t.memory.faultAt=Set(base+line*64);t.start();t.until(d.io.out.valid.peek().litToBoolean)
        d.io.cancel.poke(true.B);t.finish(3,fault=Some(base+line*64));t.memory.faultAt=Set.empty;t.healthy()
      }
    }
  }
}
