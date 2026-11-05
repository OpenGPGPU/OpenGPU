package opengpu.core

import chisel3._
import chisel3.reflect.DataMirror
import chisel3.util.HasBlackBoxInline
import java.nio.file.{Files,Paths}
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.DoNotDiscover
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class ComputeUniformCapacitySramModels extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle { val present = Output(Bool()) })
  setInline("ComputeUniformCapacitySramModels.sv",
    "module ComputeUniformCapacitySramModels(output present); assign present=1'b1; endmodule\n" +
      Seq(32,64).map(w => Files.readString(Paths.get(
        s"depends/asap7_sram_0p0/generated/verilog/srambank_64x4x${w}_6t122.v"))).mkString("\n"))
}

/** One complete native CU; other optional resource services are not enabled. */
private class ComputeUniformCapacityHarness extends Module {
  val cu = Module(new GpuComputeUnit(GpuConfig(),useBlackBoxes=true,enableFpuBackend=true,
    finishOnTrap=true,textureAvailable=false,taskTextureAvailability=true,
    nativeResourcesAvailable=false,computeUniformAvailable=true,nativeExecutionProfile=true))
  val io = IO(new Bundle { val compute = chiselTypeOf(cu.io); val modelsPresent = Output(Bool()) })
  io.compute <> cu.io
  io.modelsPresent := Module(new ComputeUniformCapacitySramModels).io.present
}

