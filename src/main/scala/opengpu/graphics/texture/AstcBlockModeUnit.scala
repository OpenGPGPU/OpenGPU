package opengpu.graphics

import chisel3._
import chisel3.util._

/** ASTC LDR 2D block-mode front end.
  *
  * Decodes the 11-bit block mode into the weight grid, dual-plane flag,
  * quantization method and integer-sequence bit count, and classifies
  * void-extent blocks. Reserved modes, grids larger than the footprint,
  * weight counts above 64, sequences outside 24..96 bits and dual-plane
  * blocks with four partitions are rejected. Reserved bit patterns follow
  * the reference decoding rules; endpoint, weight and partition decoding
  * remain outside this unit.
  */
object AstcBlockModeStatus {val valid=0;val invalid=1}

class AstcBlockModeRequest extends Bundle {
  val tag=UInt(8.W);val block=UInt(128.W);val blockWidth=UInt(4.W);val blockHeight=UInt(4.W)
}
class AstcBlockModeResult extends Bundle {
  val tag=UInt(8.W);val status=UInt(2.W);val voidExtent=Bool()
  val gridX=UInt(4.W);val gridY=UInt(4.W);val dualPlane=Bool()
  val quantMode=UInt(4.W);val weightBits=UInt(7.W);val partitions=UInt(3.W)
}

class AstcBlockModeUnit extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new AstcBlockModeRequest));val out=Decoupled(new AstcBlockModeResult)
    val busy=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,respond=Value}
  private val phase=RegInit(State.idle);private val result=Reg(new AstcBlockModeResult)
  io.in.ready:=phase===State.idle;io.out.valid:=phase===State.respond;io.out.bits:=result;io.busy:=phase=/=State.idle
  private val block=io.in.bits.block
  private val bm=block(10,0)
  private val base=bm(4) | (Mux(bm(1,0)=/=0.U,bm(1,0),bm(3,2))<<1)
  private val a=bm(6,5);private val b=bm(8,7)
  private val firstFamily=bm(1,0)=/=0.U
  private val family=(bm(3,2))
  private val lowFamilyValid=family=/=0.U
  private val gxFirst=MuxLookup(family,0.U(4.W))(Seq(
    0.U->(b+&4.U),1.U->(b+&8.U),2.U->(a+&2.U),3.U->Mux(bm(8),b(0)+2.U,a+&2.U)))
  private val gyFirst=MuxLookup(family,0.U(4.W))(Seq(
    0.U->(a+&2.U),1.U->(a+&2.U),2.U->(b+&8.U),3.U->Mux(bm(8),a+&2.U,b(0)+&6.U)))
  private val gxSecond=MuxLookup(bm(8,7),0.U(4.W))(Seq(
    0.U->12.U,1.U->(a+&2.U),2.U->(a+&6.U),3.U->MuxLookup(a,0.U(4.W))(Seq(0.U->6.U,1.U->10.U))))
  private val gySecond=MuxLookup(bm(8,7),0.U(4.W))(Seq(
    0.U->(a+&2.U),1.U->12.U,2.U->(bm(10,9)+&6.U),3.U->MuxLookup(a,0.U(4.W))(Seq(0.U->10.U,1.U->6.U))))
  private val secondReserved=bm(8,7)===3.U && a>1.U
  private val gx=Mux(firstFamily,gxFirst,gxSecond)
  private val gy=Mux(firstFamily,gyFirst,gySecond)
  private val secondCase= !firstFamily && bm(8,7)===2.U
  private val dualPlane=bm(10) && !secondCase
  private val partitions=block(12,11)+&1.U
  private val highPrecision=Mux(secondCase,false.B,bm(9))
  private val quant=(base.pad(4)-2.U(4.W)+&Mux(highPrecision,6.U(4.W),0.U(4.W)))(3,0)
  private val scale=MuxLookup(quant,0.U(6.W))(Seq(
    0.U->1.U,1.U->8.U,2.U->2.U,3.U->7.U,4.U->13.U,5.U->3.U,
    6.U->10.U,7.U->18.U,8.U->4.U,9.U->13.U,10.U->23.U,11.U->5.U))
  private val divisor=MuxLookup(quant,1.U(6.W))(Seq(
    0.U->1.U,1.U->5.U,2.U->1.U,3.U->3.U,4.U->5.U,5.U->1.U,
    6.U->3.U,7.U->5.U,8.U->1.U,9.U->3.U,10.U->5.U,11.U->1.U))
  private val count=gx*gy*Mux(dualPlane,2.U(5.W),1.U(5.W))
  private val weightBits=((scale*count)+(divisor-1.U))/divisor
  private val voidExtent=bm(8,0)==="h1fc".U
  private val gridLegal=gx<=io.in.bits.blockWidth && gy<=io.in.bits.blockHeight && gx=/=0.U && gy=/=0.U
  private val countLegal=count<=64.U && weightBits>=24.U && weightBits<=96.U
  private val reserved= !firstFamily && (!lowFamilyValid || secondReserved)
  private val legal=gridLegal && countLegal && !reserved && !(dualPlane && partitions===4.U)
  when(io.in.fire){
    result:=0.U.asTypeOf(result);result.tag:=io.in.bits.tag
    result.voidExtent:=voidExtent
    result.status:=Mux(voidExtent || legal,AstcBlockModeStatus.valid.U,AstcBlockModeStatus.invalid.U)
    result.gridX:=Mux(voidExtent,0.U,Mux(legal,gx,0.U))
    result.gridY:=Mux(voidExtent,0.U,Mux(legal,gy,0.U))
    result.dualPlane:=Mux(voidExtent,false.B,Mux(legal,dualPlane,false.B))
    result.quantMode:=Mux(voidExtent,0.U,Mux(legal,quant,0.U))
    result.weightBits:=Mux(voidExtent,0.U,Mux(legal,weightBits,0.U))
    result.partitions:=Mux(voidExtent,1.U(3.W),Mux(legal,partitions,0.U(3.W)))
    phase:=State.respond
  }
  when(phase===State.respond && io.out.fire){phase:=State.idle}
}
