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

private class SharedPayloadClientSystemHarness extends Module {
  val cfg=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2)
  val p=ShaderPayloadConfig(capacityBytes=4096,maxPayloadBytes=288,owners=2,ports=4)
  private val pins=2*3+2
  private val slots=GpuSystem.totalMemoryTransactions(2,4,8)
  val io=IO(new Bundle {
    val clients=Vec(2,new SharedCuGraphicsPort(cfg,8,2))
    val payload=Input(Vec(2,Valid(new ShaderPayloadBinding(cfg,p))))
    val management=new ShaderPayloadManagementPort(p)
    val memoryRequest=Decoupled(new ComputeMemoryRequest(cfg,64,slots))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,slots)))
    val blockLaunch=Input(Bool())
    val idle=Output(Bool())
  })
  private val router=Module(new SharedShaderTaskRouter(cfg,2,2,tasksPerClient=3,shaderPayload=Some(p)))
  private val system=Module(new GpuSystem(cfg,numComputeUnits=2,enableGraphicsKernels=true,
    graphicsHostTransactions=8,instructionCacheSets=4,instructionCacheWays=1,
    vectorCacheSets=4,vectorCacheWays=1,shaderPayload=Some(p),producerPayloadPins=pins))
  private def tie(data: Data): Unit=data match {
    case r: Record=>r.elements.values.foreach(tie)
    case v: Vec[_]=>v.foreach(tie)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b:=false.B
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s:=0.S
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u:=0.U
    case _=>()
  }
  tie(system.io)
  router.io.clients<>io.clients;router.io.clientPayload.get:=io.payload
  system.io.graphicsKernels.get<>router.io.pool
  system.io.graphicsKernels.get.launch.valid:=router.io.pool.launch.valid && !io.blockLaunch
  router.io.pool.launch.ready:=system.io.graphicsKernels.get.launch.ready && !io.blockLaunch
  system.io.graphicsPayload.get:=router.io.poolPayload.get
  system.io.payloadProducerPins.get:=router.io.payloadPins.get
  system.io.payloadManagement.get<>io.management
  system.io.graphicsDrained:=router.io.drained
  io.memoryRequest<>system.io.memoryRequest;system.io.memoryResponse<>io.memoryResponse
  system.io.graphicsShaderL1Invalidate.ready:=true.B
  io.idle:=system.io.vmContextIdle
}

