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
  private class Driver(val d: FragmentOutputReader,val stride: Int) {
    var cycle=0;var peak=0
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
      d.io.request.ready.poke(ready(cycle).B)
      if(response.isEmpty){
        val due=pending.indices.filter(i=>pending(i).due<=cycle)
        if(due.nonEmpty)response=Some(pending.remove(due.last))
      }
      d.io.response.valid.poke(response.nonEmpty.B)
      response.foreach{r=>d.io.response.bits.transactionId.poke(r.request.id.U)
        d.io.response.bits.readData.poke(r.data.U);d.io.response.bits.fault.poke(r.fault.B)}
      val offered=d.io.request.valid.peek().litToBoolean
      val bits=d.io.request.bits.peek().litValue
      heldRequest.foreach(old=>assert(offered && bits==old,"readback withdrew or changed a held request"))
      heldRequest=if(offered && !ready(cycle))Some(bits)else None
      response.foreach{r=>if(d.io.response.ready.peek().litToBoolean){
        assert(owned.remove(r.request.id),"readback response did not own an accepted request")
        responses+=r.request.order;response=None
      }}
      if(offered && ready(cycle)){
        val q=d.io.request.bits;q.isWrite.expect(false.B);q.sizeLog2.expect(6.U)
        val r=Request(q.transactionId.peek().litValue,q.address.peek().litValue,q.byteMask.peek().litValue,requests.size)
        assert((r.address&63)==0 && owned.add(r.id),"readback reused a live ID or an unaligned line")
        requests+=r
        val data=(0 until 64).foldLeft(BigInt(0))((v,b)=>v|(BigInt(byte(r.address+b))<<(8*b)))
        pending+=Reply(r,data,cycle+latency(r),fault(r))
      }
      if(d.io.write.valid.peek().litToBoolean){val w=d.io.write.bits
        assert(!d.io.cancel.peek().litToBoolean,"canceled readback committed cache data")
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
      requests.clear();responses.clear();writes.clear();peak=0
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
      assert(writes.size==fields*4)
      for((w,i)<-writes.zipWithIndex){
        assert(w.field==i/4 && w.row==i%4,"readback changed field/row publication order")
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
