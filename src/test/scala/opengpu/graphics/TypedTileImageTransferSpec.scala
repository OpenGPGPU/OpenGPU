package opengpu.graphics

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class TypedTileImageTransferSpec extends AnyFlatSpec {
  import TypedColorReference.{fp,format,encode}
  private def zero(x: Data): Unit=x match {
    case r: Record=>r.elements.values.foreach(zero);case v: Vec[_]=>v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U);case _=>()
  }
  private case class View(index: Int,offset: Int,bytes: Int,load: Int,clear: BigInt,base: BigInt,pitch: Int,width: Int=13,height: Int=9,store: Boolean=true)
  private class Driver(val d: TypedTileImageTransfer){
    zero(d.io);d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
    val memory=mutable.Map.empty[BigInt,Int];val initial=mutable.Map.empty[BigInt,Int];val expected=mutable.Map.empty[BigInt,Int]
    val resident=mutable.Map.empty[Int,(BigInt,Boolean)];var views=Vector.empty[View]
    var mode=2;var ox=1;var oy=2;var width=5;var height=3;var cycle=0
    var blockBus=false;var blockLocal=false;var blockAck=false;var faultRead=false;var faultWrite=false;var cancelOnStore=false
    var delivered=0;var reads=0;var writes=0;var locals=0;var issuedBytes=0;var storedBytes=0
    case class Bus(data: BigInt,due: Int,fault: Boolean)
    var bus: Option[Bus]=None;var local: Option[(BigInt,Boolean,Boolean,Int)]=None
    var heldBus: Option[BigInt]=None;var heldLocal: Option[BigInt]=None
    def slotWord(v: View,index: Int): BigInt=(0 until 8).foldLeft(BigInt(0)){(a,b)=>val n=index*8+b-v.offset
      a|(((v.clear>>(8*(n%v.bytes)))&255)<<(8*b))}
    def find(word: Int): View=views.find(v=>word*8>=v.offset && word*8<v.offset+64*(1<<mode)*v.bytes).get
    def physical(v: View,localByte: Int): Option[BigInt]={val n=localByte-v.offset;val row=n/(8*(1<<mode)*v.bytes);val col=n%(8*(1<<mode)*v.bytes)
      if(row<height && col<width*(1<<mode)*v.bytes)Some(v.base+(oy+row)*v.pitch+ox*(1<<mode)*v.bytes+col)else None}
    def refillValue(word: Int): BigInt={val v=find(word);(0 until 8).foldLeft(BigInt(0))((a,b)=>a|(BigInt(physical(v,word*8+b).map(initial).getOrElse(0))<<(8*b)))}
    def tick(): Unit={
      d.io.memoryRequest.ready.poke((!blockBus && cycle%3!=0).B);d.io.memoryResponse.valid.poke(bus.exists(x=>x.due<=cycle && !blockAck).B)
      bus.foreach{x=>d.io.memoryResponse.bits.readData.poke(x.data.U);d.io.memoryResponse.bits.fault.poke(x.fault.B);d.io.memoryResponse.bits.transactionId.poke(0.U)}
      if(d.io.memoryResponse.valid.peek().litToBoolean && d.io.memoryResponse.ready.peek().litToBoolean)bus=None
      if(d.io.memoryRequest.valid.peek().litToBoolean){val r=d.io.memoryRequest.bits;val bits=r.peek().litValue;heldBus.foreach(x=>assert(x==bits,"held physical request changed"))
        if(d.io.memoryRequest.ready.peek().litToBoolean){assert(bus.isEmpty);val address=r.address.peek().litValue;assert((address&63)==0)
          r.cachePolicy.expect(opengpu.config.CachePolicy.uncached);r.sizeLog2.expect(6.U)
          val write=r.isWrite.peek().litToBoolean;val mask=r.byteMask.peek().litValue;val data=r.writeData.peek().litValue
          val failed=if(write)faultWrite else faultRead
          if(write){writes+=1;issuedBytes+=mask.bitCount;if(!failed){storedBytes+=mask.bitCount
            for(b<-0 until 64 if mask.testBit(b))memory(address+b)=((data>>(8*b))&255).toInt}
            if(cancelOnStore)d.io.cancel.poke(true.B)
          }else reads+=1
          val dataRead=(0 until 64).foldLeft(BigInt(0))((a,b)=>a|(BigInt(memory.getOrElse(address+b,0))<<(8*b)))
          bus=Some(Bus(dataRead,cycle+5,failed));heldBus=None
        }else heldBus=Some(bits)
      }else assert(heldBus.isEmpty)
      d.io.memory.ready.poke((!blockLocal && cycle%4!=0).B);d.io.reply.valid.poke(local.exists(_._4<=cycle).B)
      local.foreach{case(data,available,dirty,_)=>d.io.reply.bits.data.poke(data.U);d.io.reply.bits.available.poke(available.B);d.io.reply.bits.dirty.poke(dirty.B);d.io.reply.bits.fault.poke(false.B)}
      if(d.io.reply.valid.peek().litToBoolean && d.io.reply.ready.peek().litToBoolean)local=None
      if(d.io.memory.valid.peek().litToBoolean){val bits=d.io.memory.bits.peek().litValue;heldLocal.foreach(x=>assert(x==bits,"held maintenance request changed"))
        if(d.io.memory.ready.peek().litToBoolean){assert(local.isEmpty);locals+=1;d.io.memory.bits.write.expect(false.B)
          val word=d.io.memory.bits.address.peek().litValue.toInt;val v=find(word)
          val value=resident.get(word);val available=value.nonEmpty || (v.index<4 && v.load!=0)
          local=Some((value.map(_._1).getOrElse(if(v.index<4 && v.load!=0)slotWord(v,word)else BigInt(0)),available,value.exists(_._2),cycle+2));heldLocal=None
        }else heldLocal=Some(bits)
      }else assert(heldLocal.isEmpty)
      d.clock.step();cycle+=1
    }
    def until(p: => Boolean): Unit={var n=0;while(!p && n<100000){tick();n+=1};assert(p,s"transfer stalled cycle=$cycle reads=$reads writes=$writes local=$locals")}
    def prepare(names: Vector[String],load: Int=1,depth: Int=1,stencil: Boolean=true): Unit={
      memory.clear();initial.clear();expected.clear();resident.clear();views=Vector.empty;delivered=0;reads=0;writes=0;locals=0;issuedBytes=0;storedBytes=0
      mode=2;ox=1;oy=2;width=5;height=3
      val b=d.io.begin.bits;zero(b);b.version.poke(1.U);val q=b.pixels;q.version.poke(1.U);val c=q.colors;c.version.poke(1.U)
      c.identity.context.poke(7.U);c.identity.pass.poke(8.U);c.identity.generation.poke(9.U)
      c.tileLog2.poke(3.U);c.tileWidth.poke(width.U);c.tileHeight.poke(height.U);c.originX.poke(ox.S);c.originY.poke(oy.S);c.sampleMode.poke(mode.U)
      var offset=0
      def add(index: Int,bytes: Int,clear: BigInt): Unit={val base=BigInt(0x400000+index*0x10000+59);val pitch=13*(1<<mode)*bytes+7
        val v=View(index,offset,bytes,load,clear,base,pitch);views :+=v;val a=b.views(index);a.base.poke(base.U)
        a.limit.poke((base+8*pitch+13*(1<<mode)*bytes).U);a.allocationBase.poke((base& ~BigInt(63)).U)
        a.allocationLimit.poke(((base+9*pitch+63)& ~BigInt(63)).U);a.imageWidth.poke(13.U);a.imageHeight.poke(9.U);a.rowPitch.poke(pitch.U)
        a.readable.poke(true.B);a.writable.poke(true.B);a.store.poke(true.B)
        val start=base& ~BigInt(63);val end=(base+9*pitch+63)& ~BigInt(63)
        for(a<-start until end){val x=((a*73+17)&255).toInt;memory(a)=x;initial(a)=x;expected(a)=x}
        offset+=64*(1<<mode)*bytes
      }
      if(depth!=0){val z=TypedDepthReference.encode(fp(0.75),depth);val st=if(TypedDepthReference.hasStencil(depth))BigInt(0x65)<<TypedDepthReference.stencilShift(depth)else BigInt(0)
        q.depth.format.poke(depth.U);q.depth.offset.poke(offset.U);q.depth.loadOp.poke(load.U);q.depth.clearDepth.poke(fp(0.75).U);q.depth.clearStencil.poke(0x65.U)
        add(4,TypedDepthReference.bytes(depth),if(load==2)BigInt(0)else z|st)}
      if(stencil){q.stencil.format.poke(6.U);q.stencil.offset.poke(offset.U);q.stencil.loadOp.poke(load.U);q.stencil.clearStencil.poke(0x37.U);add(5,1,if(load==2)BigInt(0)else BigInt(0x37))}
      c.reservedBytes.poke(offset.U)
      for((name,i)<-names.zipWithIndex){val v=c.colors(i);val f=format(name);val values=if(f.kind==0)Vector(0.2,0.4,0.6,0.8).map(fp)else Vector(1,2,3,4).map(BigInt(_))
        v.enabled.poke(true.B);v.format.poke(TypedColorFormat.id(name).U);v.offset.poke(offset.U);v.loadOp.poke(load.U);v.channelMask.poke(15.U);v.clear.zip(values).foreach{case(a,x)=>a.poke(x.U)}
        add(i,f.bytes,if(load==2)BigInt(0)else encode(name,values)._1)}
    }
    def expectedStores(): Unit={
      for(v<-views if v.store;y<-0 until height;x<-0 until width*(1<<mode)*v.bytes){val localByte=v.offset+y*8*(1<<mode)*v.bytes+x;val word=localByte/8
        val value=resident.get(word)
        if(value.exists(_._2) || v.load!=0){val data=value.map(_._1).getOrElse(slotWord(v,word));expected(physical(v,localByte).get)=((data>>(8*(localByte%8)))&255).toInt}}
    }
    def begin(): Unit={d.io.begin.valid.poke(true.B);until(d.io.begin.ready.peek().litToBoolean);tick();d.io.begin.valid.poke(false.B)
      until(d.io.bound.peek().litToBoolean || d.io.completion.valid.peek().litToBoolean)}
    def refill(word: Int,code: Boolean=false): Unit={d.io.refill.bits.address.poke(word.U);d.io.refill.valid.poke(true.B);until(d.io.refill.ready.peek().litToBoolean);tick();d.io.refill.valid.poke(false.B)
      until(d.io.refillReply.valid.peek().litToBoolean);d.io.refillReply.bits.fault.expect(code.B)
      if(!code){d.io.refillReply.bits.data.expect(refillValue(word).U);val v=find(word);delivered+=(0 until 8).count(b=>physical(v,word*8+b).nonEmpty)}
      val held=d.io.refillReply.bits.peek().litValue;for(_<-0 until 3){tick();assert(d.io.refillReply.bits.peek().litValue==held)}
      d.io.refillReply.ready.poke(true.B);tick();d.io.refillReply.ready.poke(false.B)}
    def flush(success: Boolean=true): Unit={d.io.flush.bits.poke(success.B);d.io.flush.valid.poke(true.B);until(d.io.flush.ready.peek().litToBoolean);tick();d.io.flush.valid.poke(false.B)}
    def finish(code: Int=0,verify: Boolean=true): Unit={until(d.io.completion.valid.peek().litToBoolean);val r=d.io.completion.bits;r.status.expect(code.U)
      r.loadBytes.expect(delivered.U);r.readLines.expect(reads.U);r.writeLines.expect(writes.U);r.issuedStoreBytes.expect(issuedBytes.U);r.storedBytes.expect(storedBytes.U)
      val held=r.peek().litValue;for(_<-0 until 4){tick();assert(r.peek().litValue==held);d.io.begin.ready.expect(false.B)}
      assert(bus.isEmpty && local.isEmpty && heldBus.isEmpty && heldLocal.isEmpty)
      if(verify)assert(memory==expected,"pitched image bytes or allocation guard bytes changed")
      d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B);d.io.busy.expect(false.B)
    }
  }
  it should "export all36 typed colors with edge crops unaligned pitches and depth stencil defaults while skipping clean load words" in {
    simulate(new TypedTileImageTransfer()){d=>val t=new Driver(d);var bindings=0
      for(group<-TypedColorFormat.formats.map(_.name).grouped(4);load<-0 to 2){t.prepare(group.toVector,load)
        for(v<-t.views){t.resident(v.offset/8)=(BigInt("d1c2b3a495867768",16),true)
          if(load==0)t.resident(v.offset/8+1)=(t.refillValue(v.offset/8+1),false)}
        t.expectedStores();t.begin();val before=t.reads
        if(load==0)for(v<-t.views){t.refill(v.offset/8);t.refill((v.offset+8*(1<<t.mode)*v.bytes*2+(t.width*(1<<t.mode)*v.bytes/8)*8)/8)}
        else assert(t.reads==before)
        val loaded=t.reads;t.flush();t.finish();assert(t.reads==loaded);bindings+=1
      }
      for(f<-Seq(2,3,4,5,6)){t.prepare(Vector("RGB8"),1,depth=f,stencil=false);t.expectedStores();t.begin();t.flush();t.finish();bindings+=1}
      info(s"TYPED_IMAGE_TRANSFER bindings=$bindings colors=36 depthStencilLayouts=7 loadClearDiscard=true activeEdge=5x3 physicalBaseUnaligned=true oddPitches=true packedRows=true guardedBytes=true cleanLoadSkip=true noStoreReads=true")
    }
  }
  it should "reject invalid allocations permissions aliases and image bounds before memory access" in {
    simulate(new TypedTileImageTransfer()){d=>val t=new Driver(d)
      val invalid=Seq[()=>Unit](()=>d.io.begin.bits.version.poke(0.U),()=>d.io.begin.bits.pixels.version.poke(0.U),
        ()=>d.io.begin.bits.views(0).imageWidth.poke(0.U),()=>d.io.begin.bits.views(0).imageHeight.poke(0.U),
        ()=>d.io.begin.bits.views(0).rowPitch.poke(1.U),()=>d.io.begin.bits.views(0).allocationBase.poke(1.U),
        ()=>d.io.begin.bits.views(0).allocationLimit.poke((BigInt(1)<<32|1).U),()=>d.io.begin.bits.views(0).limit.poke(0.U),
        ()=>d.io.begin.bits.views(0).readable.poke(false.B),()=>d.io.begin.bits.views(0).writable.poke(false.B),
        ()=>d.io.begin.bits.pixels.colors.originX.poke((-1).S),()=>d.io.begin.bits.pixels.colors.originY.poke(100.S),
        ()=>d.io.begin.bits.views(1).poke(d.io.begin.bits.views(0).peek()))
      for(change<-invalid){t.prepare(Vector("RGB8","RGB8"),0);change();t.begin();t.finish(1);assert(t.reads==0 && t.writes==0 && t.locals==0)}
      t.prepare(Vector("RGB8"));t.expectedStores();t.begin();t.flush();t.finish()
      info(s"TYPED_IMAGE_TRANSFER invalidDescriptors=${invalid.size} noInvalidAccess=true physicalAliases=true permissions=true overflow=true healthyReuse=true")
    }
  }
  it should "hold physical and private offers through cancellation and wait for actual store acknowledgements" in {
    simulate(new TypedTileImageTransfer()){d=>val t=new Driver(d)
      t.prepare(Vector("RGB32F"),0);t.begin();t.blockBus=true;d.io.refill.bits.address.poke((t.views.last.offset/8).U);d.io.refill.valid.poke(true.B)
      t.until(d.io.refill.ready.peek().litToBoolean);t.tick();d.io.refill.valid.poke(false.B);t.until(d.io.memoryRequest.valid.peek().litToBoolean)
      d.io.cancel.poke(true.B);t.tick();d.io.cancel.poke(false.B);for(_<-0 until 4)t.tick();t.blockBus=false
      t.until(d.io.refillReply.valid.peek().litToBoolean);d.io.refillReply.bits.fault.expect(true.B);d.io.refillReply.ready.poke(true.B);t.tick();d.io.refillReply.ready.poke(false.B)
      t.flush(false);t.finish(4)
      t.prepare(Vector("RGB8"));t.begin();t.blockLocal=true;t.flush();t.until(d.io.memory.valid.peek().litToBoolean)
      d.io.cancel.poke(true.B);t.tick();d.io.cancel.poke(false.B);for(_<-0 until 4)t.tick();t.blockLocal=false;t.finish(4)
      t.prepare(Vector("RGBA32F"));t.begin();t.blockAck=true;t.cancelOnStore=true;t.flush();t.until(t.writes==1);t.cancelOnStore=false
      d.io.cancel.poke(false.B);for(_<-0 until 6){t.tick();d.io.completion.valid.expect(false.B)}
      t.blockAck=false;t.finish(4,false);assert(t.writes==1 && t.storedBytes>0)
      t.prepare(Vector("RGB8"));t.expectedStores();t.begin();t.flush();t.finish()
      info("TYPED_IMAGE_TRANSFER heldPhysicalCancel=true heldPrivateCancel=true storeAckHeld=true committedPrefixCounted=true cancellationLatched=true healthyReuse=true")
    }
  }
  it should "propagate load and store faults without publishing success" in {
    simulate(new TypedTileImageTransfer()){d=>val t=new Driver(d)
      t.prepare(Vector("RGBA8"),0);t.begin();t.refill(4095,true);t.flush(false);t.finish(3);assert(t.reads==0 && t.writes==0)
      t.prepare(Vector("RGBA8"),0);t.begin();t.faultRead=true;t.refill(t.views.last.offset/8,true);t.flush(false);t.finish(3);t.faultRead=false
      t.prepare(Vector("RGBA8"));t.begin();t.faultWrite=true;t.flush();t.finish(3);assert(t.storedBytes==0);t.faultWrite=false
      t.prepare(Vector("RGBA8"));t.expectedStores();t.begin();t.flush();t.finish()
      info("TYPED_IMAGE_TRANSFER invalidSlotRefill=true noInvalidPhysicalRead=true readFault=true writeFault=true noFaultedStoreCommit=true noFalseSuccess=true healthyReuse=true")
    }
  }
  it should "allow an allocation ending at four GiB and clear line-cache ownership on rebind" in {
    simulate(new TypedTileImageTransfer()){d=>val t=new Driver(d)
      t.prepare(Vector("RGBA8"),0,depth=0,stencil=false);t.mode=0;t.ox=0;t.oy=0;t.width=4;t.height=4
      t.memory.clear();t.initial.clear();t.expected.clear();val base=BigInt("ffffffc0",16)
      t.views=Vector(View(0,0,4,0,0,base,16,4,4));val q=d.io.begin.bits.pixels.colors
      q.originX.poke(0.S);q.originY.poke(0.S);q.tileWidth.poke(4.U);q.tileHeight.poke(4.U);q.sampleMode.poke(0.U)
      val v=d.io.begin.bits.views(0);v.base.poke(base.U);v.limit.poke((BigInt(1)<<32).U);v.allocationBase.poke(base.U);v.allocationLimit.poke((BigInt(1)<<32).U)
      v.imageWidth.poke(4.U);v.imageHeight.poke(4.U);v.rowPitch.poke(16.U)
      for(a<-base until(BigInt(1)<<32)){t.memory(a)=(a&255).toInt;t.initial(a)=t.memory(a);t.expected(a)=t.memory(a)}
      t.begin();t.refill(13);t.resident(13)=(BigInt("ffeeddccbbaa9988",16),true);t.expectedStores();t.flush();t.finish();assert(t.reads==1 && t.storedBytes==8)
      t.prepare(Vector("RGB8"),0);t.begin();val word=t.views.last.offset/8;t.refill(word);t.flush(false);t.finish()
      t.prepare(Vector("RGB8"),0);for((a,x)<-t.initial.toVector){t.initial(a)=x^255;t.memory(a)=x^255;t.expected(a)=x^255}
      t.begin();t.refill(t.views.last.offset/8);assert(t.reads>0);t.flush(false);t.finish()
      info("TYPED_IMAGE_TRANSFER exclusive4GiB=true highestByte=true fullLineBounds=true coldRefillOnRebind=true noStaleGenerationData=true")
    }
  }

  it should "share immutable load sources while rejecting overlapping stored views" in {
    simulate(new TypedTileImageTransfer()){d=>val t=new Driver(d)
      t.prepare(Vector("RGB8","RGB8"),0,depth=0,stencil=false)
      val a=t.views(0);val b=t.views(1);t.views=t.views.updated(1,b.copy(base=a.base,pitch=a.pitch,store=false))
      d.io.begin.bits.views(1).poke(d.io.begin.bits.views(0).peek());d.io.begin.bits.views(1).store.poke(false.B);d.io.begin.bits.views(1).writable.poke(false.B)
      t.begin();t.refill(a.offset/8+1);val reads=t.reads;t.refill(b.offset/8+1);assert(t.reads==reads)
      t.resident(a.offset/8)=(BigInt("ffeeddccbbaa9988",16),true);t.expectedStores();t.flush();t.finish()
      t.prepare(Vector("RGBA8"),1);t.views=t.views.map(_.copy(store=false))
      for(v<-t.views){val q=d.io.begin.bits.views(v.index);zero(q)}
      t.begin();t.flush();t.finish();assert(t.reads==0 && t.writes==0 && t.locals==0)
      info("TYPED_IMAGE_TRANSFER storeDiscardNoBacking=true sharedLoadAlias=true singleStoredAlias=true snapshotBeforeExport=true cacheLineReuse=true overlappingStoresRejected=true")
    }
  }

}
