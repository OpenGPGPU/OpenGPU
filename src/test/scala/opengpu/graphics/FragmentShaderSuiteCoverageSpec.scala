package opengpu.graphics

import org.scalatest.flatspec.AnyFlatSpec

class FragmentShaderSuiteCoverageSpec extends AnyFlatSpec {
  it should "keep real fragment smoke cases and the complete local qualification registered" in {
    val routine=new FragmentShaderStageSpec().testNames
    val full=new FragmentShaderStageQualificationSpec().testNames
    assert(routine.size==3 && routine.subsetOf(full))
    assert(routine.exists(_.contains("deliver captured geometry metadata")))
    assert(routine.exists(_.contains("fragment quads through real shared CUs local=true")))
    assert(routine.exists(_.contains("fragment quads through real shared CUs local=false")))
    assert(full.size>=30)
    assert(full.exists(_.contains("drain staging, shader and malformed export faults")))
    assert(full.exists(_.contains("execute projective samples")))
    assert(full.exists(_.contains("execute native uniform blocks")))
  }
}
