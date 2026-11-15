package opengpu.system

import chisel3._
import opengpu.config.GpuConfig
import opengpu.graphics.VertexShaderStageConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

/** Actual viewport, mapper, evaluator, context and patch-store path. The
  * shared-CU interface is a transport peer; its emitted values are not a shader
  * execution oracle and this test is not actual-CU qualification. */
private class TessellationViewportMappingObservation extends Bundle {
  val offered=Bool();val sequence=UInt(64.W);val primitiveId=UInt(32.W)
}
private class IsolineViewportEndpoint extends TessellationSystemIsolineViewport(
    GpuConfig(lanes=16,warps=8),VertexShaderStageConfig(),4,2,evaluationPatchAvailable=true) {
  val mapping=IO(Output(new TessellationViewportMappingObservation))
  mapping.offered:=mapper.io.in.valid
  mapping.sequence:=mapper.io.in.bits.sequence;mapping.primitiveId:=mapper.io.in.bits.primitiveId
}
private class TriangleViewportEndpoint extends TessellationSystemTriangleViewport(
    GpuConfig(lanes=16,warps=8),VertexShaderStageConfig(),4,2,evaluationPatchAvailable=true) {
  val mapping=IO(Output(new TessellationViewportMappingObservation))
  mapping.offered:=mapper.io.in.valid
  mapping.sequence:=mapper.io.in.bits.sequence;mapping.primitiveId:=mapper.io.in.bits.primitiveId
}
private class QuadViewportEndpoint extends TessellationSystemQuadViewport(
    GpuConfig(lanes=16,warps=8),VertexShaderStageConfig(),4,2,evaluationPatchAvailable=true) {
  val mapping=IO(Output(new TessellationViewportMappingObservation))
  mapping.offered:=mapper.io.in.valid
  mapping.sequence:=mapper.io.in.bits.sequence;mapping.primitiveId:=mapper.io.in.bits.primitiveId
}
private class TessellationViewportEndpointHarness(domain:Int) extends Module {
  private val actual:(Record,TessellationViewportMappingObservation)=domain match {
    case 0=>val c=Module(new IsolineViewportEndpoint);(c.io,c.mapping)
    case 1=>val c=Module(new TriangleViewportEndpoint);(c.io,c.mapping)
    case 2=>val c=Module(new QuadViewportEndpoint);(c.io,c.mapping)
  }
  val io=IO(chiselTypeOf(actual._1));io<>actual._1
  val mapping=IO(Output(new TessellationViewportMappingObservation));mapping:=actual._2
}

