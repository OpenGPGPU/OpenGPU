package opengpu.system

import java.nio.{ByteBuffer,ByteOrder}
import java.nio.file.Files
import opengpu.config.ComputeResourceProfile
import opengpu.graphics.{NativeBinnedGraphicsCapabilities,NativeProductFrameInputs,TileBinHostRegs}
import org.scalatest.flatspec.AnyFlatSpec

/** Checks the actual sparse fixture encoding without rendering a large frame. */
class CompiledNativeApiFixturesSpec extends AnyFlatSpec {
  it should "encode both bounded and product commands with current discovery words" in {
    val api=CompiledNativeApiFixtures
    for(product<-Seq(false,true); (frame,index)<-api.frames(product).zipWithIndex){
      val input=NativeProductFrameInputs.encodeApiExtent(frame.scene,frame.scissors)
      val path=Files.createTempFile("opengpu-api-commands-", ".bin")
      try {
        api.writeCommands(path,frame.scene,input,index+1,product)
        val bytes=ByteBuffer.wrap(Files.readAllBytes(path)).order(ByteOrder.LITTLE_ENDIAN)
        val magic=new Array[Byte](8);bytes.get(magic)
        assert(new String(magic,"US-ASCII")=="OGAPI001")
        val extent=if(product)2048 else 32
        assert(bytes.getInt()==extent && bytes.getInt()==extent)
        assert(bytes.getInt()==(if(index==0)1 else 4) && bytes.getInt()==index+1)
        assert(bytes.getLong()==(if(product)1000000000L else 10000000L))
        assert(bytes.getInt()==24)
        val resources=Vector.fill(24)((bytes.getInt(),bytes.getInt().toLong & 0xffffffffL))
        val compute=ComputeResourceCapabilities.words(ComputeResourceProfile(api.gpu,4,32)).map(_.toLong)
        assert(compute(2)==15L)
        assert(resources.take(16)==compute.zipWithIndex.map{case(v,i)=>(0x360+4*i,v)})
        val native=NativeBinnedGraphicsCapabilities.words(api.native,4,api.display).map(_.toLong)
        assert(resources.drop(16)==native.zipWithIndex.map{case(v,i)=>(TileBinHostRegs.CAPABILITIES+4*i,v)})
        assert(native(5)==0x08000800L)
      } finally Files.deleteIfExists(path)
    }
  }
}
