package opengpu.core

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.graphics.GeometryOutputEvent
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class GpuComputeUnitGeometrySpec extends AnyFlatSpec {
  private def zero(data:Data):Unit=data match {
    case r:Record=>r.elements.values.foreach(zero)
    case v:Vec[_]=>v.foreach(zero)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case _=>()
  }
  private def geometry(fn:Int,rd:Int=0,rs1:Int=0,rs2:Int=0):BigInt =
    (BigInt(fn)<<25)|(BigInt(rs2)<<20)|(BigInt(rs1)<<15)|(BigInt(rd)<<7)|0x0b
  for((enabled,cancelled,uniformAvailable)<-Seq((false,false,false),(true,false,false),(true,true,false),(true,false,true))) it should s"execute geometry scalar instructions only with a captured task enabled=$enabled cancelled=$cancelled uniform=$uniformAvailable" in {
    simulate(new GpuComputeUnit(GpuConfig(lanes=4,warps=2),finishOnTrap=true,textureAvailable=false,
      geometryAvailable=true,computeUniformAvailable=uniformAvailable,taskTextureAvailability=uniformAvailable,
      instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B);d.io.l1InvalidateDone.ready.poke(true.B)
      val program=Vector(BigInt("00500093",16),BigInt("03f00113",16),geometry(32,3,1,2),
        BigInt("00000213",16),geometry(33,0,4,3),BigInt("00700293",16),geometry(34,0,5),
        geometry(35),geometry(36),BigInt("30500073",16))
      val code=program.zipWithIndex.map{case(word,i)=>word<<(32*i)}.sum
      val t=d.io.geometryTask.get;t.taskId.poke(19.U);t.primitiveId.poke(27.U);t.invocationId.poke(3.U)
      t.inputVertices.poke(6.U);t.inputComponents.poke(64.U);t.outputComponents.poke(4.U);t.maxVertices.poke(2.U)
      t.vertices(5).components(63).poke("h12345678".U)
      d.io.geometryEnabled.get.poke(enabled.B);d.io.kernel.bits.kernelPc.poke(0x1000.U)
      for(i<-0 until 3){d.io.kernel.bits.gridSize(i).poke(1.U);d.io.kernel.bits.localSize(i).poke(1.U)}
      d.io.kernel.valid.poke(true.B);d.io.kernel.ready.expect(true.B);d.clock.step();d.io.kernel.valid.poke(false.B)
      zero(t);d.io.geometryEnabled.get.poke(false.B);d.io.geometryCancel.get.poke(cancelled.B);d.io.geometryLease.get.expect(enabled.B)
      val pending=mutable.Queue.empty[(BigInt,BigInt,Int)];var cycle=0;var reads=0;var events=0;var trapped=false;var held=0
      while(!d.io.completion.valid.peek().litToBoolean && cycle<5000){
        d.io.memoryRequest.ready.poke((cycle%3!=0).B)
        val reply=pending.headOption.filter(_._3<=cycle);d.io.memoryResponse.valid.poke(reply.nonEmpty.B)
        reply.foreach{case(id,data,_)=>d.io.memoryResponse.bits.transactionId.poke(id.U);d.io.memoryResponse.bits.readData.poke(data.U)}
        if(reply.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean)pending.dequeue()
        if(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean){
          val q=d.io.memoryRequest.bits;q.address.expect(0x1000.U);q.isWrite.expect(false.B)
          pending.enqueue((q.transactionId.peek().litValue,code,cycle+3))}
        if(d.io.committedWriteback.valid.peek().litToBoolean && d.io.committedWriteback.bits.rd.peek().litValue==3){
          d.io.committedWriteback.bits.data.expect("h12345678".U);reads+=1}
        val event=d.io.geometryEvent.get
        d.io.texSample.valid.expect(false.B)
        if(event.valid.peek().litToBoolean){
          assert(enabled && !cancelled);event.bits.operation.expect((if(events==0)GeometryOutputEvent.emit else GeometryOutputEvent.endPrimitive))
          if(events==0){event.bits.vertex.components(0).expect("h12345678".U);event.bits.vertex.layer.expect(7.U)}
          if(held<8){event.ready.poke(false.B);held+=1}else{event.ready.poke(true.B);events+=1;held=0}
        }else event.ready.poke(false.B)
        if(d.io.trap.valid.peek().litToBoolean){assert(!enabled || cancelled);d.io.trap.bits.pc.expect(0x1008.U)
          if(!enabled)d.io.trap.bits.cause.expect(2.U);d.io.trap.ready.poke(true.B);trapped=true}
        d.clock.step();cycle+=1
      }
      val success=enabled && !cancelled
      d.io.completion.valid.expect(true.B);d.io.completion.bits.success.expect(success.B)
      assert((if(success)reads==1 && events==2 && !trapped else reads==0 && events==0 && trapped) && pending.isEmpty)
      d.io.geometryLease.get.expect(enabled.B);d.io.completion.ready.poke(true.B);d.clock.step();d.io.geometryLease.get.expect(false.B)
    }
  }
}
