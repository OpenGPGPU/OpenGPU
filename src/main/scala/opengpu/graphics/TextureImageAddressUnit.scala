package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.util.{BlockPrefixAdder,CarrySaveMultiplier}

case class TextureImageAddressConfig(max2D: Int=16383,max3D: Int=256,maxLayers: Int=256,
    maxBufferTexels: Int=TextureImageTarget.defaultBufferTexels) {
  require(max2D>=1 && max2D<=16383 && max3D>=1 && max3D<=16383)
  require(maxLayers>=1 && maxLayers<=65535)
  require(maxBufferTexels>=65536)
}
object TextureImageTarget {
  val image2D=0;val array2D=1;val cube=2;val image3D=3;val cubeArray=4;val buffer=5
  val image2DMS=6;val array2DMS=7
  val defaultBufferTexels=1<<27
  /** Face planes for one cube; cube-array plane count is layers times this. */
  val cubeFaces=6
  /** Largest cube-array layer count that still fits plane indices in 16 bits. */
  val maxCubeArrayLayers=((1<<16)-1)/cubeFaces
}
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
  /** Multisample texel-fetch contract: 0 selects a single-sample view. */
  val samples=UInt(3.W);val samplePitch=UInt(32.W)
  /** Buffer-only fields. Dimensions are one and all pitches are zero. The
    * range may contain an incomplete final texel; its whole byte span must fit
    * the allocation. Neither this count nor the integer index uses max2D.
    */
  val bufferTexels=UInt(32.W);val bufferBytes=UInt(33.W)
}
class TextureImageAddressRequest extends Bundle {
  val tag=UInt(32.W);val view=new TextureImageLevelView
  /** Already selected/wrapped texel coordinates, never normalized UVs. */
  val x=UInt(32.W);val y=UInt(32.W);val z=UInt(32.W);val layer=UInt(32.W);val face=UInt(3.W)
  val sample=UInt(3.W)
}
class TextureImageAddressResult extends Bundle {
  val tag=UInt(32.W);val level=UInt(4.W);val status=UInt(3.W)
  val address=UInt(32.W);val bytes=UInt(5.W)
  /** Texel location within a compressed block; zero for packed encodings. */
  val blockX=UInt(2.W);val blockY=UInt(2.W)
}

/** Three elastic register stages by default; pipelineBounds and pipelineProducts
  * each add one stage. Composed resource owners enable both cuts.
  * Both profiles sustain one address per cycle with no stalls.
  * Validate full-width coordinates before using bounded arithmetic operands.
  * Check the entire level span and nonoverlapping rows/slices/layers, not just
  * the requested texel. Every product and sum retains its full useful width;
  * only a successful <=4GiB span may publish a narrowed byte address.
  * This unit has no memory/cache state: owners drain accepted replies on cancel.
  */
