package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.{CachePolicy, GpuConfig}
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse}

class PrimitiveBinReadDescriptor extends Bundle {
  val version = UInt(8.W)
  val pool = new BinPagePoolDescriptor
  val tile = UInt(32.W)
  val headValid = Bool()
  val head = UInt(32.W)
  val count = UInt(32.W)
}

object PrimitiveBinReadStatus {
  val invalidPage = 6
}

class PrimitiveBinReference extends Bundle {
  val identity = new GraphicsWorkIdentity
  val tile = UInt(32.W)
  val ordinal = UInt(32.W)
  val reference = new BinPrimitiveReference
}

class PrimitiveBinReadCompletion extends Bundle {
  val identity = new GraphicsWorkIdentity
  val tile = UInt(32.W)
  val status = UInt(PrimitiveBinStatus.width.W)
  val emitted = UInt(32.W)
  val pages = UInt(32.W)
  val readLines = UInt(32.W)
}

/** Replay a completed PrimitiveBinWriter chain through one cached64-byte line.
  * Descriptor and page ownership, bounds, counts, ordinal and forward-only
  * links are checked before following a page. Reference sequences must grow.
  * No faulted or invalid reference is replaced by a successful zero value.
  *
  * One tagged read can be outstanding. Offered requests and reference outputs
  * are irrevocable; cancel drains both before completion/rebinding. A caller
  * must publish writer stores to this memory domain before beginning replay,
  * retain the arena until completion, and discard a failed pass's outputs.
  * This block does not fetch primitive/state handles or schedule tile engines.
  */
