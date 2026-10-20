package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig

/** Use an already admitted immutable GXL1 snapshot. There are no descriptor
  * reads or cache fills here. The caller pins publication and VM lifetime.
  * The ordinary checked address pipeline still validates every span and tap.
  */
class TextureImageCapturedAddressUnit(config: GpuConfig=GpuConfig(),p: TextureImageAddressConfig=TextureImageAddressConfig()) extends Module {
  val io=IO(new TextureImageResourceAddressIO(config,true))
  private object State extends ChiselEnum {val idle,check,offer,waitAddress,finish,complete=Value}
  private val phase=RegInit(State.idle);private val request=Reg(new TextureImageResourceAddressRequest)
  private val view=Reg(new TextureImageLevelView);private val span=Reg(new TextureImageAddressResult)
  private val result=Reg(new TextureImageResourceAddressResult);private val status=RegInit(0.U(3.W))
  private val active=phase=/=State.idle && phase=/=State.complete;private val stop=status=/=0.U || io.cancel
  private val address=Module(new TextureImageAddressUnit(p,pipelineBounds=true,pipelineProducts=true))
  io.in.ready:=phase===State.idle && !io.cancel;io.out.valid:=phase===State.complete;io.out.bits:=result;io.busy:=phase=/=State.idle
  io.memoryRequest.valid:=false.B;io.memoryRequest.bits:=0.U.asTypeOf(io.memoryRequest.bits);io.memoryResponse.ready:=false.B
  address.io.in.valid:=phase===State.offer;address.io.in.bits.view:=view;address.io.in.bits.tag:=request.tag
  address.io.in.bits.x:=request.x;address.io.in.bits.y:=request.y;address.io.in.bits.z:=request.z
  address.io.in.bits.layer:=request.layer;address.io.in.bits.face:=request.face;address.io.out.ready:=phase===State.waitAddress
  when(active && io.cancel && status===0.U){status:=TextureImageResourceStatus.cancelled.U}
  switch(phase){
    is(State.idle){when(io.in.fire){
      assert(!address.io.busy,"captured view abandoned address ownership")
      request:=io.in.bits;view:=io.capturedView.get;span:=0.U.asTypeOf(span);status:=0.U;phase:=State.check
    }}
    is(State.check){
      val a=request.lookup.table;val begin=a.base.pad(64)+(request.lookup.handle.pad(64)<<7)
      val legal=a.version===1.U && a.base(5,0)===0.U && a.limit(5,0)===0.U && a.limit>a.base &&
        a.limit<=(BigInt(1)<<32).U && (a.limit-a.base)(6,0)===0.U && begin+128.U<=a.limit
      when(stop){phase:=State.finish}.elsewhen(!legal){status:=TextureImageResourceStatus.view.U;phase:=State.finish}
        .elsewhen(view.level=/=request.lookup.level){status:=TextureImageResourceStatus.encoding.U;phase:=State.finish}
        .otherwise{phase:=State.offer}
    }
    is(State.offer){when(address.io.in.fire){phase:=State.waitAddress}}
    is(State.waitAddress){when(address.io.out.fire){
      span:=address.io.out.bits
      when(!stop){status:=MuxLookup(address.io.out.bits.status,TextureImageResourceStatus.view.U)(Seq(
        TextureImageAddressStatus.ok.U->TextureImageResourceStatus.ok.U,
        TextureImageAddressStatus.invalidView.U->TextureImageResourceStatus.view.U,
        TextureImageAddressStatus.coordinate.U->TextureImageResourceStatus.coordinate.U,
        TextureImageAddressStatus.bounds.U->TextureImageResourceStatus.bounds.U))}
      phase:=State.finish
    }}
    is(State.finish){
      assert(!address.io.busy,"captured view completion escaped address drain")
      result:=0.U.asTypeOf(result);result.identity:=request.lookup.table.identity;result.handle:=request.lookup.handle
      result.tag:=request.tag;result.level:=request.lookup.level
      result.status:=Mux(io.cancel && status===0.U,TextureImageResourceStatus.cancelled.U,status)
      when(!stop){result.viewValid:=true.B;result.view:=view;result.address:=span.address;result.bytes:=span.bytes
        result.blockX:=span.blockX;result.blockY:=span.blockY}
      phase:=State.complete
    }
    is(State.complete){when(io.out.fire){phase:=State.idle}}
  }
  when(io.out.valid){assert(!address.io.busy && !io.memoryRequest.valid,"held captured view must own drained children")}
}
