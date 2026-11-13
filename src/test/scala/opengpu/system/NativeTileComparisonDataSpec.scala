package opengpu.system

import opengpu.graphics.{NativeProductFrameInputs,NativeProductFrameReference,NativeProductWorkloads}
import org.scalatest.flatspec.AnyFlatSpec
import java.nio.file.Files
import opengpu.testutil.NativeFrameMemoryCodec

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

  it should "verify all owner CLI images and reject changed pixels, read-only bytes and tiling" in {
    val directory=Files.createTempDirectory("owner-image-oracle-")
    val previous=sys.props.get("opengpu.fullHostQualification")
    sys.props("opengpu.fullHostQualification")="1"
    try {
      for(log2<-Seq(3,4)) {
        // Synthetic oracle fixtures exercise the verifier only. They are never
        // submitted as GPU inputs or reported as DUT execution evidence.
        for(scene<-NativeProductWorkloads.suite(32,32)) {
          val input=NativeProductFrameInputs.encodeApiExtentTiled(scene,log2)
          val reference=NativeProductFrameReference.apiExtent(scene)
          val memory=input.memory.snapshot()
          for(p<-input.planes if !p.resolve || p.location==0 && scene.samples==4;
              y<-0 until scene.height;x<-0 until scene.width;s<-0 until p.samples) {
            val value=if(p.resolve)reference.resolvedColor(x,y)
              else if(p.location==0)reference.storedColor(x,y,s) else reference.storedDepthStencil(x,y,s)
            memory.writeWord(p.address(x,y,s),value)
          }
          NativeFrameMemoryCodec.write(memory,directory.resolve(scene.id+".memory"))
        }
        val args=Array("--smoke","--output",directory.toString,"--tile-log2",log2.toString)
        VerifyNativeOwnerProduct.main(args)
        intercept[IllegalArgumentException] {
          VerifyNativeOwnerProduct.main(args.updated(4,(7-log2).toString))
        }
        val scene=NativeProductWorkloads.suite(32,32).head
        val input=NativeProductFrameInputs.encodeApiExtentTiled(scene,log2)
        val path=directory.resolve(scene.id+".memory")
        val original=NativeFrameMemoryCodec.read(path)
        val pixel=input.planes.find(p=>p.location==0 && !p.resolve).get.address(0,0,0)
        for(address<-Seq(pixel,input.jobBase)) {
          val broken=original.snapshot();broken.writeByte(address,broken.readByte(address)^1)
          NativeFrameMemoryCodec.write(broken,path)
          if(address==pixel)intercept[AssertionError](VerifyNativeOwnerProduct.main(args))
          else intercept[IllegalArgumentException](VerifyNativeOwnerProduct.main(args))
        }
      }
    } finally {
      previous match {case Some(value)=>sys.props("opengpu.fullHostQualification")=value
        case None=>sys.props.remove("opengpu.fullHostQualification")}
      val files=Files.list(directory)
      try files.forEach(path=>Files.deleteIfExists(path)) finally files.close()
      Files.deleteIfExists(directory)
    }
  }
}
