package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.core.memory.L2SramArray

class BankedTextureLookup(tagWidth: Int,cachePolicyInput: Boolean=false) extends Bundle {
  val identity=new GraphicsWorkIdentity;val textureKey=UInt(64.W)
  /** Physical word address after successful translation/permission admission. */
  val address=UInt(32.W);val rowParity=Bool();val tag=UInt(tagWidth.W)
  val noCache=if(cachePolicyInput)Some(Bool())else None
}
class BankedTextureWord(tagWidth: Int) extends Bundle {
  val tag=UInt(tagWidth.W);val hit=Bool();val data=UInt(32.W)
}
class BankedTextureRefill extends Bundle {
  val identity=new GraphicsWorkIdentity;val textureKey=UInt(64.W)
  val address=UInt(32.W);val rowParity=Bool();val data=UInt(512.W);val fault=Bool()
}
class BankedTextureRefillCompletion extends Bundle {
  val identity=new GraphicsWorkIdentity;val textureKey=UInt(64.W)
  val address=UInt(32.W);val rowParity=Bool();val installed=Bool();val fault=Bool();val invalidated=Bool()
}
/** Four logical single-port word banks: bank=(rowParity,physicalAddress[2]).
  * An aligned64-byte row segment occupies8 words in each bank of its parity
  * pair. Each pair has independent physical-line/context/view tags, so default
  * storage holds128 unique lines (8KiB), with no replicated data copies.
  *
  * This store has no TLB or miss scheduler. The owner admits physical reads,
  * resolves explicit misses/faults and pins VM/publication through drain.
  * Its aligned RGBA8 row mapping requires64-byte-aligned row starts/pitches;
  * unsupported layouts use the general sampler. Two data banks are written
  * for8 cycles per refill;the other pair can keep reading. Failed fills never
  * publish valid data. Invalidation revokes reuse,not accepted reply ownership.
  */
