package opengpu.graphics

import chisel3._
import chisel3.util._

/** Bounded ASTC LDR block decoder for void-extent (single-color) blocks.
  *
  * A 2D void-extent block has block-mode bits [8:0] equal to0x1FC, bits10/11
  * set, a dynamic-range bit9 of zero for LDR, and UNORM16 A/B/G/R components
  * in bits127:64. This owner decodes those constant-color blocks. Non-void
  * blocks are reported as unsupported and HDR or reserved-bit violations as
  * illegal; both return the ASTC error color (opaque magenta). Weight, partition
  * and endpoint decoding for general LDR blocks remains open.
  */
object AstcLdrBlockStatus {val constant=0;val unsupported=1;val illegal=2}

class AstcLdrBlockRequest extends Bundle {val block=UInt(128.W);val tag=UInt(8.W)}
class AstcLdrBlockResult extends Bundle {
  val tag=UInt(8.W);val status=UInt(2.W);val voidExtent=Bool();val rgba=Vec(4,UInt(32.W))
}

class AstcLdrBlockDecoder extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new AstcLdrBlockRequest));val out=Decoupled(new AstcLdrBlockResult)
    val busy=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,respond=Value}
  private val phase=RegInit(State.idle);private val result=Reg(new AstcLdrBlockResult)
  io.in.ready:=phase===State.idle;io.out.valid:=phase===State.respond;io.out.bits:=result;io.busy:=phase=/=State.idle
  private val block=io.in.bits.block
  private def unorm16(n: UInt): UInt={
    val repeated=(Fill(3,n))(47,8)
    val leading=PriorityEncoder(Reverse(repeated))
    val normalized=(repeated<<leading)(39,0)
    val rounded=normalized(39,16)+&normalized(15)
    val exponent=(126.U(8.W)-leading+rounded(24))(7,0)
    Mux(n===0.U,0.U,Cat(0.U(1.W),exponent,rounded(22,0)))
  }
  private val one="h3f800000".U(32.W)
  private val error=VecInit(one,0.U(32.W),one,one)
  private val constant=VecInit(unorm16(block(79,64)),unorm16(block(95,80)),unorm16(block(111,96)),unorm16(block(127,112)))
  private val voidExtent=block(8,0)==="h1fc".U
  private val ldr= !block(9)
  private val reserved=block(11)&&block(10)
  private val legal=voidExtent && ldr && reserved
  when(io.in.fire){
    result:=0.U.asTypeOf(result);result.tag:=io.in.bits.tag
    result.status:=Mux(legal,AstcLdrBlockStatus.constant.U,Mux(voidExtent,AstcLdrBlockStatus.illegal.U,AstcLdrBlockStatus.unsupported.U))
    result.voidExtent:=legal
    result.rgba:=Mux(legal,constant,error)
    phase:=State.respond
  }
  when(phase===State.respond && io.out.fire){phase:=State.idle}
}
