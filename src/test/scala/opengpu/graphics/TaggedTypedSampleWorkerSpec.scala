package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable
import java.nio.file.{Files,Paths}

private class TaggedWorkerMacroResources extends BlackBox with HasBlackBoxInline {
  val io=IO(new Bundle {val present=Output(Bool())})
  setInline("TaggedWorkerMacroResources.sv","module TaggedWorkerMacroResources(output present); assign present=1'b1; endmodule\n"+
    Files.readString(Paths.get("depends/asap7_sram_0p0/generated/verilog/srambank_64x4x64_6t122.v")))
}
private class ResidentSampleWorkerHarness(macros: Boolean,virtualWords: Boolean=false) extends ResidentTypedSamplePipeline(useBlackBoxes=macros,virtualWords=virtualWords) {
  val trace=IO(new Bundle {val observed=Vec(8,Valid(new TypedTileWordRequest));val stalls=Output(UInt(3.W))})
  if(macros){val resources=Module(new TaggedWorkerMacroResources);dontTouch(resources.io.present)}
  trace.stalls:=Cat(window.io.work.valid && !worker.io.in.ready,
    VecInit((4 until 8).map(p=>worker.io.request(p).valid && !memory.io.request(p).ready)).asUInt.orR,
    VecInit((0 until 4).map(p=>worker.io.request(p).valid && !memory.io.request(p).ready)).asUInt.orR)
  for(p<-0 until 8){trace.observed(p).valid:=memory.io.request(p).fire;trace.observed(p).bits:=memory.io.request(p).bits}
}

