package opengpu.graphics

import chisel3._
import chisel3.experimental.hierarchy.Instantiate

private class FourTextureAddressDefinitions(cached: Boolean) extends Module {
  val busy=IO(Output(Vec(4,Bool())))
  for(i<-0 until 4){
    val p=TextureImageAddressConfig()
    val port=if(cached)Instantiate(new TextureImageAddressUnit(p,true,true)).io
      else Module(new TextureImageAddressUnit(p,true,true)).io
    port.in.valid:=false.B;port.in.bits:=0.U.asTypeOf(port.in.bits)
    port.out.ready:=true.B;busy(i):=port.busy
  }
}

/** Raw elaboration only: no RTL, physical sharing, or execution claim. */
object QualifyTextureAddressDefinitions {
  private def invoke(o: AnyRef,n: String): AnyRef=o.getClass.getMethod(n).invoke(o)
  private def count(block: AnyRef): Long=invoke(block,"getAllCommands").asInstanceOf[Seq[AnyRef]].iterator.map{cmd=>
    if(cmd.getClass.getSimpleName=="When")1L+count(invoke(cmd,"ifRegion"))+
      (if(invoke(cmd,"hasElse").asInstanceOf[java.lang.Boolean])count(invoke(cmd,"elseRegion"))else 0L)
    else 1L
  }.sum
  private def run(cached: Boolean): Long={
    val anns=new chisel3.stage.phases.Elaborate().transform(Seq(
      chisel3.stage.ChiselGeneratorAnnotation(()=>new FourTextureAddressDefinitions(cached))))
    val ec=anns.collectFirst{case a:chisel3.stage.ChiselCircuitAnnotation=>a.elaboratedCircuit}.get
    val modules=invoke(invoke(ec,"_circuit"),"components").asInstanceOf[Seq[AnyRef]]
      .filter(_.getClass.getMethods.exists(_.getName=="block"))
    val units=modules.filter(m=>invoke(m,"name").toString.startsWith("TextureImageAddressUnit"))
    val parent=modules.find(m=>invoke(m,"name").toString.startsWith("FourTextureAddressDefinitions")).get
    val instances=invoke(invoke(parent,"block"),"getAllCommands").asInstanceOf[Seq[AnyRef]]
      .count(_.getClass.getSimpleName.contains("Instance"))
    val commands=modules.map(m=>count(invoke(m,"block"))).sum
    require(units.size==(if(cached)1 else 4) && instances==4)
    println(s"TEXTURE_ADDRESS_DEFINITION_PROBE cached=$cached definitions=${units.size} physicalInstances=$instances recursiveCommands=$commands")
    commands
  }
  def main(args: Array[String]): Unit={
    require(sys.props.get("opengpu.textureAddressDefinitionQualification").contains("1"),
      "Use -Dopengpu.textureAddressDefinitionQualification=1 for the raw elaboration comparison")
    val baseline=run(false);System.gc()
    val cached=run(true);require(cached<baseline)
    println(s"TEXTURE_ADDRESS_DEFINITION_PROBE PASS savedCommands=${baseline-cached} physicalInstancesUnchanged=true")
  }
}
