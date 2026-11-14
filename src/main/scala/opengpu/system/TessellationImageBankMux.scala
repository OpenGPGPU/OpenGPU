package opengpu.system

import chisel3._
import chisel3.reflect.DataMirror
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.graphics._

/** Route every engine leaf under the same retained job owner. No channel has
  * independent arbitration: delayed memory, shader and payload replies cannot
  * migrate to the next domain. Unselected domains observe an idle engine.
  */
class TessellationImageBankMux(config:GpuConfig,p:VertexShaderStageConfig,shared:SharedCuShaderConfig,
    engines:Int,payload:Option[ShaderPayloadConfig],fragmentImageAvailable:Boolean=false) extends Module {
  require(engines>=1 && engines<=4)
  private val sharedConfig=shared;private val payloadConfig=payload
  val io=IO(new Bundle {
    val clients=Vec(3,Vec(engines,new TypedReplayTileImagePipelineIO(config,p,sharedConfig,payloadConfig,fragmentImageAvailable=fragmentImageAvailable)))
    val bank=Flipped(Vec(engines,new TypedReplayTileImagePipelineIO(config,p,sharedConfig,payloadConfig,fragmentImageAvailable=fragmentImageAvailable)))
    val active=Input(Bool());val domain=Input(UInt(2.W))
  })
  when(io.active){assert(io.domain<3.U,"invalid shared tessellation image bank owner")}
  private def leaf[T <: Data](bank:T,clients:Seq[T]):Unit={
    DataMirror.directionOf(bank) match {
      case ActualDirection.Output =>
        bank:=Mux(io.active,MuxLookup(io.domain,clients.head)(Seq(1.U->clients(1),2.U->clients(2))),0.U.asTypeOf(bank))
      case ActualDirection.Input =>
        for(i<-clients.indices)clients(i):=Mux(io.active && io.domain===i.U,bank,0.U.asTypeOf(bank))
      case other => throw new IllegalArgumentException(s"unsupported image bank leaf direction $other")
    }
  }
  private def connect(bank:Data,clients:Seq[Data]):Unit=bank match {
    case r:Record => r.elements.foreach{case(name,value)=>connect(value,clients.map(_.asInstanceOf[Record].elements(name)))}
    case v:Vec[_] => v.indices.foreach(i=>connect(v(i),clients.map(_.asInstanceOf[Vec[Data]](i))))
    case u:UInt => leaf(u,clients.map(_.asInstanceOf[UInt]))
    case s:SInt => leaf(s,clients.map(_.asInstanceOf[SInt]))
    case e:EnumType => leaf(e,clients.map(_.asInstanceOf[EnumType]))
    case other => throw new IllegalArgumentException(s"unsupported image bank leaf ${other.getClass.getSimpleName}")
  }
  for(e<-0 until engines)connect(io.bank(e),io.clients.map(_(e)).toSeq)
}
