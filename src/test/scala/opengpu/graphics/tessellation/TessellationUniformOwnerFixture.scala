package opengpu.graphics.tessellation

import java.io.{BufferedOutputStream, DataOutputStream}
import java.nio.file.{Files, Path, Paths}

import opengpu.command.GpuCommandOpcode
import opengpu.config.GpuConfig
import opengpu.graphics.{FragmentInputAbi, FragmentShaderConfig, GpuCommandMmioRegs,
  NativeBinnedGraphicsConfig, NativeBinnedGraphicsFrameRecord, NativeBinnedGraphicsLayout,
  NativeTypedGraphicsLayout, RenderHostRegs, TransformFeedbackAction, TypedFragmentState,
  TypedFragmentStateLayout, VertexShaderStageConfig}
import opengpu.system._
import opengpu.testutil.{NativeFrameMemoryCodec, PagedFrameMemory}

import scala.collection.mutable

/** Produces only host uploads and independently expected feedback for the full-owner replay. */
object TessellationUniformOwnerFixture {
  private type Bytes = mutable.Map[Long, Int]
  private type Writes = Vector[(Int, Long)]
  private final case class Phase(id: Int, opcode: Int, writes: Writes, after: Writes = Vector.empty,
    reads: Vector[Long] = Vector.empty, upload: Boolean = true)
  private final case class UniformRead(root: Long, defaultBinding: Long, defaultLine: Long)
  private final case class Draw(patch: Bytes, reads: Vector[Long], uniformReads: Vector[UniformRead])

  private val drawOffset = 0x8e0000L
  private val codeOffset = 0x1000L
  private val feedbackRecord = 0x18400000L
  private val feedbackBuffer = 0x1880003cL
  private val feedbackRecordVa = 0x30400000L
  private val feedbackBufferVa = 0x3080003cL
  private val satp = 0x80000030L
  private val context = 0x71000007L
  private val cookie = 101L
  private val program = 9L
  private val one = 0x3f800000L
  private val cease = 0x30500073L
  private val tessellate = GpuCommandOpcode.tessellationJob.litValue.toInt
  private val invalidate = GpuCommandOpcode.invalidate.litValue.toInt
  private val feedback = GpuCommandOpcode.transformFeedback.litValue.toInt

  private def word(bytes: Bytes, address: Long, value: Long): Unit =
    (0 until 4).foreach(i => bytes(address+i) = ((value >>> (i*8)) & 255).toInt)

  private def readWord(bytes: Bytes, address: Long): Long =
    (0 until 4).foldLeft(0L)((value, i) => value | (bytes.getOrElse(address+i, 0).toLong << (i*8)))

  private def words(bytes: Bytes, address: Long, values: Seq[Long]): Unit =
    values.zipWithIndex.foreach { case (value, i) => word(bytes, address+4*i, value) }

  private def writePatch(bytes: Bytes, file: Path): Unit = {
    val memory = new PagedFrameMemory
    val ordered = bytes.keysIterator.toVector.sorted
    var first = 0
    while (first < ordered.size) {
      var end = first+1
      while (end < ordered.size && ordered(end) == ordered(end-1)+1) end += 1
      memory.mapRange(ordered(first), ordered(end-1)+1, 0)
      first = end
    }
    ordered.foreach(address => memory.writeByte(address, bytes(address)))
    NativeFrameMemoryCodec.write(memory, file)
  }

