package opengpu.system

import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.graphics._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class NativeGeometrySharedCuHarness(config: GpuConfig,p: VertexShaderStageConfig) extends Module {
  val gpu=Module(new GpuSystem(config,numComputeUnits=2,commandIdWidth=4,transactionsPerCu=4,
    enableGraphicsKernels=true,geometryAvailable=true,instructionCacheSets=4,instructionCacheWays=1,
    vectorCacheSets=4,vectorCacheWays=1))
  val client=Module(new NativeGeometryPrimitivePipeline(config,p,4,2))
  val io=IO(new Bundle {
    val system=chiselTypeOf(gpu.io)
    val task=Flipped(Decoupled(new NativeGeometryPrimitivePipelineTask(p,4)))
    val primitive=Decoupled(new GeometryMappedPrimitive(p))
    val completion=Decoupled(new GeometryPrimitivePipelineResult)
    val uniformRequest=chiselTypeOf(client.io.memoryRequest)
    val uniformResponse=chiselTypeOf(client.io.memoryResponse)
    val cancel=Input(Bool())
    val trapObserved=Output(Valid(chiselTypeOf(client.io.shared.trap.bits.event)))
  })
  io.system<>gpu.io;client.io.task<>io.task;io.primitive<>client.io.primitive
  io.completion<>client.io.completion;client.io.cancel:=io.cancel
  client.io.shared<>gpu.io.graphicsKernels.get
  io.trapObserved.valid:=client.io.shared.trap.fire;io.trapObserved.bits:=client.io.shared.trap.bits.event
  io.uniformRequest<>client.io.memoryRequest;client.io.memoryResponse<>io.uniformResponse
  client.io.invalidate.valid:=false.B;client.io.invalidate.bits:=0.U.asTypeOf(client.io.invalidate.bits)
  client.io.invalidateDone.ready:=true.B
}

