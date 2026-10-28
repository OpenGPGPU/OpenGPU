package opengpu.graphics

import org.scalatest.flatspec.AnyFlatSpec

/** CPU-only oracle/input checks; inspect a few extreme coordinates, not a rendered frame. */
class NativeApiBoundaryFrameSpec extends AnyFlatSpec {
  it should "address the final tile and keep the upper-edge scissor exclusive" in {
    for(samples<-Seq(1,4)){
      val f=NativeApiBoundaryFrame(samples);val in=NativeProductFrameInputs.encodeApiExtent(f.scene,f.scissors)
      assert(in.frame("frame.bins.bins.tileCount")==65536)
      val d=in.draws.head
      assert(d("draw.vertex.viewport.extentX")==2048 && d("draw.vertex.viewport.extentY")==2048)
      assert(d("draw.raster.scissorEnable")==1 && d("draw.raster.scissorMinX")==2047 && d("draw.raster.scissorMinY")==2047)
      assert(d("draw.raster.scissorMaxX")==2048 && d("draw.raster.scissorMaxY")==2048)
      val oracle=NativeProductFrameReference.apiExtent(f.scene,f.scissors)
      for(s<-0 until samples){
        assert(oracle.storedColor(2047,2047,s)==BigInt("ff0000ff",16))
        assert(oracle.storedDepthStencil(2047,2047,s)==0xbfffff) // RNE(((1+0.5)/2)*(2^24-1)).
        for((x,y)<-Seq((0,0),(2046,2046),(2046,2047),(2047,2046))){
          assert(oracle.storedColor(x,y,s)==0 && oracle.storedDepthStencil(x,y,s)==0xffffff)
        }
      }
      intercept[IllegalArgumentException]{oracle.pixel(2048,2047)}
      intercept[IllegalArgumentException]{oracle.pixel(2047,2048)}
    }
  }

  it should "distinguish the scissor from the geometry and reject mismatched bounds" in {
    val f=NativeApiBoundaryFrame();val all=NativeProductFrameReference.apiExtent(f.scene)
    assert(all.storedColor(2046,2046,0)==BigInt("ff0000ff",16))
    intercept[IllegalArgumentException]{NativeProductFrameInputs.encodeApiExtent(f.scene,Map(1->f.scissors(0)))}
    val invalid=Map(0->NativeProductFrameReference.Scissor(2047,2047,2049,2048))
    intercept[IllegalArgumentException]{NativeProductFrameInputs.encodeApiExtent(f.scene,invalid)}
    intercept[IllegalArgumentException]{NativeProductFrameReference.apiExtent(f.scene,invalid)}
  }

  it should "check the complete stored image and guards independently on a bounded API-format frame" in {
    val f=NativeApiBoundaryFrame();val scene=f.scene.copy(width=16,height=16,draws=f.scene.draws.map(_.copy(
      triangles=Vector(NativeProductWorkloads.Triangle(
        NativeProductWorkloads.Vertex(-1,-1,0.5f,1),NativeProductWorkloads.Vertex(1,-1,0.5f,1),
        NativeProductWorkloads.Vertex(-1,1,0.5f,1))))))
    val scissors=Map(0->NativeProductFrameReference.Scissor(0,0,1,1))
    val in=NativeProductFrameInputs.encodeApiExtent(scene,scissors);val oracle=NativeProductFrameReference.apiExtent(scene,scissors)
    for(p<-in.planes if !p.resolve; y<-0 until 16;x<-0 until 16){
      val v=if(p.location==0)oracle.storedColor(x,y,0)else oracle.storedDepthStencil(x,y,0)
      in.memory.writeWord(p.address(x,y),v)
    }
    NativeProductFrameInputs.verifyApiExtent(scene,in,scissors)
    val color=in.planes.find(p=>p.location==0 && !p.resolve).get
    for(a<-Seq(color.address(1,1),color.address(0,0),color.allocationBase,color.limit)){
      val old=in.memory.readByte(a);in.memory.writeByte(a,old^1)
      intercept[AssertionError]{NativeProductFrameInputs.verifyApiExtent(scene,in,scissors)}
      in.memory.writeByte(a,old)
    }
  }
}
