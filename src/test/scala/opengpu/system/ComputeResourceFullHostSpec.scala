package opengpu.system

import opengpu.config.GpuConfig
import opengpu.graphics.GraphicsConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class ComputeResourceFullHostSpec extends AnyFlatSpec with GpuHostTestSupport {
  it should "bind the four-CU compute resource record to the actual 256bit memory host" in {
    simulate(new GpuHostSystemAxi(GraphicsConfig(screenWidth=16,screenHeight=16),GpuConfig(),
      numComputeUnits=4,memoryAxiDataBytes=32,enableIndirectCompute=true,
      instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1)) { d=>
      initialize(d)
      val expected=Vector[BigInt](BigInt("47435201",16),16,7,4,16,8,128,128,BigInt("ffffffff",16),
        16384,16,8,BigInt("10000000",16),32,65536,4)
      for(i<-expected.indices)assert(axiRead(d,0x360+4*i)==expected(i),s"full host resource word$i differs")
      assert(axiRead(d,0x35c)==128)
      info("COMPUTE_RESOURCE_FULL_HOST actualCUs4=true lanes16=true warps8=true AXIbits256=true L2bytes65536=true perCULocal128=true cachesReducedForMMIOTest=true independent16words=true")
    }
  }
}
