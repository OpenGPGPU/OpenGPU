package opengpu.graphics

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class GeometryAdjacencyAssemblyStageSpec extends AnyFlatSpec {
  private val p=VertexShaderStageConfig()
  private def zero(x: Data): Unit=x match {
    case r: Record=>r.elements.values.foreach(zero);case v: Vec[_]=>v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U);case _=>()
  }
  private case class V(ordinal: Int,instance: Int,restart: Boolean=false){
    def position(c: Int): BigInt=BigInt("7fc00000",16)+instance*65536+ordinal*4+c
    def varying(c: Int): BigInt=BigInt("80000000",16)+instance*65536+ordinal*64+c
    def point: BigInt=BigInt("3f800000",16)+ordinal*256+instance
  }
  private case class Expected(vertices: Vector[V],id: Int)
  // Literal full input arrays from GLES 3.2 table 10.1; these are not a
  // sliding-window RTL implementation. Odd input tails select the same row.
  private val strips=Vector(
    Vector.empty[Vector[Int]],
    Vector(Vector(1,2,3,6,5,4)),
    Vector(Vector(1,2,3,7,5,4),Vector(5,1,3,6,7,8)),
    Vector(Vector(1,2,3,7,5,4),Vector(5,1,3,6,7,9),Vector(5,3,7,10,9,8)),
    Vector(Vector(1,2,3,7,5,4),Vector(5,1,3,6,7,9),Vector(5,3,7,11,9,8),Vector(9,5,7,10,11,12)),
    Vector(Vector(1,2,3,7,5,4),Vector(5,1,3,6,7,9),Vector(5,3,7,11,9,8),Vector(9,5,7,10,11,13),Vector(9,7,11,14,13,12)),
    Vector(Vector(1,2,3,7,5,4),Vector(5,1,3,6,7,9),Vector(5,3,7,11,9,8),Vector(9,5,7,10,11,13),Vector(9,7,11,15,13,12),Vector(13,9,11,14,15,16)))
  private def oracle(mode: Int,inputs: Vector[Vector[V]]): (Vector[Expected],Int)={
    val out=mutable.ArrayBuffer.empty[Expected];var incomplete=0
    for(input<-inputs){
      var id=0;val segments=mutable.ArrayBuffer.empty[Vector[V]];var s=Vector.empty[V]
      for(v<-input)if(v.restart){segments+=s;s=Vector.empty}else s :+= v
      segments+=s
      for(s<-segments){
        val n=s.length
        val values=mode match {
          case 10=>s.grouped(4).filter(_.size==4).toVector
          case 11=>if(n<4)Vector.empty else s.sliding(4).toVector
          case 12=>s.grouped(6).filter(_.size==6).toVector
          case 13=>strips(((n/2)-2).max(0)).map(_.map(i=>s(i-1)))
        }
        incomplete+= (mode match {case 10=>n%4;case 12=>n%6;case 11=>if(n<4)n else 0;case 13=>if(n<6)n else n%2})
        for(v<-values){out+=Expected(v,id);id+=1}
      }
    }
    (out.toVector,incomplete)
  }
  private class Driver(val d: GeometryAdjacencyAssemblyStage){
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    var cycle=0;var ready: Option[Boolean]=None;var held: Option[BigInt]=None
    var sentVertices=0;var sentRestarts=0;var outputs=0;var mode=10
    var expected=Vector.empty[Expected];var checkOutputs=true
    def tick(): Unit={
      d.io.out.ready.poke(ready.getOrElse(cycle%7<3).B)
      if(d.io.out.valid.peek().litToBoolean){
        val bits=d.io.out.bits.peek().litValue;held.foreach(x=>assert(bits==x,"held GS input changed"))
        if(d.io.out.ready.peek().litToBoolean){
          if(checkOutputs){assert(outputs<expected.size,s"extra GS primitive mode=$mode");val e=expected(outputs);val q=d.io.out.bits
            q.sequence.expect(outputs.U);q.primitiveId.expect(e.id.U);q.instanceId.expect(e.vertices.head.instance.U)
            q.identity.context.expect(7.U);q.identity.pass.expect(8.U);q.identity.generation.expect(9.U)
            q.baseInstance.expect(2.U);q.topology.expect(mode.U);q.kind.expect((if(mode<12)1 else 2).U)
            q.vertexCount.expect(e.vertices.size.U);q.varyingSlots.expect(16.U)
            for(i<-0 until 6){val r=q.vertices(i)
              if(i>=e.vertices.size)assert(r.peek().litValue==0)else{val v=e.vertices(i)
                r.ordinal.expect(v.ordinal.U);r.vertexId.expect((v.ordinal%3).U);r.instanceId.expect(v.instance.U)
                r.identity.context.expect(7.U);r.identity.pass.expect(8.U);r.identity.generation.expect(9.U)
                r.baseInstance.expect(2.U);r.restart.expect(false.B);r.pointSize.expect(v.point.U);r.pointSizeWritten.expect((v.ordinal%2==1).B)
                for(c<-0 until 4)r.position(c).expect(v.position(c).U)
                for(c<-0 until 64)r.varyings(c/4)(c%4).expect(v.varying(c).U)
              }
            }
          }
          outputs+=1;held=None
        }else held=Some(bits)
      }else assert(held.isEmpty,"held GS input withdrawn")
      if(d.io.in.valid.peek().litToBoolean && d.io.in.ready.peek().litToBoolean){
        if(d.io.in.bits.restart.peek().litToBoolean)sentRestarts+=1 else sentVertices+=1
      }
      d.clock.step();cycle+=1
    }
    def until(test: => Boolean): Unit={var n=0;while(!test && n<10000){tick();n+=1};assert(test,s"GS assembly stalled cycle=$cycle outputs=$outputs")}
    def begin(m: Int,n: Int,instances: Int,e: Vector[Expected]=Vector.empty,adjust: PrimitiveAssemblyDraw=>Unit=_=>()): Unit={
      mode=m;outputs=0;expected=e;sentVertices=0;sentRestarts=0;val q=d.io.draw.bits
      q.version.poke(1.U);q.identity.context.poke(7.U);q.identity.pass.poke(8.U);q.identity.generation.poke(9.U)
      q.topology.poke(m.U);q.count.poke(n.U);q.instances.poke(instances.U);q.baseInstance.poke(2.U)
      q.varyingSlots.poke(16.U);q.flatMask.poke(((BigInt(1)<<64)-1).U);adjust(q)
      d.io.draw.valid.poke(true.B);until(d.io.draw.ready.peek().litToBoolean);tick();d.io.draw.valid.poke(false.B)
      q.topology.poke(0.U);q.identity.context.poke(999.U)
    }
    def offer(v: V): Unit={
      val r=d.io.in.bits;r.identity.context.poke(7.U);r.identity.pass.poke(8.U);r.identity.generation.poke(9.U)
      r.ordinal.poke(v.ordinal.U);r.vertexId.poke((v.ordinal%3).U);r.instanceId.poke(v.instance.U);r.baseInstance.poke(2.U)
      r.restart.poke(v.restart.B);r.pointSize.poke(v.point.U);r.pointSizeWritten.poke((v.ordinal%2==1).B)
      for(c<-0 until 4)r.position(c).poke(v.position(c).U)
      for(c<-0 until 64)r.varyings(c/4)(c%4).poke(v.varying(c).U)
      d.io.in.valid.poke(true.B)
    }
    def send(v: V): Unit={offer(v);until(d.io.in.ready.peek().litToBoolean);tick();d.io.in.valid.poke(false.B)}
    def end(code: Int=0,badIdentity: Boolean=false,badCount: Boolean=false): Unit={
      zero(d.io.end.bits);val q=d.io.end.bits;q.identity.context.poke((if(badIdentity)999 else 7).U);q.identity.pass.poke(8.U);q.identity.generation.poke(9.U)
      q.status.poke(code.U);q.vertices.poke((sentVertices+(if(badCount)1 else 0)).U);q.restarts.poke(sentRestarts.U)
      d.io.end.valid.poke(true.B);until(d.io.end.ready.peek().litToBoolean);tick();d.io.end.valid.poke(false.B)
    }
    def finish(code: Int,tail: Option[Int]=None): Unit={
      until(d.io.completion.valid.peek().litToBoolean);val q=d.io.completion.bits
      q.status.expect(code.U);q.vertices.expect(sentVertices.U);q.restarts.expect(sentRestarts.U);q.primitives.expect(outputs.U);q.points.expect(0.U)
      tail.foreach(x=>q.incompleteVertices.expect(x.U));if(code==0)assert(outputs==expected.size)
      val bits=q.peek().litValue;for(_<-0 until 5){tick();assert(q.peek().litValue==bits);d.io.draw.ready.expect(false.B)}
      d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B);d.io.busy.expect(false.B)
    }
    def healthy(): Unit={val in=Vector(Vector.tabulate(6)(i=>V(i,0)));val(e,t)=oracle(12,in)
      begin(12,6,1,e);in.flatten.foreach(send);end();finish(0,Some(t))}
  }
  it should "retain all six adjacency vertices with exact strip first middle last restart and instance order" in {
    simulate(new GeometryAdjacencyAssemblyStage(p)){d=>val t=new Driver(d)
      for(mode<-10 to 13;n<-0 to 17;pattern<-0 to 2){
        val restarts=pattern match {case 0=>Set.empty[Int];case 1=>Set(0,n/2,n-1);case _=>Set(3,4,10)}
        val in=Vector.tabulate(2)(inst=>Vector.tabulate(n)(i=>V(i,inst,restarts(i))))
        val(e,tail)=oracle(mode,in);t.begin(mode,n,2,e)
        for(v<-in.flatten){t.send(v);if(v.ordinal%7==2)for(_<-0 until 9)t.tick()}
        t.end();t.finish(0,Some(tail))
      }
      t.begin(13,19,0);t.end();t.finish(0,Some(0));t.healthy()
    }
  }
  it should "keep held GS input across a cancellation pulse and drain every source fault before reuse" in {
    simulate(new GeometryAdjacencyAssemblyStage(p)){d=>val t=new Driver(d);t.checkOutputs=false;t.ready=Some(false)
      t.begin(13,12,1);(0 until 8).foreach(i=>t.send(V(i,0)));t.until(d.io.out.valid.peek().litToBoolean)
      val bits=d.io.out.bits.peek().litValue;d.io.cancel.poke(true.B);t.tick();d.io.cancel.poke(false.B)
      for(_<-0 until 7){t.tick();assert(d.io.out.bits.peek().litValue==bits);d.io.completion.valid.expect(false.B)}
      (8 until 12).foreach(i=>t.send(V(i,0)));t.end(VertexInputStatus.cancelled)
      d.io.completion.valid.expect(false.B);t.ready=Some(true);t.finish(VertexInputStatus.cancelled);assert(t.outputs==1)
      t.checkOutputs=true;t.healthy()
      for(problem<-Seq("identity","ordinal","instance","base","early","endIdentity","endCount","memory")){
        t.begin(12,6,1);t.offer(V(0,0))
        problem match {
          case "identity"=>d.io.in.bits.identity.context.poke(999.U)
          case "ordinal"=>d.io.in.bits.ordinal.poke(2.U)
          case "instance"=>d.io.in.bits.instanceId.poke(1.U)
          case "base"=>d.io.in.bits.baseInstance.poke(3.U)
          case _=>()
        }
        t.until(d.io.in.ready.peek().litToBoolean);t.tick();d.io.in.valid.poke(false.B)
        if(problem!="early" && problem!="memory")(1 until 6).foreach(i=>t.send(V(i,0)))
        t.checkOutputs=false
        t.end(if(problem=="memory")VertexInputStatus.memoryFault else 0,badIdentity=problem=="endIdentity",badCount=problem=="endCount")
        t.finish(if(problem=="memory")VertexInputStatus.memoryFault else PrimitiveAssemblyStatus.streamFault)
        t.checkOutputs=true;t.healthy()
      }
      for(mode<-Seq(0,7,9,14,15)){t.begin(mode,0,0);t.end();t.finish(VertexInputStatus.invalidDescriptor);t.healthy()}
    }
  }
}
