package opengpu.graphics

import org.scalatest.flatspec.AnyFlatSpec

class NativeProductFrameInputsSpec extends AnyFlatSpec {
  behavior of "native product frame input images"

  it should "bind all recipe draws to the executable shaders and isolated engine scratch" in {
    for(scene<-NativeProductWorkloads.suite(32,32)){
      val input=NativeProductFrameInputs.encode(scene)
      assert(input.memory.readWord(input.jobBase)==BigInt("474e4a01",16))
      assert(input.job("job.drawCount")==scene.draws.size && input.job("job.drawStride")==2048)
      assert(input.memory.readWord(input.job("job.frameBase").toLong)==BigInt("474e4601",16))
      for((draw,i)<-scene.draws.zipWithIndex){
        val fields=input.draws(i);val state=input.states(i)
        assert(fields("draw.state")==i && fields("draw.vertex.geometry.vertex.attributeSlots")==3)
        assert(fields("draw.vertex.geometry.vertex.varyingMask")==255)
        assert(fields("draw.vertex.geometry.vertex.input.count")==draw.triangles.size*3)
        val pc=fields("draw.vertex.geometry.vertex.shaderPc").toLong
        assert(input.memory.readWord(pc)==BigInt("00241393",16))
        val fragment=NativeProductShaderPrograms.fragment(16,8,draw.textured)
        for((w,n)<-fragment.zipWithIndex)assert(input.memory.readWord(state("fragment.shaderPc").toLong+4*n)==w)
        assert(state("tests.depthEnable")== (if(draw.depthTest)1 else 0))
        assert(state("tests.depthWrite")== (if(draw.depthWrite)1 else 0))
        assert(state("blend.enable")== (if(draw.blend)1 else 0))
        assert(state("blend.srcRGB")==4 && state("blend.dstRGB")==5 && state("blend.srcAlpha")==1 && state("blend.dstAlpha")==5)
        assert(state("fragment.resources.version")== (if(draw.textured)1 else 0))
        val scratch=(0 until 4).map(e=>state(if(e==0)"fragment.scratch.base"else s"scratch[${e-1}].base"))
        assert(scratch.distinct.size==4)
        for(a<-scratch;b<-scratch if a!=b)assert((a-b).abs>=10240)
        for(e<-0 until 4){val span=input.frame(s"frame.scratch[$e].base")
          assert(scratch(e)==span+i*16384 && scratch(e)+10240<=input.frame(s"frame.scratch[$e].limit"))}
      }
      val tex=0x09020000L+60
      for((w,i)<-NativeProductWorkloads.textureRgba8.zipWithIndex)
        assert(input.memory.readWord(tex+i*4)==BigInt(w.toLong&0xffffffffL))
    }
  }

  it should "retain clear-start RGBA8 D24S8 storage pitched guards and only the required 4x resolve" in {
    for(scene<-NativeProductWorkloads.suite(17,19)){
      val input=NativeProductFrameInputs.encode(scene);val f=input.frame
      assert(f("frame.image.pixels.depth.format")==4 && f("frame.image.pixels.depth.clearDepth")==BigInt(0x3f800000))
      assert(f("frame.image.pixels.colors.colors[0].loadOp")==1 && f("frame.image.pixels.depth.loadOp")==1)
      assert(f("frame.image.pixels.colors.colors[0].offset")==1024*scene.samples)
      assert(f.getOrElse("frame.image.resolves[0].enable",BigInt(0))==(if(scene.samples==4)1 else 0))
      assert(!f.keys.exists(_.startsWith("frame.image.resolves[4].")))
      assert(input.planes.filterNot(_.resolve).map(_.location)==Vector(0,4))
      for(p<-input.planes){
        assert(p.rowPitch==17*p.samples*4+7)
        assert(input.memory.readByte(p.allocationBase)==0xa7 && input.memory.readByte(p.base)==0xa7)
        assert(input.memory.readByte(p.limit)==0xa7 && input.memory.readByte(p.allocationLimit-1)==0xa7)
      }
    }
  }

  it should "verify complete images and reject corrupted samples resolves and guard bytes" in {
    val scene=NativeProductWorkloads.scene("msaa-4x",16,16)
    val input=NativeProductFrameInputs.encode(scene)
    val oracle=new NativeProductFrameReference(scene)
    for(p<-input.planes if !p.resolve || p.location==0;y<-0 until 16;x<-0 until 16;s<-0 until p.samples){
      val value=if(p.resolve)oracle.resolvedColor(x,y)else if(p.location==0)oracle.storedColor(x,y,s)else oracle.storedDepthStencil(x,y,s)
      input.memory.writeWord(p.address(x,y,s),value)
    }
    NativeProductFrameInputs.verify(scene,input)
    val color=input.planes.find(p=>p.location==0 && !p.resolve).get
    val resolved=input.planes.find(p=>p.location==0 && p.resolve).get
    val depth=input.planes.find(p=>p.location==4 && !p.resolve).get
    val unused=input.planes.find(p=>p.location==4 && p.resolve).get
    for(address<-Vector(color.address(7,8,3),depth.address(9,10,2),resolved.address(5,6),
      color.allocationBase,color.limit,unused.base)){
      val original=input.memory.readByte(address)
      input.memory.writeByte(address,original^1)
      intercept[AssertionError]{NativeProductFrameInputs.verify(scene,input)}
      input.memory.writeByte(address,original)
    }
    NativeProductFrameInputs.verify(scene,input)
  }
}
