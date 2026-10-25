package opengpu.graphics

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable
import scala.util.Random

class TypedTilePixelBackendSpec extends AnyFlatSpec {
  protected def parallelColors: Boolean=false
  protected def parallelDepth: Boolean=false
  protected def nativeSamples: Boolean=false
  protected def streamingWordPorts: Int=4
  private def model(wordPorts: Int=streamingWordPorts): TypedTilePixelBackend=
    new TypedTilePixelBackend(TypedTileColorConfig(parallelLanes=parallelColors,
      parallelDepth=parallelDepth,nativeSamples=nativeSamples,streamingWordPorts=wordPorts))
  import TypedColorReference._
  private case class View(f: Int,offset: Int,load: Int=1,z: BigInt=fp(0.75),st: Int=5)
  private case class Face(function: Int=6,reference: Int=7,readMask: Int=255,writeMask: Int=255,fail: Int=0,zfail: Int=0,zpass: Int=0)
  private case class Tests(depth: Boolean=true,write: Boolean=true,function: Int=0,stencil: Boolean=true,front: Face=Face(),back: Face=Face())
  private case class Expected(tested: Int,passed: Int,sfail: Int,zfail: Int,depth: Int,stencil: Int,reads: Int,fills: Int,writes: Int)
  private def zero(d: Data): Unit=d match{
    case r: Record=>r.elements.values.foreach(zero)
    case v: Vec[_]=>v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case _=>()
  }
  private class Driver(val d: TypedTilePixelBackend){
    val p=TypedTileColorConfig();zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    var mode=2;var tileLog2=3;var primary=View(4,0);var secondary=View(0,0);var colorNames=Vector("RGBA8");var colorOffsets=Vector(1024)
    val model=mutable.Map.empty[Int,Int];val backing=mutable.Map.empty[Int,Int];val validDS=mutable.Set.empty[Int];val changed=mutable.Set.empty[Int]
    var completedWrites=BigInt(0);var completedFills=BigInt(0);var submittedQuads=0
    var cycle=0;var refills=0;var blockRefill=false;var fault: Int=>Boolean=_=>false
    var pending: Option[(Int,BigInt,Boolean)]=None;var offered: Option[(BigInt,Boolean)]=None
    def word(map: mutable.Map[Int,Int],address: Int): BigInt=(0 until 8).foldLeft(BigInt(0))((v,b)=>v|(BigInt(map.getOrElse(address*8+b,0))<<(8*b)))
    def get(address: Int,bytes: Int): BigInt=(0 until bytes).foldLeft(BigInt(0))((v,b)=>v|(BigInt(model.getOrElse(address+b,0))<<(8*b)))
    def put(address: Int,bytes: Int,value: BigInt): Unit=for(b<-0 until bytes)model(address+b)=((value>>(8*b))&255).toInt
    def tick(): Unit={
      d.io.refill.ready.poke((!blockRefill && cycle%3!=0).B)
      if(offered.isEmpty && pending.exists(_._1<=cycle)){offered=pending.map(x=>(x._2,x._3));pending=None}
      d.io.refillReply.valid.poke(offered.nonEmpty.B);offered.foreach{case(data,f)=>d.io.refillReply.bits.data.poke(data.U);d.io.refillReply.bits.fault.poke(f.B)}
      if(offered.nonEmpty && d.io.refillReply.ready.peek().litToBoolean)offered=None
      if(d.io.refill.valid.peek().litToBoolean && d.io.refill.ready.peek().litToBoolean){val a=d.io.refill.bits.address.peek().litValue.toInt
        assert(pending.isEmpty && offered.isEmpty);pending=Some((cycle+4,word(backing,a),fault(a)));refills+=1}
      d.clock.step();cycle+=1
    }
    def until(condition: => Boolean): Unit={var n=0;while(!condition && n<20000){tick();n+=1};assert(condition,s"late pixel stalled cycle=$cycle refills=$refills")}
    def identity(q: GraphicsWorkIdentity): Unit={q.context.poke(7.U);q.pass.poke(8.U);q.generation.poke(9.U)}
    def begin(a: View=View(4,0),b: View=View(0,0),m: Int=2,colors: Vector[String]=Vector("RGBA8"),tile: Int=3): Unit={
      prepare(a,b,m,colors,tile);submitBegin()
    }
    def prepare(a: View,b: View,m: Int=2,colors: Vector[String]=Vector("RGBA8"),tile: Int=3): Unit={
      tileLog2=tile
      completedWrites=0;completedFills=0;submittedQuads=0
      primary=a;secondary=b;mode=m;colorNames=colors;model.clear();backing.clear();validDS.clear();changed.clear()
      val q=d.io.begin.bits;zero(q);q.version.poke(1.U);q.colors.version.poke(1.U);identity(q.colors.identity)
      q.colors.tileLog2.poke(tile.U);q.colors.tileWidth.poke((1<<tile).U);q.colors.tileHeight.poke((1<<tile).U);q.colors.sampleMode.poke(m.U)
      val count=1<<(2*tile+m)
      def init(v: View,target: TypedDepthView): Unit={target.format.poke(v.f.U);target.offset.poke(v.offset.U);target.loadOp.poke(v.load.U)
        target.clearDepth.poke(v.z.U);target.clearStencil.poke(v.st.U)
        if(v.f!=0)for(i<-0 until count){val z=if(TypedDepthReference.hasDepth(v.f))TypedDepthReference.encode(v.z,v.f)else BigInt(0)
          val st=if(TypedDepthReference.hasStencil(v.f))BigInt(v.st)<<TypedDepthReference.stencilShift(v.f)else BigInt(0)
          val pad=if(v.load==0 && v.f==2)BigInt(0xa5)<<24 else if(v.load==0 && v.f==5)BigInt("abcdef",16)<<40 else BigInt(0)
          put(v.offset+i*TypedDepthReference.bytes(v.f),TypedDepthReference.bytes(v.f),if(v.load==2)BigInt(0)else z|st|pad)}
      }
      init(a,q.depth);init(b,q.stencil)
      var offset=Seq(a,b).filter(_.f!=0).map(v=>v.offset+count*TypedDepthReference.bytes(v.f)).foldLeft(0)(math.max)
      offset=(offset+63)& ~63;q.colors.reservedBytes.poke(offset.U);colorOffsets=Vector.empty
      for((name,i)<-colors.zipWithIndex){val f=format(name);val c=q.colors.colors(i);c.enabled.poke(true.B);c.format.poke(TypedColorFormat.id(name).U)
        c.offset.poke(offset.U);c.channelMask.poke(15.U);c.loadOp.poke(1.U);colorOffsets :+=offset
        val clear=Vector(fp(0.1),fp(0.2),fp(0.3),fp(1));c.clear.zip(clear).foreach{case(x,y)=>x.poke(y.U)}
        val value=encode(name,clear)._1
        for(j<-0 until count*f.bytes)model(offset+j)=((value>>(8*(j%f.bytes)))&255).toInt
        offset+=count*f.bytes
      }
      backing++=model
    }
    def submitBegin(): Unit={d.io.begin.valid.poke(true.B);until(d.io.begin.ready.peek().litToBoolean);tick();d.io.begin.valid.poke(false.B)
      until(d.io.in.ready.peek().litToBoolean || d.io.completion.valid.peek().litToBoolean)}
    def memory(address: Int): (BigInt,Boolean,Boolean)={
      d.io.maintenance.poke(true.B);d.io.memory.bits.address.poke(address.U);d.io.memory.bits.write.poke(false.B);d.io.memory.bits.fill.poke(false.B)
      d.io.memory.valid.poke(true.B);until(d.io.memory.ready.peek().litToBoolean);tick();d.io.memory.valid.poke(false.B)
      until(d.io.reply.valid.peek().litToBoolean);d.io.reply.bits.fault.expect(false.B)
      val value=(d.io.reply.bits.data.peek().litValue,d.io.reply.bits.available.peek().litToBoolean,d.io.reply.bits.dirty.peek().litToBoolean)
      val held=d.io.reply.bits.peek().litValue;for(_<-0 until 2){tick();assert(d.io.reply.bits.peek().litValue==held)}
      d.io.reply.ready.poke(true.B);tick();d.io.reply.ready.poke(false.B);d.io.maintenance.poke(false.B);value
    }
    private def sourceColor(i: Int,l: Int,seed: Int): Vector[BigInt]=if(format(colorNames(i)).kind==0)Vector(fp(0.15*(seed+l+1)),fp(0.5),fp(1),fp(0.75))
      else Vector(BigInt("ffffffff",16),BigInt(0x80000000L+l),BigInt(seed*100+l),BigInt(l+1))
    def packet(seed: Int=0,state: Tests=Tests(),front: Boolean=true,z: BigInt=fp(0.25),masks: Vector[Int]=Vector(15,1,0,0),allLanes: Boolean=false,apply: Boolean=true,depthValues: Option[Vector[Vector[BigInt]]]=None,overrideDepth: Boolean=true,blendState: Option[TypedBlendReference.State]=None,origin: (Int,Int)=(0,0)): Expected={
      val b=d.io.in.bits;zero(b);val q=b.color.quad;identity(q.identity);q.sequence.poke((100+seed).U);q.primitiveId.poke((20+seed).U)
      q.partIndex.poke((seed%7).U);q.instanceId.poke(2.U);q.sampleMode.poke(mode.U);q.colorSlots.poke(colorNames.size.U);q.frontFacing.poke(front.B)
      blendState.foreach(s=>TypedBlendReference.poke(b.color.blend,s))
      b.state.version.poke(1.U);b.state.depthEnable.poke(state.depth.B);b.state.depthWrite.poke(state.write.B);b.state.depthFunction.poke(state.function.U)
      b.state.stencilEnable.poke(state.stencil.B)
      def face(a: TypedStencilFace,f: Face): Unit={a.function.poke(f.function.U);a.reference.poke(f.reference.U);a.readMask.poke(f.readMask.U)
        a.writeMask.poke(f.writeMask.U);a.fail.poke(f.fail.U);a.depthFail.poke(f.zfail.U);a.depthPass.poke(f.zpass.U)}
      face(b.state.front,state.front);face(b.state.back,state.back)
      for((name,i)<-colorNames.zipWithIndex)q.colorTypes(i).poke(format(name).kind.U)
      var tested=0;var passed=0;var sfail=0;var zfail=0;var dz=0;var ds=0;var reads=0;var fills=0;var writes=0
      val doDepth=state.depth && TypedDepthReference.hasDepth(primary.f)
      val stencilView=if(TypedDepthReference.hasStencil(primary.f))primary else secondary
      val doStencil=state.stencil && TypedDepthReference.hasStencil(stencilView.f)
      val f=if(front)state.front else state.back
      def read(v: View,index: Int): Unit={val address=(v.offset+index*TypedDepthReference.bytes(v.f))/8;reads+=1
        if(!validDS(address)){validDS+=address;fills+=1}}
      for(l<-0 until 4){val x=origin._1+l%2;val y=origin._2+l/2;val coverage=if(l==3 && !allLanes)0 else(1<<(1<<mode))-1
        val selected=coverage&masks(l);val r=q.lanes(l)
        r.x.poke(x.S);r.y.poke(y.S);r.depth.poke(z.U);r.depthOverride.poke(overrideDepth.B);r.sampleDepthValid.poke(depthValues.nonEmpty.B)
        for(s<-0 until 4)r.sampleDepth(s).poke(depthValues.map(_(l)(s)).getOrElse(BigInt(0)).U);r.helper.poke((coverage==0).B)
        r.coverageMask.poke(coverage.U);r.colorMask.poke((if(coverage==0)0 else(1<<colorNames.size)-1).U);b.color.samples(l).poke(selected.U)
        for(i<-colorNames.indices)r.colors(i).zip(sourceColor(i,l,seed)).foreach{case(a,b)=>a.poke(b.U)}
        if(apply)for(s<-0 until(1<<mode) if (selected&(1<<s))!=0){tested+=1;val index=((y*(1<<tileLog2)+x)<<mode)+s
          val primaryAddress=primary.offset+index*TypedDepthReference.bytes(primary.f)
          val stencilAddress=stencilView.offset+index*TypedDepthReference.bytes(stencilView.f)
          if(doStencil)read(stencilView,index)
          val oldS=if(doStencil)((get(stencilAddress,TypedDepthReference.bytes(stencilView.f))>>TypedDepthReference.stencilShift(stencilView.f))&255).toInt else 0
          val sp= !doStencil || TypedDepthReference.compare(f.reference&f.readMask,oldS&f.readMask,f.function)
          if(sp && doDepth && !(doStencil && stencilView==primary))read(primary,index)
          val oldZ=if(doDepth)get(primaryAddress,TypedDepthReference.bytes(primary.f))&TypedDepthReference.depthMask(primary.f)else BigInt(0)
          val effective=if(depthValues.nonEmpty && !overrideDepth)depthValues.get(l)(s)else z
          val newZ=if(doDepth)TypedDepthReference.encode(effective,primary.f)else BigInt(0)
          val dp= !doDepth || !sp || TypedDepthReference.compare(newZ,oldZ,state.function,primary.f==3 || primary.f==5)
          val pass=sp&&dp;if(!sp)sfail+=1 else if(!dp)zfail+=1
          val op=if(!sp)f.fail else if(!dp)f.zfail else f.zpass
          val n=(TypedDepthReference.operation(oldS,f.reference,op)&f.writeMask)|(oldS&(~f.writeMask&255))
          val updateS=doStencil && n!=oldS;val updateZ=doDepth && pass && state.write
          if(updateZ){val old=get(primaryAddress,TypedDepthReference.bytes(primary.f));val mask=TypedDepthReference.depthMask(primary.f)
            put(primaryAddress,TypedDepthReference.bytes(primary.f),(old& ~mask)|(newZ&mask));dz+=1}
          if(updateS){val old=get(stencilAddress,TypedDepthReference.bytes(stencilView.f));val shift=TypedDepthReference.stencilShift(stencilView.f)
            put(stencilAddress,TypedDepthReference.bytes(stencilView.f),(old& ~(BigInt(255)<<shift))|(BigInt(n)<<shift));ds+=1}
          if(updateZ || (updateS && stencilView==primary)){writes+=1;changed+=primaryAddress/8}
          if(updateS && stencilView!=primary){writes+=1;changed+=stencilAddress/8}
          if(pass){passed+=1
            for(i<-colorNames.indices){val f=format(colorNames(i));val a=colorOffsets(i)+index*f.bytes;val source=sourceColor(i,l,seed)
              val value=blendState.map(s=>TypedBlendReference.blend(colorNames(i),source,get(a,f.bytes),s)).getOrElse(source)
              put(a,f.bytes,encode(colorNames(i),value)._1)
              for(w<-a/8 to(a+f.bytes-1)/8)changed+=w}
          }
        }
      }
      Expected(tested,passed,sfail,zfail,dz,ds,reads,fills,writes)
    }
    def submit(): Unit={submittedQuads+=1;d.io.in.valid.poke(true.B);until(d.io.in.ready.peek().litToBoolean);tick();d.io.in.valid.poke(false.B)}
    def output(code: Int=0,expected: Option[Expected]=None,holdClocks: Int=3): Unit={
      until(d.io.out.valid.peek().litToBoolean);val r=d.io.out.bits;r.status.expect(code.U)
      expected.foreach{e=>r.testedSamples.expect(e.tested.U);r.passingSamples.expect(e.passed.U);r.stencilRejected.expect(e.sfail.U);r.depthRejected.expect(e.zfail.U)
        r.depthUpdates.expect(e.depth.U);r.stencilUpdates.expect(e.stencil.U);r.dsReads.expect(e.reads.U);r.dsFills.expect(e.fills.U);r.dsWrites.expect(e.writes.U)}
      completedWrites+=r.dsWrites.peek().litValue;completedFills+=r.dsFills.peek().litValue
      val held=r.peek().litValue;for(_<-0 until holdClocks){tick();assert(r.peek().litValue==held);if(!nativeSamples)d.io.in.ready.expect(false.B)}
      d.io.out.ready.poke(true.B);tick();d.io.out.ready.poke(false.B)
    }
    def verify(): Unit={for(a<-changed.toVector.sorted){val(data,available,_)=memory(a);assert(available)
      assert(data==word(model,a),s"late pixel word$a observed=${data.toString(16)} expected=${word(model,a).toString(16)}")}}
    def finish(code: Int=0,automatic: Boolean=false): Unit={
      if(!automatic){d.io.end.valid.poke(true.B);d.io.end.bits.poke(true.B);until(d.io.end.ready.peek().litToBoolean);tick();d.io.end.valid.poke(false.B)}
      until(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.status.expect(code.U)
      d.io.completion.bits.inputQuads.expect(submittedQuads.U);d.io.completion.bits.dsWrites.expect(completedWrites.U);d.io.completion.bits.dsFills.expect(completedFills.U)
      val held=d.io.completion.bits.peek().litValue;for(_<-0 until 4){tick();assert(d.io.completion.bits.peek().litValue==held);d.io.begin.ready.expect(false.B)}
      assert(pending.isEmpty && offered.isEmpty);d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B);d.io.busy.expect(false.B)
    }
  }

