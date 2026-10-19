package opengpu.graphics

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable
import scala.util.Random

private[graphics] object PrimitiveClipReference {
  case class E(sign: Boolean=false,exponent: Int=0,sig: BigInt=0,special: Int=0)
  def fp(value: Double): BigInt=BigInt(java.lang.Float.floatToRawIntBits(value.toFloat).toLong&0xffffffffL)
  def number(bits: BigInt): Double=java.lang.Float.intBitsToFloat(bits.toInt).toDouble
  private def decode(bits: BigInt): E={
    val sign=bits.testBit(31);val exponent=((bits>>23)&255).toInt;val fraction=bits&0x7fffff
    if(exponent==255)E(sign,0,fraction,if(fraction==0)1 else 2)
    else if(exponent==0){if(fraction==0)E(sign)else {val shift=24-fraction.bitLength;E(sign,-126-shift,fraction<<shift)}}
    else E(sign,exponent-127,fraction|0x800000)
  }
  private def round(n: BigInt,d: BigInt): BigInt={val(q,r)=n /% d;q+(if(r*2>d || (r*2==d && q.testBit(0)))1 else 0)}
  private def rational(v: E): (BigInt,BigInt)={val e=v.exponent-23;val n=v.sig*(if(v.sign)-1 else 1);if(e>=0)(n<<e,BigInt(1))else(n,BigInt(1)<<(-e))}
  private def normalize(n: BigInt,d: BigInt,negativeZero: Boolean): E={
    if(n==0)return E(negativeZero)
    val a=n.abs;val b=d.abs;var e=a.bitLength-b.bitLength
    if(if(e>=0)a<(b<<e)else(a<<(-e))<b)e-=1
    val q=if(23-e>=0)round(a<<(23-e),b)else round(a,b<<(e-23))
    if(q.bitLength>24)E(n.signum!=d.signum,e+1,q>>1)else E(n.signum!=d.signum,e,q)
  }
  private def calc(a: E,b: E,operation: Int): E={
    if(a.special!=0 || b.special!=0) {
      val nan=E(false,0,0x400000,2)
      if(a.special==2 || b.special==2)return nan
      if(operation==2) {
        if((a.special==1 && b.sig==0 && b.special==0)||(b.special==1 && a.sig==0 && a.special==0))return nan
        return E(a.sign^b.sign,0,0,1)
      }
      if(operation==0) {
        if(a.special==1 && b.special==1 && a.sign!=b.sign)return nan
        return if(a.special==1)a else b
      }
      throw new IllegalArgumentException("special clipping distance")
    }
    val(an,ad)=rational(a);val(bn,bd)=rational(b)
    operation match {
      case 0=>normalize(an*bd+bn*ad,ad*bd,a.sig==0 && b.sig==0 && a.sign && b.sign)
      case 1=>normalize(an*bd-bn*ad,ad*bd,a.sig==0 && b.sig==0 && a.sign && !b.sign)
      case 2=>normalize(an*bn,ad*bd,a.sign^b.sign)
      case 3=>normalize(an*bd,ad*bn,a.sign^b.sign)
    }
  }
  private def pack(v: E): BigInt={
    val sign=if(v.sign)BigInt(1)<<31 else BigInt(0)
    if(v.special!=0)sign|BigInt("7f800000",16)|(v.sig&0x7fffff)
    else if(v.sig==0)sign
    else if(v.exponent>127)sign|BigInt("7f800000",16)
    else if(v.exponent>= -126)sign|(BigInt(v.exponent+127)<<23)|(v.sig&0x7fffff)
    else sign|round(v.sig,BigInt(1)<<(-126-v.exponent))
  }
  case class V(position: Vector[BigInt],varyings: Vector[BigInt],ordinal: Int,id: Int,generated: Boolean=false)
  case class Answer(parts: Vector[Vector[V]],intersections: Int,maxVertices: Int,planes: Int)
  def vertex(x: Double,y: Double,z: Double,w: Double,id: Int,components: Int=8): V=
    V(Vector(x,y,z,w).map(fp),Vector.tabulate(components)(c=>fp(id*10+c)),100+id,10+id)
  private def inside(v: V,plane: Int): Boolean={val c=number(v.position(plane/2));val w=number(v.position(3));if(plane%2==0)c>= -w else c<=w}
  private def boundary(v: V,plane: Int): Boolean=number(v.position(plane/2))==(if(plane%2==0)-number(v.position(3))else number(v.position(3)))
  def clip(input: Vector[V],kind: Int,flat: BigInt,slots: Int,provoking: Int): Answer={
    val relevant=if(kind==2)0 until 6 else 4 until 6
    val planes=relevant.filter(q=>input.exists(v=> !inside(v,q))).map(q=>1<<q).sum
    var intersections=0;var maximum=input.size
    val flatValues=input(provoking).varyings
    var polygon=input.map(v=>v.copy(varyings=v.varyings.indices.map(c=>if(c>=slots*4)BigInt(0)else if(flat.testBit(c))flatValues(c)else v.varyings(c)).toVector))
    def crossing(first: V,last: V,plane: Int): V={
      val(a,b)=if(inside(first,plane))(first,last)else(last,first)
      if(boundary(a,plane))return a
      val da=calc(decode(a.position(3)),decode(a.position(plane/2)),if(plane%2==0)0 else 1)
      val db=calc(decode(b.position(3)),decode(b.position(plane/2)),if(plane%2==0)0 else 1).copy(sign=false)
      val den=calc(da,db,0);val wa=calc(db,den,3);val wb=calc(da,den,3)
      def interpolate(x: BigInt,y: BigInt): BigInt={
        if(x==y)return x
        val out=pack(calc(calc(decode(x),wa,2),calc(decode(y),wb,2),0))
        if(((x>>23)&255)!=255 && ((y>>23)&255)!=255) {
          val low=if(number(x)<number(y))x else y;val high=if(number(x)<number(y))y else x
          if(number(out)<number(low))low else if(number(out)>number(high))high else out
        }else out
      }
      var position=a.position.indices.map(c=>interpolate(a.position(c),b.position(c))).toVector
      val paired=if(kind==1)plane==5 else plane>=1
      var w=position(3)
      if(paired && number(w)<0)w=0
      position=position.updated(3,w)
      for(axis<-0 until 3) {
        val low=w^BigInt("80000000",16);var coordinate=position(axis)
        if((kind==2 || axis==2) && plane>=2*axis && number(coordinate)<number(low))coordinate=low
        if((kind==2 || axis==2) && plane>=2*axis+1 && number(coordinate)>number(w))coordinate=w
        if(plane/2==axis)coordinate=if(plane%2==0)low else w
        position=position.updated(axis,coordinate)
      }
      val varying=a.varyings.indices.map(c=>if(c>=slots*4)BigInt(0)else if(flat.testBit(c))flatValues(c)else interpolate(a.varyings(c),b.varyings(c))).toVector
      intersections+=1;V(position,varying,0,0,true)
    }
    if(kind==0)polygon=if(relevant.forall(q=>inside(polygon.head,q)))polygon else Vector.empty
    else for(plane<-relevant if polygon.nonEmpty) {
      if(kind==1) {
        val a=polygon(0);val b=polygon(1);val ai=inside(a,plane);val bi=inside(b,plane)
        if(!ai && !bi)polygon=Vector.empty
        else if(ai!=bi){val cross=crossing(a,b,plane);polygon=if(ai)Vector(a,cross)else Vector(cross,b)}
      }else {
        val out=mutable.ArrayBuffer.empty[V]
        for(i<-polygon.indices) {
          val a=polygon((i+polygon.size-1)%polygon.size);val b=polygon(i);val ai=inside(a,plane);val bi=inside(b,plane)
          if(bi){if(!ai && !boundary(b,plane))out+=crossing(a,b,plane);out+=b}
          else if(ai && !boundary(a,plane))out+=crossing(a,b,plane)
        }
        polygon=out.toVector
      }
      maximum=maximum max polygon.size
    }
    val parts=if(kind==2){if(polygon.size<3)Vector.empty else (1 until polygon.size-1).map(i=>Vector(polygon(0),polygon(i),polygon(i+1))).toVector}
      else if(polygon.size==kind+1)Vector(polygon)else Vector.empty
    Answer(parts,intersections,maximum,planes)
  }
}

