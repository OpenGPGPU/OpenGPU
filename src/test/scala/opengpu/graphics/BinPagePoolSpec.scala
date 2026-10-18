package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class BinPagePoolSpec extends AnyFlatSpec {
  private def initialize(d: BinPagePool): Unit = {
    d.io.begin.valid.poke(false.B); d.io.allocate.valid.poke(false.B)
    d.io.end.valid.poke(false.B); d.io.end.bits.poke(true.B)
    d.io.allocation.ready.poke(false.B); d.io.completion.ready.poke(false.B)
    d.io.cancel.poke(false.B)
    d.io.begin.bits.poke(0.U.asTypeOf(d.io.begin.bits))
    d.io.allocate.bits.poke(0.U.asTypeOf(d.io.allocate.bits))
    d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
  }

  private def bind(d: BinPagePool, base: BigInt, limit: BigInt, pageBits: Int,
                   generation: Int = 19, version: Int = 1,
                   mutate: BinPagePoolDescriptor=>Unit = _=>()): Unit = {
    d.io.begin.bits.poke(0.U.asTypeOf(d.io.begin.bits))
    d.io.begin.bits.version.poke(version.U)
    d.io.begin.bits.identity.context.poke(0x13579bdf.U)
    d.io.begin.bits.identity.pass.poke(0x2468ace0L.U)
    d.io.begin.bits.identity.generation.poke(generation.U)
    d.io.begin.bits.base.poke(base.U); d.io.begin.bits.limit.poke(limit.U)
    d.io.begin.bits.pageSizeLog2.poke(pageBits.U)
    mutate(d.io.begin.bits)
    d.io.begin.valid.poke(true.B); d.io.begin.ready.expect(true.B)
    d.clock.step(); d.io.begin.valid.poke(false.B)
    d.io.begin.bits.poke(0.U.asTypeOf(d.io.begin.bits))
    d.clock.step()
  }

  private def finish(d: BinPagePool, status: Int, count: Int, generation: Int = 19, spills: Int = 0): Unit = {
    d.io.allocate.valid.poke(false.B); d.io.allocation.ready.poke(true.B)
    d.io.end.valid.poke(true.B); d.io.end.ready.expect(true.B)
    d.clock.step(); d.io.end.valid.poke(false.B)
    var ticks = 0
    while (!d.io.completion.valid.peek().litToBoolean && ticks < 10) {
      d.clock.step(); ticks += 1
    }
    assert(ticks < 10)
    d.io.completion.bits.status.expect(status.U)
    d.io.completion.bits.allocatedPages.expect(count.U)
    d.io.completion.bits.spillTransitions.expect(spills.U)
    d.io.completion.bits.identity.generation.expect(generation.U)
    val held = d.io.completion.bits.peek().litValue
    for (_ <- 0 until 3) {
      d.io.begin.ready.expect(false.B); d.clock.step()
      d.io.completion.valid.expect(true.B)
      assert(d.io.completion.bits.peek().litValue == held)
    }
    d.io.completion.ready.poke(true.B); d.clock.step()
    d.io.completion.ready.poke(false.B); d.io.busy.expect(false.B)
  }

  for (pageBits <- Seq(6,12,16)) {
    it should s"allocate disjoint bounded pages and retain an exact exhausted restart token at size2^$pageBits" in {
      simulate(new BinPagePool(1 << pageBits)) { d =>
        initialize(d)
        val base = BigInt(0x20000)
        val bytes = BigInt(1) << pageBits
        bind(d,base,base+4*bytes,pageBits)
        val pending = mutable.Queue.empty[Int]
        var sent = 0; var received = 0; var ticks = 0
        var held = Option.empty[BigInt]
        while (received < 5 && ticks < 100) {
          d.io.allocate.valid.poke((sent < 5).B)
          d.io.allocate.bits.tile.poke((0x8000+sent).U)
          d.io.allocate.bits.token.poke((0x9000+sent).U)
          val ready = ticks % 7 >= 3
          d.io.allocation.ready.poke(ready.B)
          if (d.io.allocation.valid.peek().litToBoolean) {
            held.foreach(x => assert(d.io.allocation.bits.peek().litValue == x))
            if (ready) {
              val n = pending.dequeue()
              d.io.allocation.bits.identity.context.expect(0x13579bdf.U)
              d.io.allocation.bits.identity.pass.expect(0x2468ace0L.U)
              d.io.allocation.bits.identity.generation.expect(19.U)
              d.io.allocation.bits.request.tile.expect((0x8000+n).U)
              d.io.allocation.bits.request.token.expect((0x9000+n).U)
              d.io.allocation.bits.ordinal.expect(n.U)
              d.io.allocation.bits.status.expect((if (n < 4) BinPageStatus.success else BinPageStatus.exhausted).U)
              d.io.allocation.bits.address.expect((if (n < 4) base+n*bytes else BigInt(0)).U)
              received += 1; held = None
            } else held = Some(d.io.allocation.bits.peek().litValue)
          } else assert(held.isEmpty)
          if (sent < 5 && d.io.allocate.ready.peek().litToBoolean) {
            d.io.allocated.expect((sent < 4).B)
            d.io.allocationFailed.expect((sent == 4).B)
            pending.enqueue(sent); sent += 1
          } else {
            d.io.allocated.expect(false.B); d.io.allocationFailed.expect(false.B)
          }
          d.clock.step(); ticks += 1
        }
        assert(sent == 5 && received == 5 && pending.isEmpty)
        d.io.exhausted.expect(true.B)
        d.io.allocate.valid.poke(true.B)
        for (_ <- 0 until 4) { d.io.allocate.ready.expect(false.B); d.clock.step() }
        finish(d,BinPageStatus.exhausted,4)
      }
    }
  }

  it should "reject malformed arenas and admit an empty bounded arena without a memory access" in {
    simulate(new BinPagePool()) { d =>
      initialize(d)
      val bad = Seq((BigInt(1),BigInt(8192),12,1),
        (BigInt(0),BigInt(8193),12,1),(BigInt(8192),BigInt(4096),12,1),
        (BigInt(0),(BigInt(1)<<32)+4096,12,1),
        (BigInt(0),BigInt(4096),11,1),(BigInt(0),BigInt(4096),12,3))
      for (((base,limit,bits,version),n) <- bad.zipWithIndex) {
        bind(d,base,limit,bits,generation=n,version=version)
        d.io.allocate.valid.poke(true.B)
        var ticks = 0
        while (!d.io.completion.valid.peek().litToBoolean && ticks < 10) {
          d.io.allocate.ready.expect(false.B)
          d.io.allocation.valid.expect(false.B)
          d.clock.step(); ticks += 1
        }
        assert(ticks < 10)
        d.io.completion.bits.status.expect(BinPageStatus.invalidDescriptor.U)
        d.io.completion.bits.allocatedPages.expect(0.U)
        d.io.completion.bits.identity.generation.expect(n.U)
        d.io.completion.ready.poke(true.B); d.clock.step()
        d.io.completion.ready.poke(false.B); d.io.allocate.valid.poke(false.B)
      }
      bind(d,4096,4096,12)
      d.io.allocate.bits.token.poke(0x55.U); d.io.allocate.valid.poke(true.B)
      d.io.allocate.ready.expect(true.B); d.clock.step(); d.io.allocate.valid.poke(false.B)
      d.io.allocation.valid.expect(true.B)
      d.io.allocation.bits.status.expect(BinPageStatus.exhausted.U)
      d.io.allocation.bits.request.token.expect(0x55.U)
      d.io.allocation.bits.address.expect(0.U)
      d.io.allocation.ready.poke(true.B); d.clock.step()
      finish(d,BinPageStatus.exhausted,0)
    }
  }

  it should "retain the final 4GiB page and offered ownership across cancellation and rebinding" in {
    simulate(new BinPagePool()) { d =>
      initialize(d)
      bind(d,BigInt("fffff000",16),BigInt(1)<<32,12)
      d.io.allocate.bits.tile.poke(7.U); d.io.allocate.bits.token.poke(9.U)
      d.io.allocate.valid.poke(true.B); d.io.allocate.ready.expect(true.B)
      d.clock.step(); d.io.allocate.valid.poke(false.B)
      d.io.allocation.valid.expect(true.B)
      d.io.allocation.bits.address.expect(BigInt("fffff000",16).U)
      val held = d.io.allocation.bits.peek().litValue
      d.io.cancel.poke(true.B); d.clock.step(); d.io.cancel.poke(false.B)
      for (_ <- 0 until 5) {
        d.io.allocation.valid.expect(true.B)
        assert(d.io.allocation.bits.peek().litValue == held)
        d.io.completion.valid.expect(false.B); d.io.begin.ready.expect(false.B)
        d.clock.step()
      }
      d.io.allocation.ready.poke(true.B); d.clock.step(); d.clock.step()
      d.io.completion.valid.expect(true.B)
      d.io.completion.bits.status.expect(BinPageStatus.cancelled.U)
      d.io.completion.bits.allocatedPages.expect(1.U)
      d.io.completion.ready.poke(true.B); d.clock.step()
      d.io.completion.ready.poke(false.B)
      bind(d,BigInt("fffff000",16),BigInt(1)<<32,12,generation=20)
      d.io.allocate.valid.poke(true.B); d.clock.step(); d.io.allocate.valid.poke(false.B)
      d.io.allocation.bits.identity.generation.expect(20.U)
      d.io.allocation.bits.ordinal.expect(0.U)
      d.io.allocation.bits.address.expect(BigInt("fffff000",16).U)
      d.clock.step()
      d.io.allocate.valid.poke(true.B); d.clock.step(); d.io.allocate.valid.poke(false.B)
      d.io.allocation.bits.status.expect(BinPageStatus.exhausted.U)
      d.io.allocation.bits.address.expect(0.U)
      d.clock.step()
      finish(d,BinPageStatus.exhausted,1,generation=20)
    }
  }

  it should "sustain one page reply per cycle and reset or cancel before admission" in {
    simulate(new BinPagePool()) { d =>
      initialize(d)
      bind(d,0,64*4096,12)
      d.io.allocation.ready.poke(true.B)
      for (tick <- 0 until 65) {
        d.io.allocate.valid.poke((tick < 64).B)
        d.io.allocate.bits.tile.poke((tick%3).U); d.io.allocate.bits.token.poke(tick.U)
        d.io.allocation.valid.expect((tick >= 1).B)
        if (tick < 64) d.io.allocate.ready.expect(true.B)
        if (tick >= 1) {
          d.io.allocation.bits.ordinal.expect((tick-1).U)
          d.io.allocation.bits.address.expect(((tick-1)*4096).U)
          d.io.allocation.bits.request.token.expect((tick-1).U)
        }
        d.clock.step()
      }
      finish(d,BinPageStatus.success,64)
      // A new binding can be cancelled while its descriptor is validating.
      d.io.begin.bits.version.poke(1.U); d.io.begin.valid.poke(true.B)
      d.clock.step(); d.io.begin.valid.poke(false.B); d.io.cancel.poke(true.B)
      d.clock.step(); d.io.cancel.poke(false.B); d.clock.step()
      d.io.completion.valid.expect(true.B)
      d.io.completion.bits.status.expect(BinPageStatus.cancelled.U)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      d.io.completion.valid.expect(false.B); d.io.busy.expect(false.B)
      d.io.allocation.valid.expect(false.B); d.io.begin.ready.expect(true.B)
    }
  }

  it should "stop admission at end and delay completion until the offered page is consumed" in {
    simulate(new BinPagePool()) { d =>
      initialize(d)
      bind(d,0,8192,12)
      d.io.allocate.bits.token.poke(77.U); d.io.allocate.valid.poke(true.B)
      d.clock.step()
      d.io.allocation.valid.expect(true.B)
      val held = d.io.allocation.bits.peek().litValue
      d.io.allocate.bits.token.poke(78.U)
      d.io.end.valid.poke(true.B)
      d.io.end.ready.expect(true.B); d.io.allocate.ready.expect(false.B)
      d.clock.step(); d.io.end.valid.poke(false.B)
      d.io.allocate.valid.poke(false.B)
      for (_ <- 0 until 5) {
        d.io.completion.valid.expect(false.B); d.io.begin.ready.expect(false.B)
        d.io.allocation.valid.expect(true.B)
        assert(d.io.allocation.bits.peek().litValue == held)
        d.clock.step()
      }
      d.io.allocation.ready.poke(true.B); d.clock.step(); d.clock.step()
      d.io.completion.valid.expect(true.B)
      d.io.completion.bits.status.expect(BinPageStatus.success.U)
      d.io.completion.bits.allocatedPages.expect(1.U)
      d.io.completion.ready.poke(true.B); d.clock.step()
      d.io.completion.ready.poke(false.B)
      bind(d,0,8192,12,generation=21)
      d.io.allocation.ready.poke(false.B)
      d.io.allocate.valid.poke(true.B); d.clock.step()
      d.io.allocate.valid.poke(false.B); d.io.allocation.valid.expect(true.B)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      d.io.allocation.valid.expect(false.B); d.io.completion.valid.expect(false.B)
      d.io.begin.ready.expect(true.B); d.io.busy.expect(false.B)
    }
  }
  it should "cross all eight preallocated gaps under backpressure and retain the exact exhausted token" in {
    simulate(new BinPagePool(128)) { d=>
      initialize(d)
      for (emptyPrimary <- Seq(false,true)) {
        val base=0x10000L
        val spans=(0 until 8).map(i=>(base+0x1000L*(i+1),base+0x1000L*(i+1)+128L*(1+i%3)))
        val addresses=(if (emptyPrimary) Vector.empty[Long] else Vector(base,base+128)) ++
          spans.flatMap { case (start,end)=>(start until end by 128L) }
        bind(d,base,spans.last._2,7,version=2,mutate=p=>{
          p.initialLimit.poke((if (emptyPrimary) base else base+256).U)
          p.spillCount.poke(8.U)
          for (((start,end),i) <- spans.zipWithIndex) {
            p.spills(i).base.poke(start.U); p.spills(i).limit.poke(end.U)
          }
        })
        val pending=mutable.Queue.empty[Int]
        var sent=0; var received=0; var ticks=0; var transitions=0
        var held=Option.empty[BigInt]
        while (received<=addresses.size && ticks<500) {
          val sending=sent<=addresses.size
          d.io.allocate.valid.poke(sending.B)
          d.io.allocate.bits.tile.poke((sent%3).U); d.io.allocate.bits.token.poke((1000+sent).U)
          val ready=ticks%11>=5
          d.io.allocation.ready.poke(ready.B)
          if (d.io.spilled.peek().litToBoolean) transitions+=1
          if (d.io.allocation.valid.peek().litToBoolean) {
            held.foreach(old=>assert(d.io.allocation.bits.peek().litValue==old))
            if (ready) {
              val n=pending.dequeue()
              val success=n<addresses.size
              d.io.allocation.bits.status.expect((if (success) BinPageStatus.success else BinPageStatus.exhausted).U)
              d.io.allocation.bits.address.expect((if (success) addresses(n) else 0L).U)
              d.io.allocation.bits.ordinal.expect((if (success) (addresses(n)-base)/128 else addresses.size.toLong).U)
              d.io.allocation.bits.request.tile.expect((n%3).U)
              d.io.allocation.bits.request.token.expect((1000+n).U)
              received+=1; held=None
            } else held=Some(d.io.allocation.bits.peek().litValue)
          } else assert(held.isEmpty)
          if (sending && d.io.allocate.ready.peek().litToBoolean) {
            pending.enqueue(sent); sent+=1
          }
          d.clock.step(); ticks+=1
        }
        assert(received==addresses.size+1 && pending.isEmpty && transitions==8)
        d.io.exhausted.expect(true.B)
        finish(d,BinPageStatus.exhausted,addresses.size,spills=8)
      }
    }
  }

  it should "reject malformed spill layouts before allocating any page and cancel validation" in {
    simulate(new BinPagePool(128)) { d=>
      initialize(d)
      val mutations=Seq[BinPagePoolDescriptor=>Unit](
        _.spillCount.poke(9.U),_.initialLimit.poke(0xff80.U),_.initialLimit.poke(0x10081.U),
        _.spills(0).base.poke(0x10000.U),_.spills(0).base.poke(0x11001.U),
        _.spills(0).limit.poke(0x11000.U),_.spills(0).limit.poke(0x11101.U),
        _.spills(0).limit.poke(0x11280.U),_.limit.poke(0x11200.U),
        _.spills(7).base.poke(0x12000.U),
        p=>{p.spillCount.poke(2.U); p.spills(1).base.poke(0x11000.U); p.spills(1).limit.poke(0x11100.U)})
      for ((mutate,n) <- mutations.zipWithIndex) {
        bind(d,0x10000,0x11100,7,generation=n,version=2,mutate=p=>{
          p.initialLimit.poke(0x10080.U); p.spillCount.poke(1.U)
          p.spills(0).base.poke(0x11000.U); p.spills(0).limit.poke(0x11100.U); mutate(p)
        })
        d.io.allocate.valid.poke(true.B)
        var ticks=0
        while (!d.io.completion.valid.peek().litToBoolean && ticks<20) {
          d.io.allocate.ready.expect(false.B); d.io.allocation.valid.expect(false.B)
          d.io.spilled.expect(false.B); d.clock.step(); ticks+=1
        }
        assert(ticks<20)
        d.io.completion.bits.status.expect(BinPageStatus.invalidDescriptor.U)
        d.io.completion.bits.allocatedPages.expect(0.U); d.io.completion.bits.spillTransitions.expect(0.U)
        d.io.completion.ready.poke(true.B); d.clock.step(); d.io.completion.ready.poke(false.B)
        d.io.allocate.valid.poke(false.B)
      }
      bind(d,0x10000,0x11100,7,version=2,mutate=p=>{
        p.initialLimit.poke(0x10080.U); p.spillCount.poke(1.U)
        p.spills(0).base.poke(0x11000.U); p.spills(0).limit.poke(0x11100.U)
      })
      d.clock.step(3); d.io.cancel.poke(true.B); d.clock.step(); d.io.cancel.poke(false.B)
      d.clock.step(2); d.io.completion.valid.expect(true.B)
      d.io.completion.bits.status.expect(BinPageStatus.cancelled.U)
      d.io.completion.bits.allocatedPages.expect(0.U)
    }
  }

  it should "spill into the final 4GiB page and preserve a held allocation across cancellation" in {
    simulate(new BinPagePool()) { d=>
      initialize(d)
      val base=BigInt("ffffc000",16)
      bind(d,base,BigInt(1)<<32,12,version=2,mutate=p=>{
        p.initialLimit.poke(base.U); p.spillCount.poke(1.U)
        p.spills(0).base.poke(BigInt("fffff000",16).U); p.spills(0).limit.poke((BigInt(1)<<32).U)
      })
      d.io.allocate.valid.poke(true.B); d.io.allocate.bits.token.poke(991.U)
      var ticks=0
      while (!d.io.allocate.ready.peek().litToBoolean && ticks<20) { d.clock.step(); ticks+=1 }
      assert(ticks<20); d.clock.step(); d.io.allocate.valid.poke(false.B)
      d.io.allocation.valid.expect(true.B); d.io.allocation.bits.ordinal.expect(3.U)
      d.io.allocation.bits.address.expect(BigInt("fffff000",16).U)
      val held=d.io.allocation.bits.peek().litValue
      d.io.cancel.poke(true.B); d.clock.step(); d.io.cancel.poke(false.B)
      for (_ <- 0 until 4) {
        assert(d.io.allocation.bits.peek().litValue==held); d.io.allocation.valid.expect(true.B)
        d.io.completion.valid.expect(false.B); d.clock.step()
      }
      d.io.allocation.ready.poke(true.B); d.clock.step(2)
      d.io.completion.valid.expect(true.B); d.io.completion.bits.status.expect(BinPageStatus.cancelled.U)
      d.io.completion.bits.allocatedPages.expect(1.U); d.io.completion.bits.spillTransitions.expect(1.U)
    }
  }

}
