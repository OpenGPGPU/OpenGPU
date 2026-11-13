package opengpu.core.memory

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.backend.VectorCommitRequest
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

private class SharedAtomicSramHarness(c: GpuConfig) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new SharedAtomicVectorRequest(c)))
    val commit = Decoupled(new VectorCommitRequest(c))
    val fault = Valid(new VectorMemoryFault(c))
    val memory = Flipped(Decoupled(new VectorMemoryTransaction(c)))
    val result = Decoupled(new VectorMemoryResponse(c))
    val warpStart = Flipped(Valid(UInt(c.warpIdWidth.W)))
    val cancel = Input(Bool())
    val allowRequest = Input(Bool()); val allowResponse = Input(Bool())
    val offered = Valid(new SharedAtomicRequest(c))
    val accepted = Output(Bool()); val busy = Output(Bool())
  })
  val vector = Module(new SharedAtomicVectorExecuteUnit(c))
  val memory = Module(new BankedSharedMemory(c))
  vector.io.in <> io.in; io.commit <> vector.io.commit; io.fault := vector.io.fault
  vector.io.warpStart := io.warpStart; vector.io.cancel := io.cancel
  memory.io.in <> io.memory; io.result <> memory.io.out
  memory.io.atomicIn.valid := vector.io.request.valid && io.allowRequest
  memory.io.atomicIn.bits := vector.io.request.bits
  vector.io.request.ready := memory.io.atomicIn.ready && io.allowRequest
  vector.io.response.valid := memory.io.atomicOut.valid && io.allowResponse
  vector.io.response.bits := memory.io.atomicOut.bits
  memory.io.atomicOut.ready := vector.io.response.ready && io.allowResponse
  io.offered.valid := vector.io.request.valid; io.offered.bits := vector.io.request.bits
  io.accepted := memory.io.atomicIn.fire; io.busy := vector.io.busy
}

class SharedAtomicVectorExecuteUnitSpec extends AnyFlatSpec {
  private def word(fn: Int, op: Int, rd: Int, a: Int, b: Int, unmasked: Boolean = true): BigInt =
    (BigInt(fn)<<26) | (if(unmasked) BigInt(1)<<25 else BigInt(0)) |
      (BigInt(b)<<20) | (BigInt(a)<<15) | (BigInt(op)<<12) | (BigInt(rd)<<7) | 0x2b
  private val mask32 = BigInt("ffffffff",16)
  private def signed(v: BigInt): BigInt = if(v.testBit(31)) v-(BigInt(1)<<32) else v
  private def applyOp(op: Int, old: BigInt, value: BigInt, compare: BigInt): BigInt = (op match {
    case 0 => value; case 1 => old+value; case 2 => old^value
    case 3 => old|value; case 4 => old&value
    case 5 => if(signed(old)<signed(value)) old else value
    case 6 => if(signed(old)>signed(value)) old else value
    case 7 => old.min(value); case 8 => old.max(value)
    case 9 => if(old==compare) value else old
  }) & mask32
  behavior of "per-invocation shared word atomics"

