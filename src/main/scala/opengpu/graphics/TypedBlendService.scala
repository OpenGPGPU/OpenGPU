package opengpu.graphics

import chisel3._
import chisel3.util._

/** One serialized typed blend service in front of the ordinary core blend,
  * the eleven separable KHR advanced modes and the four non-separable HSL
  * modes. A request selects the advanced path explicitly; advanced inputs are
  * premultiplied. Unsupported advanced equation ids are rejected before any
  * child work. `barrierReady` is high only after every accepted blend has
  * retired, which is the ordering condition a blend-barrier command needs.
  */
class TypedBlendServiceRequest extends Bundle {
  val tag=UInt(8.W)
  val source=Vec(4,UInt(32.W))
  /** Premultiplied destination for advanced blending. */
  val destination=Vec(4,UInt(32.W))
  /** Encoded destination word for the ordinary typed core blend. */
  val ordinaryDestination=UInt(128.W)
  val format=UInt(6.W);val state=new TypedBlendState
  val advanced=Bool();val advancedEquation=UInt(4.W)
}
class TypedBlendServiceResult extends Bundle {
  val rgba=Vec(4,UInt(32.W));val tag=UInt(8.W);val status=UInt(2.W)
  val arithmeticStatus=UInt(5.W);val cancelled=Bool()
}

class TypedBlendService extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new TypedBlendServiceRequest));val out=Decoupled(new TypedBlendServiceResult)
    val barrierReady=Output(Bool());val cancel=Input(Bool());val busy=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,run,invalid,respond=Value}
  private val phase=RegInit(State.idle)
  private val request=Reg(new TypedBlendServiceRequest)
  private val routeOrdinary=RegInit(false.B);private val routeSep=RegInit(false.B);private val routeHsl=RegInit(false.B)
  private val pending=RegInit(0.U(2.W));private val invalidResult=Reg(new TypedBlendServiceResult)
  private val ordinary=Module(new TypedColorBlend)
  private val sep=Module(new TypedAdvancedColorBlend)
  private val hsl=Module(new TypedAdvancedHslBlend)
  private val requestRouting=WireDefault(0.U(2.W))
  ordinary.io.in.valid:=phase===State.run && routeOrdinary
  ordinary.io.in.bits:=0.U.asTypeOf(ordinary.io.in.bits)
  ordinary.io.in.bits.source:=request.source;ordinary.io.in.bits.destination:=request.ordinaryDestination
  ordinary.io.in.bits.format:=request.format;ordinary.io.in.bits.state:=request.state;ordinary.io.in.bits.tag:=request.tag
  sep.io.in.valid:=phase===State.run && routeSep
  sep.io.in.bits:=0.U.asTypeOf(sep.io.in.bits)
  sep.io.in.bits.source:=request.source;sep.io.in.bits.destination:=request.destination
  sep.io.in.bits.equation:=request.advancedEquation;sep.io.in.bits.tag:=request.tag
  hsl.io.in.valid:=phase===State.run && routeHsl
  hsl.io.in.bits:=0.U.asTypeOf(hsl.io.in.bits)
  hsl.io.in.bits.source:=request.source;hsl.io.in.bits.destination:=request.destination
  hsl.io.in.bits.equation:=(request.advancedEquation-TypedAdvancedBlendEquation.softlight.U)(1,0)
  hsl.io.in.bits.tag:=request.tag
  ordinary.io.cancel:=io.cancel;sep.io.cancel:=io.cancel;hsl.io.cancel:=io.cancel
  private val childValid=Mux(routeOrdinary,ordinary.io.out.valid,Mux(routeSep,sep.io.out.valid,hsl.io.out.valid))
  private val childResult=Wire(new TypedBlendServiceResult)
  childResult:=0.U.asTypeOf(childResult)
  when(routeOrdinary){childResult.rgba:=ordinary.io.out.bits.rgba;childResult.tag:=ordinary.io.out.bits.tag
    childResult.status:=ordinary.io.out.bits.status;childResult.arithmeticStatus:=ordinary.io.out.bits.arithmeticStatus
    childResult.cancelled:=ordinary.io.out.bits.cancelled}
  when(routeSep){childResult.rgba:=sep.io.out.bits.rgba;childResult.tag:=sep.io.out.bits.tag
    childResult.status:=sep.io.out.bits.status;childResult.arithmeticStatus:=sep.io.out.bits.arithmeticStatus
    childResult.cancelled:=sep.io.out.bits.cancelled}
  when(routeHsl){childResult.rgba:=hsl.io.out.bits.rgba;childResult.tag:=hsl.io.out.bits.tag
    childResult.status:=hsl.io.out.bits.status;childResult.arithmeticStatus:=hsl.io.out.bits.arithmeticStatus
    childResult.cancelled:=hsl.io.out.bits.cancelled}
  ordinary.io.out.ready:=io.out.ready && routeOrdinary
  sep.io.out.ready:=io.out.ready && routeSep
  hsl.io.out.ready:=io.out.ready && routeHsl
  io.out.valid:=Mux(phase===State.invalid,true.B,Mux(phase===State.run,childValid,false.B))
  io.out.bits:=Mux(phase===State.invalid,invalidResult,childResult)
  io.in.ready:=phase===State.idle && !io.cancel
  io.barrierReady:=pending===0.U
  io.busy:=pending=/=0.U
  when(io.in.fire){
    request:=io.in.bits
    val adv=io.in.bits.advanced;val eq=io.in.bits.advancedEquation
    routeOrdinary := !adv || eq>14.U
    routeSep:=adv && eq<TypedAdvancedBlendEquation.supported.U
    routeHsl:=adv && eq>=TypedAdvancedBlendEquation.supported.U && eq<=14.U
    invalidResult:=0.U.asTypeOf(invalidResult);invalidResult.tag:=io.in.bits.tag
    invalidResult.status:=Mux(adv && eq>14.U,1.U,0.U)
    pending:=pending+1.U
    phase:=Mux(adv && eq>14.U,State.invalid,State.run)
  }
  when(io.out.fire){when(pending=/=0.U){pending:=pending-1.U}}
  when(phase===State.invalid && io.out.fire){phase:=State.idle}
  when(phase===State.run && childValid && io.out.fire){phase:=State.idle}
}
