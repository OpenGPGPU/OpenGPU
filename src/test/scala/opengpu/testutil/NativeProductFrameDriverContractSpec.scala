package opengpu.testutil

import opengpu.graphics.NativeFourEngineDefaultHostAxiSpec
import opengpu.system.{NativeSparseFrameSmokeQualification,NativeProductFrame720Qualification,QualifyNativeProductFrames}
import org.scalatest.flatspec.AnyFlatSpec

class NativeProductFrameDriverContractSpec extends AnyFlatSpec {
  behavior of "explicit native frame qualification"

  private class DefaultProbe extends NativeFourEngineDefaultHostAxiSpec {
    def contract: (Boolean, Int, Int, Int, Seq[Boolean]) =
      (sparseFrameBacking, frameServiceCycleLimit, frameWidth, frameHeight, payloadModes)
  }

  it should "retain the original ordinary fixture defaults and one-million-cycle guard" in {
    assert(new DefaultProbe().contract == ((false, 1000000, 32, 32, Seq(true, false))))
  }

  it should "retain both ordinary cases and exactly one complete opt-in case per profile" in {
    val ordinary = new DefaultProbe().testNames
    assert(ordinary.size == 2)
    assert(ordinary.exists(_.endsWith("local=true")) && ordinary.exists(_.endsWith("local=false")))
    assert(new NativeSparseFrameSmokeQualification().testNames.size == 1)
    assert(new NativeProductFrame720Qualification().testNames.size == 1)
  }

  it should "select smoke and product execution only with matching explicit flags" in {
    for (flag <- Seq(None, Some("0")))
      assert(!QualifyNativeProductFrames.productMode(Array("--smoke", "--reports", "out"), Some("1"), flag))
    assert(QualifyNativeProductFrames.productMode(Array("--product", "--reports", "out"), Some("1"), Some("1")))
  }

  it should "reject missing authorization flags, mismatched modes and case filters before hardware construction" in {
    for (full <- Seq(None, Some("0")); flag <- Seq(None, Some("0"), Some("1")))
      intercept[IllegalArgumentException] {
        QualifyNativeProductFrames.productMode(Array("--smoke", "--reports", "out"), full, flag)
      }
    for (flag <- Seq(None, Some("0"), Some("invalid")))
      intercept[IllegalArgumentException] {
        QualifyNativeProductFrames.productMode(Array("--product", "--reports", "out"), Some("1"), flag)
      }
    intercept[IllegalArgumentException] {
      QualifyNativeProductFrames.productMode(Array("--smoke", "--reports", "out"), Some("1"), Some("1"))
    }
    for (args <- Seq(Array.empty[String], Array("--unknown", "--reports", "out"),
      Array("--product", "--reports", "out", "-z", "4x")))
      intercept[IllegalArgumentException] {
        QualifyNativeProductFrames.productMode(args, Some("1"), Some("1"))
      }
  }
}
