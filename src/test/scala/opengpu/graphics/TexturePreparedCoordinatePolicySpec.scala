package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable
import scala.util.Random

private class TexturePreparedPolicyHarness(overlap: Boolean) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new TextureCoordinateRequest(4, true)))
    val out = Decoupled(new Bundle { val value = UInt(32.W); val fault = Bool() })
    val invalidate = Input(Bool())
    val mip = Input(UInt(4.W)); val fraction = Input(UInt(8.W)); val maxLevel = Input(UInt(4.W))
    val admitted = Output(Bool()); val admittedFault = Output(Bool())
    val req = Decoupled(new OmMemoryRequest)
    val resp = Flipped(Decoupled(new OmMemoryResponse))
  })
  val coordinates = Module(new TextureCoordinatePipeline(4, true))
  val sampler = Module(new TextureUnit(wordCacheEntries = 8, allowPrepared = true,
    overlapPrepared = overlap, coordinatePolicyInput = true))
  coordinates.io.in <> io.in
  coordinates.io.out.ready := sampler.io.sample.ready
  sampler.io.sample.valid := coordinates.io.out.valid
  sampler.io.sample.bits := 0.U.asTypeOf(sampler.io.sample.bits)
  sampler.io.sample.bits.mipLevel := io.mip
  sampler.io.sample.bits.lodFrac := io.fraction
  sampler.io.prepared.get.valid := coordinates.io.out.valid
  sampler.io.prepared.get.bits := coordinates.io.out.bits.sample
  sampler.io.preparedCoordinateFault.get := coordinates.io.out.bits.coordinateFault.get
  sampler.io.invalidateCache.get := io.invalidate
  // Prepared metadata must remain authoritative even with poisoned live bindings.
  sampler.io.texBase := "he0000000".U
  sampler.io.texWidth := 0.U; sampler.io.texHeight := 0.U
  sampler.io.texFormat := 0.U; sampler.io.wrapMode := true.B
  sampler.io.texMaxLevel := io.maxLevel
  sampler.io.coordinatePolicy.get.wrapU := 3.U
  sampler.io.coordinatePolicy.get.wrapV := 3.U
  sampler.io.coordinatePolicy.get.signed := false.B
  io.out.valid := sampler.io.result.valid
  sampler.io.result.ready := io.out.ready
  io.out.bits.value := sampler.io.result.bits
  io.out.bits.fault := sampler.io.coordinateFault.get
  io.admitted := sampler.io.sample.fire
  io.admittedFault := sampler.io.sample.fire && coordinates.io.out.bits.coordinateFault.get
  io.req <> sampler.io.mem.req
  sampler.io.mem.resp <> io.resp
}

