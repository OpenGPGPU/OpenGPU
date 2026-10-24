package opengpu.graphics

import chisel3._
import chisel3.util._

class TypedTileColorLaneIO extends Bundle {
  val start=Input(Bool());val cancel=Input(Bool())
  val descriptor=Input(new TypedTileColorDescriptor);val input=Input(new TypedTileColorQuad)
  val request=Decoupled(new TypedTileWordRequest);val response=Flipped(Decoupled(new TypedTileWordResponse))
  val done=Output(Bool());val fault=Output(Bool());val faultOffset=Output(UInt(17.W))
  val touched=Output(UInt(4.W));val locations=Output(UInt(5.W));val writes=Output(UInt(6.W));val bits=Output(UInt(12.W))
}

/** Sixty-four disjoint pixel/sample/location owners share one streaming blend.
  * A quad stays owned until every word, formatter and arithmetic result drains.
  * The enclosing store performs layout/quad preflight and lazy materialization.
  */
class StreamingTypedTileColorLanes(wordPorts: Int=8) extends Module {
  require(wordPorts==4 || wordPorts==8)
  private val channels=wordPorts/4
  val io=IO(new Bundle {
    val lanes=Vec(4,new TypedTileColorLaneIO)
    val extra=if(wordPorts==8)Some(new Bundle {
      val request=Vec(4,Decoupled(new TypedTileWordRequest))
      val response=Vec(4,Flipped(Decoupled(new TypedTileWordResponse)))
    })else None
  })
  private object Job extends ChiselEnum {
    val prepare,readReady,memoryFlight,blendPrepare,blendReady,blendFlight,
      encodeReady,encodeFlight,waitSource,writeReady,finishLocation,done=Value
  }
  private val states=RegInit(VecInit(Seq.fill(64)(Job.done)))
  private val addresses=Reg(Vec(64,UInt(17.W)));private val counts=Reg(Vec(64,UInt(2.W)))
  private val words=Reg(Vec(64,UInt(2.W)));private val destinations=Reg(Vec(64,Vec(3,UInt(64.W))))
  private val packedDestination=Reg(Vec(64,UInt(128.W)));private val blended=Reg(Vec(64,Vec(4,UInt(32.W))))
  private val writeData=Reg(Vec(64,Vec(3,UInt(64.W))));private val writeMask=Reg(Vec(64,Vec(3,UInt(64.W))))
  private val blending=Reg(Vec(64,Bool()))
  private val descriptor=Reg(new TypedTileColorDescriptor);private val input=Reg(new TypedTileColorQuad)
  private val active=RegInit(false.B);private val killed=RegInit(false.B)
  private val faults=RegInit(VecInit(Seq.fill(4)(false.B)));private val faultOffsets=Reg(Vec(4,UInt(17.W)))
  private val touched=RegInit(VecInit(Seq.fill(4)(0.U(4.W))))
  private val locations=RegInit(VecInit(Seq.fill(4)(0.U(5.W))))
  private val writes=RegInit(VecInit(Seq.fill(4)(0.U(6.W))));private val bits=RegInit(VecInit(Seq.fill(4)(0.U(12.W))))
  private val start=io.lanes.head.start
  private val stop=killed || io.lanes.map(_.cancel).reduce(_ || _) || faults.asUInt.orR
  when(active && stop){killed:=true.B}

