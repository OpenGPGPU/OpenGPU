package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class NativeShaderTextureUnitSpec extends AnyFlatSpec {
  import WindowRecordTest._
  import NativeTextureFetchTestData._
  private class Driver(val d: NativeShaderTextureUnit) {
    private val lanes=d.io.vectorIn.bits.issued.vs1Data.length
    private val allMask=(BigInt(1)<<lanes)-1
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
      q.decode.warpId.poke(1.U);q.decode.pc.poke(0x200.U);q.decode.activeMask.poke(allMask.U)
      q.rs1Data.poke(pointer.U);q.rs2Data.poke(unit.U);d.io.in.valid.poke(true.B)
      until(d.io.in.ready.peek().litToBoolean);tick();d.io.in.valid.poke(false.B);zero(q);zero(d.io.resources);zero(d.io.identity)
    }
    def vectorRequest(c: Int,mask: BigInt=5,units: Seq[Int]=Seq(0,1,2,3),pointers: Seq[BigInt]=(0 until 4).map(i=>packet+i*64)): Unit={
      context();zero(d.io.vectorIn.bits);val q=d.io.vectorIn.bits;q.issued.decode.instruction.poke(vector(c,masked=true).U)
      q.issued.decode.warpId.poke(1.U);q.issued.decode.pc.poke(0x204.U);q.issued.decode.activeMask.poke(allMask.U);q.executionMask.poke(mask.U)
      for(i<-0 until lanes){q.issued.vs1Data(i).poke(pointers(i).U);q.issued.vs2Data(i).poke(units(i).U);q.issued.oldVdData(i).poke((0xabc00000L+i).U)}
      d.io.vectorIn.valid.poke(true.B);until(d.io.vectorIn.ready.peek().litToBoolean);tick();d.io.vectorIn.valid.poke(false.B)
      zero(q);zero(d.io.resources);zero(d.io.identity)
    }
    def scalarResult(value: BigInt=0,fault: Boolean=false,address: BigInt=0): Unit={
      until(d.io.commit.valid.peek().litToBoolean);d.io.commit.bits.rd.expect(10.U);d.io.commit.bits.warpId.expect(1.U)
      d.io.commit.bits.nextPc.expect(0x204.U);d.io.commit.bits.activeMask.expect(allMask.U);d.io.commit.bits.writeRd.expect((!fault).B)
      d.io.commit.bits.data.expect(value.U);d.io.responseFault.expect(fault.B)
      if(fault)d.io.faultAddress.expect(address.U)
      assert(memory.pending.isEmpty && memory.reply.isEmpty);val held=d.io.commit.bits.peek().litValue
      d.io.cancel.poke(true.B);for(_<-0 until 3){tick();assert(d.io.commit.bits.peek().litValue==held)}
      d.io.commit.ready.poke(true.B);tick();d.io.commit.ready.poke(false.B);d.io.cancel.poke(false.B);d.io.busy.expect(false.B)
    }
    def vectorResult(values: Seq[BigInt],fault: Boolean=false): Unit={
      until(d.io.vectorCommit.valid.peek().litToBoolean);val q=d.io.vectorCommit.bits
      q.writeback.warpId.expect(1.U);q.writeback.vd.expect(10.U);q.pc.expect(0x204.U);q.warpActiveMask.expect(allMask.U);q.writesVd.expect((!fault).B)
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
      for(target<-0 to 3;format<-0 until 54 if !(target==3 && ImageFormatReference.format(ImageFormatReference.names(format)).depth)){val unit=(format+target)%32;val v=install(t.put,t.byte,unit,format,target,stage)
        coordinates(t.put,target);d.io.invalidate.poke(true.B);t.tick();d.io.invalidate.poke(false.B)
        for(c<-0 until 4){t.scalarRequest(c,unit);t.scalarResult(v.expected(c));checks+=1}}
      assert(checks==844)
      info(s"NATIVE_TYPED_TEXTURE stage=$stage targets4=true formats54=true componentChecks=$checks bindingSlots32=true twoPhysicalCredits=true fullRawTypes=true heldResults=true snapshotPoisoned=true")
    }
  }
  for(stage<-0 to 1)
  it should s"decode every compressed format through native binding selection and block coordinates stage=$stage" in {
    simulate(new NativeShaderTextureUnit(GpuConfig(lanes=4,warps=2),stage)){d=>val t=new Driver(d);roots(t.put,stage)
      var checks=0
      for(format<-54 until 64;target<-0 to 2){val unit=(format+target)%32;val v=install(t.put,t.byte,unit,format,target,stage)
        d.io.invalidate.poke(true.B);t.tick();d.io.invalidate.poke(false.B)
        for(y<-0 until 4;x<-0 until 4){coordinates(t.put,target,x=x,y=y)
          val expected=CompressedImageReference.decode(format,v.raw,x,y)
          for(c<-0 until 4){t.scalarRequest(c,unit);t.scalarResult(expected(c));checks+=1}}
        val planes=if(target==1)3 else if(target==2)6 else 1
        for(plane<-0 until planes){val y=if(target==2)6 else 4
          coordinates(t.put,target,x=6,y=y,layer=if(target==1)plane else 0,face=if(target==2)plane else 0)
          val expected=CompressedImageReference.decode(format,v.raw,2,y&3)
          for(c<-0 until 4){t.scalarRequest(c,unit);t.scalarResult(expected(c));checks+=1}}
        coordinates(t.put,target,x=7);t.scalarRequest(0,unit);t.scalarResult(fault=true)
      }
      for(format<-54 until 64){install(t.put,t.byte,0,format,3,stage);coordinates(t.put,3)
        d.io.invalidate.poke(true.B);t.tick();d.io.invalidate.poke(false.B);val before=t.memory.seen.size
        t.scalarRequest(0);t.scalarResult(fault=true)
        assert(!t.memory.seen.drop(before).exists(r=>r.address>=0x20000 && r.address<0x21000),"compressed3D must fail before data")}
      info(s"NATIVE_COMPRESSED_TEXTURE stage=$stage formats10=true targets2DArrayCube=true blockCoordinates16=true NPOT7x5And7x7=true allLayersAndFaces=true outOfExtentRejected=true volumeRejected=true componentChecks=$checks")
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
  it should "retain mapped read offers through cancellation cuts and preserve primary faults before healthy reuse" in {
    simulate(new NativeShaderTextureUnit(GpuConfig(lanes=4,warps=2))){d=>val t=new Driver(d);roots(t.put)
      val v=install(t.put,t.byte,0,35);coordinates(t.put);t.memory.delay=11
      for(cut<-0 until 240){
        d.io.invalidate.poke(true.B);t.tick();d.io.invalidate.poke(false.B);t.scalarRequest(0)
        for(_<-0 until cut if !d.io.commit.valid.peek().litToBoolean)t.tick()
        val held=d.io.commit.valid.peek().litToBoolean;d.io.cancel.poke(true.B)
        t.scalarResult(if(held)v.expected(0)else BigInt(0),fault= !held)
        t.scalarRequest(1);t.scalarResult(v.expected(1))
      }
      t.memory.holdRequests=true;t.scalarRequest(0);t.until(d.io.memoryRequest.valid.peek().litToBoolean);t.tick()
      val offered=d.io.memoryRequest.bits.peek().litValue;d.io.cancel.poke(true.B)
      for(_<-0 until 9){t.tick();d.io.memoryRequest.valid.expect(true.B);assert(d.io.memoryRequest.bits.peek().litValue==offered)}
      t.memory.holdRequests=false;t.scalarResult(fault=true)
      t.memory.faultAt=Set(root);val before=t.memory.seen.size;t.scalarRequest(0)
      t.until(t.memory.seen.drop(before).exists(_.address==root) && t.memory.pending.isEmpty && t.memory.reply.isEmpty)
      d.io.cancel.poke(true.B);t.scalarResult(fault=true,address=root);t.memory.faultAt=Set.empty
      t.scalarRequest(2);t.scalarResult(v.expected(2))
      info("NATIVE_TYPED_TEXTURE_CANCEL cuts240=true mappedOfferHeld9=true primaryRootFaultBeforeCancel=true noLeaseAbandonment=true exactHealthyReuse=true immutableRetirement=true")
    }
  }

  it should "select explicit mip texels and validate the sampler required chain before reading any data" in {
    simulate(new NativeShaderTextureUnit(GpuConfig(lanes=4,warps=2))){d=>val t=new Driver(d);roots(t.put);install(t.put,t.byte,0,35)
      val bytes=16;val allocation=BigInt(0x20000);val id=defaultIdentity
      t.put(images+10*4,1);t.put(images+11*4,4);t.put(images+12*4,4);t.put(images+20*4,levels+256)
      t.put(samplers+7*4,2)
      val raws=Seq(BigInt("3f8000003f4000003f0000003e800000",16),BigInt("400000003fc000003f8000003f000000",16))
      for(level<-0 to 1){val size=4>>level;val row=size*bytes+5;val data=allocation+60+level*512
        words(t.put,levels+level*128,Seq[BigInt](BigInt("47584c01",16),128,1)++id++
          Seq[BigInt](level,0,level,allocation,allocation+4096,0,data,size,size,1,1,row,0,0,0,0,0,0,bytes),32)
        val address=data+bytes+row;for(n<-0 until bytes)t.byte(address+n,((raws(level)>>(8*n))&255).toInt)
      }
      for(level<-0 to 1;c<-0 until 4){coordinates(t.put,level=level);t.scalarRequest(c)
        t.scalarResult((raws(level)>>(32*c))&0xffffffffL)}
      d.io.invalidate.poke(true.B);t.tick();d.io.invalidate.poke(false.B)
      t.put(levels+128+13*4,1);coordinates(t.put,level=0);val before=t.memory.seen.size
      t.scalarRequest(0);t.scalarResult(fault=true)
      assert(!t.memory.seen.drop(before).exists(r=>r.address>=allocation && r.address<allocation+4096))
      info("NATIVE_TYPED_TEXTURE_MIPS selectedLevels0And1=true exactComponents8=true samplerDerivedChain=true laterMismatchBeforeData=true fullLevelTransport=true")
    }
  }

  for(lanes<-Seq(1,16,32))
  it should s"preserve explicit fetch vector width and high masked lanes for lanes=$lanes" in {
    simulate(new NativeShaderTextureUnit(GpuConfig(lanes=lanes,warps=2))){d=>val t=new Driver(d);roots(t.put)
      val views=(0 until lanes).map(u=>install(t.put,t.byte,u,10,raw=BigInt(128+u)))
      val pointers=(0 until lanes).map(u=>packet+u*64)
      for(a<-pointers)coordinates(t.put,address=a)
      val all=(BigInt(1)<<lanes)-1;val mask=BigInt("a5a5a5a5",16)&all
      t.vectorRequest(0,mask,(0 until lanes),pointers)
      t.vectorResult((0 until lanes).map(u=>if(mask.testBit(u))views(u).expected(0)else BigInt(0xabc00000L+u)))
      t.vectorRequest(3,all,(0 until lanes),pointers);t.vectorResult(views.map(_.expected(3)))
      t.scalarRequest(0,lanes-1,pointers.last);t.scalarResult(views.last.expected(0))
      info(s"NATIVE_TYPED_TEXTURE_WIDTH lanes=$lanes explicitFetchNoDerivatives=true highMaskBits=true allLaneBindings=true inactiveOldVd=true scalarLastBinding=true twoCredits=true")
    }
  }

}
