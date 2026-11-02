package opengpu.graphics

import chisel3._
import circt.stage.ChiselStage
import opengpu.config.GpuConfig
import opengpu.system.SharedCuShaderConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TypedReplayFragmentImageElaborationSpec extends AnyFlatSpec {
  it should "elaborate legacy and image-enabled replay paths" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    val p = VertexShaderStageConfig(VertexInputFetchConfig(2, 2), 1)
    val f = FragmentShaderConfig(1, 4)
    val g = GraphicsConfig(screenWidth = 4, screenHeight = 4)
    for (enabled <- Seq(false, true)) {
      val chirrtl = ChiselStage.emitCHIRRTL(new TypedReplayTileImagePipeline(config, p, f,
        SharedCuShaderConfig(1), g, fragmentImageAvailable = enabled))
      assert(chirrtl.contains("TypedReplayTileImagePipeline"))
    }
  }
  it should "lower the image-enabled replay owner through FIRRTL" in {
    val rtl = ChiselStage.emitSystemVerilog(new TypedReplayTileImagePipeline(
      GpuConfig(lanes = 4, warps = 2), VertexShaderStageConfig(VertexInputFetchConfig(2, 2), 1),
      FragmentShaderConfig(1, 4), SharedCuShaderConfig(1), GraphicsConfig(screenWidth = 4, screenHeight = 4),
      fragmentImageAvailable = true))
    assert(rtl.contains("imageTask"))
  }
  it should "reject mismatched image roots and retain valid tasks at a stalled shader launch" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    val p = VertexShaderStageConfig(VertexInputFetchConfig(2, 2), 1)
    val f = FragmentShaderConfig(1, 4)
    val g = GraphicsConfig(screenWidth = 4, screenHeight = 4)
    simulate(new TypedReplayTileImagePipeline(config, p, f, SharedCuShaderConfig(1), g,
      fragmentImageAvailable = true)) { d =>
      import WindowRecordTest.{zero, Memory}
      zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      val mem = new Memory(d.io.memoryRequest.bits, d.io.memoryResponse.bits, d.io.memoryRequest.valid,
        d.io.memoryRequest.ready, d.io.memoryResponse.valid, d.io.memoryResponse.ready)
      var invalidation: Option[BigInt] = None
      def tick(): Unit = {
        mem.drive(); d.io.shared.invalidate.ready.poke(invalidation.isEmpty.B)
        d.io.shared.invalidateDone.valid.poke(invalidation.nonEmpty.B)
        invalidation.foreach(a => d.io.shared.invalidateDone.bits.lineAddress.poke(a.U))
        val completed=d.io.shared.invalidateDone.valid.peek().litToBoolean && d.io.shared.invalidateDone.ready.peek().litToBoolean
        val offered=d.io.shared.invalidate.valid.peek().litToBoolean && d.io.shared.invalidate.ready.peek().litToBoolean
        val address=if(offered)Some(d.io.shared.invalidate.bits.lineAddress.peek().litValue)else None
        mem.capture(); d.clock.step()
        if(completed)invalidation=None
        if(offered)invalidation=address
      }
      def until(p: => Boolean): Unit = { var n=0; while(!p && n<10000){tick();n+=1};assert(p,s"replay timeout after $n cycles") }
      val fields = TypedFragmentStateLayout.fields(new TypedFragmentState(f))
      val data = Map[String, BigInt]("version" -> 1, "engineCount" -> 1, "fragment.version" -> 1,
        "fragment.identity.context" -> 7, "fragment.identity.pass" -> 8, "fragment.identity.generation" -> 9,
        "fragment.shaderPc" -> 0x1000, "fragment.scratch.base" -> 0x100000,
        "fragment.scratch.limit" -> 0x101000, "fragment.scratch.allocationBase" -> 0x100000,
        "fragment.scratch.allocationLimit" -> 0x101000, "tests.version" -> 1,
        "image.version" -> 1, "image.address" -> 0x5000)
      for((v,w)<-Vector[BigInt](TypedFragmentStateLayout.magic,512,1,4,7,8,9,0).zipWithIndex)mem.putWord(0x2000+4*w,v)
      for(field<-fields){val raw=data.getOrElse(field.name,BigInt(0))&((BigInt(1)<<(field.words*32))-1)
        for(w<-0 until field.words)mem.putWord(0x2000+4*(field.word+w),(raw>>(32*w))&0xffffffffL)}
      for((v,w)<-Vector[BigInt](ShaderImagePublicationLayout.magic,64,1,99,8,9,1,1,0x8000,0x8400,0).zipWithIndex)
        mem.putWord(0x5000+4*w,v)
      def identity(i: GraphicsWorkIdentity): Unit={i.context.poke(7.U);i.pass.poke(8.U);i.generation.poke(9.U)}
      val begin=d.io.begin.bits;begin.states.version.poke(1.U);identity(begin.states.identity)
      begin.states.base.poke("h2000".U);begin.states.limit.poke("h2200".U);begin.engines.poke(1.U)
      begin.scratchStride.poke(4096.U);begin.scratch(0).base.poke("h100000".U);begin.scratch(0).limit.poke("h101000".U)
      begin.image.version.poke(1.U);begin.image.pixels.version.poke(1.U);val colors=begin.image.pixels.colors
      colors.version.poke(1.U);identity(colors.identity);colors.tileLog2.poke(3.U);colors.tileWidth.poke(4.U)
      colors.tileHeight.poke(4.U);colors.reservedBytes.poke(4096.U)
      d.io.begin.valid.poke(true.B);until(d.io.begin.ready.peek().litToBoolean);tick();d.io.begin.valid.poke(false.B)
      until(d.io.in.ready.peek().litToBoolean)
      identity(d.io.in.bits.source.identity);identity(d.io.in.bits.record.primitive.clipped.primitive.identity)
      d.io.in.valid.poke(true.B);tick();d.io.in.valid.poke(false.B)
      until(d.io.primitiveDone.valid.peek().litToBoolean)
      d.io.primitiveDone.bits.status.expect(5.U);d.io.primitiveDone.bits.faultStage.expect(1.U)
      d.io.primitiveDone.bits.renderStarted.expect(false.B);d.io.shared.launch.valid.expect(false.B)
      d.io.primitiveDone.bits.stateReadLines.expect(8.U);d.io.primitiveDone.bits.stateHit.expect(false.B)
      assert(mem.seen.count(_.address == 0x5000) == 1)
      assert(mem.seen.find(_.address == 0x5000).exists(_.tag == 16))
      d.io.primitiveDone.ready.poke(true.B);tick();d.io.primitiveDone.ready.poke(false.B)
      until(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.status.expect(5.U)
      assert(mem.pending.isEmpty && mem.reply.isEmpty)

      // A correctly scoped root must travel all the way to the held CU offer.
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B);invalidation=None
      mem.putWord(0x5000+3*4,7)
      begin.states.version.poke(1.U);identity(begin.states.identity);begin.states.base.poke("h2000".U)
      begin.states.limit.poke("h2200".U);begin.engines.poke(1.U);begin.scratchStride.poke(4096.U)
      begin.scratch(0).base.poke("h100000".U);begin.scratch(0).limit.poke("h101000".U)
      begin.image.version.poke(1.U);begin.image.pixels.version.poke(1.U);colors.version.poke(1.U)
      identity(colors.identity);colors.tileLog2.poke(3.U);colors.tileWidth.poke(4.U);colors.tileHeight.poke(4.U);colors.reservedBytes.poke(4096.U)
      d.io.begin.valid.poke(true.B);until(d.io.begin.ready.peek().litToBoolean);tick();d.io.begin.valid.poke(false.B)
      until(d.io.in.ready.peek().litToBoolean)
      val base=TriangleRasterReference.base
      val triangle=base.copy(coords=Vector((BigInt(0),BigInt(0)),(BigInt(4*256),BigInt(0)),(BigInt(0),BigInt(4*256))),
        values=base.values.copy(values=Vector.fill(3)(Vector.empty[BigInt]),flat=0,slots=0))
      TriangleRasterReference.primitive(d.io.in.bits.record.primitive,triangle,0)
      identity(d.io.in.bits.source.identity);identity(d.io.in.bits.record.primitive.clipped.primitive.identity)
      d.io.in.bits.record.primitive.clipped.primitive.vertices.foreach(v=>identity(v.identity))
      TriangleRasterReference.state(d.io.in.bits.record.raster,TriangleRasterReference.Settings(width=4,height=4))
      d.io.in.valid.poke(true.B);tick();d.io.in.valid.poke(false.B)
      until(d.io.shared.launch.valid.peek().litToBoolean || d.io.completion.valid.peek().litToBoolean)
      d.io.completion.valid.expect(false.B);d.io.shared.launch.valid.expect(true.B)
      val task=d.io.shared.imageTask.get;task.enabled.expect(true.B);task.stage.expect(GraphicsStage.fragment.U)
      task.table.identity.context.expect(7.U);task.table.identity.pass.expect(8.U);task.table.identity.generation.expect(9.U)
      task.table.base.expect("h8000".U);task.table.limit.expect("h8400".U)
      val held=task.peek().litValue
      for(_<-0 until 3){tick();d.io.shared.launch.valid.expect(true.B);assert(task.peek().litValue==held)}
    }
  }
}
