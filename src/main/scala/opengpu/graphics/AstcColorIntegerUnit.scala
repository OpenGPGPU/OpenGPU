package opengpu.graphics

object AstcColorUnquantTables {
  // ARM astcenc color_scrambled_pquant_to_uquant tables for QUANT_6..QUANT_256 (Apache-2.0).
  val maps: Seq[Seq[Int]]=Seq(
    Seq(0, 255, 51, 204, 102, 153),
    Seq(0, 36, 73, 109, 146, 182, 219, 255),
    Seq(0, 255, 28, 227, 56, 199, 84, 171, 113, 142),
    Seq(0, 255, 69, 186, 23, 232, 92, 163, 46, 209, 116, 139),
    Seq(0, 17, 34, 51, 68, 85, 102, 119, 136, 153, 170, 187, 204, 221, 238, 255),
    Seq(0, 255, 67, 188, 13, 242, 80, 175, 27, 228, 94, 161, 40, 215, 107, 148, 54, 201, 121, 134),
    Seq(0, 255, 33, 222, 66, 189, 99, 156, 11, 244, 44, 211, 77, 178, 110, 145, 22, 233, 55, 200, 88, 167, 121, 134),
    Seq(0, 8, 16, 24, 33, 41, 49, 57, 66, 74, 82, 90, 99, 107, 115, 123, 132, 140, 148, 156, 165, 173, 181, 189, 198, 206, 214, 222, 231, 239, 247, 255),
    Seq(0, 255, 32, 223, 65, 190, 97, 158, 6, 249, 39, 216, 71, 184, 104, 151, 13, 242, 45, 210, 78, 177, 110, 145, 19, 236, 52, 203, 84, 171, 117, 138, 26, 229, 58, 197, 91, 164, 123, 132),
    Seq(0, 255, 16, 239, 32, 223, 48, 207, 65, 190, 81, 174, 97, 158, 113, 142, 5, 250, 21, 234, 38, 217, 54, 201, 70, 185, 86, 169, 103, 152, 119, 136, 11, 244, 27, 228, 43, 212, 59, 196, 76, 179, 92, 163, 108, 147, 124, 131),
    Seq(0, 4, 8, 12, 16, 20, 24, 28, 32, 36, 40, 44, 48, 52, 56, 60, 65, 69, 73, 77, 81, 85, 89, 93, 97, 101, 105, 109, 113, 117, 121, 125, 130, 134, 138, 142, 146, 150, 154, 158, 162, 166, 170, 174, 178, 182, 186, 190, 195, 199, 203, 207, 211, 215, 219, 223, 227, 231, 235, 239, 243, 247, 251, 255),
    Seq(0, 255, 16, 239, 32, 223, 48, 207, 64, 191, 80, 175, 96, 159, 112, 143, 3, 252, 19, 236, 35, 220, 51, 204, 67, 188, 83, 172, 100, 155, 116, 139, 6, 249, 22, 233, 38, 217, 54, 201, 71, 184, 87, 168, 103, 152, 119, 136, 9, 246, 25, 230, 42, 213, 58, 197, 74, 181, 90, 165, 106, 149, 122, 133, 13, 242, 29, 226, 45, 210, 61, 194, 77, 178, 93, 162, 109, 146, 125, 130),
    Seq(0, 255, 8, 247, 16, 239, 24, 231, 32, 223, 40, 215, 48, 207, 56, 199, 64, 191, 72, 183, 80, 175, 88, 167, 96, 159, 104, 151, 112, 143, 120, 135, 2, 253, 10, 245, 18, 237, 26, 229, 35, 220, 43, 212, 51, 204, 59, 196, 67, 188, 75, 180, 83, 172, 91, 164, 99, 156, 107, 148, 115, 140, 123, 132, 5, 250, 13, 242, 21, 234, 29, 226, 37, 218, 45, 210, 53, 202, 61, 194, 70, 185, 78, 177, 86, 169, 94, 161, 102, 153, 110, 145, 118, 137, 126, 129),
    Seq(0, 2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22, 24, 26, 28, 30, 32, 34, 36, 38, 40, 42, 44, 46, 48, 50, 52, 54, 56, 58, 60, 62, 64, 66, 68, 70, 72, 74, 76, 78, 80, 82, 84, 86, 88, 90, 92, 94, 96, 98, 100, 102, 104, 106, 108, 110, 112, 114, 116, 118, 120, 122, 124, 126, 129, 131, 133, 135, 137, 139, 141, 143, 145, 147, 149, 151, 153, 155, 157, 159, 161, 163, 165, 167, 169, 171, 173, 175, 177, 179, 181, 183, 185, 187, 189, 191, 193, 195, 197, 199, 201, 203, 205, 207, 209, 211, 213, 215, 217, 219, 221, 223, 225, 227, 229, 231, 233, 235, 237, 239, 241, 243, 245, 247, 249, 251, 253, 255),
    Seq(0, 255, 8, 247, 16, 239, 24, 231, 32, 223, 40, 215, 48, 207, 56, 199, 64, 191, 72, 183, 80, 175, 88, 167, 96, 159, 104, 151, 112, 143, 120, 135, 1, 254, 9, 246, 17, 238, 25, 230, 33, 222, 41, 214, 49, 206, 57, 198, 65, 190, 73, 182, 81, 174, 89, 166, 97, 158, 105, 150, 113, 142, 121, 134, 3, 252, 11, 244, 19, 236, 27, 228, 35, 220, 43, 212, 51, 204, 59, 196, 67, 188, 75, 180, 83, 172, 91, 164, 99, 156, 107, 148, 115, 140, 123, 132, 4, 251, 12, 243, 20, 235, 28, 227, 36, 219, 44, 211, 52, 203, 60, 195, 68, 187, 76, 179, 84, 171, 92, 163, 100, 155, 108, 147, 116, 139, 124, 131, 6, 249, 14, 241, 22, 233, 30, 225, 38, 217, 46, 209, 54, 201, 62, 193, 70, 185, 78, 177, 86, 169, 94, 161, 102, 153, 110, 145, 118, 137, 126, 129),
    Seq(0, 255, 4, 251, 8, 247, 12, 243, 16, 239, 20, 235, 24, 231, 28, 227, 32, 223, 36, 219, 40, 215, 44, 211, 48, 207, 52, 203, 56, 199, 60, 195, 64, 191, 68, 187, 72, 183, 76, 179, 80, 175, 84, 171, 88, 167, 92, 163, 96, 159, 100, 155, 104, 151, 108, 147, 112, 143, 116, 139, 120, 135, 124, 131, 1, 254, 5, 250, 9, 246, 13, 242, 17, 238, 21, 234, 25, 230, 29, 226, 33, 222, 37, 218, 41, 214, 45, 210, 49, 206, 53, 202, 57, 198, 61, 194, 65, 190, 69, 186, 73, 182, 77, 178, 81, 174, 85, 170, 89, 166, 93, 162, 97, 158, 101, 154, 105, 150, 109, 146, 113, 142, 117, 138, 121, 134, 125, 130, 2, 253, 6, 249, 10, 245, 14, 241, 18, 237, 22, 233, 26, 229, 30, 225, 34, 221, 38, 217, 42, 213, 46, 209, 50, 205, 54, 201, 58, 197, 62, 193, 66, 189, 70, 185, 74, 181, 78, 177, 82, 173, 86, 169, 90, 165, 94, 161, 98, 157, 102, 153, 106, 149, 110, 145, 114, 141, 118, 137, 122, 133, 126, 129),
    Seq(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 58, 59, 60, 61, 62, 63, 64, 65, 66, 67, 68, 69, 70, 71, 72, 73, 74, 75, 76, 77, 78, 79, 80, 81, 82, 83, 84, 85, 86, 87, 88, 89, 90, 91, 92, 93, 94, 95, 96, 97, 98, 99, 100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110, 111, 112, 113, 114, 115, 116, 117, 118, 119, 120, 121, 122, 123, 124, 125, 126, 127, 128, 129, 130, 131, 132, 133, 134, 135, 136, 137, 138, 139, 140, 141, 142, 143, 144, 145, 146, 147, 148, 149, 150, 151, 152, 153, 154, 155, 156, 157, 158, 159, 160, 161, 162, 163, 164, 165, 166, 167, 168, 169, 170, 171, 172, 173, 174, 175, 176, 177, 178, 179, 180, 181, 182, 183, 184, 185, 186, 187, 188, 189, 190, 191, 192, 193, 194, 195, 196, 197, 198, 199, 200, 201, 202, 203, 204, 205, 206, 207, 208, 209, 210, 211, 212, 213, 214, 215, 216, 217, 218, 219, 220, 221, 222, 223, 224, 225, 226, 227, 228, 229, 230, 231, 232, 233, 234, 235, 236, 237, 238, 239, 240, 241, 242, 243, 244, 245, 246, 247, 248, 249, 250, 251, 252, 253, 254, 255))  
}


