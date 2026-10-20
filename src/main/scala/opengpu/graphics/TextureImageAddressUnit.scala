package opengpu.graphics

import chisel3._
import chisel3.util._

case class TextureImageAddressConfig(max2D: Int=16383,max3D: Int=256,maxLayers: Int=256) {
  require(max2D>=1 && max2D<=16383 && max3D>=1 && max3D<=16383)
  require(maxLayers>=1 && maxLayers<=65535)
}
object TextureImageTarget {val image2D=0;val array2D=1;val cube=2;val image3D=3}
object TextureImageAddressStatus {val ok=0;val invalidView=1;val coordinate=2;val bounds=3}

/** One resolved mip level. Pitches and exclusive allocation limits are bytes.
  * Array layer/cube-face pitch and 3D slice pitch are distinct. The unused
  * pitch must be zero. Packed encodings use bytes per texel; a 4x4 block uses
  * 8 or16 bytes. No format decode or mip-completeness claim is implied.
  */
class TextureImageLevelView extends Bundle {
  val target=UInt(3.W);val level=UInt(4.W)
  val allocationBase=UInt(32.W);val allocationLimit=UInt(33.W);val base=UInt(32.W)
  val texWidth=UInt(14.W);val texHeight=UInt(14.W);val texDepth=UInt(14.W);val layers=UInt(16.W)
  val rowPitch=UInt(33.W);val slicePitch=UInt(33.W);val layerPitch=UInt(33.W)
  val block4x4=Bool();val blockBytes=UInt(5.W)
}
class TextureImageAddressRequest extends Bundle {
  val tag=UInt(32.W);val view=new TextureImageLevelView
  /** Already selected/wrapped texel coordinates, never normalized UVs. */
  val x=UInt(32.W);val y=UInt(32.W);val z=UInt(32.W);val layer=UInt(32.W);val face=UInt(3.W)
}
class TextureImageAddressResult extends Bundle {
  val tag=UInt(32.W);val level=UInt(4.W);val status=UInt(3.W)
  val address=UInt(32.W);val bytes=UInt(5.W)
  /** Texel location within a compressed block; zero for packed encodings. */
  val blockX=UInt(2.W);val blockY=UInt(2.W)
}

/** Three elastic register stages, one address per cycle with no stalls.
  * Validate full-width coordinates before using bounded arithmetic operands.
  * Check the entire level span and nonoverlapping rows/slices/layers, not just
  * the requested texel. Every product and sum retains its full useful width;
  * only a successful <=4GiB span may publish a narrowed byte address.
  * This unit has no memory/cache state: owners drain accepted replies on cancel.
  */
