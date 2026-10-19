package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.core.memory.L2SramArray

class ClippedPrimitive(p: VertexShaderStageConfig) extends Bundle {
  val primitive=new AssembledPrimitive(p)
  val partIndex=UInt(3.W);val partCount=UInt(4.W)
  val generated=UInt(3.W);val clipped=Bool()
  val sourceProvokingOrdinal=UInt(32.W);val sourceProvokingVertexId=UInt(32.W)
}
class PrimitiveClipCompletion extends Bundle {
  val identity=new GraphicsWorkIdentity
  val sequence=UInt(64.W);val primitiveId=UInt(32.W);val instanceId=UInt(32.W)
  /** 0=success, 1=invalid descriptor, 4=cancelled, 7=numeric/capacity failure. */
  val status=UInt(3.W);val outputs=UInt(4.W);val intersections=UInt(8.W)
  val maxVertices=UInt(4.W);val arithmeticOperations=UInt(16.W)
  val arithmeticStatus=UInt(5.W);val packingStatus=UInt(5.W)
  val planes=UInt(6.W);val culled=Bool();val zeroW=Bool()
}
private class StoredClipVertex(p: VertexShaderStageConfig) extends Bundle {
  val vertex=new ShadedVertexRecord(p);val generated=Bool()
}
object PrimitiveClipStage {
  val MaxVertices=9 // Triangle plus at most one vertex per canonical plane.
  private[graphics] def equal(a: UInt,b: UInt): Bool=a===b || (a(30,0)===0.U && b(30,0)===0.U)
  private[graphics] def less(a: UInt,b: UInt): Bool= !equal(a,b) &&
    Mux(a(31)===b(31),Mux(a(31),a(30,0)>b(30,0),a(30,0)<b(30,0)),a(31))
  private[graphics] def inside(v: ShadedVertexRecord,plane: UInt): Bool={
    val coordinate=v.position((plane>>1).pad(2));val w=v.position(3)
    Mux(plane(0),!less(w,coordinate),!less(coordinate,w^"h80000000".U))
  }
  private[graphics] def boundary(v: ShadedVertexRecord,plane: UInt): Bool=
    equal(v.position((plane>>1).pad(2)),v.position(3)^Mux(plane(0),0.U,"h80000000".U))
}

/** One owned primitive, near/far point/line clipping and six-plane triangle
  * clipping. Two nine-vertex SRAM banks bound polygon growth; triangles export
  * an ordered fan of at most seven parts. Flat words never enter arithmetic.
  * Strict crossings always interpolate inside-to-outside, independent of edge
  * traversal. Boundary endpoints are copied without arithmetic or duplicate
  * polygon intersections. No artificial positive-w epsilon is introduced.
  *
  * Nonfinite positions or an internal arithmetic/capacity failure return
  * status 7 without publishing incomplete geometry. Smooth varying special
  * values follow the scalar math units; flat words retain their bits.
  */
