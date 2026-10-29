package opengpu.graphics

import chisel3._
import opengpu.config.{CachePolicy,GpuConfig}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class ShaderUniformExecuteUnitSpec extends AnyFlatSpec {
  import WindowRecordTest._
  private val root=BigInt(0x1000);private val table=BigInt(0x2000);private val defaultView=BigInt(0x2800)
  private val defaultBase=BigInt(0x8260)
  private def base(index: Int): BigInt=BigInt(0x10000)+index*16384
  private def value(index: Int,offset: BigInt): BigInt=(BigInt(0xa9000000L)^(BigInt(index)<<16)^offset)&0xffffffffL
  private def instruction(vector: Boolean): BigInt=(BigInt(7)<<(if(vector)26 else 25)) |
    (if(vector)BigInt(1)<<25 else BigInt(0)) | (BigInt(2)<<20) | (BigInt(1)<<15) | (BigInt(7)<<7) | (if(vector)0x2b else 0x0b)
  private def header(magic: BigInt): Vector[BigInt]=Vector[BigInt](magic,64,1,7,11,13)++Vector.fill(10)(BigInt(0))
  private def put33(v: Vector[BigInt],word: Int,n: BigInt): Vector[BigInt]=v.updated[BigInt](word,n&0xffffffffL).updated[BigInt](word+1,n>>32)
  private class Driver(val d: ShaderUniformExecuteUnit,val stage: Int,val lanes: Int){
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    val memory=new Memory(d.io.memoryRequest.bits,d.io.memoryResponse.bits,d.io.memoryRequest.valid,d.io.memoryRequest.ready,
      d.io.memoryResponse.valid,d.io.memoryResponse.ready)
    memory.beforeRequest=r=>{
      assert(!r.write && r.tag==0 && r.mask==(BigInt(1)<<64)-1 && r.address%64==0)
      d.io.memoryRequest.bits.sizeLog2.expect(6.U);d.io.memoryRequest.bits.cachePolicy.expect(CachePolicy.uncached)
    }
    val all=(BigInt(1)<<lanes)-1
    val old=(0 until lanes).map(i=>BigInt(0x51000000L)+i)
    var vector=false
    def tick(): Unit={memory.drive();memory.capture();d.clock.step();assert(memory.pending.size+memory.reply.size<=1)}
    def until(p: =>Boolean): Unit={var n=0;while(!p && n<4000){tick();n+=1};assert(p,"uniform execution stalled")}
    def record(address: BigInt,words: Seq[BigInt]): Unit=words.zipWithIndex.foreach{case(v,i)=>memory.putWord(address+4*i,v)}
    def view(index: Int,data: BigInt,bytes: Int): Vector[BigInt]=
      put33(put33(header(BigInt("47554201",16)).updated[BigInt](6,index).updated[BigInt](7,3).updated[BigInt](8,data)
        .updated[BigInt](11,data& ~BigInt(63)).updated[BigInt](14,1),9,data+bytes),12,(data+bytes+63)& ~BigInt(63))
    def fixture(): Unit={
      val mapping=(0 until 12).map(i=>BigInt(stage*12+i))
      val low=(0 until 6).map(i=>mapping(i)<<(5*i)).sum;val high=(0 until 6).map(i=>mapping(i+6)<<(5*i)).sum
      record(root,put33(header(BigInt("47555201",16)).updated[BigInt](6,stage).updated[BigInt](7,0xfff)
        .updated[BigInt](8,low).updated[BigInt](9,high).updated[BigInt](10,table)
        .updated[BigInt](13,defaultView).updated[BigInt](14,1),11,table+1536))
      for(index<-0 until 24){record(table+index*64,view(index,base(index),16384))
        for(offset<-Seq(0,4,8,12,16,20,16380))memory.putWord(base(index)+offset,value(index,offset))}
      val bytes=4096;record(defaultView,view(31,defaultBase,bytes))
      for(offset<-Seq(0,4,bytes-4))memory.putWord(defaultBase+offset,value(31,offset))
    }
    def arm(isVector: Boolean,blocks: Seq[BigInt],offsets: Seq[BigInt],mask: BigInt): Unit={
      vector=isVector;d.io.resources.version.poke(1.U);d.io.resources.address.poke(root.U);identity(d.io.identity)
      d.io.privateBase.poke(0x8000.U);d.io.privateLimit.poke(defaultBase.U)
      if(isVector){val q=d.io.vectorIn.bits;zero(q);val dec=q.issued.decode
        dec.instruction.poke(instruction(true).U);dec.warpId.poke(1.U);dec.pc.poke(0x2000.U);dec.activeMask.poke(all.U)
        q.executionMask.poke(mask.U)
        for(i<-0 until lanes){q.issued.vs1Data(i).poke(blocks(i).U);q.issued.vs2Data(i).poke(offsets(i).U);q.issued.oldVdData(i).poke(old(i).U)}
        d.io.vectorIn.valid.poke(true.B)
      }else{val q=d.io.in.bits;zero(q);q.decode.instruction.poke(instruction(false).U)
        q.decode.warpId.poke(1.U);q.decode.pc.poke(0x2000.U);q.decode.activeMask.poke(all.U)
        q.rs1Data.poke(blocks.head.U);q.rs2Data.poke(offsets.head.U);d.io.in.valid.poke(true.B)
      }
    }
    def start(isVector: Boolean=false,blocks: Seq[BigInt]=Seq.fill(lanes)(BigInt(0)),
        offsets: Seq[BigInt]=Seq.fill(lanes)(BigInt(0)),mask: BigInt=all): Unit={
      arm(isVector,blocks,offsets,mask)
      until(if(isVector)d.io.vectorIn.ready.peek().litToBoolean else d.io.in.ready.peek().litToBoolean);tick()
      if(isVector){d.io.vectorIn.valid.poke(false.B);zero(d.io.vectorIn.bits)}else{d.io.in.valid.poke(false.B);zero(d.io.in.bits)}
      // Accepted operands, binding, identity and private interval must be snapshots.
      d.io.resources.version.poke(0.U);d.io.resources.address.poke(0.U);d.io.identity.context.poke(99.U)
      d.io.privateBase.poke(root.U);d.io.privateLimit.poke((root+64).U)
    }
    def ready: Boolean=if(vector)d.io.vectorCommit.valid.peek().litToBoolean else d.io.commit.valid.peek().litToBoolean
    def finish(code: Int,expected: Seq[BigInt]=Seq.empty,source: Int=0,fault: Option[BigInt]=None): Unit={
      until(ready);d.io.status.expect(code.U);d.io.faultSource.expect(source.U)
      d.io.faultAddressValid.expect(fault.nonEmpty.B);d.io.faultAddress.expect(fault.getOrElse(BigInt(0)).U)
      assert(memory.pending.isEmpty && memory.reply.isEmpty);d.io.memoryRequest.valid.expect(false.B)
      if(vector){val q=d.io.vectorCommit.bits;q.writesVd.expect((code==0).B);q.writeback.warpId.expect(1.U)
        q.writeback.vd.expect(7.U);q.pc.expect(0x2000.U);q.warpActiveMask.expect(all.U)
        for(i<-0 until lanes)q.writeback.data(i).expect((if(code==0)expected(i)else BigInt(0)).U)
      }else{val q=d.io.commit.bits;q.writeRd.expect((code==0).B);q.warpId.expect(1.U);q.rd.expect(7.U)
        q.nextPc.expect(0x2004.U);q.activeMask.expect(all.U);q.data.expect((if(code==0)expected.head else BigInt(0)).U)}
      val held=if(vector)d.io.vectorCommit.bits.peek().litValue else d.io.commit.bits.peek().litValue
      d.io.cancel.poke(true.B)
      for(_<-0 until 4){d.io.in.ready.expect(false.B);d.io.vectorIn.ready.expect(false.B);tick()
        assert((if(vector)d.io.vectorCommit.bits.peek().litValue else d.io.commit.bits.peek().litValue)==held)
        d.io.status.expect(code.U)}
      if(vector)d.io.vectorCommit.ready.poke(true.B)else d.io.commit.ready.poke(true.B)
      tick();d.io.vectorCommit.ready.poke(false.B);d.io.commit.ready.poke(false.B);d.io.cancel.poke(false.B);d.io.busy.expect(false.B)
    }
    def healthy(): Unit={fixture();start();finish(0,Seq(value(stage*12,0)))}
  }

  for(stage<-0 to 1;lanes<-Seq(4,16))it should s"retire exact words and coalesce identical active operands stage=$stage lanes=$lanes" in {
    simulate(new ShaderUniformExecuteUnit(GpuConfig(lanes=lanes,warps=if(lanes==16)8 else 2),stage)){d=>val t=new Driver(d,stage,lanes);t.fixture()
      for(block<-0 until 12;offset<-Seq(0,16380)){
        t.start(blocks=Seq.fill(lanes)(BigInt(block)),offsets=Seq.fill(lanes)(BigInt(offset)));t.finish(0,Seq(value(stage*12+block,offset)))
      }
      val last=4092;t.start(blocks=Seq.fill(lanes)(BigInt(31)),offsets=Seq.fill(lanes)(BigInt(last)));t.finish(0,Seq(value(31,last)))
      t.start(true,Seq.fill(lanes)(BigInt(31)),Seq.fill(lanes)(BigInt(last)))
      t.finish(0,Seq.fill(lanes)(value(31,last)))
      t.start(true,Seq.fill(lanes)(BigInt(31)),Seq.fill(lanes-1)(BigInt(last)):+BigInt(4096))
      t.finish(2,source=3)
      val before=t.memory.seen.size;t.start(true);t.finish(0,Seq.fill(lanes)(value(stage*12,0)))
      assert(t.memory.seen.size-before==3,"broadcast repeated descriptor/data reads per lane")
      val mask=(0 until lanes).filter(_%3!=1).foldLeft(BigInt(0))((a,i)=>a.setBit(i))
      val blocks=(0 until lanes).map(i=>if(mask.testBit(i))BigInt(i%3)else BigInt(0xffffffffL))
      val offsets=(0 until lanes).map(i=>if(mask.testBit(i))BigInt((i%2)*4)else BigInt(0xfffffffeL))
      val expected=(0 until lanes).map(i=>if(mask.testBit(i))value(stage*12+blocks(i).toInt,offsets(i))else t.old(i))
      val groups=(0 until lanes).filter(mask.testBit).map(i=>(blocks(i),offsets(i))).distinct.size
      val divergent=t.memory.seen.size;t.start(true,blocks,offsets,mask);t.finish(0,expected)
      assert(t.memory.seen.size-divergent==3*groups)
      val empty=t.memory.seen.size;t.start(true,blocks,offsets,0);t.finish(0,t.old);assert(t.memory.seen.size==empty)
      t.fixture();t.memory.putWord(base(stage*12),0x7fc12345);t.start(true);t.finish(0,Seq.fill(lanes)(BigInt(0x7fc12345)))
      info(s"UNIFORM_EXECUTION stage=$stage lanes=$lanes rawBits=true mappings12=true fullBlockEnd=true defaultEnd=true inactivePreserved=true broadcastReads3=true divergentGroups=$groups")
    }
  }

  for(stage<-0 to 1)it should s"suppress partial vector writes and drain faults or cancellation before reuse stage=$stage" in {
    simulate(new ShaderUniformExecuteUnit(GpuConfig(lanes=4,warps=2),stage)){d=>val t=new Driver(d,stage,4);t.fixture()
      val blocks=Seq[BigInt](0,65536,0xffffffffL,1);val offsets=Seq.fill(4)(BigInt(0))
      val before=t.memory.seen.size;t.start(true,blocks,offsets,3);t.finish(1,source=1)
      assert(t.memory.seen.size-before==3,"invalid second lane aliased a valid block");t.healthy()
      t.start(offsets=Seq.fill(4)(BigInt(2)));t.finish(2,source=3);t.healthy()
      for((address,source)<-Seq((root,1),(table+stage*12*64,2),(base(stage*12),3))){
        t.memory.faultAt=Set(address);t.start(true)
        t.until(t.ready);d.io.cancel.poke(true.B);t.finish(3,source=source,fault=Some(address));t.memory.faultAt=Set.empty;t.healthy()
      }
      // Hold each physical phase's request, then cancel. The offered request
      // stays stable and its eventual reply must drain before zero writeback.
      for(read<-0 until 3){
        val seen=t.memory.seen.size;t.start(true)
        t.until(t.memory.seen.size==seen+read)
        t.memory.holdRequests=true;t.until(d.io.memoryRequest.valid.peek().litToBoolean)
        val held=d.io.memoryRequest.bits.peek().litValue;d.io.cancel.poke(true.B)
        for(_<-0 until 5){t.tick();d.io.memoryRequest.valid.expect(true.B);assert(d.io.memoryRequest.bits.peek().litValue==held);assert(!t.ready)}
        t.memory.holdRequests=false;t.memory.holdResponses=true;t.until(t.memory.seen.size==seen+read+1)
        for(_<-0 until 5){t.tick();assert(!t.ready)}
        t.memory.holdResponses=false;t.finish(4);assert(t.memory.seen.size==seen+read+1);t.healthy()
      }
      t.start(true);d.io.cancel.poke(true.B);t.finish(4);t.healthy()
    }
  }

  it should "arbitrate simultaneous scalar and vector offers without abandoning a held instruction" in {
    simulate(new ShaderUniformExecuteUnit(GpuConfig(lanes=4,warps=2))){d=>val t=new Driver(d,0,4);t.fixture()
      t.arm(false,Seq.fill(4)(BigInt(0)),Seq.fill(4)(BigInt(4)),t.all)
      t.arm(true,Seq.fill(4)(BigInt(1)),Seq.fill(4)(BigInt(8)),t.all)
      d.io.in.ready.expect(false.B);d.io.vectorIn.ready.expect(true.B);t.tick();d.io.vectorIn.valid.poke(false.B)
      t.finish(0,Seq.fill(4)(value(1,8)))
      // The held scalar now wins over a newly offered vector instruction.
      t.arm(true,Seq.fill(4)(BigInt(2)),Seq.fill(4)(BigInt(12)),t.all)
      d.io.in.ready.expect(true.B);d.io.vectorIn.ready.expect(false.B);t.tick();d.io.in.valid.poke(false.B)
      t.vector=false;t.finish(0,Seq(value(0,4)))
      d.io.vectorIn.ready.expect(true.B);t.tick();d.io.vectorIn.valid.poke(false.B)
      t.vector=true;t.finish(0,Seq.fill(4)(value(2,12)))
      assert(t.memory.seen.size==9)
    }
  }
}
