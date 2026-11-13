package opengpu.elaboration

import opengpu.graphics.NativeDescriptorPreflightCapabilities

object EmitNativeDescriptorPreflightAbi {
  def main(args: Array[String]): Unit={
    EmitCommandAbi.emitFixed(args,Seq("driver/opengpu_native_preflight_abi.h"->NativeDescriptorPreflightCapabilities.header))
    println(s"NATIVE_PREFLIGHT_ABI checked=${args.nonEmpty} words=${NativeDescriptorPreflightCapabilities.wordCount}")
  }
}
