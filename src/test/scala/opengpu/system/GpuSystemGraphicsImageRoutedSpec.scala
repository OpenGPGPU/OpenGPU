package opengpu.system

import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.graphics.{GraphicsStage,TextureImageFormat,SharedShaderTaskRouter}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class GraphicsImageRouterSystemHarness(atomic: Boolean = false) extends Module {
  private val config=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2)
  private val gpu=Module(new GpuSystem(config,numComputeUnits=1,commandIdWidth=4,transactionsPerCu=8,
    enableGraphicsKernels=true,imageAvailable=true,graphicsImageAvailable=true,imageAtomicAvailable=atomic,
    instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1))
  private val router=Module(new SharedShaderTaskRouter(config,1,2,taskIdWidth=4,imageAvailable=true))
  val io=IO(new Bundle {
    val system=chiselTypeOf(gpu.io)
    val clients=Vec(2,new SharedCuGraphicsPort(config,4,1,imageAvailable=true))
    val blockLaunch=Input(Bool())
  })
  io.system<>gpu.io
  router.io.clients<>io.clients
  gpu.io.graphicsKernels.get<>router.io.pool
  gpu.io.graphicsKernels.get.launch.valid:=router.io.pool.launch.valid && !io.blockLaunch
  router.io.pool.launch.ready:=gpu.io.graphicsKernels.get.launch.ready && !io.blockLaunch
  gpu.io.graphicsDrained:=router.io.drained
}