class PrimitiveClipStageSpec extends AnyFlatSpec {
  import PrimitiveClipReference._
  private val small=VertexShaderStageConfig(VertexInputFetchConfig(1,1),2)
  private def zero(data: Data): Unit=data match {
    case r: Record=>r.elements.values.foreach(zero)
    case v: Vec[_]=>v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case _=>()
  }
  private class Driver(val d: PrimitiveClipStage,p: VertexShaderStageConfig) {
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    var cycle=0;var ready=true;var held: Option[BigInt]=None;var accepted=0;var sequence=0
    var expected=Vector.empty[Vector[V]];var answer=Answer(Vector.empty,0,0,0)
    var kind=2;var flat=BigInt(0);var slots=p.varyings;var provoking=2;var input=Vector.empty[V]
    def tick(): Unit={
      d.io.out.ready.poke((ready && cycle%7<5).B)
      if(d.io.out.valid.peek().litToBoolean) {
        val bits=d.io.out.bits.peek().litValue;held.foreach(v=>assert(v==bits,"offered clip part changed"))
        if(d.io.out.ready.peek().litToBoolean) {
          assert(accepted<expected.size,s"unexpected clip output sequence=$sequence")
          val o=d.io.out.bits;val r=o.primitive;val values=expected(accepted)
          r.identity.context.expect(7.U);r.identity.pass.expect(8.U);r.identity.generation.expect(9.U)
          r.sequence.expect(sequence.U);r.primitiveId.expect(17.U);r.instanceId.expect(2.U);r.baseInstance.expect(3.U)
          r.kind.expect(kind.U);r.vertexCount.expect((kind+1).U);r.provokingVertex.expect(provoking.U)
          r.flatMask.expect(flat.U);r.varyingSlots.expect(slots.U)
          o.partIndex.expect(accepted.U);o.partCount.expect(answer.parts.size.U);o.clipped.expect((answer.planes!=0).B)
          o.sourceProvokingOrdinal.expect(input(provoking).ordinal.U);o.sourceProvokingVertexId.expect(input(provoking).id.U)
          o.generated.expect(values.zipWithIndex.map{case(v,i)=>if(v.generated)1<<i else 0}.sum.U)
          for((v,i)<-values.zipWithIndex) {
            val a=r.vertices(i);a.identity.context.expect(7.U);a.identity.pass.expect(8.U);a.identity.generation.expect(9.U)
            a.ordinal.expect(v.ordinal.U);a.vertexId.expect(v.id.U);a.instanceId.expect(2.U);a.baseInstance.expect(3.U);a.restart.expect(false.B)
            for(c<-0 until 4)a.position(c).expect(v.position(c).U)
            for(c<-v.varyings.indices)a.varyings(c/4)(c%4).expect(v.varyings(c).U)
          }
          for(i<-values.size until 3)assert(r.vertices(i).peek().litValue==0)
          accepted+=1;held=None
        }else held=Some(bits)
      }else assert(held.isEmpty,"offered clip part withdrawn")
      d.clock.step();cycle+=1
    }
    def until(condition: => Boolean): Unit={var n=0;while(!condition && n<150000){tick();n+=1};assert(condition,s"clip stalled cycle=$cycle sequence=$sequence accepted=$accepted")}
    def prepare(values: Vector[V],primitiveKind: Int=2,mask: BigInt=0,varyingSlots: Int= -1,pv: Int= -1): Unit={
      sequence+=1;accepted=0;input=values;kind=primitiveKind;flat=mask;slots=if(varyingSlots<0)p.varyings else varyingSlots;provoking=if(pv<0)kind else pv
      answer=clip(input,kind,flat,slots,provoking);expected=answer.parts
      val q=d.io.in.bits;zero(q);q.identity.context.poke(7.U);q.identity.pass.poke(8.U);q.identity.generation.poke(9.U)
      q.sequence.poke(sequence.U);q.primitiveId.poke(17.U);q.instanceId.poke(2.U);q.baseInstance.poke(3.U)
      q.topology.poke((if(kind==0)0 else if(kind==1)3 else 5).U);q.kind.poke(kind.U);q.vertexCount.poke((kind+1).U)
      q.provokingVertex.poke(provoking.U);q.flatMask.poke(flat.U);q.varyingSlots.poke(slots.U)
      for((v,i)<-values.zipWithIndex) {
        val r=q.vertices(i);r.identity.context.poke(7.U);r.identity.pass.poke(8.U);r.identity.generation.poke(9.U)
        r.ordinal.poke(v.ordinal.U);r.vertexId.poke(v.id.U);r.instanceId.poke(2.U);r.baseInstance.poke(3.U)
        for(c<-0 until 4)r.position(c).poke(v.position(c).U)
        for(c<-v.varyings.indices)r.varyings(c/4)(c%4).poke(v.varyings(c).U)
      }
    }
    def submit(): Unit={
      d.io.in.valid.poke(true.B);until(d.io.in.ready.peek().litToBoolean);tick();d.io.in.valid.poke(false.B)
      d.io.in.bits.vertexCount.poke(0.U);d.io.in.bits.sequence.poke(999.U)
    }
    def finish(status: Int=0): Unit={
      until(d.io.completion.valid.peek().litToBoolean);val r=d.io.completion.bits
      r.status.expect(status.U);r.outputs.expect(accepted.U);r.identity.context.expect(7.U);r.sequence.expect(sequence.U)
      if(status==0) {
        assert(accepted==expected.size);r.culled.expect(expected.isEmpty.B);r.planes.expect(answer.planes.U)
        assert(r.maxVertices.peek().litValue<=9)
        if(answer.planes==0 || expected.isEmpty && input.forall(v=>v.position==input.head.position))r.arithmeticOperations.expect(0.U)
        if(answer.intersections>0 && expected.nonEmpty)assert(r.arithmeticOperations.peek().litValue>=5*answer.intersections)
      }
      val bits=r.peek().litValue
      for(_<-0 until 5){d.io.in.ready.expect(false.B);tick();assert(r.peek().litValue==bits)}
      d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B);d.io.busy.expect(false.B)
    }
    def run(values: Vector[V],primitiveKind: Int=2,mask: BigInt=0,varyingSlots: Int= -1,pv: Int= -1): Unit={prepare(values,primitiveKind,mask,varyingSlots,pv);submit();finish()}
  }

  it should "clip triangles against every plane with flat preservation endpoint copies and exact reference values" in {
    simulate(new PrimitiveClipStage(small)) { d=>
      val t=new Driver(d,small);val base=Vector(vertex(-0.5,-0.5,0,1,0),vertex(0.5,-0.5,0,1,1),vertex(0,0.5,0,1,2))
      t.run(base,mask=0xa5);var cases=1
      for(plane<-0 until 6;outsides<-1 to 3) {
        val values=base.zipWithIndex.map {case(v,i)=>if(i<outsides)v.copy(position=v.position.updated(plane/2,fp(if(plane%2==0)-2 else 2)))else v}
        t.run(values,mask=0x55,pv=0);cases+=1
      }
      val boundary=base.updated(0,base(0).copy(position=Vector(fp(-1),fp(0),fp(0),fp(1))))
      t.run(boundary.updated(1,boundary(1).copy(position=Vector(fp(-2),fp(0.25),fp(0),fp(1)))),mask=0xff);cases+=1
      t.run(Vector(vertex(-2,-2,0,1,0),vertex(2,-2,0,1,1),vertex(0,2,0,1,2)),mask=0x81);cases+=1
      info(s"PRIMITIVE_CLIP triangles=$cases planes=6 flat=true rationalReference=true boundaryCopies=true snapshots=true stalls=true")
    }
  }

  it should "preserve point and line XY while clipping near far and signed-zero endpoints" in {
    simulate(new PrimitiveClipStage(small)) { d=>
      val t=new Driver(d,small);var cases=0
      for(z<-Seq(-2.0,-1.0,0.0,1.0,2.0);kind<-Seq(0,1)) {
        val values=if(kind==0)Vector(vertex(1000,-1000,z,1,0))else Vector(vertex(-1000,1000,z,1,0),vertex(1000,-1000,-z,1,1))
        t.run(values,kind,0xa5);cases+=1
      }
      t.run(Vector(vertex(0,0,0,0,0)),0);d.io.completion.valid.expect(false.B);cases+=1
      t.run(Vector(vertex(0,0,-1,1,0),vertex(1,0,-2,1,1)),1,0xff);cases+=1
      val minusZero=BigInt("80000000",16)
      t.run(Vector(vertex(0,0,0,0,0).copy(position=Vector(minusZero,0,minusZero,minusZero))),0);cases+=1
      info(s"PRIMITIVE_CLIP pointLineCases=$cases nearFarOnly=true outsideXYRetained=true signedZero=true zeroWNotEpsilonClipped=true")
    }
  }

  it should "retain extreme finite crossings and produce identical shared-edge intersections in either direction" in {
    simulate(new PrimitiveClipStage(small)) { d=>
      val t=new Driver(d,small);val maximum=BigInt("7f7fffff",16)
      val a=vertex(0,0,0,1,0).copy(position=Vector(1,0,0,2),varyings=Vector.fill(8)(BigInt(0)))
      val b=vertex(0,0,0,1,1).copy(position=Vector(maximum,0,0,0),varyings=Vector.fill(8)(maximum))
      val c=vertex(0,0,0,1,2).copy(position=Vector(0,1,0,2),varyings=Vector.fill(8)(BigInt(0)))
      t.run(Vector(a,b,c));val forward=t.answer.parts.flatten.filter(_.generated).map(v=>(v.position,v.varyings)).toSet
      t.run(Vector(b,a,c));val reverse=t.answer.parts.flatten.filter(_.generated).map(v=>(v.position,v.varyings)).toSet
      assert(forward.nonEmpty && forward==reverse)
      val mirrored=c.copy(position=c.position.updated(1,BigInt("80000001",16)),ordinal=104,id=14)
      t.run(Vector(b,a,mirrored));val shared=t.answer.parts.flatten.filter(_.generated).map(v=>(v.position,v.varyings)).toSet
      assert(forward.intersect(shared).nonEmpty)
      val large=Vector(vertex(-2,0,0,1,0),vertex(2,0,0,1,1),vertex(0,1,0,1,2)).map(v=>v.copy(position=v.position.map { word=>fp(number(word)*number(maximum)/2) }))
      t.run(large,mask=0x44)
      info("PRIMITIVE_CLIP extremeFinite=true tinyWeights=true sharedEdgeReverse=true adjacentTriangles=true largeDistances=true")
    }
  }

  it should "match randomized finite polygon clipping including negative w and bounded amplification" in {
    simulate(new PrimitiveClipStage(small)) { d=>
      val t=new Driver(d,small);val random=new Random(0x434c4950L);var largest=0
      for(i<-0 until 36) {
        val values=Vector.tabulate(3)(v=>vertex((random.nextInt(33)-16)/4.0,(random.nextInt(33)-16)/4.0,
          (random.nextInt(25)-12)/4.0,if(i%9==0)-1.0 else (random.nextInt(8)+1)/4.0,v))
        t.run(values,mask=BigInt(i*7&255));largest=largest max t.answer.maxVertices
      }
      info(s"PRIMITIVE_CLIP randomTriangles=36 negativeW=true maxReferenceVertices=$largest boundedFan=true")
    }
  }

  it should "preserve sixty-four flat and smooth components through default-size SRAM storage" in {
    val p=VertexShaderStageConfig()
    simulate(new PrimitiveClipStage(p)) { d=>
      val t=new Driver(d,p);val flat=BigInt("a55aa55aa55aa55a",16)
      val values=Vector(vertex(1.5,0,-1.5,1,0,64),vertex(-1.5,1.5,0,1,1,64),vertex(0,-1.5,1.5,1,2,64)).map { v=>
        v.copy(varyings=v.varyings.indices.map(c=>if(flat.testBit(c))BigInt("ffc01234",16)+v.id else v.varyings(c)).toVector)
      }
      t.run(values,mask=flat,pv=0)
      assert(t.answer.maxVertices==9 && t.answer.parts.size==7 && t.accepted==7)
      info("PRIMITIVE_CLIP varyingComponents=64 flatRawNaN=true smoothValues=true synchronousSram=true maxVertices=9 fanParts=7")
    }
  }

  it should "drain arithmetic cancellation and retain offered fan parts before healthy reuse" in {
    simulate(new PrimitiveClipStage(small)) { d=>
      val t=new Driver(d,small);val values=Vector(vertex(-2,-0.5,0,1,0),vertex(0.5,-0.5,0,1,1),vertex(0,0.5,0,1,2))
      for(delay<-Seq(0,35,75,110,155)) {
        t.prepare(values);t.expected=Vector.empty;t.ready=false;t.submit()
        for(_<-0 until delay)t.tick()
        d.io.cancel.poke(true.B);t.until(d.io.completion.valid.peek().litToBoolean)
        d.io.cancel.poke(false.B);t.finish(4);assert(t.accepted==0)
      }
      t.prepare(values,mask=0xa5);t.ready=false;t.submit();t.until(d.io.out.valid.peek().litToBoolean)
      val held=d.io.out.bits.peek().litValue;d.io.cancel.poke(true.B)
      for(_<-0 until 13){t.tick();assert(d.io.out.bits.peek().litValue==held);d.io.completion.valid.expect(false.B)}
      t.ready=true;t.until(d.io.completion.valid.peek().litToBoolean);d.io.cancel.poke(false.B);t.finish(4);assert(t.accepted==1)
      t.run(values,mask=0xa5)
      info("PRIMITIVE_CLIP cancelOffsets=5 memoryMathDrain=true heldFanPart=true youngerPartsSuppressed=true healthyReuse=true")
    }
  }

  it should "reject malformed descriptors and nonfinite positions without partial output" in {
    simulate(new PrimitiveClipStage(small)) { d=>
      val t=new Driver(d,small);val values=Vector(vertex(-2,-0.5,0,1,0),vertex(0.5,-0.5,0,1,1),vertex(0,0.5,0,1,2))
      val q=d.io.in.bits
      val invalid=Seq[()=>Unit](()=>q.kind.poke(3.U),()=>q.topology.poke(7.U),()=>q.vertexCount.poke(2.U),()=>q.provokingVertex.poke(3.U),
        ()=>q.varyingSlots.poke(3.U),()=>{q.varyingSlots.poke(1.U);q.flatMask.poke(16.U)},
        ()=>q.vertices(0).identity.context.poke(999.U),()=>q.vertices(1).instanceId.poke(3.U),
        ()=>q.vertices(2).baseInstance.poke(4.U),()=>q.vertices(0).restart.poke(true.B))
      for(adjust<-invalid){t.prepare(values);t.expected=Vector.empty;adjust();t.submit();t.finish(1);assert(t.accepted==0)}
      for(word<-Seq(BigInt("7f800000",16),BigInt("ff800000",16),BigInt("7fc00000",16))) {
        t.prepare(values);t.expected=Vector.empty;q.vertices(0).position(0).poke(word.U);t.submit();t.finish(7);assert(t.accepted==0)
      }
      t.prepare(Vector(vertex(1000,1000,0,1,0)),0);q.vertices(2).position(0).poke("h7fc00000".U);q.vertices(2).identity.context.poke(999.U)
      t.submit();t.finish(0)
      t.run(values)
      info("PRIMITIVE_CLIP invalidLayouts=10 nonfinitePositions=3 unusedInputsIgnored=true healthyReuse=true")
    }
  }

  it should "interpolate smooth special values while bypassing raw flat components" in {
    simulate(new PrimitiveClipStage(small)) { d=>
      val t=new Driver(d,small)
      val values=Vector(vertex(-2,-0.5,0,1,0),vertex(0.5,-0.5,0,1,1),vertex(0,0.5,0,1,2)).zipWithIndex.map { case(v,i)=>
        v.copy(varyings=v.varyings.updated(0,BigInt(if(i==0)"7f800000"else"ff800000",16)).updated(1,BigInt("ffc12345",16)+i).updated(2,BigInt("7fc12345",16)))
      }
      t.run(values,mask=0xaa,pv=0)
      info("PRIMITIVE_CLIP smoothSpecialValues=true flatRawBits=true noPositionFault=true")
    }
  }

  it should "clip positions with zero active varyings at the minimum declared interface" in {
    val p=VertexShaderStageConfig(VertexInputFetchConfig(1,1),1)
    simulate(new PrimitiveClipStage(p)) { d=>
      val t=new Driver(d,p)
      val values=Vector(vertex(-2,-0.5,0,1,0,4),vertex(0.5,-0.5,0,1,1,4),vertex(0,0.5,0,1,2,4))
      t.run(values,varyingSlots=0);assert(t.accepted==2)
      info("PRIMITIVE_CLIP minimumInterface=true varyingSlots=0 positionOnly=true unusedVaryingsZero=true")
    }
  }
}
