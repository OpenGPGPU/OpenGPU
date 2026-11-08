package opengpu.graphics

import chisel3._
import chisel3.util._

/** ASTC 2D weight infill for one texel of one weight plane.
  *
  * Scales the texel coordinate into the stored weight grid with the exact
  * fixed-point rules, then bilinearly combines the four surrounding
  * unquantized weights. Weights are the 0..64 values produced by
  * `AstcWeightGridUnit`. Dual-plane application, endpoint decoding and
  * partitioning remain outside this unit.
  */
object AstcWeightInfillStatus {val ok=0;val invalid=1}

class AstcWeightInfillRequest extends Bundle {
  val tag=UInt(8.W);val blockWidth=UInt(4.W);val blockHeight=UInt(4.W)
  val gridX=UInt(4.W);val gridY=UInt(4.W);val weights=Vec(64,UInt(7.W))
  val x=UInt(4.W);val y=UInt(4.W)
}
class AstcWeightInfillResult extends Bundle {
  val tag=UInt(8.W);val status=UInt(2.W);val weight=UInt(7.W)
}

class AstcWeightInfillUnit extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new AstcWeightInfillRequest));val out=Decoupled(new AstcWeightInfillResult)
    val busy=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,respond=Value}
  private val phase=RegInit(State.idle);private val result=Reg(new AstcWeightInfillResult)
  io.in.ready:=phase===State.idle;io.out.valid:=phase===State.respond;io.out.bits:=result;io.busy:=phase=/=State.idle
  private val q=io.in.bits
  private val scale=Seq(342,256,205,171,146,128,114,102,93)
  private val scaleTable=VecInit(scale.map(_.U(10.W)))
  private def scaleFor(size: UInt): UInt=MuxLookup(size,0.U(10.W))((4 to 12).map(s=>s.U->scaleTable(s-4)))
  private val ds=scaleFor(q.blockWidth);private val dt=scaleFor(q.blockHeight)
  private val validShape=q.blockWidth>=4.U && q.blockWidth<=12.U && q.blockHeight>=4.U && q.blockHeight<=12.U &&
    q.gridX>=1.U && q.gridX<=q.blockWidth && q.gridY>=1.U && q.gridY<=q.blockHeight &&
    q.gridX*q.gridY<=64.U && q.x<q.blockWidth && q.y<q.blockHeight
  private val cs=ds*q.x;private val ct=dt*q.y
  private val gs=(cs*(q.gridX-1.U)+32.U)>>6;private val gt=(ct*(q.gridY-1.U)+32.U)>>6
  private val js=gs>>4;private val fs=gs(3,0);private val jt=gt>>4;private val ft=gt(3,0)
  private val bank=Wire(Vec(256,UInt(7.W)));bank:=0.U.asTypeOf(bank)
  for(i<-0 until 64)bank(i):=q.weights(i)
  private val v0=js+jt*q.gridX
  private val p00=bank(v0(7,0));private val p01=bank((v0+1.U)(7,0));private val p10=bank((v0+q.gridX)(7,0));private val p11=bank((v0+q.gridX+1.U)(7,0))
  private val w11=((fs*ft)+8.U)>>4;private val w10=ft-w11;private val w01=fs-w11;private val w00=16.U-fs-ft+w11
  private val sum=p00*w00+p01*w01+p10*w10+p11*w11
  private val weight=(sum+8.U)>>4
  when(io.in.fire){
    result:=0.U.asTypeOf(result);result.tag:=q.tag
    result.status:=Mux(validShape,AstcWeightInfillStatus.ok.U,AstcWeightInfillStatus.invalid.U)
    result.weight:=Mux(validShape,weight,0.U)
    phase:=State.respond
  }
  when(phase===State.respond && io.out.fire){phase:=State.idle}
}
