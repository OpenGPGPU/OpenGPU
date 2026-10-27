package opengpu.graphics

import chisel3._
import chisel3.util.HasBlackBoxInline
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable
import java.nio.file.{Files,Paths}

private class BankedSampleSramResources extends BlackBox with HasBlackBoxInline {
  val io=IO(new Bundle {val present=Output(Bool())})
  private val vendor=Files.readString(Paths.get(
    "depends/asap7_sram_0p0/generated/verilog/srambank_64x4x32_6t122.v"))
  setInline("BankedSampleSramResources.sv",
    "module BankedSampleSramResources(output present); assign present=1'b1; endmodule\n"+vendor)
}
private class MacroBankedTileSamples(legacy: Boolean=false) extends BankedTileSampleSram(true,legacyPort=legacy) {
  val macroModelPresent=IO(Output(Bool()))
  private val resources=Module(new BankedSampleSramResources)
  macroModelPresent:=resources.io.present
}
private class TileSampleBankArbiterHarness(ports: Int) extends Module {
  val io=IO(new Bundle {
    val mask=Input(UInt(ports.W));val consume=Input(Bool());val equivalent=Output(Bool())
  })
  private val reference=Module(new chisel3.util.RRArbiter(new BankedTileSampleOperation,ports))
  private val candidate=Module(new TileSampleBankArbiter(ports))
  reference.io.out.ready:=io.consume;candidate.io.out.ready:=io.consume
  for(p <- 0 until ports) {
    reference.io.in(p).valid:=io.mask(p);candidate.io.in(p).valid:=io.mask(p)
    reference.io.in(p).bits:=0.U.asTypeOf(reference.io.in(p).bits)
    reference.io.in(p).bits.row:=p.U;reference.io.in(p).bits.color:=(0x12340000L+p).U
    candidate.io.in(p).bits:=reference.io.in(p).bits
  }
  io.equivalent:=reference.io.out.valid===candidate.io.out.valid &&
    reference.io.chosen===candidate.io.chosen &&
    VecInit((0 until ports).map(p=>reference.io.in(p).ready===candidate.io.in(p).ready)).asUInt.andR &&
    (!reference.io.out.valid || reference.io.out.bits.asUInt===candidate.io.out.bits.asUInt)
}

