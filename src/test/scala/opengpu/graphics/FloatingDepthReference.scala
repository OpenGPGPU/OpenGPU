package opengpu.graphics

/** Exact-rational oracle for the floating attachment quantizer. Absolute sample
  * positions come from the raster reference, independently of RTL increments.
  */
private[graphics] object FloatingDepthReference {
  import Fp32ViewportReference.{E,decode,rational,calc,clamp}
  import FragmentInterpolationReference.{Sample,edge}
  def add(a: E,b: E): E={
    val(an,ad)=rational(a);val(bn,bd)=rational(b);val n=an*bd+bn*ad;val d=ad*bd
    if(n==0)return E(a.sig==0 && b.sig==0 && a.sign && b.sign)
    val magnitude=n.abs;var exponent=magnitude.bitLength-d.bitLength
    if(if(exponent>=0)magnitude<(d<<exponent)else(magnitude<<(-exponent))<d)exponent-=1
    val shift=23-exponent
    val significand=if(shift>=0)(magnitude<<shift)/d else magnitude/(d<<(-shift))
    E(n<0,exponent,significand)
  }
  def pack(v: E): BigInt={
    val sign=if(v.sign)BigInt(1)<<31 else BigInt(0)
    if(v.sig==0)sign
    else if(v.exponent>127)sign|BigInt("7f7fffff",16)
    else if(v.exponent>= -126)sign|(BigInt(v.exponent+127)<<23)|(v.sig&0x7fffff)
    else sign|(v.sig>>(-126-v.exponent))
  }
  private def less(a: E,b: E): Boolean={val(an,ad)=rational(a);val(bn,bd)=rational(b);an*bd<bn*ad}
  def at(edges: Vector[BigInt],area: BigInt,depths: Vector[BigInt],vertices: Int,bias: E=E()): BigInt={
    if(vertices==1)return depths.head
    val base=decode(depths.head)
    val terms=(1 to 2).map(i=>if(i>=vertices)E()else calc(edge(edges(i)),calc(decode(depths(i)),base,1),2))
    val residual=calc(calc(terms(0),terms(1),0),edge(area),3)
    val raw=add(base,residual)
    val active=depths.take(vertices).map(decode)
    val bounded=if(edges.forall(e=>e==0 || e.signum==area.signum)){
      val minimum=active.reduce((a,b)=>if(less(a,b))a else b);val maximum=active.reduce((a,b)=>if(less(a,b))b else a)
      if(less(raw,minimum))minimum else if(less(maximum,raw))maximum else raw
    }else raw
    clamp(pack(if(bias.sig==0)bounded else add(bounded,bias)))
  }
  def center(t: TriangleRasterReference.Triangle,kind: Int,p: Sample,mode: Int,g: GraphicsConfig,bias: E=E()): BigInt={
    val valid=FragmentInterpolationReference.lane(t.values,p).valid
    val location=if(p.mask!=0 && mode!=0 && !valid)CentroidRasterReference.sample(t,kind,p,mode,g)else p
    at(location.edges,location.area,t.values.depth,kind+1,bias)
  }
  def sample(t: TriangleRasterReference.Triangle,kind: Int,p: Sample,index: Int,mode: Int,g: GraphicsConfig,bias: E=E()): BigInt={
    val location=CentroidRasterReference.sample(t,kind,p.copy(mask=1<<index),mode,g)
    at(location.edges,location.area,t.values.depth,kind+1,bias)
  }
}
