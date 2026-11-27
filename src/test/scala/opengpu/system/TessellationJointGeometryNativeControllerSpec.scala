package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.graphics._
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

/** Short observation aliases only. All GNQ/GVM/GNT/GVS/GND/GGA/GNG bytes go
  * through their actual production readers. Frame/range execution reports are
  * explicit protocol peers, not actual shaders or clear/store/resolve proof. */
private class JointGeometryNativeObserved[D <: Data](domain:Int,sequenceType:D,enabled:Boolean=true) extends Module {
  import TessellationJointReaderTestSupport.config
  private val p=VertexShaderStageConfig()
  val io=IO(new Bundle {
    val q=Flipped(Decoupled(new TessellationJointGeometryJobRequest(12)))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,1))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,1)))
    val fv=Output(Bool());val fr=Input(Bool());val f=Output(new TypedVertexBinImageDescriptor);val clear=Output(Bool())
    val fb=Input(Bool());val fd=Input(Bool());val fc=Output(Bool())
    val end=Decoupled(Bool());val ff=Flipped(Decoupled(new TessellationJointGeometryFrameExecutionRetirement))
    val rv=Output(Bool());val rr=Input(Bool());val e=Output(new TessellationJointDirectoryCapture(12))
    val c=Output(new TessellationJointGeometryCursor);val g=Output(sequenceType)
    val v=Output(new TessellationVertexCaptureCompletion(p))
    val a=Output(new TessellationJointGeometryAttachmentCompletion(p,12))
    val rb=Input(Bool());val rd=Input(Bool());val rc=Output(Bool())
    val rf=Flipped(Decoupled(new TessellationJointGeometryRangeExecutionRetirement))
    val out=Decoupled(new TessellationJointGeometryNativeCompletion(12))
    val cancel=Input(Bool());val busy=Output(Bool());val drained=Output(Bool())
  })
  private val child=Module(new TessellationJointGeometryNativeController(config,p,12,domain,sequenceType,enabled=enabled))
  child.io.submit<>io.q;io.memoryRequest<>child.io.memoryRequest;child.io.memoryResponse<>io.memoryResponse
  io.fv:=child.io.frame.valid;child.io.frame.ready:=io.fr;io.f:=child.io.frame.bits.descriptor;io.clear:=child.io.frame.bits.clearOnly
  child.io.frameBusy:=io.fb;child.io.frameDrained:=io.fd;io.fc:=child.io.cancelFrame
  io.end<>child.io.end;child.io.frameRetired<>io.ff
  io.rv:=child.io.range.valid;child.io.range.ready:=io.rr;io.e:=child.io.range.bits.directory
  io.c:=child.io.range.bits.cursor;io.g:=child.io.range.bits.captured.base.gnt.sequence
  io.v:=child.io.range.bits.captured.base.capture;io.a:=child.io.range.bits.captured.attachment
  child.io.rangeBusy:=io.rb;child.io.rangeDrained:=io.rd;io.rc:=child.io.cancelRange;child.io.rangeRetired<>io.rf
  io.out<>child.io.completion;child.io.cancel:=io.cancel;io.busy:=child.io.busy;io.drained:=child.io.drained
}

