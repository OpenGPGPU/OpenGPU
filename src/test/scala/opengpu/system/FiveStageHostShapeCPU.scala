package opengpu.system

import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import chisel3.ActualDirection
import opengpu.config.GpuConfig
import opengpu.dispatch.{KernelCommand, KernelCommandResult}
import opengpu.core.memory._
import opengpu.graphics._
import opengpu.graphics.tessellation._
import opengpu.testutil.GraphicsTestResolution

object FiveStageHostShapeCPU {
  def main(args: Array[String]): Unit = {
    require(args.length == 2, "runtime-plan.json output-shape.json")
    require(
      !GraphicsTestResolution.productQualification && GraphicsTestResolution.width == 640 && GraphicsTestResolution.height == 480
    )
    val cfg = GpuConfig(lanes = 16, warps = 8, enableSharedVectorAtomics = true)
    val p = VertexShaderStageConfig(VertexInputFetchConfig(attributes = 16, bindings = 16), varyings = 16)
    val f = FragmentShaderConfig(varyings = 16, colors = 4)
    val engines = 4; val payload = NativeShaderPayloadSizing.pool(owners = 5, ports = 12)
    require(
      payload.capacityBytes == 524288 && payload.maxPayloadBytes == 69632 && payload.owners == 5 && payload.ports == 12
    )
    val shared = SharedCuShaderConfig(2)
    val pins = TessellationSystemIsolineImagePipeline.payloadPins(
      engines,
      ownedVertexSource = true,
      nativeGeometryAvailable = true
    )
    require(pins == 34)
    val imageSlots = TessellationSystemIsolineImagePipeline.memoryTransactions(engines, 8, 65536, true, true)
    val hostSlots = 1 << log2Ceil(imageSlots + 4)
    val memorySlots = GpuSystem.totalMemoryTransactions(2, 16, hostSlots)
    require(imageSlots == 256 && hostSlots == 512 && memorySlots == 592)
    val expected = new Bundle {
      val begin = Flipped(Decoupled(new TypedVertexBinImageDescriptor))
      val task = Flipped(Decoupled(new TessellationSystemIsolineGeometryVertexInputImageTask(cfg, p, 8)))
      val taskCompletion = Decoupled(new TessellationSystemIsolineGeometryVertexInputImageTaskCompletion(cfg, p, 8))
      val end = Flipped(Decoupled(Bool()))
      val completion = Decoupled(new TessellationSystemIsolineGeometryVertexInputImageCompletion(cfg, p, 8))
      val memoryRequest = Decoupled(new ComputeMemoryRequest(cfg, 64, memorySlots))
      val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, memorySlots)))
      val frameDrained = Input(Bool()); val context = Input(UInt(32.W)); val cancel = Input(Bool())
      val compute = Flipped(Decoupled(new KernelCommand(cfg, 8, imageAvailable = true)))
      val computeDone = Decoupled(new KernelCommandResult(8))
      val out = Vec(engines, Decoupled(new TypedTilePixelQuadCompletion))
      val tileDone = Vec(engines, Decoupled(new TypedVertexBinImageTileCompletion))
      val primitiveDone = Vec(engines, Decoupled(new TypedReplayPrimitiveCompletion))
      val owned = Output(UInt(2.W)); val tags = Output(Vec(2, UInt(8.W)))
      val active = Output(Vec(2, UInt(8.W)))
      val writeback = Output(Vec(2, Valid(new opengpu.core.backend.register.ScalarRegisterWrite(cfg))))
      val launch = Output(Valid(new Bundle { val tag = UInt(8.W); val pc = UInt(32.W) }))
      val traps = Output(UInt(32.W)); val busy = Output(Bool()); val idle = Output(Bool())
      val poolReserved = Output(UInt(32.W)); val poolDrained = Output(Bool())
      val sourceLive = Output(Bool()); val sourceDrained = Output(Bool()); val taskDrained = Output(Bool())
      val fragmentEvents = Output(new FragmentPerformanceEvents)
    }
    val ports = scala.collection.mutable.LinkedHashMap.empty[String, (Int, Boolean)]
    def walk(d: Data, n: String, flip: Boolean = false, forced: Option[Boolean] = None): Unit = {
      val specified = DataMirror.specifiedDirectionOf(d).toString
      val f = if (forced.isEmpty && specified == "Flip") !flip else flip
      val direction = forced.orElse(specified match {
        case "Input" => Some(!flip); case "Output" => Some(flip); case "Flip" | "Unspecified" => None
        case other   => throw new IllegalArgumentException("unknown specified direction " + other)
      })
      d match {
        case r: Record => r.elements.foreach { case (k, v) => walk(v, n + "_" + k, f, direction) }
        case v: Vec[_] => v.zipWithIndex.foreach { case (e, i) => walk(e, n + "_" + i, f, direction) }
        case _         =>
          require(d.getWidth > 0, s"unknown width $n"); require(!ports.contains(n));
          ports(n) = (d.getWidth, direction.getOrElse(f))
      }
    }
    walk(expected, "io"); ports("clock") = (1, true); ports("reset") = (1, true)
    val plan = ujson.read(java.nio.file.Files.readString(java.nio.file.Paths.get(args(0))))
    require(
      plan("schema").str == "opengpu-original-five-runtime-v1" && plan("cpuOnly").bool && plan(
        "recordBytes"
      ).num == 1472
    )
    require(
      plan("cases").arr.map(c => (c("name").str, c("width").num.toInt)).toVector == Vector(
        ("full5", 4),
        ("pointcoord", 4)
      )
    )
    var checked = 0
    for (c <- plan("cases").arr; j <- Vector(c("begin")) ++ c("tasks").arr.map(_("assign")); (n, a) <- j.obj) {
      val (w, input) =
        ports.getOrElse(n, throw new IllegalArgumentException("actual Bundle has no assignment leaf " + n))
      require(input && BigInt(a.str) >= 0 && BigInt(a.str).bitLength <= w, s"input direction/width $n $w $input");
      checked += 1
    }
    val read = Vector(
      "io_owned",
      "io_traps",
      "io_idle",
      "io_busy",
      "io_sourceLive",
      "io_sourceDrained",
      "io_taskDrained",
      "io_poolReserved",
      "io_poolDrained",
      "io_launch_valid",
      "io_launch_bits_tag",
      "io_launch_bits_pc",
      "io_taskCompletion_valid",
      "io_completion_valid",
      "io_computeDone_valid",
      "io_computeDone_bits_commandId",
      "io_computeDone_bits_success",
      "io_memoryRequest_valid",
      "io_memoryRequest_bits_address",
      "io_memoryRequest_bits_isWrite",
      "io_memoryRequest_bits_writeData",
      "io_memoryRequest_bits_byteMask",
      "io_memoryRequest_bits_transactionId",
      "io_memoryResponse_ready"
    ) ++
      (0 until 2).flatMap(c =>
        Vector(
          s"io_writeback_${c}_valid",
          s"io_writeback_${c}_bits_rd",
          s"io_writeback_${c}_bits_data",
          s"io_tags_$c",
          s"io_active_$c"
        )
      ) ++
      (0 until 4).flatMap(c =>
        Vector(
          s"io_tileDone_${c}_valid",
          s"io_tileDone_${c}_bits_status",
          s"io_primitiveDone_${c}_valid",
          s"io_primitiveDone_${c}_bits_status"
        )
      ) ++
      Vector(
        "status",
        "taskId",
        "tupleRejected",
        "rawSourceValid",
        "ownerValid",
        "owner_status",
        "owner_image_status",
        "owner_image_published",
        "owner_source_status",
        "owner_source_vertices",
        "owner_source_words",
        "owner_source_source_batches",
        "owner_source_source_localBatches",
        "owner_source_source_spilledBatches",
        "source_status",
        "source_sourceValid",
        "source_publicationValid",
        "source_actualGsPublished",
        "source_received",
        "source_dropped",
        "source_admitted",
        "source_source_sourceValid",
        "source_source_source_status"
      ).map("io_taskCompletion_bits_" + _) ++
      Vector(
        "status",
        "taskFaultValid",
        "img_image_status",
        "img_image_faultStage",
        "img_image_faultTileValid",
        "img_image_faultTile",
        "img_image_faultAddress",
        "img_actualGsPublished",
        "img_received",
        "img_dropped",
        "img_admitted",
        "img_tasks",
        "img_image_source_tasks",
        "img_image_tiles",
        "img_image_primitives",
        "img_image_storedBytes"
      ).map("io_completion_bits_" + _)
    read.foreach { n =>
      val (w, input) =
        ports.getOrElse(n, throw new IllegalArgumentException("host observation has no actual output leaf " + n));
      require(!input && w > 0, "observation direction " + n)
    }
    val set = Vector(
      "io_context",
      "io_frameDrained",
      "io_memoryRequest_ready",
      "io_memoryResponse_valid",
      "io_memoryResponse_bits_transactionId",
      "io_memoryResponse_bits_fault",
      "io_memoryResponse_bits_readData",
      "io_begin_valid",
      "io_task_valid",
      "io_end_valid",
      "io_end_bits",
      "io_cancel",
      "io_taskCompletion_ready",
      "io_completion_ready",
      "io_compute_valid",
      "io_computeDone_ready",
      "io_compute_bits_commandId",
      "io_compute_bits_launch_kernelPc"
    ) ++
      (0 until 3).flatMap(i => Vector(s"io_compute_bits_launch_gridSize_$i", s"io_compute_bits_launch_localSize_$i")) ++
      (0 until 4).flatMap(i => Vector(s"io_out_${i}_ready", s"io_tileDone_${i}_ready", s"io_primitiveDone_${i}_ready"))
    set.foreach { n =>
      require(
        ports.getOrElse(n, throw new IllegalArgumentException("host drive has no actual leaf " + n))._2,
        "drive direction " + n
      )
    }
    require(!ports.keys.exists(_.contains("feedback")), "unexpected transform-feedback Bundle leaf")
    val out = ujson.Obj.from(ports.toVector.map { case (n, (w, input)) =>
      n -> ujson.Obj("width" -> w, "settable" -> input)
    })
    java.nio.file.Files.writeString(java.nio.file.Paths.get(args(1)), ujson.write(out, indent = 2) + "\n")
    println(
      s"FIVE_STAGE_ACTUAL_BUNDLE_CPU_PASS ports=${ports.size} assignmentLeaves=$checked observedLeaves=${read.size} additionalDriven=${set.size} Module=false ELAB=false GPU=false noTf=true"
    )
  }
}
