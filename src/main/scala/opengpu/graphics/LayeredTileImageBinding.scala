package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.util.IterativeSignedDivider

class LayeredTileImageBindingRequest extends Bundle {
  val template=new TypedReplayTileDescriptor
  val screenWidth=UInt(16.W);val screenHeight=UInt(16.W);val layerCount=UInt(16.W);val tile=UInt(32.W)
  val viewStride=Vec(6,UInt(32.W));val resolveStride=Vec(6,UInt(32.W))
}
class LayeredTileImageBindingResult extends Bundle {
  val status=UInt(3.W);val bindingValid=Bool();val layer=UInt(16.W);val descriptor=new TypedReplayTileDescriptor
}

/** Decode a layered bin tile and select bounded single-layer image views.
  * A shared iterative divider avoids a combinational wide divide per engine;
  * accepted work and a held result drain before the descriptor is released.
  */
class LayeredTileImageBinding extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new LayeredTileImageBindingRequest))
    val out=Decoupled(new LayeredTileImageBindingResult);val cancel=Input(Bool());val busy=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,validate,offerLayer,waitLayer,offerRow,waitRow,views,finish,complete=Value}
  private val state=RegInit(State.idle);private val request=Reg(new LayeredTileImageBindingRequest)
  private val result=Reg(new LayeredTileImageBindingResult);private val status=RegInit(0.U(3.W));private val cancelled=RegInit(false.B)
  private val columns=Reg(UInt(16.W));private val grid=Reg(UInt(16.W));private val local=Reg(UInt(16.W))
  private val layer=Reg(UInt(16.W));private val index=Reg(UInt(4.W))
  private val divider=Module(new IterativeSignedDivider(17,17,17))
  io.in.ready:=state===State.idle && !io.cancel;io.out.valid:=state===State.complete;io.out.bits:=result;io.busy:=state=/=State.idle
  divider.io.in.valid:=state===State.offerLayer || state===State.offerRow
  divider.io.in.bits.numerator:=Mux(state===State.offerLayer,request.tile(15,0),local).zext
  divider.io.in.bits.denominator:=Mux(state===State.offerLayer,grid,columns).zext
  divider.io.out.ready:=state===State.waitLayer || state===State.waitRow
  when(io.cancel && state=/=State.idle && state=/=State.complete){cancelled:=true.B}
  switch(state){
    is(State.idle){when(io.in.fire){request:=io.in.bits;result:=0.U.asTypeOf(result);status:=0.U;cancelled:=false.B;state:=State.validate}}
    is(State.validate){
      val log=request.template.image.pixels.colors.tileLog2
      val tileSize=1.U(17.W)<<log
      val cols=(request.screenWidth.pad(17)+tileSize-1.U)>>log
      val rows=(request.screenHeight.pad(17)+tileSize-1.U)>>log
      val tiles=cols*rows;val total=tiles*request.layerCount
      columns:=cols;grid:=tiles
      val legal=(log===3.U || log===4.U) && request.screenWidth=/=0.U && request.screenHeight=/=0.U &&
        request.layerCount=/=0.U && request.template.image.pixels.colors.sampleMode<=2.U &&
        tiles<=65535.U && total<=65535.U && request.tile<total
      when(cancelled || io.cancel){state:=State.finish}.elsewhen(!legal){status:=1.U;state:=State.finish}
        .otherwise{result.descriptor:=request.template;result.descriptor.tile:=request.tile;state:=State.offerLayer}
    }
    is(State.offerLayer){when(divider.io.in.fire){state:=State.waitLayer}}
    is(State.waitLayer){when(divider.io.out.fire){
      val q=divider.io.out.bits.quotient.asUInt
      when(cancelled || io.cancel){state:=State.finish}.elsewhen(divider.io.out.bits.divideByZero || divider.io.out.bits.magnitudeOverflow){status:=1.U;state:=State.finish}
        .otherwise{layer:=q(15,0);result.layer:=q(15,0);local:=request.tile(15,0)-q*grid;state:=State.offerRow}
    }}
    is(State.offerRow){when(divider.io.in.fire){state:=State.waitRow}}
    is(State.waitRow){when(divider.io.out.fire){
      val q=divider.io.out.bits.quotient.asUInt;val log=request.template.image.pixels.colors.tileLog2
      val x=(local-q*columns)<<log;val y=q<<log;val size=1.U(17.W)<<log
      when(cancelled || io.cancel){state:=State.finish}.elsewhen(divider.io.out.bits.divideByZero || divider.io.out.bits.magnitudeOverflow){status:=1.U;state:=State.finish}
        .otherwise{val c=result.descriptor.image.pixels.colors
          c.originX:=x.zext;c.originY:=y.zext;c.tileWidth:=(request.screenWidth-x).min(size);c.tileHeight:=(request.screenHeight-y).min(size)
          index:=0.U;state:=State.views}
    }}
    is(State.views){
      val resolved=index>=6.U;val i=Mux(resolved,index-6.U,index)(2,0)
      val en=TypedTileImageRules.enabled(request.template.image.pixels);val bytes=TypedTileImageRules.bytes(request.template.image.pixels)
      val loads=TypedTileImageRules.loads(request.template.image.pixels)
      val source=request.template.image.views(i);val destination=request.template.image.resolves(i).destination
      val view=Mux(resolved,destination,source);val stride=Mux(resolved,request.resolveStride(i),request.viewStride(i))
      val needed=Mux(resolved,request.template.image.resolves(i).enable,en(i) && (source.store || loads(i)===AttachmentLoadOp.load.U))
      val samples=Mux(resolved,1.U(5.W),1.U(5.W)<<request.template.image.pixels.colors.sampleMode)
      val rowBytes=view.imageWidth*samples*bytes(i)
      val extent=((view.imageHeight-1.U)*view.rowPitch)+&rowBytes
      val offset=layer*stride;val base=view.base.pad(65)+offset.pad(65);val limit=view.limit.pad(65)+offset.pad(65)
      val allEnd=(view.base.pad(66)+&((request.layerCount-1.U)*stride).pad(66))+&extent.pad(66)
      val lastLimit=view.limit.pad(65)+(request.layerCount-1.U)*stride
      val legal=view.imageWidth>=request.screenWidth && view.imageHeight>=request.screenHeight && view.limit>=view.base &&
        view.rowPitch>=rowBytes && view.base>=view.allocationBase &&
        view.allocationLimit<=(BigInt(1)<<32).U && lastLimit<=view.allocationLimit && lastLimit<=(BigInt(1)<<32).U &&
        base<(BigInt(1)<<32).U && limit<=(BigInt(1)<<32).U && base>=view.allocationBase &&
        limit<=view.allocationLimit && (base+&extent)<=limit && allEnd<=view.allocationLimit &&
        (request.layerCount===1.U || stride>=extent)
      when(cancelled || io.cancel){state:=State.finish}.elsewhen(needed && !legal){status:=2.U;state:=State.finish}
        .otherwise{
          when(needed){when(resolved){result.descriptor.image.resolves(i).destination.base:=base;result.descriptor.image.resolves(i).destination.limit:=limit}
            .otherwise{result.descriptor.image.views(i).base:=base;result.descriptor.image.views(i).limit:=limit}}
          when(index===11.U){state:=State.finish}.otherwise{index:=index+1.U}
        }
    }
    is(State.finish){result.status:=Mux(status=/=0.U,status,Mux(cancelled || io.cancel,4.U,0.U))
      result.bindingValid:=status===0.U && !cancelled && !io.cancel;state:=State.complete}
    is(State.complete){when(io.out.fire){state:=State.idle}}
  }
}
