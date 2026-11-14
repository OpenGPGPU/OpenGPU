package opengpu.system
import opengpu.graphics.tessellation._

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{CacheLineInvalidate,ComputeMemoryRequest,ComputeMemoryResponse}
import opengpu.graphics._

class TessellationCuIsolineSystemTask(config: GpuConfig, idWidth: Int) extends Bundle {
  val patch = new TessellationCuIsolineSharedTask(config, idWidth)
  val nativeResources = Bool(); val uniformResources = Bool()
  val uniforms = new TessellationUniformTaskResources
}

/** Own one tagged control patch and its evaluation invocations on the shared
  * CU pool. The surrounding router reserves the command tag and forwards the
  * texture, trap and invalidation services through completion. */
class TessellationCuIsolineSystemClient(config: GpuConfig, idWidth: Int, numComputeUnits: Int, tessellationUniforms: Boolean=false) extends Module {
  require(numComputeUnits > 0)
  val io = IO(new Bundle {
    val task = Flipped(Decoupled(new TessellationCuIsolineSystemTask(config, idWidth)))
    val opened = Decoupled(UInt(2.W)); val load = Flipped(Decoupled(new TessellationPatchAccess))
    val seal = Flipped(Decoupled(Bool())); val inspect = Flipped(Decoupled(new TessellationPatchAccess))
    val reply = Decoupled(new TessellationContextReply)
    val out = Decoupled(new EvaluatedIsolinePrimitive); val completion = Decoupled(new TessellationCuIsolineCompletion)
    val cancel = Input(Bool()); val busy = Output(Bool())
    val shared = Flipped(new SharedCuGraphicsPort(config, idWidth, numComputeUnits, geometryAvailable = true, tessellationAvailable = true))
    val texture = Vec(numComputeUnits, new SharedCuTexturePort(config))
    val uniformMemoryRequest=if(tessellationUniforms)Some(Decoupled(new ComputeMemoryRequest(config,64,2)))else None
    val uniformMemoryResponse=if(tessellationUniforms)Some(Flipped(Decoupled(new ComputeMemoryResponse(64,2))))else None
    val trap = Decoupled(new TaggedGraphicsTrap(config, idWidth, numComputeUnits))
    val invalidate = Flipped(Decoupled(new CacheLineInvalidate(config))); val invalidateDone = Decoupled(new CacheLineInvalidate(config))
  })
  private val client = Module(new TessellationCuIsolineSharedEvaluator(config, idWidth, numComputeUnits))
  private val tag = Reg(UInt(idWidth.W)); private val native = Reg(Bool()); private val uniform = Reg(Bool())
  private val scope=Reg(new TessellationUniformTaskResources)
  private val service=if(tessellationUniforms)Some(Module(new TessellationUniformCuService(config,idWidth,numComputeUnits)))else None
  private val serviceBusy=service.map(_.io.busy).getOrElse(false.B)
  private val serviceFault=RegInit(false.B)
  private val cancelled=RegInit(false.B)
  when(client.io.busy && io.cancel){cancelled:=true.B}
  private val rejected=RegInit(false.B)
  private val rejectedResult=Reg(chiselTypeOf(io.completion.bits))
  private val scopeLegal=if(tessellationUniforms)TessellationUniformTaskResources.legal(io.task.bits.uniforms) && !io.task.bits.uniformResources
    else io.task.bits.uniforms.asUInt===0.U
  client.io.task.valid := io.task.valid && scopeLegal && !rejected && !serviceBusy
  client.io.task.bits := io.task.bits.patch
  io.task.ready := !rejected && !serviceBusy && Mux(scopeLegal,client.io.task.ready,!client.io.busy && !io.cancel)
  when(io.task.fire){scope:=io.task.bits.uniforms;serviceFault:=false.B;cancelled:=false.B
    when(!scopeLegal){rejected:=true.B;rejectedResult:=0.U.asTypeOf(rejectedResult)
      rejectedResult.taskId:=io.task.bits.patch.control.patch.context.taskId;rejectedResult.status:=1.U}}
  when(io.task.fire) { tag := io.task.bits.patch.commandId; native := io.task.bits.nativeResources; uniform := io.task.bits.uniformResources }
  io.opened <> client.io.opened; client.io.load <> io.load; client.io.seal <> io.seal
  client.io.inspect <> io.inspect; io.reply <> client.io.reply; io.out <> client.io.out
  io.completion.valid:=rejected || (client.io.completion.valid && !serviceBusy)
  io.completion.bits:=Mux(rejected,rejectedResult,client.io.completion.bits)
  when(!rejected && serviceFault && (client.io.completion.bits.status===0.U || client.io.completion.bits.status===4.U)){
    io.completion.bits.status:=2.U
  }
  client.io.completion.ready:=io.completion.ready && !rejected && !serviceBusy
  when(io.completion.fire && rejected){rejected:=false.B}
  client.io.cancel := io.cancel || cancelled || serviceFault || service.map(_.io.fault).getOrElse(false.B)
  io.busy := client.io.busy || rejected || serviceBusy
  private val controlLive = RegInit(false.B)
  private val controlOffer = client.io.controlKernel.valid
  io.shared.launch.valid := Mux(controlOffer, client.io.controlKernel.valid, client.io.launch.valid) && !serviceBusy
  io.shared.launch.bits := client.io.launch.bits
  when(controlOffer) { io.shared.launch.bits := 0.U.asTypeOf(io.shared.launch.bits)
    io.shared.launch.bits.commandId := tag; io.shared.launch.bits.launch := client.io.controlKernel.bits }
  client.io.controlKernel.ready := io.shared.launch.ready && controlOffer && !serviceBusy
  client.io.launch.ready := io.shared.launch.ready && !controlOffer && !serviceBusy
  io.shared.tessellationBases.get.valid := controlOffer
  io.shared.tessellationBases.get.bits := client.io.bases
  io.shared.geometryTask.get := client.io.geometryTask
  when(controlOffer) { io.shared.geometryTask.get.valid := false.B }
  when(client.io.controlKernel.fire) { controlLive := true.B }
  private val retired=RegInit(false.B)
  when(io.shared.launch.fire){retired:=false.B}
  private val completion=Wire(Decoupled(chiselTypeOf(io.shared.completion.bits)))
  if(tessellationUniforms){
    val q=Module(new Queue(chiselTypeOf(io.shared.completion.bits),1))
    q.io.enq<>io.shared.completion
    completion.valid:=q.io.deq.valid && !serviceBusy;completion.bits:=q.io.deq.bits
    q.io.deq.ready:=completion.ready && !serviceBusy
  }else completion<>io.shared.completion
  when(io.shared.completion.fire){retired:=true.B
    when(!io.shared.completion.bits.success && !io.cancel && !cancelled){serviceFault:=tessellationUniforms.B}}
  private val controlCompletion = controlLive && completion.bits.commandId === tag
  client.io.controlCompletion.valid := completion.valid && controlCompletion
  client.io.controlCompletion.bits.success := completion.bits.success
  client.io.kernelCompletion.valid := completion.valid && !controlCompletion
  client.io.kernelCompletion.bits := completion.bits
  completion.ready := Mux(controlCompletion, client.io.controlCompletion.ready, client.io.kernelCompletion.ready)
  when(client.io.controlCompletion.fire) { controlLive := false.B }
  client.io.event <> io.shared.geometryEvent.get
  client.io.owner := io.shared.owned; client.io.taskIds := io.shared.taskIds
  io.shared.geometryCancel.get := client.io.cancelCu
  private val selected = VecInit((0 until numComputeUnits).map(i => controlLive && io.shared.owned(i) && io.shared.taskIds(i) === tag))
  assert(PopCount(selected) <= 1.U, "patch control tag must identify at most one CU")
  private val ports = io.shared.tessellation.get
  client.io.access.valid := (0 until numComputeUnits).map(i => selected(i) && ports(i).request.valid).reduce(_ || _)
  client.io.access.bits := Mux1H(selected, ports.map(_.request.bits))
  client.io.park.valid := (0 until numComputeUnits).map(i => selected(i) && ports(i).park.valid).reduce(_ || _)
  client.io.park.bits := Mux1H(selected, ports.map(_.park.bits))
  client.io.finish.valid := (0 until numComputeUnits).map(i => selected(i) && ports(i).finish.valid).reduce(_ || _)
  client.io.finish.bits := Mux1H(selected, ports.map(_.finish.bits))
  client.io.accessResult.ready := (0 until numComputeUnits).map(i => selected(i) && ports(i).result.ready).reduce(_ || _)
  client.io.resume.ready := (0 until numComputeUnits).map(i => selected(i) && ports(i).resume.ready).reduce(_ || _)
  for (i <- 0 until numComputeUnits) {
    ports(i).request.ready := selected(i) && client.io.access.ready
    ports(i).park.ready := selected(i) && client.io.park.ready
    ports(i).finish.ready := selected(i) && client.io.finish.ready
    ports(i).result.valid := selected(i) && client.io.accessResult.valid; ports(i).result.bits := client.io.accessResult.bits
    ports(i).resume.valid := selected(i) && client.io.resume.valid; ports(i).resume.bits := client.io.resume.bits
    ports(i).cancel := selected(i) && client.io.controlCancel; ports(i).terminal := selected(i) && client.io.controlTerminal
  }
  private val launchUniform=if(tessellationUniforms)Mux(controlOffer,
    scope.control.resources.version===3.U,scope.evaluation.resources.version===3.U)else uniform
  io.shared.nativeResources := native || (tessellationUniforms.B && launchUniform)
  io.shared.uniformResources := launchUniform
  service match {
    case None=>io.texture<>io.shared.texture
    case Some(u)=>
      u.io.texture<>io.shared.texture;u.io.scope:=scope;u.io.commandId:=tag;u.io.control:=controlLive
      u.io.owned:=io.shared.owned;u.io.taskIds:=io.shared.taskIds;u.io.active:=client.io.busy
      u.io.retired:=retired;u.io.cancel:=io.cancel || cancelled || serviceFault || u.io.fault
      io.uniformMemoryRequest.get<>u.io.memoryRequest;u.io.memoryResponse<>io.uniformMemoryResponse.get
      when(u.io.fault){serviceFault:=true.B}
      // No unsupported sampler is exposed by this uniform-only provider.
      for(p<-io.texture){p.scalar.valid:=false.B;p.scalar.bits:=0.U.asTypeOf(p.scalar.bits)
        p.vector.valid:=false.B;p.vector.bits:=0.U.asTypeOf(p.vector.bits)
        p.scalarWriteback.ready:=false.B;p.vectorWriteback.ready:=false.B}
  }
  io.trap <> io.shared.trap
  io.shared.invalidate <> io.invalidate; io.invalidateDone <> io.shared.invalidateDone
  // Keep task-local stop/fault state through the actual held report and all
  // checked-service drain. Clear only on its external retirement; otherwise
  // these latches hold client.cancel high and block the next task that would
  // have cleared them. Last assignment also retires any old same-cycle fault.
  when(io.completion.fire){cancelled:=false.B;serviceFault:=false.B}
}
