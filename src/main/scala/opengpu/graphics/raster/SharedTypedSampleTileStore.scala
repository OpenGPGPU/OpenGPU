package opengpu.graphics

import chisel3._
import chisel3.util._

/** One canonical SRAM shared by the four-sample native datapath and the legacy
  * typed policy owner. The enclosing pixel owner selects complete, drained
  * cohorts and aggregates native packet retirements into quad completions.
  * Only clear/dont-care RGBA8+D24S8 bindings are eligible for native service;
  * generic formats and cold loads retain the original refill/maintenance path.
  */
class SharedTypedSampleTileStore(p: TypedTileColorConfig=TypedTileColorConfig(parallelLanes=true,parallelDepth=true,streamingWordPorts=8),
    useBlackBoxes: Boolean=false) extends Module {
  require(p.bytes==32768 && p.parallelLanes && p.parallelDepth)
  val io=IO(new TypedTileColorStoreIO(p))
  val depthBounds=IO(Output(new D24DepthBounds))
  val samples=IO(new Bundle {
    val active=Input(Bool());val readyToEnter=Output(Bool());val eligible=Output(Bool())
    val bind=Flipped(Decoupled(new TypedTilePixelDescriptor))
    val in=Flipped(Decoupled(new TaggedTypedPixelQuad));val out=Decoupled(new TypedSamplePacketRetired)
    val drained=Output(Bool());val fault=Output(Bool());val supported=Output(Bool())
  })
  private val policy=Module(new TypedTileColorStore(p,externalMemory=true))
  private val resident=Module(new ResidentTypedSamplePipeline(useBlackBoxes,virtualWords=true,observeDepthWrites=true))
  io<>policy.io
  private val raw=policy.raw.get
  private val binding=Reg(new TypedTilePixelDescriptor)
  private val eligible=RegInit(false.B)
  private val summary=Module(new ConservativeD24DepthSummary())
  private val summaryStale=RegInit(true.B)
  private val summaryBindingPending=RegInit(false.B)
  private val incoming=WireDefault(binding)
  incoming.colors:=io.begin.bits;incoming.depth:=io.depth.get.views(0);incoming.stencil:=io.depth.get.views(1)
  private def summarySupported(d: TypedTilePixelDescriptor): Bool=
    d.depth.format===TypedDepthFormat.d24s8.U && d.depth.offset(2,0)===0.U &&
      (d.colors.tileLog2===3.U || d.colors.tileLog2===4.U) && d.colors.sampleMode<=2.U
  private def depthWords(d: TypedTilePixelDescriptor): UInt=
    (1.U(11.W)<<(((d.colors.tileLog2<<1)+&d.colors.sampleMode)-1.U))(9,0)
  // Reuse the canonical store's registered, pipelined clear conversion. A new
  // tile cannot trust its predecessor's bounds while preparation is pending.
  private val initialSummary=summaryBindingPending && raw.preparedDepthClear.valid
  private val recoverSummary=samples.bind.fire && summaryStale && !summaryBindingPending && summarySupported(binding)
  summary.io.bind.valid:=initialSummary || recoverSummary
  summary.io.bind.bits.words:=depthWords(binding)
  summary.io.bind.bits.clear:=initialSummary && binding.depth.loadOp===AttachmentLoadOp.clear.U
  summary.io.bind.bits.depth:=raw.preparedDepthClear.bits(23,0)
  // Generic owners may perform packed/partial writes or materialize a loaded
  // plane. Invalidate immediately; a later drained native bind recovers only
  // from complete observed coverage, never from the original clear value.
  private val invalidateSummary=io.cancel || policy.io.cancelSource || (resident.io.fault && samples.active && !samples.bind.fire) ||
    io.begin.fire || io.in.valid ||
    io.depth.get.request.map(_.valid).reduce(_ || _) || (io.memory.valid && io.memory.bits.write)
  summary.io.invalidate:=invalidateSummary
  when(invalidateSummary){summaryStale:=true.B}
    .elsewhen(summary.io.bind.valid){summaryStale:=false.B}
  when(initialSummary || invalidateSummary){summaryBindingPending:=false.B}
  when(io.begin.fire){summaryBindingPending:=summarySupported(incoming)}
  for((write,i)<-resident.io.depthWrites.get.zipWithIndex){
    // Do not put SRAM ready/arbitration on the combinational bounds-valid path.
    // Offered writes suppress trust immediately; this register bridges into
    // the summary's existing observation stages without delaying the SRAM.
    val committed=RegNext(write.valid,false.B)
    val bits=RegEnable(write.bits,write.valid)
    when(invalidateSummary || summary.io.bind.valid){committed:=false.B}
    val base=binding.depth.offset>>3
    val local=bits.address-base
    summary.io.writes(i).valid:=committed && bits.address>=base && local<depthWords(binding)
    summary.io.writes(i).bits.word:=local
    summary.io.writes(i).bits.depths(0):=bits.data(23,0)
    summary.io.writes(i).bits.depths(1):=bits.data(55,32)
  }
  depthBounds:=summary.io.bounds
  depthBounds.valid:=summary.io.bounds.valid && !summaryBindingPending && !resident.io.depthWriteOffered.get
  when(io.begin.fire){
    binding.version:=1.U;binding.colors:=io.begin.bits
    binding.depth:=io.depth.get.views(0);binding.stencil:=io.depth.get.views(1)
    eligible:=io.begin.bits.colors(0).enabled && io.begin.bits.colors(0).format===TypedColorFormat.id("RGBA8").U &&
      !VecInit(io.begin.bits.colors.drop(1).map(_.enabled)).asUInt.orR &&
      io.begin.bits.colors(0).loadOp=/=AttachmentLoadOp.load.U &&
      io.depth.get.views(0).format===TypedDepthFormat.d24s8.U &&
      io.depth.get.views(0).loadOp=/=AttachmentLoadOp.load.U && io.depth.get.views(1).format===0.U
  }
  samples.eligible:=eligible;samples.readyToEnter:=eligible && raw.readyForResident && resident.io.drained
  raw.residentActive:=samples.active;raw.drained:=resident.io.drained
  resident.io.maintenance:= !samples.active;resident.io.nativeMode:=true.B
  resident.io.begin:=false.B;resident.io.in.valid:=false.B
  resident.io.in.bits:=0.U.asTypeOf(resident.io.in.bits)
  resident.io.cancel:=io.cancel || policy.io.cancelSource
  resident.io.bind.valid:=samples.active && eligible && samples.bind.valid
  resident.io.bind.bits:=samples.bind.bits
  samples.bind.ready:=samples.active && eligible && resident.io.bind.ready
  resident.io.nativeIn.valid:=samples.active && samples.in.valid
  resident.io.nativeIn.bits:=samples.in.bits;samples.in.ready:=samples.active && resident.io.nativeIn.ready
  samples.out<>resident.io.out
  samples.drained:=resident.io.drained;samples.fault:=resident.io.fault;samples.supported:=resident.io.supported
  for(port<-0 until p.colorWordPorts){
    resident.io.memoryIn(port)<>raw.request(port)
    raw.response(port)<>resident.io.memoryOut(port)
  }
  for(port<-p.colorWordPorts until 8){
    resident.io.memoryIn(port).valid:=false.B
    resident.io.memoryIn(port).bits:=0.U.asTypeOf(resident.io.memoryIn(port).bits)
    resident.io.memoryOut(port).ready:=true.B
  }
  // Native old-word queries/materialization retain their four-port contract.
  for(port<-0 until 4){
    raw.query(port)<>resident.io.virtual.get(port)
    raw.materialized(port):=resident.io.materialized.get(port)
  }
  private val wasActive=RegNext(samples.active,false.B)
  when(samples.active && !wasActive){
    assert(samples.readyToEnter,"resident tile entry crossed a legacy owner or unsupported binding")
  }
  when(!samples.active && wasActive){assert(resident.io.drained,"legacy tile entry crossed native packet/write ownership")}
  when(samples.bind.fire){
    assert(samples.bind.bits.asUInt===binding.asUInt,"resident binding differs from the canonical tile policy")
    assert(!TypedTilePixelRules.invalid(samples.bind.bits,p),"resident binding bypassed ordinary validation")
  }
}
