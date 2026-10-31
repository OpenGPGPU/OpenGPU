package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TessellationIsolineRendererHandoffSpec extends AnyFlatSpec {
  it should "drain evaluation and map primitives with ordered identity and fault handling" in {
    val p=VertexShaderStageConfig(varyings=1)
    simulate(new TessellationIsolineRendererHandoff(p)) { d =>
      d.io.begin.valid.poke(false.B); d.io.request.ready.poke(false.B); d.io.response.valid.poke(false.B)
      d.io.out.ready.poke(false.B); d.io.completion.ready.poke(false.B); d.io.cancel.poke(false.B)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      def run(mode: Int): Unit = {
        val b=d.io.begin.bits
        b.tessellation.taskId.poke(42.U); b.tessellation.spacing.poke(TessellationSpacing.equal.U)
        b.tessellation.outer(0).poke(0x3f800000L.U); b.tessellation.outer(1).poke(0x40000000L.U)
        b.tessellation.pointMode.poke((mode==5).B)
        b.identity.context.poke(1.U); b.identity.pass.poke(2.U); b.identity.generation.poke(3.U)
        b.firstSequence.poke((if(mode==2) (BigInt(1)<<64)-1 else BigInt(100)).U)
        b.firstPrimitiveId.poke((if(mode==6) BigInt("ffffffff",16) else BigInt(20)).U)
        b.instanceId.poke(7.U); b.baseInstance.poke(9.U)
        b.outputComponents.poke(8.U); b.varyingSlots.poke(1.U); b.flatMask.poke(1.U)
        for (i <- 0 until 8) { b.componentSources(i).valid.poke(true.B); b.componentSources(i).bits.poke((if(mode==1 && i==2)8 else i).U) }
        b.pointSizeSource.valid.poke(false.B); b.pointSizeSource.bits.poke(0.U)
        d.io.begin.valid.poke(true.B); d.io.begin.ready.expect(true.B); d.clock.step(); d.io.begin.valid.poke(false.B)
        var pending: Option[(BigInt,BigInt,BigInt)] = None; var failed=false; var cancelled=false
        var published=0; var cycles=0
        while (!d.io.completion.valid.peek().litToBoolean && cycles<20000) {
          val reqValid=d.io.request.valid.peek().litToBoolean
          val pulseCancel=mode==4 && reqValid && !cancelled
          if(pulseCancel) cancelled=true
          d.io.cancel.poke(pulseCancel.B)
          val reqReady=cycles%3 != 0; d.io.request.ready.poke(reqReady.B)
          val outReady=cycles%5 != 0; d.io.out.ready.poke(outReady.B)
          d.io.response.valid.poke(pending.nonEmpty.B)
          pending.foreach { case(t,s,v) =>
            d.io.response.bits.taskId.poke(t.U); d.io.response.bits.sequence.poke(s.U); d.io.response.bits.vertex.poke(v.U)
            d.io.response.bits.failed.poke((mode==3 && !failed).B)
            for(i <- 0 until 64) d.io.response.bits.components(i).poke((s*1000+v*100+i).U)
          }
          val respFire=pending.nonEmpty && d.io.response.ready.peek().litToBoolean
          if(respFire && mode==3 && !failed) failed=true
          val next=if(reqValid && reqReady) Some((d.io.request.bits.taskId.peek().litValue,
            d.io.request.bits.sequence.peek().litValue,d.io.request.bits.vertex.peek().litValue)) else None
          if(d.io.out.valid.peek().litToBoolean && outReady) {
            val o=d.io.out.bits; val seq=o.primitiveId.peek().litValue-(if(mode==6) BigInt("ffffffff",16) else BigInt(20))
            o.identity.context.expect(1.U); o.instanceId.expect(7.U); o.baseInstance.expect(9.U)
            o.sequence.expect(((if(mode==2) (BigInt(1)<<64)-1 else BigInt(100))+seq).U)
            o.vertexCount.expect((if(mode==5)1 else 2).U)
            o.topology.expect((if(mode==5)PrimitiveTopology.points else PrimitiveTopology.lines).U)
            for(i <- 0 until 4) {
              o.vertices(0).position(i).expect((seq*1000+i).U)
              if(mode!=5)o.vertices(1).position(i).expect((seq*1000+100+i).U)
            }
            o.vertices(0).varyings(0)(0).expect((seq*1000+(if(mode==5)4 else 104)).U)
            o.vertices(0).varyings(0)(1).expect((seq*1000+5).U)
            published+=1
          }
          d.clock.step(); if(respFire)pending=None
          if(next.nonEmpty){assert(pending.isEmpty);pending=next}; cycles+=1
        }
        assert(cycles<20000 && pending.isEmpty)
        d.io.completion.bits.taskId.expect(42.U); d.io.completion.bits.published.expect(published.U)
        d.io.completion.bits.status.expect((if(mode==0 || mode==5)0 else if(mode==3)2 else if(mode==4)4 else 3).U)
        if(mode==0){d.io.completion.bits.generated.expect(2.U); d.io.completion.bits.evaluated.expect(2.U);assert(published==2)}
        if(mode==5){d.io.completion.bits.generated.expect(3.U);d.io.completion.bits.evaluated.expect(3.U);assert(published==3)}
        if(mode==2 || mode==6)assert(published==1)
        if(mode==3)d.io.completion.bits.evaluationFault.expect(2.U)
        val held=d.io.completion.bits.peek().litValue; d.clock.step(2); assert(d.io.completion.bits.peek().litValue==held)
        d.io.completion.ready.poke(true.B); d.clock.step(); d.io.completion.ready.poke(false.B)
        d.io.cancel.poke(false.B); d.io.busy.expect(false.B)
      }
      for(mode <- 0 to 6)run(mode)
    }
  }
}
