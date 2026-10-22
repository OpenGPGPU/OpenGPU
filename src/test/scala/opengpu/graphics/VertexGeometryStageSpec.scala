package opengpu.graphics

import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.core.memory._
import opengpu.system.{GpuSystem,SharedCuShaderConfig}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class VertexGeometryHarness(local: Boolean=true) extends Module {
  val cfg=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2)
  val p=VertexShaderStageConfig(VertexInputFetchConfig(2,2),2)
  val payload=ShaderPayloadConfig(capacityBytes=4096,maxPayloadBytes=p.prefixBytes(8,1,1),owners=2,ports=4)
  private val slots=GpuSystem.totalMemoryTransactions(2,4,4)
  val io=IO(new Bundle {
    val draw=Flipped(Decoupled(new VertexGeometryDraw(p)))
    val out=Decoupled(new AssembledPrimitive(p));val completion=Decoupled(new PrimitiveAssemblyCompletion)
    val memoryRequest=Decoupled(new ComputeMemoryRequest(cfg,64,slots))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,slots)))
    val cancel=Input(Bool());val poolCancel=Input(Bool())
    val idle=Output(Bool());val busy=Output(Bool());val reserved=Output(UInt(32.W))
    val poolDrained=Output(Bool());val globalStaging=Output(Bool())
  })
  private val stage=Module(new VertexGeometryStage(cfg,p,SharedCuShaderConfig(2),payload=if(local)Some(payload)else None))
  private val system=Module(new GpuSystem(cfg,numComputeUnits=2,enableGraphicsKernels=true,
    graphicsHostTransactions=4,instructionCacheSets=4,instructionCacheWays=1,
    vectorCacheSets=4,vectorCacheWays=1,shaderPayload=if(local)Some(payload)else None))
  private def tie(data: Data): Unit=data match {
    case r: Record=>r.elements.values.foreach(tie)
    case v: Vec[_]=>v.foreach(tie)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b:=false.B
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s:=0.S
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u:=0.U
    case _=>()
  }
  tie(system.io)
  stage.io.draw<>io.draw;io.out<>stage.io.out;io.completion<>stage.io.completion;stage.io.cancel:=io.cancel
  system.io.graphicsKernels.get<>stage.io.shared
  system.io.graphicsHostRequest<>stage.io.memoryRequest;stage.io.memoryResponse<>system.io.graphicsHostResponse
  system.io.graphicsDrained:= !stage.io.busy;system.io.graphicsShaderL1Invalidate.ready:=true.B
  io.memoryRequest<>system.io.memoryRequest;system.io.memoryResponse<>io.memoryResponse
  io.busy:=stage.io.busy;io.idle:=system.io.vmContextIdle && !stage.io.busy
  io.globalStaging:=stage.io.memoryRequest.fire && stage.io.memoryRequest.bits.transactionId===1.U
  io.reserved:=0.U;io.poolDrained:=true.B
  if(local) {
    val port=stage.io.payload.get;val management=system.io.payloadManagement.get
    management.control(0)<>port.control;port.controlResponse<>management.controlResponse(0)
    management.request(0)<>port.request;port.response<>management.response(0)
    port.context:=101.U;port.cancel:=io.poolCancel;system.io.graphicsPayload.get:=port.task
    io.reserved:=management.reservedBytes;io.poolDrained:=management.drained
  }
}

