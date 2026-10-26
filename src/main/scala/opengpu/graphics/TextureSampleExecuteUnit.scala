package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.backend.{VectorCommitRequest,VectorTextureRequest}
import opengpu.core.backend.issue.ScalarIssuedInstruction
import opengpu.core.backend.writeback.ScalarCommitRequest
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}

object ShaderTextureSampleOpcode {
  def scalarParameters(i: UInt): Bool=i(6,0)==="h0b".U && i(31,25)===3.U && i(11,7)===0.U
  def vectorParameters(i: UInt): Bool=i(6,0)==="h2b".U && i(31,26)===3.U && i(11,7)===0.U
  def scalarSample(i: UInt): Bool=i(6,0)==="h0b".U && i(31,25)===4.U && i(14,12)<4.U
  def vectorSample(i: UInt): Bool=i(6,0)==="h2b".U && i(31,26)===4.U && i(14,12)<4.U
  def scalarFetch(i: UInt): Bool=i(6,0)==="h0b".U && i(31,25)===5.U && i(14,12)<4.U
  def vectorFetch(i: UInt): Bool=i(6,0)==="h2b".U && i(31,26)===5.U && i(14,12)<4.U
  def scalarSize(i: UInt): Bool=i(6,0)==="h0b".U && i(31,25)===6.U && i(14,12)<3.U
  def vectorSize(i: UInt): Bool=i(6,0)==="h2b".U && i(31,26)===6.U && i(14,12)<3.U
  def scalar(i: UInt): Bool=scalarParameters(i)||scalarSample(i)||scalarFetch(i)||scalarSize(i)
  def vector(i: UInt): Bool=vectorParameters(i)||vectorSample(i)||vectorFetch(i)||vectorSize(i)
}
/** On-chip shader operand tuples, followed by ordinary fault-atomic texture
  * retirement. Eight parameter operations write pairs of32-bit words. Pair0
  * begins a tuple for its active lanes. Trusted task launch clears every valid
  * bit, including when a new batch reuses the same graphics identity.
  */
