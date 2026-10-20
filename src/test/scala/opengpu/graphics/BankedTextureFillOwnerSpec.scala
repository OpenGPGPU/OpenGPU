package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class BankedFillHarness(fills: Int = 16, waiters: Int = 32) extends Module {
  private val config = GpuConfig(lanes = 4, warps = 2)
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new BankedTextureFillRequest))
    val out = Decoupled(new BankedTextureFillResult)
    val memoryRequest = Decoupled(new ComputeMemoryRequest(config, 64, fills))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, fills)))
    val lookup = Flipped(Decoupled(new BankedTextureLookup(16)))
    val word = Decoupled(new BankedTextureWord(16))
    val cancel = Input(Bool()); val invalidate = Input(Bool())
    val busy = Output(Bool()); val occupied = Output(UInt(log2Ceil(fills + 1).W))
    val waiting = Output(UInt(log2Ceil(waiters + 1).W)); val joined = Output(Bool())
  })
  private val owner = Module(new BankedTextureFillOwner(config, fills, waiters))
  private val store = Module(new BankedTextureLineStore())
  owner.io.in <> io.in; io.out <> owner.io.out
  io.memoryRequest <> owner.io.memoryRequest; owner.io.memoryResponse <> io.memoryResponse
  store.io.refill <> owner.io.refill; owner.io.refillDone <> store.io.refillDone
  owner.io.cancel := io.cancel; owner.io.invalidate := io.invalidate
  store.io.invalidate := owner.io.cacheInvalidate
  store.io.in(0) <> io.lookup
  for(p <- 1 until 4) { store.io.in(p).valid := false.B; store.io.in(p).bits := 0.U.asTypeOf(store.io.in(p).bits) }
  // A lookup targets one known bank; arbitrate its tagged reply back to the test.
  private val arb = Module(new RRArbiter(new BankedTextureWord(16), 4))
  for(b <- 0 until 4) arb.io.in(b) <> store.io.out(b)
  io.word <> arb.io.out
  io.busy := owner.io.busy || store.io.busy
  io.occupied := owner.io.occupiedFills; io.waiting := owner.io.waitingConsumers; io.joined := owner.io.joined
}

