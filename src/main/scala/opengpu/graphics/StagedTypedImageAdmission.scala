package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.util.BlockPrefixAdder

class TypedImageAdmissionResult extends Bundle {
  val invalid=Bool();val cancelled=Bool()
  /** Physical tile origin for sources0..5 and single-sample destinations6..11. */
  val origins=Vec(12,UInt(32.W))
}

/** Validate one immutable image descriptor before any memory traffic. Bounds
  * retain the exclusive 4 GiB endpoint. Positive row products are accumulated
  * without wrap; a term above the admitted limit rejects rather than truncates.
  */
class StagedTypedImageAdmission(p: TypedTileColorConfig=TypedTileColorConfig()) extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new TypedTileImageDescriptor))
    val out=Decoupled(new TypedImageAdmissionResult)
    val cancel=Input(Bool());val busy=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,common,choose,prepareWidth,check,initial,rows,save,originWidth,originStart,originRows,originSave,aliases,respond=Value}
  private val phase=RegInit(State.idle);private val d=Reg(new TypedTileImageDescriptor)
  private val result=Reg(new TypedImageAdmissionResult)
  private val which=RegInit(0.U(4.W));private val plane=Reg(new TypedImageView)
  private val resolving=Reg(Bool());private val planeEnabled=Reg(Bool())
  private val readRequired=Reg(Bool());private val writeRequired=Reg(Bool())
  private val size=Reg(UInt(5.W));private val mode=Reg(UInt(2.W));private val rowWidth=Reg(UInt(40.W))
  private val accumulator=Reg(UInt(33.W));private val term=Reg(UInt(33.W));private val remainingRows=Reg(UInt(32.W))
  private val sourceEnds=Reg(Vec(6,UInt(33.W)));private val resolveEnds=Reg(Vec(6,UInt(33.W)))
  private val limit=(BigInt(1)<<32).U(33.W);private val overflow=((BigInt(1)<<32)+1).U(33.W)
  private val tile=d.pixels.colors;private val enabled=TypedTileImageRules.enabled(d.pixels)
  private val sizes=TypedTileImageRules.bytes(d.pixels);private val loads=TypedTileImageRules.loads(d.pixels)
  private val needed=VecInit(d.views.zipWithIndex.map{case(v,i)=>enabled(i) && (v.store || loads(i)===AttachmentLoadOp.load.U)})
  private val destination=which>=6.U && which<12.U
  private val index=Mux(which<6.U,which,Mux(which<12.U,which-6.U,0.U))(2,0)
  private val adder=Module(new BlockPrefixAdder(33))
  adder.io.a:=accumulator;adder.io.b:=term;adder.io.carryIn:=false.B
  private def reject(): Unit={result.invalid:=true.B;phase:=State.respond}
  private def accumulateRows(next: State.Type): Unit={
      when(remainingRows===0.U){phase:=next}.otherwise{
        when(remainingRows(0)){
          when(adder.io.sum>plane.limit){reject()}.otherwise{accumulator:=adder.io.sum(32,0)}
        }
        val doubled=term<<1
        term:=Mux(doubled>limit,overflow,doubled(32,0));remainingRows:=remainingRows>>1
        when(remainingRows===1.U && !(remainingRows(0) && adder.io.sum>plane.limit)){phase:=next}
      }
    }


  io.in.ready:=phase===State.idle && !io.cancel
  io.out.valid:=phase===State.respond;io.out.bits:=result;io.busy:=phase=/=State.idle
  switch(phase){
    is(State.idle){when(io.in.fire){
      d:=io.in.bits;result:=0.U.asTypeOf(result);which:=0.U
      sourceEnds:=0.U.asTypeOf(sourceEnds);resolveEnds:=0.U.asTypeOf(resolveEnds);phase:=State.common
    }}
    is(State.common){when(d.version=/=1.U || TypedTilePixelRules.invalid(d.pixels,p)){reject()}.otherwise{phase:=State.choose}}
    is(State.choose){
      when(which===12.U){phase:=State.aliases}
        .elsewhen(Mux(destination,d.resolves(index).enable,needed(index))){
          plane:=Mux(destination,d.resolves(index).destination,d.views(index))
          resolving:=destination;planeEnabled:=enabled(index)
          readRequired:= !destination && loads(index)===AttachmentLoadOp.load.U
          writeRequired:=destination || d.views(index).store
          size:=sizes(index);mode:=Mux(destination,0.U,tile.sampleMode);phase:=State.prepareWidth
        }.otherwise{which:=which+1.U}
    }
    is(State.prepareWidth){rowWidth:=(plane.imageWidth*size)<<mode;phase:=State.check}
    is(State.check){
      val bad=plane.imageWidth===0.U || plane.imageHeight===0.U || tile.originX<0.S || tile.originY<0.S ||
        tile.originX.asUInt.pad(34)+tile.tileWidth>plane.imageWidth || tile.originY.asUInt.pad(34)+tile.tileHeight>plane.imageHeight ||
        rowWidth>plane.rowPitch || plane.allocationBase(5,0)=/=0.U || plane.allocationLimit(5,0)=/=0.U ||
        plane.allocationLimit>limit || plane.allocationLimit<=plane.allocationBase || plane.base<plane.allocationBase ||
        plane.limit>plane.allocationLimit || plane.limit<=plane.base ||
        (readRequired && !plane.readable) || (writeRequired && !plane.writable) ||
        (resolving && (!planeEnabled || !plane.store))
      when(bad){reject()}.otherwise{phase:=State.initial}
    }
    is(State.initial){
      // The row-width check establishes that these two terms each fit32 bits.
      val firstEnd=plane.base+&rowWidth(31,0)
      when(firstEnd>plane.limit){reject()}.otherwise{
        accumulator:=firstEnd;term:=plane.rowPitch;remainingRows:=plane.imageHeight-1.U;phase:=State.rows
      }
    }
    is(State.rows){accumulateRows(State.save)}
    is(State.save){
      when(resolving){resolveEnds(index):=accumulator}.otherwise{sourceEnds(index):=accumulator}
      phase:=State.originWidth
    }
    is(State.originWidth){rowWidth:=(tile.originX.asUInt*size)<<mode;phase:=State.originStart}
    is(State.originStart){
      // Scalar bounds already prove the origin lies inside this checked view.
      val first=plane.base+&rowWidth(31,0)
      when(first>plane.limit){reject()}.otherwise{
        accumulator:=first;term:=plane.rowPitch;remainingRows:=tile.originY.asUInt;phase:=State.originRows
      }
    }
    is(State.originRows){accumulateRows(State.originSave)}
    is(State.originSave){
      assert(accumulator<limit,"admitted image origin escaped32-bit physical space")
      result.origins(which):=accumulator(31,0);which:=which+1.U;phase:=State.choose
    }
    is(State.aliases){
      val sourceAlias=(for(a<-0 until 6;b<-a+1 until 6)yield needed(a) && needed(b) && d.views(a).store && d.views(b).store &&
        d.views(a).base<sourceEnds(b) && d.views(b).base<sourceEnds(a)).reduce(_ || _)
      val resolveAlias=(for(a<-0 until 6;b<-0 until 6)yield {
        val r=d.resolves(a);val v=r.destination;val other=d.resolves(b)
        val sourceOverlap=needed(b) && v.base<sourceEnds(b) && d.views(b).base<resolveEnds(a)
        val outputOverlap=(a!=b).B && other.enable && v.base<resolveEnds(b) && other.destination.base<resolveEnds(a)
        val lateSourceAlias=r.enable && loads(a)===AttachmentLoadOp.load.U && needed(a) && needed(b) && d.views(b).store && (a!=b).B &&
          d.views(a).base<sourceEnds(b) && d.views(b).base<sourceEnds(a)
        (r.enable && (sourceOverlap || outputOverlap)) || lateSourceAlias
      }).reduce(_ || _)
      result.invalid:=sourceAlias || resolveAlias;phase:=State.respond
    }
    is(State.respond){when(io.out.fire){phase:=State.idle}}
  }
  when(io.cancel && phase=/=State.idle && phase=/=State.respond){
    result.invalid:=false.B;result.cancelled:=true.B;phase:=State.respond
  }
}