class TaggedTypedSampleWorkerSpec extends AnyFlatSpec {
  import TypedColorReference.{fp,encode}
  private val full=(BigInt(1)<<64)-1
  private val word32=(BigInt(1)<<32)-1
  private val blend=TypedBlendReference.State()
  private case class Packet(tag: Int,index: Int,active: Int=15,channels: Int=15,fault: Boolean=false)
  private class Driver(val d: ResidentSampleWorkerHarness){
    val physical=mutable.Map.empty[Int,BigInt];val expected=mutable.Map.empty[Int,BigInt]
    val virtualOld=mutable.Map.empty[Int,BigInt]
    var cycle=0;var reads=0;var writes=0;var peakReads=0;var peakWrites=0;val stalls=Array.fill(3)(0)
    var pending=mutable.Map.empty[(Int,Int),BigInt]
    d.io.begin.poke(false.B);d.io.cancel.poke(false.B);d.io.maintenance.poke(false.B)
    d.io.in.valid.poke(false.B);d.io.out.ready.poke(false.B)
    d.io.nativeMode.poke(false.B);d.io.bind.valid.poke(false.B);d.io.nativeIn.valid.poke(false.B)
    for(p<-0 until 8){d.io.memoryIn(p).valid.poke(false.B);d.io.memoryIn(p).bits.poke(0.U.asTypeOf(d.io.memoryIn(p).bits));d.io.memoryOut(p).ready.poke(true.B)}
    d.io.virtual.foreach(_.foreach(q=>{q.available.poke(false.B);q.data.poke(0.U)}))
    d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    def tick(): Unit={
      d.io.virtual.foreach(_.foreach { q=>
        val word=q.address.peek().litValue.toInt
        q.available.poke(virtualOld.contains(word).B);q.data.poke(virtualOld.getOrElse(word,BigInt(0)).U)
      })
      var nr=0;var nw=0
      for(p<-0 until 8 if d.trace.observed(p).valid.peek().litToBoolean){
        val r=d.trace.observed(p).bits;val a=r.address.peek().litValue.toInt
        if(r.write.peek().litToBoolean){
          assert(r.mask.peek().litValue==full,"sample worker issued a partial physical write")
          if(a<4096){physical(a)=r.data.peek().litValue;virtualOld.remove(a)};nw+=1
        }else nr+=1
      }
      if(!d.io.maintenance.peek().litToBoolean){
        reads+=nr;writes+=nw;peakReads=peakReads.max(nr);peakWrites=peakWrites.max(nw)
        val blocked=d.trace.stalls.peek().litValue.toInt;for(b<-0 until 3 if (blocked&(1<<b))!=0)stalls(b)+=1
      }
      d.clock.step();cycle+=1
    }
    def source(q: Packet,s: Int): Vector[BigInt]=Vector(fp(((q.tag+s*3)%7+1)/10.0),fp(0.4),fp(0.7),fp(0.5))
    def offer(q: Packet): Unit={
      val b=d.io.in.bits;b.poke(0.U.asTypeOf(b));b.tag.poke(q.tag.U);b.last.poke(true.B)
      b.active.poke(q.active.U);b.colorActive.poke((if(q.channels==0)0 else q.active).U);b.channelMask.poke(q.channels.U)
      b.frontFacing.poke(true.B);b.tests.version.poke(1.U);b.tests.depthEnable.poke(true.B);b.tests.depthWrite.poke(true.B)
      b.tests.depthFunction.poke(6.U);b.tests.stencilEnable.poke(true.B)
      for(f<-Seq(b.tests.front,b.tests.back)){f.function.poke(6.U);f.reference.poke(31.U);f.readMask.poke(255.U);f.writeMask.poke(255.U)
        f.fail.poke(5.U);f.depthFail.poke(5.U);f.depthPass.poke(5.U)}
      TypedBlendReference.poke(b.blend,blend)
      for(p<-0 until 2){b.words(p).poke((if(q.fault && p==0)4096 else 2*q.index+p).U);b.words(p+2).poke((512+2*q.index+p).U)}
      for(s<-0 until 4){b.colors(s).zip(source(q,s)).foreach{case(a,v)=>a.poke(v.U)};b.depths(s).poke(fp(0.25+s*0.125).U)}
      d.io.in.valid.poke(true.B)
    }
    def applyExpected(q: Packet): Unit={
      for(s<-0 until 4 if (q.active&(1<<s))!=0){
        val a=2*q.index+s/2;val shift=32*(s%2);val old=(expected(a)>>shift)&word32
        if(q.channels!=0){
          val packed=encode("RGBA8",TypedBlendReference.blend("RGBA8",source(q,s),old,blend))._1
          val m=(0 until 4).filter(c=>(q.channels&(1<<c))!=0).foldLeft(BigInt(0))((v,c)=>v|(BigInt(255)<<(8*c)))
          val updated=(old&(word32^m))|(packed&m)
          expected(a)=(expected(a)&(full^(word32<<shift)))|(updated<<shift)
        }
        val ds=512+a;val oldDS=(expected(ds)>>shift)&word32
        val z=TypedDepthReference.encode(fp(0.25+s*0.125),4)
        val updated=z|(((oldDS>>24)^255)<<24)
        expected(ds)=(expected(ds)&(full^(word32<<shift)))|(updated<<shift)
      }
    }
    def memory(streams: Vector[Vector[(Int,Boolean,BigInt)]]): Unit={
      d.io.maintenance.poke(true.B);val next=Array.fill(8)(0);var steps=0
      while((next.indices.exists(p=>next(p)<streams(p).size) || pending.nonEmpty) && steps<20000){
        for(p<-0 until 8){
          val in=d.io.memoryIn(p);in.valid.poke((next(p)<streams(p).size).B)
          if(next(p)<streams(p).size){val(a,w,v)=streams(p)(next(p));in.bits.address.poke(a.U);in.bits.write.poke(w.B)
            in.bits.data.poke(v.U);in.bits.mask.poke(full.U);in.bits.tag.poke(next(p).U)}
        }
        for(p<-0 until 8){
          if(next(p)<streams(p).size && d.io.memoryIn(p).ready.peek().litToBoolean){
            val(a,w,v)=streams(p)(next(p));pending((p,next(p)))=if(w)BigInt(0)else physical(a);next(p)+=1
          }
          val out=d.io.memoryOut(p)
          if(out.valid.peek().litToBoolean){val key=(p,out.bits.tag.peek().litValue.toInt)
            out.bits.fault.expect(false.B);out.bits.data.expect(pending.remove(key).get.U)}
        }
        tick();steps+=1
      }
      assert(steps<20000);for(p<-0 until 8)d.io.memoryIn(p).valid.poke(false.B)
      d.io.drained.expect(true.B);d.io.maintenance.poke(false.B)
    }
    def initialize(): Unit={
      val streams=Vector.tabulate(8)(p=>Vector.tabulate(128)(i=>{
        val a=i*8+p;val value=(BigInt(a*17+5)<<32)|BigInt(a*31+7)
        expected(a)=value;(a,true,value)
      }));memory(streams);begin()
    }
    def begin(): Unit={d.io.drained.expect(true.B);d.io.begin.poke(true.B);tick();d.io.begin.poke(false.B)}
    def verify(): Unit=memory(Vector.tabulate(8)(p=>Vector.tabulate(128)(i=>(i*8+p,false,BigInt(0)))))
    def run(qs: Vector[Packet],stalls: Boolean=false,expectedFills: Int=>Int = _=>0): (Int,Int)={
      var sent=0;var received=0;var steps=0;var held: Option[BigInt]=None
      val replies=mutable.ArrayBuffer.empty[Int];val start=cycle;var offered= -1
      while(received<qs.size && steps<qs.size*25+1000){
        if(sent<qs.size){if(offered!=sent){offer(qs(sent));offered=sent}}else d.io.in.valid.poke(false.B)
        val ready= !stalls || steps%9>=4;d.io.out.ready.poke(ready.B)
        held.foreach(v=>{d.io.out.valid.expect(true.B);assert(d.io.out.bits.peek().litValue==v,"held native sample terminal changed")})
        if(d.io.in.valid.peek().litToBoolean && d.io.in.ready.peek().litToBoolean){applyExpected(qs(sent));sent+=1}
        if(d.io.out.valid.peek().litToBoolean){val q=qs(received);val out=d.io.out.bits
          out.tag.expect(q.tag.U);out.packet.expect(0.U);out.last.expect(true.B);out.result.status.expect(0.U)
          out.result.tested.expect(q.active.U);out.result.passing.expect(q.active.U)
          out.result.depthWritten.expect(q.active.U);out.result.stencilWritten.expect(q.active.U)
          out.result.colorWritten.expect((if(q.channels==0)0 else q.active).U)
          out.result.dsReads.expect(Integer.bitCount(q.active).U);out.result.dsFills.expect(expectedFills(received).U)
          if(ready){received+=1;replies+=cycle;held=None}else held=Some(out.peek().litValue)
        }
        tick();steps+=1
      }
      assert(sent==qs.size && received==qs.size,s"resident sample worker stalled $sent/$received at $cycle")
      d.io.in.valid.poke(false.B);d.io.drained.expect(true.B)
      for((a,v)<-expected)assert(physical(a)==v,s"sample image mismatch word=$a actual=${physical(a)} expected=$v")
      val longest=replies.foldLeft((0,0,-2)){case((best,n,last),now)=>val run=if(now==last+1)n+1 else 1;(best.max(run),run,now)}._1
      (cycle-start,longest)
    }
  }
  it should "retain queued maintenance requests and held replies before resident sample reuse" in {
    simulate(new ResidentSampleWorkerHarness(false)){dut=>
      val d=new Driver(dut);d.initialize()
      dut.io.maintenance.poke(true.B);dut.io.memoryOut.foreach(_.ready.poke(false.B))
      val sent=Array.fill(8)(0);var cycles=0
      def value(p: Int,n: Int): BigInt=BigInt("ac01000000000000",16)+p*16+n
      while(sent.exists(_<2) && cycles<100){
        for(p<-0 until 8){val request=dut.io.memoryIn(p);val n=sent(p)
          request.valid.poke((n<2).B)
          request.bits.address.poke((64+p*2+n).U);request.bits.write.poke(true.B)
          request.bits.data.poke(value(p,n).U);request.bits.mask.poke(full.U);request.bits.tag.poke((900+p*2+n).U)
          if(n<2 && request.ready.peek().litToBoolean){d.expected(64+p*2+n)=value(p,n);sent(p)+=1}}
        d.tick();cycles+=1
      }
      assert(sent.forall(_==2));dut.io.memoryIn.foreach(_.valid.poke(false.B));dut.io.drained.expect(false.B)
      while(!dut.io.memoryOut.forall(_.valid.peek().litToBoolean) && cycles<200){d.tick();cycles+=1}
      assert(cycles<200)
      val held=dut.io.memoryOut.map(_.bits.peek().litValue)
      for(_<-0 until 7){
        dut.io.drained.expect(false.B)
        for(p<-0 until 8){dut.io.memoryOut(p).valid.expect(true.B);assert(dut.io.memoryOut(p).bits.peek().litValue==held(p))}
        d.tick()
      }
      val replies=Array.fill(8)(mutable.Set.empty[Int]);dut.io.memoryOut.foreach(_.ready.poke(true.B))
      while(replies.exists(_.size<2) && cycles<300){
        for(p<-0 until 8 if dut.io.memoryOut(p).valid.peek().litToBoolean){
          val tag=dut.io.memoryOut(p).bits.tag.peek().litValue.toInt
          assert(tag>=900+p*2 && tag<902+p*2 && replies(p).add(tag),"maintenance reply lost or duplicated its accepted owner")
          dut.io.memoryOut(p).bits.fault.expect(false.B)}
        d.tick();cycles+=1
      }
      assert(replies.forall(_.size==2));dut.io.drained.expect(true.B)
      dut.io.maintenance.poke(false.B);d.tick();d.begin()
      d.run(Vector.tabulate(16)(i=>Packet(8000+i,i%8)));d.verify()
      info("RESIDENT_MAINTENANCE queuedRequests=16 heldReplies=8 stableOffers=true completeDrain=true exactImage=true healthySampleReuse=true")
    }
  }
  it should "merge virtual clear words with updates and initialize read-only DS words once through real write ACKs" in {
    simulate(new ResidentSampleWorkerHarness(false,virtualWords=true)){dut=>
      val d=new Driver(dut);d.initialize()
      val clearDS=(BigInt("5afffffe",16)<<32)|BigInt("5afffffe",16)
      for(a<-Seq(516,517)){d.virtualOld(a)=clearDS;d.expected(a)=clearDS}
      def readOnly(tag: Int,index: Int,mask: Int,cancel: Boolean=false): Unit={
        val beforeWrites=d.writes
        d.offer(Packet(tag,index,active=mask,channels=0))
        dut.io.in.bits.tests.depthWrite.poke(false.B);dut.io.in.bits.tests.stencilEnable.poke(false.B)
        var steps=0
        while(!dut.io.in.ready.peek().litToBoolean && steps<100){d.tick();steps+=1}
        assert(steps<100);d.tick();dut.io.in.valid.poke(false.B)
        if(cancel){for(_<-0 until 10)d.tick();dut.io.cancel.poke(true.B);d.tick();dut.io.cancel.poke(false.B)}
        dut.io.out.ready.poke(false.B)
        while(!dut.io.out.valid.peek().litToBoolean && steps<300){d.tick();steps+=1}
        assert(steps<300);val result=dut.io.out.bits.result
        result.status.expect((if(cancel)4 else 0).U)
        result.colorWritten.expect(0.U);result.depthWritten.expect(0.U);result.stencilWritten.expect(0.U)
        result.dsFills.expect((if(cancel || tag==7001)0 else 2).U)
        if(!cancel){result.tested.expect(mask.U);result.passing.expect(mask.U);result.dsReads.expect(Integer.bitCount(mask).U)}
        val held=dut.io.out.bits.peek().litValue
        for(_<-0 until 5){d.tick();assert(dut.io.out.bits.peek().litValue==held,"held virtual clear terminal changed")}
        dut.io.out.ready.poke(true.B);d.tick();dut.io.drained.expect(true.B)
        assert(d.writes-beforeWrites==(if(cancel || tag==7001)0 else 2),"read-only clear used duplicate initialization writes")
      }
      val beforeReads=d.reads
      readOnly(7000,2,5);assert(d.reads==beforeReads,"virtual DS clear issued a redundant physical read")
      readOnly(7001,2,5);assert(d.reads-beforeReads==2,"resident read-only alias lost its two physical reads")
      for(a<-Seq(0,1,2,3,512,513,514,515)){
        val v=if(a<512)BigInt("8040201080402010",16)else clearDS
        d.virtualOld(a)=v;d.expected(a)=v
      }
      val reads=d.reads;val writes=d.writes
      d.run(Vector.tabulate(32)(i=>Packet(7100+i,i%2,channels=if(i%2==0)15 else 5)),stalls=true,
        expectedFills=i=>if(i<2)2 else 0)
      assert(d.reads-reads==30*4,"clear/update path reread virtual words")
      assert(d.writes-writes==32*4,"clear/update path performed a separate prefill write")
      assert(d.virtualOld.isEmpty);d.verify()
      // Cancellation after virtual reads suppresses both initialization and
      // logical writes; an unmaterialized word remains available to its parent.
      d.virtualOld(520)=clearDS;d.virtualOld(521)=clearDS
      readOnly(7200,4,5,cancel=true)
      assert(d.virtualOld.keySet==Set(520,521));d.verify();d.begin()
      d.virtualOld.clear();d.run(Vector(Packet(7300,4)));d.verify()
      info("VIRTUAL_SAMPLE_WORDS readOnlyInitOnce=true coalescedClearUpdate=true actualACKs=true aliasOrder=true heldOutput=true cancelNoInit=true healthyRebind=true")
    }
  }

