package opengpu.graphics

import chisel3._
import chisel3.util._

class NativeGeometryPrimitiveRequest(p: VertexShaderStageConfig) extends Bundle {
  val primitive=new GeometryInputPrimitive(p)
  val program=new NativeGeometryProgram
  val taskId=UInt(32.W)
}
class NativeGeometryInputTask extends Bundle {
  /** Retain code, stage-3 uniforms and output link through all invocations. */
  val program=new NativeGeometryProgram
  val invocation=new GeometryInvocationTask
}

/** Capture and map the complete linked six-vertex primitive. No reference into
  * a released VS batch survives acceptance. This serial mapper does not launch
  * shaders; the consumer must drain a held task or fault before owner release.
  */
class NativeGeometryInputMapper(p: VertexShaderStageConfig=VertexShaderStageConfig()) extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new NativeGeometryPrimitiveRequest(p)))
    val task=Decoupled(new NativeGeometryInputTask)
    val fault=Decoupled(new GeometryPrimitiveInputFault)
  })
  private val idle :: copy :: offer :: fault :: Nil=Enum(4)
  private val phase=RegInit(idle)
  private val request=Reg(new NativeGeometryPrimitiveRequest(p))
  private val result=Reg(new NativeGeometryInputTask)
  private val vertex=RegInit(0.U(3.W));private val component=RegInit(0.U(6.W))
  private val primitive=io.in.bits.primitive;private val program=io.in.bits.program
  private val countLegal=NativeGeometryProgramLayout.topologyMatches(program,primitive.topology) &&
    primitive.vertexCount===program.inputVertices && primitive.vertexCount<=6.U &&
    primitive.kind===Mux(program.inputVertices===1.U,0.U,Mux(program.inputVertices===2.U || program.inputVertices===4.U,1.U,2.U))
  private val identityLegal=primitive.identity.asUInt===program.identity.asUInt &&
    primitive.varyingSlots===program.vertexVaryingSlots && primitive.varyingSlots<=p.varyings.U
  private val recordsLegal=primitive.vertices.zipWithIndex.map{case(v,i)=>i.U>=primitive.vertexCount ||
    (v.identity.asUInt===primitive.identity.asUInt && v.instanceId===primitive.instanceId &&
      v.baseInstance===primitive.baseInstance && !v.restart)}.reduce(_ && _)
  private val fields=Wire(Vec(4+4*p.varyings,UInt(32.W)))
  for(i<-0 until 4)fields(i):=request.primitive.vertices(vertex).position(i)
  for(i<-0 until 4*p.varyings)fields(4+i):=request.primitive.vertices(vertex).varyings(i/4)(i%4)
  private val source=request.program.inputSources(component)
  io.in.ready:=phase===idle
  io.task.valid:=phase===offer;io.task.bits:=result
  io.fault.valid:=phase===fault;io.fault.bits.taskId:=request.taskId
  io.fault.bits.primitiveId:=request.primitive.primitiveId
  when(io.in.fire){
    request:=io.in.bits;result:=0.U.asTypeOf(result);result.program:=program
    val task=result.invocation
    task.taskId:=io.in.bits.taskId;task.primitiveId:=primitive.primitiveId
    task.inputVertices:=program.inputVertices;task.inputComponents:=program.inputComponents
    task.invocations:=program.invocations;task.topology:=program.outputTopology
    task.maxVertices:=program.maxVertices;task.outputComponents:=program.outputComponents
    task.builtinOutputMask:=program.builtinOutputMask
    vertex:=0.U;component:=0.U
    phase:=Mux(!NativeGeometryProgramLayout.legal(program,p) || !countLegal || !identityLegal || !recordsLegal,
      fault,Mux(program.inputComponents===0.U,offer,copy))
  }
  when(phase===copy){
    // All mappings were checked before any task became visible. Keep this
    // bound defensive as the captured descriptor is the only copy source.
    when(source>=fields.length.U || source>=4.U+(request.primitive.varyingSlots<<2)){phase:=fault}
      .otherwise{
        result.invocation.vertices(vertex).components(component):=fields(source)
        when(component+&1.U===request.program.inputComponents){
          component:=0.U
          when(vertex+&1.U===request.primitive.vertexCount){phase:=offer}.otherwise{vertex:=vertex+1.U}
        }.otherwise{component:=component+1.U}
      }
  }
  when(io.task.fire || io.fault.fire){phase:=idle}
}
