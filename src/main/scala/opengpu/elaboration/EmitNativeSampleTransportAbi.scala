package opengpu.elaboration

import opengpu.graphics.NativeSampleTransportCapabilities

object EmitNativeSampleTransportAbi {
  def main(args:Array[String]):Unit={
    EmitCommandAbi.emitFixed(args,Seq("driver/opengpu_native_sample_transport_abi.h"->NativeSampleTransportCapabilities.header))
    println(s"NATIVE_SAMPLE_TRANSPORT_ABI checked=${args.nonEmpty} words=${NativeSampleTransportCapabilities.wordCount}")
  }
}