  for(macros<-Seq(false,true)){
    it should s"commit tagged sample streams through real eight-port SRAM and preserve ordered aliases macros=$macros" in {
      simulate(new ResidentSampleWorkerHarness(macros)){dut=>
        val d=new Driver(dut);d.initialize()
        val (cycles,longest)=d.run(Vector.tabulate(768)(i=>Packet(i,i%256)));val denseStalls=d.stalls.toVector
        assert(longest==768,"resident sample stream failed four completed updates per clock")
        d.run(Vector.tabulate(128)(i=>Packet(1000+i,(i*13)%16,i%16,(i*7)%16)),stalls=true)
        d.verify()
        info(s"TAGGED_SAMPLE_WORKER macros=$macros densePackets=768 samples=3072 cycles=$cycles longestConsecutiveRetirements=$longest peakReads=${d.peakReads} peakWrites=${d.peakWrites} exactImage=true aliasOrder=true actualWriteACKs=true readBankStalls=${denseStalls(0)} writeBankStalls=${denseStalls(1)} readContextStalls=${denseStalls(2)}")
      }
    }
  }

  it should "drain already offered writes after sticky cancellation and safely rebind all packet owners" in {
    simulate(new ResidentSampleWorkerHarness(false)){dut=>
      val d=new Driver(dut);d.initialize();var sent=0;var steps=0
      while(sent<64 && steps<2000){
        d.offer(Packet(3000+sent,sent));if(dut.io.in.ready.peek().litToBoolean)sent+=1
        d.tick();steps+=1
      }
      assert(sent==64);dut.io.in.valid.poke(false.B);dut.io.out.valid.expect(true.B)
      val held=dut.io.out.bits.peek().litValue;val before=d.writes
      dut.io.cancel.poke(true.B)
      for(_<-0 until 4){d.tick();assert(dut.io.out.bits.peek().litValue==held,"cancel changed a held native terminal")}
      dut.io.cancel.poke(false.B);var extra=0;var retired=0;var cancelled=0
      var heldOut: Option[BigInt]=None
      while((retired<72 || extra<8) && steps<5000){
        if(extra<8)d.offer(Packet(3064+extra,extra%4))else dut.io.in.valid.poke(false.B)
        val ready=steps%3!=0;dut.io.out.ready.poke(ready.B)
        heldOut.foreach(v=>{dut.io.out.valid.expect(true.B);assert(dut.io.out.bits.peek().litValue==v)})
        if(dut.io.in.valid.peek().litToBoolean && dut.io.in.ready.peek().litToBoolean)extra+=1
        if(dut.io.out.valid.peek().litToBoolean){
          dut.io.out.bits.tag.expect((3000+retired).U)
          val status=dut.io.out.bits.result.status.peek().litValue.toInt;assert(status==0 || status==4)
          if(ready){retired+=1;if(status==4)cancelled+=1;heldOut=None}else heldOut=Some(dut.io.out.bits.peek().litValue)
        }
        d.tick();steps+=1
      }
      assert(retired==72 && extra==8 && cancelled>0);dut.io.in.valid.poke(false.B);dut.io.drained.expect(true.B)
      val postCancel=d.writes-before;assert(postCancel<=8,"cancellation formed writes beyond the already offered request queues")
      d.verify();d.expected.clear();d.expected++=d.physical;d.begin()
      d.run(Vector.tabulate(32)(i=>Packet(4000+i,i%8)));d.verify()
      info(s"TAGGED_SAMPLE_CANCEL retired=$retired cancelled=$cancelled postCancelOfferedWrites=$postCancel heldNativeTerminal=true internalPrefixDrain=true healthyReuse=true")
    }
  }
  it should "report the original read fault only after owned replies drain and recover the shared SRAM owner" in {
    simulate(new ResidentSampleWorkerHarness(false)){dut=>
      val d=new Driver(dut);d.initialize();var sent=0;var received=0;var steps=0
      while(received<8 && steps<2000){
        if(sent<8)d.offer(Packet(5000+sent,sent,fault=sent==0))else dut.io.in.valid.poke(false.B)
        dut.io.out.ready.poke(true.B)
        if(dut.io.in.valid.peek().litToBoolean && dut.io.in.ready.peek().litToBoolean)sent+=1
        if(dut.io.out.valid.peek().litToBoolean){
          dut.io.out.bits.tag.expect((5000+received).U)
          dut.io.out.bits.result.status.expect((if(received==0)3 else 4).U)
          if(received==0)dut.io.out.bits.result.faultOffset.expect(32768.U)
          dut.io.out.bits.result.colorWritten.expect(0.U);dut.io.out.bits.result.depthWritten.expect(0.U)
          dut.io.out.bits.result.stencilWritten.expect(0.U);received+=1
        }
        d.tick();steps+=1
      }
      assert(sent==8 && received==8 && d.writes==0);dut.io.in.valid.poke(false.B)
      dut.io.drained.expect(true.B);dut.io.fault.expect(true.B);d.verify();d.begin();dut.io.fault.expect(false.B)
      d.run(Vector.tabulate(16)(i=>Packet(6000+i,i%8)));d.verify()
      info("TAGGED_SAMPLE_FAULT readFault=true originalOffset=true noWrites=true replyDrain=true healthyRebind=true exactImage=true")
    }
  }

