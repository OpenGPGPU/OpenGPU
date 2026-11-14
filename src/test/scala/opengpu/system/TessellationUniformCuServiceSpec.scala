package opengpu.system

import chisel3._
import opengpu.config.GpuConfig
import opengpu.graphics.WindowRecordTest
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

/** Service-level ownership cases complement the actual-CU system test. The
  * modeled channel peer here is not an ISA execution or host qualification.
  */
class TessellationUniformCuServiceSpec extends AnyFlatSpec {
  import WindowRecordTest._
  it should "keep TCS/TES roots and physical clients distinct and drain canceled offers before reuse" in {
    simulate(new TessellationUniformCuService(GpuConfig(lanes=4,warps=2),4,2)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val memory=new Memory(d.io.memoryRequest.bits,d.io.memoryResponse.bits,d.io.memoryRequest.valid,d.io.memoryRequest.ready,
        d.io.memoryResponse.valid,d.io.memoryResponse.ready)
      def record(at:Int,words:Seq[BigInt]):Unit=words.zipWithIndex.foreach{case(v,i)=>memory.putWord(at+4*i,v)}
      for((stage,root,table,data)<-Seq((4,0x1000,0x2000,0x10000),(5,0x3000,0x4000,0x20000))){
        record(root,Vector[BigInt](BigInt("47555203",16),64,3,7,11,13,stage,1,0,0,table,table+1536,0,0,0,0))
        record(table,Vector[BigInt](BigInt("47554203",16),64,3,7,11,13,0,1<<stage,data,data+16384,0,data,data+16384,0,1,0))
        memory.putWord(data,if(stage==4)0x13579bdf else 0x2468ace0)
      }
      identity(d.io.scope.identity)
      d.io.scope.control.resources.version.poke(3.U);d.io.scope.control.resources.address.poke(0x1000.U)
      d.io.scope.evaluation.resources.version.poke(3.U);d.io.scope.evaluation.resources.address.poke(0x3000.U)
      d.io.active.poke(true.B);d.io.commandId.poke(5.U)
      d.io.taskIds(0).poke(5.U);d.io.taskIds(1).poke(8.U);d.io.owned.poke(1.U);d.io.control.poke(true.B)
      def tick():Unit={memory.drive();memory.capture();d.clock.step()}
      def until(p: =>Boolean):Unit={var n=0;while(!p && n<1000){tick();n+=1};assert(p,"tessellation uniform service stalled")}
      def start(cu:Int,vector:Boolean=false,opcode:BigInt=BigInt("0e20838b",16)):Unit={
        val p=d.io.texture(cu)
        if(vector){zero(p.vector.bits);val v=p.vector.bits
          v.issued.decode.instruction.poke("h1e2083ab".U);v.issued.decode.activeMask.poke(15.U)
          v.issued.decode.pc.poke(0x9000.U);v.executionMask.poke(15.U);p.vector.valid.poke(true.B)
          until(p.vector.ready.peek().litToBoolean);tick();p.vector.valid.poke(false.B)
        }else{zero(p.scalar.bits);val s=p.scalar.bits
          s.decode.instruction.poke(opcode.U);s.decode.activeMask.poke(15.U);s.decode.pc.poke(0x9000.U)
          p.scalar.valid.poke(true.B);until(p.scalar.ready.peek().litToBoolean);tick();p.scalar.valid.poke(false.B)}
      }
      def finish(cu:Int,value:BigInt,vector:Boolean=false,success:Boolean=true):Unit={
        val p=d.io.texture(cu);val other=d.io.texture(1-cu)
        until(if(vector)p.vectorWriteback.valid.peek().litToBoolean else p.scalarWriteback.valid.peek().litToBoolean)
        other.scalarWriteback.valid.expect(false.B);other.vectorWriteback.valid.expect(false.B)
        if(vector){p.vectorWriteback.bits.writesVd.expect(success.B)
          for(i<-0 until 4)p.vectorWriteback.bits.writeback.data(i).expect((if(success)value else BigInt(0)).U)
        }else{p.scalarWriteback.bits.writeRd.expect(success.B);p.scalarWriteback.bits.data.expect((if(success)value else BigInt(0)).U)}
        val held=if(vector)p.vectorWriteback.bits.peek().litValue else p.scalarWriteback.bits.peek().litValue
        for(_<-0 until 4){tick();assert((if(vector)p.vectorWriteback.bits.peek().litValue else p.scalarWriteback.bits.peek().litValue)==held)}
        if(vector)p.vectorWriteback.ready.poke(true.B)else p.scalarWriteback.ready.poke(true.B)
        tick();p.vectorWriteback.ready.poke(false.B);p.scalarWriteback.ready.poke(false.B)
        until(!d.io.busy.peek().litToBoolean);assert(memory.pending.isEmpty && memory.reply.isEmpty)
      }
      start(0);finish(0,0x13579bdf)
      start(0,vector=true);finish(0,0x13579bdf,vector=true)
      d.io.control.poke(false.B);d.io.owned.poke(2.U);d.io.taskIds(0).poke(8.U);d.io.taskIds(1).poke(5.U)
      start(1);finish(1,0x2468ace0)
      start(1,vector=true);finish(1,0x2468ace0,vector=true)
      assert(memory.seen.exists(r=>r.tag==0 && r.address==0x1000))
      assert(memory.seen.exists(r=>r.tag==1 && r.address==0x3000))
      // The common source port may carry a sampler. P15 rejects it without
      // pretending a sampler provider exists or issuing a descriptor read.
      val before=memory.seen.size;start(1,opcode=BigInt("0400000b",16))
      until(d.io.fault.peek().litToBoolean);finish(1,0,success=false);assert(memory.seen.size==before)
      // Physical completion may precede a canceled memory reply. It permits
      // local discard but cannot release memory or enable another task early.
      memory.holdRequests=true;start(1);until(d.io.memoryRequest.valid.peek().litToBoolean)
      val held=d.io.memoryRequest.bits.peek().litValue;d.io.cancel.poke(true.B)
      for(_<-0 until 5){tick();d.io.memoryRequest.valid.expect(true.B);assert(d.io.memoryRequest.bits.peek().litValue==held);d.io.busy.expect(true.B)}
      d.io.owned.poke(0.U);d.io.retired.poke(true.B)
      memory.holdRequests=false;memory.holdResponses=true;until(memory.pending.nonEmpty || memory.reply.nonEmpty)
      for(_<-0 until 5){tick();d.io.busy.expect(true.B);d.io.texture.foreach(p=>{p.scalarWriteback.valid.expect(false.B);p.vectorWriteback.valid.expect(false.B)})}
      memory.holdResponses=false;until(!d.io.busy.peek().litToBoolean)
      assert(memory.pending.isEmpty && memory.reply.isEmpty)
      d.io.cancel.poke(false.B);d.io.retired.poke(false.B);d.io.control.poke(true.B)
      d.io.owned.poke(1.U);d.io.taskIds(0).poke(5.U);d.io.taskIds(1).poke(8.U)
      start(0);finish(0,0x13579bdf)
    }
  }
}
