package opengpu.graphics

import chisel3._
import chisel3.util._

/** Route one bin pass's tagged terminal reports to independently owned image
  * engines. Invalid engine tags are consumed and retained as a pass fault.
  * Clear that fault only after the enclosing pass and all engines drain.
  */
class LayeredBinTileDispatch(p:VertexShaderStageConfig=VertexShaderStageConfig(),engines:Int=2) extends Module {
  require(engines>=1 && engines<=4)
  val io=IO(new Bundle {
    val enable=Input(Bool());val templates=Input(Vec(engines,new LayeredTileImageBindingRequest))
    val source=Vec(engines,Flipped(Decoupled(new WindowPrimitiveReplay(p))))
    val sourceDone=Flipped(Decoupled(new WindowPrimitiveTileCompletion));val rendererPermit=Output(Vec(engines,Bool()))
    val imageBegin=Vec(engines,Decoupled(new LayeredTileImageBindingRequest))
    val imageIn=Vec(engines,Decoupled(new WindowPrimitiveReplay(p)));val imageEnd=Vec(engines,Decoupled(Bool()))
    val imageCompletion=Vec(engines,Flipped(Decoupled(new LayeredReplayTileCompletion)))
    val imageCancel=Output(Vec(engines,Bool()));val completion=Vec(engines,Decoupled(new LayeredBinTileCompletion))
    val cancel=Input(Bool());val cancelSource=Output(Bool());val protocolFault=Output(Bool());val clearFault=Input(Bool())
    val busy=Output(Bool())
  })
  private val fault=RegInit(false.B);private val owners=Seq.fill(engines)(Module(new LayeredBinTileOwner(p)))
  private val engine=io.sourceDone.bits.bins.engine
  private val validEngine=engine<engines.U
  private val ready=VecInit(owners.zipWithIndex.map{case(o,e)=>engine===e.U && o.io.sourceDone.ready}).asUInt.orR
  io.sourceDone.ready:=io.enable && Mux(validEngine,ready,!fault)
  when(io.sourceDone.fire && !validEngine){fault:=true.B}
  when(io.clearFault && !io.busy && !io.sourceDone.fire){fault:=false.B}
  io.protocolFault:=fault
  io.cancelSource:=fault || owners.map(_.io.cancelSource).reduce(_ || _)
  io.busy:=owners.map(_.io.busy).reduce(_ || _)
  for((o,e)<-owners.zipWithIndex){
    o.io.enable:=io.enable;o.io.template:=io.templates(e);o.io.source<>io.source(e)
    o.io.sourceDone.valid:=io.sourceDone.valid && engine===e.U;o.io.sourceDone.bits:=io.sourceDone.bits
    io.rendererPermit(e):=o.io.rendererPermit
    io.imageBegin(e)<>o.io.imageBegin;io.imageIn(e)<>o.io.imageIn;io.imageEnd(e)<>o.io.imageEnd
    o.io.imageCompletion<>io.imageCompletion(e);io.imageCancel(e):=o.io.imageCancel
    io.completion(e)<>o.io.completion;o.io.cancel:=io.cancel || fault
  }
}
