package opengpu.testutil

import chisel3.RawModule
import chisel3.simulator.{PeekPokeAPI, Simulator}
import svsim.CommonCompilationSettings
import svsim.verilator.Backend

import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters._

/** Cached drop-in replacement for `chisel3.simulator.EphemeralSimulator.simulate`.
  *
  * Every `EphemeralSimulator.simulate` call elaborates the design with firtool and
  * then hands the RTL to Verilator in a private temporary directory, so the C++
  * model is rebuilt from scratch.  Compilation, not simulation, is what this test
  * suite spends its time on: the module suites call `simulate` several hundred
  * times, and even a trivial two-lane ALU costs a few seconds per call.
  *
  * This harness leaves the Chisel elaboration path alone and only interposes on
  * the Verilator executable.  `GpuSim` points the simulator at a generated shim
  * that keys the compiled model by a digest of the Verilog sources and the
  * compiler flags, so simulating an unchanged design again hard-links the cached
  * executable instead of rebuilding it.  Peeking, poking, stepping and the
  * assertion checks keep running through the stock `chisel3.simulator.Simulator`
  * implementation, and a stale cache is impossible because changed RTL produces
  * a different digest.
  *
  * The cache lives under `target/chiselsim`, so `sbt clean` drops it.  Pass
  * `-Dopengpu.simCache=off` to run against the real Verilator without caching,
  * and set `GPU_SIM_DEBUG=1` to log every build the shim restores or publishes.
  * Optional `opengpu.simHierarchy=GpuComputeUnit` compiles repeated modules
  * as hierarchy libraries; its flags and control file enter the cache key.
  * Assertions stay enabled; UNOPTFLAT is suppressed at hierarchical DPI
  * boundaries. `opengpu.simJobs=N` bounds build parallelism.
  * `opengpu.simLowMem=1` splits the generated C++ and compiles it at `-O0`,
  * which keeps g++ inside the RAM left beside the JVM on a 16 GB runner.
  * `opengpu.simLowMemOpt=1` retains splitting with modest optimization for
  * measured profiles; the default remains `0` for wider memory-sensitive builds.
  * `opengpu.simCcache=DIR` separately enables compiler-object reuse across fresh
  * model builds. It requires ccache, bounds that directory to 512 MB and checks
  * compiler content. Python 3 makes Verilator input paths workspace-relative;
  * compiler flags remain unchanged and diagnostics use build-relative paths.
  * This mode has its own executable-cache namespace. The default does not enable
  * compiler-object caching.
  * With caching off,
  * `opengpu.simWorkspace` can isolate temporary files; requested options still
  * apply. Without these properties, the normal flat cache path is unchanged.
  */
object GpuSim extends PeekPokeAPI {

  private val hierarchyModules = sys.props.get("opengpu.simHierarchy").toSeq
    .flatMap(_.split(",")).filter(_.nonEmpty).distinct.sorted
  require(hierarchyModules.forall(_.matches("[A-Za-z_][A-Za-z0-9_]*")),
    "hierarchy modules must be plain Verilog module identifiers")
  private val hierarchy = hierarchyModules.nonEmpty
  private val buildJobs = sys.props.get("opengpu.simJobs").map(_.toInt)
  require(buildJobs.forall(_ > 0), "simulation build jobs must be positive")
  private val lowMemBuild = sys.props.get("opengpu.simLowMem").contains("1")
  private val lowMemOpt = sys.props.getOrElse("opengpu.simLowMemOpt", "0")
  require(Set("0", "1").contains(lowMemOpt), "low-memory compiler optimization must be 0 or 1")
  private val compilerCache = sys.props.get("opengpu.simCcache").filter(_ != "off").map { path =>
    require(path.nonEmpty, "compiler cache directory must be nonempty")
    Paths.get(path).toAbsolutePath.normalize()
  }
  private val cacheSetting = sys.props.get("opengpu.simCache")
  private val enabled = !cacheSetting.contains("off")
  private val root = Paths.get(sys.props.get("opengpu.simWorkspace").getOrElse(
    cacheSetting.filter(_ => enabled).getOrElse("target/chiselsim")))
  // Only compiled executables are shared. A shim embeds this JVM's options,
  // and svsim removes/rebuilds workspace contents, so neither may be reused by
  // another JVM. In particular, truncating a running shell script can corrupt
  // the remainder of its input after Verilator returns.
  private val runDirectory = root.resolve("runs").resolve(ProcessHandle.current().pid().toString)
  private val binDirectory = runDirectory.resolve("bin")
  private val cacheDirectory = root.resolve("cache")

  private val slots = new AtomicInteger(0)

