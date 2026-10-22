package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import opengpu.testutil.GraphicsTestResolution
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class PagedTileBinDirectorySpec extends AnyFlatSpec {
  private val base=0x10000L
  private val backing=0x8000000L
  private val pageBytes=128
  private val pageCapacity=PrimitiveBinPageLayout.recordsPerPage(pageBytes)
  case class Ref(p: Long,s: Long,sequence: Long,token: Long)
  case class Tile(id: Int,status: Int,usable: Boolean,head: Long,count: Long,restart: Option[Ref])
  case class Summary(status: Int,opened: Int,pages: Long,count: Long,reads: Long,writes: Long,misses: Long)
  private def ref(tile: Int,n: Int): Ref=Ref(0x90000000L+tile*16+n,0xa0000000L+tile,n*3L+1,tile*100L+n)

  private class MacroDirectory extends TileBinDirectory(2,pageBytes,2,pagedCapacity=16384,
    useDirectorySramBlackBoxes=true) {
    val macroModelPresent=IO(Output(Bool()))
    private val resources=Module(new TileSramSimulationResources)
    macroModelPresent:=resources.io.present
  }

  private class Driver(val d: TileBinDirectory) {
    case class Word(address: Long,data: Long,due: Int)
    case class Line(address: Long,write: Boolean,data: BigInt,due: Int,fault: Boolean)
    val memory=mutable.LongMap.empty[Long]
    val words=mutable.ArrayBuffer.empty[Word]
    var line=Option.empty[Line]
    val results=mutable.ArrayBuffer.empty[Tile]
    var cycle=0
    var tileCount=0
    var wordRequests=0
    var lineReads=0
    var lineWrites=0
    var holdTileResults=false
    var holdLineResponses=false
    var stallLine: (Long,Boolean)=>Boolean=(_,_)=>false
    var faultLine: (Long,Boolean)=>Boolean=(_,_)=>false
    private var offered=Option.empty[BigInt]
    private var heldResult=Option.empty[BigInt]
    val req=d.io.directoryMemoryRequest.get
    val resp=d.io.directoryMemoryResponse.get
    d.io.begin.valid.poke(false.B); d.io.begin.bits.poke(0.U.asTypeOf(d.io.begin.bits))
    d.io.append.valid.poke(false.B); d.io.append.bits.poke(0.U.asTypeOf(d.io.append.bits))
    d.io.seal.valid.poke(false.B); d.io.seal.bits.poke(0.U)
    d.io.end.valid.poke(false.B); d.io.end.bits.poke(true.B); d.io.cancel.poke(false.B)
    d.io.appended.ready.poke(true.B); d.io.tileClosed.ready.poke(true.B); d.io.completion.ready.poke(false.B)
    d.io.mem.req.ready.poke(false.B); d.io.mem.resp.valid.poke(false.B)
    d.io.mem.resp.bits.poke(0.U.asTypeOf(d.io.mem.resp.bits)); d.io.mem.responseFault.poke(false.B)
    req.ready.poke(false.B); resp.valid.poke(false.B); resp.bits.poke(0.U.asTypeOf(resp.bits))
    d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
    def reference(r: BinPrimitiveReference): Ref=Ref(r.primitive.peek().litValue.toLong,
      r.state.peek().litValue.toLong,r.sequence.peek().litValue.toLong,r.token.peek().litValue.toLong)
    def lineData(address: Long): BigInt=(0 until 16).foldLeft(BigInt(0))((v,n)=>
      v | (BigInt(memory.getOrElse(address+4*n,0L)&0xffffffffL) << (n*32)))
    def step(): Unit = {
      val address=req.bits.address.peek().litValue.toLong
      val write=req.bits.isWrite.peek().litToBoolean
      val ready=cycle%5!=1 && !stallLine(address,write)
      req.ready.poke(ready.B)
      offered.foreach { old=>req.valid.expect(true.B); assert(req.bits.peek().litValue==old,"metadata request changed under stall/cancel") }
      offered=if (req.valid.peek().litToBoolean && !ready) Some(req.bits.peek().litValue) else None
      resp.valid.poke((line.nonEmpty && line.get.due<=cycle && !holdLineResponses).B)
      line.foreach { p=>
        resp.bits.transactionId.poke(0.U); resp.bits.fault.poke(p.fault.B)
        resp.bits.readData.poke((if (p.write) BigInt(0) else p.data).U)
      }
      if (resp.valid.peek().litToBoolean && resp.ready.peek().litToBoolean) {
        val p=line.get
        if (p.write) for (n <- 0 until 16) memory(p.address+n*4L)=((p.data>>(32*n))&0xffffffffL).toLong
        line=None
      }
      if (req.valid.peek().litToBoolean && ready) {
        assert(line.isEmpty,"metadata transaction overlapped another request")
        assert(address>=backing && address+64<=backing+tileCount*64L && address%64==0)
        req.bits.sizeLog2.expect(6.U); req.bits.byteMask.expect((if (write) (BigInt(1)<<64)-1 else BigInt(0)).U)
        if (write) lineWrites+=1 else lineReads+=1
        line=Some(Line(address,write,if (write) req.bits.writeData.peek().litValue else lineData(address),cycle+2,faultLine(address,write)))
      }
      d.io.mem.req.ready.poke((cycle%5!=3).B)
      val i=words.lastIndexWhere(_.due<=cycle)
      d.io.mem.resp.valid.poke((i>=0).B)
      if (i>=0) {
        val w=words(i)
        d.io.mem.resp.bits.write.poke(true.B); d.io.mem.resp.bits.addr.poke(w.address.U)
        d.io.mem.resp.bits.data.poke(0.U)
        if (d.io.mem.resp.ready.peek().litToBoolean) { memory(w.address)=w.data; words.remove(i) }
      }
      if (d.io.mem.req.valid.peek().litToBoolean && d.io.mem.req.ready.peek().litToBoolean) {
        val p=d.io.mem.req.bits; p.write.expect(true.B)
        val a=p.addr.peek().litValue.toLong
        assert(a>=base && a<backing,"bin word left its arena")
        words+=Word(a,p.data.peek().litValue.toLong,cycle+2+(a.toInt&3)); wordRequests+=1
      }
      d.io.tileClosed.ready.poke((!holdTileResults && cycle%7!=2).B)
      heldResult.foreach { old=>d.io.tileClosed.valid.expect(true.B); assert(d.io.tileClosed.bits.peek().litValue==old) }
      heldResult=if (d.io.tileClosed.valid.peek().litToBoolean && !d.io.tileClosed.ready.peek().litToBoolean)
        Some(d.io.tileClosed.bits.peek().litValue) else None
      if (d.io.tileClosed.valid.peek().litToBoolean && d.io.tileClosed.ready.peek().litToBoolean) {
        val t=d.io.tileClosed.bits; val id=t.tile.peek().litValue.toInt
        assert(!results.exists(_.id==id),s"duplicate tile $id")
        t.identity.context.expect(3.U); t.identity.pass.expect(7.U); t.identity.generation.expect(11.U)
        results+=Tile(id,t.status.peek().litValue.toInt,t.prefixUsable.peek().litToBoolean,
          if (t.headValid.peek().litToBoolean) t.head.peek().litValue.toLong else 0L,t.committedCount.peek().litValue.toLong,
          if (t.restartValid.peek().litToBoolean) Some(reference(t.restart)) else None)
      }
      d.clock.step(); cycle+=1
    }
    def until(p: => Boolean,max: Int=100000): Unit = {
      val start=cycle
      while (!p && cycle-start<max) step()
      assert(p,s"stalled at $cycle, results=${results.size} words=${words.size} metadata=$line")
    }
    def begin(count: Int,pages: Int=20000,mutate: TileBinDirectoryDescriptor=>Unit=_=>(),waitReady: Boolean=true): Unit = {
      assert(words.isEmpty && line.isEmpty)
      results.clear(); tileCount=count
      val b=d.io.begin.bits
      b.poke(0.U.asTypeOf(b)); b.version.poke(2.U); b.tileCount.poke(count.U)
      b.directoryBase.poke(backing.U); b.directoryLimit.poke((backing+64L*count).U)
      b.pool.pageSizeLog2.poke(7.U); b.pool.version.poke(1.U); b.pool.identity.context.poke(3.U); b.pool.identity.pass.poke(7.U)
      b.pool.identity.generation.poke(11.U); b.pool.base.poke(base.U); b.pool.limit.poke((base+pages*pageBytes.toLong).U)
      mutate(b)
      d.io.begin.valid.poke(true.B); until(d.io.begin.ready.peek().litToBoolean)
      step(); d.io.begin.valid.poke(false.B)
      if (waitReady) until(d.io.append.ready.peek().litToBoolean || d.io.completion.valid.peek().litToBoolean)
    }
    def offer(tile: Int,r: Ref): Unit = {
      val a=d.io.append.bits; a.tile.poke(tile.U); a.reference.primitive.poke(r.p.U)
      a.reference.state.poke(r.s.U); a.reference.sequence.poke(r.sequence.U); a.reference.token.poke(r.token.U)
      d.io.append.valid.poke(true.B); until(d.io.append.ready.peek().litToBoolean)
      step(); d.io.append.valid.poke(false.B)
    }
    def reply(status: Int): Long = {
      until(d.io.appended.valid.peek().litToBoolean,max=5000)
      d.io.appended.bits.status.expect(status.U)
      val count=d.io.appended.bits.committedCount.peek().litValue.toLong
      step(); count
    }
    def append(tile: Int,r: Ref,status: Int=0): Long = { offer(tile,r); reply(status) }
    def seal(tile: Int): Unit = {
      d.io.seal.bits.poke(tile.U); d.io.seal.valid.poke(true.B)
      until(d.io.seal.ready.peek().litToBoolean); step(); d.io.seal.valid.poke(false.B)
      until(d.io.append.ready.peek().litToBoolean || d.io.completion.valid.peek().litToBoolean)
    }
    def end(): Unit = {
      d.io.end.valid.poke(true.B); until(d.io.end.ready.peek().litToBoolean)
      step(); d.io.end.valid.poke(false.B)
    }
    def finish(): Summary = {
      until(d.io.completion.valid.peek().litToBoolean)
      val p=d.io.completion.bits
      val s=Summary(p.status.peek().litValue.toInt,p.tilesOpened.peek().litValue.toInt,
        p.allocatedPages.peek().litValue.toLong,p.committedCount.peek().litValue.toLong,
        p.directoryReadBytes.peek().litValue.toLong,p.directoryWriteBytes.peek().litValue.toLong,
        p.directoryMisses.peek().litValue.toLong)
      assert(words.isEmpty && line.isEmpty)
      d.io.completion.ready.poke(true.B); step(); d.io.completion.ready.poke(false.B)
      s
    }
    def chain(t: Tile): Vector[Ref] = {
      val out=Vector.newBuilder[Ref]; var page=t.head; var seen=Set.empty[Long]
      while (page!=0) {
        assert(!seen(page)); seen+=page
        assert(memory(page)==PrimitiveBinPageLayout.magic.toLong && memory(page+24)==t.id)
        val count=memory(page+8).toInt; assert(count>0 && count<=pageCapacity)
        for (i <- 0 until count) { val a=page+32+i*16; out+=Ref(memory(a),memory(a+4),memory(a+8),memory(a+12)) }
        page=memory(page+4)
      }
      out.result()
    }
  }

  it should "preserve chains, ordering and reported seals across repeated direct-map evictions" in {
    simulate(new TileBinDirectory(tileSlots=2,pageBytes=pageBytes,writeCredits=2,pagedCapacity=32)) { dut=>
      val d=new Driver(dut); d.begin(16)
      for (n <- 0 until 8; tile <- 0 until 16) assert(d.append(tile,ref(tile,n))==n+1)
      d.seal(0); d.seal(2); d.seal(0)
      assert(d.append(0,ref(0,8),PrimitiveBinStatus.closed)==8)
      assert(d.append(4,ref(4,0),PrimitiveBinStatus.invalidOrder)==8)
      d.end(); val s=d.finish()
      assert(s.status==PrimitiveBinStatus.invalidOrder && s.opened==16 && s.count==128 && s.pages==32)
      assert(d.results.size==16)
      for (t <- d.results) {
        assert(d.chain(t)==Vector.tabulate(8)(ref(t.id,_)))
        assert(t.count==8 && (t.status==0 || (t.id==4 && t.status==PrimitiveBinStatus.invalidOrder)))
        val a=backing+t.id*64L
        assert(d.memory(a)==0x47444401L && d.memory(a+16)==t.id && d.memory(a+32)==8)
        assert((d.memory(a+20)&15)==15,"final dirty cache state was not flushed")
      }
    }
  }

  it should "page arbitrary tile IDs through a single resident entry" in {
    simulate(new TileBinDirectory(1,pageBytes,2,pagedCapacity=16)) { dut=>
      val d=new Driver(dut); d.begin(8)
      for (n <- 0 until 3; tile <- 0 until 8) d.append(tile,ref(tile,n))
      d.end(); val s=d.finish()
      assert(s.status==0 && s.count==24 && d.results.size==8)
      for (t <- d.results) assert(d.chain(t)==Vector.tabulate(3)(ref(t.id,_)))
    }
  }

  it should s"build and close a complete ${GraphicsTestResolution.label} 16x16 tile directory with only eight resident entries" in {
    val tiles=GraphicsTestResolution.tileCount(16)
    simulate(new TileBinDirectory(tileSlots=8,pageBytes=pageBytes,writeCredits=2,pagedCapacity=16384)) { dut=>
      val d=new Driver(dut); d.begin(tiles,pages=tiles)
      for (tile <- 0 until tiles) d.append(tile,ref(tile,0))
      d.end(); val s=d.finish()
      assert(s.status==0 && s.opened==tiles && s.pages==tiles && s.count==tiles && d.results.size==tiles)
      for (t <- d.results) assert(t.usable && d.chain(t)==Vector(ref(t.id,0)))
      assert(s.reads==tiles*2L*64 && s.writes==tiles*3L*64 && s.misses==tiles*2L)
      info(s"PAGED_DIRECTORY tiles=$tiles resident=8 references=${s.count} metadataReadBytes=${s.reads} metadataWriteBytes=${s.writes} cycles=${d.cycle}")
    }
  }

  it should "preserve exact exhausted restart references after eviction while older pages still accept work" in {
    simulate(new TileBinDirectory(2,pageBytes,2,pagedCapacity=32)) { dut=>
      val d=new Driver(dut); d.begin(16,pages=8)
      for (tile <- 0 until 8) d.append(tile,ref(tile,0))
      for (tile <- 8 until 16) assert(d.append(tile,ref(tile,0),PrimitiveBinStatus.exhausted)==0)
      d.append(0,ref(0,1)); d.append(2,ref(2,1))
      d.append(8,ref(8,1),PrimitiveBinStatus.closed)
      d.end(); val s=d.finish()
      assert(s.status==PrimitiveBinStatus.exhausted && s.pages==8 && s.opened==8 && s.count==10)
      assert(d.results.size==16)
      for (t <- d.results) {
        if (t.id>=8) assert(t.status==PrimitiveBinStatus.exhausted && t.usable && t.count==0 && t.restart.contains(ref(t.id,0)))
        else assert(t.status==0 && d.chain(t)==Vector.tabulate(if (t.id==0 || t.id==2) 2 else 1)(ref(t.id,_)))
      }
    }
  }

  for (writeFault <- Seq(false,true)) {
    it should s"drain metadata faults without duplicating previously reported tiles (write=$writeFault)" in {
      simulate(new TileBinDirectory(2,pageBytes,2,pagedCapacity=16)) { dut=>
        val d=new Driver(dut); d.begin(8)
        d.append(0,ref(0,0)); d.seal(0)
        d.append(2,ref(2,0)); d.append(4,ref(4,0))
        d.faultLine=(a,w)=> if (writeFault) w && a==backing+4*64 else !w && a==backing+2*64
        d.append(2,ref(2,1),PrimitiveBinStatus.memoryFault)
        val s=d.finish()
        assert(s.status==PrimitiveBinStatus.memoryFault && s.count==3 && s.opened==3)
        assert(d.results.map(_.id).sorted==(0 until 8) && d.results.count(_.status==0)==1)
        assert(d.results.filter(_.id!=0).forall(t=> !t.usable && t.status==PrimitiveBinStatus.memoryFault))
        d.faultLine=(_,_)=>false
        d.begin(8); d.append(2,ref(2,0)); d.end(); assert(d.finish().status==0)
        assert(d.results.size==1 && d.chain(d.results.head)==Vector(ref(2,0)))
      }
    }
  }

  it should "retain an offered metadata read across cancellation and prioritize its late fault" in {
    simulate(new TileBinDirectory(2,pageBytes,2,pagedCapacity=16)) { dut=>
      val d=new Driver(dut); d.begin(8); d.append(0,ref(0,0)); d.seal(0)
      d.stallLine=(a,w)=> !w && a==backing+2*64
      d.faultLine=d.stallLine; d.offer(2,ref(2,0))
      d.until(d.req.valid.peek().litToBoolean && !d.req.bits.isWrite.peek().litToBoolean)
      dut.io.cancel.poke(true.B); d.step(); dut.io.cancel.poke(false.B)
      for (_ <- 0 until 5) d.step()
      d.holdLineResponses=true; d.stallLine=(_,_)=>false
      d.until(d.line.nonEmpty)
      for (_ <- 0 until 5) { dut.io.completion.valid.expect(false.B); d.step() }
      d.holdLineResponses=false; d.reply(PrimitiveBinStatus.memoryFault)
      assert(d.finish().status==PrimitiveBinStatus.memoryFault && d.results.size==8)
    }
  }

  for (corrupt <- Seq(false,true)) {
    it should s"finish accepted metadata validation through cancellation (corrupt=$corrupt)" in {
      simulate(new TileBinDirectory(2,pageBytes,2,pagedCapacity=16)) { dut=>
        val d=new Driver(dut); d.begin(8); d.append(0,ref(0,0)); d.append(2,ref(2,0))
        if (corrupt) d.memory(backing+12)=12
        d.stallLine=(a,w)=> !w && a==backing
        d.offer(0,ref(0,1))
        d.until(d.req.valid.peek().litToBoolean && !d.req.bits.isWrite.peek().litToBoolean)
        d.stallLine=(_,_)=>false
        d.until(d.line.nonEmpty); d.until(d.line.isEmpty)
        dut.io.cancel.poke(true.B); d.step(); dut.io.cancel.poke(false.B)
        val status=if (corrupt) PrimitiveBinStatus.memoryFault else PrimitiveBinStatus.cancelled
        d.reply(status); val s=d.finish()
        assert(s.status==status && s.count==2 && d.results.size==8)
      }
    }
  }

  it should "reject corrupt owner, tile, pointer and flag metadata before following a chain" in {
    simulate(new TileBinDirectory(2,pageBytes,2,pagedCapacity=16)) { dut=>
      val d=new Driver(dut)
      val corrupt=Seq[(Int,Long)]((3,12),(4,2),(6,base+4),(7,backing),(5,0x23),(0,0))
      for ((word,value) <- corrupt) {
        d.begin(8); d.append(0,ref(0,0)); d.append(2,ref(2,0))
        d.memory(backing+word*4L)=value
        val before=d.wordRequests
        d.append(0,ref(0,1),PrimitiveBinStatus.memoryFault)
        val s=d.finish()
        assert(s.status==PrimitiveBinStatus.memoryFault && s.count==2 && d.wordRequests==before)
        assert(d.results.size==8 && d.results.forall(t=> !t.usable))
      }
    }
  }

  it should "wait for the final dirty flush and invalidate the pass if that flush faults" in {
    simulate(new TileBinDirectory(2,pageBytes,2,pagedCapacity=16)) { dut=>
      val d=new Driver(dut); d.begin(2); d.append(0,ref(0,0)); d.append(1,ref(1,0))
      d.faultLine=(a,w)=>w && a==backing
      d.end(); val s=d.finish()
      assert(s.status==PrimitiveBinStatus.memoryFault && s.count==2 && d.results.map(_.id).sorted==Vector(0,1))
      assert(d.results.forall(_.status==0),"fault drain re-reported tiles already handed to replay")
    }
  }

  it should "retain report bits on both sides of the real SRAM bank boundary" in {
    val tiles=8194 // Cross the 8192-bit report-bank boundary without a full-frame walk.
    simulate(new MacroDirectory) { dut=>
      val d=new Driver(dut); d.begin(tiles)
      for (tile <- Seq(8191,8192,tiles-1)) d.seal(tile)
      dut.io.cancel.poke(true.B); d.step(); dut.io.cancel.poke(false.B)
      val s=d.finish()
      assert(s.status==PrimitiveBinStatus.cancelled && s.opened==0 && s.count==0)
      assert(d.results.size==tiles && d.results.count(_.status==0)==3)
      assert(d.results.filter(_.status==0).map(_.id).sorted==Vector(8191,8192,tiles-1))
      assert(d.wordRequests==0)
      info(s"PAGED_BITMAP_MACRO tiles=$tiles reportBanks=2 uniqueResults=${d.results.size} cycles=${d.cycle}")
    }
  }

  it should "reject wrapped, short, overlapping or unsupported backing descriptors before traffic" in {
    simulate(new TileBinDirectory(2,pageBytes,2,pagedCapacity=16)) { dut=>
      val d=new Driver(dut)
      val cases=Seq[TileBinDirectoryDescriptor=>Unit](
        _.directoryBase.poke((backing+4).U), _.directoryLimit.poke((backing+64).U),
        b=> { b.directoryBase.poke((0x100000000L-64).U); b.directoryLimit.poke(0x100000000L.U) },
        b=> { b.directoryBase.poke(base.U); b.directoryLimit.poke((base+512).U) },
        _.version.poke(1.U), _.tileCount.poke(17.U), _.tileCount.poke(0.U))
      for (f <- cases) {
        val before=d.wordRequests+d.lineReads+d.lineWrites
        d.begin(8,mutate=f); assert(d.finish().status==PrimitiveBinStatus.invalidDescriptor)
        assert(d.wordRequests+d.lineReads+d.lineWrites==before)
      }
    }
  }
  it should "reject an evicted metadata head or tail inside a reserved spill gap before any bin write" in {
    simulate(new TileBinDirectory(2,pageBytes,2,pagedCapacity=16)) { dut=>
      val d=new Driver(dut)
      for (word <- Seq(6,7)) {
        d.begin(8,mutate=b=>{
          val p=b.pool
          p.version.poke(2.U); p.initialLimit.poke((base+128).U); p.spillCount.poke(1.U)
          p.spills(0).base.poke((base+4096).U); p.spills(0).limit.poke((base+4096+128).U)
          p.limit.poke((base+4096+128).U)
        })
        d.append(0,ref(0,0)); d.append(2,ref(2,0))
        d.memory(backing+word*4L)=base+128
        val before=d.wordRequests
        d.append(0,ref(0,1),PrimitiveBinStatus.memoryFault)
        val s=d.finish()
        assert(s.status==PrimitiveBinStatus.memoryFault && s.count==2 && d.wordRequests==before)
        assert(d.results.size==8 && d.results.forall(t=> !t.usable))
      }
    }
  }

  it should "retain paged sequence history through eviction and batches where a tile has no references" in {
    simulate(new TileBinDirectory(2,pageBytes,2,pagedCapacity=16)) { dut=>
      val d=new Driver(dut)
      def binding(resume: Boolean)(b: TileBinDirectoryDescriptor): Unit = {
        b.version.poke(3.U); b.resume.poke(resume.B)
      }
      d.begin(8,mutate=binding(false)); d.append(0,ref(0,0)); d.append(2,ref(2,0))
      d.end(); assert(d.finish().status==0)
      d.begin(8,mutate=binding(true)); d.append(2,ref(2,1)); d.end(); assert(d.finish().status==0)
      d.begin(8,mutate=binding(true))
      val before=d.wordRequests
      d.append(0,ref(0,0),PrimitiveBinStatus.invalidOrder)
      d.end(); val failed=d.finish()
      assert(failed.status==PrimitiveBinStatus.invalidOrder && failed.count==0 && d.wordRequests==before)
      val writes=d.lineWrites; val reads=d.lineReads
      d.begin(8,mutate=binding(true)); assert(d.finish().status==PrimitiveBinStatus.invalidDescriptor)
      assert(d.lineWrites==writes && d.lineReads==reads)
      d.begin(8,mutate=binding(false)); d.append(0,ref(0,0)); d.end(); assert(d.finish().status==0)
    }
  }

  it should "validate an accepted continuation read through cancellation and invalidate corrupt history" in {
    simulate(new TileBinDirectory(2,pageBytes,2,pagedCapacity=16)) { dut=>
      val d=new Driver(dut)
      d.begin(8,mutate=_.version.poke(3.U)); d.append(0,ref(0,0)); d.end(); assert(d.finish().status==0)
      d.memory(backing+12)=12
      d.holdLineResponses=true
      d.begin(8,mutate=b=>{b.version.poke(3.U); b.resume.poke(true.B)},waitReady=false)
      d.until(d.line.nonEmpty)
      assert(!d.line.get.write && d.line.get.address==backing)
      dut.io.cancel.poke(true.B); d.step(); dut.io.cancel.poke(false.B)
      for (_ <- 0 until 5) { dut.io.completion.valid.expect(false.B); d.step() }
      d.holdLineResponses=false
      assert(d.finish().status==PrimitiveBinStatus.memoryFault)
      d.begin(8,mutate=b=>{b.version.poke(3.U); b.resume.poke(true.B)})
      assert(d.finish().status==PrimitiveBinStatus.invalidDescriptor)
      d.begin(8,mutate=_.version.poke(3.U)); d.append(0,ref(0,0)); d.end(); assert(d.finish().status==0)
    }
  }

  it should "reject cleared or unpublished continuation history instead of forgetting older sequences" in {
    simulate(new TileBinDirectory(2,pageBytes,2,pagedCapacity=16)) { dut=>
      val d=new Driver(dut)
      for (clear <- Seq(true,false)) {
        d.begin(8,mutate=_.version.poke(3.U)); d.append(0,ref(0,0)); d.end()
        assert(d.finish().status==0)
        assert(d.results.map(_.id).sorted==Vector.range(0,8))
        assert(d.memory(backing+7*64)==0x47444402L && (d.memory(backing+7*64+20)&12)==12)
        if (clear) for (n <- 0 until 16) d.memory(backing+n*4L)=0
        else d.memory(backing+20)=d.memory(backing+20)& ~8L
        val before=d.wordRequests
        d.begin(8,mutate=b=>{b.version.poke(3.U); b.resume.poke(true.B)})
        assert(d.finish().status==PrimitiveBinStatus.memoryFault && d.wordRequests==before)
      }
    }
  }

}
