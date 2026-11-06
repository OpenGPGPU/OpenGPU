package opengpu.graphics

import chisel3._
import circt.stage.ChiselStage
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class FragmentOutputReaderSpec extends AnyFlatSpec {
  private case class Request(id: BigInt,address: BigInt,mask: BigInt,order: Int)
  private case class Reply(request: Request,data: BigInt,due: Int,fault: Boolean)
  private case class Write(field: Int,row: Int,data: BigInt)
  private def byte(address: BigInt): Int=((address*73+(address>>7)*19+(address>>3))&255).toInt
  private def publishedRows(lanes: Int): Int=lanes/4
  private def digest(values: Seq[String]): String=java.security.MessageDigest.getInstance("SHA-256")
    .digest(values.mkString("\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII))
    .map(b=>f"${b&255}%02x").mkString
  private class Driver(val d: FragmentOutputReader,val stride: Int) {
    var cycle=0;var peak=0;var sameBeat=0
    var ready: Int=>Boolean=c=>c%7!=2
    var latency: Request=>Int=r=>if(r.order%2==0)13 else 2
    var fault: Request=>Boolean=_=>false
    val requests=mutable.ArrayBuffer.empty[Request]
    val responses=mutable.ArrayBuffer.empty[Int]
    val writes=mutable.ArrayBuffer.empty[Write]
    private val pending=mutable.ArrayBuffer.empty[Reply]
    private val owned=mutable.Set.empty[BigInt]
    private var response: Option[Reply]=None
    private var heldRequest: Option[BigInt]=None
    d.io.start.valid.poke(false.B);d.io.start.bits.base.poke(0.U)
    d.io.start.bits.lanes.poke(0.U);d.io.start.bits.fields.poke(0.U)
    d.io.request.ready.poke(false.B);d.io.response.valid.poke(false.B)
    d.io.response.bits.transactionId.poke(0.U);d.io.response.bits.readData.poke(0.U)
    d.io.response.bits.fault.poke(false.B);d.io.done.ready.poke(false.B);d.io.cancel.poke(false.B)
    d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
    def tick(): Unit={
      val accepting=ready(cycle);d.io.request.ready.poke(accepting.B)
      val offered=d.io.request.valid.peek().litToBoolean
      val bits=d.io.request.bits.peek().litValue
      heldRequest.foreach(old=>assert(offered && bits==old,"readback withdrew or changed a held request"))
      heldRequest=if(offered && !accepting)Some(bits)else None
      val accepted=if(offered && accepting){
        val q=d.io.request.bits;q.isWrite.expect(false.B);q.sizeLog2.expect(6.U)
        val r=Request(q.transactionId.peek().litValue,q.address.peek().litValue,q.byteMask.peek().litValue,requests.size)
        assert((r.address&63)==0 && owned.add(r.id),"readback reused a live ID or an unaligned line")
        requests+=r;Some(r)
      }else None
      if(response.isEmpty){
        val due=pending.indices.filter(i=>pending(i).due<=cycle)
        if(due.nonEmpty)response=Some(pending.remove(due.last))
      }
      accepted.foreach{r=>
        val delay=latency(r);assert(delay>=0)
        val data=(0 until 64).foldLeft(BigInt(0))((v,b)=>v|(BigInt(byte(r.address+b))<<(8*b)))
        val reply=Reply(r,data,cycle+delay,fault(r))
        if(delay==0 && response.isEmpty)response=Some(reply)else pending+=reply
      }
      d.io.response.valid.poke(response.nonEmpty.B)
      response.foreach{r=>d.io.response.bits.transactionId.poke(r.request.id.U)
        d.io.response.bits.readData.poke(r.data.U);d.io.response.bits.fault.poke(r.fault.B)}
      response.foreach{r=>if(d.io.response.ready.peek().litToBoolean){
        assert(owned.remove(r.request.id),"readback response did not own an accepted request")
        if(accepted.exists(_.id==r.request.id))sameBeat+=1
        responses+=r.request.order;response=None
      }}
      if(d.io.write.valid.peek().litToBoolean){val w=d.io.write.bits
        assert(!d.io.cancel.peek().litToBoolean &&
          !(d.io.response.valid.peek().litToBoolean && d.io.response.bits.fault.peek().litToBoolean),
          "canceled or faulting readback committed cache data")
        writes+=Write(w.field.peek().litValue.toInt,w.row.peek().litValue.toInt,w.data.peek().litValue)
      }
      peak=math.max(peak,d.io.outstanding.peek().litValue.toInt)
      if(d.io.done.valid.peek().litToBoolean)
        assert(owned.isEmpty && pending.isEmpty && response.isEmpty && !offered,"readback completed before response drain")
      d.clock.step();cycle+=1
    }
    def until(condition: => Boolean): Unit={var left=3000
      while(!condition && left>0){tick();left-=1}
      assert(condition,s"readback stalled at $cycle requests=${requests.size} responses=${responses.size} writes=${writes.size}")
    }
    def start(base: BigInt,lanes: Int,fields: Int): Unit={
      assert(owned.isEmpty && pending.isEmpty && response.isEmpty && heldRequest.isEmpty)
      requests.clear();responses.clear();writes.clear();peak=0;sameBeat=0
      d.io.start.bits.base.poke(base.U);d.io.start.bits.lanes.poke(lanes.U);d.io.start.bits.fields.poke(fields.U)
      d.io.start.valid.poke(true.B);until(d.io.start.ready.peek().litToBoolean);tick();d.io.start.valid.poke(false.B)
    }
    def finish(failed: Boolean,hold: Boolean=false): Unit={
      until(d.io.done.valid.peek().litToBoolean);d.io.done.bits.expect(failed.B)
      if(hold)for(_<-0 until 7){tick();d.io.done.valid.expect(true.B);d.io.done.bits.expect(failed.B);d.io.busy.expect(true.B)}
      d.io.done.ready.poke(true.B);tick();d.io.done.ready.poke(false.B);d.io.busy.expect(false.B)
    }
    def check(base: BigInt,lanes: Int,fields: Int): Unit={
      until(d.io.done.valid.peek().litToBoolean);d.io.done.bits.expect(false.B)
      val rows=publishedRows(lanes)
      assert(writes.size==fields*rows)
      for((w,i)<-writes.zipWithIndex){
        assert(w.field==i/rows && w.row==i%rows,"readback changed field/row publication order")
        for(b<-0 until math.max(0,math.min(16,lanes*4-w.row*16))){
          val expected=byte(base+w.field*stride+w.row*16+b)
          assert(((w.data>>(8*b))&255)==expected,s"readback field=${w.field} row=${w.row} byte=$b differs")
        }
      }
      val expected=(0 until fields).flatMap{f=>
        val address=base+f*stride;val line=address& ~BigInt(63);val offset=(address&63).toInt
        val first=math.min(lanes*4,64-offset)
        Vector((line,((BigInt(1)<<first)-1)<<offset))++
          (if(first<lanes*4)Vector((line+64,(BigInt(1)<<(lanes*4-first))-1))else Vector.empty)
      }
      val actual=requests.map(r=>(r.address,r.mask)).toVector
      assert(actual.sorted==expected.sorted,s"readback requested incorrect lines or byte masks: $actual / $expected")
      assert(requests.map(_.mask.bitCount).sum==fields*lanes*4)
      finish(false,hold=true)
    }
  }

  it should "overlap both credits with ordered cache fills at the native 128-lane stride" in {
    simulate(new FragmentOutputReader(GpuConfig(lanes=16,warps=8),18)){d=>
      val t=new Driver(d,512);t.ready=_=>true;t.latency=_=>12
      val begin=t.cycle;t.start(0x100000,16,18);t.until(d.io.done.valid.peek().litToBoolean)
      val clocks=t.cycle-begin
      t.check(0x100000,16,18);assert(t.peak==2)
      assert(clocks<=180,s"two-credit native readback exceeded 180 clocks: $clocks")
      info(s"FRAGMENT_OUTPUT_READER fields=18 lanes=16 physicalCapacity=128 latency=12 clocks=$clocks requests=${t.requests.size} bytes=${t.requests.map(_.mask.bitCount).sum} peak=${t.peak}")
    }
  }

  it should "assemble reordered split lines and partial quad windows at a twelve-lane stride" in {
    simulate(new FragmentOutputReader(GpuConfig(lanes=4,warps=3),18)){d=>
      val t=new Driver(d,48)
      for(offset<-Seq(0,4,16,31,48,61);lanes<-Seq(4,8,12)){
        val base=BigInt(0x200000)+offset;t.start(base,lanes,18);t.check(base,lanes,18)
        assert(t.peak==2 && t.responses.toVector!=t.responses.sorted.toVector)
      }
      info("FRAGMENT_OUTPUT_READER splitMatrix=18 exactBytesAndMasks=true reorderedReplies=true orderedRows=true heldCompletion=true")
    }
  }

  it should "form exact masks at every byte offset for all legal quad window widths" in {
    simulate(new FragmentOutputReader(GpuConfig(lanes=16,warps=8),18)){d=>
      val t=new Driver(d,512);t.ready=_=>true;t.latency=_=>1
      for(offset<-0 until 64;lanes<-Seq(4,8,12,16)){
        val base=BigInt(0x280000)+offset;t.start(base,lanes,1);t.check(base,lanes,1)
      }
      info("FRAGMENT_OUTPUT_MASKS offsets=64 windowWidths=4 cases=256 exactByteIntervals=true splitLines=true reusedWithoutReset=true")
      t.latency=_=>2
      for(lanes<-Seq(4,8,12,16)){
        val base=BigInt(0x290000);val begin=t.cycle
        t.start(base,lanes,18);t.until(d.io.done.valid.peek().litToBoolean)
        val clocks=t.cycle-begin;t.check(base,lanes,18)
        assert(t.peak==2 && t.requests.size==18 && t.responses.size==18)
        val requests=t.requests.map(r=>s"${r.address.toString(16)}:${r.mask.toString(16)}").toVector
        val content=t.writes.filter(_.row<lanes/4).map(w=>s"${w.field}:${w.row}:${w.data.toString(16)}").toVector
        info(s"FRAGMENT_OUTPUT_ROWS fields=18 lanes=$lanes physicalCapacity=128 stride=512 latency=2 clocks=$clocks privateWrites=${t.writes.size} requests=${t.requests.size} responses=${t.responses.size} bytes=${t.requests.map(_.mask.bitCount).sum} peak=${t.peak} requestSha256=${digest(requests)} contentSha256=${digest(content)}")
      }
      t.latency=_=>0
      t.start(0x2a003d,12,3);t.check(0x2a003d,12,3)
      assert(t.sameBeat==6)
      t.fault=_=>true;t.start(0x2b003d,12,6);t.finish(true,hold=true)
      assert(t.requests.size==1 && t.responses.toVector==Vector(0) && t.sameBeat==1 && t.writes.isEmpty)
      t.fault=_=>false;t.start(0x2c003d,4,3);t.check(0x2c003d,4,3)
      t.ready=_=>false;t.start(0x2d003d,12,6);t.until(d.io.request.valid.peek().litToBoolean)
      d.io.cancel.poke(true.B)
      for(_<-0 until 5){t.tick();d.io.done.valid.expect(false.B)}
      t.ready=_=>true;t.tick();d.io.cancel.poke(false.B);t.finish(true,hold=true)
      assert(t.requests.size==1 && t.responses.toVector==Vector(0) && t.sameBeat==1 && t.writes.isEmpty)
      t.start(0x2e003d,8,3);t.check(0x2e003d,8,3)
      t.latency=_=>24;t.start(0x2f0000,16,6);t.until(t.requests.size==2)
      d.io.cancel.poke(true.B)
      for(_<-0 until 5){t.tick();d.io.done.valid.expect(false.B)}
      d.io.cancel.poke(false.B);t.finish(true,hold=true)
      assert(t.requests.size==2 && t.responses.size==2 && t.writes.isEmpty)
      t.latency=r=>if(r.order==0)25 else 2;t.fault=_.order==1
      t.start(0x30003d,12,6);t.finish(true,hold=true)
      assert(t.requests.size==2 && t.responses.toVector==Vector(1,0) && t.writes.isEmpty)
      t.fault=_=>false;t.start(0x31003d,12,3);t.check(0x31003d,12,3)
      info("FRAGMENT_OUTPUT_SAME_BEAT healthySplit=true faultDrained=true heldCancelDrained=true noFaultOrCancelWrites=true healthyReuse=true")
      info("FRAGMENT_OUTPUT_DRAIN delayedAcceptedCancel=true reorderedSplitFault=true olderLineDrained=true healthyReuse=true")
    }
  }

  it should "retain stalled offers and drain accepted reads through cancellation before healthy reuse" in {
    simulate(new FragmentOutputReader(GpuConfig(lanes=4,warps=4),18)){d=>
      val t=new Driver(d,64)
      for(held<-Seq(true,false)){
        t.ready=_=> !held;t.latency=_=>24;t.start(0x300000,16,6)
        if(held)t.until(d.io.request.valid.peek().litToBoolean)else t.until(t.requests.size==2)
        d.io.cancel.poke(true.B)
        for(_<-0 until 5){t.tick();d.io.done.valid.expect(false.B)}
        t.ready=_=>true;t.tick();d.io.cancel.poke(false.B)
        t.finish(true,hold=true);assert(t.writes.isEmpty)
        assert(t.requests.size==(if(held)1 else 2))
        t.latency=_=>5;t.start(0x310000,8,3);t.check(0x310000,8,3)
      }
      info("FRAGMENT_OUTPUT_READER cancelCuts=2 heldOfferStable=true acceptedReadsDrained=true noCanceledWrites=true healthyReuse=true")
    }
  }

  it should "drain an older line after a reordered split-line fault and recover without reset" in {
    simulate(new FragmentOutputReader(GpuConfig(lanes=4,warps=3),18)){d=>
      val t=new Driver(d,48);t.ready=_=>true
      t.latency=r=>if(r.order==0)25 else 2;t.fault=_.order==1
      t.start(0x40003d,12,6);t.finish(true,hold=true)
      assert(t.requests.size==2 && t.responses.toVector==Vector(1,0) && t.writes.isEmpty)
      t.fault=_=>false;t.start(0x41003d,12,6);t.check(0x41003d,12,6)
      info("FRAGMENT_OUTPUT_READER splitFault=true reverseReplies=true olderLineDrained=true noFaultedWrites=true healthyReuse=true")
    }
  }
}

/** Fast iteration screen; the complete fragment-stage timing gate is separate. */
object EmitFragmentOutputReaderTiming extends App {
  require(args.length==1)
  ChiselStage.emitSystemVerilogFile(new FragmentOutputReader(GpuConfig(lanes=16,warps=8),18),
    Array("--target-dir",args(0)),Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"))
}
