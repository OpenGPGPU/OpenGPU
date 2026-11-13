package opengpu.graphics

import chisel3._
import chisel3.util._

/** Pre-flat GS inputs; every adjacent vertex retains the original VS bits. */
class GeometryInputPrimitive(p: VertexShaderStageConfig) extends Bundle {
  val identity=new GraphicsWorkIdentity
  val sequence=UInt(64.W);val primitiveId=UInt(32.W)
  val instanceId=UInt(32.W);val baseInstance=UInt(32.W)
  val topology=UInt(4.W);val kind=UInt(2.W);val vertexCount=UInt(3.W)
  val varyingSlots=UInt(5.W)
  val vertices=Vec(6,new ShadedVertexRecord(p))
}

/** GLES 3.2 section 10.1.8--11, triangle-strip ordering table 10.1.
  * This independent opt-in component does not widen ordinary assembly ports.
  * Triangle strips retain at most ten vertices: a future complete triangle,
  * not one extra vertex, distinguishes a middle edge from the last edge.
  * Restart/instance boundaries flush the last triangle before discarding the
  * optional odd trailing vertex. Held output and source completion must drain
  * before a cancelled or faulted draw can release its owner.
  */
class GeometryAdjacencyAssemblyStage(p: VertexShaderStageConfig=VertexShaderStageConfig()) extends Module {
  val io=IO(new Bundle {
    val draw=Flipped(Decoupled(new PrimitiveAssemblyDraw(p)))
    val in=Flipped(Decoupled(new ShadedVertexRecord(p)))
    val end=Flipped(Decoupled(new VertexShaderCompletion))
    val out=Decoupled(new GeometryInputPrimitive(p))
    val completion=Decoupled(new PrimitiveAssemblyCompletion)
    val cancel=Input(Bool());val cancelSource=Output(Bool());val busy=Output(Bool())
  })
  private val idle :: collect :: finish :: complete :: Nil=Enum(4)
  private val phase=RegInit(idle)
  private val draw=Reg(new PrimitiveAssemblyDraw(p));private val source=Reg(new VertexShaderCompletion)
  private val result=Reg(new PrimitiveAssemblyCompletion)
  private val history=Reg(Vec(10,new ShadedVertexRecord(p)))
  private val retained=RegInit(0.U(4.W));private val segmentVertices=RegInit(0.U(32.W))
  private val stripTriangle=RegInit(0.U(32.W))
  private val boundary=RegInit(false.B);private val instanceEnd=RegInit(false.B)
  private val expectedOrdinal=RegInit(0.U(32.W));private val expectedInstance=RegInit(0.U(32.W))
  private val streamFinished=RegInit(false.B);private val endSeen=RegInit(false.B)
  private val status=RegInit(0.U(3.W));private val faultOrdinal=Reg(UInt(32.W));private val faultInstance=Reg(UInt(32.W))
  private val output=Reg(new GeometryInputPrimitive(p));private val outputValid=RegInit(false.B)
  private val sequence=RegInit(0.U(64.W));private val primitiveId=RegInit(0.U(32.W))
  private val vertices=RegInit(0.U(64.W));private val restarts=RegInit(0.U(64.W))
  private val primitives=RegInit(0.U(64.W));private val lines=RegInit(0.U(64.W));private val triangles=RegInit(0.U(64.W))
  private val incomplete=RegInit(0.U(64.W))
  private val active=phase===collect;private val record=io.in.bits
  private val lineList=draw.topology===10.U;private val lineStrip=draw.topology===11.U
  private val triangleList=draw.topology===12.U;private val triangleStrip=draw.topology===13.U
  private val emitReady=((lineList || lineStrip) && retained>=4.U) || (triangleList && retained>=6.U) ||
    (triangleStrip && Mux(stripTriangle===0.U,retained>=8.U,retained>=10.U))
  io.end.ready:=active && !endSeen && !io.in.valid
  private val endIdentity=io.end.bits.identity.asUInt===draw.identity.asUInt
  private val endCounts=io.end.bits.vertices===vertices && io.end.bits.restarts===restarts
  private val endFault=io.end.fire && (!endIdentity || !endCounts || io.end.bits.status=/=0.U || !streamFinished)
  private val stop=status=/=0.U || io.cancel || endFault
  io.draw.ready:=phase===idle && !io.cancel;io.busy:=phase=/=idle
  io.cancelSource:=active && (status=/=0.U || io.cancel)
  io.in.ready:=active && !endSeen && (stop || (!outputValid && !boundary && !emitReady && retained<10.U))
  io.out.valid:=outputValid;io.out.bits:=output
  io.completion.valid:=phase===complete;io.completion.bits:=result
  private def fail(code: UInt): Unit=when(status===0.U && !io.cancel){
    status:=code;faultOrdinal:=expectedOrdinal;faultInstance:=expectedInstance
  }
  private def resetHistory(): Unit={retained:=0.U;segmentVertices:=0.U;stripTriangle:=0.U}
  private def emit(kind: Int,indices: Seq[Int]): Unit={
    output:=0.U.asTypeOf(output);output.identity:=draw.identity
    output.sequence:=sequence;output.primitiveId:=primitiveId
    output.instanceId:=history(indices.head).instanceId;output.baseInstance:=draw.baseInstance
    output.topology:=draw.topology;output.kind:=kind.U;output.vertexCount:=indices.size.U
    output.varyingSlots:=draw.varyingSlots
    for((index,i)<-indices.zipWithIndex)output.vertices(i):=history(index)
    outputValid:=true.B;sequence:=sequence+1.U;primitiveId:=primitiveId+1.U
  }
  private def shiftTwo(): Unit={for(i<-0 until 8)history(i):=history(i+2);retained:=retained-2.U}
  when(io.draw.fire){
    draw:=io.draw.bits;status:=0.U;faultOrdinal:=0.U;faultInstance:=0.U
    vertices:=0.U;restarts:=0.U;primitives:=0.U;lines:=0.U;triangles:=0.U;incomplete:=0.U
    sequence:=0.U;primitiveId:=0.U;expectedOrdinal:=0.U;expectedInstance:=0.U
    streamFinished:=io.draw.bits.count===0.U || io.draw.bits.instances===0.U
    endSeen:=false.B;boundary:=false.B;instanceEnd:=false.B;outputValid:=false.B;resetHistory();phase:=collect
    when(io.draw.bits.version=/=1.U || io.draw.bits.topology<10.U || io.draw.bits.topology>13.U ||
      io.draw.bits.varyingSlots>p.varyings.U || (io.draw.bits.flatMask>>(io.draw.bits.varyingSlots<<2)).orR){
      status:=VertexInputStatus.invalidDescriptor.U
    }
  }
  when(io.out.fire){outputValid:=false.B;primitives:=primitives+1.U
    when(output.kind===AssembledPrimitiveKind.line.U){lines:=lines+1.U}.otherwise{triangles:=triangles+1.U}}
  when(io.in.fire){
    when(record.restart){restarts:=restarts+1.U}.otherwise{vertices:=vertices+1.U}
    when(!stop){
      val ordered= !streamFinished && record.identity.asUInt===draw.identity.asUInt && record.ordinal===expectedOrdinal &&
        record.instanceId===expectedInstance && record.baseInstance===draw.baseInstance
      when(!ordered){fail(PrimitiveAssemblyStatus.streamFault.U)}.otherwise{
        val last=record.ordinal===draw.count-1.U
        when(last){expectedOrdinal:=0.U
          when(expectedInstance===draw.instances-1.U){streamFinished:=true.B}.otherwise{expectedInstance:=expectedInstance+1.U}
        }.otherwise{expectedOrdinal:=expectedOrdinal+1.U}
        when(record.restart){boundary:=true.B;instanceEnd:=last}.otherwise{
          assert(retained<10.U);history(retained):=record;retained:=retained+1.U;segmentVertices:=segmentVertices+1.U
          when(last){boundary:=true.B;instanceEnd:=true.B}
        }
      }
    }
  }
  when(active && !stop && !outputValid){
    when((lineList || lineStrip) && retained>=4.U){
      emit(AssembledPrimitiveKind.line,Seq(0,1,2,3))
      when(lineList){retained:=0.U}.otherwise{for(i<-0 until 3)history(i):=history(i+1);retained:=retained-1.U}
    }.elsewhen(triangleList && retained>=6.U){emit(AssembledPrimitiveKind.triangle,Seq(0,1,2,3,4,5));retained:=0.U}
      .elsewhen(triangleStrip && stripTriangle===0.U && retained>=8.U){
        emit(AssembledPrimitiveKind.triangle,Seq(0,1,2,6,4,3));stripTriangle:=1.U
      }.elsewhen(triangleStrip && stripTriangle=/=0.U && retained>=10.U){
        when(stripTriangle(0)){emit(AssembledPrimitiveKind.triangle,Seq(4,0,2,5,6,8))}
          .otherwise{emit(AssembledPrimitiveKind.triangle,Seq(2,0,4,8,6,5))}
        shiftTwo();stripTriangle:=stripTriangle+1.U
      }.elsewhen(boundary && triangleStrip && stripTriangle===0.U && retained>=6.U){
        emit(AssembledPrimitiveKind.triangle,Seq(0,1,2,5,4,3));retained:=0.U
      }.elsewhen(boundary && triangleStrip && stripTriangle=/=0.U && retained>=8.U){
        when(stripTriangle(0)){emit(AssembledPrimitiveKind.triangle,Seq(4,0,2,5,6,7))}
          .otherwise{emit(AssembledPrimitiveKind.triangle,Seq(2,0,4,7,6,5))}
        retained:=0.U
      }.elsewhen(boundary){
        val trailing=Mux(lineList || triangleList,retained.pad(32),
          Mux(segmentVertices<Mux(lineStrip,4.U,6.U),segmentVertices,Mux(triangleStrip,segmentVertices(0).asUInt,0.U)))
        incomplete:=incomplete+trailing;resetHistory();boundary:=false.B
        when(instanceEnd){primitiveId:=0.U};instanceEnd:=false.B
      }
  }
  when(io.end.fire){endSeen:=true.B;source:=io.end.bits
    when(!endIdentity || !endCounts){fail(PrimitiveAssemblyStatus.streamFault.U)}
      .elsewhen(io.end.bits.status=/=0.U){fail(io.end.bits.status)}
      .elsewhen(!streamFinished){fail(PrimitiveAssemblyStatus.streamFault.U)}}
  when(active && stop){resetHistory();boundary:=false.B;instanceEnd:=false.B}
  when(active && io.cancel && status===0.U){status:=VertexInputStatus.cancelled.U;faultOrdinal:=expectedOrdinal;faultInstance:=expectedInstance}
  when(active && endSeen && !boundary && !outputValid){phase:=finish}
  when(phase===finish){
    result.identity:=draw.identity;result.status:=Mux(io.cancel && status===0.U,VertexInputStatus.cancelled.U,status)
    result.source:=source;result.vertices:=vertices;result.restarts:=restarts;result.indirectLines:=0.U
    result.primitives:=primitives;result.points:=0.U;result.lines:=lines;result.triangles:=triangles
    result.incompleteVertices:=incomplete;result.faultOrdinal:=faultOrdinal;result.faultInstance:=faultInstance;phase:=complete
  }
  when(io.completion.fire){phase:=idle}
}
