package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable
import scala.util.Random

class Fp32PreparedQuadInterpolatorSpec extends AnyFlatSpec {
  import ClipFloatComponentReference.{V, Request, reference, one}
  import Fp32ViewportReference.{E, calc, decode, fp, pack}
  private val g = GraphicsConfig(screenWidth = 16, screenHeight = 8)
  private case class Fixture(id: Int, slots: Int = 16, kind: Int = 2,
      flat: BigInt = 0, centroid: BigInt = 0, centroidActive: Int = 0,
      perspective: Int = 15, covered: Int = 15, fallback: Int = 0,
      status: Int = 0, faultLane: Int = 0,
      values: Vector[Vector[BigInt]] = Vector.empty,
      weights: Vector[Vector[V]] = Vector.empty,
      centroids: Vector[Vector[V]] = Vector.empty,
      denominators: Vector[V] = Vector.empty) {
    def raw(vertex: Int, row: Int): BigInt = if (values.nonEmpty) values(vertex)(row)
      else fp((row - 19 + vertex * 11 + id * 3) / 64.0)
    def weight(lane: Int, centroid: Boolean): Vector[V] = {
      val supplied = if (centroid) centroids else weights
      if (supplied.nonEmpty) supplied(lane)
      else Vector(32.0-lane, 1.0+lane/2.0, if (kind == 1) 0.0 else 2.0+lane/4.0)
        .map(x => V(decode(fp(x + (if (centroid && x != 0) 0.25 else 0)))))
    }
    def denominator(lane: Int, centroid: Boolean): V = if (denominators.nonEmpty) denominators(lane)
      else { val w=weight(lane,centroid); V(calc(calc(w(0).e,w(1).e,0),w(2).e,0)) }
    def setupOperations: Int = id%5+1
    def laneOperations(lane: Int): Int = 2+lane
    def laneArithmetic(lane: Int): Int = lane&1
    def lanePacking(lane: Int): Int = Seq(0,3,5,0)(lane)
  }
  private case class Expected(words: Vector[Vector[BigInt]], status: Int, fault: Int,
      operations: Int, arithmetic: Int, packing: Int)
  private def expected(f: Fixture, varyings: Int): Expected = {
    val words = Array.fill(4, varyings*4)(BigInt(0))
    val operations = Array.tabulate(4)(f.laneOperations)
    val arithmetic = Array.tabulate(4)(f.laneArithmetic)
    val packing = Array.tabulate(4)(f.lanePacking)
    val failed = Array.fill(4)(false)
    val legal = f.kind <= 2 && f.slots <= varyings && (f.flat >> (f.slots*4)) == 0 &&
      (f.centroid >> (f.slots*4)) == 0
    for (lane <- 0 until 4; row <- 0 until math.min(f.slots*4,varyings*4)) {
      if (f.kind == 0 || f.flat.testBit(row)) words(lane)(row)=f.raw(0,row)
      else if (legal && f.status == 0 && (f.perspective & (1<<lane)) != 0 && !failed(lane)) {
        val centroid = (f.centroidActive & (1<<lane)) != 0 &&
          (f.centroid.testBit(row) || (f.fallback & (1<<lane)) != 0)
        val values = Vector.tabulate(3)(v => if (v <= f.kind) V(decode(f.raw(v,row))) else V())
        val value = reference(Request(f.weight(lane,centroid),values,f.denominator(lane,centroid)))
        words(lane)(row)=pack(value.value.e)
        operations(lane)+=value.operations; arithmetic(lane)|=value.arithmetic; packing(lane)|=value.packing
        failed(lane)=value.error != 0
      }
    }
    val bad = failed.indexWhere(identity)
    val status = if (f.status != 0) f.status else if (!legal) 1 else if (bad >= 0) 7 else 0
    val fault = if (!legal || f.status != 0) f.faultLane else if (bad >= 0) bad else 0
    val last = if (status != 0) fault else 3
    Expected(words.map(_.toVector).toVector,status,fault,
      f.setupOperations+operations.take(last+1).sum,
      1|arithmetic.take(last+1).foldLeft(0)(_ | _),packing.take(last+1).foldLeft(0)(_ | _))
  }
  private def poke(v: ClipFloat, value: V): Unit = {
    v.negative.poke(value.e.sign.B); v.exponent.poke(value.e.exponent.S)
    v.significand.poke(value.e.sig.U); v.special.poke(value.special.U)
  }
  private class Driver(val d: Fp32PreparedQuadInterpolator, val varyings: Int = 16, val contexts: Int = 4) {
    WindowRecordTest.zero(d.io)
    d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
    var cycle=0
    def tick(): Unit = { d.clock.step(); cycle+=1 }
    def until(condition: => Boolean, limit: Int = 3000): Unit = {
      var n=0; while (!condition && n<limit) { tick(); n+=1 }
      assert(condition,s"prepared quad stalled at cycle=$cycle after $n clocks")
    }
    def input(f: Fixture): Unit = {
      WindowRecordTest.zero(d.io.in.bits)
      val in=d.io.in.bits; val q=in.quad
      q.identity.context.poke((f.id+10).U); q.identity.pass.poke((f.id+20).U); q.identity.generation.poke((f.id+30).U)
      q.sequence.poke((1000+f.id).U); q.primitiveId.poke((200+f.id).U); q.instanceId.poke((300+f.id).U)
      q.partIndex.poke((f.id%7).U); q.primitiveKind.poke(f.kind.U); q.varyingSlots.poke(f.slots.U); q.flatMask.poke(f.flat.U)
      for (lane <- 0 until 4) {
        val out=q.lanes(lane)
        out.x.poke((f.id*2+(lane&1)).S); out.y.poke((lane>>1).S)
        out.covered.poke(((f.covered&(1<<lane)) != 0).B); out.coverageMask.poke((if ((f.covered&(1<<lane)) != 0) 7 else 0).U)
        out.perspectiveValid.poke(((f.perspective&(1<<lane)) != 0).B)
        out.sampleLocationValid.poke(((f.fallback&(1<<lane)) != 0).B); out.sampleLocation.poke(1.U)
        poke(out.reciprocalW,V(E(exponent = -2, sig = 0x800000+lane)))
        out.fragCoordW.poke(fp(0.25+lane/64.0).U); out.windowDepth.poke(fp(0.5).U)
        out.sampleDepthValid.poke(true.B)
        for (sample <- 0 until g.maxSampleCount) out.sampleDepth(sample).poke(fp(0.25+sample/16.0).U)
        for (axis <- 0 until 2) out.pointCoord(axis).poke(fp((axis+lane)/16.0).U)
        for (slot <- 0 until varyings; component <- 0 until 4) out.varyings(slot)(component).poke("hdeadbeef".U)
        for (v <- 0 until 3) {
          poke(in.weights(lane)(v),f.weight(lane,false)(v))
          poke(in.centroidWeights(lane)(v),f.weight(lane,true)(v))
        }
        poke(in.denominators(lane),f.denominator(lane,false)); poke(in.centroidDenominators(lane),f.denominator(lane,true))
        in.laneOperations(lane).poke(f.laneOperations(lane).U)
        in.laneArithmeticStatus(lane).poke(f.laneArithmetic(lane).U); in.lanePackingStatus(lane).poke(f.lanePacking(lane).U)
      }
      for (v <- 0 until 3; row <- 0 until varyings*4) in.values(v)(row/4)(row%4).poke(f.raw(v,row).U)
      in.centroidMask.poke(f.centroid.U); in.centroidActive.poke(f.centroidActive.U)
      in.preparationStatus.poke(f.status.U); in.preparationFaultLane.poke(f.faultLane.U)
      in.setupOperations.poke(f.setupOperations.U); in.setupArithmeticStatus.poke(1.U)
    }
    def offer(f: Fixture): Unit = {
      input(f); d.io.in.valid.poke(true.B); until(d.io.in.ready.peek().litToBoolean); tick()
      d.io.in.valid.poke(false.B)
      WindowRecordTest.zero(d.io.in.bits) // Accepted snapshots must own their data.
    }
    def checkIdentity(f: Fixture, q: Fp32InterpolatedQuad): Unit = {
      q.identity.context.expect((f.id+10).U); q.identity.pass.expect((f.id+20).U); q.identity.generation.expect((f.id+30).U)
      q.sequence.expect((1000+f.id).U); q.primitiveId.expect((200+f.id).U); q.instanceId.expect((300+f.id).U)
      q.partIndex.expect((f.id%7).U); q.primitiveKind.expect(f.kind.U); q.varyingSlots.expect(f.slots.U); q.flatMask.expect(f.flat.U)
    }
    def checkQuad(f: Fixture): Unit = {
      val e=expected(f,varyings); assert(e.status==0); val q=d.io.out.bits
      checkIdentity(f,q)
      for (lane <- 0 until 4) {
        val out=q.lanes(lane)
        out.x.expect((f.id*2+(lane&1)).S); out.y.expect((lane>>1).S)
        out.covered.expect(((f.covered&(1<<lane)) != 0).B); out.coverageMask.expect((if ((f.covered&(1<<lane)) != 0) 7 else 0).U)
        out.perspectiveValid.expect(((f.perspective&(1<<lane)) != 0).B)
        out.sampleLocationValid.expect(((f.fallback&(1<<lane)) != 0).B); out.sampleLocation.expect(1.U)
        out.reciprocalW.negative.expect(false.B); out.reciprocalW.special.expect(0.U)
        out.reciprocalW.exponent.expect((-2).S); out.reciprocalW.significand.expect((0x800000+lane).U)
        out.fragCoordW.expect(fp(0.25+lane/64.0).U); out.windowDepth.expect(fp(0.5).U)
        out.sampleDepthValid.expect(true.B)
        for (sample <- 0 until g.maxSampleCount) out.sampleDepth(sample).expect(fp(0.25+sample/16.0).U)
        for (axis <- 0 until 2) out.pointCoord(axis).expect(fp((axis+lane)/16.0).U)
        for (row <- 0 until varyings*4) withClue(s"quad=${f.id} lane=$lane row=$row: ") {
          out.varyings(row/4)(row%4).expect(e.words(lane)(row).U)
        }
      }
    }
    def checkCompletion(f: Fixture, overrideStatus: Option[Int] = None, output: Boolean = true,
        metrics: Boolean = true): Unit = {
      val e=expected(f,varyings); val r=d.io.completion.bits
      r.identity.context.expect((f.id+10).U); r.identity.pass.expect((f.id+20).U); r.identity.generation.expect((f.id+30).U)
      r.sequence.expect((1000+f.id).U); r.primitiveId.expect((200+f.id).U); r.instanceId.expect((300+f.id).U)
      r.partIndex.expect((f.id%7).U); r.status.expect(overrideStatus.getOrElse(e.status).U); r.output.expect(output.B)
      val invalid=(~f.covered & ~f.perspective)&15
      r.invalidHelpers.expect(invalid.U)
      if (overrideStatus.isEmpty) r.faultLane.expect(e.fault.U)
      if (metrics) {
        r.arithmeticOperations.expect(e.operations.U); r.arithmeticStatus.expect(e.arithmetic.U); r.packingStatus.expect(e.packing.U)
      }
    }
    def run(fixtures: Seq[Fixture], randomStalls: Boolean = false): Vector[Int] = {
      val random=new Random(931); var sent=0; var output=0; var completed=0; var loops=0
      var heldQuad: Option[BigInt]=None; var heldCompletion: Option[BigInt]=None
      val cycles=mutable.ArrayBuffer.empty[Int]
      while (completed<fixtures.size && loops<fixtures.size*200+500) {
        val offer=sent<fixtures.size && (!randomStalls || random.nextInt(4)!=0)
        d.io.in.valid.poke(offer.B); if (offer) input(fixtures(sent))
        d.io.out.ready.poke((!randomStalls || random.nextInt(3)!=0).B)
        d.io.completion.ready.poke((!randomStalls || cycle%19<12).B)
        val qValid=d.io.out.valid.peek().litToBoolean; val cValid=d.io.completion.valid.peek().litToBoolean
        heldQuad.foreach(bits=>{assert(qValid);assert(d.io.out.bits.peek().litValue==bits)})
        heldCompletion.foreach(bits=>{assert(cValid);assert(d.io.completion.bits.peek().litValue==bits)})
        heldQuad=if(qValid && !d.io.out.ready.peek().litToBoolean)Some(d.io.out.bits.peek().litValue)else None
        heldCompletion=if(cValid && !d.io.completion.ready.peek().litToBoolean)Some(d.io.completion.bits.peek().litValue)else None
        if (qValid && d.io.out.ready.peek().litToBoolean) {
          assert(output==completed); checkQuad(fixtures(completed)); output+=1; cycles+=cycle
        }
        if (cValid && d.io.completion.ready.peek().litToBoolean) {
          val success=expected(fixtures(completed),varyings).status==0
          checkCompletion(fixtures(completed),output=success)
          if (!success) assert(output==completed) else assert(output==completed+1)
          completed+=1; output=completed
        }
        if (offer && d.io.in.ready.peek().litToBoolean) { sent+=1; assert(sent-completed<=contexts) }
        tick(); loops+=1
      }
      assert(sent==fixtures.size && completed==fixtures.size)
      d.io.in.valid.poke(false.B); d.io.out.valid.expect(false.B); d.io.completion.valid.expect(false.B); d.io.busy.expect(false.B)
      cycles.toVector
    }
  }

