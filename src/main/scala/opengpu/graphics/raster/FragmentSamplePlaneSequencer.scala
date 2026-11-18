package opengpu.graphics

import chisel3._
import chisel3.util._

class FragmentSamplePlaneRequest(g:GraphicsConfig) extends Bundle {
  val quad=new RasterQuad(g);val sampleMode=UInt(2.W);val enable=Bool()
}
class FragmentSamplePlane(g:GraphicsConfig) extends Bundle {
  val quad=new RasterQuad(g);val perSample=Bool();val sampleId=UInt(2.W)
}
/** One raw raster quad owns all its sample planes before interpolation. Keep
  * four spatial pixels together; absent samples become helper lanes. Offered
  * planes remain immutable through cancellation until their real handshake.
  */
class FragmentSamplePlaneSequencer(g:GraphicsConfig) extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new FragmentSamplePlaneRequest(g)))
    val out=Decoupled(new FragmentSamplePlane(g))
    val cancel=Input(Bool());val busy=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,choose,offer=Value}
  private val phase=RegInit(State.idle);private val request=Reg(new FragmentSamplePlaneRequest(g))
  private val output=Reg(new FragmentSamplePlane(g));private val sample=RegInit(0.U(3.W))
  private val aborted=RegInit(false.B);private val stop=aborted || io.cancel
  private val coverage=VecInit(request.quad.lanes.map(_.coverageMask)).reduce(_ | _)
  io.in.ready:=phase===State.idle && !io.cancel;io.busy:=phase=/=State.idle
  io.out.valid:=phase===State.offer;io.out.bits:=output
  when(io.in.fire){request:=io.in.bits;sample:=0.U;aborted:=false.B;phase:=State.choose
    assert(io.in.bits.sampleMode<=log2Ceil(g.maxSampleCount).U,"sample plane has invalid mode")}
  when(phase=/=State.idle && io.cancel){aborted:=true.B}
  switch(phase){
    is(State.choose){
      when(stop || (request.enable && sample>=(1.U(3.W)<<request.sampleMode))){phase:=State.idle}
        .elsewhen(request.enable && !(coverage>>sample)(0)){sample:=sample+1.U}
        .otherwise{
          output.quad:=request.quad;output.perSample:=request.enable;output.sampleId:=Mux(request.enable,sample,0.U)
          for(i<-0 until 4){
            val mask=request.quad.lanes(i).coverageMask & (1.U(g.maxSampleCount.W)<<sample)
            when(request.enable){output.quad.lanes(i).coverageMask:=mask;output.quad.lanes(i).covered:=mask.orR}
          }
          phase:=State.offer
        }
    }
    is(State.offer){when(io.out.fire){
      when(stop || !request.enable){phase:=State.idle}.otherwise{sample:=sample+1.U;phase:=State.choose}
    }}
  }
}
