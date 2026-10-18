package opengpu.graphics

import chisel3._
import opengpu.config.CachePolicy
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class PrimitiveBinReaderSpec extends AnyFlatSpec {
  case class Ref(primitive: Long,state: Long,sequence: Long,token: Long)
  case class Image(base: Long,limit: Long,head: Long,references: Vector[Ref],
    words: mutable.LongMap[Long],context: Long,pass: Long,generation: Int,tile: Long)
  case class Result(status: Int,emitted: Int,pages: Int,lines: Int)
  private def reference(n: Int): Ref =
    Ref(0x80000000L+n*16,0xf0000000L+n*8,n*3L+1,0xffff0000L+n)
  private def image(pageBytes: Int,count: Int,base: Long = 0x10000,
                    gap: Int = 1,generation: Int = 19,seed: Int = 0): Image = {
    val capacity = (pageBytes-32)/16
    val refs = Vector.tabulate(count)(n => reference(n+seed))
    val groups = refs.grouped(capacity).toVector
    val pages = groups.indices.map(n => base+n.toLong*gap*pageBytes)
    val words = mutable.LongMap.empty[Long]
    for ((rs,n) <- groups.zipWithIndex) {
      val p = pages(n)
      val header = Seq(0x47424901L,if (n+1 < pages.size) pages(n+1) else 0L,
        rs.size.toLong,0x13579bdfL,0x2468ace0L,generation.toLong,0x87654321L,n.toLong*gap)
      header.zipWithIndex.foreach { case (v,i) => words(p+i*4)=v }
      for ((ref,i) <- rs.zipWithIndex) {
        Seq(ref.primitive,ref.state,ref.sequence,ref.token).zipWithIndex.foreach {
          case (v,w) => words(p+32+i*16+w*4)=v
        }
      }
    }
    Image(base,if (pages.isEmpty) base else pages.last+pageBytes,base,refs,
      words,0x13579bdfL,0x2468ace0L,generation,0x87654321L)
  }

  private class Driver(val d: PrimitiveBinReader,val pageBytes: Int) {
    case class Pending(address: Long,due: Int,fault: Boolean)
    var active = image(pageBytes,0)
    val requests = mutable.ArrayBuffer.empty[Long]
    val outputs = mutable.ArrayBuffer.empty[Ref]
    val outputCycles = mutable.ArrayBuffer.empty[Int]
    var pending = Option.empty[Pending]
    var cycle = 0
    var returnedFaults = 0
    var holdRequests = false
    var holdResponses = false
    var holdOutputs = false
    var strayResponse = false
    var alwaysReady = false
    var responseDelay = 3
    var faultAddress = Option.empty[Long]
    private var heldRequest = Option.empty[BigInt]
    private var heldOutput = Option.empty[BigInt]
    d.io.begin.valid.poke(false.B); d.io.begin.bits.poke(0.U.asTypeOf(d.io.begin.bits))
    d.io.cancel.poke(false.B); d.io.reference.ready.poke(false.B)
    d.io.completion.ready.poke(false.B); d.io.memoryRequest.ready.poke(false.B)
    d.io.memoryResponse.valid.poke(false.B)
    d.io.memoryResponse.bits.poke(0.U.asTypeOf(d.io.memoryResponse.bits))
    d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)

    def step(): Unit = {
      val ready = !holdRequests && (alwaysReady || cycle%5 != 2)
      d.io.memoryRequest.ready.poke(ready.B)
      heldRequest.foreach { old =>
        d.io.memoryRequest.valid.expect(true.B)
        assert(d.io.memoryRequest.bits.peek().litValue == old,"offered read changed")
      }
      heldRequest = if (d.io.memoryRequest.valid.peek().litToBoolean && !ready)
        Some(d.io.memoryRequest.bits.peek().litValue) else None
      val response = pending.filter(p => p.due <= cycle && !holdResponses)
      d.io.memoryResponse.valid.poke(response.nonEmpty.B)
      response.foreach { p =>
        val data = (0 until 16).foldLeft(BigInt(0)) { (out,n) =>
          out | (BigInt(active.words.getOrElse(p.address+n*4,0xa5a5a5a5L)) << (32*n))
        }
        d.io.memoryResponse.bits.readData.poke(data.U)
        d.io.memoryResponse.bits.fault.poke(p.fault.B)
        d.io.memoryResponse.bits.transactionId.poke((if (strayResponse) 4 else 3).U)
        d.io.memoryResponse.ready.expect((!strayResponse).B)
        if (!strayResponse) {
          if (p.fault) returnedFaults += 1
          pending = None
        }
      }
      val outputReady = !holdOutputs && (alwaysReady || cycle%7 < 4)
      d.io.reference.ready.poke(outputReady.B)
      if (d.io.reference.valid.peek().litToBoolean) {
        val bits = d.io.reference.bits
        heldOutput.foreach(old => assert(bits.peek().litValue == old,"offered reference changed"))
        bits.identity.context.expect(active.context.U); bits.identity.pass.expect(active.pass.U)
        bits.identity.generation.expect(active.generation.U); bits.tile.expect(active.tile.U)
        if (outputReady) {
          bits.ordinal.expect(outputs.size.U)
          val r = bits.reference
          val got = Ref(r.primitive.peek().litValue.toLong,r.state.peek().litValue.toLong,
            r.sequence.peek().litValue.toLong,r.token.peek().litValue.toLong)
          assert(outputs.size < active.references.size)
          assert(got == active.references(outputs.size),s"wrong reference at${outputs.size}: $got")
          outputs += got; outputCycles += cycle; heldOutput = None
        } else heldOutput = Some(bits.peek().litValue)
      } else assert(heldOutput.isEmpty,"offered reference valid withdrawn")
      val fire = d.io.memoryRequest.valid.peek().litToBoolean && ready
      d.io.readBytes.expect((if (fire) 64 else 0).U)
      if (fire) {
        val p = d.io.memoryRequest.bits
        val address = p.address.peek().litValue.toLong
        p.transactionId.expect(3.U); p.isWrite.expect(false.B); p.sizeLog2.expect(6.U)
        p.cachePolicy.expect(CachePolicy.uncached)
        p.cacheClient.expect(false.B); p.cacheResident.expect(false.B)
        p.byteMask.expect(0.U); p.writeData.expect(0.U)
        assert(pending.isEmpty,"reader exceeded its one-owned-line window")
        assert(address%64 == 0 && address >= active.base && address+64 <= active.limit,
          s"out-of-arena line $address [${active.base},${active.limit})")
        requests += address
        pending = Some(Pending(address,cycle+responseDelay,faultAddress.contains(address)))
      }
      d.clock.step(); cycle += 1
    }
    def until(condition: => Boolean): Unit = {
      val start = cycle
      while (!condition && cycle-start < 10000) step()
      assert(condition,s"reader stopped progressing at$cycle")
    }
    def begin(i: Image,version: Int = 1,poolVersion: Int = 1,
      headValid: Option[Boolean] = None,head: Option[Long] = None,
      count: Option[Long] = None,base: Option[Long] = None,
      limit: Option[Long] = None,pageLog: Option[Int] = None,
      mutate: BinPagePoolDescriptor=>Unit = _=>()): Unit = {
      assert(pending.isEmpty)
      active = i; requests.clear(); outputs.clear(); outputCycles.clear(); returnedFaults = 0
      val b = d.io.begin.bits
      b.version.poke(version.U); b.pool.version.poke(poolVersion.U)
      b.pool.identity.context.poke(i.context.U); b.pool.identity.pass.poke(i.pass.U)
      b.pool.identity.generation.poke(i.generation.U); b.tile.poke(i.tile.U)
      b.pool.base.poke(base.getOrElse(i.base).U); b.pool.limit.poke(limit.getOrElse(i.limit).U)
      b.pool.pageSizeLog2.poke(pageLog.getOrElse(Integer.numberOfTrailingZeros(pageBytes)).U)
      mutate(b.pool)
      b.headValid.poke(headValid.getOrElse(i.references.nonEmpty).B)
      b.head.poke(head.getOrElse(i.head).U); b.count.poke(count.getOrElse(i.references.size.toLong).U)
      d.io.begin.valid.poke(true.B); until(d.io.begin.ready.peek().litToBoolean)
      step(); d.io.begin.valid.poke(false.B); b.poke(0.U.asTypeOf(b))
    }
    def cancel(): Unit = { d.io.cancel.poke(true.B); step(); d.io.cancel.poke(false.B) }
    def finish(): Result = {
      until(d.io.completion.valid.peek().litToBoolean)
      val b = d.io.completion.bits
      b.identity.context.expect(active.context.U); b.identity.pass.expect(active.pass.U)
      b.identity.generation.expect(active.generation.U); b.tile.expect(active.tile.U)
      val result = Result(b.status.peek().litValue.toInt,b.emitted.peek().litValue.toInt,
        b.pages.peek().litValue.toInt,b.readLines.peek().litValue.toInt)
      assert(result.emitted == outputs.size && result.lines == requests.size && pending.isEmpty)
      val held = b.peek().litValue
      for (_ <- 0 until 4) {
        d.io.begin.ready.expect(false.B); d.io.memoryRequest.valid.expect(false.B)
        d.io.reference.valid.expect(false.B); step()
        d.io.completion.valid.expect(true.B); assert(b.peek().litValue == held)
      }
      d.io.completion.ready.poke(true.B); step(); d.io.completion.ready.poke(false.B)
      d.io.busy.expect(false.B); d.io.begin.ready.expect(true.B)
      result
    }
  }
  private def reader(bytes: Int): PrimitiveBinReader =
    new PrimitiveBinReader(pageBytes=bytes,memoryTransactions=8,transactionId=3)

  for ((bytes,count,base,gap) <- Seq((64,5,0L,1),(128,20,0x10000L,4),
      (4096,256,0xffffe000L,1))) {
    it should s"replay $count references with pageBytes=$bytes and gap=$gap" in {
      simulate(reader(bytes)) { dut =>
        val d = new Driver(dut,bytes)
        d.alwaysReady = true; d.responseDelay = 1
        val i = image(bytes,count,base,gap)
        d.begin(i)
        val result = d.finish()
        assert(result.status == PrimitiveBinStatus.success && d.outputs.toVector == i.references)
        val groups = i.references.grouped((bytes-32)/16).toVector
        val expectedLines = groups.zipWithIndex.flatMap { case (rs,n) =>
          val p = base+n.toLong*gap*bytes
          (0 until ((32+rs.size*16+63)/64)).map(line => p+line*64)
        }
        assert(d.requests.toVector == expectedLines)
        assert(result.pages == groups.size)
        if (bytes > 64) assert(d.outputCycles.sliding(4).exists(x => x.size == 4 && x.last-x.head == 3))
        info(s"BIN_READ_REFS=$count pageBytes=$bytes lines=${result.lines} pages=${result.pages} cycles=${d.cycle}")
      }
    }
  }

  it should "reject malformed descriptors without any read and admit an empty bounded bin" in {
    simulate(reader(128)) { dut =>
      val d = new Driver(dut,128)
      val i = image(128,2)
      val cases: Seq[() => Unit] = Seq(
        () => d.begin(i,version=2),() => d.begin(i,poolVersion=2),
        () => d.begin(i,pageLog=Some(6)),() => d.begin(i,base=Some(i.base+1)),
        () => d.begin(i,limit=Some(i.limit+1)),() => d.begin(i,limit=Some((1L<<32)+128)),
        () => d.begin(i,head=Some(i.head+1)),() => d.begin(i,head=Some(i.limit)),
        () => d.begin(i,headValid=Some(false)),() => d.begin(i,count=Some(0)),
        () => d.begin(i,count=Some(7)))
      for (start <- cases) {
        start(); val result = d.finish()
        assert(result.status == PrimitiveBinStatus.invalidDescriptor && result.emitted == 0 && result.lines == 0)
      }
      d.begin(image(128,0),head=Some(0xffffffffL))
      val empty = d.finish()
      assert(empty.status == PrimitiveBinStatus.success && empty.emitted == 0 && empty.lines == 0)
    }
  }

  it should "reject corrupt page ownership, counts, ordinals and links before exposing a page" in {
    simulate(reader(128)) { dut =>
      val d = new Driver(dut,128)
      val corruptions = Seq((0,0L),(12,1L),(16,1L),(20,18L),(24,1L),(28,1L),
        (8,0L),(8,7L),(4,0L),(4,0x10000L),(4,0x10001L),(4,0x11000L))
      for ((offset,value) <- corruptions) {
        val i = image(128,20)
        i.words(i.head+offset) = value
        d.begin(i); val result = d.finish()
        assert(result.status == PrimitiveBinReadStatus.invalidPage && result.emitted == 0 && result.pages == 0)
      }
      val extra = image(128,20)
      d.begin(extra,count=Some(6))
      assert(d.finish().status == PrimitiveBinReadStatus.invalidPage)
      val later = image(128,20)
      later.words(later.head+128+20) = 18
      d.begin(later)
      val failed = d.finish()
      assert(failed.status == PrimitiveBinReadStatus.invalidPage && failed.emitted == 6 && failed.pages == 1)
    }
  }

  it should "reject duplicate or wrapped reference order across page boundaries" in {
    simulate(reader(128)) { dut =>
      val d = new Driver(dut,128)
      val duplicate = image(128,20)
      duplicate.words(duplicate.head+128+32+8) = duplicate.references(5).sequence
      d.begin(duplicate)
      val result = d.finish()
      assert(result.status == PrimitiveBinStatus.invalidOrder && result.emitted == 6)
      val wrapped = image(128,2)
      wrapped.words(wrapped.head+32+8) = 0xffffffffL
      wrapped.words(wrapped.head+48+8) = 0
      val expected = wrapped.copy(references=Vector(wrapped.references(0).copy(sequence=0xffffffffL),wrapped.references(1)))
      d.begin(expected)
      assert(d.finish().status == PrimitiveBinStatus.invalidOrder && d.outputs.size == 1)
    }
  }

  it should "report read faults without zero references and rebind fresh ownership on reused lines" in {
    simulate(reader(128)) { dut =>
      val d = new Driver(dut,128)
      val first = image(128,6)
      d.faultAddress = Some(first.head)
      d.begin(first)
      val failed = d.finish()
      assert(failed.status == PrimitiveBinStatus.memoryFault && failed.emitted == 0)
      d.faultAddress = None
      val fresh = image(128,6,generation=20,seed=10).copy(context=0x82345678L,pass=0x98765432L,tile=7)
      fresh.words(fresh.head+12) = fresh.context
      fresh.words(fresh.head+16) = fresh.pass
      fresh.words(fresh.head+24) = fresh.tile
      d.begin(fresh)
      assert(d.finish().status == PrimitiveBinStatus.success)
      assert(d.outputs.toVector == fresh.references && d.requests.size == 2)
    }
  }

  it should "retain an offered read through cancellation and wait for its owned response" in {
    simulate(reader(128)) { dut =>
      val d = new Driver(dut,128)
      d.holdRequests = true; d.holdResponses = true
      d.begin(image(128,6))
      d.until(dut.io.memoryRequest.valid.peek().litToBoolean)
      val held = dut.io.memoryRequest.bits.peek().litValue
      d.cancel()
      for (_ <- 0 until 5) {
        d.step(); dut.io.completion.valid.expect(false.B)
        dut.io.memoryRequest.valid.expect(true.B)
        assert(dut.io.memoryRequest.bits.peek().litValue == held)
      }
      d.holdRequests = false
      d.until(d.pending.nonEmpty)
      for (_ <- 0 until 5) { d.step(); dut.io.completion.valid.expect(false.B) }
      d.holdResponses = false
      val result = d.finish()
      assert(result.status == PrimitiveBinStatus.cancelled && result.emitted == 0 && result.lines == 1)
      d.begin(image(128,2,generation=20))
      assert(d.finish().status == PrimitiveBinStatus.success)
    }
  }

  it should "preserve an offered reference while a following line faults" in {
    simulate(reader(128)) { dut =>
      val d = new Driver(dut,128)
      d.alwaysReady = true
      val i = image(128,6)
      d.faultAddress = Some(i.head+64)
      d.begin(i)
      d.until(d.outputs.size == 1)
      d.holdOutputs = true
      d.until(d.returnedFaults == 1)
      val held = dut.io.reference.bits.peek().litValue
      for (_ <- 0 until 5) {
        d.step(); dut.io.completion.valid.expect(false.B)
        dut.io.reference.valid.expect(true.B)
        assert(dut.io.reference.bits.peek().litValue == held)
      }
      d.holdOutputs = false
      val result = d.finish()
      assert(result.status == PrimitiveBinStatus.memoryFault && result.emitted == 2 && result.lines == 2)
    }
  }

  it should "deliver only an already offered reference when cancel coincides with output readiness" in {
    simulate(reader(128)) { dut =>
      val d = new Driver(dut,128)
      d.alwaysReady = true; d.holdOutputs = true
      d.begin(image(128,6))
      d.until(dut.io.reference.valid.peek().litToBoolean)
      for (_ <- 0 until 3) d.step()
      d.holdOutputs = false; d.cancel()
      val result = d.finish()
      assert(result.status == PrimitiveBinStatus.cancelled && result.emitted == 1 && d.outputs.size == 1)
    }
  }

  it should "ignore an unowned response tag and read the last line ending at4GiB" in {
    simulate(reader(64)) { dut =>
      val d = new Driver(dut,64)
      d.strayResponse = true
      d.begin(image(64,2,base=0xffffffc0L))
      d.until(d.pending.exists(_.due <= d.cycle))
      for (_ <- 0 until 5) { d.step(); dut.io.completion.valid.expect(false.B) }
      d.strayResponse = false
      val result = d.finish()
      assert(result.status == PrimitiveBinStatus.success && result.emitted == 2 && result.lines == 1)
      assert(d.requests.toVector == Vector(0xffffffc0L))
    }
  }
  it should "replay segmented chains and reject heads or forward links into reserved gaps before fetching them" in {
    simulate(reader(128)) { dut=>
      val d=new Driver(dut,128)
      val i=image(128,20,gap=8)
      def segments(p: BinPagePoolDescriptor): Unit = {
        p.initialLimit.poke((i.base+128).U); p.spillCount.poke(3.U)
        for (n <- 0 until 3) {
          p.spills(n).base.poke((i.base+(n+1)*8*128).U)
          p.spills(n).limit.poke((i.base+(n+1)*8*128+128).U)
        }
      }
      d.begin(i,poolVersion=2,mutate=segments)
      val good=d.finish()
      assert(good.status==PrimitiveBinStatus.success && good.emitted==20 && good.pages==4)
      assert(d.requests.forall(a=>(a-i.base)%(8*128)<128))
      d.begin(i,poolVersion=2,head=Some(i.base+128),mutate=segments)
      val head=d.finish()
      assert(head.status==PrimitiveBinStatus.invalidDescriptor && head.lines==0 && head.emitted==0)
      val badLink=image(128,20,gap=8)
      badLink.words(badLink.head+4)=badLink.base+128
      d.begin(badLink,poolVersion=2,mutate=segments)
      val link=d.finish()
      assert(link.status==PrimitiveBinReadStatus.invalidPage && link.emitted==0)
      assert(d.requests.toVector==Vector(i.base))
      d.begin(i,poolVersion=2,count=Some(25),mutate=segments)
      val capacity=d.finish()
      assert(capacity.status==PrimitiveBinStatus.invalidDescriptor && capacity.lines==0)
      d.begin(i,poolVersion=2,mutate=p=>{segments(p); p.spills(2).base.poke((i.base+128).U)})
      val malformed=d.finish()
      assert(malformed.status==PrimitiveBinStatus.invalidDescriptor && malformed.lines==0)
      // Cancellation during sequential segment validation must not offer a read.
      d.begin(i,poolVersion=2,mutate=segments); d.step(); d.cancel()
      val cancelled=d.finish()
      assert(cancelled.status==PrimitiveBinStatus.cancelled && cancelled.lines==0)
      d.begin(i,poolVersion=2,mutate=segments)
      assert(d.finish().status==PrimitiveBinStatus.success)
    }
  }

}
