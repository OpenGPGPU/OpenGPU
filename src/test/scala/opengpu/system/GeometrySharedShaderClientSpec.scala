package opengpu.system

import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.core.memory.CacheLineInvalidate
import opengpu.graphics._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class GeometryRoutedGpuHarness(config:GpuConfig) extends Module {
  val gpu=Module(new GpuSystem(config,numComputeUnits=2,commandIdWidth=4,transactionsPerCu=4,
    enableGraphicsKernels=true,geometryAvailable=true,instructionCacheSets=4,instructionCacheWays=1,
    vectorCacheSets=4,vectorCacheWays=1))
  val router=Module(new SharedShaderTaskRouter(config,2,2,taskIdWidth=4,geometryAvailable=true))
  val client=Module(new GeometrySharedShaderClient(config,VertexShaderStageConfig(),4,2))
  val io=IO(new Bundle {
    val system=chiselTypeOf(gpu.io)
    val task=Flipped(Decoupled(new GeometrySharedShaderTask(config,VertexShaderStageConfig(),4)))
    val primitive=Decoupled(new GeometryMappedPrimitive(VertexShaderStageConfig()))
    val completion=Decoupled(new GeometryPrimitivePipelineResult)
    val cancel=Input(Bool())
    val peer=new SharedCuGraphicsPort(config,4,2,geometryAvailable=true)
    val texture=Vec(2,new SharedCuTexturePort(config))
    val trap=Decoupled(new TaggedGraphicsTrap(config,4,2))
    val invalidate=Flipped(Decoupled(new CacheLineInvalidate(config)))
    val invalidateDone=Decoupled(new CacheLineInvalidate(config))
    val poolOwned=Output(UInt(2.W))
    val geometryOwned=Output(UInt(2.W))
    val launch=Output(Valid(new Bundle {val tag=UInt(4.W);val native=Bool();val uniform=Bool()}))
  })
  io.system<>gpu.io;router.io.pool<>gpu.io.graphicsKernels.get
  router.io.clients(0)<>client.io.shared;io.peer<>router.io.clients(1)
  client.io.task<>io.task;io.primitive<>client.io.primitive;io.completion<>client.io.completion;client.io.cancel:=io.cancel
  io.texture<>client.io.texture;io.trap<>client.io.trap
  client.io.invalidate<>io.invalidate;io.invalidateDone<>client.io.invalidateDone
  io.poolOwned:=router.io.pool.owned;io.geometryOwned:=client.io.shared.owned
  io.launch.valid:=router.io.pool.launch.fire;io.launch.bits.tag:=router.io.pool.launch.bits.commandId
  io.launch.bits.native:=router.io.pool.nativeResources;io.launch.bits.uniform:=router.io.pool.uniformResources
}

