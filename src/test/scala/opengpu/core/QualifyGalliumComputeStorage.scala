package opengpu.core

import chisel3._
import chisel3.reflect.DataMirror
import chisel3.util.HasBlackBoxInline
import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest
import java.util.Properties
import opengpu.config.{CachePolicy, GpuConfig}
import opengpu.core.memory.StorageDescriptorLayout
import opengpu.testutil.GpuSim._
import org.scalatest.DoNotDiscover
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class GalliumStorageSramModels extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle { val present = Output(Bool()) })
  setInline("GalliumStorageSramModels.sv",
    "module GalliumStorageSramModels(output present); assign present=1'b1; endmodule\n" +
      Seq(32,64).map(w => Files.readString(Paths.get(
        s"depends/asap7_sram_0p0/generated/verilog/srambank_64x4x${w}_6t122.v"))).mkString("\n"))
}

/** One complete default CU. Only the optional checked storage service is on. */
private class GalliumStorageHarness extends Module {
  val cu = Module(new GpuComputeUnit(GpuConfig(),useBlackBoxes=true,enableFpuBackend=true,
    finishOnTrap=true,textureAvailable=false,nativeResourcesAvailable=false,
    storageAvailable=true,nativeExecutionProfile=true))
  val io = IO(new Bundle { val compute = chiselTypeOf(cu.io); val modelsPresent = Output(Bool()) })
  io.compute <> cu.io
  io.modelsPresent := Module(new GalliumStorageSramModels).io.present
}

private object GalliumStorageArtifacts {
  val names = Seq("healthy-a","bounds","denied","healthy-b")
  def sha(path: Path): String = MessageDigest.getInstance("SHA-256")
    .digest(Files.readAllBytes(path)).map(b=>f"${b&255}%02x").mkString
  def properties(path: Path): Properties = {
    val p=new Properties;val stream=Files.newInputStream(path)
    try p.load(stream) finally stream.close()
    p
  }
  case class Export(name: String, words: Vector[BigInt], meta: Properties, kernarg: Array[Byte]) {
    def n(key: String): BigInt = BigInt(Option(meta.getProperty(key)).getOrElse(
      throw new IllegalArgumentException(s"missing $name $key")))
    val grid = (0 until 3).map(a=>n(s"grid.$a"))
    val local = (0 until 3).map(a=>n(s"local.$a"))
    val healthy = name.startsWith("healthy")
    val seed: BigInt = if(name=="healthy-b")BigInt("ffffffff",16) else BigInt(0x12345)
    require(n("format")==1 && n("shader.bytes")==2048 && words.size==512)
    require(local==(if(name=="healthy-b")Seq(5,5,5)else Seq(8,4,4)) &&
      grid==(if(healthy)Seq(2,1,1)else Seq(1,1,1)))
    require(n("code.words")>16 && n("code.words")<=512)
    require(words(n("code.words").toInt-1)==BigInt("30500073",16))
    require(words.drop(n("code.words").toInt).forall(_==0))
    require((words(n("firstLoad.word").toInt)&127)==0x2b &&
      (words(n("firstLoad.word").toInt)>>26)==15)
    def vector(fn: Int, form: Int, masked: Boolean, destination: Option[Int]=None): Boolean =
      words.exists(w=>(w&127)==0x57 && (w>>26)==fn && ((w>>12)&7)==form &&
        w.testBit(25)!=masked && destination.forall(d=>((w>>7)&31)==d))
    require(Seq(0x18,0x19,0x1a,0x1c).forall(fn=>vector(fn,0,false,Some(0))) &&
      vector(0x19,4,false,Some(0)) && vector(0x17,3,true) && vector(0x17,0,true),
      "actual callback output must include all four comparisons and both predicate materialization/UCMP merges")
    require((0 until 4).map(i=>n(s"binding.$i.handle")).distinct.size==4)
    for(i<-0 until 4) {
      require(n(s"binding.$i.slot")==35+i && n(s"binding.$i.offset")==0)
      require(n(s"binding.$i.bytes")==(if(name=="bounds" && i==0)3 else 128*1024*1024))
      require(n(s"binding.$i.access")== (if(i==0)1 else 3))
    }
    require(kernarg.length==12 && n("kernarg.bytes")>=12)
    for(a<-0 until 3) require((0 until 4).map(i=>BigInt(kernarg(4*a+i)&255)<<(8*i)).sum==grid(a))
  }
  def load(directory: Path, expected: String): Seq[Export] = {
    require(expected.matches("[a-f0-9]{64}") && sha(directory.resolve("manifest.properties"))==expected,
      "CPU artifact manifest changed")
    val manifest=properties(directory.resolve("manifest.properties"))
    require(manifest.getProperty("format")=="1" && manifest.size()==17)
    names.map { name=>
      for(suffix<-Seq("words","tgsi","kernarg","meta"))
        require(sha(directory.resolve(s"$name.$suffix"))==manifest.getProperty(s"$name.$suffix.sha256"),
          s"$name.$suffix differs from the actual validated CPU capture")
      val lines=Files.readString(directory.resolve(s"$name.words")).linesIterator.toVector
      require(lines.forall(_.matches("[a-f0-9]{8}")))
      Export(name,lines.map(BigInt(_,16)),properties(directory.resolve(s"$name.meta")),
        Files.readAllBytes(directory.resolve(s"$name.kernarg")))
    }
  }
}

