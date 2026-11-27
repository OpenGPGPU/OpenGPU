package opengpu.system

import opengpu.testutil.GpuSim.simulate

class TessellationJointQuadGeometryParentSpec extends TessellationJointGeometryParentChecks {
  it should "remain unavailable and traffic-free for a default-closed quad geometry parent" in {
    simulate(
      new TessellationSystemQuadJointGeometryVertexImage(
        config,
        p,
        f,
        shared,
        graphics,
        engines = engines,
        pageBytes = 128,
        writeCredits = writeCredits,
        tileSlots = 1,
        pagedCapacity = pagedCapacity,
        tessellationUniforms = true,
        primitiveIdInput = true
      )
    ) { d =>
      assert(d.memoryTransactions > ordinaryTransactions(2))
      checkClosed(d, d.io, 2)
    }
  }
}