class BankedTextureFillOwnerSpec extends AnyFlatSpec {
  import WindowRecordTest._
  private class Driver(val d: BankedFillHarness) {
    zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
    val memory = mutable.ArrayBuffer.empty[(Int, BigInt)]
    val results = mutable.ArrayBuffer.empty[(Int, Boolean, Boolean, Boolean, Boolean)]
    var cycle = 0
    private var held: Option[BigInt] = None
    def identity(q: GraphicsWorkIdentity, generation: Int = 9): Unit = {
      q.context.poke(7.U); q.pass.poke(8.U); q.generation.poke(generation.U)
    }
    def request(tag: Int, line: Int, key: Int = 1, generation: Int = 9): Unit = {
      val q = d.io.in.bits
      identity(q.identity, generation); q.textureKey.poke(key.U); q.address.poke((0x1000 + line * 64).U)
      q.rowParity.poke((line % 2 == 1).B); q.tag.poke(tag.U); d.io.in.valid.poke(true.B)
    }
    def tick(): Unit = {
      if(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean) {
        val q = d.io.memoryRequest.bits
        q.isWrite.expect(false.B); q.sizeLog2.expect(6.U); q.byteMask.expect(((BigInt(1) << 64) - 1).U)
        memory += ((q.transactionId.peek().litValue.toInt, q.address.peek().litValue))
      }
      held.foreach { value =>
        d.io.out.valid.expect(true.B)
        assert(d.io.out.bits.peek().litValue == value, "offered fill completion changed under backpressure")
      }
      held = if(d.io.out.valid.peek().litToBoolean && !d.io.out.ready.peek().litToBoolean)
        Some(d.io.out.bits.peek().litValue) else None
      if(d.io.out.valid.peek().litToBoolean && d.io.out.ready.peek().litToBoolean) {
        val q = d.io.out.bits
        results += ((q.tag.peek().litValue.toInt, q.installed.peek().litToBoolean,
          q.fault.peek().litToBoolean, q.cancelled.peek().litToBoolean, q.invalidated.peek().litToBoolean))
      }
      d.clock.step(); cycle += 1
    }
    def until(test: => Boolean): Unit = {
      var n = 0
      while(!test && n < 2000) { tick(); n += 1 }
      assert(n < 2000, "banked texture fill progress timeout")
    }
    def submit(tag: Int, line: Int, key: Int = 1, generation: Int = 9): Unit = {
      request(tag, line, key, generation); until(d.io.in.ready.peek().litToBoolean); tick(); d.io.in.valid.poke(false.B)
    }
    def data(line: Int): BigInt = (0 until 16).map(n => (BigInt(0xff000000L) + line * 256 + n) << (32*n)).sum
    def reply(id: Int, line: Int, fault: Boolean = false): Unit = {
      val q = d.io.memoryResponse.bits
      q.transactionId.poke(id.U); q.readData.poke(data(line).U); q.fault.poke(fault.B)
      d.io.memoryResponse.valid.poke(true.B); until(d.io.memoryResponse.ready.peek().litToBoolean)
      tick(); d.io.memoryResponse.valid.poke(false.B)
    }
    def lookup(line: Int, hit: Boolean, key: Int = 1): Unit = {
      val q = d.io.lookup.bits
      identity(q.identity); q.textureKey.poke(key.U); q.address.poke((0x1000+line*64).U)
      q.rowParity.poke((line%2==1).B); q.tag.poke(line.U); d.io.lookup.valid.poke(true.B)
      until(d.io.lookup.ready.peek().litToBoolean); tick(); d.io.lookup.valid.poke(false.B)
      d.io.word.ready.poke(false.B); until(d.io.word.valid.peek().litToBoolean)
      d.io.word.bits.hit.expect(hit.B); d.io.word.bits.tag.expect(line.U)
      d.io.word.bits.data.expect((if(hit)BigInt(0xff000000L)+line*256 else BigInt(0)).U)
      d.io.word.ready.poke(true.B); tick(); d.io.word.ready.poke(false.B)
    }
  }
  it should "join thirty two consumers into sixteen reordered physical fills and install exact cache data" in {
    simulate(new BankedFillHarness()) { d =>
      val t = new Driver(d)
      var sent = 0; var joins = 0
      while(sent < 32 && t.cycle < 1000) {
        d.io.memoryRequest.ready.poke((t.cycle%5!=1).B)
        t.request(sent, sent%16)
        if(d.io.in.ready.peek().litToBoolean) { if(d.io.joined.peek().litToBoolean) joins += 1; sent += 1 }
        t.tick()
      }
      d.io.in.valid.poke(false.B); d.io.memoryRequest.ready.poke(true.B)
      t.until(t.memory.size == 16)
      assert(sent == 32 && joins == 16 && t.memory.map(_._1).distinct.size == 16)
      d.io.occupied.expect(16.U); d.io.waiting.expect(32.U); d.io.out.valid.expect(false.B)
      for((id,address) <- t.memory.toVector.reverse) t.reply(id, ((address-0x1000)/64).toInt)
      for(_ <- 0 until 40) t.tick()
      while(t.results.size < 32 && t.cycle < 2000) {
        d.io.out.ready.poke((t.cycle%13<8).B); t.tick()
      }
      assert(t.results.map(_._1).sorted == (0 until 32))
      assert(t.results.forall { case(_,installed,fault,cancelled,invalidated) => installed && !fault && !cancelled && !invalidated })
      assert(t.memory.size == 16)
      d.io.out.ready.poke(true.B); t.until(!d.io.busy.peek().litToBoolean)
      for(line <- 0 until 16) t.lookup(line, true)
      info("BANKED_TEXTURE_FILLS fills16=true consumers32=true joined16=true reorderedResponses=true exactCacheData=true replyBackpressure=true boundedOwnership=true")
    }
  }
  it should "fan out faults and drain held memory offers and cancelled refills before healthy reuse" in {
    simulate(new BankedFillHarness(fills=4,waiters=8)) { d =>
      val t = new Driver(d); d.io.memoryRequest.ready.poke(true.B)
      t.submit(0, 0); t.submit(1, 0); t.until(t.memory.size == 1)
      t.reply(t.memory.head._1, 0, fault=true)
      t.until(d.io.out.valid.peek().litToBoolean)
      val public = d.io.out.bits.peek().litValue
      d.io.cancel.poke(true.B); t.tick(); d.io.cancel.poke(false.B)
      for(_ <- 0 until 5) { assert(d.io.out.bits.peek().litValue == public); d.io.in.ready.expect(false.B); t.tick() }
      d.io.out.ready.poke(true.B); t.until(t.results.size == 2); t.until(!d.io.busy.peek().litToBoolean)
      assert(t.results.forall(r => !r._2 && r._3))
      t.lookup(0, false)
      // A queued physical read is already irrevocable while the memory sink
      // stalls. It must survive cancellation and retain its ID through reply.
      d.io.memoryRequest.ready.poke(false.B); d.io.out.ready.poke(false.B)
      t.submit(2, 1); t.until(d.io.memoryRequest.valid.peek().litToBoolean)
      val offer = d.io.memoryRequest.bits.peek().litValue
      t.submit(3, 2)
      d.io.cancel.poke(true.B); t.tick(); d.io.cancel.poke(false.B)
      for(_ <- 0 until 6) { d.io.memoryRequest.valid.expect(true.B); assert(d.io.memoryRequest.bits.peek().litValue == offer); t.tick() }
      d.io.memoryRequest.ready.poke(true.B); t.tick()
      assert(t.memory.size == 2, "unoffered cancelled fill must not issue a physical read")
      t.reply(t.memory.last._1, 1)
      d.io.out.ready.poke(true.B); t.until(t.results.size == 4); t.until(!d.io.busy.peek().litToBoolean)
      assert(t.results.filter(r => r._1>=2).forall(r => !r._2 && !r._3 && r._4))
      t.lookup(1, false); t.lookup(2, false)
      t.submit(4, 3); t.until(t.memory.size == 3); t.reply(t.memory.last._1, 3)
      t.until(t.results.size == 5); t.until(!d.io.busy.peek().litToBoolean)
      assert(t.results.last == ((4,true,false,false,false))); t.lookup(3, true)
      info("BANKED_TEXTURE_FILL_DRAIN faultFanout=true heldCompletionImmutable=true offeredReadIrrevocable=true queuedReadCancelled=true cancelledDataNeverInstalled=true healthyReuse=true")
    }
  }
  it should "separate generation and view keys and never join a completed fill while its result is held" in {
    simulate(new BankedFillHarness(fills=4,waiters=8)) { d =>
      val t = new Driver(d); d.io.memoryRequest.ready.poke(true.B)
      t.submit(0, 0); t.submit(1, 0, key=2); t.submit(2, 0, generation=10)
      t.until(t.memory.size == 3); d.io.occupied.expect(3.U)
      for((id,_) <- t.memory.toVector) t.reply(id, 0)
      t.until(d.io.out.valid.peek().litToBoolean)
      // Wait until all refill acknowledgements have arrived, while holding
      // every consumer completion. A subsequent miss starts a new memory ID.
      for(_ <- 0 until 50) t.tick()
      t.submit(3, 0); t.until(t.memory.size == 4)
      t.reply(t.memory.last._1, 0)
      d.io.out.ready.poke(true.B); t.until(t.results.size == 4); t.until(!d.io.busy.peek().litToBoolean)
      assert(t.results.forall(r => r._2 && !r._3 && !r._4))
      info("BANKED_TEXTURE_FILL_KEYS generationAndViewIsolated=true completedFillNotJoined=true waiterFanoutFair=true")
    }
  }
}
