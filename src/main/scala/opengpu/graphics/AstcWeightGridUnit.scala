package opengpu.graphics

import chisel3._
import chisel3.util._

/** ASTC LDR weight-grid integer-sequence decoder.
  *
  * Reads the weight stream from the most significant block bit downward,
  * decodes the trit/quint/bit integer sequence for the requested quantization
  * method, and maps each stored value to its 0..64 unquantized weight. The
  * unquantized mapping tables are the ASTC specification's scramble-compensated
  * tables (as published by the reference astcenc implementation, Apache-2.0).
  * Infill, partitioning and endpoint application remain outside this unit.
  */
object AstcWeightGridStatus {val ok=0;val invalid=1}
object AstcWeightUnquantTables {
  // ARM astcenc quant_and_xfer_tables unscramble_and_unquant_map entries.
  val maps: Seq[Seq[Int]]=Seq(
    Seq(0,64),Seq(0,32,64),Seq(0,21,43,64),Seq(0,16,32,48,64),
    Seq(0,64,12,52,25,39),Seq(0,9,18,27,37,46,55,64),
    Seq(0,64,7,57,14,50,21,43,28,36),
    Seq(0,64,17,47,5,59,23,41,11,53,28,36),
    Seq(0,4,8,12,17,21,25,29,35,39,43,47,52,56,60,64),
    Seq(0,64,16,48,3,61,19,45,6,58,23,41,9,55,26,38,13,51,29,35),
    Seq(0,64,8,56,16,48,24,40,2,62,11,53,19,45,27,37,5,59,13,51,22,42,30,34),
    Seq(0,2,4,6,8,10,12,14,16,18,20,22,24,26,28,30,34,36,38,40,42,44,46,48,50,52,54,56,58,60,62,64))
  val bits: Seq[Int]=Seq(1,0,2,0,1,3,1,2,4,2,3,5)
  val trits: Seq[Int]=Seq(0,1,0,0,1,0,0,1,0,0,1,0)
  val quints: Seq[Int]=Seq(0,0,0,1,0,0,1,0,0,1,0,0)
  val scale: Seq[Int]=Seq(1,8,2,7,13,3,10,18,4,13,23,5)
  val divisor: Seq[Int]=Seq(1,5,1,3,5,1,3,5,1,3,5,1)
}

class AstcWeightGridRequest extends Bundle {
  val tag=UInt(8.W);val block=UInt(128.W);val gridX=UInt(4.W);val gridY=UInt(4.W)
  val dualPlane=Bool();val quant=UInt(4.W)
}
class AstcWeightGridResult extends Bundle {
  val tag=UInt(8.W);val status=UInt(2.W);val count=UInt(7.W);val weights=Vec(64,UInt(7.W))
}

