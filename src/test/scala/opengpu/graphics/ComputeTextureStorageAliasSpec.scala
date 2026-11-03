package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.core.GpuComputeUnit
import opengpu.core.memory.StorageDescriptorLayout
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** Same-address texture/storage observation through one actual CU. */
class ComputeTextureStorageAliasSpec extends AnyFlatSpec {
  import WindowRecordTest._
  import NativeTextureFetchTestData._
  private def vector(fn:Int,form:Int,rd:Int,vs1:Int):BigInt =
    (BigInt(fn)<<26)|(BigInt(1)<<25)|(BigInt(vs1)<<15)|(BigInt(form)<<12)|(BigInt(rd)<<7)|0x57
  private def storage(fn:Int,rd:Int=0,vs1:Int=0,vs2:Int=0):BigInt =
    (BigInt(fn)<<26)|(BigInt(1)<<25)|(BigInt(vs2)<<20)|(BigInt(vs1)<<15)|(BigInt(rd)<<7)|0x2b
  it should "refetch a fenced storage-written texel without retaining stale data" in {
    simulate(new GpuComputeUnit(GpuConfig(lanes=4,warps=2),finishOnTrap=true,
      textureAvailable=false,taskTextureAvailability=true,nativeResourcesAvailable=false,
      storageAvailable=true,computeTextureAvailable=true,nativeExecutionProfile=true,
      instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1)) { d =>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      d.io.l1InvalidateDone.ready.poke(true.B);d.io.trap.ready.poke(true.B)
      val memory=mutable.Map.empty[BigInt,Int]
      def put(a:BigInt,v:BigInt):Unit=for(i<-0 until 4)memory(a+i)=((v>>(8*i))&255).toInt
      def byte(a:BigInt,v:Int):Unit={memory(a)=v}
      def word(a:BigInt):BigInt=(0 until 4).map(i=>BigInt(memory.getOrElse(a+i,0))<<(8*i)).sum
      def line(a:BigInt):BigInt=(0 until 64).map(i=>BigInt(memory.getOrElse(a+i,0))<<(8*i)).sum
      roots(put,stage=2);install(put,byte,0,14,stage=2);coordinates(put,x=0,y=0)
      put(0x2003c,BigInt("12345678",16));put(0x20038,BigInt("11223344",16));put(0x20040,BigInt("55667788",16))
      val desc=Array.fill[BigInt](16)(0)
      for((i,v)<-Seq[(Int,BigInt)](0->StorageDescriptorLayout.magic,1->64,2->1,3->7,4->11,5->13,
        6->0,7->1,8->1,9->1,10->0x20000,11->0x21000,13->0x20000,14->0x21000))desc(i)=v
      desc.zipWithIndex.foreach{case(v,i)=>put(0x7000+i*4,v)}
      val code=Vector(BigInt("0c008293",16),BigInt("00000113",16),scalar(0,rd=9,rs1=5,rs2=2),
        BigInt("c100f057",16),vector(23,3,2,0),vector(23,3,3,7),BigInt("03c00193",16),vector(23,4,4,3),
        storage(14,vs1=3),storage(16,vs1=2,vs2=4),BigInt("0330000f",16),scalar(0,rd=10,rs1=5,rs2=2),
        vector(23,3,3,9),storage(14,vs1=3),storage(16,vs1=2,vs2=4),BigInt("0330000f",16),
        scalar(0,rd=11,rs1=5,rs2=2),BigInt("30500073",16))
      code.zipWithIndex.foreach{case(v,i)=>put(0x1000+i*4,v)}
      val st=d.io.storage.get;st.enabled.poke(true.B);st.table.version.poke(1.U);st.table.base.poke(0x7000.U);st.table.limit.poke(0x7100.U)
      st.table.identity.context.poke(7.U);st.table.identity.pass.poke(11.U);st.table.identity.generation.poke(13.U)
      val tex=d.io.computeTexture.get;tex.enabled.poke(true.B);tex.resources.version.poke(1.U);tex.resources.address.poke(root.U)
      tex.identity.context.poke(7.U);tex.identity.pass.poke(11.U);tex.identity.generation.poke(13.U)
      d.io.kernel.bits.kernelPc.poke(0x1000.U);d.io.kernel.bits.kernargAddress.poke((packet-192).U)
      for(i<-0 until 3){d.io.kernel.bits.gridSize(i).poke(1.U);d.io.kernel.bits.localSize(i).poke(1.U)}
      d.io.kernel.valid.poke(true.B);d.io.kernel.ready.expect(true.B);d.clock.step();d.io.kernel.valid.poke(false.B)
      case class Reply(id:BigInt,address:BigInt,data:BigInt,mask:BigInt,write:Boolean,due:Int)
      val pending=mutable.Queue.empty[Reply];var cycle=0;var writes=0;var acks=0;var reads=0
      val values=mutable.Map.empty[Int,BigInt]
      while(!d.io.completion.valid.peek().litToBoolean && cycle<30000){
        d.io.memoryRequest.ready.poke((cycle%3!=0).B)
        val available=pending.headOption.exists(_.due<=cycle);d.io.memoryResponse.valid.poke(available.B)
        if(available){val r=pending.front;d.io.memoryResponse.bits.transactionId.poke(r.id.U)
          d.io.memoryResponse.bits.readData.poke(r.data.U);d.io.memoryResponse.bits.fault.poke(false.B)}
        if(available && d.io.memoryResponse.ready.peek().litToBoolean){val r=pending.dequeue()
          if(r.write){assert(r.id==9 && r.address==0x20000 && r.mask==(BigInt(15)<<60))
            for(i<-0 until 64 if r.mask.testBit(i))memory(r.address+i)=((r.data>>(8*i))&255).toInt
            acks+=1}}
        if(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean){
          val q=d.io.memoryRequest.bits;val id=q.transactionId.peek().litValue;val a=q.address.peek().litValue;val write=q.isWrite.peek().litToBoolean
          if(write){assert(id==9 && a==0x20000 && reads==writes+1);writes+=1}
          if(id>=13 && !write && a==0x20000){assert(acks==reads,s"texture read $reads overtook storage acknowledgement $acks");reads+=1}
          pending.enqueue(Reply(id,a,if(write)q.writeData.peek().litValue else line(a),q.byteMask.peek().litValue,
            write,cycle+(if(write)31 else 5)))
        }
        if(d.io.committedWriteback.valid.peek().litToBoolean){val q=d.io.committedWriteback.bits;val rd=q.rd.peek().litValue.toInt
          if(Set(9,10,11)(rd)){assert(!values.contains(rd));values(rd)=q.data.peek().litValue}}
        d.io.trap.valid.expect(false.B);d.clock.step();cycle+=1
      }
      d.io.completion.valid.expect(true.B);d.io.completion.bits.success.expect(true.B)
      assert(pending.isEmpty && writes==2 && acks==2 && reads==3)
      assert(values.toMap==Map(9->BigInt("12345678",16),10->BigInt(7),11->BigInt(9)))
      assert(word(0x2003c)==9 && word(0x20038)==BigInt("11223344",16) && word(0x20040)==BigInt("55667788",16))
    }
  }
}
