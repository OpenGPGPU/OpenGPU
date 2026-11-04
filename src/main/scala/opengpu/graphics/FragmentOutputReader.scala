package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.{CachePolicy,GpuConfig}
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}

class FragmentOutputReadWindow extends Bundle {
  val base=UInt(32.W);val lanes=UInt(5.W);val fields=UInt(5.W)
}
class FragmentOutputCacheWrite extends Bundle {
  val field=UInt(5.W);val row=UInt(2.W);val data=UInt(128.W)
}

/** Ordered sixteen-lane transpose fills using the two staging transaction IDs.
  * Two 64-byte buffers accept reordered first/second line replies while the
  * preceding complete field is written to the existing 128-bit SRAM port.
  * An offered request remains owned until its response, including cancellation.
  */
class FragmentOutputReader(config: GpuConfig,maxFields: Int) extends Module {
  require(maxFields>0 && maxFields<=31)
  require(config.xLen==32 && config.lanes*config.warps>=4 && (config.lanes*config.warps)%4==0)
  private val stride=config.lanes*config.warps*4
  val io=IO(new Bundle {
    val start=Flipped(Decoupled(new FragmentOutputReadWindow))
    val request=Decoupled(new ComputeMemoryRequest(config,64,2))
    val response=Flipped(Decoupled(new ComputeMemoryResponse(64,2)))
    val write=Output(Valid(new FragmentOutputCacheWrite))
    val done=Decoupled(Bool())
    val cancel=Input(Bool());val busy=Output(Bool());val outstanding=Output(UInt(2.W))
  })
  private object State extends ChiselEnum {val idle,active,complete=Value}
  private val state=RegInit(State.idle)
  private val descriptor=Reg(new FragmentOutputReadWindow)
  private val allocated=RegInit(0.U(5.W));private val head=RegInit(false.B);private val tail=RegInit(false.B)
  private val row=RegInit(0.U(2.W));private val failed=RegInit(false.B)
  private val valid=RegInit(VecInit(Seq.fill(2)(false.B)))
  private val fields=Reg(Vec(2,UInt(5.W)));private val addresses=Reg(Vec(2,UInt(32.W)))
  private val ends=Reg(Vec(2,UInt(7.W)))
  private val data=Reg(Vec(2,UInt(512.W)))
  private val sent=RegInit(VecInit(Seq.fill(2)(0.U(2.W))))
  private val received=RegInit(VecInit(Seq.fill(2)(0.U(2.W))))
  private val owned=RegInit(VecInit(Seq.fill(2)(false.B)))
  private val issued=RegInit(VecInit(Seq.fill(2)(false.B)))
  private val owners=Reg(Vec(2,Bool()));private val second=Reg(Vec(2,Bool()))
  private val offered=RegInit(false.B)
  private val request=Reg(new ComputeMemoryRequest(config,64,2))
  private val stopping=failed || io.cancel || (io.response.valid && io.response.bits.fault)
  private val bytes=descriptor.lanes<<2
  private def split(slot: UInt): Bool=ends(slot)>64.U
  private def needed(slot: UInt): UInt=Mux(split(slot),3.U(2.W),1.U(2.W))
  private def needs(slot: UInt): Bool=valid(slot) && sent(slot)=/=needed(slot)

  io.start.ready:=state===State.idle && !io.cancel
  io.busy:=state=/=State.idle;io.outstanding:=PopCount(owned)
  io.request.valid:=offered;io.request.bits:=request
  io.response.ready:=state===State.active
  io.done.valid:=state===State.complete;io.done.bits:=failed
  io.write.valid:=state===State.active && !stopping && valid(head) && received(head)===needed(head)
  io.write.bits.field:=fields(head);io.write.bits.row:=row
  io.write.bits.data:=(data(head)>>(row<<7))(127,0)

