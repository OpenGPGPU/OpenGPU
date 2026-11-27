package opengpu.system

import chisel3._
import opengpu.config.GpuConfig
import opengpu.graphics._
import opengpu.testutil.GpuSim._
import opengpu.testutil.GraphicsTestResolution
import org.scalatest.flatspec.AnyFlatSpec

abstract class TessellationJointGeometryParentChecks extends AnyFlatSpec {
  import TessellationJointReaderTestSupport._

  protected val config = GpuConfig(lanes = 16, warps = 8)
  protected val p = VertexShaderStageConfig()
  protected val f = FragmentShaderConfig()
  protected val shared = SharedCuShaderConfig(2)
  protected val graphics = GraphicsConfig(
    screenWidth = math.min(4, GraphicsTestResolution.width),
    screenHeight = math.min(4, GraphicsTestResolution.height)
  )
  protected val engines = 1
  protected val writeCredits = 26
  protected val pagedCapacity = 8

  protected def ordinaryTransactions(domain: Int): Int = {
    val renderIds = domain match {
      case 0 =>
        TessellationSystemIsolineImagePipeline.memoryTransactions(
          engines,
          writeCredits,
          pagedCapacity,
          tessellationUniforms = true,
          nativeGeometryAvailable = false
        )
      case 1 =>
        TessellationSystemTriangleImagePipeline.memoryTransactions(
          engines,
          writeCredits,
          pagedCapacity,
          tessellationUniforms = true,
          nativeGeometryAvailable = false
        )
      case 2 =>
        TessellationSystemQuadImagePipeline.memoryTransactions(
          engines,
          writeCredits,
          pagedCapacity,
          tessellationUniforms = true,
          nativeGeometryAvailable = false
        )
    }
    TessellationInputMemoryFabric.transactions(renderIds)
  }

  protected def checkClosed(d: Module, io: TessellationJointGeometryDomainIO, domain: Int): Unit = {
    zero(io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
    io.primitiveDone.foreach(_.ready.poke(true.B)); io.out.foreach(_.ready.poke(true.B))
    io.tileDone.foreach(_.ready.poke(true.B)); io.reply.ready.poke(true.B)
    io.geometryInvalidateDone.ready.poke(true.B)
    val memory = new Memory(d.clock, io.memoryRequest, io.memoryResponse)
    def quiet(): Unit = {
      io.memoryRequest.valid.expect(false.B); io.shared.launch.valid.expect(false.B)
      io.shared.completion.valid.expect(false.B); io.shared.trap.valid.expect(false.B)
      io.shared.invalidateDone.valid.expect(false.B)
      io.shared.geometryEvent.foreach(_.foreach(_.valid.expect(false.B)))
      io.shared.tessellation.foreach(_.foreach { t =>
        t.request.valid.expect(false.B); t.park.valid.expect(false.B)
      })
      io.shared.evaluationPatch.foreach(_.foreach(_.request.valid.expect(false.B)))
      io.shared.texture.foreach { t =>
        t.scalar.valid.expect(false.B); t.vector.valid.expect(false.B)
        t.scalarWriteback.valid.expect(false.B); t.vectorWriteback.valid.expect(false.B)
      }
      io.reply.valid.expect(false.B); io.primitiveDone.foreach(_.valid.expect(false.B))
      io.out.foreach(_.valid.expect(false.B)); io.tileDone.foreach(_.valid.expect(false.B))
      io.geometryInvalidateDone.valid.expect(false.B)
    }
    request(io.submit.bits, domain); io.submit.valid.poke(true.B)
    memory.until("default-closed geometry submit")(io.submit.ready.peek().litToBoolean)
    memory.tick(); io.submit.valid.poke(false.B); zero(io.submit.bits)
    memory.until("default-closed geometry report")(io.completion.valid.peek().litToBoolean)
    val report = io.completion.bits
    report.base.status.expect(6.U); report.base.readBytes.expect(0.U); report.base.commandId.expect(5.U)
    report.geometryReportValid.expect(false.B); report.actualGsPublished.expect(0.U)
    report.received.expect(0.U); report.dropped.expect(0.U); report.admitted.expect(0.U)
    report.geometryTasks.expect(0.U); report.nextGsSequenceExclusive.expect(0.U)
    report.nextGsSequence.expect(0.U); report.nextBinSequence.expect(0.U)
    memory.until("default-closed geometry physical drain")(io.drained.peek().litToBoolean)
    val held = report.peek().litValue
    for (_ <- 0 until 3) {
      memory.tick(); quiet(); io.completion.valid.expect(true.B); assert(report.peek().litValue == held)
    }
    assert(memory.reads.isEmpty && memory.empty)
    io.completion.ready.poke(true.B); memory.tick(); io.completion.ready.poke(false.B)
    io.busy.expect(false.B); io.drained.expect(true.B); io.submit.ready.expect(true.B)
  }

}
