package opengpu.elaboration

import opengpu.graphics.NativeGeometryTransportCapabilities

object EmitNativeGeometryTransportAbi {
  def main(args:Array[String]):Unit={
    EmitCommandAbi.emitFixed(args,Seq("driver/opengpu_native_geometry_transport_abi.h"->NativeGeometryTransportCapabilities.header))
    println(s"NATIVE_GEOMETRY_TRANSPORT_ABI checked=${args.nonEmpty} words=${NativeGeometryTransportCapabilities.wordCount}")
  }
}
