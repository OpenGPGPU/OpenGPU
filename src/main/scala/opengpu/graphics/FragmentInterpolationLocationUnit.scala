package opengpu.graphics

import chisel3._
import chisel3.util._

/** Interpolation location selection for `interpolateAt*`.
  *
  * Selects the fragment center, a programmed covered sample position, the
  * lowest covered sample position for centroid interpolation, or the window
  * offset location computed by the shared clip-float adder. The varying
  * evaluation at that location remains outside this unit.
  */
object FragmentInterpolationLocationMode {val center=0;val centroid=1;val sample=2;val offset=3}
object FragmentInterpolationLocationStatus {val ok=0;val invalid=1;val arithmetic=2}

class FragmentInterpolationLocationRequest extends Bundle {
  val tag=UInt(8.W);val mode=UInt(2.W);val center=Vec(2,UInt(32.W))
  val sampleCount=UInt(3.W);val positions=Vec(4,Vec(2,UInt(32.W)))
  val coverage=UInt(4.W);val sampleIndex=UInt(2.W);val offset=Vec(2,UInt(32.W))
}
class FragmentInterpolationLocationResult extends Bundle {
  val tag=UInt(8.W);val status=UInt(2.W);val location=Vec(2,UInt(32.W))
}

class FragmentInterpolationLocationUnit extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new FragmentInterpolationLocationRequest))
    val out=Decoupled(new FragmentInterpolationLocationResult)
    val busy=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,select,addX,addXWait,addY,addYWait,respond=Value}
  private val phase=RegInit(State.idle);private val request=Reg(new FragmentInterpolationLocationRequest)
  private val result=Reg(new FragmentInterpolationLocationResult)
  private val add=Module(new ClipFloatStreamingAddMul(2))
  io.in.ready:=phase===State.idle
  io.out.valid:=phase===State.respond;io.out.bits:=result
  io.busy:=phase=/=State.idle
  private val lowMask=MuxLookup(request.sampleCount,1.U(4.W))(Seq(2.U->3.U(4.W),4.U->15.U(4.W)))
  private val covered=request.coverage&lowMask
  private val lowest=PriorityEncoder(covered)
  private val sampleOk=request.sampleIndex<request.sampleCount && request.sampleCount=/=0.U
  private val centroidOk=covered.orR
  private val selected=Mux(request.mode===FragmentInterpolationLocationMode.sample.U,request.positions(request.sampleIndex),
    request.positions(lowest))
  add.io.in.valid:=phase===State.addX || phase===State.addY
  add.io.in.bits.a:=ClipFloat.fromFp32(Mux(phase===State.addY,request.center(1),request.center(0)))
  add.io.in.bits.b:=ClipFloat.fromFp32(Mux(phase===State.addY,request.offset(1),request.offset(0)))
  add.io.in.bits.operation:=ClipFloatOperation.add.U
  add.io.in.bits.tag:=Mux(phase===State.addY,1.U(2.W),0.U(2.W))
  add.io.out.ready:=phase===State.addXWait || phase===State.addYWait
  add.io.cancel:=false.B
  when(io.in.fire){
    request:=io.in.bits;result:=0.U.asTypeOf(result);result.tag:=io.in.bits.tag
    phase:=Mux(io.in.bits.mode===FragmentInterpolationLocationMode.offset.U,State.addX,State.select)
  }
  switch(phase){
    is(State.select){
      when(request.mode===FragmentInterpolationLocationMode.center.U){
        result.location:=request.center;result.status:=FragmentInterpolationLocationStatus.ok.U;phase:=State.respond
      }.elsewhen(request.mode===FragmentInterpolationLocationMode.sample.U){
        when(sampleOk){result.location:=selected;result.status:=FragmentInterpolationLocationStatus.ok.U}
        .otherwise{result.status:=FragmentInterpolationLocationStatus.invalid.U}
        phase:=State.respond
      }.otherwise{
        when(centroidOk){result.location:=selected;result.status:=FragmentInterpolationLocationStatus.ok.U}
        .otherwise{result.status:=FragmentInterpolationLocationStatus.invalid.U}
        phase:=State.respond
      }
    }
    is(State.addX){phase:=State.addXWait}
    is(State.addXWait){when(add.io.out.fire){
      when(add.io.out.bits.error=/=0.U){result.status:=FragmentInterpolationLocationStatus.arithmetic.U;phase:=State.respond}
      .otherwise{result.location(0):=add.io.out.bits.fp32;phase:=State.addY}
    }}
    is(State.addY){phase:=State.addYWait}
    is(State.addYWait){when(add.io.out.fire){
      when(add.io.out.bits.error=/=0.U){result.status:=FragmentInterpolationLocationStatus.arithmetic.U;phase:=State.respond}
      .otherwise{result.location(1):=add.io.out.bits.fp32;result.status:=FragmentInterpolationLocationStatus.ok.U;phase:=State.respond}
    }}
    is(State.respond){when(io.out.fire){phase:=State.idle}}
  }
}
