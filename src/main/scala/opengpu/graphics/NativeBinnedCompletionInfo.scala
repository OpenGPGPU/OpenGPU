package opengpu.graphics

import chisel3._
import chisel3.util._

/** GNX1 accompanies one native result, including successful/cancelled jobs.
  * An empty slot or a non-native result reads as twelve zero words. These are
  * recorded owner fields, not a promise that every failure has an address.
  */
object NativeBinnedCompletionInfo {
  val magic=BigInt("474e5801",16)
  val base=0x180;val wordCount=12;val end=base+wordCount*4
  import NativeBinnedGraphicsCapabilities.Field
  val fields=Seq(Field("MAGIC",0,0,32,"GNX1"),Field("STATUS",1,0,3,"native status"),
    Field("READER_STAGE",1,3,2,"reader fault stage"),Field("FRAME_STAGE",1,5,2,"frame fault stage"),
    Field("GEOMETRY_STAGE",1,7,2,"geometry fault stage"),Field("TILE_VALID",1,9,1,"fault tile valid"),
    Field("GEOMETRY_DRAW_VALID",1,10,1,"geometry fault draw valid"),Field("SOURCE_VALID",1,11,1,"source fault snapshot valid"),
    Field("VIEWPORT_VALID",1,12,1,"viewport fault valid"),Field("CLIP_VALID",1,13,1,"clip fault valid"),
    Field("RECORDED_ADDRESS",2,0,32,"raw reader recorded address; validity depends on fault stage"),
    Field("READER_DRAW",3,0,32,"raw reader draw index"),Field("TILE",4,0,32,"fault tile; use TILE_VALID"))++
    Seq("FRAME","GEOMETRY","BIN_REPLAY","BIN_PRODUCER","BIN_REJECTION","VIEWPORT","CLIP","ASSEMBLY","VERTEX").zipWithIndex.map{
      case(n,i)=>Field(n+"_STATUS",5,i*3,3,"nested owner status")}++Seq(
    Field("CONTEXT",6,0,32,"job context"),Field("PASS",7,0,32,"job pass"),Field("GENERATION",8,0,32,"job generation"),
    Field("GEOMETRY_DRAW_LO",9,0,32,"fault draw low; use GEOMETRY_DRAW_VALID"),
    Field("GEOMETRY_DRAW_HI",10,0,32,"fault draw high; use GEOMETRY_DRAW_VALID"),Field("RESERVED",11,0,32,"zero"))
  def words(c: NativeBinnedGraphicsCompletion): Seq[UInt]={
    val frame=c.frame;val geometry=frame.source;val viewport=geometry.sourceFault
    val clip=viewport.source;val assembly=clip.source;val vertex=assembly.source
    val sourceValid=geometry.sourceFaultValid
    val flags=Cat(0.U(18.W),sourceValid && clip.clipFaultValid,sourceValid && viewport.viewportFaultValid,sourceValid,
      geometry.faultDrawValid,frame.faultTileValid,geometry.faultStage,frame.faultStage,c.faultStage,c.status)
    val statuses=Seq(frame.status,geometry.status,geometry.bins.status,geometry.bins.producer.status,
      geometry.bins.producer.binStatus)++Seq(viewport.status,clip.status,assembly.status,vertex.status).map(s=>Mux(sourceValid,s,0.U(3.W)))
    Seq(magic.U(32.W),flags,c.faultAddress,c.faultDraw,Mux(frame.faultTileValid,frame.faultTile,0.U),Cat(0.U(5.W),Cat(statuses.reverse)),
      c.identity.context,c.identity.pass,c.identity.generation,Mux(geometry.faultDrawValid,geometry.faultDraw(31,0),0.U),
      Mux(geometry.faultDrawValid,geometry.faultDraw(63,32),0.U),0.U(32.W))
  }
}
