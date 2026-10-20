package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class TextureImageSampleUnitSpec extends AnyFlatSpec {
  import WindowRecordTest._
  import TextureFilterReference.{bits,value,filter}
  import TextureSamplingReference.{axis,Axis,cube,comparison}
  private case class Image(format: Int=3,target: Int=0,base: Int=0) {
    val compressed=format>=54;val info=if(compressed)None else Some(ImageFormatReference.format(ImageFormatReference.names(format)))
    val bytes=if(compressed)CompressedImageReference.format(format).bytes else info.get.bytes
    val kind=if(compressed)0 else info.get.kind;val filterable=compressed || info.get.filterable
    val last=2;val layers=if(target==1)3 else 1
    case class Level(n: Int) {
      val w=1.max(7>>n);val h=1.max((if(target==2)7 else 5)>>n);val depth=if(target==3)1.max(3>>n)else 1
      val columns=if(compressed)(w+3)/4 else w;val rows=if(compressed)(h+3)/4 else h
      val row=columns*bytes+13;val plane=(rows-1)*row+columns*bytes
      val slice=if(target==3)plane+7 else 0;val layerPitch=if(target==1 || target==2)plane+11 else 0
      val start=BigInt(0x10003c)+n*0x4000;val planes=if(target==2)6 else layers
      def address(x: Int,y: Int,z: Int,l: Int,f: Int): BigInt=start+(if(compressed)x/4 else x)*bytes+
        (if(compressed)y/4 else y)*row+z*slice+(if(target==2)f else l)*layerPitch
      def raw(x: Int,y: Int,z: Int,l: Int,f: Int): BigInt={
        val bx=if(compressed)x/4 else x;val by=if(compressed)y/4 else y
        if(format>=49 && format<54){val p=(x+2*y+z+l+f+n)%5
          if(format==51 || format==53)(BigInt(0xa7563412L)<<32)|bits(p/4f)
          else if(format==49)BigInt(65535*p/4)else(BigInt(0xa7)<<24)|BigInt(0xffffffL*p/4)
        }else BigInt(bytes*8,new scala.util.Random(0x4d49504cL+format*103+n*313+bx*71+by*139+z*599+l*877+f*997))
      }
      def rgba(x: Int,y: Int,z: Int,l: Int,f: Int): Seq[BigInt]={val r=raw(x,y,z,l,f)
        if(compressed)CompressedImageReference.decode(format,r,x&3,y&3)else ImageFormatReference.decode(ImageFormatReference.names(format),r)}
    }
    val levels=(0 to last).map(Level)
  }
  private case class Sample(lod: Float=0.75f,min: Int=5,mag: Int=1,lo: Float= -1000f,hi: Float=1000f,
    coordinates: Seq[Float]=Seq(0.4f,0.6f,0.3f),layer: Float=1.5f,shadow: Boolean=false,function: Int=3,
    reference: Float=0.5f,compare: Boolean=false,swizzle: Seq[Int]=Seq(3,2,5,0),applySwizzle: Boolean=true,
    gradients: Option[Seq[Seq[Float]]]=None,quad: Option[Seq[Seq[Float]]]=None,lane: Int=0,bias: Float=0f)
  private def gradientLod(i: Image,s: Sample): Float={
    val v=i.levels(i.base);val g=s.quad.map{q=>(0 to 1).map{row=>
      val lo=if(row==0)(s.lane/2)*2 else s.lane%2;val hi=lo+(if(row==0)1 else 2)
      (0 until 3).map{a=>if(a==2 && i.target!=2 && i.target!=3)0.0 else{
        val (sign,e,sig)=TextureGradientReference.rounded(TextureGradientReference.integer(bits(q(hi)(a)))-TextureGradientReference.integer(bits(q(lo)(a))),-149)
        java.lang.Math.scalb((if(sign)-sig.toDouble else sig.toDouble),e-23)
      }}
    }}.getOrElse(s.gradients.get.map(_.map(_.toDouble)))
    val projected=if(i.target!=2)g else {
      val p=s.coordinates.map(_.toDouble);val m=p.map(math.abs).max;val a=p.indexWhere(x=>math.abs(x)==m);val f=a*2+(if(p(a)<0)1 else 0)
      def sc(x: Seq[Double]): Double=f match{case 0=> -x(2);case 1=>x(2);case 5=> -x(0);case _=>x(0)}
      def tc(x: Seq[Double]): Double=f match{case 2=>x(2);case 3=> -x(2);case _=> -x(1)}
      g.map{x=>val dm=if(p(a)<0)-x(a)else x(a)
        Seq((m*sc(x)-sc(p)*dm)/(2*m*m),(m*tc(x)-tc(p)*dm)/(2*m*m),0.0)}
    }
    val extents=Seq(v.w,v.h,v.depth);val rho=(for(row<-0 to 1;a<-0 until(if(i.target==3)3 else 2))yield math.abs(projected(row)(a))*extents(a)).max
    if(rho==0)Float.NegativeInfinity else (java.lang.StrictMath.log(rho)/java.lang.StrictMath.log(2.0)).toFloat
  }
  private class Driver(val d: TextureImageSampleUnit) {
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    val memory=new Memory(d.io.memoryRequest.bits,d.io.memoryResponse.bits,d.io.memoryRequest.valid,d.io.memoryRequest.ready,d.io.memoryResponse.valid,d.io.memoryResponse.ready)
    var tag=0;var startRead=0;var image=Image();var sample=Sample();var checks=0
    val completedReplies=mutable.ArrayBuffer.empty[BigInt]
    memory.beforeRequest=r=>{assert(!r.write && (r.tag<=4 || r.tag==10 || r.tag==11));assert(r.address%64==0 && r.mask==(BigInt(1)<<64)-1)}
    def tick(): Unit={memory.drive();if(memory.reply.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean)completedReplies+=memory.reply.get.address
      memory.capture();d.clock.step()}
    def until(p: =>Boolean): Unit={var n=0;while(!p && n<30000){tick();n+=1};assert(p,s"sample did not drain tag=$tag reads=${memory.seen.takeRight(8)}")}
    def invalidate(): Unit={d.io.invalidate.poke(true.B);tick();d.io.invalidate.poke(false.B)}
    def install(i: Image,s: Sample): Unit={
      image=i;sample=s
      val header=Seq[BigInt](BigInt("47584901",16),128,1,7,11,13,0,i.target,i.format,i.base,i.last,7,if(i.target==2)7 else 5,
        if(i.target==3)3 else 1,i.layers,0x100000,0x140000,0,1,0x2000,0x2800,0,0,1)++s.swizzle.map(BigInt(_))++Seq.fill(4)(BigInt(0))
      for((w,n)<-header.zipWithIndex)memory.putWord(0x1000+n*4,w)
      val sampler=Seq[BigInt](BigInt("47585301",16),64,1,7,11,13,0,s.min,s.mag,0,1,2,bits(s.lo),bits(s.hi),if(s.compare || s.shadow)1 else 0,s.function)
      for((w,n)<-sampler.zipWithIndex)memory.putWord(0x3000+n*4,w)
      for(v<-i.levels){
        val words=Seq[BigInt](BigInt("47584c01",16),128,1,7,11,13,v.n,i.target,v.n,0x100000,0x140000,0,
          v.start,v.w,v.h,v.depth,i.layers,v.row,0,v.slice,0,v.layerPitch,0,if(i.compressed)1 else 0,i.bytes).padTo(32,BigInt(0))
        for((w,n)<-words.zipWithIndex)memory.putWord(0x2000+v.n*128+n*4,w)
        for(plane<-0 until v.planes;z<-0 until v.depth;y<-0 until v.rows;x<-0 until v.columns){
          val tx=x*(if(i.compressed)4 else 1);val ty=y*(if(i.compressed)4 else 1)
          val l=if(i.target==1)plane else 0;val f=if(i.target==2)plane else 0;val a=v.address(tx,ty,z,l,f);val raw=v.raw(tx,ty,z,l,f)
          for(b<-0 until i.bytes)memory.bytes(a+b)=((raw>>(8*b))&255).toInt
        }
      }
    }
    def query(i: Image=image,s: Sample=sample,edit: TextureImageSampleRequest=>Unit=(_: TextureImageSampleRequest)=>()): Unit={
      image=i;sample=s;tag+=1;startRead=memory.seen.size;val q=d.io.in.bits;zero(q)
      val r=q.preparation;identity(r.images.identity);identity(r.samplers.identity)
      r.images.version.poke(1.U);r.images.base.poke(0x1000.U);r.images.limit.poke(0x1800.U)
      r.samplers.version.poke(1.U);r.samplers.base.poke(0x3000.U);r.samplers.limit.poke(0x3400.U)
      r.expectedType.poke(i.kind.U);r.target.poke(i.target.U);r.coordinates.zip(s.coordinates).foreach{case(a,b)=>a.poke(bits(b).U)}
      r.layer.poke(bits(s.layer).U);r.reference.poke(bits(s.reference).U);r.shadow.poke(s.shadow.B)
      r.applySwizzle.poke(s.applySwizzle.B);r.tag.poke(tag.U);q.lod.poke((if(s.gradients.nonEmpty || s.quad.nonEmpty)BigInt(0)else bits(s.lod)).U)
      q.gradient.poke(s.gradients.nonEmpty.B);s.gradients.foreach(g=>q.gradients.zip(g).foreach{case(a,b)=>a.zip(b).foreach{case(x,y)=>x.poke(bits(y).U)}})
      q.implicitLod.poke(s.quad.nonEmpty.B);q.bias.poke(bits(s.bias).U)
      s.quad.foreach(v=>{q.quadCoordinates.zip(v).foreach{case(a,b)=>a.zip(b).foreach{case(x,y)=>x.poke(bits(y).U)}}
        q.quadLane.poke(s.lane.U);q.quadValidMask.poke(15.U)})
      edit(q)
      d.io.in.valid.poke(true.B);until(d.io.in.ready.peek().litToBoolean);tick();d.io.in.valid.poke(false.B);zero(q)
    }
    def reference(): (Seq[BigInt],Int,Int,BigInt,Boolean,Int)={
      val i=image;val s=sample;val rawLod=if(s.quad.nonEmpty)gradientLod(i,s)+math.min(16f,math.max(-16f,s.bias))else if(s.gradients.nonEmpty)gradientLod(i,s)else s.lod
      val lambda=math.min(s.hi,math.max(s.lo,rawLod));val minifying=lambda>0
      val linear=if(minifying)Set(1,3,5)(s.min)else s.mag==1
      val delta=i.last-i.base;var first=i.base;var second=i.base;var weight=0f
      if(minifying && s.min>=2){if(lambda>=delta){first=i.last;second=i.last}else{
        val floor=math.floor(lambda).toInt;val frac=lambda-floor
        if(s.min>=4){first=i.base+floor;second=first+1;weight=frac}else{first=i.base+floor+(if(frac>0.5f)1 else 0);second=first}}}
      val (face,coordinates)=if(i.target!=2)(0,s.coordinates)else{
        val v=s.coordinates;val m=v.map(math.abs).max;val a=v.indexWhere(x=>math.abs(x)==m);val f=a*2+(if(v(a)<0)1 else 0)
        val (sc,tc)=f match {case 0=>(-v(2),-v(1));case 1=>(v(2),-v(1));case 2=>(v(0),v(2));case 3=>(v(0),-v(2));case 4=>(v(0),-v(1));case 5=>(-v(0),-v(1))}
        (f,Seq(java.lang.Math.fma(0.5f,sc/m,0.5f),java.lang.Math.fma(0.5f,tc/m,0.5f),0f))
      }
      val layer=if(i.target==1)math.floor(s.layer.toDouble+0.5).max(0).min(i.layers-1).toInt else 0
      def level(n: Int): (Seq[BigInt],Int)={val v=i.levels(n);val mode=if(i.target==2){if(linear)3 else 1}else -1
        val modes=if(mode<0)Seq(0,1,2)else Seq.fill(3)(mode)
        val axes=Seq(axis(bits(coordinates(0)),v.w,modes(0),linear),axis(bits(coordinates(1)),v.h,modes(1),linear),
          if(i.target==3)axis(bits(coordinates(2)),v.depth,modes(2),linear)else Axis(0,0,0))
        val unique=mutable.Set.empty[(Int,Int,Int,Int)]
        val colors=(0 until 8).map{tap=>
          val selected=(0 until 3).forall{a=>a==2 && i.target!=3 ||
            ((axes(a).weight!=0 || (tap&(1<<a))==0) && (axes(a).weight!=bits(1f) || (tap&(1<<a))!=0))}
          if((!linear && tap!=0) || (i.target!=3 && tap>=4) || !selected)Seq.fill(4)(BigInt(0))else{
            val xyz=axes.zipWithIndex.map{case(a,j)=>if((tap&(1<<j))==0)a.first else a.second}
            val (f,x,y)=if(i.target==2)cube(face,xyz(0),xyz(1),v.w)else(face,xyz(0),xyz(1));unique+=((f,x,y,xyz(2)))
            val rgba=v.rgba(x,y,xyz(2),layer,f)
            if(s.shadow)Seq(comparison(bits(s.reference),rgba(0),s.function,i.format!=51 && i.format!=53),BigInt(0),BigInt(0),bits(1f))else rgba
          }
        }
        (filter(colors,axes.map(_.weight),if(!linear)0 else if(i.target==3)3 else 2),unique.size)
      }
      val (a,ta)=level(first);val (filtered,taps)=if(first!=second && weight!=0){val (b,tb)=level(second);(filter(Seq(a,b),Seq(bits(weight)),1),ta+tb)}else(a,ta)
      val options=filtered++Seq(BigInt(0),if(i.kind==0 || s.shadow)bits(1f)else BigInt(1))
      (if(s.applySwizzle)s.swizzle.map(options)else filtered,first,second,bits(weight),linear,taps)
    }
    def check(status: Int=0,source: Int=0,fault: Option[BigInt]=None,holdMutation: Boolean=true): Unit={
      until(d.io.out.valid.peek().litToBoolean);val o=d.io.out.bits;val r=o.sample
      assert(r.status.peek().litValue==status,s"tag=$tag image=$image sample=$sample status=${r.status.peek().litValue} expected=$status reads=${memory.seen.drop(startRead)}")
      r.tag.expect(tag.U);r.faultSource.expect(source.U);r.faultAddressValid.expect(fault.nonEmpty.B);r.faultAddress.expect(fault.getOrElse(BigInt(0)).U)
      if(status==0){val (rgba,first,second,weight,linear,taps)=reference()
        assert(r.rgba.map(_.peek().litValue)==rgba,s"tag=$tag format=${image.format} target=${image.target} sample=$sample got=${r.rgba.map(_.peek().litValue.toString(16))} expected=${rgba.map(_.toString(16))}")
        o.first.expect(first.U);o.second.expect(second.U);o.weight.expect(weight.U);o.linear.expect(linear.B);o.uniqueTaps.expect(taps.U)
        r.colorType.expect(image.kind.U);o.dataReadLines.expect(memory.seen.drop(startRead).count(_.tag>=8).U)
      }else{r.rgba.foreach(_.expect(0.U));r.colorType.expect(0.U)}
      assert(memory.pending.isEmpty && memory.reply.isEmpty)
      val held=o.peek().litValue;d.io.cancel.poke(holdMutation.B);d.io.invalidate.poke(holdMutation.B)
      for(_<-0 until 3){tick();assert(o.peek().litValue==held)}
      d.io.out.ready.poke(true.B);tick();d.io.out.ready.poke(false.B);d.io.cancel.poke(false.B);d.io.invalidate.poke(false.B)
      d.io.busy.expect(false.B);checks+=1
    }
  }
  it should "sample implicit helper quads with extended differences and bias before sampler clamps" in {
    simulate(new TextureImageSampleUnit(GpuConfig(lanes=4,warps=2))){d=>val t=new Driver(d)
      val flat=Seq(Seq(0.25f,0.25f,0.25f),Seq(0.5f,0.25f,0.75f),Seq(0.25f,0.75f,0.5f),Seq(0.5f,0.75f,1f))
      val cube=Seq(Seq(0f,0f,1f),Seq(0.25f,0f,1f),Seq(0f,0.5f,1f),Seq(0.25f,0.5f,1f))
      for(format<-Seq(3,8,49);target<-0 to 3 if !(format==49 && target==3);base<-0 to 1;lane<-0 until 4;bias<-Seq(-2f,0f,0.75f,64f,-64f)){
        val q=if(target==2)cube else flat;val i=Image(format,target,base)
        val s=Sample(coordinates=q(lane),shadow=format==49,quad=Some(q),lane=lane,bias=bias)
        t.install(i,s);t.invalidate();t.query();t.check()
      }
      for(face<-0 until 6;lane<-0 until 4){
        val q=Seq((0f,0f),(0.25f,0f),(0f,0.5f),(0.25f,0.5f)).map{case(s,t)=>face match{
          case 0=>Seq(1f,-t,-s);case 1=>Seq(-1f,-t,s);case 2=>Seq(s,1f,t);case 3=>Seq(s,-1f,-t);case 4=>Seq(s,-t,1f);case 5=>Seq(-s,-t,-1f)}}
        val i=Image(target=2);val s=Sample(coordinates=q(lane),quad=Some(q),lane=lane,bias=0.5f)
        t.install(i,s);t.invalidate();t.query();t.check()
      }
      val extreme=Seq(Seq(-Float.MaxValue,0f,1f),Seq(Float.MaxValue,0f,1f),Seq(-Float.MaxValue,Float.MinPositiveValue,1f),Seq(Float.MaxValue,Float.MinPositiveValue,1f))
      for(target<-0 to 3;lane<-0 until 4){val i=Image(target=target);val s=Sample(coordinates=extreme(lane),quad=Some(extreme),lane=lane)
        t.install(i,s);t.invalidate();t.query();t.check()}
      val constant=Seq.fill(4)(Seq(0.25f,0.25f,1f))
      for(lo<-Seq(-1000f,0.75f);bias<-Seq(Float.NegativeInfinity,0f,Float.PositiveInfinity)){
        val i=Image();val s=Sample(coordinates=constant(0),quad=Some(constant),lo=lo,bias=bias)
        t.install(i,s);t.invalidate();t.query();t.check()
      }
      val i=Image();val s=Sample(coordinates=flat(0),quad=Some(flat),bias= -64f,lo=0.5f,hi=0.5f)
      t.install(i,s);t.invalidate();t.query();t.check()
      // Missing helpers, a mismatched selected coordinate and mixed modes
      // fail before descriptor reads, including an inactive helper's NaN.
      for(edit<-Seq[TextureImageSampleRequest=>Unit](q=>q.quadValidMask.poke(7.U),q=>q.preparation.coordinates(0).poke(bits(0.125f).U),q=>q.gradient.poke(true.B))){
        t.query(edit=edit);t.check(1,3);assert(t.memory.seen.size==t.startRead)
      }
      t.query(edit=q=>q.quadCoordinates(3)(0).poke(bits(Float.NaN).U));t.check(5,4)
      assert(!t.memory.seen.drop(t.startRead).exists(_.tag>=8))
      t.query(edit=q=>q.bias.poke(bits(Float.NaN).U));t.check(5,4);assert(!t.memory.seen.drop(t.startRead).exists(_.tag>=8))
      t.query();t.check()
      info(s"IMAGE_SAMPLE_IMPLICIT queries=${t.checks} targets4=true allQuadLanes=true cubeFaces6=true baseExtents=true finiteDifferenceOverflowRetained=true completeHelpers=true biasBeforeSamplerClamps=true biasLimit16=true zeroGradientMinusInfinity=true shadowBeforeFilter=true malformedModeBeforeDescriptors=true badHelperAndBiasBeforeTexels=true healthyReuse=true")
    }
  }
  it should "drain helper cube logarithm bias and texel work through implicit cancellation" in {
    simulate(new TextureImageSampleUnit(GpuConfig(lanes=4,warps=2))){d=>val t=new Driver(d);var cancelled=0;var held=0
      val q=Seq(Seq(0f,0f,1f),Seq(0.25f,0f,1f),Seq(0f,0.5f,1f),Seq(0.25f,0.5f,1f))
      for(target<-Seq(0,2);cut<-(0 until 48)++Seq(64,96,128,160,192,224,256,320,384,512,768,1024,1536)){
        val i=Image(target=target);val s=Sample(coordinates=q(3),quad=Some(q),lane=3,bias=0.75f)
        t.install(i,s);t.invalidate();t.query();for(_<-0 until cut)t.tick()
        val complete=d.io.out.valid.peek().litToBoolean;d.io.cancel.poke(true.B);t.check(if(complete)0 else 4)
        if(complete)held+=1 else cancelled+=1;t.install(i,s);t.query();t.check()
      }
      assert(cancelled>0 && held>0)
      info(s"IMAGE_SAMPLE_IMPLICIT_DRAIN cuts122=true cancelled=$cancelled held=$held cubeAndNoncube=true acceptedArithmeticAndMemoryDrains=true noPartialColor=true heldCompletion=true healthyReuse=true")
    }
  }
  it should "sample real images using base extent gradients cube Jacobians and sampler clamps" in {
    simulate(new TextureImageSampleUnit(GpuConfig(lanes=4,warps=2))){d=>val t=new Driver(d)
      val directions=(0 to 2).flatMap(a=>Seq(-1f,1f).map{sign=>val v=Array.fill(3)(0f);v(a)=sign;v.toSeq})++Seq(Seq(1f,1f,1f))
      for(format<-Seq(3,8,49);target<-0 to 3 if !(format==49 && target==3);base<-0 to 1;
        vector<- (if(target==2)directions else Seq(Seq(0.4f,0.6f,0.3f)));scale<-Seq(0f,0.125f,0.25f,1f)){
        val g=Seq(Seq(scale,0.5f*scale,0.25f*scale),Seq(0.25f*scale,-scale,0.5f*scale))
        val i=Image(format,target,base);val s=Sample(coordinates=vector,shadow=format==49,gradients=Some(g))
        t.install(i,s);t.invalidate();t.query();t.check()
      }
      val g=Seq.fill(2)(Seq(0f,0f,Float.NaN));val i=Image(target=1);val s=Sample(gradients=Some(g),lo=0.75f)
      t.install(i,s);t.invalidate();t.query();t.check()
      for(target<-0 to 3){val j=Image(target=target);val q=Sample(coordinates=Seq(1f,0f,0f),gradients=Some(Seq(Seq(Float.PositiveInfinity,0f,0f),Seq.fill(3)(0f))))
        t.install(j,q);t.invalidate();t.query();t.check(5,4);assert(!t.memory.seen.drop(t.startRead).exists(_.tag>=8))
      }
      t.install(i,s);t.query(edit=q=>q.lod.poke(bits(1f).U));t.check(1,3);assert(t.memory.seen.size==t.startRead)
      info(s"IMAGE_SAMPLE_GRADIENT queries=${t.checks} realDescriptors=true baseExtents=true targets4=true cubeFaces6=true gradientLambdaBeforeSamplerClamps=true zeroAndMagnifyingRho=true decodedSpatialAndMipFiltering=true postFilterSwizzle=true invalidUsedGradientsBeforeTexels=true unusedArrayZIgnored=true canonicalUnusedExplicitLod=true")
    }
  }
  it should "drain gradient and logarithm work before cancellation completion and retain healthy reuse" in {
    simulate(new TextureImageSampleUnit(GpuConfig(lanes=4,warps=2))){d=>val t=new Driver(d);var cancelled=0;var held=0
      for(target<-Seq(0,2);cut<-(0 until 64)++Seq(80,96,128,160,192,224,256,384,512,768,1024,1536)){
        val i=Image(target=target);val s=Sample(coordinates=Seq(1f,0.3f,-0.7f),gradients=Some(Seq(Seq(0.25f,-0.5f,1f),Seq(-1f,0.5f,0.25f))))
        t.install(i,s);t.invalidate();t.query();for(_<-0 until cut)t.tick()
        val complete=d.io.out.valid.peek().litToBoolean;d.io.cancel.poke(true.B);t.check(if(complete)0 else 4)
        if(complete)held+=1 else cancelled+=1
        t.install(i,s);t.query();t.check()
      }
      assert(cancelled>0 && held>0)
      info(s"IMAGE_SAMPLE_GRADIENT_DRAIN cuts152=true cancelled=$cancelled held=$held cubeAndNoncube=true acceptedArithmeticDrains=true heldResultImmutable=true noPartialColor=true exactHealthyReuse=true")
    }
  }
  it should "sample real GXI GXL GXS records across all formats targets min mag and mip modes" in {
    simulate(new TextureImageSampleUnit(GpuConfig(lanes=4,warps=2))){d=>val t=new Driver(d)
      for(format<-0 until 64){val i=Image(format,target=if(format>=49)format%3 else format%4)
        val s=Sample(min=2,mag=0,lod=0.75f);t.install(i,s);t.invalidate();t.query();t.check()}
      for(format<-Seq(3,8,35,49,51,54,59);target<-0 to 3 if !(target==3 && format>=49);min<-0 to 5 if Image(format).filterable || min==0 || min==2 || format==49 || format==51){
        val shadow=format==49 || format==51;val i=Image(format,target,base=1)
        val s=Sample(min=min,mag=if(Image(format).filterable || shadow)1 else 0,lod=0.25f,shadow=shadow,compare=true,
          coordinates=if(target==2)Seq(-1f,0.3f,1f)else Seq(-0.2f,1.2f,0.7f))
        t.install(i,s);t.invalidate();t.query();t.check()
      }
      for(min<-0 to 5;mag<-0 to 1;lod<-Seq(-1f,-0f,0f,Float.MinPositiveValue,0.5f,java.lang.Math.nextUp(0.5f),1f,1.75f,Float.PositiveInfinity,Float.NegativeInfinity)){
        val i=Image();val s=Sample(min=min,mag=mag,lod=lod);t.install(i,s);t.invalidate();t.query();t.check()}
      for(format<-Seq(49,50,51,52,53);function<-0 until 8){val i=Image(format,target=1)
        val s=Sample(shadow=true,function=function,min=5,lod=1.25f,layer=2.5f);t.install(i,s);t.invalidate();t.query();t.check()}
      info(s"IMAGE_SAMPLE_COMPOSED queries=${t.checks} formats64=true targets4=true minFilters6=true magFilters2=true baseLevel1=true mipBlend=true depthCompareBeforeFiltering=true postMipSwizzle=true descriptorMemoryReal=true exactTapAndLineCounts=true")
    }
  }
  it should "retain admitted mip snapshots and reject malformed resources and coordinates before data" in {
    simulate(new TextureImageSampleUnit(GpuConfig(lanes=4,warps=2))){d=>val t=new Driver(d);val i=Image();val s=Sample()
      t.install(i,s);t.query();t.until(t.memory.seen.exists(_.tag>=8))
      val reads=t.memory.seen.count(_.tag<8)
      for(a<-Seq(0x1000,0x2000,0x2080,0x2100,0x3000))t.memory.putWord(a,0)
      t.check(holdMutation=false);assert(t.memory.seen.count(_.tag<8)==reads,"mip execution reread poisoned descriptors")
      t.query();t.check();assert(t.memory.seen.count(_.tag<8)==reads,"cached sampling reread unpublished descriptor edits")
      t.install(i,s);t.invalidate();t.memory.putWord(0x2100+13*4,2);t.query();t.check(6,2)
      assert(!t.memory.seen.drop(t.startRead).exists(_.tag>=8))
      for((target,coords,layer)<-Seq((0,Seq(Float.NaN,0f,0f),0f),(1,Seq(0.2f,0.3f,0f),Float.PositiveInfinity),
        (2,Seq(0f,-0f,0f),0f),(3,Seq(0.2f,0.3f,Float.NegativeInfinity),0f))){
        val j=Image(target=target);val q=Sample(coordinates=coords,layer=layer)
        t.install(j,q);t.invalidate();t.query();t.check(5,4);assert(!t.memory.seen.drop(t.startRead).exists(_.tag>=8))
      }
      t.install(i,s.copy(lod=Float.NaN));t.invalidate();t.query();t.check(5,4)
      assert(!t.memory.seen.drop(t.startRead).exists(_.tag>=8))
      t.install(i,s);t.invalidate();t.query();t.check()
      info("IMAGE_SAMPLE_ADMISSION immutableAllMipViews=true noDescriptorReread=true malformedLaterMipBeforeData=true NaNCoordinatesAndLod=true nonfiniteArrayLayer=true zeroCubeRejected=true healthyReuse=true")
    }
  }
  it should "retain a second mip data fault across cancellation even with constant swizzles" in {
    simulate(new TextureImageSampleUnit(GpuConfig(lanes=4,warps=2))){d=>val t=new Driver(d);val i=Image()
      val s=Sample(min=4,mag=0,lod=0.75f,swizzle=Seq(4,5,4,5));t.install(i,s)
      val v=i.levels(1);val a=v.address(1,1,0,0,0)& ~BigInt(63);t.memory.faultAt=Set(a)
      t.query();t.until(d.io.out.valid.peek().litToBoolean)
      assert(t.memory.seen.exists(r=>r.tag>=8 && r.address<a),"first mip must complete before the second faults")
      d.io.cancel.poke(true.B);t.check(3,2,Some(a));t.memory.faultAt=Set.empty
      t.query();t.check()
      // Stop at the accepted faulty response, then cancel while the error
      // travels through typed fetch, level filtering, mip and outer owners.
      t.memory.faultAt=Set(a);val replyStart=t.completedReplies.size;t.query();t.until(t.completedReplies.drop(replyStart).contains(a))
      d.io.cancel.poke(true.B);t.check(3,2,Some(a));t.memory.faultAt=Set.empty;t.query();t.check()
      info("IMAGE_SAMPLE_LATE_FAULT secondMip=true constantSwizzleDoesNotMaskRead=true primaryFaultBeforeCancellation=true heldFaultStable=true noPartialColor=true healthyReuse=true")
    }
  }
  it should "drain descriptor and texel offers on cancellation then recover without partial color" in {
    simulate(new TextureImageSampleUnit(GpuConfig(lanes=4,warps=2))){d=>val t=new Driver(d);val i=Image(target=2);val s=Sample(coordinates=Seq(1f,-0.3f,0.8f))
      for(cut<-Seq(0,1,5,10,20,40,80,120,180,250,400,700,1000,1500)){
        t.install(i,s);t.invalidate();t.query();for(_<-0 until cut)t.tick()
        val done=d.io.out.valid.peek().litToBoolean;d.io.cancel.poke(true.B);t.check(if(done)0 else 4)
        t.install(i,s);t.query();t.check()
      }
      t.install(i,s);t.invalidate();t.memory.holdRequests=true;t.query();t.until(d.io.memoryRequest.valid.peek().litToBoolean)
      val offer=d.io.memoryRequest.bits.peek().litValue;d.io.cancel.poke(true.B)
      for(_<-0 until 9){t.tick();d.io.memoryRequest.valid.expect(true.B);assert(d.io.memoryRequest.bits.peek().litValue==offer);d.io.out.valid.expect(false.B)}
      t.memory.holdRequests=false;t.check(4)
      t.install(i,s);t.query();t.check()
      info("IMAGE_SAMPLE_DRAIN cancellationCuts14=true heldDescriptorOffer9=true acceptedChildrenDrain=true healthyReuse=true noPartialColor=true")
    }
  }
}