class BankedTileSampleSramSpec extends AnyFlatSpec {
  for(ports <- Seq(2,4,5))it should s"match round robin valid, choice, ready and data for every request mask at ports=$ports" in {
    simulate(new TileSampleBankArbiterHarness(ports)) { d=>
      d.io.mask.poke(0.U);d.io.consume.poke(false.B)
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      for(previous <- 0 until ports) {
        d.io.mask.poke((1<<previous).U);d.io.consume.poke(true.B)
        d.clock.step() // Seed both uninitialized round-robin histories before comparison.
        for(mask <- 0 until(1<<ports);consume <- Seq(false,true)) {
          d.io.mask.poke(mask.U);d.io.consume.poke(consume.B);d.io.equivalent.expect(true.B)
        }
      }
    }
  }
  private def model(macros: Boolean,legacy: Boolean=false): BankedTileSampleSram=
    if(macros)new MacroBankedTileSamples(legacy) else new BankedTileSampleSram(false,legacyPort=legacy)
  private case class Read(index: Int,ticket: Int)
  private case class Write(index: Int,color: Long,depth: Long,colorEnable: Boolean=true,depthEnable: Boolean=true)
  private case class Reply(index: Int,ticket: Int,color: Long,depth: Long,due: Int)
  private case class Fires(read: Vector[Boolean],write: Vector[Boolean],response: Int)
  private class Driver(val dut: BankedTileSampleSram) {
    val memory=mutable.Map.empty[Int,(Long,Long)]
    val expected=Array.fill(4)(mutable.Queue.empty[Reply])
    val readInput=Array.fill[Option[Read]](4)(None)
    val writeInput=Array.fill[Option[Write]](4)(None)
    val holdReply=Array.fill(4)(false)
    private val held=Array.fill[Option[BigInt]](4)(None)
    var randomBackpressure=true
    var cycle=0;var mode=0
    var readCount=0;var writeCount=0;var replyCount=0
    dut.io.begin.poke(false.B);dut.io.sampleMode.poke(0.U)
    dut.io.legacy.foreach { p=>
      p.exclusive.poke(false.B);p.readEnable.poke(false.B);p.readIndex.poke(0.U)
      p.writeEnable.poke(false.B);p.writeIndex.poke(0.U);p.colorEnable.poke(false.B)
      p.depthStencilEnable.poke(false.B);p.color.poke(0.U);p.depthStencil.poke(0.U)
    }
    for(p <- 0 until 4) {
      dut.io.read(p).valid.poke(false.B);dut.io.read(p).bits.poke(0.U.asTypeOf(dut.io.read(p).bits))
      dut.io.write(p).valid.poke(false.B);dut.io.write(p).bits.poke(0.U.asTypeOf(dut.io.write(p).bits))
      dut.io.response(p).ready.poke(false.B)
    }
    dut.reset.poke(true.B);dut.clock.step();dut.reset.poke(false.B)
    def step(): Fires = {
      for(p <- 0 until 4) {
        dut.io.read(p).valid.poke(readInput(p).nonEmpty.B)
        readInput(p).foreach { r=>
          assert(r.index>=0 && r.index<256*(1<<mode))
          dut.io.read(p).bits.index.poke(r.index.U);dut.io.read(p).bits.ticket.poke(r.ticket.U)
        }
        dut.io.write(p).valid.poke(writeInput(p).nonEmpty.B)
        writeInput(p).foreach { w=>
          assert(w.index>=0 && w.index<256*(1<<mode))
          val b=dut.io.write(p).bits
          b.index.poke(w.index.U);b.color.poke(w.color.U);b.depthStencil.poke(w.depth.U)
          b.colorEnable.poke(w.colorEnable.B);b.depthStencilEnable.poke(w.depthEnable.B)
        }
        dut.io.response(p).ready.poke((!holdReply(p) && (!randomBackpressure || (cycle+p)%7!=3)).B)
      }
      val reads=Vector.tabulate(4)(p=>dut.io.read(p).valid.peek().litToBoolean && dut.io.read(p).ready.peek().litToBoolean)
      val writes=Vector.tabulate(4)(p=>dut.io.write(p).valid.peek().litToBoolean && dut.io.write(p).ready.peek().litToBoolean)
      var returned=0
      for(p <- 0 until 4) {
        val out=dut.io.response(p)
        held(p).foreach { old=>out.valid.expect(true.B);assert(out.bits.peek().litValue==old,s"held reply changed port=$p cycle=$cycle") }
        if(out.valid.peek().litToBoolean) {
          assert(expected(p).nonEmpty,s"unowned reply port=$p cycle=$cycle")
          val e=expected(p).front
          assert(cycle>=e.due,"SRAM reply bypassed its synchronous result/FIFO latency")
          out.bits.index.expect(e.index.U);out.bits.ticket.expect(e.ticket.U)
          out.bits.color.expect(e.color.U);out.bits.depthStencil.expect(e.depth.U)
          if(out.ready.peek().litToBoolean) {expected(p).dequeue();returned+=1;held(p)=None}
          else held(p)=Some(out.bits.peek().litValue)
        }else assert(held(p).isEmpty)
      }
      // Expected values use logical sample addresses, independent of the RTL's
      // bank/row calculation. Same-bank reads/writes cannot both fire.
      for(p <- 0 until 4 if reads(p)) {
        val r=readInput(p).get;val value=memory(r.index)
        expected(p).enqueue(Reply(r.index,r.ticket,value._1,value._2,cycle+2))
      }
      for(p <- 0 until 4 if writes(p)) {
        val w=writeInput(p).get;val old=memory.getOrElse(w.index,(0L,0L))
        memory(w.index)=(if(w.colorEnable)w.color else old._1,if(w.depthEnable)w.depth else old._2)
      }
      dut.io.readIssues.expect(reads.count(identity).U);dut.io.writeIssues.expect(writes.count(identity).U)
      readCount+=reads.count(identity);writeCount+=writes.count(identity);replyCount+=returned
      dut.clock.step();cycle+=1
      dut.io.drained.expect(expected.forall(_.isEmpty).B)
      Fires(reads,writes,returned)
    }
    def begin(sampleMode: Int): Unit = {
      assert(expected.forall(_.isEmpty));dut.io.drained.expect(true.B)
      readInput.indices.foreach(p=>readInput(p)=None);writeInput.indices.foreach(p=>writeInput(p)=None)
      mode=sampleMode;memory.clear()
      dut.io.sampleMode.poke(sampleMode.U);dut.io.begin.poke(true.B);step();dut.io.begin.poke(false.B)
    }
    def submit(reads: Seq[Seq[Read]],writes: Seq[Seq[Write]]): Unit = {
      require(reads.size==4 && writes.size==4)
      val rc=Array.fill(4)(0);val wc=Array.fill(4)(0);val start=cycle
      while(((0 until 4).exists(p=>rc(p)<reads(p).size || wc(p)<writes(p).size)) && cycle-start<30000) {
        for(p <- 0 until 4) {readInput(p)=reads(p).lift(rc(p));writeInput(p)=writes(p).lift(wc(p))}
        val fire=step()
        for(p <- 0 until 4) {if(fire.read(p))rc(p)+=1;if(fire.write(p))wc(p)+=1}
      }
      assert((0 until 4).forall(p=>rc(p)==reads(p).size && wc(p)==writes(p).size),"bank service failed to progress")
      for(p <- 0 until 4){readInput(p)=None;writeInput(p)=None}
    }
    def drain(): Unit = {
      for(p <- 0 until 4){readInput(p)=None;writeInput(p)=None}
      val start=cycle
      while((expected.exists(_.nonEmpty) || !dut.io.drained.peek().litToBoolean) && cycle-start<30000)step()
      assert(expected.forall(_.isEmpty) && dut.io.drained.peek().litToBoolean)
      assert(readCount==replyCount)
    }
    def seed(): Unit = {
      val count=256*(1<<mode)
      val writes=Vector.tabulate(4)(p=>(p until count by 4).map(i=>
        Write(i,(0x51500000L^(i*0x010203L)^mode)&0xffffffffL,0xa5000000L|((i*8191L+mode)&0xffffffL))))
      submit(Vector.fill(4)(Vector.empty[Read]),writes)
      drain()
    }
    def verifyAll(): Unit = {
      val reads=Vector.tabulate(4)(p=>(p until 256*(1<<mode) by 4).map(i=>Read(i,(i+p*67)&255)))
      submit(reads,Vector.fill(4)(Vector.empty[Write]));drain()
    }
  }

