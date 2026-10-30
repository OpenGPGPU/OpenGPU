package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

private class GeometryOutputMappingHarness extends Module {
  val io=IO(new Bundle {
    val task=Flipped(Decoupled(new GeometryOutputTask))
    val command=Flipped(Decoupled(new GeometryOutputCommand))
    val layout=Input(new GeometryPrimitiveOutputRequest(VertexShaderStageConfig()))
    val out=Decoupled(new GeometryMappedPrimitive(VertexShaderStageConfig()))
    val fault=Decoupled(new GeometryPrimitiveOutputFault)
    val completion=Decoupled(new GeometryOutputCompletion)
  })
  val assembler=Module(new GeometryOutputAssembler);val adapter=Module(new GeometryPrimitiveOutput())
  assembler.io.task<>io.task;assembler.io.command<>io.command;assembler.io.cancel:=false.B
  io.completion<>assembler.io.completion
  adapter.io.in.valid:=assembler.io.primitive.valid;assembler.io.primitive.ready:=adapter.io.in.ready
  adapter.io.in.bits:=io.layout;adapter.io.in.bits.geometry:=assembler.io.primitive.bits
  io.out<>adapter.io.out;io.fault<>adapter.io.fault
}

class GeometryPrimitiveOutputSpec extends AnyFlatSpec {
  it should "map emitted vertices with provoking values and retain faults and outputs" in {
    simulate(new GeometryPrimitiveOutput()) { d =>
      d.io.in.valid.poke(false.B);d.io.in.bits.poke(0.U.asTypeOf(d.io.in.bits))
      d.io.out.ready.poke(false.B);d.io.fault.ready.poke(false.B)
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def until(p: => Boolean):Unit={var n=0;while(!p && n<250){d.clock.step();n+=1};assert(p)}
      def offer(count:Int,mask:Int,badSource:Boolean=false,badOwner:Boolean=false):Unit={
        val q=d.io.in.bits;q.taskId.poke(19.U);q.geometry.taskId.poke((if(badOwner)20 else 19).U)
        q.source.primitiveId.poke(27.U);q.geometry.primitiveId.poke(27.U);q.geometry.invocationId.poke(4.U)
        q.geometry.vertexCount.poke(count.U);q.source.instanceId.poke(7.U);q.source.baseInstance.poke(3.U)
        q.sequence.poke(99.U);q.outputComponents.poke(12.U);q.builtinOutputMask.poke(mask.U)
        q.varyingSlots.poke(2.U);q.flatMask.poke(((1<<2)|(1<<6)).U)
        for(c<-0 until 68){q.componentSources(c).valid.poke((c<12).B)
          q.componentSources(c).bits.poke((if(badSource && c==0)12 else if(c<4)3-c else if(c<12)c else 0).U)}
        for(v<-0 until 3){q.geometry.vertices(v).shaderPrimitiveId.poke((10+v).U);q.geometry.vertices(v).layer.poke((20+v).U)
          for(c<-0 until 64)q.geometry.vertices(v).components(c).poke((100*v+c).U)}
        d.io.in.valid.poke(true.B);d.io.in.ready.expect(true.B);d.clock.step();d.io.in.valid.poke(false.B)
        q.geometry.vertices(2).shaderPrimitiveId.poke(999.U);q.geometry.vertices(2).components(6).poke(999.U)
      }
      for((count,mask)<-Seq((3,3),(2,0),(1,3))){offer(count,mask);until(d.io.out.valid.peek().litToBoolean)
        val o=d.io.out.bits;val p=o.primitive;o.inputPrimitiveId.expect(27.U);o.invocationId.expect(4.U)
        o.layer.expect((if(mask==3)20+count-1 else 0).U);p.primitiveId.expect((if(mask==3)10+count-1 else 0).U)
        p.sequence.expect(99.U);p.instanceId.expect(7.U);p.baseInstance.expect(3.U)
        p.kind.expect((count-1).U);p.vertexCount.expect(count.U);p.provokingVertex.expect((count-1).U)
        for(v<-0 until count;c<-0 until 4)p.vertices(v).position(c).expect((100*v+3-c).U)
        for(v<-0 until count;c<-0 until 8){val source=if(c==2 || c==6)count-1 else v
          p.vertices(v).varyings(c/4)(c%4).expect((100*source+4+c).U)}
        val held=o.peek().litValue;for(_<-0 until 3){d.io.in.ready.expect(false.B);assert(o.peek().litValue==held);d.clock.step()}
        d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)
      }
      for((badSource,badOwner)<-Seq((true,false),(false,true))){offer(3,3,badSource,badOwner)
        until(d.io.fault.valid.peek().litToBoolean);d.io.out.valid.expect(false.B)
        d.io.fault.bits.taskId.expect(19.U);d.io.fault.bits.inputPrimitiveId.expect(27.U)
        for(_<-0 until 2){d.io.fault.valid.expect(true.B);d.clock.step()}
        d.io.fault.ready.poke(true.B);d.clock.step();d.io.fault.ready.poke(false.B)
      }
    }
  }
  it should "preserve provoking IDs across assembled triangle-strip winding" in {
    simulate(new GeometryOutputMappingHarness){d=>
      d.io.task.valid.poke(false.B);d.io.task.bits.poke(0.U.asTypeOf(d.io.task.bits))
      d.io.command.valid.poke(false.B);d.io.command.bits.poke(0.U.asTypeOf(d.io.command.bits))
      d.io.layout.poke(0.U.asTypeOf(d.io.layout));d.io.out.ready.poke(false.B)
      d.io.fault.ready.poke(true.B);d.io.completion.ready.poke(false.B)
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val l=d.io.layout;l.taskId.poke(19.U);l.source.primitiveId.poke(27.U);l.outputComponents.poke(4.U)
      l.builtinOutputMask.poke(2.U);l.componentSources(0).valid.poke(true.B);l.componentSources(0).bits.poke(0.U)
      val t=d.io.task.bits;t.taskId.poke(19.U);t.primitiveId.poke(27.U);t.invocationId.poke(0.U)
      t.topology.poke(2.U);t.maxVertices.poke(4.U);t.outputComponents.poke(4.U);t.builtinOutputMask.poke(2.U)
      d.io.task.valid.poke(true.B);d.io.task.ready.expect(true.B);d.clock.step();d.io.task.valid.poke(false.B)
      def until(p: => Boolean):Unit={var n=0;while(!p && n<100){d.clock.step();n+=1};assert(p)}
      def emit(v:Int):Unit={val c=d.io.command.bits;c.operation.poke(0.U)
        c.vertex.components(0).poke(v.U);c.vertex.shaderPrimitiveId.poke((10+v).U)
        d.io.command.valid.poke(true.B);until(d.io.command.ready.peek().litToBoolean);d.clock.step();d.io.command.valid.poke(false.B)}
      def check(ids:Seq[Int]):Unit={until(d.io.out.valid.peek().litToBoolean);val p=d.io.out.bits.primitive
        p.primitiveId.expect((10+ids.last).U);p.provokingVertex.expect(2.U)
        for((id,v)<-ids.zipWithIndex)p.vertices(v).position(0).expect(id.U)
        d.io.fault.valid.expect(false.B);d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)}
      emit(1);emit(2);emit(3);check(Seq(1,2,3));emit(4);check(Seq(3,2,4))
      d.io.command.bits.operation.poke(2.U);d.io.command.valid.poke(true.B)
      until(d.io.command.ready.peek().litToBoolean);d.clock.step();d.io.command.valid.poke(false.B)
      until(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.status.expect(0.U)
    }
  }

}
