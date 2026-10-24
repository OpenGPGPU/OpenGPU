package opengpu.util

import chisel3._
import chisel3.util._

/** Prepare short partial sums, then register their parallel carry prefixes.
  * A capture produces the complete width+1 sum after two clock edges. Dense
  * captures are accepted every clock; the latest completed result is held
  * through gaps. Only a groupBits-wide increment follows the second stage.
  */
class PreparedUnsignedSum(width: Int, groupBits: Int=8) extends Module {
  require(width>0 && groupBits>0)
  val io=IO(new Bundle {
    val capture=Input(Bool());val a=Input(UInt(width.W));val b=Input(UInt(width.W))
    val sum=Output(UInt((width+1).W))
  })
  private val sizes=(0 until width by groupBits).map(lo=>math.min(groupBits,width-lo))
  private val parts=sizes.zipWithIndex.map{case(bits,index)=>
    val lo=index*groupBits;val part=RegInit(0.U((bits+1).W))
    when(io.capture){part:=io.a(lo+bits-1,lo)+&io.b(lo+bits-1,lo)}
    part
  }
  private var generate: Seq[Bool]=parts.zip(sizes).map{case(part,bits)=>part(bits)}
  private var propagate: Seq[Bool]=parts.zip(sizes).map{case(part,bits)=>part(bits-1,0).andR}
  for(stage<-0 until log2Ceil(parts.size)){
    val distance=1<<stage;val g=generate;val p=propagate
    generate=parts.indices.map(i=>if(i<distance)g(i) else g(i)||(p(i)&&g(i-distance)))
    propagate=parts.indices.map(i=>if(i<distance)p(i) else p(i)&&p(i-distance))
  }
  private val advance=RegNext(io.capture,false.B)
  private val carryOut=RegInit(false.B)
  when(advance){carryOut:=generate.last}
  private val completed=parts.zip(sizes).zipWithIndex.map{case((part,bits),index)=>
    val low=RegInit(0.U(bits.W));val incoming=RegInit(false.B)
    when(advance){
      low:=part(bits-1,0)
      incoming:=(if(index==0)false.B else generate(index-1))
    }
    (low+incoming.asUInt)(bits-1,0)
  }
  io.sum:=Cat(carryOut,Cat(completed.reverse))
}
