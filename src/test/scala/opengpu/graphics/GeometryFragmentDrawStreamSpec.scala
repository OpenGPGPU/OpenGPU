package opengpu.graphics

import opengpu.system._

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.graphics._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class GeometryFragmentDrawStreamSpec extends AnyFlatSpec {
  private def zero(data: Data): Unit = data match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s.poke(0.S)
    case _ => ()
  }
  private def start(d: GeometryFragmentDrawStream): Unit = {
    zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
    d.io.begin.bits.raster.geometry.geometry.primitive.firstSequence.poke(7.U)
    d.io.begin.valid.poke(true.B); d.io.begin.ready.expect(true.B); d.clock.step()
    d.io.begin.valid.poke(false.B)
  }

  it should "serialize multiple input primitives and retain draw-wide output order" in {
    simulate(new GeometryFragmentDrawStream(GpuConfig(lanes=4,warps=2),FragmentShaderConfig(),4,
      GraphicsConfig(screenWidth=4,screenHeight=4))) { d =>
      start(d)
      for(i <- 0 until 2) {
        d.io.primitive.bits.primitiveId.poke((10+i).U); d.io.primitive.valid.poke(true.B)
        d.io.primitive.ready.expect(true.B); d.clock.step(); d.io.primitive.valid.poke(false.B)
        d.io.childDraw.valid.expect(true.B)
        d.io.childDraw.bits.raster.geometry.geometry.primitive.input.primitive.primitiveId.expect((10+i).U)
        d.io.childDraw.bits.raster.geometry.geometry.primitive.firstSequence.expect((7+2*i).U)
        val held = d.io.childDraw.bits.peek().litValue
        d.clock.step(2); assert(d.io.childDraw.bits.peek().litValue == held)
        d.io.childDraw.ready.poke(true.B); d.clock.step(); d.io.childDraw.ready.poke(false.B)
        d.io.childOut.bits.sequence.poke(99.U); d.io.childOut.valid.poke(true.B)
        d.io.out.valid.expect(true.B); d.io.out.bits.sequence.expect(i.U)
        d.clock.step(2); d.io.out.bits.sequence.expect(i.U)
        d.io.out.ready.poke(true.B); d.clock.step(); d.io.out.ready.poke(false.B); d.io.childOut.valid.poke(false.B)
        d.io.childCompletion.bits.raster.source.source.geometry.producedPrimitives.poke(2.U)
        d.io.childCompletion.bits.raster.source.source.geometry.nextSequence.poke((9+2*i).U)
        d.io.childCompletion.valid.poke(true.B); d.io.childCompletion.ready.expect(true.B); d.clock.step()
        d.io.childCompletion.valid.poke(false.B)
      }
      d.io.inputEnd.bits.primitives.poke(2.U); d.io.inputEnd.valid.poke(true.B)
      d.io.inputEnd.ready.expect(true.B); d.clock.step(); d.io.inputEnd.valid.poke(false.B); d.clock.step()
      d.io.completion.valid.expect(true.B); d.io.completion.bits.status.expect(0.U)
      d.io.completion.bits.inputPrimitives.expect(2.U); d.io.completion.bits.geometryPrimitives.expect(4.U)
      d.io.completion.bits.outputQuads.expect(2.U)
      val result = d.io.completion.bits.peek().litValue; d.clock.step(2)
      assert(d.io.completion.bits.peek().litValue == result)
      d.io.completion.ready.poke(true.B); d.clock.step(); d.io.busy.expect(false.B)
    }
  }

  it should "retain an unaccepted child offer and drain the source after cancellation" in {
    simulate(new GeometryFragmentDrawStream(GpuConfig(lanes=4,warps=2),FragmentShaderConfig(),4,
      GraphicsConfig(screenWidth=4,screenHeight=4))) { d =>
      start(d)
      d.io.primitive.valid.poke(true.B); d.io.primitive.ready.expect(true.B); d.clock.step()
      d.io.primitive.valid.poke(false.B); d.io.cancel.poke(true.B)
      d.io.childDraw.valid.expect(true.B); d.io.childCancel.expect(false.B); d.io.sourceCancel.expect(true.B)
      d.clock.step(2); d.io.childDraw.valid.expect(true.B)
      d.io.childDraw.ready.poke(true.B); d.clock.step(); d.io.childDraw.ready.poke(false.B)
      d.io.childCancel.expect(true.B)
      d.io.childCompletion.bits.status.poke(4.U); d.io.childCompletion.valid.poke(true.B); d.clock.step()
      d.io.childCompletion.valid.poke(false.B); d.io.cancel.poke(false.B)
      d.io.primitive.valid.poke(true.B); d.io.primitive.ready.expect(true.B); d.clock.step()
      d.io.primitive.valid.poke(false.B); d.io.childDraw.valid.expect(false.B)
      d.io.inputEnd.bits.primitives.poke(2.U); d.io.inputEnd.valid.poke(true.B); d.clock.step()
      d.io.inputEnd.valid.poke(false.B); d.clock.step()
      d.io.completion.valid.expect(true.B); d.io.completion.bits.status.expect(4.U)
      d.io.completion.bits.inputPrimitives.expect(2.U)
    }
  }

  it should "preserve a source fault when the active child is cancelled" in {
    simulate(new GeometryFragmentDrawStream(GpuConfig(lanes=4,warps=2),FragmentShaderConfig(),4,
      GraphicsConfig(screenWidth=4,screenHeight=4))) { d =>
      start(d)
      d.io.primitive.valid.poke(true.B); d.clock.step(); d.io.primitive.valid.poke(false.B)
      d.io.childDraw.ready.poke(true.B); d.clock.step(); d.io.childDraw.ready.poke(false.B)
      d.io.inputEnd.bits.primitives.poke(1.U); d.io.inputEnd.bits.status.poke(5.U)
      d.io.inputEnd.valid.poke(true.B); d.clock.step(); d.io.inputEnd.valid.poke(false.B)
      d.io.childCancel.expect(true.B); d.io.sourceCancel.expect(true.B)
      d.io.childCompletion.bits.status.poke(4.U); d.io.childCompletion.valid.poke(true.B); d.clock.step()
      d.io.childCompletion.valid.poke(false.B); d.clock.step()
      d.io.completion.valid.expect(true.B); d.io.completion.bits.status.expect(5.U)
    }
  }

  it should "preserve a simultaneous source fault over child cancellation" in {
    simulate(new GeometryFragmentDrawStream(GpuConfig(lanes=4,warps=2),FragmentShaderConfig(),4,
      GraphicsConfig(screenWidth=4,screenHeight=4))) { d =>
      start(d)
      d.io.primitive.valid.poke(true.B); d.clock.step(); d.io.primitive.valid.poke(false.B)
      d.io.childDraw.ready.poke(true.B); d.clock.step(); d.io.childDraw.ready.poke(false.B)
      d.io.inputEnd.bits.primitives.poke(1.U); d.io.inputEnd.bits.status.poke(5.U)
      d.io.inputEnd.valid.poke(true.B); d.io.childCompletion.bits.status.poke(4.U)
      d.io.childCompletion.valid.poke(true.B); d.clock.step()
      d.io.inputEnd.valid.poke(false.B); d.io.childCompletion.valid.poke(false.B); d.clock.step()
      d.io.completion.valid.expect(true.B); d.io.completion.bits.status.expect(5.U)
    }
  }

  it should "reject a mismatched source count and permit a clean subsequent draw" in {
    simulate(new GeometryFragmentDrawStream(GpuConfig(lanes=4,warps=2),FragmentShaderConfig(),4,
      GraphicsConfig(screenWidth=4,screenHeight=4))) { d =>
      start(d)
      d.io.inputEnd.bits.primitives.poke(1.U); d.io.inputEnd.valid.poke(true.B); d.clock.step()
      d.io.inputEnd.valid.poke(false.B); d.clock.step()
      d.io.completion.valid.expect(true.B); d.io.completion.bits.status.expect(1.U)
      d.io.completion.ready.poke(true.B); d.clock.step(); d.io.completion.ready.poke(false.B)
      d.io.begin.valid.poke(true.B); d.io.begin.ready.expect(true.B); d.clock.step(); d.io.begin.valid.poke(false.B)
      d.io.inputEnd.bits.primitives.poke(0.U); d.io.inputEnd.valid.poke(true.B); d.clock.step()
      d.io.inputEnd.valid.poke(false.B); d.clock.step()
      d.io.completion.valid.expect(true.B); d.io.completion.bits.status.expect(0.U)
      d.io.completion.bits.outputQuads.expect(0.U)
    }
  }
}