class TextureImageAddressUnit(p: TextureImageAddressConfig=TextureImageAddressConfig()) extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new TextureImageAddressRequest))
    val out=Decoupled(new TextureImageAddressResult);val busy=Output(Bool())
  })
  private class Products extends Bundle {
    val tag=UInt(32.W);val level=UInt(4.W);val target=UInt(3.W)
    val allocationBase=UInt(32.W);val allocationLimit=UInt(33.W);val base=UInt(32.W)
    val rowPitch=UInt(33.W);val slicePitch=UInt(33.W);val layerPitch=UInt(33.W)
    val rowBytes=UInt(19.W);val lastRow=UInt(47.W);val lastSlice=UInt(47.W);val lastLayer=UInt(49.W)
    val column=UInt(19.W);val row=UInt(47.W);val slice=UInt(47.W);val layer=UInt(49.W)
    val bytes=UInt(5.W);val blockX=UInt(2.W);val blockY=UInt(2.W)
    val invalid=Bool();val badCoordinate=Bool()
  }
  private val captured=Queue(io.in,1,pipe=true,flow=false)
  private val multiply=Wire(Decoupled(new Products))
  multiply.valid:=captured.valid;captured.ready:=multiply.ready
  private val q=captured.bits;private val v=q.view;private val a=multiply.bits
  private val array=v.target===TextureImageTarget.array2D.U
  private val cube=v.target===TextureImageTarget.cube.U
  private val volume=v.target===TextureImageTarget.image3D.U
  private val columns=Mux(v.block4x4,(v.texWidth+&3.U)>>2,v.texWidth).pad(14)
  private val rows=Mux(v.block4x4,(v.texHeight+&3.U)>>2,v.texHeight).pad(14)
  private val planes=Mux(cube,6.U(16.W),Mux(array,v.layers,1.U(16.W)))
  private val slices=Mux(volume,v.texDepth,1.U(14.W))
  private val packedBytes=Seq(1,2,3,4,6,8,12,16).map(n=>v.blockBytes===n.U).reduce(_ || _)
  private val encoding=Mux(v.block4x4,v.blockBytes===8.U || v.blockBytes===16.U,packedBytes)
  private val dimensions=v.texWidth=/=0.U && v.texHeight=/=0.U && v.texDepth=/=0.U && v.layers=/=0.U &&
    Mux(volume,v.texWidth<=p.max3D.U && v.texHeight<=p.max3D.U && v.texDepth<=p.max3D.U,
      v.texWidth<=p.max2D.U && v.texHeight<=p.max2D.U && v.texDepth===1.U) &&
    Mux(array,v.layers<=p.maxLayers.U,v.layers===1.U) && (!cube || v.texWidth===v.texHeight)
  private val unusedPitches=(volume || v.slicePitch===0.U) && (array || cube || v.layerPitch===0.U)
  a.invalid:=v.target>TextureImageTarget.image3D.U || !dimensions || !encoding || !unusedPitches
  a.badCoordinate:=q.x>=v.texWidth || q.y>=v.texHeight ||
    Mux(volume,q.z>=v.texDepth,q.z=/=0.U) || Mux(array,q.layer>=v.layers,q.layer=/=0.U) ||
    Mux(cube,q.face>=6.U,q.face=/=0.U)
  a.tag:=q.tag;a.level:=v.level;a.target:=v.target;a.allocationBase:=v.allocationBase
  a.allocationLimit:=v.allocationLimit;a.base:=v.base
  a.rowPitch:=v.rowPitch;a.slicePitch:=v.slicePitch;a.layerPitch:=v.layerPitch
  a.rowBytes:=columns*v.blockBytes
  a.lastRow:=(rows-1.U)*v.rowPitch;a.lastSlice:=(slices-1.U)*v.slicePitch;a.lastLayer:=(planes-1.U)*v.layerPitch
  a.column:=Mux(v.block4x4,q.x(13,0)>>2,q.x(13,0))*v.blockBytes
  a.row:=Mux(v.block4x4,q.y(13,0)>>2,q.y(13,0))*v.rowPitch
  a.slice:=Mux(volume,q.z(13,0),0.U(14.W))*v.slicePitch
  a.layer:=Mux(cube,q.face.pad(16),Mux(array,q.layer(15,0),0.U(16.W)))*v.layerPitch
  a.bytes:=v.blockBytes;a.blockX:=Mux(v.block4x4,q.x(1,0),0.U);a.blockY:=Mux(v.block4x4,q.y(1,0),0.U)
  private val products=Queue(multiply,1,pipe=true,flow=false)
  private val finish=Wire(Decoupled(new TextureImageAddressResult))
  finish.valid:=products.valid;products.ready:=finish.ready
  private val b=products.bits
  private val planeSpan=b.lastRow.pad(64)+b.rowBytes.pad(64)
  private val span=planeSpan+b.lastSlice.pad(64)+b.lastLayer.pad(64)
  private val levelEnd=b.base.pad(64)+span
  private val address=b.base.pad(64)+b.column.pad(64)+b.row.pad(64)+b.slice.pad(64)+b.layer.pad(64)
  private val selectedEnd=address+b.bytes.pad(64)
  private val planePitchBad=Mux(b.target===TextureImageTarget.image3D.U,b.slicePitch<planeSpan,
    (b.target===TextureImageTarget.array2D.U || b.target===TextureImageTarget.cube.U) && b.layerPitch<planeSpan)
  private val badBounds=b.rowPitch<b.rowBytes || planePitchBad || b.allocationBase>=b.allocationLimit ||
    b.allocationLimit>(BigInt(1)<<32).U || b.base<b.allocationBase || levelEnd>b.allocationLimit ||
    selectedEnd>b.allocationLimit || selectedEnd>(BigInt(1)<<32).U
  private val status=Mux(b.invalid,TextureImageAddressStatus.invalidView.U,
    Mux(b.badCoordinate,TextureImageAddressStatus.coordinate.U,
      Mux(badBounds,TextureImageAddressStatus.bounds.U,TextureImageAddressStatus.ok.U)))
  finish.bits:=0.U.asTypeOf(finish.bits);finish.bits.tag:=b.tag;finish.bits.level:=b.level;finish.bits.status:=status
  when(status===TextureImageAddressStatus.ok.U){
    finish.bits.address:=address(31,0);finish.bits.bytes:=b.bytes;finish.bits.blockX:=b.blockX;finish.bits.blockY:=b.blockY
  }
  private val result=Queue(finish,1,pipe=true,flow=false)
  io.out<>result
  io.busy:=captured.valid || products.valid || result.valid
}
