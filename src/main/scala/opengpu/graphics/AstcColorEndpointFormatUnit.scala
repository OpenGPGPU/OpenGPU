package opengpu.graphics

import chisel3._
import chisel3.util._

/** ASTC color endpoint format (CEM) selection for a 2D block.
  *
  * Reads the single-partition format field or the shared/per-partition encoded
  * type of a multi-partition block, reports the color endpoint formats, the
  * resulting integer count, remaining color bits and color-sequence bit offset,
  * and the dual-plane component selector. Endpoint value decoding, endpoint
  * application and physical layout remain outside this unit.
  */
object AstcColorEndpointFormatStatus {val ok=0;val invalid=1}

class AstcColorEndpointFormatRequest extends Bundle {
  val tag=UInt(8.W);val block=UInt(128.W);val partitions=UInt(3.W)
  val weightBits=UInt(7.W);val dualPlane=Bool()
}
class AstcColorEndpointFormatResult extends Bundle {
  val tag=UInt(8.W);val status=UInt(2.W);val partitions=UInt(3.W)
  val partitionIndex=UInt(10.W);val formats=Vec(4,UInt(4.W));val matched=Bool()
  val integerCount=UInt(6.W);val colorBits=UInt(8.W);val colorOffset=UInt(7.W)
  val plane2Component=UInt(2.W);val belowWeightsPos=UInt(8.W)
}

class AstcColorEndpointFormatUnit extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new AstcColorEndpointFormatRequest))
    val out=Decoupled(new AstcColorEndpointFormatResult)
    val busy=Output(Bool())
  })
  private val phase=RegInit(false.B);private val result=Reg(new AstcColorEndpointFormatResult)
  io.in.ready:= !phase;io.out.valid:=phase;io.out.bits:=result;io.busy:=phase
  private def readBits(block: UInt,n: Int,pos: Int): UInt=block(pos+n-1,pos)
  private val q=io.in.bits
  private val pc=q.partitions
  private val multi=pc>1.U
  private val highSize=Wire(UInt(8.W));highSize:=0.U
  when(multi){highSize:=(3.U*pc)-4.U}
  private val belowBase=128.U(8.W)-q.weightBits
  private val belowHigh=belowBase-highSize
  private val lowType=readBits(q.block,6,23).pad(16)
  private val highType=Wire(UInt(16.W));highType:=0.U
  when(multi && highSize=/=0.U){
    highType:=((q.block>>belowHigh)(7,0)&((1.U(8.W)<<highSize)-1.U)).pad(16)
  }
  private val encoded=(lowType|(highType<<6))(15,0)
  private val baseclass=encoded(1,0)
  private val matched=multi && baseclass===0.U
  private val sharedFormat=encoded(5,2)
  private val baseClass=baseclass-1.U
  private def formatFor(i: Int): UInt={
    val highBitPos=2+i
    val lowBitPos=(2+2*i).U(8.W)+pc.pad(8)
    val cls=(encoded>>highBitPos)(0)+baseClass
    val f=Cat(cls(1,0), (encoded>>lowBitPos)(1,0))
    Mux(pc>i.U, f, 0.U)
  }
  private val multiFormats=VecInit((0 until 4).map(formatFor))
  private val formats=VecInit((0 until 4).map{i=>
    Mux(!multi, Mux(i.U===0.U,readBits(q.block,4,13),0.U(4.W)), Mux(baseclass===0.U,sharedFormat,multiFormats(i)))})
  private val integerCount=Wire(UInt(8.W))
  integerCount:=(0 until 4).map{i=>
    val active=pc>i.U
    val cls=formats(i)(3,2)
    Mux(active,(cls<<1).pad(4)+2.U(4.W),0.U(8.W))
  }.reduce((a,b)=>a+&b)
  private val highUsed=Mux(baseclass===0.U,0.U,highSize)
  private val colorBase=Mux(multi,99.U(8.W),111.U(8.W))
  private val colorCalc=colorBase.pad(9)-q.weightBits.pad(9)-highUsed.pad(9)-Mux(q.dualPlane,2.U(9.W),0.U(9.W))
  private val colorBits=Mux(colorCalc(8),0.U(8.W),colorCalc(7,0))
  private val belowPos=Mux(multi,belowBase-highUsed,belowBase)
  private val plane2=Mux(q.dualPlane && belowPos>=2.U,(q.block>>(belowPos-2.U))(1,0),0.U)
  private val legal=pc>=1.U && pc<=4.U && q.weightBits<=96.U && integerCount<=18.U &&
    (!multi || belowHigh>=highSize) && (!q.dualPlane || belowPos>=2.U)
  when(!phase && io.in.valid){
    result:=0.U.asTypeOf(result);result.tag:=q.tag;result.partitions:=pc
    result.status:=Mux(legal,0.U,1.U)
    result.partitionIndex:=Mux(multi && legal,readBits(q.block,10,13),0.U)
    result.formats:=Mux(legal,formats,VecInit(Seq.fill(4)(0.U(4.W))))
    result.matched:=legal && matched
    result.integerCount:=Mux(legal,integerCount,0.U)
    result.colorBits:=Mux(legal,colorBits,0.U)
    result.colorOffset:=Mux(legal,Mux(multi,29.U(7.W),17.U(7.W)),0.U)
    result.plane2Component:=Mux(legal && q.dualPlane,plane2,0.U)
    result.belowWeightsPos:=Mux(legal,belowPos,0.U)
    phase:=true.B
  }.elsewhen(phase && io.out.ready){phase:=false.B}
}
