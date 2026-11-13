package opengpu.system

import chisel3._
import chisel3.reflect.DataMirror
import java.nio.file.{Files, Paths}
import opengpu.command.GpuCommandOpcode
import opengpu.config.GpuConfig
import opengpu.core.memory.StorageDescriptorLayout
import opengpu.testutil.GpuSim._
import org.scalatest.DoNotDiscover
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable
import scala.jdk.CollectionConverters._

/** Actual Mesa words, two real CUs and L2 RMW. Only lower bytes/PTEs are modeled. */
@DoNotDiscover
class GalliumStorageAtomicQualification extends AnyFlatSpec {
  private def zero(data: Data): Unit = data match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s.poke(0.S)
    case _ => ()
  }
  private val mask32=(BigInt(1)<<32)-1
  private case class Case(name:String,operation:Int,initial:BigInt,operand:BigInt,comparison:BigInt=0)
  private val cases=Vector(
    Case("exchange",0,10,7),Case("add",1,BigInt("fffffffe",16),5),
    Case("xor",2,BigInt("f0f0f0f0",16),BigInt("80000007",16)),
    Case("or",3,0x80,BigInt("80000007",16)),
    Case("and",4,BigInt("f0f0f0f0",16),BigInt("80000007",16)),
    Case("signed-min",5,7,BigInt("fffffff9",16)),Case("signed-max",6,mask32,7),
    Case("unsigned-min",7,mask32,7),Case("unsigned-max",8,7,BigInt("80000007",16)),
    Case("compare-match",9,10,7,10),Case("compare-miss",9,10,7,11))
  private def signed(n:BigInt):BigInt=if(n.testBit(31))n-(BigInt(1)<<32)else n
  private def update(c:Case,old:BigInt):BigInt=(c.operation match {
    case 0=>c.operand;case 1=>old+c.operand;case 2=>old^c.operand
    case 3=>old|c.operand;case 4=>old&c.operand
    case 5=>if(signed(old)<signed(c.operand))old else c.operand
    case 6=>if(signed(old)>signed(c.operand))old else c.operand
    case 7=>old.min(c.operand);case 8=>old.max(c.operand)
    case 9=>if(old==c.comparison)c.operand else old
  })&mask32

  it should "execute actual Mesa SSBO atomics through checked tasks, two CUs and real shared L2" in {
    require(sys.props.get("opengpu.galliumStorageAtomicQualification").contains("1"),"explicit local qualification only")
    val inputs=Paths.get(sys.props.getOrElse("opengpu.galliumStorageAtomicInputs",
      throw new IllegalArgumentException("supply the active actual-Mesa CPU input directory")))
    val names=cases.map(_.name)++Vector("endpoint","past-end","compare-inputs")
    val programs=names.map { name =>
      val lines=Files.readAllLines(inputs.resolve(s"$name.native.words")).asScala.toVector
      require(lines.nonEmpty&&lines.size<=512&&lines.forall(_.matches("[0-9a-f]{8}")))
      val words=lines.map(BigInt(_,16));require(words.last==BigInt("30500073",16))
      val atomic=words.zipWithIndex.filter{case(w,_)=>(w&127)==0x2b&&Set(25,26)((w>>26).toInt)}
      require(atomic.size==1,"one actual SSBO atomic per lane is required")
      val binding=Files.readAllLines(inputs.resolve(s"$name.native.bindings")).asScala.map { line =>
        val p=line.split("=",-1);require(p.length==2);p(0)->p(1).toInt
      }.toMap
      require(binding.keySet==Set("target","output")&&binding.values.toSet==Set(0,1))
      name -> (words,binding("target"),binding("output"),atomic.head._2)
    }.toMap
    val config=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2)
    simulate(new GpuSystem(config,numComputeUnits=2,commandIdWidth=4,transactionsPerCu=16,
      enableUnifiedCommands=true,storageAvailable=true,storageAtomicAvailable=true,computeUniformAvailable=true,
      instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1)) { d =>
      var allCommands=0;var allTraps=0;var allAmoWrites=0
      for(translated<-Seq(false,true)) {
        zero(d.io);d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
        d.io.graphicsDrained.poke(true.B);d.io.graphicsShaderL1Invalidate.ready.poke(true.B)
        val memory=mutable.LongMap.empty[Int]
        def put(at:Long,value:BigInt):Unit=for(i<-0 until 4)memory(at+i)=((value>>(8*i))&255).toInt
        def get(at:Long,bytes:Int=4):BigInt=(0 until bytes).map(i=>BigInt(memory.getOrElse(at+i,0xa5))<<(8*i)).sum
        val pc=names.zipWithIndex.map{case(name,i)=>name->(0x1000L+i*0x1000)}.toMap
        for(name<-names) {
          for(i<-0 until 512)put(pc(name)+4*i,0)
          programs(name)._1.zipWithIndex.foreach{case(w,i)=>put(pc(name)+4*i,w)}
        }
        case class Reply(id:BigInt,at:Long,data:BigInt,mask:BigInt,write:Boolean,fault:Boolean,due:Int)
        val pending=mutable.Queue.empty[Reply]
        var cycle=0;var phase=0
        val scenarios=cases.flatMap(c=>Seq((c,"healthy"),(c,"write-fault"),(c,"recover")))++
          Seq((cases(1).copy(name="endpoint"),"healthy"),(cases(1).copy(name="past-end"),"bounds"),
            (cases(1),"readonly"))++(if(translated)Seq((cases(1),"page-denied"))else Seq.empty)++
          Seq((Case("compare-inputs",9,0,0),"healthy"))
        for((c,mode)<-scenarios) {
          phase+=1
          val (code,targetSlot,outputSlot,atomicPc)=programs(c.name)
          val endpoint=c.name=="endpoint"||c.name=="past-end"
          val uniform=c.name=="compare-inputs"
          val virtual=0x04000000L+phase*0x10000L
          val physical=if(translated)0x20000000L+phase*0x10000L else virtual
          val tableV=virtual;val tableP=physical
          val outputV=virtual+0x4000;val outputP=physical+0x4000
          val targetV=if(endpoint)0xf8000000L else virtual+0x2000
          val targetLimit=if(endpoint)0x100000000L else targetV+256
          val targetWordV=if(endpoint)0xfffffffcL else targetV
          val targetWordP=if(translated)physical+0x2000+(if(endpoint)0xffc else 0)else targetWordV
          val targetLine=targetWordP & ~63L
          val root=0x100000L+phase*0x4000L
          val leaf=mutable.Map.empty[Long,Long]
          def mapPage(v:Long,p:Long,flags:Int):Unit=if(translated) {
            val vpn1=v>>22
            val l=leaf.getOrElseUpdate(vpn1,{
              val address=root+0x1000+leaf.size*0x1000
              put(root+vpn1*4,(BigInt(address>>12)<<10)|1);address
            })
            put(l+((v>>12)&1023)*4,(BigInt(p>>12)<<10)|flags)
          }
          mapPage(tableV,tableP,0x43);mapPage(outputV,outputP,0xc7)
          mapPage(targetWordV,targetWordP,if(mode=="page-denied")0x43 else 0xc7)
          if(translated)d.io.vectorSatp.poke((BigInt(1)<<31|BigInt(root>>12)).U)
          def record(at:Long,magic:BigInt,fields:Seq[(Int,BigInt)]):Unit={
            val w=Array.fill[BigInt](16)(0);w(0)=magic;w(1)=64;w(2)=1;w(3)=7;w(4)=11;w(5)=phase
            fields.foreach{case(i,v)=>w(i)=v};w.zipWithIndex.foreach{case(v,i)=>put(at+4*i,v)}
          }
          def descriptor(slot:Int,base:Long,limit:Long,writable:Boolean):Unit=record(tableP+slot*64,
            StorageDescriptorLayout.magic,Seq(6->BigInt(slot),7->BigInt(1),8->BigInt(1),9->BigInt(if(writable)1 else 0),
              10->BigInt(base),11->BigInt(limit&0xffffffffL),12->BigInt(limit>>32),
              13->BigInt(base),14->BigInt(limit&0xffffffffL),15->BigInt(limit>>32)))
          descriptor(targetSlot,targetV,targetLimit,mode!="readonly")
          descriptor(outputSlot,outputV,outputV+256,true)
          for(i<-0 until 64)memory(targetLine+i)=0x5a
          for(i<-0 until 256)memory(outputP+i)=0xa5
          val expected=mutable.Map.empty[Int,BigInt]
          if(uniform)for(i<-0 until 4){put(targetWordP+4*i,(i+1)*11);expected(i)=BigInt((i+1)*11)}
          else {put(targetWordP,c.initial);expected(0)=c.initial}
          if(uniform)for(i<-0 until 8)put(outputP+4*i,i%4)
          val targetOriginal=Vector.tabulate(64)(i=>memory(targetLine+i))
          val outputOriginal=Vector.tabulate(256)(i=>memory(outputP+i))
          val uniformRootV=virtual+0x6000;val uniformDataV=virtual+0x7000
          if(uniform) {
            mapPage(uniformRootV,physical+0x6000,0x43);mapPage(uniformDataV,physical+0x7000,0x43)
            record(physical+0x6000,BigInt("47555201",16),Seq(6->BigInt(2),7->BigInt(1),
              10->BigInt(uniformRootV+64),11->BigInt(uniformRootV+64+1536)))
            record(physical+0x6040,BigInt("47554201",16),Seq(6->BigInt(0),7->BigInt(4),
              8->BigInt(uniformDataV),9->BigInt(uniformDataV+64),11->BigInt(uniformDataV),
              12->BigInt(uniformDataV+64),14->BigInt(1)))
            for(i<-0 until 4){put(physical+0x7000+16*i,(i+1)*11);put(physical+0x7004+16*i,(i+1)*11+100)}
          }
          val returned=mutable.ArrayBuffer.empty[BigInt]
          val writtenOutput=mutable.Set.empty[Int]
          val trapped=mutable.Set.empty[Int]
          val trapHeld=mutable.Map.empty[Int,(BigInt,Int)]
          var offered=Option.empty[BigInt];var amoWrites=0;var outputWrites=0;var overlap=false
          var deniedTargetReads=0
          def tick():Unit={
            overlap ||= d.io.busyComputeUnits.peek().litValue==3
            d.io.memoryRequest.ready.poke((cycle%7<4).B)
            val reply=pending.headOption.filter(_.due<=cycle)
            d.io.memoryResponse.valid.poke(reply.nonEmpty.B)
            reply.foreach{r=>d.io.memoryResponse.bits.transactionId.poke(r.id.U)
              d.io.memoryResponse.bits.readData.poke(r.data.U);d.io.memoryResponse.bits.fault.poke(r.fault.B)}
            if(reply.nonEmpty&&d.io.memoryResponse.ready.peek().litToBoolean) {
              val r=pending.dequeue()
              if(r.write && !r.fault) {
                if(r.at==targetLine) {
                  val byte=(0 until 64).find(r.mask.testBit).get
                  val offset=((r.at+byte-targetWordP)/4).toInt
                  val before=expected(offset);returned+=before
                  expected(offset)=if(uniform) {
                    val compare=BigInt((offset+1)*11);if(before==compare)compare+100 else before
                  }else update(c,before)
                }
                for(i<-0 until 64 if r.mask.testBit(i))memory(r.at+i)=((r.data>>(8*i))&255).toInt
              }
            }
            val q=d.io.memoryRequest
            offered.foreach(bits=>{q.valid.expect(true.B);assert(q.bits.peek().litValue==bits)})
            if(q.valid.peek().litToBoolean) {
              if(q.ready.peek().litToBoolean) {
                val at=q.bits.address.peek().litValue.toLong;val writing=q.bits.isWrite.peek().litToBoolean
                val id=q.bits.transactionId.peek().litValue
                assert(!pending.exists(_.id==id),"lower transaction reused before reply")
                val mask=q.bits.byteMask.peek().litValue;val data=q.bits.writeData.peek().litValue
                val target=at==targetLine
                if(target && !writing&&(mode=="bounds"||mode=="readonly"||mode=="page-denied"))deniedTargetReads+=1
                if(writing) {
                  assert(target||at==outputP,s"read-only publication/code modified: $at")
                  assert((0 until 16).exists(w=>mask==(BigInt(15)<<(4*w))),"word write escaped its byte mask")
                  if(target) {
                    assert(mode!="bounds"&&mode!="readonly"&&mode!="page-denied")
                    assert(!pending.exists(r=>r.at==targetLine&&r.write),"RMW overtook another delayed write")
                    val byte=(0 until 64).find(mask.testBit).get
                    val offset=((at+byte-targetWordP)/4).toInt
                    assert(expected.contains(offset))
                    val value=if(uniform){val compare=BigInt((offset+1)*11);if(expected(offset)==compare)compare+100 else expected(offset)}
                      else update(c,expected(offset))
                    assert(((data>>(byte*8))&mask32)==value,s"$c $mode wrong L2 RMW value")
                    amoWrites+=1
                  }else {
                    val byte=(0 until 64).find(mask.testBit).get;val index=byte/4
                    assert(index<8 && !writtenOutput(index));writtenOutput+=index;outputWrites+=1
                  }
                }
                pending.enqueue(Reply(id,at,if(writing)data else get(at,64),mask,writing,
                  target&&writing&&mode=="write-fault"&&amoWrites==1,cycle+(if(writing)37 else 5)))
                offered=None
              }else offered=Some(q.bits.peek().litValue)
            }
            for(cu<-0 until 2) {
              val trap=d.io.trap(cu);trap.ready.poke(false.B)
              if(trap.valid.peek().litToBoolean) {
                assert(mode!="healthy"&&mode!="recover")
                val bits=trap.bits.peek().litValue
                val held=trapHeld.getOrElseUpdate(cu,(bits,0));assert(held._1==bits)
                trap.bits.pc.expect((pc(c.name)+atomicPc*4).U)
                trap.bits.laneFaultMask.expect(1.U)
                trap.bits.cause.expect((if(mode=="page-denied")15 else 7).U)
                trap.bits.tval.expect((if(mode=="bounds"||mode=="readonly")0L else targetWordV).U)
                if(held._2>=7){assert(!trapped(cu));trap.ready.poke(true.B);trapped+=cu}
                else trapHeld(cu)=(bits,held._2+1)
              }
            }
            d.clock.step();cycle+=1
          }
          def until(label:String)(condition: => Boolean):Unit={
            val start=cycle
            while(!condition&&cycle-start<80000)tick()
            assert(condition,s"$label stalled $translated ${c.name} $mode phase=$phase cycle=$cycle pending=${pending.size}")
          }
          d.io.vmContextIdle.expect(true.B)
          val command=d.io.gpuCommand.bits;zero(command)
          command.commandId.poke((phase%16).U);command.opcode.poke(GpuCommandOpcode.kernel)
          command.launch.kernelPc.poke(pc(c.name).U)
          for(axis<-0 until 3){command.launch.gridSize(axis).poke((if(axis==0)2 else 1).U)
            command.launch.localSize(axis).poke((if(axis==0)4 else 1).U)}
          val task=command.storage.get;task.enabled.poke(true.B);task.table.version.poke(1.U)
          task.table.base.poke(tableV.U);task.table.limit.poke((tableV+256).U)
          task.table.identity.context.poke(7.U);task.table.identity.pass.poke(11.U);task.table.identity.generation.poke(phase.U)
          if(uniform){val u=command.computeUniform.get;u.enabled.poke(true.B);u.resources.version.poke(1.U)
            u.resources.address.poke(uniformRootV.U);u.identity.context.poke(7.U);u.identity.pass.poke(11.U);u.identity.generation.poke(phase.U)}
          d.io.gpuCommand.valid.poke(true.B);until("admission")(d.io.gpuCommand.ready.peek().litToBoolean)
          tick();d.io.gpuCommand.valid.poke(false.B);zero(command)
          until("held completion")(d.io.gpuCompletion.valid.peek().litToBoolean)
          val success=mode=="healthy"||mode=="recover"
          d.io.gpuCompletion.bits.success.expect(success.B);d.io.gpuCompletion.bits.commandId.expect((phase%16).U)
          val held=d.io.gpuCompletion.bits.peek().litValue
          for(_<-0 until 9){d.io.gpuCompletion.valid.expect(true.B);assert(d.io.gpuCompletion.bits.peek().litValue==held)
            d.io.storageLease.get.expect(true.B);if(uniform)d.io.computeUniformLease.get.expect(true.B);tick()}
          d.io.gpuCompletion.ready.poke(true.B);tick();d.io.gpuCompletion.ready.poke(false.B)
          until("drain")(d.io.vmContextIdle.peek().litToBoolean&&pending.isEmpty)
          assert(offered.isEmpty&&overlap)
          if(success)assert(amoWrites==8&&outputWrites==8&&trapped.isEmpty)
          else if(mode=="write-fault")assert(amoWrites==5&&outputWrites==4&&trapped.size==1)
          else assert(amoWrites==0&&outputWrites==0&&trapped.size==2&&deniedTargetReads==0)
          for((offset,value)<-expected)assert(get(targetWordP+offset*4)==value)
          val actual=writtenOutput.toVector.map(i=>get(outputP+i*4)).sorted
          assert(actual==returned.toVector.sorted,s"${c.name} $mode returned old words mismatch")
          for(i<-0 until 64 if !expected.keys.exists(offset=>i>=targetWordP-targetLine+offset*4&&i<targetWordP-targetLine+offset*4+4))
            assert(memory(targetLine+i)==targetOriginal(i))
          for(i<-0 until 256 if !writtenOutput(i/4))assert(memory(outputP+i)==outputOriginal(i))
          for(_<-0 until 12){d.io.memoryRequest.valid.expect(false.B);tick()}
          allCommands+=1;allTraps+=trapped.size;allAmoWrites+=amoWrites
        }
      }
      info(s"MESA_STORAGE_ATOMIC_L2_PASS operations=10 commands=$allCommands faults=$allTraps atomicWrites=$allAmoWrites CUs=2 lanes=4 BareAndSv32=true delayedAck=37 exactOldWords=true bounds128MiB=true freshRecovery=true heldTrapsAndCompletion=true")
    }
  }
}

object QualifyGalliumStorageAtomic {
  def main(args:Array[String]):Unit={
    require(args.isEmpty,"use opengpu.galliumStorageAtomicInputs for active inputs")
    require(org.scalatest.tools.Runner.run(Array("-s",classOf[GalliumStorageAtomicQualification].getName,"-oD")),
      "actual Mesa storage-atomic qualification failed")
  }
}
