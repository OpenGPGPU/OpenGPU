package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.core.GpuComputeUnit
import opengpu.core.memory.StorageDescriptorLayout
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** Delayed external acknowledgements on the storage and compute-texture clients. */
class ComputeTextureFenceOrderingSpec extends AnyFlatSpec {
  import WindowRecordTest._
  import NativeTextureFetchTestData._
  private def vector(fn: Int, form: Int, rd: Int, vs1: Int): BigInt =
    (BigInt(fn)<<26)|(BigInt(1)<<25)|(BigInt(vs1)<<15)|(BigInt(form)<<12)|(BigInt(rd)<<7)|0x57
  private def storage(fn: Int, rd: Int = 0, vs1: Int = 0, vs2: Int = 0): BigInt =
    (BigInt(fn)<<26)|(BigInt(1)<<25)|(BigInt(vs2)<<20)|(BigInt(vs1)<<15)|(BigInt(rd)<<7)|0x2b

  it should "order storage and texture operations through delayed fences and faults" in {
    simulate(new GpuComputeUnit(GpuConfig(lanes=4,warps=2), finishOnTrap=true,
      textureAvailable=false, taskTextureAvailability=true, nativeResourcesAvailable=false,
      storageAvailable=true, computeTextureAvailable=true, nativeExecutionProfile=true,
      instructionCacheSets=4, instructionCacheWays=1, vectorCacheSets=4, vectorCacheWays=1)) { d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      d.io.l1InvalidateDone.ready.poke(true.B); d.io.trap.ready.poke(true.B)
      val memory=mutable.Map.empty[BigInt,Int]
      def put(a:BigInt,v:BigInt):Unit=for(i<-0 until 4)memory(a+i)=((v>>(8*i))&255).toInt
      def word(a:BigInt):BigInt=(0 until 4).map(i=>BigInt(memory.getOrElse(a+i,0))<<(8*i)).sum
      def line(a:BigInt):BigInt=(0 until 64).map(i=>BigInt(memory.getOrElse(a+i,0))<<(8*i)).sum
      def byte(a:BigInt,v:Int):Unit={memory(a)=v}
      roots(put,stage=2); install(put,byte,0,3,stage=2)
      val descriptor=Array.fill[BigInt](16)(0)
      for((i,v)<-Seq[(Int,BigInt)](0->StorageDescriptorLayout.magic,1->64,2->1,3->7,4->11,5->13,
        6->0,7->1,8->1,9->1,10->0x60000,11->0x60100,13->0x60000,14->0x60100))descriptor(i)=v
      descriptor.zipWithIndex.foreach{case(v,i)=>put(0x7000+i*4,v)}
      val size=(BigInt(6)<<25)|(BigInt(11)<<7)|0x0b
      val code=Vector(BigInt("c100f057",16),vector(23,3,2,0),vector(23,3,3,7),vector(23,3,4,0),
        storage(14,vs1=3),storage(16,vs1=2,vs2=4),BigInt("0330000f",16),size,
        BigInt("0330000f",16),storage(15,rd=10,vs1=2,vs2=4),BigInt("0330000f",16),
        BigInt("00b0a023",16),BigInt("30500073",16))
      code.zipWithIndex.foreach{case(v,i)=>put(0x1000+i*4,v)}
      case class Reply(id:BigInt,address:BigInt,data:BigInt,mask:BigInt,write:Boolean,fault:Boolean,due:Int)
      val pending=mutable.Queue.empty[Reply]
      var cycle=0
      for(mode<-Seq("storage-fault","texture-fault","healthy")) {
        val st=d.io.storage.get;st.enabled.poke(true.B);st.table.version.poke(1.U)
        st.table.base.poke(0x7000.U);st.table.limit.poke(0x7100.U)
        st.table.identity.context.poke(7.U);st.table.identity.pass.poke(11.U);st.table.identity.generation.poke(13.U)
        val tex=d.io.computeTexture.get;tex.enabled.poke(true.B);tex.resources.version.poke(1.U);tex.resources.address.poke(root.U)
        tex.identity.context.poke(7.U);tex.identity.pass.poke(11.U);tex.identity.generation.poke(13.U)
        d.io.kernel.bits.kernelPc.poke(0x1000.U);d.io.kernel.bits.kernargAddress.poke(0x70000.U)
        for(i<-0 until 3){d.io.kernel.bits.gridSize(i).poke(1.U);d.io.kernel.bits.localSize(i).poke(1.U)}
        var ready=0;while(!d.io.kernel.ready.peek().litToBoolean && ready<1000){d.clock.step();ready+=1}
        d.io.kernel.ready.expect(true.B);d.io.kernel.valid.poke(true.B);d.clock.step();d.io.kernel.valid.poke(false.B)
        var storageAck=false;var textureCompleted=false;var textureFaultSent=false;var result=false;var traps=0
        var textureRequests=0;var storageReads=0;var ordinaryWrites=0;var elapsed=0
        while(!d.io.completion.valid.peek().litToBoolean && elapsed<30000){
          d.io.memoryRequest.ready.poke((cycle%3!=0).B)
          val available=pending.headOption.exists(_.due<=cycle)
          d.io.memoryResponse.valid.poke(available.B)
          if(available){val r=pending.front;d.io.memoryResponse.bits.transactionId.poke(r.id.U)
            d.io.memoryResponse.bits.readData.poke(r.data.U);d.io.memoryResponse.bits.fault.poke(r.fault.B)}
          if(available && d.io.memoryResponse.ready.peek().litToBoolean){
            val r=pending.dequeue()
            if(r.write && !r.fault)for(i<-0 until 64 if r.mask.testBit(i))memory(r.address+i)=((r.data>>(8*i))&255).toInt
            if(r.write && r.id==9)storageAck=true
          }
          if(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean){
            val q=d.io.memoryRequest.bits;val id=q.transactionId.peek().litValue
            val a=q.address.peek().litValue;val write=q.isWrite.peek().litToBoolean
            if(id>=13){assert(storageAck && mode!="storage-fault","texture request overtook or followed failed storage") ;textureRequests+=1}
            if(id==9 && !write){assert(textureCompleted && mode=="healthy","storage load overtook or followed failed texture");storageReads+=1}
            if(a==0x70000 && write){assert(textureCompleted && mode=="healthy");ordinaryWrites+=1}
            val injectTexture=mode=="texture-fault" && id>=13 && !textureFaultSent
            if(injectTexture)textureFaultSent=true
            pending.enqueue(Reply(id,a,if(write)q.writeData.peek().litValue else line(a),q.byteMask.peek().litValue,
              write,(mode=="storage-fault" && id==9 && write) || injectTexture,
              cycle+(if(write || id>=13)31 else 4)))
          }
          if(d.io.committedWriteback.valid.peek().litToBoolean && d.io.committedWriteback.bits.rd.peek().litValue==11){
            assert(mode=="healthy" && storageAck);d.io.committedWriteback.bits.data.expect(2.U);textureCompleted=true
          }
          if(d.io.committedVectorWriteback.valid.peek().litToBoolean && d.io.committedVectorWriteback.bits.vd.peek().litValue==10){
            assert(mode=="healthy" && textureCompleted);d.io.committedVectorWriteback.bits.data(0).expect(7.U);result=true
          }
          if(d.io.trap.valid.peek().litToBoolean && d.io.trap.ready.peek().litToBoolean)traps+=1
          d.clock.step();cycle+=1;elapsed+=1
        }
        d.io.memoryResponse.valid.poke(false.B);d.io.completion.valid.expect(true.B)
        d.io.completion.bits.success.expect((mode=="healthy").B)
        assert(storageAck && pending.isEmpty)
        if(mode=="healthy")assert(traps==0 && textureRequests>0 && storageReads==1 && ordinaryWrites==1 && result && word(0x70000)==2)
        else {assert(traps==1 && !textureCompleted && !result && storageReads==0 && ordinaryWrites==0)
          if(mode=="storage-fault")assert(textureRequests==0) else assert(textureFaultSent && textureRequests>0)}
        d.io.completion.ready.poke(true.B);d.clock.step();d.io.completion.ready.poke(false.B)
      }
    }
  }
}
