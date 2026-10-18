package opengpu.util

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class CarrySaveMultiplierProbe(aw: Int, bw: Int, signedA: Boolean, signedB: Boolean) extends Module {
  val io = IO(new Bundle {
    val a = Input(UInt(aw.W)); val b = Input(UInt(bw.W))
    val product = Output(UInt((aw + bw).W))
  })
  io.product := CarrySaveMultiplier.apply(io.a, io.b, signedA, signedB)
}

class CarrySaveMultiplierSpec extends AnyFlatSpec {
  for ((aw, bw, sa, sb) <- Seq((1,1,true,true),(1,3,true,true),(3,1,true,true),(2,3,false,true),(3,2,true,false),
      (4,4,false,false),(4,4,true,true),(5,3,true,true),
      (16,16,false,true),(16,16,true,false),(16,16,true,true),(16,16,false,false))) {
    it should s"preserve products at${aw}x$bw signed=$sa/$sb" in {
      val random = new scala.util.Random(aw * 97 + bw * 19 + (if (sa) 11 else 0) + (if (sb) 13 else 0))
      def signed(bits: BigInt, width: Int, isSigned: Boolean): BigInt =
        if (isSigned && bits.testBit(width - 1)) bits - (BigInt(1) << width) else bits
      def values(width: Int): Vector[BigInt] = Vector(BigInt(0), BigInt(1), BigInt(2),
        (BigInt(1) << (width - 1)) - 1, BigInt(1) << (width - 1), (BigInt(1) << width) - 1).distinct
      val cases = if (aw + bw <= 8) (for (a <- 0 until (1 << aw); b <- 0 until (1 << bw))
        yield (BigInt(a), BigInt(b))).toVector
      else (for (a <- values(aw); b <- values(bw)) yield (a,b)) ++
        Vector.fill(1024)((BigInt(aw, random), BigInt(bw, random)))
      simulate(new CarrySaveMultiplierProbe(aw,bw,sa,sb)) { dut =>
        dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
        for ((a,b) <- cases) {
          dut.io.a.poke(a.U); dut.io.b.poke(b.U); dut.clock.step()
          val expected = (signed(a,aw,sa) * signed(b,bw,sb)) & ((BigInt(1) << (aw+bw)) - 1)
          assert(dut.io.product.peek().litValue == expected, s"a=$a b=$b")
        }
      }
      info(s"operandBits=$aw/$bw signed=$sa/$sb exactProducts=${cases.size}")
    }
  }
}
