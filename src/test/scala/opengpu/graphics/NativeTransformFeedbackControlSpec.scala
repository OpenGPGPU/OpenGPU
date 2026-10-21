package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class NativeTransformFeedbackControlHarness extends Module {
  val cfg=GpuConfig(lanes=4,warps=2);val p=VertexShaderStageConfig(VertexInputFetchConfig(2,2),2)
  val io=IO(new Bundle {
    val command=Flipped(Decoupled(new NativeTransformFeedbackRequest))
    val reply=Decoupled(new NativeTransformFeedbackReply)
    val packet=Flipped(Decoupled(new TransformFeedbackPrimitive(p)))
    val out=Decoupled(new AssembledPrimitive(p));val result=Decoupled(new TransformFeedbackPrimitiveResult)
    val memoryRequest=Decoupled(new ComputeMemoryRequest(cfg,64,4))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,4)))
    val satp=Input(UInt(32.W));val cancel=Input(Bool())
    val memoryRoot=Output(UInt(32.W));val busy=Output(Bool());val coreBusy=Output(Bool())
    val active=Output(UInt(4.W));val controlOffer=Output(Bool())
    val controlFire=Output(Bool());val controlAction=Output(UInt(3.W))
  })
  private val native=Module(new NativeTransformFeedbackControl(cfg))
  private val core=Module(new TransformFeedbackExport(cfg,p))
  native.io.command<>io.command;io.reply<>native.io.reply
  native.io.satp:=io.satp;native.io.cancel:=io.cancel
  core.io.command<>native.io.control;native.io.controlReply<>core.io.commandReply
  core.io.cancel:=native.io.cancelCore;core.io.abort:=false.B
  core.io.in<>io.packet;io.out<>core.io.out;io.result<>core.io.result
  io.active:=core.io.activeObjects;io.coreBusy:=core.io.busy;io.busy:=native.io.busy
  io.memoryRoot:=native.io.memorySatp
  io.controlOffer:=native.io.control.valid;io.controlFire:=native.io.control.fire
  io.controlAction:=native.io.control.bits.action
  private val arbiter=Module(new RRArbiter(new ComputeMemoryRequest(cfg,64,4),2))
  for((port,i)<-Seq(native.io.memoryRequest,core.io.memoryRequest).zipWithIndex){
    arbiter.io.in(i).valid:=port.valid;arbiter.io.in(i).bits:=port.bits
    arbiter.io.in(i).bits.transactionId:=i.U;port.ready:=arbiter.io.in(i).ready
  }
  io.memoryRequest<>arbiter.io.out
  for((port,i)<-Seq(native.io.memoryResponse,core.io.memoryResponse).zipWithIndex){
    port.valid:=io.memoryResponse.valid && io.memoryResponse.bits.transactionId===i.U
    port.bits:=io.memoryResponse.bits;port.bits.transactionId:=0.U
  }
  io.memoryResponse.ready:=Mux(io.memoryResponse.bits.transactionId===0.U,native.io.memoryResponse.ready,core.io.memoryResponse.ready)
  when(io.memoryResponse.valid){assert(io.memoryResponse.bits.transactionId<=1.U)}
}

