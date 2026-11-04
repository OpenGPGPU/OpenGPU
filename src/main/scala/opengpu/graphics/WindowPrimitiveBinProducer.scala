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

private[graphics] class WindowPrimitiveBinCursor extends Bundle {
  val reference=new BinPrimitiveReference
  val tile=UInt(16.W);val x=UInt(16.W);val lowX=UInt(16.W);val highX=UInt(16.W)
  val rowsLeft=UInt(16.W);val rowStep=UInt(16.W)
}

/** Convert a generic post-viewport stream into durable records and ordered
  * tile-bin appends. Each nonempty primitive is stored exactly once; no bin
  * reference is offered before every record store is acknowledged. Raster
  * bounds are conservative, signed and widened before clipping to the screen
  * and scissor. Point/line bounds include the clamped size; unavailable fixed
  * guard-band coordinates fan out over the effective scissor, preserving the
  * data for the downstream guard-clip/raster policy instead of dropping it.
  * A bounded cursor batch merges tile visits, retaining input order within
  * each tile. Only durable record handles and bounds are buffered. A full
  * batch or an idle input flushes it; EOF waits for all pending references.
  *
  * Pair append/appended with TileBinReplayPass (or its directory) and replay
  * the handles through independent WindowPrimitiveRecordTransfer readers.
  * The owner binds both to disjoint arenas with identical pass identity and
  * grid, seals bins only after this completion, and retains records/state
  * until all engines and publication acknowledgements drain. This producer
  * alone does not finish a render pass or establish a native host command.
  */