  for(macros <- Seq(false,true))it should s"preserve every logical sample, plane mask and reply owner across all sample modes with macros=$macros" in {
    simulate(model(macros)) { dut=>
      val d=new Driver(dut);val random=new scala.util.Random(0x5124)
      for(mode <- 0 to 2) {
        d.begin(mode);d.seed();d.verifyAll()
        // Active requests use the admitted snapshot, not a changing input CSR.
        dut.io.sampleMode.poke(((mode+1)%3).U)
        val reads=Vector.tabulate(4)(p=>Vector.tabulate(80)(n=>Read(random.nextInt(256*(1<<mode)),(p*67+n)&255)))
        val writes=Vector.tabulate(4)(p=>Vector.tabulate(80)(n=>Write(random.nextInt(256*(1<<mode)),
          random.nextLong()&0xffffffffL,random.nextLong()&0xffffffffL,n%3!=0,n%4!=0)))
        d.submit(reads,writes);d.drain();d.verifyAll()
      }
    }
  }

  for(macros <- Seq(false,true))it should s"capture a blocked SRAM result while another port overwrites and reads its bank with macros=$macros" in {
    simulate(model(macros)) { dut=>
      val d=new Driver(dut);d.begin(0);d.seed();d.holdReply(0)=true
      d.submit(Vector(Vector(Read(0,1),Read(4,2),Read(64,3)),Vector.empty,Vector.empty,Vector.empty),
        Vector.fill(4)(Vector.empty[Write]))
      val old=d.memory(64)
      val writes=Vector(Vector.empty[Write],Vector.tabulate(24)(n=>Write(64,0x60000000L+n,0x70000000L+n)),Vector.empty,Vector.empty)
      val reads=Vector(Vector.empty[Read],Vector.tabulate(24)(n=>Read(if(n%2==0)64 else 68,50+n)),Vector.empty,Vector.empty)
      d.submit(reads,writes)
      assert(d.memory(64)!=old && d.expected(0).size==3)
      for(_ <- 0 until 20){d.step();dut.io.read(0).ready.expect(false.B);dut.io.drained.expect(false.B)}
      d.holdReply(0)=false;d.drain();d.verifyAll()
    }
  }

