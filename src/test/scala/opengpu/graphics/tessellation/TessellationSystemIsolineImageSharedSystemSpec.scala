package opengpu.graphics.tessellation

import opengpu.graphics._
import opengpu.graphics.tessellation._

import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.core.memory._
import opengpu.system._
import opengpu.testutil.GpuSim._
import opengpu.testutil.GraphicsTestResolution
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class TessellationSystemIsolineImageSharedHarness(transformFeedback:Boolean=false,sampleImages:Boolean=false) extends Module {
  val cfg=GpuConfig(lanes=if(sampleImages)16 else 4,warps=if(sampleImages)8 else 2,l2Sets=8,l2Ways=2)
  val p=if(sampleImages)VertexShaderStageConfig()else VertexShaderStageConfig(VertexInputFetchConfig(2,2),2)
  val f=FragmentShaderConfig(if(sampleImages)16 else 2,4)
  val g=GraphicsConfig(screenWidth=math.min(4,GraphicsTestResolution.width),screenHeight=math.min(4,GraphicsTestResolution.height))
  val payload=ShaderPayloadConfig(capacityBytes=if(sampleImages)524288 else 4096,maxPayloadBytes=if(sampleImages)65536 else 1024,owners=3,ports=6)
  val hostSlots=TessellationSystemIsolineImagePipeline.memoryTransactions(2,2,8)
  private val slots=GpuSystem.totalMemoryTransactions(2,if(sampleImages)8 else 4,hostSlots)
  val io=IO(new Bundle {
    val begin=Flipped(Decoupled(new TypedVertexBinImageDescriptor))
    val task=Flipped(Decoupled(new TessellationSystemIsolineBinTask(cfg,p,4)))
    val taskCompletion=Decoupled(new TessellationSystemIsolineBinTaskCompletion)
    val end=Flipped(Decoupled(Bool()))
    val opened=Decoupled(UInt(2.W));val seal=Flipped(Decoupled(Bool()))
    val inspect=Flipped(Decoupled(new TessellationPatchAccess));val reply=Decoupled(new TessellationContextReply)
    val primitiveDone=Vec(2,Decoupled(new TypedReplayPrimitiveCompletion))
    val out=Vec(2,Decoupled(new TypedTilePixelQuadCompletion))
    val tileDone=Vec(2,Decoupled(new TypedVertexBinImageTileCompletion))
    val completion=Decoupled(new TessellationSystemIsolineImageCompletion(p))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(cfg,64,slots))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,slots)))
    val cancel=Input(Bool());val context=Input(UInt(32.W))
    val feedback=if(transformFeedback)Some(new TransformFeedbackPipelineIO(cfg))else None
    val imageLaunch=Output(Bool());val controlLaunch=Output(Bool());val imageRoot=Output(UInt(32.W))
    val imageLease=Output(Bool())
    val idle=Output(Bool());val reserved=Output(UInt(32.W));val poolDrained=Output(Bool())
  })
  private val stage=Module(new TessellationSystemIsolineImagePipeline(cfg,p,f,SharedCuShaderConfig(2,taskIdWidth=4),
    g=g,engines=2,pageBytes=128,writeCredits=2,tileSlots=1,pagedCapacity=8,storage=if(sampleImages)NativeBinnedGraphicsConfig.fourEngineApiExtent.storage else TypedTileColorConfig(),
    payload=Some(payload),transformFeedback=transformFeedback,
    fragmentImageAvailable=sampleImages,sampleShading=sampleImages))
  val recordBytes=stage.recordBytes
  private val system=Module(new GpuSystem(cfg,numComputeUnits=2,commandIdWidth=4,transactionsPerCu=if(sampleImages)8 else 4,
    enableGraphicsKernels=true,imageAvailable=sampleImages,graphicsImageAvailable=sampleImages,geometryAvailable=true,tessellationAvailable=true,graphicsHostTransactions=hostSlots,
    instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1,
    shaderPayload=Some(payload),producerPayloadPins=TessellationSystemIsolineImagePipeline.payloadPins(2)))
  private def tie(d:Data):Unit=d match {
    case r:Record=>r.elements.values.foreach(tie);case v:Vec[_]=>v.foreach(tie)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b:=false.B
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s:=0.S
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u:=0.U
    case _=>()
  }
  tie(system.io);tie(stage.io)
  stage.io.begin<>io.begin;stage.io.task<>io.task;io.taskCompletion<>stage.io.taskCompletion
  stage.io.end<>io.end;io.opened<>stage.io.opened;stage.io.seal<>io.seal
  stage.io.inspect<>io.inspect;io.reply<>stage.io.reply
  io.primitiveDone<>stage.io.primitiveDone;io.out<>stage.io.out
  io.tileDone<>stage.io.tileDone;io.completion<>stage.io.completion;stage.io.cancel:=io.cancel
  if(transformFeedback)io.feedback.get<>stage.io.feedback.get
  system.io.graphicsKernels.get<>stage.io.shared
  io.imageLease:=system.io.imageLease.getOrElse(false.B)
  io.imageLaunch:=false.B;io.controlLaunch:=false.B;io.imageRoot:=0.U
  if(sampleImages){
    io.imageLaunch:=stage.io.shared.launch.fire && stage.io.shared.imageTask.get.enabled
    io.controlLaunch:=stage.io.shared.launch.fire && stage.io.shared.tessellationBases.get.valid
    io.imageRoot:=stage.io.shared.imageTask.get.table.base
    when(stage.io.shared.launch.valid && (stage.io.shared.tessellationBases.get.valid || stage.io.shared.geometryTask.get.valid)){
      assert(!stage.io.shared.imageTask.get.asUInt.orR,"patch launch inherited fragment image publication")
    }
  }
  private val hostQueue=Module(new Queue(new ComputeMemoryRequest(cfg,64,hostSlots),2,pipe=true))
  hostQueue.io.enq<>stage.io.memoryRequest;system.io.graphicsHostRequest<>hostQueue.io.deq;stage.io.memoryResponse<>system.io.graphicsHostResponse
  private val lowerQueue=Module(new Queue(new ComputeMemoryRequest(cfg,64,slots),2,pipe=true))
  lowerQueue.io.enq<>system.io.memoryRequest;io.memoryRequest<>lowerQueue.io.deq;system.io.memoryResponse<>io.memoryResponse
  system.io.graphicsDrained:= !stage.io.busy && hostQueue.io.count===0.U
  system.io.graphicsShaderL1Invalidate.ready:=true.B
  val management=system.io.payloadManagement.get
  for(i<-0 until 3){val port=stage.io.payload.get(i)
    management.control(i)<>port.control;port.controlResponse<>management.controlResponse(i)
    management.request(i)<>port.request;port.response<>management.response(i)
    port.context:=io.context;port.cancel:=false.B}
  system.io.graphicsPayload.get:=stage.io.poolPayload.get;system.io.payloadProducerPins.get:=stage.io.payloadPins.get
  io.reserved:=management.reservedBytes;io.poolDrained:=management.drained;io.idle:=system.io.vmContextIdle && !stage.io.busy
}