class SharedPayloadClientSystemSpec extends AnyFlatSpec {
  private def zero(data: Data): Unit=data match {
    case r: Record=>r.elements.values.foreach(zero)
    case v: Vec[_]=>v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case _=>()
  }
  for(releaseMode <- Seq("queued","offered"))
  it should s"pin $releaseMode client leases before CU admission and restore independent payloads at the same VA" in {
    simulate(new SharedPayloadClientSystemHarness) { d=>
      zero(d.io);d.io.blockLaunch.poke(true.B)
      d.io.clients.foreach(_.trap.ready.poke(true.B))
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      case class Lease(handle: BigInt,generation: BigInt,context: BigInt,owner: Int)
      val program=Seq(BigInt("0000a483",16),BigInt("0090a223",16),BigInt("30500073",16))
      val bytes=mutable.Map.empty[BigInt,BigInt]
      for((word,i)<-program.zipWithIndex;b<-0 until 4)bytes(BigInt(0x1000+4*i+b))=(word>>(8*b))&255
      case class Reply(id: BigInt,data: BigInt,due: Int)
      val pending=mutable.ArrayBuffer.empty[Reply];var offered: Option[Reply]=None
      val completed=mutable.ArrayBuffer.empty[(Int,Int)]
      var cycle=0;var releaseSent=false;var watchRelease=false;var pendingClient=false
      var dataTraffic=0
      def tick(): Unit={
        val release=d.io.management.control(0)
        val releaseFire=watchRelease && release.valid.peek().litToBoolean && release.ready.peek().litToBoolean
        val clientFire=pendingClient && d.io.clients(0).launch.valid.peek().litToBoolean && d.io.clients(0).launch.ready.peek().litToBoolean
        for(c<-0 until 2) {
          d.io.clients(c).trap.valid.expect(false.B)
          val done=d.io.clients(c).completion
          if(done.valid.peek().litToBoolean && done.ready.peek().litToBoolean){done.bits.success.expect(true.B);completed+=((c,done.bits.commandId.peek().litValue.toInt))}
        }
        d.io.memoryRequest.ready.poke((cycle%5!=0).B)
        if(offered.isEmpty && pending.exists(_.due<=cycle))offered=Some(pending.remove(pending.indexWhere(_.due<=cycle)))
        d.io.memoryResponse.valid.poke(offered.nonEmpty.B)
        offered.foreach(r=>{d.io.memoryResponse.bits.transactionId.poke(r.id.U);d.io.memoryResponse.bits.readData.poke(r.data.U)})
        if(offered.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean)offered=None
        if(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean) {
          val r=d.io.memoryRequest.bits;val address=r.address.peek().litValue
          if(address>=0x8000)dataTraffic+=1
          r.isWrite.expect(false.B)
          val data=(0 until 64).foldLeft(BigInt(0))((v,b)=>v|(bytes.getOrElse(address+b,BigInt(0))<<(8*b)))
          pending+=Reply(r.transactionId.peek().litValue,data,cycle+7)
        }
        d.clock.step();cycle+=1
        if(releaseFire){release.valid.poke(false.B);watchRelease=false;releaseSent=true}
        if(clientFire){d.io.clients(0).launch.valid.poke(false.B);d.io.payload(0).valid.poke(false.B);pendingClient=false}
      }
      def until(condition: => Boolean): Unit={var n=0;while(!condition && n<10000){tick();n+=1};assert(condition,s"routed payload stalled cycle=$cycle")}
      def control(owner: Int,op: Int,l: Lease): Lease={
        val c=d.io.management.control(owner)
        c.bits.operation.poke(op.U);c.bits.handle.poke(l.handle.U);c.bits.generation.poke(l.generation.U)
        c.bits.context.poke(l.context.U);c.bits.bytes.poke(288.U);c.valid.poke(true.B)
        until(c.ready.peek().litToBoolean);tick();c.valid.poke(false.B)
        val r=d.io.management.controlResponse(owner);until(r.valid.peek().litToBoolean)
        r.bits.fault.expect(false.B);r.bits.spill.expect(false.B)
        val result=Lease(r.bits.handle.peek().litValue,r.bits.generation.peek().litValue,r.bits.context.peek().litValue,owner)
        r.ready.poke(true.B);tick();r.ready.poke(false.B);result
      }
      def access(l: Lease,write: Boolean,value: BigInt=0,expected: BigInt=0): Unit={
        val q=d.io.management.request(l.owner)
        q.bits.handle.poke(l.handle.U);q.bits.generation.poke(l.generation.U);q.bits.context.poke(l.context.U)
        q.bits.owner.poke(l.owner.U);q.bits.offset.poke(0.U);q.bits.write.poke(write.B)
        q.bits.data.poke(value.U);q.bits.mask.poke((if(write)15 else 240).U);q.valid.poke(true.B)
        until(q.ready.peek().litToBoolean);tick();q.valid.poke(false.B)
        val r=d.io.management.response(l.owner);until(r.valid.peek().litToBoolean)
        r.bits.fault.expect(false.B);r.bits.data.expect(expected.U);r.ready.poke(true.B);tick();r.ready.poke(false.B)
      }
      def offer(client: Int,id: Int,l: Lease): Unit={
        val port=d.io.clients(client);port.launch.bits.commandId.poke(id.U)
        port.launch.bits.launch.kernelPc.poke(0x1000.U);port.launch.bits.launch.kernargAddress.poke(0x8000.U)
        for(i<-0 until 3){port.launch.bits.launch.gridSize(i).poke(1.U);port.launch.bits.launch.localSize(i).poke((if(i==0)4 else 1).U)}
        val b=d.io.payload(client);b.valid.poke(true.B);b.bits.handle.poke(l.handle.U)
        b.bits.generation.poke(l.generation.U);b.bits.context.poke(l.context.U);b.bits.owner.poke(l.owner.U)
        b.bits.base.poke(0x8000.U);b.bits.bytes.poke(288.U);port.launch.valid.poke(true.B)
      }
      def launch(client: Int,id: Int,l: Lease): Unit={
        offer(client,id,l);until(d.io.clients(client).launch.ready.peek().litToBoolean);tick()
        d.io.clients(client).launch.valid.poke(false.B);d.io.payload(client).valid.poke(false.B)
      }
      val a=control(0,ShaderPayloadControl.allocate,Lease(0,0,101,0))
      val b=control(1,ShaderPayloadControl.allocate,Lease(0,0,202,1))
      val c=control(0,ShaderPayloadControl.allocate,Lease(0,0,303,0))
      access(a,true,0x11112222);access(b,true,0x33334444);access(c,true,0x55556666)
      launch(0,7,a);launch(1,7,b);offer(0,8,c);pendingClient=true
      val releasing=if(releaseMode=="queued")a else c
      val release=d.io.management.control(0)
      release.bits.operation.poke(ShaderPayloadControl.release.U);release.bits.handle.poke(releasing.handle.U)
      release.bits.generation.poke(releasing.generation.U);release.bits.context.poke(releasing.context.U);release.valid.poke(true.B);watchRelease=true
      for(_<-0 until 12){d.io.clients(0).launch.ready.expect(false.B);release.ready.expect(false.B);tick()}
      d.io.blockLaunch.poke(false.B);d.io.clients(1).completion.ready.poke(true.B)
      until(!pendingClient && d.io.clients(0).completion.valid.peek().litToBoolean)
      d.io.clients(0).completion.bits.commandId.expect(7.U)
      for(_<-0 until 12){release.ready.expect(false.B);tick()}
      access(a,false,expected=BigInt(0x11112222L)<<32)
      d.io.clients(0).completion.ready.poke(true.B)
      if(releaseMode=="offered") {
        until(completed.contains((0,7)));d.io.clients(0).completion.ready.poke(false.B)
        until(d.io.clients(0).completion.valid.peek().litToBoolean)
        d.io.clients(0).completion.bits.commandId.expect(8.U)
        for(_<-0 until 12){release.ready.expect(false.B);tick()}
        access(c,false,expected=BigInt(0x55556666L)<<32)
        d.io.clients(0).completion.ready.poke(true.B)
      }
      until(releaseSent && completed.contains((0,7)) && completed.contains((0,8)) && completed.contains((1,7)))
      val released=d.io.management.controlResponse(0);until(released.valid.peek().litToBoolean)
      released.bits.fault.expect(false.B);released.ready.poke(true.B);tick();released.ready.poke(false.B)
      access(b,false,expected=BigInt(0x33334444L)<<32)
      if(releaseMode=="queued")access(c,false,expected=BigInt(0x55556666L)<<32)
      control(1,ShaderPayloadControl.release,b);control(0,ShaderPayloadControl.release,if(releaseMode=="queued")c else a)
      until(d.io.idle.peek().litToBoolean)
      assert(completed.sorted==Seq((0,7),(0,8),(1,7)))
      assert(dataTraffic==0,s"payload escaped to external memory: $dataTraffic transactions")
      info(s"PAYLOAD_CLIENT_SYSTEM CUs=2 clients=2 tasks=3 sameLocalTag=7 sameVA=true releaseMode=$releaseMode preAdmissionReleaseBlocked=true heldCompletionBlocksRelease=true externalPayloadTransactions=$dataTraffic cycles=$cycle")
    }
  }
}
