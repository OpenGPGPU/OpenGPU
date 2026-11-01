package opengpu.system

import chisel3._
import chisel3.util._

/** GNZ1 completion snapshot for experimental GNQ1 host commands. The raw
  * reader address is meaningful only for the reader's reported fault stage;
  * input address/task are meaningful only when inputFaultValid is set. */
object TessellationNativeCompletionInfo {
  val magic=BigInt("474e5a01",16)
  def words(c:TessellationNativeJobCompletion):Seq[UInt]={
    val frame=c.frame;val image=frame.img;val source=image.source;val bins=source.bins
    val flags=Cat(0.U(23.W),image.faultTileValid,frame.inputFaultValid,image.faultStage,c.faultStage,c.status)
    val nested=Cat(0.U(14.W),bins.producer.binStatus,bins.producer.status,bins.status,source.status,image.status,frame.status)
    Seq(magic.U(32.W),flags,c.faultAddress,c.faultDraw,Mux(image.faultTileValid,image.faultTile,0.U),nested,
      c.identity.context,c.identity.pass,c.identity.generation,Mux(frame.inputFaultValid,frame.input.faultAddress,0.U),
      Mux(frame.inputFaultValid,frame.input.taskId,0.U),0.U(32.W))
  }
}