  private val blend=Module(new StreamingTypedColorBlend)
  private val blendOffered=RegInit(false.B);private val blendRequest=Reg(new TypedColorBlendRequest)
  blend.io.in.valid:=blendOffered;blend.io.in.bits:=blendRequest
  // A request already offered to the arithmetic service remains irrevocable.
  blend.io.cancel:=stop && !blendOffered;blend.io.out.ready:=true.B
  when(blend.io.in.fire){blendOffered:=false.B}
  private val blendLanes=Seq.fill(4)(Module(new RRArbiter(new TypedColorBlendRequest,16)))
  private val blendArbiter=Module(new RRArbiter(new TypedColorBlendRequest,4))
  for(lane<-0 until 4){
    blendArbiter.io.in(lane)<>blendLanes(lane).io.out
    for(local<-0 until 16){
      val id=lane*16+local;val color=local/4;val offer=blendLanes(lane).io.in(local)
      offer.valid:=active && !stop && states(id)===Job.blendReady
      offer.bits.source:=input.quad.lanes(lane).colors(color)
      offer.bits.destination:=packedDestination(id);offer.bits.state:=input.blend
      offer.bits.format:=descriptor.colors(color).format;offer.bits.tag:=id.U
    }
  }
  // Replace an accepted offer on the same edge so the shared II=1 blend
  // pipeline receives consecutive owners. A stalled offer remains immutable.
  blendArbiter.io.out.ready:= (!blendOffered || blend.io.in.ready) && !stop
  when(blendArbiter.io.out.fire){
    blendRequest:=blendArbiter.io.out.bits;blendOffered:=true.B
    states(blendArbiter.io.out.bits.tag(5,0)):=Job.blendFlight
  }
  when(blend.io.out.fire){
    val id=blend.io.out.bits.tag(5,0)
    assert(blend.io.out.bits.tag<64.U && states(id)===Job.blendFlight,"blend reply lost its sample/location owner")
    assert(blend.io.out.bits.status===0.U,"validated typed blend was rejected")
    blended(id):=blend.io.out.bits.rgba
    states(id):=Mux(stop || blend.io.out.bits.cancelled,Job.done,Job.encodeReady)
  }

  private val memoryOffered=RegInit(VecInit(Seq.fill(wordPorts)(false.B)))
  private val memoryPending=RegInit(VecInit(Seq.fill(wordPorts)(false.B)))
  private val memoryRequests=Reg(Vec(wordPorts,new TypedTileWordRequest));private val memoryOwners=Reg(Vec(wordPorts,UInt(4.W)))
  private val codecArbiters=Seq.fill(4)(Module(new RRArbiter(new TypedColorEncodeRequest,16)))
  private val codecReplies=Wire(Vec(4,Valid(new TypedColorEncodeResult)))
  private val codecDrained=Wire(Bool())
  locally {
    val encoder=Module(new StreamingTypedColorEncoder)
    val arbiter=Module(new RRArbiter(new TypedColorEncodeRequest,4))
    val offered=RegInit(false.B);val request=Reg(new TypedColorEncodeRequest)
    for(lane<-0 until 4){
      arbiter.io.in(lane)<>codecArbiters(lane).io.out
      codecReplies(lane).valid:=encoder.io.out.fire && encoder.io.out.bits.tag(5,4)===lane.U
      codecReplies(lane).bits:=encoder.io.out.bits
    }
    encoder.io.in.valid:=offered;encoder.io.in.bits:=request;encoder.io.out.ready:=true.B
    // Retain irrevocable offers, replacing a consumed offer on the same edge.
    // This register must not introduce a bubble into the encoder's II=1 input.
    arbiter.io.out.ready:=(!offered || encoder.io.in.ready) && !stop
    when(encoder.io.in.fire){offered:=false.B}
    when(arbiter.io.out.fire){
      offered:=true.B;request:=arbiter.io.out.bits
      states(arbiter.io.out.bits.tag(5,0)):=Job.encodeFlight
    }
    codecDrained:= !offered && !encoder.io.busy
  }