  behavior of "Fp32PreparedQuadInterpolator"
  it should "own four full-width quads and sustain a completed quad every sixty-four issue cycles" in {
    simulate(new Fp32PreparedQuadInterpolator(g=g)) { d =>
      val t=new Driver(d); val times=t.run((0 until 16).map(i=>Fixture(i)))
      assert(times.size==16 && times.head<150)
      assert(times.sliding(2).forall(pair=>pair(1)-pair(0)==64),s"quad completion intervals: ${times.sliding(2).map(p=>p(1)-p(0)).toVector}")
      info("PREPARED_QUADS contexts=4 quads=16 components=4096 steadyQuadInterval=64 exactRationalRNE=true orderedRetirement=true")
    }
  }
  it should "preserve raw flat values centroid fallback helpers point data and unused rows under stalls" in {
    simulate(new Fp32PreparedQuadInterpolator(g=g)) { d =>
      val t=new Driver(d)
      val raw=Vector.tabulate(3,64)((v,row)=>if(row==60)BigInt("7fc12000",16)+v else fp((row-19+v*11)/64.0))
      val mixed=Fixture(1,flat=(BigInt(1)<<5)|(BigInt(1)<<60),centroid=(BigInt(1)<<5)|(BigInt(1)<<60)|(BigInt(1)<<63),
        centroidActive=7,perspective=7,covered=7,fallback=2,values=raw)
      val center=expected(mixed.copy(centroidActive=0,fallback=0),16)
      val selected=expected(mixed,16)
      assert(center.words(0)(63)!=selected.words(0)(63)); assert(selected.words(3)(60)==raw(0)(60)); assert(selected.words(3)(63)==0)
      val line=Fixture(2,slots=3,kind=1,values=Vector.tabulate(3,64)((v,r)=>if(v==2)BigInt("7fc00001",16)else fp((r+v)/32.0)))
      val point=Fixture(3,kind=0,values=raw)
      t.run(Seq(mixed,line,point,Fixture(4,slots=0),Fixture(5,perspective=0,covered=0)),randomStalls=true)
    }
  }
  it should "continue arithmetic for later contexts while the first quad and completion are held" in {
    simulate(new Fp32PreparedQuadInterpolator(g=g)) { d =>
      val t=new Driver(d); val fixtures=(0 until 4).map(i=>Fixture(i+10))
      fixtures.foreach(t.offer); d.io.in.ready.expect(false.B)
      t.until(d.io.out.valid.peek().litToBoolean); t.checkQuad(fixtures.head)
      val held=d.io.out.bits.peek().litValue
      for (_ <- 0 until 300) { t.tick(); assert(d.io.out.bits.peek().litValue==held); d.io.in.ready.expect(false.B) }
      val times=mutable.ArrayBuffer.empty[Int]
      for (f <- fixtures) {
        t.until(d.io.out.valid.peek().litToBoolean); times+=t.cycle; t.checkQuad(f)
        d.io.out.ready.poke(true.B); t.tick(); d.io.out.ready.poke(false.B)
        t.until(d.io.completion.valid.peek().litToBoolean); t.checkCompletion(f)
        val completion=d.io.completion.bits.peek().litValue
        for (_ <- 0 until 3) { t.tick(); assert(d.io.completion.bits.peek().litValue==completion) }
        d.io.completion.ready.poke(true.B); t.tick(); d.io.completion.ready.poke(false.B)
      }
      assert(times.sliding(2).forall(p=>p(1)-p(0)<=7),s"later quads did not finish behind held head: $times")
      d.io.busy.expect(false.B)
    }
  }
  it should "retain lane-major first-error accounting and preparation failures before healthy reuse" in {
    simulate(new Fp32PreparedQuadInterpolator(g=g)) { d =>
      val t=new Driver(d)
      val weights=Vector.tabulate(4)(lane=>Vector(if(lane==0)V(E(exponent=400,sig=0x800000))else if(lane==1)V(E(exponent=512,sig=0x800000))else one,one,one))
      val values=Vector.tabulate(3,64)((v,r)=>if(v==0 && r==63)BigInt("7f7fffff",16)else fp((r+v+1)/64.0))
      val fault=Fixture(20,weights=weights,values=values,denominators=Vector.fill(4)(one))
      assert(expected(fault,16).fault==0)
      t.run(Seq(fault,Fixture(21,status=7,faultLane=2),Fixture(22,slots=1,flat=BigInt(1)<<63),
        Fixture(25,slots=1,flat=BigInt(1)<<63,status=7,faultLane=2),Fixture(23),Fixture(24)),randomStalls=true)
    }
  }
  it should "drain cancellation before output and preserve offered quad and completion bits" in {
    simulate(new Fp32PreparedQuadInterpolator(g=g)) { d =>
      val t=new Driver(d); val fixtures=(0 until 4).map(i=>Fixture(i+30)); fixtures.foreach(t.offer)
      for (_ <- 0 until 12) t.tick()
      d.io.cancel.poke(true.B); d.io.completion.ready.poke(true.B)
      var completed=0; var n=0
      while(d.io.busy.peek().litToBoolean && n<500) {
        d.io.in.ready.expect(false.B); d.io.out.valid.expect(false.B)
        if(d.io.completion.valid.peek().litToBoolean){t.checkCompletion(fixtures(completed),Some(4),output=false,metrics=false);completed+=1}
        t.tick();n+=1
      }
      assert(completed==4); d.io.completion.ready.poke(false.B); t.tick(); d.io.cancel.poke(false.B)
      val late=Fixture(40); t.offer(late); t.until(d.io.out.valid.peek().litToBoolean)
      val quad=d.io.out.bits.peek().litValue; d.io.cancel.poke(true.B)
      for (_ <- 0 until 7){t.tick();assert(d.io.out.bits.peek().litValue==quad)}
      d.io.out.ready.poke(true.B); t.tick(); d.io.out.ready.poke(false.B)
      t.checkCompletion(late,Some(4)); val completion=d.io.completion.bits.peek().litValue
      for (_ <- 0 until 5){t.tick();assert(d.io.completion.bits.peek().litValue==completion)}
      d.io.completion.ready.poke(true.B); t.tick(); d.io.completion.ready.poke(false.B); t.tick(); d.io.cancel.poke(false.B)
      val offered=Fixture(41); t.offer(offered); t.until(d.io.out.valid.peek().litToBoolean)
      d.io.out.ready.poke(true.B); t.tick(); d.io.out.ready.poke(false.B); t.checkCompletion(offered)
      val held=d.io.completion.bits.peek().litValue; d.io.cancel.poke(true.B)
      for (_ <- 0 until 5){t.tick();assert(d.io.completion.bits.peek().litValue==held)}
      d.io.completion.ready.poke(true.B); t.tick(); d.io.completion.ready.poke(false.B); t.tick(); d.io.cancel.poke(false.B)
      t.run(Seq(Fixture(42),Fixture(43)))
    }
  }
  it should "clear owned and posted work on reset without ghost outputs" in {
    simulate(new Fp32PreparedQuadInterpolator(g=g)) { d =>
      val t=new Driver(d); t.offer(Fixture(50)); t.offer(Fixture(51)); for(_<-0 until 30)t.tick()
      d.reset.poke(true.B); t.tick(); d.reset.poke(false.B)
      for(_<-0 until 100){t.tick();d.io.out.valid.expect(false.B);d.io.completion.valid.expect(false.B);d.io.busy.expect(false.B)}
      t.run(Seq(Fixture(52),Fixture(53)))
    }
  }
  it should "retain an earlier arithmetic fault and finish lane-major attribution during cancellation" in {
    simulate(new Fp32PreparedQuadInterpolator(g=g)) { d =>
      val t=new Driver(d)
      val weights=Vector.tabulate(4)(lane=>Vector(if(lane==0)V(E(exponent=400,sig=0x800000))else if(lane==1)V(E(exponent=512,sig=0x800000))else one,one,one))
      val values=Vector.tabulate(3,64)((v,r)=>if(v==0 && r==63)BigInt("7f7fffff",16)else fp((r+v+1)/64.0))
      val f=Fixture(70,weights=weights,values=values,denominators=Vector.fill(4)(one))
      t.offer(f)
      // Lane1/row0 has returned; lane0/row63 is still in the 65-cycle bank.
      // A known error wins cancellation, and attribution still finishes.
      for(_<-0 until 90)t.tick()
      d.io.cancel.poke(true.B); t.until(d.io.completion.valid.peek().litToBoolean)
      d.io.out.valid.expect(false.B); t.checkCompletion(f,output=false)
      d.io.completion.ready.poke(true.B); t.tick(); d.io.completion.ready.poke(false.B); t.tick(); d.io.cancel.poke(false.B)
      t.run(Seq(Fixture(71)))
    }
  }
  it should "drain parent-reserved preparation transfers while cancellation is held high" in {
    simulate(new Fp32PreparedQuadInterpolator(g=g,reservedInputs=true)) { d =>
      val t=new Driver(d); val first=Fixture(80); t.offer(first)
      for(_<-0 until 20)t.tick()
      d.io.cancel.poke(true.B)
      val fixtures=Seq(first,Fixture(81),Fixture(82,slots=1,flat=BigInt(1)<<63,status=7,faultLane=2),Fixture(83))
      fixtures.tail.foreach(t.offer); d.io.in.ready.expect(false.B)
      d.io.completion.ready.poke(true.B)
      var completed=0; var n=0
      while(d.io.busy.peek().litToBoolean && n<500) {
        d.io.out.valid.expect(false.B)
        if(d.io.completion.valid.peek().litToBoolean){
          if(completed==2)t.checkCompletion(fixtures(completed),output=false)
          else t.checkCompletion(fixtures(completed),Some(4),output=false,metrics=false)
          completed+=1
        }
        t.tick();n+=1
      }
      assert(completed==4); d.io.completion.ready.poke(false.B); t.tick(); d.io.cancel.poke(false.B)
      t.run(Seq(Fixture(84),Fixture(85)))
    }
  }
  it should "wrap two contexts with a non-power-of-two varying capacity" in {
    simulate(new Fp32PreparedQuadInterpolator(VertexShaderStageConfig(varyings=3),g,contexts=2)) { d =>
      val t=new Driver(d,3,2); t.run((0 until 8).map(i=>Fixture(i+60,slots=3)),randomStalls=true)
    }
  }
}