class BankedTextureLineStore(dataBytes: Int=8192,tagWidth: Int=16,useBlackBoxes: Boolean=false) extends Module {
  require(isPow2(dataBytes) && dataBytes>=256 && dataBytes<=32768 && tagWidth>0)
  private val sets=dataBytes/128;private val setWidth=log2Ceil(sets);private val rows=sets*8
  private val partDepth=math.min(256,rows);private val parts=rows/partDepth
  private val partBits=log2Ceil(parts);private val rowBits=log2Ceil(partDepth)
  val io=IO(new Bundle {
    val in=Vec(4,Flipped(Decoupled(new BankedTextureLookup(tagWidth))))
    val out=Vec(4,Decoupled(new BankedTextureWord(tagWidth)))
    val refill=Flipped(Decoupled(new BankedTextureRefill));val refillDone=Decoupled(new BankedTextureRefillCompletion)
    val invalidate=Input(Bool());val busy=Output(Bool())
    val readIssues=Output(UInt(3.W));val conflicts=Output(UInt(3.W));val writeIssues=Output(UInt(2.W))
  })
  private class Key extends Bundle {val identity=new GraphicsWorkIdentity;val textureKey=UInt(64.W);val physicalTag=UInt((26-setWidth).W)}
  private val valid=RegInit(VecInit(Seq.fill(2)(VecInit(Seq.fill(sets)(false.B)))))
  private val keys=Reg(Vec(2,Vec(sets,new Key)))
  private val fill=Reg(new BankedTextureRefill);private val filling=RegInit(false.B);private val beat=Reg(UInt(3.W));private val noFill=RegInit(false.B)
  private val completed=RegInit(false.B);private val completion=Reg(new BankedTextureRefillCompletion)
  private def index(a: UInt): UInt=a(5+setWidth,6)
  io.refill.ready:= !filling && !completed;io.refillDone.valid:=completed;io.refillDone.bits:=completion
  when(io.refillDone.fire){completed:=false.B}
  when(io.refill.fire){
    assert(io.refill.bits.address(5,0)===0.U,"texture refill must be an admitted aligned physical line")
    fill:=io.refill.bits;beat:=0.U;noFill:=io.invalidate
    valid(io.refill.bits.rowParity.asUInt)(index(io.refill.bits.address)):=false.B
    filling:= !io.refill.bits.fault
    when(io.refill.bits.fault){completed:=true.B;completion:=0.U.asTypeOf(completion)
      completion.identity:=io.refill.bits.identity;completion.textureKey:=io.refill.bits.textureKey
      completion.address:=io.refill.bits.address;completion.rowParity:=io.refill.bits.rowParity
      completion.fault:=true.B;completion.invalidated:=io.invalidate}
  }
  when(filling){
    when(io.invalidate){noFill:=true.B}
    when(beat===7.U){filling:=false.B;completed:=true.B
      val install= !noFill && !io.invalidate
      valid(fill.rowParity.asUInt)(index(fill.address)):=install
      val key=keys(fill.rowParity.asUInt)(index(fill.address))
      key.identity:=fill.identity;key.textureKey:=fill.textureKey;key.physicalTag:=fill.address(31,6+setWidth)
      completion:=0.U.asTypeOf(completion);completion.identity:=fill.identity;completion.textureKey:=fill.textureKey
      completion.address:=fill.address;completion.rowParity:=fill.rowParity;completion.installed:=install
      completion.invalidated:=noFill || io.invalidate
    }.otherwise{beat:=beat+1.U}
  }
  when(io.invalidate){valid.foreach(_.foreach(_:=false.B))}
  private val arbiters=Seq.fill(4)(Module(new RRArbiter(new BankedTextureLookup(tagWidth),4)))
  private val replies=Seq.fill(4)(Module(new Queue(new BankedTextureWord(tagWidth),2,pipe=true)))
  private val flight=RegInit(VecInit(Seq.fill(4)(false.B)))
  private val tags=Reg(Vec(4,UInt(tagWidth.W)));private val hits=Reg(Vec(4,Bool()))
  private val readPart=Reg(Vec(4,UInt(math.max(1,partBits).W)))
  private val readReady=Wire(Vec(4,Vec(4,Bool())))
  private val requests=Wire(Vec(4,Bool()));private val contention=Wire(Vec(4,UInt(3.W)))
  for(b<-0 until 4){
    val arb=arbiters(b);val reply=replies(b);val writing=filling && fill.rowParity===(b/2==1).B
    val capacity=reply.io.count.pad(3)+flight(b).asUInt-io.out(b).fire.asUInt
    arb.io.out.ready:= !writing && capacity<2.U
    for(p<-0 until 4){
      arb.io.in(p).valid:=io.in(p).valid && Cat(io.in(p).bits.rowParity,io.in(p).bits.address(2))===b.U
      arb.io.in(p).bits:=io.in(p).bits;readReady(p)(b):=arb.io.in(p).ready && Cat(io.in(p).bits.rowParity,io.in(p).bits.address(2))===b.U
    }
    val waiting=PopCount(arb.io.in.map(_.valid));contention(b):=Mux(waiting===0.U,0.U,waiting-1.U)
    val q=arb.io.out.bits;val row=Cat(index(q.address),q.address(5,3));val writeRow=Cat(index(fill.address),beat)
    val part=if(parts==1)0.U else row(log2Ceil(rows)-1,rowBits)
    val writePart=if(parts==1)0.U else writeRow(log2Ceil(rows)-1,rowBits)
    val memories=Seq.fill(parts)(Module(new L2SramArray(partDepth,32,useBlackBoxes)))
    for((m,p)<-memories.zipWithIndex){
      m.io.readEnable:=arb.io.out.fire && part===p.U;m.io.readAddress:=row(rowBits-1,0)
      m.io.writeEnable:=writing && writePart===p.U;m.io.writeAddress:=writeRow(rowBits-1,0)
      val words=fill.data.asTypeOf(Vec(16,UInt(32.W)))
      m.io.writeData:=words(Cat(beat,(b%2).U(1.W)))
      assert(!(m.io.readEnable && m.io.writeEnable),"one texture word bank has one operation per cycle")
    }
    flight(b):=arb.io.out.fire;requests(b):=arb.io.out.fire
    when(arb.io.out.fire){
      assert(q.address(1,0)===0.U,"texture hit lookup requires an admitted aligned physical word")
      val key=keys((b/2).U)(index(q.address))
      val replacing=io.refill.fire && io.refill.bits.rowParity===(b/2==1).B && index(io.refill.bits.address)===index(q.address)
      hits(b):=valid((b/2).U)(index(q.address)) && !io.invalidate && !replacing &&
        key.identity.asUInt===q.identity.asUInt && key.textureKey===q.textureKey && key.physicalTag===q.address(31,6+setWidth)
      tags(b):=q.tag;readPart(b):=part
    }
    reply.io.enq.valid:=flight(b);reply.io.enq.bits.tag:=tags(b);reply.io.enq.bits.hit:=hits(b)
    val data=if(parts==1)memories.head.io.readData else VecInit(memories.map(_.io.readData))(readPart(b))
    reply.io.enq.bits.data:=Mux(hits(b),data,0.U)
    when(flight(b)){assert(reply.io.enq.ready,"texture SRAM result must have a reserved reply entry")}
    io.out(b)<>reply.io.deq
  }
  for(p<-0 until 4)io.in(p).ready:=readReady(p).asUInt.orR
  io.readIssues:=PopCount(requests);io.conflicts:=contention.reduce(_+_)
  io.writeIssues:=Mux(filling,2.U,0.U)
  io.busy:=filling || completed || flight.asUInt.orR || replies.map(_.io.deq.valid).reduce(_ || _)
}