class NativeGeometrySharedCuSpec extends AnyFlatSpec {
  private val config=GpuConfig(lanes=2,warps=2,l2Sets=8,l2Ways=2)
  private val p=VertexShaderStageConfig()
  private def zero(x: Data): Unit=x match {
    case r: Record=>r.elements.values.foreach(zero);case v: Vec[_]=>v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S);case _=>()
  }
  private def ids(x: GraphicsWorkIdentity): Unit={x.context.poke(7.U);x.pass.poke(8.U);x.generation.poke(9.U)}
  private def geometry(fn: Int,rd: Int=0,rs1: Int=0,rs2: Int=0): BigInt=
    (BigInt(fn)<<25)|(BigInt(rs2)<<20)|(BigInt(rs1)<<15)|(BigInt(rd)<<7)|0x0b
  it should "execute six-input GS and GUR2 loads on real shared CUs with held output fault cancel and reuse" in {
    simulate(new NativeGeometrySharedCuHarness(config,p)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
      val sys=d.io.system;sys.graphicsDrained.poke(true.B);sys.graphicsShaderL1Invalidate.ready.poke(true.B)
      // NOP prefix is retained; the accepted entry starts at the next word.
      // Read input vertex 5/component 63 and UBO block 11 into position x/y;
      // assign w=1, emit a point, then end the physical scalar invocation.
      val code=Vector[BigInt](0x00000013,0x00500093,0x03f00113,geometry(32,3,1,2),
        0x00000213,geometry(33,0,4,3),0x00b00093,0x00000113,geometry(7,6,1,2),
        0x00100213,geometry(33,0,4,6),0x00300213,0x3f8001b7,geometry(33,0,4,3),
        geometry(35),geometry(36),0x30500073)
      var ubo=BigInt("13579bdf",16);var cycle=0;var outputs=0;var outputReady=false
      var holdUniform=false;var uniformFault=false;var cancelSent=false
      var heldOutput: Option[BigInt]=None;var heldUniform: Option[BigInt]=None
      val instructionReplies=mutable.Queue.empty[(BigInt,BigInt,Int)]
      var uniformReply: Option[(BigInt,Int)]=None
      val instructionAddresses=mutable.Set.empty[BigInt];val uniformAddresses=mutable.ArrayBuffer.empty[BigInt]
      val traps=mutable.ArrayBuffer.empty[(BigInt,BigInt,BigInt)]
      var owners=BigInt(0)
      def line(address: BigInt): BigInt={
        val w=Array.fill[BigInt](16)(0)
        if(address==0xa00000){
          for((i,v)<-Seq[(Int,BigInt)](0->BigInt("47555202",16),1->64,2->2,3->7,4->8,5->9,
              6->3,7->2048,9->(BigInt(23)<<25),10->0xa01000,11->0xa01600))w(i)=v
        }else if(address==0xa015c0){
          for((i,v)<-Seq[(Int,BigInt)](0->BigInt("47554202",16),1->64,2->2,3->7,4->8,5->9,
              6->23,7->8,8->0xa02000,9->0xa06000,11->0xa02000,12->0xa06000,14->1))w(i)=v
        }else{assert(address==0xa02000);w(0)=ubo}
        w.zipWithIndex.map{case(v,i)=>v<<(32*i)}.sum
      }
      def tick(): Unit={
        sys.memoryRequest.ready.poke((cycle%3!=0).B)
        val reply=instructionReplies.headOption.filter(_._3<=cycle)
        sys.memoryResponse.valid.poke(reply.nonEmpty.B)
        reply.foreach{case(tag,address,_)=>sys.memoryResponse.bits.transactionId.poke(tag.U)
          val first=((address-0x900000)/4).toInt
          assert(first>=0 && first<32,s"unexpected GS instruction memory address $address")
          sys.memoryResponse.bits.readData.poke((0 until 16).map(i=>code.lift(first+i).getOrElse(BigInt(0))<<(32*i)).sum.U)
          sys.memoryResponse.bits.fault.poke(false.B)}
        if(reply.nonEmpty && sys.memoryResponse.ready.peek().litToBoolean)instructionReplies.dequeue()
        if(sys.memoryRequest.valid.peek().litToBoolean && sys.memoryRequest.ready.peek().litToBoolean){
          val r=sys.memoryRequest.bits;r.isWrite.expect(false.B)
          val a=r.address.peek().litValue;instructionAddresses+=a
          instructionReplies.enqueue((r.transactionId.peek().litValue,a,cycle+5))
        }
        d.io.uniformRequest.ready.poke((!holdUniform && cycle%3!=0).B)
        val ur=uniformReply.filter(_._2<=cycle)
        d.io.uniformResponse.valid.poke(ur.nonEmpty.B)
        ur.foreach{case(a,_)=>d.io.uniformResponse.bits.transactionId.poke(0.U)
          d.io.uniformResponse.bits.readData.poke(line(a).U);d.io.uniformResponse.bits.fault.poke(uniformFault.B)}
        if(ur.nonEmpty && d.io.uniformResponse.ready.peek().litToBoolean)uniformReply=None
        if(d.io.uniformRequest.valid.peek().litToBoolean){val bits=d.io.uniformRequest.bits.peek().litValue
          heldUniform.foreach(x=>assert(x==bits,"held actual GS uniform request changed"));heldUniform=Some(bits)
          if(d.io.uniformRequest.ready.peek().litToBoolean){assert(uniformReply.isEmpty)
            val a=d.io.uniformRequest.bits.address.peek().litValue;uniformAddresses+=a;uniformReply=Some((a,cycle+7));heldUniform=None}
        }else assert(heldUniform.isEmpty,"held actual GS uniform request withdrawn")
        d.io.primitive.ready.poke(outputReady.B)
        if(d.io.primitive.valid.peek().litToBoolean){
          val q=d.io.primitive.bits;val bits=q.peek().litValue;heldOutput.foreach(x=>assert(x==bits,"held actual GS output changed"))
          q.inputPrimitiveId.expect(27.U);q.invocationId.expect(outputs.U)
          q.primitive.sequence.expect((10+outputs).U);q.primitive.instanceId.expect(3.U)
          q.primitive.vertexCount.expect(1.U);q.primitive.vertices(0).position(0).expect(5183.U)
          q.primitive.vertices(0).position(1).expect(ubo.U);q.primitive.vertices(0).position(2).expect(0.U)
          q.primitive.vertices(0).position(3).expect("h3f800000".U)
          if(outputReady){outputs+=1;heldOutput=None}else heldOutput=Some(bits)
        }else assert(heldOutput.isEmpty,"held actual GS output withdrawn")
        owners|=sys.graphicsKernels.get.owned.peek().litValue
        val trap=d.io.trapObserved
        if(trap.valid.peek().litToBoolean){val e=trap.bits
          traps+=((e.pc.peek().litValue,e.cause.peek().litValue,e.tval.peek().litValue))}
        d.clock.step();cycle+=1
      }
      def until(test: => Boolean): Unit={var n=0;while(!test && n<200000){tick();n+=1};assert(test,
        s"actual shared GS stalled cycle=$cycle outputs=$outputs completion=${d.io.completion.valid.peek().litValue}/${d.io.completion.bits.peek().litValue} owners=$owners instruction=$instructionAddresses uniform=$uniformAddresses traps=$traps")}
      def submit(invocations: Int): Unit={
        outputs=0;uniformAddresses.clear();traps.clear();zero(d.io.task.bits)
        val t=d.io.task.bits;val q=t.input.primitive;val g=t.input.program
        t.commandId.poke(4.U);t.firstSequence.poke(10.U);t.input.taskId.poke(19.U)
        ids(q.identity);q.primitiveId.poke(27.U);q.instanceId.poke(3.U);q.baseInstance.poke(2.U)
        q.topology.poke(12.U);q.kind.poke(2.U);q.vertexCount.poke(6.U);q.varyingSlots.poke(16.U)
        for(v<-0 until 6){ids(q.vertices(v).identity);q.vertices(v).instanceId.poke(3.U);q.vertices(v).baseInstance.poke(2.U)
          for(c<-0 until 64)q.vertices(v).varyings(c/4)(c%4).poke((v*1024+c).U)}
        ids(g.identity);g.inputVertices.poke(6.U);g.inputComponents.poke(64.U);g.invocations.poke(invocations.U)
        g.outputTopology.poke(0.U);g.maxVertices.poke(256.U);g.outputComponents.poke(4.U)
        g.vertexVaryingSlots.poke(16.U);g.fragmentVaryingSlots.poke(16.U);g.programVersion.poke(1.U)
        g.pc.poke(0x900004.U);g.code.base.poke(0x900000.U);g.code.limit.poke((0x900000+code.size*4).U)
        g.code.allocationBase.poke(0x900000.U);g.code.allocationLimit.poke(0x900080.U)
        g.uniforms.version.poke(2.U);g.uniforms.address.poke(0xa00000.U)
        for(c<-0 until 64)g.inputSources(c).poke((4+c).U)
        for(c<-0 until 68){g.outputSources(c).valid.poke(true.B);g.outputSources(c).bits.poke((c%4).U)}
        d.io.task.valid.poke(true.B);until(d.io.task.ready.peek().litToBoolean);tick();d.io.task.valid.poke(false.B);zero(d.io.task.bits)
      }
      def finish(status: Int,count: Int): Unit={
        until(d.io.completion.valid.peek().litToBoolean)
        assert(d.io.completion.bits.status.peek().litValue==status,
          s"actual GS status expected=$status actual=${d.io.completion.bits.status.peek().litValue} cycle=$cycle outputs=$outputs uniform=$uniformAddresses traps=$traps")
        d.io.completion.bits.producedPrimitives.expect(count.U)
        if(status==0)d.io.completion.bits.completedInvocations.expect(count.U)
        assert(outputs==count && instructionReplies.isEmpty && uniformReply.isEmpty && heldUniform.isEmpty)
        val held=d.io.completion.bits.peek().litValue
        for(_<-0 until 7){tick();assert(d.io.completion.bits.peek().litValue==held);d.io.task.ready.expect(false.B)}
        d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B)
      }
      submit(32)
      while(outputs<32){until(d.io.primitive.valid.peek().litToBoolean);for(_<-0 until 5)tick();outputReady=true;tick();outputReady=false}
      finish(0,32);assert(uniformAddresses.size==96 && instructionAddresses.nonEmpty && owners!=0)
      // Real CU fault and cancellation reuse share the same physical GPU and
      // caches; there is no reset or CPU event/completion producer between jobs.
      uniformFault=true;submit(1);finish(3,0)
      assert(traps.size==1 && traps.head._1==0x900020 && traps.head._2==5,
        s"actual failed GS uniform did not retire through its load fault: $traps")
      uniformFault=false
      holdUniform=true;submit(1);until(d.io.uniformRequest.valid.peek().litToBoolean)
      val held=d.io.uniformRequest.bits.peek().litValue;d.io.cancel.poke(true.B);tick();d.io.cancel.poke(false.B);cancelSent=true
      for(_<-0 until 7){assert(d.io.uniformRequest.bits.peek().litValue==held);tick();d.io.completion.valid.expect(false.B)}
      holdUniform=false;finish(2,0)
      assert(traps.size==1 && traps.head._1==0x900020 && traps.head._2==5,
        s"actual cancelled GS uniform did not drain its tagged load fault: $traps")
      ubo=BigInt("2468ace0",16);submit(2)
      while(outputs<2){until(d.io.primitive.valid.peek().litToBoolean);outputReady=true;tick();outputReady=false}
      finish(0,2);assert(cancelSent)
      info(s"NATIVE_GS_SHARED_CU actualCUs=2 lanes=2 warps=2 ownerMask=$owners inputVertices=6 inputComponents=64 invocations=32 maxVertices=256 stage3Uniform=true noResetReuse=true")
    }
  }
}
