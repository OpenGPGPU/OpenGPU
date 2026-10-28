package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.system.SharedCuShaderConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

/** Focused producer/EOF test. No CU model: every skipped draw must finish
  * without offering a shader launch, staging transfer or payload allocation.
  * Rendering equivalence is a separate composed-pipeline qualification.
  */
class EarlyDepthFragmentShaderStageSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  private val config=GpuConfig(lanes=4,warps=1)
  private val fragment=FragmentShaderConfig(varyings=1)
  private val graphics=GraphicsConfig(screenWidth=8,screenHeight=8)
  private class Driver(val d: FragmentShaderStage) {
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    var inputs=0
    def tick(): Unit={d.clock.step()}
    def until(condition: =>Boolean): Unit={var n=0;while(!condition && n<2000){tick();n+=1};assert(condition,"early fragment owner stalled")}
    def identity(id: GraphicsWorkIdentity): Unit={id.context.poke(7.U);id.pass.poke(8.U);id.generation.poke(9.U)}
    def begin(safe: Boolean=true,overrideDepth: Boolean=false,write: Boolean=true,function: Int=0,known: Boolean=true): Unit={
      inputs=0;val q=d.io.draw.bits;zero(q);q.version.poke(1.U);identity(q.identity);q.shaderPc.poke(0x1000.U)
      q.scratch.base.poke(0x100000.U);q.scratch.limit.poke(0x100400.U)
      q.scratch.allocationBase.poke(0x100000.U);q.scratch.allocationLimit.poke(0x100400.U)
      q.varyingSlots.poke(1.U);q.colorSlots.poke(1.U);q.colorMask.poke(1.U)
      q.sampleMode.poke(2.U);q.sampler.texWidth.poke(1.U);q.sampler.texHeight.poke(1.U)
      q.earlyDepthSafe.poke(safe.B);q.allowDepthOverride.poke(overrideDepth.B)
      val e=d.io.earlyDepth.get;e.enabled.poke(true.B);e.depthWrite.poke(write.B);e.function.poke(function.U)
      e.bounds.valid.poke(known.B);e.bounds.minimum.poke(0x400000.U);e.bounds.maximum.poke(0x800000.U)
      d.io.draw.valid.poke(true.B);until(d.io.draw.ready.peek().litToBoolean);tick();d.io.draw.valid.poke(false.B)
    }
    def send(last: Boolean=true): Unit={
      val q=d.io.in.bits;zero(q);identity(q.values.identity);q.values.sequence.poke(inputs.U)
      q.values.varyingSlots.poke(1.U);q.sampleMode.poke(2.U);q.frontFacing.poke(true.B)
      for(l<-0 until 4){val lane=q.values.lanes(l);val mask=Vector(15,3,8,0)(l)
        lane.x.poke((l%2).S);lane.y.poke((l/2).S);lane.coverageMask.poke(mask.U);lane.covered.poke((mask!=0).B)
        lane.windowDepth.poke(0x3f400000.U);lane.sampleDepthValid.poke(true.B)
        for(s<-0 until 4)lane.sampleDepth(s).poke((if((mask&(1<<s))!=0)BigInt(0x3f400000)else BigInt(0x7fc01234)).U)
        lane.perspectiveValid.poke(true.B);lane.reciprocalW.significand.poke((1<<23).U)
      }
      d.io.in.valid.poke(true.B);until(d.io.in.ready.peek().litToBoolean)
      if(last){identity(d.io.inputEnd.bits.identity);d.io.inputEnd.bits.quads.poke((inputs+1).U);d.io.inputEnd.valid.poke(true.B)}
      tick();d.io.in.valid.poke(false.B);d.io.inputEnd.valid.poke(false.B);inputs+=1
    }
    def complete(status: Int,skipped: Int): Unit={
      until(d.io.completion.valid.peek().litToBoolean);val q=d.io.completion.bits
      q.status.expect(status.U);q.inputQuads.expect(inputs.U);q.outputQuads.expect(skipped.U)
      q.earlyEligibleQuads.expect(skipped.U)
      q.earlyRejectedQuads.expect(skipped.U);q.batches.expect(0.U);q.shadedLanes.expect(0.U)
      q.stagingWriteBytes.expect(0.U);q.stagingReadBytes.expect(0.U)
      d.io.shared.launch.valid.expect(false.B);d.io.memoryRequest.valid.expect(false.B)
      d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B);d.io.cancel.poke(false.B)
      d.io.busy.expect(false.B)
    }
  }
  it should "skip certified occluded quads without CU work while retaining samples helpers EOF and held-export cancellation" in {
    simulate(new FragmentShaderStage(config,fragment,SharedCuShaderConfig(2),graphics,earlyDepthInput=true)){d=>
      val h=new Driver(d)
      h.begin()
      for(q<-0 until 3){h.send(last=q==2);h.until(d.io.out.valid.peek().litToBoolean)
        val out=d.io.out.bits;out.sequence.expect(q.U)
        for(l<-0 until 4){val lane=out.lanes(l);val mask=Vector(15,3,8,0)(l)
          lane.coverageMask.expect(mask.U);lane.helper.expect((mask==0).B);lane.discarded.expect(false.B);lane.depthOverride.expect(false.B)
          lane.depth.expect(0x3f400000.U);lane.colorMask.expect(0.U)
          for(s<-0 until 4)lane.sampleDepth(s).expect((if((mask&(1<<s))!=0)BigInt(0x3f400000)else BigInt(0)).U)
        }
        val held=out.peek().litValue
        for(_<-0 until 5){h.tick();d.io.memoryRequest.valid.expect(false.B);d.io.shared.launch.valid.expect(false.B);assert(out.peek().litValue==held)}
        d.io.out.ready.poke(true.B);h.tick();d.io.out.ready.poke(false.B)
      }
      h.complete(0,3)
      h.begin();h.send();h.until(d.io.out.valid.peek().litToBoolean);val held=d.io.out.bits.peek().litValue
      d.io.cancel.poke(true.B)
      for(_<-0 until 8){h.tick();d.io.out.valid.expect(true.B);assert(d.io.out.bits.peek().litValue==held);d.io.completion.valid.expect(false.B)}
      d.io.out.ready.poke(true.B);h.tick();d.io.out.ready.poke(false.B);h.complete(4,1)
      h.begin();h.send();h.until(d.io.out.valid.peek().litToBoolean);d.io.out.ready.poke(true.B);h.tick();d.io.out.ready.poke(false.B);h.complete(0,1)
    }
  }

  it should "retain late admission for unknown effects depth override unknown bounds and nonmonotonic writes" in {
    simulate(new FragmentShaderStage(config,fragment,SharedCuShaderConfig(2),graphics,earlyDepthInput=true)){d=>
      val h=new Driver(d)
      for((safe,overrideDepth,write,function,known)<-Vector((false,false,true,0,true),(true,true,true,0,true),
          (true,false,true,0,false),(true,false,true,5,true))){
        h.begin(safe,overrideDepth,write,function,known);h.send();h.until(d.io.memoryRequest.valid.peek().litToBoolean)
        d.io.out.valid.expect(false.B)
        val held=d.io.memoryRequest.bits.peek().litValue;d.io.cancel.poke(true.B)
        for(_<-0 until 3){h.tick();d.io.memoryRequest.valid.expect(true.B);assert(d.io.memoryRequest.bits.peek().litValue==held)}
        val tag=d.io.memoryRequest.bits.transactionId.peek().litValue
        d.io.memoryRequest.ready.poke(true.B);h.tick();d.io.memoryRequest.ready.poke(false.B)
        zero(d.io.memoryResponse.bits);d.io.memoryResponse.bits.transactionId.poke(tag.U);d.io.memoryResponse.valid.poke(true.B)
        h.until(d.io.memoryResponse.ready.peek().litToBoolean);h.tick();d.io.memoryResponse.valid.poke(false.B)
        h.until(d.io.completion.valid.peek().litToBoolean)
        d.io.completion.bits.earlyEligibleQuads.expect(0.U);d.io.completion.bits.earlyRejectedQuads.expect(0.U)
        d.io.completion.bits.status.expect(4.U);d.io.shared.launch.valid.expect(false.B)
        d.io.completion.ready.poke(true.B);h.tick();d.io.completion.ready.poke(false.B);d.io.cancel.poke(false.B)
      }
    }
  }
}
