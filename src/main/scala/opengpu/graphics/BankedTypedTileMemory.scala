package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.core.memory.L2SramArray

class TypedTileWordRequest extends Bundle {
  val address=UInt(32.W) // 64-bit word address, before bank selection
  val write=Bool()
  val data=UInt(64.W)
  val mask=UInt(64.W) // bit mask preserves packed channel neighbours
  val tag=UInt(16.W)
}
class TypedTileWordResponse extends Bundle {
  val data=UInt(64.W)
  val tag=UInt(16.W)
  val fault=Bool()
}
private class RoutedTypedTileWordResponse(ports: Int) extends Bundle {
  val port=UInt(math.max(1,log2Ceil(ports)).W)
  val response=new TypedTileWordResponse
}

/** Internal tile SRAM service for parallel typed color/depth pipelines.
  * Consecutive words select different independent single-port banks. Reads and
  * full writes can issue every clock; partial writes reserve their bank for a
  * second clock to merge the synchronous read, without a byte-write macro.
  * Same-bank operations serialize fairly. Replies carry tags and may reorder
  * across banks; each accepted operation produces exactly one reply.
  *
  * Optional row hashing preserves word-pair parity and the physical row, while
  * spreading aligned color/depth planes over the remaining bank bits. Logical
  * word addresses and reply tags are unchanged; all clients share one mapping.
  *
  * Pass ownership, clear/valid tags and refill remain the caller's contracts.
  * Accepted writes are irrevocable: cancel/release must drain this service.
  * Reset discards protocol state, rather than initializing SRAM contents.
  */