  private def fourEngineFixture(source: mutable.Map[BigInt, Int]): Bytes = {
    val bytes = mutable.Map.from(source.iterator.map { case (address, value) => address.longValue -> value })
    val native = NativeBinnedGraphicsConfig.fourEngine
    val fragment = FragmentShaderConfig(2, 4)
    val stateFields = TypedFragmentStateLayout.fields(new TypedFragmentState(fragment))
    val frameFields = NativeBinnedGraphicsLayout.frameFields(new NativeBinnedGraphicsFrameRecord)
    def set(base: Long, fields: Seq[NativeTypedGraphicsLayout.Field], name: String, value: Long): Unit = {
      val field = fields.find(_.name == name).get
      (0 until field.words).foreach(i => word(bytes, base+4*(field.word+i), value >>> (32*i)))
    }
    set(0x8000, stateFields, "engineCount", native.engines)
    set(0x8d1000, frameFields, "frame.bins.bins.pool.pageSizeLog2", Integer.numberOfTrailingZeros(native.pageBytes))
    val prefix = fragment.prefixBytes(8, 1, 1, FragmentInputAbi.center)
    for (engine <- 2 until native.engines) {
      val base = 0x100000L + engine*0x10000
      val scratch = s"scratch[${engine-1}]"
      val frameScratch = s"frame.scratch[$engine]"
      set(0x8000, stateFields, scratch+".base", base)
      set(0x8000, stateFields, scratch+".limit", base+prefix)
      set(0x8000, stateFields, scratch+".allocationBase", base)
      set(0x8000, stateFields, scratch+".allocationLimit", base+prefix)
      set(0x8d1000, frameFields, frameScratch+".base", base)
      set(0x8d1000, frameFields, frameScratch+".limit", base+8192)
    }
    bytes
  }

  private def addi(rd: Int, rs: Int, value: Int): Long =
    ((value & 4095L) << 20) | (rs << 15) | (rd << 7) | 0x13

  private def vector(fn: Int, form: Int, rd: Int, a: Int, b: Int): Long =
    (fn.toLong << 26) | (1 << 25) | (b << 20) | (a << 15) | (form << 12) | (rd << 7) | 0x57

  private def defaultLoad(rd: Int, selector: Int, offset: Int): Long =
    0x0e00000bL | (offset << 20) | (selector << 15) | (rd << 7)

  private def blockLoad(rd: Int): Long = 0x1e41002bL | (rd << 7)

  // TCS uses the combined value in the tessellation level. TES uses it in the
  // x-coordinate calculation before export.
  private def capacityLoads(result: Int): Vector[Long] = Vector(
    addi(1, 0, 31), 0x00001137L, addi(2, 2, -4), defaultLoad(10, 1, 2), vector(23, 4, 5, 10, 0),
    vector(23, 3, 2, 11, 0), 0x00004137L, addi(2, 2, -4), vector(23, 4, 4, 2, 0),
    blockLoad(3), vector(0, 1, 3, 3, 3), vector(0, 1, 3, 5, 3),
    vector(23, 3, 2, 0, 0), blockLoad(6), vector(0, 1, result, 3, 6)
  )

  private def originalCode(bytes: Bytes, address: Long): Vector[Long] = {
    val code = Vector.tabulate(64)(i => readWord(bytes, address + 4*i))
    val end = code.indexOf(cease)
    require(end > 0, s"fixture is missing its return at 0x${address.toHexString}")
    code.take(end+1)
  }