class TessellationJointGeometryNativeControllerSpec extends AnyFlatSpec {
  import TessellationJointReaderTestSupport._
  import TessellationJointCaptureTestPackets._
  private val p=VertexShaderStageConfig()
  private case class Cursor(tes:BigInt,prim:BigInt,task:BigInt,gsPrim:BigInt,in:BigInt,out:BigInt,bin:BigInt)
  private val seed=Cursor(10,20,31,0,40,100,0)
  private val middle=Cursor(17,27,38,7,47,113,5)
  private val last=Cursor(28,0,49,0,58,122,11)
  private def cursorPoke(c:TessellationJointGeometryCursor,v:Cursor):Unit={
    c.tes.sequence.poke(v.tes.U);c.tes.primitiveId.poke(v.prim.U);c.gsTaskId.poke(v.task.U)
    c.gsInputPrimitiveId.poke(v.gsPrim.U);c.inputSequence.poke(v.in.U);c.outputSequence.poke(v.out.U);c.binSequence.poke(v.bin.U)
  }
  private def cursorExpect(c:TessellationJointGeometryCursor,v:Cursor):Unit={
    c.tes.sequence.expect(v.tes.U);c.tes.primitiveId.expect(v.prim.U);c.gsTaskId.expect(v.task.U)
    c.gsInputPrimitiveId.expect(v.gsPrim.U);c.inputSequence.expect(v.in.U);c.outputSequence.expect(v.out.U);c.binSequence.expect(v.bin.U)
  }
  private def gng(domain:Int):Array[BigInt]={
    val w=Array.fill[BigInt](256)(0)
    for((i,v)<-Seq[(Int,BigInt)](0->BigInt("474e4701",16),1->1024,2->1,3->7,4->8,5->9,
      6->(if(domain==0)2 else 3),7->1,8->0,9->1,10->8,11->8,12->3,13->15,14->16,15->1,
      16->0x20000,17->0x20000,18->0x20040,20->0x20000,21->0x20040,23->2,24->0x18000))w(i)=v
    for(i<-0 until 8)w(32+i)=if(i<4)i else 56+i
    for(i<-0 until 8){val raw=if(i<4)i else 60+i;w(96+raw*2)=1;w(97+raw*2)=i}
    w
  }
  private def gga(domain:Int,index:Int,badSeed:Boolean=false,output:BigInt=100):Array[BigInt]={
    val w=Array.fill[BigInt](256)(0)
    Seq[(Int,BigInt)](0->index,1->0,2->domain,4->7,5->8,6->9,
      7->19,8->31,9->0,12->0x50000,13->0x50400,15->1024,224->40).foreach{case(i,v)=>w(i)=v}
    wide(w,10,if(badSeed)101 else output)
    for(i<-0 until 8)w(16+i)=128+i
    w
  }
  private def word(domain:Int,name:String):Int={
    val file=Seq("native-tessellation-sequence-v1.json","native-tessellation-triangle-sequence-v2.json","native-tessellation-quad-sequence-v3.json")(domain)
    val s=scala.io.Source.fromFile("docs/abi/"+file);val j=try ujson.read(s.mkString) finally s.close()
    j("fields").arr.find(_("name").str==name).get("word").num.toInt
  }
  private def run[D <: Data](domain:Int,seq: =>D):Unit={
    simulate(new JointGeometryNativeObserved(domain,seq)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val m=new Memory(d.clock,d.io.memoryRequest,d.io.memoryResponse)
      var invocation=BigInt("fedcba9876543210",16)
      def install(n:Int,zeroWork:Boolean,badSeed:Boolean,output:BigInt=100):Unit={
        val j=job(domain,n);j(0)=BigInt("474e5107",16)+domain;j(2)=3
        if(n>0){j(36)=1;j(37)=0x40000;wide(j,38,0x40000+64+1024*n);j(40)=n;j(41)=1024;j(42)=64+1024*n}
        m.put(0x1000,j);m.put(0x2000,frame(empty=n==0))
        if(n>0){
          m.put(0x8000,directoryHeader(n))
          val header=Array.fill[BigInt](16)(0)
          Seq[BigInt](BigInt("47474101",16),64+1024*n,1,1024,n,7,8,9).zipWithIndex.foreach{case(v,i)=>header(i)=v}
          m.put(0x40000,header);m.put(0x50000,gng(domain))
          for(i<-0 until n){
            val per=if(zeroWork)0 else if(n==1)10 else if(i==0)4 else 6
            val task=100+(if(i==0)0 else 4);val root=0xa000+i*1024
            m.put(0x8040+i*64,entry(index=i,id=invocation,flags=if(n==1)3 else if(i==0)1 else 6,ordinal=i,
              firstPatch=if(i==0 || zeroWork)0 else 4,instances=if(zeroWork)0 else 1,per=per,task=task,source=root))
            val g=sequence(domain);g(16)=per;g(219+(if(domain==0)0 else 1))=task
            g(179)=if(i==0)20 else 900;wide(g,180,if(i==0)10 else 800)
            g(word(domain,"template.source.viewport.varyingSlots"))=16
            g(509)=root;wide(g,510,root+512);m.put(0x4000+i*4096,g)
            val v=source();v(19)=i;v(21)=task;v(22)=per
            v(27)=0xf000;wide(v,28,0xf100);v(30)=0xf010
            v(33)=if(i==0 || zeroWork)0 else 8;v(34)=per;v(36)=if(zeroWork)0 else 1;m.put(root,v)
            m.put(0x40040+i*1024,gga(domain,i,badSeed && i==1,output))
          }
          val gnd=draw();gnd(342)=0xf010;gnd(325)=1;if(zeroWork)gnd(311)=0;m.put(0xc000,gnd)
        }
      }
      def begin(n:Int=2,zeroWork:Boolean=false,badSeed:Boolean=false,output:BigInt=100):Unit={
        assert(m.empty);m.clearReads();install(n,zeroWork,badSeed,output)
        d.io.fr.poke(false.B);d.io.rr.poke(false.B);d.io.end.ready.poke(false.B)
        d.io.fb.poke(false.B);d.io.fd.poke(true.B);d.io.rb.poke(false.B);d.io.rd.poke(true.B)
        d.io.cancel.poke(false.B);zero(d.io.ff.bits);zero(d.io.rf.bits)
        request(d.io.q.bits,domain);d.io.q.valid.poke(true.B)
        m.until("new native submit")(d.io.q.ready.peek().litToBoolean);m.tick();d.io.q.valid.poke(false.B);zero(d.io.q.bits)
      }
      def acceptFrame(empty:Boolean=false):Unit={
        m.until("real captured frame offer")(d.io.fv.peek().litToBoolean)
        d.io.clear.expect(empty.B);d.io.f.states.base.expect((if(empty)0 else 0x160000).U)
        d.io.f.image.pixels.colors.sampleMode.expect(2.U)
        for(i<-0 until 4)d.io.f.scratch(i).base.expect((0x180000+i*65536).U)
        d.io.fc.expect(false.B);val held=d.io.f.peek().litValue
        for(_<-0 until 3){m.tick();d.io.fv.expect(true.B);assert(d.io.f.peek().litValue==held)}
        d.io.fr.poke(true.B);m.tick();d.io.fr.poke(false.B);d.io.fb.poke(true.B)
      }
      def acceptRange(index:Int,c:Cursor,patches:Int,output:BigInt=100):Unit={
        m.until("actual GNT GVS GND GGA GNG range")(d.io.rv.peek().litToBoolean)
        d.io.e.entry.sequenceIndex.expect(index.U);d.io.e.entry.drawIndex.expect(2.U)
        d.io.e.entry.invocationId.expect(invocation.U);d.io.e.entry.patchCount.expect(patches.U)
        d.io.v.root.sequenceIndex.expect(index.U);d.io.v.root.drawIndex.expect(2.U)
        d.io.v.draw.input.count.expect((if(patches==0)0 else 20).U);d.io.a.status.expect(0.U)
        d.io.a.captured.commandId.expect(19.U) // Distinct from the GNQ command5.
        d.io.a.captured.firstOutputSequence.expect(output.U);d.io.a.captured.inputSequence.expect(40.U)
        cursorExpect(d.io.c,c)
        val held=d.io.e.peek().litValue;val raw=d.io.g.peek().litValue;val gs=d.io.a.peek().litValue
        val before=m.reads.size
        for(_<-0 until 4){m.tick();d.io.rv.expect(true.B);assert(d.io.e.peek().litValue==held && d.io.g.peek().litValue==raw)
          assert(d.io.a.peek().litValue==gs);assert(m.reads.size==before,"held directory lease advanced a capture")}
        d.io.rr.poke(true.B);m.tick();d.io.rr.poke(false.B);d.io.rb.poke(true.B);d.io.rd.poke(false.B)
      }
      def retireRange(patches:Int,tes:Int,c:Cursor,gs:Int,received:Int,admitted:Int,status:Int=0):Unit={
        val r=d.io.rf.bits;zero(r);r.result.range.completed.poke(patches.U);r.result.range.status.poke(status.U)
        r.result.published.poke(tes.U);r.geometry.actualGsPublished.poke(gs.U)
        r.geometry.received.poke(received.U);r.geometry.admitted.poke(admitted.U);r.geometry.dropped.poke((received-admitted).U)
        r.geometry.tasks.poke(patches.U);cursorPoke(r.geometry.nextCursor,c)
        d.io.rf.valid.poke(true.B);m.until("actual range retirement")(d.io.rf.ready.peek().litToBoolean)
        m.tick();d.io.rf.valid.poke(false.B)
        val before=m.reads.size
        for(_<-0 until 4){m.tick();assert(m.reads.size==before,"report released metadata before physical range drain")
          d.io.out.valid.expect(false.B);d.io.end.valid.expect(false.B)}
        d.io.rb.poke(false.B);d.io.rd.poke(true.B)
      }
      def frameReport(status:Int,gs:Int,received:Int,admitted:Int,tasks:Int,next:BigInt,bin:Int):Unit={
        val f=d.io.ff.bits;zero(f);identity(f.base.identity);f.base.status.poke(status.U)
        f.base.storedBytes.poke(4096.U);f.base.resolvedBytes.poke(1024.U)
        f.geometryReportValid.poke(true.B);f.actualGsPublished.poke(gs.U);f.received.poke(received.U)
        f.admitted.poke(admitted.U);f.dropped.poke((received-admitted).U);f.tasks.poke(tasks.U)
        f.nextGsSequenceExclusive.poke(next.U);f.nextBinSequence.poke(bin.U)
        d.io.ff.valid.poke(true.B);m.until("actual frame report")(d.io.ff.ready.peek().litToBoolean)
        m.tick();d.io.ff.valid.poke(false.B)
      }
      def endFrame(status:Int=0,gs:Int=22,received:Int=15,admitted:Int=11,tasks:Int=10,next:BigInt=122,bin:Int=11):Unit={
        m.until("end barrier")(d.io.end.valid.peek().litToBoolean)
        d.io.end.ready.poke(true.B);m.tick();d.io.end.ready.poke(false.B)
        frameReport(status,gs,received,admitted,tasks,next,bin)
        d.io.fd.poke(false.B);for(_<-0 until 3){m.tick();d.io.out.valid.expect(false.B)}
        d.io.fb.poke(false.B);d.io.fd.poke(true.B)
      }
      def finish(status:Int,ranges:Int,patches:Int,tes:Int,entries:Int,gs:Int,next:BigInt,bin:Int,
          clear:Boolean=false,stage:Int=0):Unit={
        m.until("new native terminal")(d.io.out.valid.peek().litToBoolean)
        val o=d.io.out.bits;o.base.status.expect(status.U);o.base.faultStage.expect(stage.U)
        o.base.retiredRanges.expect(ranges.U);o.base.retiredPatches.expect(patches.U);o.base.published.expect(tes.U)
        o.base.capturedEntries.expect(entries.U);o.base.clearOnly.expect(clear.B)
        o.base.readBytes.expect((m.reads.size*64).U);o.base.commandId.expect(5.U)
        o.actualGsPublished.expect(gs.U);o.nextGsSequenceExclusive.expect(next.U)
        o.nextGsSequence.expect((next&((BigInt(1)<<64)-1)).U);o.nextBinSequence.expect(bin.U)
        d.io.drained.expect(true.B);val held=o.peek().litValue
        d.io.cancel.poke(true.B);d.io.fd.poke(false.B)
        for(_<-0 until 4){m.tick();d.io.out.valid.expect(true.B);assert(o.peek().litValue==held);d.io.q.ready.expect(false.B)}
        d.io.fd.poke(true.B);d.io.out.ready.poke(true.B);m.tick();d.io.out.ready.poke(false.B);d.io.cancel.poke(false.B)
        d.io.busy.expect(false.B);assert(m.empty)
      }
      // 8 GNQ +32 GNF +header/2entries +2*(72 actual source +33 actual attachment).
      begin();acceptFrame();acceptRange(0,seed,4);retireRange(4,7,middle,13,7,5)
      acceptRange(1,middle,6);retireRange(6,11,last,9,8,6);endFrame();finish(0,2,10,18,2,22,122,11)
      assert(m.reads.size==253)
      // A real zero-task invocation still reads every source and GS attachment.
      // FIRST's nonzero output seed is not an actual GS frame publication.
      invocation=0;begin(n=1,zeroWork=true);acceptFrame();acceptRange(0,seed,0)
      retireRange(0,0,seed,0,0,0);endFrame(gs=0,received=0,admitted=0,tasks=0,next=0,bin=0)
      finish(0,1,0,0,1,0,0,0);assert(m.reads.size==147)
      begin(n=0);acceptFrame(empty=true);endFrame(gs=0,received=0,admitted=0,tasks=0,next=0,bin=0)
      finish(0,0,0,0,0,0,0,0,clear=true);assert(m.reads.size==40)
      // Already offered range metadata survives cancel and first transfers.
      begin(n=1);acceptFrame();m.until("held cancellable geometry range")(d.io.rv.peek().litToBoolean)
      val held=d.io.c.peek().litValue;d.io.cancel.poke(true.B);d.io.rc.expect(false.B)
      for(_<-0 until 3){m.tick();d.io.rv.expect(true.B);assert(d.io.c.peek().litValue==held)}
      d.io.rr.poke(true.B);m.tick();d.io.rr.poke(false.B);d.io.rb.poke(true.B);d.io.rd.poke(false.B)
      d.io.rc.expect(true.B);retireRange(0,0,seed,0,0,0,status=4)
      endFrame(status=2,gs=0,received=0,admitted=0,tasks=0,next=0,bin=0);finish(2,0,0,0,1,0,0,0,stage=6)
      // A noncancel frame fault on the exact successful range-drain edge
      // prevents cursor/statistics commit, even though the range report passed.
      begin();acceptFrame();acceptRange(0,seed,4);retireRange(4,7,middle,13,7,5)
      frameReport(2,13,7,5,4,113,5)
      m.until("end after same-edge frame fault")(d.io.end.valid.peek().litToBoolean)
      d.io.end.ready.poke(true.B);m.tick();d.io.end.ready.poke(false.B)
      d.io.fb.poke(false.B);d.io.fd.poke(true.B);finish(2,0,0,0,1,13,113,5,stage=6)
      assert(!m.reads.contains(BigInt(0x8080)),"same-edge fault advanced the directory")
      // CONT must repeat FIRST's static seeds, not its dynamic actual cursor.
      begin(badSeed=true);acceptFrame();acceptRange(0,seed,4);retireRange(4,7,middle,13,7,5)
      endFrame(status=4,gs=13,received=7,admitted=5,tasks=4,next=113,bin=5)
      finish(3,1,4,7,2,13,113,5,stage=7)
      // Frame totals come only from the actual frame report; a mismatched GS
      // total rejects completion even after both ranges retired normally.
      begin();acceptFrame();acceptRange(0,seed,4);retireRange(4,7,middle,13,7,5)
      acceptRange(1,middle,6);retireRange(6,11,last,9,8,6)
      endFrame(gs=23,received=15,admitted=11,tasks=10,next=122,bin=11)
      finish(3,2,10,18,2,23,122,11,stage=6)
      // The output cursor is exclusive and may legitimately reach 2^64.
      val top64=BigInt(1)<<64
      val endpoint=Cursor(20,30,41,10,50,top64,1)
      begin(n=1,output=top64-1);acceptFrame();acceptRange(0,Cursor(10,20,31,0,40,top64-1,0),10,top64-1)
      retireRange(10,10,endpoint,1,1,1);endFrame(gs=1,received=1,admitted=1,tasks=10,next=top64,bin=1)
      finish(0,1,10,10,1,1,top64,1)
      // A held real metadata request survives cancellation; its actual memory
      // error outranks cancel. No frame/range peer is offered for this case.
      begin(n=0);m.until("GNF request offer")(d.io.memoryRequest.valid.peek().litToBoolean &&
        d.io.memoryRequest.bits.address.peek().litValue==0x2000)
      m.holdRequest=true;m.tick();d.io.cancel.poke(true.B)
      for(_<-0 until 3){m.tick();d.io.out.valid.expect(false.B)}
      m.holdReply=true;m.holdRequest=false;m.until("held metadata accepted")(m.reads.size==9)
      for(_<-0 until 3){m.tick();d.io.out.valid.expect(false.B);d.io.drained.expect(false.B)}
      m.faultAt=Set(BigInt(0x2000));m.holdReply=false;finish(2,0,0,0,0,0,0,0,stage=2);m.faultAt=Set.empty
      begin(n=0);acceptFrame(empty=true);endFrame(gs=0,received=0,admitted=0,tasks=0,next=0,bin=0)
      finish(0,0,0,0,0,0,0,0,clear=true)
    }
  }
  it should "remain default closed without metadata, frame or range traffic" in {
    simulate(new JointGeometryNativeObserved(0,new TessellationPatchSequenceDescriptor(config,p,12),enabled=false)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      d.io.fd.poke(true.B);d.io.rd.poke(true.B)
      val m=new Memory(d.clock,d.io.memoryRequest,d.io.memoryResponse)
      request(d.io.q.bits,0);d.io.q.valid.poke(true.B)
      m.until("default-closed submit")(d.io.q.ready.peek().litToBoolean);m.tick();d.io.q.valid.poke(false.B)
      m.until("default-closed report")(d.io.out.valid.peek().litToBoolean)
      d.io.out.bits.base.status.expect(6.U);d.io.out.bits.base.readBytes.expect(0.U)
      d.io.fv.expect(false.B);d.io.rv.expect(false.B);d.io.end.valid.expect(false.B)
      assert(m.reads.isEmpty && m.empty);d.io.drained.expect(true.B)
      d.io.out.ready.poke(true.B);m.tick();d.io.out.ready.poke(false.B);d.io.busy.expect(false.B)
    }
  }
  it should "retain real isoline metadata and independent GS cursors through completion and drain" in {
    run(0,new TessellationPatchSequenceDescriptor(config,p,12))
  }
  it should "retain real triangle metadata and independent GS cursors through completion and drain" in {
    run(1,new TessellationTrianglePatchSequenceDescriptor(config,p,12))
  }
  it should "retain real quad metadata and independent GS cursors through completion and drain" in {
    run(2,new TessellationQuadPatchSequenceDescriptor(config,p,12))
  }
}