class BankedTypedTileMemory(p: TypedTileColorConfig=TypedTileColorConfig(),
    ports: Int=4,banks: Int=8,useBlackBoxes: Boolean=false,xorBanks: Boolean=false) extends Module {
  require(ports>0 && ports<=16)
  require(isPow2(banks) && banks>=2 && banks<=16 && p.words%banks==0)
  private val bankBits=log2Ceil(banks)
  require(!xorBanks || banks>=4,"pair-preserving row hashing needs at least four banks")
  private def bankOf(address: UInt): UInt={
    val low=address(bankBits-1,0)
    if(!xorBanks)low else{
      // Within each row, XOR is a permutation. Keep adjacent aligned pairs
      // distinct even when an attachment plane starts at a higher row.
      val width=bankBits-1
      val rowDigits=(bankBits until p.addressBits by width).map { shift=>
        address(math.min(shift+width-1,p.addressBits-1),shift).pad(width)
      }
      val upper=rowDigits.foldLeft(low(bankBits-1,1))(_^_)
      Cat(upper,low(0))
    }
  }
  private val rows=p.words/banks
  private val segmentRows=math.min(256,rows)
  private val segments=rows/segmentRows
  private val segmentBits=log2Ceil(segmentRows)
  val io=IO(new Bundle {
    val request=Vec(ports,Flipped(Decoupled(new TypedTileWordRequest)))
    val response=Vec(ports,Decoupled(new TypedTileWordResponse))
    val drained=Output(Bool())
  })
  private val selectedBanks=io.request.map(r=>bankOf(r.bits.address))
  private val queues=Seq.fill(banks)(Module(new Queue(new RoutedTypedTileWordResponse(ports),2)))
  private val pending=RegInit(VecInit(Seq.fill(banks)(false.B)))
  private val operation=Reg(Vec(banks,new TypedTileWordRequest))
  private val source=Reg(Vec(banks,UInt(math.max(1,log2Ceil(ports)).W)))
  private val captured=RegInit(VecInit(Seq.fill(banks)(false.B)))
  private val capturedData=Reg(Vec(banks,UInt(64.W)))
  private val arbiters=Seq.fill(banks)(Module(new RRArbiter(new TypedTileWordRequest,ports)))
  for(port<-0 until ports){
    io.request(port).ready:=VecInit((0 until banks).map(bank=>arbiters(bank).io.in(port).ready &&
      selectedBanks(port)===bank.U)).asUInt.orR
  }
  for(bank<-0 until banks){
    val arbiter=arbiters(bank);val held=operation(bank);val queue=queues(bank)
    for(port<-0 until ports){
      arbiter.io.in(port).valid:=io.request(port).valid && selectedBanks(port)===bank.U
      arbiter.io.in(port).bits:=io.request(port).bits
    }
    val heldLegal=held.address<p.words.U
    val partial=pending(bank) && heldLegal && held.write && held.mask.orR && !held.mask.andR
    arbiter.io.out.ready:= !partial && (!pending(bank) || queue.io.enq.ready)
    val request=arbiter.io.out.bits
    val legal=request.address<p.words.U
    val read=arbiter.io.out.fire && legal && (!request.write || (request.mask.orR && !request.mask.andR))
    val fullWrite=arbiter.io.out.fire && legal && request.write && request.mask.andR
    val mergeWrite=partial && queue.io.enq.ready
    val selectedRow=Mux(mergeWrite,held.address,request.address)>>bankBits
    val readSegment=RegEnable(((request.address>>bankBits)>>segmentBits)(math.max(1,log2Ceil(segments))-1,0),read)
    val memories=Seq.fill(segments)(Module(new L2SramArray(segmentRows,64,useBlackBoxes)))
    val readData=if(segments==1)memories.head.io.readData else VecInit(memories.map(_.io.readData))(readSegment)
    val old=Mux(captured(bank),capturedData(bank),readData)
    val writeData=Mux(mergeWrite,(old& ~held.mask)|(held.data&held.mask),request.data)
    for((memory,index)<-memories.zipWithIndex){
      val selected=(selectedRow>>segmentBits)===index.U
      memory.io.readEnable:=read && selected
      memory.io.writeEnable:=(fullWrite || mergeWrite) && selected
      memory.io.readAddress:=selectedRow
      memory.io.writeAddress:=selectedRow
      memory.io.writeData:=writeData
    }
    queue.io.enq.valid:=pending(bank)
    queue.io.enq.bits.port:=source(bank)
    queue.io.enq.bits.response.tag:=held.tag
    queue.io.enq.bits.response.fault:= !heldLegal
    queue.io.enq.bits.response.data:=Mux(heldLegal && !held.write,old,0.U)
    when(queue.io.enq.fire){pending(bank):=false.B}
    when(pending(bank) && !captured(bank)){capturedData(bank):=readData;captured(bank):=true.B}
    when(arbiter.io.out.fire){
      operation(bank):=request;source(bank):=arbiter.io.chosen;pending(bank):=true.B;captured(bank):=false.B
    }
    assert(!(read && (fullWrite || mergeWrite)),"a tile SRAM bank has one physical port")
  }
  private val replyArbiters=Seq.fill(ports)(Module(new RRArbiter(new TypedTileWordResponse,banks)))
  private val replies=Seq.fill(ports)(Module(new Queue(new TypedTileWordResponse,2)))
  for(port<-0 until ports){
    val arbiter=replyArbiters(port)
    for(bank<-0 until banks){
      arbiter.io.in(bank).valid:=queues(bank).io.deq.valid && queues(bank).io.deq.bits.port===port.U
      arbiter.io.in(bank).bits:=queues(bank).io.deq.bits.response
    }
    replies(port).io.enq<>arbiter.io.out
    io.response(port)<>replies(port).io.deq
  }
  for(bank<-0 until banks){
    queues(bank).io.deq.ready:=VecInit((0 until ports).map(port=>
      queues(bank).io.deq.bits.port===port.U && replyArbiters(port).io.in(bank).ready)).asUInt.orR
  }
  io.drained:= !pending.asUInt.orR && !VecInit(queues.map(_.io.deq.valid)).asUInt.orR &&
    !VecInit(replies.map(_.io.deq.valid)).asUInt.orR
}
