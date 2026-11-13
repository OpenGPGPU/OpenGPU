package opengpu.system

import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.core.memory._
import opengpu.graphics._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class NativeGeometryVertexHarness extends Module {
  private val config=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2)
  private val p=VertexShaderStageConfig()
  private val slots=GpuSystem.totalMemoryTransactions(2,4,8)
  val io=IO(new Bundle {
    val task=Flipped(Decoupled(new NativeGeometryVertexTask(p,4)))
    val out=Decoupled(new GeometryMappedPrimitive(p));val completion=Decoupled(new NativeGeometryVertexCompletion)
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,slots))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,slots)))
    val cancel=Input(Bool());val busy=Output(Bool());val idle=Output(Bool())
    val launchObserved=Output(Valid(Bool()));val owners=Output(UInt(2.W))
    val cancelObserved=Output(UInt(2.W))
    val trapObserved=Output(Valid(new TaggedGraphicsTrap(config,4,2)))
  })
  private val source=Module(new NativeGeometryVertexPipeline(config,p,SharedCuShaderConfig(2,taskIdWidth=4)))
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
  io.busy:=source.io.busy;io.idle:=system.io.vmContextIdle && !source.io.busy
  io.launchObserved.valid:=source.io.shared.launch.fire
  io.launchObserved.bits:=source.io.shared.geometryTask.get.valid;io.owners:=source.io.shared.owned
  io.cancelObserved:=source.io.shared.geometryCancel.get.asUInt
  io.trapObserved.valid:=source.io.shared.trap.fire;io.trapObserved.bits:=source.io.shared.trap.bits
}