  private def draw(base: Bytes, domain: Int, fields: Seq[(String, Int)], capacity: Boolean, reuse: Boolean): Draw = {
    val bytes = mutable.Map.from(base)
    def field(name: String): Int = fields.find(_._1 ==
      "template.source.viewport.system.patch." + name).get._2
    def setDraw(index: Int, value: Long): Unit = word(bytes, drawOffset+4*index, value)
    word(bytes, 0x30000L, 0xcf)
    setDraw(0, 0x474e5404L+domain)
    setDraw(2, 4+domain)
    setDraw(field("control.patch.pointMode"), 1)
    val uniformStart = TessellationUniformSequenceLayout.firstWord
    Vector(7L, 8L, 9L).zipWithIndex.foreach { case (value, i) => setDraw(uniformStart+i, value) }

    val uniformReads = Vector.newBuilder[UniformRead]
    val reads = (0 until 2).flatMap { stageIndex =>
      val stage = 4+stageIndex
      val root = 0x90000L+domain*0x4000+stageIndex*0x2000
      val binding = root+64
      setDraw(uniformStart+3+stageIndex*5, 3)
      setDraw(uniformStart+4+stageIndex*5, root)
      def setBinding(address: Long, selector: Int, base: Long, size: Int): Unit =
        words(bytes, address, Vector(0x47554203L, 64, 3, 7, 8, 9, selector, 1L << stage,
          base, base+size, 0, base, base+size, 0, 1, 0))
      if (capacity) {
        val data = 0x290000L + (2*domain+stageIndex)*0x10000
        val block11 = root+0x600
        val defaults = root+0x800
        uniformReads += UniformRead(root, defaults, data+32768+4096-64)
        words(bytes, root, Vector(0x47555203L, 64, 3, 7, 8, 9, stage,
          0x801, 23, 0, binding, block11+64, 0, defaults, 1, 0))
        val items = Vector((binding, 0, data, 16384), (block11, 23, data+16384, 16384),
          (defaults, 31, data+32768, 4096))
        val values = if (stageIndex == 0) Vector(0x3e000000L, 0x3f000000L, 0x3e800000L)
          else Vector(0x3e800000L, 0x3ec00000L, 0x3e000000L)
        val lines = for (((address, selector, base, size), value) <- items.zip(values)) yield {
          setBinding(address, selector, base, size)
          word(bytes, base+size-4, value)
          Vector(address, base+size-64)
        }
        root +: lines.flatten
      } else {
        val data = root+0x1000
        words(bytes, root, Vector(0x47555203L, 64, 3, 7, 8, 9, stage,
          0, 0, 0, 0, 0, 0, binding, 1, 0))
        setBinding(binding, 31, data, 64)
        word(bytes, data, one)
        Vector(root, binding, data)
      }
    }.toVector.sorted

    val control = originalCode(bytes, codeOffset)
    require(control(3) == 0x5641052bL && control(4) == 0x5205002bL)
    val tcs = if (capacity)
      control.take(1) ++ capacityLoads(10) ++ Vector(vector(23, 3, 4, 0, 0)) ++ control.drop(4)
    else control.take(3) ++ Vector(addi(1, 0, 31), addi(2, 0, 0), defaultLoad(10, 1, 2),
      vector(23, 4, 10, 10, 0)) ++ control.drop(4)
    val eval = originalCode(bytes, 0x3000L)
    require(eval.count(_ == 0x3f8002b7L) == 1)
    val tes = if (capacity) {
      require(eval(4) == 0x4232000bL)
      Vector(control.head) ++ capacityLoads(3) ++ Vector(
        addi(1, 0, 0), addi(2, 0, 3), 0x4020818bL, vector(23, 4, 7, 3, 0),
        vector(36, 1, 3, 3, 7)) ++ eval.take(4) ++ Vector(
        vector(23, 4, 7, 3, 0), vector(36, 1, 7, 7, 3), vector(16, 2, 3, 0, 7)) ++ eval.drop(4)
    } else eval.flatMap {
      case 0x3f8002b7L => Vector(addi(11, 0, 31), addi(12, 0, 0), defaultLoad(5, 11, 12))
      case other => Vector(other)
    }
    val tcsOffset = if (reuse) codeOffset else 0x11000L+domain*0x1000
    val tesOffset = 0x21000L+domain*0x1000
    require(tcs.size <= 64)
    words(bytes, codeOffset, tcs.padTo(64, 0L))
    if (!reuse) words(bytes, tcsOffset, tcs.padTo(64, 0L))
    words(bytes, tesOffset, tes)
    setDraw(field("control.kernel.kernelPc"), tcsOffset)
    setDraw(field("evaluationKernel.kernelPc"), tesOffset)
    Draw(bytes, reads, uniformReads.result())
  }

