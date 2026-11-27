package opengpu.system

import opengpu.testutil.GpuSim.simulate

class TessellationJointTriangleGeometryParentSpec extends TessellationJointGeometryParentChecks {
  it should "remain unavailable and traffic-free for a default-closed triangle geometry parent" in {
    simulate(
      new TessellationSystemTriangleJointGeometryVertexImage(
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
      assert(d.memoryTransactions > ordinaryTransactions(1))
      checkClosed(d, d.io, 1)
    }
  }
}
