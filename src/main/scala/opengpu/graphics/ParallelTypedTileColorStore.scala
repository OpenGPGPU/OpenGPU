package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.util.ObservedCarrySaveAccumulator

private class BufferedTypedPolicyWord extends Bundle {
  val request=new TypedTileWordRequest
  val fill=Bool()
}

/** Four independently progressing typed lanes with shared, atomic word banks.
  * Only one quad owns pixel order; its lanes may update disjoint packed fields
  * concurrently. Lazy materialization is serialized per word before neighbors
  * can issue masked updates. Refills and accepted operations drain on cancel.
  */
class ParallelTypedTileColorStore(p: TypedTileColorConfig=TypedTileColorConfig(parallelLanes=true),useBlackBoxes: Boolean=false,externalMemory: Boolean=false) extends Module {
  require(p.parallelLanes,"parallel policy requires parallel typed contexts")
  require(!externalMemory || p.parallelDepth,"external canonical words require depth policy")
  private val wordPorts=p.colorWordPorts
  val io=IO(new TypedTileColorStoreIO(p))
  val raw=if(externalMemory)Some(IO(new TypedTileExternalWordService(wordPorts)))else None
  private val residentActive=raw.map(_.residentActive).getOrElse(false.B)
  private object State extends ChiselEnum {
    val idle,validate,clearNext,clearWait,active,checkQuad,run,quadDone,emit,
      maintenance,maintenanceRead,maintenanceReadWait,maintenanceWrite,maintenanceWriteWait,externalReply,finish,complete=Value
  }
  private val phase=RegInit(State.idle);private val descriptor=Reg(new TypedTileColorDescriptor)
  private val input=Reg(new TypedTileColorQuad);private val output=Reg(new TypedTileColorQuadCompletion)
  private val result=Reg(new TypedTileColorCompletion)
  private val depthViews=io.depth.map(_=>Reg(Vec(2,new TypedDepthView)))
  private val depthMode=io.depth.map(_.active).getOrElse(false.B)
  private val depthClearValues=io.depth.map(_=>Reg(Vec(2,UInt(32.W))))
  private val depthEnds=io.depth.map(_=>Reg(Vec(2,UInt(18.W))))
  private val depthClearsReady=io.depth.map(_=>RegInit(false.B))
  io.depth.foreach { depth=>
    for(view<-0 until 2){
      val preparation=Module(new PreparedTypedDepths(1))
      preparation.io.in.valid:=io.begin.fire
      preparation.io.in.bits.raw(0):=depth.views(view).clearDepth
      preparation.io.in.bits.format:=depth.views(view).format
      preparation.io.in.bits.tag:=view.U
      when(preparation.io.out.valid){depthClearValues.get(view):=preparation.io.out.bits.values(0)}
      if(view==0)when(preparation.io.out.valid){depthClearsReady.get:=true.B}
    }
    when(io.begin.fire){depthClearsReady.get:=false.B}
    // Validation and the existing four color setup clocks cover preparation.
    when(phase===State.active){assert(depthClearsReady.get,"depth clear preparation missed tile admission")}
  }
  private val initialized=RegInit(VecInit(Seq.fill(4)(false.B)))
  private val status=RegInit(0.U(3.W));private val faultOffset=RegInit(0.U(17.W))
  private val quadStarted=RegInit(false.B)
  private val quadCounter=Module(new ObservedCarrySaveAccumulator(99))
  private val writeCounter=Module(new ObservedCarrySaveAccumulator(110))
  quadCounter.io.clear:=io.begin.fire;writeCounter.io.clear:=io.begin.fire
  quadCounter.io.addend:=io.in.fire.asUInt
  private val totalQuads=quadCounter.io.value;private val totalWrites=writeCounter.io.value
  private val valid=RegInit(0.U(p.words.W));private val dirty=RegInit(0.U(p.words.W))
  // Record accepted writes before expanding their addresses into the tile-wide
  // maps. Forward this one-clock commit window to every metadata reader.
  private val metadataPorts=wordPorts+raw.map(_=>4).getOrElse(0)
  private val metadataPending=RegInit(VecInit(Seq.fill(metadataPorts)(false.B)))
  private val metadataDirty=Reg(Vec(metadataPorts,Bool()))
  private val metadataRow=Reg(Vec(metadataPorts,UInt(p.addressBits.W)))
  private def pendingWord(row: UInt,dirtyOnly: Boolean=false): Bool=
    VecInit((0 until metadataPorts).map(i=>metadataPending(i) && metadataRow(i)===row &&
      (if(dirtyOnly)metadataDirty(i)else true.B))).asUInt.orR
  private def validWord(row: UInt): Bool=valid(row) || pendingWord(row)
  private def dirtyWord(row: UInt): Bool=dirty(row) || pendingWord(row,dirtyOnly=true)
  private val clearValues=Reg(Vec(4,UInt(128.W)));private val spans=Reg(Vec(4,UInt(17.W)))
  private val colorEnds=Reg(Vec(4,UInt(18.W)))
  private val clearColor=RegInit(0.U(2.W));private val clearCodec=Module(new PreparedTypedColorEncoder)
  private val clearWordPhases=Reg(Vec(4,UInt(2.W)))
  private def remainder3(value: UInt): UInt=TypedWordPhase.remainder3(value)
  private def wordClearIndex(rowPhase: UInt,basePhase: UInt,odd: Bool,bytes: UInt,b: Int): UInt={
    val difference=rowPhase+&3.U-basePhase
    val phase=Mux(difference>=3.U,difference-3.U,difference)(1,0)
    val start=MuxLookup(bytes,0.U(4.W))(Seq(
      3.U->Mux(phase===1.U,2.U,Mux(phase===2.U,1.U,0.U)),
      6.U->(phase<<1),12.U->Mux(phase===1.U,8.U,Mux(phase===2.U,4.U,0.U)),16.U->(odd.asUInt<<3)))
    val increment=MuxLookup(bytes,0.U(4.W))(Seq(1,2,3,4,6,8,12,16).map(n=>n.U->(b%n).U(4.W)))
    val total=start+&increment
    Mux(total>=bytes,total-bytes,total)(3,0)
  }
  private val clearOffered=RegInit(false.B);private val clearPending=RegInit(false.B)
  private val clearRequest=Reg(new TypedColorEncodeRequest)
  clearCodec.io.in.valid:=clearOffered;clearCodec.io.in.bits:=clearRequest;clearCodec.io.out.ready:=clearPending
  when(clearCodec.io.in.fire){clearOffered:=false.B;clearPending:=true.B}
  when(clearCodec.io.out.fire){clearPending:=false.B}
  private val contexts=Module(new StreamingTypedTileColorLanes(wordPorts=wordPorts))
  private val workers: Seq[TypedTileColorLaneIO]=contexts.io.lanes.toSeq
  private val laneWrites=workers.map(_.writes)
  private val written=Mux(quadStarted,(laneWrites(0)+&laneWrites(1))+&(laneWrites(2)+&laneWrites(3)),0.U)
  writeCounter.io.addend:=Mux(phase===State.quadDone,written,0.U)
  private val anyFault=phase===State.run && VecInit(workers.map(_.fault)).asUInt.orR
  private val active=phase=/=State.idle && phase=/=State.complete
  private val stop=status=/=0.U || io.cancel
  private val start=phase===State.checkQuad && !stop && !TypedTileColorRules.invalidQuad(input,descriptor)
  for(worker<-workers){worker.start:=start;worker.cancel:=stop || anyFault
    worker.descriptor:=descriptor;worker.input:=input}
  private val memory=if(externalMemory)None else Some(Module(new BankedTypedTileMemory(p,ports=wordPorts,useBlackBoxes=useBlackBoxes)))
  private val memoryRequest=memory.map(_.io.request).getOrElse(raw.get.request)
  private val memoryResponse=memory.map(_.io.response).getOrElse(raw.get.response)
  private val memoryDrained=memory.map(_.io.drained).getOrElse(raw.get.drained)
  private val wordRequests=Seq.fill(wordPorts)(Module(new Queue(new BufferedTypedPolicyWord,1,pipe=false,flow=false)))
  private val maintenanceRequest=Reg(new TypedTileColorMemoryRequest(p))
  private val maintenanceResult=Reg(new TypedTileColorMemoryReply)
  // Shadow the maintenance state at its transition, instead of decoding the
  // shared phase bits on SRAM row selection. No request clock is added.
  private val maintenanceMode=RegInit(false.B)
  private def maintenanceNext(next: State.Type): Unit={
    phase:=next
    maintenanceMode:=next===State.maintenanceRead || next===State.maintenanceReadWait ||
      next===State.maintenanceWrite || next===State.maintenanceWriteWait
  }
  assert(maintenanceMode===(phase===State.maintenanceRead || phase===State.maintenanceReadWait ||
    phase===State.maintenanceWrite || phase===State.maintenanceWriteWait),"maintenance predecode diverged from its owner")
  private val runEnabled=RegNext(phase===State.run,false.B)
  when(phase===State.run && !runEnabled){
    assert(!VecInit(workers.map(_.request.valid)).asUInt.orR,"color request preceded its registered run admission")
  }
  when(runEnabled && phase=/=State.run){assert(workers.map(_.done).reduce(_ && _),"color run ended with a live worker")}
  private val clients=Wire(Vec(wordPorts,Decoupled(new TypedTileWordRequest)))
  private val responses=Wire(Vec(wordPorts,Decoupled(new TypedTileWordResponse)))
  for((worker,lane)<-workers.zipWithIndex){
    clients(lane).valid:=runEnabled && worker.request.valid
    clients(lane).bits:=worker.request.bits
    worker.request.ready:=runEnabled && clients(lane).ready
    worker.response.valid:=runEnabled && responses(lane).valid
    worker.response.bits:=responses(lane).bits
    responses(lane).ready:=runEnabled && worker.response.ready
  }
  if(wordPorts==8){
    val extra=contexts.io.extra.get
    for(lane<-0 until 4){
      clients(lane+4).valid:=runEnabled && extra.request(lane).valid
      clients(lane+4).bits:=extra.request(lane).bits
      extra.request(lane).ready:=runEnabled && clients(lane+4).ready
      extra.response(lane).valid:=runEnabled && responses(lane+4).valid
      extra.response(lane).bits:=responses(lane+4).bits
      responses(lane+4).ready:=runEnabled && extra.response(lane).ready
    }
  }
  io.depth.foreach{depth=>
    for(lane<-0 until 4){
      // Word payload selection follows the already registered color owner.
      // The live depth enable gates acceptance without traversing address,
      // data, residency lookup and competing-row selection first.
      clients(lane).bits:=Mux(runEnabled,workers(lane).request.bits,depth.request(lane).bits)
      depth.request(lane).ready:=depthMode && !runEnabled && active && clients(lane).ready
      depth.response(lane).valid:=depthMode && responses(lane).valid
      depth.response(lane).bits.data:=responses(lane).bits.data
      depth.response(lane).bits.tag:=responses(lane).bits.tag
      depth.response(lane).bits.fault:=responses(lane).bits.fault
      depth.response(lane).bits.initialized:=initialized(lane)
      when(depthMode){
        clients(lane).valid:= !runEnabled && active && depth.request(lane).valid
        responses(lane).ready:=depth.response(lane).ready
      }
      when(depth.response(lane).fire){initialized(lane):=false.B}
    }
    assert(!depthMode || phase=/=State.run,"depth and color quads cannot own the same word ports")
    assert(!depthMode || !maintenanceMode,"depth words and legacy maintenance have distinct owners")
  }
  when(maintenanceMode){
    clients(0).valid:=phase===State.maintenanceRead || phase===State.maintenanceWrite
    clients(0).bits.address:=maintenanceRequest.address
    clients(0).bits.write:=phase===State.maintenanceWrite
    clients(0).bits.data:=maintenanceRequest.data
    clients(0).bits.mask:=Cat((0 until 8).reverse.map(b=>Fill(8,maintenanceRequest.byteMask(b))))
    clients(0).bits.tag:=0.U
    responses(0).ready:=phase===State.maintenanceReadWait || phase===State.maintenanceWriteWait
  }
  private def virtualWord(address: UInt): (Bool,UInt,Bool)={
    val available=WireDefault(false.B);val value=WireDefault(0.U(64.W));val depthWord=WireDefault(false.B);val rowPhase=remainder3(address)
    for(c<-0 until 4){val view=descriptor.colors(c)
      val selected=view.enabled && (address<<3)>=view.offset && (address<<3)<colorEnds(c)
      val bytes=Mux(TypedColorFormat.valid(view.format),TypedColorFormat.bytes(view.format),1.U)
      val data=Wire(Vec(8,UInt(8.W)))
      for(b<-0 until 8)data(b):=(clearValues(c)>>(wordClearIndex(rowPhase,clearWordPhases(c),address(0)^view.offset(3),bytes,b)<<3))(7,0)
      when(selected){available:=view.loadOp=/=AttachmentLoadOp.load.U
        value:=Mux(view.loadOp===AttachmentLoadOp.clear.U,data.asUInt,0.U)}
    }
    depthViews.foreach{views=>for((view,index)<-views.zipWithIndex){
      val end=depthEnds.get(index)
      when(view.format=/=0.U && (address<<3)>=view.offset && (address<<3)<end){
        depthWord:=true.B;available:=view.loadOp=/=AttachmentLoadOp.load.U
        value:=TypedTilePixelRules.initialWord(view,depthClearValues.get(index))
      }
    }}
    (available,value,depthWord)
  }
  private object Resolve extends ChiselEnum {val idle,refillOffer,refillWait,fillOffer,fillWait,memoryWait,synthetic,refillFault=Value}
  private val resolving=RegInit(VecInit(Seq.fill(wordPorts)(Resolve.idle)))
  private val missing=Reg(Vec(wordPorts,UInt(p.addressBits.W)))
  private val fetched=Reg(Vec(wordPorts,UInt(64.W)))
  private val initializeDirty=RegInit(VecInit(Seq.fill(wordPorts)(false.B)))
  private val synthetic=Reg(Vec(wordPorts,new TypedTileWordResponse))
  private val candidates=Wire(Vec(wordPorts,Decoupled(new TypedTileWordRequest)))
  private val isFill=Wire(Vec(wordPorts,Bool()))
  private val refillArbiter=Module(new RRArbiter(UInt(p.addressBits.W),wordPorts))
  private val refillOffered=RegInit(false.B);private val refillPending=RegInit(false.B)
  private val refillOwner=Reg(UInt(log2Ceil(wordPorts).W));private val refillAddress=Reg(UInt(p.addressBits.W))
  io.refill.valid:=refillOffered;io.refill.bits.address:=refillAddress
  when(io.refill.fire){refillOffered:=false.B;refillPending:=true.B}
  io.refillReply.ready:=refillPending
  when(io.refillReply.fire){
    refillPending:=false.B;fetched(refillOwner):=io.refillReply.bits.data
    initializeDirty(refillOwner):=false.B
    resolving(refillOwner):=Mux(io.refillReply.bits.fault || stop,Resolve.refillFault,Resolve.fillOffer)
  }
  refillArbiter.io.out.ready:= !refillOffered && !refillPending
  when(refillArbiter.io.out.fire){
    refillOwner:=refillArbiter.io.chosen;refillAddress:=refillArbiter.io.out.bits;refillOffered:=true.B
  }
  for(lane<-0 until wordPorts){
    val client=clients(lane);val response=responses(lane);val candidate=candidates(lane)
    val row=client.bits.address(p.addressBits-1,0)
    val legal=client.bits.address<p.words.U
    val colorAddress=if(lane<4)workers(lane).request.bits.address
      else contexts.io.extra.get.request(lane-4).bits.address
    val colorVirtual=virtualWord(colorAddress(p.addressBits-1,0))
    // Select the completed clear lookup, rather than putting the live owner
    // mode ahead of address arithmetic and variable byte extraction.
    val (nativeAvailable,nativeValue,depthWord)=if(lane<4 && io.depth.nonEmpty){
      val depthVirtual=virtualWord(io.depth.get.request(lane).bits.address(p.addressBits-1,0))
      (Mux(runEnabled,colorVirtual._1,depthVirtual._1),
        Mux(runEnabled,colorVirtual._2,depthVirtual._2),
        Mux(runEnabled,colorVirtual._3,depthVirtual._3))
    }else colorVirtual
    val available=Mux(maintenanceMode,maintenanceResult.available,nativeAvailable)
    val value=Mux(maintenanceMode,maintenanceResult.data,nativeValue)
    // Select independent metadata reads with the registered color owner;
    // the live depth enable remains on request admission.
    val colorResident=validWord(colorAddress(p.addressBits-1,0))
    val nativeResident=if(lane<4 && io.depth.nonEmpty)
      Mux(runEnabled,colorResident,validWord(io.depth.get.request(lane).bits.address(p.addressBits-1,0)))
    else colorResident
    val resident=Mux(maintenanceMode,validWord(row),nativeResident)
    client.ready:=false.B;response.valid:=false.B;response.bits:=synthetic(lane)
    candidate.valid:=false.B;candidate.bits:=client.bits
    isFill(lane):=false.B
    memoryResponse(lane).ready:=false.B
    refillArbiter.io.in(lane).valid:=resolving(lane)===Resolve.refillOffer && !stop
    refillArbiter.io.in(lane).bits:=missing(lane)
    when(refillArbiter.io.in(lane).fire){resolving(lane):=Resolve.refillWait}
    switch(resolving(lane)){
      is(Resolve.idle){
        when(client.valid){
          when(stop || !legal || (depthMode && !depthWord) ||
              (!depthMode && !client.bits.write && !resident && available)){
            client.ready:=true.B
            synthetic(lane).data:=Mux(legal,value,0.U);synthetic(lane).tag:=client.bits.tag
            synthetic(lane).fault:=stop || !legal || (depthMode && !depthWord);resolving(lane):=Resolve.synthetic
          }.elsewhen(!resident && available && !maintenanceMode && (depthMode || (client.bits.write && client.bits.mask.orR))){
            missing(lane):=row;fetched(lane):=value;initializeDirty(lane):=true.B
            resolving(lane):=Resolve.fillOffer
          }.elsewhen(!resident && !available && !maintenanceMode){
            // Maintenance preflight handles unavailable words without refills.
            // Load-start fault behavior is preserved even for full-word writes.
            val joined=(0 until wordPorts).filter(_!=lane).map(i=>
              (resolving(i)===Resolve.refillOffer || resolving(i)===Resolve.refillWait ||
                resolving(i)===Resolve.fillOffer || resolving(i)===Resolve.fillWait || resolving(i)===Resolve.refillFault) &&
                missing(i)===row).foldLeft(false.B)(_ || _)
            val prior=(0 until lane).map(i=>clients(i).valid && resolving(i)===Resolve.idle &&
              clients(i).bits.address===client.bits.address).foldLeft(false.B)(_ || _)
            when(!joined && !prior){missing(lane):=row;resolving(lane):=Resolve.refillOffer}
          }.otherwise{
            candidate.valid:=true.B
            val materialize= !resident && client.bits.write && client.bits.mask.orR
            when(materialize){
              assert(maintenanceMode)
              candidate.bits.data:=(maintenanceResult.data& ~client.bits.mask)|(client.bits.data&client.bits.mask)
              candidate.bits.mask:=Fill(64,1.U(1.W))
            }
            isFill(lane):=maintenanceMode && maintenanceRequest.fill
            client.ready:=candidate.ready
            when(candidate.fire){resolving(lane):=Resolve.memoryWait}
          }
        }
      }
      is(Resolve.refillFault){
        // Retain the original private offer through an irrevocable refill.
        client.ready:=true.B
        when(client.fire){synthetic(lane).data:=0.U;synthetic(lane).tag:=client.bits.tag
          synthetic(lane).fault:=true.B;resolving(lane):=Resolve.synthetic}
      }
      is(Resolve.fillOffer){
        when(stop){resolving(lane):=Resolve.refillFault}
          .elsewhen(validWord(missing(lane))){resolving(lane):=Resolve.idle}.otherwise{
          candidate.valid:=true.B;candidate.bits.address:=missing(lane);candidate.bits.write:=true.B
          candidate.bits.data:=fetched(lane);candidate.bits.mask:=Fill(64,1.U(1.W));candidate.bits.tag:=0.U
          isFill(lane):= !initializeDirty(lane)
          when(candidate.fire){
            resolving(lane):=Resolve.fillWait
            if(lane<4){when(depthMode){initialized(lane):=true.B}}
          }
        }
      }
      is(Resolve.fillWait){memoryResponse(lane).ready:=true.B
        when(memoryResponse(lane).fire){assert(!memoryResponse(lane).bits.fault);resolving(lane):=Resolve.idle}}
      is(Resolve.memoryWait){
        response.valid:=memoryResponse(lane).valid;response.bits:=memoryResponse(lane).bits
        memoryResponse(lane).ready:=response.ready
        when(response.fire){resolving(lane):=Resolve.idle}
      }
      is(Resolve.synthetic){response.valid:=true.B;when(response.fire){resolving(lane):=Resolve.idle}}
    }
    when(stop && resolving(lane)===Resolve.refillOffer){resolving(lane):=Resolve.refillFault}
  }
  // Reserve same-row priority before deciding whether an idle request needs
  // physical service. This keeps another lane's clear/residency classification
  // out of ready. A synthetic reply or an already materialized fill may retain
  // priority for one clock, then releases it through its normal state change.
  // A fill outranks idle clients even when its port number is higher: an idle
  // client may be waiting for that very fill to make its row resident.
  private val fillClaims=VecInit(resolving.map(_===Resolve.fillOffer))
  private val rowClaims=VecInit((0 until wordPorts).map(i=>
    resolving(i)===Resolve.fillOffer || (resolving(i)===Resolve.idle && clients(i).valid)))
  private val claimedRows=VecInit((0 until wordPorts).map(i=>
    Mux(resolving(i)===Resolve.fillOffer,missing(i),clients(i).bits.address)))
  for(lane<-0 until wordPorts){
    val request=candidates(lane)
    when(request.valid){
      assert(rowClaims(lane) && claimedRows(lane)===request.bits.address,
        "physical word candidate escaped its row claim")
    }
    val prior=(0 until wordPorts).filter(_!=lane).map{i=>
      val priority=if(i<lane)fillClaims(i) || !fillClaims(lane) else fillClaims(i) && !fillClaims(lane)
      rowClaims(i) && priority && claimedRows(i)===request.bits.address
    }.foldLeft(false.B)(_ || _)
    // A queued word owns admission order until the SRAM service accepts it.
    // This also prevents a younger initializer from overwriting an update.
    val queued=(0 until wordPorts).filter(_!=lane).map{i=>
      wordRequests(i).io.deq.valid && wordRequests(i).io.deq.bits.request.address===request.bits.address
    }.foldLeft(false.B)(_ || _)
    // External canonical service can buffer accepted requests independently
    // per port. Keep the initializer ahead of every younger packed update
    // until its reply proves the full-word write has actually completed.
    val initializing=(0 until wordPorts).filter(_!=lane).map{i=>
      resolving(i)===Resolve.fillWait && missing(i)===request.bits.address(p.addressBits-1,0)
    }.foldLeft(false.B)(_ || _)
    val eligible= !prior && !queued && !initializing
    val queue=wordRequests(lane)
    queue.io.enq.valid:=request.valid && eligible
    queue.io.enq.bits.request:=request.bits;queue.io.enq.bits.fill:=isFill(lane)
    request.ready:=queue.io.enq.ready && eligible
    memoryRequest(lane).valid:=queue.io.deq.valid
    memoryRequest(lane).bits:=queue.io.deq.bits.request
    queue.io.deq.ready:=memoryRequest(lane).ready
    val accepted=queue.io.deq.bits.request
    val writes=memoryRequest(lane).fire && accepted.write && accepted.mask.orR
    metadataPending(lane):=writes
    metadataDirty(lane):= !queue.io.deq.bits.fill
    metadataRow(lane):=accepted.address(p.addressBits-1,0)
  }
  raw.foreach { service=>
    for((write,port)<-service.materialized.zipWithIndex){
      when(write.valid){assert(residentActive && write.bits<p.words.U,"resident write escaped its canonical tile owner")}
      metadataPending(wordPorts+port):=write.valid
      metadataDirty(wordPorts+port):=true.B
      metadataRow(wordPorts+port):=write.bits(p.addressBits-1,0)
    }
  }
  private val validSet=(0 until metadataPorts).map(i=>
    Mux(metadataPending(i),UIntToOH(metadataRow(i),p.words),0.U(p.words.W)))
  private val dirtySet=(0 until metadataPorts).map(i=>Mux(metadataDirty(i),validSet(i),0.U(p.words.W)))
  valid:=valid|validSet.reduce(_|_);dirty:=dirty|dirtySet.reduce(_|_)
  raw.foreach { service=>for(q<-service.query){
    // Native eligibility fixes the formats to C0 RGBA8 and D24S8. Use
    // captured packed clears directly, avoiding the generic byte-layout mux.
    val byteAddress=q.address(p.addressBits-1,0)<<3
    val c=descriptor.colors(0)
    val color=c.enabled && byteAddress>=c.offset && byteAddress<colorEnds(0)
    val z=depthViews.get(0)
    val depth=byteAddress>=z.offset && byteAddress<depthEnds.get(0)
    val available=(color && c.loadOp=/=AttachmentLoadOp.load.U) || (depth && z.loadOp=/=AttachmentLoadOp.load.U)
    val colorValue=Mux(c.loadOp===AttachmentLoadOp.clear.U,Fill(2,clearValues(0)(31,0)),0.U(64.W))
    val depthValue=Mux(z.loadOp===AttachmentLoadOp.clear.U,Fill(2,Cat(z.clearStencil,depthClearValues.get(0)(23,0))),0.U(64.W))
    q.available:=q.address<p.words.U && !validWord(q.address(p.addressBits-1,0)) && available
    q.data:=Mux(depth,depthValue,colorValue)
    when(q.valid){assert(residentActive && (phase===State.active || phase===State.finish),"resident query crossed tile setup or a legacy owner")}
  }}
  private val depthOffered=io.depth.map(d=>depthMode && VecInit(d.request.map(_.valid)).asUInt.orR).getOrElse(false.B)
  private val drained=memoryDrained && !refillOffered && !refillPending && !depthOffered &&
    resolving.map(_===Resolve.idle).reduce(_ && _) && !metadataPending.asUInt.orR &&
    !VecInit(wordRequests.map(_.io.deq.valid)).asUInt.orR
  io.depth.foreach(_.drained:=drained)
  raw.foreach(_.readyForResident:=phase===State.active && drained && !depthMode && !io.maintenance && !stop)
  when(residentActive){assert(!depthMode && phase=/=State.run && !maintenanceMode,"resident and legacy words have concurrent owners")}
  when(io.begin.fire){assert(!residentActive,"tile rebind crossed a resident owner")}
  private def fail(code: UInt,address: UInt): Unit={when(status===0.U && !io.cancel){status:=code;faultOffset:=address}}
  when(io.refillReply.fire && io.refillReply.bits.fault){fail(3.U,refillAddress<<3)}
  when(active && io.cancel && status===0.U){status:=4.U}
  when(anyFault){val selected=PriorityEncoder(VecInit(workers.map(_.fault)).asUInt)
    fail(3.U,VecInit(workers.map(_.faultOffset))(selected))}
  io.begin.ready:=phase===State.idle && !io.cancel
  io.in.ready:=phase===State.active && !io.maintenance && !depthMode && !residentActive && !stop
  io.memory.ready:=phase===State.active && io.maintenance && !depthMode && !residentActive && !stop
  io.end.ready:=phase===State.active && !depthMode && !residentActive && !Mux(io.maintenance,io.memory.valid,io.in.valid) && !stop
  io.out.valid:=phase===State.emit;io.out.bits:=output
  io.reply.valid:=phase===State.externalReply;io.reply.bits:=maintenanceResult
  io.completion.valid:=phase===State.complete;io.completion.bits:=result
  io.busy:=phase=/=State.idle;io.cancelSource:=active && stop
  switch(phase){
    is(State.idle){when(io.begin.fire){
      descriptor:=io.begin.bits;status:=0.U;faultOffset:=0.U;valid:=0.U;dirty:=0.U
      assert(!metadataPending.asUInt.orR,"tile rebind crossed an accepted metadata commit")
      metadataPending.foreach(_:=false.B)
      io.depth.foreach(depth=>depthViews.get:=depth.views)
      initialized.foreach(_:=false.B)
      quadStarted:=false.B;phase:=State.validate
    }}
    is(State.validate){
      val ends=TypedTileColorRules.ends(descriptor)
      val invalidDepth=depthViews.map{views=>
        val pixel=Wire(new TypedTilePixelDescriptor);pixel.version:=1.U;pixel.colors:=descriptor
        pixel.depth:=views(0);pixel.stencil:=views(1);TypedTilePixelRules.invalid(pixel,p)
      }.getOrElse(false.B)
      when(TypedTileColorRules.invalid(descriptor,p) || invalidDepth){fail(1.U,0.U);phase:=State.finish}
        .otherwise{
          for(c<-0 until 4){spans(c):=ends(c)-descriptor.colors(c).offset;colorEnds(c):=ends(c)
            clearWordPhases(c):=remainder3(descriptor.colors(c).offset(15,3))}
          depthViews.foreach { views=>for((view,index)<-views.zipWithIndex){
            depthEnds.get(index):=view.offset+&((1.U(18.W)<<((descriptor.tileLog2<<1)+&descriptor.sampleMode))*TypedDepthFormat.bytes(view.format))
          }}
          clearColor:=0.U;phase:=State.clearNext
        }
    }
    is(State.clearNext){val view=descriptor.colors(clearColor)
      when(!stop){when(view.enabled && view.loadOp===AttachmentLoadOp.clear.U){
        clearRequest.rgba:=view.clear;clearRequest.format:=view.format;clearRequest.colorType:=TypedColorFormat.kind(view.format)
        clearRequest.mask:=15.U;clearRequest.tag:=clearColor;clearOffered:=true.B;phase:=State.clearWait
      }.otherwise{clearValues(clearColor):=0.U;when(clearColor===3.U){phase:=State.active}.otherwise{clearColor:=clearColor+1.U}}}
    }
    is(State.clearWait){when(clearCodec.io.out.fire){assert(clearCodec.io.out.bits.status===0.U)
      clearValues(clearColor):=clearCodec.io.out.bits.value
      when(clearColor===3.U){phase:=State.active}.otherwise{clearColor:=clearColor+1.U;phase:=State.clearNext}}}
    is(State.active){
      when(io.in.fire){input:=io.in.bits;quadStarted:=false.B;phase:=State.checkQuad}
      when(io.memory.fire){maintenanceRequest:=io.memory.bits;phase:=State.maintenance}
      when(io.end.fire){when(!io.end.bits){fail(5.U,0.U)};phase:=State.finish}
    }
    is(State.checkQuad){
      when(TypedTileColorRules.invalidQuad(input,descriptor)){fail(6.U,0.U);phase:=State.quadDone}
        .elsewhen(start){quadStarted:=true.B;phase:=State.run}
    }
    is(State.run){when(workers.map(_.done).reduce(_ && _) && drained){phase:=State.quadDone}}
    is(State.quadDone){
      val q=input.quad
      output:=0.U.asTypeOf(output);output.identity:=q.identity;output.sequence:=q.sequence
      output.primitiveId:=q.primitiveId;output.instanceId:=q.instanceId;output.partIndex:=q.partIndex
      output.status:=Mux(io.cancel && status===0.U,4.U,status);output.faultOffset:=faultOffset
      when(quadStarted){output.touchedSamples:=PopCount(VecInit(workers.map(_.touched)).asUInt)
        output.completedLocations:=workers.map(_.locations).reduce(_+&_)
        output.wordWrites:=written;output.writtenBits:=workers.map(_.bits).reduce(_+&_)}
      phase:=State.emit
    }
    is(State.emit){when(io.out.fire){phase:=Mux(stop,State.finish,State.active)}}
    is(State.maintenance){
      val row=maintenanceRequest.address;val (available,value,_)=virtualWord(row)
      maintenanceResult:=0.U.asTypeOf(maintenanceResult);maintenanceResult.dirty:=dirtyWord(row)
      when(maintenanceRequest.fill && (validWord(row) || !maintenanceRequest.write || maintenanceRequest.byteMask=/=255.U)){
        maintenanceResult.fault:=true.B;maintenanceNext(State.externalReply)
      }.elsewhen(validWord(row)){maintenanceNext(State.maintenanceRead)}.otherwise{
        maintenanceResult.available:=available;maintenanceResult.data:=value
        when(maintenanceRequest.write){
          when(available || maintenanceRequest.byteMask===255.U){maintenanceNext(State.maintenanceWrite)}
            .otherwise{maintenanceResult.fault:=true.B;maintenanceNext(State.externalReply)}
        }.otherwise{maintenanceNext(State.externalReply)}
      }
    }
    is(State.maintenanceRead){when(clients(0).fire){maintenanceNext(State.maintenanceReadWait)}}
    is(State.maintenanceReadWait){when(responses(0).fire){
      maintenanceResult.available:=true.B;maintenanceResult.data:=responses(0).bits.data
      maintenanceResult.fault:=responses(0).bits.fault
      maintenanceNext(Mux(maintenanceRequest.write && !responses(0).bits.fault && !stop,State.maintenanceWrite,State.externalReply))
    }}
    is(State.maintenanceWrite){when(clients(0).fire){maintenanceNext(State.maintenanceWriteWait)}}
    is(State.maintenanceWriteWait){when(responses(0).fire){maintenanceResult.fault:=responses(0).bits.fault;maintenanceNext(State.externalReply)}}
    is(State.externalReply){when(io.reply.fire){phase:=Mux(stop,State.finish,State.active)}}
    is(State.finish){when(drained && !clearOffered && !clearPending){
      result.identity:=descriptor.identity;result.status:=Mux(io.cancel && status===0.U,4.U,status)
      result.inputQuads:=totalQuads;result.wordWrites:=totalWrites;result.faultOffset:=faultOffset;phase:=State.complete
    }}
    is(State.complete){when(io.completion.fire){phase:=State.idle}}
  }
  when(stop && (phase===State.validate || phase===State.clearNext || phase===State.clearWait || phase===State.active)){
    when(!clearOffered && !clearPending){phase:=State.finish}
  }
  when(stop && phase===State.checkQuad){phase:=State.quadDone}
  when(stop && (phase===State.maintenance || maintenanceMode)){
    maintenanceResult:=0.U.asTypeOf(maintenanceResult);maintenanceResult.fault:=true.B
    when(phase===State.maintenance){maintenanceNext(State.externalReply)}
  }
}