/** Local full-system qualification for the opt-in graphics image handoff. */
class GpuSystemGraphicsImageRoutedSpec extends AnyFlatSpec {
  private def zero(data: Data): Unit = data match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s.poke(0.S)
    case _ => ()
  }
  private def image(fn:Int,pair:Int=0,vs1:Int=0,vs2:Int=0,rd:Int=0):BigInt =
    (BigInt(fn)<<26)|(BigInt(1)<<25)|(BigInt(vs2)<<20)|(BigInt(vs1)<<15)|(BigInt(pair)<<12)|(BigInt(rd)<<7)|0x2b
  private def vector(fn:Int,form:Int,rd:Int,vs1:Int,vs2:Int=0):BigInt =
    (BigInt(fn)<<26)|(BigInt(1)<<25)|(BigInt(vs2)<<20)|(BigInt(vs1)<<15)|(BigInt(form)<<12)|(BigInt(rd)<<7)|0x57

  it should "retain queued graphics image tasks through the router and actual shared CU" in {
    val config=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2)
    simulate(new GraphicsImageRouterSystemHarness){d=>
      zero(d.io);d.io.blockLaunch.poke(true.B);d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
      d.io.system.graphicsDrained.poke(true.B);d.io.system.graphicsShaderL1Invalidate.ready.poke(true.B)
      d.io.system.trap.foreach(_.ready.poke(true.B))
      val graphics=d.io.clients(0);graphics.trap.ready.poke(true.B)
      val memory=mutable.Map.empty[BigInt,Int]
      def put(a:BigInt,v:BigInt):Unit=for(i<-0 until 4)memory(a+i)=((v>>(8*i))&255).toInt
      def get(a:BigInt,n:Int):BigInt=(0 until n).foldLeft(BigInt(0))((v,i)=>v|(BigInt(memory.getOrElse(a+i,0xa5))<<(8*i)))
      val format=TextureImageFormat.id("RGBA32UI");val control=(format<<6)|40
      val desc=Array.fill[BigInt](64)(0)
      for((i,v)<-Seq[(Int,BigInt)](0->BigInt("47495501",16),1->256,2->1,3->7,4->11,5->13,
        7->1,8->1,9->(1<<GraphicsStage.fragment),10->1,11->1,12->format,13->format,14->1,
        17->15,18->1,22->0x9000,23->0xa000,25->0x903f,26->4,27->1,28->1,29->1,30->64,37->16)) desc(i)=v
      desc.zipWithIndex.foreach{case(v,i)=>put(0x8000+i*4,v)}
      for(i<-0 until 4096)memory(BigInt(0x9000+i))=0xa5
      val program=Vector(BigInt("0104f057",16),vector(23,3,2,0),
        BigInt(((control+0x800)& ~0xfff)|(10<<7)|0x37),
        BigInt(((control&0xfff).toLong<<20)|(10<<15)|(10<<7)|0x13),vector(23,4,3,10),
        image(8,vs1=2,vs2=3),image(8,pair=1,vs1=2,vs2=2))++
        (0 until 4).map(c=>vector(0,3,4+c,c+1,1))++
        Vector(image(8,pair=2,vs1=4,vs2=5),image(8,pair=3,vs1=6,vs2=7),image(10,vs1=1,vs2=2),BigInt("30500073",16))
      program.zipWithIndex.foreach{case(v,i)=>put(0x1000+i*4,v)}
      case class Reply(id:BigInt,address:BigInt,data:BigInt,mask:BigInt,write:Boolean,due:Int)
      val pending=mutable.Queue.empty[Reply];var cycle=0;var writes=0;var allowTrap=false;var traps=0
      def tick():Unit={
        d.io.system.memoryRequest.ready.poke((cycle%5!=0).B)
        val reply=pending.headOption.filter(_.due<=cycle)
        d.io.system.memoryResponse.valid.poke(reply.nonEmpty.B)
        reply.foreach{r=>d.io.system.memoryResponse.bits.transactionId.poke(r.id.U);d.io.system.memoryResponse.bits.readData.poke(r.data.U)}
        if(reply.nonEmpty && d.io.system.memoryResponse.ready.peek().litToBoolean){val r=pending.dequeue()
          if(r.write)for(i<-0 until 64 if r.mask.testBit(i))memory(r.address+i)=((r.data>>(8*i))&255).toInt}
        if(d.io.system.memoryRequest.valid.peek().litToBoolean && d.io.system.memoryRequest.ready.peek().litToBoolean){
          val q=d.io.system.memoryRequest.bits;val a=q.address.peek().litValue;val write=q.isWrite.peek().litToBoolean
          if(write)writes+=1
          pending.enqueue(Reply(q.transactionId.peek().litValue,a,if(write)q.writeData.peek().litValue else get(a,64),
            q.byteMask.peek().litValue,write,cycle+(if(write)11 else 3)))}
        d.io.system.trap.foreach(_.valid.expect(false.B)); if(graphics.trap.valid.peek().litToBoolean){assert(allowTrap);graphics.trap.bits.taskId.expect(4.U);traps+=1}
        d.clock.step();cycle+=1
      }
      def until(label:String)(condition: => Boolean):Unit={var n=0;while(!condition && n<60000){tick();n+=1};assert(condition,s"$label stalled at $cycle")}
      val task=graphics.imageTask.get;task.enabled.poke(true.B);task.stage.poke(GraphicsStage.fragment.U)
      task.table.version.poke(1.U);task.table.base.poke(0x8000.U);task.table.limit.poke(0x8100.U)
      task.table.identity.context.poke(7.U);task.table.identity.pass.poke(11.U);task.table.identity.generation.poke(13.U)
      graphics.launch.bits.commandId.poke(3.U);graphics.launch.bits.launch.kernelPc.poke(0x1000.U)
      for(i<-0 until 3){graphics.launch.bits.launch.gridSize(i).poke(1.U);graphics.launch.bits.launch.localSize(i).poke((if(i==0)4 else 1).U)}
      d.io.system.imageLease.get.expect(false.B)
      graphics.launch.valid.poke(true.B);until("graphics admission")(graphics.launch.ready.peek().litToBoolean);tick()
      graphics.launch.valid.poke(false.B);task.enabled.poke(false.B);task.stage.poke(GraphicsStage.compute.U);task.table.base.poke(0xdead0000L.U)
      d.io.system.imageLease.get.expect(false.B)
      for(_<-0 until 5){d.io.system.vmContextIdle.expect(false.B);tick()}
      d.io.blockLaunch.poke(false.B)
      until("graphics completion")(graphics.completion.valid.peek().litToBoolean)
      graphics.completion.bits.commandId.expect(3.U);graphics.completion.bits.success.expect(true.B)
      val held=graphics.completion.bits.peek().litValue
      for(_<-0 until 8){tick();assert(graphics.completion.bits.peek().litValue==held);d.io.system.imageLease.get.expect(true.B)}
      graphics.completion.ready.poke(true.B);tick();graphics.completion.ready.poke(false.B)
      d.io.system.imageLease.get.expect(false.B)
      until("memory drain")(pending.isEmpty && d.io.system.vmContextIdle.peek().litToBoolean)
      assert(writes>0,"fragment image stores must reach memory")
      for(i<-0 until 4096){val off=i-63
        val expected=if(off>=0 && off<64){val lane=off/16;val component=(off%16)/4;val byte=off%4
          ((BigInt(lane+component+1)>>(byte*8))&255).toInt}else 0xa5
        assert(memory(BigInt(0x9000+i))==expected,s"image byte $i")}
      val oldWrites=writes;val oldImage=(0 until 4096).map(i=>memory(BigInt(0x9000+i))).toVector
      task.enabled.poke(true.B);task.stage.poke(GraphicsStage.vertex.U);task.table.base.poke(0x8000.U)
      graphics.launch.bits.commandId.poke(4.U);graphics.launch.valid.poke(true.B)
      until("denied admission")(graphics.launch.ready.peek().litToBoolean);tick();graphics.launch.valid.poke(false.B)
      allowTrap=true
      until("denied CU lease")(d.io.system.imageLease.get.peek().litToBoolean)
      until("denied completion")(graphics.completion.valid.peek().litToBoolean)
      graphics.completion.bits.commandId.expect(4.U);graphics.completion.bits.success.expect(false.B)
      assert(traps>0,"a forbidden graphics stage must trap")
      graphics.completion.ready.poke(true.B);tick();graphics.completion.ready.poke(false.B)
      d.io.system.imageLease.get.expect(false.B)
      until("denied drain")(pending.isEmpty && d.io.system.vmContextIdle.peek().litToBoolean)
      assert(writes==oldWrites && oldImage==(0 until 4096).map(i=>memory(BigInt(0x9000+i))).toVector,
        "a denied stage must not modify image data")
    }
  }

  for ((compare, translated) <- Seq((false, false), (true, false), (false, true), (true, true))) {
  it should s"route fragment image atomics with helper suppression and stage denial compare=$compare translated=$translated" in {
    simulate(new GraphicsImageRouterSystemHarness(atomic = true)) { d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      d.io.system.graphicsDrained.poke(true.B); d.io.system.graphicsShaderL1Invalidate.ready.poke(true.B)
      d.io.system.trap.foreach(_.ready.poke(true.B))
      val graphics = d.io.clients(0); graphics.trap.ready.poke(true.B)
      val memory = mutable.Map.empty[BigInt, Int]
      def put(a: BigInt, v: BigInt): Unit = for (i <- 0 until 4) memory(a+i) = ((v >> (8*i)) & 255).toInt
      def get(a: BigInt, n: Int): BigInt = (0 until n).map(i => BigInt(memory.getOrElse(a+i, 0xa5)) << (8*i)).sum
      val format = TextureImageFormat.id("R32UI"); val control = (format << 6) | 40
      val desc = Array.fill[BigInt](64)(0)
      for ((i,v) <- Seq[(Int,BigInt)](0->BigInt("47495501",16),1->256,2->1,3->7,4->11,5->13,
        7->1,8->1,9->(1 << GraphicsStage.fragment),10->1,11->1,12->format,13->format,14->1,
        17->15,18->1,22->0x9000,23->0xa000,25->0x9048,26->1,27->1,28->1,29->1,30->64,37->4)) desc(i)=v
      val descriptorAddress = if(translated) 0xa000 else 0x8000
      val atomicAddress = if(translated) 0xb048 else 0x9048
      desc.zipWithIndex.foreach { case (v,i) => put(descriptorAddress+i*4,v) }; put(atomicAddress,10)
      if(translated) {
        put(0x30000,(BigInt(0x31)<<10)|1); put(0x31020,(BigInt(0xa)<<10)|0x243)
        put(0x31024,(BigInt(0xb)<<10)|0x2c7); d.io.system.vectorSatp.poke("h80000030".U)
      }
      val program = Vector(BigInt("0104f057",16),vector(23,3,2,0),
        BigInt(((control+0x800)& ~0xfff)|(10<<7)|0x37),
        BigInt(((control&0xfff).toLong<<20)|(10<<15)|(10<<7)|0x13),vector(23,4,3,10),
        image(8,vs1=2,vs2=3),image(8,pair=1,vs1=2,vs2=2),vector(23,3,4,3),
        vector(23,3,5,10),image(8,pair=2,vs1=4,vs2=if(compare)5 else 2),
        image(23,pair=if(compare)7 else 1,vs1=2,vs2=2,rd=10),BigInt("30500073",16))
      program.zipWithIndex.foreach { case (v,i) => put(0x1000+i*4,v) }
      case class Reply(id:BigInt,address:BigInt,data:BigInt,mask:BigInt,write:Boolean,due:Int)
      val pending=mutable.Queue.empty[Reply]; var cycle=0; var writes=0; var walks=0; var traps=0; var allowTrap=false; var expectPageFault=false
      def tick(): Unit = {
        d.io.system.memoryRequest.ready.poke((cycle%5 != 0).B)
        val reply=pending.headOption.filter(_.due<=cycle); d.io.system.memoryResponse.valid.poke(reply.nonEmpty.B)
        reply.foreach(r => { d.io.system.memoryResponse.bits.transactionId.poke(r.id.U); d.io.system.memoryResponse.bits.readData.poke(r.data.U) })
        if (reply.nonEmpty && d.io.system.memoryResponse.ready.peek().litToBoolean) {
          val r=pending.dequeue(); if (r.write) for(i <- 0 until 64 if r.mask.testBit(i)) memory(r.address+i)=((r.data>>(8*i))&255).toInt
        }
        if (d.io.system.memoryRequest.valid.peek().litToBoolean && d.io.system.memoryRequest.ready.peek().litToBoolean) {
          val q=d.io.system.memoryRequest.bits; val a=q.address.peek().litValue; val write=q.isWrite.peek().litToBoolean
          if(write) { assert(a==(atomicAddress & ~63)); writes+=1 }
          else if(a==0x30000 || a==0x31000) walks+=1
          pending.enqueue(Reply(q.transactionId.peek().litValue,a,if(write) q.writeData.peek().litValue else get(a,64),q.byteMask.peek().litValue,write,cycle+(if(write)11 else 3)))
        }
        d.io.system.trap.foreach(_.valid.expect(false.B))
        if(graphics.trap.valid.peek().litToBoolean) {
          assert(allowTrap)
          if(expectPageFault) {
            graphics.trap.bits.taskId.expect(5.U); graphics.trap.bits.event.pc.expect(0x1028.U)
            graphics.trap.bits.event.cause.expect(15.U); graphics.trap.bits.event.tval.expect(0x9048.U)
          }
          traps+=1
        }
        d.clock.step(); cycle+=1
      }
      def until(label:String)(condition: => Boolean): Unit = { var n=0; while(!condition && n<60000){tick();n+=1}; assert(condition,s"$label stalled at $cycle") }
      val task=graphics.imageTask.get; task.enabled.poke(true.B); task.stage.poke(GraphicsStage.fragment.U)
      task.table.version.poke(1.U); task.table.base.poke(0x8000.U); task.table.limit.poke(0x8100.U)
      task.table.identity.context.poke(7.U); task.table.identity.pass.poke(11.U); task.table.identity.generation.poke(13.U)
      graphics.launch.bits.commandId.poke(3.U); graphics.launch.bits.launch.kernelPc.poke(0x1000.U)
      graphics.launch.bits.launch.invocations.helpers.poke(10.U)
      for(i<-0 until 3) { graphics.launch.bits.launch.gridSize(i).poke(1.U); graphics.launch.bits.launch.localSize(i).poke((if(i==0)4 else 1).U) }
      graphics.launch.valid.poke(true.B); until("fragment admission")(graphics.launch.ready.peek().litToBoolean); tick(); graphics.launch.valid.poke(false.B)
      task.enabled.poke(false.B); task.table.base.poke(0xdead0000L.U)
      until("fragment completion")(graphics.completion.valid.peek().litToBoolean)
      graphics.completion.bits.commandId.expect(3.U); graphics.completion.bits.success.expect(true.B)
      for(_<-0 until 4) { d.io.system.imageLease.get.expect(true.B); tick() }
      graphics.completion.ready.poke(true.B); tick(); graphics.completion.ready.poke(false.B)
      until("fragment drain")(pending.isEmpty && d.io.system.vmContextIdle.peek().litToBoolean)
      assert(writes==2 && get(atomicAddress,4)==(if(compare)3 else 16))
      assert(if(translated) walks>0 else walks==0)
      for(i<-0 until 64 if i<8 || i>=12) assert(memory.getOrElse(BigInt((atomicAddress & ~63)+i),0xa5)==0xa5)
      task.enabled.poke(true.B); task.stage.poke(GraphicsStage.vertex.U); task.table.base.poke(0x8000.U)
      graphics.launch.bits.commandId.poke(4.U); graphics.launch.valid.poke(true.B)
      until("denied admission")(graphics.launch.ready.peek().litToBoolean); tick(); graphics.launch.valid.poke(false.B); allowTrap=true
      until("denied completion")(graphics.completion.valid.peek().litToBoolean)
      graphics.completion.bits.commandId.expect(4.U); graphics.completion.bits.success.expect(false.B); assert(traps>0)
      graphics.completion.ready.poke(true.B); tick(); graphics.completion.ready.poke(false.B)
      until("denied drain")(pending.isEmpty && d.io.system.vmContextIdle.peek().litToBoolean)
      assert(writes==2 && get(atomicAddress,4)==(if(compare)3 else 16))
      if(translated) {
        // New roots avoid relying on invalidation of previously cached PTEs.
        put(0x40000,(BigInt(0x41)<<10)|1); put(0x41020,(BigInt(0xa)<<10)|0x243)
        put(0x41024,(BigInt(0xb)<<10)|0x243)
        put(0x50000,(BigInt(0x51)<<10)|1); put(0x51020,(BigInt(0xa)<<10)|0x243)
        put(0x51024,(BigInt(0xb)<<10)|0x2c7)
        d.io.system.vectorSatp.poke("h80000040".U); task.stage.poke(GraphicsStage.fragment.U)
        val trapsBefore=traps; expectPageFault=true
        graphics.launch.bits.commandId.poke(5.U); graphics.launch.valid.poke(true.B)
        until("page-denied admission")(graphics.launch.ready.peek().litToBoolean); tick(); graphics.launch.valid.poke(false.B)
        until("page-denied completion")(graphics.completion.valid.peek().litToBoolean)
        graphics.completion.bits.commandId.expect(5.U); graphics.completion.bits.success.expect(false.B)
        assert(traps>trapsBefore && writes==2 && get(atomicAddress,4)==(if(compare)3 else 16))
        d.io.system.imageLease.get.expect(true.B); graphics.completion.ready.poke(true.B); tick(); graphics.completion.ready.poke(false.B)
        until("page-denied drain")(pending.isEmpty && d.io.system.vmContextIdle.peek().litToBoolean)
        expectPageFault=false; d.io.system.vectorSatp.poke("h80000050".U)
        graphics.launch.bits.commandId.poke(6.U); graphics.launch.valid.poke(true.B)
        until("recovery admission")(graphics.launch.ready.peek().litToBoolean); tick(); graphics.launch.valid.poke(false.B)
        until("recovery completion")(graphics.completion.valid.peek().litToBoolean)
        graphics.completion.bits.commandId.expect(6.U); graphics.completion.bits.success.expect(true.B)
        d.io.system.imageLease.get.expect(true.B); graphics.completion.ready.poke(true.B); tick(); graphics.completion.ready.poke(false.B)
        until("recovery drain")(pending.isEmpty && d.io.system.vmContextIdle.peek().litToBoolean)
        assert(writes==4 && get(atomicAddress,4)==(if(compare)3 else 22))
        for(i<-0 until 64 if i<8 || i>=12) assert(memory.getOrElse(BigInt((atomicAddress & ~63)+i),0xa5)==0xa5)
      }
    }
  }

  }
}
