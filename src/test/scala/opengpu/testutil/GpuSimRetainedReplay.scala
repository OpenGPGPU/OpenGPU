package svsim

/** Use the pinned simulator's original package-scoped constructor for a model
  * already built by it. This adapter performs no compilation or port remapping.
  */
object GpuSimRetainedReplay {
  def run[T](directory:String,info:ModuleInfo)(body:Simulation.Controller=>T):T =
    new Simulation("simulation",Simulation.Settings(None,Seq.empty,Map.empty),directory,info).run(body)
}
