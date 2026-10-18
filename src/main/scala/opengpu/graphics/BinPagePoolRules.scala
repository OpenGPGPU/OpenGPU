package opengpu.graphics

import chisel3._
import chisel3.util._

/** Immutable version-2 pool layout: reserve [base,limit), use its initial
  * prefix and up to eight increasing fallback segments. Gaps stay reserved
  * but must never be fetched or written. Version 1 ignores extension fields.
  */
object BinPagePoolRules {
  val maxSpills=8
  def aligned(value: UInt,pageBytes: Int): Bool = (value & (pageBytes-1).U)===0.U
  def headerLegal(p: BinPagePoolDescriptor,pageBytes: Int): Bool = {
    val common=p.pageSizeLog2===log2Ceil(pageBytes).U && aligned(p.base,pageBytes) &&
      aligned(p.limit,pageBytes) && p.base.pad(33)<=p.limit && p.limit<=(BigInt(1)<<32).U
    common && (p.version===1.U || (p.version===2.U && p.spillCount<=maxSpills.U &&
      aligned(p.initialLimit,pageBytes) && p.base.pad(33)<=p.initialLimit && p.initialLimit<=p.limit))
  }
  def segmentLegal(p: BinPagePoolDescriptor,index: UInt,previous: UInt,pageBytes: Int): Bool = {
    val span=p.spills(index)
    Mux(index<p.spillCount,
      aligned(span.base,pageBytes) && aligned(span.limit,pageBytes) &&
        previous<=span.base.pad(33) && span.base.pad(33)<span.limit && span.limit<=p.limit,
      span.base===0.U && span.limit===0.U)
  }
  def pageInBounds(address: UInt,p: BinPagePoolDescriptor,pageBytes: Int): Bool = {
    val end=address.pad(33)+&pageBytes.U
    val envelope=aligned(address,pageBytes) && address>=p.base && end<=p.limit.pad(34)
    val initial=end<=p.initialLimit.pad(34)
    val fallback=(0 until maxSpills).map(i=>i.U<p.spillCount && address>=p.spills(i).base &&
      end<=p.spills(i).limit.pad(34)).reduce(_ || _)
    envelope && (p.version===1.U || (p.version===2.U && (initial || fallback)))
  }
}
