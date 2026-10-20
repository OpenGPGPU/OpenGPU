package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private[graphics] object Rgba8FloatOracle {
  val denominator = BigInt(255) << 16
  // Rational arithmetic: choose the binary exponent,then round the exact
  // significand quotient with nearest-even. Independent of byte folding.
  def bits(n: BigInt): BigInt = {
    require(n >= 0 && n <= denominator)
    if(n == 0) return BigInt(0)
    var e = n.bitLength - denominator.bitLength
    val below = if(e >= 0) n < (denominator << e) else (n << -e) < denominator
    if(below) e -= 1
    val (q,r) = (n << (23-e)) /% denominator
    var rounded = q + (if(2*r > denominator || (2*r == denominator && q.testBit(0))) 1 else 0)
    if(rounded.testBit(24)) { rounded >>= 1; e += 1 }
    (BigInt(e+127) << 23) | (rounded & 0x7fffff)
  }
}
class Rgba8WeightedToFp32Spec extends AnyFlatSpec {
  import WindowRecordTest._
  import Rgba8FloatOracle._
  private def init(d: Rgba8WeightedToFp32): Unit = {
    zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
  }
  private def offer(d: Rgba8WeightedToFp32, tag: Int, values: Seq[BigInt], cancelled: Boolean=false): Unit = {
    d.io.in.valid.poke(true.B); d.io.in.bits.tag.poke(tag.U); d.io.in.bits.cancelled.poke(cancelled.B)
    d.io.in.bits.numerators.zip(values).foreach { case(a,b) => a.poke(b.U) }
  }
  it should "round rational boundary and random weighted numerators at one complete RGBA group per cycle" in {
    simulate(new Rgba8WeightedToFp32()) { d =>
      init(d); d.io.out.ready.poke(true.B)
      val limits=Vector[BigInt](0,1,2,denominator-2,denominator-1,denominator) ++
        (0 to 16).flatMap(k => (-3 to 3).map(j => (BigInt(255)<<k)+j)).filter(n => n>=0 && n<=denominator)
      val random=new scala.util.Random(0x32f00dL)
      val nums=limits ++ Vector.fill(4096)(BigInt(random.nextInt(denominator.toInt+1)))
      val groups=nums.indices.map(n => (0 until 4).map(c => nums((n+c*997)%nums.size)))
      val pending=mutable.Queue.empty[(Int,Seq[BigInt])]
      var retired=0; var cycle=0; var previous = -1
      def tick(): Unit = {
        if(d.io.out.valid.peek().litToBoolean) {
          assert(pending.nonEmpty); val (tag,want)=pending.dequeue()
          d.io.out.bits.tag.expect(tag.U); d.io.out.bits.cancelled.expect(false.B)
          d.io.out.bits.values.zip(want).foreach { case(a,b) => a.expect(bits(b).U) }
          if(previous>=0)assert(cycle==previous+1,"complete normalized result II exceeded one cycle")
          previous=cycle; retired+=1
        }
        d.clock.step(); cycle+=1
      }
      for((values,n) <- groups.zipWithIndex) {
        offer(d,n,values); d.io.in.ready.expect(true.B); pending.enqueue((n,values)); tick()
      }
      d.io.in.valid.poke(false.B)
      for(_ <- 0 until 6)tick()
      assert(retired==groups.size && pending.isEmpty); d.io.busy.expect(false.B)
      assert(bits(denominator)==BigInt(0x3f800000) && bits(0)==0)
      info(s"RGBA8_FP32_RATIONAL groups=${groups.size} scalarValues=${groups.size*4} exactRationalOracle=true completeII1=true")
    }
  }
  it should "retain every value tag and input cancellation under asymmetric backpressure" in {
    simulate(new Rgba8WeightedToFp32()) { d =>
      init(d)
      val random=new scala.util.Random(0xabc123L)
      val rows=Vector.tabulate(512)(n => (Vector.fill(4)(BigInt(random.nextInt(denominator.toInt+1))),n%13==0))
      val pending=mutable.Queue.empty[Int]
      var sent=0; var got=0; var cycle=0; var held: Option[BigInt]=None
      while(got<rows.size && cycle<6000) {
        d.io.out.ready.poke((cycle%23<8).B)
        if(sent<rows.size)offer(d,sent,rows(sent)._1,rows(sent)._2)else d.io.in.valid.poke(false.B)
        held.foreach { v => d.io.out.valid.expect(true.B); assert(d.io.out.bits.peek().litValue==v) }
        val valid=d.io.out.valid.peek().litToBoolean
        val ready=d.io.out.ready.peek().litToBoolean
        held=if(valid && !ready)Some(d.io.out.bits.peek().litValue)else None
        if(valid && ready) {
          assert(pending.nonEmpty); val tag=pending.dequeue()
          d.io.out.bits.tag.expect(tag.U); d.io.out.bits.cancelled.expect(rows(tag)._2.B)
          d.io.out.bits.values.zip(rows(tag)._1).foreach { case(a,b) => a.expect((if(rows(tag)._2)BigInt(0)else bits(b)).U) }
          got+=1
        }
        if(sent<rows.size && d.io.in.ready.peek().litToBoolean) { pending.enqueue(sent); sent+=1 }
        d.clock.step(); cycle+=1
      }
      assert(sent==512 && got==512 && pending.isEmpty); d.io.busy.expect(false.B)
      info("RGBA8_FP32_BACKPRESSURE rows512=true heldOutputImmutable=true tagsInOrder=true inputCancellation=true exactRationalOracle=true")
    }
  }
  it should "remember cancellation in every blocked internal stage while preserving an offered result" in {
    simulate(new Rgba8WeightedToFp32()) { d =>
      init(d); var cancelledCuts=0; var offeredCuts=0
      val values=Vector[BigInt](1,65535,denominator-1,denominator)
      for(cut <- 0 to 6) {
        d.io.out.ready.poke(false.B); offer(d,cut,values); d.io.in.ready.expect(true.B)
        d.clock.step(); d.io.in.valid.poke(false.B); d.clock.step(cut)
        val public=d.io.out.valid.peek().litToBoolean
        d.io.cancel.poke(true.B); d.clock.step(); d.io.cancel.poke(false.B)
        d.clock.step(7); d.io.out.valid.expect(true.B); d.io.out.bits.tag.expect(cut.U)
        d.io.out.bits.cancelled.expect((!public).B)
        d.io.out.bits.values.zip(values).foreach { case(a,b) => a.expect((if(public)bits(b)else BigInt(0)).U) }
        if(public)offeredCuts+=1 else cancelledCuts+=1
        val held=d.io.out.bits.peek().litValue; d.clock.step(3); assert(d.io.out.bits.peek().litValue==held)
        d.io.out.ready.poke(true.B); d.clock.step(); d.io.busy.expect(false.B)
      }
      assert(cancelledCuts==4 && offeredCuts==3,"cancellation cuts did not witness both internal and public ownership")
      d.io.out.ready.poke(false.B)
      for(n <- 0 until 5) { offer(d,100+n,values); d.io.in.ready.expect(true.B); d.clock.step() }
      d.io.in.valid.poke(false.B); d.io.in.ready.expect(false.B); d.io.out.valid.expect(true.B)
      val held=d.io.out.bits.peek().litValue
      d.io.cancel.poke(true.B); d.clock.step(); d.io.cancel.poke(false.B); d.clock.step(5)
      assert(d.io.out.bits.peek().litValue==held)
      d.io.out.ready.poke(true.B)
      for(n <- 0 until 5) {
        d.io.out.valid.expect(true.B); d.io.out.bits.tag.expect((100+n).U)
        d.io.out.bits.cancelled.expect((n!=0).B)
        d.io.out.bits.values.zip(values).foreach { case(a,b) => a.expect((if(n==0)bits(b)else BigInt(0)).U) }
        d.clock.step()
      }
      d.io.busy.expect(false.B)
      offer(d,200,values); d.clock.step(); d.io.in.valid.poke(false.B); d.clock.step(4)
      d.io.out.valid.expect(true.B); d.io.out.bits.tag.expect(200.U); d.io.out.bits.cancelled.expect(false.B)
      d.io.out.bits.values.zip(values).foreach { case(a,b) => a.expect(bits(b).U) }
      d.clock.step(); d.io.busy.expect(false.B)
      info("RGBA8_FP32_CANCEL internalCuts4=true publicCuts3=true fullPipelineHeld=true unofferedRows4Cancelled=true publicResultImmutable=true healthyReuse=true")
    }
  }
}
