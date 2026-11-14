package opengpu.graphics

import opengpu.testutil.{NativeFrameMemoryCodec, PagedFrameMemory}

/** The original six recipes on GNJ2/GNF3, without changing their shaders,
  * geometry, attachment formats, samples, guards or independent image oracle.
  * They exercise ordinary draws on the actual mixed geometry renderer; they
  * do not, by themselves, qualify GS, feedback or compute execution.
  */
object NativeLayeredProductFrameInputs {
  import NativeProductFrameInputs.{Inputs, Region}
  private val completionAllocation = Region("layered-completion", 0x01600000L,
    0x01600000L + NativeLayeredGraphicsLayout.completionBytes + 128)
  private val completionBase = completionAllocation.base + 64
  private lazy val completionFields = NativeLayeredCompletionEncoding.fields(new NativeLayeredCompletionCapture)
  private lazy val completionByName = completionFields.map(f => f.name -> f).toMap

  def encode(scene: NativeProductWorkloads.Scene, tileLog2: Int = 3): Inputs = {
    val original = NativeProductFrameInputs.encodeApiExtentTiled(scene, tileLog2)
    require(original.regions.forall(r => r.limit <= completionAllocation.base ||
      r.base >= completionAllocation.limit), "GNY2 allocation aliases an original recipe resource")
    val memory = original.memory
    memory.mapRange(completionAllocation.base, completionAllocation.limit, 0x6b)
    val job = original.job ++ Map[String, BigInt](
      "completion.base" -> completionBase,
      "completion.limit" -> (completionBase + NativeLayeredGraphicsLayout.completionBytes),
      "completion.allocationBase" -> completionAllocation.base,
      "completion.allocationLimit" -> completionAllocation.limit,
      "completion.writable" -> 1)
    val frame = original.frame ++ Map[String, BigInt]("frame.version" -> 3,
      "layered.version" -> 1, "layered.layers" -> 1) ++ original.planes.flatMap { plane =>
      val active = !plane.resolve || plane.location == 0 && scene.samples == 4
      if (active) Some(s"layered.${if (plane.resolve) "resolvePitch" else "sourcePitch"}[${plane.location}]" ->
        BigInt(plane.allocationLimit - plane.allocationBase)) else None
    }
    def record(base: Long, words: Int, magic: BigInt, version: Int,
        fields: Seq[NativeTypedGraphicsLayout.Field], values: Map[String, BigInt]): Unit = {
      require(values.keySet.subsetOf(fields.map(_.name).toSet), "unknown layered recipe field")
      for (word <- 0 until words) memory.writeWord(base + word * 4, 0)
      for ((value, word) <- Seq(magic, BigInt(words * 4), BigInt(version)).zipWithIndex)
        memory.writeWord(base + word * 4, value)
      for (field <- fields) {
        val value = values.getOrElse(field.name, BigInt(0))
        require(value >= 0 && value.bitLength <= field.width, s"${field.name} exceeds its ABI")
        for (word <- 0 until field.words)
          memory.writeWord(base + (field.word + word) * 4, (value >> (word * 32)) & 0xffffffffL)
      }
    }
    record(original.jobBase, NativeBinnedGraphicsLayout.jobWords, NativeLayeredGraphicsLayout.jobMagic, 2,
      NativeLayeredGraphicsLayout.jobFields(new NativeLayeredGraphicsJobRecord), job)
    record(job("job.frameBase").toLong, NativeBinnedGraphicsLayout.frameWords,
      NativeLayeredGraphicsLayout.frameMagic, 3,
      NativeLayeredGraphicsLayout.frameFields(new NativeLayeredGraphicsFrameRecord), frame)
    original.copy(completionBase = completionBase, job = job, frame = frame,
      regions = original.regions :+ completionAllocation)
  }

  /** Read the published wide field; never narrow a 145-bit count into GNX1. */
  def completionValue(memory: PagedFrameMemory, input: Inputs, name: String): BigInt = {
    val field = completionByName(name)
    (0 until field.words).foldLeft(BigInt(0)) { (value, word) =>
      value | (memory.readWord(input.completionBase + (field.word + word) * 4) << (word * 32))
    }
  }

