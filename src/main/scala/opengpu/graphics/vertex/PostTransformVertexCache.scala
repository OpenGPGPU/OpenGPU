package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.core.memory.L2SramArray

/** The draw owner clears this cache before admission. Only shaders explicitly
  * promising batch-independent, side-effect-free outputs may use it. Vertex
  * ordinals and restart tokens are stream metadata and are never cached.
  */
class VertexReuseKey extends Bundle {
  val vertexId=UInt(32.W);val instanceId=UInt(32.W);val baseInstance=UInt(32.W)
}
class VertexShaderOutputs(p: VertexShaderStageConfig) extends Bundle {
  val pointSize=UInt(32.W);val pointSizeWritten=Bool()
  val position=Vec(4,UInt(32.W));val varyings=Vec(p.varyings,Vec(4,UInt(32.W)))
}
class VertexReuseResult(p: VertexShaderStageConfig) extends Bundle {
  val hit=Bool();val outputs=new VertexShaderOutputs(p)
}

/** Serial word access keeps full-width outputs in narrow single-port SRAMs,
  * rather than allocating one physical macro for every output component.
  * Updates commit their tag only after the final data word. Responses retain
  * their complete value until consumed; clearing is an idle-only operation.
  */
object PostTransformVertexCache {
  val defaultEntries=16
  def storageBytes(entries: Int,varyings: Int=16): Int={
    require(entries==0 || (entries>=2 && entries<=32 && isPow2(entries)))
    require(varyings>=1 && varyings<=16)
    val words=((5+4*varyings)*32+1+31)/32
    val total=entries*(1<<log2Ceil(words))
    ((total+255)/256)*1024
  }
}
class PostTransformVertexCache(p: VertexShaderStageConfig,entries: Int=PostTransformVertexCache.defaultEntries,useSramBlackBoxes: Boolean=false) extends Module {
  require(entries>=2 && entries<=32 && isPow2(entries))
  private val payloadType=new VertexShaderOutputs(p)
  private val words=(payloadType.getWidth+31)/32
  private val entryWords=1<<log2Ceil(words)
  private val totalWords=entries*entryWords
  private val bankDepth=math.min(256,totalWords)
  val physicalBytes=PostTransformVertexCache.storageBytes(entries,p.varyings)
  private val bankCount=(totalWords+bankDepth-1)/bankDepth
  private val slotBits=log2Ceil(entries);private val wordBits=log2Ceil(entryWords)
  private val bankBits=math.max(1,log2Ceil(bankCount))
  val io=IO(new Bundle {
    val lookup=Flipped(Decoupled(new VertexReuseKey))
    val result=Decoupled(new VertexReuseResult(p))
    val update=Flipped(Decoupled(new ShadedVertexRecord(p)))
    val clear=Input(Bool());val busy=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,write,read,reply,complete=Value}
  private val phase=RegInit(State.idle)
  private val valid=RegInit(VecInit(Seq.fill(entries)(false.B)))
  private val tags=Reg(Vec(entries,new VertexReuseKey))
  private val next=RegInit(0.U(slotBits.W));private val slot=Reg(UInt(slotBits.W))
  private val word=Reg(UInt(wordBits.W));private val readBank=Reg(UInt(bankBits.W))
  private val writeKey=Reg(new VertexReuseKey)
  private val update=Reg(new VertexShaderOutputs(p))
  private val data=Reg(Vec(words,UInt(32.W)));private val hit=Reg(Bool())
  private val banks=Seq.fill(bankCount)(Module(new L2SramArray(bankDepth,32,useSramBlackBoxes)))
  private val address=Cat(slot,word)
  private val bank=if(totalWords>256)address>>8 else 0.U
  for((memory,i)<-banks.zipWithIndex){
    memory.io.readEnable:=phase===State.read && bank===i.U
    memory.io.writeEnable:=phase===State.write && bank===i.U
    memory.io.readAddress:=address(log2Ceil(bankDepth)-1,0)
    memory.io.writeAddress:=address(log2Ceil(bankDepth)-1,0)
    memory.io.writeData:=(update.asUInt.pad(words*32)>>(word<<5))(31,0)
  }
  private val incoming=Wire(new VertexReuseKey)
  incoming.vertexId:=io.update.bits.vertexId;incoming.instanceId:=io.update.bits.instanceId;incoming.baseInstance:=io.update.bits.baseInstance
  private def matches(key: VertexReuseKey): UInt=VecInit((0 until entries).map(i=>valid(i) && tags(i).asUInt===key.asUInt)).asUInt
  private val lookupMatches=matches(io.lookup.bits);private val updateMatches=matches(incoming)
  io.update.ready:=phase===State.idle && !io.clear
  io.lookup.ready:=phase===State.idle && !io.update.valid && !io.clear
  io.result.valid:=phase===State.complete
  io.result.bits.hit:=hit
  io.result.bits.outputs:=Mux(hit,data.asUInt.asTypeOf(payloadType),0.U.asTypeOf(payloadType))
  io.busy:=phase=/=State.idle
  when(io.clear){
    assert(phase===State.idle,"post-transform cache clear cannot abandon a held operation")
    valid.foreach(_:=false.B);next:=0.U
  }
  switch(phase){
    is(State.idle){
      when(io.update.fire){
        assert(!io.update.bits.restart,"restart tokens cannot populate the post-transform cache")
        val chosen=Mux(updateMatches.orR,PriorityEncoder(updateMatches),Mux(valid.asUInt.andR,next,PriorityEncoder(~valid.asUInt)))
        slot:=chosen;valid(chosen):=false.B;writeKey:=incoming
        update.pointSize:=io.update.bits.pointSize;update.pointSizeWritten:=io.update.bits.pointSizeWritten
        update.position:=io.update.bits.position;update.varyings:=io.update.bits.varyings
        word:=0.U;phase:=State.write
      }.elsewhen(io.lookup.fire){
        hit:=lookupMatches.orR;slot:=PriorityEncoder(lookupMatches);word:=0.U
        phase:=Mux(lookupMatches.orR,State.read,State.complete)
      }
    }
    is(State.write){
      when(word===(words-1).U){valid(slot):=true.B;tags(slot):=writeKey;next:=slot+1.U;phase:=State.idle}
        .otherwise{word:=word+1.U}
    }
    is(State.read){readBank:=bank;phase:=State.reply}
    is(State.reply){
      data(word):=(if(bankCount==1)banks.head.io.readData else VecInit(banks.map(_.io.readData))(readBank))
      when(word===(words-1).U){phase:=State.complete}.otherwise{word:=word+1.U;phase:=State.read}
    }
    is(State.complete){when(io.result.fire){phase:=State.idle}}
  }
}
