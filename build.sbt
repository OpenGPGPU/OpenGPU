name := "gpu-chisel"

version := "0.1.0"

scalaVersion := "2.13.18"

val chiselVersion = "7.15.0"

addCompilerPlugin("org.chipsalliance" % "chisel-plugin" % chiselVersion cross CrossVersion.full)

libraryDependencies ++= Seq(
  "org.chipsalliance" %% "chisel" % chiselVersion,
  "org.scalatest" %% "scalatest" % "3.2.19" % "test"
)

Compile / scalacOptions ++= Seq("-language:reflectiveCalls", "-Ymacro-annotations")

Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-oD")

lazy val scalaAutofixFiles = taskKey[Option[Seq[File]]]("Files selected by scripts/scala_autofix.sh")
lazy val scalaFormatReplayConfig = taskKey[Unit]("Verify and prepare the config for a formatting replay")

scalaFormatReplayConfig := ScalaFormatReplay.prepareConfig(
  file(sys.env("SCALA_FORMAT_BASE_CONFIG")),
  scalafmtConfig.value,
  file(sys.env("SCALA_FORMAT_REPLAY_CONFIG"))
)

scalaAutofixFiles := {
  val root = baseDirectory.value
  sys.env.get("SCALA_AUTOFIX_FILES").map { path =>
    IO.read(file(path)).split('\u0000').toSeq.map(root / _)
  }
}

// The scoped run feeds all selected files to Scalafmt, including build files and standalone scripts.
Compile / scalafmt / unmanagedSources :=
  scalaAutofixFiles.value.getOrElse((Compile / unmanagedSources).value)

Seq(Compile, Test).map { config =>
  config / scalafix / unmanagedSources := {
    val sources = (config / unmanagedSources).value
    scalaAutofixFiles.value.fold(sources)(files => sources.filter(files.toSet))
  }
}

// FloatFMA is vendored at src/main/scala/yunsuan/fpu/FloatFMA.scala (a local
// copy of YunSuan's FMA with an explicit intermediate register for the
// infinity-sign selection, used by the FpuBackend timing closure) so the
// build is self-contained and reproducible.  Its small compatibility
// definitions live in src/main/scala/yunsuan/FmaCompatibility.scala; the
// rest of YunSuan is not pulled into this build.
