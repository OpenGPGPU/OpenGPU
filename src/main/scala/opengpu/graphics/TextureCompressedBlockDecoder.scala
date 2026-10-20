package opengpu.graphics

import chisel3._
import chisel3.util._

object TextureBlockCodec {val rgb=1;val punch=2;val rgba=3;val r11=4;val rg11=5}
class TextureCompressedBlockRequest extends Bundle {
  val raw=UInt(128.W);val codec=UInt(3.W);val signed=Bool();val x=UInt(2.W);val y=UInt(2.W);val tag=UInt(8.W)
}
class TextureCompressedBlockResult extends Bundle {
  // Quantized8-bit color or11-bit EAC, sign extended to12 bits for signed EAC.
  // Image conversion supplies missing-component defaults and normalization.
  val rgba=Vec(4,UInt(12.W));val tag=UInt(8.W);val status=UInt(2.W)
}
/** GLES3 Appendix C, one selected pixel from a published ETC2/EAC block.
  * Bytes retain external compressed order (first byte at raw[7:0]). Extract,
  * multiply, sum and clamp occupy separate cycles. No decoded-block cache or
  * filtering rate is implied. The sole result is immutable until retirement.
  */
class TextureCompressedBlockDecoder extends Module {
  val io=IO(new Bundle {val in=Flipped(Decoupled(new TextureCompressedBlockRequest));val out=Decoupled(new TextureCompressedBlockResult);val busy=Output(Bool())})
  private object State extends ChiselEnum {val idle,extract,products,sum,clamp,respond=Value}
  private val phase=RegInit(State.idle);private val request=Reg(new TextureCompressedBlockRequest);private val result=Reg(new TextureCompressedBlockResult)
  private val origin=Reg(Vec(3,UInt(8.W)));private val horizontal=Reg(Vec(3,UInt(8.W)));private val vertical=Reg(Vec(3,UInt(8.W)))
  private val modifier=Reg(SInt(9.W));private val planar=Reg(Bool());private val transparent=Reg(Bool())
  private val xProduct=Reg(Vec(3,SInt(14.W)));private val yProduct=Reg(Vec(3,SInt(14.W)));private val colorSum=Reg(Vec(3,SInt(14.W)))
  private val eacBase=Reg(Vec(2,SInt(14.W)));private val eacModifier=Reg(Vec(2,SInt(5.W)));private val eacMultiplier=Reg(Vec(2,UInt(7.W)))
  private val eacProduct=Reg(Vec(2,SInt(14.W)));private val eacSum=Reg(Vec(2,SInt(14.W)))
  private val bytes=VecInit((0 until 16).map(i=>request.raw(i*8+7,i*8)))
  private val color=VecInit((0 until 8).map(i=>Mux(request.codec===TextureBlockCodec.rgba.U,bytes(i+8),bytes(i))))
  private val pixel=Cat(request.x,request.y)
  private val indices=Cat(color(4),color(5),color(6),color(7))
  private val selector=Cat(indices(16.U+pixel),indices(pixel.pad(5)))
  private val punch=request.codec===TextureBlockCodec.punch.U
  private val differential=punch || color(3)(1)
  private val second=Mux(color(3)(0),request.y(1),request.x(1))
  private val delta=(0 until 3).map(c=>Cat(0.U(1.W),color(c)(7,3)).asSInt+&color(c)(2,0).asSInt)
  private val overflow=delta.map(n=>n<0.S || n>31.S)
  private val mode=Mux(!differential,0.U,Mux(overflow(0),2.U,Mux(overflow(1),3.U,Mux(overflow(2),4.U,1.U))))
  private def extend4(n: UInt): UInt=Cat(n,n)
  private def extend5(n: UInt): UInt=Cat(n,n(4,2))
  private def extend6(n: UInt): UInt=Cat(n,n(5,4))
  private def extend7(n: UInt): UInt=Cat(n,n(6))
  private val table=Mux(second,color(3)(4,2),color(3)(7,5))
  private val colorTables=Seq(Seq(2,8,-2,-8),Seq(5,17,-5,-17),Seq(9,29,-9,-29),Seq(13,42,-13,-42),
    Seq(18,60,-18,-60),Seq(24,80,-24,-80),Seq(33,106,-33,-106),Seq(47,183,-47,-183))
  private val ordinaryModifier=VecInit(colorTables.map(row=>VecInit(row.map(_.S(9.W)))))(table)(selector)
  private val nonopaque=punch && !color(3)(1)
  private val diffModifier=Mux(nonopaque && !selector(0),0.S(9.W),ordinaryModifier)
  private val tFirst=Seq(Cat(color(0)(4,3),color(0)(1,0)),color(1)(7,4),color(1)(3,0)).map(extend4)
  private val tSecond=Seq(color(2)(7,4),color(2)(3,0),color(3)(7,4)).map(extend4)
  private val hFirst=Seq(color(0)(6,3),Cat(color(0)(2,0),color(1)(4)),Cat(color(1)(3),color(1)(1,0),color(2)(7))).map(extend4)
  private val hSecond=Seq(color(2)(6,3),Cat(color(2)(2,0),color(3)(7)),color(3)(6,3)).map(extend4)
  private val distances=VecInit(Seq(3,6,11,16,23,32,41,64).map(_.U(7.W)))
  private val tDistance=Cat(0.U(2.W),distances(Cat(color(3)(3,2),color(3)(0)))).asSInt
  private val hDistance=Cat(0.U(2.W),distances(Cat(color(3)(2),color(3)(0),Cat(hFirst)>=Cat(hSecond)))).asSInt
  private val pOrigin=Seq(extend6(color(0)(6,1)),extend7(Cat(color(0)(0),color(1)(6,1))),
    extend6(Cat(color(1)(0),color(2)(4,3),color(2)(1,0),color(3)(7))))
  private val pHorizontal=Seq(extend6(Cat(color(3)(6,2),color(3)(0))),extend7(color(4)(7,1)),extend6(Cat(color(4)(0),color(5)(7,3))))
  private val pVertical=Seq(extend6(Cat(color(5)(2,0),color(6)(7,5))),extend7(Cat(color(6)(4,0),color(7)(7,6))),extend6(color(7)(5,0)))
  private val eacTables=Seq(Seq(-3,-6,-9,-15,2,5,8,14),Seq(-3,-7,-10,-13,2,6,9,12),
    Seq(-2,-5,-8,-13,1,4,7,12),Seq(-2,-4,-6,-13,1,3,5,12),Seq(-3,-6,-8,-12,2,5,7,11),Seq(-3,-7,-9,-11,2,6,8,10),
    Seq(-4,-7,-8,-11,3,6,7,10),Seq(-3,-5,-8,-11,2,4,7,10),Seq(-2,-6,-8,-10,1,5,7,9),Seq(-2,-5,-8,-10,1,4,7,9),
    Seq(-2,-4,-8,-10,1,3,7,9),Seq(-2,-5,-7,-10,1,4,6,9),Seq(-3,-4,-7,-10,2,3,6,9),Seq(-1,-2,-3,-10,0,1,2,9),
    Seq(-4,-6,-8,-9,3,5,7,8),Seq(-3,-5,-7,-9,2,4,6,8))
  private def bounded(n: SInt,lo: Int,hi: Int): SInt=Mux(n<lo.S,lo.S(14.W),Mux(n>hi.S,hi.S(14.W),n))
  io.in.ready:=phase===State.idle;io.out.valid:=phase===State.respond;io.out.bits:=result;io.busy:=phase=/=State.idle
  switch(phase){
    is(State.idle){when(io.in.fire){request:=io.in.bits;result:=0.U.asTypeOf(result);result.tag:=io.in.bits.tag
      when(io.in.bits.codec<1.U || io.in.bits.codec>5.U){result.status:=1.U;phase:=State.respond}.otherwise{phase:=State.extract}}}
    is(State.extract){
      planar:=mode===4.U;transparent:=nonopaque && mode=/=4.U && selector===2.U
      modifier:=Mux(mode===2.U,Mux(selector===1.U,tDistance,Mux(selector===3.U,-tDistance,0.S)),
        Mux(mode===3.U,Mux(selector(0),-hDistance,hDistance),diffModifier))
      for(c<-0 until 3){
        val individual=Mux(second,extend4(color(c)(3,0)),extend4(color(c)(7,4)))
        val added=delta(c).asUInt
        val diff=Mux(second,extend5(added(4,0)),extend5(color(c)(7,3)))
        origin(c):=MuxLookup(mode,individual)(Seq(1.U->diff,2.U->Mux(selector===0.U,tFirst(c),tSecond(c)),
          3.U->Mux(selector(1),hSecond(c),hFirst(c)),4.U->pOrigin(c)))
        horizontal(c):=pHorizontal(c);vertical(c):=pVertical(c)
      }
      for(c<-0 until 2){
        val alpha=request.codec===TextureBlockCodec.rgba.U
        val base=bytes(c*8);val parameters=bytes(c*8+1);val codes=Cat((2 until 8).map(n=>bytes(c*8+n)))
        val code=(codes>>((15.U-pixel)*3.U))(2,0)
        val signedBase=Mux(base===128.U,(-127).S(8.W),base.asSInt)
        eacBase(c):=Mux(alpha,base.zext,Mux(request.signed,(signedBase.pad(14)<<3),((base.zext.pad(14)<<3)+4.S)))
        eacModifier(c):=VecInit(eacTables.map(row=>VecInit(row.map(_.S(5.W)))))(parameters(3,0))(code)
        eacMultiplier(c):=Mux(alpha,parameters(7,4).pad(7),Mux(parameters(7,4)===0.U,1.U,Cat(parameters(7,4),0.U(3.W))))
      }
      phase:=State.products
    }
    is(State.products){
      for(c<-0 until 3){xProduct(c):=request.x.zext*(horizontal(c).zext-origin(c).zext);yProduct(c):=request.y.zext*(vertical(c).zext-origin(c).zext)}
      for(c<-0 until 2)eacProduct(c):=eacModifier(c)*eacMultiplier(c).zext
      phase:=State.sum
    }
    is(State.sum){
      for(c<-0 until 3)colorSum(c):=Mux(planar,(xProduct(c)+yProduct(c)+(origin(c).zext.pad(14)<<2)+2.S)>>2,origin(c).zext+&modifier)
      for(c<-0 until 2)eacSum(c):=eacBase(c)+eacProduct(c)
      phase:=State.clamp
    }
    is(State.clamp){
      for(c<-0 until 3)result.rgba(c):=Mux(transparent,0.U,bounded(colorSum(c),0,255).asUInt)(11,0)
      result.rgba(3):=Mux(transparent,0.U,Mux(request.codec===TextureBlockCodec.rgba.U,bounded(eacSum(0),0,255).asUInt,255.U))(11,0)
      when(request.codec===TextureBlockCodec.r11.U || request.codec===TextureBlockCodec.rg11.U){
        for(c<-0 until 2)result.rgba(c):=Mux(request.signed,bounded(eacSum(c),-1023,1023),bounded(eacSum(c),0,2047)).asUInt(11,0)
        result.rgba(2):=0.U;result.rgba(3):=0.U
        when(request.codec===TextureBlockCodec.r11.U){result.rgba(1):=0.U}
      }
      phase:=State.respond
    }
    is(State.respond){when(io.out.fire){phase:=State.idle}}
  }
}