class WindowPrimitiveBinProducer(config: GpuConfig=GpuConfig(),p: VertexShaderStageConfig=VertexShaderStageConfig(),
    g: GraphicsConfig=GraphicsConfig(),batchEntries: Int=8) extends Module {
  require(batchEntries==1 || batchEntries==8)
  require(g.subPixelBits>=2 && g.subPixelBits<=16)
  require(g.screenWidth>=1 && g.screenWidth<=65535 && g.screenHeight>=1 && g.screenHeight<=65535)
  val io=IO(new Bundle {
    val begin=Flipped(Decoupled(new WindowPrimitiveBinDescriptor))
    val in=Flipped(Decoupled(new WindowPrimitiveRecord(p)));val end=Flipped(Decoupled(Bool()))
    val append=Decoupled(new TileBinAppend);val appended=Flipped(Decoupled(new TileBinAppendResult))
    val completion=Decoupled(new WindowPrimitiveBinCompletion(p))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,1))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,1)))
    val cancel=Input(Bool());val cancelSource=Output(Bool());val busy=Output(Bool())
  })
  private val transfer=Module(new WindowPrimitiveRecordTransfer(config,p))
  val recordBytes=transfer.recordBytes
  private object State extends ChiselEnum {
    val idle,validate,validateGrid,ready,bounds,extrema,expand,clip,finishBounds,store,storeWait,choose,append,appendWait,finish,complete=Value
  }
  private val phase=RegInit(State.idle);private val descriptor=Reg(new WindowPrimitiveBinDescriptor)
  private val current=Reg(new WindowPrimitiveRecord(p));private val result=Reg(new WindowPrimitiveBinCompletion(p))
  private val status=RegInit(0.U(3.W));private val binStatus=RegInit(0.U(3.W))
  private val accepted=RegInit(0.U(32.W));private val stored=RegInit(0.U(32.W));private val references=RegInit(0.U(64.W))
  private val writeLines=RegInit(0.U(64.W));private val haveSequence=RegInit(false.B);private val lastSequence=Reg(UInt(32.W))
  private val columns=Reg(UInt(16.W));private val rows=Reg(UInt(16.W))
  private val descriptorLegal=Reg(Bool())
  private val lowX=Reg(UInt(16.W));private val highX=Reg(UInt(16.W));private val highY=Reg(UInt(16.W))
  private val x=Reg(UInt(16.W));private val y=Reg(UInt(16.W));private val faultTile=RegInit(0.U(32.W))
  private val cursors=Reg(Vec(batchEntries,new WindowPrimitiveBinCursor))
  private val cursorValid=RegInit(VecInit(Seq.fill(batchEntries)(false.B)))
  private val batchCount=RegInit(0.U(log2Ceil(batchEntries+1).W))
  private val selected=Reg(UInt(log2Ceil(batchEntries).max(1).W))
  private val pendingExhaustion=RegInit(false.B)
  private val axes=Reg(Vec(2,Vec(3,SInt(67.W))))
  private val limits=Reg(Vec(4,SInt(67.W)))
  private val pixels=Reg(Vec(4,SInt(67.W)))
  private val clipped=Reg(Vec(4,SInt(67.W)))
  private val scissor=Reg(Vec(4,UInt(16.W)))
  private val sizeInput=Reg(UInt(32.W));private val triangle=Reg(Bool())
  private val margin=Reg(SInt(67.W));private val maximumBias=Reg(SInt(67.W))
  private val unavailable=Reg(Bool());private val emptyScissor=Reg(Bool())
  private val nextRecordEnd=Reg(UInt(33.W))
  private def cursor(index: UInt): WindowPrimitiveBinCursor=
    if(batchEntries==1)cursors(0) else cursors(index)
  private def choose(indices: Seq[Int]): (Bool,UInt,UInt) = {
    if(indices.size==1){val i=indices.head;(cursorValid(i),cursors(i).tile,i.U(log2Ceil(batchEntries).max(1).W))}
    else {
      val(a,at,ai)=choose(indices.take(indices.size/2))
      val(b,bt,bi)=choose(indices.drop(indices.size/2))
      // Lower slots hold older input records. Ties retain their input order.
      val first=a && (!b || at<=bt)
      (a || b,Mux(first,at,bt),Mux(first,ai,bi))
    }
  }
  private val(anyCursor,_,nextCursor)=choose(0 until batchEntries)
  private val stop=status=/=0.U || io.cancel
  io.begin.ready:=phase===State.idle && !io.cancel;io.busy:=phase=/=State.idle
  io.in.ready:=phase===State.ready && batchCount<batchEntries.U && !stop
  io.end.ready:=phase===State.ready && !io.in.valid && batchCount===0.U && !stop
  io.cancelSource:=phase=/=State.idle && phase=/=State.complete && stop
  io.completion.valid:=phase===State.complete;io.completion.bits:=result
  io.append.valid:=phase===State.append
  io.append.bits.tile:=cursor(selected).tile
  io.append.bits.reference:=cursor(selected).reference
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
      accepted:=0.U;stored:=0.U;references:=0.U;writeLines:=0.U;haveSequence:=false.B;faultTile:=0.U
      cursorValid.foreach(_:=false.B);batchCount:=0.U;pendingExhaustion:=false.B;phase:=State.validate}}
    is(State.validate){
      val c=(descriptor.screenWidth.pad(17)+(1.U(17.W)<<descriptor.tileLog2)-1.U)>>descriptor.tileLog2
      val r=(descriptor.screenHeight.pad(17)+(1.U(17.W)<<descriptor.tileLog2)-1.U)>>descriptor.tileLog2
      columns:=c;rows:=r
      nextRecordEnd:=descriptor.arena.base.pad(33)+recordBytes.U(33.W)
      descriptorLegal:=descriptor.version===1.U && descriptor.arena.version===1.U && descriptor.tileLog2>=3.U && descriptor.tileLog2<=6.U &&
        descriptor.screenWidth>=1.U && descriptor.screenWidth<=g.screenWidth.U && descriptor.screenHeight>=1.U && descriptor.screenHeight<=g.screenHeight.U &&
        descriptor.arena.base(5,0)===0.U && descriptor.arena.limit(5,0)===0.U &&
        descriptor.arena.limit>=descriptor.arena.base && descriptor.arena.limit<=(BigInt(1)<<32).U
      phase:=Mux(stop,State.finish,State.validateGrid)
    }
    is(State.validateGrid){
      // Register rounded dimensions before multiplying and checking capacity.
      // The runtime tile-size shift must not share the grid admission path.
      when(stop){phase:=State.finish}.elsewhen(!descriptorLegal || columns*rows>65536.U){status:=1.U;phase:=State.finish}
        .otherwise{phase:=State.ready}
    }
    is(State.ready){when(stop){phase:=State.finish}.elsewhen(io.in.fire){
      current:=io.in.bits;accepted:=accepted+1.U;phase:=State.bounds
      val d=io.in.bits;val m=d.primitive.clipped.primitive
      val point=m.vertices(0);val rawSize=point.pointSize
      val selectedSize=Mux(point.pointSizeWritten,Mux(rawSize(31) || Fp32ViewportState.nan(rawSize) || rawSize===0.U,"h3f800000".U,rawSize),d.raster.pointSize)
      sizeInput:=Mux(m.kind===0.U,selectedSize,d.raster.lineWidth);triangle:=m.kind===2.U
      when((haveSequence && d.sequence<=lastSequence) || accepted==="hffffffff".U ||
        m.identity.asUInt=/=descriptor.arena.identity.asUInt || !Fp32RasterState.legal(d.raster,g) ||
        m.kind>2.U || m.vertexCount=/=m.kind+&1.U || m.varyingSlots>p.varyings.U){status:=1.U}
      haveSequence:=true.B;lastSequence:=d.sequence
    }.elsewhen(io.end.fire){when(!io.end.bits){status:=1.U};phase:=State.finish}
      // Flush a partial batch as soon as the input is idle. An upstream owner
      // may wait for append progress before producing another record or EOF.
      .elsewhen(batchCount=/=0.U){phase:=State.choose}}
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
      val size=Mux(triangle,0.U,Fp32RasterState.fixedSize(sizeInput,g.subPixelBits)).zext.pad(67)
      axes(0):=VecInit(xs);axes(1):=VecInit(ys)
      margin:=size
      unavailable:=current.primitive.guardBandRequired || current.primitive.vertices.zipWithIndex.map{case(v,i)=>i.U<m.vertexCount && !v.fixedValid}.reduce(_ || _)
      scissor:=VecInit(Seq(sx,sy,ex,ey));emptyScissor:=sx>=ex || sy>=ey
      phase:=Mux(stop,State.finish,State.extrema)
    }
    is(State.extrema){
      // Coordinates are signed64 and size is unsigned24. Signed67 retains
      // enough headroom to fold floor(sum/2^bits)+1 into one addition.
      maximumBias:=margin+(BigInt(1)<<g.subPixelBits).S(67.W)
      limits(0):=axes(0).reduce((a,b)=>Mux(a<b,a,b));limits(1):=axes(1).reduce((a,b)=>Mux(a<b,a,b))
      limits(2):=axes(0).reduce((a,b)=>Mux(a>b,a,b));limits(3):=axes(1).reduce((a,b)=>Mux(a>b,a,b))
      phase:=Mux(stop,State.finish,State.expand)
    }
    is(State.expand){
      for(i<-0 until 4){
        val adjusted=if(i<2)(limits(i)-margin)>>g.subPixelBits else (limits(i)+maximumBias)>>g.subPixelBits
        pixels(i):=Mux(unavailable,scissor(i).zext.pad(67),adjusted)
      }
      phase:=Mux(stop,State.finish,State.clip)
    }
    is(State.clip){
      for(i<-0 until 4){
        clipped(i):=(if(i<2)pixels(i).max(scissor(i).zext.pad(67)) else pixels(i).min(scissor(i).zext.pad(67)))
      }
      phase:=Mux(stop,State.finish,State.finishBounds)
    }
    is(State.finishBounds){
      val bx=clipped(0);val by=clipped(1);val tx=clipped(2);val ty=clipped(3)
      lowX:=bx.asUInt>>descriptor.tileLog2;x:=bx.asUInt>>descriptor.tileLog2;y:=by.asUInt>>descriptor.tileLog2
      highX:=(tx.asUInt-1.U)>>descriptor.tileLog2;highY:=(ty.asUInt-1.U)>>descriptor.tileLog2
      when(stop){phase:=State.finish}.elsewhen(bx>=tx || by>=ty || emptyScissor){phase:=State.ready}
        .elsewhen(nextRecordEnd>descriptor.arena.limit){
          when(batchCount=/=0.U){pendingExhaustion:=true.B;phase:=State.choose}
            .otherwise{status:=2.U;phase:=State.finish}
        }.otherwise{phase:=State.store}
    }
    // A newly offered transfer is irrevocable. Its cancellation path accepts
    // that offer and emits the result even if cancel arrived before acceptance.
    is(State.store){when(transfer.io.request.fire){phase:=State.storeWait}}
    is(State.storeWait){when(transfer.io.result.fire){writeLines:=writeLines+transfer.io.result.bits.lines
      when(transfer.io.result.bits.status=/=0.U && status===0.U && !io.cancel){status:=transfer.io.result.bits.status}
      when(stop || transfer.io.result.bits.status=/=0.U){phase:=State.finish}
        .otherwise{
          for(i<-0 until batchEntries){when(batchCount===i.U){
            cursorValid(i):=true.B
            cursors(i).reference.primitive:=stored;cursors(i).reference.state:=current.state
            cursors(i).reference.sequence:=current.sequence;cursors(i).reference.token:=current.token
            // Up to 65,536 tiles need exactly sixteen bits for their IDs.
            cursors(i).tile:=(y*columns+x)(15,0)
            cursors(i).x:=x;cursors(i).lowX:=lowX;cursors(i).highX:=highX
            cursors(i).rowsLeft:=highY-y;cursors(i).rowStep:=columns+lowX-highX
          }}
          stored:=stored+1.U;batchCount:=batchCount+1.U;nextRecordEnd:=nextRecordEnd+recordBytes.U
          phase:=Mux(batchCount+1.U===batchEntries.U,State.choose,State.ready)
        }}}
    is(State.choose){
      when(stop){phase:=State.finish}
        .elsewhen(anyCursor){selected:=nextCursor;phase:=State.append}
        .otherwise{
          batchCount:=0.U
          when(pendingExhaustion){status:=2.U;phase:=State.finish}.otherwise{phase:=State.ready}
        }
    }
    is(State.append){when(io.append.fire){phase:=State.appendWait}}
    is(State.appendWait){when(io.appended.fire){
      val a=io.appended.bits
      val matching=a.tile===io.append.bits.tile && a.identity.asUInt===descriptor.arena.identity.asUInt &&
        a.reference.asUInt===io.append.bits.reference.asUInt
      when(!matching && status===0.U && !io.cancel){status:=7.U}
      when(matching && a.status=/=0.U && status===0.U && !io.cancel){status:=5.U;binStatus:=a.status;faultTile:=a.tile}
      when(matching && a.status===0.U){references:=references+1.U}
      when(stop || !matching || a.status=/=0.U){phase:=State.finish}
        .otherwise{
          val c=cursor(selected)
          when(c.x===c.highX && c.rowsLeft===0.U){
            if(batchEntries==1)cursorValid(0):=false.B else cursorValid(selected):=false.B
          }.elsewhen(c.x===c.highX){
            c.x:=c.lowX;c.rowsLeft:=c.rowsLeft-1.U;c.tile:=c.tile+c.rowStep
          }.otherwise{c.x:=c.x+1.U;c.tile:=c.tile+1.U}
          phase:=State.choose
        }
    }}
    is(State.finish){result:=0.U.asTypeOf(result);result.identity:=descriptor.arena.identity
      result.status:=Mux(io.cancel && status===0.U,4.U,status);result.binStatus:=binStatus
      result.accepted:=accepted;result.stored:=stored;result.references:=references;result.recordWriteLines:=writeLines
      result.faultTile:=faultTile;result.restartValid:=status===2.U
      when(status===2.U){result.restart:=current};phase:=State.complete}
    is(State.complete){when(io.completion.fire){phase:=State.idle}}
  }
  when(io.completion.valid){assert(!transfer.io.busy,"bin producer escaped record acknowledgement drain")}
  when(phase===State.append || phase===State.appendWait){
    assert(if(batchEntries==1)cursorValid(0) else cursorValid(selected),"bin append lost its stored record cursor")
    assert(io.append.bits.reference.primitive<stored,"bin reference escaped its record store acknowledgement")
  }
}
