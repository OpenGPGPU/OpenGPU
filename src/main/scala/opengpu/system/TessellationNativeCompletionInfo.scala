package opengpu.system

import chisel3._
import chisel3.util._

/** GNZ1 completion snapshot for experimental GNQ1 host commands. The raw
  * reader address is meaningful only for the reader's reported fault stage;
  * input address/task are meaningful only when inputFaultValid is set. */
object TessellationNativeCompletionInfo {
  val magic=BigInt("474e5a01",16)
  val base=opengpu.graphics.NativeBinnedCompletionInfo.base
  val wordCount=opengpu.graphics.NativeBinnedCompletionInfo.wordCount
  import opengpu.graphics.NativeBinnedGraphicsCapabilities.Field
  val fields=Seq(Field("MAGIC",0,0,32,"GNZ1"),Field("STATUS",1,0,3,"job status"),
    Field("READER_STAGE",1,3,2,"reader fault stage"),Field("IMAGE_STAGE",1,5,2,"image fault stage"),
    Field("INPUT_FAULT_VALID",1,7,1,"input fault fields valid"),Field("TILE_VALID",1,8,1,"fault tile valid"),
    Field("READER_ADDRESS",2,0,32,"raw reader address"),Field("READER_DRAW",3,0,32,"raw reader draw"),
    Field("TILE",4,0,32,"fault tile when valid"))++
    Seq("FRAME","IMAGE","SOURCE","BINS","PRODUCER","BIN_PRODUCER").zipWithIndex.map{
      case(name,i)=>Field(name+"_STATUS",5,i*3,3,"nested owner status")}++Seq(
    Field("CONTEXT",6,0,32,"job context"),Field("PASS",7,0,32,"job pass"),
    Field("GENERATION",8,0,32,"job generation"),Field("INPUT_ADDRESS",9,0,32,"input fault address when valid"),
    Field("INPUT_TASK",10,0,32,"input fault task when valid"),Field("RESERVED",11,0,32,"zero"))
  def words(c:TessellationNativeJobCompletion):Seq[UInt]={
    val frame=c.frame;val image=frame.img;val source=image.source;val bins=source.bins
    val flags=Cat(0.U(23.W),image.faultTileValid,frame.inputFaultValid,image.faultStage,c.faultStage,c.status)
    val nested=Cat(0.U(14.W),bins.producer.binStatus,bins.producer.status,bins.status,source.status,image.status,frame.status)
    Seq(magic.U(32.W),flags,c.faultAddress,c.faultDraw,Mux(image.faultTileValid,image.faultTile,0.U),nested,
      c.identity.context,c.identity.pass,c.identity.generation,Mux(frame.inputFaultValid,frame.input.faultAddress,0.U),
      Mux(frame.inputFaultValid,frame.input.taskId,0.U),0.U(32.W))
  }
  /** Encode triangle job completions in the same GNZ1 layout as isoline jobs. */
  def words(c:TessellationNativeTriangleJobCompletion):Seq[UInt]={
    val frame=c.frame;val image=frame.img;val source=image.source;val bins=source.bins
    val flags=Cat(0.U(23.W),image.faultTileValid,frame.inputFaultValid,image.faultStage,c.faultStage,c.status)
    val nested=Cat(0.U(14.W),bins.producer.binStatus,bins.producer.status,bins.status,source.status,image.status,frame.status)
    Seq(magic.U(32.W),flags,c.faultAddress,c.faultDraw,Mux(image.faultTileValid,image.faultTile,0.U),nested,
      c.identity.context,c.identity.pass,c.identity.generation,Mux(frame.inputFaultValid,frame.input.faultAddress,0.U),
      Mux(frame.inputFaultValid,frame.input.taskId,0.U),0.U(32.W))
  }

}
