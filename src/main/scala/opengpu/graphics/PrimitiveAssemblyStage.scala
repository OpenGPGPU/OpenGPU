package opengpu.graphics

import chisel3._
import chisel3.util._

/** Values match the GLES draw-mode enums, but this is an internal stream ABI. */
object PrimitiveTopology {
  val points=0;val lines=1;val lineLoop=2;val lineStrip=3
  val triangles=4;val triangleStrip=5;val triangleFan=6
}
object AssembledPrimitiveKind {val point=0;val line=1;val triangle=2}
object PrimitiveAssemblyStatus {val streamFault=6}

class PrimitiveAssemblyDraw(p: VertexShaderStageConfig) extends Bundle {
  val version=UInt(8.W);val identity=new GraphicsWorkIdentity
  val topology=UInt(4.W);val count=UInt(32.W);val instances=UInt(32.W);val baseInstance=UInt(32.W)
  val varyingSlots=UInt(5.W);val flatMask=UInt((4*p.varyings).W)
}
class AssembledPrimitive(p: VertexShaderStageConfig) extends Bundle {
  val identity=new GraphicsWorkIdentity
  /** Sequence spans the complete draw; primitiveId restarts per instance only. */
  val sequence=UInt(64.W);val primitiveId=UInt(32.W)
  val instanceId=UInt(32.W);val baseInstance=UInt(32.W)
  val topology=UInt(4.W);val kind=UInt(2.W);val vertexCount=UInt(2.W)
  val provokingVertex=UInt(2.W);val loopClosing=Bool()
  val varyingSlots=UInt(5.W);val flatMask=UInt((4*p.varyings).W)
  /** Unused slots are zero. Default assembly applies flat shading; the explicit
    * pre-flat profile retains raw values for transform feedback export. */
  val vertices=Vec(3,new ShadedVertexRecord(p))
}
class PrimitiveAssemblyCompletion extends Bundle {
  val identity=new GraphicsWorkIdentity;val status=UInt(3.W)
  val source=new VertexShaderCompletion
  val vertices=UInt(64.W);val restarts=UInt(64.W)
  val primitives=UInt(64.W);val points=UInt(64.W);val lines=UInt(64.W);val triangles=UInt(64.W)
  val incompleteVertices=UInt(64.W)
  val faultOrdinal=UInt(32.W);val faultInstance=UInt(32.W)
}

/** Bounded assembly after generic vertex shading. Two retained records suffice
  * for every GLES 3.0 topology. One private primitive holds all output values;
  * no reference into a released shader batch survives input acceptance.
  *
  * Restart, implicit instance ends and explicit source completion are distinct
  * boundaries. Loops close once at restart/instance end, including two-vertex
  * loops. Strip winding advances even for degenerate triangles. Flat values
  * are replicated before subsequent clipping can remove a provoking vertex.
  *
  * Cancellation retains offered primitives, drains every source record and
  * waits for its completion. cancelSource asks the enclosing producer to stop
  * younger work; it is not an acknowledgement that the producer has drained.
  */
