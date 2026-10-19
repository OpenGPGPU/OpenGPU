package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.ShaderPayloadBinding

/** Producer buses retain fixed physical owners; task metadata follows the
  * shared shader launch and pins cover every queue tier before CU admission.
  */
class ShaderPayloadRendererPort(config: GpuConfig,p: ShaderPayloadConfig,
    producers: Int,pins: Int) extends Bundle {
  val producer=Vec(producers,new ShaderPayloadProducerPort(p))
  val task=Output(Valid(new ShaderPayloadBinding(config,p)))
  val pinsOut=Output(Vec(pins,Valid(new ShaderPayloadPin(p))))
}
