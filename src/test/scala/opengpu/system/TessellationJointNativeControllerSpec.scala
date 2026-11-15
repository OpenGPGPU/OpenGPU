package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.graphics._
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

/** Short observation names avoid DPI identifier truncation. All captures are
  * made by the production readers. External retirement is an explicit execution
  * transport peer here, not proof of shader or clear/store/resolve execution. */
private class JointNativeObserved[D <: Data](domain:Int,sequenceType:D,enabled:Boolean=true) extends Module {
  import TessellationJointReaderTestSupport.config
  private val p=VertexShaderStageConfig()
  val io=IO(new Bundle {
    val q=Flipped(Decoupled(new TessellationJointJobRequest(12)))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,1))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,1)))
    val fv=Output(Bool());val fr=Input(Bool());val f=Output(new TypedVertexBinImageDescriptor);val clear=Output(Bool())
    val fb=Input(Bool());val fd=Input(Bool());val fc=Output(Bool())
    val end=Decoupled(Bool());val ff=Flipped(Decoupled(new TessellationJointFrameExecutionRetirement))
    val rv=Output(Bool());val rr=Input(Bool());val e=Output(new TessellationJointDirectoryCapture(12))
    val c=Output(new TessellationJointPublicationCursor);val g=Output(sequenceType)
    val v=Output(new TessellationVertexCaptureCompletion(p))
    val rb=Input(Bool());val rd=Input(Bool());val rc=Output(Bool())
    val rf=Flipped(Decoupled(new TessellationJointRangeExecutionRetirement))
    val out=Decoupled(new TessellationJointNativeCompletion(12))
    val cancel=Input(Bool());val busy=Output(Bool());val drained=Output(Bool())
  })
  private val child=Module(new TessellationJointNativeController(config,p,12,domain,sequenceType,enabled=enabled))
  child.io.submit<>io.q;io.memoryRequest<>child.io.memoryRequest;child.io.memoryResponse<>io.memoryResponse
  io.fv:=child.io.frame.valid;child.io.frame.ready:=io.fr;io.f:=child.io.frame.bits.descriptor;io.clear:=child.io.frame.bits.clearOnly
  child.io.frameBusy:=io.fb;child.io.frameDrained:=io.fd;io.fc:=child.io.cancelFrame
  io.end<>child.io.end;child.io.frameRetired<>io.ff
  io.rv:=child.io.range.valid;child.io.range.ready:=io.rr;io.e:=child.io.range.bits.directory
  io.c:=child.io.range.bits.cursor;io.g:=child.io.range.bits.captured.gnt.sequence;io.v:=child.io.range.bits.captured.capture
  child.io.rangeBusy:=io.rb;child.io.rangeDrained:=io.rd;io.rc:=child.io.cancelRange;child.io.rangeRetired<>io.rf
  io.out<>child.io.completion;child.io.cancel:=io.cancel;io.busy:=child.io.busy;io.drained:=child.io.drained
}