  it should "qualify shared typed colors with every depth stencil layout and preserve load padding and face snapshots" in {
    simulate(model()){d=>val t=new Driver(d);var count=0
      val layouts=Vector((View(1,0),View(6,512)),(View(2,0,0),View(6,1024,0)),(View(3,0,0),View(0,0)),
        (View(4,0),View(0,0)),(View(5,0,0),View(0,0)),(View(6,0),View(0,0)),(View(0,0),View(0,0)))
      for((a,b)<-layouts){t.begin(a,b,colors=Vector("RGBA8","RGBA32UI","RGBA16F","RGBA8I"))
        val state=Tests(front=Face(writeMask=0x55,zpass=6,zfail=5),back=Face(reference=255,writeMask=0xaa,zpass=2,zfail=7))
        for(i<-0 until 3){val e=t.packet(i,state,i%2==0,fp(if(i==1)0.9 else 0.25));t.submit();d.io.in.bits.state.depthEnable.poke(false.B);t.output(expected=Some(e));count+=1}
        t.verify();t.finish()
      }
      info(s"TYPED_PIXEL layouts=7 quads=$count typedMRT=4 depth16and24and32F=true combinedD24S8D32FS8=true separateStencil8=true paddingPreserved=true frontBackSnapshots=true residentColorVisibility=true")
    }
  }
  it should "implement every compare and stencil operation with ordered overlap and disabled depth writes" in {
    simulate(model()){d=>val t=new Driver(d);t.begin(View(4,0));var count=0
      for(f<-0 until 8;op<-0 until 8){val face=Face(function=f,reference=(f*31+op)&255,readMask=0x5f,writeMask=0xa7,fail=op,zfail=op,zpass=op)
        val s=Tests(depth=f%3!=0,write=op%3!=0,function=(f+op)%8,front=face,back=face.copy(reference=255-face.reference))
        val e=t.packet(count,s,front=count%2==0,z=fp(if(count%2==0)0.4 else 0.8),masks=Vector(15,3,5,0));t.submit();t.output(expected=Some(e));count+=1}
      t.verify();t.finish()
      info(s"TYPED_PIXEL changingStateQuads=$count functions=8 operations=8 overlapOrder=true disabledDepthNeverWrites=true maskedReferenceAndWrites=true sampleRejectCounts=true")
    }
  }
  it should "skip separate depth refills on stencil rejection and use clean load fills" in {
    simulate(model()){d=>val t=new Driver(d)
      t.begin(View(1,0,0),View(6,512));t.fault=a=>a<64
      val fail=Tests(front=Face(function=4,reference=1,fail=6))
      val e=t.packet(state=fail,masks=Vector(1,0,0,0));t.submit();t.output(expected=Some(e));assert(t.refills==0)
      assert(!t.memory(0)._2);t.verify();t.finish();t.fault=_=>false
      t.begin(View(3,0,0),View(0,0));val reject=Tests(write=false,function=7,stencil=false)
      val n=t.packet(state=reject,masks=Vector(1,0,0,0));t.submit();t.output(expected=Some(n));assert(t.refills>0)
      val loaded=t.memory(0);assert(loaded._2 && !loaded._3);t.finish()
      info("TYPED_PIXEL stencilFirst=true noRejectedDepthRefill=true cleanDepthFill=true readOnlyDepthRemainsClean=true")
    }
  }
  it should "reject invalid layouts or quads before depth side effects and preserve unordered floating comparisons" in {
    simulate(model()){d=>val t=new Driver(d)
      for((a,b)<-Seq((View(7,0),View(0,0)),(View(4,0),View(6,1024)),(View(1,1),View(0,0)),(View(1,0),View(2,1024)))){
        t.prepare(a,b);t.submitBegin();t.finish(1,true)}
      t.begin(View(4,0));t.packet(apply=false);d.io.in.bits.color.quad.colorTypes(0).poke(1.U);t.submit();t.output(6,Some(Expected(0,0,0,0,0,0,0,0,0)));t.finish(6,true)
      t.begin(View(4,0));t.packet(apply=false);d.io.in.bits.color.blend.enable.poke(true.B);d.io.in.bits.color.blend.version.poke(1.U)
      d.io.in.bits.color.blend.srcRGB.poke(15.U);t.submit();t.output(6,Some(Expected(0,0,0,0,0,0,0,0,0)));t.finish(6,true)
      t.begin(View(3,0));t.packet(z=BigInt("7fc00001",16),apply=false);t.submit();t.output(7,Some(Expected(0,0,0,0,0,0,0,0,0)));t.finish(7,true)
      for(f<-Seq(0,4,5,6)){t.begin(View(3,0,0,z=BigInt("7fc00001",16)));val e=t.packet(state=Tests(function=f,stencil=false));t.submit();t.output(expected=Some(e));t.verify();t.finish()}
      info("TYPED_PIXEL invalidLayouts=4 invalidQuadBeforeDS=true invalidBlendBeforeDS=true sourceNaNRejected=true storedNaNUnordered=true healthyRebind=true")
    }
  }
  it should "drain cancelled depth and color refills and retain a held final pixel completion" in {
    simulate(model()){d=>val t=new Driver(d)
      t.begin(View(5,0,0));t.blockRefill=true;t.packet(apply=false);t.submit();t.until(d.io.refill.valid.peek().litToBoolean)
      val held=d.io.refill.bits.peek().litValue;d.io.cancel.poke(true.B)
      for(_<-0 until 8){t.tick();assert(d.io.refill.bits.peek().litValue==held);d.io.completion.valid.expect(false.B)}
      d.io.cancel.poke(false.B);t.blockRefill=false;t.output(4);t.finish(4,true)
      t.begin(View(5,0,0));t.fault=_=>true;t.packet(apply=false);t.submit();t.until(d.io.out.valid.peek().litToBoolean)
      d.io.out.bits.dsWrites.expect(0.U);d.io.out.bits.dsFills.expect(0.U);t.output(3);t.finish(3,true);t.fault=_=>false
      t.prepare(View(0,0),View(0,0));d.io.begin.bits.colors.colors(0).loadOp.poke(0.U);t.submitBegin()
      t.blockRefill=true;t.packet(apply=false);t.submit();t.until(d.io.refill.valid.peek().litToBoolean)
      val colorRefill=d.io.refill.bits.peek().litValue;d.io.cancel.poke(true.B)
      for(_<-0 until 8){t.tick();assert(d.io.refill.bits.peek().litValue==colorRefill);d.io.completion.valid.expect(false.B)}
      d.io.cancel.poke(false.B);t.blockRefill=false;t.output(4);t.finish(4,true)
      t.begin(View(4,0));val e=t.packet();t.submit();t.until(d.io.out.valid.peek().litToBoolean)
      val out=d.io.out.bits.peek().litValue;d.io.cancel.poke(true.B)
      for(_<-0 until 8){t.tick();assert(d.io.out.bits.peek().litValue==out);d.io.completion.valid.expect(false.B)}
      t.output(expected=Some(e));d.io.cancel.poke(false.B);t.finish(4,true)
      t.begin(View(4,0));val healthy=t.packet();t.submit();t.output(expected=Some(healthy));t.verify();t.finish()
      info("TYPED_PIXEL heldDepthRefillCancel=true heldColorRefillCancel=true depthRefillFault=true heldPixelCompletion=true childMemoryDrain=true cancellationLatched=true healthyReuse=true")
    }
  }
  it should "test independent sample depths in every depth layout and broadcast explicit shader overrides" in {
    simulate(model()){d=>val t=new Driver(d);var quads=0
      val values=Vector(Vector(0.125,0.625,0.875,0.375),Vector(0.875,0.375,0.125,0.625),Vector(0.25,0.5,0.75,1.0),Vector(0.75,0.25,0.5,0.0)).map(_.map(fp))
      for(f<-1 to 5;mode<-0 to 2){val separate=if(f<=2)View(6,2048)else View(0,0)
        t.begin(View(f,0,z=fp(0.5)),separate,m=mode,colors=Vector("RGBA8","RGBA32UI","RGBA16F","RGBA8I"))
        val tests=Tests(function=0,front=Face(function=6,zfail=6,zpass=2),back=Face(function=6,zfail=7,zpass=5))
        val first=t.packet(state=tests,masks=Vector(15,15,15,15),allLanes=true,depthValues=Some(values),overrideDepth=false);t.submit();t.output(expected=Some(first));quads+=1
        if(mode!=0)assert(first.passed>0 && first.zfail>0)
        val second=t.packet(state=tests.copy(function=3),front=false,z=fp(0.2),masks=Vector(15,7,5,3),allLanes=true,depthValues=Some(values),overrideDepth=true)
        t.submit();t.output(expected=Some(second));quads+=1;t.verify();t.finish()
      }
      info(s"TYPED_SAMPLE_DEPTH layouts=5 modes=3 quads=$quads independentSampleCompare=true mixedSampleVisibility=true shaderOverrideBroadcast=true typedMRT=4 stencilOutcomes=true paddingPreserved=true")
    }
  }
  it should "preflight only selected effective depths and ignore inactive NaNs or overridden sample metadata" in {
    simulate(model()){d=>val t=new Driver(d);val nan=BigInt("7fc00001",16)
      val values=Vector.fill(4)(Vector(fp(0.1),nan,nan,nan));val tests=Tests(function=1,stencil=false)
      t.begin(View(3,0));val active=t.packet(state=tests,z=nan,masks=Vector(1,0,0,0),depthValues=Some(values),overrideDepth=false)
      t.submit();t.output(expected=Some(active));t.verify();t.finish()
      t.begin(View(3,0));val overrideValues=Vector.fill(4)(Vector.fill(4)(nan))
      val overridden=t.packet(state=tests,z=fp(0.1),masks=Vector(15,0,0,0),depthValues=Some(overrideValues),overrideDepth=true)
      t.submit();t.output(expected=Some(overridden));t.verify();t.finish()
      t.begin(View(3,0));t.packet(state=tests,masks=Vector(3,0,0,0),apply=false,depthValues=Some(values),overrideDepth=false)
      t.submit();t.output(7,Some(Expected(0,0,0,0,0,0,0,0,0)));t.finish(7,true)
      t.begin(View(4,0));val disabled=t.packet(state=tests.copy(depth=false),masks=Vector(15,0,0,0),depthValues=Some(overrideValues),overrideDepth=false)
      t.submit();t.output(expected=Some(disabled));t.verify();t.finish()
      info("TYPED_SAMPLE_DEPTH selectedNaNBeforeSideEffects=true unselectedNaNIgnored=true unusedCenterNaNIgnored=true overrideIgnoresRasterNaN=true disabledDepthIgnoresNaN=true healthyReuse=true")
    }
  }

