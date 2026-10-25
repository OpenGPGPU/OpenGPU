package opengpu.util

import chisel3._
import chisel3.util._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

private class PreparedCounterBoundaryOracle extends Module {
  val seeds=(0 to 64).map(n=>(BigInt(1)<<n)-1)
  val io=IO(new Bundle {
    val increment=Input(UInt(7.W));val commit=Input(Bool());val clear=Input(Bool())
    val values=Output(Vec(seeds.size,UInt(64.W)))
  })
  for((seed,i)<-seeds.zipWithIndex){
    val current=RegInit(seed.U(64.W));val add=Module(new PreparedSmallIncrement64(seed))
    add.io.current:=current;add.io.increment:=io.increment;add.io.commit:=io.commit;add.io.clear:=io.clear
    when(io.clear){current:=seed.U}.elsewhen(io.commit){current:=add.io.sum}
    io.values(i):=current
  }
}

class PreparedSmallIncrement64Spec extends AnyFlatSpec {
  it should "preserve every carry boundary, immediate observations and consecutive commits through clear and reset" in {
    simulate(new PreparedCounterBoundaryOracle){d=>
      val seeds=(0 to 64).map(n=>(BigInt(1)<<n)-1).toVector;val mask=(BigInt(1)<<64)-1
      var expected=seeds;val rng=new scala.util.Random(0x6bc064)
      d.io.increment.poke(0.U);d.io.commit.poke(false.B);d.io.clear.poke(false.B)
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      expected.zipWithIndex.foreach{case(v,i)=>d.io.values(i).expect(v.U)}
      for(cycle<-0 until 512){
        val amount=if(cycle<128)cycle else rng.nextInt(128)
        val commit=cycle%9!=7;val clear=cycle%37==36;val reset=cycle==257
        d.io.increment.poke(amount.U);d.io.commit.poke(commit.B);d.io.clear.poke(clear.B);d.reset.poke(reset.B)
        d.clock.step()
        expected=if(clear || reset)seeds else if(commit)expected.map(v=>(v+amount)&mask)else expected
        expected.zipWithIndex.foreach{case(v,i)=>d.io.values(i).expect(v.U)}
      }
      d.reset.poke(false.B)
      info("PREPARED_SMALL_INCREMENT64 seeds=65 clocks=512 incrementRange0to127=true allCarryBoundaries=true fullWraparound=true consecutiveCommits=true noObservationDelay=true clearPriority=true reset=true heldOnNoCommit=true")
    }
  }
}
