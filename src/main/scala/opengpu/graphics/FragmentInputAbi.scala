package opengpu.graphics

import chisel3._

/** Versions 3/4 enable centroid qualifiers without changing the corresponding
  * version 1/2 shader SoA offsets. Version 4 retains pointCoord and kind flags.
  */
object FragmentInputAbi {
  val center=1;val pointCoord=2;val centroid=3;val centroidPointCoord=4
  def legal(version: UInt): Bool=version>=center.U && version<=centroidPointCoord.U
  def hasPointCoord(version: UInt): Bool=version===pointCoord.U || version===centroidPointCoord.U
  def hasCentroid(version: UInt): Bool=version===centroid.U || version===centroidPointCoord.U
  def hasPointCoord(version: Int): Boolean=version==pointCoord || version==centroidPointCoord
  def header(prefix: String): String=Seq("CENTER"->center,"POINT_COORD"->pointCoord,
    "CENTROID"->centroid,"CENTROID_POINT_COORD"->centroidPointCoord)
    .map{case(n,v)=>s"#define ${prefix}FRAGMENT_VERSION_$n ${v}u\n"}.mkString
}
