package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class NativeShaderTextureUnitSpec extends AnyFlatSpec {
  import WindowRecordTest._
  import NativeTextureFetchTestData._
  private class Driver(val d: NativeShaderTextureUnit) {
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    val memory=new Memory(d.io.memoryRequest.bits,d.io.memoryResponse.bits,d.io.memoryRequest.valid,d.io.memoryRequest.ready,
      d.io.memoryResponse.valid,d.io.memoryResponse.ready)
    memory.beforeRequest=r=>assert(!r.write && r.tag<2 && r.address%64==0 && r.mask==(BigInt(1)<<64)-1)
    def tick(): Unit={memory.drive();memory.capture();d.clock.step()}
    def until(p: =>Boolean,limit: Int=15000): Unit={var n=0;while(!p && n<limit){tick();n+=1};assert(p,s"native texture timeout reads=${memory.seen.takeRight(8)}")}
    def context(): Unit={d.io.resources.version.poke(1.U);d.io.resources.address.poke(root.U);identity(d.io.identity)}
    def put(a: BigInt,v: BigInt): Unit=memory.putWord(a,v)
    def byte(a: BigInt,v: Int): Unit={memory.bytes(a)=v}
    def scalarRequest(c: Int,unit: BigInt=0,pointer: BigInt=packet): Unit={
      context();zero(d.io.in.bits);val q=d.io.in.bits;q.decode.instruction.poke(scalar(c).U)
      q.decode.warpId.poke(1.U);q.decode.pc.poke(0x200.U);q.decode.activeMask.poke(15.U)
      q.rs1Data.poke(pointer.U);q.rs2Data.poke(unit.U);d.io.in.valid.poke(true.B)
      until(d.io.in.ready.peek().litToBoolean);tick();d.io.in.valid.poke(false.B);zero(q);zero(d.io.resources);zero(d.io.identity)
    }
    def vectorRequest(c: Int,mask: Int=5,units: Seq[Int]=Seq(0,1,2,3),pointers: Seq[BigInt]=(0 until 4).map(i=>packet+i*64)): Unit={
      context();zero(d.io.vectorIn.bits);val q=d.io.vectorIn.bits;q.issued.decode.instruction.poke(vector(c,masked=true).U)
      q.issued.decode.warpId.poke(1.U);q.issued.decode.pc.poke(0x204.U);q.issued.decode.activeMask.poke(15.U);q.executionMask.poke(mask.U)
      for(i<-0 until 4){q.issued.vs1Data(i).poke(pointers(i).U);q.issued.vs2Data(i).poke(units(i).U);q.issued.oldVdData(i).poke((0xabc00000L+i).U)}
      d.io.vectorIn.valid.poke(true.B);until(d.io.vectorIn.ready.peek().litToBoolean);tick();d.io.vectorIn.valid.poke(false.B)
      zero(q);zero(d.io.resources);zero(d.io.identity)
    }
    def scalarResult(value: BigInt=0,fault: Boolean=false,address: BigInt=0): Unit={
      until(d.io.commit.valid.peek().litToBoolean);d.io.commit.bits.rd.expect(10.U);d.io.commit.bits.warpId.expect(1.U)
      d.io.commit.bits.nextPc.expect(0x204.U);d.io.commit.bits.activeMask.expect(15.U);d.io.commit.bits.writeRd.expect((!fault).B)
      d.io.commit.bits.data.expect(value.U);d.io.responseFault.expect(fault.B)
      if(fault)d.io.faultAddress.expect(address.U)
      assert(memory.pending.isEmpty && memory.reply.isEmpty);val held=d.io.commit.bits.peek().litValue
      d.io.cancel.poke(true.B);for(_<-0 until 3){tick();assert(d.io.commit.bits.peek().litValue==held)}
      d.io.commit.ready.poke(true.B);tick();d.io.commit.ready.poke(false.B);d.io.cancel.poke(false.B);d.io.busy.expect(false.B)
    }
    def vectorResult(values: Seq[BigInt],fault: Boolean=false): Unit={
      until(d.io.vectorCommit.valid.peek().litToBoolean);val q=d.io.vectorCommit.bits
      q.writeback.warpId.expect(1.U);q.writeback.vd.expect(10.U);q.pc.expect(0x204.U);q.warpActiveMask.expect(15.U);q.writesVd.expect((!fault).B)
      q.writeback.data.zip(values).foreach{case(a,b)=>a.expect(b.U)};d.io.responseFault.expect(fault.B)
      assert(memory.pending.isEmpty && memory.reply.isEmpty);val held=q.peek().litValue
      for(_<-0 until 3){tick();assert(q.peek().litValue==held)}
      d.io.vectorCommit.ready.poke(true.B);tick();d.io.vectorCommit.ready.poke(false.B);d.io.busy.expect(false.B)
    }
  }
  for(stage<-0 to 1)
  it should s"fetch every typed component and target through independent stage bindings with stage=$stage" in {
    simulate(new NativeShaderTextureUnit(GpuConfig(lanes=4,warps=2),stage)){d=>val t=new Driver(d);roots(t.put,stage)
      var checks=0
      for(target<-0 to 3;format<-0 until 36){val unit=(format+target)%32;val v=install(t.put,t.byte,unit,format,target,stage)
        coordinates(t.put,target);d.io.invalidate.poke(true.B);t.tick();d.io.invalidate.poke(false.B)
        for(c<-0 until 4){t.scalarRequest(c,unit);t.scalarResult(v.expected(c));checks+=1}}
      assert(checks==576)
      info(s"NATIVE_TYPED_TEXTURE stage=$stage targets4=true formats36=true componentChecks=$checks bindingSlots32=true twoPhysicalCredits=true fullRawTypes=true heldResults=true snapshotPoisoned=true")
    }
  }
  it should "retain inactive vector lanes and suppress every destination lane on a later active lane fault" in {
    simulate(new NativeShaderTextureUnit(GpuConfig(lanes=4,warps=2))){d=>val t=new Driver(d);roots(t.put)
      val values=(0 until 4).map(i=>install(t.put,t.byte,i,if(i%2==0)35 else 10,raw=BigInt(i+1)<<96|BigInt(0xfedcba98L+i)))
      for(i<-0 until 4)coordinates(t.put,address=packet+i*64)
      t.vectorRequest(0);t.vectorResult(Seq(values(0).expected(0),BigInt(0xabc00001L),values(2).expected(0),BigInt(0xabc00003L)))
      t.vectorRequest(3,mask=15);t.vectorResult(values.map(_.expected(3)))
      coordinates(t.put,address=packet+2*64,x=0xffffffffL)
      t.vectorRequest(0);t.vectorResult(Seq.fill(4)(BigInt(0)),fault=true)
      coordinates(t.put,address=packet+2*64);t.vectorRequest(0,mask=0);val before=t.memory.seen.size
      t.vectorResult((0 until 4).map(i=>BigInt(0xabc00000L+i)));assert(t.memory.seen.size==before)
      t.vectorRequest(1,mask=15);t.vectorResult(values.map(_.expected(1)))
      info("NATIVE_TYPED_TEXTURE_VECTOR inactiveOldVd=true maskedPerLaneBindings=true allComponents=true laterLaneFaultNoPartialWrite=true zeroMaskNoReads=true healthyReuse=true")
    }
  }
  it should "reject root stage binding identity malformed packets and full width indices before unsafe reads" in {
    simulate(new NativeShaderTextureUnit(GpuConfig(lanes=4,warps=2))){d=>val t=new Driver(d);roots(t.put);val v=install(t.put,t.byte,0);coordinates(t.put)
      t.scalarRequest(0,32);t.scalarResult(fault=true);t.scalarRequest(0,0xffffffffL);t.scalarResult(fault=true)
      roots(t.put,stage=1);t.scalarRequest(0);t.scalarResult(fault=true);roots(t.put)
      for((a,value)<-Seq((bindings+12,BigInt(9)),(packet+12,BigInt(9)),(packet+48,BigInt(1)),(packet+40,BigInt(8)),(packet+44,BigInt(0x10000000)))){
        install(t.put,t.byte,0);coordinates(t.put);t.put(a,value);t.scalarRequest(0);t.scalarResult(fault=true)}
      coordinates(t.put);t.scalarRequest(0,pointer=packet+1);t.scalarResult(fault=true)
      t.scalarRequest(0);t.scalarResult(v.expected(0))
      info("NATIVE_TYPED_TEXTURE_ADMISSION stageIdentity=true bindingIdentity=true packetIdentityAndReserved=true full32BitUnitAndLevel=true fullFaceBeforeNarrowing=true misalignmentBeforeRead=true healthyReuse=true")
    }
  }
}
