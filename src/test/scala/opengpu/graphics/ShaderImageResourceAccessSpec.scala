package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class ShaderImageResourceAccessSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  private val values = Vector[BigInt](0x01234567L, 0x89abcdefL, 0xffffffffL, 0x80000000L)
  // Independent GIU1 wire record, deliberately not using the RTL field map.
  private def record(unit: Int = 0, generation: Int = 13, address: Int = 0x103f): Vector[BigInt] = {
    val w = Array.fill[BigInt](64)(0)
    val f = TextureImageFormat.id("RGBA32UI")
    for ((i,v) <- Seq[(Int,BigInt)](0 -> BigInt("47495501",16), 1 -> 256, 2 -> 1,
        3 -> 7, 4 -> 11, 5 -> generation, 6 -> unit, 7 -> 1, 8 -> 1, 9 -> 4,
        10 -> 1, 11 -> 1, 12 -> f, 13 -> f, 14 -> 1, 17 -> 15, 18 -> 1,
        22 -> 0x1000, 23 -> 0x1400, 25 -> address, 26 -> 1, 27 -> 1,
        28 -> 1, 29 -> 1, 30 -> 64, 37 -> 16)) w(i)=v
    w.toVector
  }
  private class Driver(val d: ShaderImageResourceAccess) {
    zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
    val memory = mutable.Map.empty[BigInt,Int]
    def byte(a: BigInt): Int = memory.getOrElse(a,(a.toInt*17+9)&255)
    def install(a: BigInt, x: BigInt, bytes: Int): Unit =
      for(i<-0 until bytes) memory(a+i)=((x>>(8*i))&255).toInt
    def descriptor(w: Vector[BigInt], unit: Int = 0, base: Int = 0x2000): Unit =
      w.zipWithIndex.foreach { case (v,i) => install(BigInt(base+unit*256+i*4),v,4) }
    def texel(a: Int, xs: Seq[BigInt] = values): Unit =
      xs.zipWithIndex.foreach { case(v,i)=>install(BigInt(a+i*4),v,4) }
    case class Pending(due: Int, data: BigInt, id: Int, fault: Boolean)
    var pending: Option[Pending] = None
    var cycle=0; var tag=0; var unit=0; var generation=13; var write=false
    var requests=Vector.empty[(Int,BigInt)]
    var ready=true; var holdAck=false; var wrongId=false
    var failAt=Set.empty[(Int,BigInt)]
    var held: Option[BigInt]=None
    var descriptorBase=BigInt(0x2000); var texelAddress=BigInt(0x103f)
    def tick(): Unit = {
      val q=d.io.memoryRequest; val r=d.io.memoryResponse
      q.ready.poke(ready.B)
      val reply=pending.filter(p=>p.due<=cycle && !holdAck)
      r.valid.poke(reply.nonEmpty.B)
      r.bits.transactionId.poke(reply.map(p=>if(wrongId)1-p.id else p.id).getOrElse(0).U)
      r.bits.readData.poke(reply.map(_.data).getOrElse(BigInt(0)).U)
      r.bits.fault.poke(reply.exists(_.fault).B)
      held.foreach { old=>q.valid.expect(true.B); assert(q.bits.peek().litValue==old) }
      held=if(q.valid.peek().litToBoolean && !ready)Some(q.bits.peek().litValue)else None
      if(reply.nonEmpty && r.ready.peek().litToBoolean)pending=None
      if(q.valid.peek().litToBoolean && ready) {
        assert(pending.isEmpty,"descriptor/data transaction reused before acknowledgement")
        val id=q.bits.transactionId.peek().litValue.toInt; val a=q.bits.address.peek().litValue
        assert(id==0 || id==1); q.bits.sizeLog2.expect(6.U); q.bits.cachePolicy.expect(2.U)
        q.bits.isWrite.expect((id==1 && write).B)
        val count=requests.count(_._1==id)
        if(id==0) {
          assert(!requests.exists(_._1==1) && count<4)
          assert(a==descriptorBase+unit*256+count*64)
        } else {
          assert(count<2 && a==(texelAddress & ~BigInt(63))+count*64)
        }
        val data=(0 until 64).map(i=>BigInt(byte(a+i))<<(8*i)).sum
        if(id==1 && write) {
          val mask=(0 until 64).filter(i=>a+i>=texelAddress && a+i<texelAddress+16)
            .foldLeft(BigInt(0))((m,i)=>m.setBit(i))
          q.bits.byteMask.expect(mask.U)
          val raw=q.bits.writeData.peek().litValue
          for(i<-0 until 64 if mask.testBit(i))memory(a+i)=((raw>>(8*i))&255).toInt
        } else q.bits.byteMask.expect(((BigInt(1)<<64)-1).U)
        pending=Some(Pending(cycle+3,data,id,failAt((id,a))))
        requests=requests :+ (id,a)
      }
      d.clock.step(); cycle+=1
    }
    def until(p: => Boolean): Unit = {
      var n=0;while(!p && n<256){tick();n+=1};assert(p,s"resource operation stalled at $cycle")
    }
    def invalidate(): Unit = { d.io.invalidate.poke(true.B);tick();d.io.invalidate.poke(false.B) }
    def begin(index: Int = 0, gen: Int = 13, store: Boolean = false, base: Int = 0x2000,
        address: Int = 0x103f, change: ShaderImageResourceAccessRequest => Unit = _=>()): Unit = {
      assert(pending.isEmpty && held.isEmpty)
      unit=index;generation=gen;write=store;descriptorBase=base;texelAddress=address
      tag+=1;requests=Vector.empty;failAt=Set.empty
      val q=d.io.in.bits;zero(q)
      q.table.version.poke(1.U);q.table.base.poke(base.U);q.table.limit.poke((base+1024).U)
      q.table.identity.context.poke(7.U);q.table.identity.pass.poke(11.U);q.table.identity.generation.poke(gen.U)
      q.unitIndex.poke(index.U);q.tag.poke(tag.U);q.stage.poke(GraphicsStage.compute.U)
      q.isWrite.poke(store.B);q.colorType.poke(1.U);q.hasFormatQualifier.poke(true.B)
      q.shaderFormat.poke(TextureImageFormat.id("RGBA32UI").U)
      q.rgba.zip(values).foreach{case(v,x)=>v.poke(x.U)};change(q)
      d.io.in.ready.expect(true.B);d.io.in.valid.poke(true.B);tick()
      d.io.in.valid.poke(false.B);zero(q)
    }
    def finish(status: Int = 0, descriptorLines: Int = 4, dataLines: Int = 2,
        hit: Boolean = false, result: Seq[BigInt] = values, fault: Option[BigInt] = None): Unit = {
      until(d.io.out.valid.peek().litToBoolean)
      assert(pending.isEmpty && requests.count(_._1==0)==descriptorLines && requests.count(_._1==1)==dataLines)
      val r=d.io.out.bits;r.tag.expect(tag.U);r.unitIndex.expect(unit.U);r.isWrite.expect(write.B)
      r.identity.context.expect(7.U);r.identity.pass.expect(11.U);r.identity.generation.expect(generation.U)
      r.status.expect(status.U);r.descriptorReadLines.expect(descriptorLines.U);r.descriptorHit.expect(hit.B)
      r.faultAddressValid.expect(fault.nonEmpty.B);r.faultAddress.expect(fault.getOrElse(BigInt(0)).U)
      val wanted=if(status!=0 || write || dataLines==0)Vector.fill(4)(BigInt(0))else result
      r.rgba.zip(wanted).foreach{case(v,x)=>v.expect(x.U)}
      val saved=r.peek().litValue
      for(_<-0 until 3){tick();d.io.busy.expect(true.B);d.io.in.ready.expect(false.B);assert(r.peek().litValue==saved)}
      d.io.out.ready.poke(true.B);tick();d.io.out.ready.poke(false.B);d.io.busy.expect(false.B)
    }
  }

  it should "consume GPU descriptors before exact split stores and loads for every image unit" in {
    simulate(new ShaderImageResourceAccess) { d=>
      val t=new Driver(d)
      for(unit<-0 until 4) {
        t.descriptor(record(unit),unit)
        val before=Vector.tabulate(1024)(i=>t.byte(BigInt(0x1000+i)))
        t.begin(index=unit,store=true);t.finish()
        for(i<-0 until 1024) {
          val off=0x1000+i-0x103f
          val expected=if(off>=0 && off<16)((values(off/4)>>(8*(off%4)))&255).toInt else before(i)
          assert(t.byte(BigInt(0x1000+i))==expected)
        }
        t.begin(index=unit);t.finish(descriptorLines=0,hit=true)
      }
      info("SHADER_IMAGE_RESOURCE units=4 operations=8 GPUDescriptorBeforeData=true splitLines=true guards=true immutableInputs=true")
    }
  }

  it should "refetch rebound descriptors and reject permissions malformed words and owner mismatches before data" in {
    simulate(new ShaderImageResourceAccess) { d=>
      val t=new Driver(d);t.descriptor(record());t.texel(0x103f)
      t.begin();t.finish()
      t.descriptor(record(address=0x10bf));t.texel(0x10bf,values.reverse)
      t.begin();t.finish(descriptorLines=0,hit=true)
      t.invalidate();t.begin(address=0x10bf);t.finish(result=values.reverse)
      t.descriptor(record(generation=14));t.begin(gen=14);t.finish()
      t.descriptor(record(),base=0x3000);t.begin(base=0x3000);t.finish()
      for((word,value,status)<-Seq[(Int,BigInt,Int)]((10,0,3),(9,1,3),(5,99,2),(6,1,2),(63,1,1),(24,2,1),(23,0x1040,4),(7,0,0),(8,0,0))) {
        t.invalidate();t.descriptor(record().updated(word,value));t.begin();t.finish(status=status,dataLines=0)
      }
      t.invalidate();t.descriptor(record());t.begin(change=q=>q.x.poke("hffffffff".U));t.finish(dataLines=0)
      t.begin();t.finish(descriptorLines=0,hit=true)
      info("SHADER_IMAGE_RESOURCE cacheRebind=true generationAndTableKey=true rejectedOrNoOp=10 dataRequests=0 healthyReuse=true")
    }
  }

  it should "drain held descriptor and data offers with disjoint reply IDs and primary faults" in {
    simulate(new ShaderImageResourceAccess) { d=>
      val t=new Driver(d);t.descriptor(record());t.texel(0x103f)
      for(owner<-0 to 1;fault<-Seq(false,true)) {
        t.invalidate();t.begin(store=owner==1)
        t.until(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.bits.transactionId.peek().litValue==owner)
        t.ready=false;t.holdAck=true;t.tick();d.io.cancel.poke(true.B)
        for(_<-0 until 3)t.tick()
        val line=if(owner==0)BigInt(0x2000)else BigInt(0x1000)
        t.failAt=if(fault)Set((owner,line))else Set.empty
        t.ready=true;t.tick()
        for(_<-0 until 4){t.tick();d.io.out.valid.expect(false.B)}
        t.holdAck=false;t.wrongId=true
        for(_<-0 until 3){t.tick();d.io.memoryResponse.ready.expect(false.B);d.io.out.valid.expect(false.B)}
        t.wrongId=false
        t.finish(status=if(fault)7 else 5,descriptorLines=if(owner==0)1 else 4,dataLines=if(owner==0)0 else 1,
          fault=if(fault)Some(if(owner==0)line else BigInt(0x103f))else None)
        d.io.cancel.poke(false.B)
      }
      t.invalidate();t.texel(0x103f);t.begin();t.finish()
      info("SHADER_IMAGE_RESOURCE descriptorAndDataOwners=true heldOffers=true delayedACK=true wrongReplyIDs=true cancelDrain=true primaryFault=true healthyReuse=true")
    }
  }

  it should "retain cancellation across descriptor and texel handoffs without losing ownership" in {
    simulate(new ShaderImageResourceAccess) { d=>
      val t=new Driver(d);t.descriptor(record());t.texel(0x103f)
      for(delay<-0 until 96) {
        t.invalidate();t.begin()
        for(_<-0 until delay if !d.io.out.valid.peek().litToBoolean)t.tick()
        val done=d.io.out.valid.peek().litToBoolean
        d.io.cancel.poke(true.B);t.until(d.io.out.valid.peek().litToBoolean)
        t.finish(status=if(done)0 else 5,descriptorLines=t.requests.count(_._1==0),dataLines=t.requests.count(_._1==1))
        d.io.cancel.poke(false.B)
      }
      t.invalidate();t.begin();t.finish()
      info("SHADER_IMAGE_RESOURCE cancelCycleSweep=96 allOwnersDrained=true heldResultsStable=true healthyReuse=true")
    }
  }
}
