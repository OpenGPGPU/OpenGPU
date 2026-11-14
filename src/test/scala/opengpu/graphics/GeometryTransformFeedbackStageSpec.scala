package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** Production GS strip assembler/raw mapper and ordinary raw assembler feed
  * the same actual exporter. This fixture checks renderer inputs, not GPU
  * shader execution, clipping, protected translation or product frame rate.
  */
private class GeometryFeedbackHarness extends Module {
  val p=VertexShaderStageConfig();val config=GpuConfig()
  val io=IO(new Bundle {
    val geometry=Input(Bool());val cancel=Input(Bool())
    val task=Flipped(Decoupled(new GeometryOutputTask))
    val emit=Flipped(Decoupled(new GeometryOutputCommand))
    val layout=Input(new GeometryPrimitiveOutputRequest(p))
    val geometryDone=Decoupled(new GeometryOutputCompletion)
    val draw=Flipped(Decoupled(new PrimitiveAssemblyDraw(p)))
    val vertex=Flipped(Decoupled(new ShadedVertexRecord(p)))
    val end=Flipped(Decoupled(new VertexShaderCompletion))
    val ordinaryDone=Decoupled(new PrimitiveAssemblyCompletion)
    val out=Decoupled(new GeometryMappedPrimitive(p))
    val feedback=new TransformFeedbackPipelineIO(config)
    val result=Valid(new TransformFeedbackPrimitiveResult)
    val busy=Output(Bool());val mappingFault=Output(Bool())
  })
  private val gs=Module(new GeometryOutputAssembler)
  private val mapper=Module(new GeometryPrimitiveOutput(p,flatShading=false))
  private val ordinary=Module(new PrimitiveAssemblyStage(p,flatShading=false))
  private val stage=Module(new GeometryTransformFeedbackStage(config,p))
  gs.io.task<>io.task;gs.io.command<>io.emit;io.geometryDone<>gs.io.completion;gs.io.cancel:=io.cancel
  mapper.io.in.valid:=gs.io.primitive.valid;gs.io.primitive.ready:=mapper.io.in.ready
  mapper.io.in.bits:=io.layout;mapper.io.in.bits.geometry:=gs.io.primitive.bits
  private val sequence=Reg(UInt(64.W))
  when(io.task.fire){sequence:=io.layout.sequence}
  mapper.io.in.bits.sequence:=sequence;when(mapper.io.in.fire){sequence:=sequence+1.U}
  io.mappingFault:=mapper.io.fault.valid;mapper.io.fault.ready:=true.B
  ordinary.io.draw<>io.draw;ordinary.io.in<>io.vertex;ordinary.io.end<>io.end
  io.ordinaryDone<>ordinary.io.completion;ordinary.io.cancel:=io.cancel
  stage.io.in.valid:=Mux(io.geometry,mapper.io.out.valid,ordinary.io.out.valid)
  stage.io.in.bits:=mapper.io.out.bits
  when(!io.geometry){stage.io.in.bits:=0.U.asTypeOf(stage.io.in.bits)
    stage.io.in.bits.primitive:=ordinary.io.out.bits
    stage.io.in.bits.inputPrimitiveId:=ordinary.io.out.bits.primitiveId}
  mapper.io.out.ready:=io.geometry && stage.io.in.ready
  ordinary.io.out.ready:= !io.geometry && stage.io.in.ready
  io.out<>stage.io.out;io.feedback<>stage.io.feedback;io.result:=stage.io.result
  stage.io.cancel:=io.cancel;io.busy:=stage.io.busy
}