class TessellationViewportPublicationEndpointSpec extends AnyFlatSpec {
  private def zero(x:Data):Unit=x match {
    case r:Record=>r.elements.values.foreach(zero);case v:Vec[_]=>v.foreach(zero)
    case b:Bool if chisel3.reflect.DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u:UInt if chisel3.reflect.DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case _=>()
  }
  private def at(root:Data,path:String):Data=path.split('.').foldLeft(root){(d,n)=>d match {
    case r:Record=>r.elements(n);case v:Vec[_]=>v(n.toInt)
  }}
  for(domain<-0 until 3){
    it should s"suppress the second unrepresentable actual domain $domain publication before mapping" in {
      simulate(new TessellationViewportEndpointHarness(domain)){d=>
        zero(d.io);d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
        def x(path:String)=at(d.io,path)
        def put(path:String,n:BigInt):Unit=x(path) match {
          case b:Bool=>b.poke((n!=0).B);case u:UInt=>u.poke(n.U)
        }
        def value(path:String):BigInt=x(path).asInstanceOf[UInt].peek().litValue
        def yes(path:String)=value(path)!=0
        def fire(path:String)=yes(path+".valid") && yes(path+".ready")
        var owner=false;var phase=0;var automatic=false;var event=0;var evaluations=0;var outputs=0
        var expectedSequence=BigInt(0);var expectedPrimitive=BigInt(0)
        var outputHeld:Option[BigInt]=None;var holds=0
        def tick():Unit={
          put("shared.owned",if(owner)1 else 0);put("shared.taskIds.0",3)
          put("shared.launch.ready",1)
          put("shared.geometryEvent.0.valid",if(automatic && phase==2 && event<1)1 else 0)
          // Only actual kernel completion closes the geometry invocation.
          put("shared.geometryEvent.0.bits.operation",0)
          put("shared.geometryEvent.0.bits.vertex.components.3",BigInt("3f800000",16))
          if(automatic)put("shared.completion.valid",if(phase==2 && event==1)1 else 0)
          put("shared.completion.bits.commandId",3);put("shared.completion.bits.success",1)
          val offered=fire("shared.launch")
          val evaluating=yes("shared.geometryTask.valid")
          val eventFire=fire("shared.geometryEvent.0");val complete=fire("shared.completion")
          if(d.mapping.offered.peek().litToBoolean){
            assert(d.mapping.sequence.peek().litValue==expectedSequence,"wrapped sequence was offered to actual mapper")
            assert(d.mapping.primitiveId.peek().litValue==expectedPrimitive,"wrapped primitive ID was offered to actual mapper")
          }
          val valid=yes("out.valid")
          assert(outputHeld.isEmpty || valid,"actual held viewport output was withdrawn")
          val keep=valid && holds<4
          put("out.ready",if(keep)0 else 1)
          if(valid){
            val raw=x("out.bits").peek().litValue
            outputHeld.foreach(v=>assert(v==raw,"actual held viewport output changed"))
            assert(value("out.bits.clipped.primitive.sequence")==expectedSequence,"wrapped sequence reached viewport output")
            assert(value("out.bits.clipped.primitive.primitiveId")==expectedPrimitive,"wrapped primitive ID reached viewport output")
            if(keep){outputHeld=Some(raw);holds+=1}else{outputHeld=None;outputs+=1}
          }
          d.clock.step()
          if(offered){assert(!owner,"overlapping shared launch");owner=true;phase=if(evaluating)2 else 1;event=0
            if(evaluating)evaluations+=1}
          if(eventFire)event+=1
          if(complete){owner=false;phase=0}
        }
        def until(label:String,limit:Int=10000)(condition: =>Boolean):Unit={
          var n=0;while(!condition && n<limit){tick();n+=1};assert(condition,s"$label stalled (phase=$phase owner=$owner evaluations=$evaluations event=$event outputs=$outputs)")
        }
        def send(path:String):Unit={put(path+".valid",1);until(path)(yes(path+".ready"));tick();put(path+".valid",0)}
        def run(sequence:BigInt,primitive:BigInt):Unit={
          automatic=false;owner=false;phase=0;event=0;evaluations=0;outputs=0;outputHeld=None;holds=0
          expectedSequence=sequence;expectedPrimitive=primitive
          put("task.bits.firstSequence",sequence);put("task.bits.firstPrimitiveId",primitive)
          val context="task.bits.system.patch.control.patch.context"
          put(context+".taskId",71);put(context+".layout.inputVertices",1)
          put(context+".layout.inputComponents",0);put(context+".layout.outputVertices",1)
          put(context+".layout.outputComponents",4);put(context+".layout.patchComponents",0)
          put("task.bits.system.patch.control.patch.pointMode",1)
          put("task.bits.system.patch.commandId",3);put("task.bits.system.patch.outputComponents",4)
          for(i<-0 until 3){put(s"task.bits.system.patch.control.kernel.gridSize.$i",1)
            put(s"task.bits.system.patch.control.kernel.localSize.$i",1)}
          for(i<-0 until 4){put(s"task.bits.componentSources.$i.valid",1);put(s"task.bits.componentSources.$i.bits",i)}
          put("task.bits.viewport.version",1);put("task.bits.viewport.extentX",4);put("task.bits.viewport.extentY",4)
          put("task.bits.viewport.farDepth",BigInt("3f800000",16))
          put("shared.tessellation.0.result.ready",1);put("shared.tessellation.0.resume.ready",1)
          send("task");put("opened.ready",1);until("owned opening")(yes("opened.valid"))
          assert(value("opened.bits")==0);tick();put("opened.ready",0);send("seal")
          until("actual control launch")(phase==1)
          // Real control requests populate the owned store's six levels.
          for(i<-0 until 6){
            val a="shared.tessellation.0.request.bits"
            put(a+".warp",0);put(a+".base",0);put(a+".mask",1)
            put(a+".access.0.output",1);put(a+".access.0.perPatch",1)
            put(a+".access.0.tessLevel",1);put(a+".access.0.write",1)
            put(a+".access.0.component",i);put(a+".access.0.data",BigInt("40000000",16))
            send("shared.tessellation.0.request")
            until("actual level write reply")(yes("shared.tessellation.0.result.valid"))
            assert(value("shared.tessellation.0.result.bits.status")==0);tick()
          }
          put("shared.tessellation.0.finish.bits",0);send("shared.tessellation.0.finish")
          put("shared.completion.bits.commandId",3);put("shared.completion.bits.success",1)
          send("shared.completion");owner=false;phase=0;automatic=true
          until("overflow completion")(yes("completion.valid"))
          assert(value("completion.bits.status")==5,"second unrepresentable publication must fault before mapper")
          assert(value("completion.bits.mapped")==1,"exactly the last representable primitive may enter mapper")
          assert(value("completion.bits.patch.published")>=2,"actual second publication was not exercised")
          assert(evaluations>=2 && outputs<=1 && !owner,"actual evaluator/CU transport did not drain")
          val held=x("completion.bits").peek().litValue
          put("cancel",1);for(_<-0 until 4){tick();assert(x("completion.bits").peek().litValue==held)}
          put("completion.ready",1);tick();put("completion.ready",0);put("cancel",0)
          until("reset-free reuse")(yes("task.ready"))
        }
        run((BigInt(1)<<64)-1,7)
        run(13,(BigInt(1)<<32)-1)
      }
    }
  }
}
