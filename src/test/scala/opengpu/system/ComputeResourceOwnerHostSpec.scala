package opengpu.system

import opengpu.config.GpuConfig
import opengpu.graphics.GraphicsConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class ComputeResourceOwnerHostSpec extends AnyFlatSpec with GpuHostTestSupport {
  it should "report the executing compute pool's indirect workgroup limit when legacy graphics uses a different profile" in {
    val compute=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2)
    val graphics=GpuConfig(lanes=16,warps=8)
    simulate(new GpuHostSystemAxi(GraphicsConfig(screenWidth=16,screenHeight=16),compute,
      graphicsGpuConfig=Some(graphics),numComputeUnits=1,enableIndirectCompute=true,
      instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1)) { d=>
      initialize(d)
      assert(axiRead(d,0)==0x47550002L,"complete host must advertise discoverable ABI v2")
      assert(axiRead(d,0x360)==0x47435201L,"ABI v2 must provide GCR1")
      assert(axiRead(d,0x368)==15,"GCR1 must expose implemented word atomics")
      val localItems=axiRead(d,0x35c)
      assert(localItems==8,s"GCI1 must report compute4x2 capacity8, not graphics16x8 capacity128: got=$localItems")
      info("COMPUTE_RESOURCE_OWNER actualHost=true compute4x2=true legacyGraphics16x8=true indirectLocalItems8=true")
    }
  }
}
