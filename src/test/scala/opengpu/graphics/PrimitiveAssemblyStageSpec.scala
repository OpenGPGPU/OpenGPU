package opengpu.graphics

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class PrimitiveAssemblyStageSpec extends AnyFlatSpec {
  private val p=VertexShaderStageConfig()
  private val identity=(BigInt(7)<<64)|(BigInt(8)<<32)|9
  private def zero(data: Data): Unit=data match {
    case r: Record=>r.elements.values.foreach(zero)
    case v: Vec[_]=>v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case _=>()
  }
  private def pack(fields: (BigInt,Int)*): BigInt=fields.foldLeft(BigInt(0)){case(acc,(v,w))=>assert(v>=0 && v<(BigInt(1)<<w));(acc<<w)|v}
  private def vector(values: Seq[BigInt],width: Int): BigInt=values.zipWithIndex.foldLeft(BigInt(0)){case(acc,(v,i))=>acc|(v<<(i*width))}
  private case class V(ordinal: Int,instance: Int,id: Int,restart: Boolean=false,degenerate: Boolean=false) {
    def position(c: Int): BigInt=BigInt("7fc00000",16)+instance*4096+(if(degenerate)id else ordinal)*4+c
    def varying(c: Int): BigInt=BigInt("80000000",16)+instance*65536+ordinal*64+c
    def bits(flat: BigInt,provoking: V): BigInt=pack(
      identity->96,BigInt(ordinal)->32,BigInt(id)->32,BigInt(instance)->32,BigInt(2)->32,BigInt(if(restart)1 else 0)->1,
      vector((0 until 4).map(position),32)->128,
      vector((0 until 64).map(c=>if(flat.testBit(c))provoking.varying(c)else varying(c)),32)->2048)
  }
  private case class Prim(values: Vector[V],closing: Boolean=false)
  private def reference(mode: Int,instances: Vector[Vector[V]]): (Vector[(Prim,Int)],Int)={
    val result=mutable.ArrayBuffer.empty[(Prim,Int)];var discarded=0
    for(input<-instances) {
      var primitiveId=0;val segments=mutable.ArrayBuffer.empty[Vector[V]];var segment=Vector.empty[V]
      for(v<-input)if(v.restart){segments+=segment;segment=Vector.empty}else segment :+= v
      segments+=segment
      for(s<-segments) {
        val n=s.size
        val primitives: Vector[Prim]=mode match {
          case 0=>s.map(v=>Prim(Vector(v)))
          case 1=>s.grouped(2).filter(_.size==2).map(v=>Prim(v.toVector)).toVector
          case 2=>if(n<2)Vector.empty else s.sliding(2).map(v=>Prim(v.toVector)).toVector:+Prim(Vector(s.last,s.head),true)
          case 3=>if(n<2)Vector.empty else s.sliding(2).map(v=>Prim(v.toVector)).toVector
          case 4=>s.grouped(3).filter(_.size==3).map(v=>Prim(v.toVector)).toVector
          case 5=>if(n<3)Vector.empty else s.sliding(3).zipWithIndex.map{case(v,i)=>Prim(if(i%2==0)v.toVector else Vector(v(1),v(0),v(2)))}.toVector
          case 6=>if(n<3)Vector.empty else s.tail.sliding(2).map(v=>Prim(Vector(s.head,v(0),v(1)))).toVector
        }
        discarded+= (mode match {case 0=>0;case 1=>n%2;case 4=>n%3;case 2|3=>if(n<2)n else 0;case _=>if(n<3)n else 0})
        for(primitive<-primitives){result+=((primitive,primitiveId));primitiveId+=1}
      }
    }
    (result.toVector,discarded)
  }
  private def primitiveBits(primitive: Prim,id: Int,sequence: Int,mode: Int,flat: BigInt): BigInt={
    val values=primitive.values;val kind=values.size-1;val vertexWidth=96+32*4+1+128+2048
    pack(identity->96,BigInt(sequence)->64,BigInt(id)->32,BigInt(values.last.instance)->32,BigInt(2)->32,
      BigInt(mode)->4,BigInt(kind)->2,BigInt(values.size)->2,BigInt(values.size-1)->2,BigInt(if(primitive.closing)1 else 0)->1,
      BigInt(16)->5,flat->64,
      vector(values.map(_.bits(flat,values.last))++Vector.fill(3-values.size)(BigInt(0)),vertexWidth)->(3*vertexWidth))
  }
  private class Driver(val d: PrimitiveAssemblyStage) {
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    var cycle=0;var ready: Option[Boolean]=None;var sentVertices=0;var sentRestarts=0
    var held: Option[BigInt]=None;val outputs=mutable.ArrayBuffer.empty[BigInt]
    def tick(): Unit={
      d.io.out.ready.poke(ready.getOrElse(cycle%7<4).B)
      if(d.io.out.valid.peek().litToBoolean) {
        val bits=d.io.out.bits.peek().litValue;held.foreach(v=>assert(v==bits,"stalled primitive changed"))
        if(d.io.out.ready.peek().litToBoolean){outputs+=bits;held=None}else held=Some(bits)
      } else assert(held.isEmpty,"stalled primitive was withdrawn")
      if(d.io.in.valid.peek().litToBoolean && d.io.in.ready.peek().litToBoolean) {
        if(d.io.in.bits.restart.peek().litToBoolean)sentRestarts+=1 else sentVertices+=1
      }
      d.clock.step();cycle+=1
    }
    def until(condition: => Boolean): Unit={var n=0;while(!condition && n<10000){tick();n+=1};assert(condition,s"assembly stalled cycle=$cycle outputs=${outputs.size}")}
    def begin(mode: Int,count: Int,instances: Int,flat: BigInt=0,adjust: PrimitiveAssemblyDraw=>Unit=_=>()): Unit={
      sentVertices=0;sentRestarts=0;outputs.clear();val q=d.io.draw.bits
      q.version.poke(1.U);q.identity.context.poke(7.U);q.identity.pass.poke(8.U);q.identity.generation.poke(9.U)
      q.topology.poke(mode.U);q.count.poke(count.U);q.instances.poke(instances.U);q.baseInstance.poke(2.U)
      q.varyingSlots.poke(16.U);q.flatMask.poke(flat.U);adjust(q);d.io.draw.valid.poke(true.B)
      until(d.io.draw.ready.peek().litToBoolean);tick();d.io.draw.valid.poke(false.B)
      // Accepted layout and identity are immutable snapshots.
      q.topology.poke(15.U);q.identity.context.poke(999.U);q.flatMask.poke(0.U)
    }
    def offer(v: V): Unit={
      val r=d.io.in.bits;r.identity.context.poke(7.U);r.identity.pass.poke(8.U);r.identity.generation.poke(9.U)
      r.ordinal.poke(v.ordinal.U);r.vertexId.poke(v.id.U);r.instanceId.poke(v.instance.U);r.baseInstance.poke(2.U);r.restart.poke(v.restart.B)
      for(c<-0 until 4)r.position(c).poke(v.position(c).U)
      for(c<-0 until 64)r.varyings(c/4)(c%4).poke(v.varying(c).U)
      d.io.in.valid.poke(true.B)
    }
    def send(v: V): Unit={offer(v);until(d.io.in.ready.peek().litToBoolean);tick();d.io.in.valid.poke(false.B)}
    def end(status: Int=0,foreign: Boolean=false,badCount: Boolean=false): Unit={
      val q=d.io.end.bits;zero(q);q.identity.context.poke((if(foreign)999 else 7).U);q.identity.pass.poke(8.U);q.identity.generation.poke(9.U)
      q.status.poke(status.U);q.vertices.poke((sentVertices+(if(badCount)1 else 0)).U);q.restarts.poke(sentRestarts.U)
      d.io.end.valid.poke(true.B);until(d.io.end.ready.peek().litToBoolean);tick();d.io.end.valid.poke(false.B)
    }
    def finish(status: Int,discarded: Option[Int]=None): Unit={
      until(d.io.completion.valid.peek().litToBoolean)
      val r=d.io.completion.bits;r.status.expect(status.U);r.vertices.expect(sentVertices.U);r.restarts.expect(sentRestarts.U)
      r.primitives.expect(outputs.size.U);discarded.foreach(n=>r.incompleteVertices.expect(n.U))
      assert(r.points.peek().litValue+r.lines.peek().litValue+r.triangles.peek().litValue==outputs.size)
      val held=r.peek().litValue
      for(_<-0 until 5){d.io.draw.ready.expect(false.B);tick();assert(r.peek().litValue==held)}
      d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B);d.io.busy.expect(false.B)
    }
  }

  it should "assemble every GLES topology with restart instance boundaries flat values and stalled consumers" in {
    simulate(new PrimitiveAssemblyStage(p)) { d=>
      val t=new Driver(d);var draws=0;var total=0
      for(mode<-0 to 6;n<-0 to 9;pattern<-0 to 2) {
        val restart: Set[Int]=pattern match {case 0=>Set.empty;case 1=>Set(0,n/2,n-1).filter(_>=0);case _=>(0 until n).filter(_%3==1).toSet}
        val input=Vector.tabulate(2)(inst=>Vector.tabulate(n)(i=>V(i,inst,if(i%4==0)0 else i,restart(i))))
        val flat=pattern match {case 0=>BigInt(0);case 1=>(BigInt(1)<<64)-1;case _=>BigInt("a55aa55aa55aa55a",16)}
        val (expected,discarded)=reference(mode,input);t.begin(mode,n,2,flat)
        input.flatten.foreach(t.send);t.end();t.finish(0,Some(discarded))
        val bits=expected.zipWithIndex.map{case((primitive,id),seq)=>primitiveBits(primitive,id,seq,mode,flat)}
        assert(t.outputs.toVector==bits,s"mode=$mode count=$n pattern=$pattern output mismatch")
        draws+=1;total+=expected.size
      }
      t.begin(2,9,0);t.end();t.finish(0,Some(0));assert(t.outputs.isEmpty)
      info(s"PRIMITIVE_ASSEMBLY topologies=7 draws=$draws primitives=$total instances=2 flatComponents=64 restart=true winding=true loopClosing=true incompleteTails=true snapshots=true stalls=true")
    }
  }

  it should "advance strip parity through degenerates and preserve loops across long producer gaps" in {
    simulate(new PrimitiveAssemblyStage(p)) { d=>
      val t=new Driver(d);var total=0
      for(mode<-Seq(2,5,6)) {
        val input=Vector(Vector.tabulate(23)(i=>V(i,0,Seq(1,1,2,2,2,1,3)(i%7),Set(8,9,17)(i),true)))
        val (expected,discarded)=reference(mode,input);t.begin(mode,23,1)
        for(v<-input.head){t.send(v);for(_<-0 until (if(v.ordinal==7 || v.ordinal==15)19 else 1))t.tick()}
        t.end();t.finish(0,Some(discarded))
        assert(t.outputs.toVector==expected.zipWithIndex.map{case((primitive,id),seq)=>primitiveBits(primitive,id,seq,mode,0)})
        total+=expected.size
      }
      info(s"PRIMITIVE_ASSEMBLY degeneratePositions=true longGaps=true restartAdjacency=true primitives=$total")
    }
  }

  it should "retain offered primitives through cancellation and wait for the source to drain" in {
    simulate(new PrimitiveAssemblyStage(p)) { d=>
      val t=new Driver(d);t.ready=Some(false);t.begin(2,5,1,BigInt("55",16))
      t.send(V(0,0,0));t.send(V(1,0,1));t.until(d.io.out.valid.peek().litToBoolean)
      val held=d.io.out.bits.peek().litValue;d.io.cancel.poke(true.B)
      for(_<-0 until 11){t.tick();d.io.cancelSource.expect(true.B);assert(d.io.out.bits.peek().litValue==held);d.io.completion.valid.expect(false.B)}
      t.send(V(2,0,2));t.send(V(3,0,3));d.io.cancel.poke(false.B)
      t.ready=Some(true);t.end(VertexInputStatus.cancelled);t.finish(VertexInputStatus.cancelled)
      assert(t.outputs.toVector==Vector(held),"cancel emitted younger or loop-closing primitives")
      t.begin(4,3,1);(0 until 3).foreach(i=>t.send(V(i,0,i)));t.end();t.finish(0,Some(0));assert(t.outputs.size==1)
      info("PRIMITIVE_ASSEMBLY cancelHeldPrimitive=true sourceDrain=true noCancelledLoopClose=true healthyReuse=true")
    }
  }

  it should "attribute malformed record and completion streams and propagate source faults" in {
    simulate(new PrimitiveAssemblyStage(p)) { d=>
      val t=new Driver(d);t.ready=Some(true)
      for(mode<-Seq("identity","ordinal","instance","base","extra","endIdentity","endCount","earlyEnd")) {
        t.begin(0,if(mode=="earlyEnd")2 else 1,1);t.offer(V(0,0,7))
        mode match {
          case "identity"=>d.io.in.bits.identity.context.poke(999.U)
          case "ordinal"=>d.io.in.bits.ordinal.poke(1.U)
          case "instance"=>d.io.in.bits.instanceId.poke(1.U)
          case "base"=>d.io.in.bits.baseInstance.poke(3.U)
          case _=>()
        }
        t.until(d.io.in.ready.peek().litToBoolean);t.tick();d.io.in.valid.poke(false.B)
        if(mode=="extra")t.send(V(1,0,8))
        t.end(foreign=mode=="endIdentity",badCount=mode=="endCount")
        t.finish(PrimitiveAssemblyStatus.streamFault)
        if(Set("identity","ordinal","instance","base")(mode))assert(t.outputs.isEmpty)
      }
      t.begin(4,3,1);t.send(V(0,0,0));t.send(V(1,0,1));t.end(VertexInputStatus.memoryFault)
      t.finish(VertexInputStatus.memoryFault);assert(t.outputs.isEmpty)
      // Invalid declarations still retain/drain the paired producer completion.
      for(mode<-Seq(7,15)) {t.begin(mode,0,0);d.io.cancelSource.expect(true.B);t.end(VertexInputStatus.cancelled);t.finish(VertexInputStatus.invalidDescriptor)}
      info("PRIMITIVE_ASSEMBLY malformedStreams=8 sourceFault=true invalidTopologies=2 heldCompletion=true healthyReuse=true")
    }
  }

  it should "reject version slot and flat-mask layouts while retaining producer drain ownership" in {
    simulate(new PrimitiveAssemblyStage(p)) { d=>
      val t=new Driver(d)
      val layouts=Seq[PrimitiveAssemblyDraw=>Unit](
        q=>q.version.poke(0.U),q=>q.varyingSlots.poke(17.U),q=>q.varyingSlots.poke(31.U),
        q=>{q.varyingSlots.poke(0.U);q.flatMask.poke(1.U)},
        q=>{q.varyingSlots.poke(1.U);q.flatMask.poke(16.U)})
      for(adjust<-layouts) {
        t.begin(0,0,0,adjust=adjust);d.io.cancelSource.expect(true.B)
        for(_<-0 until 7){t.tick();d.io.busy.expect(true.B);d.io.completion.valid.expect(false.B)}
        t.end(VertexInputStatus.cancelled);t.finish(VertexInputStatus.invalidDescriptor);assert(t.outputs.isEmpty)
      }
      t.begin(0,0,0);t.end();t.finish(0,Some(0))
      info("PRIMITIVE_ASSEMBLY invalidLayouts=5 producerDrainRequired=true healthyReuse=true")
    }
  }

  it should "close a two-vertex loop with selective flat values at the minimum varying interface" in {
    simulate(new PrimitiveAssemblyStage(VertexShaderStageConfig(VertexInputFetchConfig(1,1),1))) { d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B);d.io.out.ready.poke(true.B)
      var outputs=0
      def tick(): Unit={
        if(d.io.out.valid.peek().litToBoolean) {
          val close=outputs==1;d.io.out.bits.loopClosing.expect(close.B);d.io.out.bits.provokingVertex.expect(1.U)
          d.io.out.bits.varyingSlots.expect(1.U);d.io.out.bits.flatMask.expect(10.U)
          d.io.out.bits.sequence.expect(outputs.U);d.io.out.bits.primitiveId.expect(outputs.U)
          for(v<-0 until 2;c<-0 until 4) {
            val input=if(close)1-v else v;val provoking=if(close)0 else 1
            d.io.out.bits.vertices(v).position(c).expect((input*10+c).U)
            d.io.out.bits.vertices(v).varyings(0)(c).expect(((if(c%2==1)provoking else input)*100+c).U)
          }
          assert(d.io.out.bits.vertices(2).peek().litValue==0);outputs+=1
        }
        d.clock.step()
      }
      def until(condition: => Boolean): Unit={var n=0;while(!condition && n<100){tick();n+=1};assert(condition)}
      val q=d.io.draw.bits;q.version.poke(1.U);q.count.poke(2.U);q.instances.poke(1.U);q.topology.poke(2.U);q.varyingSlots.poke(1.U);q.flatMask.poke(10.U)
      d.io.draw.valid.poke(true.B);until(d.io.draw.ready.peek().litToBoolean);tick();d.io.draw.valid.poke(false.B)
      for(v<-0 until 2) {
        d.io.in.bits.ordinal.poke(v.U);d.io.in.bits.vertexId.poke((v+17).U)
        for(c<-0 until 4){d.io.in.bits.position(c).poke((v*10+c).U);d.io.in.bits.varyings(0)(c).poke((v*100+c).U)}
        d.io.in.valid.poke(true.B);until(d.io.in.ready.peek().litToBoolean);tick();d.io.in.valid.poke(false.B)
      }
      d.io.end.bits.vertices.poke(2.U);d.io.end.valid.poke(true.B);until(d.io.end.ready.peek().litToBoolean);tick();d.io.end.valid.poke(false.B)
      until(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.status.expect(0.U);d.io.completion.bits.primitives.expect(2.U)
      assert(outputs==2);d.io.completion.ready.poke(true.B);tick();d.io.busy.expect(false.B)
      info("PRIMITIVE_ASSEMBLY varyingSlots=1 twoVertexLoop=true selectiveFlat=true unusedVertexZero=true")
    }
  }
}