  it should "snapshot pass totals after an unheld final quad reply including late cancellation and immediate reuse" in {
    simulate(model()){d=>val t=new Driver(d)
      for(cancel<-Seq(false,true,false)){
        t.begin(View(4,0),m=0)
        val expected=t.packet(masks=Vector(1,1,1,1),allLanes=true);t.submit()
        t.until(d.io.out.valid.peek().litToBoolean)
        if(cancel)d.io.cancel.poke(true.B)
        t.output(expected=Some(expected),holdClocks=0)
        t.finish(if(cancel)4 else 0,automatic=cancel);d.io.cancel.poke(false.B)
      }
      info("TYPED_PIXEL_COUNTERS unheldFinalReply=true exactPassTotals=true lateCancel=true immediateReuse=true")
    }
  }

  it should "measure matched depth stencil and typed color streams with exact stored outputs" in {
    simulate(model()){d=>val t=new Driver(d)
      for((name,mode,colors,blend)<-Seq(("d24s8-rgba8",0,Vector("RGBA8"),None),
          ("d24s8-mrt",2,Vector("RGBA8","RGB8","RGBA16F","SRGB8_ALPHA8"),None),
          ("d24s8-blended-rgba8",0,Vector("RGBA8"),Some(TypedBlendReference.State())))){
        t.prepare(View(4,0),View(0,0),m=mode,colors=colors);t.submitBegin();val first=t.cycle
        var samples=0
        for(i<-0 until 32){val expected=t.packet(seed=i%8,state=Tests(function=1),
            masks=Vector.fill(4)((1<<(1<<mode))-1),allLanes=true,blendState=blend)
          samples+=expected.tested;t.submit();t.output(expected=Some(expected))}
        val cycles=t.cycle-first;t.verify();t.finish()
        info(s"TYPED_PIXEL_COMBINED parallel=$parallelColors parallelDepth=$parallelDepth wordPorts=$streamingWordPorts profile=$name quads=32 samples=$samples cycles=$cycles heldCompletionClocks=3 exactImage=true")
      }
    }
  }
  if(nativeSamples){
    it should "drain generic packing across cancellation windows and a held color refill fault before healthy reuse" in {
      simulate(model()){d=>val t=new Driver(d)
        val formats=Vector("RGBA8","RGB8","RGBA16F","SRGB8_ALPHA8")
        for(delay<-Seq(8,40,80)){
          t.begin(View(3,0),View(0,0),m=2,colors=formats)
          t.packet(apply=false,allLanes=true,masks=Vector.fill(4)(15),blendState=Some(TypedBlendReference.State()))
          t.submit();for(_<-0 until delay)t.tick()
          d.io.cancel.poke(true.B);var held=Option.empty[BigInt]
          for(_<-0 until 12){
            held.foreach(value=>{d.io.out.valid.expect(true.B);assert(d.io.out.bits.peek().litValue==value)})
            if(d.io.out.valid.peek().litToBoolean)held=Some(d.io.out.bits.peek().litValue)
            d.io.completion.valid.expect(false.B);t.tick()
          }
          d.io.cancel.poke(false.B);t.until(d.io.out.valid.peek().litToBoolean)
          val status=d.io.out.bits.status.peek().litValue.toInt;assert(status==0 || status==4)
          t.output(status);t.finish(4,automatic=true)
          t.begin(View(3,0),View(0,0),m=2,colors=formats)
          val healthy=t.packet(allLanes=true,masks=Vector.fill(4)(15),blendState=Some(TypedBlendReference.State()))
          t.submit();t.output(expected=Some(healthy));t.verify();t.finish()
        }
        t.prepare(View(0,0),View(0,0),m=2,colors=formats)
        for(c<-0 until 4)d.io.begin.bits.colors.colors(c).loadOp.poke(0.U)
        t.submitBegin();t.blockRefill=true;t.fault=_=>true
        t.packet(apply=false,allLanes=true,masks=Vector.fill(4)(15),state=Tests(depth=false,write=false,stencil=false))
        t.submit();t.until(d.io.refill.valid.peek().litToBoolean)
        val held=d.io.refill.bits.peek().litValue
        for(_<-0 until 16){t.tick();assert(d.io.refill.bits.peek().litValue==held);d.io.completion.valid.expect(false.B)}
        t.blockRefill=false;t.output(3);t.finish(3,automatic=true);t.fault=_=>false
        t.begin(View(3,0),View(0,0),m=2,colors=formats)
        val healthy=t.packet(allLanes=true,masks=Vector.fill(4)(15),blendState=Some(TypedBlendReference.State()))
        t.submit();t.output(expected=Some(healthy));t.verify();t.finish()
        info("STREAMING_PIXEL_PACK_DRAIN cancelWindows=3 immutableHeldReplies=true heldColorRefillFault=true exactPassTotals=true healthyReuses=4 exactHealthyImages=true")
      }
    }
  }
  if(nativeSamples){
    it should "preserve distinct loaded samples and packed masked neighbors across all typed formats" in {
      simulate(model()){d=>val t=new Driver(d);var quads=0;val updatedFormats=mutable.Set.empty[String]
        for(group<-TypedColorFormat.formats.map(_.name).grouped(4);mode<-Seq(0,2);absentOnly<-(if(group.contains("R8"))Seq(false,true)else Seq(false))){
          val names=group.toVector;val masks=names.zipWithIndex.map{case(name,c)=>if(absentOnly && name=="R8")2 else if(absentOnly && name=="RGB8")8 else Vector(5,3,9,7)(c)}
          t.prepare(View(3,0),View(0,0),m=mode,colors=names)
          for((name,c)<-names.zipWithIndex){
            d.io.begin.bits.colors.colors(c).loadOp.poke(0.U)
            d.io.begin.bits.colors.colors(c).channelMask.poke(masks(c).U)
            val f=format(name)
            for(index<-0 until(64<<mode)){
              val values=Vector.tabulate(4)(channel=>if(f.kind==0)fp(((index+channel)%29).toDouble/28)else BigInt(index*17+channel))
              t.put(t.colorOffsets(c)+index*f.bytes,f.bytes,encode(name,values)._1)
            }
          }
          t.backing.clear();t.backing++=t.model
          t.fault=word=>names.zipWithIndex.exists{case(name,c)=>
            encode(name,Vector.fill(4)(BigInt(0)),masks(c))._2==0 && word*8>=t.colorOffsets(c) &&
              word*8<t.colorOffsets(c)+(64<<mode)*format(name).bytes
          }
          t.submitBegin()
          for(seed<-0 until 2){
            val old=t.model.toMap;val previouslyChanged=t.changed.toSet
            val expected=t.packet(seed=seed,state=Tests(depth=false,write=false,stencil=false),
              masks=Vector.fill(4)((1<<(1<<mode))-1),allLanes=true,blendState=Some(TypedBlendReference.State(11,12,13,14)))
            var colorWrites=0;var writtenBits=0;val effects=mutable.Set.empty[Int]
            for((name,c)<-names.zipWithIndex;l<-0 until 4;sample<-0 until(1<<mode)){
              val f=format(name);val index=((l/2)*8+l%2)*(1<<mode)+sample
              val address=t.colorOffsets(c)+index*f.bytes
              val before=(0 until f.bytes).foldLeft(BigInt(0))((v,b)=>v|(BigInt(old(address+b))<<(8*b)))
              val mask=encode(name,Vector.fill(4)(BigInt(0)),masks(c))._2
              if(mask!=0)updatedFormats+=name
              t.put(address,f.bytes,(before& ~mask)|(t.get(address,f.bytes)&mask))
              val shifted=mask<<((address%8)*8)
              for(word<-0 until((address%8+f.bytes+7)/8) if ((shifted>>(word*64))&((BigInt(1)<<64)-1))!=0){
                effects+=address/8+word;colorWrites+=1
              }
              writtenBits+=mask.bitCount
            }
            t.changed.clear();t.changed++=previouslyChanged;t.changed++=effects
            t.submit();t.until(d.io.out.valid.peek().litToBoolean)
            val activeColors=names.zipWithIndex.count{case(name,c)=>encode(name,Vector.fill(4)(BigInt(0)),masks(c))._2!=0}
            d.io.out.bits.colors.touchedSamples.expect((if(activeColors>0)expected.passed else 0).U)
            d.io.out.bits.colors.completedLocations.expect((expected.passed*activeColors).U)
            d.io.out.bits.colors.wordWrites.expect(colorWrites.U);d.io.out.bits.colors.writtenBits.expect(writtenBits.U)
            t.output(expected=Some(expected));quads+=1
          }
          t.verify();t.finish()
        }
        assert(updatedFormats==TypedColorFormat.formats.map(_.name).toSet,"some formats exercised only an absent-channel no-op")
        info(s"STREAMING_PIXEL_FORMATS formats=36 updatedFormats=${updatedFormats.size} quads=$quads samples1x4x=true independentLoadedSamples=true packedNeighborMasks=true absentChannelsNeverRefill=true overlapAcrossQuads=true exactColorCounters=true")
      }
    }
  }
  if(nativeSamples && streamingWordPorts==8){
    it should "compare four and eight word ports with streaming packing and unchanged SRAM and arithmetic capacity" in {
      val clocks=mutable.Map.empty[(Int,Int),Int]
      for(ports<-Seq(4,8))simulate(model(wordPorts=ports)){d=>val t=new Driver(d)
        for(mode<-Seq(0,2)){
          t.prepare(View(3,0),View(0,0),m=mode,colors=Vector("RGBA8","RGB8","RGBA16F","SRGB8_ALPHA8"))
          t.submitBegin();val first=t.cycle
          for(i<-0 until 32){val expected=t.packet(seed=i%8,state=Tests(function=1),
            masks=Vector.fill(4)((1<<(1<<mode))-1),allLanes=true,blendState=Some(TypedBlendReference.State()))
            t.submit();t.output(expected=Some(expected))}
          clocks((ports,mode))=t.cycle-first;t.verify();t.finish()
          info(s"STREAMING_PACKED_WORD_PORTS ports=$ports sampleMode=$mode quads=32 cycles=${clocks((ports,mode))} fp32Units=100 sramBanks=16 exactImageAndTotals=true")
        }
      }
      assert(clocks((8,0))<=clocks((4,0)),"eight ports regressed matched 1x service")
      assert(clocks((8,2))*10<clocks((4,2))*9,"eight ports did not save 10% on matched streaming 4x service")
    }
    it should "retain auxiliary port refills through cancellation and fault drain before healthy reuse" in {
      simulate(model()){d=>val t=new Driver(d)
        for(cancel<-Seq(true,false)){
          t.prepare(View(3,0),View(0,0),m=2,colors=Vector("RGBA8","RGB8","RGBA16F","SRGB8_ALPHA8"))
          d.io.begin.bits.colors.colors(1).loadOp.poke(0.U)
          val first=t.colorOffsets(1);val end=first+256*format("RGB8").bytes
          t.fault=word=> !cancel && word*8>=first && word*8<end
          t.blockRefill=cancel;t.submitBegin()
          t.packet(seed=3,state=Tests(depth=false,write=false,stencil=false),allLanes=true,
            masks=Vector.fill(4)(15),apply=false,blendState=Some(TypedBlendReference.State()))
          t.submit()
          if(cancel){
            t.until(d.io.refill.valid.peek().litToBoolean)
            val held=d.io.refill.bits.peek().litValue
            assert(held*8>=first && held*8<end,"held refill did not belong to the auxiliary RGB8 port")
            d.io.cancel.poke(true.B)
            for(_<-0 until 6){d.io.refill.valid.expect(true.B);assert(d.io.refill.bits.peek().litValue==held)
              d.io.out.valid.expect(false.B);d.io.completion.valid.expect(false.B);t.tick()}
            d.io.cancel.poke(false.B);t.blockRefill=false
          }
          val code=if(cancel)4 else 3;t.output(code=code);t.finish(code=code,automatic=true)
          t.fault=_=>false;t.begin(View(3,0),View(0,0),m=2,colors=Vector("RGBA8","RGB8","RGBA16F","SRGB8_ALPHA8"))
          val expected=t.packet(seed=5,state=Tests(depth=false,write=false,stencil=false),allLanes=true,
            masks=Vector.fill(4)(15),blendState=Some(TypedBlendReference.State()))
          t.submit();t.output(expected=Some(expected));t.verify();t.finish()
        }
        info("STREAMING_PIXEL_AUX_DRAIN heldRefillImmutable=true auxiliaryRefillFault=true cancellationLatched=true noEarlyCompletion=true healthyExactReuse=true")
      }
    }
  }
  if(nativeSamples){
    it should "overlap native quads at the full sample rate while preserving logical counters and every canonical word" in {
      simulate(model()){d=>val t=new Driver(d)
        for(mode<-0 to 2){
          t.begin(m=mode,tile=4)
          def run(n: Int,base: Int): (Int,Int)={
            val expected=mutable.Queue.empty[(Int,Expected)]
            var sent=0;var retired=0;var offered= -1;var clocks=0
            val replies=mutable.ArrayBuffer.empty[Int]
            d.io.out.ready.poke(true.B)
            while(retired<n && clocks<n*(1<<mode)*20+1000){
              if(sent<n){if(offered!=sent){
                val seed=base+sent
                val e=t.packet(seed=seed,state=Tests(function=6,front=Face(zpass=5)),allLanes=true,
                  masks=Vector.fill(4)((1<<(1<<mode))-1),blendState=Some(TypedBlendReference.State()),
                  origin=((sent%8)*2,((sent/8)%8)*2))
                expected.enqueue((seed,e));offered=sent
              };d.io.in.valid.poke(true.B)}else d.io.in.valid.poke(false.B)
              if(d.io.in.valid.peek().litToBoolean && d.io.in.ready.peek().litToBoolean){sent+=1;t.submittedQuads+=1}
              if(d.io.out.valid.peek().litToBoolean){
                val(seed,e)=expected.dequeue();val r=d.io.out.bits
                r.status.expect(0.U);r.sequence.expect((100+seed).U);r.primitiveId.expect((20+seed).U);r.partIndex.expect((seed%7).U)
                r.testedSamples.expect(e.tested.U);r.passingSamples.expect(e.passed.U)
                r.depthUpdates.expect(e.depth.U);r.stencilUpdates.expect(e.stencil.U)
                r.dsReads.expect(e.reads.U);r.dsFills.expect(e.fills.U);r.dsWrites.expect(e.writes.U)
                r.colors.sequence.expect((100+seed).U);r.colors.touchedSamples.expect(e.passed.U)
                r.colors.completedLocations.expect(e.passed.U);r.colors.wordWrites.expect(e.passed.U)
                r.colors.writtenBits.expect((e.passed*32).U)
                t.completedWrites+=e.writes;t.completedFills+=e.fills;retired+=1;replies+=clocks
              }
              t.tick();clocks+=1
            }
            assert(sent==n && retired==n && expected.isEmpty)
            d.io.in.valid.poke(false.B);d.io.out.ready.poke(false.B)
            val longest=replies.foldLeft((0,0,-100)){case((best,count,last),now)=>val next=if(now-last==(1<<mode))count+1 else 1;(best.max(next),next,now)}._1
            (clocks,longest)
          }
          run(64,0);val hot=run(128,64)
          assert(hot._2==128,s"native complete-quad interval failed mode=$mode result=$hot")
          t.verify();t.finish()
          info(s"NATIVE_PIXEL_OWNER_RATE mode=$mode hotQuads=128 hotSamples=${512<<mode} cycles=${hot._1} completeQuadInterval=${1<<mode} consecutiveQuads=${hot._2} exactLogicalCounters=true exactCanonicalWords=true")
        }
      }
    }
    it should "bound a stalled native prefix, cancel it in order and retain early EOF ownership through healthy reuse" in {
      simulate(model()){d=>val t=new Driver(d);t.begin(m=0,tile=4)
        var sent=0;var blocked=0;var clocks=0;var offered= -1
        while(blocked<12 && clocks<1000){
          if(offered!=sent){t.packet(seed=sent,apply=false,allLanes=true,masks=Vector.fill(4)(1),origin=((sent%8)*2,((sent/8)%8)*2));offered=sent}
          d.io.in.valid.poke(true.B)
          if(d.io.in.ready.peek().litToBoolean){sent+=1;t.submittedQuads+=1;blocked=0}else blocked+=1
          t.tick();clocks+=1
        }
        assert(sent==68 && blocked==12,"held output did not bound native admission to 64 metadata, two results and two input slots")
        d.io.in.valid.poke(false.B);t.until(d.io.out.valid.peek().litToBoolean)
        val held=d.io.out.bits.peek().litValue;d.io.cancel.poke(true.B)
        for(_<-0 until 8){t.tick();assert(d.io.out.bits.peek().litValue==held);d.io.completion.valid.expect(false.B);d.io.in.ready.expect(false.B)}
        d.io.cancel.poke(false.B);var received=0;var cancelled=0
        while(received<sent && clocks<20000){
          val ready=clocks%3!=0;d.io.out.ready.poke(ready.B)
          if(d.io.out.valid.peek().litToBoolean){
            val r=d.io.out.bits;r.sequence.expect((100+received).U)
            val code=r.status.peek().litValue.toInt;assert(code==0 || code==4)
            if(ready){t.completedWrites+=r.dsWrites.peek().litValue;t.completedFills+=r.dsFills.peek().litValue
              received+=1;if(code==4)cancelled+=1}
          }
          d.io.completion.valid.expect(false.B);t.tick();clocks+=1
        }
        assert(received==sent && cancelled>0);d.io.out.ready.poke(false.B);t.finish(4,automatic=true)
        t.begin(m=2);val expected=mutable.Queue.empty[Expected]
        for(i<-0 until 16){expected.enqueue(t.packet(seed=i,state=Tests(function=6),allLanes=true));t.submit()}
        d.io.end.valid.poke(true.B);d.io.end.bits.poke(true.B)
        t.until(d.io.end.ready.peek().litToBoolean);t.tick();d.io.end.valid.poke(false.B)
        for(i<-0 until 16){d.io.completion.valid.expect(false.B)
          t.output(expected=Some(expected.dequeue()),holdClocks=i%3)}
        t.finish(automatic=true)
        info(s"NATIVE_PIXEL_OWNER_DRAIN acceptedPrefix=$sent orderedCancel=true immutableHeldQuad=true exactPassTotals=true earlyEOF=true healthyReuse=true")
      }
    }
  }

}

class NativeSampleTypedTilePixelBackendSpec extends TypedTilePixelBackendSpec {
  override protected def parallelColors: Boolean=true
  override protected def parallelDepth: Boolean=true
  override protected def nativeSamples: Boolean=true
  override protected def streamingWordPorts: Int=8
}
