package opengpu.graphics

/** Absolute-position oracle, independent of the RTL's center-edge increments. */
private[graphics] object CentroidRasterReference {
  import TriangleRasterReference.{Triangle,Quad}
  import FragmentInterpolationReference.{Sample,lane}
  def sample(t: Triangle,kind: Int,p: Sample,mode: Int,g: GraphicsConfig): Sample={
    if(mode==0 || p.mask==0 || kind==0)return p
    val index=Integer.numberOfTrailingZeros(p.mask);val unit=BigInt(1)<<g.subPixelBits
    val row=if(mode==1)index else index>>1
    val x=p.x*unit+unit/2+(if((index&1)==0)-unit/4 else unit/4)
    val y=p.y*unit+unit/2+(if((row&1)==0)-unit/4 else unit/4)
    val edges=if(kind==2)Vector.tabulate(3)(TriangleRasterReference.edgeAt(t,_,x,y))else {
      val a=t.coords(0);val b=t.coords(1);val dx=b._1-a._1;val dy=b._2-a._2
      val length=dx*dx+dy*dy;val dot=(x-a._1)*dx+(y-a._2)*dy
      Vector(length-dot,dot,BigInt(0))
    }
    p.copy(edges=edges,area=edges.sum)
  }
  def apply(t: Triangle,kind: Int,q: Quad,mode: Int,mask: BigInt,g: GraphicsConfig): Quad={
    q.copy(lanes=q.samples.zip(q.lanes).map{case(p,center)=>
      val selected=lane(t.values,sample(t,kind,p,mode,g))
      val fallback=p.mask!=0 && mode!=0 && !center.valid
      val values=center.values.indices.map{c=>
        if(!t.values.flat.testBit(c) && (fallback || mask.testBit(c)))selected.values(c)else center.values(c)
      }.toVector
      if(fallback)center.copy(reciprocal=selected.reciprocal,depth=selected.depth,values=values,valid=selected.valid)
      else center.copy(values=values)
    })
  }
}
