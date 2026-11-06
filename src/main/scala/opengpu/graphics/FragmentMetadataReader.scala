package opengpu.graphics

import chisel3._
import chisel3.util._

/** Private metadata storage, including the native array's minimum 256 rows. */
private[graphics] case class FragmentMetadataLayout(metadataBits: Int,quads: Int,wordBits: Int) {
  require(metadataBits>0 && quads>0 && Set(128,256)(wordBits))
  val rows=(metadataBits+wordBits-1)/wordBits
  val depth=1<<log2Ceil(quads*rows)
  val banks=(depth+255)/256
  val nativeMacros=banks*(wordBits/64)
  def word(data: UInt,row: UInt): UInt=
    (data.pad(rows*wordBits)>>(row<<log2Ceil(wordBits)))(wordBits-1,0)
}
private[graphics] object FragmentMetadataLayout {
  def select(metadataBits: Int,quads: Int): FragmentMetadataLayout={
    val narrow=FragmentMetadataLayout(metadataBits,quads,128)
    val wide=FragmentMetadataLayout(metadataBits,quads,256)
    if(wide.nativeMacros<=narrow.nativeMacros)wide else narrow
  }
}

/** Prefetch one quad through a private synchronous metadata SRAM port.
  * Consuming the buffered quad can start its successor on the same clock.
  * Uses one quad register buffer; the caller retains the existing SRAM.
  */
class FragmentMetadataReader(rows: Int,quads: Int,wordBits: Int=128) extends Module {
  require(rows>0 && quads>0 && Set(128,256)(wordBits))
  private val quadWidth=math.max(1,log2Ceil(quads))
  private val rowWidth=math.max(1,log2Ceil(rows))
  val io=IO(new Bundle {
    val start=Flipped(Decoupled(UInt(quadWidth.W)))
    val read=Output(Valid(UInt(math.max(1,log2Ceil(rows*quads)).W)))
    val readData=Input(UInt(wordBits.W))
    val out=Decoupled(new Bundle {
      val quad=UInt(quadWidth.W);val data=Vec(rows,UInt(wordBits.W))
    })
    val cancel=Input(Bool())
  })
  private val reading=RegInit(false.B);private val valid=RegInit(false.B)
  private val quad=Reg(UInt(quadWidth.W));private val row=Reg(UInt(rowWidth.W))
  private val data=Reg(Vec(rows,UInt(wordBits.W)))
  io.read.valid:=reading && !io.cancel
  io.read.bits:=quad*rows.U+row
  private val pending=RegNext(io.read.valid,false.B)
  private val replyRow=RegEnable(row,io.read.valid)
  io.start.ready:= !io.cancel && !reading && !pending && (!valid || io.out.ready)
  io.out.valid:=valid && !io.cancel;io.out.bits.quad:=quad;io.out.bits.data:=data
  when(io.out.fire){valid:=false.B}
  when(io.start.fire){
    assert(io.start.bits<quads.U,"fragment metadata prefetch exceeds the physical batch")
    quad:=io.start.bits;row:=0.U;reading:=true.B
  }
  when(io.read.valid){
    when(row===(rows-1).U){reading:=false.B}.otherwise{row:=row+1.U}
  }
  when(pending && !io.cancel){
    data(replyRow):=io.readData
    when(replyRow===(rows-1).U){valid:=true.B}
  }
  when(io.cancel){reading:=false.B;valid:=false.B}
  when(io.out.valid){assert(!reading && !pending,"fragment metadata published before its final SRAM reply")}
}
