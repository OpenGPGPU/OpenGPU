package opengpu.core.frontend.decode

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class UniformExecutionAvailabilityDecodeSpec extends AnyFlatSpec {
  for((texture,native,uniform,task,texturePermission,nativePermission,uniformPermission)<-Seq(
      (true,true,false,true,true,true,true),(false,true,true,true,true,true,true),
      (true,false,true,true,true,true,true),(true,true,true,true,false,true,true),
      (true,true,true,true,true,false,true),(true,true,true,true,true,true,false),
      (true,true,true,true,true,true,true),(true,true,true,false,false,false,false))){
    it should s"retain scalar/vector uniform policy static=$texture/$native/$uniform task=$task permission=$texturePermission/$nativePermission/$uniformPermission" in {
      simulate(new DecodePipe(GpuConfig(),textureAvailable=texture,taskTextureAvailability=task,
          nativeResourcesAvailable=native,nativeUniformsAvailable=uniform)){d=>
        UniformDecodeTest.zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
        val allowed=texture && native && uniform && (!task || texturePermission && nativePermission && uniformPermission)
        for((word,vector,reserved)<-Seq((BigInt("0e20838b",16),false,false),
            (BigInt("1c2083ab",16),true,false),(BigInt("1e2083ab",16),true,false),
            (BigInt("0e20938b",16),false,true),(BigInt("1e2093ab",16),true,true))){
          d.io.textureEnabled.foreach(_.poke(texturePermission.B));d.io.nativeResourcesEnabled.foreach(_.poke(nativePermission.B))
          d.io.uniformResourcesEnabled.foreach(_.poke(uniformPermission.B))
          val accepted=allowed && !reserved
          d.io.in.bits.instruction.poke(word.U);d.io.in.bits.pc.poke(0x1800.U);d.io.in.bits.warpId.poke(7.U);d.io.in.bits.activeMask.poke(0xa57f.U)
          d.io.in.valid.poke(true.B);d.io.in.ready.expect(true.B);d.clock.step();d.io.in.valid.poke(false.B)
          d.io.scalarOut.valid.expect((!accepted || !vector).B);d.io.vectorOut.valid.expect((accepted && vector).B);d.io.fpuOut.valid.expect(false.B)
          if(accepted && vector){val q=d.io.vectorOut.bits;q.decoded.readsVs1.expect(true.B);q.decoded.readsVs2.expect(true.B);q.decoded.writesVd.expect(true.B)}
          else {d.io.scalarOut.bits.illegalInstruction.expect((!accepted).B)
            if(accepted){d.io.scalarOut.bits.decoded.useRs1.expect(true.B);d.io.scalarOut.bits.decoded.useRs2.expect(true.B);d.io.scalarOut.bits.decoded.texSample.expect(true.B)}}
          val packet=if(accepted && vector)d.io.vectorOut.bits else d.io.scalarOut.bits
          val held=packet.peek().litValue
          d.io.textureEnabled.foreach(_.poke((!texturePermission).B));d.io.nativeResourcesEnabled.foreach(_.poke((!nativePermission).B))
          d.io.uniformResourcesEnabled.foreach(_.poke((!uniformPermission).B));d.io.in.bits.instruction.poke(0xffffffffL.U)
          for(_<-0 until 5){d.io.in.ready.expect(false.B);assert(packet.peek().litValue==held);d.clock.step()}
          if(accepted && vector)d.io.vectorOut.ready.poke(true.B)else d.io.scalarOut.ready.poke(true.B)
          d.clock.step();d.io.vectorOut.ready.poke(false.B);d.io.scalarOut.ready.poke(false.B)
        }
      }
    }
  }
}

private object UniformDecodeTest {
  def zero(data: Data): Unit=data match {
    case r: Record=>r.elements.values.foreach(zero)
    case v: Vec[_]=>v.foreach(zero)
    case b: Bool if chisel3.reflect.DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u: UInt if chisel3.reflect.DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case _=>()
  }
}
