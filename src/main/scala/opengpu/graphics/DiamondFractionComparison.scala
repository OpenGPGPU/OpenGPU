package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.util.BlockPrefixAdder

/** Registered limb products for a64*b34-c64*d34. Only the high limb of
  * each numerator is signed. Each unsigned denominator has three limbs.
  */
private[graphics] class DiamondFractionProductParts extends Bundle {
  val left=MixedVec(DiamondFractionComparison.productWidths.map(w=>UInt(w.W)))
  val right=MixedVec(DiamondFractionComparison.productWidths.map(w=>UInt(w.W)))
  val epsilon=SInt(38.W);val epsilon2=SInt(38.W)
}
private[graphics] class DiamondFractionReduced extends Bundle {
  val sum=UInt(100.W);val carry=UInt(100.W)
  val epsilon=SInt(38.W);val epsilon2=SInt(38.W)
}

/** Exact symbolic rational ordering, split at three explicit register cuts:
  * <=16-bit limb products, carry-save difference, then prefix propagation.
  * The real difference fits signed99;100 bits preserve it without relying
  * on the rasterizer's tighter geometric numerator bounds.
  */
private[graphics] object DiamondFractionComparison {
  private val chunks=for(i<-0 until 4;j<-0 until 3)yield(i,j,if(j==2)2 else 16)
  val productWidths=chunks.map{case(_,_,bits)=>16+bits}
  private val width=100

  def split(a: DiamondFraction,b: DiamondFraction): DiamondFractionProductParts={
    val p=Wire(new DiamondFractionProductParts)
    def products(n: SInt,d: UInt,out: MixedVec[UInt]): Unit={
      for(((i,j,bits),k)<-chunks.zipWithIndex){
        val x=n(16*i+15,16*i);val y=d(16*j+bits-1,16*j)
        // The signed-high product fits16+bits: the zero extension of y
        // contributes a redundant sign bit which this assignment removes.
        out(k):= (if(i==3)(x.asSInt*y.zext).asUInt else x*y)
      }
    }
    products(a.numerator,b.denominator,p.left)
    products(b.numerator,a.denominator,p.right)
    p.epsilon:=a.epsilon*b.denominator.zext-&b.epsilon*a.denominator.zext
    p.epsilon2:=a.epsilon2*b.denominator.zext-&b.epsilon2*a.denominator.zext
    p
  }

  def reduce(p: DiamondFractionProductParts): DiamondFractionReduced={
    def align(parts: MixedVec[UInt]): Seq[UInt]=chunks.zipWithIndex.map{case((i,j,_),k)=>
      val extended=if(i==3)parts(k).asSInt.pad(width).asUInt else parts(k).pad(width)
      (extended<<(16*(i+j)))(width-1,0)
    }
    // -sum(right)=sum(~right)+numberOfRows modulo2^100. Every subsequent
    // reduction preserves this modular sum, whose signed result is exact.
    var rows=align(p.left)++align(p.right).map(x=> ~x):+chunks.size.U(width.W)
    while(rows.size>2){
      rows=rows.grouped(3).flatMap{g=>
        if(g.size<3)g else {
          val a=g(0);val b=g(1);val c=g(2)
          Seq(a^b^c,(((a&b)|(a&c)|(b&c))<<1)(width-1,0))
        }
      }.toSeq
    }
    val r=Wire(new DiamondFractionReduced)
    r.sum:=rows.head;r.carry:=rows(1);r.epsilon:=p.epsilon;r.epsilon2:=p.epsilon2;r
  }

  def resolve(r: DiamondFractionReduced): SInt={
    val adder=Module(new BlockPrefixAdder(width))
    adder.io.a:=r.sum;adder.io.b:=r.carry;adder.io.carryIn:=false.B
    adder.io.sum(width-1,0).asSInt
  }
  def less(real: SInt,e: SInt,e2: SInt): Bool=
    real<0.S || (real===0.S && (e<0.S || (e===0.S && e2<0.S)))
}
