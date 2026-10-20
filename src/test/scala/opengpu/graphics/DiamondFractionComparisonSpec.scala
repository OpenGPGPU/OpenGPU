package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable
import scala.util.Random

private class DiamondFractionComparisonHarness(width: Int) extends Module {
  val io=IO(new Bundle {
    val a=Input(new DiamondFraction);val b=Input(new DiamondFraction)
    val enable=Input(Bool());val valid=Output(Bool());val less=Output(Bool())
    val real=Output(SInt((width+36).W));val epsilon=Output(SInt(38.W));val epsilon2=Output(SInt(38.W))
  })
  val parts=RegNext(DiamondFractionComparison.split(io.a,io.b,width))
  val reduced=RegNext(DiamondFractionComparison.reduce(parts))
  val real=RegNext(DiamondFractionComparison.resolve(reduced))
  val e=RegNext(reduced.epsilon);val e2=RegNext(reduced.epsilon2)
  io.valid:=RegNext(RegNext(RegNext(io.enable,false.B),false.B),false.B)
  io.real:=real;io.epsilon:=e;io.epsilon2:=e2
  io.less:=DiamondFractionComparison.less(real,e,e2)
}

class DiamondFractionComparisonSpec extends AnyFlatSpec {
  private case class Fraction(n: BigInt,d: BigInt,e: Int=0,e2: Int=0)
  private case class Expected(real: BigInt,e: BigInt,e2: BigInt) {
    val less=real<0 || (real==0 && (e<0 || (e==0 && e2<0)))
  }
  it should "equate paired slab tests with interval intersection for every endpoint weak order" in {
    var checked=0
    // Six ranks represent every weak ordering (including ties) of the six
    // endpoints0,1,lu,hu,lv,hv. Enabled slabs have strictly ordered endpoints;
    // a stationary slab contributes no interval endpoints.
    for(z<-0 until 6;one<-0 until 6 if z<one;
      lu<-0 until 6;hu<-0 until 6;lv<-0 until 6;hv<-0 until 6;
      u<-Seq(false,true);v<-Seq(false,true) if (!u || lu<hu) && (!v || lv<hv)){
      val lower=Seq(z)++(if(u)Seq(lu)else Nil)++(if(v)Seq(lv)else Nil)
      val upper=Seq(one)++(if(u)Seq(hu)else Nil)++(if(v)Seq(hv)else Nil)
      val expected=lower.max<upper.min
      val bounds=(!u || (lu<one && z<hu)) && (!v || (lv<one && z<hv))
      val cross= !u || !v || (lu<hv && lv<hu)
      assert(expected==(bounds && cross),s"interval order mismatch: $z $one $lu $hu $lv $hv $u $v")
      checked+=1
    }
    assert(checked==39015)
    info(s"DIAMOND_INTERVAL_ORDER rankCases=$checked ranks=6 everyWeakOrder=true ties=true activeAxisPatterns=4")
  }

  for(width<-Seq(36,64))
  it should s"preserve full signed products symbolic ties and sample order through registered reduction width$width" in {
    simulate(new DiamondFractionComparisonHarness(width)){dut=>
      val pending=mutable.Queue.empty[Option[Expected]]
      var checked=0;var bubbles=0
      def poke(target: DiamondFraction,x: Fraction): Unit={
        target.numerator.poke(x.n.S);target.denominator.poke(x.d.U)
        target.epsilon.poke(x.e.S);target.epsilon2.poke(x.e2.S)
      }
      poke(dut.io.a,Fraction(0,1));poke(dut.io.b,Fraction(0,1));dut.io.enable.poke(false.B)
      dut.reset.poke(true.B);dut.clock.step();dut.reset.poke(false.B)
      def tick(pair: Option[(Fraction,Fraction)]): Unit={
        dut.io.enable.poke(pair.nonEmpty.B)
        val expected=pair.map{case(a,b)=>
          poke(dut.io.a,a);poke(dut.io.b,b)
          Expected(a.n*b.d-b.n*a.d,BigInt(a.e)*b.d-BigInt(b.e)*a.d,BigInt(a.e2)*b.d-BigInt(b.e2)*a.d)
        }
        pending.enqueue(expected);dut.clock.step()
        if(pending.size>=3){
          val result=pending.dequeue();dut.io.valid.expect(result.nonEmpty.B)
          result.foreach{x=>dut.io.real.expect(x.real.S);dut.io.epsilon.expect(x.e.S)
            dut.io.epsilon2.expect(x.e2.S);dut.io.less.expect(x.less.B);checked+=1}
        }else dut.io.valid.expect(false.B)
      }
      val minimum= -(BigInt(1)<<(width-1));val maximum=(BigInt(1)<<(width-1))-1
      val numerators=Seq(minimum,maximum,BigInt(-1),BigInt(0),BigInt(1),-(BigInt(1)<<31),BigInt(1)<<31,
        -(BigInt(1)<<32),BigInt(1)<<32)
      val denominators=Seq(BigInt(0),BigInt(1),(BigInt(1)<<32)-1,BigInt(1)<<32,(BigInt(1)<<34)-1)
      val words=for(n<-numerators;d<-denominators)yield Fraction(n,d)
      val edges=for(a<-words;b<-words)yield(a,b)
      val ties=for((a,b)<-Seq((BigInt(1),BigInt(1)),((BigInt(1)<<34)-1,BigInt(1)),
        ((BigInt(1)<<34)-1,(BigInt(1)<<34)-2));e<- -2 to 1;f<- -2 to 1;e2<- -2 to 1;f2<- -2 to 1)
        yield(Fraction(0,a,e,e2),Fraction(0,b,f,f2))
      val scaled=for(n<-Seq(BigInt(0),BigInt(-1),BigInt(1),minimum/4,maximum/4);
        d<-Seq(BigInt(1),BigInt(17),BigInt(1)<<31);k<-Seq(2,3))yield(Fraction(n,d),Fraction(n*k,d*k))
      val random=new Random(0xd1a604)
      def fraction(): Fraction={val raw=BigInt(width,random)
        Fraction(if(raw.testBit(width-1))raw-(BigInt(1)<<width)else raw,BigInt(34,random),random.nextInt(4)-2,random.nextInt(4)-2)}
      val cases=edges++ties++scaled++Seq.fill(2048)((fraction(),fraction()))
      for((pair,i)<-cases.zipWithIndex){
        if(i%7==0){tick(None);bubbles+=1};tick(Some(pair))
      }
      for(_<-0 until 3)tick(None)
      assert(checked==cases.size)
      info(s"DIAMOND_FRACTION_PIPELINE exactComparisons=$checked bubbles=$bubbles realBits=${width+36} numeratorBits=$width denominatorBits=34 epsilonBits=2 signedBoundaries=true symbolicTies=${ties.size} fullPrecision=true latency=3")
    }
  }
}
