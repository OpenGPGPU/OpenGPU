package opengpu.graphics

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.util.Random

private[graphics] object PointLineReference {
  import TriangleRasterReference.{Settings,Triangle,Quad}
  import FragmentInterpolationReference.{Sample,Lane,lane}
  import Fp32ViewportReference._
  def zero(data: Data): Unit=data match {
    case r: Record=>r.elements.values.foreach(zero);case v: Vec[_]=>v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U);case _=>()
  }
  case class Sizes(point: Double=1,line: Double=1)
  def size(x: Double): Double=math.max(1,math.min(64,x.toFloat.toDouble))
  def fixed(x: Double,g: GraphicsConfig): BigInt=BigInt(math.rint(size(x)*(1L<<g.subPixelBits)).toLong)
  private case class Q(n: BigInt,d: BigInt){def <(b: Q): Boolean=n*b.d<b.n*d}
  private def fraction(n: BigInt,d: BigInt): Q=if(d<0)Q(-n,-d)else Q(n,d)
  /** Arbitrary precision intersection after a perturbation far below the
    * separation possible for bounded integer subpixel endpoints. This oracle
    * uses scaled coordinates, independently of the RTL's symbolic terms.
    */
  def diamond(a: (BigInt,BigInt),b: (BigInt,BigInt),center: (BigInt,BigInt),half: BigInt): Boolean={
    val scale=BigInt(1)<<128;val epsilon=BigInt(1)<<64
    val ax=a._1*scale-epsilon;val ay=a._2*scale-1;val bx=b._1*scale-epsilon;val by=b._2*scale-1
    val axes=Vector((ax+ay,bx+by,(center._1+center._2)*scale),(ax-ay,bx-by,(center._1-center._2)*scale))
    var lower=Q(0,1);var upper=Q(1,1);var inside=true
    for((a,b,c)<-axes){val lo=c-half*scale;val hi=c+half*scale;inside &&= b>lo && b<hi
      if(a==b){if(a<=lo || a>=hi)return false}
      else {val first=fraction(lo-a,b-a);val last=fraction(hi-a,b-a)
        val l=if(b>a)first else last;val h=if(b>a)last else first
        if(lower<l)lower=l;if(h<upper)upper=h}}
    !inside && lower<upper
  }
  def samples(coords: Vector[(BigInt,BigInt)],kind: Int,s: Settings,g: GraphicsConfig,z: Sizes=Sizes()): Vector[Vector[Sample]]={
    val unit=BigInt(1)<<g.subPixelBits;val half=unit/2;val quarter=unit/4
    val width=if(s.width==0 || s.width>g.screenWidth)g.screenWidth else s.width
    val height=if(s.height==0 || s.height>g.screenHeight)g.screenHeight else s.height
    val a=coords(0);val b=coords(if(kind==0)0 else 1);val dx=b._1-a._1;val dy=b._2-a._2;val length=dx*dx+dy*dy
    val point=fixed(z.point,g);val line=fixed(z.line,g);val wide=math.rint(size(z.line)).toInt
    val offsets=s.mode match{case 0=>Vector((0,0));case 1=>Vector((-1,-1),(1,1));case _=>Vector((-1,-1),(1,-1),(-1,1),(1,1))}
    def pixel(x: Int,y: Int): Sample={val center=(BigInt(x)*unit+half,BigInt(y)*unit+half)
      val parameter=(center._1-a._1)*dx+(center._2-a._2)*dy
      val clip=x>=0 && x<width && y>=0 && y<height && (!s.scissor || (x>=s.minX && x<s.maxX && y>=s.minY && y<s.maxY))
      var mask=0
      if(clip)for(((ox,oy),i)<-offsets.zipWithIndex){val q=(center._1+ox*quarter,center._2+oy*quarter);val rx=q._1-a._1;val ry=q._2-a._2
        val cross=rx*dy-ry*dx;val dot=rx*dx+ry*dy
        val hit=if(kind==0)2*rx>= -point && 2*rx<point && 2*ry>= -point && 2*ry<point
          else if(length==0)false
          else if(s.mode!=0)dot>=0 && dot<length && 4*cross*cross<line*line*length
          else (0 until wide).exists{n=>val t=(2*n-(wide-1))*half
            diamond(a,b,if(dx.abs>=dy.abs)(center._1,center._2-t)else(center._1-t,center._2),half)}
        if(hit)mask|=1<<i}
      Sample(if(kind==0)Vector(BigInt(1),BigInt(0),BigInt(0))else Vector(length-parameter,parameter,BigInt(0)),if(kind==0)1 else length,mask,x,y)
    }
    (for(y<-0 until height by 2;x<-0 until width by 2)yield Vector.tabulate(4)(i=>pixel(x+(i&1),y+(i>>1)))).filter(_.exists(_.mask!=0)).toVector
  }
  def raster(t: Triangle,kind: Int,s: Settings,g: GraphicsConfig,z: Sizes=Sizes()): Vector[Quad]={
    val data=t.values.copy(inv=t.values.inv.take(kind+1).padTo(3,E()),depth=t.values.depth.take(kind+1).padTo(3,BigInt(0)),
      values=t.values.values.take(kind+1).padTo(3,Vector.fill(t.values.values.head.size)(BigInt(0))))
    samples(t.coords,kind,s,g,z).map { ss=>val lanes=ss.map(p=>if(kind==0)Lane(data.inv.head,data.depth.head,data.values.head.take(4*data.slots),true)else lane(data,p))
      Quad(ss.head.x,ss.head.y,true,ss,lanes)}
  }
  def exactPointCoord(local: (E,E),origin: (Int,Int),x: Int,y: Int,z: Sizes): Vector[BigInt]={
    Vector((local._1,origin._1,x,false),(local._2,origin._2,y,true)).map{case(v,o,p,invert)=>
      val(n,d)=rational(v);val numerator=(BigInt(2)*p+1-BigInt(2)*o)*d-2*n
      val difference=normalize(if(invert)-numerator else numerator,2*d,negativeZero=false)
      pack(calc(calc(difference,decode(fp(size(z.point))),3),decode(fp(0.5)),0))}
  }
  def pointCoord(coord: (BigInt,BigInt),x: Int,y: Int,z: Sizes,g: GraphicsConfig): Vector[BigInt]={
    Vector((BigInt(x)*(BigInt(1)<<g.subPixelBits)+(BigInt(1)<<(g.subPixelBits-1))-coord._1),
      coord._2-(BigInt(y)*(BigInt(1)<<g.subPixelBits)+(BigInt(1)<<(g.subPixelBits-1)))).map{d=>
      val delta=FragmentInterpolationReference.edge(d);val scaled=delta.copy(exponent=delta.exponent-g.subPixelBits)
      pack(calc(calc(scaled,decode(fp(size(z.point))),3),decode(fp(0.5)),0))}
  }
}
class PointLineRasterizerSpec extends AnyFlatSpec {
  import PointLineReference._
  import TriangleRasterReference.{Settings,base}
  import Fp32ViewportReference.fp
  private val g=GraphicsConfig(screenWidth=8,screenHeight=8)
  private class Driver(val d: PointLineRasterizer,geometry: GraphicsConfig=g){
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B);var cycle=0;var accepted=0;var held: Option[BigInt]=None
    var expected=Vector.empty[Vector[FragmentInterpolationReference.Sample]];var hold=false
    def tick(): Unit={d.io.out.ready.poke((!hold && cycle%5!=0).B)
      if(d.io.out.valid.peek().litToBoolean){val value=d.io.out.bits.peek().litValue;held.foreach(v=>assert(v==value))
        if(d.io.out.ready.peek().litToBoolean){assert(accepted<expected.size)
          for((o,e)<-d.io.out.bits.lanes.zip(expected(accepted))){o.x.expect(e.x.S);o.y.expect(e.y.S);o.coverageMask.expect(e.mask.U);o.covered.expect((e.mask!=0).B)
            o.area.expect(e.area.S);o.e0.expect(e.edges(0).S);o.e1.expect(e.edges(1).S);o.e2.expect(e.edges(2).S)}
          accepted+=1;held=None}else held=Some(value)
      }else assert(held.isEmpty)
      d.clock.step();cycle+=1}
    def until(p: => Boolean): Unit={var n=0;while(!p && n<30000){tick();n+=1};assert(p,s"point/line coverage stalled cycle=$cycle outputs=$accepted")}
    def send(coords: Vector[(BigInt,BigInt)],kind: Int,s: Settings=Settings(),z: Sizes=Sizes()): Unit={
      accepted=0;expected=samples(coords,kind,s,geometry,z);val q=d.io.in.bits;zero(q)
      q.point.poke((kind==0).B);q.x0.poke(coords(0)._1.S);q.y0.poke(coords(0)._2.S);q.x1.poke(coords.last._1.S);q.y1.poke(coords.last._2.S)
      TriangleRasterReference.state(q.state,s);q.state.pointSize.poke(fp(z.point).U);q.state.lineWidth.poke(fp(z.line).U)
      d.io.in.valid.poke(true.B);d.io.in.ready.expect(true.B);tick();d.io.in.valid.poke(false.B)
    }
    def finish(cancel: Boolean=false): Unit={until(!d.io.busy.peek().litToBoolean);d.io.fault.expect(false.B);if(!cancel)assert(accepted==expected.size)}
    def run(coords: Vector[(BigInt,BigInt)],kind: Int,s: Settings=Settings(),z: Sizes=Sizes()): Unit={send(coords,kind,s,z);finish()}
  }
  it should "match independently perturbed diamond exit and multisample rectangles across directions widths endpoints and scissors" in {
    simulate(new PointLineRasterizer(g)){d=>val t=new Driver(d);val r=new Random(0x1ee7);var draws=0
      val endpoints=Vector((0,0),(128,128),(256,256),(1920,384),(384,1920),(2048,2048),(128,1920),(1920,128))
      for(a<-endpoints.take(5);b<-endpoints if a!=b){t.run(Vector(a,b).map{case(x,y)=>(BigInt(x),BigInt(y))},1);draws+=1}
      for(mode<-0 to 2;i<-0 until 20){val c=Vector.fill(2)((BigInt(r.nextInt(12*256)-2*256),BigInt(r.nextInt(12*256)-2*256)))
        t.run(c,1,Settings(mode=mode,scissor=i%3==0,minX=1,minY=2,maxX=6,maxY=7),Sizes(line=Vector(1.0,1.4999999,1.5,2.0,2.5,3.0,5.25)(i%7)));draws+=1}
      val a=(BigInt(128),BigInt(128));val b=(BigInt(896),BigInt(640));val c=(BigInt(1920),BigInt(1408))
      t.run(Vector(a,b),1);val first=t.expected.flatten.filter(_.mask!=0).map(p=>(p.x,p.y)).toSet
      t.run(Vector(b,c),1);val second=t.expected.flatten.filter(_.mask!=0).map(p=>(p.x,p.y)).toSet
      assert((first&second).isEmpty && !first((3,2)) && second((3,2)))
      t.run(Vector((BigInt(1024),BigInt(1024)),(BigInt(1024),BigInt(1024))),1)
      info(s"POINT_LINE_COVERAGE lineDraws=$draws modes=3 directions=8 exactDiamondPerturbation=true halfOpenEndpoints=true connectedEndpointOnce=true wideAliased=true geometricMSAA=true centerWeights=true scissor=true zeroLength=true")
    }
  }
  it should "cover fractional sized points at every sample mode and retain offered quads through cancellation" in {
    simulate(new PointLineRasterizer(g)){d=>val t=new Driver(d);var draws=0
      for(mode<-0 to 2;size<-Seq(0.25,1.0,1.5,2.0,3.25,64.0,128.0,Double.PositiveInfinity);x<-Seq(-64,0,128,511,2048)){
        t.run(Vector((BigInt(x),BigInt(513))),0,Settings(mode=mode,cull=2),Sizes(point=size));draws+=1}
      t.hold=true;t.send(Vector((BigInt(256),BigInt(256)),(BigInt(1792),BigInt(1792))),1,Settings(mode=2))
      t.until(d.io.out.valid.peek().litToBoolean);d.io.cancel.poke(true.B);for(_<-0 until 7)t.tick();t.hold=false;t.finish(cancel=true);d.io.cancel.poke(false.B)
      t.run(Vector((BigInt(128),BigInt(128))),0)
      info(s"POINT_LINE_COVERAGE pointDraws=$draws fractionalSize=true sizeClamp1To64=true modes=3 cullIgnored=true partialSamples=true helpers=true heldQuadCancel=true healthyReuse=true")
    }
  }
  for(bits<-Seq(2,4,8,16))
  it should s"retain extreme coordinate differences and exact weights before narrowing subpixelBits=$bits" in {
    val geometry=g.copy(subPixelBits=bits)
    simulate(new PointLineRasterizer(geometry)){d=>val t=new Driver(d,geometry)
      val unit=BigInt(1)<<bits;val middle=2*unit+unit/2
      val minimum= -(BigInt(1)<<31);val maximum=(BigInt(1)<<31)-1
      val pairs=Vector(
        Vector((minimum,middle),(4*unit,middle)),Vector((4*unit,middle),(minimum,middle)),
        Vector((middle,minimum),(middle,4*unit)),Vector((middle,4*unit),(middle,minimum)),
        Vector((-(BigInt(1)<<30),-(BigInt(1)<<30)),(4*unit,4*unit)),
        Vector((maximum,middle),(unit,middle)))
      for(mode<-0 to 2;coords<-pairs)t.run(coords,1,Settings(mode=mode))
      t.run(pairs.head,1,Settings(mode=2,scissor=true,minX=1,minY=1,maxX=6,maxY=7),Sizes(line=2.5))
      t.send(Vector((minimum,BigInt(0)),(maximum,BigInt(0))),1,Settings(mode=2))
      t.until(!d.io.busy.peek().litToBoolean);d.io.fault.expect(true.B);assert(t.accepted==0 && t.held.isEmpty)
      t.run(Vector((unit/2,unit/2),(4*unit,4*unit)),1,Settings(mode=2))
      info(s"POINT_LINE_EXTREME subpixelBits=$bits exactCoordinateDifferences=true fullWidthWeights=true modes=3 directions=6 scissor=true lengthOverflowRejected=true healthyReuse=true")
    }
  }

  it should "snapshot sizes and retire cancellation at every preparation and early sample cut before exact reuse" in {
    simulate(new PointLineRasterizer(g)){d=>val t=new Driver(d)
      val coords=Vector((BigInt(128),BigInt(128)),(BigInt(1792),BigInt(1280)))
      t.send(coords,1,Settings(mode=2),Sizes(line=2.5))
      d.io.in.bits.state.lineWidth.poke(fp(64).U);d.io.in.bits.state.pointSize.poke(fp(64).U)
      d.io.in.bits.x0.poke((-(BigInt(1)<<31)).S);d.io.in.bits.y1.poke(((BigInt(1)<<31)-1).S)
      t.finish()
      for(cut<-0 to 10){
        t.send(coords,1,Settings(mode=2),Sizes(line=2.5));for(_<-0 until cut)t.tick()
        d.io.out.valid.expect(false.B);d.io.cancel.poke(true.B);t.tick();d.io.busy.expect(false.B)
        d.io.out.valid.expect(false.B);d.io.fault.expect(false.B);d.io.cancel.poke(false.B)
        t.run(coords,1,Settings(mode=2),Sizes(line=2.5))
      }
      info("POINT_LINE_PIPELINE snapshotSizesAndEndpoints=true cancelCuts=11 noPartialOffers=true exactHealthyReuse=true")
    }
  }

  it should "reject malformed sample modes and sizes without scanning or exposing a partial quad" in {
    simulate(new PointLineRasterizer(g)){d=>val t=new Driver(d)
      for(change<-Seq[()=>Unit](()=>d.io.in.bits.state.sampleMode.poke(3.U),()=>d.io.in.bits.state.lineWidth.poke(fp(-1).U),
        ()=>d.io.in.bits.state.pointSize.poke(BigInt("7fc00000",16).U))){t.send(Vector((BigInt(128),BigInt(128))),0);change()
        // The descriptor is already accepted by send; submit malformed state on a fresh binding instead.
        t.finish();d.io.in.valid.poke(true.B);t.tick();d.io.in.valid.poke(false.B);t.until(!d.io.busy.peek().litToBoolean)
        d.io.fault.expect(true.B);assert(t.held.isEmpty)}
      t.run(Vector((BigInt(128),BigInt(128))),0)
      info("POINT_LINE_COVERAGE invalidModesAndSizes=3 boundedFailure=true noPartialQuad=true healthyReuse=true")
    }
  }

}
