package opengpu.graphics

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class FragmentCoordinateRegisterSpec extends AnyFlatSpec {
  it should "capture exact signed FP32 center and sample coordinates and retain held values" in {
    simulate(new FragmentCoordinateRegister){d=>
      d.io.pixel.poke(0.S);d.io.sampleLocationValid.poke(false.B)
      d.io.high.poke(false.B);d.io.capture.poke(false.B)
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val boundaries=Vector(0,1,-1,2,-2,Int.MinValue,Int.MinValue+1,Int.MaxValue-1,Int.MaxValue)++
        (20 to 30).flatMap(b=>Vector((1<<b)-1,1<<b,(1<<b)+1,-(1<<b)-1,-(1<<b),-(1<<b)+1))
      val random=new scala.util.Random(0x47464301L)
      for(pixel<-boundaries++Vector.fill(512)(random.nextInt());sampled<-Seq(false,true);high<-Seq(false,true)){
        val fraction=if(!sampled)0.5 else if(high)0.75 else 0.25
        val expected=BigInt(java.lang.Float.floatToRawIntBits((pixel.toDouble+fraction).toFloat)&0xffffffffL)
        d.io.pixel.poke(pixel.S);d.io.sampleLocationValid.poke(sampled.B);d.io.high.poke(high.B)
        d.io.capture.poke(true.B);d.clock.step();d.io.value.expect(expected.U)
        d.io.capture.poke(false.B);d.io.pixel.poke((~pixel).S);d.io.high.poke((!high).B)
        d.clock.step();d.io.value.expect(expected.U)
      }
    }
  }
  it should "prepare signed quarter pixels before FP32 rounding and ignore poisoned or held inputs" in {
    simulate(new PreparedFragmentCoordinateRegister){d=>
      d.io.pixel.poke(0.S);d.io.sampleLocationValid.poke(false.B);d.io.high.poke(false.B)
      d.io.prepare.poke(false.B);d.io.capture.poke(false.B);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val boundaries=Vector(0,1,-1,Int.MinValue,Int.MinValue+1,Int.MaxValue-1,Int.MaxValue)++
        (20 to 30).flatMap(b=>Vector((1<<b)-1,1<<b,(1<<b)+1,-(1<<b)-1,-(1<<b),-(1<<b)+1))
      val random=new scala.util.Random(0x51554152L)
      val cases=(for(pixel<-boundaries++Vector.fill(512)(random.nextInt());sampled<-Seq(false,true);high<-Seq(false,true))yield (pixel,sampled,high)).toVector
      def expected(w: (Int,Boolean,Boolean)): BigInt={
        val(pixel,sampled,high)=w;val f=if(!sampled)0.5 else if(high)0.75 else 0.25
        BigInt(java.lang.Float.floatToRawIntBits((pixel.toDouble+f).toFloat)&0xffffffffL)
      }
      def offer(w: (Int,Boolean,Boolean)): Unit={val(pixel,sampled,high)=w
        d.io.pixel.poke(pixel.S);d.io.sampleLocationValid.poke(sampled.B);d.io.high.poke(high.B)}
      for(w<-cases){
        offer(w);d.io.prepare.poke(true.B);d.io.capture.poke(false.B);d.clock.step()
        offer((~w._1,!w._2,!w._3));d.io.prepare.poke(false.B);d.io.capture.poke(true.B);d.clock.step();d.io.value.expect(expected(w).U)
        d.io.capture.poke(false.B);d.clock.step();d.io.value.expect(expected(w).U)
      }
      for(i<-0 to cases.size){
        d.io.prepare.poke((i<cases.size).B);d.io.capture.poke((i>0).B)
        if(i<cases.size)offer(cases(i));d.clock.step()
        if(i>0)d.io.value.expect(expected(cases(i-1)).U)
      }
      info(s"PREPARED_FRAGMENT_COORDINATES cases=${cases.size} signed32=true allFractions=true acceptanceSnapshot=true heldValue=true denseII=1")
    }
  }

}

/** Registered launch, exact coordinate capture, and staged input packing. */
class FragmentCoordinateTimingHarness extends Module {
  val io=IO(new Bundle {
    val x=Input(SInt(32.W));val y=Input(SInt(32.W));val sampled=Input(Bool())
    val mode=Input(UInt(2.W));val location=Input(UInt(2.W));val capture=Input(Bool())
    val selectY=Input(Bool());val offset=Input(UInt(6.W));val data=Output(UInt(512.W))
  })
  private val capture=RegNext(io.capture,false.B)
  private val selectY=RegNext(io.selectY);private val offset=RegNext(io.offset)
  private val coordinates=Seq.fill(2)(Module(new PreparedFragmentCoordinateRegister))
  for((coordinate,axis)<-coordinates.zipWithIndex){
    coordinate.io.pixel:=(if(axis==0)io.x else io.y);coordinate.io.sampleLocationValid:=io.sampled
    coordinate.io.high:=(if(axis==0)io.location(0)else Mux(io.mode===1.U,io.location(0),io.location(1)))
    coordinate.io.prepare:=io.capture;coordinate.io.capture:=capture
  }
  io.data:=RegNext(Fill(4,Mux(selectY,coordinates(1).io.value,coordinates(0).io.value)).pad(512)<<(offset<<3))
}

object EmitFragmentCoordinateTiming extends App {
  require(args.length==1)
  ChiselStage.emitSystemVerilogFile(new FragmentCoordinateTimingHarness,
    Array("--target-dir",args(0)),Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"))
}
