package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.core.frontend.decode.VectorDecoder
import opengpu.graphics.tessellation._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

/** Instruction peer, not shader execution or original ESSL evidence. */
class TessellationEvaluationVectorAccessSpec extends AnyFlatSpec {
  import WindowRecordTest._
  it should "reserve the new encoding unless the independent decoder option is explicitly enabled" in {
    val instruction=(BigInt(33)<<26)|(BigInt(1)<<25)|(BigInt(4)<<20)|(BigInt(2)<<15)|(BigInt(5)<<7)|0x2b
    for(enabled<-Seq(false,true))simulate(new VectorDecoder(evaluationPatchAvailable=enabled)){d=>
      d.io.instruction.poke(instruction.U);d.io.decoded.valid.expect(enabled.B)
      if(enabled){d.io.decoded.readsVs1.expect(true.B);d.io.decoded.readsVs2.expect(true.B)
        d.io.decoded.writesVd.expect(true.B);d.io.decoded.writesScalar.expect(false.B)}
      d.io.instruction.poke((instruction|0x1000).U);d.io.decoded.valid.expect(false.B)
    }
  }
  it should "fail closed on wrong binding versions, stages and coupled storage capacity" in {
    simulate(new TessellationEvaluationVectorAccess(GpuConfig(lanes=16,warps=8))){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val b=d.io.start.bits
      val mutations=Seq[()=>Unit](()=>b.version.poke(2.U),()=>b.stage.poke(4.U),
        ()=>b.layout.inputVertices.poke(0.U),()=>b.layout.inputVertices.poke(33.U),
        ()=>b.layout.inputComponents.poke(65.U),()=>b.layout.outputVertices.poke(0.U),
        ()=>b.layout.outputVertices.poke(33.U),()=>b.layout.outputComponents.poke(65.U),
        ()=>b.layout.patchComponents.poke(121.U),()=>b.layout.outputComponents.poke(64.U))
      for(mutate<-mutations){zero(b);b.version.poke(1.U);b.stage.poke(5.U)
        b.layout.inputVertices.poke(32.U);b.layout.inputComponents.poke(64.U)
        b.layout.outputVertices.poke(32.U);b.layout.outputComponents.poke(60.U);b.layout.patchComponents.poke(120.U)
        mutate();d.io.start.valid.poke(true.B);d.clock.step();d.io.start.valid.poke(false.B)
        d.io.enabled.expect(false.B);d.io.in.ready.expect(false.B);d.io.request.valid.expect(false.B)
        d.io.end.poke(true.B);d.clock.step();d.io.end.poke(false.B)
      }
    }
  }
  it should "retain full checked dynamic indices and drain cancellation without writing failed values" in {
    val c=GpuConfig(lanes=16,warps=8)
    simulate(new TessellationEvaluationVectorAccess(c)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def until(p: =>Boolean):Unit={var n=0;while(!p && n<100){d.clock.step();n+=1};assert(p)}
      def bind():Unit={val b=d.io.start.bits;zero(b)
        b.version.poke(1.U);b.stage.poke(5.U);b.taskId.poke(17.U);b.sequence.poke(23.U)
        b.layout.inputVertices.poke(32.U);b.layout.inputComponents.poke(64.U)
        b.layout.outputVertices.poke(32.U);b.layout.outputComponents.poke(60.U);b.layout.patchComponents.poke(120.U)
        d.io.start.valid.poke(true.B);d.clock.step();d.io.start.valid.poke(false.B)
        zero(b);d.io.warpStart.valid.poke(true.B);d.io.warpStart.bits.poke(3.U)
        d.clock.step();d.io.warpStart.valid.poke(false.B)}
      def issue(vertex:BigInt,address:BigInt):Unit={val q=d.io.in.bits;zero(q)
        q.issued.decode.warpId.poke(3.U);q.issued.decode.activeMask.poke(1.U)
        q.issued.decode.pc.poke(0x100.U);q.issued.decode.instruction.poke(((BigInt(33)<<26)|(BigInt(1)<<25)|(BigInt(5)<<7)|0x2b).U)
        q.executionMask.poke(1.U);q.issued.vs1Data(0).poke(vertex.U);q.issued.vs2Data(0).poke(address.U)
        for(i<-0 until c.lanes)q.issued.oldVdData(i).poke((0x1000+i).U)
        d.io.in.valid.poke(true.B);until(d.io.in.ready.peek().litToBoolean)
        d.clock.step();d.io.in.valid.poke(false.B);zero(q)}
      def commit(status:Int):Unit={until(d.io.commit.valid.peek().litToBoolean)
        d.io.status.expect(status.U);d.io.commit.bits.writesVd.expect((status==0).B)
        d.io.commit.bits.writeback.warpId.expect(3.U);d.io.commit.bits.writeback.vd.expect(5.U)
        val held=d.io.commit.bits.peek().litValue
        for(_<-0 until 3){d.clock.step();d.io.commit.valid.expect(true.B);assert(d.io.commit.bits.peek().litValue==held)}
        d.io.commit.ready.poke(true.B);d.clock.step();d.io.commit.ready.poke(false.B);d.io.busy.expect(false.B)}
      bind()
      for((v,a)<-Seq((31,59*8+1),(0,119*8+3),(0,5*8+7))){
        issue(v,a);until(d.io.request.valid.peek().litToBoolean)
        d.io.request.bits.scope.taskId.expect(17.U);d.io.request.bits.vertices(0).expect(v.U)
        d.io.request.bits.addresses(0).expect(a.U)
        d.io.request.ready.poke(true.B);d.clock.step();d.io.request.ready.poke(false.B)
        zero(d.io.result.bits);d.io.result.bits.warp.poke(3.U);d.io.result.bits.retired.poke(1.U)
        d.io.result.bits.data(0).poke(0xdeadbeefL.U);d.io.result.valid.poke(true.B)
        until(d.io.result.ready.peek().litToBoolean);d.clock.step();d.io.result.valid.poke(false.B)
        d.io.commit.bits.writeback.data(0).expect(0xdeadbeefL.U)
        for(i<-1 until c.lanes)d.io.commit.bits.writeback.data(i).expect((0x1000+i).U)
        commit(0)
      }
      for((v,a)<-Seq((BigInt(32),BigInt(1)),(BigInt(0),BigInt(60*8+1)),(BigInt(0),BigInt(120*8+3)),
        (BigInt(0),BigInt(6*8+7)),(BigInt(0),BigInt(5)),(BigInt(0),BigInt(1025)),(BigInt("10000000",16),BigInt(1)))){
        issue(v,a);until(d.io.commit.valid.peek().litToBoolean);d.io.request.valid.expect(false.B);commit(1)
      }
      issue(32,1);d.io.cancel.poke(true.B);commit(1);d.io.cancel.poke(false.B)
      issue(31,59*8+1);until(d.io.request.valid.peek().litToBoolean)
      val held=d.io.request.bits.peek().litValue;d.io.cancel.poke(true.B)
      for(_<-0 until 4){d.clock.step();d.io.request.valid.expect(true.B);assert(d.io.request.bits.peek().litValue==held)}
      d.io.request.ready.poke(true.B);d.clock.step();d.io.request.ready.poke(false.B)
      for(_<-0 until 4){d.clock.step();d.io.commit.valid.expect(false.B);d.io.busy.expect(true.B)}
      zero(d.io.result.bits);d.io.result.bits.warp.poke(3.U);d.io.result.bits.retired.poke(1.U)
      d.io.result.valid.poke(true.B);until(d.io.result.ready.peek().litToBoolean);d.clock.step();d.io.result.valid.poke(false.B)
      commit(2);d.io.cancel.poke(false.B)
      d.io.end.poke(true.B);d.clock.step();d.io.end.poke(false.B);d.io.enabled.expect(false.B)
      bind();issue(0,1);until(d.io.request.valid.peek().litToBoolean)
      d.io.request.ready.poke(true.B);d.clock.step();d.io.request.ready.poke(false.B)
      zero(d.io.result.bits);d.io.result.bits.warp.poke(4.U);d.io.result.bits.retired.poke(1.U)
      d.io.result.valid.poke(true.B);until(d.io.result.ready.peek().litToBoolean);d.clock.step();d.io.result.valid.poke(false.B);commit(1)
    }
  }
}
