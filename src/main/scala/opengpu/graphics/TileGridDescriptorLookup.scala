package opengpu.graphics

import chisel3._
import chisel3.util._

class TileGridDescriptorResult extends Bundle {
  val status=UInt(PrimitiveBinStatus.width.W)
  val tile=UInt(32.W)
  val descriptor=new TileRenderPassDescriptor
}

/** Regenerate one row-major tile descriptor using bounded per-engine state.
  * Admission checks the attachment views; this block checks the grid shape
  * and tile ordinal, snapshots the binding, and divides the 16-bit ordinal
  * by the column count over sixteen cycles. No full-frame descriptor table
  * or wide combinational divider is required. Accepted requests, including
  * cancellations, retain their result until consumption.
  */
class TileGridDescriptorLookup extends Module {
  val io=IO(new Bundle {
    val grid=Input(new TileBinRenderPassDescriptor)
    val in=Flipped(Decoupled(UInt(32.W)))
    val out=Decoupled(new TileGridDescriptorResult)
    val cancel=Input(Bool())
    val busy=Output(Bool())
  })
  private val Seq(idle,shape,check,divide,coordinates,done)=Enum(6)
  private val phase=RegInit(idle)
  private val snapshot=Reg(new TileBinRenderPassDescriptor)
  private val tile=Reg(UInt(32.W))
  private val columns=Reg(UInt(17.W))
  private val count=Reg(UInt(34.W))
  private val endX=Reg(UInt(17.W)); private val endY=Reg(UInt(17.W))
  private val quotient=Reg(UInt(16.W)); private val remainder=Reg(UInt(18.W))
  private val remaining=Reg(UInt(5.W))
  private val cancelled=RegInit(false.B)
  private val result=Reg(new TileGridDescriptorResult)
  private val stop=cancelled || io.cancel
  private val edge=Mux(snapshot.attachments.tileLog2===3.U,8.U,16.U)
  io.in.ready:=phase===idle && !io.cancel
  io.out.valid:=phase===done; io.out.bits:=result
  io.busy:=phase=/=idle
  when(io.in.fire) {
    snapshot:=io.grid; tile:=io.in.bits; cancelled:=false.B
    result:=0.U.asTypeOf(result); result.tile:=io.in.bits; phase:=shape
  }
  when(io.cancel && phase=/=idle && phase=/=done) { cancelled:=true.B }
  private def finish(status: UInt): Unit = { result.status:=status; phase:=done }
  when(phase===shape) {
    val w=snapshot.extentW+& (edge-1.U)
    val h=snapshot.extentH+& (edge-1.U)
    val cols=Mux(snapshot.attachments.tileLog2===3.U,w>>3,w>>4)
    val rows=Mux(snapshot.attachments.tileLog2===3.U,h>>3,h>>4)
    columns:=cols; count:=cols*rows
    endX:=snapshot.attachments.originX+&snapshot.extentW
    endY:=snapshot.attachments.originY+&snapshot.extentH
    phase:=check
  }
  when(phase===check) {
    val legal=(snapshot.attachments.tileLog2===3.U || snapshot.attachments.tileLog2===4.U) &&
      snapshot.extentW>0.U && snapshot.extentH>0.U && columns>0.U &&
      count<=65536.U && count===snapshot.replay.bins.tileCount && tile<count && tile<65536.U &&
      endX<=snapshot.screenW && endY<=snapshot.screenH
    when(stop) { finish(PrimitiveBinStatus.cancelled.U) }
      .elsewhen(!legal) { finish(PrimitiveBinStatus.invalidDescriptor.U) }
      .otherwise { quotient:=tile(15,0); remainder:=0.U; remaining:=16.U; phase:=divide }
  }
  when(phase===divide) {
    val candidate=Cat(remainder(16,0),quotient(15))
    val subtract=candidate>=columns
    remainder:=Mux(subtract,candidate-columns,candidate)
    quotient:=Cat(quotient(14,0),subtract)
    remaining:=remaining-1.U
    when(stop) { finish(PrimitiveBinStatus.cancelled.U) }
      .elsewhen(remaining===1.U) { phase:=coordinates }
  }
  when(phase===coordinates) {
    val x=snapshot.attachments.originX+&Mux(snapshot.attachments.tileLog2===3.U,remainder<<3,remainder<<4)
    val y=snapshot.attachments.originY+&Mux(snapshot.attachments.tileLog2===3.U,quotient<<3,quotient<<4)
    result.descriptor:=snapshot.attachments
    result.descriptor.originX:=x; result.descriptor.originY:=y
    result.descriptor.tileWidth:=Mux(endX-x<edge,endX-x,edge)
    result.descriptor.tileHeight:=Mux(endY-y<edge,endY-y,edge)
    finish(Mux(stop,PrimitiveBinStatus.cancelled.U,0.U))
  }
  when(io.out.fire) { phase:=idle; cancelled:=false.B }
}