  private def setupFeedback(base: Bytes): Bytes = {
    val bytes = mutable.Map.from(base)
    word(bytes, 0x30000L, 0xcf)
    for ((va, pa) <- Vector(feedbackRecordVa -> feedbackRecord, 0x30800000L -> 0x18800000L))
      word(bytes, 0x30000L + (va >> 22)*4, ((pa >> 12) << 10) | 0x2cf)
    words(bytes, feedbackRecord, Vector[Long](0x47544601L, 512, 1, 0, context, cookie, program, 0, 1, 1, 1)
      .padTo(128, 0L))
    word(bytes, feedbackRecord+16*4, feedbackBufferVa)
    word(bytes, feedbackRecord+17*4, feedbackBufferVa+4096)
    word(bytes, feedbackRecord+41*4, satp)
    for (address <- (feedbackBuffer & ~63L) until feedbackBuffer+256) bytes(address) = 0xa7
    bytes
  }

  private def checkpoint(action: Int): Bytes = {
    val bytes = mutable.Map.empty[Long, Int]
    word(bytes, feedbackRecord+3*4, action)
    words(bytes, feedbackRecord+42*4, Vector(0L, 0L))
    bytes
  }

  import GpuCommandMmioRegs._
  private val flush: (Int, Long) = TLB_FLUSH -> 1L
  private val enableIrq: (Int, Long) = RenderHostRegs.IRQ -> 1L
  private val clearIrq: (Int, Long) = RenderHostRegs.IRQ -> 3L
  private val submit: (Int, Long) = SUBMIT -> 1L
  private def stage(id: Int, opcode: Int, source: Long, destination: Long, height: Long,
      stride: Long, flags: Long, control: Boolean): Writes = {
    val address = Vector(SOURCE -> source, DESTINATION -> destination)
    val extent = if (control) Vector(BYTES -> 512L, PATTERN -> 0L) else Vector(PATTERN -> 0L, BYTES -> 512L)
    Vector(COMMAND_ID -> id.toLong, OPCODE -> opcode.toLong) ++ address ++ extent ++
      Vector(WIDTH -> context, HEIGHT -> height, SOURCE_STRIDE -> stride, FLAGS -> flags, submit)
  }