class NativeTransformFeedbackControlSpec extends AnyFlatSpec {
  import WindowRecordTest._
  private val root=BigInt(0x80000030L)
  private class Driver(val d: NativeTransformFeedbackControlHarness){
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    d.io.satp.poke(root.U);d.io.out.ready.poke(true.B);d.io.result.ready.poke(true.B)
    val memory=new Memory(d.io.memoryRequest.bits,d.io.memoryResponse.bits,d.io.memoryRequest.valid,
      d.io.memoryRequest.ready,d.io.memoryResponse.valid,d.io.memoryResponse.ready)
    val actions=mutable.ArrayBuffer.empty[Int];val results=mutable.ArrayBuffer.empty[Map[String,BigInt]]
    var outputs=0
    def tick(): Boolean={
      memory.drive()
      val accepted=d.io.command.valid.peek().litToBoolean && d.io.command.ready.peek().litToBoolean
      if(d.io.controlFire.peek().litToBoolean)actions+=d.io.controlAction.peek().litValue.toInt
      if(d.io.result.valid.peek().litToBoolean && d.io.result.ready.peek().litToBoolean)results+=values(d.io.result.bits)
      if(d.io.out.valid.peek().litToBoolean && d.io.out.ready.peek().litToBoolean)outputs+=1
      memory.capture();d.clock.step();accepted
    }
    def until(condition: =>Boolean): Unit={var n=0;while(!condition && n<20000){tick();n+=1}
      assert(condition,s"native TF control stalled cycle=${memory.cycle} actions=$actions pending=${memory.pending}")}
    def put(address: BigInt,index: Int,value: BigInt): Unit=memory.putWord(address+index*4,value)
    def word(address: BigInt,index: Int): BigInt=memory.word(address+index*4)
    def install(address: BigInt=0x60000,context: Int=31,cookie: Int=101,program: Int=9,
        target: BigInt=0x20003c,recordRoot: BigInt=root): Unit={
      for(i<-0 until 128)put(address,i,0)
      Seq(0->NativeTransformFeedbackRecord.magic,1->BigInt(512),2->BigInt(1),4->BigInt(context),
        5->BigInt(cookie),6->BigInt(program),7->BigInt(0),8->BigInt(1),9->BigInt(1),10->BigInt(4),
        16->target,17->(target+4096),41->recordRoot).foreach{case(i,v)=>put(address,i,v)}
      for(i<-0 until 4)put(address,48+i,i)
    }
    def submit(action: Int=0,address: BigInt=0x60000,context: Int=31,cookie: Int=101,
        program: Int=9,tag: Int=77,limit: Option[BigInt]=None): Unit={
      put(address,3,action);put(address,42,0);put(address,43,0)
      val q=d.io.command.bits;q.address.poke(address.U);q.limit.poke(limit.getOrElse(address+512).U)
      q.context.poke(context.U);q.cookie.poke(cookie.U);q.program.poke(program.U);q.tag.poke(tag.U)
      d.io.command.valid.poke(true.B);var accepted=false;var n=0
      while(!accepted && n<20000){accepted=tick();n+=1};assert(accepted);d.io.command.valid.poke(false.B)
    }
    def reply(status: Int=0,complete: Boolean=true,active: Boolean=true,applied: Boolean=true,
        tag: Int=77,address: BigInt=0x60000,context: Int=31,cookie: Int=101): Unit={
      until(d.io.reply.valid.peek().litToBoolean);val r=d.io.reply.bits
      r.status.expect(status.U);r.recordComplete.expect(complete.B);r.active.expect(active.B);r.applied.expect(applied.B)
      r.tag.expect(tag.U);r.address.expect(address.U);r.context.expect(context.U);r.cookie.expect(cookie.U)
      val held=r.peek().litValue
      for(_<-0 until 5){tick();assert(r.peek().litValue==held);d.io.busy.expect(true.B);d.io.command.ready.expect(false.B)}
      d.io.reply.ready.poke(true.B);tick();d.io.reply.ready.poke(false.B)
      until(!d.io.busy.peek().litToBoolean && !d.io.coreBusy.peek().litToBoolean)
    }
    def packet(context: Int=31,cookie: Int=101,program: Int=9,mode: Int=1,status: Int=0,
        captured: Boolean=true): Unit={
      val q=d.io.packet.bits;zero(q);q.context.poke(context.U);q.root.poke(root.U)
      q.program.poke(program.U);q.mode.poke(mode.U);q.cookie.poke(cookie.U)
      val p=q.primitive;identity(p.identity);p.kind.poke(0.U);p.vertexCount.poke(1.U)
      p.provokingVertex.poke(0.U);p.varyingSlots.poke(1.U);identity(p.vertices(0).identity)
      for(c<-0 until 4)p.vertices(0).position(c).poke((0x80000100L+c).U)
      val before=results.size;val oldOutputs=outputs
      d.io.packet.valid.poke(true.B);until(d.io.packet.ready.peek().litToBoolean);tick();d.io.packet.valid.poke(false.B)
      until(results.size==before+1);val r=results.last
      assert(r("status")==status && r("captured")== (if(captured)1 else 0))
      if(mode==1)assert(r("cookie")==cookie)
      until(!d.io.coreBusy.peek().litToBoolean)
      if(status!=0)assert(outputs==oldOutputs,"failed capture scope forwarded raster geometry")
    }
  }

