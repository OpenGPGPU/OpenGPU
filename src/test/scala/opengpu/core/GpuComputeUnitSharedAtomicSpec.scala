package opengpu.core

import chisel3._
import chisel3.reflect.DataMirror
import chisel3.util.HasBlackBoxInline
import java.nio.file.{Files, Paths}
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.{DoNotDiscover}
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class SharedAtomicCuSramModels extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle { val present = Output(Bool()) })
  setInline("SharedAtomicCuSramModels.sv",
    "module SharedAtomicCuSramModels(output present); assign present=1'b1; endmodule\n" +
      Seq(32,64).map(width=>Files.readString(Paths.get(
        s"depends/asap7_sram_0p0/generated/verilog/srambank_64x4x${width}_6t122.v"))).mkString("\n"))
}
private class SharedAtomicSramComputeUnit extends GpuComputeUnit(
  GpuConfig(enableSharedVectorAtomics=true),useBlackBoxes=true,enableFpuBackend=true,
  nativeExecutionProfile=true,finishOnTrap=true,instructionCacheSets=4,instructionCacheWays=1,
  vectorCacheSets=4,vectorCacheWays=1) {
  val resourcesPresent=IO(Output(Bool()))
  resourcesPresent:=Module(new SharedAtomicCuSramModels).io.present
}

/** Explicit stock-capacity execution, separate from the small owner unit test. */
@DoNotDiscover
class GpuComputeUnitSharedAtomicSpec extends AnyFlatSpec {
  private def zero(data: Data): Unit = data match {
    case r: Record=>r.elements.values.foreach(zero)
    case v: Vec[_]=>v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case _=>()
  }
  private def addi(d: Int,a: Int,v: Int): Long=((v.toLong&4095)<<20)|(a<<15)|(d<<7)|0x13
  private def add(d: Int,a: Int,b: Int): Long=(b.toLong<<20)|(a<<15)|(d<<7)|0x33
  private def vector(fn: Int,f: Int,d: Int,a: Int,b: Int=0): Long=
    (fn.toLong<<26)|(1L<<25)|(b<<20)|(a<<15)|(f<<12)|(d<<7)|0x57
  private def memory(base: Int,v: Int,store: Boolean): Long=
    (1L<<25)|(base<<15)|(6<<12)|(v<<7)|(if(store)0x27 else 0x07)
  private def atomic(fn: Int,op: Int,d: Int,a: Int,b: Int=0): Long=
    (fn.toLong<<26)|(1L<<25)|(b<<20)|(a<<15)|(op<<12)|(d<<7)|0x2b
  behavior of "stock SRAM CU per-invocation shared atomics"