  for(virtualWords<-Seq(false,true)){
  it should s"expand actual native quads into exact resident sample updates at every MSAA mode virtual=$virtualWords" in {
    simulate(new ResidentSampleWorkerHarness(false,virtualWords=virtualWords)){dut=>
      val d=new Driver(dut);d.initialize();dut.io.nativeMode.poke(true.B)
      for(mode<-0 to 2;padded<-Seq(false,true)){
        val colorBase=if(padded)512 else 128<<mode;val baseTag=mode*2000+(if(padded)1000 else 0)
        val descriptor=dut.io.bind.bits;descriptor.poke(0.U.asTypeOf(descriptor))
        descriptor.version.poke(1.U);descriptor.colors.version.poke(1.U)
        descriptor.colors.tileLog2.poke(4.U);descriptor.colors.tileWidth.poke(16.U);descriptor.colors.tileHeight.poke(16.U)
        descriptor.colors.sampleMode.poke(mode.U);descriptor.colors.reservedBytes.poke((colorBase*8).U)
        val color=descriptor.colors.colors(0);color.enabled.poke(true.B);color.format.poke(TypedColorFormat.id("RGBA8").U)
        color.offset.poke((colorBase*8).U);color.channelMask.poke(15.U);color.loadOp.poke(0.U)
        descriptor.depth.format.poke(TypedDepthFormat.d24s8.U);descriptor.depth.loadOp.poke(0.U)
        dut.io.bind.valid.poke(true.B);var wait=0
        while(!dut.io.bind.ready.peek().litToBoolean && wait<30){d.tick();wait+=1}
        assert(wait<30);d.tick();dut.io.bind.valid.poke(false.B);dut.io.supported.expect(true.B)
        def coordinates(i: Int,l: Int): (Int,Int)=(((i%8)*2+l%2),(((i/8)%8)*2+l/2))
        def z(l: Int,sample: Int): BigInt=fp(0.25+l*0.03125+sample*0.125)
        def applyQuad(i: Int): Unit={
          val tag=baseTag+i
          for(l<-0 until 4;sample<-0 until(1<<mode)){
            val(x,y)=coordinates(i,l);val index=((y*16+x)<<mode)+sample;val shift=(index&1)*32
            val a=colorBase+(index>>1);val ds=index>>1;val old=(d.expected(a)>>shift)&word32
            val src=d.source(Packet(tag,0),l)
            val packed=encode("RGBA8",TypedBlendReference.blend("RGBA8",src,old,blend))._1
            d.expected(a)=(d.expected(a)&(full^(word32<<shift)))|(packed<<shift)
            val oldDS=(d.expected(ds)>>shift)&word32
            val updated=TypedDepthReference.encode(z(l,sample),4)|(((oldDS>>24)^255)<<24)
            d.expected(ds)=(d.expected(ds)&(full^(word32<<shift)))|(updated<<shift)
          }
        }
        def offerQuad(i: Int): Unit={
          val b=dut.io.nativeIn.bits;b.poke(0.U.asTypeOf(b));val tag=baseTag+i;b.tag.poke(tag.U)
          val q=b.quad.color.quad;q.sampleMode.poke(mode.U);q.colorSlots.poke(1.U);q.frontFacing.poke((i%2==0).B)
          TypedBlendReference.poke(b.quad.color.blend,blend);val tests=b.quad.state
          tests.version.poke(1.U);tests.depthEnable.poke(true.B);tests.depthWrite.poke(true.B);tests.depthFunction.poke(6.U);tests.stencilEnable.poke(true.B)
          for(f<-Seq(tests.front,tests.back)){f.function.poke(6.U);f.reference.poke(31.U);f.readMask.poke(255.U);f.writeMask.poke(255.U)
            f.fail.poke(5.U);f.depthFail.poke(5.U);f.depthPass.poke(5.U)}
          for(l<-0 until 4){
            val(x,y)=coordinates(i,l);val pixel=q.lanes(l);pixel.x.poke(x.S);pixel.y.poke(y.S)
            pixel.colorMask.poke(1.U);pixel.coverageMask.poke(((1<<(1<<mode))-1).U)
            pixel.depth.poke(z(l,0).U);pixel.sampleDepthValid.poke(true.B)
            for(sample<-0 until 4)pixel.sampleDepth(sample).poke(z(l,sample).U)
            pixel.colors(0).zip(d.source(Packet(tag,0),l)).foreach{case(a,v)=>a.poke(v.U)}
            b.quad.color.samples(l).poke(((1<<(1<<mode))-1).U)
          }
          dut.io.nativeIn.valid.poke(true.B)
        }
        var sent=0;var received=0;var steps=0;var held: Option[BigInt]=None;var offered= -1
        val retirements=mutable.ArrayBuffer.empty[Int]
        val packets=128<<mode;val before=d.cycle;val blocked=d.stalls.toVector
        while(received<packets && steps<packets*25+1000){
          if(sent<128){if(offered!=sent){offerQuad(sent);offered=sent}}else dut.io.nativeIn.valid.poke(false.B)
          val ready= !padded || steps%9>=2;dut.io.out.ready.poke(ready.B)
          held.foreach(v=>{dut.io.out.valid.expect(true.B);assert(dut.io.out.bits.peek().litValue==v)})
          if(dut.io.nativeIn.valid.peek().litToBoolean && dut.io.nativeIn.ready.peek().litToBoolean){applyQuad(sent);sent+=1}
          if(dut.io.out.valid.peek().litToBoolean){
            val out=dut.io.out.bits;out.tag.expect((baseTag+(received>>mode)).U)
            out.packet.expect((received&((1<<mode)-1)).U);out.last.expect(((received&((1<<mode)-1))==((1<<mode)-1)).B)
            out.result.status.expect(0.U);out.result.tested.expect(15.U);out.result.passing.expect(15.U)
            out.result.colorWritten.expect(15.U);out.result.depthWritten.expect(15.U);out.result.stencilWritten.expect(15.U)
            out.result.dsReads.expect(4.U)
            if(ready){received+=1;retirements+=d.cycle;held=None}else held=Some(out.peek().litValue)
          }
          d.tick();steps+=1
        }
        assert(sent==128 && received==packets);dut.io.nativeIn.valid.poke(false.B);dut.io.drained.expect(true.B)
        for((a,v)<-d.expected)assert(d.physical(a)==v,s"native MSAA mode=$mode mismatched word=$a")
        val elapsed=d.cycle-before;val bankStalls=d.stalls(0)-blocked(0)
        val longest=retirements.foldLeft((0,0,-2)){case((best,n,last),now)=>val run=if(now==last+1)n+1 else 1;(best.max(run),run,now)}._1
        if(!padded)assert(longest==packets,s"native MSAA mode=$mode failed sustained four-sample retirement")
        d.verify()
        info(s"NATIVE_RESIDENT_SAMPLES mode=$mode padded=$padded quads=128 packets=$packets samples=${packets*4} cycles=$elapsed longestConsecutiveRetirements=$longest readBankStalls=$bankStalls exactImage=true sampleDepths=true heldPacketRetirement=true actualWriteACKs=true")
      }
    }
  }
  }
}
