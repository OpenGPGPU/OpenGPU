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
            assert(!forcedFault && allowed.exists(r=> !r.fault && a>=r.base && a<r.base+r.w.toLong*r.h*4 && (a-r.base)%4==0),s"unowned physical read $a")
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
  it should "retain direct signed mip sampling and fault recovery in a prepared-capable overlap sampler" in {
    simulate(new TextureUnit(wordCacheEntries=8,allowPrepared=true,overlapPrepared=true,coordinatePolicyInput=true)) { d =>
      PointLineReference.zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      val good=Request(0,0xfffed234L,0x23567L,0x10000L,7,5,2,0,true)
      var results=0; var faults=0; var reads=0
      def run(r: Request,mip: Int=0,fraction: Int=0,maxLevel: Int=0): Unit = {
        d.io.prepared.get.valid.poke(false.B); d.io.preparedCoordinateFault.get.poke(false.B)
        d.io.texBase.poke(r.base.U); d.io.texWidth.poke(r.w.U); d.io.texHeight.poke(r.h.U)
        d.io.texMaxLevel.poke(maxLevel.U); d.io.texFormat.poke(0.U); d.io.wrapMode.poke(false.B)
        d.io.coordinatePolicy.get.wrapU.poke(r.wrapU.U); d.io.coordinatePolicy.get.wrapV.poke(r.wrapV.U)
        d.io.coordinatePolicy.get.signed.poke(r.signed.B)
        d.io.sample.bits.u.poke(r.u.U); d.io.sample.bits.v.poke(r.v.U)
        d.io.sample.bits.mipLevel.poke(mip.U); d.io.sample.bits.lodFrac.poke(fraction.U)
        d.io.sample.valid.poke(true.B); d.io.invalidateCache.get.poke(true.B)
        def level(l: Int): Request = {
          var b=r.base; var w=r.w; var h=r.h
          for(_<-0 until l) {b+=w.toLong*h*4; w=(w/2).max(1); h=(h/2).max(1)}
          r.copy(base=b,w=w,h=h)
        }
        val l=mip.min(maxLevel)
        val first=if(r.fault)BigInt(0)else color(level(l))
        val expected=if(r.fault || fraction==0 || l==maxLevel)first else {
          val second=color(level(l+1))
          (0 until 4).foldLeft(BigInt(0)) { (out,c) => val shift=24-c*8
            (out<<8)|(((((first>>shift)&255)*(256-fraction)+((second>>shift)&255)*fraction)>>8)&255)
          }
        }
        val pending=mutable.ArrayBuffer.empty[(Long,Int)]; var reply: Option[Long]=None
        var accepted=false; var done=false; var cycle=0; var held=0; val before=reads
        while(!done && cycle<500) {
          if(accepted) {
            d.io.sample.valid.poke(false.B); d.io.invalidateCache.get.poke(false.B)
            d.io.texBase.poke("he0000000".U); d.io.texWidth.poke(0.U); d.io.texHeight.poke(0.U)
            d.io.texMaxLevel.poke(0.U); d.io.texFormat.poke(1.U)
            d.io.coordinatePolicy.get.wrapU.poke(3.U); d.io.coordinatePolicy.get.wrapV.poke(3.U)
            d.io.coordinatePolicy.get.signed.poke((!r.signed).B)
          }
          d.io.result.ready.poke(false.B); d.io.mem.req.ready.poke((cycle%5!=0).B)
          if(reply.isEmpty && cycle%3==0) {
            val eligible=pending.indices.filter(i=>pending(i)._2<=cycle)
            if(eligible.nonEmpty)reply=Some(pending.remove(eligible.last)._1)
          }
          d.io.mem.resp.valid.poke(reply.nonEmpty.B)
          reply.foreach {a=>d.io.mem.resp.bits.addr.poke(a.U); d.io.mem.resp.bits.data.poke(word(a).U); d.io.mem.resp.bits.write.poke(false.B)}
          val response=reply.nonEmpty && d.io.mem.resp.ready.peek().litToBoolean
          val accept=d.io.sample.valid.peek().litToBoolean && d.io.sample.ready.peek().litToBoolean
          if(d.io.mem.req.valid.peek().litToBoolean && d.io.mem.req.ready.peek().litToBoolean) {
            val a=d.io.mem.req.bits.addr.peek().litValue.toLong
            assert(!r.fault && (0 to maxLevel).exists { n => val b=level(n); a>=b.base && a<b.base+b.w.toLong*b.h*4 && (a-b.base)%4==0 })
            pending+=((a,cycle+3)); reads+=1
          }
          if(d.io.result.valid.peek().litToBoolean) {
            d.io.result.bits.expect(expected.U); d.io.coordinateFault.get.expect(r.fault.B)
            if(held==5) {d.io.result.ready.poke(true.B); done=true} else held+=1
          }
          d.clock.step(); if(accept)accepted=true; if(response)reply=None; cycle+=1
        }
        assert(done && accepted && pending.isEmpty && reply.isEmpty)
        if(r.fault) {assert(reads==before); faults+=1}
        results+=1
      }
      for(u<-0 to 2;v<-0 to 2)run(good.copy(wrapU=u,wrapV=v))
      run(good,1,127,3); run(good,2,255,3); run(good.copy(signed=false),0,1,3)
      run(good.copy(wrapU=3)); run(good.copy(wrapV=3)); run(good.copy(w=0)); run(good.copy(h=0))
      run(good.copy(w=1,h=1)); run(good)
      assert(results==18 && faults==4)
      info(s"PREPARED_POLICY_DIRECT results=$results faults=$faults reads=$reads mixedAxes=9 capturedMipBinding=true trilinear=true zeroDimensions=true noInvalidReads=true heldFault=true healthyReuse=true")
    }
  }
}
