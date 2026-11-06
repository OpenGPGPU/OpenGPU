package opengpu.graphics

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class TypedTileImageTransferSpec extends AnyFlatSpec {
  import TypedColorReference.{fp,format,encode}
  // Share one complete supported configuration across resolve regressions,
  // including the exclusive 64 KiB source-slot boundary.
  private def resolveTransfer=new TypedTileImageTransfer(p=TypedTileColorConfig(65536))
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
    val localWords=mutable.ArrayBuffer.empty[Int]
    var destinations=Vector.empty[(View,BigInt,Int)];var resolvedIssued=0;var resolvedAcked=0
    var mode=2;var edge=8;var ox=1;var oy=2;var width=5;var height=3;var cycle=0
    var blockBus=false;var blockLocal=false;var blockAck=false;var faultRead=false;var faultWrite=false;var cancelOnStore=false
    var fixedLatency=false
    var delivered=0;var reads=0;var writes=0;var locals=0;var issuedBytes=0;var storedBytes=0
    var fullLineWrites=0;var maxWriteBytes=0
    case class Bus(data: BigInt,due: Int,fault: Boolean)
    var bus: Option[Bus]=None;var local: Option[(BigInt,Boolean,Boolean,Int)]=None
    var heldBus: Option[BigInt]=None;var heldLocal: Option[BigInt]=None
    def slotWord(v: View,index: Int): BigInt=(0 until 8).foldLeft(BigInt(0)){(a,b)=>val n=index*8+b-v.offset
      a|(((v.clear>>(8*(n%v.bytes)))&255)<<(8*b))}
    def find(word: Int): View=views.find(v=>word*8>=v.offset && word*8<v.offset+edge*edge*(1<<mode)*v.bytes).get
    def physical(v: View,localByte: Int): Option[BigInt]={val n=localByte-v.offset;val row=n/(edge*(1<<mode)*v.bytes);val col=n%(edge*(1<<mode)*v.bytes)
      if(row<height && col<width*(1<<mode)*v.bytes)Some(v.base+(oy+row)*v.pitch+ox*(1<<mode)*v.bytes+col)else None}
    def refillValue(word: Int): BigInt={val v=find(word);(0 until 8).foldLeft(BigInt(0))((a,b)=>a|(BigInt(physical(v,word*8+b).map(initial).getOrElse(0))<<(8*b)))}
    def tick(): Unit={
      d.io.memoryRequest.ready.poke((!blockBus && (fixedLatency || cycle%3!=0)).B);d.io.memoryResponse.valid.poke(bus.exists(x=>x.due<=cycle && !blockAck).B)
      bus.foreach{x=>d.io.memoryResponse.bits.readData.poke(x.data.U);d.io.memoryResponse.bits.fault.poke(x.fault.B);d.io.memoryResponse.bits.transactionId.poke(0.U)}
      if(d.io.memoryResponse.valid.peek().litToBoolean && d.io.memoryResponse.ready.peek().litToBoolean)bus=None
      if(d.io.memoryRequest.valid.peek().litToBoolean){val r=d.io.memoryRequest.bits;val bits=r.peek().litValue;heldBus.foreach(x=>assert(x==bits,"held physical request changed"))
        if(d.io.memoryRequest.ready.peek().litToBoolean){assert(bus.isEmpty);val address=r.address.peek().litValue;assert((address&63)==0)
          r.cachePolicy.expect(opengpu.config.CachePolicy.uncached);r.sizeLog2.expect(6.U)
          val write=r.isWrite.peek().litToBoolean;val mask=r.byteMask.peek().litValue;val data=r.writeData.peek().litValue
          val failed=if(write)faultWrite else faultRead
          if(write){writes+=1;issuedBytes+=mask.bitCount;fullLineWrites+= (if(mask.bitCount==64)1 else 0)
            maxWriteBytes=maxWriteBytes.max(mask.bitCount)
            val resolving=destinations.exists{case(v,base,pitch)=>address+64>base && address<base+v.height*pitch}
            if(resolving)resolvedIssued+=mask.bitCount
            if(!failed){storedBytes+=mask.bitCount;if(resolving)resolvedAcked+=mask.bitCount
            for(b<-0 until 64 if mask.testBit(b))memory(address+b)=((data>>(8*b))&255).toInt}
            if(cancelOnStore)d.io.cancel.poke(true.B)
          }else reads+=1
          val dataRead=(0 until 64).foldLeft(BigInt(0))((a,b)=>a|(BigInt(memory.getOrElse(address+b,0))<<(8*b)))
          bus=Some(Bus(dataRead,cycle+5,failed));heldBus=None
        }else heldBus=Some(bits)
      }else assert(heldBus.isEmpty)
      d.io.memory.ready.poke((!blockLocal && (fixedLatency || cycle%4!=0)).B);d.io.reply.valid.poke(local.exists(_._4<=cycle).B)
      local.foreach{case(data,available,dirty,_)=>d.io.reply.bits.data.poke(data.U);d.io.reply.bits.available.poke(available.B);d.io.reply.bits.dirty.poke(dirty.B);d.io.reply.bits.fault.poke(false.B)}
      if(d.io.reply.valid.peek().litToBoolean && d.io.reply.ready.peek().litToBoolean)local=None
      if(d.io.memory.valid.peek().litToBoolean){val bits=d.io.memory.bits.peek().litValue;heldLocal.foreach(x=>assert(x==bits,"held maintenance request changed"))
        if(d.io.memory.ready.peek().litToBoolean){assert(local.isEmpty);locals+=1;d.io.memory.bits.write.expect(false.B)
          val word=d.io.memory.bits.address.peek().litValue.toInt;localWords+=word;val v=find(word)
          val value=resident.get(word);val available=value.nonEmpty || (v.index<4 && v.load!=0)
          local=Some((value.map(_._1).getOrElse(if(v.index<4 && v.load!=0)slotWord(v,word)else BigInt(0)),available,value.exists(_._2),cycle+2));heldLocal=None
        }else heldLocal=Some(bits)
      }else assert(heldLocal.isEmpty)
      d.clock.step();cycle+=1
    }
    def until(p: => Boolean): Unit={var n=0;while(!p && n<100000){tick();n+=1};assert(p,s"transfer stalled cycle=$cycle reads=$reads writes=$writes local=$locals")}
    def prepare(names: Vector[String],load: Int=1,depth: Int=1,stencil: Boolean=true,sampleMode: Int=2,
        tileLog2: Int=3,tileWidth: Int=5,tileHeight: Int=3,imageWidth: Int=13,imageHeight: Int=9): Unit={
      memory.clear();initial.clear();expected.clear();resident.clear();localWords.clear();views=Vector.empty;delivered=0;reads=0;writes=0;locals=0;issuedBytes=0;storedBytes=0
      fullLineWrites=0;maxWriteBytes=0
      destinations=Vector.empty;resolvedIssued=0;resolvedAcked=0
      mode=sampleMode;edge=1<<tileLog2;ox=1;oy=2;width=tileWidth;height=tileHeight
      val b=d.io.begin.bits;zero(b);b.version.poke(1.U);val q=b.pixels;q.version.poke(1.U);val c=q.colors;c.version.poke(1.U)
      c.identity.context.poke(7.U);c.identity.pass.poke(8.U);c.identity.generation.poke(9.U)
      c.tileLog2.poke(tileLog2.U);c.tileWidth.poke(width.U);c.tileHeight.poke(height.U);c.originX.poke(ox.S);c.originY.poke(oy.S);c.sampleMode.poke(mode.U)
      var offset=0
      def add(index: Int,bytes: Int,clear: BigInt): Unit={val base=BigInt(0x400000+index*0x10000+59);val pitch=imageWidth*(1<<mode)*bytes+7
        val v=View(index,offset,bytes,load,clear,base,pitch,imageWidth,imageHeight);views :+=v;val a=b.views(index);a.base.poke(base.U)
        a.limit.poke((base+(imageHeight-1)*pitch+imageWidth*(1<<mode)*bytes).U);a.allocationBase.poke((base& ~BigInt(63)).U)
        a.allocationLimit.poke(((base+imageHeight*pitch+63)& ~BigInt(63)).U);a.imageWidth.poke(imageWidth.U);a.imageHeight.poke(imageHeight.U);a.rowPitch.poke(pitch.U)
        a.readable.poke(true.B);a.writable.poke(true.B);a.store.poke(true.B)
        val start=base& ~BigInt(63);val end=(base+imageHeight*pitch+63)& ~BigInt(63)
        for(a<-start until end){val x=((a*73+17)&255).toInt;memory(a)=x;initial(a)=x;expected(a)=x}
        offset+=edge*edge*(1<<mode)*bytes
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
      for(v<-views if v.store;y<-0 until height;x<-0 until width*(1<<mode)*v.bytes){val localByte=v.offset+y*edge*(1<<mode)*v.bytes+x;val word=localByte/8
        val value=resident.get(word)
        if(value.exists(_._2) || v.load!=0){val data=value.map(_._1).getOrElse(slotWord(v,word));expected(physical(v,localByte).get)=((data>>(8*(localByte%8)))&255).toInt}}
    }
    def enableResolves(sourceStore: Boolean=false): Unit={
      views=views.map(_.copy(store=sourceStore))
      for(v<-views){d.io.begin.bits.views(v.index).store.poke(sourceStore.B)
        val base=BigInt(0x800000+v.index*0x10000+61);val pitch=v.width*v.bytes+11;val r=d.io.begin.bits.resolves(v.index)
        r.enable.poke(true.B);val a=r.destination;a.base.poke(base.U);a.limit.poke((base+(v.height-1)*pitch+v.width*v.bytes).U)
        a.allocationBase.poke((base& ~BigInt(63)).U);a.allocationLimit.poke(((base+v.height*pitch+63)& ~BigInt(63)).U)
        a.imageWidth.poke(v.width.U);a.imageHeight.poke(v.height.U);a.rowPitch.poke(pitch.U);a.writable.poke(true.B);a.store.poke(true.B)
        destinations :+=((v,base,pitch))
        for(a<-(base& ~BigInt(63)) until((base+v.height*pitch+63)& ~BigInt(63))){memory(a)=0xa5;initial(a)=0xa5;expected(a)=0xa5}
      }
    }
    def expectedResolves(names: Vector[String]): Unit={
      for((v,base,pitch)<-destinations;y<-0 until height;x<-0 until width){
        val values=Vector.tabulate(4){sample=>if(sample>=(1<<mode))BigInt(0)else {
          (0 until v.bytes).foldLeft(BigInt(0)){(r,b)=>val local=v.offset+((y*edge+x)*(1<<mode)+sample)*v.bytes+b;val word=local/8
            val value=resident.get(word).map(_._1).getOrElse(if(v.load==0)refillValue(word)else slotWord(v,word))
            r|(((value>>(8*(local%8)))&255)<<(8*b))}}}
        val value=TypedResolveReference.resolve(if(v.index<4)names(v.index)else "R8",values,mode,depth=v.index>=4)
        for(b<-0 until v.bytes)expected(base+(oy+y)*pitch+(ox+x)*v.bytes+b)=((value>>(8*b))&255).toInt
      }
    }
    def distinctSamples(): Unit={
      val patterns=Vector("800000007fc123457f800000ff800000","0000000080000000000000017fffffff",
        "f17b23c905a6de38809144e26c02bf57","05a6de38f17b23c96c02bf57809144e2").map(BigInt(_,16))
      for(v<-views;y<-0 until height;x<-0 until width;s<-0 until(1<<mode);b<-0 until v.bytes){
        val local=v.offset+((y*edge+x)*(1<<mode)+s)*v.bytes+b;val word=local/8
        val value=(patterns((x+y+s+v.index)%patterns.size)>>(8*b))&255
        val previous=resident.get(word).map(_._1).getOrElse(BigInt(0))
        resident(word)=(previous|(value<<(8*(local%8))),false)
      }
    }
    def alignedRgba(withResolve: Boolean,withDepth: Boolean=false): Unit={
      prepare(Vector("RGBA8"),1,depth=if(withDepth)4 else 0,stencil=false,sampleMode=0,
        tileLog2=4,tileWidth=16,tileHeight=16,imageWidth=16,imageHeight=16)
      ox=0;oy=0;d.io.begin.bits.pixels.colors.originX.poke(0.S)
      d.io.begin.bits.pixels.colors.originY.poke(0.S)
      val source=BigInt(0x400040);val destination=BigInt(0x800040)
      views=views.map(v=>v.copy(base=source+v.index*0x10000,pitch=64))
      memory.clear();initial.clear();expected.clear()
      def bind(a: TypedImageView,base: BigInt): Unit={
        a.base.poke(base.U);a.limit.poke((base+1024).U)
        a.allocationBase.poke((base-64).U);a.allocationLimit.poke((base+1088).U)
        a.imageWidth.poke(16.U);a.imageHeight.poke(16.U);a.rowPitch.poke(64.U)
        a.readable.poke(true.B);a.writable.poke(true.B);a.store.poke(true.B)
        for(address<-base-64 until base+1088){memory(address)=0xa5;initial(address)=0xa5;expected(address)=0xa5}
      }
      for(v<-views)bind(d.io.begin.bits.views(v.index),v.base)
      if(withResolve){
        d.io.begin.bits.resolves(0).enable.poke(true.B)
        bind(d.io.begin.bits.resolves(0).destination,destination)
        destinations=Vector((views.find(_.index==0).get,destination,64))
      }
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
      if(destinations.isEmpty)r.loadBytes.expect(delivered.U)else assert(r.loadBytes.peek().litValue>=delivered)
      r.issuedResolveBytes.expect(resolvedIssued.U);r.resolvedBytes.expect(resolvedAcked.U)
      if(code==0)r.resolvedPixels.expect((destinations.size*width*height).U)
      r.readLines.expect(reads.U);r.writeLines.expect(writes.U);r.issuedStoreBytes.expect((issuedBytes-resolvedIssued).U);r.storedBytes.expect((storedBytes-resolvedAcked).U)
      val held=r.peek().litValue;for(_<-0 until 4){tick();assert(r.peek().litValue==held);d.io.begin.ready.expect(false.B)}
      assert(bus.isEmpty && local.isEmpty && heldBus.isEmpty && heldLocal.isEmpty)
      if(verify)assert(memory==expected,"pitched image bytes or allocation guard bytes changed")
      d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B);d.io.busy.expect(false.B)
    }
  }
  it should "bound publication throughput for complete RGBA8 and D24S8 tiles" in {
    simulate(resolveTransfer){d=>val t=new Driver(d);t.fixedLatency=true
      for(depth<-Seq(false,true)){
        t.alignedRgba(false,withDepth=depth);t.expectedStores();t.begin()
        val start=t.cycle;t.flush();t.until(d.io.completion.valid.peek().litToBoolean)
        val clocks=t.cycle-start;t.finish()
        val bytes=if(depth)2048 else 1024;val lines=bytes/64
        assert(t.locals==bytes/8 && t.reads==0 && t.writes==lines && t.fullLineWrites==lines)
        assert(t.issuedBytes==bytes && t.storedBytes==bytes)
        assert(clocks<=(if(depth)1774 else 895),s"publication throughput regressed: depth=$depth clocks=$clocks")
        info(s"TYPED_IMAGE_STORE_RATE frame=16x16 depth=$depth clocks=$clocks enabledBytes=$bytes physicalWrites=$lines exactImagesAndGuards=true localReplyDelay=2 busReplyDelay=5 periodicBackpressure=false")
      }
    }
  }
  it should "decode every legal tile row and sample stride including packed RGB and the highest 64 KiB slot word" in {
    simulate(new TypedTileImageTransfer(p=TypedTileColorConfig(65536))){d=>val t=new Driver(d);var bindings=0;var refills=0;var highest=0
      def checkRows(): Unit={
        t.begin()
        for(v<-t.views.reverse;y<-0 until t.edge){
          val stride=t.edge*(1<<t.mode)*v.bytes;val active=t.width*(1<<t.mode)*v.bytes
          for(x<-Vector(0,active/8,stride/8-1).distinct if x<stride/8){
            val word=(v.offset+y*stride)/8+x;t.refill(word);refills+=1;highest=highest.max(word)
          }
        }
        t.flush(false);t.finish();assert(t.writes==0 && t.locals==0);bindings+=1
      }
      for(log2<-Seq(3,4);mode<-0 to 2;group<-TypedColorFormat.formats.map(_.name).grouped(4)){
        t.prepare(group.toVector,0,depth=0,stencil=false,sampleMode=mode,tileLog2=log2,
          tileWidth=(1<<log2)-1,tileHeight=(1<<log2)-1,imageWidth=27,imageHeight=21)
        checkRows()
      }
      for(mode<-0 to 2;depth<-1 to 6){
        t.prepare(Vector("R8"),0,depth=depth,stencil=false,sampleMode=mode,tileLog2=4,
          tileWidth=15,tileHeight=15,imageWidth=27,imageHeight=21)
        checkRows()
      }
      t.prepare(Vector.fill(4)("RGBA32F"),0,depth=0,stencil=false,sampleMode=2,tileLog2=4,
        tileWidth=15,tileHeight=15,imageWidth=27,imageHeight=21)
      checkRows();assert(highest==8191)
      info(s"TYPED_IMAGE_REFILL_ROWS bindings=$bindings refills=$refills tileSizes8x16=true samples1x2x4=true colors36=true depthFormats6=true packed3x6x12=true allRows=true highestWord=$highest croppedPaddingZero=true guardsUnchanged=true")
    }
  }
  it should "cancel during refill selection and every bounded row decode step without issuing physical traffic then recover" in {
    simulate(new TypedTileImageTransfer()){d=>val t=new Driver(d)
      for(delay<-0 to 6){
        t.prepare(Vector("RGB32F"),0,depth=0,stencil=false,sampleMode=2,tileLog2=4,
          tileWidth=15,tileHeight=15,imageWidth=27,imageHeight=21)
        t.begin();d.io.refill.bits.address.poke((14*16*4*12/8).U);d.io.refill.valid.poke(true.B)
        t.until(d.io.refill.ready.peek().litToBoolean);t.tick();d.io.refill.valid.poke(false.B)
        for(_<-0 until delay)t.tick()
        d.io.cancel.poke(true.B);t.tick();d.io.cancel.poke(false.B)
        t.until(d.io.refillReply.valid.peek().litToBoolean);d.io.refillReply.bits.fault.expect(true.B)
        val held=d.io.refillReply.bits.peek().litValue
        for(_<-0 until 4){t.tick();assert(d.io.refillReply.bits.peek().litValue==held);d.io.completion.valid.expect(false.B)}
        d.io.refillReply.ready.poke(true.B);t.tick();d.io.refillReply.ready.poke(false.B)
        t.flush(false);t.finish(4);assert(t.reads==0 && t.writes==0 && t.locals==0)
        t.prepare(Vector("RGB8"),0,depth=0,stencil=false);t.begin();t.refill(2*8*4*3/8+2);t.flush(false);t.finish()
      }
      info("TYPED_IMAGE_REFILL_CANCEL arrivalPhases=7 heldFaultReply=true noPhysicalAccess=true boundedDrain=true exactHealthyReuse=true")
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

  it should "resolve all typed views through cold loads virtual clears and packed pitched guarded destinations independently of source stores" in {
    simulate(resolveTransfer){d=>val t=new Driver(d);var bindings=0
      for((group,g)<-TypedColorFormat.formats.map(_.name).grouped(4).zipWithIndex;load<-0 to 2;mode<-0 to 2){
        val names=group.toVector;t.prepare(names,load,depth=(g%6)+1,stencil=g%6<3,sampleMode=mode)
        for(v<-t.views)t.resident(v.offset/8)=(BigInt("3f4000003e800000",16),true)
        t.enableResolves(sourceStore=(load+mode)%2==0);t.expectedStores();t.expectedResolves(names)
        t.begin();t.flush();t.finish();assert(t.resolvedAcked==t.destinations.map(_._1.bytes).sum*t.width*t.height)
        if(load!=0)assert(t.reads==0);bindings+=1
      }
      info(s"TYPED_IMAGE_RESOLVE bindings=$bindings colors=36 depthStencilLayouts=7 modes=3 loadClearDiscard=true independentSourceStore=true coldPackedGather=true exactGuardedBytes=true unalignedResolveBase=true oddResolvePitch=true")
    }
  }
  it should "resolve a complete four-sample RGBA8 tile with distinct samples and exact pitched guards" in {
    simulate(resolveTransfer){d=>val t=new Driver(d);t.fixedLatency=true
      t.prepare(Vector("RGBA8"),1,depth=0,stencil=false,sampleMode=2,
        tileLog2=3,tileWidth=8,tileHeight=8,imageWidth=16,imageHeight=16)
      val view=t.views.head
      // Every sample/channel varies; the independent format/resolve oracle
      // checks the original four inputs rather than repeating a clear value.
      for(word<-0 until 128){
        val value=(0 until 8).foldLeft(BigInt(0)){(a,b)=>
          a|(BigInt((word*37+b*53+19)&255)<<(8*b))}
        t.resident(view.offset/8+word)=(value,false)
      }
      t.enableResolves();t.expectedResolves(Vector("RGBA8"));t.begin()
      val start=t.cycle;t.flush();t.finish()
      val cycles=t.cycle-start
      assert(t.locals==128 && t.reads==0 && t.writes==10 && t.resolvedAcked==256)
      assert(cycles<=5598,s"distinct four-sample resolve regressed: cycles=$cycles")
      info(s"TYPED_RESOLVE_GATHER tile=8x8 samples=4 format=RGBA8 cycles=$cycles localReads=${t.locals} busReads=${t.reads} busWrites=${t.writes} resolvedBytes=${t.resolvedAcked} exactImagesAndGuards=true")
    }
  }

  it should "bound single-sample resolve throughput with distinct pixels and exact pitched guards" in {
    simulate(resolveTransfer){d=>val t=new Driver(d);t.fixedLatency=true
      t.prepare(Vector("RGBA8"),1,depth=0,stencil=false,sampleMode=0,
        tileWidth=8,tileHeight=8,imageWidth=16,imageHeight=16)
      t.distinctSamples();t.enableResolves();t.expectedResolves(Vector("RGBA8"));t.begin()
      val start=t.cycle;t.flush();t.finish();val clocks=t.cycle-start
      assert(t.locals==32 && t.reads==0 && t.writes==10 && t.resolvedAcked==256)
      assert(clocks<=542,s"single-sample resolve regressed: clocks=$clocks")
      info(s"TYPED_RESOLVE_IDENTITY frame=8x8 samples=1 format=RGBA8 clocks=$clocks pixels=64 localReads=${t.locals} busReads=${t.reads} busWrites=${t.writes} resolvedBytes=${t.resolvedAcked} exactImagesAndGuards=true fixedLatency=true")
    }
  }

  // These three performance probes are explicitly selected qualifications.
  // Ordinary PR runs do not register additional simulation cases.
  sys.props.get("opengpu.rgba8UniformResolveQualification").foreach{variant=>
    require(Set("control","candidate")(variant),"qualification variant must be control or candidate")
    for(pattern<-Vector("same","mixed","distinct")){
      it should s"qualify identical RGBA8 four-sample $pattern throughput with complete traffic and guards" in {
        simulate(resolveTransfer){d=>val t=new Driver(d);t.fixedLatency=true
          t.prepare(Vector("RGBA8"),1,depth=0,stencil=false,sampleMode=2,
            tileWidth=8,tileHeight=8,imageWidth=16,imageHeight=16)
          val view=t.views.head
          if(pattern=="distinct"){
            // Byte-identical to the existing distinct-input throughput probe.
            for(word<-0 until 128){val value=(0 until 8).foldLeft(BigInt(0)){(a,b)=>
              a|(BigInt((word*37+b*53+19)&255)<<(8*b))}
              t.resident(view.offset/8+word)=(value,false)}
          }else{
            for(y<-0 until 8;x<-0 until 8;s<-0 until 4){
              val pixel=x+8*y
              val base=(0 until 4).map(c=>BigInt((pixel*17+c*53+19)&255)<<(8*c)).sum
              val value=if(pattern=="mixed" && (x+y)%2==1 && s==3)base^(BigInt(1)<<24)else base
              val local=view.offset+((y*8+x)*4+s)*4;val word=local/8
              t.resident(word)=(t.resident.get(word).map(_._1).getOrElse(BigInt(0))|(value<<(8*(local%8))),false)
            }
          }
          t.enableResolves();t.expectedResolves(Vector("RGBA8"));t.begin()
          val start=t.cycle;t.flush();t.finish();val clocks=t.cycle-start
          assert(t.locals==128 && t.reads==0 && t.writes==10 && t.resolvedAcked==256)
          // Recheck the current fixed control, then apply the predeclared gates.
          val controlClocks=5598
          if(variant=="control")assert(clocks==controlClocks,s"control changed: pattern=$pattern clocks=$clocks")
          else if(pattern=="distinct")assert(clocks<=controlClocks,s"distinct fallback regressed: $clocks")
          else {val saved=controlClocks-clocks;val minimum=if(pattern=="same")4096 else 2048
            assert(saved>=minimum && saved*100>=controlClocks*2,
              s"qualification gate failed: pattern=$pattern saved=$saved minimum=$minimum clocks=$clocks")}
          info(s"TYPED_RGBA8_IDENTICAL_RATE variant=$variant pattern=$pattern tile=8x8 samples=4 clocks=$clocks controlClocks=$controlClocks localReads=${t.locals} externalReads=${t.reads} writes=${t.writes} resolvedBytes=${t.resolvedAcked} exactImagesAndGuards=true functionalSlotBytes=65536")
        }
      }
    }
  }

  it should "preserve identical RGBA8 tails and drain only owned cancelled resolve writes before reuse" in {
    simulate(resolveTransfer){d=>val t=new Driver(d);t.fixedLatency=true
      def prepare(width: Int=3,height: Int=1): Unit={
        t.prepare(Vector("RGBA8"),1,depth=0,stencil=false,sampleMode=2,
          tileWidth=width,tileHeight=height,imageWidth=16,imageHeight=16)
        t.enableResolves();t.expectedResolves(Vector("RGBA8"))
      }
      t.prepare(Vector("RGBA8"),1,depth=0,stencil=false,sampleMode=2,
        tileWidth=8,tileHeight=8,imageWidth=16,imageHeight=16)
      val lastSlot=65536-8*8*4*4;t.views=Vector(t.views.head.copy(offset=lastSlot))
      d.io.begin.bits.pixels.colors.colors(0).offset.poke(lastSlot.U)
      t.enableResolves();t.expectedResolves(Vector("RGBA8"));t.begin();t.flush();t.finish()
      assert(t.localWords.toVector==(lastSlot/8 until 8192).toVector && t.resolvedAcked==256)
      // Three pixels end in one partial physical write. After the final
      // private-word offer these offsets span its reply, short resolve and
      // the irrevocable physical offer; no internal state is observed.
      var ownedOffers=0
      for(delay<-0 to 24){
        prepare();d.io.begin.bits.pixels.colors.colors(0).channelMask.poke(1.U)
        t.begin();t.blockBus=true;t.flush();t.until(t.locals==6)
        for(_<-0 until delay)t.tick()
        val offered=d.io.memoryRequest.valid.peek().litToBoolean
        if(offered)ownedOffers+=1
        d.io.cancel.poke(true.B);t.tick();d.io.cancel.poke(false.B);t.blockBus=false;t.finish(4,verify=false)
        assert(t.writes==(if(offered)1 else 0) && t.resolvedAcked==(if(offered)12 else 0))
        assert(t.memory==(if(offered)t.expected else t.initial),"cancelled identical resolve changed an unoffered or guard byte")
      }
      assert(ownedOffers>0 && ownedOffers<25,"cancel window missed the short-path physical offer boundary")
      prepare();t.begin();t.blockAck=true;t.cancelOnStore=true;t.flush();t.until(t.writes==1)
      t.cancelOnStore=false;d.io.cancel.poke(false.B)
      for(_<-0 until 6){t.tick();d.io.completion.valid.expect(false.B)}
      t.blockAck=false;t.finish(4,verify=false);assert(t.resolvedAcked==12 && t.memory==t.expected)
      prepare();t.begin();t.faultWrite=true;t.flush();t.finish(3,verify=false)
      assert(t.resolvedAcked==0 && t.memory==t.initial);t.faultWrite=false
      prepare();t.begin();t.flush();t.finish()
      info("TYPED_RGBA8_IDENTICAL_TAIL highestWord=8191 fullFourSampleReads=true shortCancelOffsets=25 partialWriteBytes=12 heldAckDrain=true faultNoCredit=true healthyReuse=true exactGuards=true")
    }
  }

  it should "preserve single-sample and raw resolve bits across formats rows and the final source word" in {
    simulate(resolveTransfer){d=>val t=new Driver(d);var bindings=0
      val all=TypedColorFormat.formats.map(_.name).toVector
      val integers=TypedColorFormat.formats.filter(_.kind!=0).map(_.name).toVector
      for((names,g)<-all.grouped(4).zipWithIndex;load<-0 to 2){
        t.prepare(names,load,depth=(g%6)+1,stencil=g%6<3,sampleMode=0,
          tileWidth=3,tileHeight=2,imageWidth=8,imageHeight=4)
        t.distinctSamples();if(load==0)t.resident.keys.filter(_%3==0).toVector.foreach(t.resident.remove)
        t.enableResolves(sourceStore=load==1);t.expectedStores();t.expectedResolves(names)
        t.begin();t.flush();t.finish();bindings+=1
      }
      for(names<-integers.grouped(4);mode<-1 to 2;depth<-1 to 6){
        t.prepare(names,1,depth=depth,stencil=depth<=3,sampleMode=mode,
          tileWidth=3,tileHeight=2,imageWidth=8,imageHeight=4)
        t.distinctSamples();t.enableResolves();t.expectedResolves(names)
        t.begin();t.flush();t.finish();bindings+=1
      }
      t.prepare(Vector("RGBA32F"),1,depth=0,stencil=false,sampleMode=0,
        tileWidth=8,tileHeight=8,imageWidth=16,imageHeight=16)
      val lastSlot=65536-8*8*16;t.views=Vector(t.views.head.copy(offset=lastSlot))
      d.io.begin.bits.pixels.colors.colors(0).offset.poke(lastSlot.U)
      t.distinctSamples();t.enableResolves();t.expectedResolves(Vector("RGBA32F"));t.begin();t.flush();t.finish()
      assert(t.localWords.toVector==(lastSlot/8 until 8192).toVector && t.resolvedAcked==1024)
      info(s"TYPED_RESOLVE_IDENTITY_BITS bindings=$bindings formats1x=36 integerModes2x4x=true depthStencilFormats=7 rawSample0=true exact128bit=true signedZeroAndNanPayloads=true nonzeroOrigin=true crossedRows=true guardedPitch=true coldLoad=true sourceStores=true highestWord=8191 exclusiveSourceBytes=65536")
    }
  }

  it should "cancel raw resolve boundary windows and drain held traffic before healthy reuse" in {
    simulate(resolveTransfer){d=>val t=new Driver(d);t.fixedLatency=true
      // Public local-response timing spans the complete-record boundary and
      // the original resolver offer/wait window without internal signal taps.
      for((name,mode,depth)<-Seq(("RGBA8",0,0),("R32I",2,0),("RGBA8",2,4));delay<-0 to 9){
        t.prepare(if(depth==0)Vector(name)else Vector.empty,1,depth=depth,stencil=false,sampleMode=mode,
          tileWidth=1,tileHeight=1,imageWidth=8,imageHeight=4)
        t.distinctSamples();t.enableResolves();t.expectedResolves(if(depth==0)Vector(name)else Vector.empty)
        t.begin();t.blockBus=true;t.flush();t.until(t.locals==1)
        for(_<-0 until delay)t.tick()
        val offered=d.io.memoryRequest.valid.peek().litToBoolean
        d.io.cancel.poke(true.B);t.tick();d.io.cancel.poke(false.B);t.blockBus=false;t.finish(4,verify=false)
        assert(t.reads==0 && t.writes==(if(offered)1 else 0))
        assert(t.memory==(if(offered)t.expected else t.initial),"cancelled resolve changed unoffered or guarded bytes")
      }
      // Sweep both halves of a 16-byte record and the final stencil view.
      // Bus backpressure keeps any irrevocable offer owned until cancellation.
      for((name,stencil,words)<-Seq(("RGBA32UI",false,2),("R8",true,1));delay<-0 to 24){
        val names=if(stencil)Vector.empty[String]else Vector(name)
        t.prepare(names,1,depth=0,stencil=stencil,sampleMode=0,
          tileWidth=1,tileHeight=1,imageWidth=8,imageHeight=4)
        t.distinctSamples();t.enableResolves();t.expectedResolves(names)
        t.begin();t.blockBus=true;t.flush();t.until(t.locals==words)
        for(_<-0 until delay)t.tick()
        val offered=d.io.memoryRequest.valid.peek().litToBoolean
        d.io.cancel.poke(true.B);t.tick();d.io.cancel.poke(false.B);t.blockBus=false;t.finish(4,verify=false)
        assert(t.reads==0 && t.writes==(if(offered)1 else 0))
        assert(t.memory==(if(offered)t.expected else t.initial),"cancelled record tail changed unoffered or guarded bytes")
      }
      // A 16-byte pixel straddles a line after seven bytes. Its first owned
      // write must drain while the unoffered nine-byte suffix is discarded.
      t.prepare(Vector("RGBA32UI"),1,depth=0,stencil=false,sampleMode=0,
        tileWidth=1,tileHeight=1,imageWidth=8,imageHeight=4)
      t.distinctSamples();t.enableResolves()
      val (wideView,oldBase,widePitch)=t.destinations.head;val wideBase=oldBase+22
      val wideDestination=d.io.begin.bits.resolves(0).destination
      wideDestination.base.poke(wideBase.U)
      wideDestination.limit.poke((wideBase+(wideView.height-1)*widePitch+wideView.width*wideView.bytes).U)
      wideDestination.allocationBase.poke((wideBase& ~BigInt(63)).U)
      wideDestination.allocationLimit.poke(((wideBase+wideView.height*widePitch+63)& ~BigInt(63)).U)
      t.destinations=Vector((wideView,wideBase,widePitch));t.expectedResolves(Vector("RGBA32UI"))
      val widePixel=wideBase+t.oy*widePitch+t.ox*wideView.bytes;assert((widePixel&63)==57)
      t.begin();t.blockAck=true;t.cancelOnStore=true;t.flush();t.until(t.writes==1)
      t.cancelOnStore=false;d.io.cancel.poke(false.B)
      for(_<-0 until 6){t.tick();d.io.completion.valid.expect(false.B)}
      t.blockAck=false;t.finish(4,false);assert(t.writes==1 && t.resolvedAcked==7)
      for((address,value)<-t.memory){
        val owned=address>=widePixel && address<widePixel+7
        assert(value==(if(owned)t.expected(address)else t.initial(address)),"wide resolve changed an unowned suffix or guard byte")
      }
      t.prepare(Vector("RGBA8"),0,depth=0,stencil=false,sampleMode=0);t.enableResolves();t.begin()
      t.blockBus=true;t.flush();t.until(d.io.memoryRequest.valid.peek().litToBoolean)
      d.io.cancel.poke(true.B);t.tick();d.io.cancel.poke(false.B);for(_<-0 until 4)t.tick()
      t.blockBus=false;t.finish(4)
      t.prepare(Vector("R32UI"),1,depth=0,stencil=false,sampleMode=2);t.enableResolves();t.begin()
      t.blockLocal=true;t.flush();t.until(d.io.memory.valid.peek().litToBoolean)
      d.io.cancel.poke(true.B);t.tick();d.io.cancel.poke(false.B);t.tick();t.blockLocal=false;t.finish(4)
      t.prepare(Vector.empty,1,depth=4,stencil=false,sampleMode=2);t.distinctSamples();t.enableResolves();t.expectedResolves(Vector.empty);t.begin()
      t.blockAck=true;t.cancelOnStore=true;t.flush();t.until(t.writes==1);t.cancelOnStore=false;d.io.cancel.poke(false.B)
      for(_<-0 until 6){t.tick();d.io.completion.valid.expect(false.B)}
      t.blockAck=false;t.finish(4,false);assert(t.writes==1 && t.resolvedAcked>0)
      for((address,value)<-t.memory)assert(value==t.initial(address) || value==t.expected(address),"acknowledged prefix changed a source or guard byte")
      t.prepare(Vector("RGBA8"),0,depth=0,stencil=false,sampleMode=0);t.enableResolves();t.begin()
      t.faultRead=true;t.flush();t.finish(3);t.faultRead=false
      t.prepare(Vector("R32I"),1,depth=0,stencil=false,sampleMode=2);t.enableResolves();t.begin()
      t.faultWrite=true;t.flush();t.finish(3);t.faultWrite=false;assert(t.resolvedAcked==0)
      t.prepare(Vector("RGBA8"),1,depth=0,stencil=false,sampleMode=0);t.distinctSamples();t.enableResolves();t.expectedResolves(Vector("RGBA8"))
      t.begin();t.flush();t.finish()
      info("TYPED_RESOLVE_IDENTITY_CANCEL boundaryOffsets=10 tailOffsets=25 paths=singleSampleIntegerDepth wide128bit=true finalStencilView=true heldRead=true heldPrivate=true heldWriteAck=true exactSevenBytePrefix=true readFault=true writeFault=true noFalseSuccess=true healthyReuse=true")
    }
  }

  it should "preserve paired sample tails raw modes cancellation and the final 64 KiB source word" in {
    simulate(resolveTransfer){d=>val t=new Driver(d);t.fixedLatency=true;var bindings=0
      def fill(name: String): Unit={
        val patterns=name match {
          case "R32F"=>Vector("80000000","3f800000","c0000000","40800000")
          case "RG16F"=>Vector("3c00bc00","38004000","80000000","7bff0001")
          case _=>Vector("f17b23c9","05a6de38","809144e2","6c02bf57")
        }
        val values=patterns.map(BigInt(_,16));val v=t.views.head
        for(y<-0 until t.height;x<-0 until t.width;s<-0 until(1<<t.mode)){
          val local=v.offset+((y*t.edge+x)*(1<<t.mode)+s)*4;val word=local/8
          val previous=t.resident.get(word).map(_._1).getOrElse(BigInt(0))
          t.resident(word)=(previous|(values((x+y+s)%4)<<(8*(local%8))),false)
        }
      }
      val names=Vector("RGBA8","RGB10_A2","SRGB8_ALPHA8","RG16F","R32F","R32I","R32UI")
      for(name<-names;mode<-0 to 2){
        t.prepare(Vector(name),1,depth=0,stencil=false,sampleMode=mode,tileWidth=3,tileHeight=1)
        fill(name);t.enableResolves();t.expectedResolves(Vector(name));t.begin();t.flush();t.finish()
        val raw=format(name).kind!=0
        val words=if(raw && mode!=0)3 else (3*(if(raw)1 else 1<<mode)+1)/2
        assert(t.locals==words && t.reads==0 && t.resolvedAcked==12,
          s"sample ownership changed: format=$name mode=$mode local=${t.locals}")
        bindings+=1
      }
      t.prepare(Vector("RGBA8"),1,depth=0,stencil=false,sampleMode=2,
        tileWidth=8,tileHeight=8,imageWidth=16,imageHeight=16)
      val lastSlot=65536-8*8*4*4
      t.views=Vector(t.views.head.copy(offset=lastSlot))
      d.io.begin.bits.pixels.colors.colors(0).offset.poke(lastSlot.U)
      fill("RGBA8");t.enableResolves();t.expectedResolves(Vector("RGBA8"));t.begin();t.flush();t.finish()
      assert(t.localWords.toVector==(lastSlot/8 until 8192).toVector)
      assert(t.locals==128 && t.reads==0 && t.resolvedAcked==256)
      // This public-response-relative window spans gather, the next owned
      // word request, the tail pair and resolver entry without internal taps.
      for(delay<-0 to 14){
        t.prepare(Vector("RGBA8"),1,depth=0,stencil=false,sampleMode=2,tileWidth=3,tileHeight=1)
        fill("RGBA8");t.enableResolves();t.begin();t.flush();t.until(t.locals==1)
        for(_<-0 until delay)t.tick()
        d.io.cancel.poke(true.B);t.tick();d.io.cancel.poke(false.B);t.finish(4)
        assert(t.writes==0 && t.reads==0 && t.resolvedAcked==0)
      }
      t.prepare(Vector("RGBA8"),1,depth=0,stencil=false);t.enableResolves()
      d.io.begin.bits.pixels.colors.colors(0).offset.poke(4.U)
      t.begin();t.finish(1);assert(t.locals==0 && t.reads==0 && t.writes==0)
      t.prepare(Vector("RGBA8"),1,depth=0,stencil=false,sampleMode=2,tileWidth=3,tileHeight=1)
      fill("RGBA8");t.enableResolves();t.expectedResolves(Vector("RGBA8"));t.begin();t.flush();t.finish()
      info(s"TYPED_RESOLVE_PAIRS bindings=$bindings samples1x2x4=true eligibleFormats5=true rawIntegerSample0=true exactIndependentResolve=true highestWord=8191 exclusiveSlotBytes=65536 cancelOffsets=15 invalidUnalignedSlotNoTraffic=true healthyReuse=true")
    }
  }

  it should "reject resolve bounds permissions output aliases and late source aliases before memory access" in {
    simulate(resolveTransfer){d=>val t=new Driver(d)
      val changes=Seq[()=>Unit](
        ()=>d.io.begin.bits.resolves(0).destination.writable.poke(false.B),()=>d.io.begin.bits.resolves(0).destination.store.poke(false.B),
        ()=>d.io.begin.bits.resolves(0).destination.limit.poke(1.U),()=>d.io.begin.bits.resolves(0).destination.rowPitch.poke(1.U),
        ()=>d.io.begin.bits.resolves(0).destination.imageWidth.poke(1.U),()=>d.io.begin.bits.resolves(0).destination.allocationLimit.poke((BigInt(1)<<32|1).U),
        ()=>d.io.begin.bits.resolves(0).destination.poke(d.io.begin.bits.views(0).peek()),
        ()=>d.io.begin.bits.resolves(1).destination.poke(d.io.begin.bits.resolves(0).destination.peek()),
        ()=>d.io.begin.bits.resolves(3).enable.poke(true.B),
        ()=>{d.io.begin.bits.views(1).poke(d.io.begin.bits.views(0).peek());d.io.begin.bits.views(1).store.poke(true.B)})
      for(change<-changes){t.prepare(Vector("RGB8","RGB8"),0,depth=0,stencil=false);t.enableResolves();change();t.begin();t.finish(1);assert(t.locals==0 && t.reads==0 && t.writes==0)}
      t.prepare(Vector("RGBA8"),1,depth=0,stencil=false);t.enableResolves();zero(d.io.begin.bits.views(0));t.expectedResolves(Vector("RGBA8"));t.begin();t.flush();t.finish()
      info(s"TYPED_IMAGE_RESOLVE invalidDescriptors=${changes.size} noInvalidAccess=true outputSourceAlias=true outputOutputAlias=true lateLoadSourceAlias=true clearResolveWithoutSourceBacking=true")
    }
  }
  it should "drain held resolve reads private requests stores and cancellation while preserving acknowledged prefixes" in {
    simulate(resolveTransfer){d=>val t=new Driver(d)
      t.prepare(Vector("RGBA32F"),0,depth=0,stencil=false);t.enableResolves();t.begin();t.blockBus=true;t.flush();t.until(d.io.memoryRequest.valid.peek().litToBoolean)
      d.io.cancel.poke(true.B);t.tick();d.io.cancel.poke(false.B);for(_<-0 until 4)t.tick();t.blockBus=false;t.finish(4)
      t.prepare(Vector("RGBA8"),1,depth=0,stencil=false);t.enableResolves();t.begin();t.blockLocal=true;t.flush();t.until(d.io.memory.valid.peek().litToBoolean)
      d.io.cancel.poke(true.B);t.tick();d.io.cancel.poke(false.B);t.tick();t.blockLocal=false;t.finish(4)
      t.prepare(Vector("RGBA32F"),1,depth=0,stencil=false);t.enableResolves();t.begin();t.blockAck=true;t.cancelOnStore=true;t.flush();t.until(t.writes==1);t.cancelOnStore=false;d.io.cancel.poke(false.B)
      for(_<-0 until 6){t.tick();d.io.completion.valid.expect(false.B)};t.blockAck=false;t.finish(4,false);assert(t.resolvedAcked>0 && t.writes==1)
      t.prepare(Vector("RGBA8"),1,depth=0,stencil=false);t.enableResolves();t.begin();t.blockBus=true;t.flush();t.until(d.io.memoryRequest.valid.peek().litToBoolean)
      d.io.cancel.poke(true.B);t.tick();d.io.cancel.poke(false.B);for(_<-0 until 5)t.tick();t.blockBus=false;t.finish(4,false);assert(t.writes==1 && t.resolvedAcked>0)
      t.prepare(Vector("RGBA8"),0,depth=0,stencil=false);t.enableResolves();t.begin();t.faultRead=true;t.flush();t.finish(3);t.faultRead=false
      t.prepare(Vector("RGBA8"),1,depth=0,stencil=false);t.enableResolves();t.begin();t.faultWrite=true;t.flush();t.finish(3);t.faultWrite=false;assert(t.resolvedAcked==0)
      t.prepare(Vector("SRGB8_ALPHA8"),1,depth=0,stencil=false);t.enableResolves();t.expectedResolves(Vector("SRGB8_ALPHA8"));t.begin();t.flush();t.finish()
      info("TYPED_IMAGE_RESOLVE heldReadCancel=true heldPrivateCancel=true heldResolveAck=true heldResolveOfferCancel=true acknowledgedPrefix=true resolveReadFault=true resolveWriteFault=true noFalseSuccess=true healthyReuse=true")
    }
  }

  it should "resolve into the highest physical bytes with a four GiB exclusive destination limit" in {
    simulate(resolveTransfer){d=>val t=new Driver(d)
      t.prepare(Vector("RGBA8"),0,depth=0,stencil=false);t.width=4;t.height=4;t.ox=0;t.oy=0
      val tile=d.io.begin.bits.pixels.colors;tile.originX.poke(0.S);tile.originY.poke(0.S);tile.tileWidth.poke(4.U);tile.tileHeight.poke(4.U)
      t.enableResolves();val base=BigInt("ffffffc0",16);val end=BigInt(1)<<32
      t.destinations=Vector((t.views.head,base,16));val r=d.io.begin.bits.resolves(0).destination
      r.base.poke(base.U);r.limit.poke(end.U);r.allocationBase.poke(base.U);r.allocationLimit.poke(end.U)
      r.imageWidth.poke(4.U);r.imageHeight.poke(4.U);r.rowPitch.poke(16.U)
      for(a<-base until end){t.memory(a)=0xa5;t.initial(a)=0xa5;t.expected(a)=0xa5}
      t.expectedResolves(Vector("RGBA8"));t.begin();t.flush();t.finish();assert(t.resolvedAcked==64 && t.reads>0)
      info("TYPED_IMAGE_RESOLVE destinationExclusive4GiB=true highestByte=true coldLoadResolve=true exactHighestLine=true")
    }
  }


  it should "retain exact prepared lazy depth clear words across formats and poisoned live descriptors" in {
    simulate(new TypedTileImageTransfer){d=>val t=new Driver(d);var checked=0
      val raws=Vector(BigInt(0),BigInt("80000000",16),fp(0.5),fp(0.75),BigInt("3f7fffff",16),fp(1.0),fp(-1.0),BigInt("7f800000",16))
      for(format<-1 to 5;raw<-raws){
        t.prepare(Vector.empty,depth=format,stencil=false,sampleMode=0,tileWidth=1,tileHeight=1,imageWidth=8,imageHeight=4)
        d.io.begin.bits.pixels.depth.clearDepth.poke(raw.U)
        val encoded=TypedDepthReference.encode(raw,format)
        val st=if(TypedDepthReference.hasStencil(format))BigInt(0x65)<<TypedDepthReference.stencilShift(format)else BigInt(0)
        t.views=t.views.map(v=>if(v.index==4)v.copy(clear=encoded|st)else v)
        t.expectedStores();t.begin();zero(d.io.begin.bits);t.flush();t.finish();checked+=1
      }
      info(s"TYPED_IMAGE_PREPARED_CLEAR checked=$checked formatsD16D24D32FD24S8D32FS8=true exactLazyWords=true signedZero=true saturation=true liveDescriptorIsolation=true guardedImages=true")
    }
  }

  it should "drain every binding cancellation offset including depth preparation and reset before healthy reuse" in {
    simulate(new TypedTileImageTransfer){d=>val t=new Driver(d)
      def prepare(): Unit=t.prepare(Vector.empty,depth=4,stencil=false,sampleMode=0,tileWidth=1,tileHeight=1,imageWidth=8,imageHeight=4)
      def accept(): Unit={d.io.begin.ready.expect(true.B);d.io.begin.valid.poke(true.B);t.tick();d.io.begin.valid.poke(false.B)}
      prepare();accept();var latency=0
      while(!d.io.bound.peek().litToBoolean && latency<512){t.tick();latency+=1}
      assert(latency<512);t.flush(false);t.finish()
      for(offset<-0 to latency){
        prepare();accept();for(_<-0 until offset)t.tick()
        d.io.cancel.poke(true.B);t.tick()
        if(d.io.bound.peek().litToBoolean)t.flush(false)
        t.finish(4);assert(t.reads==0 && t.writes==0 && t.locals==0)
        d.io.cancel.poke(false.B)
      }
      for(offset<-(0 to latency)){
        prepare();accept();for(_<-0 until offset)t.tick()
        d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
        d.io.begin.ready.expect(true.B);d.io.completion.valid.expect(false.B)
      }
      prepare();t.expectedStores();t.begin();t.flush();t.finish()
      info(s"TYPED_IMAGE_CLEAR_LIFETIME bindLatency=$latency cancelOffsets=${latency+1} resetOffsets=${latency+1} noCancelledTraffic=true ownedPipelineDrained=true healthyReuse=true")
    }
  }

  it should "cancel every store preparation offset and drain only an already offered physical write" in {
    simulate(new TypedTileImageTransfer){d=>val t=new Driver(d)
      def prepare(): Unit={
        t.prepare(Vector("R8"),depth=0,stencil=false,sampleMode=0,tileWidth=1,tileHeight=1,imageWidth=8,imageHeight=4)
        t.expectedStores();t.blockBus=true;t.begin();t.flush()
      }
      prepare();val start=t.cycle;t.until(d.io.memoryRequest.valid.peek().litToBoolean)
      val latency=t.cycle-start;t.blockBus=false;t.finish()
      for(offset<-0 to latency){
        prepare();for(_<-0 until offset)t.tick()
        val offered=d.io.memoryRequest.valid.peek().litToBoolean
        d.io.cancel.poke(true.B);t.tick();t.blockBus=false;t.finish(4,verify=false)
        assert(t.reads==0 && t.writes==(if(offered)1 else 0))
        assert(t.memory==(if(offered)t.expected else t.initial),"cancelled unoffered bytes escaped or an irrevocable offer was lost")
        d.io.cancel.poke(false.B)
      }
      t.prepare(Vector("R8"),depth=0,stencil=false,sampleMode=0,tileWidth=1,tileHeight=1,imageWidth=8,imageHeight=4)
      t.expectedStores();t.begin();t.flush();t.finish()
      info(s"TYPED_IMAGE_STORE_PREPARATION firstOfferClocks=$latency cancelOffsets=${latency+1} noUnofferedCommit=true heldOfferDrains=true exactAcknowledgedPrefix=true healthyReuse=true")
    }
  }

  it should "gather complete stored and resolved lines with exact enabled bytes and allocation guards" in {
    simulate(resolveTransfer){d=>val t=new Driver(d)
      t.alignedRgba(true);t.expectedStores();t.expectedResolves(Vector("RGBA8"))
      t.begin();t.flush();t.finish()
      // Two independent 16x16 RGBA8 images occupy 32 physical 64-byte lines.
      // Word-at-a-time stores and pixel-at-a-time resolves needed 128+256 writes.
      assert(t.writes==32 && t.fullLineWrites==32 && t.maxWriteBytes==64)
      assert(t.issuedBytes==2048 && t.storedBytes==2048 && t.resolvedAcked==1024 && t.reads==0)
      info("TYPED_IMAGE_LINE_GATHER frame=16x16 storedAndResolved=true physicalWrites=32 enabledBytes=2048 fullMasks=true exactImagesAndGuards=true")
    }
  }

  it should "discard unissued partial lines and retain held or faulted grouped writes through acknowledgement" in {
    simulate(new TypedTileImageTransfer()){d=>val t=new Driver(d)
      // The fast tail may retain either a partial line or a full unoffered
      // line while the following private read is held. Both remain discardable.
      for(words<-Seq(1,8)){
        t.alignedRgba(false);t.begin();t.flush();t.until(t.locals==words);t.blockLocal=true
        t.until(d.io.memory.valid.peek().litToBoolean)
        assert(t.writes==0 && t.issuedBytes==0)
        d.io.cancel.poke(true.B);t.tick();d.io.cancel.poke(false.B)
        for(_<-0 until 5){t.tick();d.io.completion.valid.expect(false.B)}
        t.blockLocal=false;t.finish(4)
        assert(t.writes==0 && t.storedBytes==0)
      }

      t.alignedRgba(false);t.begin();t.blockAck=true;t.cancelOnStore=true;t.flush()
      t.until(t.writes==1);t.cancelOnStore=false;d.io.cancel.poke(false.B)
      assert(t.maxWriteBytes==64 && t.issuedBytes==64)
      for(_<-0 until 6){t.tick();d.io.completion.valid.expect(false.B)}
      t.blockAck=false;t.finish(4,false)
      assert(t.writes==1 && t.storedBytes==64)

      t.alignedRgba(false);t.begin();t.faultWrite=true;t.flush();t.finish(3)
      t.faultWrite=false
      assert(t.writes==1 && t.maxWriteBytes==64 && t.issuedBytes==64 && t.storedBytes==0)

      t.alignedRgba(true);t.expectedStores();t.expectedResolves(Vector("RGBA8"))
      t.begin();t.flush();t.finish();assert(t.writes==32)
      info("TYPED_IMAGE_LINE_GATHER unsentPartialAndFullDiscarded=true heldPrivateDrain=true heldGroupAck=true faultedGroupNotCommitted=true healthyReuse=true")
    }
  }

}
