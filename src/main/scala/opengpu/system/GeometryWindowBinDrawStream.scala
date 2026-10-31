package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.graphics._

class GeometryWindowBinDrawResult extends Bundle {
  val identity=new GraphicsWorkIdentity;val status=UInt(3.W);val source=new PrimitiveAssemblyCompletion
  val inputPrimitives=UInt(64.W);val geometryPrimitives=UInt(64.W);val binRecords=UInt(64.W)
}

/** Serialize an assembled draw through a geometry/bin pass. Offered tasks stay
  * owned through cancellation; the enclosing owner delays pass cancellation
  * until childCancel is asserted and drains the source through inputEnd.
  */
class GeometryWindowBinDrawStream(config:GpuConfig,p:VertexShaderStageConfig,taskIdWidth:Int) extends Module {
  val io=IO(new Bundle {
    val begin=Flipped(Decoupled(new GeometryWindowBinTask(config,p,taskIdWidth)))
    val primitive=Flipped(Decoupled(new AssembledPrimitive(p)))
    val inputEnd=Flipped(Decoupled(new PrimitiveAssemblyCompletion))
    val childTask=Decoupled(new GeometryWindowBinTask(config,p,taskIdWidth))
    val childCompletion=Flipped(Decoupled(new GeometryWindowBinTaskCompletion))
    val childCancel=Output(Bool());val sourceCancel=Output(Bool())
    val completion=Decoupled(new GeometryWindowBinDrawResult);val cancel=Input(Bool());val busy=Output(Bool())
  })
  private val active=RegInit(false.B);private val done=RegInit(false.B);private val pending=RegInit(false.B)
  private val childActive=RegInit(false.B);private val endSeen=RegInit(false.B)
  private val template=Reg(new GeometryWindowBinTask(config,p,taskIdWidth));private val offer=Reg(new GeometryWindowBinTask(config,p,taskIdWidth))
  private val source=Reg(new PrimitiveAssemblyCompletion);private val result=Reg(new GeometryWindowBinDrawResult)
  private val status=RegInit(0.U(3.W));private val inputCount=RegInit(0.U(64.W))
  private val geometryCount=RegInit(0.U(64.W));private val records=RegInit(0.U(64.W));private val sequence=Reg(UInt(64.W))
  private val identity=template.geometry.geometry.primitive.input.primitive.identity
  private val stop=status=/=0.U || io.cancel || (endSeen && source.status=/=0.U)
  io.begin.ready:= !active && !done && !io.cancel;io.busy:=active || done
  io.sourceCancel:=active && stop
  io.primitive.ready:=active && !endSeen && !pending && !childActive
  io.childTask.valid:=pending;io.childTask.bits:=offer
  io.childCancel:=active && childActive && stop
  io.inputEnd.ready:=active && !endSeen
  io.childCompletion.ready:=active && childActive
  io.completion.valid:=done;io.completion.bits:=result
  when(io.begin.fire){active:=true.B;pending:=false.B;childActive:=false.B;endSeen:=false.B
    template:=io.begin.bits;status:=0.U;inputCount:=0.U;geometryCount:=0.U;records:=0.U
    sequence:=io.begin.bits.geometry.geometry.primitive.firstSequence}
  when(io.primitive.fire){
    when(inputCount.andR){status:=1.U}.otherwise{inputCount:=inputCount+1.U}
    when(!stop && !inputCount.andR){when(io.primitive.bits.identity.asUInt=/=identity.asUInt){status:=1.U}
      .otherwise{offer:=template;offer.geometry.geometry.primitive.input.primitive:=io.primitive.bits
        offer.geometry.geometry.primitive.firstSequence:=sequence;pending:=true.B}}
  }
  when(io.childTask.fire){pending:=false.B;childActive:=true.B}
  when(io.inputEnd.fire){source:=io.inputEnd.bits;endSeen:=true.B}
  when(active && io.cancel && status===0.U){status:=4.U}
  when(io.childCompletion.fire){childActive:=false.B
    val g=io.childCompletion.bits.source.source.geometry
    val total=geometryCount+&g.producedPrimitives;val bins=records+&io.childCompletion.bits.admitted
    when(total(64) || bins(64)){status:=1.U}.otherwise{geometryCount:=total(63,0);records:=bins(63,0)}
    when(io.childCompletion.bits.status=/=0.U && status===0.U && !io.cancel &&
      !(endSeen && source.status=/=0.U) && !(io.inputEnd.fire && io.inputEnd.bits.status=/=0.U)){
      status:=io.childCompletion.bits.status}
    when(io.childCompletion.bits.status===0.U){sequence:=g.nextSequence}
  }
  when(active && endSeen && !pending && !childActive){
    result.identity:=identity;result.source:=source;result.inputPrimitives:=inputCount
    result.geometryPrimitives:=geometryCount;result.binRecords:=records
    result.status:=Mux(io.cancel,4.U,Mux(status=/=0.U,status,Mux(source.status=/=0.U,source.status,
      Mux(source.identity.asUInt=/=identity.asUInt || source.primitives=/=inputCount,1.U,0.U))))
    active:=false.B;done:=true.B
  }
  when(io.completion.fire){done:=false.B}
}
