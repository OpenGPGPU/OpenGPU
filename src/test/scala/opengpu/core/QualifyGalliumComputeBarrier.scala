package opengpu.core

import chisel3._
import chisel3.reflect.DataMirror
import java.nio.file.{Files, Paths}
import opengpu.config.GpuConfig
import opengpu.core.memory.StorageDescriptorLayout
import opengpu.testutil.GpuSim._
import org.scalatest.DoNotDiscover
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable
import scala.jdk.CollectionConverters._

/** Executes the actual words emitted from the real Mesa barrier compiler run.
  * Only lower memory is modeled. Cross-CU coherence has separate system tests.
  */
@DoNotDiscover
class GalliumComputeBarrierQualification extends AnyFlatSpec {
  private def zero(data: Data): Unit = data match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s.poke(0.S)
    case _ => ()
  }

  it should "execute real Mesa memory barriers with delayed storage acknowledgements and fault recovery" in {
    require(sys.props.get("opengpu.galliumComputeBarrierQualification").contains("1"),
      "explicit local qualification only")
    val directory=Paths.get(sys.props.getOrElse("opengpu.galliumComputeBarrierInputs",
      throw new IllegalArgumentException("supply actual CPU-qualified GLSL output directory")))
    val names=Seq("buffer","image","counter","shared","group","all","branch")
    val programs=names.map { name =>
      val path=directory.resolve(s"$name.native.words")
      val lines=Files.readAllLines(path).asScala.toVector
      require(lines.nonEmpty && lines.size<=512 && lines.forall(_.matches("[0-9a-f]{8}")))
      val code=lines.map(BigInt(_,16))
      require(code.last==BigInt("30500073",16) && code.count(_==BigInt("0330000f",16))>=4)
      require(!code.contains(BigInt("0000000b",16)),"memory barrier became a workgroup rendezvous")
      name -> code
    }
    simulate(new GpuComputeUnit(GpuConfig(lanes=4,warps=2), finishOnTrap=true,
      textureAvailable=false, nativeResourcesAvailable=false, storageAvailable=true,
      instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1)) { d =>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      d.io.l1InvalidateDone.ready.poke(true.B)
      val memory=mutable.Map.empty[BigInt,Int]
      def put(at: BigInt,value: BigInt): Unit =
        for(i<-0 until 4)memory(at+i)=((value>>(8*i))&255).toInt
      def word(at: BigInt): BigInt =
        (0 until 4).map(i=>BigInt(memory.getOrElse(at+i,0))<<(8*i)).sum
      def line(at: BigInt): BigInt =
        (0 until 64).map(i=>BigInt(memory.getOrElse(at+i,0))<<(8*i)).sum
      val descriptor=Array.fill[BigInt](16)(0)
      for((i,v)<-Seq[(Int,BigInt)](0->StorageDescriptorLayout.magic,1->64,2->1,
        3->7,4->11,5->13,6->0,7->1,8->1,9->1,10->0x9000,11->0x9100,
        13->0x9000,14->0x9100))descriptor(i)=v
      descriptor.zipWithIndex.foreach{case(v,i)=>put(0xa000+4*i,v)}
      case class Reply(id:BigInt,at:BigInt,data:BigInt,mask:BigInt,write:Boolean,fault:Boolean,due:Int)
      val pending=mutable.Queue.empty[Reply]
      var cycle=0;var totalKernels=0;var totalFaults=0
      for(((name,code),index)<-programs.zipWithIndex) {
        val pc=BigInt(0x1000+index*0x1000)
        code.zipWithIndex.foreach{case(v,i)=>put(pc+4*i,v)}
        val firstStore=code.indexWhere(w=>(w&127)==0x2b && (w>>26)==16)
        assert(firstStore>=0)
        for(faulted<-Seq(true,false)) {
          val initial=Vector.tabulate(64)(w=>if(w==2)BigInt(41)else BigInt(0x6000+w))
          initial.zipWithIndex.foreach{case(v,w)=>put(0x9000+4*w,v)}
          val task=d.io.storage.get
          task.enabled.poke(true.B);task.table.version.poke(1.U)
          task.table.base.poke(0xa000.U);task.table.limit.poke(0xa100.U)
          task.table.identity.context.poke(7.U);task.table.identity.pass.poke(11.U)
          task.table.identity.generation.poke(13.U)
          d.io.kernel.bits.kernelPc.poke(pc.U)
          for(i<-0 until 3) {
            d.io.kernel.bits.gridSize(i).poke(1.U)
            d.io.kernel.bits.localSize(i).poke((if(i==0 && name=="branch")4 else 1).U)
          }
          assert(d.io.kernel.ready.peek().litToBoolean)
          d.io.kernel.valid.poke(true.B);d.clock.step();d.io.kernel.valid.poke(false.B)
          var writes=0;var acks=0;var reads=0;var traps=0;var firstAck=false
          var elapsed=0;var heldRequest=Option.empty[BigInt]
          while(!d.io.completion.valid.peek().litToBoolean && elapsed<12000) {
            val q=d.io.memoryRequest
            q.ready.poke((cycle%7<4).B)
            val reply=pending.headOption.filter(_.due<=cycle)
            d.io.memoryResponse.valid.poke(reply.nonEmpty.B)
            reply.foreach { r =>
              d.io.memoryResponse.bits.transactionId.poke(r.id.U)
              d.io.memoryResponse.bits.readData.poke(r.data.U)
              d.io.memoryResponse.bits.fault.poke(r.fault.B)
            }
            if(reply.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean) {
              val r=pending.dequeue()
              if(r.write) {
                if(!r.fault)for(i<-0 until 64 if r.mask.testBit(i))memory(r.at+i)=((r.data>>(8*i))&255).toInt
                acks+=1;firstAck=true
              }
            }
            heldRequest.foreach(v=>{q.valid.expect(true.B);assert(q.bits.peek().litValue==v)})
            if(q.valid.peek().litToBoolean) {
              val at=q.bits.address.peek().litValue;val id=q.bits.transactionId.peek().litValue
              val write=q.bits.isWrite.peek().litToBoolean
              if(id==9 && !write)assert(firstAck && !faulted,"Mesa read passed the prior storage acknowledgement")
              if(q.ready.peek().litToBoolean) {
                if(id==9) {
                  assert(at>=0x9000 && at<0x9100)
                  if(write)writes+=1 else reads+=1
                }
                pending.enqueue(Reply(id,at,if(write)q.bits.writeData.peek().litValue else line(at),
                  q.bits.byteMask.peek().litValue,write,faulted && id==9 && write,cycle+(if(write)37 else 5)))
                heldRequest=None
              } else heldRequest=Some(q.bits.peek().litValue)
            }
            if(d.io.trap.valid.peek().litToBoolean) {
              assert(faulted && traps==0)
              d.io.trap.bits.pc.expect((pc+4*firstStore).U)
              d.io.trap.bits.cause.expect(7.U)
              d.io.trap.bits.tval.expect((if(name=="branch")0x9004 else 0x9000).U)
              d.io.trap.ready.poke(true.B);traps+=1
            } else d.io.trap.ready.poke(false.B)
            d.clock.step();cycle+=1;elapsed+=1
          }
          d.io.memoryResponse.valid.poke(false.B);d.io.trap.ready.poke(false.B)
          d.io.completion.valid.expect(true.B);d.io.completion.bits.success.expect((!faulted).B)
          assert(pending.isEmpty && heldRequest.isEmpty && writes==acks)
          if(faulted)assert(traps==1 && writes==1 && reads==0)
          else assert(traps==0 && writes==(if(name=="branch")4 else 2) && reads==(if(name=="branch")2 else 1))
          for(w<-0 until 64) {
            val expected=if(faulted)initial(w)else if(name=="branch") {
              if(w==1 || w==3)BigInt(7)else if(w==5 || w==7)initial(w+4)+3 else initial(w)
            } else if(w==0)BigInt(7)else if(w==1)BigInt(44)else initial(w)
            assert(word(0x9000+4*w)==expected,s"$name fault=$faulted word=$w wrong output/guard")
          }
          val completion=d.io.completion.bits.peek().litValue
          for(_<-0 until 9) {
            d.io.completion.valid.expect(true.B);assert(d.io.completion.bits.peek().litValue==completion)
            d.io.kernel.ready.expect(false.B);d.clock.step()
          }
          d.io.completion.ready.poke(true.B);d.clock.step();d.io.completion.ready.poke(false.B)
          totalKernels+=1;if(faulted)totalFaults+=1
        }
      }
      info(s"MESA_MEMBAR_CU_PASS programs=7 kernels=$totalKernels preciseFaults=$totalFaults delayedAck=37 exactWordsAndGuards=true healthyReuse=true heldCompletion=true noFrame=true")
    }
  }
}

object QualifyGalliumComputeBarrier {
  def main(args: Array[String]): Unit = {
    require(args.isEmpty,"use opengpu.galliumComputeBarrierInputs for the temporary CPU input directory")
    require(org.scalatest.tools.Runner.run(Array("-s",classOf[GalliumComputeBarrierQualification].getName,
      "-oD")),"real-Mesa CU memory-barrier qualification failed")
  }
}