class TexturePreparedCoordinatePolicySpec extends AnyFlatSpec {
  behavior of "Prepared texture coordinate policy"
  private case class Request(lane: Int, u: Long, v: Long, base: Long, w: Int, h: Int,
    wrapU: Int, wrapV: Int, signed: Boolean) {
    def fault: Boolean = w == 0 || h == 0 || wrapU == 3 || wrapV == 3
  }
  private def coefficients(r: Request): Map[Long, Int] = {
    if (r.fault) return Map.empty
    def axis(raw: Long, extent: Int, mode: Int): (Int, Int, Int) = {
      val c = if (r.signed && (raw & 0x80000000L) != 0) raw - 0x100000000L else raw
      val q = (c * extent >> 8) - 128
      val index = Math.floorDiv(q, 256L); val fraction = Math.floorMod(q, 256L).toInt
      def wrap(i: Long): Int = mode match {
        case 0 => Math.floorMod(i, extent.toLong).toInt
        case 1 => i.max(0).min(extent - 1).toInt
        case 2 => val p = Math.floorMod(i, extent * 2L); (if (p < extent) p else extent * 2L - 1 - p).toInt
      }
      (wrap(index), wrap(index + 1), fraction)
    }
    val (x0,x1,fx) = axis(r.u,r.w,r.wrapU); val (y0,y1,fy) = axis(r.v,r.h,r.wrapV)
    val positions = Seq((x0,y0),(x1,y0),(x0,y1),(x1,y1))
    val weights = Seq((256-fx)*(256-fy),fx*(256-fy),(256-fx)*fy,fx*fy)
    positions.zip(weights).map { case ((x,y),weight) =>
      ((r.base + (y.toLong*r.w+x)*4) & 0xffffffffL,weight)
    }.filter(_._2 != 0).groupMapReduce(_._1)(_._2)(_+_)
  }
  private def word(address: Long): BigInt = (0 until 4).foldLeft(BigInt(0)) { (v,c) =>
    (v << 8) | BigInt(((address >> 2)*(13+c*6) ^ (address >> (7+c)) ^ (c*71+19)) & 255)
  }
  private def color(r: Request): BigInt = {
    val taps = coefficients(r)
    (0 until 4).foldLeft(BigInt(0)) { (v,c) =>
      (v << 8) | (taps.toSeq.map { case (a,w) => ((word(a) >> (24-c*8)) & 255)*w }.sum >> 16)
    }
  }
  private def poke(port: TextureCoordinateRequest, r: Request): Unit = {
    port.lane.poke(r.lane.U); port.u.poke(r.u.U); port.v.poke(r.v.U)
    port.base.poke(r.base.U); port.texWidth.poke(r.w.U); port.texHeight.poke(r.h.U)
    port.clamp.poke(false.B)
    port.coordinatePolicy.get.wrapU.poke(r.wrapU.U); port.coordinatePolicy.get.wrapV.poke(r.wrapV.U)
    port.coordinatePolicy.get.signed.poke(r.signed.B)
  }
  private def cases: Vector[Request] = {
    val rng = new Random(0x50524550L)
    val edges = Vector[Long](0,1,127,128,255,32767,32768,65535,65536,65537,98303,98304,
      131071,131072,0x7fffffffL,0x80000000L,0xffff8000L,0xffffffffL)
    val dimensions = Vector((1,1),(1,3),(7,1),(3,5),(257,129),(16383,16383),(16383,16382))
    val directed = for ((w,h) <- dimensions; u <- 0 to 2; v <- 0 to 2; signed <- Vector(false,true);
      (c,i) <- edges.zipWithIndex) yield Request(i%16,c,edges((i*7+3)%edges.size),
        if (i%3==0) 0xfffffff0L else 0x10000L,w,h,u,v,signed)
    val random = Vector.tabulate(400) { n => val (w,h)=dimensions(n%dimensions.size)
      Request(n%16,rng.nextLong()&0xffffffffL,rng.nextLong()&0xffffffffL,
        rng.nextLong()&0xfffffffcL,w,h,n%3,(n/3)%3,n%2==0) }
    val invalid = Vector((0,5,0,0),(3,0,2,1),(0,0,3,3),(3,5,3,0),(3,5,0,3),(3,5,3,3)).map {
      case(w,h,u,v) => Request(15,0xffffffffL,0x10000L,0xe0000000L,w,h,u,v,true)
    }
    directed ++ random ++ invalid
  }
  it should "preserve independent index-wrap coefficients and faults through six elastic stages" in {
    val requests = cases
    simulate(new TextureCoordinatePipeline(4,true)) { d =>
      PointLineReference.zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      val pending = mutable.Queue.empty[Request]
      var sent=0; var received=0; var cycle=0; var held: Option[BigInt]=None
      def check(r: Request): Unit = {
        d.io.out.bits.lane.expect(r.lane.U); d.io.out.bits.coordinateFault.get.expect(r.fault.B)
        val got = (0 until 4).map { i =>
          (d.io.out.bits.sample.addresses(i).peek().litValue.toLong,
            d.io.out.bits.sample.weights(i).peek().litValue.toInt)
        }
        if (r.fault) assert(got.forall { case(a,w) => a==0 && w==0 })
        else {
          assert(got.map(_._2).sum == 65536)
          val merged = got.filter(_._2!=0).groupMapReduce(_._1)(_._2)(_+_)
          assert(merged == coefficients(r),s"index-wrap mismatch: $r got=$merged expected=${coefficients(r)}")
        }
      }
      while(received<requests.size && cycle<30000) {
        val send=sent<requests.size && cycle%17<13
        d.io.in.valid.poke(send.B)
        // Mutate unaccepted pins continuously; only the handshake owns metadata.
        poke(d.io.in.bits,requests(if(sent<requests.size)sent else cycle%requests.size))
        val ready=cycle%31<17 && cycle%7!=3; d.io.out.ready.poke(ready.B)
        if(d.io.out.valid.peek().litToBoolean) {
          val snapshot=d.io.out.bits.peek().litValue
          held.foreach(x=>assert(x==snapshot,"metadata/fault changed under backpressure"))
          if(ready) { check(pending.dequeue()); received+=1; held=None } else held=Some(snapshot)
        } else assert(held.isEmpty)
        if(send && d.io.in.ready.peek().litToBoolean) { pending.enqueue(requests(sent)); sent+=1 }
        d.clock.step(); cycle+=1
      }
      assert(received==requests.size && pending.isEmpty)
      d.io.in.valid.poke(false.B); d.io.out.ready.poke(true.B); d.clock.step(2); d.io.busy.expect(false.B)
      val stream=requests.take(506)++requests.takeRight(6)
      for(tick<-0 until stream.size+6) {
        d.io.in.valid.poke((tick<stream.size).B)
        if(tick<stream.size) { poke(d.io.in.bits,stream(tick)); d.io.in.ready.expect(true.B) }
        d.io.out.valid.expect((tick>=6).B)
        if(tick>=6) check(stream(tick-6))
        d.clock.step()
      }
      d.io.busy.expect(false.B); d.io.out.valid.expect(false.B)
      d.io.in.valid.poke(true.B); d.io.out.ready.poke(false.B); d.clock.step(6)
      d.io.out.valid.expect(true.B); d.io.busy.expect(true.B)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      d.io.in.valid.poke(false.B); d.io.out.ready.poke(true.B); d.clock.step(6)
      d.io.busy.expect(false.B); d.io.out.valid.expect(false.B)
      info(s"PREPARED_POLICY_COORDINATES cases=$received faults=${requests.count(_.fault)} stream=512 latency=6 bubbles=0 signedAndUnsigned=true maxBothAxes=true wrappedAddress32=true heldFault=true resetOccupied=true")
    }
  }
  for(overlap<-Seq(false,true))
  it should s"filter ordered prepared policies through faults held results and deferred invalidation overlap=$overlap" in {
    val rng=new Random(88421)
    val good=Request(0,0xffff4001L,0x23456L,0x10000L,7,5,2,0,true)
    val bad=good.copy(lane=1,base=0xe0000000L,wrapU=3)
    val stream=Vector(good,bad,good.copy(lane=2,u=1,wrapU=1,wrapV=2)) ++
      Vector.tabulate(180) { n =>
        val(w,h)=Vector((1,1),(3,5),(7,1),(1,9),(257,129),(16383,16383))(n%6)
        Request(n%16,rng.nextLong()&0xffffffffL,rng.nextLong()&0xffffffffL,
          0x10000L+n.toLong*0x1000,w,h,if(n%19==0)3 else n%3,(n/3)%3,n%2==0)
      } ++ Vector(bad.copy(wrapU=0,w=0),bad.copy(wrapU=0,h=0),good)
    simulate(new TexturePreparedPolicyHarness(overlap)) { d =>
      PointLineReference.zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      var physicalReads=0; var reordered=0; var faultResults=0; var invalidationPulses=0
      var earlyAdmissions=0; var earlyFaultAdmissions=0
      def run(requests: Vector[Request], forcedFault: Boolean=false, initialHold: Boolean=false): Unit = {
        val pending=mutable.ArrayBuffer.empty[(Long,Int)]; var reply: Option[Long]=None
        var sent=0; var received=0; var admitted=0; var cycle=0; var held: Option[BigInt]=None
        var invalidated=false
        val allowed=mutable.ArrayBuffer.empty[Request]
        while(received<requests.size && cycle<30000) {
          val send=sent<requests.size && cycle%11<9
          d.io.in.valid.poke(send.B)
          poke(d.io.in.bits,requests(if(sent<requests.size)sent else cycle%requests.size))
          val ready=(!initialHold || cycle>=100) && cycle%17<11
          d.io.out.ready.poke(ready.B)
          d.io.req.ready.poke((cycle%5!=0).B)
          val invalidate= !invalidated && pending.nonEmpty
          d.io.invalidate.poke(invalidate.B)
          if(invalidate) { invalidated=true; invalidationPulses+=1 }
          if(reply.isEmpty && cycle%3==0) {
            val choices=pending.indices.filter(i=>pending(i)._2<=cycle)
            if(choices.nonEmpty) {val i=choices.last; if(i>0)reordered+=1; reply=Some(pending.remove(i)._1)}
          }
          d.io.resp.valid.poke(reply.nonEmpty.B)
          reply.foreach { a => d.io.resp.bits.addr.poke(a.U); d.io.resp.bits.data.poke(word(a).U); d.io.resp.bits.write.poke(false.B) }
          val response=reply.nonEmpty && d.io.resp.ready.peek().litToBoolean
          if(d.io.in.valid.peek().litToBoolean && d.io.in.ready.peek().litToBoolean) {
            allowed+=requests(sent); sent+=1
          }
          if(d.io.admitted.peek().litToBoolean) {
            admitted+=1
            if(initialHold && cycle<100) {
              earlyAdmissions+=1
              if(d.io.admittedFault.peek().litToBoolean)earlyFaultAdmissions+=1
            }
          }
          if(d.io.req.valid.peek().litToBoolean && d.io.req.ready.peek().litToBoolean) {
            val a=d.io.req.bits.addr.peek().litValue.toLong
            assert(!forcedFault && allowed.exists(r=> !r.fault && coefficients(r).contains(a)),s"unowned physical read $a")
            pending+=((a,cycle+4)); physicalReads+=1
          }
          if(d.io.out.valid.peek().litToBoolean) {
            val snapshot=d.io.out.bits.peek().litValue
            held.foreach(x=>assert(x==snapshot,"younger context changed held result/fault"))
            val r=requests(received); val fault=forcedFault || r.fault
            d.io.out.bits.fault.expect(fault.B); d.io.out.bits.value.expect((if(fault)BigInt(0)else color(r)).U)
            if(ready) {if(fault)faultResults+=1; received+=1; held=None} else held=Some(snapshot)
          } else assert(held.isEmpty)
          d.clock.step(); if(response)reply=None; cycle+=1
        }
        assert(received==requests.size && admitted==requests.size && pending.isEmpty && reply.isEmpty)
        d.io.in.valid.poke(false.B); d.io.invalidate.poke(false.B); d.io.out.ready.poke(true.B)
        d.clock.step(3); d.io.out.valid.expect(false.B)
      }
      run(stream,initialHold=true)
      if(overlap) assert(earlyAdmissions>=3 && earlyFaultAdmissions>=1,"fault successor did not overlap a held older result")
      else assert(earlyAdmissions==1 && earlyFaultAdmissions==0)
      // Reject all unsupported prepared LOD inputs before memory access, then reuse.
      d.io.mip.poke(1.U); run(Vector(good),forcedFault=true); d.io.mip.poke(0.U)
      d.io.fraction.poke(1.U); run(Vector(good),forcedFault=true); d.io.fraction.poke(0.U)
      d.io.maxLevel.poke(1.U); run(Vector(good),forcedFault=true); d.io.maxLevel.poke(0.U)
      run(Vector(good,bad,good.copy(u=0xffffffffL)))
      assert(reordered>0 && invalidationPulses>=1)
      info(s"PREPARED_POLICY_SAMPLER overlap=$overlap results=${stream.size+6} faults=$faultResults reads=$physicalReads reordered=$reordered invalidatePulses=$invalidationPulses earlyAdmissions=$earlyAdmissions earlyFaultAdmissions=$earlyFaultAdmissions poisonedLiveBinding=true noInvalidReads=true heldResultFault=true fixedMipFaults=3 healthyReuse=true")
    }
  }
}