/** Explicit compiler-word execution evidence; no Linux, host or full API claim. */
@DoNotDiscover
class GalliumComputeStorageQualification extends AnyFlatSpec {
  private def zero(data: Data): Unit = data match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input => u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input => s.poke(0.S)
    case _ => ()
  }

  it should "execute actual TGSI predicates on full and partial native workgroups with four full SSBO views and recover from faults" in {
    require(sys.props.get("opengpu.galliumComputeStorageQualification").contains("1"),"explicit local qualification only")
    val exports=GalliumStorageArtifacts.load(Paths.get(sys.props("opengpu.galliumComputeArtifacts")),
      sys.props("opengpu.galliumComputeArtifactSha256"))
    require(exports.take(3).map(_.words).distinct.size==1 && exports.last.words!=exports.head.words)
    simulate(new GalliumStorageHarness) { top=>
      val d=top.io.compute
      zero(top.io);top.reset.poke(true.B);top.clock.step(2);top.reset.poke(false.B)
      top.io.modelsPresent.expect(true.B);d.l1InvalidateDone.ready.poke(true.B)
      val mask32=(BigInt(1)<<32)-1
      val inputs=Seq("00000000","00000001","7fffffff","80000000","ffffffff","80000001","fffffffe","12345678")
        .map(BigInt(_,16))
      var totalCycles=0;var kernelStarts=0
      for((e,epoch)<-exports.zipWithIndex) {
        val memory=mutable.Map.empty[BigInt,Int]
        def put(a: BigInt,v: BigInt): Unit = for(i<-0 until 4)memory(a+i)=((v>>(8*i))&255).toInt
        def read(a: BigInt,n: Int): BigInt = (0 until n).map(i=>BigInt(memory.getOrElse(a+i,0xa5))<<(8*i)).sum
        val pc=BigInt(0x01000000);val kernargV=pc+0x2000;val tableV=pc+0x3000
        val codeP=BigInt(0x00100000)+epoch*0x100000;val kernargP=codeP+0x2000;val tableP=codeP+0x3000
        def dataV(i: Int): BigInt = BigInt(0x10000000)*(i+1)
        def dataP(i: Int): BigInt = BigInt(0x10000000)+epoch*0x1000000+i*0x10000
        val iRoot=BigInt(0x10000)+epoch*0x20000;val vRoot=iRoot+0x10000
        val iSatp=BigInt("80000000",16)|(BigInt(epoch+1)<<22)|(iRoot>>12)
        val vSatp=BigInt("80000000",16)|(BigInt(epoch+1)<<22)|(vRoot>>12)
        val pageTables=mutable.Set.empty[BigInt]
        val levels=mutable.Map.empty[(BigInt,BigInt),BigInt]
        def blankPage(a: BigInt): Unit = for(i<-0 until 4096)memory(a+i)=0
        for(root<-Seq(iRoot,vRoot)){blankPage(root);pageTables+=root}
        def mapPage(root: BigInt,virtual: BigInt,physical: BigInt,flags: Int): Unit = {
          val vpn1=virtual>>22;val vpn0=(virtual>>12)&1023
          val level=levels.getOrElseUpdate((root,vpn1),{
            val p=root+4096*(1+levels.keys.count(_._1==root));blankPage(p);pageTables+=p;p
          })
          put(root+vpn1*4,((level>>12)<<10)|1);put(level+vpn0*4,((physical>>12)<<10)|flags)
        }
        for(i<-0 until 4096)memory(codeP+i)=0
        e.words.zipWithIndex.foreach { case(w,i)=>put(codeP+4*i,w) }
        mapPage(iRoot,pc,codeP,0x4b)
        mapPage(vRoot,kernargV,kernargP,0x43);mapPage(vRoot,tableV,tableP,0x43)
        e.kernarg.zipWithIndex.foreach { case(b,i)=>memory(kernargP+i)=b&255 }
        for(i<-0 until 4) {
          val record=Array.fill[BigInt](16)(0)
          for((index,value)<-Seq[(Int,BigInt)](0->StorageDescriptorLayout.magic,1->64,2->1,
            3->e.n("context"),4->7,5->(epoch+1),6->i,7->1,
            8->(e.n(s"binding.$i.access")&1),9->((e.n(s"binding.$i.access")>>1)&1),
            10->dataV(i),11->(dataV(i)+e.n(s"binding.$i.bytes")),
            13->dataV(i),14->(dataV(i)+128*1024*1024)))record(index)=value
          record.zipWithIndex.foreach { case(w,j)=>put(tableP+i*64+j*4,w) }
          mapPage(vRoot,dataV(i),dataP(i),if(e.name=="denied" && i==0)0xc9 else if(i==0)0x43 else 0xc7)
          // First-page bytes and adjacent complete-line guards are checked;
          // the untouched 128MiB interior is not allocated or traversed.
          for(j<- -64 until 4160)memory(dataP(i)+j)=0xa5
          for(j<-0 until 1024)put(dataP(i)+j*4,if(i==0)inputs(j%inputs.size) else BigInt(0xdeadfaceL))
        }
        val frozen=memory.toMap
        val expected=mutable.Map.empty[BigInt,BigInt]
        val items=(e.grid.product*e.local.product).toInt
        for(i<-0 until items) {
          // Independent per-word conditions, not an emulation of packed v0.
          val value=(inputs(i%inputs.size)+e.seed)&mask32
          val p=Seq(value==0,value!=0,value<128,value>=BigInt("80000000",16))
          val selected=if(p.head)p.reverse else p
          expected(dataP(1)+i*4)=if(p.head)BigInt("deadbeef",16)else value
          expected(dataP(2)+i*4)=selected.zipWithIndex.map { case(set,bit)=>if(set)BigInt(1)<<bit else BigInt(0) }.sum
          expected(dataP(3)+i*4)=if(value!=0)e.seed else BigInt(i)
        }
        case class Reply(id: BigInt, address: BigInt, value: BigInt, due: Int,
                         write: Option[(BigInt,BigInt,BigInt)])
        val pending=mutable.ArrayBuffer.empty[Reply];var offered=Option.empty[Reply]
        val outstanding=mutable.Set.empty[BigInt]
        var heldRequest=Option.empty[BigInt];var heldTrap=Option.empty[BigInt]
        val descriptors=mutable.Set.empty[Int];val loaded=mutable.Set.empty[Int]
        val written=mutable.Set.empty[BigInt];val acknowledged=mutable.Set.empty[BigInt]
        val trapped=mutable.Set.empty[Int]
        var cycles=0;var peakWarps=0;var heldTrapCycles=0;var walks=0;var codeReads=0;var kernargReads=0
        var dataReads=0;var warpStarts=0;var priorActive=BigInt(0)
        var requestStalls=0;var replyStalls=0;var admission=false
        def drained: Boolean = pending.isEmpty && offered.isEmpty && outstanding.isEmpty &&
          heldRequest.isEmpty && !d.memoryRequest.valid.peek().litToBoolean && !d.memoryResponse.valid.peek().litToBoolean
        def tick(): Unit = {
          assert(cycles<131072 && totalCycles<524288,s"${e.name} exceeded bounded cycle budget ($cycles/$totalCycles)")
          if(admission) {
            d.instructionSatp.expect(iSatp.U);d.vectorSatp.expect(vSatp.U);d.storageLease.get.expect(true.B)
          }
          val active=d.active.peek().litValue
          peakWarps=peakWarps.max(active.bitCount);warpStarts+=(active & ~priorActive).bitCount;priorActive=active
          d.globalAtomicRequest.valid.expect(false.B);d.texSample.valid.expect(false.B);d.vectorTexSample.valid.expect(false.B)
          if(d.trap.valid.peek().litToBoolean) {
            assert(!e.healthy);val t=d.trap.bits;val warp=t.warpId.peek().litValue.toInt
            t.pc.expect((pc+4*e.n("firstLoad.word")).U)
            t.cause.expect((if(e.name=="bounds")5 else 13).U);t.laneFaultMask.expect(1.U)
            t.tval.expect((if(e.name=="bounds")BigInt(0)else dataV(0)+warp*64).U)
            val bits=t.peek().litValue;heldTrap.foreach(old=>assert(old==bits,"held trap changed"))
            d.trap.ready.poke((heldTrapCycles>=9).B)
            if(d.trap.ready.peek().litToBoolean) {
              assert(peakWarps==8 && !trapped(warp),"fault must retire eight unique resident physical warps")
              trapped+=warp;heldTrap=None
            } else { heldTrap=Some(bits);heldTrapCycles+=1 }
          } else {assert(heldTrap.isEmpty,"held trap disappeared");d.trap.ready.poke(false.B)}
          d.memoryRequest.ready.poke((totalCycles%7!=0).B)
          if(offered.isEmpty && totalCycles%5!=0) {
            val ready=pending.indices.filter(i=>pending(i).due<=totalCycles)
            if(ready.nonEmpty)offered=Some(pending.remove(ready.maxBy(i=>pending(i).id)))
          }
          d.memoryResponse.valid.poke(offered.nonEmpty.B);d.memoryResponse.bits.fault.poke(false.B)
          offered.foreach { r=>d.memoryResponse.bits.transactionId.poke(r.id.U);d.memoryResponse.bits.readData.poke(r.value.U) }
          if(offered.nonEmpty) {
            if(d.memoryResponse.ready.peek().litToBoolean) {
              val r=offered.get;assert(outstanding.remove(r.id))
              r.write.foreach { case(a,value,mask)=>
                val first=mask.lowestSetBit;assert(first>=0 && mask==(BigInt(15)<<first))
                val word=a+first;assert(written(word) && !acknowledged(word));acknowledged+=word
                for(i<-0 until 64 if mask.testBit(i))memory(a+i)=((value>>(8*i))&255).toInt
              }
              offered=None
            } else replyStalls+=1
          }
          val valid=d.memoryRequest.valid.peek().litToBoolean
          if(valid) {
            val q=d.memoryRequest.bits;val bits=q.peek().litValue
            heldRequest.foreach(old=>assert(old==bits,"held memory request changed"))
            if(d.memoryRequest.ready.peek().litToBoolean) {
              heldRequest=None
              val id=q.transactionId.peek().litValue;val a=q.address.peek().litValue
              val wr=q.isWrite.peek().litToBoolean;val size=q.sizeLog2.peek().litValue.toInt
              assert(!outstanding(id),s"transaction $id reused before response");outstanding+=id
              assert(id<4 || id==8 || id==9 || id==10,s"unexpected memory namespace $id")
              if(size==2) {
                assert(!wr && (a&3)==0 && pageTables(a& ~BigInt(4095)));walks+=1
              } else {
                assert(size==6 && (a&63)==0)
                if(id==8) {assert(!wr && a>=tableP && a<tableP+256);descriptors+=((a-tableP)/64).toInt}
                else if(id==9) {
                  q.cachePolicy.expect(CachePolicy.uncached)
                  assert(e.healthy,"faulting descriptor/PTE reached storage data")
                  if(wr) {
                    val mask=q.byteMask.peek().litValue;val first=mask.lowestSetBit
                    assert(first>=0 && mask==(BigInt(15)<<first))
                    val word=a+first;assert(expected.contains(word) && !written(word),s"unexpected/repeated write $word")
                    assert(((q.writeData.peek().litValue>>(8*first))&mask32)==expected(word),s"incorrect shader output at $word")
                    written+=word
                  } else {
                    assert(a>=dataP(0) && a<dataP(0)+items*4);loaded+=((a-dataP(0))/64).toInt;dataReads+=1
                  }
                } else {
                  assert(id<4 && !wr)
                  if(a>=codeP && a<codeP+4096)codeReads+=1
                  else {assert(a==kernargP);kernargReads+=1}
                }
              }
              val write=if(wr)Some((a,q.writeData.peek().litValue,q.byteMask.peek().litValue))else None
              pending+=Reply(id,a,if(wr)BigInt(0)else read(a,if(size==2)4 else 64),totalCycles+(if(wr)17 else 3),write)
            } else {heldRequest=Some(bits);requestStalls+=1}
          } else assert(heldRequest.isEmpty,"held memory offer was withdrawn")
          top.clock.step();cycles+=1;totalCycles+=1
        }
        def until(label: String)(p: => Boolean): Unit = while(!p) {
          assert(cycles<131072,s"${e.name}: $label timed out");tick()
        }
        // Flush on an idle CU, before changing the next publication's roots.
        d.invalidateInstructionCache.poke(true.B);d.instructionTlbFlush.valid.poke(true.B)
        d.vectorTlbFlush.valid.poke(true.B);tick()
        d.invalidateInstructionCache.poke(false.B);d.instructionTlbFlush.valid.poke(false.B)
        d.vectorTlbFlush.valid.poke(false.B)
        d.instructionSatp.poke(iSatp.U);d.vectorSatp.poke(vSatp.U)
        val task=d.storage.get
        task.enabled.poke(true.B);task.table.version.poke(1.U);task.table.base.poke(tableV.U);task.table.limit.poke((tableV+256).U)
        task.table.identity.context.poke(e.n("context").U);task.table.identity.pass.poke(7.U);task.table.identity.generation.poke((epoch+1).U)
        d.kernel.bits.kernelPc.poke(pc.U);d.kernel.bits.kernargAddress.poke(kernargV.U)
        for(a<-0 until 3){d.kernel.bits.gridSize(a).poke(e.grid(a).U);d.kernel.bits.localSize(a).poke(e.local(a).U)}
        d.kernel.valid.poke(true.B);until("kernel admission")(d.kernel.ready.peek().litToBoolean)
        tick();kernelStarts+=1;admission=true;d.kernel.valid.poke(false.B)
        zero(task) // The true captured storage publication must outlive stale live inputs.
        until("completion")(d.completion.valid.peek().litToBoolean)
        d.completion.bits.success.expect(e.healthy.B)
        // Clear the software-driven response valid, then inspect every actual
        // held/unaccepted offer as well as accepted outstanding traffic.
        tick();assert(drained,s"${e.name}: completion escaped lower-memory drain")
        assert(walks>0 && codeReads>0 && kernargReads>0 && peakWarps==8 && requestStalls>0 &&
          warpStarts==(e.grid.product*8).toInt)
        val golden=mutable.Map.empty[BigInt,Int]++frozen
        if(e.healthy) {
          assert(trapped.isEmpty && descriptors.toSet==(0 until 4).toSet)
          assert(written.toSet==expected.keySet && acknowledged.toSet==expected.keySet && written.size==items*3)
          assert(loaded.toSet==(0 until (items+15)/16).toSet && dataReads==items)
          expected.foreach { case(a,v)=>for(i<-0 until 4)golden(a+i)=((v>>(8*i))&255).toInt }
        } else assert(written.isEmpty && acknowledged.isEmpty && loaded.isEmpty &&
          descriptors.toSet==Set(0) && trapped.toSet==(0 until 8).toSet && heldTrapCycles>=9)
        assert(memory.toMap==golden.toMap,"an output, immutable byte or allocation guard changed")
        val held=d.completion.bits.peek().litValue;d.kernel.valid.poke(true.B)
        for(_<-0 until 13){d.kernel.ready.expect(false.B);tick();assert(d.completion.bits.peek().litValue==held && drained)}
        d.kernel.valid.poke(false.B);d.completion.ready.poke(true.B);tick();admission=false
        d.completion.ready.poke(false.B);d.storageLease.get.expect(false.B);assert(drained)
        val tail=((e.local.product.toInt-1)%16)+1
        info(s"GALLIUM_TGSI_CU case=${e.name} PASS codeWords=${e.n("code.words")} grid=${e.grid.mkString("x")} local=${e.local.mkString("x")} lastWarpActive=$tail kernelStarts=1 workgroups=${e.grid.product} actualWarpStarts=$warpStarts nativeLanes=16 nativeWarps=8 binding0Bytes=${e.n("binding.0.bytes")} maxViewBytes=134217728 bindings=4 reads=$dataReads writes=${written.size} acks=${acknowledged.size} uniqueTraps=${trapped.size} cycles=$cycles responseHoldCycles=$replyStalls heldCompletion=true capturedStorage=true stableActiveSatp=true predicates=true")
      }
      assert(kernelStarts==4 && totalCycles<=524288)
      val healthyItems=exports.filter(_.healthy).map(e=>e.grid.product*e.local.product).sum
      assert(healthyItems==506)
      info(s"GALLIUM_TGSI_CU PASS parents=4 actualCuKernelStarts=$kernelStarts workgroups=6 healthyItems=$healthyItems totalCycles=$totalCycles realNativeSram=true hostLinuxAPIQualified=false H7Complete=false")
    }
  }
}

object QualifyGalliumComputeStorage {
  def main(args: Array[String]): Unit = {
    require(sys.props.get("opengpu.galliumComputeStorageQualification").contains("1"),
      "requires -Dopengpu.galliumComputeStorageQualification=1")
    require(args.length==6 && args(0)=="--artifacts" && args(2)=="--manifest-sha256" && args(4)=="--reports",
      "usage: --artifacts DIRECTORY --manifest-sha256 SHA256 --reports DIRECTORY")
    // Validate before the test can elaborate or consult a native cache.
    GalliumStorageArtifacts.load(Paths.get(args(1)),args(3))
    sys.props("opengpu.galliumComputeArtifacts")=args(1)
    sys.props("opengpu.galliumComputeArtifactSha256")=args(3)
    val passed=org.scalatest.tools.Runner.run(Array("-s",classOf[GalliumComputeStorageQualification].getName,"-oD","-u",args(5)))
    require(passed,"Gallium TGSI compute storage qualification failed")
  }
}
