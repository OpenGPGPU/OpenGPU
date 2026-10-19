package opengpu.graphics

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class TypedTileColorStoreSpec extends AnyFlatSpec {
  import TypedColorReference._
  private val p=TypedTileColorConfig()
  private case class View(name: String,offset: Int,mask: Int=15,load: Int=1,clear: Vector[BigInt]=Vector(fp(0.25),fp(0.5),fp(0.75),fp(1)))
  private def zero(d: Data): Unit=d match{
    case r: Record=>r.elements.values.foreach(zero)
    case v: Vec[_]=>v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case _=>()
  }
  private class Driver(val d: TypedTileColorStore){
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    var cycle=0;var mode=2;var tileLog=3;var views=Vector.empty[View];var blockRefill=false;var refillFault=false
    var pending: Option[(Int,BigInt,Boolean)]=None;var responseHeld: Option[(BigInt,Boolean)]=None
    val backing=mutable.Map.empty[Int,Int];val model=mutable.Map.empty[Int,Int];val touchedWords=mutable.Set.empty[Int]
    var refillCount=0;val refillAddresses=mutable.ArrayBuffer.empty[Int]
    def word(address: Int): BigInt=(0 until 8).foldLeft(BigInt(0))((x,b)=>x|(BigInt(model.getOrElse(address*8+b,0))<<(8*b)))
    def tick(): Unit={
      d.io.refill.ready.poke((!blockRefill && cycle%3!=0).B)
      if(responseHeld.isEmpty && pending.exists(_._1<=cycle)){responseHeld=pending.map(x=>(x._2,x._3));pending=None}
      d.io.refillReply.valid.poke(responseHeld.nonEmpty.B)
      responseHeld.foreach{case(data,fault)=>d.io.refillReply.bits.data.poke(data.U);d.io.refillReply.bits.fault.poke(fault.B)}
      if(responseHeld.nonEmpty && d.io.refillReply.ready.peek().litToBoolean)responseHeld=None
      if(d.io.refill.valid.peek().litToBoolean && d.io.refill.ready.peek().litToBoolean){
        val a=d.io.refill.bits.address.peek().litValue.toInt;assert(pending.isEmpty && responseHeld.isEmpty)
        val data=(0 until 8).foldLeft(BigInt(0))((v,b)=>v|(BigInt(backing.getOrElse(a*8+b,0))<<(8*b)))
        pending=Some((cycle+4,data,refillFault));refillCount+=1;refillAddresses+=a
      }
      d.clock.step();cycle+=1
    }
    def until(condition: => Boolean): Unit={var n=0;while(!condition && n<10000){tick();n+=1};assert(condition,s"typed tile stalled cycle=$cycle refills=$refillCount")}
    def identity(q: GraphicsWorkIdentity): Unit={q.context.poke(7.U);q.pass.poke(8.U);q.generation.poke(9.U)}
    def prepare(v: Vector[View],m: Int=2,log: Int=3,reserved: Int=64): Unit={
      views=v;mode=m;tileLog=log;model.clear();backing.clear();touchedWords.clear()
      val b=d.io.begin.bits;zero(b);b.version.poke(1.U);identity(b.identity);b.tileLog2.poke(log.U)
      b.tileWidth.poke((1<<log).U);b.tileHeight.poke((1<<log).U);b.sampleMode.poke(m.U);b.reservedBytes.poke(reserved.U)
      for((view,i)<-v.zipWithIndex){val c=b.colors(i);c.enabled.poke(true.B);c.format.poke(TypedColorFormat.id(view.name).U)
        c.offset.poke(view.offset.U);c.channelMask.poke(view.mask.U);c.loadOp.poke(view.load.U);c.clear.zip(view.clear).foreach{case(x,y)=>x.poke(y.U)}
        val f=format(view.name);val clear=encode(view.name,view.clear)._1;val span=(1<<(2*log+m))*f.bytes
        for(n<-0 until span){val value=if(view.load==0)(view.offset+n*73+19)&255 else if(view.load==2)0 else ((clear>>(8*(n%f.bytes)))&255).toInt
          model(view.offset+n)=value;backing(view.offset+n)=value}
      }
      for(n<-0 until reserved)model(n)=0x5a
    }
    def begin(): Unit={d.io.begin.valid.poke(true.B);until(d.io.begin.ready.peek().litToBoolean);tick();d.io.begin.valid.poke(false.B)
      until(d.io.in.ready.peek().litToBoolean || d.io.completion.valid.peek().litToBoolean)}
    def maintenance(address: Int,write: Boolean=false,data: BigInt=0,mask: Int=255): (BigInt,Boolean,Boolean,Boolean)={
      d.io.maintenance.poke(true.B);d.io.memory.bits.address.poke(address.U);d.io.memory.bits.write.poke(write.B)
      d.io.memory.bits.data.poke(data.U);d.io.memory.bits.byteMask.poke(mask.U);d.io.memory.valid.poke(true.B)
      until(d.io.memory.ready.peek().litToBoolean);tick();d.io.memory.valid.poke(false.B);until(d.io.reply.valid.peek().litToBoolean)
      val r=d.io.reply.bits;val result=(r.data.peek().litValue,r.available.peek().litToBoolean,r.dirty.peek().litToBoolean,r.fault.peek().litToBoolean)
      val held=r.peek().litValue;for(_<-0 until 2){tick();assert(r.peek().litValue==held)}
      d.io.reply.ready.poke(true.B);tick();d.io.reply.ready.poke(false.B);d.io.maintenance.poke(false.B);result
    }
    private def rgba(view: View,seed: Int,lane: Int): Vector[BigInt]={
      val f=format(view.name)
      if(f.kind==0)Vector(fp((seed+lane+1)*0.13),fp(0.5),if(seed%2==0)BigInt("7fc01234",16)else fp(1.5),fp(0.7))
      else Vector(BigInt("ffffffff",16)-seed,BigInt(0x80000000L+lane),BigInt(123456+seed*17+lane),BigInt(seed+lane))
    }
    def packet(seed: Int,allHelpers: Boolean=false,x0: Option[Int]=None,y0: Int=0,allVisible: Boolean=false): (Int,Int,Int,Int)={
      val b=d.io.in.bits;zero(b);val q=b.quad;identity(q.identity);q.sequence.poke((seed+100).U);q.primitiveId.poke((seed+20).U)
      q.instanceId.poke(2.U);q.partIndex.poke((seed%7).U);q.colorSlots.poke(views.size.U);q.sampleMode.poke(mode.U)
      for((v,i)<-views.zipWithIndex)q.colorTypes(i).poke(format(v.name).kind.U)
      val max=(1<<(1<<mode))-1;var wordWrites=0;var writtenBits=0;var locationWrites=0;var samples=0
      for(l<-0 until 4){val r=q.lanes(l);val x=x0.getOrElse((seed%3)*2)+l%2;val y=y0+l/2
        val coverage=if(allHelpers || (l==3 && !allVisible))0 else max
        val selected=if(allVisible)coverage else if(l==0)coverage else if(l==1)1&coverage else 0
        val locations=if(coverage==0)0 else (if(seed%2==0)(1<<views.size)-1 else 5&((1<<views.size)-1))
        r.x.poke(x.S);r.y.poke(y.S);r.helper.poke((coverage==0).B);r.coverageMask.poke(coverage.U);r.colorMask.poke(locations.U);b.samples(l).poke(selected.U)
        for((v,c)<-views.zipWithIndex){val values=rgba(v,seed,l);r.colors(c).zip(values).foreach{case(a,z)=>a.poke(z.U)}
          val(data,mask)=encode(v.name,values,v.mask);val f=format(v.name)
          if((locations&(1<<c))!=0 && mask!=0)for(s<-0 until (1<<mode) if (selected&(1<<s))!=0){
            val byteAddress=v.offset+(((y<<tileLog)+x)*(1<<mode)+s)*f.bytes
            val shift=8*(byteAddress%8);val base=byteAddress/8
            for(w<-0 until ((byteAddress%8+f.bytes+7)/8)){val m=((mask<<shift)>>(64*w))&((BigInt(1)<<64)-1)
              if(m!=0){val a=base+w;val old=word(a);val next=(old& ~m)|(((data<<shift)>>(64*w))&m)
                for(z<-0 until 8)model(a*8+z)=((next>>(8*z))&255).toInt
                touchedWords+=a;wordWrites+=1;writtenBits+=m.bitCount}}
            locationWrites+=1
          }
        }
        if(selected!=0 && views.indices.exists(c=>(locations&(1<<c))!=0 && encode(views(c).name,rgba(views(c),seed,l),views(c).mask)._2!=0))samples+=Integer.bitCount(selected)
      }
      (wordWrites,writtenBits,locationWrites,samples)
    }
    def submit(): Unit={d.io.in.valid.poke(true.B);until(d.io.in.ready.peek().litToBoolean);tick();d.io.in.valid.poke(false.B)}
    def output(status: Int=0,expected: Option[(Int,Int,Int,Int)]=None): Unit={
      until(d.io.out.valid.peek().litToBoolean);val r=d.io.out.bits;r.status.expect(status.U)
      expected.foreach{case(w,b,l,s)=>r.wordWrites.expect(w.U);r.writtenBits.expect(b.U);r.completedLocations.expect(l.U);r.touchedSamples.expect(s.U)}
      val held=r.peek().litValue;for(_<-0 until 3){tick();assert(r.peek().litValue==held);d.io.in.ready.expect(false.B)}
      d.io.out.ready.poke(true.B);tick();d.io.out.ready.poke(false.B)
    }
    def verify(): Unit={
      val extra=views.flatMap{v=>val span=(1<<(2*tileLog+mode))*format(v.name).bytes;Vector(v.offset/8,v.offset/8+1,(v.offset+span)/8-1)}
      for(a<-(touchedWords.toVector++extra).distinct.sorted){val(data,available,dirty,fault)=maintenance(a)
        assert(!fault && (available || views.exists(v=>v.load==0 && a*8>=v.offset && a*8<v.offset+(1<<(2*tileLog+mode))*format(v.name).bytes)))
        if(available)assert(data==word(a),s"tile word$a differs observed=${data.toString(16)} expected=${word(a).toString(16)}")
        if(touchedWords(a))assert(dirty)}
    }
    def finish(status: Int=0,automatic: Boolean=false): Unit={
      if(!automatic){d.io.end.valid.poke(true.B);d.io.end.bits.poke(true.B);until(d.io.end.ready.peek().litToBoolean);tick();d.io.end.valid.poke(false.B)}
      until(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.status.expect(status.U)
      val held=d.io.completion.bits.peek().litValue;for(_<-0 until 4){tick();assert(d.io.completion.bits.peek().litValue==held);d.io.begin.ready.expect(false.B)}
      assert(pending.isEmpty && responseHeld.isEmpty);d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B);d.io.busy.expect(false.B)
    }
  }

  it should "store all36 typed formats with four MRT views, clear tags, channel masks and visibility-qualified MSAA samples" in {
    simulate(new TypedTileColorStore()){d=>val t=new Driver(d);var total=0
      for((group,index)<-names.grouped(4).zipWithIndex){var offset=64
        val views=group.zipWithIndex.map{case(name,i)=>val v=View(name,offset,Vector(15,5,10,8)(i));offset+=64*4*format(name).bytes;v}.toVector
        t.prepare(views);t.begin();d.io.begin.bits.colors(0).format.poke(63.U)
        // Protected depth/stencil prefix shares the same resident slot.
        t.maintenance(0,true,BigInt("5a5a5a5a5a5a5a5a",16))
        for(seed<-0 until 3){val expected=t.packet(seed);t.submit();d.io.in.bits.quad.lanes(0).colors(0)(0).poke(0.U);t.output(expected=Some(expected));total+=1}
        val empty=t.packet(3,true);t.submit();t.output(expected=Some(empty));t.verify()
        assert(t.maintenance(0)._1==BigInt("5a5a5a5a5a5a5a5a",16));t.finish()
      }
      info(s"TYPED_TILE formats=36 groups=9 MRT=4 sampleMode=4x quads=$total packedNeighbors=true componentMasks=true helperSuppression=true lateVisibilityMasks=true reservedDepthPrefix=true clearTags=true heldOutputs=true snapshots=true")
    }
  }

  it should "refill load-start words once, preserve resident updates and support discard and partial maintenance" in {
    simulate(new TypedTileColorStore()){d=>val t=new Driver(d)
      t.prepare(Vector(View("RGB8",64,7,0),View("RGBA32UI",832,15,2)),m=1);t.begin()
      val expected=t.packet(0);t.submit();t.output(expected=Some(expected));assert(t.refillCount>0)
      val first=t.refillCount;val again=t.packet(0);t.submit();t.output(expected=Some(again));assert(t.refillCount==first)
      t.verify();val a=64/8;val old=t.word(a);val(data,_,_,fault)=t.maintenance(a,true,BigInt("aa",16),1);assert(!fault)
      t.model(a*8)=0xaa;assert(t.maintenance(a)._1==((old& ~BigInt(255))|0xaa));t.finish()
      info("TYPED_TILE loadRefill=true residentReuse=true discardZero=true partialMaintenance=true taggedWordReplies=true noRepeatedRefill=true")
    }
  }

  it should "reject invalid layouts and quads before updates and enforce the32KiB slot budget" in {
    simulate(new TypedTileColorStore()){d=>val t=new Driver(d)
      val bad=Seq[()=>Unit](()=>d.io.begin.bits.version.poke(0.U),()=>d.io.begin.bits.tileLog2.poke(2.U),()=>d.io.begin.bits.sampleMode.poke(3.U),
        ()=>d.io.begin.bits.colors(0).format.poke(63.U),()=>d.io.begin.bits.colors(0).offset.poke(65.U),()=>d.io.begin.bits.colors(0).loadOp.poke(3.U),
        ()=>d.io.begin.bits.reservedBytes.poke(128.U),()=>d.io.begin.bits.tileWidth.poke(9.U))
      for(change<-bad){t.prepare(Vector(View("RGBA8",64)),m=0);change();t.begin();t.finish(1,true)}
      t.prepare(Vector(View("RGBA8",64),View("R32UI",64)),m=0);t.begin();t.finish(1,true)
      t.prepare(Vector.tabulate(4)(i=>View("RGBA16F",4096+i*8192)),log=4,reserved=4096);t.begin();t.finish(1,true)
      t.prepare(Vector.tabulate(4)(i=>View("RGBA16F",1024+i*2048)),log=3,reserved=1024);t.begin();t.finish()
      val malformed=Seq[()=>Unit](()=>d.io.in.bits.quad.identity.pass.poke(99.U),()=>d.io.in.bits.quad.colorTypes(0).poke(1.U),
        ()=>d.io.in.bits.quad.lanes(0).x.poke(1.S),()=>d.io.in.bits.samples(3).poke(1.U),()=>d.io.in.bits.quad.lanes(1).x.poke(8.S),()=>{for(l<-0 until 4)d.io.in.bits.quad.lanes(l).x.poke((8+l%2).S)})
      for(change<-malformed){t.prepare(Vector(View("RGBA8",64)),m=0);t.begin();t.packet(0);change();t.submit();t.output(6)
        t.until(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.wordWrites.expect(0.U);t.finish(6,true)}
      info("TYPED_TILE invalidDescriptors=10 invalidQuads=6 noPartialInvalidUpdates=true slotBudget32768=true smallerTileFits=true newPassReuse=true")
    }
  }

  it should "drain faulted and cancelled refill offers and retain an already offered quad completion" in {
    simulate(new TypedTileColorStore()){d=>val t=new Driver(d)
      t.prepare(Vector(View("RGBA32F",64,15,0)),m=0);t.begin();t.refillFault=true;t.packet(0);t.submit();t.output(3)
      t.finish(3,true);t.refillFault=false
      t.prepare(Vector(View("RGBA32F",64,15,0)),m=0);t.begin();t.blockRefill=true;t.packet(0);t.submit()
      t.until(d.io.refill.valid.peek().litToBoolean);val held=d.io.refill.bits.peek().litValue;d.io.cancel.poke(true.B)
      for(_<-0 until 7){t.tick();d.io.refill.valid.expect(true.B);assert(d.io.refill.bits.peek().litValue==held);d.io.completion.valid.expect(false.B)}
      d.io.cancel.poke(false.B);t.blockRefill=false;t.until(d.io.out.valid.peek().litToBoolean);d.io.out.bits.wordWrites.expect(0.U);t.output(4);t.finish(4,true)
      // Cancel between the two words of one RGBA32 record: retain/count only the committed prefix.
      t.prepare(Vector(View("RGBA32F",64,15,0)),m=0);t.begin();t.packet(0);t.submit()
      t.until(d.io.refill.valid.peek().litToBoolean && d.io.refill.bits.address.peek().litValue==9)
      t.blockRefill=true;d.io.cancel.poke(true.B)
      for(_<-0 until 7){t.tick();d.io.refill.valid.expect(true.B);d.io.refill.bits.address.expect(9.U)}
      d.io.cancel.poke(false.B);t.blockRefill=false;t.output(4,Some((1,64,0,1)));t.finish(4,true)
      t.prepare(Vector(View("SRGB8_ALPHA8",64)),m=0);t.begin();val expected=t.packet(0);t.submit()
      t.until(d.io.out.valid.peek().litToBoolean);val offered=d.io.out.bits.peek().litValue;d.io.cancel.poke(true.B)
      for(_<-0 until 7){t.tick();assert(d.io.out.bits.peek().litValue==offered);d.io.completion.valid.expect(false.B)}
      t.output(expected=Some(expected));d.io.cancel.poke(false.B);t.finish(4,true)
      t.prepare(Vector(View("RGBA8",64)),m=0);t.begin();val healthy=t.packet(0)
      d.io.end.valid.poke(true.B);d.io.end.bits.poke(true.B);d.io.in.valid.poke(true.B)
      d.io.in.ready.expect(true.B);d.io.end.ready.expect(false.B);t.tick();d.io.in.valid.poke(false.B)
      t.output(expected=Some(healthy));t.finish()
      info("TYPED_TILE refillFault=true heldRefillCancel=true noCancelledRefillCommit=true partialPrefixWords=1 partialPrefixBits=64 heldQuadCompletion=true cancellationLatched=true simultaneousLastInputEnd=true healthyReuse=true")
    }
  }

  for(bytes<-Seq(1024,65536))
  it should s"support the advertised slot capacity boundary bytes=$bytes" in {
    simulate(new TypedTileColorStore(TypedTileColorConfig(bytes))){d=>val t=new Driver(d)
      val max=bytes==65536
      t.prepare(if(max)Vector.tabulate(4)(i=>View("RGBA32F",i*16384))else Vector(View("RGBA32F",0)),m=if(max)2 else 0,log=if(max)4 else 3,reserved=0)
      t.begin();val edge=if(max)16 else 8
      val expected=t.packet(0,x0=Some(edge-2),y0=edge-2,allVisible=true)
      if(max)assert(expected==(128,8192,64,16))
      t.submit();t.output(expected=Some(expected));t.verify();t.finish()
      info(s"TYPED_TILE capacityBoundary=$bytes allRGBA32=true endExclusive=true macroMinimum=true highestWordWritten=true maxCounters=true exactStoredWords=true")
    }
  }
}
