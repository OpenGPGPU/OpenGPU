package opengpu.core.memory

import chisel3._
import chisel3.experimental.hierarchy.Instantiate
import opengpu.config.GpuConfig

private class FourMshrDefinitions(cached: Boolean) extends Module {
  val live=IO(Output(Vec(4,UInt(4.W))))
  for(i<-0 until 4){
    val p=GpuConfig(lanes=16,warps=8,l2Sets=512,l2Ways=4)
    val port=if(cached)Instantiate(new L2MshrTable(p,4,32850,2,64,128,4)).io
      else Module(new L2MshrTable(p,4,32850,2,64,128,4)).io
    port.request.valid:=false.B;port.request.bits:=0.U.asTypeOf(port.request.bits)
    port.allocation.ready:=true.B;port.refill.valid:=false.B
    port.refill.bits:=0.U.asTypeOf(port.refill.bits)
    port.fill.ready:=true.B;port.response.ready:=true.B
    live(i):=port.validEntries
  }
}

object QualifyL2MshrDefinitions {
  private def invoke(o: AnyRef,n: String): AnyRef=o.getClass.getMethod(n).invoke(o)
  private def count(block: AnyRef): Long=invoke(block,"getAllCommands").asInstanceOf[Seq[AnyRef]].iterator.map{cmd=>
    if(cmd.getClass.getSimpleName=="When")1L+count(invoke(cmd,"ifRegion"))+
      (if(invoke(cmd,"hasElse").asInstanceOf[java.lang.Boolean])count(invoke(cmd,"elseRegion"))else 0L)
    else 1L
  }.sum
  private def run(cached: Boolean): (Int,Long)={
    val anns=new chisel3.stage.phases.Elaborate().transform(Seq(
      chisel3.stage.ChiselGeneratorAnnotation(()=>new FourMshrDefinitions(cached))))
    val ec=anns.collectFirst{case a:chisel3.stage.ChiselCircuitAnnotation=>a.elaboratedCircuit}.get
    val modules=invoke(invoke(ec,"_circuit"),"components").asInstanceOf[Seq[AnyRef]]
    val definitions=modules.filter(m=>m.getClass.getMethods.exists(_.getName=="block"))
    val tables=definitions.filter(m=>invoke(m,"name").toString.startsWith("L2MshrTable"))
    val commands=definitions.map(m=>count(invoke(m,"block"))).sum
    val parent=definitions.filter(m=>invoke(m,"name").toString.startsWith("FourMshrDefinitions")).head
    val instances=invoke(invoke(parent,"block"),"getAllCommands").asInstanceOf[Seq[AnyRef]]
      .count(_.getClass.getSimpleName.contains("Instance"))
    println(s"MSHR_DEFINITION_PROBE cached=$cached definitions=${tables.size} commands=$commands physicalInstances=$instances entries=4 transactions=32850")
    assert(tables.size==(if(cached)1 else 4))
    assert(instances==4)
    (tables.size,commands)
  }
  def main(args: Array[String]): Unit={
    require(sys.props.get("opengpu.l2MshrDefinitionQualification").contains("1"),
      "Full-namespace elaboration requires -Dopengpu.l2MshrDefinitionQualification=1")
    val baseline=run(false);System.gc()
    val reused=run(true)
    assert(reused._2<baseline._2)
    println(s"MSHR_DEFINITION_PROBE PASS savedCommands=${baseline._2-reused._2} identicalFullCapacity=true")
  }
}
