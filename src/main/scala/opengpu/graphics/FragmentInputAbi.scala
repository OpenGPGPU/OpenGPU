package opengpu.graphics

import chisel3._

/** Versions 3/4 enable centroid qualifiers without changing the corresponding
  * version 1/2 shader SoA offsets. Version 4 retains pointCoord and kind flags.
  */
object FragmentInputAbi {
  val center=1;val pointCoord=2;val centroid=3;val centroidPointCoord=4
  // Internal geometry input; public capability and descriptor versions remain 1–4.
  val geometry=5
  // Opt-in sample-plane inputs; geometrySample is internal like geometry.
  val sample=6;val geometrySample=7
  def hasSample(version:UInt):Bool=version===sample.U || version===geometrySample.U
  def hasSample(version:Int):Boolean=version==sample || version==geometrySample
  def supported(version:UInt,geometryInput:Boolean=false,sampleShading:Boolean=false):Bool=
    legal(version) || (geometryInput.B && version===geometry.U) ||
      (sampleShading.B && (version===sample.U || (geometryInput.B && version===geometrySample.U)))
  def hasGeometry(version: UInt): Bool=version===geometry.U || version===geometrySample.U
  def hasGeometry(version: Int): Boolean=version==geometry || version==geometrySample
  def legal(version: UInt): Bool=version>=center.U && version<=centroidPointCoord.U
  def hasPointCoord(version: UInt): Bool=version===pointCoord.U || version===centroidPointCoord.U || hasGeometry(version) || hasSample(version)
  def hasCentroid(version: UInt): Bool=version===centroid.U || version===centroidPointCoord.U || hasGeometry(version) || hasSample(version)
  def hasPointCoord(version: Int): Boolean=version==pointCoord || version==centroidPointCoord || hasGeometry(version) || hasSample(version)
  def header(prefix: String): String=Seq("CENTER"->center,"POINT_COORD"->pointCoord,
    "CENTROID"->centroid,"CENTROID_POINT_COORD"->centroidPointCoord,"SAMPLE"->sample)
    .map{case(n,v)=>s"#define ${prefix}FRAGMENT_VERSION_$n ${v}u\n"}.mkString
}
