package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}

class WindowPrimitiveBinDescriptor extends Bundle {
  val version=UInt(8.W);val arena=new PrimitiveStateDescriptor
  val screenWidth=UInt(16.W);val screenHeight=UInt(16.W);val tileLog2=UInt(3.W)
}
class WindowPrimitiveBinCompletion(p: VertexShaderStageConfig) extends Bundle {
  val identity=new GraphicsWorkIdentity
  /** 0 success, 1 descriptor/order, 2 record exhaustion, 3 memory,
    * 4 cancelled, 5 bin rejection, 7 reply protocol. */
  val status=UInt(3.W);val binStatus=UInt(3.W)
  val accepted=UInt(32.W);val stored=UInt(32.W);val references=UInt(64.W)
  val recordWriteLines=UInt(64.W);val faultTile=UInt(32.W)
  /** Arena exhaustion before a record store retains the exact rejected input.
    * Bin rejection may have committed part of this record's fanout; it does
    * not establish a restartable pass prefix and must invalidate the pass.
    */
  val restartValid=Bool();val restart=new WindowPrimitiveRecord(p)
}

/** Convert a generic post-viewport stream into durable records and ordered
  * tile-bin appends. Each nonempty primitive is stored exactly once; no bin
  * reference is offered before every record store is acknowledged. Raster
  * bounds are conservative, signed and widened before clipping to the screen
  * and scissor. Point/line bounds include the clamped size; unavailable fixed
  * guard-band coordinates fan out over the effective scissor, preserving the
  * data for the downstream guard-clip/raster policy instead of dropping it.
  *
  * Pair append/appended with TileBinReplayPass (or its directory) and replay
  * the handles through independent WindowPrimitiveRecordTransfer readers.
  * The owner binds both to disjoint arenas with identical pass identity and
  * grid, seals bins only after this completion, and retains records/state
  * until all engines and publication acknowledgements drain. This producer
  * alone does not finish a render pass or establish a native host command.
  */
