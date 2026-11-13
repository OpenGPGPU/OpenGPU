package opengpu.system

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.graphics._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class NativeGeometryPrimitivePipelineSpec extends AnyFlatSpec {
  private val config=GpuConfig(lanes=2,warps=2)
  private val p=VertexShaderStageConfig()
  private def zero(x: Data): Unit=x match {
    case r: Record=>r.elements.values.foreach(zero);case v: Vec[_]=>v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U);case _=>()
  }
  private def ids(x: GraphicsWorkIdentity): Unit={x.context.poke(7.U);x.pass.poke(8.U);x.generation.poke(9.U)}
  it should "own queued physical launches stage three UBOs events and cancellation through tagged drain" in {
    // The external physical CU port is an explicit protocol peer here. Actual
    // shader execution and native VS/clip/bin integration require other gates.
    simulate(new NativeGeometryPrimitivePipeline(config,p,4,2)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
      val shared=d.io.shared
      shared.invalidate.ready.poke(true.B)
      var cycle=0;var memoryReady=true;var blockReply=false;var badRoot=false
      var pending: Option[(BigInt,Int)]=None;var heldMemory: Option[BigInt]=None
      var writebacks=0;var lastWrite=false;var lastValue=BigInt(0);var outputs=0
      var heldOutput: Option[BigInt]=None;var outputReady=false
      val addresses=mutable.ArrayBuffer.empty[BigInt]
      def line(address: BigInt): BigInt={
        val w=Array.fill[BigInt](16)(0)
        if(address==0xa00000){
          for((i,v)<-Seq[(Int,BigInt)](0->BigInt("47555202",16),1->64,2->2,3->7,4->8,5->9,
              6->(if(badRoot)1 else 3),7->2048,9->(BigInt(23)<<25),10->0xa01000,11->0xa01600))w(i)=v
        }else if(address==0xa015c0){
          for((i,v)<-Seq[(Int,BigInt)](0->BigInt("47554202",16),1->64,2->2,3->7,4->8,5->9,
              6->23,7->8,8->0xa02000,9->0xa06000,11->0xa02000,12->0xa06000,14->1))w(i)=v
        }else{assert(address==0xa05fc0);w(15)=BigInt("fedcba98",16)}
        w.zipWithIndex.map{case(v,i)=>v<<(32*i)}.sum
      }
      def tick(): Unit={
        d.io.memoryRequest.ready.poke(memoryReady.B)
        val reply=pending.filter(_._2<=cycle).filter(_=> !blockReply)
        d.io.memoryResponse.valid.poke(reply.nonEmpty.B)
        reply.foreach{case(a,_)=>d.io.memoryResponse.bits.transactionId.poke(0.U);d.io.memoryResponse.bits.readData.poke(line(a).U);d.io.memoryResponse.bits.fault.poke(false.B)}
        if(reply.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean)pending=None
        if(d.io.memoryRequest.valid.peek().litToBoolean){
          val bits=d.io.memoryRequest.bits.peek().litValue;heldMemory.foreach(x=>assert(x==bits,"held uniform line changed"));heldMemory=Some(bits)
          if(memoryReady){assert(pending.isEmpty);val a=d.io.memoryRequest.bits.address.peek().litValue
            d.io.memoryRequest.bits.isWrite.expect(false.B);d.io.memoryRequest.bits.sizeLog2.expect(6.U)
            pending=Some((a,cycle+5));addresses+=a;heldMemory=None}
        }else assert(heldMemory.isEmpty)
        for(cu<-0 until 2){val port=shared.texture(cu)
          if(port.scalarWriteback.valid.peek().litToBoolean && port.scalarWriteback.ready.peek().litToBoolean){
            writebacks+=1;lastWrite=port.scalarWriteback.bits.writeRd.peek().litToBoolean;lastValue=port.scalarWriteback.bits.data.peek().litValue
            port.scalarWriteback.bits.rd.expect(5.U);port.scalarWriteback.bits.nextPc.expect(0x900008.U)
          }
        }
        d.io.primitive.ready.poke(outputReady.B)
        if(d.io.primitive.valid.peek().litToBoolean){val bits=d.io.primitive.bits.peek().litValue
          heldOutput.foreach(x=>assert(x==bits,"held mapped GS output changed"))
          if(outputReady){outputs+=1;heldOutput=None}else heldOutput=Some(bits)
        }else assert(heldOutput.isEmpty)
        d.clock.step();cycle+=1
      }
      def until(test: => Boolean): Unit={var n=0;while(!test && n<4000){tick();n+=1};assert(test,s"native GS peer stalled cycle=$cycle")}
      def submit(invocations: Int=1): Unit={
        zero(d.io.task.bits);val t=d.io.task.bits;val q=t.input.primitive;val g=t.input.program
        t.commandId.poke(4.U);t.firstSequence.poke(10.U);t.input.taskId.poke(19.U)
        ids(q.identity);q.primitiveId.poke(27.U);q.instanceId.poke(3.U);q.baseInstance.poke(2.U)
        q.topology.poke(12.U);q.kind.poke(2.U);q.vertexCount.poke(6.U);q.varyingSlots.poke(16.U)
        for(v<-0 until 6){ids(q.vertices(v).identity);q.vertices(v).instanceId.poke(3.U);q.vertices(v).baseInstance.poke(2.U)
          for(c<-0 until 64)q.vertices(v).varyings(c/4)(c%4).poke((v*1024+c).U)}
        ids(g.identity);g.inputVertices.poke(6.U);g.inputComponents.poke(64.U);g.invocations.poke(invocations.U)
        g.outputTopology.poke(0.U);g.maxVertices.poke(256.U);g.outputComponents.poke(4.U)
        g.vertexVaryingSlots.poke(16.U);g.fragmentVaryingSlots.poke(16.U);g.programVersion.poke(1.U)
        g.pc.poke(0x900004.U);g.code.base.poke(0x900000.U);g.code.limit.poke(0x900800.U)
        g.code.allocationBase.poke(0x900000.U);g.code.allocationLimit.poke(0x900800.U)
        g.uniforms.version.poke(2.U);g.uniforms.address.poke(0xa00000.U)
        for(c<-0 until 64)g.inputSources(c).poke((4+c).U)
        for(c<-0 until 68){g.outputSources(c).valid.poke(true.B);g.outputSources(c).bits.poke((c%4).U)}
        writebacks=0;outputs=0;addresses.clear()
        d.io.task.valid.poke(true.B);d.io.task.ready.expect(true.B);tick();d.io.task.valid.poke(false.B);zero(d.io.task.bits)
      }
      def launch(index: Int,cu: Int): Unit={
        until(shared.launch.valid.peek().litToBoolean)
        shared.launch.bits.commandId.expect(4.U);shared.launch.bits.launch.kernelPc.expect(0x900004.U)
        shared.nativeResources.expect(false.B);shared.uniformResources.expect(true.B)
        shared.geometryTask.get.valid.expect(true.B);val g=shared.geometryTask.get.bits
        g.invocationId.expect(index.U);g.inputVertices.expect(6.U);g.inputComponents.expect(64.U);g.maxVertices.expect(256.U)
        for(v<-0 until 6;c<-0 until 64)g.vertices(v).components(c).expect((v*1024+c).U)
        val held=shared.launch.bits.peek().litValue;val geometry=g.peek().litValue
        for(_<-0 until 3){tick();assert(shared.launch.bits.peek().litValue==held && g.peek().litValue==geometry)}
        shared.launch.ready.poke(true.B);tick();shared.launch.ready.poke(false.B)
        shared.owned.poke((1<<cu).U);shared.taskIds(cu).poke(4.U)
      }
      def issueUniform(cu: Int): Int={
        val old=writebacks;val in=shared.texture(cu).scalar
        zero(in.bits);in.bits.decode.instruction.poke(((BigInt(7)<<25)|(BigInt(5)<<7)|0x0b).U)
        in.bits.decode.pc.poke(0x900004.U);in.bits.decode.activeMask.poke(1.U);in.bits.decode.warpId.poke(0.U)
        in.bits.rs1Data.poke(11.U);in.bits.rs2Data.poke(16380.U)
        in.valid.poke(true.B);until(in.ready.peek().litToBoolean);tick();in.valid.poke(false.B)
        old
      }
      def uniform(cu: Int): Unit={val old=issueUniform(cu);until(writebacks==old+1)}
      for(cu<-0 until 2)shared.texture(cu).scalarWriteback.ready.poke(true.B)
      def emit(cu: Int,value: Int): Unit={val e=shared.geometryEvent.get(cu)
        zero(e.bits);e.bits.operation.poke(0.U);for(c<-0 until 4)e.bits.vertex.components(c).poke((value+c).U)
        e.valid.poke(true.B);until(e.ready.peek().litToBoolean);tick();e.valid.poke(false.B)
      }
      def physicalCompletion(ok: Boolean): Unit={shared.completion.bits.commandId.poke(4.U);shared.completion.bits.success.poke(ok.B)
        shared.completion.valid.poke(true.B);until(shared.completion.ready.peek().litToBoolean);tick()
        shared.completion.valid.poke(false.B);shared.owned.poke(0.U)
      }
      def finish(status: Int,invocations: Int,produced: Int): Unit={
        until(d.io.completion.valid.peek().litToBoolean);val r=d.io.completion.bits
        r.status.expect(status.U);r.completedInvocations.expect(invocations.U);r.producedPrimitives.expect(produced.U)
        r.nextSequence.expect((10+produced).U);assert(pending.isEmpty && heldMemory.isEmpty)
        val held=r.peek().litValue;for(_<-0 until 5){tick();assert(r.peek().litValue==held);d.io.task.ready.expect(false.B)}
        d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B);d.io.busy.expect(false.B)
      }
      submit(32)
      for(i<-0 until 32){val cu=i%2;launch(i,cu);uniform(cu);assert(lastWrite && lastValue==BigInt("fedcba98",16))
        emit(cu,100+i);physicalCompletion(true);until(d.io.primitive.valid.peek().litToBoolean)
        val q=d.io.primitive.bits;q.inputPrimitiveId.expect(27.U);q.invocationId.expect(i.U)
        q.primitive.sequence.expect((10+i).U);q.primitive.instanceId.expect(3.U)
        for(c<-0 until 4)q.primitive.vertices(0).position(c).expect((100+i+c).U)
        for(c<-0 until 64)q.primitive.vertices(0).varyings(c/4)(c%4).expect((100+i+c%4).U)
        for(_<-0 until 3)tick();outputReady=true;tick();outputReady=false
      }
      finish(0,32,32);assert(addresses.size==96)
      // Cancel an already-held pool offer. The paired physical task may not
      // disappear; it is accepted and then receives real failed completion.
      submit();until(shared.launch.valid.peek().litToBoolean)
      val held=shared.launch.bits.peek().litValue;val geom=shared.geometryTask.get.bits.peek().litValue
      d.io.cancel.poke(true.B);tick();d.io.cancel.poke(false.B)
      for(_<-0 until 7){shared.launch.valid.expect(true.B);assert(shared.launch.bits.peek().litValue==held)
        assert(shared.geometryTask.get.bits.peek().litValue==geom);tick();d.io.completion.valid.expect(false.B)}
      shared.launch.ready.poke(true.B);tick();shared.launch.ready.poke(false.B)
      shared.owned.poke(1.U);shared.taskIds(0).poke(4.U);shared.geometryCancel.get(0).expect(true.B)
      physicalCompletion(false);finish(2,0,0)
      // A bad stage root drains its accepted external request and writeback,
      // requests actual worker abort, then waits for physical completion.
      submit();launch(0,1);badRoot=true;uniform(1);assert(!lastWrite)
      shared.geometryCancel.get(1).expect(true.B);physicalCompletion(false);finish(3,0,0);badRoot=false
      // A single cancel pulse cannot withdraw a root line already offered to
      // the memory fabric. Cancellation is not converted into a worker fault.
      submit();launch(0,0);memoryReady=false;val before=issueUniform(0)
      until(d.io.memoryRequest.valid.peek().litToBoolean);val rootOffer=d.io.memoryRequest.bits.peek().litValue
      d.io.cancel.poke(true.B);tick();d.io.cancel.poke(false.B)
      for(_<-0 until 7){d.io.memoryRequest.valid.expect(true.B);assert(d.io.memoryRequest.bits.peek().litValue==rootOffer)
        tick();assert(writebacks==before);d.io.completion.valid.expect(false.B)}
      memoryReady=true;tick();until(writebacks==before+1);assert(!lastWrite)
      physicalCompletion(false);finish(2,0,0)
      submit();launch(0,1);shared.trap.bits.taskId.poke(4.U);shared.trap.valid.poke(true.B)
      shared.trap.ready.expect(true.B);tick();shared.trap.valid.poke(false.B)
      shared.geometryCancel.get(1).expect(true.B);physicalCompletion(false);finish(3,0,0)
      submit();launch(0,0);uniform(0);assert(lastWrite);physicalCompletion(true);finish(0,1,0)
    }
  }
}
