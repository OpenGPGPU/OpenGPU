package opengpu.core.frontend.decode

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TextureExecutionAvailabilityDecodeSpec extends AnyFlatSpec {
  private val scalar = Seq("0200050b", "0400050b", "0600000b", "0800050b", "0a00050b", "0c00050b", "0c00150b")
  private val vector = Seq("0600052b", "0a00052b", "0e00002b", "1200052b", "1600052b", "1a00052b", "1a00152b")
  for ((available,taskPolicy,permission) <- Seq((false,false,false),(true,false,true),(true,true,false),(true,true,true))) {
    it should s"retain every texture family's packet with static=$available taskPolicy=$taskPolicy permission=$permission" in {
      simulate(new DecodePipe(GpuConfig(), textureAvailable=available,taskTextureAvailability=taskPolicy)) { d =>
        d.io.in.valid.poke(false.B); d.io.in.bits.poke(0.U.asTypeOf(d.io.in.bits))
        d.io.scalarOut.ready.poke(false.B); d.io.vectorOut.ready.poke(false.B); d.io.fpuOut.ready.poke(false.B)
        d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
        val cases = scalar.map((_,0,true)) ++ vector.map((_,2,true)) ++
          Seq(("00100513",0,false),("0000000b",0,false),("0104f057",2,false),("3210052b",2,false),("00000553",1,false))
        for (((hex,route,texture),index) <- cases.zipWithIndex) {
          d.io.textureEnabled.foreach(_.poke(permission.B))
          val denied=texture && !(available && (!taskPolicy || permission)); val selected=if(denied) 0 else route
          val word=BigInt(hex,16); val pc=0x1000+4*index; val warp=index%8
          d.io.in.bits.instruction.poke(word.U); d.io.in.bits.pc.poke(pc.U)
          d.io.in.bits.warpId.poke(warp.U); d.io.in.bits.activeMask.poke(0xa57f.U)
          d.io.in.valid.poke(true.B); d.io.in.ready.expect(true.B); d.clock.step(); d.io.in.valid.poke(false.B)
          d.io.scalarOut.valid.expect((selected==0).B); d.io.fpuOut.valid.expect((selected==1).B); d.io.vectorOut.valid.expect((selected==2).B)
          if(selected==0) {
            d.io.scalarOut.bits.illegalInstruction.expect(denied.B)
            if(denied) d.io.scalarOut.bits.executionType.expect(ExecutionType.illegal)
          }
          def hold(valid: Bool,bits: Data,instruction: UInt,packetPC: UInt,packetWarp: UInt,mask: UInt): Unit = {
            instruction.expect(word.U); packetPC.expect(pc.U); packetWarp.expect(warp.U); mask.expect(0xa57f.U)
            val saved=bits.peek().litValue
            d.io.textureEnabled.foreach(_.poke((!permission).B))
            d.io.in.bits.instruction.poke(0xffffffffL.U); d.io.in.bits.pc.poke(0xdead.U)
            for(_ <- 0 until 7) {
              valid.expect(true.B); d.io.in.ready.expect(false.B); assert(bits.peek().litValue==saved); d.clock.step()
            }
          }
          if(selected==0) { val q=d.io.scalarOut.bits; hold(d.io.scalarOut.valid,q,q.instruction,q.pc,q.warpId,q.activeMask); d.io.scalarOut.ready.poke(true.B) }
          if(selected==1) { val q=d.io.fpuOut.bits; hold(d.io.fpuOut.valid,q,q.instruction,q.pc,q.warpId,q.activeMask); d.io.fpuOut.ready.poke(true.B) }
          if(selected==2) { val q=d.io.vectorOut.bits; hold(d.io.vectorOut.valid,q,q.instruction,q.pc,q.warpId,q.activeMask); d.io.vectorOut.ready.poke(true.B) }
          d.clock.step(); d.io.scalarOut.ready.poke(false.B); d.io.fpuOut.ready.poke(false.B); d.io.vectorOut.ready.poke(false.B)
          d.io.scalarOut.valid.expect(false.B); d.io.fpuOut.valid.expect(false.B); d.io.vectorOut.valid.expect(false.B)
        }
        info(s"TEXTURE_EXECUTION_DECODE available=$available taskPolicy=$taskPolicy permission=$permission scalarFamilies7=true vectorFamilies7=true default16lane8warp=true heldPacketsAndPolicyChanges=true ordinaryScalarVectorFPBarrierDerivativePreserved=true")
      }
    }
  }
}
