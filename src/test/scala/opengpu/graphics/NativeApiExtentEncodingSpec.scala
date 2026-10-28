package opengpu.graphics

import org.scalatest.flatspec.AnyFlatSpec

/** CPU encoding checks; no rasterization, shader execution or large image walk. */
class NativeApiExtentEncodingSpec extends AnyFlatSpec {
  import NativeProductWorkloads._
  private def scene(width: Int=2048,height: Int=2048,samples: Int=1): Scene = {
    val t=Triangle(Vertex(0,0,0.5f,1),Vertex(0.125f,0,0.5f,1),Vertex(0,0.125f,0.5f,1))
    Scene("native-api-extent",width,height,samples,Vector(Draw(Vector(t),Vector(1f,0f,0f,1f))))
  }

  it should "encode every tile-count bit without changing native record sizes" in {
    val in=NativeProductFrameInputs.encodeApiExtent(scene());val frame=in.job("job.frameBase").toLong
    assert(in.frame("frame.version")==2 && in.frame("frame.bins.bins.tileCount")==65536)
    assert(in.frame("frame.bins.producer.tileLog2")==3)
    assert(in.memory.readWord(frame+55*4)==65536)
    assert(in.memory.readWord(frame+8)==1) // Record envelope remains v1; frame payload is v2.
    assert(in.memory.readWord(frame+4)==NativeBinnedGraphicsLayout.frameBytes)
    assert(in.job("job.drawStride")==NativeBinnedGraphicsLayout.drawBytes)
    assert(in.frame("frame.bins.bins.directoryLimit")-in.frame("frame.bins.bins.directoryBase")==4*1024*1024)
    assert(in.frame("frame.bins.bins.pool.limit")-in.frame("frame.bins.bins.pool.base")>=BigInt(65536)*4096)
  }

  it should "keep all source resolve arena and directory allocations away from shared memory" in {
    for(samples<-Seq(1,4)){
      val in=NativeProductFrameInputs.encodeApiExtent(scene(samples=samples))
      for(r<-in.regions){
        assert(r.limit<=0x10000000L || r.base>=0x10020000L)
        assert(in.containsLine(r.base) && in.containsLine(r.limit-64))
      }
      for((a,i)<-in.regions.zipWithIndex;b<-in.regions.drop(i+1))
        assert(a.limit<=b.base || b.limit<=a.base)
      for(p<-in.planes){
        assert(p.width==2048 && p.height==2048)
        assert(p.address(2047,2047,p.samples-1)+4<=p.limit)
      }
      assert(in.frame("frame.image.pixels.colors.reservedBytes")==256*samples)
    }
  }

  it should "reject dimensions beyond the admitted API maximum before encoding" in {
    intercept[IllegalArgumentException]{NativeProductFrameInputs.encodeApiExtent(scene(width=2049))}
    intercept[IllegalArgumentException]{NativeProductFrameInputs.encodeApiExtent(scene(height=2049))}
    for((w,h)<-Seq((2047,2048),(2048,2047),(2048,16),(16,2048))){
      val in=NativeProductFrameInputs.encodeApiExtent(scene(w,h))
      assert(in.frame("frame.bins.bins.tileCount")==((w+7)/8)*((h+7)/8))
    }
  }
}