class PrimitiveAssemblyStage(p: VertexShaderStageConfig=VertexShaderStageConfig(),
    flatShading: Boolean=true) extends Module {
  val io=IO(new Bundle {
    val draw=Flipped(Decoupled(new PrimitiveAssemblyDraw(p)))
    val in=Flipped(Decoupled(new ShadedVertexRecord(p)))
    val end=Flipped(Decoupled(new VertexShaderCompletion))
    val out=Decoupled(new AssembledPrimitive(p))
    val completion=Decoupled(new PrimitiveAssemblyCompletion)
    val cancel=Input(Bool());val cancelSource=Output(Bool());val busy=Output(Bool())
  })
  private val idle :: collect :: finish :: complete :: Nil=Enum(4)
  private val phase=RegInit(idle)
  private val draw=Reg(new PrimitiveAssemblyDraw(p));private val source=Reg(new VertexShaderCompletion)
  private val result=Reg(new PrimitiveAssemblyCompletion)
  private val a=Reg(new ShadedVertexRecord(p));private val b=Reg(new ShadedVertexRecord(p))
  private val history=RegInit(0.U(2.W));private val segmentVertices=RegInit(0.U(32.W))
  private val parity=RegInit(false.B);private val boundary=RegInit(false.B);private val instanceEnd=RegInit(false.B)
  private val expectedOrdinal=RegInit(0.U(32.W));private val expectedInstance=RegInit(0.U(32.W))
  private val streamFinished=RegInit(false.B);private val endSeen=RegInit(false.B)
  private val status=RegInit(0.U(3.W));private val faultOrdinal=Reg(UInt(32.W));private val faultInstance=Reg(UInt(32.W))
  private val output=Reg(new AssembledPrimitive(p));private val outputValid=RegInit(false.B)
  private val sequence=RegInit(0.U(64.W));private val primitiveId=RegInit(0.U(32.W))
  private val vertices=RegInit(0.U(64.W));private val restarts=RegInit(0.U(64.W))
  private val primitives=RegInit(0.U(64.W));private val points=RegInit(0.U(64.W))
  private val lines=RegInit(0.U(64.W));private val triangles=RegInit(0.U(64.W))
  private val incomplete=RegInit(0.U(64.W))
  private val active=phase===collect
  private val record=io.in.bits
  private val sequenceValid= !streamFinished && record.identity.asUInt===draw.identity.asUInt &&
    record.ordinal===expectedOrdinal && record.instanceId===expectedInstance && record.baseInstance===draw.baseInstance
  io.end.ready:=active && !endSeen && !io.in.valid
  private val endIdentity=io.end.bits.identity.asUInt===draw.identity.asUInt
  private val endCounts=io.end.bits.vertices===vertices && io.end.bits.restarts===restarts
  private val endFault=io.end.fire && (!endIdentity || !endCounts || io.end.bits.status=/=0.U || !streamFinished)
  private val stop=status=/=0.U || io.cancel || endFault
  io.draw.ready:=phase===idle && !io.cancel;io.busy:=phase=/=idle
  io.cancelSource:=active && (status=/=0.U || io.cancel)
  io.in.ready:=active && !endSeen && (stop || (!outputValid && !boundary))
  io.out.valid:=outputValid;io.out.bits:=output
  io.completion.valid:=phase===complete;io.completion.bits:=result

  private def fail(code: UInt): Unit={
    when(status===0.U && !io.cancel){status:=code;faultOrdinal:=expectedOrdinal;faultInstance:=expectedInstance}
  }
  private def emit(kind: Int,values: Seq[ShadedVertexRecord],closing: Boolean=false): Unit={
    output:=0.U.asTypeOf(output);output.identity:=draw.identity
    output.sequence:=sequence;output.primitiveId:=primitiveId
    output.instanceId:=values.last.instanceId;output.baseInstance:=draw.baseInstance
    output.topology:=draw.topology;output.kind:=kind.U;output.vertexCount:=values.size.U
    output.provokingVertex:=(values.size-1).U;output.loopClosing:=closing.B
    output.varyingSlots:=draw.varyingSlots;output.flatMask:=draw.flatMask
    for((value,i)<-values.zipWithIndex) {
      output.vertices(i):=value
      if(flatShading)for(v<-0 until p.varyings;c<-0 until 4) {
        output.vertices(i).varyings(v)(c):=Mux(draw.flatMask(v*4+c),values.last.varyings(v)(c),value.varyings(v)(c))
      }
    }
    outputValid:=true.B;sequence:=sequence+1.U;primitiveId:=primitiveId+1.U
  }
  private def resetHistory(): Unit={history:=0.U;segmentVertices:=0.U;parity:=false.B}
  private val list=draw.topology===PrimitiveTopology.lines.U || draw.topology===PrimitiveTopology.triangles.U
  private val minimum=Mux(draw.topology===PrimitiveTopology.points.U,1.U,
    Mux(draw.topology<=PrimitiveTopology.lineStrip.U,2.U,3.U))
  private val trailing=Mux(list,history.pad(32),Mux(segmentVertices<minimum,segmentVertices,0.U))

  when(io.draw.fire) {
    draw:=io.draw.bits;status:=0.U;faultOrdinal:=0.U;faultInstance:=0.U
    vertices:=0.U;restarts:=0.U;primitives:=0.U;points:=0.U;lines:=0.U;triangles:=0.U;incomplete:=0.U
    sequence:=0.U;primitiveId:=0.U;expectedOrdinal:=0.U;expectedInstance:=0.U
    streamFinished:=io.draw.bits.count===0.U || io.draw.bits.instances===0.U
    endSeen:=false.B;boundary:=false.B;instanceEnd:=false.B;outputValid:=false.B;resetHistory();phase:=collect
    when(io.draw.bits.version=/=1.U || io.draw.bits.topology>PrimitiveTopology.triangleFan.U ||
      io.draw.bits.varyingSlots>p.varyings.U || (io.draw.bits.flatMask>>(io.draw.bits.varyingSlots<<2)).orR) {
      status:=VertexInputStatus.invalidDescriptor.U
    }
  }
  when(io.out.fire) {
    outputValid:=false.B;primitives:=primitives+1.U
    switch(output.kind) {
      is(AssembledPrimitiveKind.point.U){points:=points+1.U}
      is(AssembledPrimitiveKind.line.U){lines:=lines+1.U}
      is(AssembledPrimitiveKind.triangle.U){triangles:=triangles+1.U}
    }
  }
  when(io.in.fire) {
    when(record.restart){restarts:=restarts+1.U}.otherwise{vertices:=vertices+1.U}
    when(!stop) {
      when(!sequenceValid){fail(PrimitiveAssemblyStatus.streamFault.U)}.otherwise {
        val last=record.ordinal===draw.count-1.U
        when(last) {
          expectedOrdinal:=0.U
          when(expectedInstance===draw.instances-1.U){streamFinished:=true.B}.otherwise{expectedInstance:=expectedInstance+1.U}
        }.otherwise{expectedOrdinal:=expectedOrdinal+1.U}
        when(record.restart) {boundary:=true.B;instanceEnd:=last}.otherwise {
          segmentVertices:=segmentVertices+1.U
          switch(draw.topology) {
            is(PrimitiveTopology.points.U){emit(AssembledPrimitiveKind.point,Seq(record))}
            is(PrimitiveTopology.lines.U){
              when(history===0.U){a:=record;history:=1.U}
                .otherwise{emit(AssembledPrimitiveKind.line,Seq(a,record));history:=0.U}
            }
            is(PrimitiveTopology.lineLoop.U,PrimitiveTopology.lineStrip.U){
              when(history===0.U){a:=record;b:=record;history:=1.U}
                .otherwise{emit(AssembledPrimitiveKind.line,Seq(b,record));b:=record;history:=2.U}
            }
            is(PrimitiveTopology.triangles.U){
              when(history===0.U){a:=record;history:=1.U}
                .elsewhen(history===1.U){b:=record;history:=2.U}
                .otherwise{emit(AssembledPrimitiveKind.triangle,Seq(a,b,record));history:=0.U}
            }
            is(PrimitiveTopology.triangleStrip.U){
              when(history===0.U){a:=record;history:=1.U}
                .elsewhen(history===1.U){b:=record;history:=2.U}
                .otherwise{
                  when(parity){emit(AssembledPrimitiveKind.triangle,Seq(b,a,record))}
                    .otherwise{emit(AssembledPrimitiveKind.triangle,Seq(a,b,record))}
                  a:=b;b:=record;parity:= !parity
                }
            }
            is(PrimitiveTopology.triangleFan.U){
              when(history===0.U){a:=record;history:=1.U}
                .elsewhen(history===1.U){b:=record;history:=2.U}
                .otherwise{emit(AssembledPrimitiveKind.triangle,Seq(a,b,record));b:=record}
            }
          }
          when(last){boundary:=true.B;instanceEnd:=true.B}
        }
      }
    }
  }
  when(active && !stop && boundary && !outputValid) {
    when(draw.topology===PrimitiveTopology.lineLoop.U && history===2.U){emit(AssembledPrimitiveKind.line,Seq(b,a),true)}
    incomplete:=incomplete+trailing;resetHistory();boundary:=false.B
    when(instanceEnd){primitiveId:=0.U};instanceEnd:=false.B
  }
  when(io.end.fire) {
    endSeen:=true.B;source:=io.end.bits
    when(!endIdentity || !endCounts){fail(PrimitiveAssemblyStatus.streamFault.U)}
      .elsewhen(io.end.bits.status=/=0.U){fail(io.end.bits.status)}
      .elsewhen(!streamFinished){fail(PrimitiveAssemblyStatus.streamFault.U)}
  }
  when(active && stop){resetHistory();boundary:=false.B;instanceEnd:=false.B}
  when(active && io.cancel && status===0.U){status:=VertexInputStatus.cancelled.U;faultOrdinal:=expectedOrdinal;faultInstance:=expectedInstance}
  when(active && endSeen && !boundary && !outputValid){phase:=finish}
  when(phase===finish) {
    result.identity:=draw.identity;result.status:=Mux(io.cancel && status===0.U,VertexInputStatus.cancelled.U,status)
    result.source:=source;result.vertices:=vertices;result.restarts:=restarts
    result.primitives:=primitives;result.points:=points;result.lines:=lines;result.triangles:=triangles
    result.incompleteVertices:=incomplete;result.faultOrdinal:=faultOrdinal;result.faultInstance:=faultInstance;phase:=complete
  }
  when(io.completion.fire){phase:=idle}
}
