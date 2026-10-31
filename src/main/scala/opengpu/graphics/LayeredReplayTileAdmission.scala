package opengpu.graphics

import chisel3._
import chisel3.util._

class LayeredReplayTileStart extends Bundle {
  val descriptor=new TypedReplayTileDescriptor
  val layer=UInt(16.W)
}

/** Owns binding and image admission until the image has published its tile. */
class LayeredReplayTileAdmission extends Module {
  val io=IO(new Bundle {
    val begin=Flipped(Decoupled(new LayeredTileImageBindingRequest))
    val imageBegin=Decoupled(new LayeredReplayTileStart)
    val imageCompletion=Flipped(Decoupled(new TypedReplayTileCompletion))
    val completion=Decoupled(new LayeredReplayTileCompletion)
    val cancel=Input(Bool());val imageCancel=Output(Bool());val running=Output(Bool())
    val cancelSource=Output(Bool());val busy=Output(Bool())
  })
  private val binder=Module(new LayeredTileImageBinding)
  private object State extends ChiselEnum {val idle,bindOffer,bindWait,imageOffer,run,complete=Value}
  private val state=RegInit(State.idle)
  private val request=Reg(new LayeredTileImageBindingRequest);private val bound=Reg(new LayeredReplayTileStart)
  private val cancelled=RegInit(false.B);private val result=Reg(new LayeredReplayTileCompletion)
  private val stop=cancelled || io.cancel
  io.busy:=state=/=State.idle;io.running:=state===State.run
  io.cancelSource:=state=/=State.idle && (stop || (state===State.complete && result.status=/=0.U))
  io.begin.ready:=state===State.idle && !io.cancel
  io.completion.valid:=state===State.complete;io.completion.bits:=result
  when(state=/=State.idle && state=/=State.complete && io.cancel){cancelled:=true.B}
  binder.io.in.valid:=state===State.bindOffer && !stop;binder.io.in.bits:=request
  binder.io.cancel:=state===State.bindWait && stop;binder.io.out.ready:=state===State.bindWait
  io.imageBegin.valid:=state===State.imageOffer;io.imageBegin.bits:=bound
  io.imageCancel:=state===State.run && stop
  io.imageCompletion.ready:=state===State.run
  switch(state){
    is(State.idle){when(io.begin.fire){request:=io.begin.bits;result:=0.U.asTypeOf(result)
      result.tile:=io.begin.bits.tile;cancelled:=false.B;state:=State.bindOffer}}
    is(State.bindOffer){when(stop){result.status:=4.U;state:=State.complete}
      .elsewhen(binder.io.in.fire){state:=State.bindWait}}
    is(State.bindWait){when(binder.io.out.fire){
      when(stop || !binder.io.out.bits.bindingValid){result.status:=Mux(stop,4.U,binder.io.out.bits.status);state:=State.complete}
        .otherwise{bound.descriptor:=binder.io.out.bits.descriptor;bound.layer:=binder.io.out.bits.layer
          result.layer:=binder.io.out.bits.layer;state:=State.imageOffer}}}
    is(State.imageOffer){when(io.imageBegin.fire){result.imageStarted:=true.B;state:=State.run}}
    is(State.run){when(io.imageCompletion.fire){result.image:=io.imageCompletion.bits
      result.status:=Mux(stop && io.imageCompletion.bits.status===0.U,4.U,io.imageCompletion.bits.status);state:=State.complete}}
    is(State.complete){when(io.completion.fire){state:=State.idle}}
  }
}
