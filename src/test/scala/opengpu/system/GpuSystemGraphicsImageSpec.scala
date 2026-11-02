package opengpu.system

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.graphics.{GraphicsStage,TextureImageFormat}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** Local full-system qualification for the opt-in graphics image handoff. */
class GpuSystemGraphicsImageSpec extends AnyFlatSpec {
  private def zero(data: Data): Unit = data match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s.poke(0.S)
    case _ => ()
  }
  private def image(fn:Int,pair:Int=0,vs1:Int=0,vs2:Int=0):BigInt =
    (BigInt(fn)<<26)|(BigInt(1)<<25)|(BigInt(vs2)<<20)|(BigInt(vs1)<<15)|(BigInt(pair)<<12)|0x2b
  private def vector(fn:Int,form:Int,rd:Int,vs1:Int,vs2:Int=0):BigInt =
    (BigInt(fn)<<26)|(BigInt(1)<<25)|(BigInt(vs2)<<20)|(BigInt(vs1)<<15)|(BigInt(form)<<12)|(BigInt(rd)<<7)|0x57

  it should "capture graphics image tasks and enforce stage permissions through an actual shared CU" in {
    val config=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2)
    simulate(new GpuSystem(config,numComputeUnits=1,commandIdWidth=4,transactionsPerCu=8,
      enableGraphicsKernels=true,imageAvailable=true,graphicsImageAvailable=true,
      instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
      d.io.graphicsDrained.poke(true.B);d.io.graphicsShaderL1Invalidate.ready.poke(true.B)
      d.io.trap.foreach(_.ready.poke(true.B))
      val graphics=d.io.graphicsKernels.get;graphics.trap.ready.poke(true.B)
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
        d.io.memoryRequest.ready.poke((cycle%5!=0).B)
        val reply=pending.headOption.filter(_.due<=cycle)
        d.io.memoryResponse.valid.poke(reply.nonEmpty.B)
        reply.foreach{r=>d.io.memoryResponse.bits.transactionId.poke(r.id.U);d.io.memoryResponse.bits.readData.poke(r.data.U)}
        if(reply.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean){val r=pending.dequeue()
          if(r.write)for(i<-0 until 64 if r.mask.testBit(i))memory(r.address+i)=((r.data>>(8*i))&255).toInt}
        if(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean){
          val q=d.io.memoryRequest.bits;val a=q.address.peek().litValue;val write=q.isWrite.peek().litToBoolean
          if(write)writes+=1
          pending.enqueue(Reply(q.transactionId.peek().litValue,a,if(write)q.writeData.peek().litValue else get(a,64),
            q.byteMask.peek().litValue,write,cycle+(if(write)11 else 3)))}
        d.io.trap.foreach(_.valid.expect(false.B)); if(graphics.trap.valid.peek().litToBoolean){assert(allowTrap);graphics.trap.bits.taskId.expect(4.U);traps+=1}
        d.clock.step();cycle+=1
      }
      def until(label:String)(condition: => Boolean):Unit={var n=0;while(!condition && n<60000){tick();n+=1};assert(condition,s"$label stalled at $cycle")}
      val task=graphics.imageTask.get;task.enabled.poke(true.B);task.stage.poke(GraphicsStage.fragment.U)
      task.table.version.poke(1.U);task.table.base.poke(0x8000.U);task.table.limit.poke(0x8100.U)
      task.table.identity.context.poke(7.U);task.table.identity.pass.poke(11.U);task.table.identity.generation.poke(13.U)
      graphics.launch.bits.commandId.poke(3.U);graphics.launch.bits.launch.kernelPc.poke(0x1000.U)
      for(i<-0 until 3){graphics.launch.bits.launch.gridSize(i).poke(1.U);graphics.launch.bits.launch.localSize(i).poke((if(i==0)4 else 1).U)}
      graphics.launch.valid.poke(true.B);until("graphics admission")(graphics.launch.ready.peek().litToBoolean);tick()
      graphics.launch.valid.poke(false.B);task.enabled.poke(false.B);task.stage.poke(GraphicsStage.compute.U);task.table.base.poke(0xdead0000L.U)
      until("graphics completion")(graphics.completion.valid.peek().litToBoolean)
      graphics.completion.bits.commandId.expect(3.U);graphics.completion.bits.success.expect(true.B)
      val held=graphics.completion.bits.peek().litValue
      for(_<-0 until 8){tick();assert(graphics.completion.bits.peek().litValue==held)}
      graphics.completion.ready.poke(true.B);tick();graphics.completion.ready.poke(false.B)
      until("memory drain")(pending.isEmpty && d.io.vmContextIdle.peek().litToBoolean)
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
      until("denied completion")(graphics.completion.valid.peek().litToBoolean)
      graphics.completion.bits.commandId.expect(4.U);graphics.completion.bits.success.expect(false.B)
      assert(traps>0,"a forbidden graphics stage must trap")
      graphics.completion.ready.poke(true.B);tick();graphics.completion.ready.poke(false.B)
      until("denied drain")(pending.isEmpty && d.io.vmContextIdle.peek().litToBoolean)
      assert(writes==oldWrites && oldImage==(0 until 4096).map(i=>memory(BigInt(0x9000+i))).toVector,
        "a denied stage must not modify image data")
    }
  }
}