class WindowPrimitiveBinProducer(config: GpuConfig=GpuConfig(),p: VertexShaderStageConfig=VertexShaderStageConfig(),
    g: GraphicsConfig=GraphicsConfig(),layered: Boolean=false) extends Module {
  require(g.subPixelBits>=2 && g.subPixelBits<=16)
  require(g.screenWidth>=1 && g.screenWidth<=65535 && g.screenHeight>=1 && g.screenHeight<=65535)
  val io=IO(new Bundle {
    val begin=Flipped(Decoupled(new WindowPrimitiveBinDescriptor))
    val layerCount=if(layered)Some(Input(UInt(16.W)))else None
    val in=Flipped(Decoupled(new WindowPrimitiveRecord(p)));val end=Flipped(Decoupled(Bool()))
    val append=Decoupled(new TileBinAppend);val appended=Flipped(Decoupled(new TileBinAppendResult))
    val completion=Decoupled(new WindowPrimitiveBinCompletion(p))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,1))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,1)))
    val cancel=Input(Bool());val cancelSource=Output(Bool());val busy=Output(Bool())
  })
  private val transfer=Module(new WindowPrimitiveRecordTransfer(config,p))
  val recordBytes=transfer.recordBytes
  private object State extends ChiselEnum {val idle,validate,ready,bounds,store,storeWait,append,appendWait,finish,complete=Value}
  private val phase=RegInit(State.idle);private val descriptor=Reg(new WindowPrimitiveBinDescriptor)
  private val layerCount=if(layered)Some(Reg(UInt(16.W)))else None
  private val current=Reg(new WindowPrimitiveRecord(p));private val result=Reg(new WindowPrimitiveBinCompletion(p))
  private val status=RegInit(0.U(3.W));private val binStatus=RegInit(0.U(3.W))
  private val accepted=RegInit(0.U(32.W));private val stored=RegInit(0.U(32.W));private val references=RegInit(0.U(64.W))
  private val writeLines=RegInit(0.U(64.W));private val haveSequence=RegInit(false.B);private val lastSequence=Reg(UInt(32.W))
  private val columns=Reg(UInt(16.W));private val rows=Reg(UInt(16.W))
  private val layerStride=if(layered)Some(Reg(UInt(16.W)))else None
  private val lowX=Reg(UInt(16.W));private val highX=Reg(UInt(16.W));private val highY=Reg(UInt(16.W))
  private val x=Reg(UInt(16.W));private val y=Reg(UInt(16.W));private val faultTile=RegInit(0.U(32.W))
  private val stop=status=/=0.U || io.cancel
  io.begin.ready:=phase===State.idle && !io.cancel;io.busy:=phase=/=State.idle
  io.in.ready:=phase===State.ready && !stop
  io.end.ready:=phase===State.ready && !io.in.valid && !stop
  io.cancelSource:=phase=/=State.idle && phase=/=State.complete && stop
  io.completion.valid:=phase===State.complete;io.completion.bits:=result
  io.append.valid:=phase===State.append
  private val currentLayer=Mux(current.geometry.valid,current.geometry.bits.layer,0.U)
  io.append.bits.tile:= (if(layered)currentLayer(15,0)*layerStride.get+y*columns+x else y*columns+x)
  io.append.bits.reference.primitive:=stored-1.U;io.append.bits.reference.state:=current.state
  io.append.bits.reference.sequence:=current.sequence;io.append.bits.reference.token:=current.token
  io.appended.ready:=phase===State.appendWait
  transfer.io.request.valid:=phase===State.store
  transfer.io.request.bits.arena:=descriptor.arena;transfer.io.request.bits.handle:=stored
  transfer.io.request.bits.write:=true.B;transfer.io.request.bits.record:=current
  transfer.io.result.ready:=phase===State.storeWait
  transfer.io.cancel:=(io.cancel || status=/=0.U) && phase=/=State.store
  io.memoryRequest<>transfer.io.memoryRequest;transfer.io.memoryResponse<>io.memoryResponse
  when(io.cancel && phase=/=State.idle && phase=/=State.complete && status===0.U){status:=4.U}
  switch(phase) {
    is(State.idle){when(io.begin.fire){descriptor:=io.begin.bits;status:=0.U;binStatus:=0.U
      layerCount.foreach(_:=io.layerCount.get)
      accepted:=0.U;stored:=0.U;references:=0.U;writeLines:=0.U;haveSequence:=false.B;faultTile:=0.U;phase:=State.validate}}
    is(State.validate){
      val c=(descriptor.screenWidth.pad(17)+(1.U(17.W)<<descriptor.tileLog2)-1.U)>>descriptor.tileLog2
      val r=(descriptor.screenHeight.pad(17)+(1.U(17.W)<<descriptor.tileLog2)-1.U)>>descriptor.tileLog2
      columns:=c;rows:=r;layerStride.foreach(_:=c*r)
      val legal=descriptor.version===1.U && descriptor.arena.version===1.U && descriptor.tileLog2>=3.U && descriptor.tileLog2<=6.U &&
        descriptor.screenWidth>=1.U && descriptor.screenWidth<=g.screenWidth.U && descriptor.screenHeight>=1.U && descriptor.screenHeight<=g.screenHeight.U &&
        (if(layered)layerCount.get=/=0.U && (c*r)*layerCount.get<=65535.U else c*r<=65535.U) &&
        descriptor.arena.base(5,0)===0.U && descriptor.arena.limit(5,0)===0.U &&
        descriptor.arena.limit>=descriptor.arena.base && descriptor.arena.limit<=(BigInt(1)<<32).U
      when(stop){phase:=State.finish}.elsewhen(!legal){status:=1.U;phase:=State.finish}.otherwise{phase:=State.ready}
    }
    is(State.ready){when(stop){phase:=State.finish}.elsewhen(io.in.fire){
      current:=io.in.bits;accepted:=accepted+1.U;phase:=State.bounds
      val d=io.in.bits;val m=d.primitive.clipped.primitive
      when((haveSequence && d.sequence<=lastSequence) || accepted==="hffffffff".U ||
        m.identity.asUInt=/=descriptor.arena.identity.asUInt || !Fp32RasterState.legal(d.raster,g) ||
        m.kind>2.U || m.vertexCount=/=m.kind+&1.U || m.varyingSlots>p.varyings.U ||
        (if(layered)Mux(d.geometry.valid,d.geometry.bits.layer,0.U)>=layerCount.get else false.B)){status:=1.U}
      haveSequence:=true.B;lastSequence:=d.sequence
    }.elsewhen(io.end.fire){when(!io.end.bits){status:=1.U};phase:=State.finish}}
    is(State.bounds){
      val m=current.primitive.clipped.primitive;val r=current.raster
      val sw=Mux(r.screenW===0.U || r.screenW>g.screenWidth.U,g.screenWidth.U,r.screenW)
      val sh=Mux(r.screenH===0.U || r.screenH>g.screenHeight.U,g.screenHeight.U,r.screenH)
      val screenW=sw.min(descriptor.screenWidth);val screenH=sh.min(descriptor.screenHeight)
      val sx=Mux(r.scissorEnable,r.scissorMinX,0.U);val sy=Mux(r.scissorEnable,r.scissorMinY,0.U)
      val ex=Mux(r.scissorEnable,r.scissorMaxX.min(screenW),screenW)
      val ey=Mux(r.scissorEnable,r.scissorMaxY.min(screenH),screenH)
      val xs=current.primitive.vertices.zipWithIndex.map{case(v,i)=>Mux(i.U<m.vertexCount,v.screenX,current.primitive.vertices(0).screenX).pad(67)}
      val ys=current.primitive.vertices.zipWithIndex.map{case(v,i)=>Mux(i.U<m.vertexCount,v.screenY,current.primitive.vertices(0).screenY).pad(67)}
      val point=m.vertices(0);val rawSize=point.pointSize
      val selectedSize=Mux(point.pointSizeWritten,Mux(rawSize(31) || Fp32ViewportState.nan(rawSize) || rawSize===0.U,"h3f800000".U,rawSize),r.pointSize)
      val margin=Mux(m.kind===2.U,0.U,Fp32RasterState.fixedSize(Mux(m.kind===0.U,selectedSize,r.lineWidth),g.subPixelBits)).zext.pad(67)
      val unavailable=current.primitive.guardBandRequired || current.primitive.vertices.zipWithIndex.map{case(v,i)=>i.U<m.vertexCount && !v.fixedValid}.reduce(_ || _)
      def minimum(values: Seq[SInt],scissor: UInt): SInt=Mux(unavailable,scissor.zext.pad(67),
        ((values.reduce((a,b)=>Mux(a<b,a,b))-margin)>>g.subPixelBits).max(scissor.zext.pad(67)))
      def maximum(values: Seq[SInt],scissor: UInt): SInt=Mux(unavailable,scissor.zext.pad(67),
        (((values.reduce((a,b)=>Mux(a>b,a,b))+margin)>>g.subPixelBits)+1.S).min(scissor.zext.pad(67)))
      val bx=minimum(xs,sx);val by=minimum(ys,sy);val tx=maximum(xs,ex);val ty=maximum(ys,ey)
      lowX:=bx.asUInt>>descriptor.tileLog2;x:=bx.asUInt>>descriptor.tileLog2;y:=by.asUInt>>descriptor.tileLog2
      highX:=(tx.asUInt-1.U)>>descriptor.tileLog2;highY:=(ty.asUInt-1.U)>>descriptor.tileLog2
      val end=descriptor.arena.base.pad(64)+(stored.pad(64)+1.U)*recordBytes.U(64.W)
      when(stop){phase:=State.finish}.elsewhen(bx>=tx || by>=ty || sx>=ex || sy>=ey){phase:=State.ready}
        .elsewhen(end>descriptor.arena.limit){status:=2.U;phase:=State.finish}.otherwise{phase:=State.store}
    }
    // A newly offered transfer is irrevocable. Its cancellation path accepts
    // that offer and emits the result even if cancel arrived before acceptance.
    is(State.store){when(transfer.io.request.fire){phase:=State.storeWait}}
    is(State.storeWait){when(transfer.io.result.fire){writeLines:=writeLines+transfer.io.result.bits.lines
      when(transfer.io.result.bits.status=/=0.U && status===0.U && !io.cancel){status:=transfer.io.result.bits.status}
      when(stop || transfer.io.result.bits.status=/=0.U){phase:=State.finish}
        .otherwise{stored:=stored+1.U;phase:=State.append}}}
    is(State.append){when(io.append.fire){phase:=State.appendWait}}
    is(State.appendWait){when(io.appended.fire){
      val a=io.appended.bits
      val matching=a.tile===io.append.bits.tile && a.identity.asUInt===descriptor.arena.identity.asUInt &&
        a.reference.asUInt===io.append.bits.reference.asUInt
      when(!matching && status===0.U && !io.cancel){status:=7.U}
      when(matching && a.status=/=0.U && status===0.U && !io.cancel){status:=5.U;binStatus:=a.status;faultTile:=a.tile}
      when(matching && a.status===0.U){references:=references+1.U}
      when(stop || !matching || a.status=/=0.U){phase:=State.finish}
        .elsewhen(x===highX && y===highY){phase:=State.ready}
        .otherwise{when(x===highX){x:=lowX;y:=y+1.U}.otherwise{x:=x+1.U};phase:=State.append}
    }}
    is(State.finish){result:=0.U.asTypeOf(result);result.identity:=descriptor.arena.identity
      result.status:=Mux(io.cancel && status===0.U,4.U,status);result.binStatus:=binStatus
      result.accepted:=accepted;result.stored:=stored;result.references:=references;result.recordWriteLines:=writeLines
      result.faultTile:=faultTile;result.restartValid:=status===2.U
      when(status===2.U){result.restart:=current};phase:=State.complete}
    is(State.complete){when(io.completion.fire){phase:=State.idle}}
  }
  when(io.completion.valid){assert(!transfer.io.busy,"bin producer escaped record acknowledgement drain")}
}
