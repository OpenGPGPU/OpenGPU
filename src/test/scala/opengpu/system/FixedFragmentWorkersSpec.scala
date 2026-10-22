package opengpu.system

/** Bounded execution/ownership controls, independent of the opt-in 128-thread
  * shader-bound scaling qualification. Each image is only 16x16.
  */
class FixedFragmentWorkersSpec extends WideSharedGraphicsChecks(32,Seq(1,2,4),
  side=16,aluIterations=2,lanes=4,warps=2,runtimeShader=true,fragmentWorkerCount=4)
class NonPowerFragmentWorkersSpec extends WideSharedGraphicsChecks(32,Seq(2),
  side=16,aluIterations=2,lanes=4,warps=2,runtimeShader=true,fragmentWorkerCount=3)
class UnderfilledFragmentWorkersSpec extends WideSharedGraphicsChecks(32,Seq(4),
  side=16,aluIterations=2,lanes=4,warps=2,runtimeShader=true,fragmentWorkerCount=1)
