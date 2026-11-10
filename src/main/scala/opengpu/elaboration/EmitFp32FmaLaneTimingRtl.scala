package opengpu.elaboration

import opengpu.core.execute.fpu.Fp32FmaLane

object EmitFp32FmaLaneTimingRtl {
  def main(args: Array[String]): Unit = {
    val targetDir = args.headOption.getOrElse("timing/generated/fpu/yunsuan_lane")
    EmitRtl.emit(new Fp32FmaLane(), targetDir)
  }
}