class TessellationSystemIsolineImageSharedSystemSpec extends AnyFlatSpec {
  protected def sampleImageQualification:Boolean=false
  protected def sampleImageOpcodes:Boolean=false
  import WindowRecordTest.{zero,Memory}
  import TypedColorReference.fp
  private def vector(fn:Int,form:Int,rd:Int,vs1:Int,vs2:Int=0):BigInt=
    (BigInt(fn)<<26)|(BigInt(1)<<25)|(BigInt(vs2)<<20)|(BigInt(vs1)<<15)|(BigInt(form)<<12)|(BigInt(rd)<<7)|0x57
  private def patch(fn:Int,vs1:Int=0,vs2:Int=0):BigInt=
    (BigInt(fn)<<26)|(BigInt(1)<<25)|(BigInt(vs2)<<20)|(BigInt(vs1)<<15)|0x2b
  private def geometry(fn:Int,rd:Int=0,rs1:Int=0,rs2:Int=0):BigInt=
    (BigInt(fn)<<25)|(BigInt(rs2)<<20)|(BigInt(rs1)<<15)|(BigInt(rd)<<7)|0x0b
  private def addi(rd:Int,rs:Int,n:Int):BigInt=(BigInt(n&4095)<<20)|(BigInt(rs)<<15)|(BigInt(rd)<<7)|0x13
  private def vm(base:Int,v:Int,write:Boolean):BigInt=(BigInt(1)<<25)|(BigInt(base)<<15)|(BigInt(6)<<12)|(BigInt(v)<<7)|(if(write)0x27 else 7)
  private val controlProgram=Vector(BigInt("0104f057",16),BigInt("3f800537",16),vector(23,4,3,10),vector(23,3,2,0),
    vector(23,3,4,7),patch(20,vs1=3),patch(22,vs1=2,vs2=4),vector(23,3,4,15),patch(22,vs1=2,vs2=4),
    vector(23,3,4,3),patch(22,vs1=2,vs2=4),BigInt("0000000b",16),BigInt("30500073",16))
  private val isolineProgram=Vector(BigInt("00000093",16),BigInt("00000113",16),geometry(32,3,1,2),
    BigInt("00000213",16),geometry(33,0,4,3),BigInt("00100113",16),geometry(32,3,1,2),
    BigInt("00100213",16),geometry(33,0,4,3),BigInt("3f8002b7",16),BigInt("00300213",16),
    geometry(33,0,4,5),BigInt("00300113",16),geometry(32,3,1,2),BigInt("00400213",16),
    geometry(33,0,4,3),geometry(35),geometry(36),BigInt("30500073",16))
  private def add(rd:Int,a:Int,b:Int):BigInt=(BigInt(b)<<20)|(BigInt(a)<<15)|(BigInt(rd)<<7)|0x33
  private val fragmentProgram={
    val stride=32;val inputs=10
    val code=mutable.ArrayBuffer[BigInt]((BigInt(2)<<20)|(BigInt(8)<<15)|(BigInt(1)<<12)|(BigInt(7)<<7)|0x13,
      add(7,1,7),(BigInt(3)<<30)|(BigInt(0x10)<<20)|(BigInt(4)<<15)|(BigInt(7)<<12)|0x57)
    code+=addi(5,7,6*stride);code+=vm(5,1,false);code+=addi(6,7,inputs*stride);code+=vm(6,1,true)
    code+=BigInt("30500073",16);code.toVector
  }
  // Explicit TES recipe: clip (u,-0.25,0,1), u=0..1. With the 4x4
  // viewport this is the fixed width-one line (2,1.5)..(4,1.5).
  private val sampleEvaluationProgram=Vector(addi(1,0,0),addi(2,0,0),geometry(32,3,1,2),
    addi(4,0,0),geometry(33,0,4,3),BigInt("be8001b7",16),addi(4,0,1),geometry(33,0,4,3),
    BigInt("3f8002b7",16),addi(4,0,3),geometry(33,0,4,5),addi(4,0,4),geometry(33,0,4,5),
    geometry(35),geometry(36),BigInt("30500073",16))
  private def image(fn:Int,pair:Int=0,rd:Int=0,vs1:Int=0,vs2:Int=0):BigInt=
    (BigInt(fn)<<26)|(BigInt(1)<<25)|(BigInt(vs2)<<20)|(BigInt(vs1)<<15)|
      (BigInt(pair)<<12)|(BigInt(rd)<<7)|0x2b
  private def imageConstant(sample:Int,component:Int):BigInt=BigInt(0x53000000)+(sample<<12)+component*0x101
  private def sampleProgram:Vector[BigInt]={
    // Full ABI6: 128 threads, 16 varyings, four raw MRTs. Export SampleID,
    // SamplePosition.xy and SampleMaskIn without float/uint reinterpretation.
    val code=mutable.ArrayBuffer[BigInt](BigInt("00241393",16),add(7,1,7),BigInt("c1087057",16))
    def address(rd:Int,array:Int):Unit={
      val offset=array*512
      code+=((BigInt((offset+2048)>>12)<<12)|(BigInt(rd)<<7)|0x37)
      code+=addi(rd,rd,offset&4095);code+=add(rd,7,rd)
    }
    def read(array:Int,v:Int):Unit={address(5,array);code+=vm(5,v,false)}
    def write(array:Int,v:Int):Unit={address(6,array);code+=vm(6,v,true)}
    for(location<-0 until(if(sampleImageOpcodes)2 else 4);(source,c)<-Seq(72,73,74,5).zipWithIndex){
      read(source,1);write(75+location*4+c,1)
    }
    if(sampleImageOpcodes){
      // Actual CUSTOM-1 image ISA from GpuComputeUnitImageSpec. GIU1 unit0
      // is read-only; return its independently initialized raw uint tuple.
      for((source,v)<-Seq(72->4,73->5,74->6,5->7,0->8,1->9))read(source,v)
      code+=vector(23,3,2,0) // unit0
      val control=(TextureImageFormat.id("RGBA32UI")<<6)|40 // 2D,uint,format-qualified
      code+=addi(13,0,control);code+=vector(23,4,3,13)
      code+=vector(23,3,16,0) // z and reserved are zero
      code+=image(8,vs1=2,vs2=3);code+=image(8,pair=1,vs1=16,vs2=16)
      code+=image(9,rd=10,vs1=4,vs2=16) // imageLoad(unit0,SampleID,0)
      for(c<-1 until 4)code+=image(11,pair=c,rd=10+c)
      for(c<-0 until 4)write(87+c,10+c) // MRT3, before pair0 invalidates the load snapshot
      // Distinct helper rows make a missing invocation side-effect mask
      // observable in RW backing. Only the fixed 4x4 line is qualified.
      code+=vector(0x28,3,14,22,8);code+=vector(0x09,3,14,1,14) // (rawX>>22)&1
      code+=vector(0x28,3,15,23,9);code+=vector(0x09,3,15,3,15) // (rawY>>23)&3
      code+=vector(0x25,3,17,2,4);code+=vector(0,0,15,17,15) // 4*SampleID+rowClass
      code+=vector(23,3,2,1);code+=image(8,vs1=2,vs2=3) // unit1
      code+=image(8,pair=1,vs1=16,vs2=16)
      code+=image(8,pair=2,vs1=4,vs2=5);code+=image(8,pair=3,vs1=6,vs2=7)
      code+=image(10,vs1=14,vs2=15) // real imageStore, helpers suppressed by CU policy
      code+=image(9,rd=10,vs1=14,vs2=15) // dependent read after the store's ACK
      for(c<-1 until 4)code+=image(11,pair=c,rd=10+c)
      for(c<-0 until 4)write(83+c,10+c) // MRT2 proves actual store/load result
    }
    code+=BigInt("30500073",16);require(code.size<=512);code.toVector
  }
  for(testMode<-(if(sampleImageQualification)Seq("sample-images-0","sample-images-1","sample-images-2")
    else Seq("baseline","capture","discard","fault","reset","reset_fault")))
  it should s"shade controlled isoline patches into typed images with feedback mode=$testMode" in {
    val sampleImages=sampleImageQualification;val sampleMode=if(sampleImages)testMode.last.asDigit else 0
    require(!sampleImageOpcodes || sampleImages)
    if(sampleImages)require(sys.props.get(if(sampleImageOpcodes)"opengpu.tessellationSampleImageOpsQualification"else "opengpu.tessellationSampleImageQualification").contains("1"),
      "select the full-capacity component qualification explicitly")
    val enabled= !sampleImages && testMode!="baseline";val fault=testMode=="fault" || testMode=="reset_fault"
    val reset=testMode.startsWith("reset");val discard=testMode=="discard"
    val frameStatus=if(fault)5 else if(reset)4 else 0
    simulate(new TessellationSystemIsolineImageSharedHarness(transformFeedback=enabled,sampleImages=sampleImages)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
      val memory=new Memory(d.io.memoryRequest.bits,d.io.memoryResponse.bits,d.io.memoryRequest.valid,d.io.memoryRequest.ready,
        d.io.memoryResponse.valid,d.io.memoryResponse.ready)
      val feedbackMemory=d.io.feedback.map(f=>new Memory(f.memoryRequest.bits,f.memoryResponse.bits,f.memoryRequest.valid,
        f.memoryRequest.ready,f.memoryResponse.valid,f.memoryResponse.ready))
      feedbackMemory.foreach{m=>m.delay=24;if(fault)m.faultAt=Set(BigInt(0x700000))}
      var imageLaunches=0;var controlLaunches=0
      var delayedStore=false;var delayedStoreUntil=0;var heldStoreCycles=0
      var immutableImageBytes=Map.empty[BigInt,Int]
      if(sampleImageOpcodes)memory.beforeRequest=r=>{
        if(!delayedStore && r.write && r.address>=0x710000 && r.address<0x711000){
          delayedStore=true;delayedStoreUntil=memory.cycle+12;memory.holdResponses=true
        }
      }
      var recorded=0;var resetIssued=false;var pendingEnd=false
      controlProgram.zipWithIndex.foreach{case(w,i)=>memory.putWord(0x1000+i*4,w)}
      (if(sampleImages)sampleEvaluationProgram else isolineProgram).zipWithIndex.foreach{case(w,i)=>memory.putWord(0x3000+i*4,w)}
      (if(sampleImages)sampleProgram else fragmentProgram).zipWithIndex.foreach{case(w,i)=>memory.putWord(0x2000+i*4,w)}
      val fields=TypedFragmentStateLayout.fields(new TypedFragmentState(FragmentShaderConfig(if(sampleImages)16 else 2,4)))
      val clear=Vector(0.1,0.2,0.3,0.4).map(fp)
      def identity(i:GraphicsWorkIdentity):Unit={i.context.poke(7.U);i.pass.poke(8.U);i.generation.poke(9.U)}
      def tick():Unit={
        if(sampleImageOpcodes && delayedStore && memory.holdResponses){
          if(memory.cycle>=delayedStoreUntil)memory.holdResponses=false
          else {
            assert(memory.pending.exists(r=>r.write && r.address>=0x710000 && r.address<0x711000),
              "delayed accepted store lost its response owner")
            d.io.completion.valid.expect(false.B);d.io.idle.expect(false.B);d.io.imageLease.expect(true.B)
            heldStoreCycles+=1
          }
        }
        memory.drive();feedbackMemory.foreach(_.drive())
        if(sampleImages && d.io.imageLaunch.peek().litToBoolean){imageLaunches+=1;d.io.imageRoot.expect(0x9000.U)}
        if(sampleImages && d.io.controlLaunch.peek().litToBoolean)controlLaunches+=1
        for(e<-0 until 2){d.io.out(e).ready.poke(true.B);d.io.primitiveDone(e).ready.poke(true.B);d.io.tileDone(e).ready.poke(true.B)}
        d.io.feedback.foreach{f=>
          if(pendingEnd)f.command.ready.expect(false.B)
          if(f.recorded.valid.peek().litToBoolean)recorded+=1
        }
        memory.capture();feedbackMemory.foreach{m=>
          val before=m.seen.size;m.capture()
          if(reset && !resetIssued && m.seen.size>before){resetIssued=true;d.io.feedback.get.cancel.poke(true.B)}
        }
        d.clock.step();d.io.feedback.foreach(_.cancel.poke(false.B))
      }
      def until(label:String)(condition: =>Boolean):Unit={var n=0;while(!condition && n<200000){tick();n+=1}
        assert(condition,s"$label stalled at ${memory.cycle} pending=${memory.pending.size}")}
      def feedbackCommand(action:Int):Unit={
        val f=d.io.feedback.get;zero(f.command.bits);f.command.bits.action.poke(action.U)
        val q=f.command.bits.state.descriptor;q.context.poke(7.U);q.cookie.poke(1.U);q.program.poke(9.U)
        if(action==TransformFeedbackAction.begin){q.kind.poke(1.U);q.interleaved.poke(true.B);q.buffers.poke(1.U)
          q.components(0).poke(1.U);q.selectors(0).poke(0.U);q.views(0).base.poke(0x700000.U);q.views(0).limit.poke(0x700100.U)}
        f.command.valid.poke(true.B)
      }
      if(enabled){
        val f=d.io.feedback.get;val scope=f.scope;scope.context.poke(7.U);scope.root.poke(0.U);scope.program.poke(9.U)
        scope.mode.poke(1.U);scope.cookie.poke(1.U);scope.rasterDiscard.poke(discard.B)
        feedbackCommand(TransformFeedbackAction.begin);until("feedback begin")(f.command.ready.peek().litToBoolean)
        tick();f.command.valid.poke(false.B);until("feedback begin reply")(f.commandReply.valid.peek().litToBoolean)
        f.commandReply.bits.status.expect(0.U);f.commandReply.ready.poke(true.B);tick();f.commandReply.ready.poke(false.B)
      }
      val stateBase=0x8000;for(w<-0 until 128)memory.putWord(stateBase+w*4,0)
      for((v,i)<-Vector[BigInt](TypedFragmentStateLayout.magic,512,1,4,7,8,9,0).zipWithIndex)memory.putWord(stateBase+i*4,v)
      val scratch0=0x100000;val scratch1=if(sampleImages)0x140000 else 0x110000
      val abi=if(sampleImages)FragmentInputAbi.sample else FragmentInputAbi.center
      val prefix=FragmentShaderConfig(if(sampleImages)16 else 2,4).prefixBytes(if(sampleImages)128 else 8,
        if(sampleImages)16 else 1,if(sampleImages)4 else 1,abi)
      val values=Map[String,BigInt]("version"->1,"engineCount"->2,"fragment.version"->abi,"fragment.identity.context"->7,"fragment.identity.pass"->8,"fragment.identity.generation"->9,
        "fragment.shaderPc"->0x2000,"fragment.varyingSlots"->(if(sampleImages)16 else 1),"fragment.colorSlots"->(if(sampleImages)4 else 1),"fragment.colorMask"->(if(sampleImages)15 else 1),"fragment.sampleMode"->sampleMode,
        "fragment.sampler.texWidth"->1,"fragment.sampler.texHeight"->1,
        "fragment.scratch.base"->scratch0,"fragment.scratch.limit"->(scratch0+prefix),"fragment.scratch.allocationBase"->scratch0,"fragment.scratch.allocationLimit"->(scratch0+prefix),
        "scratch[0].base"->scratch1,"scratch[0].limit"->(scratch1+prefix),"scratch[0].allocationBase"->scratch1,"scratch[0].allocationLimit"->(scratch1+prefix),
        "tests.version"->1,"tests.depthEnable"->0,"tests.front.function"->6,"tests.back.function"->6,"blend.version"->1) ++
        (if(sampleImages)Map[String,BigInt]("image.version"->1,"image.address"->0x8800) ++
          (0 until 4).map(i=>s"fragment.colorTypes[$i]"->BigInt(1))else Map.empty[String,BigInt])
      if(sampleImages){
        for((v,w)<-Vector[BigInt](ShaderImagePublicationLayout.magic,64,1,7,8,9,1,1,0x9000,0x9400,0).zipWithIndex)
          memory.putWord(0x8800+4*w,v)
      }
      if(sampleImageOpcodes){
        val format=TextureImageFormat.id("RGBA32UI")
        def record(unit:Int,allocation:Int,base:Int,width:Int,height:Int,pitch:Int,writable:Boolean):Unit={
          val words=Array.fill[BigInt](ShaderImageDescriptorLayout.words)(0)
          for((word,value)<-Seq[(Int,BigInt)](0->ShaderImageDescriptorLayout.magic,1->256,2->1,
            3->7,4->8,5->9,6->unit,7->1,8->1,9->2,10->1,11->(if(writable)1 else 0),
            12->format,13->format,14->1,22->allocation,23->(allocation+4096),25->base,
            26->width,27->height,28->1,29->1,30->pitch,37->16))words(word)=value
          words.zipWithIndex.foreach{case(value,word)=>memory.putWord(0x9000+unit*256+word*4,value)}
        }
        // Preserve the complete published table allocation, including unused
        // records. All reserved words remain zero, not Bundle-packed data.
        for(a<-0x9000 until 0x9400)memory.bytes(a)=0
        for(unit<-2 until 4;(value,word)<-Vector[BigInt](ShaderImageDescriptorLayout.magic,256,1,7,8,9,unit).zipWithIndex)
          memory.putWord(0x9000+unit*256+word*4,value) // unaccessed slots; no binding acceptance asserted
        for(a<-0x700000 until 0x701000)memory.bytes(a)=0xa5
        for(a<-0x710000 until 0x711000)memory.bytes(a)=0xa5
        record(0,0x700000,0x70003f,4,1,64,writable=false)
        record(1,0x710000,0x71003f,4,16,128,writable=true)
        for(s<-0 until 4;c<-0 until 4)memory.putWord(0x70003f+s*16+c*4,imageConstant(s,c))
        immutableImageBytes=((0x8800 until 0x8840)++(0x9000 until 0x9400)++(0x700000 until 0x701000))
          .map(a=>BigInt(a)->memory.bytes.getOrElse(BigInt(a),0)).toMap
      }
      for(field<-fields){val raw=values.getOrElse(field.name,BigInt(0))&((BigInt(1)<<(field.words*32))-1)
        for(w<-0 until field.words)memory.putWord(stateBase+(field.word+w)*4,(raw>>(w*32))&0xffffffffL)}

      val dsc=d.io.begin.bits;zero(dsc);dsc.version.poke(1.U)
      val a=dsc.bins.producer;a.version.poke(1.U);a.arena.version.poke(1.U);identity(a.arena.identity)
      a.arena.base.poke(0x200000.U);a.arena.limit.poke((0x200000+32*d.recordBytes).U)
      a.screenWidth.poke(4.U);a.screenHeight.poke(4.U);a.tileLog2.poke(3.U)
      val b=dsc.bins.bins;b.version.poke(3.U);b.tileCount.poke(1.U);b.directoryBase.poke(0x500000.U);b.directoryLimit.poke(0x500080.U)
      b.pool.version.poke(1.U);identity(b.pool.identity);b.pool.base.poke(0x400000.U);b.pool.limit.poke(0x410000.U);b.pool.pageSizeLog2.poke(7.U)
      dsc.states.version.poke(1.U);identity(dsc.states.identity);dsc.states.base.poke(0x8000.U);dsc.states.limit.poke(0x8400.U);dsc.scratchStride.poke((if(sampleImages)65536 else 4096).U)
      for(e<-0 until 2){dsc.scratch(e).base.poke((0x100000+e*(if(sampleImages)0x40000 else 0x10000)).U);dsc.scratch(e).limit.poke(((if(sampleImages)0x120000 else 0x102000)+e*(if(sampleImages)0x40000 else 0x10000)).U)}
      val im=dsc.image;im.version.poke(1.U);im.pixels.version.poke(1.U);val c=im.pixels.colors;c.version.poke(1.U);identity(c.identity)
      c.tileLog2.poke(3.U);c.tileWidth.poke(4.U);c.tileHeight.poke(4.U);c.reservedBytes.poke(4096.U)
      val color=c.colors(0);color.enabled.poke(true.B);color.format.poke(TypedColorFormat.id("RGBA8").U);color.offset.poke(4096.U)
      color.channelMask.poke(15.U);color.loadOp.poke(AttachmentLoadOp.clear.U);color.clear.zip(clear).foreach{case(x,y)=>x.poke(y.U)}
      val view=im.views(0);view.base.poke(0x600000.U);view.limit.poke(0x600040.U)
      view.allocationBase.poke(0x600000.U);view.allocationLimit.poke(0x600040.U)
      view.imageWidth.poke(4.U);view.imageHeight.poke(4.U);view.rowPitch.poke(16.U)
      view.readable.poke(true.B);view.writable.poke(true.B);view.store.poke(true.B)
      for(a<-0x600000 until 0x600040)memory.bytes(a)=0
      if(sampleImages){
        c.sampleMode.poke(sampleMode.U)
        for(i<-0 until 4){
          val v=c.colors(i);v.enabled.poke(true.B);v.format.poke(TypedColorFormat.id("RGBA32UI").U)
          v.offset.poke((4096+i*4096).U);v.channelMask.poke(15.U);v.loadOp.poke(AttachmentLoadOp.clear.U)
          v.clear.foreach(_.poke(0.U))
          val out=im.views(i);val base=0x600000+i*0x10000;val bytes=16*16*(1<<sampleMode)
          out.base.poke(base.U);out.limit.poke((base+bytes).U);out.allocationBase.poke(base.U)
          out.allocationLimit.poke((base+bytes).U);out.imageWidth.poke(4.U);out.imageHeight.poke(4.U)
          out.rowPitch.poke((4*16*(1<<sampleMode)).U);out.readable.poke(true.B);out.writable.poke(true.B);out.store.poke(true.B)
          for(a<-base-64 until base+bytes+64)memory.bytes(a)=0xa7
        }
      }
      d.io.context.poke(101.U);d.io.begin.valid.poke(true.B);until("begin")(d.io.begin.ready.peek().litToBoolean);tick();d.io.begin.valid.poke(false.B)
      if(enabled){feedbackCommand(TransformFeedbackAction.end);pendingEnd=true}

      def drivePatch(taskId:Int):Unit={
        val t=d.io.task.bits;val view=t.viewport;val src=view.system.patch;val ctl=src.control;zero(t)
        identity(view.identity)
        view.viewport.version.poke(1.U);view.viewport.extentX.poke(4.U);view.viewport.extentY.poke(4.U);view.viewport.farDepth.poke("h3f800000".U)
        src.commandId.poke(3.U);src.evaluationKernel.kernelPc.poke(0x3000.U);src.outputComponents.poke(5.U)
        src.patchInputCount.poke(1.U);src.patchInputs(0).perPatch.poke(true.B)
        for(i<-Seq(0,1,3)){view.componentSources(i).valid.poke(true.B);view.componentSources(i).bits.poke(i.U)}
        view.varyingSlots.poke((if(sampleImages)16 else 1).U);view.componentSources(4).valid.poke(true.B);view.componentSources(4).bits.poke(4.U)
        ctl.patch.context.taskId.poke(taskId.U);ctl.patch.context.layout.inputVertices.poke(1.U)
        ctl.patch.context.layout.outputVertices.poke(4.U);ctl.patch.context.layout.patchComponents.poke(1.U)
        ctl.kernel.kernelPc.poke(0x1000.U);for(i<-0 until 3){ctl.kernel.gridSize(i).poke(1.U);ctl.kernel.localSize(i).poke((if(i==0)4 else 1).U)}
        ctl.bases(0).poke(0.U);ctl.bases(1).poke(4.U)
        t.raster.version.poke(1.U);t.raster.sampleMode.poke(sampleMode.U);t.raster.pointSize.poke("h3f800000".U);if(sampleImages)t.raster.lineWidth.poke(fp(1).U);t.raster.screenW.poke(4.U);t.raster.screenH.poke(4.U)
        t.state.poke(0.U);t.token.poke((200+taskId).U)
        d.io.feedback.foreach{f=>f.scope.context.poke(7.U);f.scope.program.poke(9.U)}
        d.io.task.valid.poke(true.B);until("task")(d.io.task.ready.peek().litToBoolean);tick();d.io.task.valid.poke(false.B)
        d.io.feedback.foreach{f=>f.scope.context.poke(99.U);f.scope.program.poke(99.U)}
        d.io.opened.ready.poke(true.B);until("open")(d.io.opened.valid.peek().litToBoolean);d.io.opened.bits.expect(0.U)
        tick();d.io.opened.ready.poke(false.B);d.io.seal.valid.poke(true.B)
        until("seal")(d.io.seal.ready.peek().litToBoolean);tick();d.io.seal.valid.poke(false.B)
        until("task completion")(d.io.taskCompletion.valid.peek().litToBoolean)
        d.io.taskCompletion.bits.status.expect(frameStatus.U)
        if(enabled)d.io.taskCompletion.bits.source.feedbackStatus.expect((if(fault)3 else if(reset)4 else 0).U)
        if(frameStatus==0 && !discard)assert(d.io.taskCompletion.bits.admitted.peek().litValue>=1)
        else d.io.taskCompletion.bits.admitted.expect(0.U)
        d.io.taskCompletion.ready.poke(true.B);tick();d.io.taskCompletion.ready.poke(false.B)
      }
      drivePatch(19);if(frameStatus==0)drivePatch(20)
      if(frameStatus==0){d.io.end.valid.poke(true.B);d.io.end.bits.poke(true.B);until("end")(d.io.end.ready.peek().litToBoolean);tick();d.io.end.valid.poke(false.B)}
      until("completion")(d.io.completion.valid.peek().litToBoolean)
      val done=d.io.completion.bits
      assert(done.status.peek().litValue==frameStatus,
        s"frame status=${done.status.peek().litValue} faultStage=${done.faultStage.peek().litValue} "+
          s"faultTile=${done.faultTileValid.peek().litValue}/${done.faultTile.peek().litValue} "+
          s"source.status=${done.source.status.peek().litValue} tasks=${done.source.tasks.peek().litValue} "+
          s"tiles=${done.tiles.peek().litValue} primitives=${done.primitives.peek().litValue} "+
          s"load=${done.loadBytes.peek().litValue} store=${done.storedBytes.peek().litValue} "+
          s"cycles=${memory.cycle} pending=${memory.pending.size} seen=${memory.seen.takeRight(8).map(r=>(r.address.toString(16),r.write))}")
      done.source.tasks.expect((if(frameStatus==0)2 else 1).U)
      if(frameStatus==0 && !discard){assert(done.primitives.peek().litValue>=2);assert(done.tiles.peek().litValue==1)}
      else assert(done.primitives.peek().litValue==0)
      val held=done.peek().litValue;val primitives=done.primitives.peek().litValue
      for(_<-0 until 4){tick();assert(done.peek().litValue==held)}
      d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B)
      if(enabled){
        val f=d.io.feedback.get;pendingEnd=false
        until("feedback end")(f.command.ready.peek().litToBoolean);tick();f.command.valid.poke(false.B)
        until("feedback end reply")(f.commandReply.valid.peek().litToBoolean)
        f.commandReply.bits.status.expect((if(reset)5 else if(fault)3 else 0).U)
        f.commandReply.bits.state.primitives.expect((if(frameStatus==0)2 else 0).U)
        f.commandReply.bits.state.vertices.expect((if(frameStatus==0)4 else 0).U)
        f.commandReply.bits.state.offsets(0).expect((if(frameStatus==0)16 else 0).U)
        f.commandReply.ready.poke(true.B);tick();f.commandReply.ready.poke(false.B)
        assert(recorded==(if(frameStatus==0)2 else 0));if(reset)assert(resetIssued)
        if(frameStatus==0){val fm=feedbackMemory.get
          assert((0 until 4).map(i=>fm.word(0x700000+i*4))==Seq[BigInt](0,BigInt("3f800000",16),0,BigInt("3f800000",16)))}
      }
      until("idle")(d.io.idle.peek().litToBoolean);d.io.reserved.expect(0.U);d.io.poolDrained.expect(true.B)
      assert(memory.pending.isEmpty && memory.reply.isEmpty)
      val reds=(0 until 16).map(i=>memory.bytes.getOrElse(0x600000+i*4,0))
      if(frameStatus==0 && !discard && !sampleImages)assert(reds.contains(255) && reds.contains(26),s"expected shaded red and clear pixels, got $reds")
      if(discard)assert(reds.forall(_==26),s"discarded tessellation changed the clear image: $reds")
      if(sampleImages){
        assert(imageLaunches>0 && controlLaunches>=2,"real patch and image-bound fragment tasks must both execute")
        assert(memory.seen.exists(_.address==0x8800),"the actual GFS publication must be fetched")
        // Expected coverage comes from the fixed TES program and the
        // independent integer diamond/line reference, never returned pixels.
        val unit=BigInt(1)<<d.g.subPixelBits
        val coords=Vector((2*unit,3*unit/2),(4*unit,3*unit/2),(2*unit,3*unit/2))
        val settings=TriangleRasterReference.Settings(width=4,height=4,mode=sampleMode)
        val covered=PointLineReference.samples(coords,1,settings,d.g,PointLineReference.Sizes(line=1)).flatten
          .flatMap(p=>(0 until(1<<sampleMode)).filter(s=>(p.mask&(1<<s))!=0).map(s=>(p.x,p.y,s))).toSet
        for(s<-0 until(1<<sampleMode))assert(covered((2,1,s)),"independent interior sample witness missing")
        for(i<-0 until 4;y<-0 until 4;x<-0 until 4;s<-0 until(1<<sampleMode)){
          val address=0x600000+i*0x10000+((y*4+x)*(1<<sampleMode)+s)*16
          val got=Vector.tabulate(4)(c=>memory.word(address+4*c))
          val px=fp(if(sampleMode==0)0.5 else if((s&1)!=0)0.75 else 0.25)
          val py=fp(if(sampleMode==0)0.5 else if((s&(if(sampleMode==1)1 else 2))!=0)0.75 else 0.25)
          val expected=if(!covered((x,y,s)))Vector.fill(4)(BigInt(0))
            else if(sampleImageOpcodes && i==3)Vector.tabulate(4)(c=>imageConstant(s,c))
            else Vector[BigInt](s,px,py,1<<s)
          assert(got==expected,s"sample MRT=$i ($x,$y,$s): $got expected=$expected")
        }
        for(i<-0 until 4;offset<-( -64 until 0)++(16*16*(1<<sampleMode) until 16*16*(1<<sampleMode)+64))
          assert(memory.bytes(0x600000+i*0x10000+offset)==0xa7,"sample source allocation guard changed")
      }
      if(sampleImageOpcodes){
        assert(delayedStore && heldStoreCycles>=11,"no accepted image store ACK was held")
        immutableImageBytes.foreach{case(address,value)=>
          assert(memory.bytes.getOrElse(address,0)==value,s"image source/publication/table changed at 0x${address.toString(16)}")}
        val expectedRw=mutable.Map.empty[BigInt,Int]
        for(a<-0x710000 until 0x711000)expectedRw(BigInt(a))=0xa5
        val unit=BigInt(1)<<d.g.subPixelBits
        val coords=Vector((2*unit,3*unit/2),(4*unit,3*unit/2),(2*unit,3*unit/2))
        val covered=PointLineReference.samples(coords,1,TriangleRasterReference.Settings(width=4,height=4,mode=sampleMode),
          d.g,PointLineReference.Sizes(line=1)).flatten
          .flatMap(p=>(0 until(1<<sampleMode)).filter(s=>(p.mask&(1<<s))!=0).map(s=>(p.x,p.y,s))).toSet
        for((x,y,s)<-covered){
          assert(y==1 && (x==2 || x==3),"the independent fixed-line recipe changed")
          val rgba=Vector[BigInt](s,fp(if(sampleMode==0)0.5 else if((s&1)!=0)0.75 else 0.25),
            fp(if(sampleMode==0)0.5 else if((s&(if(sampleMode==1)1 else 2))!=0)0.75 else 0.25),1<<s)
          val address=BigInt(0x71003f)+(4*s+3)*128+(x-2)*16
          for(c<-0 until 4;b<-0 until 4)expectedRw(address+c*4+b)=((rgba(c)>>(8*b))&255).toInt
        }
        expectedRw.foreach{case(address,value)=>
          assert(memory.bytes(address)==value,s"image RW/helper/padding guard mismatch at 0x${address.toString(16)}")}
        for(unit<-0 until 2;line<-0 until 4)assert(memory.seen.exists(r=> !r.write && r.address==0x9000+unit*256+line*64),
          "real CU image descriptor lines must be fetched")
        assert(memory.seen.exists(r=> !r.write && r.address>=0x700000 && r.address<0x701000),"RO image load did not reach memory")
        assert(memory.seen.exists(r=>r.write && r.address>=0x710000 && r.address<0x711000),"RW image store did not reach memory")
        assert(memory.seen.exists(r=> !r.write && r.address>=0x710000 && r.address<0x711000),"RW image readback did not reach memory")
        d.io.imageLease.expect(false.B)
        info("TESSELLATION_IMAGE_OPS physical=true actualISA=true loadStoreReadback=true helperRowsGuarded=true delayedStoreAck=true bothCUsOpcodeQualified=false protected112Qualified=false")
      }
      info(s"CONTROLLED_ISOLINE_WINDOW_BIN_TYPED_IMAGE_SHARED imageOpcodes=$sampleImageOpcodes patches=2 primitives=$primitives tiles=1 cycles=${memory.cycle}")
    }
  }
}
