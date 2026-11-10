package opengpu.elaboration

import opengpu.graphics._

object EmitTransformFeedback {
  def main(args: Array[String]): Unit = {
    require(args.length == 2 && Set("export", "geometry", "control")(args(0)),
      "EmitTransformFeedback <export|geometry|control> <target-dir>")
    args(0) match {
      case "export" => EmitRtl.emit(new TransformFeedbackExport(), args(1))
      case "control" => EmitRtl.emit(new NativeTransformFeedbackControl(), args(1))
      case "geometry" => EmitRtl.emit(new TransformFeedbackGeometryStage(), args(1))
      case _ => throw new IllegalArgumentException(args(0))
    }
  }
}