class GeometrySharedShaderClientSpec extends AnyFlatSpec {
  private def zero(data:Data):Unit=data match {
    case r:Record=>r.elements.values.foreach(zero)
    case v:Vec[_]=>v.foreach(zero)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case x:SInt if DataMirror.directionOf(x)==ActualDirection.Input=>x.poke(0.S)
    case _=>()
  }
  private def geometry(fn:Int,rd:Int=0,rs1:Int=0,rs2:Int=0):BigInt =
    (BigInt(fn)<<25)|(BigInt(rs2)<<20)|(BigInt(rs1)<<15)|(BigInt(rd)<<7)|0x0b
  it should "route geometry and a peer with the same local tag to independent shared CUs" in {
    val config=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2)
    simulate(new GeometryRoutedGpuHarness(config)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
      val sys=d.io.system;sys.graphicsDrained.poke(true.B);sys.graphicsShaderL1Invalidate.ready.poke(true.B)
      d.io.trap.ready.poke(true.B);d.io.peer.trap.ready.poke(true.B);d.io.peer.completion.ready.poke(true.B)
      d.io.invalidateDone.ready.poke(true.B);d.io.peer.invalidateDone.ready.poke(true.B)
      val program=Vector(BigInt("00200093",16),BigInt("00000113",16),geometry(32,3,1,2),
        BigInt("00000213",16),geometry(33,0,4,3),BigInt("05d00293",16),geometry(38,0,5),
        geometry(35),geometry(36),BigInt("30500073",16))
      val code=program.zipWithIndex.map{case(word,i)=>word<<(32*i)}.sum
      val t=d.io.task.bits;t.nativeResources.poke(true.B);t.uniformResources.poke(true.B)
      val x=t.primitive;x.commandId.poke(3.U);x.kernel.kernelPc.poke(0x1000.U);x.firstSequence.poke(20.U)
      val q=x.input;q.taskId.poke(19.U);q.primitive.primitiveId.poke(27.U);q.primitive.kind.poke(2.U)
      q.primitive.vertexCount.poke(3.U);q.primitive.varyingSlots.poke(1.U);q.primitive.vertices(2).position(0).poke("h12345678".U)
      q.inputComponents.poke(1.U);q.invocations.poke(1.U);q.outputTopology.poke(0.U);q.maxVertices.poke(1.U)
      q.outputComponents.poke(1.U);q.builtinOutputMask.poke(2.U);x.outputSources(0).valid.poke(true.B)
      d.io.task.valid.poke(true.B);d.io.task.ready.expect(true.B);d.clock.step();d.io.task.valid.poke(false.B)
      t.nativeResources.poke(false.B);t.uniformResources.poke(false.B);q.primitive.vertices(2).position(0).poke(0.U)
      val peer=d.io.peer;peer.launch.bits.commandId.poke(3.U);peer.launch.bits.launch.kernelPc.poke(0x2000.U)
      for(i<-0 until 3){peer.launch.bits.launch.gridSize(i).poke(1.U);peer.launch.bits.launch.localSize(i).poke(1.U)}
      val pending=mutable.Queue.empty[(BigInt,BigInt,Int)];var cycle=0;var submitted=false;var peerDone=false
      var emitted=0;var both=false;var geometryLaunch=false;var peerLaunch=false;var held:Option[BigInt]=None
      while((!d.io.completion.valid.peek().litToBoolean || !peerDone) && cycle<30000){
        if(!submitted && d.io.geometryOwned.peek().litValue!=0)peer.launch.valid.poke(true.B)
        val peerAccepted=peer.launch.valid.peek().litToBoolean && peer.launch.ready.peek().litToBoolean
        both ||= d.io.poolOwned.peek().litValue==3
        if(d.io.launch.valid.peek().litToBoolean){val tag=d.io.launch.bits.tag.peek().litValue.toInt
          if(tag==0){d.io.launch.bits.native.expect(true.B);d.io.launch.bits.uniform.expect(true.B);geometryLaunch=true}
          else {assert(tag==1);d.io.launch.bits.native.expect(false.B);d.io.launch.bits.uniform.expect(false.B);peerLaunch=true}}
        sys.memoryRequest.ready.poke((cycle%3!=0).B);val reply=pending.headOption.filter(_._3<=cycle)
        sys.memoryResponse.valid.poke(reply.nonEmpty.B);reply.foreach(r=>{sys.memoryResponse.bits.transactionId.poke(r._1.U);sys.memoryResponse.bits.readData.poke(r._2.U)})
        if(reply.nonEmpty && sys.memoryResponse.ready.peek().litToBoolean)pending.dequeue()
        if(sys.memoryRequest.valid.peek().litToBoolean && sys.memoryRequest.ready.peek().litToBoolean){val addr=sys.memoryRequest.bits.address.peek().litValue
          assert(addr==0x1000 || addr==0x2000);pending.enqueue((sys.memoryRequest.bits.transactionId.peek().litValue,if(addr==0x1000)code else BigInt("30500073",16),cycle+3))}
        d.io.primitive.ready.poke((cycle%7<4).B)
        if(d.io.primitive.valid.peek().litToBoolean){val o=d.io.primitive.bits;o.inputPrimitiveId.expect(27.U);o.primitive.primitiveId.expect(93.U)
          o.primitive.vertices(0).position(0).expect("h12345678".U);val value=o.peek().litValue;held.foreach(v=>assert(v==value))
          if(d.io.primitive.ready.peek().litToBoolean){emitted+=1;held=None}else held=Some(value)
        }else assert(held.isEmpty)
        if(peer.completion.valid.peek().litToBoolean){peer.completion.bits.commandId.expect(3.U);peer.completion.bits.success.expect(true.B);assert(!peerDone);peerDone=true}
        d.io.trap.valid.expect(false.B);peer.trap.valid.expect(false.B)
        d.clock.step();if(peerAccepted){submitted=true;peer.launch.valid.poke(false.B)};cycle+=1
      }
      d.io.completion.valid.expect(true.B);d.io.completion.bits.status.expect(0.U)
      d.io.completion.bits.producedPrimitives.expect(1.U);assert(peerDone && emitted==1 && both && geometryLaunch && peerLaunch && pending.isEmpty)
    }
  }
}