  it should "publish exact bounded state with the commit line last and retain captured root through held replies" in {
    simulate(new NativeTransformFeedbackControlHarness){d=>val t=new Driver(d);t.install()
      t.memory.holdRequests=true;t.submit()
      t.until(d.io.memoryRequest.valid.peek().litToBoolean)
      d.io.satp.poke(0x80000031L.U);d.io.command.bits.cookie.poke(999.U)
      for(_<-0 until 7){t.tick();d.io.memoryRoot.expect(root.U)}
      t.memory.holdRequests=false
      t.memory.beforeRequest=r=>if(r.write && r.tag==0 && r.address==0x60080)t.memory.holdResponses=true
      t.until(t.memory.seen.exists(r=>r.write && r.address==0x60080))
      for(_<-0 until 9){t.tick();d.io.reply.valid.expect(false.B);d.io.memoryRoot.expect(root.U)}
      assert(t.word(0x60000,43)==0,"completion marker appeared before final acknowledgement")
      t.memory.holdResponses=false;t.reply()
      assert(t.word(0x60000,43)==7 && t.word(0x60000,41)==root)
      val writes=t.memory.seen.filter(r=>r.write && r.tag==0).map(_.address-0x60000)
      assert(writes==Seq[BigInt](0,64,192,256,320,384,448,128))
      d.io.satp.poke(root.U);t.memory.beforeRequest=_=>()
      t.packet();t.submit(action=1);t.reply(active=false)
      assert(t.word(0x60000,36)==1 && t.word(0x60000,38)==1 && t.word(0x60000,28)==16)
      for(c<-0 until 4)assert(t.memory.word(0x20003c+c*4)==0x80000100L+c)
      d.io.active.expect(0.U)
    }
  }

  it should "reject malformed records and mismatched command identities without changing feedback objects" in {
    simulate(new NativeTransformFeedbackControlHarness){d=>val t=new Driver(d)
      val before=t.memory.seen.size
      t.submit(address=0xffffffc0L,limit=Some(BigInt(1)<<32));t.reply(1,false,false,false,address=0xffffffc0L)
      assert(t.memory.seen.size==before)
      for((index,value,status)<-Seq((0,BigInt(0),1),(14,BigInt(1),1),(48,BigInt(128),1),
        (5,BigInt(999),2),(6,BigInt(10),2),(41,BigInt(0x80000031L),2))){
        t.install();t.put(0x60000,index,value);val old=t.memory.seen.size
        t.submit();t.reply(status,false,false,false)
        assert(t.memory.seen.drop(old).forall(!_.write) && t.actions.isEmpty)
      }
      t.install(address=0xfffffe00L);t.submit(address=0xfffffe00L,limit=Some(BigInt(1)<<32))
      t.reply(address=0xfffffe00L);d.io.active.expect(1.U)
    }
  }

