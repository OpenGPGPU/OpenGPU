package opengpu.util

import chisel3._
import chisel3.util._

/** Exact modulo2^64 addition of a seven-bit increment, with registered carry
  * metadata between byte banks. The owner must reset/clear current to initialValue
  * and update it to sum exactly on commit; clear wins over commit. No observation
  * clock is added, and consecutive commits are supported.
  */
class PreparedSmallIncrement64(initialValue: BigInt=0) extends Module {
  require(initialValue>=0 && initialValue<(BigInt(1)<<64))
  val io=IO(new Bundle {
    val current=Input(UInt(64.W));val increment=Input(UInt(7.W))
    val commit=Input(Bool());val clear=Input(Bool());val sum=Output(UInt(64.W))
  })
  // Only bytes1..6 propagate a carry into another represented upper byte.
  private val initial=(1 until 7).map(i=>((initialValue>>(8*i))&255)==255)
  private val propagate=RegInit(VecInit(initial.map(_.B)))
  private val low=io.current(7,0)+&io.increment
  private val bytes=(0 until 8).map{i=>
    if(i==0)low(7,0)else {
      val carry=low(8) && (if(i==1)true.B else propagate.take(i-1).reduce(_ && _))
      (io.current(8*i+7,8*i)+carry.asUInt)(7,0)
    }
  }
  io.sum:=Cat(bytes.reverse)
  when(io.clear){propagate:=VecInit(initial.map(_.B))}
    .elsewhen(io.commit){for(i<-1 until 7)propagate(i-1):=bytes(i).andR}
}
