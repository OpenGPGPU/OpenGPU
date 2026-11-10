package opengpu.elaboration

import opengpu.config.MobileGpuConfig
import opengpu.graphics.WindowPrimitiveRecordReadCache

/** Native four-engine record-reader component, without whole-host emission. */
object EmitWindowPrimitiveRecordReadCache extends App {
  require(args.length == 1, "output directory required")
  EmitRtl.emit(
    new WindowPrimitiveRecordReadCache(
      MobileGpuConfig().execution, engines = 4, recordLines = 23, transactions = 18,
      firstReaderId = 14, useSramBlackBoxes = true),
    args(0),
    Array("--disable-all-randomization", "--strip-debug-info",
      "--lowering-options=disallowLocalVariables,disallowPackedArrays"))
}
