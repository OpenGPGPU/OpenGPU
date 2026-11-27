package opengpu.testutil

import java.nio.file.{Files, Paths}
import svsim.{Backend, CommonCompilationSettings, Workspace}

private[testutil] final case class FlashSimCompilationSettings(jobs: Int, shards: Int) extends Backend.Settings {
  require(jobs > 0 && shards >= 0, "FlashSim requires positive build jobs and a nonnegative shard count")
}

/** Runs the stock ChiselSim host protocol against a compiled FlashSim DUT. */
private[testutil] final class FlashSimBackend extends Backend {
  type CompilationSettings = FlashSimCompilationSettings

  override def escapeDefine(string: String): String = string
  override val assertionFailed = "^FlashSim assertion .*$".r

  override def generateParameters(
      outputBinaryName: String,
      topModuleName: String,
      additionalHeaderPaths: Seq[String],
      commonSettings: CommonCompilationSettings,
      backendSpecificSettings: CompilationSettings
  ): Backend.Parameters = {
    require(topModuleName == Workspace.testbenchModuleName, "FlashSim requires the standard svsim testbench")
    require(commonSettings.linkLibraryPaths.isEmpty, "FlashSim cannot link simulator DPI libraries")
    require(commonSettings.simulationSettings.plusArgs.isEmpty, "FlashSim does not support simulation plusargs")
    require(!commonSettings.simulationSettings.enableWavesAtTimeZero, "FlashSim does not support svsim tracing")
    val script = Paths.get("scripts", "compile_flashsim_svsim.py").toAbsolutePath.normalize()
    require(Files.isRegularFile(script), s"FlashSim compiler adapter is missing at $script")
    val python = sys.props.getOrElse("opengpu.flashsimPython", "python3")
    require(python.matches("[a-zA-Z0-9_./-]+"), "FlashSim Python must be a single executable path")
    val defines = commonSettings.verilogPreprocessorDefines.flatMap { define =>
      Seq("--define", define.name + define.value.map("=" + _).getOrElse(""))
    }
    Backend.Parameters(
      python,
      Backend.Parameters.Invocation(
        Seq(
          "-B",
          script.toString,
          "--output",
          outputBinaryName,
          "--jobs",
          backendSpecificSettings.jobs.toString,
          "--shards",
          backendSpecificSettings.shards.toString
        ) ++ defines,
        Seq.empty
      ),
      Backend.Parameters.Invocation(Seq.empty, Seq.empty)
    )
  }
}
