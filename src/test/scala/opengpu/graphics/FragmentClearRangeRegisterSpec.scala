package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class FragmentClearRangeRegisterSpec extends AnyFlatSpec {
  private val full=(BigInt(1)<<64)-1
  private def expected(base: BigInt,input: BigInt,prefix: BigInt): Vector[BigInt]={
    val first=(base+input)& ~BigInt(63);val last=(base+prefix-1)& ~BigInt(63)
    val low=(full<<((base+input)&63).toInt)&full
    val end=((base+prefix)&63).toInt;val high=if(end==0)full else full>>(64-end)
    Vector(first,last,low,high)
  }
  it should "capture exact aligned clear words and byte masks at every line boundary and32bit address edge" in {
    simulate(new FragmentClearRangeRegister){d=>
      d.io.capture.poke(false.B);d.io.base.poke(0.U);d.io.inputBytes.poke(0.U);d.io.prefixBytes.poke(1.U)
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val bases=Vector(BigInt(0),BigInt(64),BigInt("7fffffc0",16),BigInt("80000000",16),BigInt("ffffffc0",16))
      val directed=(for(base<-bases;low<-0 until 64;length<-Vector(1,2,3,16,63,64,65,127)
        if base+low+length<=(BigInt(1)<<32))yield (base,BigInt(low),BigInt(low+length))).toVector
      val random=new scala.util.Random(0x434c5247L)
      val varied=Vector.fill(4096){
        val base=BigInt(32,random)& ~BigInt(63);val room=(BigInt(1)<<32)-base
        val input=BigInt(16,random).min(room-1)
        val length=(BigInt(random.nextInt(4096))+1).min(room-input)
        (base,input,input+length)
      }
      for((base,input,prefix)<-directed++varied){
        d.io.base.poke(base.U);d.io.inputBytes.poke(input.U);d.io.prefixBytes.poke(prefix.U);d.io.capture.poke(true.B)
        d.clock.step();val e=expected(base,input,prefix)
        d.io.first.expect(e(0).U);d.io.last.expect(e(1).U);d.io.low.expect(e(2).U);d.io.high.expect(e(3).U)
      }
      info(s"FRAGMENT_CLEAR_RANGE cases=${directed.size+varied.size} allLineRemainders=true signedBoundary=true endAt4GiB=true exactByteMasks=true captureClocks=1")
    }
  }
  it should "hold the validated draw range while uncaptured descriptor pins change" in {
    simulate(new FragmentClearRangeRegister){d=>
      d.io.base.poke(0x1000.U);d.io.inputBytes.poke(176.U);d.io.prefixBytes.poke(448.U);d.io.capture.poke(true.B)
      d.clock.step();val first=d.io.first.peek().litValue;val last=d.io.last.peek().litValue
      val low=d.io.low.peek().litValue;val high=d.io.high.peek().litValue
      d.io.capture.poke(false.B)
      for(i<-0 until 32){
        d.io.base.poke((3+i).U);d.io.inputBytes.poke((i*71).U);d.io.prefixBytes.poke(i.U);d.clock.step()
        d.io.first.expect(first.U);d.io.last.expect(last.U);d.io.low.expect(low.U);d.io.high.expect(high.U)
      }
    }
  }
}
