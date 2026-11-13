package opengpu.graphics

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class NativeGeometryInputMapperSpec extends AnyFlatSpec {
  private val p=VertexShaderStageConfig()
  private def zero(x: Data): Unit=x match {
    case r: Record=>r.elements.values.foreach(zero);case v: Vec[_]=>v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U);case _=>()
  }
  private def identity(x: GraphicsWorkIdentity): Unit={x.context.poke(7.U);x.pass.poke(8.U);x.generation.poke(9.U)}
  private def data(vertex: Int,source: Int): BigInt=BigInt("80000000",16)+vertex*4096+source
  it should "capture all six full VS payloads and immutable GS code uniform and output links before offering tasks" in {
    simulate(new NativeGeometryInputMapper(p)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def prepare(count: Int,components: Int,topology: Int): Unit={
        zero(d.io.in.bits);val primitive=d.io.in.bits.primitive;val g=d.io.in.bits.program
        identity(primitive.identity);identity(g.identity)
        primitive.sequence.poke(99.U);primitive.primitiveId.poke(31.U);primitive.instanceId.poke(5.U);primitive.baseInstance.poke(8.U)
        primitive.topology.poke(topology.U);primitive.kind.poke((if(count==1)0 else if(count==2 || count==4)1 else 2).U)
        primitive.vertexCount.poke(count.U);primitive.varyingSlots.poke(16.U)
        d.io.in.bits.taskId.poke(41.U)
        for(i<-0 until count){val v=primitive.vertices(i);identity(v.identity);v.instanceId.poke(5.U);v.baseInstance.poke(8.U)
          for(c<-0 until 4)v.position(c).poke(data(i,c).U)
          for(c<-0 until 64)v.varyings(c/4)(c%4).poke(data(i,c+4).U)
        }
        g.inputVertices.poke(count.U);g.inputComponents.poke(components.U);g.invocations.poke(32.U)
        g.outputTopology.poke(2.U);g.maxVertices.poke(256.U);g.outputComponents.poke(4.U)
        g.vertexVaryingSlots.poke(16.U);g.fragmentVaryingSlots.poke(16.U);g.programVersion.poke(1.U)
        g.pc.poke(0x900004.U);g.code.base.poke(0x900000.U);g.code.limit.poke(0x900800.U)
        g.code.allocationBase.poke(0x900000.U);g.code.allocationLimit.poke(0x900800.U)
        g.uniforms.version.poke(2.U);g.uniforms.address.poke(0xa00000.U);g.flatMask.poke(((BigInt(1)<<64)-1).U)
        for(c<-0 until components)g.inputSources(c).poke((67-c).U)
        for(c<-0 until 68){g.outputSources(c).valid.poke(true.B);g.outputSources(c).bits.poke((c%4).U)}
      }
      def accept(): BigInt={
        val program=d.io.in.bits.program.peek().litValue
        d.io.in.valid.poke(true.B);d.io.in.ready.expect(true.B);d.clock.step();d.io.in.valid.poke(false.B)
        zero(d.io.in.bits);program
      }
      def until(test: => Boolean): Unit={var n=0;while(!test && n<500){d.clock.step();n+=1};assert(test,"GS input mapper exceeded full 6*64 copy bound")}
      def good(count: Int,components: Int,topology: Int): Unit={
        prepare(count,components,topology);val program=accept();until(d.io.task.valid.peek().litToBoolean)
        d.io.fault.valid.expect(false.B);val t=d.io.task.bits.invocation
        t.taskId.expect(41.U);t.primitiveId.expect(31.U);t.inputVertices.expect(count.U);t.inputComponents.expect(components.U)
        t.invocations.expect(32.U);t.maxVertices.expect(256.U);t.outputComponents.expect(4.U);t.topology.expect(2.U)
        assert(d.io.task.bits.program.peek().litValue==program)
        for(i<-0 until 6;c<-0 until 64)t.vertices(i).components(c).expect((if(i<count && c<components)data(i,67-c)else BigInt(0)).U)
        for(i<-0 until 6){t.vertices(i).layer.expect(0.U);t.vertices(i).shaderPrimitiveId.expect(0.U)}
        val held=d.io.task.bits.peek().litValue
        for(_<-0 until 7){d.clock.step();d.io.in.ready.expect(false.B);assert(d.io.task.bits.peek().litValue==held)}
        d.io.task.ready.poke(true.B);d.clock.step();d.io.task.ready.poke(false.B);d.io.in.ready.expect(true.B)
      }
      for((count,topology)<-Seq(1->0,2->1,2->2,2->3,3->4,3->5,3->6,4->10,4->11,6->12,6->13);components<-Seq(0,1,64))good(count,components,topology)
      for(problem<-Seq("identity","vertexIdentity","instance","base","restart","count","kind","topology","varyings","source","program","uniform","total")){
        prepare(6,64,12);val q=d.io.in.bits;val g=q.program
        problem match {
          case "identity"=>g.identity.pass.poke(99.U)
          case "vertexIdentity"=>q.primitive.vertices(5).identity.context.poke(99.U)
          case "instance"=>q.primitive.vertices(5).instanceId.poke(99.U)
          case "base"=>q.primitive.vertices(5).baseInstance.poke(99.U)
          case "restart"=>q.primitive.vertices(5).restart.poke(true.B)
          case "count"=>q.primitive.vertexCount.poke(3.U)
          case "kind"=>q.primitive.kind.poke(1.U)
          case "topology"=>q.primitive.topology.poke(4.U)
          case "varyings"=>q.primitive.varyingSlots.poke(15.U)
          case "source"=>g.inputSources(63).poke(68.U)
          case "program"=>g.programVersion.poke(2.U)
          case "uniform"=>g.uniforms.version.poke(1.U)
          case "total"=>g.outputComponents.poke(5.U)
        }
        accept();until(d.io.fault.valid.peek().litToBoolean);d.io.task.valid.expect(false.B)
        d.io.fault.bits.taskId.expect(41.U);d.io.fault.bits.primitiveId.expect(31.U)
        val held=d.io.fault.bits.peek().litValue;for(_<-0 until 7){d.clock.step();assert(d.io.fault.bits.peek().litValue==held);d.io.in.ready.expect(false.B)}
        d.io.fault.ready.poke(true.B);d.clock.step();d.io.fault.ready.poke(false.B);good(6,64,13)
      }
    }
  }
}