  private val backend: Backend = if (enabled || hierarchy || buildJobs.nonEmpty || lowMemBuild || compilerCache.nonEmpty) locateVerilator() match {
    case Some(verilator) =>
      if (compilerCache.nonEmpty) for (tool <- Seq("ccache", "python3")) {
        if (!sys.env.getOrElse("PATH", "").split(java.io.File.pathSeparatorChar)
          .exists(dir => Files.isExecutable(Paths.get(dir).resolve(tool))))
          throw new IllegalStateException(s"Explicit compiler caching requires $tool on PATH")
      }
      val shim = binDirectory.resolve("verilator")
      Files.createDirectories(binDirectory)
      Files.write(shim, shimScript(verilator, cacheDirectory.toAbsolutePath).getBytes("UTF-8"))
      shim.toFile.setExecutable(true)
      new Backend(shim.toAbsolutePath.toString)
    case None if hierarchy || buildJobs.nonEmpty || lowMemBuild || compilerCache.nonEmpty =>
      throw new IllegalStateException("Explicit simulation options require Verilator on PATH")
    case None =>
      Backend.initializeFromProcessEnvironment()
  } else {
    Backend.initializeFromProcessEnvironment()
  }

  private final class Runner(val workspacePath: String) extends Simulator[Backend] {
    val backend: Backend = GpuSim.backend
    val tag: String = "verilator"
    val commonCompilationSettings: CommonCompilationSettings = CommonCompilationSettings.default
    val backendSpecificCompilationSettings: Backend.CompilationSettings = new Backend.CompilationSettings()
  }

  /** Simulate `module`, handing `body` the elaborated module exactly like
    * `chisel3.simulator.EphemeralSimulator.simulate`.
    */
  def simulate[T <: RawModule](module: => T)(body: T => Unit): Unit = {
    Files.createDirectories(runDirectory)
    val workspace = runDirectory.resolve(s"slot-${slots.getAndIncrement()}")
    try {
      new Runner(workspace.toString)
        .simulate(module)(simulated => body(simulated.wrapped))
        .result
    } finally {
      delete(workspace)
    }
  }

  private def locateVerilator(): Option[String] = {
    val absoluteBin = binDirectory.toAbsolutePath
    sys.env
      .getOrElse("PATH", "")
      .split(java.io.File.pathSeparatorChar)
      .iterator
      .map(dir => Paths.get(dir).resolve("verilator"))
      .filter(p => Files.isExecutable(p) && !p.toAbsolutePath.startsWith(absoluteBin))
      .map(_.toAbsolutePath.toString)
      .toSeq
      .headOption
  }

  /** Only path-valued Verilator inputs are normalized. CFLAGS/LDFLAGS are
    * consumed from a different working directory and must remain untouched.
    */
  private def compilerCacheShellFunction: String =
    """run_compiler_cached_verilator() {
      |  python3 - "$verilator" "$@" <<'GPU_SIM_RELATIVE_COMPILER_INPUTS'
      |import os
      |import sys
      |args = sys.argv[2:]
      |cwd = os.getcwd()
      |prefixes = {os.path.dirname(cwd)}
      |for index, arg in enumerate(args[:-1]):
      |    if arg == '-F' and os.path.isabs(args[index + 1]):
      |        parent = os.path.dirname(args[index + 1])
      |        try:
      |            if os.path.samefile(parent, cwd):
      |                prefixes.add(os.path.dirname(parent))
      |        except OSError:
      |            pass
      |def relative(value):
      |    for prefix in sorted(prefixes, key=len, reverse=True):
      |        value = value.replace(prefix + os.sep, '../')
      |    return value
      |path_flags = {'-F', '-f', '-y', '-I', '--Mdir'}
      |normalized = []
      |for index, arg in enumerate(args):
      |    if arg.startswith('+incdir+') or (index and args[index - 1] in path_flags):
      |        arg = relative(arg)
      |    normalized.append(arg)
      |os.execv(sys.argv[1], [sys.argv[1], *normalized])
      |GPU_SIM_RELATIVE_COMPILER_INPUTS
      |}
      |""".stripMargin

