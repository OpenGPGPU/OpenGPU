package opengpu.elaboration

import opengpu.util.PipelinedSignedDivider

object EmitLegacyInterpolationDivider {
  def main(args: Array[String]): Unit = {
    require(args.length <= 1, "EmitLegacyInterpolationDivider [target-dir]")
    EmitRtl.emit(new PipelinedSignedDivider(),
      args.headOption.getOrElse("generated/legacy-interpolation-divider"))
  }
}
