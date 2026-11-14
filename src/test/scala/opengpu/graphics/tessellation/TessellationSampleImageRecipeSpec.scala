package opengpu.graphics.tessellation

import opengpu.graphics._
import org.scalatest.flatspec.AnyFlatSpec

private[tessellation] object TessellationSampleImageRecipe {
  // VFUNARY0, vm=1, vs1=6: the existing decoder/ALU's unsigned RTZ form.
  def floorToUnsigned(destination:Int,source:Int):BigInt=
    (BigInt(0x12)<<26)|(BigInt(1)<<25)|(BigInt(source)<<20)|
      (BigInt(6)<<15)|(BigInt(1)<<12)|(BigInt(destination)<<7)|0x57
  def rwAddress(x:Int,y:Int,sample:Int):BigInt={
    require(x>=0 && x<4 && y>=0 && y<4 && sample>=0 && sample<4)
    BigInt(0x71003f)+(4*sample+y)*128+x*16
  }
}

/** Data-only checks: no Module, simulator, compiler, or returned DUT data. */
class TessellationSampleImageRecipeSpec extends AnyFlatSpec {
  behavior of "The fixed tessellation image-op recipe"

  it should "encode unsigned RTZ coordinates with the existing vector unary ISA" in {
    for((destination,source)<-Seq(14->8,15->9)){
      val instruction=TessellationSampleImageRecipe.floorToUnsigned(destination,source)
      assert((instruction&0x7f)==0x57)
      assert(((instruction>>26)&63)==0x12 && ((instruction>>25)&1)==1)
      assert(((instruction>>12)&7)==1 && ((instruction>>15)&31)==6)
      assert(((instruction>>7)&31)==destination && ((instruction>>20)&31)==source)
    }
  }

  it should "map the independent 1x 2x 4x coverage and every helper pixel to disjoint texels" in {
    val g=GraphicsConfig(screenWidth=4,screenHeight=4)
    val unit=BigInt(1)<<g.subPixelBits
    val coords=Vector((2*unit,3*unit/2),(4*unit,3*unit/2),(2*unit,3*unit/2))
    for(mode<-0 to 2){
      val count=1<<mode
      val covered=PointLineReference.samples(coords,1,TriangleRasterReference.Settings(width=4,height=4,mode=mode),
        g,PointLineReference.Sizes(line=1)).flatten
        .flatMap(p=>(0 until count).filter(s=>(p.mask&(1<<s))!=0).map(s=>(p.x,p.y,s))).toSet
      assert(covered.nonEmpty)
      // The perturbed diamond includes the left edge pixel in 1x mode.
      // Do not discard it or infer the covered set from returned colors.
      if(mode==0)assert(covered((1,1,0)))
      val pixels=(for(s<-0 until count;y<-0 until 4;x<-0 until 4)yield(x,y,s)).toVector
      val addresses=pixels.map{case(x,y,s)=>
        val ox=if(mode==0)0.5f else if((s&1)!=0)0.75f else 0.25f
        val oy=if(mode==0)0.5f else if((s&(if(mode==1)1 else 2))!=0)0.75f else 0.25f
        // All legal fragment coordinates are finite, nonnegative, and below
        // four; unsigned RTZ therefore equals floor without saturation.
        assert((x.toFloat+ox).toInt==x && (y.toFloat+oy).toInt==y)
        val address=TessellationSampleImageRecipe.rwAddress(x,y,s)
        assert(address>=0x710000 && address+16<=0x711000)
        address
      }
      assert(addresses.distinct.size==pixels.size)
      val written=covered.toVector.flatMap{case(x,y,s)=>
        (0 until 16).map(b=>TessellationSampleImageRecipe.rwAddress(x,y,s)+b)}.toSet
      assert(written.size==covered.size*16)
      for((x,y,s)<-pixels if !covered((x,y,s));b<-0 until 16)
        assert(!written(TessellationSampleImageRecipe.rwAddress(x,y,s)+b))
      assert((0 until count).forall(s=> !covered((0,0,s))))
      assert((0 until count).forall(s=> !covered((0,1,s)))) // helper row/column witnesses
    }
  }
}