class TextureSampleExecuteUnit(config: GpuConfig=GpuConfig(),stage: Int=0,groupedTexture: Boolean=false,instructionSnapshots: Boolean=true,streamBurst: Boolean=true) extends Module {
  require(stage==0 || stage==1)
  private val laneWidth=math.max(1,log2Ceil(config.lanes))
  val io=IO(new Bundle {
    val grouped=if(groupedTexture)Some(new NativeRgba8TextureClientIO)else None
    val in=Flipped(Decoupled(new ScalarIssuedInstruction(config)));val vectorIn=Flipped(Decoupled(new VectorTextureRequest(config)))
    val commit=Decoupled(new ScalarCommitRequest(config));val vectorCommit=Decoupled(new VectorCommitRequest(config))
    val resources=Input(new ShaderTextureResources);val identity=Input(new GraphicsWorkIdentity);val clearParameters=Input(Bool())
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,16));val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,16)))
    val cancel=Input(Bool());val invalidate=Input(Bool());val busy=Output(Bool())
    val status=Output(UInt(3.W));val faultAddressValid=Output(Bool());val faultAddress=Output(UInt(32.W))
  })
  private object State extends ChiselEnum {val idle,writeParameters,nextLane,capture,offer,waitSample,commit=Value; val stream=if(groupedTexture)Some(Value)else None}
  private val phase=RegInit(State.idle);private val query=RegInit(false.B);private val integer=RegInit(false.B);private val vector=RegInit(false.B);private val parameter=RegInit(false.B);private val preferVector=RegInit(true.B)
  private val values=Reg(Vec(config.warps,Vec(config.lanes,Vec(16,UInt(32.W)))))
  private val valid=RegInit(VecInit(Seq.fill(config.warps)(VecInit(Seq.fill(config.lanes)(0.U(16.W))))))
  private val resources=Reg(new ShaderTextureResources);private val identity=Reg(new GraphicsWorkIdentity)
  private val warp=Reg(UInt(config.warpIdWidth.W));private val rd=Reg(UInt(5.W));private val pc=Reg(UInt(32.W));private val active=Reg(UInt(config.lanes.W))
  private val mask=Reg(UInt(config.lanes.W));private val lane=Reg(UInt(laneWidth.W));private val pair=Reg(UInt(3.W));private val component=Reg(UInt(2.W))
  private val left=Reg(Vec(config.lanes,UInt(32.W)));private val right=Reg(Vec(config.lanes,UInt(32.W)))
  private val data=Reg(Vec(config.lanes,UInt(32.W)));private val scalarData=Reg(UInt(32.W))
  private val status=RegInit(0.U(3.W));private val faultValid=RegInit(false.B);private val faultAddress=Reg(UInt(32.W))
  private val plannedFetch=Reg(new ShaderTextureFetchRequest(true));private val planned=Reg(new ShaderTextureSampleRequest);private val started=RegInit(false.B);private val noFill=RegInit(false.B)
  private val sample=Module(new ShaderTextureSampleUnit(config,stage,groupedTexture=groupedTexture));private val fetch=Module(new ShaderTextureFetchUnit(config,capturedCoordinates=true))
  private val enterStream = WireDefault(false.B)
  private val mainCommitSelected = WireDefault(true.B)
  private val incomingScalarFree = WireDefault(true.B)
  private val incomingVectorFree = WireDefault(true.B)
  private val resumingStream = WireDefault(false.B)
  private val snapshotHit = WireDefault(false.B)
  private val size=Module(new ShaderTextureSizeUnit(config,stage));private val plannedSize=Reg(new ShaderTextureSizeRequest)
  io.memoryRequest.valid:=Mux(query,size.io.memoryRequest.valid,Mux(integer,fetch.io.memoryRequest.valid,sample.io.memoryRequest.valid))
  io.memoryRequest.bits:=Mux(query,size.io.memoryRequest.bits,Mux(integer,fetch.io.memoryRequest.bits,sample.io.memoryRequest.bits))
  sample.io.memoryRequest.ready:= !query && !integer && io.memoryRequest.ready;fetch.io.memoryRequest.ready:= !query && integer && io.memoryRequest.ready
  size.io.memoryRequest.ready:=query && io.memoryRequest.ready
  sample.io.memoryResponse.valid:= !query && !integer && io.memoryResponse.valid;sample.io.memoryResponse.bits:=io.memoryResponse.bits
  fetch.io.memoryResponse.valid:= !query && integer && io.memoryResponse.valid;fetch.io.memoryResponse.bits:=io.memoryResponse.bits
  size.io.memoryResponse.valid:=query && io.memoryResponse.valid;size.io.memoryResponse.bits:=io.memoryResponse.bits
  io.memoryResponse.ready:=Mux(query,size.io.memoryResponse.ready,Mux(integer,fetch.io.memoryResponse.ready,sample.io.memoryResponse.ready))
  private val chooseVector=io.vectorIn.valid && incomingVectorFree && (!io.in.valid || !incomingScalarFree || preferVector)
  io.in.ready:=phase===State.idle && !io.clearParameters && !resumingStream && incomingScalarFree && !chooseVector
  io.vectorIn.ready:=phase===State.idle && !io.clearParameters && !resumingStream && incomingVectorFree && chooseVector
  io.busy:=phase=/=State.idle;io.status:=status;io.faultAddressValid:=faultValid;io.faultAddress:=faultAddress
  when(io.clearParameters){assert(phase===State.idle,"parameter reset must wait for texture retirement");valid.foreach(_.foreach(_:=0.U))}
  sample.io.in.valid:=phase===State.offer && !integer && !query;sample.io.in.bits:=planned;sample.io.out.ready:=phase===State.waitSample
  sample.io.cancel:=started && !integer && !query && (io.cancel || status===4.U);sample.io.invalidate:=io.invalidate || noFill || status=/=0.U
  fetch.io.in.valid:=phase===State.offer && integer && !query;fetch.io.in.bits:=plannedFetch;fetch.io.out.ready:=phase===State.waitSample
  fetch.io.cancel:=started && integer && !query && (io.cancel || status===4.U);fetch.io.invalidate:=io.invalidate || noFill || status=/=0.U
  size.io.in.valid:=phase===State.offer && query;size.io.in.bits:=plannedSize;size.io.out.ready:=phase===State.waitSample
  size.io.cancel:=started && query && (io.cancel || status===4.U);size.io.invalidate:=io.invalidate || noFill || status=/=0.U
  when(phase=/=State.idle && phase=/=State.commit && !parameter && io.cancel && status===0.U){status:=4.U}
  when(phase=/=State.idle && io.invalidate){noFill:=true.B}
  io.commit.valid:=mainCommitSelected && phase===State.commit && !vector;io.commit.bits:=0.U.asTypeOf(io.commit.bits)
  io.commit.bits.warpId:=warp;io.commit.bits.nextPc:=pc+4.U;io.commit.bits.activeMask:=active
  io.commit.bits.rd:=Mux(parameter,0.U,rd);io.commit.bits.writeRd:=parameter || status===0.U
  io.commit.bits.data:=Mux(!parameter && status===0.U,scalarData,0.U)
  io.vectorCommit.valid:=mainCommitSelected && phase===State.commit && vector;io.vectorCommit.bits:=0.U.asTypeOf(io.vectorCommit.bits)
  io.vectorCommit.bits.writeback.warpId:=warp;io.vectorCommit.bits.writeback.vd:=Mux(parameter,0.U,rd)
  io.vectorCommit.bits.writeback.data:=Mux(!parameter && status===0.U,data,0.U.asTypeOf(data))
  io.vectorCommit.bits.writesVd:= !parameter && status===0.U
  // A parameter operation acknowledges through the existing scalar x0 path.
  // It must never write/release v0 or reserve a vector destination.
  io.vectorCommit.bits.writesScalar:=parameter;io.vectorCommit.bits.scalarData:=0.U
  io.vectorCommit.bits.pc:=pc;io.vectorCommit.bits.warpActiveMask:=active
  private def next(): Unit={when(!vector || lane===(config.lanes-1).U){phase:=State.commit}.otherwise{lane:=lane+1.U;phase:=State.nextLane}}
  switch(phase){
    is(State.idle){when(io.in.fire || io.vectorIn.fire){
      assert(!sample.io.busy && !fetch.io.busy && !size.io.busy,"texture execution abandoned sampling/fetch ownership")
      resources:=io.resources;identity:=io.identity;status:=0.U;faultValid:=false.B;faultAddress:=0.U
      scalarData:=0.U;started:=false.B;noFill:=io.invalidate
      when(io.vectorIn.fire){val q=io.vectorIn.bits;val d=q.issued.decode
        assert(ShaderTextureSampleOpcode.vector(d.instruction));assert(d.warpId<config.warps.U)
        query:=ShaderTextureSampleOpcode.vectorSize(d.instruction);integer:=ShaderTextureSampleOpcode.vectorFetch(d.instruction);vector:=true.B;parameter:=ShaderTextureSampleOpcode.vectorParameters(d.instruction);pair:=d.instruction(14,12)
        warp:=d.warpId;rd:=d.instruction(11,7);pc:=d.pc;active:=d.activeMask;mask:=q.executionMask;lane:=0.U;component:=d.instruction(13,12)
        left:=q.issued.vs1Data;right:=q.issued.vs2Data;data:=q.issued.oldVdData;preferVector:=false.B
        phase:=Mux(ShaderTextureSampleOpcode.vectorParameters(d.instruction),State.writeParameters,State.nextLane)
      }.otherwise{val q=io.in.bits;val d=q.decode;val first=PriorityEncoder(d.activeMask)
        assert(ShaderTextureSampleOpcode.scalar(d.instruction));assert(d.warpId<config.warps.U)
        query:=ShaderTextureSampleOpcode.scalarSize(d.instruction);integer:=ShaderTextureSampleOpcode.scalarFetch(d.instruction);vector:=false.B;parameter:=ShaderTextureSampleOpcode.scalarParameters(d.instruction);pair:=d.instruction(14,12)
        warp:=d.warpId;rd:=d.instruction(11,7);pc:=d.pc;active:=d.activeMask;lane:=first;component:=d.instruction(13,12)
        mask:=Mux(ShaderTextureSampleOpcode.scalarParameters(d.instruction),d.activeMask,UIntToOH(first,config.lanes))
        left:=VecInit(Seq.fill(config.lanes)(q.rs1Data));right:=VecInit(Seq.fill(config.lanes)(q.rs2Data));data:=0.U.asTypeOf(data);preferVector:=true.B
        phase:=Mux(ShaderTextureSampleOpcode.scalarParameters(d.instruction),State.writeParameters,State.nextLane)
      }
    }}
    is(State.writeParameters){
      val bits=(3.U(16.W)<<(pair<<1))(15,0)
      for(l<-0 until config.lanes){when(mask(l)){
        values(warp)(l)(Cat(pair,0.U(1.W))):=left(l);values(warp)(l)(Cat(pair,1.U(1.W))):=right(l)
        valid(warp)(l):=Mux(pair===0.U,bits,valid(warp)(l)|bits)
      }}
      phase:=State.commit
    }
    is(State.nextLane){when(status=/=0.U || io.cancel){phase:=State.commit}.elsewhen(mask(lane)){phase:=State.capture}.otherwise{next()}}
    is(State.capture){
      val words=values(warp)(lane);val control=right(lane);val implicitMode=control(1,0)===0.U
      val illegal=valid(warp)(lane)=/="hffff".U || Mux(integer,control.orR || words.slice(6,13).map(_.orR).reduce(_ || _),control(31,3).orR || control(1,0)===3.U) ||
        words(13)(31,12).orR || words(14)=/=0.U || words(15)=/=0.U
      when(status=/=0.U || io.cancel){phase:=State.commit}.elsewhen(query){
        plannedSize:=0.U.asTypeOf(plannedSize);plannedSize.resources:=resources;plannedSize.identity:=identity
        plannedSize.unit:=left(lane);plannedSize.lod:=right(lane);plannedSize.component:=component;plannedSize.tag:=lane;phase:=State.offer
      }.elsewhen(illegal){status:=1.U;phase:=State.commit}.otherwise{
        plannedFetch:=0.U.asTypeOf(plannedFetch);plannedFetch.resources:=resources;plannedFetch.identity:=identity;plannedFetch.stage:=stage.U
        plannedFetch.unit:=left(lane);plannedFetch.component:=component;plannedFetch.tag:=lane
        val iq=plannedFetch.coordinates.get;iq.identity:=identity;iq.x:=words(0);iq.y:=words(1);iq.z:=words(2)
        iq.layer:=words(3);iq.face:=words(4);iq.level:=words(5)
        plannedFetch.offsets.get.zipWithIndex.foreach{case(o,a)=>o:=words(13)(a*4+3,a*4).asSInt.pad(5)}
        planned:=0.U.asTypeOf(planned);planned.resources:=resources;planned.identity:=identity;planned.unit:=left(lane);planned.component:=component;planned.tag:=lane
        val q=planned.parameters;q.mode:=control(1,0);q.shadow:=control(2);q.coordinates:=VecInit(words.take(3));q.layer:=words(3)
        q.reference:=words(4);q.lod:=words(5);q.bias:=words(6)
        q.gradients(0):=VecInit(words.slice(7,10));q.gradients(1):=VecInit(words.slice(10,13))
        q.offsets.zipWithIndex.foreach{case(o,a)=>o:=words(13)(a*4+3,a*4).asSInt.pad(5)}
        if(config.lanes>=4 && config.lanes%4==0){
          val group=(lane>>2)<<2
          for(l<-0 until 4){q.quadCoordinates(l):=VecInit(values(warp)(group+l.U).take(3))}
          q.quadLane:=lane(1,0);q.quadValidMask:=VecInit((0 until 4).map(l=>valid(warp)(group+l.U)==="hffff".U)).asUInt
        }else{
          when(!integer && (stage==1).B && implicitMode){status:=1.U}
        }
        phase:=Mux((stage==1 && (config.lanes<4 || config.lanes%4!=0)).B && implicitMode && !integer,State.commit,
          (if(groupedTexture)Mux(snapshotHit,State.stream.get,State.offer)else State.offer))
      }
    }
    is(State.offer){when(sample.io.in.fire || fetch.io.in.fire || size.io.in.fire){started:=true.B;phase:=State.waitSample}}
    is(State.waitSample){when(sample.io.out.fire || fetch.io.out.fire || size.io.out.fire){
      started:=false.B;val r=Wire(new ShaderTextureFetchResult);r:=fetch.io.out.bits
      when(!integer){r:=0.U.asTypeOf(r);r.tag:=sample.io.out.bits.tag;r.unit:=sample.io.out.bits.unit;r.status:=sample.io.out.bits.status
        r.value:=sample.io.out.bits.value;r.faultAddressValid:=sample.io.out.bits.faultAddressValid;r.faultAddress:=sample.io.out.bits.faultAddress}
      when(query){r:=size.io.out.bits};assert(r.tag===lane && r.unit===left(lane));val code=r.status
      when((code=/=0.U && code=/=4.U) || (status===0.U && !io.cancel)){status:=code
        when(code=/=0.U){faultValid:=r.faultAddressValid;faultAddress:=r.faultAddress}}
      when(code===0.U && status===0.U && !io.cancel){when(vector){data(lane):=r.value}.otherwise{scalarData:=r.value}}
      when(code=/=0.U || status=/=0.U || io.cancel){phase:=State.commit}.otherwise{
        if(groupedTexture){when(enterStream){phase:=State.idle}.otherwise{next()}}else{next()}
      }
    }}
    is(State.commit){when(mainCommitSelected && (io.commit.fire || io.vectorCommit.fire)){assert(!sample.io.busy && !fetch.io.busy && !size.io.busy,"texture retirement escaped sample/fetch/group drain");phase:=State.idle}}
  }
  if(groupedTexture){
    val streams=Module(new NativeTextureInstructionStream(config,math.min(8,config.warps),streamBurst))
    streams.io.cancel:=io.cancel;streams.io.invalidate:=io.invalidate
    io.busy:=phase=/=State.idle || streams.io.busy
    incomingScalarFree:= !streams.io.warpBusy(io.in.bits.decode.warpId)
    incomingVectorFree:= !streams.io.warpBusy(io.vectorIn.bits.issued.decode.warpId)
    when(io.clearParameters){assert(!streams.io.busy,"parameter reset escaped streaming instruction ownership")}
    val template=Reg(new NativeRgba8TextureLaneTemplate);val templateValid=RegInit(false.B)
    when(io.in.fire || io.vectorIn.fire || io.clearParameters){templateValid:=false.B}
    when(sample.io.laneTemplate.get.valid && !noFill && !io.invalidate){
      template:=sample.io.laneTemplate.get.bits;templateValid:=true.B
    }
    when(io.invalidate){templateValid:=false.B}
    // The general implicit path rejects incomplete/nonfinite helper quads.
    // Preserve that check even for masked neighbors before reusing metadata.
    // Finite FP32 differences use the extended-exponent gradient arithmetic;
    // every such 2D derivative is representable, including opposite maxima.
    val implicitQuads=if(config.lanes>=4 && config.lanes%4==0)
      VecInit((0 until config.lanes).map{l=>
        val first=(l/4)*4
        !mask(l) || (0 until 4).map{k=>
          valid(warp)(first+k)==="hffff".U &&
            values(warp)(first+k).take(2).map(v=>v(30,23)=/=255.U).reduce(_ && _)
        }.reduce(_ && _)
      }).asUInt.andR else false.B
    val reusableControl=right(lane)===1.U || (right(lane)===0.U && implicitQuads)
    val uniform=VecInit((0 until config.lanes).map { l =>
      val words=values(warp)(l)
      !mask(l) || (valid(warp)(l)==="hffff".U && left(l)===left(lane) && right(l)===right(lane) && reusableControl &&
        words(5)===values(warp)(lane)(5) && words(6)===0.U &&
        !words(13)(31,8).orR && words(14)===0.U && words(15)===0.U)
    }).asUInt.andR
    val snapshotEligible=vector && !integer && !query && uniform && !noFill && !io.invalidate && !io.cancel && status===0.U
    val templateReusable=right(lane)===1.U || template.lodIndependent
    val eligible=snapshotEligible && templateReusable && templateValid && template.group.tag===lane &&
      lane<(config.lanes-1).U && sample.io.out.bits.status===0.U
    val cachedTemplate=Reg(new NativeRgba8TextureLaneTemplate)
    if(instructionSnapshots){
      val cache=Module(new NativeTextureSnapshotCache())
      cache.io.lookup.resources:=resources;cache.io.lookup.identity:=identity
      cache.io.lookup.unit:=left(lane);cache.io.lookup.lod:=values(warp)(lane)(5)
      cache.io.clear:=io.clearParameters || io.invalidate || io.cancel
      cache.io.write.valid:=phase===State.waitSample && sample.io.out.fire && snapshotEligible &&
        templateReusable && templateValid && template.group.tag===lane && sample.io.out.bits.status===0.U
      cache.io.write.bits.key:=cache.io.lookup;cache.io.write.bits.template:=template
      snapshotHit:=phase===State.capture && snapshotEligible && cache.io.hit &&
        (right(lane)===1.U || cache.io.template.lodIndependent)
      when(snapshotHit){cachedTemplate:=cache.io.template}
    }
    val cached=phase===State.stream.get
    when(cached){
      when(io.cancel || status=/=0.U){phase:=State.commit}
        .elsewhen(noFill || io.invalidate){phase:=State.offer}
        .elsewhen(streams.io.in.fire){phase:=State.idle}
    }
    streams.io.in.valid:=(phase===State.waitSample && sample.io.out.valid && eligible) ||
      (cached && !io.cancel && status===0.U && !noFill && !io.invalidate)
    streams.io.in.bits:=0.U.asTypeOf(streams.io.in.bits)
    val offered=streams.io.in.bits
    offered.template:=Mux(cached,cachedTemplate,template);offered.resources:=resources;offered.warp:=warp
    offered.pc:=pc;offered.vd:=rd;offered.active:=active;offered.mask:=mask
    offered.lane:=Mux(cached,lane,lane+1.U);offered.component:=component;offered.unit:=left(lane)
    offered.data:=data;when(!cached){offered.data(lane):=sample.io.out.bits.value}
    for(l<-0 until config.lanes){
      offered.coordinates(l):=VecInit(values(warp)(l).take(2))
      for(a<-0 until 2){offered.offsets(l)(a):=values(warp)(l)(13)(a*4+3,a*4).asSInt.pad(5)}
    }
    sample.io.out.ready:=phase===State.waitSample && (!eligible || streams.io.in.ready)
    enterStream:=eligible && streams.io.in.ready
    when(streams.io.in.fire){assert(cached || sample.io.out.fire)}

    // Separate private tag spaces allow metadata resolution of another warp
    // while earlier vector lanes are still in flight. The output queue owns
    // every externally held request, regardless of which resolver produced it.
    val port=io.grouped.get;val child=sample.io.grouped.get;val lanes=streams.io.grouped
    val requests=Module(new RRArbiter(new TextureRgba8GroupRequest,2))
    val requestQueue=Module(new Queue(new TextureRgba8GroupRequest,2))
    requests.io.in(0)<>child.request;requests.io.in(1)<>lanes.request
    requestQueue.io.enq<>requests.io.out;port.request<>requestQueue.io.deq
    child.response.valid:=port.response.valid && !port.response.bits.tag(31)
    child.response.bits:=port.response.bits
    lanes.response.valid:=port.response.valid && port.response.bits.tag(31)
    lanes.response.bits:=port.response.bits
    port.response.ready:=Mux(port.response.bits.tag(31),lanes.response.ready,child.response.ready)
    val cancels=Module(new RRArbiter(new BankedTextureGroupCancel,2))
    cancels.io.in(0).valid:=child.cancelGroup.valid;cancels.io.in(0).bits:=child.cancelGroup.bits
    cancels.io.in(1).valid:=lanes.cancelGroup.valid;cancels.io.in(1).bits:=lanes.cancelGroup.bits
    cancels.io.out.ready:=true.B
    port.cancelGroup.valid:=cancels.io.out.valid;port.cancelGroup.bits:=cancels.io.out.bits
    port.invalidate:=child.invalidate || lanes.invalidate

    val returned=streams.io.out.bits
    val resume=streams.io.out.valid && !returned.completed
    resumingStream:=resume
    // A public completion locks its selected source until the consumer takes
    // it. Another warp becoming ready cannot change a held writeback.
    val held=RegInit(false.B);val heldStream=Reg(Bool())
    val chooseStream=Mux(held,heldStream,streams.io.out.valid && returned.completed && phase=/=State.commit)
    mainCommitSelected:= !chooseStream
    val mainValid=phase===State.commit
    val streamValid=streams.io.out.valid && returned.completed
    val publicValid=Mux(chooseStream,streamValid,mainValid)
    val publicReady=Mux(chooseStream || vector,io.vectorCommit.ready,io.commit.ready)
    when(publicValid && !publicReady){held:=true.B;heldStream:=chooseStream}
    when(publicValid && publicReady){held:=false.B}
    streams.io.out.ready:=Mux(returned.completed,chooseStream && io.vectorCommit.ready,phase===State.idle)
    when(chooseStream){
      io.commit.valid:=false.B
      io.vectorCommit.valid:=streamValid
      io.vectorCommit.bits:=0.U.asTypeOf(io.vectorCommit.bits)
      io.vectorCommit.bits.writeback.warpId:=returned.instruction.warp
      io.vectorCommit.bits.writeback.vd:=returned.instruction.vd
      io.vectorCommit.bits.writeback.data:=Mux(returned.status===0.U,returned.instruction.data,0.U.asTypeOf(data))
      io.vectorCommit.bits.writesVd:=returned.status===0.U
      io.vectorCommit.bits.pc:=returned.instruction.pc
      io.vectorCommit.bits.warpActiveMask:=returned.instruction.active
      io.status:=returned.status;io.faultAddressValid:=returned.faultAddressValid
      io.faultAddress:=returned.faultAddress
    }
    when(streams.io.out.fire && !returned.completed){
      val q=returned.instruction
      resources:=q.resources;identity:=q.template.group.lookup.table.identity;warp:=q.warp;rd:=q.vd;pc:=q.pc
      active:=q.active;mask:=q.mask;lane:=q.lane;component:=q.component
      left:=VecInit(Seq.fill(config.lanes)(q.unit));right:=VecInit(Seq.fill(config.lanes)(1.U(32.W)))
      data:=q.data;status:=0.U;faultValid:=false.B;faultAddress:=0.U
      vector:=true.B;parameter:=false.B;query:=false.B;integer:=false.B
      started:=false.B;noFill:=true.B;templateValid:=false.B;phase:=State.nextLane
    }
  }
}
