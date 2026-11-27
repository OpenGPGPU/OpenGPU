package opengpu.system

import chisel3._
import chisel3.reflect.DataMirror
import chisel3.ActualDirection
import java.nio.file.{Files, Paths}
import opengpu.testutil.{GpuSim, GraphicsTestResolution}
import chisel3.simulator.PeekPokeAPI

object RetainFiveStageMesaModel extends PeekPokeAPI {
  def main(args: Array[String]): Unit = {
    require(args.length == 2, "retained-model-directory model-source-binding.json")
    require(
      !GraphicsTestResolution.productQualification && GraphicsTestResolution.width == 640 && GraphicsTestResolution.height == 480
    )
    val target = Paths.get(args(0)).toAbsolutePath.normalize(); val source = Paths.get(args(1)).toRealPath()
    require(!Files.exists(target), "consumer target must not exist")
    val binding = ujson.read(Files.readString(source))
    require(binding("schema").str == "opengpu-original-five-isoline-no-tf-v1")
    require(
      binding("properties")("opengpu.productQualification").str == "0" && binding("supported_graphics").arr
        .map(_.num.toInt)
        .toVector == Vector(640, 480)
    )
    require(!binding("transformFeedback").bool && !binding("abi10").bool && binding("physicalGraphicsCUs").num == 2)
    require(binding("hostRoute").str == "direct-typed-isoline" && binding("recordBytes").num == 1472)
    require(
      binding("cases").arr.map(c => (c("name").str, c("width").num.toInt)).toVector == Vector(
        ("full5", 4),
        ("pointcoord", 4)
      )
    )
    GpuSim.simulateWithInlineVerificationRetainingModel(
      new FiveStageIsolineMesaTop,
      GpuSim.RetainedModelConsumer(target, "original-linked-five-isoline-no-tf", source)
    ) { d =>
      def zero(data: Data): Unit = data match {
        case r: Record => r.elements.values.foreach(zero); case v: Vec[_] => v.foreach(zero)
        case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
        case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
        case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s.poke(0.S)
        case _                                                             => ()
      }
      require(d.cfg.lanes == 16 && d.cfg.warps == 8 && d.cfg.enableSharedVectorAtomics)
      require(
        d.p.input.attributes == 16 && d.p.input.bindings == 16 && d.p.varyings == 16 && d.f.varyings == 16 && d.f.colors == 4
      )
      require(d.shared.numComputeUnits == 2 && d.engines == 4 && d.payload.owners == 5 && d.payload.ports == 12)
      require(d.imageSlots == 256 && d.hostSlots == 512 && d.memorySlots == 592 && d.recordBytes == 1472)
      require(!d.io.elements.contains("feedback"))
      zero(d.io); d.io.frameDrained.poke(true.B); d.reset.poke(true.B); d.clock.step(3); d.reset.poke(false.B);
      d.clock.step(5)
      d.io.traps.expect(0.U); d.io.busy.expect(false.B); d.io.sourceLive.expect(false.B)
      d.io.sourceDrained.expect(true.B); d.io.poolReserved.expect(0.U); d.io.poolDrained.expect(true.B)
      println("FIVE_STAGE_MODEL_RESET_IDLE_ONLY originalShaderExecuted=false noTf=true physicalGraphicsCUs=2")
    }
  }
}