class AstcWeightGridUnit extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new AstcWeightGridRequest));val out=Decoupled(new AstcWeightGridResult)
    val busy=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,reverse,decode,unpack,unpackWrite,respond=Value}
  private val phase=RegInit(State.idle);private val request=Reg(new AstcWeightGridRequest)
  private val result=Reg(new AstcWeightGridResult)
  private val stream=Reg(UInt(128.W));private val index=Reg(UInt(7.W));private val offset=Reg(UInt(7.W))
  private val lcounter=Reg(UInt(3.W));private val hcounter=Reg(UInt(5.W))
  private val ms=Reg(Vec(64,UInt(5.W)));private val tq=Reg(Vec(22,UInt(8.W)))
  private val count=Reg(UInt(7.W));private val status=RegInit(0.U(2.W))
  private val bitsReg=Reg(UInt(3.W));private val tritsReg=Reg(Bool());private val quintsReg=Reg(Bool())
  private val resultIndex=Reg(UInt(7.W))
  io.in.ready:=phase===State.idle;io.out.valid:=phase===State.respond;io.out.bits:=result;io.busy:=phase=/=State.idle
  private val q=request.quant
  private val bitsCount=MuxLookup(q,0.U(3.W))(AstcWeightUnquantTables.bits.zipWithIndex.map{case(b,i)=>i.U->b.U})
  private val tritFlag=MuxLookup(q,false.B)(AstcWeightUnquantTables.trits.zipWithIndex.map{case(t,i)=>i.U->(t!=0).B})
  private val quintFlag=MuxLookup(q,false.B)(AstcWeightUnquantTables.quints.zipWithIndex.map{case(t,i)=>i.U->(t!=0).B})
  private val bitRead=MuxLookup(lcounter,0.U(2.W))(Seq(0.U->2.U,1.U->2.U,2.U->1.U,3.U->2.U,4.U->1.U))
  private val bitShift=MuxLookup(lcounter,0.U(3.W))(Seq(0.U->0.U,1.U->2.U,2.U->4.U,3.U->5.U,4.U->7.U))
  private val nextL=MuxLookup(lcounter,0.U(3.W))(Seq(0.U->1.U,1.U->2.U,2.U->3.U,3.U->4.U,4.U->0.U))
  private val incH=MuxLookup(lcounter,false.B)(Seq(4.U->true.B))
  private val qRead=MuxLookup(lcounter,0.U(2.W))(Seq(0.U->3.U,1.U->2.U,2.U->2.U))
  private val qShift=MuxLookup(lcounter,0.U(3.W))(Seq(0.U->0.U,1.U->3.U,2.U->5.U))
  private val qNext=MuxLookup(lcounter,0.U(3.W))(Seq(0.U->1.U,1.U->2.U,2.U->0.U))
  private val qIncH=MuxLookup(lcounter,false.B)(Seq(2.U->true.B))
  private def readBits(n: UInt,base: UInt): UInt=(stream>>base)(4,0)&((1.U(5.W)<<n)-1.U)
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
  private val tritBlock=(resultIndex/5.U)(4,0);private val tritK=(resultIndex%5.U)(2,0)
  private val quintBlock=(resultIndex/3.U)(4,0);private val quintK=(resultIndex%3.U)(1,0)
  private val value=Mux(tritFlag,tritAt(tq(tritBlock),tritK),Mux(quintFlag,quintAt(tq(quintBlock),quintK),0.U))
  private val stored=(value<<bitsReg)|ms(resultIndex(5,0))
  private val mapRow=VecInit(AstcWeightUnquantTables.maps.map(m=>VecInit(m.map(_.U(7.W)).padTo(32,0.U))))(q)
  when(io.in.fire){
    request:=io.in.bits;result:=0.U.asTypeOf(result);result.tag:=io.in.bits.tag
    stream:=Reverse(io.in.bits.block);index:=0.U;offset:=0.U;lcounter:=0.U;hcounter:=0.U
    ms.foreach(_:=0.U);tq.foreach(_:=0.U)
    val c=io.in.bits.gridX*io.in.bits.gridY*Mux(io.in.bits.dualPlane,2.U(7.W),1.U(7.W))
    val scale=MuxLookup(io.in.bits.quant,1.U(6.W))(AstcWeightUnquantTables.scale.zipWithIndex.map{case(v,i)=>i.U->v.U})
    val divisor=MuxLookup(io.in.bits.quant,1.U(6.W))(AstcWeightUnquantTables.divisor.zipWithIndex.map{case(v,i)=>i.U->v.U})
    val needed=((scale*c)+(divisor-1.U))/divisor
    count:=c
    bitsReg:=MuxLookup(io.in.bits.quant,0.U(3.W))(AstcWeightUnquantTables.bits.zipWithIndex.map{case(b,i)=>i.U->b.U})
    tritsReg:=MuxLookup(io.in.bits.quant,false.B)(AstcWeightUnquantTables.trits.zipWithIndex.map{case(t,i)=>i.U->(t!=0).B})
    quintsReg:=MuxLookup(io.in.bits.quant,false.B)(AstcWeightUnquantTables.quints.zipWithIndex.map{case(t,i)=>i.U->(t!=0).B})
    status:=Mux(io.in.bits.quant>11.U || c===0.U || c>64.U || needed>120.U,1.U,0.U)
    phase:=Mux(io.in.bits.quant>11.U || c===0.U || c>64.U || needed>120.U,State.respond,State.reverse)
  }
  switch(phase){
    is(State.reverse){phase:=State.decode}
    is(State.decode){
      when(index===count){phase:=State.unpack;resultIndex:=0.U}
        .otherwise{
          val nextOffset=offset+bitsReg
          ms(index(5,0)):=readBits(bitsReg,offset)
          when(tritFlag){
            tq(hcounter):=tq(hcounter)|(readBits(bitRead,nextOffset)<<bitShift)
            offset:=nextOffset+bitRead;lcounter:=nextL
            when(incH){hcounter:=hcounter+1.U}
          }.elsewhen(quintFlag){
            tq(hcounter):=tq(hcounter)|(readBits(qRead,nextOffset)<<qShift)
            offset:=nextOffset+qRead;lcounter:=qNext
            when(qIncH){hcounter:=hcounter+1.U}
          }.otherwise{offset:=nextOffset}
          index:=index+1.U
        }
    }
    is(State.unpack){
      when(resultIndex===count){phase:=State.respond;result.count:=count;result.status:=status}
        .elsewhen(status=/=0.U){phase:=State.respond}
        .otherwise{phase:=State.unpackWrite}
    }
    is(State.unpackWrite){
      when(request.tag===1.U && resultIndex<4.U){printf(p"WU i=$resultIndex ms=${ms(resultIndex(5,0))} v=$value cat=${Cat(value,bitsReg)} t=$tritFlag qf=$quintFlag stored=$stored\n")}
      result.weights(resultIndex(5,0)):=mapRow(stored)
      resultIndex:=resultIndex+1.U;phase:=State.unpack
    }
    is(State.respond){when(io.out.fire){phase:=State.idle}}
  }
  when(phase===State.respond && status===0.U){result.count:=count;result.status:=status}
}
