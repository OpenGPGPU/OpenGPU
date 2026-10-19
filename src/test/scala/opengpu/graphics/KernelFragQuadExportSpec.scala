package opengpu.graphics

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class KernelFragQuadExportSpec extends AnyFlatSpec {
  private def zero(x: Data): Unit=x match {
    case r: Record=>r.elements.values.foreach(zero)
    case v: Vec[_]=>v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case _=>()
  }
  private case class Reply(id: Int,address: Long,write: Boolean,data: BigInt,mask: BigInt,due: Int,fault: Boolean)
  for((lanes,warps,credits) <- Seq((4,2,2),(8,2,2),(16,8,8)))
  it should s"retain shader quad coverage and selected depths through stalls faults and late retirement lanes=$lanes warps=$warps" in {
    val cap=lanes*warps
    simulate(new KernelFragStage(GpuConfig(lanes=lanes,warps=warps),GraphicsConfig(),credits,quadExports=true)) { d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      d.io.shaderPc.poke(0x1000.U);d.io.kernargBase.poke(0x8000.U);d.io.kernargBankStride.poke(0x4000.U)
      d.io.inputSkip.poke(15.U);d.io.kernelLaunch.ready.poke(true.B)
      d.io.texMem.req.ready.poke(true.B);d.io.kernelTexWriteback.ready.poke(true.B)
      d.io.kernelVectorTexWriteback.ready.poke(true.B)
      val memory=mutable.LongMap.empty[BigInt]
      def put(a: Long,v: BigInt): Unit = {
        val base=a& ~63L;val shift=(a&63).toInt*8
        memory(base)=(memory.getOrElse(base,BigInt(0)) & ~(BigInt("ffffffff",16)<<shift)) | ((v&BigInt("ffffffff",16))<<shift)
      }
      for(kind <- Seq("full","mixed","abi0","discard","helpers","writeFault","readFault","kernelFault","lateFlush","partial")) {
        val count=if(kind=="partial")math.max(4,cap-4)else cap
        val abi1=kind!="abi0"
        def covered(i: Int): Boolean=kind!="helpers" && (kind=="full" || kind=="lateFlush" || i%7!=6)
        def control(i: Int): Long=if(kind=="discard")0 else if(kind=="abi0")0x80000002L else if(kind=="mixed")
          Vector(0L,1L,2L,3L,0x80000001L,1L)(i%6)else 1L
        def emits(i: Int): Boolean=covered(i) && (if(abi1)(control(i)& ~3L)==0 && (control(i)&1)!=0 else control(i)!=0)
        def mask(i: Int): Int=if(emits(i))((i*3)%15)+1 else 0
        def shaderDepth(i: Int): Long=(0x81234567L+i*97)&0xffffffffL
        val expected=(0 until count/4).filter(q=>(0 until 4).exists(p=>mask(q*4+p)!=0))
        val expectOutput= !kind.endsWith("Fault")
        d.io.abi1.poke(abi1.B);d.io.outputSkip.poke(0.U)
        d.io.flush.poke(false.B);d.io.kernelCompletion.valid.poke(false.B)
        d.io.wordMemReq.ready.poke(false.B);d.io.wordMemResp.valid.poke(false.B)
        d.io.quadOut.get.ready.poke(false.B);d.io.drawRetire.ready.poke(false.B)
        d.clock.step()
        for(q <- 0 until count/4) {
          d.io.fragIn.ready.expect(true.B)
          for(p <- 0 until 4) {
            val i=q*4+p;val f=d.io.fragIn.bits.lanes(p)
            f.x.poke((32+i).S);f.y.poke((64-i).S);f.depth.poke((700+i).S)
            f.color.r.poke(12.U);f.color.g.poke(34.U);f.color.b.poke(56.U);f.alpha.poke(255.U)
            f.covered.poke(covered(i).B);f.coverageMask.poke((((i*3)%15)+1).U)
            for(s <- 0 until 4)d.io.fragDepths(p)(s).poke((10000+i*16+s*3).U)
          }
          d.io.fragIn.valid.poke(true.B);d.clock.step()
          d.io.abi1.poke((!abi1).B) // Only the first quad owns this batch's ABI.
        }
        d.io.fragIn.valid.poke(false.B)
        val late=kind=="lateFlush"
        if(!late){d.io.flush.poke(true.B);d.clock.step();d.io.flush.poke(false.B)}
        var cycle=0;var launched=false;var completed=false;var injected=false;var retired=false
        var received=0;var held=Option.empty[BigInt];var flushed= !late;var holdLast=0
        val pending=mutable.ArrayBuffer.empty[Reply]
        val outputCycles=mutable.ArrayBuffer.empty[Int]
        while(!retired && cycle<16000) {
          d.io.wordMemReq.ready.poke((cycle%9>=2).B)
          d.io.kernelCompletion.valid.poke((launched && !completed).B)
          d.io.kernelCompletion.bits.success.poke((kind!="kernelFault").B)
          val r=pending.lastIndexWhere(_.due<=cycle)
          d.io.wordMemResp.valid.poke((r>=0).B)
          if(r>=0) {
            val a=pending(r);d.io.wordMemResp.bits.transactionId.poke(a.id.U)
            d.io.wordMemResp.bits.readData.poke(memory.getOrElse(a.address,BigInt(0)).U)
            d.io.wordMemResp.bits.fault.poke(a.fault.B)
          }
          val out=d.io.quadOut.get
          val lastHeld=late && out.valid.peek().litToBoolean && received==expected.size-1 && holdLast<12
          if(lastHeld)holdLast+=1
          if(late && holdLast==6 && !flushed){d.io.flush.poke(true.B);flushed=true}
          else d.io.flush.poke(false.B)
          out.ready.poke((!lastHeld && (kind=="full" || cycle%7>=3)).B)
          held.foreach(b=>{out.valid.expect(true.B);assert(out.bits.peek().litValue==b,"stalled quad export changed")})
          if(out.valid.peek().litToBoolean) {
            assert(expectOutput && received<expected.size && pending.isEmpty)
            val q=expected(received)
            for(p <- 0 until 4) {
              val i=q*4+p;val b=out.bits(p)
              b.x.expect((32+i).U);b.y.expect(((64-i)&65535).U)
              b.coverageMask.expect(mask(i).U)
              if(mask(i)!=0)b.color.expect(((0x10203040L+i*0x010101L)&0xffffffffL).U)
              for(s <- 0 until 4 if mask(i)!=0) {
                val overrideDepth= !abi1 || (control(i)&2)!=0
                b.depths(s).expect((if(overrideDepth)shaderDepth(i)&0xffffff else 10000+i*16+s*3).U)
              }
            }
            if(out.ready.peek().litToBoolean){received+=1;outputCycles+=cycle;held=None}
            else held=Some(out.bits.peek().litValue)
          }else held=None
          d.io.out.valid.expect(false.B)
          if(d.io.kernelLaunch.valid.peek().litToBoolean) {
            assert(!launched && pending.isEmpty && kind!="writeFault")
            val base=d.io.kernelLaunch.kernargAddress.peek().litValue.toLong
            for(i <- 0 until count) {
              put(base+24L*cap+i*4,(0x10203040L+i*0x010101L)&0xffffffffL)
              put(base+28L*cap+i*4,shaderDepth(i));put(base+32L*cap+i*4,control(i))
            }
            launched=true
          }
          if(d.io.kernelCompletion.valid.peek().litToBoolean && d.io.kernelCompletion.ready.peek().litToBoolean)completed=true
          if(d.io.drawRetire.valid.peek().litToBoolean) {
            assert(flushed && held.isEmpty && received==(if(expectOutput)expected.size else 0) && pending.isEmpty)
            d.io.drawRetire.ready.poke(true.B);retired=true
          }else d.io.drawRetire.ready.poke(false.B)
          if(r>=0 && d.io.wordMemResp.ready.peek().litToBoolean) {
            val a=pending.remove(r)
            if(a.write && !a.fault)for(b <- 0 until 64 if a.mask.testBit(b)) {
              val shift=b*8;memory(a.address)=(memory.getOrElse(a.address,BigInt(0)) & ~(BigInt(255)<<shift))|(((a.data>>shift)&255)<<shift)
            }
          }
          if(d.io.wordMemReq.valid.peek().litToBoolean && d.io.wordMemReq.ready.peek().litToBoolean) {
            val b=d.io.wordMemReq.bits;val id=b.transactionId.peek().litValue.toInt
            assert(!pending.exists(_.id==id))
            val write=b.isWrite.peek().litToBoolean
            val fault= !injected && ((kind=="writeFault" && write)||(kind=="readFault" && !write))
            if(fault)injected=true
            pending+=Reply(id,b.address.peek().litValue.toLong,write,b.writeData.peek().litValue,b.byteMask.peek().litValue,cycle+5+id%3,fault)
          }
          d.clock.step();cycle+=1
        }
        assert(retired && received==(if(expectOutput)expected.size else 0),s"stalled $kind outputs=$received/$expected")
        if(kind=="full")assert(outputCycles.size==cap/4 && outputCycles.sliding(2).forall(v=>v(1)==v(0)+1))
        d.io.flush.poke(false.B);d.io.kernelCompletion.valid.poke(false.B);d.io.wordMemResp.valid.poke(false.B)
        d.io.drawRetire.ready.poke(false.B);d.clock.step(2);d.io.drained.expect(true.B)
        println(s"SHADER_QUAD cap=$cap case=$kind packets=$received PASS")
      }
    }
  }
}