  it should "serialize all 128 contending invocations, retain held faults and reuse without reset" in {
    val c=GpuConfig(enableSharedVectorAtomics=true)
    simulate(new SharedAtomicSramComputeUnit) { d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
      d.resourcesPresent.expect(true.B);d.io.l1InvalidateDone.ready.poke(true.B)
      val output=0x20000L
      val bytes=mutable.Map.empty[Long,Int]
      def put(address: Long,value: Long): Unit=for(b<-0 until 4)bytes(address+b)=((value>>>(8*b))&255).toInt
      def get(address: Long): Long=(0 until 4).foldLeft(0L)((v,b)=>v|(bytes.getOrElse(address+b,0).toLong<<(8*b)))
      case class Reply(id: BigInt,data: BigInt,due: Int)
      val pending=mutable.Queue.empty[Reply]
      var cycle=0;var traps=Set.empty[Int];var allowTrap=false
      val warps=mutable.Set.empty[Int];val returned=mutable.ArrayBuffer.empty[Long]
      def tick(): Unit={
        d.io.globalAtomicRequest.valid.expect(false.B)
        d.io.trap.ready.poke(allowTrap.B)
        if(d.io.trap.valid.peek().litToBoolean) {
          d.io.trap.bits.cause.expect(7.U)
          if(allowTrap)traps+=d.io.trap.bits.warpId.peek().litValue.toInt
        }
        d.io.memoryRequest.ready.poke((cycle%7<5).B)
        val response=pending.headOption.filter(_.due<=cycle)
        d.io.memoryResponse.valid.poke(response.nonEmpty.B)
        response.foreach {r=>d.io.memoryResponse.bits.transactionId.poke(r.id.U);d.io.memoryResponse.bits.readData.poke(r.data.U)}
        if(response.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean)pending.dequeue()
        if(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean) {
          val q=d.io.memoryRequest.bits;val address=q.address.peek().litValue.toLong
          assert((address>=0x1000 && address<0x1800)||(address>=output && address<output+2048))
          if(q.isWrite.peek().litToBoolean) {
            assert(address>=output)
            val mask=q.byteMask.peek().litValue;val value=q.writeData.peek().litValue
            for(b<-0 until 64 if mask.testBit(b))bytes(address+b)=((value>>(8*b))&255).toInt
          }
          val value=(0 until 64).foldLeft(BigInt(0))((v,b)=>v|(BigInt(bytes.getOrElse(address+b,0))<<(8*b)))
          pending.enqueue(Reply(q.transactionId.peek().litValue,value,cycle+37))
        }
        if(d.io.committedVectorWriteback.valid.peek().litToBoolean &&
            d.io.committedVectorWriteback.bits.vd.peek().litValue==3) {
          val q=d.io.committedVectorWriteback.bits
          warps+=q.warpId.peek().litValue.toInt
          for(l<-0 until c.lanes)returned+=q.data(l).peek().litValue.toLong
        }
        d.clock.step();cycle+=1
      }
      def until(f: => Boolean): Unit={var n=0;while(!f && n<200000){tick();n+=1};assert(f,s"stalled at $cycle")}
      def program(bad: Int): Vector[Long]={
        val p=mutable.ArrayBuffer[Long](0xc1087057L,
          (2L<<20)|(8<<15)|(1<<12)|(12<<7)|0x13, // warp byte offset
          (c.sharedMemoryBase&0xfffff000L)|(10<<7)|0x37,
          add(11,10,12),vector(0x17,3,0,0)) // all shared words initialized to zero
        for(stripe<-0 until 32){p+=memory(11,0,true);if(stripe!=31)p+=addi(11,11,512)}
        p++=Seq(0x0330000fL,0x0000000bL,vector(0x17,3,2,1),atomic(30,0,0,2,0))
        if(bad==1)p+=addi(10,10,2)
        if(bad==2)p+=(((c.sharedMemoryBase+c.sharedMemoryBytes)&0xfffff000L)|(10<<7)|0x37)
        p++=Seq(atomic(31,1,3,10),add(13,1,12),memory(13,3,true),0x0330000fL,0x0000000bL,
          0x30500073L)
        assert(p.size<=512);p.toVector
      }
      for(epoch<-0 until 5) {
        val bad=if(epoch==1)1 else if(epoch==3)2 else 0
        val p=program(bad);p.zipWithIndex.foreach{case(w,i)=>put(0x1000L+i*4,w)}
        for(a<-output-64 until output+576)bytes(a)=0xa5
        warps.clear();returned.clear();traps=Set.empty;allowTrap=false
        d.io.invalidateInstructionCache.poke(true.B);tick();d.io.invalidateInstructionCache.poke(false.B)
        d.io.kernel.bits.kernelPc.poke(0x1000.U);d.io.kernel.bits.kernargAddress.poke(output.U)
        for(i<-0 until 3){d.io.kernel.bits.gridSize(i).poke(1.U);d.io.kernel.bits.localSize(i).poke((if(i==0)128 else 1).U)}
        d.io.kernel.valid.poke(true.B);until(d.io.kernel.ready.peek().litToBoolean);tick();d.io.kernel.valid.poke(false.B)
        if(bad!=0) {
          until(d.io.trap.valid.peek().litToBoolean)
          val held=d.io.trap.bits.peek().litValue
          for(_<-0 until 7){tick();assert(d.io.trap.bits.peek().litValue==held);d.io.completion.valid.expect(false.B)}
          allowTrap=true
        }
        until(d.io.completion.valid.peek().litToBoolean)
        d.io.completion.bits.success.expect((bad==0).B)
        for(_<-0 until 7){tick();d.io.completion.valid.expect(true.B)}
        assert(pending.isEmpty)
        if(bad==0) {
          assert(warps.toSet==(0 until 8).toSet)
          assert(returned.sorted==(0 until 128).map(_.toLong))
          assert((0 until 128).map(i=>get(output+i*4)).sorted==(0 until 128).map(_.toLong))
        }else {
          assert(traps==(0 until 8).toSet && returned.isEmpty)
          for(a<-output until output+512)assert(bytes(a)==0xa5)
        }
        for(a<-output-64 until output)assert(bytes(a)==0xa5)
        for(a<-output+512 until output+576)assert(bytes(a)==0xa5)
        d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B)
        d.io.active.expect(0.U)
      }
    }
  }
}
