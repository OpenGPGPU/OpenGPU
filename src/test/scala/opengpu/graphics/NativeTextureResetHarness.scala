package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse,VectorTlbFlush}

/** Exercise the production three-client pool and the cluster's own retirement
  * ordering with the same memory/VM oracle. No cancelled reply is fabricated
  * by this harness.
  */
private[graphics] class NativeTextureResetHarness(pool: Boolean=true, credits: Int=4, clients: Int=3) extends Module {
  val clusters=if(pool)2 else 1
  private val config=GpuConfig()
  val io=IO(new Bundle {
    val client=Vec(clients,Flipped(new NativeRgba8TextureClientIO))
    val cuMask=Input(Vec(clients,UInt(4.W)))
    val memoryRequest=Vec(clusters,Decoupled(new ComputeMemoryRequest(config,64,16)))
    val memoryResponse=Vec(clusters,Flipped(Decoupled(new ComputeMemoryResponse(64,16))))
    val pageWalk=Vec(clusters,Decoupled(new ComputeMemoryRequest(config,64,4)))
    val pageWalkResponse=Vec(clusters,Flipped(Decoupled(new ComputeMemoryResponse(64,4))))
    val satp=Input(UInt(32.W));val flush=Flipped(Valid(new VectorTlbFlush(config)))
    val invalidate=Input(Bool());val cancel=Input(Bool());val busy=Output(Bool())
  })
  if(pool){
    val p=Module(new NativeRgba8TexturePool(config,4,clients,maxOutstandingPerClient=credits))
    io <> p.io
  }else{
    val c=Module(new NativeRgba8TextureCluster(config,clients,maxOutstandingPerClient=credits))
    io.client <> c.io.client
    io.memoryRequest(0) <> c.io.memoryRequest;c.io.memoryResponse <> io.memoryResponse(0)
    io.pageWalk(0) <> c.io.pageWalk;c.io.pageWalkResponse <> io.pageWalkResponse(0)
    c.io.satp:=io.satp;c.io.flush:=io.flush;c.io.invalidate:=io.invalidate;c.io.cancel:=io.cancel
    io.busy:=c.io.busy
  }
}
