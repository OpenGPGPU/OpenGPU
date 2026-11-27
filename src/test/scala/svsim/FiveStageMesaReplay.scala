package svsim

import chisel3._
import scala.collection.mutable
import scala.language.dynamics
import scala.jdk.CollectionConverters._
import java.nio.file.{Files, Path, Paths, LinkOption}
import opengpu.testutil.GpuSim

object FiveStageMesaReplay {
  private var controller: Simulation.Controller = null
  private var entries = Vector.empty[ujson.Value]
  private var ports = Map.empty[String, Simulation.Port]
  private var dirty = false
  private val groups = mutable.Map.empty[String, Vector[ujson.Value]]
  private def members(prefix: String) = groups.getOrElseUpdate(
    prefix,
    entries.filter(e => e("name").str == prefix || e("name").str.startsWith(prefix + "_"))
  )
  private def flush(): Unit = if (dirty) { controller.run(0); dirty = false }
  case class Snapshot(values: Vector[(String, BigInt, Int)]) {
    def litValue: BigInt = values.foldLeft(BigInt(0)) { case (a, (_, v, w)) => (a << w) | v }
    def litToBoolean: Boolean = { require(values.size == 1); litValue != 0 }
  }
  case class Node(prefix: String) extends Dynamic {
    def selectDynamic(name: String): Node = Node(prefix + "_" + name)
    def apply(i: Int): Node = Node(prefix + "_" + i)
    def applyDynamic(name: String)(i: Int): Node = Node(prefix + "_" + name + "_" + i)
    def poke(x: Bits): Unit = poke(x.litValue)
    def poke(x: BigInt): Unit = {
      require(ports.contains(prefix), s"not a leaf $prefix")
      val p = ports(prefix);
      require(
        p.info.isSettable && x >= 0 && x.bitLength <= entries.find(_("name").str == prefix).get("width").num.toInt,
        s"not a legal input $prefix=$x"
      )
      p.set(x); dirty = true
    }
    def peek(): Snapshot = {
      flush(); val es = members(prefix); require(es.nonEmpty, s"unknown port/group $prefix")
      require(es.forall(_("gettable").bool), s"unreadable aggregate $prefix")
      Snapshot(es.map { e =>
        val n = e("name").str; (n.drop(prefix.length), ports(n).get().asBigInt, e("width").num.toInt)
      })
    }
    def expect(x: BigInt): Unit = { val a = peek().litValue; assert(a == x, s"$prefix expected=$x observed=$a") }
    def step(n: Int = 1): Unit = { require(prefix == "clock"); flush(); ports(prefix).tick(1, n, BigInt(1), BigInt(0)) }
  }
  private val io = Node("io"); private val clock = Node("clock"); private val reset = Node("reset")
  private def zero(d: Node): Unit =
    members(d.prefix).filter(_("settable").bool).foreach(e => Node(e("name").str).poke(BigInt(0)))
  private def yes(d: Node) = d.peek().litToBoolean
  private def value(d: Node) = d.peek().litValue
  private def integer(j: ujson.Value) = BigInt(j.str)
  private def number(j: ujson.Value) = BigInt(j.num.toLong)
  private def sha(p: Path): String = {
    val md = java.security.MessageDigest.getInstance("SHA-256"); val in = Files.newInputStream(p)
    try {
      val b = new Array[Byte](65536); var n = in.read(b);
      while (n >= 0) { if (n > 0) md.update(b, 0, n); n = in.read(b) }
    } finally in.close()
    md.digest().map(b => f"${b & 255}%02x").mkString
  }
  private def regular(p: Path): Unit =
    require(p.toRealPath() == p && Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS), s"aliased/nonregular $p")
  private def boundInput(p: Path, h: String, tools: ujson.Value): Unit = {
    val matches = tools.obj.values.filter(t => t("entry").str == p.toString).toVector
    require(matches.size <= 1, s"ambiguous bound tool $p")
    if (matches.isEmpty) regular(p)
    else {
      val t = matches.head; val target = Paths.get(t("target").str)
      regular(target); require(p.toRealPath() == target, s"retargeted bound tool $p")
      val alias = if (Files.isSymbolicLink(p)) ujson.Str(Files.readSymbolicLink(p).toString) else ujson.Null
      require(alias == t("alias"), s"changed tool alias $p")
      require(sha(target) == t("sha256").str && h == t("sha256").str, s"changed tool target SHA $p")
    }
    require(sha(p) == h, s"changed bound source/class/tool/input $p")
  }

  private class Memory(prefix: String, val bytes: mutable.Map[BigInt, Int]) {
    val port = Node(prefix)
    case class Pending(address: BigInt, write: Boolean, data: BigInt, mask: BigInt, tag: BigInt, due: Int)
    val pending = mutable.ArrayBuffer.empty[Pending]; val seen = mutable.ArrayBuffer.empty[Pending]
    var cycle = 0; var reply = Option.empty[Pending]; var held = Option.empty[Snapshot]
    var delay = 5; var blockAddress = Option.empty[BigInt]
    var checkWrite: (BigInt, Int) => Unit = (_, _) => ()
    def idle = pending.isEmpty && reply.isEmpty && !yes(port.memoryRequest.valid)
    def known(a: BigInt, n: Int): Unit = for (i <- 0 until n)
      assert(bytes.contains(a + i), s"$prefix unmapped backing byte ${a + i}")
    def word(a: BigInt): BigInt = {
      known(a, 4); (0 until 4).foldLeft(BigInt(0))((v, i) => v | (BigInt(bytes(a + i)) << (i * 8)))
    }
    def line(a: BigInt): BigInt = {
      known(a, 64); (0 until 64).foldLeft(BigInt(0))((v, i) => v | (BigInt(bytes(a + i)) << (i * 8)))
    }
    def putWord(a: BigInt, v: BigInt): Unit = for (i <- 0 until 4) bytes(a + i) = ((v >> (i * 8)) & 255).toInt
    def drive(): Unit = {
      port.memoryRequest.ready.poke((cycle % 5 != 0).B)
      if (reply.isEmpty) {
        val i = pending.lastIndexWhere(r => r.due <= cycle && !blockAddress.contains(r.address));
        if (i >= 0) reply = Some(pending.remove(i))
      }
      port.memoryResponse.valid.poke(reply.nonEmpty.B)
      reply.foreach { r =>
        val b = port.memoryResponse.bits; b.transactionId.poke(r.tag); b.fault.poke(false.B);
        b.readData.poke(line(r.address))
      }
    }
    def capture(): Unit = {
      val q = port.memoryRequest; val v = yes(q.valid)
      val now = if (v || held.nonEmpty) Some(q.bits.peek()) else None
      held.foreach(s => assert(v && now.get == s, s"$prefix changed a held real memory offer"))
      held = if (v && !yes(q.ready)) now else None
      if (v && yes(q.ready)) {
        val b = q.bits;
        val r = Pending(
          value(b.address),
          yes(b.isWrite),
          value(b.writeData),
          value(b.byteMask),
          value(b.transactionId),
          cycle + delay
        )
        assert((r.address & 63) == 0, s"unaligned lower line ${r.address}")
        known(r.address, 64)
        assert(
          !pending.exists(_.tag == r.tag) && !reply.exists(_.tag == r.tag),
          s"duplicate $prefix transaction ${r.tag}"
        )
        if (r.write)
          for (i <- 0 until 64 if r.mask.testBit(i)) checkWrite(r.address + i, ((r.data >> (i * 8)) & 255).toInt)
        seen += r; pending += r
      }
      if (reply.nonEmpty && yes(port.memoryResponse.ready)) {
        val r = reply.get
        if (r.write) for (i <- 0 until 64 if r.mask.testBit(i)) bytes(r.address + i) = ((r.data >> (i * 8)) & 255).toInt
        reply = None
      };
      cycle += 1
    }
  }

  private def requireOptions(binding: ujson.Value): Unit = {
    require(
      binding("schema").str == "opengpu-svsim-active-consumer-v1" && binding(
        "simulation_body_returned"
      ).bool && !binding("qualification").bool
    )
    require(
      binding("module").str == "FiveStageIsolineMesaTop" && binding(
        "purpose"
      ).str == "original-linked-five-isoline-no-tf"
    )
    require(
      binding("inline_verification").bool && binding("firtool_options").arr.map(_.str).toVector == Vector(
        "--default-layer-specialization=enable"
      )
    )
    val o = binding("options");
    require(
      o("hierarchy").arr.map(_.str).toVector == Vector("GpuComputeUnit") && o("optimization").str == "0" &&
        o("build_jobs").num == 1 && o("low_memory").bool && o("separate_build").bool && o("release_heap").bool && !o(
          "cache_enabled"
        ).bool && o("compiler_cache") == ujson.Null
    )
  }
  private def validatePlan(plan: ujson.Value): Unit = {
    require(plan("schema").str == "opengpu-original-five-runtime-v1" && plan("cpuOnly").bool)
    require(
      plan("recordBytes").num == 1472 && plan("cases").arr
        .map(c => (c("name").str, c("width").num.toInt))
        .toVector == Vector(("full5", 4), ("pointcoord", 4))
    )
    require(plan("inputRoot").str.nonEmpty)
    plan("originalInputs").obj.keys.foreach(n =>
      require(Paths.get(n).isAbsolute, "runtime original input must be absolute")
    )
    plan("cases").arr.zipWithIndex.foreach { case (c, i) =>
      require(
        c("expectedGsPrimitives").num == i && c("expectedOriginalTesPrimitives").num == 1 && c(
          "taskCount"
        ).num == 1 && c("tasks").arr.size == 1
      )
      require(c("images").arr.size == 4 && c("fsScratchBases").arr.size == 4 && c("codePcs").arr.size == 5)
      c("images").arr.foreach { im =>
        require(
          im("guardedBytes").num == 896 && im("storedBytes").num == 1024 &&
            im("expectedWithGuardsHex").str.length == 1792 && im("expectedStoredHex").str.length == 2048
        )
      }
      require(
        !c.obj.keys.exists(k => k.contains("feedback") || k.contains("Tf")),
        s"transform-feedback plan field in ${c("name").str}"
      )
    }
  }
  private def validateAssignments(plan: ujson.Value): Unit = {
    val byName = entries.map(e => e("name").str -> e).toMap
    require(!byName.keys.exists(_.contains("feedback")), "retained model exposes transform-feedback ports")
    def assignment(j: ujson.Value): Unit = j.obj.foreach { case (n, v) =>
      val e = byName.getOrElse(n, throw new IllegalArgumentException(s"absent actual model input: $n"));
      val a = integer(v)
      require(e("settable").bool && a >= 0 && a.bitLength <= e("width").num.toInt, s"assignment direction/width $n=$a")
    }
    plan("cases").arr.foreach { c => assignment(c("begin")); c("tasks").arr.foreach(t => assignment(t("assign"))) }
    for (
      n <- Vector("clock", "reset", "io_begin_valid", "io_task_valid", "io_end_valid", "io_cancel", "io_frameDrained")
    ) require(byName(n)("settable").bool)
    for (
      n <- Vector(
        "io_begin_ready",
        "io_task_ready",
        "io_taskCompletion_valid",
        "io_completion_valid",
        "io_idle",
        "io_owned",
        "io_traps",
        "io_sourceDrained",
        "io_taskDrained",
        "io_poolDrained",
        "io_poolReserved",
        "io_launch_valid",
        "io_completion_bits_img_image_status",
        "io_completion_bits_img_image_faultStage",
        "io_completion_bits_img_image_faultTileValid",
        "io_completion_bits_img_image_faultTile",
        "io_completion_bits_img_image_faultAddress"
      )
    ) require(byName(n)("gettable").bool)
    for (c <- 0 until 2) {
      require(byName(s"io_writeback_${c}_valid")("gettable").bool); require(byName(s"io_tags_$c")("gettable").bool)
    }
  }

  private def run(plan: ujson.Value): Unit = {
    zero(io); reset.poke(true.B); clock.step(3); reset.poke(false.B)
    val bytes = mutable.Map.empty[BigInt, Int]
    val memory = new Memory("io", bytes)
    val immutable = mutable.Map.empty[BigInt, Int]
    val allowed = mutable.ArrayBuffer.empty[(BigInt, BigInt)]
    val codeStages = mutable.Map.empty[BigInt, Int]
    def install(a: BigInt, b: Seq[Byte], ro: Boolean): Unit = b.zipWithIndex.foreach { case (v, i) =>
      val address = a + i; val data = v & 255
      if (bytes.contains(address)) require(bytes(address) == data, s"conflicting preloaded original byte $address")
      bytes(address) = data; if (ro) immutable(address) = data
    }
    def fill(a: BigInt, n: Int, v: Int): Unit = {
      require(!(0 until n).exists(i => bytes.contains(a + i)), s"overlapping allocation $a/$n");
      for (i <- 0 until n) bytes(a + i) = v
    }
    def reserve(a: BigInt, n: Int, guards: Boolean = true): Unit = {
      fill(a - (if (guards) 64 else 0), n + (if (guards) 128 else 0), 0xa5); allowed += ((a, a + n))
    }
    def wordBytes(words: ujson.Value): Vector[Byte] =
      words.arr.toVector.flatMap(w => (0 until 4).map(i => ((number(w) >> (8 * i)) & 255).toByte))
    val stages = Vector("vs", "tcs", "tes", "gs", "fs")
    val inputFiles =
      plan("originalInputs").obj.map { case (n, _) => Paths.get(n) }.map(p => p.getFileName.toString -> p).toMap
    require(inputFiles.size == plan("originalInputs").obj.size, "duplicate original input basename")
    plan("cases").arr.foreach { c =>
      val name = c("name").str
      c("codePcs").arr.zip(stages).zipWithIndex.foreach { case ((pc, s), stage) =>
        val a = number(pc); val raw = Files.readAllBytes(inputFiles(name + "." + s + ".words"))
        install(a, raw.toVector ++ Vector.fill((64 - raw.length % 64) % 64)(0.toByte), true); codeStages(a) = stage
      }
      val privateImage = java.nio.ByteBuffer
        .wrap(Files.readAllBytes(inputFiles(name + ".uniform-private.bin")))
        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
      privateImage.position(44)
      for (_ <- 0 until 3) {
        val a = BigInt(privateImage.getInt().toLong & 0xffffffffL); val n = privateImage.getInt();
        val b = new Array[Byte](n); privateImage.get(b); install(a, b.toVector, true)
      }
      require(!privateImage.hasRemaining)
      install(number(c("vertexBase")), wordBytes(c("vertexWords")), true);
      install(number(c("gfsBase")), wordBytes(c("gfsWords")), true)
      for (
        (b, n) <- Vector(
          ("vsScratchBase", "vsScratchBytes"),
          ("arenaBase", "arenaBytes"),
          ("poolBase", "poolBytes"),
          ("directoryBase", "directoryBytes")
        )
      ) reserve(number(c(b)), c(n).num.toInt)
      c("fsScratchBases").arr.foreach(a => reserve(number(a), c("fsScratchBytes").num.toInt))
      for (k <- Vector("directory", "pool", "arena")) {
        val a = number(c(k + "Base")); for (i <- 0 until c(k + "Bytes").num.toInt) bytes(a + i) = 0
      }
      c("images").arr.foreach { im =>
        val base = number(im("base")); reserve(base, im("storedBytes").num.toInt, guards = false);
        fill(base - 64, 64, 0xa5)
      }
    }
    val blocker = BigInt(0x700000); reserve(blocker, 64); memory.putWord(blocker, 0x12345678)
    val computePc = BigInt(0x710000)
    val computeWords =
      Vector(BigInt("00700337", 16), BigInt("12300393", 16), BigInt("00732023", 16), BigInt("30500073", 16))
    install(
      computePc,
      computeWords.flatMap(w => (0 until 4).map(i => ((w >> (8 * i)) & 255).toByte)) ++ Vector.fill(48)(0.toByte),
      true
    )
    def checkWrite(a: BigInt, v: Int): Unit = {
      assert(!immutable.contains(a), s"real request writes original RO source at $a")
      assert(allowed.exists { case (b, e) => a >= b && a < e }, s"real request writes outside precise allocation $a")
    }
    memory.checkWrite = checkWrite
    var computeDone = false; var cycles = 0
    val accepted = mutable.Map.empty[BigInt, (Int, Int)]
    val launches = mutable.ArrayBuffer.empty[(Int, BigInt, Int)]
    val writebacks = mutable.ArrayBuffer.empty[(Int, Int, BigInt, BigInt, BigInt, Int)]
    val tiles = mutable.ArrayBuffer.empty[Snapshot]
    def tick(): Unit = {
      io.frameDrained.poke(memory.idle.B); memory.drive()
      if (cycles % 2000 == 0)
        println(
          s"FIVE_STAGE_PROGRESS cycle=$cycles lower=${memory.seen.size} pending=${memory.pending.size} wb=${writebacks.size} owned=${value(io.owned)} traps=${value(io.traps)} beginReady=${yes(io.begin.ready)} beginValid=${yes(io.begin.valid)} taskReady=${yes(io.task.ready)} taskValid=${yes(io.task.valid)} completion=${yes(io.taskCompletion.valid)} launch=${yes(io.launch.valid)} busy=${yes(io.busy)} frameDrained=${yes(io.frameDrained)}"
        )
      for (c <- 0 until 2 if yes(io.writeback(c).valid) && value(io.owned).testBit(c)) {
        val tag = value(io.tags(c));
        val stage =
          accepted.getOrElse(tag, throw new AssertionError(s"graphics WB without accepted launch tag=$tag"))._1
        writebacks += ((c, stage, tag, value(io.writeback(c).bits.rd), value(io.writeback(c).bits.data), cycles))
      }
      if (yes(io.launch.valid)) {
        val pc = value(io.launch.bits.pc); require(codeStages.contains(pc), s"unexpected graphics kernel PC $pc")
        val tag = value(io.launch.bits.tag); accepted(tag) = (codeStages(pc), cycles);
        launches += ((codeStages(pc), tag, cycles))
      }
      for (e <- 0 until 4 if yes(io.tileDone(e).valid)) {
        io.tileDone(e).bits.status.expect(0); tiles += io.tileDone(e).bits.peek()
      }
      for (e <- 0 until 4 if yes(io.primitiveDone(e).valid)) io.primitiveDone(e).bits.status.expect(0)
      if (yes(io.computeDone.valid)) {
        io.computeDone.bits.commandId.expect(99); io.computeDone.bits.success.expect(1); computeDone = true
      }
      memory.capture(); clock.step(); cycles += 1; io.traps.expect(0)
    }
    def until(label: String)(p: => Boolean): Unit = {
      var n = 0; while (!p && n < 1000000) { tick(); n += 1 };
      assert(p, s"$label no actual progress at cycle=$cycles pending=${memory.pending.size}")
    }
    def assignments(j: ujson.Value): Unit = j.obj.foreach { case (k, v) => Node(k).poke(integer(v)) }
    def handshake(port: Node, label: String): Unit = {
      until(label)(yes(port.ready)); port.valid.poke(true.B); tick(); port.valid.poke(false.B);
      println(s"FIVE_STAGE_HANDSHAKE $label valid=${yes(port.valid)} ready=${yes(port.ready)} cycle=$cycles")
    }
    def hold(port: Node): Unit = {
      val snapshot = port.bits.peek();
      for (_ <- 0 until 5) {
        tick(); assert(yes(port.valid) && port.bits.peek() == snapshot, s"$port report changed while held")
      }
    }
    def computeBlocked(): Boolean = !computeDone && memory.pending.exists(r =>
      r.address == blocker && r.write
    ) && value(io.active(0)) != 0 && memory.word(blocker) == 0x12345678
    io.context.poke(101); io.computeDone.ready.poke(true.B)
    for (e <- 0 until 4) {
      io.out(e).ready.poke(true.B); io.tileDone(e).ready.poke(true.B); io.primitiveDone(e).ready.poke(true.B)
    }
    for ((c, ci) <- plan("cases").arr.zipWithIndex) {
      val name = c("name").str; val width = c("width").num.toInt; val work = number(c("expectedGsPrimitives"))
      val firstWb = writebacks.size; val firstLaunch = launches.size; val firstTile = tiles.size
      zero(io.begin.bits); assignments(c("begin")); handshake(io.begin, s"$name/$width actual begin")
      if (ci == 1) {
        memory.blockAddress = Some(blocker); zero(io.compute.bits); io.compute.bits.commandId.poke(99);
        io.compute.bits.launch.kernelPc.poke(computePc)
        for (a <- 0 until 3) { io.compute.bits.launch.gridSize(a).poke(1); io.compute.bits.launch.localSize(a).poke(1) }
        handshake(io.compute, "real compute owner admission");
        until("real compute SW outstanding")(memory.pending.exists(r => r.address == blocker && r.write))
        assert(computeBlocked(), "SW ACK failed to retain actual CU0")
      }
      for ((task, index) <- c("tasks").arr.zipWithIndex) {
        zero(io.task.bits); assignments(task("assign")); handshake(io.task, "actual original VS/TC/TE/GS tuple")
        until("actual tuple report")(yes(io.taskCompletion.valid)); val r = io.taskCompletion.bits
        r.status.expect(0); r.taskId.expect(number(task("taskId"))); r.tupleRejected.expect(0);
        r.rawSourceValid.expect(1); r.ownerValid.expect(1)
        r.owner.status.expect(0); r.owner.image.status.expect(0); r.owner.image.published.expect(1)
        r.owner.source.status.expect(0); r.owner.source.vertices.expect(2); r.owner.source.words.expect(16)
        r.owner.source.source.batches.expect(1); r.owner.source.source.localBatches.expect(1);
        r.owner.source.source.spilledBatches.expect(0)
        r.source.status.expect(0); r.source.sourceValid.expect(1); r.source.publicationValid.expect(1);
        r.source.actualGsPublished.expect(if (name == "full5") BigInt(0) else BigInt(1))
        r.source.received.expect(if (name == "full5") BigInt(0) else BigInt(1)); r.source.dropped.expect(0);
        r.source.admitted.expect(if (name == "full5") BigInt(0) else BigInt(1))
        r.source.firstSequence.expect(if (name == "full5") BigInt(0) else BigInt(index));
        r.source.nextSequence.expect(if (name == "full5") BigInt(0) else BigInt(index + 1))
        r.source.nextGsSequence.expect(BigInt(3000 + index) + (if (name == "full5") BigInt(0) else BigInt(1)))
        r.source.source.sourceValid.expect(1); r.source.source.source.status.expect(0)
        hold(io.taskCompletion); io.taskCompletion.ready.poke(true.B); tick(); io.taskCompletion.ready.poke(false.B)
        if (ci == 1 && index == 0) {
          assert(computeBlocked(), "compute blocker retired before CU1 graphics writebacks")
          until("CU1 actual original stage writebacks before release")(
            writebacks.drop(firstWb).exists(w => w._1 == 1 && w._2 == 0) &&
              writebacks.drop(firstWb).exists(w => w._1 == 1 && w._2 == 1) && writebacks
                .drop(firstWb)
                .exists(w => w._1 == 1 && w._2 == 2) &&
              writebacks.drop(firstWb).exists(w => w._1 == 1 && w._2 == 3)
          )
          assert(computeBlocked(), "compute blocker retired while waiting for CU1 graphics writebacks")
          for (stage <- 0 until 4)
            assert(
              writebacks.drop(firstWb).exists(w => w._1 == 1 && w._2 == stage),
              s"CU1 missing actual original stage$stage committed WB before ACK release"
            )
          memory.blockAddress = None; until("real compute store retirement")(computeDone);
          assert(memory.word(blocker) == 0x123)
          for (i <- 4 until 64) assert(bytes(blocker + i) == 0xa5)
        }
      }
      io.end.bits.poke(true.B); handshake(io.end, "actual end barrier");
      until("actual frame report")(yes(io.completion.valid))
      val f = io.completion.bits; f.status.expect(0); f.taskFaultValid.expect(0); f.img.image.status.expect(0)
      f.img.image.faultTileValid.expect(0)
      f.img.actualGsPublished.expect(work); f.img.received.expect(work); f.img.dropped.expect(0);
      f.img.admitted.expect(work); f.img.tasks.expect(number(c("taskCount")))
      f.img.image.source.tasks.expect(number(c("taskCount"))); f.img.image.tiles.expect(1);
      f.img.image.primitives.expect(work)
      f.img.image.storedBytes.expect(BigInt(4 * width * width * 16))
      hold(io.completion); io.completion.ready.poke(true.B); tick(); io.completion.ready.poke(false.B)
      until("whole actual frame/payload drain")(
        yes(io.idle) && yes(io.taskDrained) && yes(io.sourceDrained) && yes(io.poolDrained) && memory.idle
      )
      io.poolReserved.expect(0)
      val actualStages = writebacks.drop(firstWb).map(_._2).toSet
      for (stage <- 0 until (if (name == "full5") 4 else 5))
        assert(actualStages(stage), s"original stage$stage has no committed graphics writeback")
      if (name == "full5")
        assert(!launches.drop(firstLaunch).exists(_._1 == 4), "zero-output original GS unexpectedly invoked FS")
      else assert(launches.drop(firstLaunch).exists(_._1 == 4), "nonempty GS window did not execute original FS")
      assert(tiles.size - firstTile == 1)
      c("images").arr.foreach { im =>
        val expected = im("expectedStoredHex").str.grouped(2).map(Integer.parseInt(_, 16)).toVector
        val guarded = im("expectedWithGuardsHex").str.grouped(2).map(Integer.parseInt(_, 16)).toVector
        require(expected.take(guarded.size) == guarded)
        val base = number(im("base")) - 64;
        expected.zipWithIndex.foreach { case (v, i) =>
          assert(
            bytes(base + i) == v,
            s"all-MRT original point/clear/padding/guard mismatch case=$name/$width address=${base + i} expected=$v observed=${bytes(base + i)}"
          )
        }
        for (i <- expected.size until im("storedBytes").num.toInt + 64)
          assert(bytes(base + i) == 0xa5, s"stored image tail changed $name/$width address=${base + i}")
      }
      immutable.foreach { case (a, v) => assert(bytes(a) == v, s"original immutable byte changed $a") }
      println(
        s"ACTUAL_ORIGINAL_FIVE_STAGE_FRAME name=$name size=$width task=${c("taskCount")} GS=$work tiles=1 FS=${actualStages(4)} noTf=true cycles=$cycles"
      )
    }
    for (c <- 0 until 2)
      assert(writebacks.exists(_._1 == c), s"physical CU$c has no actual graphics committed writeback")
    assert(memory.idle && value(io.traps) == 0)
    println(
      s"ACTUAL_ORIGINAL_FIVE_STAGE_ISOLINE_PASS cases=2 noReset=true noTf=true physicalGraphicsCUs=2 cycles=$cycles committedGraphicsWB=${writebacks.size}"
    )
  }

  def retainResetIdle(workspace: String, model: String, source: String, shapePath: String): Unit = {
    val shape = ujson.read(Files.readString(Paths.get(shapePath)))
    val names = shape.obj.keys.toVector.sorted
    val ids = Files
      .readAllLines(Paths.get(workspace).resolve("generated-sources/c-dpi-bridge.cpp"))
      .asScala
      .flatMap { line =>
        val text = line.trim
        if (text.startsWith("case ") && text.contains(": // ")) {
          val id = text.stripPrefix("case ").takeWhile(_.isDigit).toInt
          Some(text.split(": // ", 2)(1) -> id)
        } else None
      }
      .toMap
    require(
      ids.keySet == names.toSet && ids.values.toSet == names.indices.toSet,
      s"generated model port IDs differ from Bundle shape names=${names.size} ids=${ids.size} unique=${ids.values.toSet.size} missing=${(names.toSet -- ids.keySet).size} extra=${(ids.keySet -- names.toSet).size} range=${ids.values.min}/${ids.values.max}"
    )
    val bindings = names.sortBy(ids).map { name =>
      val leaf = shape(name);
      ujson.Obj(
        "id" -> ujson.Num(ids(name)),
        "name" -> ujson.Str(name),
        "width" -> leaf("width"),
        "settable" -> leaf("settable"),
        "gettable" -> true
      )
    }
    val info = ModuleInfo(
      "FiveStageIsolineMesaTop",
      bindings.map { b => ModuleInfo.Port(b("name").str, b("settable").bool, b("gettable").bool) }
    )
    GpuSimRetainedReplay.run(Paths.get(workspace).resolve("workdir-verilator").toString, info) { c =>
      controller = c; entries = bindings; ports = entries.map(e => e("name").str -> c.port(e("name").str)).toMap
      entries.foreach { e =>
        val p = ports(e("name").str);
        require(
          p.info.isSettable == e("settable").bool && p.info.isGettable == e("gettable").bool && p.get().bitCount == e(
            "width"
          ).num.toInt,
          s"retained port ${e("name")} shape=${e("width")} actual=${p.get().bitCount} settable=${p.info.isSettable}/${e("settable")} gettable=${p.info.isGettable}/${e("gettable")}"
        )
      }
      zero(io); io.frameDrained.poke(true.B); reset.poke(true.B); clock.step(3); reset.poke(false.B); clock.step(5)
      io.traps.expect(0); io.busy.expect(0); io.sourceLive.expect(0); io.sourceDrained.expect(1)
      io.poolReserved.expect(0); io.poolDrained.expect(1)
      GpuSim.publishRetainedModel(
        Paths.get(workspace),
        GpuSim.RetainedModelConsumer(Paths.get(model), "original-linked-five-isoline-no-tf", Paths.get(source)),
        bindings,
        "FiveStageIsolineMesaTop",
        true
      )
      println("FIVE_STAGE_MODEL_RESET_IDLE_ONLY originalShaderExecuted=false noTf=true physicalGraphicsCUs=2")
    }
  }

  def main(args: Array[String]): Unit = {
    if (args.toVector == Vector("--cpu-memory-test")) {
      val b = mutable.Map.empty[BigInt, Int]; for (i <- 0 until 64) b(BigInt(i)) = i
      val m = new Memory("cpu-only", b)
      assert(m.word(0) == BigInt("03020100", 16) && m.word(60) == BigInt("3f3e3d3c", 16)); m.line(0)
      def rejected(f: => Unit): Unit = {
        val caught = try { f; false }
        catch { case _: AssertionError => true };
        require(caught, "unknown backing byte accepted")
      }
      rejected { m.word(61); () }; rejected { m.line(64); () }; b.remove(31); rejected { m.line(0); () }
      println("FIVE_BACKING_CPU_PASS explicitTail=true word/line/holeRejects=3 nativeSpawn=false GPU=false"); return
    }
    if (args.length == 2 && args(0) == "--cpu-bound-input-test") {
      val fixture = ujson.read(Files.readString(Paths.get(args(1)))); var rejected = 0
      for (c <- fixture("cases").arr) {
        val passed = try { boundInput(Paths.get(c("path").str), c("sha256").str, c("tools")); true }
        catch { case _: IllegalArgumentException => false }
        require(passed == c("accepted").bool, s"bound input gate mismatch ${c("name").str}"); if (!passed) rejected += 1
      }
      println(s"FIVE_BOUND_TOOL_CPU_PASS cases=${fixture("cases").arr.size} rejects=$rejected nativeSpawn=false");
      return
    }
    if (args.length == 5 && args(0) == "--retain-reset-idle") {
      retainResetIdle(args(1), args(2), args(3), args(4)); return
    }
    require(args.length == 4, "model-directory replay-directory runtime-plan.json host-binding.json")
    val model = Paths.get(args(0)).toAbsolutePath.normalize();
    val replay = Paths.get(args(1)).toAbsolutePath.normalize()
    val planPath = Paths.get(args(2)).toAbsolutePath.normalize();
    val guardPath = Paths.get(args(3)).toAbsolutePath.normalize()
    for (
      p <- Vector(
        model.resolve("simulation"),
        model.resolve("binding.json"),
        model.resolve("source-binding.json"),
        replay.resolve("simulation"),
        replay.resolve("source-binding.json"),
        planPath,
        guardPath
      )
    ) regular(p)
    val b = ujson.read(Files.readString(model.resolve("binding.json"))); requireOptions(b)
    require(
      sha(model.resolve("simulation")) == b("binary_sha256").str && sha(model.resolve("source-binding.json")) == b(
        "source_binding_sha256"
      ).str &&
        sha(replay.resolve("simulation")) == b("binary_sha256").str && sha(replay.resolve("source-binding.json")) == b(
          "source_binding_sha256"
        ).str
    )
    val source = ujson.read(Files.readString(model.resolve("source-binding.json")))
    require(
      source("schema").str == "opengpu-original-five-isoline-no-tf-v1" && !source("transformFeedback").bool && !source(
        "abi10"
      ).bool
    )
    require(
      source("physicalGraphicsCUs").num == 2 && source("hostRoute").str == "direct-typed-isoline" && source(
        "recordBytes"
      ).num == 1472
    )
    require(
      source("cases").arr.map(c => (c("name").str, c("width").num.toInt)).toVector == Vector(
        ("full5", 4),
        ("pointcoord", 4)
      )
    )
    require(
      source("properties")("opengpu.productQualification").str == "0" && source("supported_graphics").arr
        .map(_.num.toInt)
        .toVector == Vector(640, 480)
    )
    val plan = ujson.read(Files.readString(planPath)); validatePlan(plan)
    val guard = ujson.read(Files.readString(guardPath));
    require(guard("schema").str == "private-five-host-bound-inputs-v1")
    require(
      guard("runtimePlanSha256").str == sha(planPath) && guard("modelBindingSha256").str == sha(
        model.resolve("binding.json")
      )
    )
    def check(): Unit = {
      for (
        collection <- Vector(guard("inputs"), plan("originalInputs"), source("inputs")); (file, h) <- collection.obj
      ) { val p = Paths.get(file); boundInput(p, h.str, source("tools")) }
    }
    check(); entries = b("port_bindings").arr.toVector
    require(
      entries.nonEmpty && entries
        .map(_("id").num.toInt) == entries.indices.toVector && entries.map(_("name").str).distinct.size == entries.size
    )
    validateAssignments(plan)
    val info = ModuleInfo(
      b("module").str,
      entries.map(e => ModuleInfo.Port(e("name").str, e("settable").bool, e("gettable").bool))
    )
    GpuSimRetainedReplay.run(replay.toString, info) { c =>
      controller = c; ports = entries.map(e => e("name").str -> c.port(e("name").str)).toMap
      entries.foreach { e =>
        val p = ports(e("name").str);
        require(
          p.info.isSettable == e("settable").bool && p.info.isGettable == e("gettable").bool && p.get().bitCount == e(
            "width"
          ).num.toInt
        )
      }
      run(plan); c.completeInFlightCommands(); check()
      require(
        sha(model.resolve("simulation")) == b("binary_sha256").str && sha(model.resolve("source-binding.json")) == b(
          "source_binding_sha256"
        ).str
      )
    }
  }
}
