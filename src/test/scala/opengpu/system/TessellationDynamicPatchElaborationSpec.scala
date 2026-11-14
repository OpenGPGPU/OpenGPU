package opengpu.system

import circt.stage.ChiselStage
import opengpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

/** Structural checks only: no CU or shader is executed. */
class TessellationDynamicPatchElaborationSpec extends AnyFlatSpec {
  private val config=GpuConfig(lanes=16,warps=8)
  for(domain<-Seq("Isoline","Triangle","Quad"))it should s"select owned initialization in the actual $domain patch hierarchy only for dynamic reads" in {
    for(dynamic<-Seq(false,true)){
      val fir=domain match {
        case "Isoline"=>ChiselStage.emitCHIRRTL(new TessellationCuIsolineSharedEvaluator(config,8,2,evaluationPatchAvailable=dynamic))
        case "Triangle"=>ChiselStage.emitCHIRRTL(new TessellationCuTriangleSharedEvaluator(config,8,2,evaluationPatchAvailable=dynamic))
        case "Quad"=>ChiselStage.emitCHIRRTL(new TessellationCuQuadSharedEvaluator(config,8,2,evaluationPatchAvailable=dynamic))
      }
      val at=fir.indexOf("module TessellationPatchStore :");assert(at>=0)
      val end=fir.indexOf("\n  module ",at+1)
      val store=fir.substring(at,if(end<0)fir.length else end)
      assert(store.contains("clearAddress")==dynamic,"actual store must capture the new-mode initialization option")
      assert("(?:smem|cmem) (?:input|output) : UInt<32>\\[2048\\]".r.findAllIn(store).size==2)
      assert("write mport \\w+ = (?:input|output)\\[".r.findAllIn(store).size==2,
        "initialization must share the original one write port per bank")
      assert(fir.contains("evaluationPatchBinding")==dynamic,"legacy IO must remain frozen")
    }
  }
}