import chisel3._
import chisel3.util._

/** ASTC color endpoint integer selection and decoding.
  *
  * Selects the color quantization level from the integer count and remaining
  * color bits using the specification's descending-size search, then decodes
  * the bounded integer sequence at the given block bit offset and maps each
  * scrambled quantized value to its 8-bit color contribution. Revision-aware
  * endpoint-CEM selection, partitions and HDR modes remain outside this unit.
  */
object AstcColorIntegerStatus {val ok=0;val invalid=1}

class AstcColorIntegerRequest extends Bundle {
  val tag=UInt(8.W);val block=UInt(128.W)
  val integerCount=UInt(5.W);val colorBits=UInt(8.W);val bitOffset=UInt(7.W)
}
class AstcColorIntegerResult extends Bundle {
  val tag=UInt(8.W);val status=UInt(2.W);val quant=UInt(5.W);val count=UInt(5.W)
  val values=Vec(18,UInt(8.W))
}

class AstcColorIntegerUnit extends Module {
  object State extends ChiselEnum {val idle,decode,unpack,unpackWrite,respond=Value}
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new AstcColorIntegerRequest))
    val out=Decoupled(new AstcColorIntegerResult)
    val busy=Output(Bool())
  })
  // QUANT_2..QUANT_256, ascending; enum value is the index.
  private val levels=Seq(2,3,4,5,6,8,10,12,16,20,24,32,40,48,64,80,96,128,160,192,256)
  private val bitsPer=Seq(1,0,2,0,1,3,1,2,4,2,3,5,3,4,6,4,5,7,5,6,8)
  private val tritPer=Seq(0,1,0,0,1,0,0,1,0,0,1,0,0,1,0,0,1,0,0,1,0)
  private val quintPer=Seq(0,0,0,1,0,0,1,0,0,1,0,0,1,0,0,1,0,0,1,0,0)
  private def needed(n: UInt,b: Int,t: Int,q: Int): UInt={
    val base=n*b.U
    val trit=if(t!=0)((n*8.U)+4.U)/5.U else 0.U
    val quint=if(q!=0)((n*7.U)+2.U)/3.U else 0.U
    base+trit+quint
  }
  private def fits(n: UInt,colorBits: UInt,i: Int): Bool=needed(n,bitsPer(i),tritPer(i),quintPer(i))<=colorBits

  private val phase=RegInit(State.idle)
  private val request=Reg(new AstcColorIntegerRequest)
  private val result=Reg(new AstcColorIntegerResult)
  private val stream=Reg(UInt(128.W));private val index=Reg(UInt(5.W));private val offset=Reg(UInt(8.W))
  private val lcounter=Reg(UInt(3.W));private val hcounter=Reg(UInt(5.W))
  private val ms=Reg(Vec(18,UInt(8.W)));private val tq=Reg(Vec(22,UInt(8.W)))
  private val count=Reg(UInt(5.W));private val status=RegInit(0.U(2.W))
  private val bitsReg=Reg(UInt(4.W));private val tritsReg=Reg(Bool());private val quintsReg=Reg(Bool())
  private val resultIndex=Reg(UInt(5.W));private val quantReg=Reg(UInt(5.W))

  io.in.ready:=phase===State.idle
  io.out.valid:=phase===State.respond;io.out.bits:=result;io.busy:=phase=/=State.idle

  private def readBits(n: UInt,base: UInt): UInt=(stream>>base)(7,0)&((1.U(8.W)<<n)-1.U)
  private def tritAt(block: UInt,k: UInt): UInt={
    val T=block(7,0)
    val special=T(4,2)==="b111".U
    val c=Mux(special,Cat(T(7,5),T(1,0)),T(4,0))
    val t4=Mux(special,2.U,Mux(T(6,5)==="b11".U,2.U,T(7).asUInt))
    val t3=Mux(special,2.U,Mux(T(6,5)==="b11".U,T(7).asUInt,T(6,5)))
    val t2=Mux(c(1,0)==="b11".U,2.U,Mux(c(3,2)==="b11".U,2.U,c(4).asUInt))
    val t1=Mux(c(1,0)==="b11".U,c(4).asUInt,Mux(c(3,2)==="b11".U,2.U,c(3,2)))
    val t0=Mux(c(1,0)==="b11".U,Cat(c(3),c(2) && !c(3)),Mux(c(3,2)==="b11".U,c(1,0),Cat(c(1),c(0) && !c(1))))
    VecInit(t0,t1,t2,t3,t4)(k)
  }
  private def quintAt(block: UInt,k: UInt): UInt={
    val Q=block(6,0)
    val special=Q(2,1)==="b11".U && Q(6,5)==="b00".U
    val q2=Mux(special,Cat(Q(0),Q(4) && !Q(0),Q(3) && !Q(0)),Mux(Q(2,1)==="b11".U,4.U,Q(6,5)))
    val c=Mux(special,0.U(5.W),Mux(Q(2,1)==="b11".U,Cat(Q(4,3),~Q(6,5),Q(0)),Q(4,0)))
    val q1=Mux(special,4.U,Mux(c(2,0)==="b101".U,4.U,c(4,3)))
    val q0=Mux(special,4.U,Mux(c(2,0)==="b101".U,c(4,3),c(2,0)))
    VecInit(q0,q1,q2)(k)
  }
  private val bitRead=MuxLookup(lcounter,0.U(2.W))(Seq(0.U->2.U,1.U->2.U,2.U->1.U,3.U->2.U,4.U->1.U))
  private val bitShift=MuxLookup(lcounter,0.U(3.W))(Seq(0.U->0.U,1.U->2.U,2.U->4.U,3.U->5.U,4.U->7.U))
  private val nextL=MuxLookup(lcounter,0.U(3.W))(Seq(0.U->1.U,1.U->2.U,2.U->3.U,3.U->4.U,4.U->0.U))
  private val incH=MuxLookup(lcounter,false.B)(Seq(4.U->true.B))
  private val qRead=MuxLookup(lcounter,0.U(2.W))(Seq(0.U->3.U,1.U->2.U,2.U->2.U))
  private val qShift=MuxLookup(lcounter,0.U(3.W))(Seq(0.U->0.U,1.U->3.U,2.U->5.U))
  private val qNext=MuxLookup(lcounter,0.U(3.W))(Seq(0.U->1.U,1.U->2.U,2.U->0.U))
  private val qIncH=MuxLookup(lcounter,false.B)(Seq(2.U->true.B))
  private val tritBlock=(resultIndex/5.U)(4,0);private val tritK=(resultIndex%5.U)(2,0)
  private val quintBlock=(resultIndex/3.U)(4,0);private val quintK=(resultIndex%3.U)(1,0)
  private val pquant=Mux(tritsReg,tritAt(tq(tritBlock),tritK),Mux(quintsReg,quintAt(tq(quintBlock),quintK),0.U))
  private val stored=(pquant<<bitsReg)|ms(resultIndex)
  private val mapRows=AstcColorUnquantTables.maps.zipWithIndex.map{case(row,i)=>
    val v=VecInit(row.map(_.U(8.W)))
    (i+4).U -> v(stored(log2Ceil(row.size)-1,0))
  }
  private val uquant=MuxLookup(quantReg,0.U(8.W))(mapRows)

  private val q=io.in.bits
  private val fitsVec=VecInit(levels.indices.reverse.map(i=>fits(q.integerCount,q.colorBits,i)))
  private val sel=PriorityEncoder(fitsVec)
  private val levelEnum=MuxLookup(sel,0.U(5.W))(levels.indices.reverse.map{case i=>(levels.size-1-i).U->i.U})
  private val chosenNeeded=MuxLookup(sel,0.U(9.W))(levels.indices.reverse.map{case i=>(levels.size-1-i).U->needed(q.integerCount,bitsPer(i),tritPer(i),quintPer(i))})
  private val anyFit=fitsVec.asUInt.orR
  private val legal=anyFit && q.integerCount>=2.U && q.integerCount<=18.U && q.integerCount(0)===0.U &&
    q.colorBits<128.U && levelEnum>=4.U && q.bitOffset.pad(9)+chosenNeeded<=128.U
  private val invalid= !legal

  when(io.in.fire){
    request:=io.in.bits;result:=0.U.asTypeOf(result);result.tag:=io.in.bits.tag
    stream:=Reverse(io.in.bits.block);index:=0.U;offset:=io.in.bits.bitOffset.pad(8)
    lcounter:=0.U;hcounter:=0.U;resultIndex:=0.U
    ms.foreach(_:=0.U);tq.foreach(_:=0.U)
    count:=io.in.bits.integerCount
    quantReg:=levelEnum
    bitsReg:=MuxLookup(sel,0.U(4.W))(levels.indices.reverse.map{case i=>(levels.size-1-i).U->bitsPer(i).U})
    tritsReg:=MuxLookup(sel,false.B)(levels.indices.reverse.map{case i=>(levels.size-1-i).U->(tritPer(i)!=0).B})
    quintsReg:=MuxLookup(sel,false.B)(levels.indices.reverse.map{case i=>(levels.size-1-i).U->(quintPer(i)!=0).B})
    status:=Mux(invalid,1.U,0.U);result.status:=Mux(invalid,1.U,0.U);result.quant:=levelEnum;result.count:=io.in.bits.integerCount
    phase:=Mux(invalid,State.respond,State.decode)
  }
  switch(phase){
    is(State.decode){
      when(index===count){phase:=State.unpack;resultIndex:=0.U}
      .otherwise{
        val nextOffset=offset+bitsReg
        ms(index):=readBits(bitsReg,offset)
        when(tritsReg){
          tq(hcounter):=tq(hcounter)|(readBits(bitRead,nextOffset)<<bitShift)
          offset:=nextOffset+bitRead;lcounter:=nextL
          when(incH){hcounter:=hcounter+1.U}
        }.elsewhen(quintsReg){
          tq(hcounter):=tq(hcounter)|(readBits(qRead,nextOffset)<<qShift)
          offset:=nextOffset+qRead;lcounter:=qNext
          when(qIncH){hcounter:=hcounter+1.U}
        }.otherwise{offset:=nextOffset}
        index:=index+1.U
      }
    }
    is(State.unpack){
      when(resultIndex===count){
        result.quant:=quantReg;result.count:=count;result.status:=status;phase:=State.respond
      }.elsewhen(status=/=0.U){phase:=State.respond}
      .otherwise{phase:=State.unpackWrite}
    }
    is(State.unpackWrite){
      result.values(resultIndex):=uquant
      resultIndex:=resultIndex+1.U;phase:=State.unpack
    }
    is(State.respond){when(io.out.fire){phase:=State.idle}}
  }
  when(phase===State.respond && status===0.U){result.quant:=quantReg;result.count:=count;result.status:=status}
}