class NativeGeometryVertexPipelineSpec extends AnyFlatSpec {
  private def zero(x: Data): Unit=x match {
    case r: Record=>r.elements.values.foreach(zero);case v: Vec[_]=>v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U);case _=>()
  }
  private def ids(x: GraphicsWorkIdentity): Unit={x.context.poke(7.U);x.pass.poke(8.U);x.generation.poke(9.U)}
  private def addi(rd: Int,rs: Int,n: Int): BigInt=(BigInt(n&4095)<<20)|(BigInt(rs)<<15)|(BigInt(rd)<<7)|0x13
  private def vm(base: Int,v: Int,write: Boolean): BigInt=
    (BigInt(1)<<25)|(BigInt(base)<<15)|(BigInt(6)<<12)|(BigInt(v)<<7)|(if(write)0x27 else 7)
  private def geometry(fn: Int,rd: Int=0,rs1: Int=0,rs2: Int=0): BigInt=
    (BigInt(fn)<<25)|(BigInt(rs2)<<20)|(BigInt(rs1)<<15)|(BigInt(rd)<<7)|0x0b
  // Physical capacity is eight. Copy all 64 actual attribute components to
  // the full 16-varying output, plus the first attribute to clip position.
  private val vertexProgram: Vector[BigInt]={
    val code=mutable.ArrayBuffer[BigInt](BigInt("00241393",16),BigInt("007083b3",16),BigInt("c1027057",16),
      addi(6,7,1024),addi(6,6,1120))
    for(source<-(0 until 4)++(0 until 64))code++=Seq(addi(5,7,source*32),vm(5,1,false),vm(6,1,true),addi(6,6,32))
    (code:+BigInt("30500073",16)).toVector
  }
  private val geometryProgram=Vector[BigInt](0x00000013,addi(1,0,2),geometry(37,1,1),addi(1,1,-1),addi(2,0,63),
    geometry(32,3,1,2),addi(4,0,0),geometry(33,0,4,3),addi(1,0,11),addi(2,0,0),geometry(7,6,1,2),
    addi(4,0,1),geometry(33,0,4,6),addi(4,0,3),BigInt("3f8001b7",16),geometry(33,0,4,3),geometry(35),geometry(36),BigInt("30500073",16))
  it should "execute real full-width VS to ordinary or six-vertex GS including indirect draws and drained reuse" in {
    simulate(new NativeGeometryVertexHarness){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
      val memory=mutable.Map.empty[BigInt,BigInt]
      def put(address: BigInt,value: BigInt): Unit=for(b<-0 until 4)memory(address+b)=(value>>(b*8))&255
      vertexProgram.zipWithIndex.foreach{case(w,i)=>put(0x1000+4*i,w)}
      geometryProgram.zipWithIndex.foreach{case(w,i)=>put(0x900000+4*i,w)}
      for(v<-0 until 12;c<-0 until 64)put(0x20000+v*256+c*4,v*1024+c)
      Vector(238,5,4,3,2,1,0).zipWithIndex.foreach{case(v,i)=>memory(BigInt(0x21000+i))=BigInt(v)}
      for(a<-0x2fffc0 until 0x301140)memory(BigInt(a))=BigInt(0x5a)
      for((i,v)<-Seq[(Int,BigInt)](0->BigInt("47555202",16),1->64,2->2,3->7,4->8,5->9,
          6->3,7->2048,9->(BigInt(23)<<25),10->0xa01000,11->0xa01600))put(0xa00000+i*4,v)
      for((i,v)<-Seq[(Int,BigInt)](0->BigInt("47554202",16),1->64,2->2,3->7,4->8,5->9,
          6->23,7->8,8->0xa02000,9->0xa06000,11->0xa02000,12->0xa06000,14->1))put(0xa015c0+i*4,v)
      var ubo=BigInt("13579bdf",16);put(0xa02000,ubo)
      case class Reply(tag: BigInt,data: BigInt,due: Int,fault: Boolean,address: BigInt)
      val pending=mutable.ArrayBuffer.empty[Reply];var offered: Option[Reply]=None
      var heldRequest: Option[BigInt]=None;var heldOutput: Option[BigInt]=None
      var cycle=0;var requests=0;var outputs=0;var vsLaunches=0;var gsLaunches=0;var ownerMask=BigInt(0)
      var outputReady=false;var holdRoot=false;var rootFault=false;var expectedX=BigInt(0)
      var sourceFault=false;var holdRootResponse=false;var faultLaunchBaseline=0;var cancelMask=BigInt(0);var caseLabel="initial";var sourceFaultReplies=0
      val traps=mutable.ArrayBuffer.empty[(BigInt,BigInt,BigInt,BigInt,BigInt)]
      def tick(): Unit={
        val request=d.io.memoryRequest
        val block=holdRoot && request.valid.peek().litToBoolean && request.bits.address.peek().litValue==0xa00000
        request.ready.poke((!block && cycle%3!=0).B)
        if(offered.isEmpty){val ready=pending.indexWhere(r=>r.due<=cycle && !(sourceFault && r.fault && gsLaunches<=faultLaunchBaseline) &&
          !(holdRootResponse && r.address==0xa00000));if(ready>=0)offered=Some(pending.remove(ready))}
        d.io.memoryResponse.valid.poke(offered.nonEmpty.B)
        offered.foreach{r=>d.io.memoryResponse.bits.transactionId.poke(r.tag.U);d.io.memoryResponse.bits.readData.poke(r.data.U)
          d.io.memoryResponse.bits.fault.poke(r.fault.B)}
        if(offered.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean){
          if(sourceFault && offered.get.fault){
            assert(gsLaunches>faultLaunchBaseline,"source fault preceded its physical GS launch");sourceFaultReplies+=1
          }
          offered=None
        }
        if(request.valid.peek().litToBoolean){
          val bits=request.bits.peek().litValue;heldRequest.foreach(x=>assert(x==bits,"held VS/GS physical request changed"));heldRequest=Some(bits)
          if(request.ready.peek().litToBoolean){
            val r=request.bits;val address=r.address.peek().litValue;val failed=(rootFault && address==0xa00000) || (sourceFault && address==0x20900)
            if(r.isWrite.peek().litToBoolean && !failed){val mask=r.byteMask.peek().litValue;val data=r.writeData.peek().litValue
              for(b<-0 until 64 if mask.testBit(b))memory(address+b)=(data>>(b*8))&255}
            val data=(0 until 64).map(b=>memory.getOrElse(address+b,BigInt(0))<<(8*b)).sum
            requests+=1;pending+=Reply(r.transactionId.peek().litValue,data,cycle+5+requests%4,failed,address);heldRequest=None
          }
        }else assert(heldRequest.isEmpty,"held VS/GS physical request withdrawn")
        d.io.out.ready.poke(outputReady.B)
        if(d.io.out.valid.peek().litToBoolean){val q=d.io.out.bits;val bits=q.peek().litValue
          heldOutput.foreach(x=>assert(x==bits,"held VS/GS output changed"))
          q.inputPrimitiveId.expect(0.U);q.invocationId.expect(outputs.U);q.primitive.sequence.expect((10+outputs).U)
          q.primitive.vertexCount.expect(1.U);q.primitive.vertices(0).position(0).expect(expectedX.U)
          q.primitive.vertices(0).position(1).expect(ubo.U);q.primitive.vertices(0).position(2).expect(0.U)
          q.primitive.vertices(0).position(3).expect("h3f800000".U)
          for(c<-0 until 64)q.primitive.vertices(0).varyings(c/4)(c%4).expect(Vector(expectedX,ubo,BigInt(0),BigInt("3f800000",16))(c%4).U)
          if(outputReady){outputs+=1;heldOutput=None}else heldOutput=Some(bits)
        }else assert(heldOutput.isEmpty,"held VS/GS output withdrawn")
        if(d.io.launchObserved.valid.peek().litToBoolean){if(d.io.launchObserved.bits.peek().litToBoolean)gsLaunches+=1 else vsLaunches+=1}
        if(d.io.trapObserved.valid.peek().litToBoolean){val t=d.io.trapObserved.bits;val e=t.event
          traps+=((t.taskId.peek().litValue,t.cu.peek().litValue,e.pc.peek().litValue,e.cause.peek().litValue,e.tval.peek().litValue))}
        cancelMask|=d.io.cancelObserved.peek().litValue
        ownerMask|=d.io.owners.peek().litValue;d.clock.step();cycle+=1
      }
      def until(test: => Boolean): Unit={var n=0;while(!test && n<200000){tick();n+=1}
        assert(test,s"real VS/GS stalled case=$caseLabel pending=${pending.size} offered=$offered held=$heldRequest cancel=$cancelMask cycle=$cycle outputs=$outputs VS=$vsLaunches GS=$gsLaunches requests=$requests completion=${d.io.completion.valid.peek().litValue}/${d.io.completion.bits.peek().litValue}")}
      def view(x: VertexInputView,base: Int,bytes: Int): Unit={x.base.poke(base.U);x.limit.poke((base+bytes).U)
        x.allocationBase.poke((base& ~63).U);x.allocationLimit.poke(((base+bytes+63)& ~63).U)}
      def task(vertices: Int,mode: Int,invocations: Int,indirect: Int=0,sourceCount: Int=0): Unit={
        caseLabel=s"vertices=$vertices mode=$mode invocations=$invocations indirect=$indirect count=$sourceCount rootFault=$rootFault holdRoot=$holdRoot sourceFault=$sourceFault holdRootResponse=$holdRootResponse"
        zero(d.io.task.bits);outputs=0;cancelMask=0;traps.clear();val t=d.io.task.bits;val v=t.draw.vertex;val g=t.program
        t.commandId.poke(4.U);t.taskId.poke(19.U);t.firstSequence.poke(10.U);t.draw.topology.poke(mode.U)
        v.version.poke(1.U);v.shaderPc.poke(0x1000.U);v.attributeSlots.poke(16.U);v.varyingSlots.poke(16.U)
        v.varyingMask.poke(((BigInt(1)<<64)-1).U);v.input.version.poke(1.U);ids(v.input.identity)
        v.input.count.poke((if(indirect==0){if(sourceCount==0)vertices else sourceCount}else 0).U);v.input.instances.poke(1.U);v.input.baseInstance.poke(2.U)
        view(v.input.bindings(0),0x20000,3072);v.input.bindings(0).stride.poke(256.U)
        for(i<-0 until 16){val a=v.input.attributes(i);a.enabled.poke(true.B);a.components.poke(4.U)
          a.format.poke(VertexInputFormat.uint32.U);a.offset.poke((i*16).U)}
        view(v.scratch,0x300000,4320);v.sampler.texWidth.poke(1.U);v.sampler.texHeight.poke(1.U)
        if(indirect!=0){val parameter=if(indirect==1)0x28000 else 0x28100
          t.draw.indirect.mode.poke(indirect.U);view(t.draw.indirect.view,parameter,if(indirect==1)16 else 20)
          for((i,w)<-Vector(vertices,1,if(indirect==2)1 else 0,0,0).zipWithIndex.map{case(w,i)=>(i,w)})put(parameter+i*4,w)
          if(indirect==2){v.input.indexFormat.poke(1.U);view(v.input.indices,0x21000,7)}}
        ids(g.identity);g.inputVertices.poke(vertices.U);g.inputComponents.poke(64.U);g.invocations.poke(invocations.U)
        g.outputTopology.poke(0.U);g.maxVertices.poke(256.U);g.outputComponents.poke(4.U)
        g.vertexVaryingSlots.poke(16.U);g.fragmentVaryingSlots.poke(16.U);g.programVersion.poke(1.U)
        g.pc.poke(0x900004.U);view(g.code,0x900000,geometryProgram.size*4)
        g.uniforms.version.poke(2.U);g.uniforms.address.poke(0xa00000.U)
        for(c<-0 until 64)g.inputSources(c).poke((4+c).U)
        for(c<-0 until 68){g.outputSources(c).valid.poke(true.B);g.outputSources(c).bits.poke((c%4).U)}
        expectedX=if(indirect==2)BigInt(63)else BigInt((vertices-1)*1024+63)
      }
      def submit(): Unit={d.io.task.valid.poke(true.B);until(d.io.task.ready.peek().litToBoolean);tick()
        d.io.task.valid.poke(false.B);zero(d.io.task.bits)}
      def finish(status: Int,count: Int): Unit={until(d.io.completion.valid.peek().litToBoolean)
        val r=d.io.completion.bits;r.status.expect(status.U);r.emittedPrimitives.expect(count.U);assert(outputs==count)
        if(status==0){r.submittedPrimitives.expect(1.U);r.completedInvocations.expect(count.U);r.nextSequence.expect((10+count).U)}
        assert(pending.isEmpty && offered.isEmpty && heldRequest.isEmpty)
        val bits=r.peek().litValue;for(_<-0 until 7){tick();assert(r.peek().litValue==bits);d.io.task.ready.expect(false.B)}
        d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B);until(d.io.idle.peek().litToBoolean)
      }
      def run(vertices: Int,mode: Int,invocations: Int,indirect: Int=0): Unit={task(vertices,mode,invocations,indirect);submit()
        while(outputs<invocations){until(d.io.out.valid.peek().litToBoolean);for(_<-0 until 5)tick();outputReady=true;tick();outputReady=false}
        finish(0,invocations);assert(traps.isEmpty,s"healthy VS/GS trapped: $traps")}
      task(6,12,1);d.io.task.bits.program.outputComponents.poke(5.U)
      val oldRequests=requests;submit();finish(1,0);assert(vsLaunches==0 && gsLaunches==0 && requests==oldRequests)
      run(6,12,32);run(3,4,1);run(6,12,1,1);run(6,12,1,2)
      rootFault=true;task(6,12,1);submit();finish(5,0)
      assert(traps.size==1 && traps.head._1==0 && traps.head._3==0x900028 && traps.head._4==5,
        s"GS memory fault lost its real pool task/PC/cause: $traps")
      rootFault=false
      holdRoot=true;task(6,12,1);submit()
      until(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.bits.address.peek().litValue==0xa00000)
      val held=d.io.memoryRequest.bits.peek().litValue;d.io.cancel.poke(true.B);tick();d.io.cancel.poke(false.B)
      for(_<-0 until 7){assert(d.io.memoryRequest.bits.peek().litValue==held);tick();d.io.completion.valid.expect(false.B)}
      holdRoot=false;finish(4,0)
      assert(traps.size==1 && traps.head._1==0 && traps.head._3==0x900028 && traps.head._4==5,
        s"cancelled GS uniform lost its real pool task/PC/cause: $traps")
      sourceFault=true;holdRootResponse=true;faultLaunchBaseline=gsLaunches
      task(6,12,1,sourceCount=12);submit()
      until(cancelMask!=0);holdRootResponse=false
      until(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.faultStage.expect(2.U)
      d.io.completion.bits.source.status.expect(3.U)
      d.io.completion.bits.source.source.status.expect(3.U)
      d.io.completion.bits.source.source.input.status.expect(3.U)
      finish(3,0)
      assert(cancelMask!=0 && sourceFaultReplies==1,"source memory fault did not cancel its actual live GS owner")
      sourceFault=false
      ubo=BigInt("2468ace0",16);put(0xa02000,ubo);run(3,4,1)
      for(v<-0 until 12;c<-0 until 64;b<-0 until 4)assert(memory(BigInt(0x20000+v*256+c*4+b))==((BigInt(v*1024+c)>>(8*b))&255))
      assert(memory(BigInt(0x2fffff))==0x5a && memory(BigInt(0x3010e0))==0x5a)
      assert(vsLaunches==8 && gsLaunches==39)
      info(s"NATIVE_VS_GS real2CU4x2 attributes16 varyings16 input6x64 GS32max256 ownerMask=$ownerMask noResetReuse=true")
    }
  }
}
