package opengpu.util

import chisel3._
import chisel3.util.Cat

/** Exact modular accumulator with bitwise feedback and a one-clock observer.
  * Each short sum is registered independently. Their registered carries are
  * combined at the output; consumers snapshot that output after draining the
  * final update. This bounds both feedback and observation carry depth.
  */
class ObservedCarrySaveAccumulator(width: Int) extends Module {
  require(width>0)
  val io=IO(new Bundle {
    val clear=Input(Bool());val addend=Input(UInt(width.W));val value=Output(UInt(width.W))
  })
  private val sum=RegInit(0.U(width.W));private val carry=RegInit(0.U(width.W))
  private val nextSum=sum^carry^io.addend
  private val generated=(sum&carry)|(sum&io.addend)|(carry&io.addend)
  when(io.clear){sum:=0.U;carry:=0.U}.otherwise{sum:=nextSum;carry:=(generated<<1)(width-1,0)}
  private val chunkBits=32
  private val sizes=(0 until width by chunkBits).map(lo=>math.min(chunkBits,width-lo))
  private val parts=sizes.zipWithIndex.map{case(bits,index)=>
    val low=index*chunkBits;val part=RegInit(0.U((bits+1).W))
    when(io.clear){part:=0.U}.otherwise{part:=sum(low+bits-1,low)+&carry(low+bits-1,low)}
    part
  }
  private var carryIn: Bool=false.B
  private val completed=parts.zip(sizes).map{case(part,bits)=>
    val incoming=carryIn
    carryIn=part(bits) || (part(bits-1,0).andR && incoming)
    (part(bits-1,0)+incoming.asUInt)(bits-1,0)
  }
  io.value:=Cat(completed.reverse)
}
