package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class PreparedThresholdCoverageSpec extends AnyFlatSpec {
  for(width<-Seq(4,16,64);samples<-Seq(1,2,4))
  it should s"match exact signed offset coverage with width$width samples$samples" in {
    simulate(new PreparedThresholdCoverage(samples,width)){d=>
      val random=new scala.util.Random(0x54e0+width+samples)
      def limits(bits: Int): Seq[BigInt]=Seq(-(BigInt(1)<<(bits-1)),-1,0,1,(BigInt(1)<<(bits-1))-1)
      def signed(bits: Int): BigInt=BigInt(bits,random)-(BigInt(1)<<(bits-1))
      val cases=if(width==4) {
        for(e<- -8 until 8;o<- -16 until 16)yield(BigInt(e),BigInt(o))
      }else (for(e<-limits(width);o<-limits(width+1))yield(e,o))++Seq.fill(512)((signed(width),signed(width+1)))
      for(((edge,offset),index)<-cases.zipWithIndex){
        val edges=Seq(edge,if(index%3==0)signed(width)else edge,if(index%5==0)signed(width)else edge)
        val offsets=(0 until 4).map(slot=>(0 until 3).map(e=>
          if(e==0)offset else if((slot&1)==0)BigInt(0)else BigInt(-1)))
        val topLeft=(0 until 3).map(e=>((index>>e)&1)!=0)
        for(e<-0 until 3){
          d.io.edges(e).poke(edges(e).S);d.io.topLeft(e).poke(topLeft(e).B)
          for(slot<-0 until 4)d.io.thresholds(slot)(e).poke((-offsets(slot)(e)).S)
        }
        for(front<-Seq(false,true);mode<-0 to chisel3.util.log2Ceil(samples)){
          d.io.front.poke(front.B);d.io.sampleMode.poke(mode.U)
          val slots=if(mode==0)Seq(-1)else if(mode==1)Seq(0,3)else Seq(0,1,2,3)
          val expected=slots.zipWithIndex.foldLeft(0){case(mask,(slot,bit))=>
            val covered=(0 until 3).forall{e=>
              val value=edges(e)+(if(slot<0)BigInt(0)else offsets(slot)(e))
              (if(front)value>0 else value<0)||(value==0 && topLeft(e))
            }
            if(covered)mask|(1<<bit)else mask
          }
          d.io.mask.expect(expected.U)
        }
        if(samples<4){d.io.sampleMode.poke(2.U);d.io.mask.expect(0.U)}
        d.io.sampleMode.poke(3.U);d.io.mask.expect(0.U)
      }
      info(s"THRESHOLD_COVERAGE width=$width samples=$samples fullSignedOffsets=true outsideEdgeRange=true topLeftAndWinding=true independentExactSumReference=true reservedModesZero=true")
    }
  }
}

private class RasterThresholdSetupHarness(bits: Int) extends Module {
  val io=IO(new Bundle {
    val a=Input(SInt(34.W));val b=Input(SInt(34.W))
    val thresholds=Output(Vec(4,SInt(66.W)))
  })
  private val width=RasterSampleOffsetMath.quarterWidth(34,bits)+1
  private val a=RasterSampleOffsetMath.quarter(io.a,bits)
  private val b=RasterSampleOffsetMath.quarter(io.b,bits)
  private val plus=RegNext(a +& b)
  private val minus=RegNext(a.pad(width)-b.pad(width))
  io.thresholds:=RegNext(VecInit(RasterSampleOffsetMath.thresholds(plus,minus)))
}

class RasterThresholdSetupSpec extends AnyFlatSpec {
  for(bits<-Seq(0,1,8,30,31,32))
  it should s"capture exact negative sample offsets without narrowing at subPixelBits$bits" in {
    simulate(new RasterThresholdSetupHarness(bits)){d=>
      val random=new scala.util.Random(0x66aa+bits);val modulus=BigInt(1)<<64
      def quarter(value: BigInt): BigInt={val low=(value<<bits)&(modulus-1);(if(low.testBit(63))low-modulus else low)>>2}
      val limits=Seq(-(BigInt(1)<<33),-(BigInt(1)<<33)+1,BigInt(-1),BigInt(0),BigInt(1),(BigInt(1)<<33)-1)
      val cases=(for(a<-limits;b<-limits)yield(a,b))++Seq.fill(128)((BigInt(34,random)-(BigInt(1)<<33),BigInt(34,random)-(BigInt(1)<<33)))
      for((a,b)<-cases){
        d.io.a.poke(a.S);d.io.b.poke(b.S);d.clock.step(2)
        val x=quarter(a);val y=quarter(b)
        Seq(x+y,-x+y,x-y,-x-y).zipWithIndex.foreach{case(v,i)=>d.io.thresholds(i).expect(v.S)}
      }
      info(s"THRESHOLD_SETUP subPixelBits=$bits signed34Extremes=true modular64Quarter=true exact66bitNegation=true")
    }
  }
}
