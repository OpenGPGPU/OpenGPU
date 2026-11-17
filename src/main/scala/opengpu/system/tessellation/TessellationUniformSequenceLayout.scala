package opengpu.system

import chisel3._
import opengpu.graphics.TessellationUniformTaskResources

/** Uniform-capable GNT4/5/6 keep the complete old domain record and append one
  * cache line. The 2048-byte extent and every original field offset stay fixed.
  * These fields were reserved zero in GNT1/2/3 and are never decoded there.
  */
object TessellationUniformSequenceLayout {
  val firstWord=496;val endWord=509
  private val prefix="template.source.viewport.system.uniforms."
  def isUniformField(name:String):Boolean=name.startsWith(prefix)
  def fields(s:TessellationUniformTaskResources):Seq[(String,Int,Data)]=Seq(
    ("identity.context",496,s.identity.context),("identity.pass",497,s.identity.pass),
    ("identity.generation",498,s.identity.generation),
    ("control.resources.version",499,s.control.resources.version),("control.resources.address",500,s.control.resources.address),
    ("control.privateBase",501,s.control.privateBase),("control.privateLimit",502,s.control.privateLimit),
    ("evaluation.resources.version",504,s.evaluation.resources.version),("evaluation.resources.address",505,s.evaluation.resources.address),
    ("evaluation.privateBase",506,s.evaluation.privateBase),("evaluation.privateLimit",507,s.evaluation.privateLimit)
  ).map{case(name,word,value)=>(prefix+name,word,value)}
}
