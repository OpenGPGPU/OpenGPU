package opengpu.testutil

import chisel3.RawModule
import chisel3.simulator.{PeekPokeAPI, Randomization, Settings, Simulator}
import svsim.CommonCompilationSettings
import svsim.verilator.Backend

import java.nio.file.{Files, Path, Paths, LinkOption, StandardOpenOption}
import java.security.MessageDigest
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
  * `opengpu.simSeparateBuild=1` finishes Verilation before running make, so
  * the generator releases its memory before C++ compilation. It requires Python 3
  * and preserves the generated model and compiler settings.
  * With caching off,
  * `opengpu.simWorkspace` can isolate temporary files; requested options still
  * apply. Without these properties, the normal flat cache path is unchanged.
  * `opengpu.simBackend=flashsim` selects the synchronous FlashSim backend.
  * It uses the same Chisel test body, the staged assertion layers and the
  * installed FlashSim model compiler.
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
  private val separateBuild = sys.props.get("opengpu.simSeparateBuild").contains("1")
  private val releaseHeap = sys.props.get("opengpu.simReleaseHeap").contains("1")
  private val heapReleaseTool = if (releaseHeap) {
    val tool = Paths.get(sys.props("java.home"), "bin", "jcmd")
    require(Files.isExecutable(tool), "Explicit heap release requires the current JDK's jcmd")
    Some(tool)
  } else None
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
  private val objectCacheShim = binDirectory.resolve("object-cache")
  private val cacheDirectory = root.resolve("cache")

  private val slots = new AtomicInteger(0)

  // The shim also keeps svsim's exported DPI names intact when caching is off.
  private lazy val backend: Backend = locateVerilator() match {
    case Some(verilator) =>
      for (tool <- (if (compilerCache.nonEmpty) Seq("ccache", "python3")
                    else if (separateBuild) Seq("python3") else Seq.empty)) {
        if (!sys.env.getOrElse("PATH", "").split(java.io.File.pathSeparatorChar)
          .exists(dir => Files.isExecutable(Paths.get(dir).resolve(tool))))
          throw new IllegalStateException(s"Explicit native build options require $tool on PATH")
      }
      val shim = binDirectory.resolve("verilator")
      Files.createDirectories(binDirectory)
      if (compilerCache.nonEmpty) {
        Files.write(objectCacheShim, compilerCacheScript.getBytes("UTF-8"))
        objectCacheShim.toFile.setExecutable(true)
      }
      Files.write(shim, shimScript(verilator, cacheDirectory.toAbsolutePath).getBytes("UTF-8"))
      shim.toFile.setExecutable(true)
      new Backend(shim.toAbsolutePath.toString)
    case None if hierarchy || buildJobs.nonEmpty || lowMemBuild || compilerCache.nonEmpty || releaseHeap || separateBuild =>
      throw new IllegalStateException("Explicit simulation options require Verilator on PATH")
    case None =>
      Backend.initializeFromProcessEnvironment()
  }

  private final class Runner(val workspacePath: String, inlineVerification: Boolean = false) extends Simulator[Backend] {
    val backend: Backend = GpuSim.backend
    val tag: String = "verilator"
    val commonCompilationSettings: CommonCompilationSettings = {
      val defaults = CommonCompilationSettings.default
      if (inlineVerification) defaults.copy(
        availableParallelism = buildJobs.map(CommonCompilationSettings.AvailableParallelism.UpTo(_))
          .getOrElse(defaults.availableParallelism),
        verilogPreprocessorDefines = defaults.verilogPreprocessorDefines :+
          CommonCompilationSettings.VerilogPreprocessorDefine("OPENGPU_SIM_INLINE_VERIFICATION", "1"))
      else defaults
    }
    val backendSpecificCompilationSettings: Backend.CompilationSettings = new Backend.CompilationSettings()
  }

  private final class FlashSimRunner(val workspacePath: String) extends Simulator[FlashSimBackend] {
    val backend = new FlashSimBackend
    val tag = "flashsim"
    val commonCompilationSettings: CommonCompilationSettings = CommonCompilationSettings.default
    val backendSpecificCompilationSettings = FlashSimCompilationSettings(
      buildJobs.getOrElse(1), sys.props.getOrElse("opengpu.flashsimShards", "0").toInt)
  }

  /** Simulate `module`, handing `body` the elaborated module exactly like
    * `chisel3.simulator.EphemeralSimulator.simulate`.
    */
  def simulate[T <: RawModule](module: => T)(body: T => Unit): Unit =
    simulateConfigured(module, inlineVerification = false)(body)

  /** Explicit qualification mode for hierarchy tools that cannot resolve bind
    * assertions reaching internal signals across a compiled module boundary.
    * Permanently enables/inlines layers, retaining the assertion conditions.
    * Its compiler define also separates the normal model/cache identity.
    */
  def simulateWithInlineVerification[T <: RawModule](module: => T)(body: T => Unit): Unit =
    simulateConfigured(module, inlineVerification = true)(body)

  /** An explicitly registered next consumer, not a model qualification receipt.
    * `inputBinding` is the caller's immutable source/tool/options binding. The
    * caller must check its contents again before consuming the retained model.
    * Only the executable, ordered port ABI and binding survive normal cleanup.
    */
  final case class RetainedModelConsumer(directory: Path, purpose: String, inputBinding: Path)

  def simulateWithInlineVerificationRetainingModel[T <: RawModule](
      module: => T, consumer: RetainedModelConsumer)(body: T => Unit): Unit =
    simulateConfigured(module, inlineVerification = true, consumer = Some(consumer))(body)

  def publishRetainedModel(workspace: Path, consumer: RetainedModelConsumer,
      ports: Seq[ujson.Value], moduleName: String, completed: Boolean): Unit = {
    val state = prepareConsumer(consumer)
    try state.save(workspace, ports, moduleName, completed)
    catch { case error: Throwable =>
      delete(state.directory)
      throw new IllegalStateException(s"Model publication failed for ${state.directory}", error)
    }
  }

  private def digest(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 255}%02x").mkString

  private def fileDigest(path: Path): String = {
    val hash = MessageDigest.getInstance("SHA-256")
    val in = Files.newInputStream(path)
    try {
      val buffer = new Array[Byte](65536)
      var n = in.read(buffer)
      while (n >= 0) { if (n > 0) hash.update(buffer, 0, n); n = in.read(buffer) }
    } finally in.close()
    hash.digest().map(b => f"${b & 255}%02x").mkString
  }

  private final case class ConsumerState(spec: RetainedModelConsumer, bytes: Array[Byte]) {
    val directory: Path = spec.directory.toAbsolutePath.normalize()
    private val directoryKey = Files.readAttributes(directory, classOf[java.nio.file.attribute.BasicFileAttributes],
      LinkOption.NOFOLLOW_LINKS).fileKey()
    require(directoryKey != null, "model consumer filesystem has no stable directory identity")
    private def checkDirectory(): Unit = {
      require(!Files.isSymbolicLink(directory) && directory.toRealPath() == directory &&
        Files.readAttributes(directory, classOf[java.nio.file.attribute.BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
          .fileKey() == directoryKey, "model consumer directory changed or is aliased")
      val copy = directory.resolve("source-binding.json")
      require(Files.isRegularFile(copy, LinkOption.NOFOLLOW_LINKS) &&
        java.util.Arrays.equals(Files.readAllBytes(copy), bytes), "model consumer copied binding changed")
    }
    def save(workspace: Path, ports: Seq[ujson.Value], moduleName: String, completed: Boolean): Unit = {
      checkDirectory()
      require(java.util.Arrays.equals(Files.readAllBytes(spec.inputBinding), bytes), "model consumer input binding changed")
      val binary = workspace.resolve("workdir-verilator/simulation")
      require(Files.isRegularFile(binary, LinkOption.NOFOLLOW_LINKS) && Files.isExecutable(binary),
        "retained model executable is missing or aliased")
      require(ports.nonEmpty, "retained model has no captured port ABI")
      val binaryHash = fileDigest(binary)
      val retained = directory.resolve("simulation")
      Files.createLink(retained, binary)
      require(Files.isSameFile(retained, binary) && fileDigest(retained) == binaryHash,
        "retained executable differs from the compiled model")
      val stream = Files.walk(workspace)
      val rtl = try stream.iterator().asScala.filter(p => Files.isRegularFile(p) && p.toString.endsWith(".sv"))
        .toVector.sortBy(p => workspace.relativize(p).toString) finally stream.close()
      val rtlHash = MessageDigest.getInstance("SHA-256")
      rtl.foreach { path =>
        rtlHash.update(workspace.relativize(path).toString.getBytes("UTF-8"))
        rtlHash.update(0.toByte)
        rtlHash.update(fileDigest(path).getBytes("US-ASCII"))
        rtlHash.update(0.toByte)
      }
      val metadata = ujson.Obj(
        "schema" -> "opengpu-svsim-active-consumer-v1", "purpose" -> spec.purpose,
        "qualification" -> false, "simulation_body_returned" -> completed,
        "module" -> moduleName, "binary_sha256" -> binaryHash,
        "source_binding_sha256" -> digest(bytes), "port_bindings" -> ujson.Arr.from(ports),
        "rtl_files" -> rtl.size, "rtl_digest" -> rtlHash.digest().map(b => f"${b & 255}%02x").mkString,
        "inline_verification" -> true, "firtool_options" -> ujson.Arr("--default-layer-specialization=enable"),
        "options" -> ujson.Obj("hierarchy" -> ujson.Arr.from(hierarchyModules),
          "build_jobs" -> buildJobs.map(n => ujson.Num(n)).getOrElse(ujson.Null),
          "low_memory" -> lowMemBuild, "optimization" -> lowMemOpt, "separate_build" -> separateBuild,
          "cache_enabled" -> enabled, "compiler_cache" -> compilerCache.map(p => ujson.Str(p.toString)).getOrElse(ujson.Null),
          "release_heap" -> releaseHeap),
        "compile_makefile_sha256" -> fileDigest(workspace.resolve("workdir-verilator/Makefile")),
        "compile_log_sha256" -> fileDigest(workspace.resolve("workdir-verilator/compilation-log.txt")),
        "testbench_sha256" -> fileDigest(workspace.resolve("generated-sources/testbench.sv")))
      val temporary = directory.resolve("binding.tmp")
      Files.write(temporary, ujson.write(metadata, indent = 2).getBytes("UTF-8"), StandardOpenOption.CREATE_NEW)
      checkDirectory()
      require(java.util.Arrays.equals(Files.readAllBytes(spec.inputBinding), bytes), "model consumer input binding changed")
      // A hard-link publication is atomic and never replaces an existing ready record.
      Files.createLink(directory.resolve("binding.json"), temporary)
      Files.delete(temporary)
    }
  }

  private def prepareConsumer(spec: RetainedModelConsumer): ConsumerState = {
    require(spec.purpose.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,95}"), "model consumer purpose must be explicit")
    val target = spec.directory.toAbsolutePath.normalize()
    val binding = spec.inputBinding.toAbsolutePath.normalize()
    require(target.getParent.toRealPath() == target.getParent, "model consumer directory parent is aliased")
    require(!Files.exists(target, LinkOption.NOFOLLOW_LINKS), "model consumer directory already exists")
    require(Files.isRegularFile(binding, LinkOption.NOFOLLOW_LINKS) && binding.toRealPath() == binding,
      "model consumer input binding is missing or aliased")
    val bytes = Files.readAllBytes(binding)
    require(bytes.nonEmpty && bytes.length <= 16 * 1024 * 1024, "model consumer input binding size is invalid")
    Files.createDirectory(target)
    try Files.write(target.resolve("source-binding.json"), bytes, StandardOpenOption.CREATE_NEW)
    catch { case e: Throwable => delete(target); throw e }
    ConsumerState(spec.copy(directory = target, inputBinding = binding), bytes)
  }

  private def simulateConfigured[T <: RawModule](module: => T, inlineVerification: Boolean,
      consumer: Option[RetainedModelConsumer] = None)(body: T => Unit): Unit = {
    val retained = consumer.map(prepareConsumer)
    Files.createDirectories(runDirectory)
    val workspace = runDirectory.resolve(s"slot-${slots.getAndIncrement()}")
    var ports = Seq.empty[ujson.Value]
    var moduleName = ""
    var completed = false
    try {
      sys.props.getOrElse("opengpu.simBackend", "verilator") match {
        case "verilator" =>
          val options = if (inlineVerification) Array("--default-layer-specialization=enable") else Array.empty[String]
          implicit val modification: chisel3.simulator.FirtoolOptionsModifications = new chisel3.simulator.FirtoolOptionsModifications {
            def apply(existing: Array[String]): Array[String] = existing ++ options
          }
          new Runner(workspace.toString, inlineVerification)
            .simulate(module) { simulated =>
              if (retained.nonEmpty) {
                val snapshot = chisel3.simulator.GpuSimModelPortABI.capture(simulated)
                moduleName = snapshot.module
                ports = snapshot.ports.map { port =>
                  ujson.Obj("id" -> port.id, "name" -> port.name, "width" -> port.width,
                    "settable" -> port.settable, "gettable" -> port.gettable)
                }
              }
              body(simulated.wrapped)
            }
            .result
        case "flashsim" if !inlineVerification && retained.isEmpty =>
          new FlashSimRunner(workspace.toString)
            .simulate(module,
              firtoolOpts = Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"),
              settings = Settings.defaultRaw[T].copy(randomization = Randomization.uninitialized)
            )(simulated => body(simulated.wrapped))
            .result
        case "flashsim" =>
          throw new IllegalArgumentException("Inline verification and retained models require the Verilator backend")
        case other => throw new IllegalArgumentException(s"Unknown OpenGPU simulation backend $other")
      }
      completed = true
    } finally {
      retained match {
        case Some(state) if Files.isRegularFile(workspace.resolve("workdir-verilator/simulation")) =>
          try state.save(workspace, ports, moduleName, completed)
          catch { case e: Throwable =>
            throw new IllegalStateException(s"Model retention failed; compiled model remains at $workspace; consumer ${state.directory}", e)
          }
          delete(workspace)
        case Some(state) => delete(workspace); delete(state.directory)
        case None => delete(workspace)
      }
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

  // A restored Clang PCH can reference headers in a deleted simulator
  // workspace. Build PCHs locally; only ordinary object compilations use
  // ccache. Compiler arguments, assertions and optimization stay unchanged.
  private def compilerCacheScript: String =
    """#!/usr/bin/env python3
      |import os
      |import sys
      |args = sys.argv[1:]
      |if not args:
      |    sys.exit('Compiler command required')
      |header = '-emit-pch' in args or any(
      |    (arg == '-x' and i + 1 < len(args) and args[i + 1].endswith('-header')) or
      |    (arg.startswith('-x') and arg.endswith('-header'))
      |    for i, arg in enumerate(args))
      |command = args if header else ['ccache', *args]
      |os.execvp(command[0], command)
      |""".stripMargin

  /** Only path-valued Verilator inputs are normalized. CFLAGS/LDFLAGS are
    * consumed from a different working directory and must remain untouched.
    */
  private def nativeBuildShellFunction: String =
    s"""run_native_verilator() {
      |  python3 - "$$verilator" "$$@" <<'GPU_SIM_RELATIVE_COMPILER_INPUTS'
      |import os
      |import sys
      |import subprocess
      |from pathlib import Path
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
       |# svsim UpTo(n) supplies make flags but leaves Verilator's own -j at 0.
       |# Only the explicit inline qualification mode normalizes this exact pair.
       |inline_jobs = None
       |if '+define+OPENGPU_SIM_INLINE_VERIFICATION=1' in args and ${buildJobs.nonEmpty.toString.capitalize}:
       |    inline_jobs = '${buildJobs.getOrElse(1)}'
       |    if args.count('-MAKEFLAGS') != 1 or args.count('-j') != 1:
       |        sys.exit('Inline qualification requires exact simulator parallelism flags')
       |    make_index, job_index = args.index('-MAKEFLAGS'), args.index('-j')
       |    if make_index + 1 >= len(args) or args[make_index + 1] != '-j ' + inline_jobs or args[job_index + 1] != '0':
       |        sys.exit('Unexpected inline qualification parallelism flags')
       |    args[job_index + 1] = inline_jobs
       |if ${compilerCache.nonEmpty.toString.capitalize}:
       |    args = normalized
      |if not ${separateBuild.toString.capitalize}:
      |    os.execv(sys.argv[1], [sys.argv[1], *args])
      |# svsim supplies --cc --exe --build and an explicit top module. Generate
      |# every hierarchy first, then wait for Verilator to exit before make.
      |def option(name, default=None):
      |    return args[args.index(name) + 1] if name in args else default
      |if not all(flag in args for flag in ('--cc', '--exe', '--build')):
      |    sys.exit('Separate native build requires --cc --exe --build')
      |top = option('--top-module')
       |if not top or ('-MAKEFLAGS' in args and inline_jobs is None) or '--binary' in args:
      |    sys.exit('Unsupported separate native build arguments')
      |mdir = option('--Mdir', 'obj_dir')
      |prefix = option('--prefix', 'V' + top)
      |jobs = option('--build-jobs', '1')
      |if not jobs.isdigit() or int(jobs) < 1:
      |    sys.exit('Separate native build requires positive build jobs')
      |generate = [arg for arg in args if arg != '--build']
      |result = subprocess.run([sys.argv[1], *generate])
      |if result.returncode:
      |    sys.exit(result.returncode if result.returncode > 0 else 128 - result.returncode)
      |if not (Path(mdir) / (prefix + '.mk')).is_file():
      |    sys.exit('Verilator did not produce the expected model makefile')
      |print('[GpuSim build] Verilator exited; compiling generated model', flush=True)
      |os.execvp(os.environ.get('MAKE', 'make'),
      |          [os.environ.get('MAKE', 'make'), '-C', mdir, '-f', prefix + '.mk', '-j', jobs])
      |GPU_SIM_RELATIVE_COMPILER_INPUTS
      |}
      |""".stripMargin

  // The simulator is waiting for this child process, so its completed
  // elaboration can be collected before Verilator allocates native memory.
  // This changes neither generated RTL nor simulator clocks/compilation flags.
  private def releaseHeapCommand: String = heapReleaseTool.map { tool =>
    val quoted = "'" + tool.toString.replace("'", "'\"'\"'") + "'"
    quoted + " " + ProcessHandle.current().pid() + " GC.run >&2 || exit $?\n"
  }.getOrElse("")

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
       |# svsim calls its exported DPI names verbatim. Verilator otherwise
       |# hashes identifiers longer than 128 characters, breaking that ABI.
       |# Keep the complete names; DUT ports, widths and assertions are unchanged.
       |set -- "$$@" --comp-limit-syms 0
       |${compilerCache.map { directory =>
          val quoted = "'" + directory.toString.replace("'", "'\"'\"'") + "'"
          val launcher = "'" + objectCacheShim.toAbsolutePath.toString.replace("'", "'\"'\"'") + "'"
          "export OBJCACHE=" + launcher + "\n" +
            "export CCACHE_DIR=" + quoted + "\n" +
            "export CCACHE_BASEDIR=\"$(cd .. && pwd -P)\"\n" +
            "export CCACHE_COMPILERCHECK=content\n" +
            "export CCACHE_MAXSIZE=512M\n"
        }.getOrElse("")}
       |${if (compilerCache.nonEmpty || separateBuild) nativeBuildShellFunction else ""}
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
       |${if (lowMemBuild) "set -- \"$@\" --output-groups 0 --output-split 10000 --output-split-cfuncs 200 -CFLAGS -O" + lowMemOpt else ""}
       |${if (!enabled) {
          releaseHeapCommand + (if (compilerCache.nonEmpty || separateBuild) "run_native_verilator \"$@\"; exit $?"
          else "exec \"$verilator\" \"$@\"")
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
       |$releaseHeapCommand
       |${if (compilerCache.nonEmpty || separateBuild) "run_native_verilator \"$@\"" else "\"$verilator\" \"$@\""}
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
