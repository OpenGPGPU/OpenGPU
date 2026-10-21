package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.backend.{VectorBackend,VectorCommitRequest,VectorTextureRequest}
import opengpu.core.backend.register.{ScalarRegisterWrite,VectorRegisterWrite}
import opengpu.core.frontend.decode.{DecodePipe,DecodeRequest}
import opengpu.core.memory.VectorMemoryFault
import opengpu.core.execute.control.SimtPath
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

private class TextureParameterBackendHarness extends Module {
  private val config=GpuConfig(lanes=4,warps=2)
  val io=IO(new Bundle {
    val fetch=Flipped(Decoupled(new DecodeRequest(config)));val initialize=Flipped(Decoupled(new VectorRegisterWrite(config)))
    val texture=Decoupled(new VectorTextureRequest(config));val textureCommit=Flipped(Decoupled(new VectorCommitRequest(config)))
    val committed=Output(Valid(new VectorRegisterWrite(config)));val scalar=Decoupled(new ScalarRegisterWrite(config))
    val redirect=Decoupled(new SimtPath(config));val fault=Decoupled(new VectorMemoryFault(config))
  })
  private val decode=Module(new DecodePipe(config));private val backend=Module(new VectorBackend(config))
  decode.io.in<>io.fetch;decode.io.scalarOut.ready:=true.B;decode.io.fpuOut.ready:=true.B;backend.io.in<>decode.io.vectorOut
  backend.io.scalarRs1Data:=0.U;backend.io.scalarRs2Data:=0.U;backend.io.scalarFpData:=0.U;backend.io.scalarFpBusy:=VecInit(Seq.fill(2)(0.U(32.W)))
  backend.io.initialize<>io.initialize;io.scalar<>backend.io.scalarWriteback;io.redirect<>backend.io.redirect;io.fault<>backend.io.memoryFault
  io.texture<>backend.io.texSample;backend.io.texCommit<>io.textureCommit;io.committed:=backend.io.committedVectorWriteback
  backend.io.scalarFlagsWrite.valid:=false.B;backend.io.scalarFlagsWrite.bits:=0.U.asTypeOf(backend.io.scalarFlagsWrite.bits)
  backend.io.shaderCsrWrite.valid:=false.B;backend.io.shaderCsrWrite.bits:=0.U.asTypeOf(backend.io.shaderCsrWrite.bits)
  backend.io.clearWarp.valid:=false.B;backend.io.clearWarp.bits:=0.U;backend.io.scalarReserve.ready:=true.B;backend.io.fpReserve.ready:=true.B;backend.io.fpWriteback.ready:=true.B
  backend.io.memoryRequest.ready:=true.B;backend.io.memoryResponse.valid:=false.B;backend.io.memoryResponse.bits:=0.U.asTypeOf(backend.io.memoryResponse.bits)
  backend.io.unimplemented.ready:=true.B
}

class TextureParameterRetirementSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  it should "retire parameter writes through x0 while preserving an outstanding v0 divide reservation" in {
    simulate(new TextureParameterBackendHarness){d=>
      zero(d.io);d.io.scalar.ready.poke(true.B);d.io.redirect.ready.poke(true.B);d.io.fault.ready.poke(true.B)
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val writes=scala.collection.mutable.ArrayBuffer.empty[(Int,Seq[BigInt])];var zeroAcks=0
      def tick(): Unit={
        d.io.fault.valid.expect(false.B)
        if(d.io.committed.valid.peek().litToBoolean)writes+=((d.io.committed.bits.vd.peek().litValue.toInt,d.io.committed.bits.data.map(_.peek().litValue).toSeq))
        if(d.io.scalar.valid.peek().litToBoolean && d.io.scalar.ready.peek().litToBoolean && d.io.scalar.bits.rd.peek().litValue==0)zeroAcks+=1
        d.clock.step()
      }
      def until(p: =>Boolean): Unit={var n=0;while(!p && n<256){tick();n+=1};assert(p,"backend texture parameter retirement stalled")}
      def initialize(reg: Int,value: BigInt): Unit={d.io.initialize.bits.warpId.poke(0.U);d.io.initialize.bits.vd.poke(reg.U)
        d.io.initialize.bits.data.foreach(_.poke(value.U));d.io.initialize.valid.poke(true.B);until(d.io.initialize.ready.peek().litToBoolean);tick();d.io.initialize.valid.poke(false.B)}
      def issue(word: BigInt,pc: Int): Unit={d.io.fetch.bits.instruction.poke(word.U);d.io.fetch.bits.pc.poke(pc.U);d.io.fetch.bits.warpId.poke(0.U)
        d.io.fetch.bits.activeMask.poke(15.U)
        d.io.fetch.bits.instructionAccessFault.poke(false.B);d.io.fetch.bits.executionFault.poke(0.U);d.io.fetch.valid.poke(true.B)
        until(d.io.fetch.ready.peek().litToBoolean);tick();d.io.fetch.valid.poke(false.B)}
      initialize(0,0x9badf00dL);initialize(1,0x13579bdfL);initialize(2,1);initialize(3,3);initialize(4,4)
      issue(BigInt("c1027057",16),0);for(_<-0 until 8)tick();zeroAcks=0
      val divide=(BigInt(0x20)<<26)|(BigInt(1)<<25)|(BigInt(1)<<20)|(BigInt(2)<<15)|(BigInt(2)<<12)|0x57
      issue(divide,4)
      val parameter=(BigInt(3)<<26)|(BigInt(1)<<25)|(BigInt(4)<<20)|(BigInt(3)<<15)|0x2b
      issue(parameter,8);until(d.io.texture.valid.peek().litToBoolean)
      assert(!writes.exists(_._1==0),"divide must still own its v0 destination before parameter completion")
      d.io.texture.bits.issued.decode.decoded.writesVd.expect(false.B);d.io.texture.bits.issued.decode.decoded.writesScalar.expect(true.B)
      d.io.texture.ready.poke(true.B);tick();d.io.texture.ready.poke(false.B)
      zero(d.io.textureCommit.bits);val ack=d.io.textureCommit.bits
      ack.writeback.warpId.poke(0.U);ack.writeback.vd.poke(0.U);ack.writesScalar.poke(true.B);ack.pc.poke(8.U);ack.warpActiveMask.poke(15.U)
      d.io.textureCommit.valid.poke(true.B);until(d.io.textureCommit.ready.peek().litToBoolean);tick();d.io.textureCommit.valid.poke(false.B)
      val add=(BigInt(1)<<25)|(BigInt(0)<<20)|(BigInt(3)<<15)|(BigInt(5)<<7)|0x57
      issue(add,12);until(writes.exists(_._1==5))
      assert(zeroAcks==1,"parameter operation did not acknowledge through x0")
      assert(writes.filter(_._1==0).map(_._2)==Seq(Seq.fill(4)(BigInt(0x13579bdfL))),"parameter ack wrote or prematurely released v0")
      assert(writes.find(_._1==5).get._2==Seq.fill(4)(BigInt(0x13579be2L)),"dependent ALU observed stale v0")
      info("TEXTURE_PARAMETER_RETIRE realDecode=true vectorDestinationNone=true scalarX0Ack=true pendingV0Divide=true dependentReadWaits=true noSpuriousTextureFault=true")
    }
  }
}
