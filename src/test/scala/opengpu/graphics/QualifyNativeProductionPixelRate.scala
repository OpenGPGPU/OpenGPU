package opengpu.graphics

import chisel3._
import chisel3.reflect.DataMirror
import chisel3.util.HasBlackBoxPath
import opengpu.testutil.GpuSim._
import org.scalatest.DoNotDiscover
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable
import java.nio.file.{Files,Path,Paths}
import java.security.MessageDigest
import scala.sys.process._

private[graphics] object NativeProductionPixelModel {
  val config=TypedTileColorConfig(parallelLanes=true,parallelDepth=true,nativeSamples=true,streamingWordPorts=8)
  def sha(p: Path): String=MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)).map(b=>f"${b&255}%02x").mkString
  lazy val model: ujson.Value={
    val selected=Paths.get(sys.props.getOrElse("opengpu.nativePixelRateModel",
      throw new IllegalArgumentException("prepare production pixel RTL and set -Dopengpu.nativePixelRateModel=DIR"))).toAbsolutePath
    val file=if(Files.isDirectory(selected))selected.resolve("manifest.json")else selected
    val c=ujson.read(Files.readString(file))
    require(c("schema").num==1 && c("top").str=="TypedTilePixelBackend" && c("definitions").num==128)
    require(c("production_RTL_copied_byte_exact").bool && c("no_DUT_ports_or_logic_modified").bool)
    require(c("real_SRAMs").num==16 && c("tile_bytes").num==32768 && c("streaming_word_ports").num==8)
    val names=Process(Seq("git","ls-files","src/main","build.sbt","project")).!!.linesIterator.filter(n=>Files.isRegularFile(Paths.get(n))).toSet
    require(names==c("source_sha256").obj.keySet.toSet,"production/build source inventory changed")
    require(Process(Seq("git","ls-files","--others","--exclude-standard","src/main","build.sbt","project")).!!.trim.isEmpty)
    for((n,h)<-c("source_sha256").obj)require(sha(Paths.get(n))==h.str,s"production source changed: $n")
    require(Process(Seq("git","rev-parse","HEAD"),Paths.get("depends/asap7_sram_0p0").toFile).!!.trim==c("dependency_pin").str)
    require(sha(Paths.get(c("provenance").str))==c("provenance_sha256").str)
    require(c("rtl_sha256").obj.size==128)
    for((n,h)<-c("rtl_sha256").obj)require(sha(Paths.get(c("rtl").str).resolve(n))==h.str,s"production RTL changed: $n")
    require(sha(Paths.get(c("vendor").str))==c("vendor_SRAM_sha256").str)
    c
  }
}

private[graphics] class NativeProductionPixelBlackBox extends BlackBox with HasBlackBoxPath {
  override def desiredName: String="TypedTilePixelBackend"
  val io=IO(new Bundle {
    val clock=Input(Clock());val reset=Input(Bool())
    val io=new TypedTilePixelBackendIO(NativeProductionPixelModel.config)
    val depthBounds=Output(new D24DepthBounds)
  })
  private val c=NativeProductionPixelModel.model
  for(n<-c("rtl_sha256").obj.keys)addPath(Paths.get(c("rtl").str).resolve(n).toString)
  addPath(c("vendor").str)
}

/** Only public wires surround the literal production RTL and vendor SRAM model. */
private[graphics] class NativeProductionPixelHarness extends Module {
  val io=IO(new TypedTilePixelBackendIO(NativeProductionPixelModel.config))
  val depthBounds=IO(Output(new D24DepthBounds))
  private val dut=Module(new NativeProductionPixelBlackBox)
  dut.io.clock:=clock;dut.io.reset:=reset.asBool
  io<>dut.io.io;depthBounds:=dut.io.depthBounds
}

/** Independent pixel oracle and selected rate/drain fixtures retain their original logic. */
@DoNotDiscover
class NativeProductionPixelRateQualification extends AnyFlatSpec {
  private val nativeSamples=true
  private def model(): NativeProductionPixelHarness=new NativeProductionPixelHarness
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
  private class Driver(val d: NativeProductionPixelHarness){
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

object QualifyNativeProductionPixelRate {
  def main(args: Array[String]): Unit={
    require(args.length==2 && !opengpu.testutil.GraphicsTestResolution.productQualification)
    if(args(0)=="--check-model"){
      sys.props("opengpu.nativePixelRateModel")=args(1)
      NativeProductionPixelModel.model
      println("NATIVE_PRODUCTION_PIXEL_MODEL PASS definitions=128 realSRAMs=16 tileBytes=32768 ports=8 hardwareExecuted=false");return
    }
    if(args(0)=="--emit-wrapper"){
      _root_.circt.stage.ChiselStage.emitSystemVerilogFile(new NativeProductionPixelHarness,
        Array("--target-dir",args(1)),Array("-O=release","--split-verilog"))
      println("NATIVE_PRODUCTION_PIXEL_WRAPPER emitted=true hardwareExecuted=false");return
    }
    require(args(0)=="--reports")
    val suite=new NativeProductionPixelRateQualification;require(suite.testNames.size==2)
    require(org.scalatest.tools.Runner.run(Array("-s",classOf[NativeProductionPixelRateQualification].getName,"-oD","-u",args(1))))
    val report=scala.xml.XML.loadFile(new java.io.File(args(1),"TEST-"+classOf[NativeProductionPixelRateQualification].getName+".xml"))
    val cases=report \ "testcase"
    require((report \ "@tests").text=="2" && cases.size==2 && cases.map(n=>(n \ "@name").text).toSet==suite.testNames.toSet)
    require(Seq("errors","failures","skipped").forall(a=>report.attribute(a).forall(_.text=="0")) &&
      Seq("failure","error","skipped").forall(n=>(report \\ n).isEmpty))
    println("NATIVE_PRODUCTION_PIXEL_RATE PASS cases=2 byteExactRTL=true realSRAMs=16 tileBytes=32768 wordPorts=8 clockQualified=false FPSQualified=false")
  }
}
