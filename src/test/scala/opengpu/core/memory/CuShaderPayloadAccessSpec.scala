package opengpu.core.memory

import chisel3._
import opengpu.config.GpuConfig
import opengpu.graphics.{NativeBinnedGraphicsConfig,ShaderPayloadConfig}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class CuShaderPayloadAccessSpec extends AnyFlatSpec {
  it should "overlap resident vector and scalar line ownership with reordered payload replies and held completions" in {
    val gpu=GpuConfig(lanes=4,warps=2)
    val p=ShaderPayloadConfig(capacityBytes=4096,maxPayloadBytes=1024,owners=2,ports=2)
    simulate(new CuShaderPayloadAccess(gpu,p)){d=>
      d.io.vector.valid.poke(false.B);d.io.vector.bits.poke(0.U.asTypeOf(d.io.vector.bits));d.io.vectorResult.ready.poke(false.B)
      d.io.line.valid.poke(false.B);d.io.line.bits.poke(0.U.asTypeOf(d.io.line.bits));d.io.lineResult.ready.poke(false.B)
      d.io.vectorFallbackRequest.ready.poke(false.B);d.io.vectorFallbackResponse.valid.poke(false.B)
      d.io.vectorFallbackResponse.bits.poke(0.U.asTypeOf(d.io.vectorFallbackResponse.bits))
      d.io.memoryRequest.ready.poke(false.B);d.io.memoryResponse.valid.poke(false.B)
      d.io.memoryResponse.bits.poke(0.U.asTypeOf(d.io.memoryResponse.bits))
      d.io.payload.request.ready.poke(false.B);d.io.payload.response.valid.poke(false.B)
      d.io.payload.response.bits.poke(0.U.asTypeOf(d.io.payload.response.bits));d.io.payload.cancel.poke(false.B)
      val b=d.io.payload.task.bits;d.io.payload.task.valid.poke(true.B)
      b.base.poke(0x8000.U);b.bytes.poke(256.U);b.handle.poke(2.U);b.generation.poke(17.U);b.context.poke(23.U);b.owner.poke(1.U)
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val v=d.io.vector;v.bits.warpId.poke(1.U);v.bits.elementSize.poke(2.U);v.bits.laneMask.poke(15.U)
      for(l<-0 until 4)v.bits.addresses(l).poke((0x8040+4*l).U)
      val s=d.io.line;s.bits.lineAddress.poke(0x8000.U);s.bits.byteMask.poke(((BigInt(1)<<64)-1).U)
      v.valid.poke(true.B);s.valid.poke(true.B);v.ready.expect(true.B);s.ready.expect(true.B)
      d.clock.step();v.valid.poke(false.B);s.valid.poke(false.B)
      var waited=0
      while(!d.io.payload.request.valid.peek().litToBoolean && waited<10){d.clock.step();waited+=1}
      d.io.payload.request.valid.expect(true.B);val held=d.io.payload.request.bits.peek().litValue
      d.clock.step(4);assert(d.io.payload.request.bits.peek().litValue==held)
      d.io.payload.drained.expect(false.B)
      val requests=scala.collection.mutable.Map.empty[Int,BigInt]
      d.io.payload.request.ready.poke(true.B)
      while(requests.size<2 && waited<30){
        val q=d.io.payload.request
        if(q.valid.peek().litToBoolean){val id=q.bits.tag.peek().litValue.toInt
          assert(!requests.contains(id));requests(id)=q.bits.offset.peek().litValue
          q.bits.handle.expect(2.U);q.bits.generation.expect(17.U);q.bits.context.expect(23.U);q.bits.owner.expect(1.U)}
        d.clock.step();waited+=1
      }
      d.io.payload.request.ready.poke(false.B);assert(requests.toMap==Map(0->BigInt(0),1->BigInt(64)))
      val values=Map(0->((BigInt(1)<<511)|BigInt("deadbeef",16)),
        1->(0 until 16).foldLeft(BigInt(0))((x,l)=>x|(BigInt(123+l)<<(32*l))))
      for(id<-Seq(0,1)){
        val r=d.io.payload.response;r.bits.handle.poke(2.U);r.bits.generation.poke(17.U);r.bits.context.poke(23.U)
        r.bits.tag.poke(id.U);r.bits.data.poke(values(id).U);r.valid.poke(true.B);r.ready.expect(true.B)
        d.clock.step();r.valid.poke(false.B)
      }
      d.clock.step(3);d.io.lineResult.valid.expect(true.B);d.io.vectorResult.valid.expect(true.B)
      d.io.lineResult.bits.fault.expect(false.B);d.io.lineResult.bits.readData.expect(values(0).U)
      d.io.vectorResult.bits.faultMask.expect(0.U)
      for(l<-0 until 4)d.io.vectorResult.bits.readData(l).expect((123+l).U)
      d.io.payload.drained.expect(false.B);d.clock.step(3)
      d.io.lineResult.ready.poke(true.B);d.io.vectorResult.ready.poke(true.B);d.clock.step()
      d.io.payload.drained.expect(true.B)
      d.io.vectorFallbackRequest.valid.expect(false.B);d.io.memoryRequest.valid.expect(false.B)
    }
  }
  it should "carry a vector cancel pulse through delayed general-line admission without memory side effects" in {
    val gpu=GpuConfig(lanes=4,warps=2)
    val p=ShaderPayloadConfig(capacityBytes=4096,maxPayloadBytes=1024,owners=2,ports=2)
    simulate(new CuShaderPayloadAccess(gpu,p)){d=>
      d.io.vector.valid.poke(false.B);d.io.vector.bits.poke(0.U.asTypeOf(d.io.vector.bits));d.io.vectorResult.ready.poke(false.B)
      d.io.line.valid.poke(false.B);d.io.line.bits.poke(0.U.asTypeOf(d.io.line.bits));d.io.lineResult.ready.poke(false.B)
      d.io.vectorFallbackRequest.ready.poke(false.B);d.io.vectorFallbackResponse.valid.poke(false.B)
      d.io.vectorFallbackResponse.bits.poke(0.U.asTypeOf(d.io.vectorFallbackResponse.bits))
      d.io.memoryRequest.ready.poke(true.B);d.io.memoryResponse.valid.poke(false.B)
      d.io.memoryResponse.bits.poke(0.U.asTypeOf(d.io.memoryResponse.bits))
      d.io.payload.request.ready.poke(true.B);d.io.payload.response.valid.poke(false.B)
      d.io.payload.response.bits.poke(0.U.asTypeOf(d.io.payload.response.bits));d.io.payload.cancel.poke(false.B)
      val b=d.io.payload.task.bits;d.io.payload.task.valid.poke(true.B)
      b.base.poke(0x8000.U);b.bytes.poke(256.U);b.handle.poke(2.U);b.generation.poke(17.U);b.context.poke(23.U);b.owner.poke(1.U)
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val v=d.io.vector;v.bits.warpId.poke(1.U);v.bits.elementSize.poke(1.U);v.bits.laneMask.poke(5.U);v.bits.isStore.poke(true.B)
      for(l<-0 until 4)v.bits.addresses(l).poke((0x8000+2*l).U)
      v.valid.poke(true.B);v.ready.expect(true.B);d.clock.step();v.valid.poke(false.B);d.clock.step()
      d.io.vectorFallbackRequest.valid.expect(true.B);d.io.vectorFallbackRequest.ready.poke(true.B)
      d.clock.step();d.io.vectorFallbackRequest.ready.poke(false.B)
      d.io.payload.cancel.poke(true.B);d.clock.step();d.io.payload.cancel.poke(false.B);d.clock.step(3)
      val s=d.io.line;s.bits.lineAddress.poke(0x8000.U);s.bits.byteMask.poke(0x33.U);s.bits.isStore.poke(true.B)
      s.valid.poke(true.B);s.ready.expect(true.B);d.clock.step();s.valid.poke(false.B)
      var clocks=0
      while(!d.io.lineResult.valid.peek().litToBoolean && clocks<12){
        d.io.payload.request.valid.expect(false.B);d.io.memoryRequest.valid.expect(false.B)
        d.clock.step();clocks+=1
      }
      d.io.lineResult.valid.expect(true.B);d.io.lineResult.bits.fault.expect(true.B)
      d.io.lineResult.ready.poke(true.B);d.clock.step();d.io.lineResult.ready.poke(false.B)
      d.io.vectorFallbackResponse.valid.poke(true.B);d.io.vectorFallbackResponse.bits.faultMask.poke(5.U)
      d.io.vectorResult.valid.expect(true.B);d.io.vectorResult.bits.faultMask.expect(5.U)
      d.io.payload.drained.expect(false.B);d.clock.step(3)
      d.io.vectorResult.ready.poke(true.B);d.clock.step();d.io.vectorFallbackResponse.valid.poke(false.B)
      d.io.payload.drained.expect(true.B)
    }
  }
}

object EmitCuShaderPayloadAccessTiming {
  def main(args: Array[String]): Unit={
    require(args.length==1)
    circt.stage.ChiselStage.emitSystemVerilogFile(new CuShaderPayloadAccess(
      GpuConfig(l2Sets=512,l2StoreEntries=8),NativeBinnedGraphicsConfig.fourEngine.payload.get),
      Array("--target-dir",args(0)),Array("--disable-all-randomization","--split-verilog",
        "--lowering-options=disallowLocalVariables,disallowPackedArrays"))
  }
}
