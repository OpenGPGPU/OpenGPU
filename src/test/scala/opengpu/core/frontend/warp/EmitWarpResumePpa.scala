package opengpu.core.frontend.warp

import circt.stage.ChiselStage
import java.nio.file.{Files, Paths}
import opengpu.config.GpuConfig

/** Local component timing input; no simulator or routine test entry point. */
object EmitWarpResumePpa {
  def main(args: Array[String]): Unit = {
    require(sys.props.get("opengpu.warpResumePpa").contains("1"))
    require(args.length == 1, "usage: EmitWarpResumePpa <new-output-parent>")
    val config = GpuConfig()
    require(config.lanes == 16 && config.warps == 8)
    for ((name, forward) <- Seq("original" -> false, "candidate" -> true)) {
      val target = Paths.get(args(0), name)
      require(!Files.exists(target), s"refusing to replace $target")
      ChiselStage.emitSystemVerilogFile(
        new WarpScheduler(config, resumeToIssue = forward),
        Array("--target-dir", target.toString),
        Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"))
    }
  }
}
