package opengpu.graphics

import chisel3._

/** Versions 3/4 enable centroid qualifiers without changing the corresponding
  * version 1/2 shader SoA offsets. Version 4 retains pointCoord and kind flags.
  */
object FragmentInputAbi {
  val center=1;val pointCoord=2;val centroid=3;val centroidPointCoord=4
  // Internal geometry input; public capability and descriptor versions remain 1–4.
  val geometry=5
  def hasGeometry(version: UInt): Bool=version===geometry.U
  def hasGeometry(version: Int): Boolean=version==geometry
  def legal(version: UInt): Bool=version>=center.U && version<=centroidPointCoord.U
  def hasPointCoord(version: UInt): Bool=version===pointCoord.U || version===centroidPointCoord.U || hasGeometry(version)
  def hasCentroid(version: UInt): Bool=version===centroid.U || version===centroidPointCoord.U || hasGeometry(version)
  def hasPointCoord(version: Int): Boolean=version==pointCoord || version==centroidPointCoord || hasGeometry(version)
  def header(prefix: String): String=Seq("CENTER"->center,"POINT_COORD"->pointCoord,
    "CENTROID"->centroid,"CENTROID_POINT_COORD"->centroidPointCoord)
    .map{case(n,v)=>s"#define ${prefix}FRAGMENT_VERSION_$n ${v}u\n"}.mkString
}