  /** Verify real returned backing and its acknowledged GNY2 publication.
    * Count equalities check conservation, not an independent clipping count.
    * The independent original oracle checks every stored sample and resolve.
    */
  def verify(scene: NativeProductWorkloads.Scene, input: Inputs,
      actual: PagedFrameMemory, commandId: Int): Unit = {
    require(commandId >= 1 && commandId <= 6)
    require(input.frame("frame.version") == 3 && input.frame("layered.layers") == 1 &&
      input.memory.readWord(input.jobBase) == NativeLayeredGraphicsLayout.jobMagic)
    val mutableNames = Set("vertex-scratch", "fragment-scratch", "primitive-arena", "bin-pool", "bin-directory")
    val writable = input.regions.filter(r => mutableNames(r.name) || r.name.startsWith("image-"))
      .map(r => (r.base, r.limit)) :+ (input.completionBase ->
        (input.completionBase + NativeLayeredGraphicsLayout.completionBytes))
    require(NativeFrameMemoryCodec.unchangedOutside(input.memory, actual, writable),
      s"${scene.id} changed immutable data, allocation metadata or a GNY2 guard")
    NativeProductFrameInputs.verifyWith(scene, input.copy(memory = actual),
      NativeProductFrameReference.apiExtent(scene))
    val header = Vector(NativeLayeredGraphicsLayout.completionMagic,
      BigInt(NativeLayeredGraphicsLayout.completionBytes), BigInt(2))
    for ((word, index) <- header.zipWithIndex)
      require(actual.readWord(input.completionBase + index * 4) == word, "incomplete GNY2 header")
    val used = completionFields.flatMap(f => f.word until f.word + f.words).toSet ++ Set(0, 1, 2)
    for (word <- 0 until NativeLayeredGraphicsLayout.completionWords if !used(word))
      require(actual.readWord(input.completionBase + word * 4) == 0, "nonzero reserved GNY2 word")
    def value(name: String): BigInt = completionValue(actual, input, name)
    val expected = Map[String, BigInt]("commandId" -> commandId, "status" -> 0, "faultStage" -> 0,
      "faultAddress" -> 0, "faultDraw" -> 0, "issuedDraws" -> scene.draws.size,
      "completedDraws" -> scene.draws.size, "frame.version" -> 1, "frame.status" -> 0,
      "frame.faultStage" -> 0, "frame.draws" -> scene.draws.size, "frame.droppedParts" -> 0,
      "frame.sourceFaultValid" -> 0, "frame.faultDrawValid" -> 0,
      "frame.submittedGeometryPrimitives" -> 0, "frame.completedGeometryInvocations" -> 0,
      "frame.emittedGeometryPrimitives" -> 0, "frame.images.version" -> 1, "frame.images.status" -> 0,
      "frame.images.producer.status" -> 0, "frame.images.producer.binStatus" -> 0,
      "frame.images.producer.restartValid" -> 0, "frame.images.directory.status" -> 0,
      "frame.images.images.version" -> 1, "frame.images.images.status" -> 0,
      "frame.images.images.faultValid" -> 0, "frame.images.images.overflow" -> 0,
      "frame.images.images.tiles" -> input.frame("frame.bins.bins.tileCount"))
    for ((name, word) <- expected) require(value(name) == word, s"${scene.id} GNY2 $name differs")
    for (prefix <- Seq("identity.", "frame.identity.", "frame.images.identity.",
        "frame.images.producer.identity.", "frame.images.directory.identity.", "frame.images.images.identity.");
        (name, word) <- Seq("context" -> 7, "pass" -> 8, "generation" -> 9))
      require(value(prefix + name) == word, s"${scene.id} GNY2 identity differs at $prefix$name")
    val admitted = value("frame.admittedParts")
    val references = value("frame.images.producer.references")
    require(admitted > 0 && value("frame.receivedParts") == admitted &&
      value("frame.images.producer.accepted") == admitted, "GNY2 source conservation differs")
    require(value("frame.images.producer.stored") <= admitted && references > 0 &&
      value("frame.images.directory.committedCount") == references, "GNY2 durable bins differ")
    for (name <- Seq("scanned", "selected", "accepted", "issued", "completed"))
      require(value("frame.images.images." + name) == references, s"GNY2 $name replay conservation differs")
  }
}