  /** A Verilator front end that serves a content-addressed cache of built models.
    *
    * svsim runs Verilator in `<workspace>/workdir-verilator` and expects the
    * executable named by `-o` to appear next to it.  The shim digests the files
    * in the `-F` filelist together with the compiler flags, with the workspace
    * prefix stripped so the digest does not depend on where the run happened, and
    * then either restores a previously built executable or runs Verilator and
    * publishes the result.  Anything unexpected falls back to plain Verilator.
    */
  private def shimScript(verilator: String, cache: Path): String =
    s"""#!/bin/sh
       |# Generated by opengpu.testutil.GpuSim.
       |verilator='$verilator'
       |cache='$cache'
       |log="$cache/shim.log"
       |case "$${1:-}" in --version) exec "$$verilator" "$$@" ;; esac
       |${compilerCache.map { directory =>
          val quoted = "'" + directory.toString.replace("'", "'\"'\"'") + "'"
          "export OBJCACHE=ccache\n" +
            "export CCACHE_DIR=" + quoted + "\n" +
            "export CCACHE_BASEDIR=\"$(cd .. && pwd -P)\"\n" +
            "export CCACHE_COMPILERCHECK=content\n" +
            "export CCACHE_MAXSIZE=512M\n"
        }.getOrElse("")}
       |${if (compilerCache.nonEmpty) compilerCacheShellFunction else ""}
       |${if (hierarchy) {
          val control = "`verilator_config\n" + hierarchyModules.map(m =>
            "hier_block -module \"" + m + "\"\n").mkString
          "cat > gpu-sim-hierarchy.vlt <<'GPU_SIM_HIERARCHY_CONTROL'\n" +
            control + "GPU_SIM_HIERARCHY_CONTROL\n" +
            "assertions=0\nfor arg in \"$@\"; do\n" +
            "  case \"$arg\" in --assert) assertions=1 ;; --no-assert) exit 2 ;; esac\ndone\n" +
            "[ \"$assertions\" = 1 ] || { echo 'GpuSim hierarchy requires assertions' >&2; exit 2; }\n" +
            "set -- \"$@\" --hierarchical -Wno-UNOPTFLAT gpu-sim-hierarchy.vlt"
        } else ""}
       |${buildJobs.map(n => "set -- \"$@\" --build-jobs " + n).getOrElse("")}
       |${if (lowMemBuild) "set -- \"$@\" --output-split 10000 --output-split-cfuncs 200 -CFLAGS -O" + lowMemOpt else ""}
       |${if (!enabled) {
          if (compilerCache.nonEmpty) "run_compiler_cached_verilator \"$@\"; exit $?"
          else "exec \"$verilator\" \"$@\""
        } else ""}
       |out=''
       |mdir=''
       |filelist=''
       |prev=''
       |for arg in "$$@"; do
       |  case "$$prev" in
       |    -o) out=$$arg ;;
       |    --Mdir) mdir=$$arg ;;
       |    -F) filelist=$$arg ;;
       |  esac
       |  case "$$arg" in
       |    -o|--Mdir|-F) prev=$$arg ;;
       |    *) prev='' ;;
       |  esac
       |done
       |[ -n "$$mdir" ] || mdir='verilated-sources'
       |[ -n "$$out" ] || out='../simulation'
       |mkdir -p "$$mdir" 2>/dev/null
       |workspace=$$(cd .. 2>/dev/null && pwd)
       |physical=$$(cd .. 2>/dev/null && pwd -P)
       |dest=$$(cd "$$mdir/$$(dirname "$$out")" 2>/dev/null && pwd)/$$(basename "$$out")
       |key=$$(
       |  {
       |${if (compilerCache.nonEmpty) "    printf '%s\\n' compiler-relative-inputs-v1" else ""}
       |    "$$verilator" --version 2>/dev/null
       |    printf '%s\\n' "$$@" | sed "s|$$workspace/|WS/|g; s|$$physical/|WS/|g"
       |${if (hierarchy) "    cksum < gpu-sim-hierarchy.vlt" else ""}
       |    if [ -n "$$filelist" ] && [ -f "$$filelist" ]; then
       |      while IFS= read -r f; do
       |        f=$$(printf '%s' "$$f" | sed "s|$$workspace/||g; s|$$physical/||g")
       |        [ -f "$$f" ] || continue
       |        printf '%s %s\\n' "$$f" "$$(cksum < "$$f" | cut -d' ' -f1,2)"
       |      done < "$$filelist"
       |    fi
       |  } 2>/dev/null | cksum | cut -d' ' -f1,2 | tr -d ' \\n'
       |)
       |hit="$$cache/$$key"
       |mkdir -p "$$cache" 2>/dev/null
       |if [ -n "$$key" ] && [ -x "$$hit" ]; then
       |  if mkdir -p "$$(dirname "$$dest")" 2>/dev/null &&
       |     { ln -f "$$hit" "$$dest" 2>/dev/null || cp "$$hit" "$$dest" 2>/dev/null; } &&
       |     chmod +x "$$dest" 2>/dev/null && [ -x "$$dest" ]; then
       |    if [ -n "$${GPU_SIM_DEBUG:-}" ]; then printf 'hit %s\\n' "$$key" >> "$$log"; fi
       |    exit 0
       |  fi
       |fi
       |${if (compilerCache.nonEmpty) "run_compiler_cached_verilator \"$@\"" else "\"$verilator\" \"$@\""}
       |status=$$?
       |if [ "$$status" = 0 ] && [ -n "$$key" ] && [ -x "$$dest" ]; then
       |  tmp="$$hit.tmp.$$$$"
       |  if cp "$$dest" "$$tmp" 2>/dev/null && chmod +x "$$tmp" 2>/dev/null &&
       |     mv -f "$$tmp" "$$hit" 2>/dev/null; then
       |    if [ -n "$${GPU_SIM_DEBUG:-}" ]; then printf 'store %s\\n' "$$key" >> "$$log"; fi
       |  else
       |    rm -f "$$tmp"
       |  fi
       |else
       |  if [ -n "$${GPU_SIM_DEBUG:-}" ]; then
       |    printf 'miss status=%s key=%s dest=%s\\n' "$$status" "$$key" "$$dest" >> "$$log"
       |  fi
       |fi
       |exit "$$status"
       |""".stripMargin

  private def delete(path: Path): Unit = {
    if (Files.exists(path)) {
      val stream = Files.walk(path)
      try {
        stream
          .sorted(java.util.Comparator.reverseOrder[Path]())
          .iterator()
          .asScala
          .foreach(p => Files.deleteIfExists(p))
      } finally stream.close()
    }
  }
}
