package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.util.BlockPrefixAdder

/** Capture clear addresses/masks during the existing descriptor-validation
  * clock. Operational callers already require a64-byte aligned scratch base;
  * invalid descriptors are rejected before these captured values are consumed.
  */
class FragmentClearRangeRegister extends Module {
  val io=IO(new Bundle {
    val base=Input(UInt(32.W));val inputBytes=Input(UInt(32.W));val prefixBytes=Input(UInt(32.W))
    val capture=Input(Bool())
    val first=Output(UInt(32.W));val last=Output(UInt(32.W))
    val low=Output(UInt(64.W));val high=Output(UInt(64.W))
  })
  private val base=io.base(31,6)
  private val first=Module(new BlockPrefixAdder(26))
  first.io.a:=base;first.io.b:=io.inputBytes(31,6);first.io.carryIn:=false.B
  private val prefix=io.prefixBytes(31,6)
  private val aligned=io.prefixBytes(5,0)===0.U
  // Last word = baseWord + prefixWords - (prefix remainder ==0).
  // Fold the conditional -1 as a carry-save third operand before one adder.
  private val last=Module(new BlockPrefixAdder(26))
  last.io.a:=Mux(aligned,~(base^prefix),base^prefix)
  last.io.b:=(Mux(aligned,base|prefix,base&prefix)<<1)(25,0)
  last.io.carryIn:=false.B
  private val low=((~0.U(64.W))<<io.inputBytes(5,0))(63,0)
  private val high=Mux(aligned,~0.U(64.W),(~0.U(64.W))>>(64.U(7.W)-io.prefixBytes(5,0)))
  io.first:=RegEnable(Cat(first.io.sum(25,0),0.U(6.W)),io.capture)
  io.last:=RegEnable(Cat(last.io.sum(25,0),0.U(6.W)),io.capture)
  io.low:=RegEnable(low,io.capture);io.high:=RegEnable(high,io.capture)
}