class VertexGeometryStageSpec extends AnyFlatSpec {
  private val indices=Vector(0,1,2,3,4,5,6,7,8,9,10,255,4,3,2,1,0,255,255,2,3,4,5,6,7)
  private val identity=(BigInt(7)<<64)|(BigInt(8)<<32)|9
  private def fp(value: Int): BigInt=BigInt(java.lang.Float.floatToRawIntBits(value.toFloat).toLong&0xffffffffL)
  private def zero(data: Data): Unit=data match {
    case r: Record=>r.elements.values.foreach(zero)
    case v: Vec[_]=>v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case _=>()
  }
  private def addi(rd: Int,rs: Int,n: Int): BigInt=(BigInt(n&4095)<<20)|(BigInt(rs)<<15)|(BigInt(rd)<<7)|0x13
  private def vm(base: Int,v: Int,write: Boolean): BigInt=(BigInt(1)<<25)|(BigInt(base)<<15)|(BigInt(6)<<12)|(BigInt(v)<<7)|(if(write)0x27 else 7)
  private val program: Seq[BigInt]={
    val code=mutable.ArrayBuffer[BigInt](BigInt("00241393",16),BigInt("007083b3",16),BigInt("c1027057",16))
    for(i<-0 until 7)code++=Seq(addi(5,7,i*32),vm(5,1,false),addi(6,7,(7+i)*32),vm(6,1,true))
    code++=Seq(addi(13,1,480),(BigInt(13)<<15)|(BigInt(2)<<12)|(BigInt(13)<<7)|3,
      addi(5,7,4*32),vm(5,1,false),(BigInt(1)<<25)|(BigInt(1)<<20)|(BigInt(13)<<15)|(BigInt(4)<<12)|(BigInt(1)<<7)|0x57,
      addi(6,7,14*32),vm(6,1,true),BigInt("30500073",16));code.toVector
  }
  private case class V(ordinal: Int,instance: Int,id: Int,baseInstance: Int=2) {
    val position=(0 until 4).map(c=>fp(id*10+c+1)).toVector
    val vary=Vector(BigInt(id),BigInt(instance),BigInt(baseInstance),BigInt(id+17),BigInt(0),BigInt(0),BigInt(0),BigInt(0))
  }
  private case class Prim(values: Vector[V],id: Int,closing: Boolean=false)
  private def reference(mode: Int,baseInstance: Int=2,indexValues: Vector[Int]=indices): Vector[Prim]={
    val out=mutable.ArrayBuffer.empty[Prim]
    for(inst<-0 until 2) {
      var pid=0;val segments=mutable.ArrayBuffer.empty[Vector[V]];var vertices=Vector.empty[V]
      for((id,ordinal)<-indexValues.zipWithIndex)if(id==255){segments+=vertices;vertices=Vector.empty}else vertices :+= V(ordinal,inst,id,baseInstance)
      segments+=vertices
      for(segment<-segments if segment.nonEmpty) {
        val groups: Vector[(Vector[V],Boolean)]=mode match {
          case 2=>if(segment.size<2)Vector.empty else segment.sliding(2).map(v=>(v.toVector,false)).toVector:+((Vector(segment.last,segment.head),true))
          case 4=>segment.grouped(3).filter(_.size==3).map(v=>(v.toVector,false)).toVector
          case 5=>if(segment.size<3)Vector.empty else segment.sliding(3).zipWithIndex.map{case(v,i)=>(if(i%2==0)v.toVector else Vector(v(1),v(0),v(2)),false)}.toVector
          case 6=>if(segment.size<3)Vector.empty else segment.drop(1).sliding(2).map(v=>(Vector(segment.head,v(0),v(1)),false)).toVector
        }
        for((values,close)<-groups){out+=Prim(values,pid,close);pid+=1}
      }
    }
    out.toVector
  }
  private def pack(fields: (BigInt,Int)*): BigInt=fields.foldLeft(BigInt(0)){case(acc,(v,w))=>(acc<<w)|v}
  private def vector(values: Seq[BigInt],width: Int): BigInt=values.zipWithIndex.foldLeft(BigInt(0)){case(acc,(v,i))=>acc|(v<<(width*i))}
  private def bits(primitive: Prim,seq: Int,mode: Int,flat: BigInt,baseInstance: Int=2): BigInt={
    val vertices=primitive.values.map { v=>
      val varying=v.vary.indices.map(c=>if(flat.testBit(c))primitive.values.last.vary(c)else v.vary(c))
      pack(identity->96,BigInt(v.ordinal)->32,BigInt(v.id)->32,BigInt(v.instance)->32,BigInt(baseInstance)->32,BigInt(0)->1,BigInt(0)->32,BigInt(0)->1,
        vector(v.position,32)->128,vector(varying,32)->256)
    }++Vector.fill(3-primitive.values.size)(BigInt(0))
    pack(identity->96,BigInt(seq)->64,BigInt(primitive.id)->32,BigInt(primitive.values.last.instance)->32,BigInt(baseInstance)->32,
      BigInt(mode)->4,BigInt(primitive.values.size-1)->2,BigInt(primitive.values.size)->2,BigInt(primitive.values.size-1)->2,
      BigInt(if(primitive.closing)1 else 0)->1,BigInt(1)->5,flat->8,vector(vertices,642)->1926)
  }
  private class Driver(val d: VertexGeometryHarness) {
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    val memory=mutable.Map.empty[BigInt,BigInt]
    def put(address: BigInt,value: BigInt): Unit=for(b<-0 until 4)memory(address+b)=(value>>(8*b))&255
    program.zipWithIndex.foreach{case(w,i)=>put(0x1000+4*i,w)}
    put(0x2000,0)
    for(i<-0 until 16;c<-0 until 4)put(0x4000+i*16+c*4,fp(i*10+c+1))
    indices.zipWithIndex.foreach{case(v,i)=>memory(BigInt(0x6000+i))=v}
    for(i<-0 until 512 by 4)put(0x100000+i,BigInt("deadbeef",16));put(0x100000+480,17)
    case class Reply(id: BigInt,data: BigInt,due: Int,fault: Boolean)
    val pending=mutable.ArrayBuffer.empty[Reply];var offered: Option[Reply]=None
    var cycle=0;var requests=0;var globalStaging=0
    var fault: (BigInt,Boolean)=>Boolean=(_,_)=>false
    var onRead: BigInt=>Unit=_=>()
    var expected=Vector.empty[BigInt];var accepted=0;var held: Option[BigInt]=None
    var holdAt: Option[Int]=None;var ready=true
    def tick(): Unit={
      d.io.out.ready.poke((ready && !holdAt.contains(accepted) && cycle%7<5).B)
      if(d.io.out.valid.peek().litToBoolean) {
        val value=d.io.out.bits.peek().litValue;held.foreach(v=>assert(v==value,"held geometry primitive changed"))
        assert(accepted<expected.size && expected(accepted)==value,s"geometry primitive differs at $accepted cycle=$cycle")
        if(d.io.out.ready.peek().litToBoolean){accepted+=1;held=None}else held=Some(value)
      }else assert(held.isEmpty)
      if(d.io.globalStaging.peek().litToBoolean)globalStaging+=1
      d.io.memoryRequest.ready.poke((cycle%5!=0).B)
      if(offered.isEmpty) {
        val due=pending.indices.filter(i=>pending(i).due<=cycle)
        if(due.nonEmpty)offered=Some(pending.remove(if(cycle%2==0)due.last else due.head))
      }
      d.io.memoryResponse.valid.poke(offered.nonEmpty.B)
      offered.foreach { r=>d.io.memoryResponse.bits.transactionId.poke(r.id.U);d.io.memoryResponse.bits.readData.poke(r.data.U);d.io.memoryResponse.bits.fault.poke(r.fault.B) }
      if(offered.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean)offered=None
      if(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean) {
        requests+=1;val r=d.io.memoryRequest.bits;val address=r.address.peek().litValue
        val failed=fault(address,r.isWrite.peek().litToBoolean)
        if(!r.isWrite.peek().litToBoolean)onRead(address)
        if(r.isWrite.peek().litToBoolean && !failed) {
          val mask=r.byteMask.peek().litValue;val data=r.writeData.peek().litValue
          for(b<-0 until 64 if mask.testBit(b))memory(address+b)=(data>>(8*b))&255
        }
        val data=(0 until 64).foldLeft(BigInt(0))((v,b)=>v|(memory.getOrElse(address+b,BigInt(0))<<(8*b)))
        pending+=Reply(r.transactionId.peek().litValue,data,cycle+5+requests%4,failed)
      }
      d.clock.step();cycle+=1
    }
    def until(condition: => Boolean): Unit={var n=0;while(!condition && n<100000){tick();n+=1};assert(condition,s"geometry stalled cycle=$cycle primitives=$accepted")}
    def descriptor(mode: Int): Unit={
      val q=d.io.draw.bits;zero(q);q.topology.poke(mode.U);q.flatMask.poke(9.U)
      val v=q.vertex;v.version.poke(1.U);v.shaderPc.poke(0x1000.U);v.attributeSlots.poke(1.U);v.varyingSlots.poke(1.U);v.varyingMask.poke(15.U)
      v.input.version.poke(1.U);v.input.identity.context.poke(7.U);v.input.identity.pass.poke(8.U);v.input.identity.generation.poke(9.U)
      v.input.count.poke(indices.size.U);v.input.instances.poke(2.U);v.input.baseInstance.poke(2.U)
      v.input.indexFormat.poke(1.U);v.input.restart.poke(true.B)
      def view(s: VertexInputView,base: Int,bytes: Int): Unit={s.base.poke(base.U);s.limit.poke((base+bytes).U);s.allocationBase.poke(base.U);s.allocationLimit.poke(((base+bytes+63)& ~63).U)}
      view(v.input.indices,0x6000,indices.size);view(v.input.bindings(0),0x4000,256);v.input.bindings(0).stride.poke(16.U)
      v.input.attributes(0).enabled.poke(true.B);v.input.attributes(0).components.poke(4.U);v.input.attributes(0).format.poke(VertexInputFormat.float32.U)
      view(v.scratch,0x100000,484);v.sampler.texWidth.poke(1.U);v.sampler.texHeight.poke(1.U)
      accepted=0;expected=if(mode<=6)reference(mode).zipWithIndex.map{case(p,i)=>bits(p,i,mode,9)}else Vector.empty
    }
    def submit(): Unit={d.io.draw.valid.poke(true.B);until(d.io.draw.ready.peek().litToBoolean);tick();d.io.draw.valid.poke(false.B)}
    def finish(status: Int): Unit={
      until(d.io.completion.valid.peek().litToBoolean);val r=d.io.completion.bits;r.status.expect(status.U);r.primitives.expect(accepted.U)
      val held=r.peek().litValue
      for(_<-0 until 9){d.io.draw.ready.expect(false.B);d.io.busy.expect(true.B);d.io.idle.expect(false.B);tick();assert(r.peek().litValue==held)}
      d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B);until(d.io.idle.peek().litToBoolean)
      d.io.poolDrained.expect(true.B);d.io.reserved.expect(0.U);assert(pending.isEmpty && offered.isEmpty)
    }
  }

  for(local<-Seq(true,false))
  it should s"resolve indirect arrays and signed indexed parameters before shared-CU shading and assembly local=$local" in {
    simulate(new VertexGeometryHarness(local)){d=>val t=new Driver(d)
      var serial=0
      def prepare(mode: Int,count: Int,instances: Int=2,reserved: Int=0): BigInt={
        t.descriptor(4);d.io.draw.bits.vertex.input.count.poke(0.U);d.io.draw.bits.vertex.input.instances.poke(0.U)
        val c=d.io.draw.bits.indirect;val address=BigInt(0x9003c+128*serial);serial+=1
        c.mode.poke(mode.U);c.view.base.poke(address.U);c.view.limit.poke((address+(if(mode==1)16 else 20)).U)
        c.view.allocationBase.poke((address& ~BigInt(63)).U);c.view.allocationLimit.poke(((address+128)& ~BigInt(63)).U)
        val values=if(mode==1)Seq(count,instances,2,reserved)else Seq(count,instances,1,-1,reserved)
        values.zipWithIndex.foreach{case(v,i)=>t.put(address+4*i,BigInt(v)&0xffffffffL)}
        if(mode==1){d.io.draw.bits.vertex.input.indexFormat.poke(0.U);d.io.draw.bits.vertex.input.restart.poke(false.B)}
        else{
          t.memory(BigInt(0x8000))=99
          indices.zipWithIndex.foreach{case(v,i)=>t.memory(BigInt(0x8001+i))=if(v==255)255 else v+1}
          val v=d.io.draw.bits.vertex.input.indices
          v.base.poke(0x8000.U);v.limit.poke((0x8000+indices.size+1).U)
          v.allocationBase.poke(0x8000.U);v.allocationLimit.poke(0x8040.U)
        }
        val stream=if(mode==1)Vector.range(2,8)else indices
        t.expected=if(count==0 || reserved!=0)Vector.empty else reference(4,0,stream).zipWithIndex.map{case(p,i)=>bits(p,i,4,9,0)}
        address
      }
      for(mode<-Seq(1,2)){
        prepare(mode,if(mode==1)6 else indices.size);t.submit()
        d.io.draw.bits.indirect.mode.poke(3.U);d.io.draw.bits.indirect.view.base.poke(0.U)
        d.io.draw.bits.vertex.input.count.poke("hffffffff".U)
        t.until(d.io.completion.valid.peek().litToBoolean)
        d.io.completion.bits.indirectLines.expect(2.U)
        d.io.completion.bits.source.vertices.expect((if(mode==1)12 else 44).U)
        d.io.completion.bits.source.restarts.expect((if(mode==1)0 else 6).U)
        assert(t.accepted==t.expected.size);t.finish(0)
      }
      prepare(1,0);d.io.draw.bits.vertex.shaderPc.poke(0x2000.U);t.submit()
      t.until(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.source.batches.expect(0.U);t.finish(0)
      val reserved=prepare(2,3,reserved=1);t.submit()
      t.until(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.source.batches.expect(0.U)
      d.io.completion.bits.source.faultAddress.expect((reserved+16).U);t.finish(1)
      prepare(1,6);t.expected=Vector.empty
      val privateBase=d.io.draw.bits.vertex.scratch.base.peek().litValue
      val alias=d.io.draw.bits.indirect.view
      alias.base.poke(privateBase.U);alias.limit.poke((privateBase+16).U)
      alias.allocationBase.poke(privateBase.U);alias.allocationLimit.poke((privateBase+64).U)
      val beforeAlias=t.requests;t.submit();t.until(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.source.faultAddress.expect(privateBase.U)
      d.io.completion.bits.indirectLines.expect(0.U);d.io.completion.bits.source.batches.expect(0.U)
      t.finish(1);assert(t.requests==beforeAlias,"private-prefix alias issued memory work")
      val failed=prepare(2,3);t.expected=Vector.empty;t.fault=(a,w)=> !w && a==(failed& ~BigInt(63))+64
      t.submit();t.until(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.source.faultAddress.expect(((failed& ~BigInt(63))+64).U)
      d.io.completion.bits.source.batches.expect(0.U);t.finish(3);t.fault=(_,_)=>false
      val canceled=prepare(2,3);t.expected=Vector.empty;var requested=false
      t.onRead=a=>if(a==(canceled& ~BigInt(63))){requested=true;d.io.cancel.poke(true.B)}
      t.submit();t.until(d.io.completion.valid.peek().litToBoolean)
      assert(requested);d.io.completion.bits.indirectLines.expect(1.U)
      d.io.completion.bits.source.batches.expect(0.U);t.finish(4);d.io.cancel.poke(false.B);t.onRead=_=>()
      prepare(1,6);t.submit();t.finish(0);assert(t.accepted==t.expected.size)
      info(s"INDIRECT_DRAW_GEOMETRY local=$local actualCUs2=true arraysAndElements=true staticCountsIgnored=true signedBaseVertex=true firstIndex=true instances2=true restarts=true exactFlatPrimitives=true emptyNoLaunch=true reservedRejected=true privateAliasRejected=true secondLineFault=true delayedReplyCancel=true healthyReuse=true")
    }
  }

  for(local<-Seq(true,false))
  it should s"assemble actual indexed instanced shader batches across restarts with local=$local" in {
    simulate(new VertexGeometryHarness(local)) { d=>
      val t=new Driver(d);var total=0
      for(mode<-Seq(2,4,5,6)) {
        t.descriptor(mode);t.holdAt=Some(0);t.submit();d.io.draw.bits.topology.poke(15.U);d.io.draw.bits.vertex.shaderPc.poke(0.U)
        t.until(d.io.out.valid.peek().litToBoolean)
        if(local)d.io.reserved.expect(d.payload.slotBytes.U)
        for(_<-0 until 13){t.tick();d.io.busy.expect(true.B);d.io.idle.expect(false.B)}
        t.holdAt=Some(t.expected.size-1)
        t.until(t.accepted==t.expected.size-1 && d.io.out.valid.peek().litToBoolean && d.io.poolDrained.peek().litToBoolean)
        d.io.reserved.expect(0.U)
        for(_<-0 until 11){t.tick();d.io.busy.expect(true.B);d.io.idle.expect(false.B);d.io.completion.valid.expect(false.B)}
        t.holdAt=None;t.until(d.io.completion.valid.peek().litToBoolean)
        d.io.completion.bits.source.vertices.expect(44.U);d.io.completion.bits.source.restarts.expect(6.U)
        assert(d.io.completion.bits.source.batches.peek().litValue>2)
        assert(t.accepted==t.expected.size);total+=t.accepted;t.finish(0)
      }
      assert((t.globalStaging==0)==local)
      info(s"VERTEX_GEOMETRY local=$local CUs=2 draws=4 instances=2 verticesPerDraw=44 restartsPerDraw=6 primitives=$total crossBatch=true flat=true heldPrimitive=true privateCopyAfterRelease=true completionOwnsContext=true")
    }
  }

  it should "drain pre-admission pool cancellation invalid topology and shader faults before healthy reuse" in {
    simulate(new VertexGeometryHarness()) { d=>
      val t=new Driver(d);t.descriptor(5);t.expected=Vector.empty;t.submit();d.io.poolCancel.poke(true.B)
      t.until(d.io.completion.valid.peek().litToBoolean);d.io.poolCancel.poke(false.B);t.finish(VertexInputStatus.cancelled);assert(t.requests==0)
      t.descriptor(15);t.submit();t.finish(VertexInputStatus.invalidDescriptor);assert(t.requests==0)
      t.descriptor(5);t.expected=Vector.empty;d.io.draw.bits.vertex.shaderPc.poke(0x2000.U);t.submit()
      t.finish(VertexShaderStatus.shaderFault);assert(t.accepted==0)
      t.descriptor(2);t.holdAt=Some(t.expected.size-1);t.submit()
      t.until(t.accepted==t.expected.size-1 && d.io.out.valid.peek().litToBoolean && d.io.poolDrained.peek().litToBoolean)
      val held=d.io.out.bits.peek().litValue;d.io.cancel.poke(true.B)
      for(_<-0 until 13){t.tick();assert(d.io.out.bits.peek().litValue==held);d.io.completion.valid.expect(false.B)}
      t.holdAt=None;t.until(d.io.completion.valid.peek().litToBoolean);d.io.cancel.poke(false.B);t.finish(VertexInputStatus.cancelled)
      t.descriptor(5);t.submit();t.until(d.io.completion.valid.peek().litToBoolean);assert(t.accepted==t.expected.size);t.finish(0)
      info("VERTEX_GEOMETRY cancelBeforeChildrenAdmit=true invalidTopology=true shaderFault=true cancelAfterSourceDrain=true heldPrimitive=true healthyReuse=true")
    }
  }
}
