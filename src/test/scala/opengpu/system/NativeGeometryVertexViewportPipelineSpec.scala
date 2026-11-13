package opengpu.system

import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.core.memory._
import opengpu.graphics._
import opengpu.testutil.GraphicsTestResolution
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class NativeGeometryVertexViewportHarness extends Module {
  private val config=GpuConfig(lanes=4,warps=1,l2Sets=8,l2Ways=2)
  private val p=VertexShaderStageConfig()
  private val slots=GpuSystem.totalMemoryTransactions(2,4,8)
  val io=IO(new Bundle {
    val task=Flipped(Decoupled(new NativeGeometryVertexViewportTask(p,4)))
    val out=Decoupled(new GeometryWindowPrimitive(p))
    val completion=Decoupled(new NativeGeometryVertexViewportCompletion)
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,slots))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,slots)))
    val cancel=Input(Bool());val idle=Output(Bool())
    val launchObserved=Output(Valid(Bool()));val trapObserved=Output(Valid(new TaggedGraphicsTrap(config,4,2)))
    val owners=Output(UInt(2.W))
  })
  private val source=Module(new NativeGeometryVertexViewportPipeline(config,p,SharedCuShaderConfig(2,taskIdWidth=4)))
  private val system=Module(new GpuSystem(config,numComputeUnits=2,commandIdWidth=4,enableGraphicsKernels=true,
    geometryAvailable=true,graphicsHostTransactions=8,instructionCacheSets=4,instructionCacheWays=1,
    vectorCacheSets=4,vectorCacheWays=1))
  private def tie(x: Data): Unit=x match {
    case r: Record=>r.elements.values.foreach(tie);case v: Vec[_]=>v.foreach(tie)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b:=false.B
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s:=0.S
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u:=0.U;case _=>()
  }
  tie(system.io)
  source.io.task<>io.task;io.out<>source.io.out;io.completion<>source.io.completion;source.io.cancel:=io.cancel
  system.io.graphicsKernels.get<>source.io.shared
  system.io.graphicsHostRequest<>source.io.memoryRequest;source.io.memoryResponse<>system.io.graphicsHostResponse
  system.io.graphicsDrained:= !source.io.busy;system.io.graphicsShaderL1Invalidate.ready:=true.B
  io.memoryRequest<>system.io.memoryRequest;system.io.memoryResponse<>io.memoryResponse
  io.idle:=system.io.vmContextIdle && !source.io.busy
  io.launchObserved.valid:=source.io.shared.launch.fire
  io.launchObserved.bits:=source.io.shared.geometryTask.get.valid
  io.trapObserved.valid:=source.io.shared.trap.fire;io.trapObserved.bits:=source.io.shared.trap.bits
  io.owners:=source.io.shared.owned
}
class NativeGeometryVertexViewportPipelineSpec extends AnyFlatSpec {
  private def zero(x: Data): Unit=x match {
    case r: Record=>r.elements.values.foreach(zero);case v: Vec[_]=>v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U);case _=>()
  }
  private def addi(rd: Int,rs: Int,n: Int): BigInt=(BigInt(n&4095)<<20)|(BigInt(rs)<<15)|(BigInt(rd)<<7)|0x13
  private def vm(base: Int,v: Int,write: Boolean): BigInt=
    (BigInt(1)<<25)|(BigInt(base)<<15)|(BigInt(6)<<12)|(BigInt(v)<<7)|(if(write)0x27 else 7)
  private def geometry(fn: Int,rd: Int=0,rs1: Int=0,rs2: Int=0): BigInt=
    (BigInt(fn)<<25)|(BigInt(rs2)<<20)|(BigInt(rs1)<<15)|(BigInt(rd)<<7)|0x0b
  private def bne(rs1: Int,rs2: Int,offset: Int): BigInt={
    require(offset%2==0 && offset>= -4096 && offset<=4094)
    val bits=BigInt(offset&8191)
    (((bits>>12)&1)<<31)|(((bits>>5)&63)<<25)|(BigInt(rs2)<<20)|(BigInt(rs1)<<15)|
      (BigInt(1)<<12)|(((bits>>1)&15)<<8)|(((bits>>11)&1)<<7)|0x63
  }
  private val vertexProgram=Vector(BigInt("00241393",16),BigInt("007083b3",16),BigInt("c1027057",16),addi(6,7,112))++
    (0 until 4).flatMap(c=>Seq(addi(5,7,c*16),vm(5,1,false),vm(6,1,true),addi(6,6,16)))++Vector(BigInt("30500073",16))
  private def geometryProgram(builtins: Boolean): Vector[BigInt]=
    (0 until 4).flatMap(c=>Seq(addi(1,0,0),addi(2,0,c),geometry(32,3,1,2),geometry(33,0,2,3))).toVector++
      (if(builtins)Vector(addi(4,0,9),geometry(34,0,4),addi(4,0,93),geometry(38,0,4))else Vector.empty)++
      Vector(geometry(35),geometry(36),BigInt("30500073",16))

  // Emit clears the staged geometry registers. Keep the four original
  // position words in scalar registers and write all four before EVERY emit.
  // One counted invocation therefore produces 256 real four-component points
  // (1024 total components), rather than merely accepting maxVertices=256.
  // This 19-word physical-source program exercises a bounded hardware loop;
  // Protected validation and private RX ownership are checked separately;
  // this fixture executes direct physical-source code.
  private val fullEmissionProgram=Vector(addi(2,0,0))++
    (0 until 4).flatMap(c=>Seq(addi(10+c,0,c),geometry(32,14+c,2,10+c)))++
    Vector(addi(20,0,256))++(0 until 4).map(c=>geometry(33,0,10+c,14+c))++
    Vector(geometry(35),addi(20,20,-1),bne(20,0,-24),geometry(36),BigInt("30500073",16))
  require(fullEmissionProgram.size==19 && fullEmissionProgram.size<=512)

  private val trianglePositions=Vector(
    Vector(BigInt("c0000000",16),BigInt(0),BigInt(0),BigInt("3f800000",16)),
    Vector(BigInt(0),BigInt("bf800000",16),BigInt(0),BigInt("3f800000",16)),
    Vector(BigInt(0),BigInt("3f800000",16),BigInt(0),BigInt("3f800000",16)))
  private val triangleProgram=trianglePositions.flatMap { position=>
    position.zipWithIndex.flatMap{case(bits,c)=>Vector(addi(2,0,c),bits|(BigInt(3)<<7)|0x37,geometry(33,0,2,3))} :+ geometry(35)
  }++Vector(geometry(36),BigInt("30500073",16))

  it should "keep GS expansion counters beyond ordinary vertex widths" in {
    val result=new NativeGeometryVertexViewportCompletion
    val vertices=((BigInt(1)<<32)-1).pow(2)
    val mapped=vertices*32*256
    val parts=mapped*7
    assert(mapped.bitLength<=result.mappedInputs.getWidth)
    for(field<-Seq(result.clipOutputs,result.viewportInputs,result.preparedParts,result.droppedClipped))
      assert(parts.bitLength<=field.getWidth)
    assert((parts*255).bitLength<=result.arithmeticOperations.getWidth)
  }
  it should "clip and prepare real VS to GS output with stable sidebands and drained reuse" in {
    val side=math.min(16,math.min(GraphicsTestResolution.width,GraphicsTestResolution.height))
    simulate(new NativeGeometryVertexViewportHarness){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
      val memory=mutable.Map.empty[BigInt,BigInt]
      def put(address: BigInt,value: BigInt): Unit=for(b<-0 until 4)memory(address+b)=(value>>(8*b))&255
      for((base,program)<-Seq(0x1000->vertexProgram,0x900000->geometryProgram(false),0x901000->geometryProgram(true),
          0x902000->triangleProgram,0x903000->fullEmissionProgram);
          (word,i)<-program.zipWithIndex)put(base+4*i,word)
      for(a<-0x2fffc0 until 0x300100)memory(BigInt(a))=BigInt(0x5a)
      case class Reply(tag: BigInt,address: BigInt,data: BigInt,due: Int,fault: Boolean)
      val pending=mutable.ArrayBuffer.empty[Reply];var offered: Option[Reply]=None
      var cycle=0;var requests=0;var outputs=0;var vs=0;var gs=0;var owners=BigInt(0)
      var outputReady=false;var expectedBuiltins=false;var expectedTriangle=false;var expectedFullEmission=false
      var inputFault=false;var sourceBase=0x20000
      var lateInputFault=false;var firstViewportHeld=false;var lateFaultReplies=0
      var caseLabel="initial"
      val triangles=mutable.ArrayBuffer.empty[Vector[(BigInt,BigInt)]]
      val fullEmissionSequences=mutable.ArrayBuffer.empty[BigInt]
      // Independent exact clipping oracle: the left plane cuts the source
      // triangle at (-1,+/-1/2). The convex quad has NDC doubled area three.
      val clippedQuad=Map(
        (BigInt("bf800000",16),BigInt("3f000000",16))->(BigInt(0),BigInt(side*192)),
        (BigInt("bf800000",16),BigInt("bf000000",16))->(BigInt(0),BigInt(side*64)),
        (BigInt(0),BigInt("bf800000",16))->(BigInt(side*128),BigInt(0)),
        (BigInt(0),BigInt("3f800000",16))->(BigInt(side*128),BigInt(side*256)))
      var heldRequest: Option[BigInt]=None;var heldOutput: Option[BigInt]=None
      def tick(): Unit={
        d.io.memoryRequest.ready.poke((cycle%3!=0).B)
        if(offered.isEmpty){val ready=pending.indexWhere(r=>r.due<=cycle &&
          !(lateInputFault && r.fault && !firstViewportHeld))
          if(ready>=0)offered=Some(pending.remove(ready))}
        d.io.memoryResponse.valid.poke(offered.nonEmpty.B)
        offered.foreach{r=>d.io.memoryResponse.bits.transactionId.poke(r.tag.U);d.io.memoryResponse.bits.readData.poke(r.data.U)
          d.io.memoryResponse.bits.fault.poke(r.fault.B)}
        if(offered.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean){
          if(lateInputFault && offered.get.fault){assert(firstViewportHeld);lateFaultReplies+=1}
          offered=None}
        val request=d.io.memoryRequest
        if(request.valid.peek().litToBoolean){
          val bits=request.bits.peek().litValue;heldRequest.foreach(x=>assert(x==bits));heldRequest=Some(bits)
          if(request.ready.peek().litToBoolean){val r=request.bits;val address=r.address.peek().litValue
            val fault=(inputFault && address==sourceBase) || (lateInputFault && address==sourceBase+9*256)
            if(r.isWrite.peek().litToBoolean && !fault){val mask=r.byteMask.peek().litValue;val data=r.writeData.peek().litValue
              for(b<-0 until 64 if mask.testBit(b))memory(address+b)=(data>>(8*b))&255}
            val data=(0 until 64).map(b=>memory.getOrElse(address+b,BigInt(0))<<(8*b)).sum
            requests+=1;pending+=Reply(r.transactionId.peek().litValue,address,data,cycle+4,fault);heldRequest=None
          }
        }else assert(heldRequest.isEmpty)
        d.io.out.ready.poke(outputReady.B)
        if(d.io.out.valid.peek().litToBoolean){val o=d.io.out.bits;val bits=o.peek().litValue
          if(lateInputFault && !outputReady)firstViewportHeld=true
          heldOutput.foreach(x=>assert(x==bits,"held GS viewport output changed"))
          val invocation=if(expectedFullEmission)0 else if(expectedTriangle)outputs/2 else outputs
          val sequence=17+(if(expectedTriangle)outputs/2 else outputs)
          o.inputPrimitiveId.expect(0.U);o.invocationId.expect(invocation.U)
          o.layer.expect((if(expectedBuiltins)9 else 0).U)
          val p=o.window.clipped.primitive
          p.sequence.expect(sequence.U)
          p.primitiveId.expect((if(expectedBuiltins)93 else 0).U)
          if(expectedTriangle){
            p.vertexCount.expect(3.U);o.window.clipped.partIndex.expect((outputs%2).U)
            o.window.clipped.partCount.expect(2.U);o.window.clipped.clipped.expect(true.B)
            var generated=0
            val coordinates=(0 until 3).map { i=>
              val vertex=p.vertices(i);val key=(vertex.position(0).peek().litValue,vertex.position(1).peek().litValue)
              assert(clippedQuad.contains(key),s"unexpected clipped GS vertex $key")
              if(key._1==BigInt("bf800000",16))generated|=1<<i
              vertex.position(2).expect(0.U);vertex.position(3).expect("h3f800000".U)
              val expected=clippedQuad(key);o.window.vertices(i).screenX.expect(expected._1.S)
              o.window.vertices(i).screenY.expect(expected._2.S)
              o.window.vertices(i).windowDepth.expect("h3f000000".U);o.window.vertices(i).fixedValid.expect(true.B)
              expected
            }.toVector
            assert(coordinates.distinct.size==3);o.window.clipped.generated.expect(generated.U)
            if(outputReady)triangles+=coordinates
          }else{
            p.vertexCount.expect(1.U);o.window.clipped.partIndex.expect(0.U)
            for(c<-0 until 4){val expected=if(c==3)BigInt("3f800000",16)else BigInt(0)
              assert(p.vertices(0).position(c).peek().litValue==expected,s"$caseLabel output=$outputs position[$c] expected=$expected actual=${p.vertices(0).position(c).peek().litValue}")}
            o.window.vertices(0).screenX.expect((side*128).S);o.window.vertices(0).screenY.expect((side*128).S)
            o.window.vertices(0).windowDepth.expect("h3f000000".U);o.window.vertices(0).fixedValid.expect(true.B)
          }
          if(outputReady){if(expectedFullEmission)fullEmissionSequences+=p.sequence.peek().litValue
            outputs+=1;heldOutput=None}else heldOutput=Some(bits)
        }else assert(heldOutput.isEmpty,"held GS viewport output withdrawn")
        assert(!d.io.trapObserved.valid.peek().litToBoolean,"unexpected physical VS/GS trap")
        if(d.io.launchObserved.valid.peek().litToBoolean){if(d.io.launchObserved.bits.peek().litToBoolean)gs+=1 else vs+=1}
        owners|=d.io.owners.peek().litValue;d.clock.step();cycle+=1
      }
      def until(label: String)(p: => Boolean): Unit={var n=0;while(!p && n<200000){tick();n+=1}
        assert(p,s"$caseLabel: $label stalled cycle=$cycle outputs=$outputs VS=$vs GS=$gs requests=$requests pending=${pending.map(r=>(r.tag,r.address,r.fault))} offered=$offered")}
      def view(x: VertexInputView,base: Int,bytes: Int): Unit={x.base.poke(base.U);x.limit.poke((base+bytes).U)
        x.allocationBase.poke((base& ~63).U);x.allocationLimit.poke(((base+bytes+63)& ~63).U)}
      def task(invocations: Int,builtins: Boolean=false,outside: Boolean=false,triangle: Boolean=false,
          fullEmission: Boolean=false): Unit={
        require(!fullEmission || (invocations==1 && !builtins && !triangle))
        caseLabel=s"invocations=$invocations builtins=$builtins outside=$outside triangle=$triangle fullEmission=$fullEmission lateInputFault=$lateInputFault"
        outputs=0;triangles.clear();fullEmissionSequences.clear()
        expectedBuiltins=builtins;expectedTriangle=triangle;expectedFullEmission=fullEmission;zero(d.io.task.bits)
        val t=d.io.task.bits;val s=t.source;val v=s.draw.vertex;val g=s.program
        s.commandId.poke(4.U);s.taskId.poke(19.U);s.firstSequence.poke(17.U)
        v.version.poke(1.U);v.shaderPc.poke(0x1000.U);v.attributeSlots.poke(1.U)
        v.input.version.poke(1.U);v.input.identity.context.poke(7.U);v.input.identity.pass.poke(8.U);v.input.identity.generation.poke(9.U)
        v.input.count.poke(1.U);v.input.instances.poke(1.U)
        view(v.input.bindings(0),sourceBase,16);v.input.bindings(0).stride.poke(16.U)
        val a=v.input.attributes(0);a.enabled.poke(true.B);a.components.poke(4.U);a.format.poke(VertexInputFormat.float32.U)
        for(c<-0 until 4)put(sourceBase+c*4,if(c==3)BigInt("3f800000",16)else if(c==2 && outside)BigInt("40000000",16)else BigInt(0))
        view(v.scratch,0x300000,176);v.sampler.texWidth.poke(1.U);v.sampler.texHeight.poke(1.U)
        g.identity.context.poke(7.U);g.identity.pass.poke(8.U);g.identity.generation.poke(9.U)
        g.inputVertices.poke(1.U);g.inputComponents.poke(4.U);g.invocations.poke(invocations.U)
        g.maxVertices.poke((if(builtins)170 else 256).U);g.outputComponents.poke(4.U)
        g.builtinOutputMask.poke((if(builtins)3 else 0).U);g.programVersion.poke(1.U);g.outputTopology.poke((if(triangle)2 else 0).U)
        val pc=if(fullEmission)0x903000 else if(triangle)0x902000 else if(builtins)0x901000 else 0x900000
        val program=if(fullEmission)fullEmissionProgram else if(triangle)triangleProgram else geometryProgram(builtins)
        g.pc.poke(pc.U);view(g.code,pc,program.size*4)
        for(c<-0 until 4){g.inputSources(c).poke(c.U);g.outputSources(c).valid.poke(true.B);g.outputSources(c).bits.poke(c.U)}
        t.viewport.version.poke(1.U);t.viewport.extentX.poke(side.U);t.viewport.extentY.poke(side.U)
        t.viewport.nearDepth.poke(0.U);t.viewport.farDepth.poke("h3f800000".U)
      }
      def submit(): Unit={d.io.task.valid.poke(true.B);until("task")(d.io.task.ready.peek().litToBoolean);tick()
        d.io.task.valid.poke(false.B);zero(d.io.task.bits)}
      def retireResult(): Unit={
        val r=d.io.completion.bits
        assert(pending.isEmpty && offered.isEmpty && heldRequest.isEmpty)
        val bits=r.peek().litValue;for(_<-0 until 5){tick();assert(r.peek().litValue==bits);d.io.task.ready.expect(false.B)}
        d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B);until("idle")(d.io.idle.peek().litToBoolean)
        sourceBase+=256
      }
      def finish(status: Int,emitted: Int,parts: Int,culled: Int=0,clippedParts: Option[Int]=None): Unit={
        until("completion")(d.io.completion.valid.peek().litToBoolean);val r=d.io.completion.bits
        r.status.expect(status.U);r.source.emittedPrimitives.expect(emitted.U)
        r.mappedInputs.expect(emitted.U);r.clipInputs.expect(emitted.U);r.clipCulled.expect(culled.U)
        r.clipOutputs.expect(clippedParts.getOrElse(parts).U);r.viewportInputs.expect(clippedParts.getOrElse(parts).U)
        r.preparedParts.expect(parts.U)
        r.droppedMapped.expect(0.U);r.droppedClipped.expect(0.U);assert(outputs==parts)
        retireResult()
      }
      val emptyRequests=requests
      task(1);d.io.task.bits.viewport.version.poke(0.U);submit();finish(1,0,0)
      assert(vs==0 && gs==0 && requests==emptyRequests)
      task(1);d.io.task.bits.source.program.outputComponents.poke(5.U);submit();finish(1,0,0)
      assert(vs==0 && gs==0 && requests==emptyRequests)
      task(32);submit()
      while(outputs<32){until("32-invocation viewport output")(d.io.out.valid.peek().litToBoolean)
        for(_<-0 until 3)tick();outputReady=true;tick();outputReady=false}
      finish(0,32,32)
      for(cancelLast<-Seq(false,true)){
        val beforeVs=vs;val beforeGs=gs
        task(1,fullEmission=true);submit()
        while(outputs<255){until("256-point GS prefix")(d.io.out.valid.peek().litToBoolean)
          outputReady=true;tick();outputReady=false}
        until("256th GS point held")(d.io.out.valid.peek().litToBoolean)
        val last=d.io.out.bits.peek().litValue
        if(cancelLast){d.io.cancel.poke(true.B);tick();d.io.cancel.poke(false.B)}
        for(_<-0 until 9){d.io.out.valid.expect(true.B);assert(d.io.out.bits.peek().litValue==last)
          d.io.completion.valid.expect(false.B);tick()}
        outputReady=true;tick();outputReady=false
        until("256-point GS summary")(d.io.completion.valid.peek().litToBoolean)
        d.io.completion.bits.source.completedInvocations.expect(1.U)
        d.io.completion.bits.source.nextSequence.expect(273.U)
        assert(fullEmissionSequences.toVector==(17 until 273).map(BigInt(_)).toVector)
        finish(if(cancelLast)4 else 0,256,256)
        assert(vs==beforeVs+1 && gs==beforeGs+1)
      }
      task(1);submit();until("reuse after 256-point cancellation")(d.io.out.valid.peek().litToBoolean)
      outputReady=true;tick();outputReady=false;finish(0,1,1)
      task(1,builtins=true);submit();until("builtin output")(d.io.out.valid.peek().litToBoolean)
      outputReady=true;tick();outputReady=false;finish(0,1,1)
      task(1,builtins=true);submit();until("held cancellation output")(d.io.out.valid.peek().litToBoolean)
      val held=d.io.out.bits.peek().litValue;d.io.cancel.poke(true.B);tick();d.io.cancel.poke(false.B)
      for(_<-0 until 7){assert(d.io.out.bits.peek().litValue==held);d.io.completion.valid.expect(false.B);tick()}
      outputReady=true;tick();outputReady=false;finish(4,1,1)
      task(1,builtins=true,outside=true);submit();finish(0,1,0,1)
      inputFault=true;task(1);submit();finish(3,0,0);inputFault=false
      task(1,builtins=true);put(sourceBase,BigInt("7fc00000",16));submit()
      until("clip fault identity")(d.io.completion.valid.peek().litToBoolean)
      val fault=d.io.completion.bits
      fault.status.expect(7.U);fault.faultStage.expect(3.U);fault.clipFaultValid.expect(true.B)
      fault.clipFault.status.expect(7.U);fault.clipFault.sequence.expect(17.U)
      fault.clipFault.primitiveId.expect(93.U);fault.viewportFaultValid.expect(false.B)
      fault.clipFaultGeometry.inputPrimitiveId.expect(0.U);fault.clipFaultGeometry.invocationId.expect(0.U)
      fault.clipFaultGeometry.layer.expect(9.U);finish(7,1,0)
      task(1,builtins=true);put(sourceBase+12,0);submit()
      until("viewport fault identity")(d.io.completion.valid.peek().litToBoolean)
      fault.status.expect(7.U);fault.faultStage.expect(4.U);fault.viewportFaultValid.expect(true.B)
      fault.viewportFault.status.expect(7.U);fault.viewportFault.sequence.expect(17.U)
      fault.viewportFault.primitiveId.expect(93.U);fault.viewportFault.partIndex.expect(0.U)
      fault.viewportFault.faultVertex.expect(0.U);fault.clipFaultValid.expect(false.B)
      fault.viewportFaultGeometry.inputPrimitiveId.expect(0.U);fault.viewportFaultGeometry.invocationId.expect(0.U)
      fault.viewportFaultGeometry.layer.expect(9.U);finish(7,1,0,clippedParts=Some(1))
      // The third VS batch's fault must arrive while a REAL downstream
      // output is held. Other tags continue to return while that fault waits,
      // so it cannot block the first VS/GS instruction fill that produces it.
      val lateVsStart=vs;val lateGsStart=gs
      sourceBase+=4096;lateInputFault=true;firstViewportHeld=false;lateFaultReplies=0
      task(1);val lateVertex=d.io.task.bits.source.draw.vertex
      lateVertex.input.count.poke(12.U);view(lateVertex.input.bindings(0),sourceBase,11*256+16)
      d.io.task.bits.source.draw.topology.poke(12.U);d.io.task.bits.source.program.inputVertices.poke(6.U)
      lateVertex.input.bindings(0).stride.poke(256.U)
      for(i<-0 until 12;c<-0 until 4)put(sourceBase+i*256+c*4,if(c==3)BigInt("3f800000",16)else BigInt(0))
      submit();until("source fault during held viewport output")(lateFaultReplies==1)
      d.io.out.valid.expect(true.B);val heldOnFault=d.io.out.bits.peek().litValue
      for(_<-0 until 9){tick();d.io.out.valid.expect(true.B);assert(d.io.out.bits.peek().litValue==heldOnFault)
        d.io.completion.valid.expect(false.B)}
      outputReady=true;tick();outputReady=false
      until("source error ahead of downstream cancellation")(d.io.completion.valid.peek().litToBoolean)
      fault.status.expect(3.U);fault.faultStage.expect(2.U);fault.source.status.expect(3.U)
      fault.source.source.source.input.status.expect(3.U)
      fault.source.source.source.input.faultAddress.expect((sourceBase+9*256).U)
      fault.clipFaultValid.expect(false.B);fault.viewportFaultValid.expect(false.B)
      assert(outputs==1 && lateFaultReplies==1)
      assert(fault.mappedInputs.peek().litValue==fault.source.emittedPrimitives.peek().litValue)
      assert(fault.mappedInputs.peek().litValue==fault.clipInputs.peek().litValue+fault.droppedMapped.peek().litValue)
      assert(fault.clipOutputs.peek().litValue==fault.viewportInputs.peek().litValue+fault.droppedClipped.peek().litValue)
      val lateVsLaunches=vs-lateVsStart;val lateGsLaunches=gs-lateGsStart
      assert(lateVsLaunches==2 && lateGsLaunches==1)
      retireResult();sourceBase+=4096;lateInputFault=false
      task(1);submit();until("healthy reuse output")(d.io.out.valid.peek().litToBoolean)
      outputReady=true;tick();outputReady=false;finish(0,1,1)
      task(2,triangle=true);submit()
      while(outputs<4){until("clipped GS fan output")(d.io.out.valid.peek().litToBoolean)
        for(_<-0 until 5)tick();outputReady=true;tick();outputReady=false}
      finish(0,2,4)
      for(pair<-triangles.grouped(2)){
        assert(pair.size==2 && pair.flatten.toSet==clippedQuad.values.toSet)
        assert((pair.head.toSet intersect pair.last.toSet).size==2)
        val area=pair.map { triangle=>
          val a=triangle(0);val b=triangle(1);val c=triangle(2)
          val cross=(b._1-a._1)*(c._2-a._2)-(b._2-a._2)*(c._1-a._1)
          assert(cross>0);cross
        }.sum
        assert(area==BigInt(3)*BigInt(side*128).pow(2))
      }
      // The late source-fault case may cancel a physical batch/GS offer after
      // its first output. Its observed launches are checked separately above.
      assert(vs==11+lateVsLaunches && gs==43+lateGsLaunches)
      assert(memory(BigInt(0x2fffff))==0x5a && memory(BigInt(0x3000c0))==0x5a)
      info(s"NATIVE_VS_GS_VIEWPORT real2CU4x1 actualInvocations32 actualPoints256 singleInvocationComponents1024 fullEmitProgramWords=${fullEmissionProgram.size} layerPrimitiveId=true clippedFans=true ownerMask=$owners noResetReuse=true")
    }
  }
}