class TextureImageAddressUnit(p: TextureImageAddressConfig=TextureImageAddressConfig(),pipelineBounds: Boolean=false,pipelineProducts: Boolean=false) extends Module {
  def this(p: TextureImageAddressConfig)=this(p,false,false)
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new TextureImageAddressRequest))
    val out=Decoupled(new TextureImageAddressResult);val busy=Output(Bool())
  })
  private class Products extends Bundle {
    val tag=UInt(32.W);val level=UInt(4.W);val target=UInt(3.W)
    val allocationBase=UInt(32.W);val allocationLimit=UInt(33.W);val base=UInt(32.W)
    val rowPitch=UInt(33.W);val slicePitch=UInt(33.W);val layerPitch=UInt(33.W);val samplePitch=UInt(33.W)
    val rowBytes=UInt(19.W);val lastRow=UInt(47.W);val lastSlice=UInt(47.W);val lastLayer=UInt(49.W)
    val lastSample=UInt(49.W);val sampleOffset=UInt(49.W);val samples=UInt(3.W)
    val column=UInt(19.W);val row=UInt(47.W);val slice=UInt(47.W);val layer=UInt(49.W)
    val bufferTexels=UInt(32.W);val bufferBytes=UInt(33.W)
    val bufferSpan=UInt(37.W);val bufferColumn=UInt(37.W)
    val bytes=UInt(5.W);val blockX=UInt(2.W);val blockY=UInt(2.W)
    val invalid=Bool();val badCoordinate=Bool()
  }
  private class Sums extends Bundle {
    val tag=UInt(32.W);val level=UInt(4.W);val target=UInt(3.W);val invalid=Bool();val badCoordinate=Bool()
    val base=UInt(32.W);val allocationBase=UInt(32.W);val allocationLimit=UInt(33.W)
    val rowPitch=UInt(33.W);val rowBytes=UInt(19.W);val planePitch=UInt(33.W);val needsPlanePitch=Bool()
    val samplePitch=UInt(33.W);val samples=UInt(3.W)
    val planeSpan=UInt(64.W);val levelEnd=UInt(64.W);val address=UInt(64.W);val selectedEnd=UInt(64.W)
    val bytes=UInt(5.W);val blockX=UInt(2.W);val blockY=UInt(2.W)
  }
  private class Coefficients extends Bundle {
    val request=new TextureImageAddressRequest
    val columns=UInt(14.W);val rowsMinusOne=UInt(14.W);val slicesMinusOne=UInt(14.W);val planesMinusOne=UInt(16.W)
    val column=UInt(14.W);val row=UInt(14.W);val slice=UInt(14.W);val layer=UInt(16.W)
    val sample=UInt(3.W);val samplesMinusOne=UInt(3.W)
    val invalid=Bool();val badCoordinate=Bool()
  }
  private class CompressedProducts extends Bundle {
    val metadata=new Products
    val rows=Vec(8,Vec(2,UInt(49.W)))
  }
  private def productFields(d: Products): Seq[UInt]=Seq(d.lastRow,d.lastSlice,d.lastLayer,d.lastSample,d.row,d.slice,d.layer,d.sampleOffset)
  private val captured=Queue(io.in,1,pipe=true,flow=false)
  private val prepare=Wire(Decoupled(new Coefficients))
  prepare.valid:=captured.valid;captured.ready:=prepare.ready
  private val input=captured.bits;private val incoming=input.view;private val operands=prepare.bits
  private val array=incoming.target===TextureImageTarget.array2D.U
  private val cube=incoming.target===TextureImageTarget.cube.U
  private val cubeArray=incoming.target===TextureImageTarget.cubeArray.U
  private val volume=incoming.target===TextureImageTarget.image3D.U
  private val buffer=incoming.target===TextureImageTarget.buffer.U
  private val msaa=incoming.target===TextureImageTarget.image2DMS.U || incoming.target===TextureImageTarget.array2DMS.U
  private val msArray=incoming.target===TextureImageTarget.array2DMS.U
  private val layered=array || cube || cubeArray || msArray
  private val squareCube=cube || cubeArray
  private val columns=Mux(incoming.block4x4,(incoming.texWidth+&3.U)>>2,incoming.texWidth).pad(14)
  private val rows=Mux(incoming.block4x4,(incoming.texHeight+&3.U)>>2,incoming.texHeight).pad(14)
  private val cubeArrayPlanes=(incoming.layers.pad(19)*TextureImageTarget.cubeFaces.U)(18,0)
  private val planes=Mux(cubeArray,cubeArrayPlanes(15,0),
    Mux(cube,TextureImageTarget.cubeFaces.U(16.W),Mux(array || msArray,incoming.layers,1.U(16.W))))
  private val slices=Mux(volume,incoming.texDepth,1.U(14.W))
  private val packedBytes=Seq(1,2,3,4,6,8,12,16).map(n=>incoming.blockBytes===n.U).reduce(_ || _)
  private val encoding=Mux(incoming.block4x4,incoming.blockBytes===8.U || incoming.blockBytes===16.U,packedBytes)
  private val layeredCount=Mux(array || cubeArray || msArray,incoming.layers<=p.maxLayers.U &&
    (!cubeArray || cubeArrayPlanes<=((1<<16)-1).U),incoming.layers===1.U)
  private val dimensions=incoming.texWidth=/=0.U && incoming.texHeight=/=0.U && incoming.texDepth=/=0.U && incoming.layers=/=0.U &&
    Mux(volume,incoming.texWidth<=p.max3D.U && incoming.texHeight<=p.max3D.U && incoming.texDepth<=p.max3D.U,
      incoming.texWidth<=p.max2D.U && incoming.texHeight<=p.max2D.U && incoming.texDepth===1.U) &&
    layeredCount && (!squareCube || incoming.texWidth===incoming.texHeight)
  private val unusedPitches=(volume || incoming.slicePitch===0.U) && (layered || incoming.layerPitch===0.U)
  private val bufferShape=incoming.level===0.U && incoming.texWidth===1.U && incoming.texHeight===1.U &&
    incoming.texDepth===1.U && incoming.layers===1.U && incoming.rowPitch===0.U && incoming.slicePitch===0.U &&
    incoming.layerPitch===0.U && !incoming.block4x4 && incoming.bufferTexels=/=0.U &&
    incoming.bufferTexels<=p.maxBufferTexels.U && incoming.bufferBytes=/=0.U &&
    incoming.bufferBytes<=(BigInt(1)<<32).U && Seq(1,2,4,8,12,16).map(incoming.blockBytes===_.U).reduce(_ || _)
  private val msShape=Mux(msaa,
    (incoming.samples===2.U || incoming.samples===4.U) && incoming.samplePitch=/=0.U &&
      incoming.level===0.U && !incoming.block4x4,
    incoming.samples===0.U && incoming.samplePitch===0.U)
  operands.invalid:=incoming.target>TextureImageTarget.array2DMS.U || !msShape ||
    Mux(buffer,!bufferShape,!dimensions || !encoding || !unusedPitches || incoming.bufferTexels=/=0.U || incoming.bufferBytes=/=0.U)
  operands.badCoordinate:=Mux(buffer,input.x>=incoming.bufferTexels,input.x>=incoming.texWidth) ||
    input.y>=incoming.texHeight ||
    Mux(volume,input.z>=incoming.texDepth,input.z=/=0.U) ||
    Mux(array || cubeArray || msArray,input.layer>=incoming.layers,input.layer=/=0.U) ||
    Mux(msaa,input.sample>=incoming.samples,input.sample=/=0.U) ||
    Mux(squareCube,input.face>=TextureImageTarget.cubeFaces.U,input.face=/=0.U)
  operands.request:=input;operands.columns:=columns;operands.rowsMinusOne:=rows-1.U
  operands.slicesMinusOne:=slices-1.U;operands.planesMinusOne:=planes-1.U
  operands.column:=Mux(incoming.block4x4,input.x(13,0)>>2,input.x(13,0))
  operands.row:=Mux(incoming.block4x4,input.y(13,0)>>2,input.y(13,0))
  operands.slice:=Mux(volume,input.z(13,0),0.U)
  operands.layer:=Mux(cubeArray,(input.layer(15,0)*TextureImageTarget.cubeFaces.U)+input.face.pad(16),
    Mux(cube,input.face.pad(16),Mux(array || msArray,input.layer(15,0),0.U)))
  operands.sample:=input.sample;operands.samplesMinusOne:=(incoming.samples-1.U)(2,0)
  private val multiply=Wire(Decoupled(new Products))
  multiply.valid:=prepare.valid;prepare.ready:=multiply.ready
  private val q=prepare.bits.request;private val v=q.view;private val a=multiply.bits;private val coefficients=prepare.bits
  private val pitchOperands=Seq(coefficients.rowsMinusOne->v.rowPitch,coefficients.slicesMinusOne->v.slicePitch,
    coefficients.planesMinusOne->v.layerPitch,coefficients.samplesMinusOne->v.samplePitch,
    coefficients.row->v.rowPitch,coefficients.slice->v.slicePitch,coefficients.layer->v.layerPitch,
    coefficients.sample->v.samplePitch)
  a.invalid:=coefficients.invalid;a.badCoordinate:=coefficients.badCoordinate
  a.tag:=q.tag;a.level:=v.level;a.target:=v.target;a.allocationBase:=v.allocationBase
  a.allocationLimit:=v.allocationLimit;a.base:=v.base
  a.rowPitch:=v.rowPitch;a.slicePitch:=v.slicePitch;a.layerPitch:=v.layerPitch
  a.samplePitch:=v.samplePitch;a.samples:=v.samples
  a.rowBytes:=coefficients.columns*v.blockBytes
  a.column:=coefficients.column*v.blockBytes
  a.bufferTexels:=v.bufferTexels;a.bufferBytes:=v.bufferBytes
  a.bufferSpan:=v.bufferTexels*v.blockBytes;a.bufferColumn:=q.x*v.blockBytes
  productFields(a).zip(pitchOperands).foreach{case(field,(coefficient,pitch))=>field:=(if(pipelineProducts)0.U else coefficient*pitch)}
  a.bytes:=v.blockBytes;a.blockX:=Mux(v.block4x4,q.x(1,0),0.U);a.blockY:=Mux(v.block4x4,q.y(1,0),0.U)
  private val productInput=if(pipelineProducts){
    // Use the existing cut for compressed rows, so target selection and the
    // compressor are separated from prefix finishing without another cycle.
    // The full 47/49 useful bits and validity of rejected views are retained.
    val partial=Wire(Decoupled(new CompressedProducts))
    partial.valid:=multiply.valid;multiply.ready:=partial.ready;partial.bits.metadata:=multiply.bits
    pitchOperands.zipWithIndex.foreach{case((coefficient,pitch),i)=>
      val(sum,carry)=CarrySaveMultiplier.compress(pitch,coefficient,false,false)
      partial.bits.rows(i)(0):=sum;partial.bits.rows(i)(1):=carry
    }
    val compressed=Queue(partial,1,pipe=true,flow=false)
    val finished=Wire(Decoupled(new Products))
    finished.valid:=compressed.valid;compressed.ready:=finished.ready;finished.bits:=compressed.bits.metadata
    productFields(finished.bits).zipWithIndex.foreach{case(field,i)=>
      val width=field.getWidth;val adder=Module(new BlockPrefixAdder(width))
      adder.io.a:=compressed.bits.rows(i)(0)(width-1,0);adder.io.b:=compressed.bits.rows(i)(1)(width-1,0)
      adder.io.carryIn:=false.B;field:=adder.io.sum(width-1,0)
    }
    finished
  }else multiply
  private val products=Queue(productInput,1,pipe=true,flow=false)
  private val finish=Wire(Decoupled(new TextureImageAddressResult))
  private def exactSum(terms: Seq[UInt]): UInt={
    // Enough bits for all original unsigned terms, including rejected views.
    // Carry-save reduction leaves only one carry-propagating prefix addition.
    val width=terms.map(_.getWidth).max+log2Ceil(terms.size)
    var pending=terms.map(_.pad(width))
    while(pending.size>2){
      pending=pending.grouped(3).flatMap{group=>
        if(group.size<3)group else {
          val Seq(a,b,c)=group
          val sum=a^b^c;val carry=(((a&b)|(a&c)|(b&c))<<1)(width-1,0)
          Seq(sum,carry)
        }
      }.toSeq
    }
    val adder=Module(new BlockPrefixAdder(width));adder.io.a:=pending(0);adder.io.b:=pending(1);adder.io.carryIn:=false.B
    adder.io.sum
  }
  // In a composed owner the final wide sums and checks can exceed one
  // cycle. This optional cut preserves full arithmetic and steady throughput;
  // the legacy/default path below retains its three-cycle register placement.
  private val boundsBusy=if(pipelineBounds){
    val sum=Wire(Decoupled(new Sums));sum.valid:=products.valid;products.ready:=sum.ready
    val b=products.bits;val a=sum.bits
    val buffer=b.target===TextureImageTarget.buffer.U
    val badCount=b.bufferSpan>b.bufferBytes ||
      (b.bufferTexels<p.maxBufferTexels.U && (b.bufferSpan+&b.bytes)<=b.bufferBytes)
    a.tag:=b.tag;a.level:=b.level;a.target:=b.target;a.invalid:=b.invalid || (buffer && badCount);a.badCoordinate:=b.badCoordinate
    a.base:=b.base;a.allocationBase:=b.allocationBase;a.allocationLimit:=b.allocationLimit
    // Buffer spans do not have rows. Reuse the existing full-width bounds
    // pipeline without narrowing the buffer count or selected byte product.
    a.rowPitch:=b.rowPitch;a.rowBytes:=Mux(buffer,0.U,b.rowBytes)
    a.planePitch:=Mux(b.target===TextureImageTarget.image3D.U,b.slicePitch,b.layerPitch)
    a.needsPlanePitch:=b.target===TextureImageTarget.image3D.U || b.target===TextureImageTarget.array2D.U ||
      b.target===TextureImageTarget.cube.U || b.target===TextureImageTarget.cubeArray.U ||
      b.target===TextureImageTarget.array2DMS.U
    a.samplePitch:=b.samplePitch;a.samples:=b.samples
    a.planeSpan:=exactSum(Seq(b.lastRow,b.rowBytes))
    a.levelEnd:=Mux(buffer,exactSum(Seq(b.base,b.bufferBytes)),
      exactSum(Seq(b.base,b.lastRow,b.rowBytes,b.lastSlice,b.lastLayer,b.lastSample)))
    val coordinates=Seq(b.base,b.column,b.row,b.slice,b.layer,b.sampleOffset)
    a.address:=Mux(buffer,exactSum(Seq(b.base,b.bufferColumn)),exactSum(coordinates))
    a.selectedEnd:=Mux(buffer,exactSum(Seq(b.base,b.bufferColumn,b.bytes)),exactSum(coordinates:+b.bytes))
    a.bytes:=b.bytes;a.blockX:=b.blockX;a.blockY:=b.blockY
    val summed=Queue(sum,1,pipe=true,flow=false);val q=summed.bits
    finish.valid:=summed.valid;summed.ready:=finish.ready
    val msaaBounds=(q.target===TextureImageTarget.image2DMS.U || q.target===TextureImageTarget.array2DMS.U) &&
      (q.samplePitch<q.planeSpan || (q.target===TextureImageTarget.array2DMS.U &&
        q.planePitch<q.samplePitch.pad(64)*q.samples.pad(64)))
    val badBounds=msaaBounds || q.rowPitch<q.rowBytes || (q.needsPlanePitch && q.planePitch<q.planeSpan) ||
      q.allocationBase>=q.allocationLimit || q.allocationLimit>(BigInt(1)<<32).U || q.base<q.allocationBase ||
      q.levelEnd>q.allocationLimit || q.selectedEnd>q.allocationLimit || q.selectedEnd>(BigInt(1)<<32).U
    val status=Mux(q.invalid,TextureImageAddressStatus.invalidView.U,
      Mux(q.badCoordinate,TextureImageAddressStatus.coordinate.U,
        Mux(badBounds,TextureImageAddressStatus.bounds.U,TextureImageAddressStatus.ok.U)))
    finish.bits:=0.U.asTypeOf(finish.bits);finish.bits.tag:=q.tag;finish.bits.level:=q.level;finish.bits.status:=status
    when(status===TextureImageAddressStatus.ok.U){
      finish.bits.address:=q.address(31,0);finish.bits.bytes:=q.bytes;finish.bits.blockX:=q.blockX;finish.bits.blockY:=q.blockY
    }
    summed.valid
  }else{
    finish.valid:=products.valid;products.ready:=finish.ready
    val b=products.bits
    val buffer=b.target===TextureImageTarget.buffer.U
    val badCount=b.bufferSpan>b.bufferBytes ||
      (b.bufferTexels<p.maxBufferTexels.U && (b.bufferSpan+&b.bytes)<=b.bufferBytes)
    val planeSpan=b.lastRow.pad(64)+b.rowBytes.pad(64)
    val span=planeSpan+b.lastSlice.pad(64)+b.lastLayer.pad(64)
    val levelEnd=b.base.pad(64)+Mux(buffer,b.bufferBytes.pad(64),span+b.lastSample.pad(64))
    val address=b.base.pad(64)+Mux(buffer,b.bufferColumn.pad(64),
      b.column.pad(64)+b.row.pad(64)+b.slice.pad(64)+b.layer.pad(64)+b.sampleOffset.pad(64))
    val selectedEnd=address+b.bytes.pad(64)
    val planePitchBad=Mux(b.target===TextureImageTarget.image3D.U,b.slicePitch<planeSpan,
      (b.target===TextureImageTarget.array2D.U || b.target===TextureImageTarget.cube.U ||
        b.target===TextureImageTarget.cubeArray.U ||
        b.target===TextureImageTarget.array2DMS.U) && b.layerPitch<planeSpan)
    val msaaBounds=(b.target===TextureImageTarget.image2DMS.U || b.target===TextureImageTarget.array2DMS.U) &&
      (b.samplePitch<planeSpan || (b.target===TextureImageTarget.array2DMS.U &&
        b.layerPitch<b.samplePitch.pad(64)*b.samples.pad(64)))
    val badBounds=msaaBounds || (!buffer && b.rowPitch<b.rowBytes) || planePitchBad || b.allocationBase>=b.allocationLimit ||
      b.allocationLimit>(BigInt(1)<<32).U || b.base<b.allocationBase || levelEnd>b.allocationLimit ||
      selectedEnd>b.allocationLimit || selectedEnd>(BigInt(1)<<32).U
    val status=Mux(b.invalid || (buffer && badCount),TextureImageAddressStatus.invalidView.U,
      Mux(b.badCoordinate,TextureImageAddressStatus.coordinate.U,
        Mux(badBounds,TextureImageAddressStatus.bounds.U,TextureImageAddressStatus.ok.U)))
    finish.bits:=0.U.asTypeOf(finish.bits);finish.bits.tag:=b.tag;finish.bits.level:=b.level;finish.bits.status:=status
    when(status===TextureImageAddressStatus.ok.U){
      finish.bits.address:=address(31,0);finish.bits.bytes:=b.bytes;finish.bits.blockX:=b.blockX;finish.bits.blockY:=b.blockY
    }
    false.B
  }
  private val result=Queue(finish,1,pipe=true,flow=false)
  io.out<>result
  io.busy:=captured.valid || productInput.valid || products.valid || result.valid || boundsBusy
}
