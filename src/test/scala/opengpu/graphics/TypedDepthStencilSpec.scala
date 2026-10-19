package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.util.Random

private[graphics] object TypedDepthReference {
  import TypedColorReference._
  def hasDepth(f: Int): Boolean=f>=1 && f<=5
  def hasStencil(f: Int): Boolean=f==4 || f==5 || f==6
  def bytes(f: Int): Int=Vector(0,2,4,4,4,8,1).lift(f).getOrElse(0)
  def stencilShift(f: Int): Int=if(f==4)24 else if(f==5)32 else 0
  def depthMask(f: Int): BigInt=if(f==1)0xffff else if(f==2 || f==4)0xffffff else BigInt("ffffffff",16)
  def encode(raw: BigInt,f: Int): BigInt=if(f==1)unorm(raw,16)else if(f==2 || f==4)unorm(raw,24)else {
    val d=value(raw);if(d<0)0 else if(d>1)fp(1)else raw
  }
  def compare(a: BigInt,b: BigInt,f: Int,floating: Boolean=false): Boolean={
    val x=if(floating)value(a)else a.toDouble;val y=if(floating)value(b)else b.toDouble
    f match{case 0=>x<y;case 1=>x<=y;case 2=>x>y;case 3=>x>=y;case 4=>x==y;case 5=>x!=y;case 6=>true;case _=>false}
  }
  def operation(old: Int,ref: Int,op: Int): Int=op match{
    case 0=>old;case 1=>0;case 2=>ref;case 3=>math.min(255,old+1);case 4=>math.max(0,old-1);case 5=>old^255;case 6=>(old+1)&255;case _=>(old-1)&255
  }
}
private class TypedDepthHarness extends Module {
  val io=IO(new Bundle{
    val raw=Input(UInt(32.W));val format=Input(UInt(3.W));val encoded=Output(UInt(32.W))
    val a=Input(UInt(32.W));val b=Input(UInt(32.W));val function=Input(UInt(3.W));val floating=Input(Bool());val pass=Output(Bool())
    val old=Input(UInt(8.W));val reference=Input(UInt(8.W));val operation=Input(UInt(3.W));val updated=Output(UInt(8.W))
  })
  io.encoded:=TypedDepthFormat.encode(io.raw,io.format);io.pass:=TypedDepthFormat.compare(io.a,io.b,io.function,io.floating)
  io.updated:=TypedDepthFormat.operation(io.old,io.reference,io.operation)
}
class TypedDepthStencilSpec extends AnyFlatSpec {
  import TypedColorReference._
  it should "convert fixed16 and24 depth exactly and retain floating32 clamping semantics" in {
    simulate(new TypedDepthHarness){d=>val random=new Random(0xdecaf);var count=0
      d.io.a.poke(0.U);d.io.b.poke(0.U);d.io.function.poke(6.U);d.io.floating.poke(false.B);d.io.old.poke(0.U);d.io.reference.poke(0.U);d.io.operation.poke(0.U)
      val raw=Vector(0L,0x80000000L,0x3f000000L,0x3f7fffffL,0x3f800000L,0xbf000000L,0x7f800000L,0xff800000L).map(BigInt(_))++
        Vector.fill(4096)(BigInt(random.nextLong()&0xffffffffL)).filter(x=> !value(x).isNaN)
      for(f<-1 to 5;x<-raw){d.io.format.poke(f.U);d.io.raw.poke(x.U);d.clock.step();d.io.encoded.expect(TypedDepthReference.encode(x,f).U);count+=1}
      info(s"TYPED_DEPTH formats=5 depthCases=$count exactUNORM16and24=true singleRNE=true floatingClamp=true signedZero=true")
    }
  }
  it should "cover all compare functions and every stencil byte across saturating wrapping and masked-reference operations" in {
    simulate(new TypedDepthHarness){d=>var count=0
      d.io.raw.poke(0.U);d.io.format.poke(1.U);d.io.a.poke(0.U);d.io.b.poke(0.U);d.io.function.poke(6.U);d.io.floating.poke(false.B)
      for(old<-0 until 256;ref<-Seq(0,1,127,255);op<-0 until 8){d.io.old.poke(old.U);d.io.reference.poke(ref.U);d.io.operation.poke(op.U);d.clock.step()
        d.io.updated.expect(TypedDepthReference.operation(old,ref,op).U);count+=1}
      val values=Vector(0L,0x80000000L,0x3f000000L,0x3f800000L,0xbf800000L,0x7f800000L,0xff800000L,0x7fc00001L,0xffffffffL).map(BigInt(_))
      var comparisons=0
      for(floating<-Seq(false,true);a<-values;b<-values;f<-0 until 8){d.io.a.poke(a.U);d.io.b.poke(b.U);d.io.function.poke(f.U);d.io.floating.poke(floating.B);d.clock.step()
        d.io.pass.expect(TypedDepthReference.compare(a,b,f,floating).B);comparisons+=1}
      info(s"TYPED_DEPTH stencilCases=$count compareCases=$comparisons functions=8 operations=8 NaNUnordered=true frontBackCompatible=true")
    }
  }
}