  for(lane<-0 until 4){
    val codecArbiter=codecArbiters(lane)
    for(local<-0 until 16){
      val id=lane*16+local;val color=local/4;val sample=local%4
      val encoding=codecArbiter.io.in(local)
      val write=states(id)===Job.writeReady
      encoding.valid:=active && !stop && states(id)===Job.encodeReady
      encoding.bits.rgba:=Mux(blending(id),blended(id),input.quad.lanes(lane).colors(color))
      encoding.bits.format:=descriptor.colors(color).format
      encoding.bits.colorType:=TypedColorFormat.kind(descriptor.colors(color).format)
      encoding.bits.mask:=descriptor.colors(color).channelMask;encoding.bits.tag:=id.U

      when(states(id)===Job.prepare){
        val view=descriptor.colors(color);val v=input.quad.lanes(lane)
        val x=(v.x.pad(33)-descriptor.originX.pad(33)).asUInt
        val y=(v.y.pad(33)-descriptor.originY.pad(33)).asUInt
        val bytes=TypedColorFormat.bytes(view.format)
        val address=view.offset+((((y<<descriptor.tileLog2)+x)<<descriptor.sampleMode)+sample.U)*bytes
        val present=(0 until 4).map(c=>view.channelMask(c) && TypedColorFormat.componentWidth(view.format,c.U)=/=0.U).reduce(_ || _)
        val live=view.enabled && v.colorMask(color) && present && input.samples(lane)(sample)
        val needsBlend=input.blend.enable && TypedColorFormat.kind(view.format)===0.U
        addresses(id):=address;counts(id):=(address(2,0)+&bytes+7.U)>>3;words(id):=0.U
        blending(id):=needsBlend
        // Source packing is shared by all selected samples of an unblended location.
        states(id):=Mux(!live,Job.done,Mux(needsBlend,Job.readReady,
          Mux(sample.U===PriorityEncoder(input.samples(lane)),Job.encodeReady,Job.waitSource)))
      }
      when(states(id)===Job.blendPrepare){
        packedDestination(id):=(Cat(destinations(id).reverse)>>(addresses(id)(2,0)<<3))(127,0)
        states(id):=Job.blendReady
      }
      when(write && !writeMask(id)(words(id)).orR && !stop){
        when(words(id)+1.U===counts(id)){states(id):=Job.finishLocation}
          .otherwise{words(id):=words(id)+1.U}
      }
      when(states(id)===Job.finishLocation && !stop){states(id):=Job.done}
      // In-flight offers, replies and arithmetic retain their owners on stop.
      when(stop && states(id)=/=Job.memoryFlight && states(id)=/=Job.blendFlight && states(id)=/=Job.encodeFlight){states(id):=Job.done}
    }
    val completed=PopCount(VecInit((0 until 16).map(local=>states(lane*16+local)===Job.finishLocation && !stop)))
    locations(lane):=locations(lane)+completed
    val reply=codecReplies(lane)
    when(reply.valid){
      val owner=reply.bits.tag(3,0);val id=Cat(lane.U(2.W),owner)
      assert(reply.bits.tag===id && states(id)===Job.encodeFlight,"encoder reply lost its sample/location owner")
      assert(reply.bits.status===0.U,"validated typed packing was rejected")
      for(local<-0 until 16){
        val target=lane*16+local
        val own=owner===local.U
        val share= !blending(id) && owner(3,2)===(local/4).U && states(target)===Job.waitSource
        when(own || share){
          val data=reply.bits.value.pad(192)<<(addresses(target)(2,0)<<3)
          val mask=reply.bits.mask.pad(192)<<(addresses(target)(2,0)<<3)
          for(word<-0 until 3){writeData(target)(word):=(data>>(64*word))(63,0);writeMask(target)(word):=(mask>>(64*word))(63,0)}
          words(target):=0.U;states(target):=Mux(stop || !reply.bits.mask.orR,Job.done,Job.writeReady)
        }
      }
    }
  }
  private val ackWrites=Wire(Vec(wordPorts,Bool()))
  private val ackBits=Wire(Vec(wordPorts,UInt(7.W)))
  private val ackTouched=Wire(Vec(wordPorts,UInt(4.W)))
  private val wordFault=Wire(Vec(wordPorts,Bool()))
  private val wordFaultOffsets=Wire(Vec(wordPorts,UInt(17.W)))
  for(portId<-0 until wordPorts){
    val lane=portId%4;val channel=portId/4
    val members=(0 until 16).filter(local=>(local/4)%channels==channel)
    val request=if(portId<4)io.lanes(lane).request else io.extra.get.request(lane)
    val response=if(portId<4)io.lanes(lane).response else io.extra.get.response(lane)
    request.valid:=memoryOffered(portId);request.bits:=memoryRequests(portId)
    response.ready:=memoryPending(portId)
    when(request.fire){memoryOffered(portId):=false.B;memoryPending(portId):=true.B}
    val arbiter=Module(new RRArbiter(new TypedTileWordRequest,members.size))
    arbiter.io.out.ready:= !memoryOffered(portId) && !memoryPending(portId) && !stop
    for((local,index)<-members.zipWithIndex){
      val id=lane*16+local;val candidate=arbiter.io.in(index)
      val read=states(id)===Job.readReady;val write=states(id)===Job.writeReady
      candidate.valid:=active && !stop && (read || (write && writeMask(id)(words(id)).orR))
      candidate.bits.address:=(addresses(id)>>3)+words(id);candidate.bits.write:=write
      candidate.bits.data:=writeData(id)(words(id));candidate.bits.mask:=Mux(write,writeMask(id)(words(id)),0.U)
      candidate.bits.tag:=id.U
    }
    when(arbiter.io.out.fire){
      val local=arbiter.io.out.bits.tag(3,0);val id=Cat(lane.U(2.W),local)
      memoryRequests(portId):=arbiter.io.out.bits;memoryOwners(portId):=local;memoryOffered(portId):=true.B
      states(id):=Job.memoryFlight
    }
    val wrote=response.fire && !response.bits.fault && memoryRequests(portId).write
    ackWrites(portId):=wrote;ackBits(portId):=Mux(wrote,PopCount(memoryRequests(portId).mask),0.U)
    ackTouched(portId):=Mux(wrote,1.U(4.W)<<memoryOwners(portId)(1,0),0.U)
    wordFault(portId):=response.fire && response.bits.fault
    wordFaultOffsets(portId):=memoryRequests(portId).address<<3
    when(response.fire){
      val local=memoryOwners(portId);val id=Cat(lane.U(2.W),local);val accepted=memoryRequests(portId)
      assert(response.bits.tag===id && states(id)===Job.memoryFlight,"word reply lost its sample/location owner")
      memoryPending(portId):=false.B
      when(response.bits.fault){states(id):=Job.done}.otherwise{
        when(accepted.write){
          when(stop){states(id):=Job.done}
            .elsewhen(words(id)+1.U===counts(id)){states(id):=Job.finishLocation}
            .otherwise{words(id):=words(id)+1.U;states(id):=Job.writeReady}
        }.otherwise{
          destinations(id)(words(id)):=response.bits.data
          when(stop){states(id):=Job.done}
            .elsewhen(words(id)+1.U===counts(id)){states(id):=Job.blendPrepare}
            .otherwise{words(id):=words(id)+1.U;states(id):=Job.readReady}
        }
      }
    }
  }
  for(lane<-0 until 4){
    val ports=(lane until wordPorts by 4)
    writes(lane):=writes(lane)+PopCount(VecInit(ports.map(ackWrites(_))))
    bits(lane):=bits(lane)+ports.map(ackBits(_)).reduce(_+&_)
    touched(lane):=touched(lane)|ports.map(ackTouched(_)).reduce(_|_)
    val failures=VecInit(ports.map(wordFault(_))).asUInt
    when(failures.orR && !faults(lane)){
      faults(lane):=true.B
      faultOffsets(lane):=(if(ports.size==1)wordFaultOffsets(ports.head)else
        VecInit(ports.map(wordFaultOffsets(_)))(PriorityEncoder(failures)))
    }
  }
  private val drained= !blendOffered && !blend.io.busy &&
    !memoryOffered.asUInt.orR && !memoryPending.asUInt.orR && codecDrained
  private val done=active && states.map(_===Job.done).reduce(_ && _) && drained
  for(lane<-0 until 4){
    io.lanes(lane).done:=done;io.lanes(lane).fault:=faults(lane);io.lanes(lane).faultOffset:=faultOffsets(lane)
    io.lanes(lane).touched:=touched(lane);io.lanes(lane).locations:=locations(lane)
    io.lanes(lane).writes:=writes(lane);io.lanes(lane).bits:=bits(lane)
  }
  when(start){
    assert(!active || done,"streaming color contexts rebound before drain")
    assert(io.lanes.map(_.start).reduce(_ && _),"quad lanes started separately")
    descriptor:=io.lanes.head.descriptor;input:=io.lanes.head.input;active:=true.B;killed:=false.B
    for(id<-0 until 64)states(id):=Job.prepare
    for(lane<-0 until 4){faults(lane):=false.B;faultOffsets(lane):=0.U;touched(lane):=0.U
      locations(lane):=0.U;writes(lane):=0.U;bits(lane):=0.U}
  }
}