  it should "serve four reads and four writes per cycle on disjoint banks without calling that complete RMW throughput" in {
    simulate(model(true)) { dut=>
      val d=new Driver(dut);d.randomBackpressure=false;d.begin(0);d.seed()
      val ra=Vector(0,1,16,17);val wa=Vector(2,3,18,19)
      val before=d.readCount
      for(n <- 0 until 64) {
        for(p <- 0 until 4) {
          d.readInput(p)=Some(Read(ra(p),(n+p*67)&255))
          d.writeInput(p)=Some(Write(wa(p),0x80000000L+n*4+p,0x90000000L+n*4+p))
        }
        val fire=d.step();assert(fire.read.forall(identity) && fire.write.forall(identity))
        if(n>=2)assert(fire.response==4)
      }
      d.drain();assert(d.readCount-before==256);d.verifyAll()
      info("BANKED_SAMPLE_SERVICE rawReads=4/cycle rawWrites=4/cycle streamCycles=64; complete pixel RMW integration remains required")
    }
  }

  it should "serialize conflicting bank requests fairly while preserving tagged results" in {
    simulate(model(false)) { dut=>
      val d=new Driver(dut);d.begin(0);d.seed()
      val addresses=Vector(0,4,64,68);val accepted=Array.fill(4)(false)
      val start=d.cycle
      while(!accepted.forall(identity) && d.cycle-start<8) {
        for(p <- 0 until 4)d.readInput(p)=if(accepted(p))None else Some(Read(addresses(p),100+p))
        val fire=d.step();assert(fire.read.count(identity)<=1)
        for(p <- 0 until 4)accepted(p)=accepted(p)||fire.read(p)
      }
      assert(accepted.forall(identity));d.drain()
    }
  }
  it should "serve every continuously eligible client within ten cycles with four reads and five writes" in {
    simulate(new BankedTileSampleSram(writePorts=5)) { d=>
      d.io.begin.poke(false.B);d.io.sampleMode.poke(0.U)
      for(p <- 0 until 4) {
        d.io.read(p).valid.poke(false.B);d.io.read(p).bits.poke(0.U.asTypeOf(d.io.read(p).bits))
        d.io.response(p).ready.poke(true.B)
      }
      for(p <- 0 until 5) {
        d.io.write(p).valid.poke(false.B);d.io.write(p).bits.poke(0.U.asTypeOf(d.io.write(p).bits))
      }
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      d.io.begin.poke(true.B);d.clock.step();d.io.begin.poke(false.B)
      d.io.write(0).valid.poke(true.B);d.io.write(0).bits.colorEnable.poke(true.B)
      d.io.write(0).bits.depthStencilEnable.poke(true.B)
      d.io.write(0).bits.color.poke(0x12345678L.U);d.io.write(0).bits.depthStencil.poke(0xabcdefL.U)
      d.io.write(0).ready.expect(true.B);d.clock.step()
      var color=0x12345678L;var depth=0xabcdefL
      val expected=Array.fill(4)(mutable.Queue.empty[(Long,Long)])
      val last=Array.fill(9)(-1);val counts=Array.fill(9)(0)
      def replies(): Unit=for(p <- 0 until 4 if d.io.response(p).valid.peek().litToBoolean) {
        assert(expected(p).nonEmpty)
        val(c,z)=expected(p).dequeue()
        d.io.response(p).bits.ticket.expect((0x40+p).U)
        d.io.response(p).bits.index.expect(0.U)
        d.io.response(p).bits.color.expect(c.U);d.io.response(p).bits.depthStencil.expect(z.U)
      }
      for(p <- 0 until 4) {
        d.io.read(p).valid.poke(true.B);d.io.read(p).bits.ticket.poke((0x40+p).U)
      }
      for(p <- 0 until 5) {
        val w=d.io.write(p);w.valid.poke(true.B);w.bits.colorEnable.poke(true.B);w.bits.depthStencilEnable.poke(true.B)
        w.bits.color.poke((0x80000000L+p).U);w.bits.depthStencil.poke((0x90000000L+p).U)
      }
      for(cycle <- 0 until 100) {
        replies()
        val reads=(0 until 4).filter(p=>d.io.read(p).ready.peek().litToBoolean)
        val writes=(0 until 5).filter(p=>d.io.write(p).ready.peek().litToBoolean)
        assert(reads.size+writes.size==1,"one bank must commit exactly one offered operation")
        for(p <- reads){expected(p).enqueue((color,depth));assert(cycle-last(p)<=10);last(p)=cycle;counts(p)+=1}
        for(p <- writes){color=0x80000000L+p;depth=0x90000000L+p;assert(cycle-last(p+4)<=10);last(p+4)=cycle;counts(p+4)+=1}
        if(cycle>=9)assert(last.forall(_>=0),"a continuously eligible bank client starved")
        d.clock.step()
      }
      for(p <- 0 until 4)d.io.read(p).valid.poke(false.B)
      for(p <- 0 until 5)d.io.write(p).valid.poke(false.B)
      for(_ <- 0 until 4){replies();d.clock.step()}
      assert(expected.forall(_.isEmpty) && counts.forall(_>=9));d.io.drained.expect(true.B)
    }
  }
  for(macros <- Seq(false,true))it should s"hand exclusive attachment ownership back to tagged service without address-dependent arbitration macros=$macros" in {
    simulate(model(macros,legacy=true)) { dut=>
      val d=new Driver(dut);d.begin(2);d.seed();d.drain()
      val p=dut.io.legacy.get;p.exclusive.poke(true.B)
      val indices=Vector(0,1,4,64,1023)
      for((index,n) <- indices.zipWithIndex) {
        for(port <- 0 until 4) {
          dut.io.read(port).bits.index.poke(((index+port*37)&1023).U)
          dut.io.write(port).bits.index.poke(((1023-index+port*53)&1023).U)
          dut.io.read(port).ready.expect(false.B);dut.io.write(port).ready.expect(false.B)
        }
        val c=0x12340000L+n;val z=0x87650000L+n
        p.writeEnable.poke(true.B);p.writeIndex.poke(index.U)
        p.colorEnable.poke(true.B);p.depthStencilEnable.poke(true.B)
        p.color.poke(c.U);p.depthStencil.poke(z.U);d.step();d.memory(index)=(c,z)
        p.writeEnable.poke(false.B)
        p.readEnable.poke(true.B);p.readIndex.poke(index.U);d.step()
        p.colorData.expect(c.U);p.depthStencilData.expect(z.U)
        p.readEnable.poke(false.B)
      }
      p.exclusive.poke(false.B)
      d.submit(Vector.tabulate(4)(port=>indices.map(i=>Read(i,port*32+i%32))),Vector.fill(4)(Vector.empty[Write]))
      d.drain();d.verifyAll()
      p.exclusive.poke(true.B);d.step();dut.io.drained.expect(true.B)
    }
  }
}
