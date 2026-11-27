package opengpu.testutil
import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import scala.jdk.CollectionConverters._
private class GpuSimInlineVerificationSentinel extends Module {
  val io = IO(new Bundle { val a = Input(Bool()); val b = Input(Bool()); val echo = Output(UInt(7.W)) })
  val previous = RegNext(io.a, false.B)
  io.echo := chisel3.util.Cat("b101".U(3.W), io.a, io.b, previous, false.B)
  assert(!previous || io.b, "INLINE_HELPER_INTERNAL_STATE")
  assert(!io.a || io.b, "INLINE_HELPER_PORT")
}
class GpuSimInlineVerificationSpec extends AnyFlatSpec {
  private def remove(path: Path): Unit = {
    val walk = Files.walk(path)
    try walk.iterator().asScala.toVector.reverse.foreach(Files.deleteIfExists)
    finally walk.close()
  }
  private def withConsumer(body: (Path, RetainedModelConsumer) => Unit): Unit = {
    val root = Files.createTempDirectory("gpu-model-consumer-").toRealPath()
    val input = root.resolve("inputs.json"); Files.writeString(input, "{\"leaf_source\":\"explicit-test-fixture\"}")
    try body(root, RetainedModelConsumer(root.resolve("active"), "leaf-host-replay", input))
    finally remove(root)
  }
  private def sha(path: Path): String = MessageDigest
    .getInstance("SHA-256")
    .digest(Files.readAllBytes(path))
    .map(b => f"${b & 255}%02x")
    .mkString
  def run(mode: Int): Unit = GpuSim.simulateWithInlineVerification(new GpuSimInlineVerificationSentinel) { d =>
    d.io.a.poke(false.B); d.io.b.poke(true.B); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
    if (mode == 1) { d.io.a.poke(true.B); d.io.b.poke(false.B); d.clock.step() }
    d.io.a.poke(true.B); d.io.b.poke(true.B); d.clock.step()
    d.io.a.poke(false.B); d.io.b.poke((mode != 2).B); d.clock.step()
  }
  it should "keep healthy inline hierarchy assertions" in { run(0) }
  it should "reject a false port assertion in inline hierarchy" in {
    val failure = intercept[chisel3.simulator.Exceptions.AssertionFailed] { run(1) }
    assert(failure.getMessage.contains("INLINE_HELPER_PORT"))
  }
  it should "reject a false internal-state assertion in inline hierarchy" in {
    val failure = intercept[chisel3.simulator.Exceptions.AssertionFailed] { run(2) }
    assert(failure.getMessage.contains("INLINE_HELPER_INTERNAL_STATE"))
  }
  it should "retain only an explicit next-consumer model and replay its real ordered ABI" in {
    withConsumer { (root, consumer) =>
      GpuSim.simulateWithInlineVerificationRetainingModel(new GpuSimInlineVerificationSentinel, consumer) { d =>
        d.io.a.poke(false.B); d.io.b.poke(true.B); d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B);
        d.clock.step(2)
      }
      val dir = consumer.directory; val binding = ujson.read(Files.readString(dir.resolve("binding.json")))
      assert(binding("schema").str == "opengpu-svsim-active-consumer-v1" && !binding("qualification").bool)
      assert(binding("simulation_body_returned").bool)
      assert(binding("binary_sha256").str == sha(dir.resolve("simulation")))
      assert(binding("source_binding_sha256").str == sha(consumer.inputBinding))
      assert(
        java.util.Arrays
          .equals(Files.readAllBytes(consumer.inputBinding), Files.readAllBytes(dir.resolve("source-binding.json")))
      )
      val files = Files.list(dir);
      try
        assert(
          files.iterator().asScala.map(_.getFileName.toString).toSet ==
            Set("simulation", "binding.json", "source-binding.json")
        )
      finally files.close()
      val ports = binding("port_bindings").arr.toVector
      assert(ports.map(_("id").num.toInt) == ports.indices.toVector)
      assert(ports.map(_("name").str).toSet == Set("clock", "reset", "io_a", "io_b", "io_echo"))
      val echo = ports.find(_("name").str == "io_echo").get
      assert(echo("width").num == 7 && !echo("settable").bool && echo("gettable").bool)
      val info = svsim.ModuleInfo(
        binding("module").str,
        ports.map(p => svsim.ModuleInfo.Port(p("name").str, p("settable").bool, p("gettable").bool))
      )
      svsim.GpuSimRetainedReplay.run(dir.toString, info) { c =>
        ports.foreach { p =>
          val actual = c.port(p("name").str); assert(actual.get().bitCount == p("width").num.toInt)
          assert(actual.info.isSettable == p("settable").bool && actual.info.isGettable == p("gettable").bool)
        }
        c.port("io_a").set(BigInt(0)); c.port("io_b").set(BigInt(1)); c.port("reset").set(BigInt(1))
        c.port("clock").tick(1, 2, BigInt(1), BigInt(0)); c.port("reset").set(BigInt(0))
        c.port("clock").tick(1, 8, BigInt(1), BigInt(0)); assert(c.port("io_echo").get().asBigInt == 84)
        c.completeInFlightCommands()
      }
      // Replay must retain the actual inlined assertion, not merely start a process.
      val failed = intercept[Throwable] {
        svsim.GpuSimRetainedReplay.run(dir.toString, info) { c =>
          c.port("io_a").set(BigInt(0)); c.port("io_b").set(BigInt(1)); c.port("reset").set(BigInt(1))
          c.port("clock").tick(1, 2, BigInt(1), BigInt(0)); c.port("reset").set(BigInt(0))
          c.port("io_a").set(BigInt(1)); c.port("io_b").set(BigInt(0))
          c.port("clock").tick(1, 1, BigInt(1), BigInt(0)); c.completeInFlightCommands()
        }
      }
      assert(Files.readString(dir.resolve("simulation-log.txt")).contains("INLINE_HELPER_PORT"), failed.toString)
    }
  }
  it should "reject existing or aliased consumer paths before elaborating" in {
    withConsumer { (root, consumer) =>
      var evaluated = 0
      def module = { evaluated += 1; new GpuSimInlineVerificationSentinel }
      Files.createDirectory(consumer.directory)
      intercept[IllegalArgumentException] {
        GpuSim.simulateWithInlineVerificationRetainingModel(module, consumer)(_ => ())
      }
      Files.delete(consumer.directory); Files.createSymbolicLink(consumer.directory, root)
      intercept[IllegalArgumentException] {
        GpuSim.simulateWithInlineVerificationRetainingModel(module, consumer)(_ => ())
      }
      Files.delete(consumer.directory)
      val link = root.resolve("alias"); Files.createSymbolicLink(link, root)
      intercept[IllegalArgumentException] {
        GpuSim.simulateWithInlineVerificationRetainingModel(module, consumer.copy(directory = link.resolve("active")))(
          _ => ()
        )
      }
      val inputAlias = root.resolve("input-alias"); Files.createSymbolicLink(inputAlias, consumer.inputBinding)
      intercept[IllegalArgumentException] {
        GpuSim.simulateWithInlineVerificationRetainingModel(module, consumer.copy(inputBinding = inputAlias))(_ => ())
      }
      assert(evaluated == 0, "bad consumer path reached elaboration")
    }
  }
  it should "preserve the unique compiled workspace when consumer binding changes" in {
    withConsumer { (_, consumer) =>
      val failure = intercept[IllegalStateException] {
        GpuSim.simulateWithInlineVerificationRetainingModel(new GpuSimInlineVerificationSentinel, consumer) { d =>
          d.io.a.poke(false.B); d.io.b.poke(true.B); d.reset.poke(true.B); d.clock.step(2)
          Files.writeString(consumer.inputBinding, "changed")
        }
      }
      assert(failure.getCause.getMessage.contains("model consumer input binding changed"))
      assert(!Files.exists(consumer.directory.resolve("binding.json")))
      val prefix = "Model retention failed; compiled model remains at "
      assert(failure.getMessage.startsWith(prefix))
      val workspace = java.nio.file.Paths.get(failure.getMessage.stripPrefix(prefix).split("; consumer ")(0))
      assert(
        Files.isExecutable(workspace.resolve("workdir-verilator/simulation")),
        "saving failure deleted the only executable"
      )
      remove(workspace)
    }
  }
}
