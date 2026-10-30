package opengpu.core

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.core.memory.StorageDescriptorLayout
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class GpuComputeUnitStorageSpec extends AnyFlatSpec {
  protected def fullProfile: Boolean = false
  private def zero(data: Data): Unit = data match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s.poke(0.S)
    case _ => ()
  }
  private def vector(fn: Int, form: Int, rd: Int, vs1: Int, vs2: Int = 0): BigInt =
    (BigInt(fn)<<26)|(BigInt(1)<<25)|(BigInt(vs2)<<20)|(BigInt(vs1)<<15)|(BigInt(form)<<12)|(BigInt(rd)<<7)|0x57
  private def storage(fn: Int, rd: Int = 0, vs1: Int = 0, vs2: Int = 0): BigInt =
    (BigInt(fn)<<26)|(BigInt(1)<<25)|(BigInt(vs2)<<20)|(BigInt(vs1)<<15)|(BigInt(rd)<<7)|0x2b

  for((translated, capacity) <- Seq((false,false),(true,false),(false,true),(true,true))) {
  it should (if (capacity) s"execute a 128 MiB storage view at the 4 GiB boundary through the CU with Sv32=$translated"
    else s"execute checked storage instructions through the CU with Sv32=$translated") in {
    val config=if(fullProfile) GpuConfig(lanes=16,warps=8) else GpuConfig(lanes=4,warps=2)
    val threads=if(fullProfile) config.lanes*config.warps else config.lanes
    simulate(new GpuComputeUnit(config, finishOnTrap=true, textureAvailable=false,
      nativeResourcesAvailable=false, imageAvailable=true, storageAvailable=true, nativeExecutionProfile=true,
      instructionCacheSets=4, instructionCacheWays=1, vectorCacheSets=4, vectorCacheWays=1)) { d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      d.io.l1InvalidateDone.ready.poke(true.B); d.io.trap.ready.poke(true.B)
      val memory=mutable.Map.empty[BigInt,Int]
      def put(a: BigInt,v: BigInt): Unit=for(i<-0 until 4)memory(a+i)=((v>>(8*i))&255).toInt
      def line(a: BigInt): BigInt=(0 until 64).map(i=>BigInt(memory.getOrElse(a+i,0))<<(8*i)).sum
      val offsetInstructions=if(capacity) Vector(BigInt("080002b7",16),BigInt("ffc28293",16),vector(23,4,2,5))
        else Vector(vector(23,3,2,0))
      val program=Vector(BigInt("0104f057",16)) ++ offsetInstructions ++ Vector(vector(23,3,3,7),vector(23,3,4,0),
        storage(14,vs1=3),storage(16,vs1=4,vs2=2),storage(15,rd=10,vs1=4,vs2=2),BigInt("30500073",16))
      program.zipWithIndex.foreach{case(v,i)=>put(0x1000+i*4,v)}
      val descriptorVirtual = if(translated) 0x00804000 else 0x8000
      val dataVirtual = if(capacity) BigInt("f8000000",16) else BigInt(if(translated) 0x00806000 else 0x9000)
      val descriptorPhysical = if(translated) 0xa000 else 0x8000
      val dataPhysical = if(capacity && translated) BigInt(0xbfc0)
        else if(capacity) BigInt("ffffffc0",16) else BigInt(if(translated) 0xb000 else 0x9000)
      val dataLimit = if(capacity) BigInt(1)<<32 else dataVirtual+256
      val words=Array.fill[BigInt](16)(0)
      for((i,v)<-Seq[(Int,BigInt)](0->StorageDescriptorLayout.magic,1->64,2->1,3->7,4->11,5->13,
        6->0,7->1,8->1,9->1,10->dataVirtual,11->(dataLimit & 0xffffffffL),12->(dataLimit >> 32),
        13->dataVirtual,14->(dataLimit & 0xffffffffL),15->(dataLimit >> 32)))words(i)=v
      words.zipWithIndex.foreach{case(v,i)=>put(descriptorPhysical+i*4,v)}
      if(translated) {
        put(0x30008,(BigInt(0x31)<<10)|1)
        put(0x31010,(BigInt(descriptorPhysical>>12)<<10)|0x243)
        if(capacity) {
          put(0x30ffc,(BigInt(0x32)<<10)|1)
          put(0x32ffc,(BigInt(0xb)<<10)|0x2c7)
        } else put(0x31018,((dataPhysical>>12)<<10)|0x2c7)
        d.io.vectorSatp.poke(BigInt("80000030",16).U)
      }
      val task=d.io.storage.get;task.enabled.poke(true.B);task.table.version.poke(1.U)
      task.table.base.poke(descriptorVirtual.U);task.table.limit.poke((descriptorVirtual+256).U)
      task.table.identity.context.poke(7.U);task.table.identity.pass.poke(11.U);task.table.identity.generation.poke(13.U)
      d.io.kernel.bits.kernelPc.poke(0x1000.U)
      for(i<-0 until 3){d.io.kernel.bits.gridSize(i).poke(1.U);d.io.kernel.bits.localSize(i).poke((if(i==0)threads else 1).U)}
      d.io.kernel.valid.poke(true.B);d.io.kernel.ready.expect(true.B);d.clock.step();d.io.kernel.valid.poke(false.B)
      zero(task);d.io.vectorSatp.poke("h80000040".U);d.io.storageLease.get.expect(true.B)
      case class Reply(id:BigInt,data:BigInt,write:Option[(BigInt,BigInt,BigInt)])
      val pending=mutable.Queue.empty[Reply];var cycle=0;var writes=0;var descriptorReads=0;var dataReads=0;var walks=0;var vectorResults=0
      while(!d.io.completion.valid.peek().litToBoolean && cycle<100000){
        d.io.memoryRequest.ready.poke((cycle%3!=0).B)
        d.io.memoryResponse.valid.poke(pending.nonEmpty.B)
        pending.headOption.foreach{r=>d.io.memoryResponse.bits.transactionId.poke(r.id.U);d.io.memoryResponse.bits.readData.poke(r.data.U)}
        if(pending.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean){
          val r=pending.dequeue();r.write.foreach{case(a,data,mask)=>for(i<-0 until 64 if mask.testBit(i))memory(a+i)=((data>>(8*i))&255).toInt}
        }
        if(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean){
          val r=d.io.memoryRequest.bits;val id=r.transactionId.peek().litValue;val a=r.address.peek().litValue;val wr=r.isWrite.peek().litToBoolean
          if(id==8){assert(a==descriptorPhysical && !wr);descriptorReads+=1}
          if(id==9){assert(a==dataPhysical);if(wr)writes+=1 else dataReads+=1}
          if(id==10){assert(translated && !wr);walks+=1}
          assert(id<4 || id==8 || id==9 || id==10,s"unexpected CU/image/storage owner $id")
          pending.enqueue(Reply(id,if(wr)BigInt(0)else line(a),if(wr)Some((a,r.writeData.peek().litValue,r.byteMask.peek().litValue))else None))
        }
        if(d.io.committedVectorWriteback.valid.peek().litToBoolean && d.io.committedVectorWriteback.bits.vd.peek().litValue==10){
          for(i<-0 until config.lanes)d.io.committedVectorWriteback.bits.data(i).expect(7.U);vectorResults+=1
        }
        assert(!d.io.trap.valid.peek().litToBoolean,
          s"unexpected trap pc=0x${d.io.trap.bits.pc.peek().litValue.toString(16)} " +
            s"cause=${d.io.trap.bits.cause.peek().litValue} tval=0x${d.io.trap.bits.tval.peek().litValue.toString(16)} " +
            s"descriptorReads=$descriptorReads dataReads=$dataReads writes=$writes")
        d.clock.step();cycle+=1
      }
      d.io.completion.valid.expect(true.B);d.io.completion.bits.success.expect(true.B)
      assert(vectorResults==threads/config.lanes && descriptorReads==2*threads && writes==threads && dataReads==threads && pending.isEmpty && (if(translated) walks>0 else walks==0),
        s"results=$vectorResults descriptorReads=$descriptorReads writes=$writes dataReads=$dataReads")
      d.io.storageLease.get.expect(true.B);d.clock.step(3);d.io.storageLease.get.expect(true.B)
      d.io.completion.ready.poke(true.B);d.clock.step();d.io.storageLease.get.expect(false.B)
    }
  }
  }
}