  when(io.start.fire){
    assert(io.start.bits.lanes=/=0.U && io.start.bits.lanes<=math.min(16,config.lanes*config.warps).U && io.start.bits.lanes(1,0)===0.U,
      "fragment readback window must contain complete quads within sixteen lanes")
    assert(io.start.bits.fields=/=0.U && io.start.bits.fields<=maxFields.U,
      "fragment readback fields exceed the transpose SRAM")
    descriptor:=io.start.bits;allocated:=0.U;head:=false.B;tail:=false.B;row:=0.U;failed:=false.B
    valid.foreach(_:=false.B);sent.foreach(_:=0.U);received.foreach(_:=0.U)
    assert(!owned.asUInt.orR && !offered,"fragment readback reused an owned request")
    state:=State.active
  }
  private val allocate=state===State.active && !stopping && allocated<descriptor.fields && !valid(tail)
  when(allocate){
    val address=descriptor.base+allocated*stride.U
    valid(tail):=true.B;fields(tail):=allocated;addresses(tail):=address
    // Valid windows contain at most 64 bytes, so the exclusive end is <=127.
    // Capture it before slot selection and parallel byte-mask comparisons.
    ends(tail):=(address(5,0)+&bytes)(6,0)
    data(tail):=0.U;sent(tail):=0.U;received(tail):=0.U
    allocated:=allocated+1.U;tail:= !tail
  }
  private val slot=Mux(needs(head),head,!head)
  private val tag=PriorityEncoder(~owned.asUInt)
  when(state===State.active && !stopping && !offered && !owned.asUInt.andR && needs(slot)){
    val isSecond=sent(slot)(0)
    val offset=addresses(slot)(5,0)
    val end=ends(slot)
    val line=Cat(addresses(slot)(31,6),0.U(6.W))
    request:=0.U.asTypeOf(request);request.transactionId:=tag
    request.address:=Mux(isSecond,line+64.U,line)
    request.byteMask:=VecInit((0 until 64).map(b=>
      Mux(isSecond,end>(b+64).U,offset<=b.U && end>b.U))).asUInt
    request.sizeLog2:=6.U;request.cachePolicy:=CachePolicy.uncached
    offered:=true.B;owned(tag):=true.B;issued(tag):=false.B;owners(tag):=slot;second(tag):=isSecond
    sent(slot):=sent(slot)|Mux(isSecond,2.U,1.U)
  }
  when(io.request.fire){offered:=false.B;issued(request.transactionId):=true.B}
  when(io.response.fire){
    val id=io.response.bits.transactionId;val destination=owners(id)
    assert(owned(id) && (issued(id) || (io.request.fire && request.transactionId===id)),
      "fragment readback response lost its transaction owner")
    assert(valid(destination),"fragment readback response lost its field")
    when(!stopping){
      val shift=addresses(destination)(5,0)<<3
      // A split always has a nonzero offset. Modular negation keeps the barrel
      // shift bounded to nine bits; zero is used only by an unsplit first line.
      val reverse=(0.U(9.W)-shift)(8,0)
      val part=Mux(second(id),(io.response.bits.readData<<reverse)(511,0),io.response.bits.readData>>shift)
      data(destination):=data(destination)|part
      received(destination):=received(destination)|Mux(second(id),2.U,1.U)
    }
    owned(id):=false.B;issued(id):=false.B
  }
  when(io.write.valid){
    when(row===3.U){valid(head):=false.B;head:= !head;row:=0.U}.otherwise{row:=row+1.U}
  }
  when(state===State.active){
    when(stopping){failed:=true.B}
    when(!offered && !owned.asUInt.orR &&
      (stopping || (allocated===descriptor.fields && !valid.asUInt.orR))){
      valid.foreach(_:=false.B);state:=State.complete
    }
  }
  when(io.done.fire){state:=State.idle}
  when(state===State.active && !stopping){
    for(i<-0 until 2){
      assert((received(i)& ~sent(i))===0.U,"fragment readback received an unposted line")
    }
  }
  when(state===State.complete){assert(!offered && !owned.asUInt.orR,"fragment readback completed before draining")}
}
