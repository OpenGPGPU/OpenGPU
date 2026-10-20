package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.util.{BlockPrefixAdder,CarrySaveMultiplier}

/** Registered limb products for aN*b34-cN*d34. Only the high limb of
  * each numerator is signed. Each unsigned denominator has three limbs.
  */
private[graphics] class DiamondFractionProductParts(val numeratorBits: Int=64) extends Bundle {
  require(numeratorBits>=2 && numeratorBits<=64)
  val left=MixedVec(DiamondFractionComparison.productWidths(numeratorBits).map(w=>UInt(w.W)))
  val right=MixedVec(DiamondFractionComparison.productWidths(numeratorBits).map(w=>UInt(w.W)))
  val epsilon=SInt(38.W);val epsilon2=SInt(38.W)
}
private[graphics] class DiamondFractionReduced(val numeratorBits: Int=64) extends Bundle {
  val realWidth=numeratorBits+36
  val sum=UInt(realWidth.W);val carry=UInt(realWidth.W)
  val epsilon=SInt(38.W);val epsilon2=SInt(38.W)
}

/** Exact symbolic rational ordering, split at three explicit register cuts:
  * <=16-bit limb products, carry-save difference, then prefix propagation.
  * The real difference fits signed(N+35);N+36 bits retain full precision.
  * Generic64-bit callers remain supported;the raster owner proves N=36.
  */
private[graphics] object DiamondFractionComparison {
  private def chunks(n: Int)=for(i<-0 until (n+15)/16;j<-0 until 3)
    yield(i,j,math.min(16,n-16*i),if(j==2)2 else 16)
  def productWidths(n: Int)=chunks(n).map{case(_,_,a,b)=>a+b}

  def split(a: DiamondFraction,b: DiamondFraction,numeratorBits: Int=64): DiamondFractionProductParts={
    val p=Wire(new DiamondFractionProductParts(numeratorBits))
    val terms=chunks(numeratorBits);val last=(numeratorBits-1)/16
    def products(n: SInt,d: UInt,out: MixedVec[UInt]): Unit={
      for(((i,j,aBits,bBits),k)<-terms.zipWithIndex){
        val x=n(16*i+aBits-1,16*i);val y=d(16*j+bBits-1,16*j)
        out(k):=CarrySaveMultiplier(x,y,signedA=i==last,signedB=false)
      }
    }
    products(a.numerator,b.denominator,p.left)
    products(b.numerator,a.denominator,p.right)
    p.epsilon:=a.epsilon*b.denominator.zext-&b.epsilon*a.denominator.zext
    p.epsilon2:=a.epsilon2*b.denominator.zext-&b.epsilon2*a.denominator.zext
    p
  }

  def reduce(p: DiamondFractionProductParts): DiamondFractionReduced={
    val terms=chunks(p.numeratorBits);val last=(p.numeratorBits-1)/16;val width=p.numeratorBits+36
    def align(parts: MixedVec[UInt]): Seq[UInt]=terms.zipWithIndex.map{case((i,j,_,_),k)=>
      val extended=if(i==last)parts(k).asSInt.pad(width).asUInt else parts(k).pad(width)
      (extended<<(16*(i+j)))(width-1,0)
    }
    // -sum(right)=sum(~right)+numberOfRows modulo2^width. Every subsequent
    // reduction preserves this modular sum, whose signed result is exact.
    var rows=align(p.left)++align(p.right).map(x=> ~x):+terms.size.U(width.W)
    while(rows.size>2){
      rows=rows.grouped(3).flatMap{g=>
        if(g.size<3)g else {
          val a=g(0);val b=g(1);val c=g(2)
          Seq(a^b^c,(((a&b)|(a&c)|(b&c))<<1)(width-1,0))
        }
      }.toSeq
    }
    val r=Wire(new DiamondFractionReduced(p.numeratorBits))
    r.sum:=rows.head;r.carry:=rows(1);r.epsilon:=p.epsilon;r.epsilon2:=p.epsilon2;r
  }

  def resolve(r: DiamondFractionReduced): SInt={
    val width=r.realWidth
    val adder=Module(new BlockPrefixAdder(width))
    adder.io.a:=r.sum;adder.io.b:=r.carry;adder.io.carryIn:=false.B
    adder.io.sum(width-1,0).asSInt
  }
  def less(real: SInt,e: SInt,e2: SInt): Bool=
    real<0.S || (real===0.S && (e<0.S || (e===0.S && e2<0.S)))
}
