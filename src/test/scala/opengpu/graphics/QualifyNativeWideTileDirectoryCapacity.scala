package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.DoNotDiscover
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** Explicit metadata-capacity qualification, excluded from ordinary CI discovery.
  * No pixels, rasterization or large-frame rendering are exercised.
  */
@DoNotDiscover
class NativeWideTileDirectoryCapacityQualification extends AnyFlatSpec {
  private val base=0x1000000L
  private val backing=0x20000000L
  private val pageBytes=4096
  private val pageCapacity=PrimitiveBinPageLayout.recordsPerPage(pageBytes)
  case class Ref(p: Long,s: Long,sequence: Long,token: Long)
  case class Tile(id: Int,status: Int,usable: Boolean,head: Long,count: Long,restart: Option[Ref])
  case class Summary(status: Int,opened: Int,pages: Long,count: Long,reads: Long,writes: Long,misses: Long)
  private def ref(tile: Int,n: Int): Ref=Ref(0x90000000L+tile*16+n,0xa0000000L+tile,n*3L+1,tile*100L+n)

  private class MacroDirectory extends TileBinDirectory(8,pageBytes,8,pagedCapacity=65536,
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
    val reported=mutable.HashSet.empty[Int]
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
        assert(reported.add(id),s"duplicate tile $id")
        t.identity.context.expect(3.U); t.identity.pass.expect(7.U); t.identity.generation.expect(11.U)
        results+=Tile(id,t.status.peek().litValue.toInt,t.prefixUsable.peek().litToBoolean,
          if (t.headValid.peek().litToBoolean) t.head.peek().litValue.toLong else 0L,t.committedCount.peek().litValue.toLong,
          if (t.restartValid.peek().litToBoolean) Some(reference(t.restart)) else None)
      }
      d.clock.step(); cycle+=1
    }
    def until(p: => Boolean,max: Int=2000000): Unit = {
      val start=cycle
      while (!p && cycle-start<max) step()
      assert(p,s"stalled at $cycle, results=${results.size} words=${words.size} metadata=$line")
    }
    def begin(count: Int,pages: Int=65536,mutate: TileBinDirectoryDescriptor=>Unit=_=>(),waitReady: Boolean=true): Unit = {
      assert(words.isEmpty && line.isEmpty)
      results.clear(); reported.clear(); tileCount=count
      val b=d.io.begin.bits
      b.poke(0.U.asTypeOf(b)); b.version.poke(3.U); b.tileCount.poke(count.U)
      b.directoryBase.poke(backing.U); b.directoryLimit.poke((backing+64L*count).U)
      b.pool.pageSizeLog2.poke(12.U); b.pool.version.poke(1.U); b.pool.identity.context.poke(3.U); b.pool.identity.pass.poke(7.U)
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
    def append(tile: Int,r: Ref,status: Int=0): Long = {
      offer(tile,r); until(d.io.appended.valid.peek().litToBoolean,max=5000)
      d.io.appended.bits.tile.expect(tile.U)
      assert(reference(d.io.appended.bits.reference)==r)
      reply(status)
    }
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
      val held=p.peek().litValue
      for (_ <- 0 until 5) {
        d.io.begin.ready.expect(false.B)
        d.io.mem.req.valid.expect(false.B); req.valid.expect(false.B)
        step(); d.io.completion.valid.expect(true.B)
        assert(p.peek().litValue==held,"held directory completion changed")
      }
      d.io.completion.ready.poke(true.B); step(); d.io.completion.ready.poke(false.B)
      d.io.busy.expect(false.B); d.io.begin.ready.expect(true.B)
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

  it should "initialize, open and close all 65536 metadata entries without wrapping and then reuse the arena" in {
    require(sys.props.get("opengpu.apiExtentQualification").contains("1"),
      "Run this metadata-only capacity qualification explicitly outside PR/nightly lanes")
    simulate(new MacroDirectory) { dut =>
      val d=new Driver(dut)
      dut.macroModelPresent.expect(true.B)
      d.begin(65536)
      assert(d.lineWrites==65536 && d.lineReads==0,
        "every metadata entry must be initialized before append admission")
      for(tile <- 0 until 65536; word <- 0 until 16)
        assert(d.memory(backing+tile*64L+word*4L)==0L)
      for(tile <- 0 until 65536) {
        assert(d.append(tile,ref(tile,0))==1)
        if((tile&8191)==8191) info(s"WIDE_DIRECTORY_PROGRESS appended=${tile+1} cycles=${d.cycle}")
      }
      d.end(); val full=d.finish()
      assert(full.status==PrimitiveBinStatus.success && full.opened==65536 &&
        full.pages==65536 && full.count==65536 && d.results.size==65536)
      assert(full.reads==65536L*2*64 && full.writes==65536L*3*64 && full.misses==65536L*2)
      for(t <- d.results) {
        assert(t.status==PrimitiveBinStatus.success && t.usable && t.count==1 && t.restart.isEmpty)
        assert(d.chain(t)==Vector(ref(t.id,0)))
        val a=backing+t.id*64L
        assert(d.memory(a)==0x47444402L && d.memory(a+16)==t.id)
        assert((d.memory(a+20)&63)==47 && d.memory(a+32)==1,
          "all final metadata entries must be closed, reported and retain sequence history")
      }
      val fullCycles=d.cycle
      d.begin(2,pages=2)
      for(tile <- 0 until 2) assert(d.append(tile,ref(tile,1))==1)
      d.end(); val reused=d.finish()
      assert(reused.status==PrimitiveBinStatus.success && reused.opened==2 &&
        reused.pages==2 && reused.count==2 && d.results.size==2)
      for(t <- d.results) assert(t.usable && d.chain(t)==Vector(ref(t.id,1)))
      info(s"WIDE_DIRECTORY_CAPACITY entries=65536 initialized=65536 opened=${full.opened} pages=${full.pages} committed=${full.count} reported=65536 highestTile=65535 realBitmapBanks=8 heldCompletion=true healthyReuse=true renderedPixels=0 fullCycles=$fullCycles reuseCycles=${d.cycle-fullCycles}")
    }
  }
}
