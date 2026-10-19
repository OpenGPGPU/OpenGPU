package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class BankedRenderTerminationSpec extends AnyFlatSpec {
  import TilePassTestData._
  private case class Request(slot: Int,tid: Int,address: Long,write: Boolean,due: Int)
  for(double <- Seq(false,true);action <- Seq("end","cancel","fault"))
  it should s"drain raster and sample producers through $action then reuse the pass double=$double" in {
    val cfg=GraphicsConfig(screenWidth=32,screenHeight=32,tileSize=16,tileAttachments=true)
    simulate(new RenderPipeline(cfg,GpuConfig(lanes=4,warps=2),persistentAttachments=true,
      doubleBufferedAttachments=double,tileStoreCredits=2,bankedAttachments=true)) { d=>
      val p=Pass(mode=1,width=8,height=8,originX=8,originY=8,pitch=512,tileLog2=3,
        colorLoad=if(action=="end")1 else 0,depthLoad=if(action=="end")1 else 0)
      val pass=d.io.tilePass.get
      d.io.draw.valid.poke(false.B)
      d.io.colorFormat.poke(0.U);d.io.colorBase.poke(p.colorBase.U);d.io.depthBase.poke(p.depthBase.U)
      d.io.stride.poke(p.pitch.U);d.io.sampleMode.poke(p.mode.U);d.io.screenW.poke(32.U);d.io.screenH.poke(32.U)
      d.io.depthTestEnable.poke(false.B);d.io.depthWriteEnable.poke(false.B);d.io.depthFunc.poke(6.U)
      d.io.blendCfgEnable.poke(false.B);d.io.blendSrcFactor.poke(1.U);d.io.blendDstFactor.poke(0.U)
      d.io.blendEquation.poke(0.U);d.io.stencilTestEnable.poke(false.B);d.io.stencilFunc.poke(6.U)
      d.io.stencilRef.poke(0.U);d.io.stencilReadMask.poke(255.U);d.io.stencilWriteMask.poke(255.U)
      d.io.stencilFailOp.poke(0.U);d.io.stencilZFailOp.poke(0.U);d.io.stencilZPassOp.poke(0.U)
      d.io.cullMode.poke(0.U);d.io.texEnable.poke(false.B);d.io.texFormat.poke(0.U)
      d.io.texBase.poke(0.U);d.io.texWidth.poke(1.U);d.io.texHeight.poke(1.U)
      d.io.texWrapClamp.poke(false.B);d.io.texMaxLevel.poke(0.U)
      d.io.texMem.req.ready.poke(true.B);d.io.texMem.resp.valid.poke(false.B)
      pass.begin.valid.poke(false.B);pass.end.valid.poke(false.B);pass.end.bits.poke(true.B)
      pass.cancel.poke(false.B);pass.memoryFault.poke(false.B);pass.completion.ready.poke(false.B)
      pass.clear.valid.poke(false.B);pass.clear.bits.poke(0.U.asTypeOf(pass.clear.bits));pass.clearDone.ready.poke(true.B)
      d.io.mem.req.ready.poke(false.B);d.io.mem.resp.valid.poke(false.B)
      d.io.mem.resp.bits.poke(0.U.asTypeOf(d.io.mem.resp.bits))
      d.io.tileMemoryRequest.foreach(_.foreach(_.ready.poke(false.B)))
      d.io.tileMemoryResponse.foreach(_.foreach(r=>{r.valid.poke(false.B);r.bits.poke(0.U.asTypeOf(r.bits))}))
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      var cycle=0;var reads=0;var writes=0;var accepted=0;var committed=0;var ends=0;var faulted=false
      val queue=mutable.ArrayBuffer.empty[Request]
      val returning=Array.fill[Option[Request]](if(double)2 else 1)(None)
      def tick(): Unit = {
        pass.memoryFault.poke(false.B)
        for(s <- returning.indices) {
          if(returning(s).isEmpty) {
            val i=queue.lastIndexWhere(r=>r.slot==s && r.due<=cycle)
            if(i>=0)returning(s)=Some(queue.remove(i))
          }
          val response=returning(s)
          val fault=response.exists(r=>action=="fault" && !faulted && !r.write)
          val ready=cycle%5!=2
          if(double) {
            val req=d.io.tileMemoryRequest.get(s);val out=d.io.tileMemoryResponse.get(s)
            req.ready.poke(ready.B);out.valid.poke(response.nonEmpty.B)
            response.foreach { r=>
              out.bits.transactionId.poke(r.tid.U);out.bits.fault.poke(fault.B)
              out.bits.readData.poke(BigInt("00ffffff"*16,16).U)
            }
            if(response.nonEmpty && out.ready.peek().litToBoolean){returning(s)=None;if(fault)faulted=true}
            if(req.valid.peek().litToBoolean && ready) {
              val b=req.bits;val write=b.isWrite.peek().litToBoolean
              if(write)writes+=1 else reads+=1
              queue+=Request(s,b.transactionId.peek().litValue.toInt,b.address.peek().litValue.toLong,write,cycle+17)
            }
            d.io.mem.req.valid.expect(false.B)
          }else {
            d.io.mem.req.ready.poke(ready.B);d.io.mem.resp.valid.poke(response.nonEmpty.B)
            response.foreach { r=>
              d.io.mem.resp.bits.addr.poke(r.address.U);d.io.mem.resp.bits.write.poke(r.write.B)
              d.io.mem.resp.bits.data.poke(0x00ffffff.U)
            }
            pass.memoryFault.poke(fault.B)
            if(response.nonEmpty && d.io.mem.resp.ready.peek().litToBoolean){returning(s)=None;if(fault)faulted=true}
            if(d.io.mem.req.valid.peek().litToBoolean && ready) {
              val b=d.io.mem.req.bits;val write=b.write.peek().litToBoolean
              if(write)writes+=1 else reads+=1
              queue+=Request(0,0,b.addr.peek().litValue.toLong,write,cycle+17)
            }
          }
        }
        accepted+=d.io.tileAcceptedSamples.get.peek().litValue.toInt
        committed+=d.io.tileCommittedSamples.get.peek().litValue.toInt
        if(pass.end.valid.peek().litToBoolean && pass.end.ready.peek().litToBoolean)ends+=1
        d.clock.step();cycle+=1
      }
      def until(condition: => Boolean): Unit = {
        val start=cycle
        while(!condition && cycle-start<30000)tick()
        assert(condition,s"stalled $action double=$double cycle=$cycle accepted=$accepted reads=$reads writes=$writes")
      }
      pokeDescriptor(pass.begin.bits,p);pass.begin.valid.poke(true.B)
      until(pass.begin.ready.peek().litToBoolean);tick();pass.begin.valid.poke(false.B)
      val draw=d.io.draw.bits.asInstanceOf[SceneTriangle];draw.poke(0.U.asTypeOf(draw))
      draw.stateOverride.poke(true.B);draw.depthWriteEnable.poke(true.B)
      for((v,i) <- Seq((-1,-1),(1,-1),(-1,1)).zipWithIndex) {
        draw.clip(i).x.poke((v._1*65536).S);draw.clip(i).y.poke((v._2*65536).S);draw.clip(i).w.poke(65536.S)
        draw.color(i).r.poke(123.U);draw.color(i).g.poke(45.U);draw.color(i).b.poke(67.U)
        draw.alpha(i).poke(255.U);draw.depth(i).poke((100+i*50).S)
      }
      d.io.draw.valid.poke(true.B);until(d.io.draw.ready.peek().litToBoolean);tick();d.io.draw.valid.poke(false.B)
      if(action=="end")pass.end.valid.poke(true.B)
      else {
        until(reads>0)
        if(action=="cancel"){pass.cancel.poke(true.B);tick();pass.cancel.poke(false.B)}
      }
      until(pass.completion.valid.peek().litToBoolean && d.io.done.peek().litToBoolean)
      if(action=="end"){assert(ends==1 && writes>0);pass.end.valid.poke(false.B)}
      else assert(writes==0)
      assert(accepted>0)
      pass.completion.bits.status.expect((if(action=="end")TilePassStatus.success else if(action=="cancel")TilePassStatus.cancelled else TilePassStatus.memoryFault).U)
      assert(action!="fault" || faulted)
      val held=pass.completion.bits.peek().litValue
      for(_ <- 0 until 5){tick();assert(pass.completion.bits.peek().litValue==held);pass.completion.valid.expect(true.B)}
      pass.completion.ready.poke(true.B);tick();pass.completion.ready.poke(false.B)
      assert(queue.isEmpty && returning.forall(_.isEmpty))
      val next=p.copy(id=31,colorLoad=1,depthLoad=1,colorStore=false,depthStore=false)
      val committedBefore=committed
      pokeDescriptor(pass.begin.bits,next);pass.begin.valid.poke(true.B)
      until(pass.begin.ready.peek().litToBoolean);tick();pass.begin.valid.poke(false.B)
      d.io.draw.valid.poke(true.B);until(d.io.draw.ready.peek().litToBoolean);tick();d.io.draw.valid.poke(false.B)
      pass.end.valid.poke(true.B);val before=ends;until(ends>before);pass.end.valid.poke(false.B)
      until(pass.completion.valid.peek().litToBoolean)
      pass.completion.bits.identity.pass.expect(31.U);pass.completion.bits.status.expect(TilePassStatus.success.U)
      assert(committed>committedBefore,"a new pass inherited the previous producer abort")
      pass.completion.ready.poke(true.B);tick()
    }
  }
}