class TessellationJointNativeControllerSpec extends AnyFlatSpec {
  import TessellationJointReaderTestSupport._
  import TessellationJointCaptureTestPackets._
  private val p=VertexShaderStageConfig()
  private def run[D <: Data](domain:Int,seq: =>D):Unit={
    simulate(new JointNativeObserved(domain,seq)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val m=new Memory(d.clock,d.io.memoryRequest,d.io.memoryResponse)
      val invocation=BigInt("fedcba9876543210",16)
      def install(n:Int=2,zeroWork:Boolean=false,wrongRoot:Boolean=false):Unit={
        m.put(0x1000,job(domain,n));m.put(0x2000,frame(empty=n==0))
        if(n>0){
          m.put(0x8000,directoryHeader(n))
          for(i<-0 until n){
            val per=if(zeroWork)0 else if(n==1)10 else if(i==0)4 else 6
            val task=100+(if(i==0)0 else 4);val root=0xa000+i*1024
            val ent=entry(index=i,id=invocation,flags=if(n==1)3 else if(i==0)1 else 6,ordinal=i,
              firstPatch=if(i==0 || zeroWork)0 else 4,instances=if(zeroWork)0 else 1,per=per,task=task,source=root)
            m.put(0x8040+i*64,ent)
            val g=sequence(domain);g(16)=per;g(219+(if(domain==0)0 else 1))=task
            g(179)=if(i==0)20 else 900;wide(g,180,if(i==0)10 else 800)
            g(509)=if(wrongRoot)0xb000 else root;wide(g,510,if(wrongRoot)0xb200 else root+512)
            m.put(0x4000+i*4096,g)
            val v=source();v(19)=i;v(21)=task;v(22)=per
            v(27)=0xf000;wide(v,28,0xf100);v(30)=0xf010
            v(33)=if(i==0 || zeroWork)0 else 8;v(34)=per;v(36)=if(zeroWork)0 else 1
            m.put(root,v)
          }
          val gnd=draw();gnd(342)=0xf010;gnd(325)=1;if(zeroWork)gnd(311)=0
          m.put(0xc000,gnd)
        }
      }
      def begin(n:Int=2,zeroWork:Boolean=false,wrongRoot:Boolean=false):Unit={
        assert(m.empty);m.clearReads();install(n,zeroWork,wrongRoot)
        d.io.fr.poke(false.B);d.io.rr.poke(false.B);d.io.end.ready.poke(false.B)
        d.io.fb.poke(false.B);d.io.fd.poke(true.B);d.io.rb.poke(false.B);d.io.rd.poke(true.B)
        d.io.cancel.poke(false.B);zero(d.io.ff.bits);zero(d.io.rf.bits)
        request(d.io.q.bits,domain);d.io.q.valid.poke(true.B)
        m.until("native submit")(d.io.q.ready.peek().litToBoolean);m.tick();d.io.q.valid.poke(false.B);zero(d.io.q.bits)
      }
      def acceptFrame(empty:Boolean=false):Unit={
        m.until("actual captured frame offer")(d.io.fv.peek().litToBoolean)
        d.io.clear.expect(empty.B);d.io.f.states.base.expect((if(empty)0 else 0x160000).U)
        d.io.f.image.pixels.colors.sampleMode.expect(2.U)
        for(i<-0 until 4)d.io.f.scratch(i).base.expect((0x180000+i*65536).U)
        d.io.fc.expect(false.B);val held=d.io.f.peek().litValue
        for(_<-0 until 3){m.tick();d.io.fv.expect(true.B);assert(d.io.f.peek().litValue==held)}
        d.io.fr.poke(true.B);m.tick();d.io.fr.poke(false.B);d.io.fb.poke(true.B)
      }
      def acceptRange(index:Int,sequence:BigInt,primitive:BigInt,patches:Int):Unit={
        m.until("actual captured range offer")(d.io.rv.peek().litToBoolean)
        d.io.e.entry.sequenceIndex.expect(index.U);d.io.e.entry.drawIndex.expect(2.U)
        d.io.e.entry.invocationId.expect(invocation.U);d.io.e.entry.patchCount.expect(patches.U)
        d.io.v.root.sequenceIndex.expect(index.U);d.io.v.root.drawIndex.expect(2.U)
        d.io.v.draw.input.count.expect((if(patches==0)0 else 20).U);d.io.v.draw.input.instances.expect(1.U)
        d.io.c.sequence.expect(sequence.U);d.io.c.primitiveId.expect(primitive.U)
        val held=d.io.e.peek().litValue;val raw=d.io.g.peek().litValue
        val before=m.reads.size
        for(_<-0 until 4){m.tick();d.io.rv.expect(true.B);assert(d.io.e.peek().litValue==held && d.io.g.peek().litValue==raw)
          assert(m.reads.size==before,"held directory lease started another range")}
        d.io.rr.poke(true.B);m.tick();d.io.rr.poke(false.B);d.io.rb.poke(true.B);d.io.rd.poke(false.B)
      }
      def retireRange(patches:Int,published:Int,seq:BigInt,prim:BigInt,status:Int=0):Unit={
        zero(d.io.rf.bits);d.io.rf.bits.result.range.completed.poke(patches.U)
        d.io.rf.bits.result.range.status.poke(status.U);d.io.rf.bits.result.published.poke(published.U)
        d.io.rf.bits.nextCursor.sequence.poke(seq.U);d.io.rf.bits.nextCursor.primitiveId.poke(prim.U)
        d.io.rf.valid.poke(true.B);m.until("actual range report accepted")(d.io.rf.ready.peek().litToBoolean)
        m.tick();d.io.rf.valid.poke(false.B)
        val before=m.reads.size
        for(_<-0 until 4){m.tick();assert(m.reads.size==before,"range report advanced directory before task drain")
          d.io.out.valid.expect(false.B);d.io.end.valid.expect(false.B)}
        d.io.rb.poke(false.B);d.io.rd.poke(true.B)
      }
      def retireFrame(status:Int=0,lateFault:Boolean=false):Unit={
        m.until("real end offer")(d.io.end.valid.peek().litToBoolean)
        d.io.out.valid.expect(false.B);d.io.end.ready.poke(true.B);m.tick();d.io.end.ready.poke(false.B)
        identity(d.io.ff.bits.identity);d.io.ff.bits.status.poke(status.U)
        d.io.ff.bits.storedBytes.poke(4096.U);d.io.ff.bits.resolvedBytes.poke(1024.U)
        d.io.ff.valid.poke(true.B);if(lateFault)d.io.cancel.poke(true.B)
        m.until("actual frame report accepted")(d.io.ff.ready.peek().litToBoolean);m.tick();d.io.ff.valid.poke(false.B)
        d.io.fd.poke(false.B)
        for(_<-0 until 3){m.tick();d.io.out.valid.expect(false.B)}
        d.io.fb.poke(false.B);d.io.fd.poke(true.B)
      }
      def finish(status:Int,ranges:Int,patches:Int,published:Int,entries:Int,empty:Boolean=false,stage:Int=0):Unit={
        m.until("native retired report")(d.io.out.valid.peek().litToBoolean)
        val out=d.io.out.bits;out.status.expect(status.U);out.faultStage.expect(stage.U)
        out.retiredRanges.expect(ranges.U);out.retiredPatches.expect(patches.U);out.published.expect(published.U)
        out.capturedEntries.expect(entries.U);out.clearOnly.expect(empty.B);out.readBytes.expect((m.reads.size*64).U)
        out.commandId.expect(5.U)
        out.identity.context.expect(7.U);out.identity.pass.expect(8.U);out.identity.generation.expect(9.U)
        d.io.drained.expect(true.B);val held=out.peek().litValue;d.io.cancel.poke(true.B);d.io.fd.poke(false.B)
        for(_<-0 until 4){m.tick();d.io.out.valid.expect(true.B);assert(out.peek().litValue==held);d.io.q.ready.expect(false.B)}
        d.io.fd.poke(true.B);d.io.out.ready.poke(true.B);m.tick();d.io.out.ready.poke(false.B);d.io.cancel.poke(false.B)
        d.io.busy.expect(false.B);assert(m.empty)
      }
      // Actual 8 GNQ +32 GNF +1 directory header +2 entries +2*72 range lines.
      begin();acceptFrame();acceptRange(0,10,20,4);retireRange(4,7,17,27)
      acceptRange(1,17,27,6);retireRange(6,11,28,0);retireFrame();finish(0,2,10,18,2)
      assert(m.reads.size==187)
      // Neither cancel nor a frame terminal may withdraw an already held range
      // offer. It first transfers, then receives cancellation, then drains.
      begin(n=1);acceptFrame();m.until("held cancellable range")(d.io.rv.peek().litToBoolean)
      val offered=d.io.g.peek().litValue;d.io.cancel.poke(true.B);d.io.rc.expect(false.B)
      for(_<-0 until 3){m.tick();d.io.rv.expect(true.B);assert(d.io.g.peek().litValue==offered)}
      d.io.rr.poke(true.B);m.tick();d.io.rr.poke(false.B);d.io.rb.poke(true.B);d.io.rd.poke(false.B);d.io.rc.expect(true.B)
      retireRange(0,0,10,20,status=4);retireFrame(status=2,lateFault=true);finish(2,0,0,0,1,stage=6)
      // The actual range report has already transferred; its physical drain
      // now coincides with a late actual frame fault. That new fault must
      // block cursor/statistics commit on this very edge, not one cycle later.
      begin();acceptFrame();acceptRange(0,10,20,4);retireRange(4,7,17,27)
      identity(d.io.ff.bits.identity);d.io.ff.bits.status.poke(2.U);d.io.ff.valid.poke(true.B)
      d.io.ff.ready.expect(true.B);m.tick();d.io.ff.valid.poke(false.B)
      m.until("end after simultaneous frame fault and range drain")(d.io.end.valid.peek().litToBoolean)
      d.io.end.ready.poke(true.B);m.tick();d.io.end.ready.poke(false.B)
      d.io.fd.poke(false.B);for(_<-0 until 3){m.tick();d.io.out.valid.expect(false.B)}
      d.io.fb.poke(false.B);d.io.fd.poke(true.B)
      m.until("same-cycle fault native report")(d.io.out.valid.peek().litToBoolean)
      assert(d.io.out.bits.retiredRanges.peek().litValue==0 && d.io.out.bits.published.peek().litValue==0,
        "same-cycle frame fault committed a range cursor/statistics")
      finish(2,0,0,0,1,stage=6)
      assert(!m.reads.contains(BigInt(0x8080)),"late fault released a second directory entry")
      // A wrong directory/GNT source link is rejected before any GVS read.
      begin(n=1,wrongRoot=true);acceptFrame();retireFrame(status=4);finish(1,0,0,0,1,stage=4)
      assert(!m.reads.exists(a=>a>=0xa000 && a<0xc800))
      // A real invocation with count zero still captures all 72 source lines,
      // retires an actual zero-task range, and differs from no-invocation clear.
      begin(n=1,zeroWork=true);acceptFrame();acceptRange(0,10,20,0)
      retireRange(0,0,10,20);retireFrame();finish(0,1,0,0,1);assert(m.reads.size==114)
      // No-state frame remains tied to actual frame report + full drain. The
      // transport peer here does not claim execution of clear/store/resolve.
      begin(n=0);acceptFrame(empty=true);retireFrame();finish(0,0,0,0,0,empty=true)
      assert(m.reads.size==40)
      // A held real request survives cancellation, and the late fault wins.
      begin(n=0);m.until("frame read offer")(d.io.memoryRequest.valid.peek().litToBoolean &&
        d.io.memoryRequest.bits.address.peek().litValue==0x2000)
      m.holdRequest=true;m.tick();d.io.cancel.poke(true.B)
      for(_<-0 until 3){m.tick();d.io.out.valid.expect(false.B)}
      m.holdReply=true;m.holdRequest=false;m.until("cancelled request accepted")(m.reads.size==9)
      for(_<-0 until 3){m.tick();d.io.out.valid.expect(false.B);d.io.drained.expect(false.B)}
      m.faultAt=Set(BigInt(0x2000));m.holdReply=false;finish(2,0,0,0,0,stage=2);m.faultAt=Set.empty
      begin(n=0);acceptFrame(empty=true);retireFrame();finish(0,0,0,0,0,empty=true)
    }
  }
  it should "retain actual isoline directory leases through execution reports and physical drain" in {
    run(0,new TessellationPatchSequenceDescriptor(config,p,12))
  }
  it should "retain actual triangle directory leases through execution reports and physical drain" in {
    run(1,new TessellationTrianglePatchSequenceDescriptor(config,p,12))
  }
  it should "retain actual quad directory leases through execution reports and physical drain" in {
    run(2,new TessellationQuadPatchSequenceDescriptor(config,p,12))
  }
}