  private def emitUniformReads(file: Path, draws: Seq[Draw]): Unit = {
    require(draws.size == 3 && draws.forall(_.uniformReads.size == 2))
    val out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))
    try {
      out.writeBytes("OGTUR001")
      for (draw <- draws; read <- draw.uniformReads;
          address <- Vector(read.root, read.defaultBinding, read.defaultLine))
        (0 until 4).foreach(i => out.writeByte((address >>> (i*8)).toInt))
    } finally out.close()
  }

  private def emitPlan(file: Path, phases: Seq[Phase]): Unit = {
    val out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))
    def put(value: Long): Unit = (0 until 4).foreach(i => out.writeByte((value >>> (i*8)).toInt))
    def vector[A](values: Seq[A])(emit: A => Unit): Unit = { put(values.size); values.foreach(emit) }
    def pairs(values: Seq[(Long, Long)]): Unit = vector(values) { case (a, b) => put(a); put(b) }
    def commands(values: Writes): Unit = pairs(values.map { case (a, b) => a.toLong -> b })
    try {
      out.writeBytes("OGTUF001")
      put(1000000); put(COMPLETION)
      commands(Vector(VECTOR_SATP -> satp, INSTRUCTION_SATP -> satp, flush, enableIrq))
      commands(Vector(COMPLETION_POP -> 1L, clearIrq))
      vector(0 until 12)(i => put(0x180+4*i))
      pairs(Vector(0L -> 0x400000L, 0x800000L -> 0xc00000L,
        feedbackRecord -> (feedbackRecord+4096), 0x18800000L -> 0x18802000L))
      val expected = Vector(0L, one, 0L, one) ++ Vector(one, 0L, 0L, one, 0L, 0L) ++
        Vector(0L, one, one, 0L, 0L, one, one, 0L) ++ Vector(0L, one, 0L, one)
      pairs(Vector((feedbackRecord+28*4) -> 88L, (feedbackRecord+36*4) -> 22L, (feedbackRecord+38*4) -> 22L) ++
        expected.zipWithIndex.map { case (value, i) => (feedbackBuffer+4*i) -> value })
      vector(Vector((feedbackBuffer & ~63L, feedbackBuffer), (feedbackBuffer+88, feedbackBuffer+256))) {
        case (first, end) => put(first); put(end); put(0xa7)
      }
      vector(phases) { phase =>
        val encoded = (phase.opcode & 7) << 8 | (phase.opcode & 8) << 13 | 0x8000 | phase.id
        val draw = phase.opcode == tessellate
        val memory = phase.opcode != invalidate
        Vector[Long](phase.id, phase.opcode, if (draw) 0x1ffff else 0xf8ff,
          if (draw) encoded else 0x8000 | phase.id, if (memory) 3 else 0, if (memory) 7 else 0,
          if (draw) 50000 else 0, if (phase.opcode == feedback) feedbackRecord+42*4 else 0,
          if (phase.upload) 1 else 0).foreach(put)
        commands(phase.writes)
        commands(phase.after)
        vector(phase.reads)(put)
      }
    } finally out.close()
  }

  def main(args: Array[String]): Unit = {
    require(args.length == 1, "usage: TessellationUniformOwnerFixture NEW_DIRECTORY")
    require(sys.props.get("opengpu.simBackend").contains("flashsim"), "fixture requires FlashSim")
    val directory = Files.createDirectory(Paths.get(args(0)))
    val bases = Vector(fourEngineFixture(TessellationNativeHostFixture.build()),
      fourEngineFixture(TessellationNativeTriangleHostFixture.build()),
      fourEngineFixture(TessellationNativeQuadHostFixture.build()))
    val gpu = GpuConfig()
    val shader = VertexShaderStageConfig()
    val fields = Vector(
      TessellationNativeSequenceLayout.fields(new TessellationPatchSequenceDescriptor(gpu, shader, 12))
        .map(field => field.name -> field.word),
      TessellationNativeTriangleSequenceLayout.fields(new TessellationTrianglePatchSequenceDescriptor(gpu, shader, 12))
        .map(field => field.name -> field.word),
      TessellationNativeQuadSequenceLayout.fields(new TessellationQuadPatchSequenceDescriptor(gpu, shader, 12))
        .map(field => field.name -> field.word)
    )
    writePatch(setupFeedback(bases.head), directory.resolve("initial.memory"))
    for (name <- Vector("distinct", "capacity", "invalidate")) {
      val reuse = name == "invalidate"
      val draws = bases.indices.map(i => draw(bases(i), i, fields(i), name == "capacity", reuse))
      if (name == "capacity") emitUniformReads(directory.resolve("capacity.resources"), draws)
      val phases = mutable.ArrayBuffer.empty[Phase]
      def append(phase: Phase, patch: Option[Bytes]): Unit = {
        require(phase.upload == patch.isDefined)
        patch.foreach(bytes => writePatch(bytes, directory.resolve(s"$name-${phases.size}.memory")))
        phases += phase
      }
      def control(id: Int, action: Int): Unit =
        append(Phase(id, feedback, stage(id, feedback, feedbackRecordVa, feedbackRecordVa+512,
          cookie, program, 0, control = true)), Some(checkpoint(action)))

      control(210, TransformFeedbackAction.begin)
      for ((domain, i) <- Vector(0, 1, 2, 0).zipWithIndex) {
        val draw = draws(domain)
        if (reuse) {
          val id = 220+domain
          append(Phase(id, invalidate, Vector(COMMAND_ID -> id.toLong, OPCODE -> invalidate.toLong,
            SOURCE -> codeOffset, BYTES -> 256L, FLAGS -> 0L, submit), Vector(flush)), Some(draw.patch))
        }
        val id = 211+i
        append(Phase(id, tessellate, stage(id, tessellate, 0x4d0000L, 0x4d0200L,
          program, cookie, 24, control = false), reads = draw.reads, upload = !reuse),
          if (reuse) None else Some(draw.patch))
      }
      control(215, TransformFeedbackAction.end)
      emitPlan(directory.resolve(name+".commands"), phases.toVector)
    }
  }
}
