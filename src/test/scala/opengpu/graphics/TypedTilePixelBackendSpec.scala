package opengpu.graphics

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable
import scala.util.Random

class TypedTilePixelBackendSpec extends AnyFlatSpec {
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
    var mode=2;var primary=View(4,0);var secondary=View(0,0);var colorNames=Vector("RGBA8");var colorOffsets=Vector(1024)
    val model=mutable.Map.empty[Int,Int];val backing=mutable.Map.empty[Int,Int];val validDS=mutable.Set.empty[Int];val changed=mutable.Set.empty[Int]
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
    def begin(a: View=View(4,0),b: View=View(0,0),m: Int=2,colors: Vector[String]=Vector("RGBA8")): Unit={
      prepare(a,b,m,colors);submitBegin()
    }
    def prepare(a: View,b: View,m: Int=2,colors: Vector[String]=Vector("RGBA8")): Unit={
      primary=a;secondary=b;mode=m;colorNames=colors;model.clear();backing.clear();validDS.clear();changed.clear()
      val q=d.io.begin.bits;zero(q);q.version.poke(1.U);q.colors.version.poke(1.U);identity(q.colors.identity)
      q.colors.tileLog2.poke(3.U);q.colors.tileWidth.poke(8.U);q.colors.tileHeight.poke(8.U);q.colors.sampleMode.poke(m.U)
      val count=64<<m
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
    def packet(seed: Int=0,state: Tests=Tests(),front: Boolean=true,z: BigInt=fp(0.25),masks: Vector[Int]=Vector(15,1,0,0),allLanes: Boolean=false,apply: Boolean=true): Expected={
      val b=d.io.in.bits;zero(b);val q=b.color.quad;identity(q.identity);q.sequence.poke((100+seed).U);q.primitiveId.poke((20+seed).U)
      q.partIndex.poke((seed%7).U);q.instanceId.poke(2.U);q.sampleMode.poke(mode.U);q.colorSlots.poke(colorNames.size.U);q.frontFacing.poke(front.B)
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
      for(l<-0 until 4){val x=l%2;val y=l/2;val coverage=if(l==3 && !allLanes)0 else(1<<(1<<mode))-1
        val selected=coverage&masks(l);val r=q.lanes(l)
        r.x.poke(x.S);r.y.poke(y.S);r.depth.poke(z.U);r.depthOverride.poke(true.B);r.helper.poke((coverage==0).B)
        r.coverageMask.poke(coverage.U);r.colorMask.poke((if(coverage==0)0 else(1<<colorNames.size)-1).U);b.color.samples(l).poke(selected.U)
        for(i<-colorNames.indices)r.colors(i).zip(sourceColor(i,l,seed)).foreach{case(a,b)=>a.poke(b.U)}
        if(apply)for(s<-0 until(1<<mode) if (selected&(1<<s))!=0){tested+=1;val index=((y*8+x)<<mode)+s
          val primaryAddress=primary.offset+index*TypedDepthReference.bytes(primary.f)
          val stencilAddress=stencilView.offset+index*TypedDepthReference.bytes(stencilView.f)
          if(doStencil)read(stencilView,index)
          val oldS=if(doStencil)((get(stencilAddress,TypedDepthReference.bytes(stencilView.f))>>TypedDepthReference.stencilShift(stencilView.f))&255).toInt else 0
          val sp= !doStencil || TypedDepthReference.compare(f.reference&f.readMask,oldS&f.readMask,f.function)
          if(sp && doDepth && !(doStencil && stencilView==primary))read(primary,index)
          val oldZ=if(doDepth)get(primaryAddress,TypedDepthReference.bytes(primary.f))&TypedDepthReference.depthMask(primary.f)else BigInt(0)
          val newZ=TypedDepthReference.encode(z,primary.f)
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
            for(i<-colorNames.indices){val f=format(colorNames(i));val a=colorOffsets(i)+index*f.bytes;put(a,f.bytes,encode(colorNames(i),sourceColor(i,l,seed))._1)
              for(w<-a/8 to(a+f.bytes-1)/8)changed+=w}
          }
        }
      }
      Expected(tested,passed,sfail,zfail,dz,ds,reads,fills,writes)
    }
    def submit(): Unit={d.io.in.valid.poke(true.B);until(d.io.in.ready.peek().litToBoolean);tick();d.io.in.valid.poke(false.B)}
    def output(code: Int=0,expected: Option[Expected]=None): Unit={
      until(d.io.out.valid.peek().litToBoolean);val r=d.io.out.bits;r.status.expect(code.U)
      expected.foreach{e=>r.testedSamples.expect(e.tested.U);r.passingSamples.expect(e.passed.U);r.stencilRejected.expect(e.sfail.U);r.depthRejected.expect(e.zfail.U)
        r.depthUpdates.expect(e.depth.U);r.stencilUpdates.expect(e.stencil.U);r.dsReads.expect(e.reads.U);r.dsFills.expect(e.fills.U);r.dsWrites.expect(e.writes.U)}
      val held=r.peek().litValue;for(_<-0 until 3){tick();assert(r.peek().litValue==held);d.io.in.ready.expect(false.B)}
      d.io.out.ready.poke(true.B);tick();d.io.out.ready.poke(false.B)
    }
    def verify(): Unit={for(a<-changed.toVector.sorted){val(data,available,_)=memory(a);assert(available)
      assert(data==word(model,a),s"late pixel word$a observed=${data.toString(16)} expected=${word(model,a).toString(16)}")}}
    def finish(code: Int=0,automatic: Boolean=false): Unit={
      if(!automatic){d.io.end.valid.poke(true.B);d.io.end.bits.poke(true.B);until(d.io.end.ready.peek().litToBoolean);tick();d.io.end.valid.poke(false.B)}
      until(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.status.expect(code.U)
      val held=d.io.completion.bits.peek().litValue;for(_<-0 until 4){tick();assert(d.io.completion.bits.peek().litValue==held);d.io.begin.ready.expect(false.B)}
      assert(pending.isEmpty && offered.isEmpty);d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B);d.io.busy.expect(false.B)
    }
  }

  it should "qualify shared typed colors with every depth stencil layout and preserve load padding and face snapshots" in {
    simulate(new TypedTilePixelBackend()){d=>val t=new Driver(d);var count=0
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
    simulate(new TypedTilePixelBackend()){d=>val t=new Driver(d);t.begin(View(4,0));var count=0
      for(f<-0 until 8;op<-0 until 8){val face=Face(function=f,reference=(f*31+op)&255,readMask=0x5f,writeMask=0xa7,fail=op,zfail=op,zpass=op)
        val s=Tests(depth=f%3!=0,write=op%3!=0,function=(f+op)%8,front=face,back=face.copy(reference=255-face.reference))
        val e=t.packet(count,s,front=count%2==0,z=fp(if(count%2==0)0.4 else 0.8),masks=Vector(15,3,5,0));t.submit();t.output(expected=Some(e));count+=1}
      t.verify();t.finish()
      info(s"TYPED_PIXEL changingStateQuads=$count functions=8 operations=8 overlapOrder=true disabledDepthNeverWrites=true maskedReferenceAndWrites=true sampleRejectCounts=true")
    }
  }
  it should "skip separate depth refills on stencil rejection and use clean load fills" in {
    simulate(new TypedTilePixelBackend()){d=>val t=new Driver(d)
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
    simulate(new TypedTilePixelBackend()){d=>val t=new Driver(d)
      for((a,b)<-Seq((View(7,0),View(0,0)),(View(4,0),View(6,1024)),(View(1,1),View(0,0)),(View(1,0),View(2,1024)))){
        t.prepare(a,b);t.submitBegin();t.finish(1,true)}
      t.begin(View(4,0));t.packet(apply=false);d.io.in.bits.color.quad.colorTypes(0).poke(1.U);t.submit();t.output(6,Some(Expected(0,0,0,0,0,0,0,0,0)));t.finish(6,true)
      t.begin(View(3,0));t.packet(z=BigInt("7fc00001",16),apply=false);t.submit();t.output(7,Some(Expected(0,0,0,0,0,0,0,0,0)));t.finish(7,true)
      for(f<-Seq(0,4,5,6)){t.begin(View(3,0,0,z=BigInt("7fc00001",16)));val e=t.packet(state=Tests(function=f,stencil=false));t.submit();t.output(expected=Some(e));t.verify();t.finish()}
      info("TYPED_PIXEL invalidLayouts=4 invalidQuadBeforeDS=true sourceNaNRejected=true storedNaNUnordered=true healthyRebind=true")
    }
  }
  it should "drain cancelled depth and color refills and retain a held final pixel completion" in {
    simulate(new TypedTilePixelBackend()){d=>val t=new Driver(d)
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
}
