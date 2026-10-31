package opengpu.graphics

import chisel3._
import chisel3.util._

class LayeredBinTileCompletion extends Bundle {
  val status=UInt(3.W);val source=new WindowPrimitiveTileCompletion
  val imageStarted=Bool();val image=new LayeredReplayTileCompletion
}

/** One engine's bin-to-image handoff. The enclosing pass holds `template`
  * stable and keeps enable asserted through completion, including cancellation.
  * It owns the shared-CU and memory routers, propagates
  * cancelSource to its bin producer, and discards all outputs after a fault.
  */
class LayeredBinTileOwner(p: VertexShaderStageConfig=VertexShaderStageConfig()) extends Module {
  val io=IO(new Bundle {
    val enable=Input(Bool());val template=Input(new LayeredTileImageBindingRequest)
    val source=Flipped(Decoupled(new WindowPrimitiveReplay(p)))
    val sourceDone=Flipped(Decoupled(new WindowPrimitiveTileCompletion))
    val rendererPermit=Output(Bool())
    val imageBegin=Decoupled(new LayeredTileImageBindingRequest)
    val imageIn=Decoupled(new WindowPrimitiveReplay(p));val imageEnd=Decoupled(Bool())
    val imageCompletion=Flipped(Decoupled(new LayeredReplayTileCompletion))
    val imageCancel=Output(Bool());val cancel=Input(Bool());val cancelSource=Output(Bool())
    val completion=Decoupled(new LayeredBinTileCompletion);val busy=Output(Bool())
  })
  private val owned=RegInit(false.B);private val tile=Reg(UInt(32.W));private val request=Reg(new LayeredTileImageBindingRequest)
  private val offered=RegInit(false.B);private val started=RegInit(false.B);private val imageDone=RegInit(false.B)
  private val endSent=RegInit(false.B);private val haveBin=RegInit(false.B);private val stopped=RegInit(false.B)
  private val bin=Reg(new WindowPrimitiveTileCompletion);private val image=Reg(new LayeredReplayTileCompletion)
  private val output=Reg(new LayeredBinTileCompletion);private val outputValid=RegInit(false.B)
  private val sourceCode=Mux(io.sourceDone.bits.recordStatus=/=0.U,io.sourceDone.bits.recordStatus,io.sourceDone.bits.bins.status)
  private val imageFault=io.imageCompletion.valid && io.imageCompletion.bits.status=/=0.U
  private val stop=stopped || io.cancel || imageFault
  private val incoming=io.source.valid || io.sourceDone.valid
  private val candidate=Mux(io.source.valid,io.source.bits.source.tile,io.sourceDone.bits.bins.tile)
  io.busy:=owned || outputValid;io.rendererPermit:=io.enable && !owned && !outputValid
  io.cancelSource:=owned && (stop || (haveBin && (bin.recordStatus=/=0.U || bin.bins.status=/=0.U)))
  io.imageBegin.valid:=offered;io.imageBegin.bits:=request
  io.imageIn.valid:=io.enable && owned && started && !imageDone && !stop && io.source.valid
  io.imageIn.bits:=io.source.bits
  io.source.ready:=io.enable && (stop || (owned && started && !imageDone && io.imageIn.ready))
  io.sourceDone.ready:=io.enable && !haveBin && (owned || (!outputValid && !io.source.valid))
  io.imageEnd.valid:=owned && haveBin && started && !imageDone && !endSent && !stop && io.imageEnd.ready
  io.imageEnd.bits:=true.B;io.imageCancel:=started && stop
  io.imageCompletion.ready:=started && !imageDone
  io.completion.valid:=outputValid;io.completion.bits:=output
  when(io.completion.fire){outputValid:=false.B}
  when(io.enable && !owned && !outputValid && incoming){
    owned:=true.B;tile:=candidate;started:=false.B;imageDone:=false.B;endSent:=false.B;haveBin:=false.B
    stopped:=io.cancel;image:=0.U.asTypeOf(image)
    request:=io.template;request.tile:=candidate
    offered:= !io.cancel && (!io.sourceDone.valid || io.source.valid || sourceCode===0.U)
  }
  when(owned && io.cancel){stopped:=true.B}
  when(io.imageBegin.fire){offered:=false.B;started:=true.B}
  when(io.imageIn.fire){assert(io.imageIn.bits.source.tile===tile,"replay record changed tile owner")}
  when(io.sourceDone.fire){
    when(owned){assert(io.sourceDone.bits.bins.tile===tile,"bin completion changed tile owner")}
    bin:=io.sourceDone.bits;haveBin:=true.B
    when(sourceCode=/=0.U){stopped:=true.B}
  }
  when(io.imageEnd.fire){endSent:=true.B}
  when(io.imageCompletion.fire){image:=io.imageCompletion.bits;imageDone:=true.B
    when(io.imageCompletion.bits.status=/=0.U){stopped:=true.B}}
  when(owned && haveBin && !offered && (!started || imageDone) && !outputValid){
    output:=0.U.asTypeOf(output);output.source:=bin;output.imageStarted:=started;output.image:=image
    output.status:=Mux(bin.recordStatus=/=0.U,bin.recordStatus,Mux(bin.bins.status=/=0.U,bin.bins.status,
      Mux(image.status=/=0.U,image.status,Mux(stopped || io.cancel,4.U,0.U))))
    outputValid:=true.B;owned:=false.B;haveBin:=false.B
  }
}
