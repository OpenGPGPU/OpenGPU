package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.{CachePolicy,GpuConfig}
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}

/** Packed GLES 3.1 parameter records; these are independent of input formats. */
object IndirectDrawMode {val direct=0;val arrays=1;val elements=2}
class IndirectDrawControl extends Bundle {
  val mode=UInt(2.W)
  val view=new VertexInputView
}
object IndirectDrawControl {
  def legal(c: IndirectDrawControl,indexFormat: UInt): Bool=
    (c.mode===0.U && c.view.asUInt===0.U) ||
      (c.mode===1.U && indexFormat===0.U) || (c.mode===2.U && indexFormat=/=0.U)
  def fields(modeWord: Int,viewWord: Int,prefix: String,c: IndirectDrawControl): Seq[NativeTypedGraphicsLayout.Field]={
    import NativeTypedGraphicsLayout.Field
    Seq(Field(prefix+"mode",modeWord,c.mode),Field(prefix+"view.base",viewWord,c.view.base),
      Field(prefix+"view.limit",viewWord+1,c.view.limit),Field(prefix+"view.allocationBase",viewWord+3,c.view.allocationBase),
      Field(prefix+"view.allocationLimit",viewWord+4,c.view.allocationLimit))
  }
}
class IndirectDrawResult extends Bundle {
  val count=UInt(32.W);val instances=UInt(32.W);val first=UInt(32.W);val baseVertex=SInt(32.W)
  val status=UInt(VertexInputStatus.width.W);val faultAddress=UInt(32.W);val lines=UInt(2.W)
}

/** One or two translated line reads capture a complete parameter record before
  * its counts can reach either shaders or assembly. Logical and padded physical
  * bounds are separate. An offered read survives cancellation until its real
  * reply; no younger line follows cancellation. Results remain immutable while
  * held, including when cancellation arrives after the result was offered.
  */
class IndirectDrawReader(config: GpuConfig=GpuConfig()) extends Module {
  require(config.xLen==32)
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new IndirectDrawControl));val out=Decoupled(new IndirectDrawResult)
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,1))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,1)))
    val cancel=Input(Bool());val busy=Output(Bool())
  })
  private object Phase extends ChiselEnum {val idle,check,offer,reply,validate,complete=Value}
  private val phase=RegInit(Phase.idle)
  private val control=Reg(new IndirectDrawControl);private val result=Reg(new IndirectDrawResult)
  private val words=Reg(Vec(5,UInt(32.W)));private val second=RegInit(false.B)
  private val line=Reg(UInt(32.W));private val canceled=RegInit(false.B)
  private val stop=io.cancel || canceled
  private val bytes=Mux(control.mode===IndirectDrawMode.arrays.U,16.U(6.W),20.U(6.W))
  private val v=control.view
  private val end=v.base+&bytes
  private val span=v.base(5,0)+&bytes
  private val firstBytes=Mux(span>64.U,64.U(7.W)-v.base(5,0),bytes)
  private val readBytes=Mux(second,span-64.U,firstBytes)
  private val offset=Mux(second,0.U(6.W),v.base(5,0))
  io.in.ready:=phase===Phase.idle
  io.out.valid:=phase===Phase.complete;io.out.bits:=result
  io.busy:=phase=/=Phase.idle
  io.memoryRequest.valid:=phase===Phase.offer
  io.memoryRequest.bits:=0.U.asTypeOf(io.memoryRequest.bits)
  io.memoryRequest.bits.address:=line;io.memoryRequest.bits.sizeLog2:=6.U
  io.memoryRequest.bits.cachePolicy:=CachePolicy.cached
  io.memoryRequest.bits.byteMask:=((~0.U(64.W))>>(64.U(7.W)-readBytes))<<offset
  io.memoryResponse.ready:=phase===Phase.reply
  when(io.cancel && phase=/=Phase.idle){canceled:=true.B}
  private def fail(code: Int,address: UInt): Unit={result.status:=code.U;result.faultAddress:=address;phase:=Phase.complete}
  switch(phase){
    is(Phase.idle){when(io.in.fire){
      control:=io.in.bits;result:=0.U.asTypeOf(result);words.foreach(_:=0.U)
      second:=false.B;canceled:=io.cancel;phase:=Phase.check
    }}
    is(Phase.check){
      val shape=(control.mode===1.U || control.mode===2.U) && v.base(1,0)===0.U &&
        v.allocationBase(5,0)===0.U && v.allocationLimit(5,0)===0.U &&
        v.allocationLimit<=(BigInt(1)<<32).U && v.allocationLimit>v.allocationBase &&
        v.base>=v.allocationBase && v.limit>=v.base && v.limit<=v.allocationLimit
      when(stop){fail(VertexInputStatus.cancelled,0.U)}
        .elsewhen(!shape){fail(VertexInputStatus.invalidDescriptor,v.base)}
        .elsewhen(end>v.limit || end>(BigInt(1)<<32).U){fail(VertexInputStatus.bounds,v.base)}
        .otherwise{line:=Cat(v.base(31,6),0.U(6.W));phase:=Phase.offer}
    }
    is(Phase.offer){when(io.memoryRequest.fire){result.lines:=result.lines+1.U;phase:=Phase.reply}}
    is(Phase.reply){when(io.memoryResponse.fire){
      assert(io.memoryResponse.bits.transactionId===0.U,"indirect draw reply lost its sole owner")
      when(stop){fail(VertexInputStatus.cancelled,0.U)}
        .elsewhen(io.memoryResponse.bits.fault){fail(VertexInputStatus.memoryFault,line)}
        .otherwise{
          val data=io.memoryResponse.bits.readData.asTypeOf(Vec(16,UInt(32.W)))
          for(k<-0 until 5){
            val index=v.base(5,2)+&k.U(4.W)
            when((control.mode===2.U || (k<4).B) && Mux(second,index>=16.U,index<16.U)){
              words(k):=data(index(3,0))
            }
          }
          when(!second && span>64.U){second:=true.B;line:=line+64.U;phase:=Phase.offer}
            .otherwise{phase:=Phase.validate}
        }
    }}
    is(Phase.validate){
      val reserved=Mux(control.mode===1.U,words(3),words(4))
      when(stop){fail(VertexInputStatus.cancelled,0.U)}
        .elsewhen(reserved=/=0.U){fail(VertexInputStatus.invalidDescriptor,v.base+Mux(control.mode===1.U,12.U,16.U))}
        .otherwise{
          result.count:=words(0);result.instances:=words(1);result.first:=words(2)
          result.baseVertex:=Mux(control.mode===2.U,words(3).asSInt,0.S);phase:=Phase.complete
        }
    }
    is(Phase.complete){when(io.out.fire){phase:=Phase.idle}}
  }
}
