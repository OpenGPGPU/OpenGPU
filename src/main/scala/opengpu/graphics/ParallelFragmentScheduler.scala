package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig

private class FragmentRetirementToken(workerWidth: Int) extends Bundle {
  val drawEnd = Bool()
  val worker = UInt(workerWidth.W)
}

/** Partition the quad stream into independent worker batches, then restore
  * submission order before the sample expander/output merger.
  *
  * Each worker receives at most one batch. Its flush seals that batch and its
  * existing drawRetire handshake reports batch retirement. Real draw ends
  * occupy separate ordered tokens, including empty draws and a flush arriving
  * after every batch has already retired. Complete helper quads are passed
  * through unchanged. Worker i owns kernarg banks 2*i and 2*i+1.
  */
class ParallelFragmentScheduler(
  config: GpuConfig,
  gfxConfig: GraphicsConfig,
  numWorkers: Int,
  maxDraws: Int = 2
) extends Module {
  require(numWorkers > 0 && numWorkers <= ShaderResourceConfig.FieldMask / 2)
  require(maxDraws > 0)
  private val batchCapacity = config.lanes * config.warps
  require(batchCapacity % 4 == 0)
  private val dispatchCapacity = if (gfxConfig.fragmentBatchLimit == 0)
    batchCapacity else gfxConfig.fragmentBatchLimit
  require(dispatchCapacity > 0 && dispatchCapacity <= batchCapacity &&
    dispatchCapacity % config.lanes == 0,
    "fragment dispatch must fit the physical batch and preserve whole warps")
  private val quadsPerBatch = dispatchCapacity / 4
  private val workerWidth = math.max(1, log2Ceil(numWorkers))
  private val quadCountWidth = math.max(1, log2Ceil(quadsPerBatch + 1))

  val io = IO(new Bundle {
    val front = new KernelFragFrontendIO(config, gfxConfig)
    val workers = Vec(numWorkers, Flipped(new KernelFragFrontendIO(config, gfxConfig)))
    val assignedWorkers = Output(UInt(numWorkers.W))
  })
  private val tokens = Module(new Queue(new FragmentRetirementToken(workerWidth), numWorkers + maxDraws))
  private val allocated = RegInit(VecInit(Seq.fill(numWorkers)(false.B)))
  private val sealedBatch = RegInit(VecInit(Seq.fill(numWorkers)(false.B)))
  private val collecting = RegInit(false.B)
  private val currentWorker = RegInit(0.U(workerWidth.W))
  private val quadCount = RegInit(0.U(quadCountWidth.W))
  private val previousFlush = RegNext(io.front.flush, false.B)
  private val flushRise = io.front.flush && !previousFlush
  private val pendingEnds = RegInit(0.U(log2Ceil(maxDraws + 1).W))
  private val endPending = pendingEnds =/= 0.U

  // A zero-stride legacy binding has no independent worker storage. Keep
  // that binding on worker zero; resource-aware parallel bindings use a
  // nonzero stride and are bounds-checked by the driver.
  private val free = VecInit((0 until numWorkers).map { worker =>
    !allocated(worker) && (if (worker == 0) true.B else io.front.kernargBankStride.orR)
  }).asUInt
  private val selected = PriorityEncoder(free)
  private val chosen = Mux(collecting, currentWorker, selected)
  private val chosenReady = MuxLookup(chosen, false.B)((0 until numWorkers).map { worker =>
    worker.U -> io.workers(worker).fragIn.ready
  })
  private val acceptWindow = !endPending && !flushRise
  private val canPresent = acceptWindow && (collecting || (free.orR && tokens.io.enq.ready))
  io.front.fragIn.ready := canPresent && chosenReady

  tokens.io.enq.valid := endPending ||
    (acceptWindow && !collecting && free.orR && io.front.fragIn.valid && chosenReady)
  tokens.io.enq.bits.drawEnd := endPending
  tokens.io.enq.bits.worker := selected
  private val endEnqueued = tokens.io.enq.fire && endPending
  when(flushRise =/= endEnqueued) {
    pendingEnds := Mux(flushRise, pendingEnds + 1.U, pendingEnds - 1.U)
  }
  when(flushRise && !endEnqueued) {
    assert(pendingEnds < maxDraws.U, "fragment draw-end queue exceeded the admitted draw count")
  }

  when(io.front.fragIn.fire) {
    when(!collecting) {
      currentWorker := selected
      quadCount := 1.U
      collecting := (quadsPerBatch > 1).B
    }.elsewhen(quadCount === (quadsPerBatch - 1).U) {
      collecting := false.B
      quadCount := 0.U
    }.otherwise {
      quadCount := quadCount + 1.U
    }
  }
  when(flushRise && collecting) {
    collecting := false.B
    quadCount := 0.U
  }

  private val headWorker = VecInit((0 until numWorkers).map { worker =>
    tokens.io.deq.valid && !tokens.io.deq.bits.drawEnd && tokens.io.deq.bits.worker === worker.U
  })
  io.front.out.valid := (0 until numWorkers).map { worker =>
    headWorker(worker) && io.workers(worker).out.valid
  }.reduce(_ || _)
  io.front.out.bits := Mux1H((0 until numWorkers).map { worker =>
    headWorker(worker) -> io.workers(worker).out.bits
  })
  io.front.outDepths := Mux1H((0 until numWorkers).map { worker =>
    headWorker(worker) -> io.workers(worker).outDepths
  })
  io.front.depthOverride := (0 until numWorkers).map { worker =>
    headWorker(worker) && io.workers(worker).depthOverride
  }.reduce(_ || _)
  io.front.drawRetire.valid := tokens.io.deq.valid && tokens.io.deq.bits.drawEnd
  io.front.drawRetire.bits := true.B
  private val batchRetired = (0 until numWorkers).map { worker =>
    headWorker(worker) && io.workers(worker).drawRetire.valid
  }.reduce(_ || _)
  tokens.io.deq.ready := Mux(tokens.io.deq.bits.drawEnd,
    io.front.drawRetire.ready, batchRetired)

  for (worker <- 0 until numWorkers) {
    val port = io.workers(worker)
    port.fragIn.valid := io.front.fragIn.valid && canPresent && chosen === worker.U
    port.fragIn.bits := io.front.fragIn.bits
    port.fragUv := io.front.fragUv
    port.fragDepths := io.front.fragDepths
    port.shaderPc := io.front.shaderPc
    port.inputSkip := io.front.inputSkip
    port.abi1 := io.front.abi1
    port.kernargBase := io.front.kernargBase + io.front.kernargBankStride * (2 * worker).U
    port.kernargBankStride := io.front.kernargBankStride
    port.texFormat := io.front.texFormat
    port.texBase := io.front.texBase
    port.texWidth := io.front.texWidth
    port.texHeight := io.front.texHeight
    port.texWrapClamp := io.front.texWrapClamp
    port.texMaxLevel := io.front.texMaxLevel
    port.texLodBias := io.front.texLodBias
    port.texMinLevel := io.front.texMinLevel
    port.flush := sealedBatch(worker)
    port.out.ready := headWorker(worker) && io.front.out.ready
    port.drawRetire.ready := headWorker(worker)

    when(io.front.fragIn.fire && chosen === worker.U) {
      when(!collecting) { allocated(worker) := true.B }
      when((!collecting && (quadsPerBatch == 1).B) ||
          (collecting && quadCount === (quadsPerBatch - 1).U)) {
        sealedBatch(worker) := true.B
      }
    }
    when(flushRise && collecting && currentWorker === worker.U) {
      sealedBatch(worker) := true.B
    }
    when(port.drawRetire.fire) {
      allocated(worker) := false.B
      sealedBatch(worker) := false.B
    }
    when(port.fragIn.fire) {
      assert(!sealedBatch(worker), "cannot append to a sealed fragment batch")
    }
  }
  io.assignedWorkers := allocated.asUInt
  io.front.drained := !tokens.io.deq.valid && !allocated.asUInt.orR &&
    !collecting && !endPending && !flushRise && io.workers.map(_.drained).reduce(_ && _)
}
