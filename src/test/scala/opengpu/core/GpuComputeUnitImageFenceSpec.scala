package opengpu.core

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.graphics.GraphicsStage
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** One warp, delayed writes on both image and ordinary-memory clients. */
class GpuComputeUnitImageFenceSpec extends AnyFlatSpec {
  private def zero(data: Data): Unit = data match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s.poke(0.S)
    case _ => ()
  }
  private def vector(fn: Int, form: Int, rd: Int, vs1: Int): BigInt =
    (BigInt(fn)<<26)|(BigInt(1)<<25)|(BigInt(vs1)<<15)|(BigInt(form)<<12)|(BigInt(rd)<<7)|0x57
  private def image(fn: Int, rd: Int = 0, vs1: Int = 0, vs2: Int = 0): BigInt =
    (BigInt(fn)<<26)|(BigInt(1)<<25)|(BigInt(vs2)<<20)|(BigInt(vs1)<<15)|(BigInt(rd)<<7)|0x2b

  it should "order image and ordinary memory across fences after acknowledgements" in {
    val config=GpuConfig(lanes=4, warps=2)
    simulate(new GpuComputeUnit(config, finishOnTrap=true, textureAvailable=false,
      nativeResourcesAvailable=false, imageAvailable=true, storageAvailable=false,
      nativeExecutionProfile=true, instructionCacheSets=4, instructionCacheWays=1,
      vectorCacheSets=4, vectorCacheWays=1)) { d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      d.io.l1InvalidateDone.ready.poke(true.B); d.io.trap.ready.poke(true.B)
      val memory=mutable.Map.empty[BigInt,Int]
      def put(a: BigInt,v: BigInt): Unit=for(i<-0 until 4)memory(a+i)=((v>>(8*i))&255).toInt
      def word(a: BigInt): BigInt=(0 until 4).map(i=>BigInt(memory.getOrElse(a+i,0))<<(8*i)).sum
      def line(a: BigInt): BigInt=(0 until 64).map(i=>BigInt(memory.getOrElse(a+i,0))<<(8*i)).sum
      val code=Vector(BigInt("c100f057",16), vector(23,3,2,0), BigInt("3a800513",16),
        vector(23,4,3,10), image(8,vs1=2,vs2=3),
        image(8,rd=0,vs1=2,vs2=2)|(BigInt(1)<<12), vector(23,3,4,7),
        image(8,rd=0,vs1=4,vs2=2)|(BigInt(2)<<12),
        image(8,rd=0,vs1=2,vs2=2)|(BigInt(3)<<12),
        image(10,vs1=2,vs2=2), BigInt("0330000f",16),
        BigInt("0000a283",16), BigInt("00128293",16), BigInt("0050a223",16),
        BigInt("0330000f",16), image(9,rd=10,vs1=2,vs2=2), BigInt("30500073",16))
      code.zipWithIndex.foreach{case(v,i)=>put(0x1000+i*4,v)}
      put(0x8000,41); put(0x8004,0)
      val words=Array.fill[BigInt](64)(0)
      for((i,v)<-Seq[(Int,BigInt)](0->BigInt("47495501",16),1->256,2->1,3->7,4->11,5->13,
        6->0,7->1,8->1,9->4,10->1,11->1,12->14,13->14,
        22->0x9000,23->0x9100,25->0x9000,26->1,27->1,28->1,29->1,30->4,37->4)) words(i)=v
      words.zipWithIndex.foreach{case(v,i)=>put(0xa000+i*4,v)}
      val task=d.io.images.get
      case class Reply(id:BigInt,address:BigInt,data:BigInt,mask:BigInt,write:Boolean,fault:Boolean,due:Int)
      val pending=mutable.Queue.empty[Reply]
      var cycle=0
      for (faulted <- Seq(true,false)) {
        task.enabled.poke(true.B); task.table.version.poke(1.U)
        task.table.base.poke(0xa000.U); task.table.limit.poke(0xa400.U); task.stage.poke(GraphicsStage.compute.U)
        task.table.identity.context.poke(7.U); task.table.identity.pass.poke(11.U); task.table.identity.generation.poke(13.U)
        d.io.kernel.bits.kernelPc.poke(0x1000.U); d.io.kernel.bits.kernargAddress.poke(0x8000.U)
        for(i<-0 until 3){d.io.kernel.bits.gridSize(i).poke(1.U);d.io.kernel.bits.localSize(i).poke(1.U)}
        var readyCycles=0
        while(!d.io.kernel.ready.peek().litToBoolean && readyCycles<1000){d.clock.step();readyCycles+=1}
        d.io.kernel.ready.expect(true.B); d.io.kernel.valid.poke(true.B);d.clock.step();d.io.kernel.valid.poke(false.B)
        var imageAck=false; var ordinaryAck=false; var result=false; var traps=0
        var imageWrites=0; var ordinaryWrites=0; var imageReads=0; var ordinaryReads=0
        var elapsed=0
        while(!d.io.completion.valid.peek().litToBoolean && elapsed<10000){
          d.io.memoryRequest.ready.poke((cycle%3!=0).B)
          val ready=pending.headOption.exists(_.due<=cycle)
          d.io.memoryResponse.valid.poke(ready.B)
          if(ready){ val r=pending.front; d.io.memoryResponse.bits.transactionId.poke(r.id.U)
            d.io.memoryResponse.bits.readData.poke(r.data.U); d.io.memoryResponse.bits.fault.poke(r.fault.B) }
          if(ready && d.io.memoryResponse.ready.peek().litToBoolean){
            val r=pending.dequeue()
            if(r.write && !r.fault) {
              for(i<-0 until 64 if r.mask.testBit(i)) memory(r.address+i)=((r.data>>(8*i))&255).toInt
            }
            if(r.write && r.id==5) imageAck=true
            if(r.write && r.address==0x8000) ordinaryAck=true
          }
          if(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean){
            val q=d.io.memoryRequest.bits;val id=q.transactionId.peek().litValue
            val a=q.address.peek().litValue;val write=q.isWrite.peek().litToBoolean
            if(a==0x8000 && !write){assert(imageAck && !faulted,"ordinary read overtook or followed failed image write");ordinaryReads+=1}
            if(id==5 && !write){assert(ordinaryAck && !faulted,"image read overtook ordinary write acknowledgement");imageReads+=1}
            if(id==5 && write) imageWrites+=1
            if(a==0x8000 && write) ordinaryWrites+=1
            assert(id<4 || id==4 || id==5,s"unexpected transaction owner $id address $a")
            pending.enqueue(Reply(id,a,if(write)q.writeData.peek().litValue else line(a),
              q.byteMask.peek().litValue,write,faulted && id==5 && write,cycle+(if(write)31 else 4)))
          }
          if(d.io.committedVectorWriteback.valid.peek().litToBoolean && d.io.committedVectorWriteback.bits.vd.peek().litValue==10){
            assert(ordinaryAck && imageAck && !faulted);d.io.committedVectorWriteback.bits.data(0).expect(7.U);result=true
          }
          if(d.io.trap.valid.peek().litToBoolean && d.io.trap.ready.peek().litToBoolean) traps+=1
          d.clock.step();cycle+=1;elapsed+=1
        }
        d.io.memoryResponse.valid.poke(false.B)
        d.io.completion.valid.expect(true.B);d.io.completion.bits.success.expect((!faulted).B)
        assert(imageAck && imageWrites==1 && pending.isEmpty)
        if(faulted){
          assert(traps==1 && !result && !ordinaryAck && ordinaryWrites==0 && ordinaryReads==0 && imageReads==0)
          assert(word(0x9000)==0 && word(0x8004)==0)
        }else{
          assert(traps==0 && result && ordinaryAck && ordinaryWrites==1 && ordinaryReads==1 && imageReads==1)
          assert(word(0x9000)==7 && word(0x8004)==42)
        }
        d.io.completion.ready.poke(true.B);d.clock.step();d.io.completion.ready.poke(false.B)
      }
    }
  }
}
