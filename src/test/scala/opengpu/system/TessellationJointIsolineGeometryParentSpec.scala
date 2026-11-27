package opengpu.system

import opengpu.testutil.GpuSim.simulate

class TessellationJointIsolineGeometryParentSpec extends TessellationJointGeometryParentChecks {
  it should "remain unavailable and traffic-free for a default-closed isoline geometry parent" in {
    simulate(
      new TessellationSystemIsolineJointGeometryVertexImage(
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
      assert(d.memoryTransactions > ordinaryTransactions(0))
      checkClosed(d, d.io, 0)
    }
  }
}
