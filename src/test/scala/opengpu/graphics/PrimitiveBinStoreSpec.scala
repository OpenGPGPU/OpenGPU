package opengpu.graphics

import chisel3._
import opengpu.config.CachePolicy
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class PrimitiveBinStoreSpec extends AnyFlatSpec {
  case class Ref(primitive: Long,state: Long,sequence: Long,token: Long)
  case class Reply(status: Int,reference: Ref,address: Long,count: Long)
  case class Completion(status: Int,usable: Boolean,committed: Long,emitted: Long,
    pages: Long,restart: Option[Ref],spills: Int)
  private def ref(n: Int): Ref =
    Ref(0x80000000L+n*16,0xf0000000L+n*8,n*3L+1,0xffff0000L+n)
  private def lines(records: Int): Int = (32+records*16+63)/64

  private class Driver(val d: PrimitiveBinStore,val pageBytes: Int,val writeCredits: Int) {
    case class Op(id: Int,address: Long,data: BigInt,mask: BigInt,write: Boolean,
      due: Int,fault: Boolean)
    val memory = mutable.LongMap.empty[Long]
    val pending = mutable.ArrayBuffer.empty[Op]
    val reads = mutable.ArrayBuffer.empty[Long]
    val writes = mutable.ArrayBuffer.empty[Long]
    val outputs = mutable.ArrayBuffer.empty[Ref]
    val replies = mutable.ArrayBuffer.empty[Reply]
    var expected = Vector.empty[Ref]
    var cycle = 0
    var base = 0L
    var limit = 0L
    var segments=Vector.empty[(Long,Long)]
    var context = 0x13579bdfL
    var pass = 0x2468ace0L
    var tile = 0x87654321L
    var generation = 19
    var holdRequests = false
    var holdResponses = false
    var holdOutputs = false
    var alwaysReady = false
    var responseDelay = 3
    var reordered = false
    var faultRequest: (Boolean,Long) => Boolean = (_,_) => false
    private var heldRequest = Option.empty[BigInt]
    private var heldResponse = Option.empty[Op]
    private var heldAppend = Option.empty[BigInt]
    private var heldOutput = Option.empty[BigInt]

    d.io.begin.valid.poke(false.B); d.io.append.valid.poke(false.B)
    d.io.begin.bits.poke(0.U.asTypeOf(d.io.begin.bits))
    d.io.append.bits.poke(0.U.asTypeOf(d.io.append.bits))
    d.io.end.valid.poke(false.B); d.io.end.bits.poke(true.B)
    d.io.cancel.poke(false.B); d.io.appended.ready.poke(false.B)
    d.io.reference.ready.poke(false.B); d.io.completion.ready.poke(false.B)
    d.io.memoryRequest.ready.poke(false.B); d.io.memoryResponse.valid.poke(false.B)
    d.io.memoryResponse.bits.poke(0.U.asTypeOf(d.io.memoryResponse.bits))
    d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)

    private def line(address: Long): BigInt =
      (0 until 16).foldLeft(BigInt(0)) { (out,i) =>
        out | (BigInt(memory.getOrElse(address+i*4,0L)) << (32*i))
      }
    private def applyWrite(op: Op): Unit = {
      for (w <- 0 until 16) {
        val nibble = ((op.mask >> (4*w)) & 0xf).toInt
        if (nibble != 0) {
          assert(nibble == 0xf,"the word adapter must not emit a partial-word mask")
          memory(op.address+w*4) = ((op.data >> (32*w)) & 0xffffffffL).toLong
        }
      }
    }
    private def peekReference(r: BinPrimitiveReference): Ref = Ref(
      r.primitive.peek().litValue.toLong,r.state.peek().litValue.toLong,
      r.sequence.peek().litValue.toLong,r.token.peek().litValue.toLong)
    private def pokeReference(r: BinPrimitiveReference,v: Ref): Unit = {
      r.primitive.poke(v.primitive.U); r.state.poke(v.state.U)
      r.sequence.poke(v.sequence.U); r.token.poke(v.token.U)
    }
    def step(): Unit = {
      // --- memory request offer -------------------------------------------
      val ready = !holdRequests && (alwaysReady || cycle%5 != 2)
      d.io.memoryRequest.ready.poke(ready.B)
      heldRequest.foreach { old =>
        d.io.memoryRequest.valid.expect(true.B)
        assert(d.io.memoryRequest.bits.peek().litValue == old,"offered memory request changed")
      }
      heldRequest = if (d.io.memoryRequest.valid.peek().litToBoolean && !ready)
        Some(d.io.memoryRequest.bits.peek().litValue) else None

      // --- memory response -------------------------------------------------
      if (heldResponse.isEmpty) {
        val index = pending.lastIndexWhere(_.due <= cycle)
        if (index >= 0) heldResponse = Some(pending.remove(index))
      }
      d.io.memoryResponse.valid.poke(heldResponse.nonEmpty.B)
      heldResponse.foreach { op =>
        d.io.memoryResponse.bits.transactionId.poke(op.id.U)
        d.io.memoryResponse.bits.readData.poke(line(op.address).U)
        d.io.memoryResponse.bits.fault.poke(op.fault.B)
        d.io.memoryResponse.ready.expect(true.B)
        if (op.write) applyWrite(op)
        heldResponse = None
      }

      // --- word appends -----------------------------------------------------
      d.io.appended.ready.poke((alwaysReady || cycle%7 != 3).B)
      if (d.io.appended.valid.peek().litToBoolean) {
        val bits = d.io.appended.bits
        bits.identity.context.expect(context.U); bits.identity.pass.expect(pass.U)
        bits.identity.generation.expect(generation.U); bits.tile.expect(tile.U)
        heldAppend.foreach(x => assert(bits.peek().litValue == x,"offered append reply changed"))
        if (d.io.appended.ready.peek().litToBoolean) {
          replies += Reply(bits.status.peek().litValue.toInt,peekReference(bits.reference),
            bits.recordAddress.peek().litValue.toLong,bits.committedCount.peek().litValue.toLong)
          heldAppend = None
        } else heldAppend = Some(bits.peek().litValue)
      } else assert(heldAppend.isEmpty,"offered append reply valid withdrawn")

      // --- replayed references ---------------------------------------------
      val outputReady = !holdOutputs && (alwaysReady || cycle%5 < 3)
      d.io.reference.ready.poke(outputReady.B)
      if (d.io.reference.valid.peek().litToBoolean) {
        val bits = d.io.reference.bits
        bits.identity.context.expect(context.U); bits.identity.pass.expect(pass.U)
        bits.identity.generation.expect(generation.U); bits.tile.expect(tile.U)
        heldOutput.foreach(x => assert(bits.peek().litValue == x,"offered reference changed"))
        if (outputReady) {
          bits.ordinal.expect(outputs.size.U)
          val got = peekReference(bits.reference)
          assert(outputs.size < expected.size,"too many replayed references")
          assert(got == expected(outputs.size),s"wrong reference at ${outputs.size}: $got")
          outputs += got; heldOutput = None
        } else heldOutput = Some(bits.peek().litValue)
      } else assert(heldOutput.isEmpty,"offered reference valid withdrawn")

      // --- memory request acceptance ---------------------------------------
      if (d.io.memoryRequest.valid.peek().litToBoolean && ready) {
        val bits = d.io.memoryRequest.bits
        val address = bits.address.peek().litValue.toLong
        val isWrite = bits.isWrite.peek().litToBoolean
        val id = bits.transactionId.peek().litValue.toInt
        bits.sizeLog2.expect(6.U)
        bits.cachePolicy.expect(CachePolicy.uncached)
        bits.cacheClient.expect(false.B); bits.cacheResident.expect(false.B)
        assert(address%64 == 0,s"unaligned line request $address")
        assert(BigInt(address) >= BigInt(base) && BigInt(address)+64 <= BigInt(limit),
          s"line $address outside [$base,$limit)")
        assert(segments.exists { case (start,end)=>address>=start && address+64<=end },"bin traffic entered a reserved gap")
        assert(!pending.exists(_.id == id),"reused a live memory transaction id")
        if (isWrite) {
          assert(id < writeCredits,"writer used the reader's transaction id")
          val mask = bits.byteMask.peek().litValue
          assert(mask.bitCount == 4,"word adapter mask must cover exactly one word")
          val word = address+4*(java.lang.Long.numberOfTrailingZeros(mask.toLong)/4)
          writes += word
          reordered ||= pending.nonEmpty && pending.last.due <= cycle+responseDelay
          pending += Op(id,address,bits.writeData.peek().litValue,mask,true,
            cycle+responseDelay+(writes.size%3),faultRequest(true,word))
        } else {
          assert(id == writeCredits,"reader must own the highest transaction id")
          bits.writeData.expect(0.U); bits.byteMask.expect(0.U)
          reads += address
          pending += Op(id,address,0,0,false,
            cycle+responseDelay,faultRequest(false,address))
        }
      }
      d.clock.step(); cycle += 1
    }
    def until(condition: => Boolean): Unit = {
      val start = cycle
      while (!condition && cycle-start < 200000) step()
      assert(condition,s"store stopped progressing at $cycle")
    }
    def begin(start: Long,pages: Int,generation: Int = 19,version: Int = 1,
              context: Long = 0x13579bdfL,pass: Long = 0x2468ace0L,
              tile: Long = 0x87654321L,refs: Vector[Ref] = Vector.empty,
              mutate: BinPagePoolDescriptor=>Unit = _=>()): Unit = {
      assert(pending.isEmpty)
      base = start; limit = start+pages.toLong*pageBytes
      this.context = context; this.pass = pass; this.tile = tile; this.generation = generation
      expected = refs
      reads.clear(); writes.clear(); outputs.clear(); replies.clear()
      reordered = false
      val b = d.io.begin.bits
      b.version.poke(version.U); b.pool.version.poke(1.U)
      b.pool.identity.context.poke(context.U); b.pool.identity.pass.poke(pass.U)
      b.pool.identity.generation.poke(generation.U)
      b.pool.base.poke(base.U); b.pool.limit.poke(limit.U)
      b.pool.pageSizeLog2.poke(Integer.numberOfTrailingZeros(pageBytes).U)
      b.tile.poke(tile.U)
      mutate(b.pool)
      limit=b.pool.limit.peek().litValue.toLong
      segments=if (b.pool.version.peek().litValue==2) {
        Vector((base,b.pool.initialLimit.peek().litValue.toLong)) ++
          (0 until b.pool.spillCount.peek().litValue.toInt.min(8)).map { n=>
            (b.pool.spills(n).base.peek().litValue.toLong,b.pool.spills(n).limit.peek().litValue.toLong)
          }
      } else Vector((base,limit))
      d.io.begin.valid.poke(true.B); until(d.io.begin.ready.peek().litToBoolean)
      step(); d.io.begin.valid.poke(false.B); b.poke(0.U.asTypeOf(b))
    }
    def sendOnly(r: Ref): Unit = {
      pokeReference(d.io.append.bits,r); d.io.append.valid.poke(true.B)
      until(d.io.append.ready.peek().litToBoolean)
      step(); d.io.append.valid.poke(false.B)
      d.io.append.bits.poke(0.U.asTypeOf(d.io.append.bits))
    }
    def send(r: Ref): Reply = {
      val before = replies.size
      sendOnly(r); until(replies.size == before+1)
      val got = replies.last
      assert(got.reference == r)
      got
    }
    def close(): Unit = {
      d.io.end.valid.poke(true.B); until(d.io.end.ready.peek().litToBoolean)
      step(); d.io.end.valid.poke(false.B)
    }
    def cancel(): Unit = { d.io.cancel.poke(true.B); step(); d.io.cancel.poke(false.B) }
    def finish(): Completion = {
      until(d.io.completion.valid.peek().litToBoolean)
      assert(pending.isEmpty)
      val b = d.io.completion.bits
      b.identity.context.expect(context.U); b.identity.pass.expect(pass.U)
      b.identity.generation.expect(generation.U); b.tile.expect(tile.U)
      val result = Completion(b.status.peek().litValue.toInt,
        b.prefixUsable.peek().litToBoolean,b.committed.peek().litValue.toLong,
        b.emitted.peek().litValue.toLong,b.allocatedPages.peek().litValue.toLong,
        if (b.restartValid.peek().litToBoolean) Some(peekReference(b.restart)) else None,
        b.spillTransitions.peek().litValue.toInt)
      assert(result.emitted == outputs.size &&
        outputs.toVector == expected.take(outputs.size),"replayed prefix mismatch")
      assert(result.committed == replies.count(_.status == PrimitiveBinStatus.success))
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
    def expectedLines(groups: Seq[Int],at: Long): Vector[Long] =
      groups.zipWithIndex.flatMap { case (records,n) =>
        val page = at+n.toLong*pageBytes
        (0 until lines(records)).map(i => page+i*64L)
      }.toVector
  }

  private def store(bytes: Int,credits: Int): PrimitiveBinStore =
    new PrimitiveBinStore(pageBytes=bytes,writeCredits=credits)

  for (credits <- Seq(1,3,8)) {
    it should s"write and replay twenty references across shared credits=$credits" in {
      simulate(store(128,credits)) { dut =>
        val d = new Driver(dut,128,credits)
        val expected = (0 until 20).map(n => ref(n)).toVector
        d.begin(0x10000,4,refs=expected)
        expected.foreach(r => assert(d.send(r).status == PrimitiveBinStatus.success))
        d.close()
        val result = d.finish()
        assert(result.status == PrimitiveBinStatus.success && result.usable)
        assert(result.committed == 20 && result.emitted == 20 && result.pages == 4)
        assert(d.outputs.toVector == expected)
        assert(d.reads.toVector == d.expectedLines(Seq(6,6,6,2),0x10000))
        assert(d.writes.nonEmpty && d.memory(0x10000) == PrimitiveBinPageLayout.magic)
        assert(d.memory(0x10008) == 6 && d.memory(0x10000+3*128+8) == 2)
        if (credits > 1) assert(d.reordered)
        info(s"BIN_STORE_REFS=20 credits=$credits at=${d.writes.size} " +
          s"reads=${d.reads.size} cycles=${d.cycle}")
      }
    }
  }

  it should "replay only the committed prefix and restart exactly on arena exhaustion" in {
    simulate(store(128,3)) { dut =>
      val d = new Driver(dut,128,3)
      val references = (0 until 13).map(n => ref(n)).toVector
      d.begin(0x20000,2,refs=references)
      references.take(12).foreach(r => assert(d.send(r).status == PrimitiveBinStatus.success))
      val failed = d.send(references.last)
      assert(failed.status == PrimitiveBinStatus.exhausted && failed.count == 12)
      d.close()
      val result = d.finish()
      assert(result.status == PrimitiveBinStatus.exhausted && result.usable)
      assert(result.committed == 12 && result.emitted == 12 && result.pages == 2)
      assert(result.restart.contains(references.last))
      assert(d.outputs.toVector == references.take(12))
      // The restart reference is accepted by a fresh generation reusing the arena.
      d.begin(0x20000,1,generation=20,refs=Vector(references.last))
      assert(d.send(result.restart.get).status == PrimitiveBinStatus.success)
      d.close()
      val second = d.finish()
      assert(second.status == PrimitiveBinStatus.success && second.emitted == 1)
      assert(d.outputs.toVector == Vector(references.last))
      assert(d.memory(0x20014) == 20 && d.memory(0x20008) == 1)
    }
  }

  for (phase <- Seq("payload","commit")) {
    it should s"fail the whole binding on a $phase write fault without replaying it" in {
      simulate(store(128,3)) { dut =>
        val d = new Driver(dut,128,3)
        d.begin(0x30000,2,refs=(0 until 3).map(ref).toVector)
        assert(d.send(ref(0)).status == PrimitiveBinStatus.success)
        // Record zero is already committed above; these addresses now belong
        // only to the second append.
        d.faultRequest = (write,address) => write && (phase match {
          case "payload" => address == 0x30030
          case "commit" => address == 0x30008
        })
        val bad = d.send(ref(1))
        assert(bad.status == PrimitiveBinStatus.memoryFault,bad.toString)
        val result = d.finish()
        assert(result.status == PrimitiveBinStatus.memoryFault && !result.usable)
        assert(result.emitted == 0 && result.restart.isEmpty)
        assert(d.outputs.isEmpty)
        d.faultRequest = (_,_) => false
        d.begin(0x30000,2,generation=20,refs=Vector(ref(9)))
        assert(d.send(ref(9)).status == PrimitiveBinStatus.success)
        d.close()
        assert(d.finish().status == PrimitiveBinStatus.success)
        assert(d.outputs.toVector == Vector(ref(9)))
      }
    }
  }

  it should "fault a page header and a replay line without substituting zero references" in {
    simulate(store(128,3)) { dut =>
      val d = new Driver(dut,128,3)
      val expected = (0 until 6).map(ref).toVector
      d.begin(0x40000,2,generation=20,refs=expected)
      d.faultRequest = (write,address) => write && address == 0x4000c
      val headerFault = d.send(ref(0))
      assert(headerFault.status == PrimitiveBinStatus.memoryFault)
      val first = d.finish()
      assert(first.status == PrimitiveBinStatus.memoryFault && first.emitted == 0 && !first.usable)
      d.faultRequest = (_,_) => false
      d.begin(0x40000,2,generation=21,refs=expected)
      expected.foreach(r => assert(d.send(r).status == PrimitiveBinStatus.success))
      // The second 64-byte line of the first page faults during replay. Writes
      // are unaffected, so install it before ending the build.
      d.faultRequest = (write,address) => !write && address == 0x40040
      d.close()
      val replayFault = d.finish()
      assert(replayFault.status == PrimitiveBinStatus.memoryFault && replayFault.emitted == 2)
      assert(d.outputs.toVector == expected.take(2))
    }
  }

  it should "drain a stalled build after cancellation and reuse the arena" in {
    simulate(store(128,3)) { dut =>
      val d = new Driver(dut,128,3)
      d.begin(0x50000,2,refs=Vector(ref(0),ref(1)))
      assert(d.send(ref(0)).status == PrimitiveBinStatus.success)
      d.holdRequests = true
      d.sendOnly(ref(1))
      d.until(dut.io.memoryRequest.valid.peek().litToBoolean)
      val held = dut.io.memoryRequest.bits.peek().litValue
      d.cancel()
      for (_ <- 0 until 5) {
        d.step(); dut.io.completion.valid.expect(false.B)
        dut.io.memoryRequest.valid.expect(true.B)
        assert(dut.io.memoryRequest.bits.peek().litValue == held)
      }
      d.holdRequests = false
      val result = d.finish()
      assert(result.status == PrimitiveBinStatus.cancelled && !result.usable && result.emitted == 0)
      assert(d.outputs.isEmpty)
      d.begin(0x50000,1,generation=20,refs=Vector(ref(5)))
      assert(d.send(ref(5)).status == PrimitiveBinStatus.success)
      d.close()
      assert(d.finish().status == PrimitiveBinStatus.success)
      assert(d.outputs.toVector == Vector(ref(5)))
    }
  }

  it should "stop replay on cancellation after only a committed prefix was exposed" in {
    simulate(store(128,3)) { dut =>
      val d = new Driver(dut,128,3)
      val expected = (0 until 6).map(ref).toVector
      d.begin(0x60000,1,refs=expected)
      expected.foreach(r => assert(d.send(r).status == PrimitiveBinStatus.success))
      d.close()
      d.until(d.outputs.size >= 2)
      d.holdOutputs = true
      d.cancel()
      // An already offered reference still has to be accepted; completion
      // must wait for it. A cancelled line read is allowed to drain later.
      for (_ <- 0 until 4) {
        if (dut.io.reference.valid.peek().litToBoolean)
          dut.io.completion.valid.expect(false.B)
        d.step()
      }
      d.holdOutputs = false
      val result = d.finish()
      assert(result.status == PrimitiveBinStatus.cancelled && !result.usable)
      assert(result.emitted == d.outputs.size && result.emitted < expected.size)
      assert(d.outputs.toVector == expected.take(d.outputs.size))
    }
  }

  it should "admit an empty bin and reject an unsupported descriptor without memory traffic" in {
    simulate(store(128,3)) { dut =>
      val d = new Driver(dut,128,3)
      d.begin(0x70000,1)
      d.close()
      val empty = d.finish()
      assert(empty.status == PrimitiveBinStatus.success && empty.usable)
      assert(empty.committed == 0 && empty.emitted == 0 && empty.pages == 0)
      assert(d.reads.isEmpty && d.writes.isEmpty)
      d.begin(0x70000,1,version=2)
      val invalid = d.finish()
      assert(invalid.status == PrimitiveBinStatus.invalidDescriptor && !invalid.usable)
      assert(invalid.emitted == 0 && d.reads.isEmpty && d.writes.isEmpty)
    }
  }

  it should "replay the final page below the exclusive 4GiB arena limit" in {
    simulate(store(64,3)) { dut =>
      val d = new Driver(dut,64,3)
      val expected = (0 until 3).map(ref).toVector
      d.begin(0xffffff80L,2,refs=expected)
      expected.foreach(r => assert(d.send(r).status == PrimitiveBinStatus.success))
      d.close()
      val result = d.finish()
      assert(result.status == PrimitiveBinStatus.success && result.emitted == 3 && result.pages == 2)
      assert(d.reads.toVector == Vector(0xffffff80L,0xffffffc0L))
      assert(d.memory(0xffffffecL) == expected.last.token)
      assert(d.memory.keysIterator.forall(_ >= 0xffffff80L))
    }
  }
  it should "write and replay spill segments automatically and fault a later-segment payload without publishing a prefix" in {
    simulate(store(128,3)) { dut=>
      val d=new Driver(dut,128,3)
      val expected=Vector.tabulate(20)(ref)
      val pages=Vector(0x10000L,0x11000L,0x11080L,0x14000L)
      def binding(p: BinPagePoolDescriptor): Unit = {
        p.version.poke(2.U); p.initialLimit.poke(0x10080.U); p.limit.poke(0x14080.U)
        p.spillCount.poke(2.U)
        p.spills(0).base.poke(0x11000.U); p.spills(0).limit.poke(0x11100.U)
        p.spills(1).base.poke(0x14000.U); p.spills(1).limit.poke(0x14080.U)
      }
      d.begin(0x10000,4,refs=expected,mutate=binding)
      expected.foreach(r=>assert(d.send(r).status==PrimitiveBinStatus.success))
      d.close(); val good=d.finish()
      assert(good.status==0 && good.usable && good.committed==20 && good.emitted==20 && good.pages==4 && good.spills==2)
      for ((page,n) <- pages.zipWithIndex) {
        assert(d.memory(page+28)==(page-0x10000)/128)
        assert(d.memory(page+4)==pages.lift(n+1).getOrElse(0L))
      }
      assert(d.reads.toVector==pages.zip(Seq(6,6,6,2)).flatMap {case (page,count)=>(0 until lines(count)).map(n=>page+n*64L)})
      d.begin(0x10000,4,generation=20,refs=expected,mutate=binding)
      expected.take(6).foreach(r=>assert(d.send(r).status==0))
      d.faultRequest=(write,address)=>write && address==0x11020L
      assert(d.send(expected(6)).status==PrimitiveBinStatus.memoryFault)
      val failed=d.finish()
      assert(failed.status==PrimitiveBinStatus.memoryFault && !failed.usable && failed.emitted==0 && failed.restart.isEmpty)
      assert(d.reads.isEmpty)
      d.faultRequest=(_,_)=>false
      d.begin(0x10000,4,generation=21,refs=expected.take(7),mutate=binding)
      expected.take(7).foreach(r=>assert(d.send(r).status==0))
      d.close(); assert(d.finish().status==0)
    }
  }

}
