package opengpu.elaboration

import circt.stage.ChiselStage
import opengpu.graphics._

object EmitTransformFeedback {
  def main(args: Array[String]): Unit={
    require(args.length==2 && Set("export","geometry","control")(args(0)),
      "EmitTransformFeedback <export|geometry|control> <target-dir>")
    val stage=Array("--target-dir",args(1))
    val options=Array("--lowering-options=disallowLocalVariables,disallowPackedArrays")
    if(args(0)=="export")ChiselStage.emitSystemVerilogFile(new TransformFeedbackExport(),stage,options)
    else if(args(0)=="control")ChiselStage.emitSystemVerilogFile(new NativeTransformFeedbackControl(),stage,options)
    else ChiselStage.emitSystemVerilogFile(new TransformFeedbackGeometryStage(),stage,options)
  }
}