/** Explicit local qualification: all views coexist; backing is sparse byte memory. */
@DoNotDiscover
class ComputeUniformCapacityQualification extends AnyFlatSpec {
  private def zero(data: Data): Unit = data match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input => u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input => s.poke(0.S)
    case _ => ()
  }
  private def addi(rd: Int,rs: Int,n: Int): BigInt =
    (BigInt(n&4095)<<20)|(BigInt(rs)<<15)|(BigInt(rd)<<7)|0x13
  private def constant(rd: Int,n: Int): Seq[BigInt] = {
    val high=(n+2048)>>12; val low=n-(high<<12)
    if(high==0) Seq(addi(rd,0,low))
    else Seq((BigInt(high)<<12)|(BigInt(rd)<<7)|0x37,addi(rd,rd,low))
  }
  private def vector(fn: Int,form: Int,rd: Int,vs1: Int,vs2: Int=0): BigInt =
    (BigInt(fn)<<26)|(BigInt(1)<<25)|(BigInt(vs2)<<20)|(BigInt(vs1)<<15)|(BigInt(form)<<12)|(BigInt(rd)<<7)|0x57
  private def uniform(rd: Int,block: Int,offset: Int): BigInt =
    (BigInt(7)<<26)|(BigInt(1)<<25)|(BigInt(offset)<<20)|(BigInt(block)<<15)|(BigInt(rd)<<7)|0x2b

  it should "retain 50176 compute uniform components across 128 work-items and recover after bounds and VM faults" in {
    require(sys.props.get("opengpu.computeUniformCapacityQualification").contains("1"),
      "run explicitly with -Dopengpu.computeUniformCapacityQualification=1")
    simulate(new ComputeUniformCapacityHarness) { top =>
      val d=top.io.compute
      zero(top.io);top.reset.poke(true.B);top.clock.step(2);top.reset.poke(false.B)
      top.io.modelsPresent.expect(true.B)
      d.l1InvalidateDone.ready.poke(true.B)
      val memory=mutable.Map.empty[BigInt,Int]
      def put(a: BigInt,v: BigInt): Unit = for(i<-0 until 4) memory(a+i)=((v>>(8*i))&255).toInt
      def bytes(a: BigInt,n: Int): BigInt = (0 until n).map(i=>BigInt(memory.getOrElse(a+i,0xa5))<<(8*i)).sum
      val mask32=(BigInt(1)<<32)-1;val addressSpace=BigInt(1)<<32
      val mappings=Vector(23,0,21,2,19,4,17,6,15,8,13,10)
      val rootV=BigInt(0x00804000);val tableV=BigInt(0x00805000);val defaultRecordV=tableV+0x800
      val defaultV=BigInt(0x02000000)
      def blockV(i: Int): BigInt = if(i==11) addressSpace-16384 else BigInt(0x01000000)+i*65536
      def blockValue(epoch: Int,i: Int,offset: Int): BigInt =
        (BigInt(0xa1000000L)^(BigInt(epoch)<<24)^(BigInt(mappings(i))<<16)^offset)&mask32
      def defaultValue(epoch: Int,i: Int): BigInt =
        ((BigInt(0x9e3779b9L)*(i+1))^(BigInt(0xd1000000L)+(BigInt(epoch)<<20)))&mask32
      def program(mode: String): Seq[BigInt] = {
        val init=Seq(BigInt("c1087057",16)) // vsetivli zero,16,e32,m1
        if(mode!="capacity") {
          val block=if(mode=="bounds")31 else 11;val offset=if(mode=="bounds")4096 else 16380
          init++constant(14,block)++constant(15,offset)++Seq(vector(23,4,3,14),vector(23,4,4,15),
            uniform(30,3,4),BigInt("30500073",16))
        } else {
          val blocks=(0 until 12).flatMap { i =>
            constant(14,i)++constant(15,0)++Seq(
              (BigInt(7)<<25)|(BigInt(15)<<20)|(BigInt(14)<<15)|(BigInt(16)<<7)|0x0b)++
              constant(15,16380)++Seq(vector(23,4,3,14),vector(23,4,4,15),uniform(10,3,4))
          }
          val defaults=constant(14,31)++Seq(vector(23,4,3,14))++(0 until 8).flatMap { round =>
            // v1 contains the initialized local IDs 0..127. Each round reads
            // 128 different default components through divergent vector offsets.
            Seq(vector(0x25,3,4,2,1))++constant(15,round*512)++
              Seq(vector(0,4,4,15,4),uniform(16+round,3,4))
          }
          init++blocks++defaults++Seq(BigInt("30500073",16))
        }
      }
      case class Reply(id: BigInt,address: BigInt,value: BigInt,due: Int)
      val pending=mutable.ArrayBuffer.empty[Reply];var offered=Option.empty[Reply];var cycle=0
      for((mode,epoch)<-Seq("capacity","bounds","denied","capacity").zipWithIndex) {
        val pc=0x1000+epoch*0x2000
        program(mode).zipWithIndex.foreach { case(w,i)=>put(pc+4*i,w) }
        val rootP=BigInt(0x00200000)+epoch*0x100000;val tableP=rootP+0x1000
        val defaultP=BigInt(0x03000000)+epoch*0x1000000
        def blockP(i: Int): BigInt = BigInt(0x04000000)+epoch*0x1000000+i*65536
        val pageTable=BigInt(0x30000)+epoch*0x10000
        val secondLevels=mutable.Map.empty[BigInt,BigInt]
        val translatedPages=mutable.Map.empty[BigInt,BigInt]
        def mapPage(virtual: BigInt,physical: BigInt,denied: Boolean=false): Unit = {
          val vpn1=virtual>>22;val vpn0=(virtual>>12)&1023
          val level=secondLevels.getOrElseUpdate(vpn1,pageTable+4096*(secondLevels.size+1))
          put(pageTable+vpn1*4,((level>>12)<<10)|1)
          put(level+vpn0*4,((physical>>12)<<10)|(if(denied)0xc9 else 0x43))
          translatedPages(virtual>>12)=physical>>12
        }
        mapPage(rootV,rootP);mapPage(tableV,tableP);mapPage(defaultV,defaultP)
        for(i<-0 until 12;page<-0 until 4)
          mapPage(blockV(i)+page*4096,blockP(i)+page*4096,mode=="denied" && i==11 && page==3)
        def record(a: BigInt,magic: BigInt,fields: Seq[(Int,BigInt)]): Unit = {
          val words=Array.fill[BigInt](16)(0)
          words(0)=magic;words(1)=64;words(2)=1;words(3)=7;words(4)=11;words(5)=13+epoch
          fields.foreach { case(i,v)=>words(i)=v };words.zipWithIndex.foreach { case(v,i)=>put(a+4*i,v) }
        }
        record(rootP,BigInt("47555201",16),Seq(6->2,7->0xfff,
          8->(0 until 6).map(i=>BigInt(mappings(i))<<(5*i)).sum,
          9->(0 until 6).map(i=>BigInt(mappings(i+6))<<(5*i)).sum,
          10->tableV,11->(tableV+1536),13->defaultRecordV,14->1))
        def view(a: BigInt,index: Int,base: BigInt,size: Int): Unit = {
          val end=base+size
          record(a,BigInt("47554201",16),Seq(6->index,7->4,8->base,9->(end&mask32),10->(end>>32),
            11->base,12->(end&mask32),13->(end>>32),14->1))
        }
        for(i<-0 until 12) {
          view(tableP+mappings(i)*64,mappings(i),blockV(i),16384)
          for(offset<-Seq(0,16380)) put(blockP(i)+offset,blockValue(epoch,i,offset))
        }
        view(tableP+0x800,31,defaultV,4096)
        for(i<-0 until 1024) put(defaultP+4*i,defaultValue(epoch,i))
        assert(translatedPages.size==51 && mappings.distinct.size==12)
        val frozen=memory.toMap
        val scalarSeen=Array.fill(8)(0);val vectorSeen=Array.fill(8)(0)
        val defaultSeen=mutable.Set.empty[Int];val dataReads=mutable.Set.empty[BigInt]
        val allowedDescriptors=Set(rootP,tableP+0x800)++mappings.map(i=>tableP+i*64)
        val allowedData=(0 until 12).flatMap(i=>Seq(blockP(i),blockP(i)+16320)).toSet++
          (0 until 64).map(i=>defaultP+i*64)
        var uniformReads=0;var walks=0;var trapCount=0;var heldTrapCycles=0;var peakWarps=0
        val trappedWarps=mutable.Set.empty[Int]
        var heldTrap=Option.empty[BigInt]
        def tick(): Unit = {
          peakWarps=peakWarps.max(d.active.peek().litValue.bitCount)
          d.globalAtomicRequest.valid.expect(false.B)
          if(d.committedWriteback.valid.peek().litToBoolean && d.committedWriteback.bits.rd.peek().litValue==16) {
            assert(mode=="capacity");val w=d.committedWriteback.bits.warpId.peek().litValue.toInt
            val i=scalarSeen(w);assert(i<12);d.committedWriteback.bits.data.expect(blockValue(epoch,i,0).U);scalarSeen(w)+=1
          }
          if(d.committedVectorWriteback.valid.peek().litToBoolean) {
            val wb=d.committedVectorWriteback.bits;val rd=wb.vd.peek().litValue.toInt;val w=wb.warpId.peek().litValue.toInt
            if(rd==10) {
              assert(mode=="capacity");val i=vectorSeen(w);assert(i<12)
              wb.data.foreach(_.expect(blockValue(epoch,i,16380).U));vectorSeen(w)+=1
            }
            if(rd>=16 && rd<24) {
              assert(mode=="capacity")
              for(lane<-0 until 16) {
                val component=(rd-16)*128+w*16+lane
                assert(!defaultSeen(component));wb.data(lane).expect(defaultValue(epoch,component).U);defaultSeen+=component
              }
            }
            assert(rd!=30,"faulting uniform instruction published a destination")
          }
          if(d.trap.valid.peek().litToBoolean) {
            assert(mode!="capacity");d.trap.bits.cause.expect((if(mode=="denied")13 else 5).U)
            d.trap.bits.tval.expect((if(mode=="denied")addressSpace-4 else BigInt(0)).U)
            d.trap.bits.laneFaultMask.expect(65535.U)
            val value=d.trap.bits.peek().litValue;heldTrap.foreach(v=>assert(v==value))
            d.trap.ready.poke((heldTrapCycles>=9).B)
            if(heldTrapCycles<9) { heldTrap=Some(value);heldTrapCycles+=1;d.computeUniformLease.get.expect(true.B) }
            if(d.trap.ready.peek().litToBoolean) {
              val warp=d.trap.bits.warpId.peek().litValue.toInt
              assert(!trappedWarps(warp),s"warp $warp trapped twice")
              trappedWarps+=warp;trapCount+=1;heldTrap=None
            }
          } else { assert(heldTrap.isEmpty,"held trap disappeared before acceptance");d.trap.ready.poke(false.B) }
          d.memoryRequest.ready.poke((cycle%7!=0).B)
          if(offered.isEmpty) {
            val eligible=pending.indices.filter(i=>pending(i).due<=cycle)
            if(eligible.nonEmpty) offered=Some(pending.remove(eligible.maxBy(i=>pending(i).id)))
          }
          d.memoryResponse.valid.poke(offered.nonEmpty.B)
          offered.foreach { r=>d.memoryResponse.bits.transactionId.poke(r.id.U);d.memoryResponse.bits.readData.poke(r.value.U) }
          if(offered.nonEmpty && d.memoryResponse.ready.peek().litToBoolean) offered=None
          if(d.memoryRequest.valid.peek().litToBoolean && d.memoryRequest.ready.peek().litToBoolean) {
            val q=d.memoryRequest.bits;val id=q.transactionId.peek().litValue;val a=q.address.peek().litValue
            q.isWrite.expect(false.B)
            assert(!pending.exists(_.id==id) && !offered.exists(_.id==id),s"transaction $id reused before reply")
            if(id==11) {
              uniformReads+=1;assert(allowedDescriptors(a)||allowedData(a),s"unowned uniform physical read 0x${a.toString(16)}")
              if(allowedData(a)) dataReads+=a
              if(mode=="bounds") assert(!allowedData(a),"out-of-view component reached data memory")
              if(mode=="denied") assert(a!=blockP(11)+16320,"denied mapping reached physical data")
            } else if(id==12) {
              walks+=1;assert(a>=pageTable && a<pageTable+4096*(secondLevels.size+1),"walk lost captured VM root")
            } else assert(id<4 && a>=pc && a<pc+((program(mode).size*4+63)/64)*64+128,s"unexpected owner/address $id/$a")
            pending+=Reply(id,a,bytes(a,64),cycle+(if(id==11 && allowedData(a))23 else 5))
          }
          top.clock.step();cycle+=1
        }
        def until(label: String)(p: => Boolean): Unit = {
          var n=0;while(!p && n<1000000) { tick();n+=1 };assert(p,s"$mode $label stalled at cycle $cycle")
        }
        val task=d.computeUniform.get
        task.enabled.poke(true.B);task.resources.version.poke(1.U);task.resources.address.poke(rootV.U)
        task.identity.context.poke(7.U);task.identity.pass.poke(11.U);task.identity.generation.poke((13+epoch).U)
        task.privateBase.poke(0x07000000.U);task.privateLimit.poke(0x07001000.U)
        d.vectorSatp.poke((BigInt("80000000",16)+(pageTable>>12)).U)
        d.kernel.bits.kernelPc.poke(pc.U);d.kernel.bits.kernargAddress.poke(0x07000000.U)
        for(axis<-0 until 3) { d.kernel.bits.gridSize(axis).poke(1.U);d.kernel.bits.localSize(axis).poke((if(axis==0)128 else 1).U) }
        d.kernel.valid.poke(true.B);until("admission")(d.kernel.ready.peek().litToBoolean);tick();d.kernel.valid.poke(false.B)
        zero(task);d.vectorSatp.poke(BigInt("800000ff",16).U)
        d.computeUniformLease.get.expect(true.B)
        until("completion")(d.completion.valid.peek().litToBoolean)
        d.completion.bits.success.expect((mode=="capacity").B)
        assert(pending.isEmpty && offered.isEmpty && memory.toMap==frozen && walks>0)
        if(mode=="capacity") {
          assert(scalarSeen.forall(_==12) && vectorSeen.forall(_==12) && defaultSeen.size==1024 && peakWarps==8)
          assert(allowedData.subsetOf(dataReads) && trapCount==0)
        } else assert(defaultSeen.isEmpty && scalarSeen.sum==0 && vectorSeen.sum==0 &&
          trapCount==8 && trappedWarps.toSet==(0 until 8).toSet)
        val held=d.completion.bits.peek().litValue;d.kernel.valid.poke(true.B)
        for(_<-0 until 13) {
          d.kernel.ready.expect(false.B);d.computeUniformLease.get.expect(true.B);tick()
          assert(d.completion.bits.peek().litValue==held)
        }
        d.kernel.valid.poke(false.B);d.completion.ready.poke(true.B);tick();d.completion.ready.poke(false.B)
        d.computeUniformLease.get.expect(false.B)
        info(s"COMPUTE_UNIFORM_CAPACITY mode=$mode epoch=$epoch workItems=128 lanes=16 warps=8 blocks=12 blockBytes=16384 defaultComponents=${defaultSeen.size} combinedComponents=50176 physicalPages=51 fullDefaultValues=${mode=="capacity"} blockEndpoints=${mode=="capacity"} finalBlockEndsAt4GiB=true capturedVm=true heldCompletion=true traps=$trapCount uniformReads=$uniformReads walks=$walks")
      }
    }
  }
}

object QualifyComputeUniformCapacity {
  def main(args: Array[String]): Unit = {
    require(sys.props.get("opengpu.computeUniformCapacityQualification").contains("1"),
      "requires -Dopengpu.computeUniformCapacityQualification=1")
    require(args.length==2 && args(0)=="--reports","usage: --reports DIRECTORY")
    val passed=org.scalatest.tools.Runner.run(Array("-s",classOf[ComputeUniformCapacityQualification].getName,"-oD","-u",args(1)))
    require(passed,"compute uniform capacity qualification failed")
    println("COMPUTE_UNIFORM_CAPACITY PASS realNativeSramCu=true tasks=4 healthyTasks=2 boundsAndVmFaultRecovery=true combinedComponents=50176 APIQualified=false fullResourceCoexistence=false H7Complete=false")
  }
}
