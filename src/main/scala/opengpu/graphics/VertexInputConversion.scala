package opengpu.graphics

import chisel3._
import chisel3.util._

/** Scalar vertex conversion uses RNE and preserves FP32 bits. Integer shader
  * inputs use the original raw formats; converted formats produce FP32 bits.
  * Packed REV components occupy bits 0:9, 10:19, 20:29 and 30:31.
  */
object VertexInputConversion {
  private def magnitude(raw: UInt,bits: UInt,signed: Bool): (Bool,UInt)={
    val extended=((raw<<(32.U-bits))(31,0).asSInt>>(32.U-bits)).asUInt
    val negative=signed && extended(31)
    (negative,Mux(negative,(~extended+1.U)(31,0),raw))
  }
  private def integer(raw: UInt,bits: UInt,signed: Bool,fixed: Bool): UInt={
    val (negative,n)=magnitude(raw,bits,signed)
    val leading=PriorityEncoder(Reverse(n));val normalized=(n<<leading)(31,0)
    val increment=normalized(7) && (normalized(6,0).orR || normalized(8))
    val rounded=normalized(31,8)+&increment
    val exponent=158.U(9.W)-leading-Mux(fixed,16.U,0.U)+rounded(24)
    Mux(n===0.U,0.U,Cat(negative,exponent(7,0),rounded(22,0)))
  }
  /** A normalized fraction repeats its b-bit numerator indefinitely. Sixty-four
    * fractional bits retain the leading bit and rounding guard for b<=32.
    * Nonintegral fractions with odd denominator cannot be binary midpoints.
    */
  private def unorm(n: UInt,bits: UInt): UInt={
    val repeated=MuxLookup(bits,0.U(64.W))(Seq(1,2,7,8,9,10,15,16,31,32).map { b=>
      val copies=(64+b-1)/b;val width=copies*b
      b.U->Fill(copies,n(b-1,0))(width-1,width-64)
    })
    val leading=PriorityEncoder(Reverse(repeated));val normalized=(repeated<<leading)(63,0)
    val rounded=normalized(63,40)+&normalized(39)
    val exponent=126.U(8.W)-leading+rounded(24)
    Mux(n===0.U,0.U,Cat(0.U(1.W),exponent(7,0),rounded(22,0)))
  }
  def apply(word: UInt,format: UInt,component: UInt): UInt={
    import VertexInputFormat._
    val isPacked=packed(format)
    val bits=Mux(isPacked,Mux(component===3.U,2.U,10.U),MuxLookup(format,32.U)(Seq(
      float16.U->16.U,uint16Float.U->16.U,sint16Float.U->16.U,unorm16.U->16.U,snorm16.U->16.U,
      uint8Float.U->8.U,sint8Float.U->8.U,unorm8.U->8.U,snorm8.U->8.U)))
    val shift=Mux(isPacked,Mux(component===3.U,30.U,component*10.U),0.U)
    val raw=(word>>shift)&((1.U(33.W)<<bits)-1.U)(31,0)
    val signed=Seq(sint8Float,sint16Float,sint32Float,snorm8,snorm16,snorm32,snorm2101010,sint2101010Float,fixed16_16)
      .map(format===_.U).reduce(_ || _)
    val normalized=Seq(unorm8,snorm8,unorm16,snorm16,unorm32,snorm32,unorm2101010,snorm2101010)
      .map(format===_.U).reduce(_ || _)
    val (negative,n)=magnitude(raw(31,0),bits,signed)
    val maximum=((1.U(33.W)<<(bits-1.U))-1.U)(31,0)
    val fraction=unorm(Mux(signed,Mux(n>maximum,maximum,n),n),Mux(signed,bits-1.U,bits))
    val converted=Mux(normalized,Cat(negative,fraction(30,0)),integer(raw(31,0),bits,signed,format===fixed16_16.U))
    Mux(format===float16.U,TypedColorDecode.half(word(15,0)),Mux(format>=float16.U,converted,VertexInputFormat.expand(word,format)))
  }
}
