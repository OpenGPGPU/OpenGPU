package opengpu.system

import opengpu.graphics.{NativeProductFrameInputs,NativeProductFrameReference,NativeProductWorkloads}
import org.scalatest.flatspec.AnyFlatSpec

/** Oracle tests only; these synthetic images are never GPU execution evidence. */
class NativeTileComparisonDataSpec extends AnyFlatSpec {
  it should "check independent partial-tile samples and retain every read-only input" in {
    val scene=NativeProductWorkloads.scene("msaa-4x",17,19)
    val oracle=new NativeProductFrameReference(scene)
    for(log2<-Seq(3,4)) {
      val input=NativeProductFrameInputs.encodeApiExtentTiled(scene,log2)
      val actual=input.memory.snapshot()
      for(p<-input.planes if !p.resolve || p.location==0;
          y<-0 until scene.height;x<-0 until scene.width;s<-0 until p.samples) {
        val value=if(p.resolve)oracle.resolvedColor(x,y)
          else if(p.location==0)oracle.storedColor(x,y,s) else oracle.storedDepthStencil(x,y,s)
        actual.writeWord(p.address(x,y,s),value)
      }
      VerifyNativeTileComparison.verify(scene,input,actual)
      for(address<-Seq(input.jobBase,input.job("job.frameBase").toLong,
          input.job("job.drawBase").toLong,0x01200000L,0x01400000L,0x01800000L,0x0902003cL,0x70000000L)) {
        val broken=actual.snapshot();broken.writeByte(address,broken.readByte(address)^1)
        intercept[IllegalArgumentException](VerifyNativeTileComparison.verify(scene,input,broken))
      }
      val plane=input.planes.find(p=>p.location==0 && !p.resolve).get
      for(address<-Seq(plane.address(16,18,3),plane.allocationBase,plane.limit)) {
        val broken=actual.snapshot();broken.writeByte(address,broken.readByte(address)^1)
        intercept[AssertionError](VerifyNativeTileComparison.verify(scene,input,broken))
      }
    }
  }
}
