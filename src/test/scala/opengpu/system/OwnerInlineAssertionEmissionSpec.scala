package opengpu.system

import chisel3._
import circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

private class OwnerInlineAssertionSentinel extends Module {
  val enabled = IO(Input(Bool()))
  val okay = IO(Input(Bool()))
  val previous = RegNext(okay, false.B)
  when(enabled) {
    assert(okay, "owner inline current value")
    assert(previous, "owner inline private register")
  }
}

class OwnerInlineAssertionEmissionSpec extends AnyFlatSpec with Matchers {
  "The complete sample owner emission" should "keep both checks inside their owning module" in {
    val bound = ChiselStage.emitSystemVerilog(new OwnerInlineAssertionSentinel,
      firtoolOpts = PrepareOwnerResourceSampleHost.firtoolOptions(false))
    val inline = ChiselStage.emitSystemVerilog(new OwnerInlineAssertionSentinel,
      firtoolOpts = PrepareOwnerResourceSampleHost.firtoolOptions(true))
    bound should include ("_Verification_Assert")
    inline should not include "_Verification_Assert"
    inline should not include "bind "
    for (message <- Seq("owner inline current value", "owner inline private register")) {
      bound should include (message)
      inline should include (message)
    }
  }

  it should "preserve the complete resource manifest and explicitly label inline checks" in {
    val bound = ujson.read(PrepareOwnerResourceSampleHost.manifest(rtlEmitted = true))
    val inline = ujson.read(PrepareOwnerResourceSampleHost.manifest(
      rtlEmitted = true, inlineAssertions = true))
    inline.obj.remove("assertionMode") should be (Some(ujson.Str("inline")))
    inline should be (bound)
    inline("requiredDiscovery").arr.size should be (112)
  }
}
