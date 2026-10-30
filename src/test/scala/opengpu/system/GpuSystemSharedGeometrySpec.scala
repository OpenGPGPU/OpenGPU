package opengpu.system

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.graphics.GeometryOutputEvent
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class GpuSystemSharedGeometrySpec extends AnyFlatSpec {
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
  it should "retain distinct geometry tasks across shared graphics CU ownership" in {
    val config=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2)
    simulate(new GpuSystem(config,numComputeUnits=2,commandIdWidth=4,transactionsPerCu=4,
      enableGraphicsKernels=true,geometryAvailable=true,instructionCacheSets=4,instructionCacheWays=1,
      vectorCacheSets=4,vectorCacheWays=1)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
      d.io.graphicsDrained.poke(true.B);d.io.graphicsShaderL1Invalidate.ready.poke(true.B)
      d.io.commandCompletion.ready.poke(true.B);d.io.trap.foreach(_.ready.poke(true.B))
      val g=d.io.graphicsKernels.get;g.trap.ready.poke(true.B);g.completion.ready.poke(true.B)
      val program=Vector(BigInt("00500093",16),BigInt("03f00113",16),geometry(32,3,1,2),
        BigInt("00000213",16),geometry(33,0,4,3),geometry(35),geometry(36),BigInt("30500073",16))
      val code=program.zipWithIndex.map{case(word,i)=>word<<(32*i)}.sum
      val pending=mutable.Queue.empty[(BigInt,Int)];val completions=mutable.Map.empty[Int,Boolean]
      val events=mutable.Map.empty[Int,Vector[Int]].withDefaultValue(Vector.empty);val traps=mutable.ArrayBuffer.empty[Int]
      var cycle=0;var simultaneous=false;var computeTraps=0;var computeCompletion:Option[Boolean]=None
      val held=Array.fill[Option[BigInt]](2)(None)
      def tick():Unit={
        simultaneous ||= g.owned.peek().litValue==3
        d.io.memoryRequest.ready.poke((cycle%3!=0).B);val reply=pending.headOption.filter(_._2<=cycle)
        d.io.memoryResponse.valid.poke(reply.nonEmpty.B);reply.foreach(r=>{d.io.memoryResponse.bits.transactionId.poke(r._1.U);d.io.memoryResponse.bits.readData.poke(code.U)})
        if(reply.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean)pending.dequeue()
        if(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean){
          d.io.memoryRequest.bits.address.expect(0x1000.U);pending.enqueue((d.io.memoryRequest.bits.transactionId.peek().litValue,cycle+3))}
        for(cu<-0 until 2){val e=g.geometryEvent.get(cu);e.ready.poke((cycle%7<4).B)
          if(e.valid.peek().litToBoolean){assert(g.owned.peek().litValue.testBit(cu));val id=g.taskIds(cu).peek().litValue.toInt
            assert(Set(1,2)(id));val op=e.bits.operation.peek().litValue.toInt
            if(op==0)e.bits.vertex.components(0).expect((0x1000+id).U)
            val value=e.bits.peek().litValue;held(cu).foreach(x=>assert(x==value))
            if(e.ready.peek().litToBoolean){events(id)=events(id):+op;held(cu)=None}else held(cu)=Some(value)
          }else assert(held(cu).isEmpty)}
        if(g.trap.valid.peek().litToBoolean && g.trap.ready.peek().litToBoolean){
          val id=g.trap.bits.taskId.peek().litValue.toInt;assert(id==3);g.trap.bits.event.cause.expect(2.U);traps+=id}
        if(g.completion.valid.peek().litToBoolean && g.completion.ready.peek().litToBoolean){
          val id=g.completion.bits.commandId.peek().litValue.toInt;assert(!completions.contains(id))
          completions(id)=g.completion.bits.success.peek().litToBoolean}
        for(t<-d.io.trap)if(t.valid.peek().litToBoolean && t.ready.peek().litToBoolean){t.bits.cause.expect(2.U);computeTraps+=1}
        if(d.io.commandCompletion.valid.peek().litToBoolean && d.io.commandCompletion.ready.peek().litToBoolean){
          d.io.commandCompletion.bits.commandId.expect(4.U);assert(computeCompletion.isEmpty)
          computeCompletion=Some(d.io.commandCompletion.bits.success.peek().litToBoolean)}
        d.clock.step();cycle+=1
      }
      def until(label:String)(p: => Boolean):Unit={var n=0;while(!p && n<30000){tick();n+=1};assert(p,s"$label stalled at $cycle")}
      def submit(id:Int,enable:Boolean):Unit={
        g.launch.bits.commandId.poke(id.U);g.launch.bits.launch.kernelPc.poke(0x1000.U)
        for(i<-0 until 3){g.launch.bits.launch.gridSize(i).poke(1.U);g.launch.bits.launch.localSize(i).poke(1.U)}
        val t=g.geometryTask.get;t.valid.poke(enable.B);t.bits.taskId.poke((100+id).U);t.bits.primitiveId.poke((200+id).U)
        t.bits.invocationId.poke(id.U);t.bits.inputVertices.poke(6.U);t.bits.inputComponents.poke(64.U)
        t.bits.maxVertices.poke(2.U);t.bits.outputComponents.poke(4.U);t.bits.vertices(5).components(63).poke((0x1000+id).U)
        g.launch.valid.poke(true.B);until("graphics launch")(g.launch.ready.peek().litToBoolean);tick();g.launch.valid.poke(false.B)
        t.valid.poke(false.B);t.bits.vertices(5).components(63).poke(0.U)
      }
      submit(1,true);submit(2,true)
      d.io.command.bits.commandId.poke(4.U);d.io.command.bits.launch.kernelPc.poke(0x1000.U)
      for(i<-0 until 3){d.io.command.bits.launch.gridSize(i).poke(1.U);d.io.command.bits.launch.localSize(i).poke(1.U)}
      d.io.command.valid.poke(true.B);until("compute admission")(d.io.command.ready.peek().litToBoolean);tick();d.io.command.valid.poke(false.B)
      until("geometry and compute completions")(completions.size==2 && computeCompletion.nonEmpty)
      assert(completions.toMap==Map(1->true,2->true) && events.toMap==Map(1->Vector(0,1),2->Vector(0,1)))
      assert(computeCompletion.contains(false) && computeTraps==1)
      assert(simultaneous,s"both CUs were not active together")
      submit(3,false);until("disabled geometry completion")(completions.size==3)
      assert(completions(3)==false && traps.toSeq==Seq(3) && !events.contains(3))
      until("memory drain")(pending.isEmpty && d.io.vmContextIdle.peek().litToBoolean)
    }
  }
}