class GeometryTransformFeedbackStageSpec extends AnyFlatSpec {
  import WindowRecordTest._
  private val flat=BigInt("aaaaaaaaaaaaaaaa",16)
  private val base=BigInt(0x1003c)
  private def raw(vertex:Int,component:Int):BigInt=BigInt(0x80000000L)+vertex*256+component
  private class Driver(val d:GeometryFeedbackHarness) {
    zero(d.io);d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
    val memory=new Memory(d.io.feedback.memoryRequest.bits,d.io.feedback.memoryResponse.bits,
      d.io.feedback.memoryRequest.valid,d.io.feedback.memoryRequest.ready,
      d.io.feedback.memoryResponse.valid,d.io.feedback.memoryResponse.ready)
    val outputs=mutable.ArrayBuffer.empty[Map[String,BigInt]]
    val results=mutable.ArrayBuffer.empty[(Int,Boolean,BigInt)]
    private var held=Option.empty[BigInt]
    val scope=d.io.feedback.scope
    scope.context.poke(7.U);scope.root.poke(0x80000030L.U);scope.program.poke(9.U)
    scope.mode.poke(1.U);scope.cookie.poke(1.U)
    d.io.geometryDone.ready.poke(true.B);d.io.ordinaryDone.ready.poke(true.B)
    private def whenOrdinaryCompletion():Unit={
      if(d.io.ordinaryDone.valid.peek().litToBoolean && d.io.ordinaryDone.ready.peek().litToBoolean){
        d.io.ordinaryDone.bits.status.expect(0.U);d.io.ordinaryDone.bits.vertices.expect(3.U)
        d.io.ordinaryDone.bits.restarts.expect(0.U);d.io.ordinaryDone.bits.source.status.expect(0.U)
        d.io.ordinaryDone.bits.source.vertices.expect(3.U)
      }
    }
    def tick():Unit={
      memory.drive();d.io.mappingFault.expect(false.B)
      whenOrdinaryCompletion()
      held.foreach(value=>{d.io.out.valid.expect(true.B);assert(d.io.out.bits.peek().litValue==value)})
      held=if(d.io.out.valid.peek().litToBoolean && !d.io.out.ready.peek().litToBoolean)
        Some(d.io.out.bits.peek().litValue)else None
      if(d.io.out.valid.peek().litToBoolean && d.io.out.ready.peek().litToBoolean)outputs+=values(d.io.out.bits)
      if(d.io.result.valid.peek().litToBoolean)results+=((d.io.result.bits.status.peek().litValue.toInt,
        d.io.result.bits.captured.peek().litToBoolean,d.io.result.bits.bytes.peek().litValue))
      if(d.io.feedback.memoryRequest.valid.peek().litToBoolean)d.io.feedback.memorySatp.expect(0x80000030L.U)
      memory.capture();d.clock.step()
    }
    def until(label:String)(condition: =>Boolean):Unit={var n=0;while(!condition && n<20000){tick();n+=1}
      assert(condition,s"$label stalled cycle=${memory.cycle}")}
    def command(action:Int,kind:Int=2,context:Int=7,cookie:Int=1,status:Int=0):Map[String,BigInt]={
      val q=d.io.feedback.command;zero(q.bits);q.bits.action.poke(action.U)
      val s=q.bits.state;val v=s.descriptor;s.root.poke(0x80000030L.U)
      v.context.poke(context.U);v.cookie.poke(cookie.U);v.program.poke(9.U)
      if(action==TransformFeedbackAction.begin){v.kind.poke(kind.U);v.interleaved.poke(true.B)
        v.buffers.poke(1.U);v.components(0).poke(64.U);v.views(0).base.poke(base.U);v.views(0).limit.poke((base+8192).U)
        for(i<-0 until 64)v.selectors(i).poke((4+i).U)}
      q.valid.poke(true.B);until("TF command")(q.ready.peek().litToBoolean);tick();q.valid.poke(false.B)
      until("real TF command reply")(d.io.feedback.commandReply.valid.peek().litToBoolean)
      val r=d.io.feedback.commandReply;r.bits.status.expect(status.U)
      val observed=values(r.bits.state);val old=r.bits.peek().litValue
      for(_<-0 until 7){tick();r.valid.expect(true.B);assert(r.bits.peek().litValue==old);d.io.busy.expect(true.B)}
      r.ready.poke(true.B);tick();r.ready.poke(false.B)
      observed
    }
    def gsStart(kind:Int=2):Unit={
      d.io.geometry.poke(true.B);val l=d.io.layout;zero(l)
      l.taskId.poke(19.U);l.source.primitiveId.poke(27.U);identity(l.source.identity)
      l.sequence.poke(99.U);l.outputComponents.poke(8.U);l.builtinOutputMask.poke(3.U)
      l.varyingSlots.poke(16.U);l.flatMask.poke(flat.U)
      for(i<-0 until 68){l.componentSources(i).valid.poke(true.B)
        l.componentSources(i).bits.poke((if(i<4)i else 4+(i-4)%4).U)}
      val t=d.io.task;t.bits.taskId.poke(19.U);t.bits.primitiveId.poke(27.U);t.bits.invocationId.poke(3.U)
      t.bits.topology.poke(kind.U);t.bits.maxVertices.poke(4.U);t.bits.outputComponents.poke(8.U);t.bits.builtinOutputMask.poke(3.U)
      t.valid.poke(true.B);until("GS output task")(t.ready.peek().litToBoolean);tick();t.valid.poke(false.B)
    }
    def emit(vertex:Int):Unit={val q=d.io.emit;zero(q.bits);q.bits.operation.poke(GeometryOutputEvent.emit)
      for(c<-0 until 8)q.bits.vertex.components(c).poke(raw(vertex,c).U)
      q.bits.vertex.layer.poke(2.U);q.bits.vertex.shaderPrimitiveId.poke((90+vertex).U)
      q.valid.poke(true.B);until("real GS emit")(q.ready.peek().litToBoolean);tick();q.valid.poke(false.B)}
    def finishGs():Unit={val q=d.io.emit;q.bits.operation.poke(GeometryOutputEvent.finish)
      q.valid.poke(true.B);until("GS finish")(q.ready.peek().litToBoolean);tick();q.valid.poke(false.B)}
    def ordinary():Unit={
      d.io.geometry.poke(false.B);val q=d.io.draw;zero(q.bits)
      q.bits.version.poke(1.U);identity(q.bits.identity);q.bits.topology.poke(PrimitiveTopology.triangles.U)
      q.bits.count.poke(3.U);q.bits.instances.poke(1.U);q.bits.varyingSlots.poke(16.U);q.bits.flatMask.poke(flat.U)
      q.valid.poke(true.B);until("ordinary draw")(q.ready.peek().litToBoolean);tick();q.valid.poke(false.B)
      for(v<-0 until 3){val x=d.io.vertex;zero(x.bits);identity(x.bits.identity);x.bits.ordinal.poke(v.U)
        for(c<-0 until 4)x.bits.position(c).poke(raw(v,c).U)
        for(c<-0 until 64)x.bits.varyings(c/4)(c%4).poke(raw(v,c+4).U)
        x.valid.poke(true.B);until("ordinary vertex")(x.ready.peek().litToBoolean);tick();x.valid.poke(false.B)}
      zero(d.io.end.bits);identity(d.io.end.bits.identity)
      d.io.end.bits.vertices.poke(3.U);d.io.end.valid.poke(true.B)
      until("ordinary end")(d.io.end.ready.peek().litToBoolean);tick();d.io.end.valid.poke(false.B)
    }
    def rendered(vertices:Seq[Int],gs:Boolean):Unit={
      until("TF ACK then renderer input")(d.io.out.valid.peek().litToBoolean)
      assert(memory.pending.isEmpty && memory.reply.isEmpty)
      val q=d.io.out.bits;val p=q.primitive
      q.inputPrimitiveId.expect((if(gs)27 else 0).U);q.invocationId.expect((if(gs)3 else 0).U);q.layer.expect((if(gs)2 else 0).U)
      p.vertexCount.expect(vertices.size.U);p.provokingVertex.expect((vertices.size-1).U)
      p.primitiveId.expect((if(gs)90+vertices.last else 0).U)
      for((v,index)<-vertices.zipWithIndex;c<-0 until 64){
        val selected=if(flat.testBit(c))vertices.last else v
        val component=if(gs)4+c%4 else 4+c
        p.vertices(index).varyings(c/4)(c%4).expect(raw(selected,component).U)
      }
      val old=q.peek().litValue
      for(_<-0 until 9){tick();d.io.out.valid.expect(true.B);assert(q.peek().litValue==old);d.io.busy.expect(true.B)}
      d.io.out.ready.poke(true.B);tick();d.io.out.ready.poke(false.B)
      until("real metadata drain")(!d.io.busy.peek().litToBoolean)
    }
    def capturedWords(vertices:Seq[Int],gs:Boolean,offset:Int=0):Unit={
      for((v,i)<-vertices.zipWithIndex;c<-0 until 64){
        val expected=raw(v,if(gs)4+c%4 else c+4)
        assert(memory.word(base+offset+(i*64+c)*4)==expected,"feedback captured flat or differently ordered values")
      }
    }
  }
  it should "capture raw GS strip vertices before the original flat renderer mapping and hold end acknowledgements" in {
    simulate(new GeometryFeedbackHarness){d=>val t=new Driver(d);t.command(TransformFeedbackAction.begin)
      t.gsStart();t.emit(0);t.emit(1);t.emit(2);t.rendered(Seq(0,1,2),gs=true)
      t.emit(3);t.rendered(Seq(2,1,3),gs=true);t.finishGs()
      t.capturedWords(Seq(0,1,2,2,1,3),gs=true)
      val s=t.command(TransformFeedbackAction.end)
      assert(s("primitives")==2 && s("vertices")==6 && s("offsets[0]")==1536)
      t.command(TransformFeedbackAction.begin);t.ordinary();t.rendered(Seq(0,1,2),gs=false)
      t.capturedWords(Seq(0,1,2),gs=false)
      val reused=t.command(TransformFeedbackAction.end)
      assert(reused("primitives")==1 && reused("vertices")==3 && reused("offsets[0]")==768)
    }
  }
  it should "retire discarded metadata from real feedback completion and render the next source without reset" in {
    simulate(new GeometryFeedbackHarness){d=>val t=new Driver(d)
      t.command(TransformFeedbackAction.begin,kind=0);t.scope.rasterDiscard.poke(true.B)
      t.gsStart(kind=0);t.emit(0);t.finishGs()
      t.until("discard ACK")(t.results.nonEmpty);t.until("discard metadata drain")(!d.io.busy.peek().litToBoolean)
      d.io.out.valid.expect(false.B);assert(t.results.last==(0,true,BigInt(256)))
      val s=t.command(TransformFeedbackAction.end,kind=0);assert(s("vertices")==1 && s("offsets[0]")==256)
      t.scope.rasterDiscard.poke(false.B);t.command(TransformFeedbackAction.begin)
      t.ordinary();t.rendered(Seq(0,1,2),gs=false);t.command(TransformFeedbackAction.end)
    }
  }
  it should "retain owned store tuples through cancellation and preserve a late memory fault while keeping peer objects" in {
    simulate(new GeometryFeedbackHarness){d=>val t=new Driver(d)
      for(fault<-Seq(false,true)){
        t.command(TransformFeedbackAction.begin,kind=0)
        t.command(TransformFeedbackAction.begin,kind=0,context=20,cookie=2)
        t.memory.holdRequests=true;t.gsStart(kind=0);t.emit(0);t.finishGs()
        t.until("real held TF store")(d.io.feedback.memoryRequest.valid.peek().litToBoolean)
        val held=d.io.feedback.memoryRequest.bits.peek().litValue
        d.io.cancel.poke(true.B);t.tick();d.io.cancel.poke(false.B)
        for(_<-0 until 9){t.tick();d.io.feedback.memoryRequest.valid.expect(true.B)
          assert(d.io.feedback.memoryRequest.bits.peek().litValue==held);d.io.busy.expect(true.B)}
        if(fault)t.memory.faultAt=Set(base& ~BigInt(63))
        val previous=t.results.size;t.memory.holdRequests=false
        t.until("real cancelled store result")(t.results.size>previous)
        val status=if(fault)3 else 4;assert(t.results.last._1==status && !t.results.last._2)
        t.until("cancel metadata drain")(!d.io.busy.peek().litToBoolean)
        d.io.out.valid.expect(false.B)
        val s=t.command(TransformFeedbackAction.end,kind=0,status=status)
        assert(s("vertices")==0 && s("primitives")==0 && s("offsets[0]")==0)
        t.command(TransformFeedbackAction.end,kind=0,context=20,cookie=2)
        t.memory.faultAt=Set.empty
        t.command(TransformFeedbackAction.begin);t.ordinary();t.rendered(Seq(0,1,2),gs=false)
        t.command(TransformFeedbackAction.end)
      }
    }
  }
  it should "keep ordinary bypass raw until post-feedback flat mapping while the object is paused" in {
    simulate(new GeometryFeedbackHarness){d=>val t=new Driver(d);t.command(TransformFeedbackAction.begin)
      t.command(TransformFeedbackAction.pause);t.scope.mode.poke(2.U)
      val stores=t.memory.seen.size;t.ordinary();t.rendered(Seq(0,1,2),gs=false)
      assert(t.memory.seen.size==stores && t.results.last==(0,false,BigInt(0)))
      t.command(TransformFeedbackAction.end)
    }
  }
}