  it should "invalidate only a mutated object after checkpoint write failure and fail closed on stale capture leases" in {
    simulate(new NativeTransformFeedbackControlHarness){d=>val t=new Driver(d)
      t.install(context=10);t.submit(context=10);t.reply(context=10)
      t.install(0x62000,20,202,target=0x21003c);t.submit(address=0x62000,context=20,cookie=202)
      t.reply(address=0x62000,context=20,cookie=202);d.io.active.expect(2.U)
      t.packet(context=10);t.packet(context=20,cookie=202)
      t.memory.beforeRequest=r=>if(r.write && r.tag==0 && r.address==0x60000)t.memory.faultAt=Set(BigInt(0x60000))
      t.submit(action=2,context=10);t.reply(3,false,false,false,context=10)
      d.io.active.expect(1.U);assert(t.actions.count(_==TransformFeedbackAction.invalidate)==1)
      t.memory.faultAt=Set.empty;t.memory.beforeRequest=_=>()
      t.packet(context=10,status=5,captured=false)
      t.packet(context=20,cookie=999,status=2,captured=false)
      t.packet(context=20,cookie=202,program=10,status=2,captured=false)
      t.packet(context=20,cookie=202,mode=2,status=5,captured=false)
      t.packet(context=20,cookie=202)
      t.submit(action=1,address=0x62000,context=20,cookie=202)
      t.reply(active=false,address=0x62000,context=20,cookie=202)
      assert(t.word(0x62000,36)==2 && t.word(0x62000,28)==32)
      d.io.active.expect(0.U)
    }
  }

  it should "preserve an existing object when a rejected nonmutating command cannot publish its response" in {
    simulate(new NativeTransformFeedbackControlHarness){d=>val t=new Driver(d)
      t.install();t.submit();t.reply();t.packet()
      t.memory.beforeRequest=r=>if(r.write && r.tag==0 && r.address==0x60000)t.memory.faultAt=Set(BigInt(0x60000))
      t.submit();t.reply(3,false,true,false)
      assert(!t.actions.contains(TransformFeedbackAction.invalidate));d.io.active.expect(1.U)
      t.memory.faultAt=Set.empty;t.memory.beforeRequest=_=>()
      t.packet();t.submit(action=1);t.reply(active=false)
      assert(t.word(0x60000,36)==2 && t.word(0x60000,28)==32)
    }
  }

  it should "drain an offered record read through reset and keep the primary memory fault ahead of cancellation" in {
    simulate(new NativeTransformFeedbackControlHarness){d=>val t=new Driver(d)
      for(fault<-Seq(false,true)){
        t.install();t.memory.holdRequests=true;t.submit()
        t.until(d.io.memoryRequest.valid.peek().litToBoolean)
        val held=d.io.memoryRequest.bits.peek().litValue;d.io.cancel.poke(true.B)
        for(_<-0 until 7){t.tick();assert(d.io.memoryRequest.bits.peek().litValue==held)}
        if(fault)t.memory.faultAt=Set(BigInt(0x60000))
        t.memory.holdRequests=false;t.reply(if(fault)3 else 4,false,false,false)
        d.io.active.expect(0.U);d.io.cancel.poke(false.B);t.memory.faultAt=Set.empty
        assert(t.actions.isEmpty)
      }
    }
  }

  it should "retain a one-cycle reset across an admitted core command without creating an object or deadlocking" in {
    simulate(new NativeTransformFeedbackControlHarness){d=>val t=new Driver(d);t.install();t.submit()
      t.until(d.io.controlOffer.peek().litToBoolean)
      d.io.cancel.poke(true.B);t.tick();d.io.cancel.poke(false.B)
      t.reply(4,false,false,false);d.io.active.expect(0.U)
      assert(t.actions==Seq(TransformFeedbackAction.begin) && t.memory.seen.forall(!_.write))
      t.install(cookie=102);t.submit(cookie=102);t.reply(cookie=102)
      t.packet(cookie=102);t.submit(action=1,cookie=102);t.reply(active=false,cookie=102)
      assert(t.word(0x60000,36)==1)
      info("NATIVE_TRANSFORM_FEEDBACK_CONTROL recordBytes=512 immutableScope=true rootPinned=true finalCommitLine2=true scopedPublicationRecovery=true rejectedCommandsDoNotMutate=true requiredCaptureCookieProgram=true partialRecordsIgnoredOnFault=true resetOfferDrain=true primaryFault=true healthyReuse=true")
    }
  }
}
