package opengpu.graphics

import chisel3._
import opengpu.config.{CachePolicy,GpuConfig}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class ShaderUniformLoadUnitSpec extends AnyFlatSpec {
  import WindowRecordTest._
  private val addressSpace=BigInt(1)<<32
  private val rootAddress=BigInt(0x1000);private val tableAddress=BigInt(0x2000)
  private val defaultRecord=BigInt(0x2800);private val defaultBase=BigInt(0x8260)
  private def bufferBase(index: Int): BigInt=BigInt(0x10000)+index*16384
  private def pattern(index: Int,offset: Int): BigInt=(BigInt(0xa5000000L)^(BigInt(index)<<16)^offset)&0xffffffffL
  private def header(magic: BigInt): Vector[BigInt]=Vector[BigInt](magic,64,1,7,11,13)++Vector.fill(10)(BigInt(0))
  private def put33(v: Vector[BigInt],word: Int,n: BigInt): Vector[BigInt]=v.updated[BigInt](word,n&0xffffffffL).updated[BigInt](word+1,n>>32)
  private def rootWords(stage: Int,table: BigInt=tableAddress): Vector[BigInt]={
    val mappings=(0 until 12).map(i=>BigInt(stage*12+i))
    val low=(0 until 6).map(i=>mappings(i)<<(5*i)).sum
    val high=(0 until 6).map(i=>mappings(i+6)<<(5*i)).sum
    put33(header(BigInt("47555201",16)).updated[BigInt](6,stage).updated[BigInt](7,0xfff).updated[BigInt](8,low).updated[BigInt](9,high)
      .updated[BigInt](10,table).updated[BigInt](13,defaultRecord).updated[BigInt](14,1),11,table+1536)
  }
  private def viewWords(index: Int,base: BigInt,bytes: Int): Vector[BigInt]={
    val limit=base+bytes
    put33(put33(header(BigInt("47554201",16)).updated[BigInt](6,index).updated[BigInt](7,3).updated[BigInt](8,base)
      .updated[BigInt](11,base& ~BigInt(63)).updated[BigInt](14,1),9,limit),12,(limit+63)& ~BigInt(63))
  }
  private class Driver(val d: ShaderUniformLoadUnit,val stage: Int){
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    val memory=new Memory(d.io.memoryRequest.bits,d.io.memoryResponse.bits,d.io.memoryRequest.valid,d.io.memoryRequest.ready,
      d.io.memoryResponse.valid,d.io.memoryResponse.ready)
    memory.beforeRequest=r=>{
      assert(!r.write && r.tag==0 && r.mask==(BigInt(1)<<64)-1 && r.address%64==0 && r.address+64<=addressSpace)
      d.io.memoryRequest.bits.cachePolicy.expect(CachePolicy.uncached)
      d.io.memoryRequest.bits.sizeLog2.expect(6.U)
    }
    var afterReply: BigInt=>Unit=_=>()
    var sequence=0;var currentBlock=BigInt(0);var currentTag=0
    def tick(): Unit={
      memory.drive()
      val reply=if(d.io.memoryResponse.valid.peek().litToBoolean && d.io.memoryResponse.ready.peek().litToBoolean)memory.reply.map(_.address)else None
      memory.capture();d.clock.step();reply.foreach(afterReply)
      assert(memory.pending.size+memory.reply.size<=1,"uniform owner exceeded its single physical credit")
    }
    def until(p: =>Boolean): Unit={var n=0;while(!p && n<400){tick();n+=1};assert(p,s"uniform load stalled stage=$stage reads=${memory.seen.takeRight(5)}")}
    def record(address: BigInt,words: Seq[BigInt]): Unit=words.zipWithIndex.foreach{case(v,i)=>memory.putWord(address+4*i,v)}
    def fixture(root: BigInt=rootAddress,table: BigInt=tableAddress,defaultBytes: Int=0): Unit={
      record(root,rootWords(stage,table))
      for(index<-0 until 24){record(table+index*64,viewWords(index,bufferBase(index),16384))
        for(offset<-Seq(0,4,8192,16380))memory.putWord(bufferBase(index)+offset,pattern(index,offset))}
      val bytes=if(defaultBytes==0)Seq(4096,3584)(stage)else defaultBytes
      record(defaultRecord,viewWords(31,defaultBase,bytes))
      for(component<-0 until bytes/4)memory.putWord(defaultBase+4*component,pattern(31,4*component))
    }
    def start(block: BigInt=0,offset: BigInt=0,root: BigInt=rootAddress,
        privateBase: BigInt=0x8000,privateLimit: BigInt=defaultBase): Unit={
      val q=d.io.in.bits;zero(q);q.resources.version.poke(1.U);q.resources.address.poke(root.U);identity(q.identity)
      q.block.poke(block.U);q.offset.poke(offset.U);q.tag.poke(sequence.U)
      q.privateBase.poke(privateBase.U);q.privateLimit.poke(privateLimit.U)
      currentBlock=block;currentTag=sequence;sequence+=1;d.io.in.valid.poke(true.B)
      until(d.io.in.ready.peek().litToBoolean);tick();d.io.in.valid.poke(false.B);zero(q)
    }
    def finish(status: Int,expected: BigInt=0,source: Int=0,fault: Option[BigInt]=None): Unit={
      until(d.io.out.valid.peek().litToBoolean);val q=d.io.out.bits
      q.status.expect(status.U);q.value.expect(expected.U);q.block.expect(currentBlock.U);q.tag.expect(currentTag.U)
      q.identity.context.expect(7.U);q.identity.pass.expect(11.U);q.identity.generation.expect(13.U)
      if(status!=0)q.faultSource.expect(source.U)
      q.faultAddressValid.expect(fault.nonEmpty.B);q.faultAddress.expect(fault.getOrElse(BigInt(0)).U)
      assert(memory.pending.isEmpty && memory.reply.isEmpty);d.io.memoryRequest.valid.expect(false.B)
      val held=q.peek().litValue;d.io.cancel.poke(true.B)
      for(_<-0 until 3){d.io.in.ready.expect(false.B);tick();assert(q.peek().litValue==held)}
      d.io.out.ready.poke(true.B);tick();d.io.out.ready.poke(false.B);d.io.cancel.poke(false.B);d.io.busy.expect(false.B)
    }
    def healthy(): Unit={start();finish(0,pattern(stage*12,0))}
  }

  for(stage<-0 to 1)it should s"load twelve full-size block mappings and every default component without touching private prefix stage=$stage" in {
    simulate(new ShaderUniformLoadUnit(GpuConfig(lanes=4,warps=2),stage)){d=>val t=new Driver(d,stage);t.fixture(defaultBytes=4096)
      for(block<-0 until 12;offset<-Seq(0,4,8192,16380)){t.start(block,offset);t.finish(0,pattern(stage*12+block,offset))}
      for(component<-0 until 1024){t.start(31,component*4);t.finish(0,pattern(31,component*4))}
      val index=stage*12+11
      for(offset<-0 until 16384 by 4)t.memory.putWord(bufferBase(index)+offset,pattern(index,offset))
      for(offset<-0 until 16384 by 4){t.start(11,offset);t.finish(0,pattern(index,offset))}
      assert((0 until 12).forall(b=>t.memory.seen.exists(_.address==tableAddress+(stage*12+b)*64)))
      assert(t.memory.seen.exists(_.address==(defaultBase& ~BigInt(63))),"unaligned default tail was not exercised")
      info(s"UNIFORM_CAPACITY stage=$stage blocks12=true combinedBindingMap24=true blockBytes16384=true everyBlockWord4096=true defaultComponents=1024 defaultAfterPrivatePrefix=true rawValues=true onePhysicalCredit=true")
    }
  }

  it should "bound the expanded fragment default view and retain smaller legacy views" in {
    simulate(new ShaderUniformLoadUnit(GpuConfig(lanes=4,warps=2),1)){d=>val t=new Driver(d,1)
      t.fixture(defaultBytes=4096)
      for(offset<-Seq(3584,4092)){t.start(31,offset);t.finish(0,pattern(31,offset))}
      var before=t.memory.seen.size;t.start(31,4096);t.finish(2,source=3)
      assert(t.memory.seen.size-before==2,"out-of-view access reached data memory")
      t.record(defaultRecord,viewWords(31,defaultBase,4100));before=t.memory.seen.size
      t.start(31);t.finish(1,source=2);assert(t.memory.seen.size-before==2,"oversized view reached data memory")
      t.fixture();t.start(31,3580);t.finish(0,pattern(31,3580))
      before=t.memory.seen.size;t.start(31,3584);t.finish(2,source=3);assert(t.memory.seen.size-before==2)
      t.fixture(defaultBytes=4096);t.start(31,4092);t.finish(0,pattern(31,4092))
    }
  }

  for(stage<-0 to 1)it should s"reject full-width selection and offset aliases before unsafe reads stage=$stage" in {
    simulate(new ShaderUniformLoadUnit(GpuConfig(lanes=4,warps=2),stage)){d=>val t=new Driver(d,stage);t.fixture()
      for(block<-Seq[BigInt](12,24,32,65536,0xffffffffL)){
        val before=t.memory.seen.size;t.start(block);t.finish(1,source=1);assert(t.memory.seen.size==before);t.healthy()
      }
      for(offset<-Seq[BigInt](2,16384,65536,0xfffffffcL)){
        val before=t.memory.seen.size;t.start(offset=offset);t.finish(2,source=3)
        assert(t.memory.seen.size-before==(if(offset==2)0 else 2));t.healthy()
      }
      t.start(31,Seq(4096,3584)(stage));t.finish(2,source=3);t.healthy()
      for((base,limit,reads)<-Seq((rootAddress,rootAddress+64,0),(tableAddress,tableAddress+1,1),
        (defaultRecord,defaultRecord+64,1),(BigInt(1),BigInt(0),0),(BigInt(0),addressSpace+1,0))){
        val before=t.memory.seen.size;t.start(privateBase=base,privateLimit=limit);t.finish(1,source=1)
        assert(t.memory.seen.size-before==reads);t.healthy()
      }
    }
  }

  for(stage<-0 to 1)it should s"reject malformed roots and read-only views before data reads and recover stage=$stage" in {
    simulate(new ShaderUniformLoadUnit(GpuConfig(lanes=4,warps=2),stage)){d=>val t=new Driver(d,stage)
      val root=rootWords(stage);val index=stage*12;val record=tableAddress+index*64;val view=viewWords(index,bufferBase(index),16384)
      val badRoots=Seq(root.updated[BigInt](0,0),root.updated[BigInt](15,1),root.updated[BigInt](3,99),root.updated[BigInt](6,1-stage),root.updated[BigInt](7,0x1fff),
        root.updated[BigInt](8,root(8)|(BigInt(1)<<31)),root.updated[BigInt](8,(root(8)& ~BigInt(31))|24),root.updated[BigInt](10,tableAddress+4),
        put33(root,11,tableAddress+1535),put33(root,11,addressSpace+64),root.updated[BigInt](14,0),root.updated[BigInt](13,0x8000))
      for((words,caseIndex)<-badRoots.zipWithIndex){t.fixture();t.record(rootAddress,words);val before=t.memory.seen.size
        t.start();t.until(d.io.out.valid.peek().litToBoolean);assert(t.memory.seen.size-before==1)
        t.finish(if(caseIndex<6)7 else 1,source=1);t.fixture();t.healthy()}
      val badViews=Seq(view.updated[BigInt](0,0),view.updated[BigInt](15,1),view.updated[BigInt](3,99),view.updated[BigInt](6,(index+1)%24),
        view.updated[BigInt](7,1<<(1-stage)),view.updated[BigInt](14,0),view.updated[BigInt](8,bufferBase(index)+4),
        put33(view,9,bufferBase(index)+16385),view.updated[BigInt](11,bufferBase(index)+1),put33(view,12,addressSpace+64),
        viewWords(index,0x8000,608))
      for((words,caseIndex)<-badViews.zipWithIndex){t.fixture();t.record(record,words);val before=t.memory.seen.size
        t.start();t.until(d.io.out.valid.peek().litToBoolean);assert(t.memory.seen.size-before==2)
        t.finish(if(caseIndex<5)7 else 1,source=2);t.fixture();t.healthy()}
      t.fixture();t.record(defaultRecord,viewWords(31,0x8004,64));t.start(31);t.finish(1,source=2);t.fixture();t.healthy()
    }
  }

  for(stage<-0 to 1)it should s"retain root table and final data words at the exclusive 4 GiB boundary stage=$stage" in {
    simulate(new ShaderUniformLoadUnit(GpuConfig(lanes=4,warps=2),stage)){d=>val t=new Driver(d,stage)
      t.fixture(root=0);t.start(root=0);t.finish(0,pattern(stage*12,0))
      t.fixture(table=0);t.start();t.finish(0,pattern(stage*12,0))
      t.fixture();t.record(rootAddress,rootWords(stage).updated[BigInt](13,0))
      t.record(0,viewWords(31,defaultBase,Seq(4096,3584)(stage)));t.start(31);t.finish(0,pattern(31,0))
      t.fixture(root=addressSpace-64);t.start(root=addressSpace-64);t.finish(0,pattern(stage*12,0))
      t.fixture(table=addressSpace-1536);t.start(11);t.finish(0,pattern(stage*12+11,0))
      t.fixture();val index=stage*12
      t.record(tableAddress+index*64,viewWords(index,addressSpace-256,256));t.memory.putWord(addressSpace-4,0x12345678)
      t.start(offset=252);t.finish(0,0x12345678);t.start(offset=256);t.finish(2,source=3)
      t.record(defaultRecord,viewWords(31,addressSpace-4,4));t.start(31);t.finish(0,0x12345678)
      t.fixture();t.healthy()
    }
  }

  it should "snapshot descriptors and operands while observing new publication on the next instruction" in {
    simulate(new ShaderUniformLoadUnit(GpuConfig(lanes=4,warps=2))){d=>val t=new Driver(d,0);t.fixture()
      t.afterReply=a=>{
        if(a==rootAddress)t.record(rootAddress,rootWords(0).updated[BigInt](8,(rootWords(0)(8)& ~BigInt(31))|23))
        if(a==tableAddress)t.record(tableAddress,viewWords(0,bufferBase(22),16384))
      }
      t.start();t.finish(0,pattern(0,0));assert(t.memory.seen.map(_.address).toVector==Vector(rootAddress,tableAddress,bufferBase(0)))
      t.afterReply=_=>();t.start();t.finish(0,pattern(23,0));t.fixture();t.healthy()
    }
  }

  for(stage<-0 to 1)it should s"retain first memory faults through cancellation and drain every held read before reuse stage=$stage" in {
    simulate(new ShaderUniformLoadUnit(GpuConfig(lanes=4,warps=2),stage)){d=>val t=new Driver(d,stage);t.fixture()
      val locations=Seq(rootAddress,tableAddress+stage*12*64,bufferBase(stage*12))
      for((address,source)<-locations.zipWithIndex){
        t.memory.faultAt=Set(address);t.afterReply=a=>if(a==address)d.io.cancel.poke(true.B)
        val offset=if(source==2)4 else 0
        t.start(offset=offset);t.finish(3,source=source+1,fault=Some(address+offset));t.memory.faultAt=Set.empty;t.afterReply=_=>();t.healthy()
      }
      for(cut<-0 until 36){
        t.start();for(_<-0 until cut if !d.io.out.valid.peek().litToBoolean)t.tick()
        val completed=d.io.out.valid.peek().litToBoolean
        d.io.cancel.poke(true.B);t.finish(if(completed)0 else 4,if(completed)pattern(stage*12,0)else BigInt(0));t.healthy()
      }
      for(address<-locations){
        t.memory.holdRequests=true;t.start()
        // Allow older reads, then retain exactly the selected offered request.
        t.until(d.io.memoryRequest.valid.peek().litToBoolean)
        while(d.io.memoryRequest.bits.address.peek().litValue!=address){t.memory.holdRequests=false;t.tick();t.memory.holdRequests=true;t.until(d.io.memoryRequest.valid.peek().litToBoolean)}
        d.io.cancel.poke(true.B);val held=d.io.memoryRequest.bits.peek().litValue
        for(_<-0 until 9){t.tick();d.io.memoryRequest.valid.expect(true.B);assert(d.io.memoryRequest.bits.peek().litValue==held);d.io.out.valid.expect(false.B)}
        t.memory.holdRequests=false;t.finish(4);t.healthy()
      }
      for(address<-locations){
        val check=t.memory.beforeRequest
        t.memory.beforeRequest=r=>{check(r);if(r.address==address)t.memory.holdResponses=true}
        t.start();t.until(t.memory.pending.exists(_.address==address));d.io.cancel.poke(true.B)
        for(_<-0 until 9){t.tick();d.io.busy.expect(true.B);d.io.out.valid.expect(false.B)}
        t.memory.holdResponses=false;t.finish(4);t.memory.beforeRequest=check;t.healthy()
      }
    }
  }
}
