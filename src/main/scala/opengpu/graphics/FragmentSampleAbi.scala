package opengpu.graphics

import chisel3._
import chisel3.util._

/** Opt-in versions6/7 retain the old color/depth/control arrays. Control bit6
  * means shader sample mask written; bits10:7 carry its low four bits. Old
  * versions still reject every bit31:6. Input appends SampleID and Position.xy
  * after point/geometry inputs; the existing coverage array is SampleMaskIn.
  */
object FragmentSampleAbi {
  def invalidControl(control:UInt,version:UInt):Bool=
    Mux(FragmentInputAbi.hasSample(version),control(31,11).orR,control(31,6).orR)
  def effectiveMask(coverage:UInt,control:UInt,version:UInt,enabled:Bool):UInt=
    Mux(enabled,coverage & Mux(FragmentInputAbi.hasSample(version) && control(6),control(10,7),15.U),0.U)
  def position(mode:UInt,id:UInt,axis:Int):UInt={
    require(axis==0 || axis==1)
    val high=if(axis==0)id(0)else Mux(mode===1.U,id(0),id(1))
    Mux(mode===0.U,"h3f000000".U,Mux(high,"h3f400000".U,"h3e800000".U))
  }
}