class PrimitiveBinReader(
  config: GpuConfig = GpuConfig(),
  pageBytes: Int = 4096,
  memoryTransactions: Int = 1,
  transactionId: Int = 0
) extends Module {
  require(config.xLen == 32)
  require(pageBytes >= 64 && pageBytes <= 65536 && (pageBytes & (pageBytes-1)) == 0)
  require(transactionId >= 0 && transactionId < memoryTransactions)
  private val pageBits = log2Ceil(pageBytes)
  private val capacity = PrimitiveBinPageLayout.recordsPerPage(pageBytes)
  private val indexBits = log2Ceil(capacity+1).max(1)
  val io = IO(new Bundle {
    val begin = Flipped(Decoupled(new PrimitiveBinReadDescriptor))
    val reference = Decoupled(new PrimitiveBinReference)
    val cancel = Input(Bool())
    val completion = Decoupled(new PrimitiveBinReadCompletion)
    val memoryRequest = Decoupled(new ComputeMemoryRequest(config,64,memoryTransactions))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64,memoryTransactions)))
    val busy = Output(Bool())
    val readBytes = Output(UInt(7.W))
  })
  private val Seq(idle,bounds,boundsCheck,headerPrepare,offer,waiting,
    headerCheck,emit,finish,done,segments) = Enum(11)
  private val state = RegInit(idle)
  private val descriptor = Reg(new PrimitiveBinReadDescriptor)
  private val poolLegal = Reg(Bool())
  private val headLegal = Reg(Bool())
  // Arena page count is registered separately so the constant-capacity
  // multiply that bounds the descriptor does not follow the 33-bit subtract
  // and shift in one cycle.
  private val segmentIndex=Reg(UInt(3.W))
  private val previousLimit=Reg(UInt(33.W))
  private val pageCapacity = Reg(UInt(33.W))
  private val page = Reg(UInt(32.W))
  private val expectedPageOrdinal = Reg(UInt(32.W))
  private val nextPage = Reg(UInt(32.W))
  private val pageCount = Reg(UInt(indexBits.W))
  private val index = RegInit(0.U(indexBits.W))
  private val remaining = RegInit(0.U(32.W))
  private val admitted = RegInit(0.U(32.W))
  private val emitted = RegInit(0.U(32.W))
  private val pages = RegInit(0.U(32.W))
  private val readLines = RegInit(0.U(32.W))
  private val haveSequence = RegInit(false.B)
  private val lastSequence = Reg(UInt(32.W))
  private val requestAddress = Reg(UInt(32.W))
  private val requestHeader = Reg(Bool())
  private val line = Reg(UInt(512.W))
  private val lineAddress = Reg(UInt(32.W))
  private val lineValid = RegInit(false.B)
  private val staged = Reg(new PrimitiveBinReference)
  private val stagedSequence = Reg(UInt(32.W))
  private val stagedValid = RegInit(false.B)
  private val referenceValid = RegInit(false.B)
  private val reference = Reg(new PrimitiveBinReference)
  private val result = Reg(new PrimitiveBinReadCompletion)
  private val cancelled = RegInit(false.B)
  private val status = RegInit(PrimitiveBinStatus.success.U(PrimitiveBinStatus.width.W))
  private val faultNow = io.memoryResponse.fire && io.memoryResponse.bits.fault
  private val stop = cancelled || io.cancel || faultNow
  private val effectiveStatus = Mux((cancelled || io.cancel) && status === PrimitiveBinStatus.success.U,
    PrimitiveBinStatus.cancelled.U,status)

  private def pageInBounds(address: UInt): Bool =
    BinPagePoolRules.pageInBounds(address,descriptor.pool,pageBytes)

  io.begin.ready := state === idle && !io.cancel
  io.reference.valid := referenceValid
  io.reference.bits := reference
  io.completion.valid := state === done
  io.completion.bits := result
  io.busy := state =/= idle
  io.memoryRequest.valid := state === offer
  io.memoryRequest.bits := 0.U.asTypeOf(io.memoryRequest.bits)
  io.memoryRequest.bits.address := requestAddress
  io.memoryRequest.bits.sizeLog2 := 6.U
  io.memoryRequest.bits.cachePolicy := CachePolicy.uncached
  io.memoryRequest.bits.transactionId := transactionId.U
  io.memoryResponse.ready := state === waiting &&
    io.memoryResponse.bits.transactionId === transactionId.U
  io.readBytes := Mux(io.memoryRequest.fire,64.U,0.U)

  when(io.reference.fire) { referenceValid := false.B; emitted := emitted+1.U }
  when(io.memoryRequest.fire) { readLines := readLines+1.U }
  switch(state) {
    is(idle) {
      when(io.begin.fire) {
        descriptor := io.begin.bits
        page := io.begin.bits.head
        remaining := io.begin.bits.count
        admitted := 0.U; emitted := 0.U; pages := 0.U; readLines := 0.U
        index := 0.U
        haveSequence := false.B
        lineValid := false.B
        stagedValid := false.B
        cancelled := false.B
        status := PrimitiveBinStatus.success.U
        state := bounds
      }
    }
    is(bounds) {
      poolLegal := descriptor.version === 1.U && BinPagePoolRules.headerLegal(descriptor.pool,pageBytes)
      headLegal := pageInBounds(descriptor.head)
      pageCapacity := (Mux(descriptor.pool.version===2.U,descriptor.pool.initialLimit,descriptor.pool.limit)-descriptor.pool.base.pad(33)) >> pageBits
      segmentIndex:=0.U; previousLimit:=descriptor.pool.initialLimit
      state := Mux(descriptor.pool.version===2.U,segments,boundsCheck)
    }
    is(segments) {
      val p=descriptor.pool
      val valid=BinPagePoolRules.segmentLegal(p,segmentIndex,previousLimit,pageBytes)
      val last=Mux(segmentIndex<p.spillCount,p.spills(segmentIndex).limit,previousLimit)
      when(!valid || (segmentIndex===(BinPagePoolRules.maxSpills-1).U && last=/=p.limit)) { poolLegal:=false.B }
      when(segmentIndex<p.spillCount) {
        pageCapacity:=pageCapacity+((p.spills(segmentIndex).limit-p.spills(segmentIndex).base.pad(33))>>pageBits)
      }
      previousLimit:=last; segmentIndex:=segmentIndex+1.U
      when(segmentIndex===(BinPagePoolRules.maxSpills-1).U) { state:=boundsCheck }
    }
    is(boundsCheck) {
      val maximumReferences = pageCapacity * capacity.U
      when(!poolLegal || descriptor.headValid =/= (descriptor.count =/= 0.U) ||
          descriptor.count > maximumReferences || (descriptor.headValid && !headLegal)) {
        status := PrimitiveBinStatus.invalidDescriptor.U
        state := finish
      }.elsewhen(!descriptor.headValid) { state := finish }
        .otherwise { state := headerPrepare }
    }
    is(headerPrepare) {
      requestAddress := page
      requestHeader := true.B
      expectedPageOrdinal := (page-descriptor.pool.base) >> pageBits
      state := offer
    }
    is(offer) { when(io.memoryRequest.fire) { state := waiting } }
    is(waiting) {
      when(io.memoryResponse.fire) {
        when(io.memoryResponse.bits.fault) {
          status := PrimitiveBinStatus.memoryFault.U
          lineValid := false.B
          state := finish
        }.elsewhen(stop) { state := finish }
          .otherwise {
            line := io.memoryResponse.bits.readData
            lineAddress := requestAddress
            lineValid := true.B
            state := Mux(requestHeader,headerCheck,emit)
          }
      }
    }
    is(headerCheck) {
      val words = (0 until 8).map(i => line(32*i+31,32*i))
      val owner = words(3) === descriptor.pool.identity.context &&
        words(4) === descriptor.pool.identity.pass && words(5) === descriptor.pool.identity.generation &&
        words(6) === descriptor.tile && words(7) === expectedPageOrdinal
      val countValid = words(2) > 0.U && words(2) <= capacity.U && words(2) <= remaining
      val linkValid = Mux(words(1) === 0.U,words(2) === remaining,
        words(2) < remaining && words(1) > page && pageInBounds(words(1)))
      when(words(0) =/= PrimitiveBinPageLayout.magic.U || !owner || !countValid || !linkValid) {
        status := PrimitiveBinReadStatus.invalidPage.U
        state := finish
      }.otherwise {
        nextPage := words(1)
        pageCount := words(2)
        index := 0.U
        pages := pages+1.U
        state := emit
      }
    }
    is(emit) {
      def recordAddress(i: UInt): UInt =
        page + PrimitiveBinPageLayout.headerBytes.U + (i << 4)
      def recordHit(i: UInt): Bool = {
        val address = recordAddress(i)
        lineValid && address(31,6) === lineAddress(31,6)
      }
      def recordWords(i: UInt) = {
        val address = recordAddress(i)
        val groups = VecInit((0 until 4).map(g => VecInit((0 until 4).map(w =>
          line(128*g+32*w+31,128*g+32*w)))))
        groups(address(5,4))
      }
      // One record is staged while the previous one is admitted. The wide
      // sequence compare and line selection therefore end at the staged
      // registers instead of feeding the 32-bit counter carry chains in the
      // same cycle.
      val nextIndex = index+1.U
      val nextWords = recordWords(nextIndex)
      val nextSequence = Mux(stagedValid,stagedSequence,lastSequence)
      val nextHasOrder = stagedValid || haveSequence
      val nextRemaining = Mux(stagedValid,remaining-1.U,remaining)
      val nextEligible = nextRemaining =/= 0.U && nextIndex < pageCount &&
        recordHit(nextIndex) && !(nextHasOrder && nextWords(2) <= nextSequence)
      when(stagedValid) {
        when(!stop && (!referenceValid || io.reference.ready)) {
          reference := staged
          referenceValid := true.B
          index := index+1.U
          remaining := remaining-1.U
          admitted := admitted+1.U
          lastSequence := stagedSequence
          haveSequence := true.B
          stagedValid := nextEligible
          when(nextEligible) {
            staged.identity := descriptor.pool.identity
            staged.tile := descriptor.tile
            staged.ordinal := admitted+1.U
            staged.reference.primitive := nextWords(0)
            staged.reference.state := nextWords(1)
            staged.reference.sequence := nextWords(2)
            staged.reference.token := nextWords(3)
            stagedSequence := nextWords(2)
          }
        }
      }.otherwise {
        val address = recordAddress(index)
        val hit = lineValid && address(31,6) === lineAddress(31,6)
        val words = recordWords(index)
        when(remaining === 0.U) { state := finish }
          .elsewhen(index === pageCount) {
            when(nextPage === 0.U) { status := PrimitiveBinReadStatus.invalidPage.U; state := finish }
              .otherwise { page := nextPage; state := headerPrepare }
          }.elsewhen(!hit) {
            requestAddress := Cat(address(31,6),0.U(6.W))
            requestHeader := false.B
            state := offer
          }.elsewhen(haveSequence && words(2) <= lastSequence) {
            status := PrimitiveBinStatus.invalidOrder.U
            state := finish
          }.otherwise {
            staged.identity := descriptor.pool.identity
            staged.tile := descriptor.tile
            staged.ordinal := admitted
            staged.reference.primitive := words(0)
            staged.reference.state := words(1)
            staged.reference.sequence := words(2)
            staged.reference.token := words(3)
            stagedSequence := words(2)
            stagedValid := true.B
          }
      }
    }
    is(finish) {
      when(!referenceValid) {
        result.identity := descriptor.pool.identity
        result.tile := descriptor.tile
        result.status := effectiveStatus
        result.emitted := emitted
        result.pages := pages
        result.readLines := readLines
        state := done
      }
    }
    is(done) { when(io.completion.fire) { state := idle } }
  }
  // Neither an offered read nor an offered reference may be withdrawn.
  // Finish owns no memory request; it waits for the remaining output reply.
  when(stop && state =/= idle && state =/= done && state =/= offer &&
      state =/= waiting && state =/= finish) { state := finish }
  when(io.cancel && state =/= idle && state =/= done) {
    cancelled := true.B
    when(status === PrimitiveBinStatus.success.U && !faultNow) {
      status := PrimitiveBinStatus.cancelled.U
    }
  }
}
