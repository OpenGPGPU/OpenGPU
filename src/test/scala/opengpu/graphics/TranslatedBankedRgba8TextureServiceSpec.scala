package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class TranslatedBankedRgba8TextureServiceSpec extends AnyFlatSpec {
  import WindowRecordTest._
  private case class Group(tag: Int,addresses: Vector[BigInt],rows: Vector[Boolean],weights: Vector[Int])
  private case class Read(id: Int,address: BigInt,cycle: Int)
  private case class Reply(id: Int,data: BigInt,fault: Boolean,due: Int)
  private case class Result(tag: Int,values: Vector[BigInt],hit: Boolean,fault: Boolean,cancelled: Boolean,
      source: Int,address: BigInt,cycle: Int)
  private val weights=Vector(16384,16384,16384,16384)
  private def group(tag: Int,pair: Int=0): Group={
    val a=BigInt(0x401000)+pair*128
    Group(tag,Vector(a,a+4,a+64,a+68),Vector(false,false,true,true),weights)
  }
  private def word(address: BigInt,epoch: Int): BigInt={
    val n=(address>>2)^(address>>10)^(address>>17)
    Vector((n*13+17+epoch*31)&255,(n*7+71+epoch*17)&255,
      (n*3+193+epoch*29)&255,BigInt(128)+((n+epoch*11)&127)).zipWithIndex.map{case(v,b)=>v<<(8*b)}.sum
  }
  private def line(address: BigInt,epoch: Int): BigInt=(0 until 16).map(n=>word(address+4*n,epoch)<<(32*n)).sum
  private class Driver(val d: TranslatedBankedRgba8TextureService){
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    var cycle=0;var epoch=0;var root=0x80;var latency=7
    var resultReady: Int=>Boolean= _=>true
    var dataReady: Int=>Boolean= _=>true
    var walkReady: Int=>Boolean= _=>true
    var dataFault: BigInt=>Boolean= _=>false
    var walkFault: BigInt=>Boolean= _=>false
    val ptes=mutable.Map.empty[BigInt,BigInt]
    val mappings=mutable.Map.empty[(Int,BigInt),BigInt]
    val reads=mutable.ArrayBuffer.empty[Read];val walks=mutable.ArrayBuffer.empty[Read]
    val results=mutable.ArrayBuffer.empty[Result];val inputCycles=mutable.ArrayBuffer.empty[(Int,Int)]
    val expected=mutable.Map.empty[Int,Vector[BigInt]]
    private val dataPending=mutable.ArrayBuffer.empty[Reply];private val walkPending=mutable.ArrayBuffer.empty[Reply]
    private val dataLive=mutable.Set.empty[Int];private val walkLive=mutable.Set.empty[Int]
    private var dataOffer: Option[Reply]=None;private var walkOffer: Option[Reply]=None
    private var heldData: Option[BigInt]=None;private var heldWalk: Option[BigInt]=None;private var heldResult: Option[BigInt]=None
    def vm(next: Int): Unit={root=next;d.io.satp.poke(((BigInt(1)<<31)|root).U)}
    vm(root)
    def mapping(virtual: BigInt,physical: BigInt,readable: Boolean=true,policy: Int=0,vmRoot: Int=0x80): Unit={
      val vpn=virtual>>12;val table=BigInt(0x90+(vmRoot-0x80)*32)+(vpn>>10)
      ptes((BigInt(vmRoot)<<12)+(vpn>>10)*4)=(table<<10)|1
      ptes((table<<12)+(vpn&1023)*4)=((physical>>12)<<10)|(BigInt(policy)<<8)|(if(readable)0x43 else 0x49)
      mappings((vmRoot,vpn))=physical>>12
    }
    def physical(a: BigInt): BigInt=(mappings((root,a>>12))<<12)|(a&4095)
    def oracle(q: Group): Vector[BigInt]=(0 until 4).map{c=>
      val n=q.addresses.zip(q.weights).filter(_._2!=0).map{case(a,w)=>((word(physical(a),epoch)>>(8*c))&255)*w}.sum
      Rgba8FloatOracle.bits(n)
    }.toVector
    private def due(q: mutable.ArrayBuffer[Reply]): Option[Reply]={
      val ready=q.indices.filter(i=>q(i).due<=cycle)
      if(ready.nonEmpty)Some(q.remove(ready.last))else None
    }
    def tick(): Boolean={
      d.io.out.ready.poke(resultReady(cycle).B);d.io.memoryRequest.ready.poke(dataReady(cycle).B)
      d.io.pageWalk.ready.poke(walkReady(cycle).B)
      if(dataOffer.isEmpty)dataOffer=due(dataPending)
      if(walkOffer.isEmpty)walkOffer=due(walkPending)
      d.io.memoryResponse.valid.poke(dataOffer.nonEmpty.B)
      dataOffer.foreach{r=>d.io.memoryResponse.bits.transactionId.poke(r.id.U);d.io.memoryResponse.bits.readData.poke(r.data.U);d.io.memoryResponse.bits.fault.poke(r.fault.B)}
      d.io.pageWalkResponse.valid.poke(walkOffer.nonEmpty.B)
      walkOffer.foreach{r=>d.io.pageWalkResponse.bits.transactionId.poke(r.id.U);d.io.pageWalkResponse.bits.readData.poke(r.data.U);d.io.pageWalkResponse.bits.fault.poke(r.fault.B)}
      heldResult.foreach{v=>d.io.out.valid.expect(true.B);assert(d.io.out.bits.peek().litValue==v,"held virtual result changed")}
      heldData.foreach{v=>d.io.memoryRequest.valid.expect(true.B);assert(d.io.memoryRequest.bits.peek().litValue==v,"held physical data offer changed")}
      heldWalk.foreach{v=>d.io.pageWalk.valid.expect(true.B);assert(d.io.pageWalk.bits.peek().litValue==v,"held PTE offer changed")}
      heldResult=if(d.io.out.valid.peek().litToBoolean && !d.io.out.ready.peek().litToBoolean)Some(d.io.out.bits.peek().litValue)else None
      heldData=if(d.io.memoryRequest.valid.peek().litToBoolean && !d.io.memoryRequest.ready.peek().litToBoolean)Some(d.io.memoryRequest.bits.peek().litValue)else None
      heldWalk=if(d.io.pageWalk.valid.peek().litToBoolean && !d.io.pageWalk.ready.peek().litToBoolean)Some(d.io.pageWalk.bits.peek().litValue)else None
      if(d.io.memoryResponse.valid.peek().litToBoolean && d.io.memoryResponse.ready.peek().litToBoolean){
        assert(dataLive(dataOffer.get.id));dataLive-=dataOffer.get.id;dataOffer=None
      }
      if(d.io.pageWalkResponse.valid.peek().litToBoolean && d.io.pageWalkResponse.ready.peek().litToBoolean){
        assert(walkLive(walkOffer.get.id));walkLive-=walkOffer.get.id;walkOffer=None
      }
      if(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean){
        val r=d.io.memoryRequest.bits;val id=r.transactionId.peek().litValue.toInt;val a=r.address.peek().litValue
        assert(!dataLive(id));dataLive+=id;r.isWrite.expect(false.B);r.sizeLog2.expect(6.U)
        r.cachePolicy.expect(2.U);assert((a&63)==0);reads+=Read(id,a,cycle)
        dataPending+=Reply(id,line(a,epoch),dataFault(a),cycle+latency+(a.toInt/64%5))
      }
      if(d.io.pageWalk.valid.peek().litToBoolean && d.io.pageWalk.ready.peek().litToBoolean){
        val r=d.io.pageWalk.bits;val id=r.transactionId.peek().litValue.toInt;val a=r.address.peek().litValue
        assert(!walkLive(id),"PTE transaction ID reused before completion");walkLive+=id
        r.isWrite.expect(false.B);r.sizeLog2.expect(2.U);r.cachePolicy.expect(2.U)
        walks+=Read(id,a,cycle);walkPending+=Reply(id,ptes.getOrElse(a,BigInt(0)),walkFault(a),cycle+3+(3-id))
      }
      if(d.io.out.valid.peek().litToBoolean && d.io.out.ready.peek().litToBoolean){
        val r=d.io.out.bits;r.identity.context.expect(7.U);r.identity.pass.expect(8.U);r.identity.generation.expect(9.U)
        results+=Result(r.tag.peek().litValue.toInt,r.values.map(_.peek().litValue).toVector,r.hit.peek().litToBoolean,
          r.fault.peek().litToBoolean,r.cancelled.peek().litToBoolean,r.faultSource.peek().litValue.toInt,
          r.faultAddress.peek().litValue,cycle)
      }
      val input=d.io.in.valid.peek().litToBoolean && d.io.in.ready.peek().litToBoolean
      if(input)inputCycles+=((d.io.in.bits.tag.peek().litValue.toInt,cycle))
      d.clock.step();cycle+=1
      if(dataLive.nonEmpty || walkLive.nonEmpty)d.io.busy.expect(true.B)
      input
    }
    def until(p: =>Boolean): Unit={var n=0;while(!p && n<6000){tick();n+=1};assert(p,s"translated service stalled cycle=$cycle data=${reads.size} walks=${walks.size} results=${results.map(_.tag)}")}
    def submit(q: Group): Unit={
      val b=d.io.in.bits;b.identity.context.poke(7.U);b.identity.pass.poke(8.U);b.identity.generation.poke(9.U)
      b.tag.poke(q.tag.U);b.textureKey.poke(1.U)
      for(t<-0 until 4){b.addresses(t).poke(q.addresses(t).U);b.rowParity(t).poke(q.rows(t).B);b.weights(t).poke(q.weights(t).U)}
      expected(q.tag)=oracle(q);d.io.in.valid.poke(true.B)
      var n=0;var fire=false;while(!fire && n<6000){fire=tick();n+=1};assert(fire)
      d.io.in.valid.poke(false.B);b.textureKey.poke("hdeadbeef".U);b.identity.generation.poke(99.U)
    }
    def good(r: Result): Unit={
      assert(r.hit && !r.fault && !r.cancelled && r.source==0 && r.address==0,r.toString)
      assert(r.values==expected(r.tag),s"tag=${r.tag} got=${r.values} want=${expected(r.tag)}")
    }
    def drain(): Unit={until(!d.io.busy.peek().litToBoolean);assert(dataLive.isEmpty && walkLive.isEmpty);tick();tick()}
    def flush(): Unit={d.io.flush.valid.poke(true.B);tick();d.io.flush.valid.poke(false.B)}
  }
  it should "translate nonidentity pages then sustain complete resident groups at one per cycle" in {
    simulate(new TranslatedBankedRgba8TextureService()){d=>
      val t=new Driver(d);t.mapping(0x401000,0x101000)
      for(n<-0 until 8)t.submit(group(n,n))
      t.until(t.results.size==8);t.results.foreach(t.good);t.drain()
      assert(t.reads.size==16 && t.reads.forall(r=>r.address>=0x101000 && r.address<0x102000))
      assert(t.walks.size==8 && t.walks.map(_.id).toSet==Set(0,1,2,3))
      val mark=t.results.size;val walks=t.walks.size
      for(n<-0 until 96)t.submit(group(1000+n,n%8))
      t.until(t.results.size==mark+96);t.results.drop(mark).foreach(t.good)
      val issued=t.inputCycles.filter(_._1>=1000).map(_._2);val retired=t.results.drop(mark).map(_.cycle)
      assert(issued.size==96 && issued.sliding(2).forall(p=>p(1)-p(0)==1),"translated resident input II exceeded one")
      assert(retired.size==96 && retired.sliding(2).forall(p=>p(1)-p(0)==1),"translated complete output II exceeded one")
      t.resultReady=c=>c%19<7
      for(n<-0 until 192)t.submit(group(2000+n,n%8))
      t.until(t.results.size==mark+288);t.results.drop(mark+96).foreach(t.good)
      assert(t.reads.size==16 && t.walks.size==walks)
      t.resultReady=_=>true;t.drain()
      info("BANKED_TRANSLATED_RATE nonidentitySv32=true TLBPorts4=true coldDataReads16=true warmGroups96=true inputII1=true completeOutputII1=true backpressureGroups192=true noHotPTEOrDataReads=true")
    }
  }
  it should "fault before any physical read and recheck permission before reusing a resident physical alias" in {
    simulate(new TranslatedBankedRgba8TextureService()){d=>
      val t=new Driver(d);t.mapping(0x401000,0x101000);t.mapping(0x402000,0x102000,readable=false)
      val bad=group(0).copy(addresses=Vector[BigInt](0x401000,0x401004,0x402000,0x402004))
      t.submit(bad);t.until(t.results.size==1);t.drain()
      val r=t.results.head;assert(r.fault && !r.hit && !r.cancelled && r.source==1 && Set(BigInt(0x402000),BigInt(0x402004))(r.address) && r.values.forall(_==0))
      assert(t.reads.isEmpty,"a partially admitted group read physical texels before its fault")
      t.submit(group(1));t.until(t.results.size==2);t.good(t.results.last);t.drain();assert(t.reads.size==2)
      t.mapping(0x403000,0x101000,readable=false)
      val denied=group(2).copy(addresses=group(2).addresses.map(_+0x2000))
      t.submit(denied);t.until(t.results.size==3);t.drain()
      val a=t.results.last;assert(a.fault && !a.hit && a.source==1 && denied.addresses.contains(a.address))
      assert(t.reads.size==2,"a denied resident alias reached the physical cache")
      // Unused taps may be unmapped;they do not require permission or reads.
      val point=group(3).copy(addresses=Vector[BigInt](0x401000,0x601000,0x702000,0x803000),weights=Vector(65536,0,0,0))
      val walkCount=t.walks.size;t.submit(point);t.until(t.results.size==4);t.good(t.results.last);t.drain()
      assert(t.walks.size==walkCount && t.reads.size==2)
      info("BANKED_TRANSLATED_PERMISSION noPartialDataIssue=true deniedResidentAlias=true originalVirtualFault=true zeroWeightUnmappedTapsSkipped=true noSuccessfulZero=true")
    }
  }
  it should "honor uncached aliases and invalidate translations automatically across root changes" in {
    simulate(new TranslatedBankedRgba8TextureService()){d=>
      val t=new Driver(d);t.mapping(0x401000,0x101000);t.mapping(0x402000,0x101000,policy=2)
      t.submit(group(0));t.until(t.results.size==1);t.good(t.results.last);t.drain();assert(t.reads.size==2)
      val uncached=group(1).copy(addresses=group(1).addresses.map(_+0x1000))
      for(n<-1 to 2){t.epoch=n;t.submit(uncached.copy(tag=n));t.until(t.results.size==n+1);t.good(t.results.last);t.drain();assert(t.reads.size==2+n*2)}
      t.mapping(0x401000,0x201000,vmRoot=0x81);t.vm(0x81)
      val before=t.walks.size;t.submit(group(3));t.until(t.results.size==4);t.good(t.results.last);t.drain()
      assert(t.walks.size==before+8 && t.reads.takeRight(2).forall(_.address>=0x201000))
      t.mapping(0x401000,0x301000,vmRoot=0x81);t.flush()
      t.submit(group(4));t.until(t.results.size==5);t.good(t.results.last);t.drain()
      assert(t.reads.takeRight(2).forall(_.address>=0x301000))
      info("BANKED_TRANSLATED_VM uncachedAliasBypassesResident=true changedBackingObserved=true rootChangeAutoFlush=true sameRootExplicitFlush=true nonidentityPhysicalRemap=true")
    }
  }
  it should "retain exact virtual attribution for physical line faults and recover" in {
    simulate(new TranslatedBankedRgba8TextureService()){d=>
      val t=new Driver(d);t.mapping(0x401000,0x101000);t.dataFault=_==0x101000
      t.submit(group(0));t.until(t.results.size==1);t.drain();val r=t.results.head
      assert(r.fault && !r.hit && r.source==2 && Set(BigInt(0x401000),BigInt(0x401004))(r.address) && r.values.forall(_==0))
      t.dataFault=_=>false;t.submit(group(1));t.until(t.results.size==2);t.good(t.results.last);t.drain()
      assert(t.reads.size==3)
      info("BANKED_TRANSLATED_DATA_FAULT originalVirtualWord=true primaryFault=true noFailedLineInstall=true healthyReuse=true")
    }
  }
  it should "drain a held PTE request across cancellation and retain a concurrent page-walk fault" in {
    simulate(new TranslatedBankedRgba8TextureService()){d=>
      val t=new Driver(d);t.mapping(0x401000,0x101000);t.walkReady=_=>false
      t.submit(group(0));t.until(d.io.pageWalk.valid.peek().litToBoolean)
      d.io.cancel.poke(true.B);t.tick();d.io.cancel.poke(false.B)
      for(_<-0 until 8)t.tick()
      d.io.out.valid.expect(false.B);d.io.busy.expect(true.B)
      t.walkFault=_=>true;t.walkReady=_=>true
      t.until(t.results.size==1);t.drain();val r=t.results.head
      assert(r.fault && r.cancelled && !r.hit && r.source==1 && group(0).addresses.contains(r.address))
      assert(t.reads.isEmpty && t.walks.nonEmpty)
      t.walkFault=_=>false;t.submit(group(1));t.until(t.results.size==2);t.good(t.results.last);t.drain()
      info("BANKED_TRANSLATED_PTE_CANCEL heldWalkImmutable=true acceptedWalksDrain=true primaryFaultRetained=true noDataIssue=true healthyReuse=true")
    }
  }
  it should "cancel every unoffered completed row while keeping the single public result immutable" in {
    simulate(new TranslatedBankedRgba8TextureService()){d=>
      val t=new Driver(d);t.mapping(0x401000,0x101000)
      t.submit(group(0));t.until(t.results.size==1);t.good(t.results.last);t.drain()
      t.resultReady=_=>false
      for(n<-1 to 3)t.submit(group(n))
      t.until(d.io.out.valid.peek().litToBoolean)
      for(_<-0 until 100)t.tick()
      d.io.cancel.poke(true.B);t.tick();d.io.cancel.poke(false.B)
      for(_<-0 until 8)t.tick()
      t.resultReady=_=>true;t.until(t.results.size==4);t.drain()
      t.good(t.results(1));assert(t.results.drop(2).forall(r=>r.cancelled && !r.hit && !r.fault && r.values.forall(_==0)))
      t.submit(group(4));t.until(t.results.size==5);t.good(t.results.last);t.drain()
      info("BANKED_TRANSLATED_PUBLIC heldPublicSuccess=true hiddenCompletedRowsCancelled2=true exactOrder=true healthyReuse=true")
    }
  }
  it should "retain cancellation and invalidation at translation cache fill and final retirement cuts" in {
    simulate(new TranslatedBankedRgba8TextureService()){d=>
      val t=new Driver(d);t.mapping(0x401000,0x101000)
      t.walkReady=c=>c%7<4;t.dataReady=c=>c%11<5
      var offered=0;var cancelled=0
      for((cut,n)<-Vector(0,1,3,8,16,32,64,128).zipWithIndex){
        t.resultReady=_=>false;val q=group(n*2,n);t.submit(q)
        for(_<-0 until cut)t.tick()
        val public=d.io.out.valid.peek().litToBoolean
        if(n%2==0)d.io.cancel.poke(true.B)else d.io.invalidate.poke(true.B)
        t.tick();d.io.cancel.poke(false.B);d.io.invalidate.poke(false.B)
        for(_<-0 until 8)t.tick()
        t.resultReady=_=>true;t.until(t.results.exists(_.tag==q.tag));t.drain()
        val r=t.results.find(_.tag==q.tag).get
        if(public){offered+=1;t.good(r)}else{cancelled+=1;assert(r.cancelled && !r.hit && !r.fault && r.values.forall(_==0))}
        t.submit(q.copy(tag=q.tag+1));t.until(t.results.exists(_.tag==q.tag+1));t.good(t.results.last);t.drain()
      }
      assert(offered>0 && cancelled>0,"cancel cuts did not witness both internal and public ownership")
      info(s"BANKED_TRANSLATED_CUTS cuts8=true publicWitnesses=$offered cancelledWitnesses=$cancelled cancelAndInvalidate=true heldOffersStable=true completeDrain=true")
    }
  }  it should "cancel one identity tag without stopping other groups or a later reuse of that tag" in {
    simulate(new TranslatedBankedRgba8TextureService()){d=>
      val t=new Driver(d);t.mapping(0x401000,0x101000)
      def cancel(tag: Int): Unit={
        d.io.cancelGroup.bits.identity.context.poke(7.U);d.io.cancelGroup.bits.identity.pass.poke(8.U)
        d.io.cancelGroup.bits.identity.generation.poke(9.U);d.io.cancelGroup.bits.tag.poke(tag.U)
        d.io.cancelGroup.valid.poke(true.B)
      }
      t.submit(group(0));t.until(t.results.size==1);t.good(t.results.last);t.drain()
      t.resultReady=_=>false
      for(n<-1 to 3)t.submit(group(n))
      t.until(d.io.out.valid.peek().litToBoolean);for(_<-0 until 100)t.tick()
      cancel(2);t.tick();d.io.cancelGroup.valid.poke(false.B);for(_<-0 until 8)t.tick()
      t.resultReady=_=>true;t.until(t.results.size==4);t.drain()
      t.good(t.results(1));t.good(t.results(3))
      assert(t.results(2).cancelled && !t.results(2).hit && !t.results(2).fault)
      t.submit(group(2));t.until(t.results.size==5);t.good(t.results.last);t.drain()
      t.mapping(0x402000,0x102000);t.mapping(0x403000,0x103000)
      t.walkReady=_=>false;val mark=t.reads.size
      t.submit(group(4,32));t.until(d.io.pageWalk.valid.peek().litToBoolean);t.submit(group(5,64))
      cancel(4);t.tick();d.io.cancelGroup.valid.poke(false.B)
      t.walkReady=_=>true;t.until(t.results.size==7);t.drain()
      assert(t.results(5).cancelled && !t.results(5).hit && !t.results(5).fault)
      t.good(t.results(6));assert(t.reads.size==mark+2 && t.reads.drop(mark).forall(r=>r.address>=0x103000 && r.address<0x104000))
      val walks=t.walks.size;val reads=t.reads.size
      cancel(6);t.submit(group(6));d.io.cancelGroup.valid.poke(false.B)
      t.until(t.results.size==8);t.drain()
      assert(t.results.last.cancelled && !t.results.last.hit && t.reads.size==reads && t.walks.size==walks)
      info("BANKED_TRANSLATED_SCOPED_CANCEL matchingHiddenRowOnly=true unrelatedRowsSucceed=true laterTagReuse=true heldPTEOwnerDrains=true noCancelledDataIssue=true concurrentAdmissionCancel=true")
    }
  }

}