class PrimitiveClipStage(p: VertexShaderStageConfig=VertexShaderStageConfig(),useSramBlackBoxes: Boolean=false) extends Module {
  import PrimitiveClipStage._
  private val storedBits=(new StoredClipVertex(p)).getWidth
  private val chunks=(storedBits+63)/64
  private val bankRows=MaxVertices*chunks
  private val rows=1<<log2Ceil(bankRows*2)
  private val segments=math.max(1,rows/256)
  private val segmentRows=math.min(256,rows)
  private val addressWidth=log2Ceil(rows)
  private val chunkWidth=math.max(1,log2Ceil(chunks))
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new AssembledPrimitive(p)))
    val out=Decoupled(new ClippedPrimitive(p))
    val completion=Decoupled(new PrimitiveClipCompletion)
    val cancel=Input(Bool());val busy=Output(Bool())
  })
  private object State extends ChiselEnum {
    val idle,validate,initialize,initialized,planeStart,loadPrevious,loadCurrent,classify,edgeAdvance,planeAdvance,
      distanceInside,haveInside,distanceOutside,haveOutside,denominator,haveDenominator,weightInside,haveWeightInside,
      weightOutside,haveWeightOutside,interpolateField,productOutside,combine,storeField,intersectionReady,
      appendVertex,appended,exportStart,exportLoad,exportLoaded,emit,readIssue,readReply,writeChunk,waitMath,
      abort,finish,complete=Value
  }
  private val phase=RegInit(State.idle)
  private val output=Reg(new ClippedPrimitive(p));private val result=Reg(new PrimitiveClipCompletion)
  private val flat=Reg(Vec(p.varyings,Vec(4,UInt(32.W))))
  private val previous=Reg(new StoredClipVertex(p));private val current=Reg(new StoredClipVertex(p))
  private val intersection=Reg(new StoredClipVertex(p));private val buffer=Reg(new StoredClipVertex(p))
  private val bank=RegInit(false.B);private val count=RegInit(0.U(4.W));private val written=RegInit(0.U(4.W))
  private val edge=RegInit(0.U(4.W));private val initial=RegInit(0.U(2.W));private val plane=RegInit(0.U(3.W))
  private val entering=RegInit(false.B);private val copied=RegInit(false.B)
  private val field=RegInit(0.U(7.W));private val exportSlot=RegInit(0.U(2.W))
  private val appendSelect=Reg(UInt(2.W));private val secondSelect=Reg(UInt(2.W));private val second=RegInit(false.B)
  private val distanceA=Reg(new ClipFloat);private val distanceB=Reg(new ClipFloat);private val divisor=Reg(new ClipFloat)
  private val weightA=Reg(new ClipFloat);private val weightB=Reg(new ClipFloat);private val productA=Reg(new ClipFloat)
  private val status=RegInit(0.U(3.W));private val outputs=RegInit(0.U(4.W));private val intersections=RegInit(0.U(8.W))
  private val maxVertices=RegInit(0.U(4.W));private val operations=RegInit(0.U(16.W))
  private val arithmeticFlags=RegInit(0.U(5.W));private val packingFlags=RegInit(0.U(5.W))
  private val affected=Reg(UInt(6.W));private val culled=RegInit(false.B);private val zeroW=RegInit(false.B)
  private val active=phase=/=State.idle && phase=/=State.complete
  private val stop=status=/=0.U || io.cancel
  private val triangle=output.primitive.kind===AssembledPrimitiveKind.triangle.U
  private val line=output.primitive.kind===AssembledPrimitiveKind.line.U
  private val metadata=output.primitive
  private val arithmetic=Module(new ClipFloatArithmetic())
  private val mathOffered=RegInit(false.B);private val mathPending=RegInit(false.B)
  private val mathRequest=Reg(new ClipFloatRequest(8));private val mathReturn=Reg(State())
  private val mathValue=Reg(new ClipFloat);private val mathPacked=Reg(UInt(32.W))
  arithmetic.io.in.valid:=mathOffered;arithmetic.io.in.bits:=mathRequest
  arithmetic.io.out.ready:=mathPending;arithmetic.io.cancel:=stop && mathPending
  when(arithmetic.io.in.fire){mathOffered:=false.B;mathPending:=true.B;operations:=operations+1.U}
  when(arithmetic.io.out.fire) {
    assert(arithmetic.io.out.bits.tag===mathRequest.tag)
    mathPending:=false.B;mathValue:=arithmetic.io.out.bits.value;mathPacked:=arithmetic.io.out.bits.fp32
    arithmeticFlags:=arithmeticFlags|arithmetic.io.out.bits.arithmeticStatus
    packingFlags:=packingFlags|arithmetic.io.out.bits.packingStatus
    when(arithmetic.io.out.bits.error=/=0.U && status===0.U && !io.cancel){status:=7.U}
  }
  private def calculate(a: ClipFloat,b: ClipFloat,operation: UInt,next: State.Type): Unit={
    mathRequest.a:=a;mathRequest.b:=b;mathRequest.operation:=operation;mathRequest.tag:=operations(7,0)
    mathReturn:=next;mathOffered:=true.B;phase:=State.waitMath
  }
  private def calculate(a: ClipFloat,b: ClipFloat,operation: Int,next: State.Type): Unit=calculate(a,b,operation.U,next)
  private val memories=Seq.fill(segments)(Module(new L2SramArray(segmentRows,64,useSramBlackBoxes)))
  private val memoryBase=Reg(UInt(addressWidth.W));private val chunk=RegInit(0.U(chunkWidth.W))
  private val memoryBits=Reg(UInt((chunks*64).W));private val memoryReturn=Reg(State())
  private val address=memoryBase+chunk
  private val selected=if(segments==1)0.U else address(addressWidth-1,8)
  private val readSegment=RegInit(0.U(math.max(1,log2Ceil(segments)).W))
  for((memory,i)<-memories.zipWithIndex) {
    memory.io.readEnable:=phase===State.readIssue && !stop && selected===i.U
    memory.io.readAddress:=address(log2Ceil(segmentRows)-1,0)
    memory.io.writeEnable:=phase===State.writeChunk && !stop && selected===i.U
    memory.io.writeAddress:=address(log2Ceil(segmentRows)-1,0)
    memory.io.writeData:=(memoryBits>>(chunk<<6))(63,0)
  }
  private val readWord=if(segments==1)memories.head.io.readData else VecInit(memories.map(_.io.readData))(readSegment)
  private def load(index: UInt,which: Bool,next: State.Type): Unit={
    memoryBase:=index*chunks.U+Mux(which,bankRows.U,0.U);chunk:=0.U;memoryBits:=0.U;memoryReturn:=next;phase:=State.readIssue
  }
  private def store(index: UInt,which: Bool,value: StoredClipVertex,next: State.Type): Unit={
    memoryBase:=index*chunks.U+Mux(which,bankRows.U,0.U);chunk:=0.U;memoryBits:=value.asUInt.pad(chunks*64);memoryReturn:=next;phase:=State.writeChunk
  }
  private def fail(): Unit={when(status===0.U && !io.cancel){status:=7.U};phase:=State.abort}
  private def enqueue(first: Int,last: Option[Int]=None): Unit={
    appendSelect:=first.U;second:=last.nonEmpty.B;secondSelect:=last.getOrElse(0).U;phase:=State.appendVertex
  }
  private val insideEndpoint=Mux(entering,current,previous)
  private val outsideEndpoint=Mux(entering,previous,current)
  private def fieldWord(v: StoredClipVertex): UInt={
    val out=WireDefault(0.U(32.W))
    for(c<-0 until 4)when(field===c.U){out:=v.vertex.position(c)}
    for(varying<-0 until p.varyings;c<-0 until 4)when(field===(4+varying*4+c).U){out:=v.vertex.varyings(varying)(c)}
    out
  }
  private val insideWord=fieldWord(insideEndpoint);private val outsideWord=fieldWord(outsideEndpoint)
  private val insideW=ClipFloat.fromFp32(insideEndpoint.vertex.position(3))
  private val insideCoordinate=ClipFloat.fromFp32(insideEndpoint.vertex.position(plane>>1))
  private val outsideW=ClipFloat.fromFp32(outsideEndpoint.vertex.position(3))
  private val outsideCoordinate=ClipFloat.fromFp32(outsideEndpoint.vertex.position(plane>>1))
  private val insideNumber=ClipFloat.fromFp32(insideWord);private val outsideNumber=ClipFloat.fromFp32(outsideWord)
  private val initialValue=Wire(new StoredClipVertex(p))
  initialValue.generated:=false.B;initialValue.vertex:=metadata.vertices(initial)
  for(v<-0 until p.varyings;c<-0 until 4)initialValue.vertex.varyings(v)(c):=Mux(v.U>=metadata.varyingSlots,0.U,Mux(metadata.flatMask(v*4+c),flat(v)(c),metadata.vertices(initial).varyings(v)(c)))
  private val flatWord=WireDefault(0.U(32.W));private val fieldFlat=WireDefault(false.B)
  for(v<-0 until p.varyings;c<-0 until 4)when(field===(4+v*4+c).U){flatWord:=flat(v)(c);fieldFlat:=metadata.flatMask(v*4+c)}
  private def saveField(word: UInt): Unit={
    for(c<-0 until 4)when(field===c.U){intersection.vertex.position(c):=word}
    for(v<-0 until p.varyings;c<-0 until 4)when(field===(4+v*4+c).U){intersection.vertex.varyings(v)(c):=word}
    when(field===3.U+(metadata.varyingSlots<<2)){phase:=State.intersectionReady}
      .otherwise{field:=field+1.U;phase:=State.interpolateField}
  }
  private val snapped=WireDefault(intersection)
  private val paired=Mux(line,plane===5.U,plane>=1.U)
  private val snappedW=Mux(paired && intersection.vertex.position(3)(31) && intersection.vertex.position(3)(30,0).orR,0.U,intersection.vertex.position(3))
  snapped.vertex.position(3):=snappedW
  for(axis<-0 until 3) {
    val coordinate=intersection.vertex.position(axis)
    val low=snappedW^"h80000000".U
    val boundedLow=Mux((triangle || (axis==2).B) && plane>=(2*axis).U && less(coordinate,low),low,coordinate)
    val bounded=Mux((triangle || (axis==2).B) && plane>=(2*axis+1).U && less(snappedW,boundedLow),snappedW,boundedLow)
    snapped.vertex.position(axis):=Mux((plane>>1)===axis.U,Mux(plane(0),snappedW,low),bounded)
  }
  io.in.ready:=phase===State.idle && !io.cancel;io.busy:=phase=/=State.idle
  io.out.valid:=phase===State.emit;io.out.bits:=output
  io.completion.valid:=phase===State.complete;io.completion.bits:=result

  when(!stop || phase===State.idle || phase===State.emit || phase===State.abort || phase===State.finish || phase===State.complete) {
    switch(phase) {
      is(State.idle){when(io.in.fire) {
        output:=0.U.asTypeOf(output);output.primitive:=io.in.bits
        output.sourceProvokingOrdinal:=io.in.bits.vertices(io.in.bits.provokingVertex).ordinal
        output.sourceProvokingVertexId:=io.in.bits.vertices(io.in.bits.provokingVertex).vertexId
        flat:=io.in.bits.vertices(io.in.bits.provokingVertex).varyings
        status:=0.U;outputs:=0.U;intersections:=0.U;operations:=0.U;arithmeticFlags:=0.U;packingFlags:=0.U
        affected:=0.U;culled:=false.B;zeroW:=false.B;maxVertices:=io.in.bits.vertexCount;phase:=State.validate
      }}
      is(State.validate){
        val kindValid=metadata.kind<=2.U && metadata.vertexCount===metadata.kind+1.U && metadata.provokingVertex<metadata.vertexCount
        val modeValid=MuxLookup(metadata.kind,false.B)(Seq(0.U->(metadata.topology===0.U),1.U->(metadata.topology>=1.U && metadata.topology<=3.U),2.U->(metadata.topology>=4.U && metadata.topology<=6.U)))
        val recordsValid=(0 until 3).map { i=>i.U>=metadata.vertexCount ||
          (metadata.vertices(i).identity.asUInt===metadata.identity.asUInt && metadata.vertices(i).instanceId===metadata.instanceId &&
            metadata.vertices(i).baseInstance===metadata.baseInstance && !metadata.vertices(i).restart) }.reduce(_ && _)
        val finite=(0 until 3).map(i=>i.U>=metadata.vertexCount || metadata.vertices(i).position.map(_(30,23)=/=255.U).reduce(_ && _)).reduce(_ && _)
        val planes=VecInit((0 until 6).map { q=>
          val used=triangle || (q>=4).B
          used && (0 until 3).map(i=>i.U<metadata.vertexCount && !inside(metadata.vertices(i),q.U)).reduce(_ || _)
        }).asUInt
        val rejected=(0 until 6).map(q=>(triangle || (q>=4).B) &&
          (0 until 3).map(i=>i.U>=metadata.vertexCount || !inside(metadata.vertices(i),q.U)).reduce(_ && _)).reduce(_ || _)
        when(!kindValid || !modeValid || !recordsValid || metadata.varyingSlots>p.varyings.U || (metadata.flatMask>>(metadata.varyingSlots<<2)).orR) {
          status:=1.U;phase:=State.abort
        }.elsewhen(!finite){status:=7.U;phase:=State.abort}
          .otherwise {
            affected:=planes;output.clipped:=planes.orR
            when(rejected || (metadata.kind===0.U && planes.orR)){culled:=true.B;phase:=State.finish}
              .elsewhen(!planes.orR) {
                for(i<-0 until 3) {
                  when(i.U>=metadata.vertexCount){output.primitive.vertices(i):=0.U.asTypeOf(output.primitive.vertices(i))}
                  for(v<-0 until p.varyings;c<-0 until 4)when(i.U<metadata.vertexCount) {
                    output.primitive.vertices(i).varyings(v)(c):=Mux(v.U>=metadata.varyingSlots,0.U,Mux(metadata.flatMask(v*4+c),flat(v)(c),metadata.vertices(i).varyings(v)(c)))
                  }
                }
                zeroW:=(0 until 3).map(i=>i.U<metadata.vertexCount && metadata.vertices(i).position(3)(30,0)===0.U).reduce(_ || _)
                output.partCount:=1.U;phase:=State.emit
              }.otherwise{bank:=false.B;count:=metadata.vertexCount;initial:=0.U;plane:=Mux(triangle,0.U,4.U);phase:=State.initialize}
          }
      }
      is(State.initialize){
        store(initial,false.B,initialValue,State.initialized)
      }
      is(State.initialized){when(initial===metadata.vertexCount-1.U){phase:=State.planeStart}.otherwise{initial:=initial+1.U;phase:=State.initialize}}
      is(State.planeStart){written:=0.U;edge:=0.U;load(Mux(line,0.U,count-1.U),bank,State.loadPrevious)}
      is(State.loadPrevious){previous:=buffer;load(Mux(line,1.U,0.U),bank,State.loadCurrent)}
      is(State.loadCurrent){current:=buffer;phase:=State.classify}
      is(State.classify){
        val pin=inside(previous.vertex,plane);val cin=inside(current.vertex,plane)
        val onBoundary=Mux(pin,boundary(previous.vertex,plane),boundary(current.vertex,plane))
        when(line && pin && cin){phase:=State.planeAdvance}
          .elsewhen(line && !pin && !cin){culled:=true.B;phase:=State.finish}
          .elsewhen(pin=/=cin && (line || !onBoundary)) {
            entering:= !pin;copied:=onBoundary
            when(onBoundary){intersection:=Mux(pin,previous,current);phase:=State.intersectionReady}
              .otherwise {
                intersection:=0.U.asTypeOf(intersection);intersection.generated:=true.B
                intersection.vertex.identity:=metadata.identity;intersection.vertex.instanceId:=metadata.instanceId
                intersection.vertex.baseInstance:=metadata.baseInstance;phase:=State.distanceInside
              }
          }.elsewhen(cin){enqueue(1)}.otherwise{phase:=State.edgeAdvance}
      }
      is(State.distanceInside){calculate(insideW,insideCoordinate,Mux(plane(0),ClipFloatOperation.subtract.U,ClipFloatOperation.add.U),State.haveInside)}
      is(State.haveInside){
        when(mathValue.special=/=0.U || mathValue.negative || mathValue.significand===0.U){fail()}
          .otherwise{distanceA:=mathValue;phase:=State.distanceOutside}
      }
      is(State.distanceOutside){calculate(outsideW,outsideCoordinate,Mux(plane(0),ClipFloatOperation.subtract.U,ClipFloatOperation.add.U),State.haveOutside)}
      is(State.haveOutside){
        when(mathValue.special=/=0.U || !mathValue.negative || mathValue.significand===0.U){fail()}
          .otherwise{distanceB:=mathValue;distanceB.negative:=false.B;phase:=State.denominator}
      }
      is(State.denominator){calculate(distanceA,distanceB,ClipFloatOperation.add,State.haveDenominator)}
      is(State.haveDenominator){divisor:=mathValue;phase:=State.weightInside}
      is(State.weightInside){calculate(distanceB,divisor,ClipFloatOperation.divide,State.haveWeightInside)}
      is(State.haveWeightInside){weightA:=mathValue;phase:=State.weightOutside}
      is(State.weightOutside){calculate(distanceA,divisor,ClipFloatOperation.divide,State.haveWeightOutside)}
      is(State.haveWeightOutside){weightB:=mathValue;field:=0.U;phase:=State.interpolateField}
      is(State.interpolateField){
        when(fieldFlat){saveField(flatWord)}.elsewhen(insideWord===outsideWord){saveField(insideWord)}
          .otherwise{calculate(insideNumber,weightA,ClipFloatOperation.multiply,State.productOutside)}
      }
      is(State.productOutside){productA:=mathValue;calculate(outsideNumber,weightB,ClipFloatOperation.multiply,State.combine)}
      is(State.combine){calculate(productA,mathValue,ClipFloatOperation.add,State.storeField)}
      is(State.storeField){
        val finite=insideWord(30,23)=/=255.U && outsideWord(30,23)=/=255.U
        val low=Mux(less(insideWord,outsideWord),insideWord,outsideWord)
        val high=Mux(less(insideWord,outsideWord),outsideWord,insideWord)
        val word=Mux(finite && less(mathPacked,low),low,Mux(finite && less(high,mathPacked),high,mathPacked))
        when(field<4.U && (mathValue.special=/=0.U || word(30,23)===255.U)){fail()}.otherwise{saveField(word)}
      }
      is(State.intersectionReady){
        when(!copied){intersection:=snapped;intersections:=intersections+1.U}
        when(line){when(entering){enqueue(2,Some(1))}.otherwise{enqueue(0,Some(2))}}
          .otherwise{when(entering){enqueue(2,Some(1))}.otherwise{enqueue(2)}}
      }
      is(State.appendVertex){
        when(written>=MaxVertices.U){fail()}.otherwise {
          store(written,!bank,MuxLookup(appendSelect,intersection)(Seq(0.U->previous,1.U->current)),State.appended)
        }
      }
      is(State.appended){
        written:=written+1.U
        when(written+1.U>maxVertices){maxVertices:=written+1.U}
        when(second){appendSelect:=secondSelect;second:=false.B;phase:=State.appendVertex}.otherwise{phase:=State.edgeAdvance}
      }
      is(State.edgeAdvance){
        when(line || edge===count-1.U){count:=written;bank:= !bank;phase:=State.planeAdvance}
          .otherwise{previous:=current;edge:=edge+1.U;load(edge+1.U,bank,State.loadCurrent)}
      }
      is(State.planeAdvance){
        when(count<Mux(triangle,3.U,2.U)){culled:=true.B;phase:=State.finish}
          .elsewhen(plane===5.U){output.partIndex:=0.U;output.partCount:=Mux(triangle,count-2.U,1.U);phase:=State.exportStart}
          .otherwise{plane:=plane+1.U;phase:=State.planeStart}
      }
      is(State.exportStart){output.primitive.vertices.foreach(_:=0.U.asTypeOf(new ShadedVertexRecord(p)));output.generated:=0.U;exportSlot:=0.U;phase:=State.exportLoad}
      is(State.exportLoad){load(Mux(triangle && exportSlot=/=0.U,output.partIndex+&exportSlot,exportSlot),bank,State.exportLoaded)}
      is(State.exportLoaded){
        output.primitive.vertices(exportSlot):=buffer.vertex
        output.generated:=output.generated|Mux(buffer.generated,1.U(3.W)<<exportSlot,0.U)
        when(buffer.vertex.position(3)(30,0)===0.U){zeroW:=true.B}
        when(exportSlot===metadata.vertexCount-1.U){phase:=State.emit}.otherwise{exportSlot:=exportSlot+1.U;phase:=State.exportLoad}
      }
      is(State.emit){when(io.out.fire){
        outputs:=outputs+1.U
        when(stop){phase:=State.abort}.elsewhen(output.partIndex+&1.U===output.partCount){phase:=State.finish}
          .otherwise{output.partIndex:=output.partIndex+1.U;phase:=State.exportStart}
      }}
      is(State.readIssue){readSegment:=selected;phase:=State.readReply}
      is(State.readReply){
        val assembled=memoryBits|(readWord<<(chunk<<6));memoryBits:=assembled
        when(chunk===(chunks-1).U){buffer:=assembled(storedBits-1,0).asTypeOf(new StoredClipVertex(p));phase:=memoryReturn}
          .otherwise{chunk:=chunk+1.U;phase:=State.readIssue}
      }
      is(State.writeChunk){when(chunk===(chunks-1).U){phase:=memoryReturn}.otherwise{chunk:=chunk+1.U}}
      is(State.waitMath){when(arithmetic.io.out.fire && arithmetic.io.out.bits.error===0.U){phase:=mathReturn}}
      is(State.abort){when(!mathOffered && !mathPending && !arithmetic.io.busy){phase:=State.finish}}
      is(State.finish){
        result.identity:=metadata.identity;result.sequence:=metadata.sequence;result.primitiveId:=metadata.primitiveId;result.instanceId:=metadata.instanceId
        result.status:=Mux(io.cancel && status===0.U,4.U,status);result.outputs:=outputs;result.intersections:=intersections
        result.maxVertices:=maxVertices;result.arithmeticOperations:=operations
        result.arithmeticStatus:=arithmeticFlags;result.packingStatus:=packingFlags;result.planes:=affected
        result.culled:=culled;result.zeroW:=zeroW;phase:=State.complete
      }
      is(State.complete){when(io.completion.fire){phase:=State.idle}}
    }
  }
  when(active && io.cancel && status===0.U){status:=4.U}
  when(active && stop && phase=/=State.emit && phase=/=State.abort && phase=/=State.finish){phase:=State.abort}
}