  it should "return every old word for all ten operations on 128 invocations and both SRAM endpoints" in {
    val c=GpuConfig(enableSharedVectorAtomics=true)
    simulate(new SharedAtomicSramHarness(c)) { d =>
      d.io.in.valid.poke(false.B); d.io.in.bits.poke(0.U.asTypeOf(d.io.in.bits))
      d.io.memory.valid.poke(false.B); d.io.memory.bits.poke(0.U.asTypeOf(d.io.memory.bits))
      d.io.result.ready.poke(false.B);d.io.commit.ready.poke(false.B)
      d.io.warpStart.valid.poke(false.B);d.io.warpStart.bits.poke(0.U)
      d.io.cancel.poke(false.B);d.io.allowRequest.poke(true.B);d.io.allowResponse.poke(true.B)
      d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
      def until(f: => Boolean): Unit = {var n=0;while(!f && n<10000){d.clock.step();n+=1};assert(f)}
      def memory(address: Long, value: BigInt, store: Boolean): BigInt = {
        d.io.memory.bits.poke(0.U.asTypeOf(d.io.memory.bits))
        d.io.memory.bits.laneMask.poke(1.U);d.io.memory.bits.elementSize.poke(2.U)
        d.io.memory.bits.addresses(0).poke(address.U);d.io.memory.bits.writeData(0).poke(value.U)
        d.io.memory.bits.isStore.poke(store.B);d.io.memory.valid.poke(true.B)
        until(d.io.memory.ready.peek().litToBoolean);d.clock.step();d.io.memory.valid.poke(false.B)
        until(d.io.result.valid.peek().litToBoolean);d.io.result.bits.faultMask.expect(0.U)
        val result=d.io.result.bits.readData(0).peek().litValue
        d.io.result.ready.poke(true.B);d.clock.step();d.io.result.ready.poke(false.B);result
      }
      def issue(warp: Int, instruction: BigInt, values: Seq[BigInt], comparisons: Seq[BigInt],
          base: Long, mask: BigInt, side: BigInt): Unit = {
        d.io.in.bits.poke(0.U.asTypeOf(d.io.in.bits))
        d.io.in.bits.issued.decode.warpId.poke(warp.U)
        d.io.in.bits.issued.decode.pc.poke(0x1234.U)
        d.io.in.bits.issued.decode.activeMask.poke(((BigInt(1)<<c.lanes)-1).U)
        d.io.in.bits.issued.decode.instruction.poke(instruction.U)
        d.io.in.bits.executionMask.poke(mask.U);d.io.in.bits.sideEffectMask.poke(side.U)
        d.io.in.bits.issued.scalarRs1Data.poke(base.U)
        for(l<-0 until c.lanes) {
          d.io.in.bits.issued.vs1Data(l).poke(values(l).U)
          d.io.in.bits.issued.vs2Data(l).poke(comparisons(l).U)
          d.io.in.bits.issued.oldVdData(l).poke(0xfeed.U)
        }
        d.io.in.valid.poke(true.B);until(d.io.in.ready.peek().litToBoolean)
        d.clock.step();d.io.in.valid.poke(false.B)
        // Inputs are deliberately destroyed after the accepted snapshot.
        d.io.in.bits.poke(0.U.asTypeOf(d.io.in.bits))
      }
      def commit(expected: Seq[BigInt], params: Boolean, failed: Boolean=false): Unit = {
        until(d.io.commit.valid.peek().litToBoolean)
        d.io.commit.bits.writesVd.expect((!params && !failed).B)
        d.io.commit.bits.writesScalar.expect((params && !failed).B)
        d.io.fault.valid.expect(failed.B)
        if(!params && !failed)for(l<-0 until c.lanes)d.io.commit.bits.writeback.data(l).expect(expected(l).U)
        val held=d.io.commit.bits.peek().litValue
        for(_<-0 until 4){d.clock.step();assert(d.io.commit.bits.peek().litValue==held)}
        d.io.commit.ready.poke(true.B);d.clock.step();d.io.commit.ready.poke(false.B)
      }
      val all=(BigInt(1)<<c.lanes)-1
      for(op<-0 until 10; offset<-Seq(0,c.sharedMemoryBytes-4)) {
        val address=c.sharedMemoryBase+offset
        var expected=BigInt("80000000",16)
        memory(address,expected,true)
        val guard=if(offset==0)address+4 else address-4
        memory(guard,BigInt("a5a5a5a5",16),true)
        for(w<-0 until c.warps) {
          d.io.warpStart.valid.poke(true.B);d.io.warpStart.bits.poke(w.U);d.clock.step();d.io.warpStart.valid.poke(false.B)
          val values=(0 until c.lanes).map(l=>Seq(BigInt(1),mask32,BigInt("80000000",16),BigInt("7fffffff",16))((w*c.lanes+l)%4))
          val compares=(0 until c.lanes).map(l=>if(l%2==0)BigInt("80000000",16) else BigInt(17))
          issue(w,word(30,0,0,2,3),values,compares,0,all,all);commit(Nil,params=true)
          val old=(0 until c.lanes).map {l=>val v=expected;expected=applyOp(op,expected,values(l),compares(l));v}
          issue(w,word(if(op<8)31 else 32,op%8,4,5,0),values,compares,address,all,all)
          commit(old,params=false)
        }
        assert(memory(address,0,false)==expected)
        assert(memory(guard,0,false)==BigInt("a5a5a5a5",16))
      }
      // Inactive and helper lanes preserve old vector data and issue no AMO.
      val address=c.sharedMemoryBase
      memory(address,0,true)
      issue(0,word(30,0,0,2,3),Seq.fill(c.lanes)(BigInt(1)),Seq.fill(c.lanes)(BigInt(0)),0,all,all)
      commit(Nil,params=true)
      val selected=BigInt("5555",16)
      var old=BigInt(0)
      val expected=(0 until c.lanes).map(l=>if(selected.testBit(l)){val v=old;old+=1;v}else BigInt(0xfeed))
      issue(0,word(31,1,4,5,0,unmasked=false),Nil.padTo(c.lanes,BigInt(0)),Nil.padTo(c.lanes,BigInt(0)),address,all,selected)
      commit(expected,params=false);assert(memory(address,0,false)==8)
      // An offered request survives cancel and drains, but never publishes data.
      d.io.allowRequest.poke(false.B)
      issue(0,word(31,1,4,5,0),Seq.fill(c.lanes)(BigInt(0)),Seq.fill(c.lanes)(BigInt(0)),address,all,all)
      until(d.io.offered.valid.peek().litToBoolean)
      val offered=d.io.offered.bits.peek().litValue;d.io.cancel.poke(true.B)
      for(_<-0 until 5){d.clock.step();d.io.offered.valid.expect(true.B);assert(d.io.offered.bits.peek().litValue==offered)}
      d.io.allowRequest.poke(true.B);commit(Nil,params=false,failed=true);d.io.cancel.poke(false.B)
      assert(memory(address,0,false)==9) // exactly the irrevocable lane, no rollback claim
      issue(0,word(31,1,4,5,0),Seq.fill(c.lanes)(BigInt(0)),Seq.fill(c.lanes)(BigInt(0)),address+2,all,all)
      commit(Nil,params=false,failed=true);assert(memory(address,0,false)==9)
      issue(0,word(31,1,4,5,0),Seq.fill(c.lanes)(BigInt(0)),Seq.fill(c.lanes)(BigInt(0)),address+c.sharedMemoryBytes,all,all)
      commit(Nil,params=false,failed=true);assert(memory(address,0,false)==9)
      // Same owner is healthy again without reset; warp reuse invalidates params.
      issue(0,word(31,1,4,5,0),Seq.fill(c.lanes)(BigInt(0)),Seq.fill(c.lanes)(BigInt(0)),address,all,all)
      commit((9 until 25).map(BigInt(_)),params=false);assert(memory(address,0,false)==25)
      d.io.allowResponse.poke(false.B)
      issue(0,word(31,1,4,5,0),Seq.fill(c.lanes)(BigInt(0)),Seq.fill(c.lanes)(BigInt(0)),address,all,all)
      until(d.io.accepted.peek().litToBoolean);d.clock.step()
      for(_<-0 until 37){d.clock.step();d.io.commit.valid.expect(false.B);d.io.offered.valid.expect(false.B)}
      d.io.allowResponse.poke(true.B)
      commit((25 until 41).map(BigInt(_)),params=false);assert(memory(address,0,false)==41)
      // Reset a genuinely accepted write with its SRAM response held. Reset
      // drops control/parameters, not the already modified memory contents.
      d.io.allowResponse.poke(false.B)
      issue(0,word(31,1,4,5,0),Seq.fill(c.lanes)(BigInt(0)),Seq.fill(c.lanes)(BigInt(0)),address,all,all)
      until(d.io.accepted.peek().litToBoolean);d.clock.step(20)
      d.io.commit.valid.expect(false.B)
      d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
      d.io.allowResponse.poke(true.B)
      for(_<-0 until 4){d.clock.step();d.io.commit.valid.expect(false.B);d.io.offered.valid.expect(false.B)}
      assert(memory(address,0,false)==42)
      issue(0,word(31,1,4,5,0),Seq.fill(c.lanes)(BigInt(0)),Seq.fill(c.lanes)(BigInt(0)),address,all,all)
      commit(Nil,params=false,failed=true);assert(memory(address,0,false)==42)
      issue(0,word(30,0,0,2,3),Seq.fill(c.lanes)(BigInt(1)),Seq.fill(c.lanes)(BigInt(0)),0,all,all)
      commit(Nil,params=true)
      issue(0,word(31,1,4,5,3),Seq.fill(c.lanes)(BigInt(0)),Seq.fill(c.lanes)(BigInt(8)),0xfffffffcL,all,all)
      commit(Nil,params=false,failed=true);assert(memory(address,0,false)==42)
      d.io.warpStart.valid.poke(true.B);d.io.warpStart.bits.poke(0.U);d.clock.step();d.io.warpStart.valid.poke(false.B)
      issue(0,word(31,1,4,5,0),Seq.fill(c.lanes)(BigInt(0)),Seq.fill(c.lanes)(BigInt(0)),address,all,all)
      commit(Nil,params=false,failed=true);assert(memory(address,0,false)==42)
    }
  }
}
