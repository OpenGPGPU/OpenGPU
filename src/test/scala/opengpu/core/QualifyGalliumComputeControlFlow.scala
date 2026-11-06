package opengpu.core

import chisel3._
import chisel3.reflect.DataMirror
import chisel3.util.HasBlackBoxInline
import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest
import java.util.Properties
import opengpu.config.{CachePolicy, GpuConfig}
import opengpu.core.memory.StorageDescriptorLayout
import opengpu.graphics.{ShaderUniformBindingLayout, ShaderUniformRootLayout}
import opengpu.testutil.GpuSim._
import org.scalatest.DoNotDiscover
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class GalliumControlSramModels extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle { val present = Output(Bool()) })
  setInline("GalliumControlSramModels.sv",
    "module GalliumControlSramModels(output present); assign present=1'b1; endmodule\n" +
      Seq(32,64).map(w=>Files.readString(Paths.get(
        s"depends/asap7_sram_0p0/generated/verilog/srambank_64x4x${w}_6t122.v"))).mkString("\n"))
}

/** Unchanged default native CU; both checked resource endpoints are real. */
private class GalliumControlHarness extends Module {
  private val c=GpuConfig()
  require(c.lanes==16 && c.warps==8 && c.simtStackDepth==8 && c.sharedMemoryBytes==16384)
  val cu=Module(new GpuComputeUnit(c,useBlackBoxes=true,enableFpuBackend=true,
    finishOnTrap=true,textureAvailable=false,taskTextureAvailability=true,
    nativeResourcesAvailable=false,storageAvailable=true,computeUniformAvailable=true,
    nativeExecutionProfile=true))
  val io=IO(new Bundle { val compute=chiselTypeOf(cu.io);val modelsPresent=Output(Bool()) })
  io.compute<>cu.io
  io.modelsPresent:=Module(new GalliumControlSramModels).io.present
}

private object GalliumControlArtifacts {
  val names=Seq("full128","nested","masked","selected-fault","fresh125")
  def sha(p: Path): String=MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p))
    .map(b=>f"${b&255}%02x").mkString
  def properties(p: Path): Properties={
    val result=new Properties;val stream=Files.newInputStream(p)
    try result.load(stream) finally stream.close()
    result
  }
  def word(data: Array[Byte],offset: Int): BigInt=
    (0 until 4).map(i=>BigInt(data(offset+i)&255)<<(8*i)).sum
  case class Export(name: String,words: Vector[BigInt],meta: Properties,
                    kernarg: Array[Byte],input: Vector[Array[Byte]],uniform: Vector[Array[Byte]]) {
    def n(key: String): BigInt=BigInt(Option(meta.getProperty(key)).getOrElse(
      throw new IllegalArgumentException(s"missing $name $key")))
    val full=name=="full128" || name=="fresh125"
    val healthy=name!="selected-fault"
    val grid=(0 until 3).map(a=>n(s"grid.$a"));val local=(0 until 3).map(a=>n(s"local.$a"))
    val codeWords=n("code.words").toInt
    val items=(grid.product*local.product).toInt
    val uniformEnabled=n("uniform.enabled")==1
    val uniformWord=words.indexWhere(w=>(w&127)==0x2b && (w>>26)==7)
    val checkedUniformLoads=if(full)416 else if(name=="masked")256 else if(!healthy)8 else 0
    val storageWordOperations=if(full)items*4 else if(name=="nested")128 else if(healthy)320 else 128
    val cycleBudget=if(full)524288 else if(name=="masked")196608 else 131072
    require(n("format")==1 && meta.getProperty("name")==name && words.size==512 &&
      n("shader.bytes")==2048 && n("shader.offset")==0 && n("kernarg.bytes")>=12 && kernarg.length==12)
    require(grid==(if(full)Seq(2,1,1)else Seq(1,1,1)) &&
      local==(if(name=="fresh125")Seq(5,5,5)else Seq(8,4,4)))
    require(codeWords==(if(full)439 else if(name=="nested")194 else 222) &&
      words(codeWords-1)==BigInt("30500073",16) && words.drop(codeWords).forall(_==0))
    require(words.count(w=>(w&0x707f)==0x305b)==(if(name=="nested")4 else 1))
    require(words.count(w=>(w&127)==0x2b && (w>>26)==7)==(if(full)26 else if(name=="nested")0 else 4))
    require(!words.exists(w=>(w&127)==3),"actual five programs use init builtins, not kernarg loads")
    val divides=words.count(w=>(w&127)==0x57 && ((w>>12)&7)==6 && ((w>>26)==0x20 || (w>>26)==0x22))
    require(divides==5)
    // Finite text is visited at most once per physical warp. Charge all text
    // (including skipped markers), all serial lane memory work, full divider
    // iteration time and cold startup separately; see the frozen budget note.
    val staticCycles=codeWords*(grid.product*8).toInt*32+divides*(grid.product*8).toInt*40+
      storageWordOperations*80+checkedUniformLoads*192+8192
    require(staticCycles<=cycleBudget,s"$name static $staticCycles exceeds frozen $cycleBudget")
    for(a<-0 until 3)require(word(kernarg,4*a)==grid(a))
    require((0 until 4).map(b=>n(s"binding.$b.handle")).distinct.size==4 &&
      (0 until 4).map(b=>n(s"binding.$b.api")).toSet==Set[BigInt](0,1,2,3))
    for(b<-0 until 4)require(n(s"binding.$b.slot")==35+b && n(s"binding.$b.offset")==0 &&
      n(s"binding.$b.bytes")== (if(full)BigInt(128)*1024*1024 else BigInt(65536)) &&
      n(s"binding.$b.access")==3 && input(b).length==4096)
    require(uniformEnabled==(name!="nested"))
    for(s<-0 until 13)require(uniform(s).length==n(s"uniform.$s.bytes") &&
      uniform(s).length==(if(full) {if(s==0)4096 else 16384} else if(s==0 && uniformEnabled)4096 else 0))
    if(name=="masked" || !healthy) {
      val source=input((0 until 4).find(b=>n(s"binding.$b.api")==0).get)
      for(i<-0 until 128)require(word(source,4*i)==
        (if(!healthy && i%16==1)BigInt("40000000",16)else if(i%2==1)BigInt(i)else BigInt("ffffffff",16)))
    }
  }
  def load(directory: Path,expected: String): Seq[Export]={
    val manifestPath=directory.resolve("manifest.properties")
    require(expected.matches("[a-f0-9]{64}") && sha(manifestPath)==expected,"actual CPU manifest changed")
    val manifest=properties(manifestPath);require(manifest.getProperty("format")=="1")
    val common=Seq("words","tgsi","kernarg","meta")++(0 until 4).map(b=>s"input$b")
    val exports=names.map { name=>
      val meta=properties(directory.resolve(s"$name.meta"))
      val sizes=(0 until 13).map(s=>meta.getProperty(s"uniform.$s.bytes").toInt)
      val suffixes=common++(if(meta.getProperty("uniform.enabled")=="1")Seq("gum1")else Seq.empty)++
        sizes.indices.filter(s=>sizes(s)>0).map(s=>s"cb$s")
      suffixes.foreach(s=>require(sha(directory.resolve(s"$name.$s"))==
        manifest.getProperty(s"$name.$s.sha256"),s"$name.$s changed after real CPU admission"))
      val text=Files.readString(directory.resolve(s"$name.words")).linesIterator.toVector
      require(text.forall(_.matches("[a-f0-9]{8}")))
      Export(name,text.map(BigInt(_,16)),meta,Files.readAllBytes(directory.resolve(s"$name.kernarg")),
        (0 until 4).map(b=>Files.readAllBytes(directory.resolve(s"$name.input$b"))).toVector,
        sizes.indices.map(s=>if(sizes(s)>0)Files.readAllBytes(directory.resolve(s"$name.cb$s"))else Array.emptyByteArray).toVector)
    }
    val expectedCount=1+exports.map(e=>common.size+(if(e.uniformEnabled)1 else 0)+e.uniform.count(_.nonEmpty)).sum
    require(manifest.size()==expectedCount,"unexpected or missing actual capture files")
    // The 128/125 programs retain their real, different local-ID div/mod
    // constants. Only the healthy/fault pair shares exactly the same program.
    require(exports(2).words==exports(3).words &&
      exports.last.n("context")!=exports(3).n("context"),"fresh reuse must retain actual source and context identity")
    exports
  }
}

/** Explicit direct-CU compiler evidence, never discovered in ordinary CI. */
@DoNotDiscover
class GalliumComputeControlFlowQualification extends AnyFlatSpec {
  private def zero(d: Data): Unit=d match {
    case r: Record=>r.elements.values.foreach(zero)
    case v: Vec[_]=>v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case _=>()
  }
  private val mask32=(BigInt(1)<<32)-1
  private def value(epoch: BigInt,slot: Int,word: Int): BigInt=
    ((BigInt("4ab319d7",16)+epoch*0x1020304+slot*0x718293+BigInt(word)*BigInt("9e3779b9",16))^
      (BigInt(slot+13)<<(word%23)))&mask32
  private def checksum(epoch: BigInt): BigInt={
    var a=value(epoch,0,2)^value(epoch,0,1023)
    for(s<-1 to 12)a=((a*33)&mask32)^value(epoch,s,0)^value(epoch,s,4095)
    a
  }

  it should "execute real ESSL if/else words with native masks, checked resources, held faults and fresh reuse" in {
    require(sys.props.get("opengpu.galliumComputeControlFlowQualification").contains("1"),"explicit local qualification only")
    val exports=GalliumControlArtifacts.load(Paths.get(sys.props("opengpu.galliumControlArtifacts")),
      sys.props("opengpu.galliumControlArtifactSha256"))
    simulate(new GalliumControlHarness) { top=>
      val d=top.io.compute;zero(top.io);top.reset.poke(true.B);top.clock.step(2);top.reset.poke(false.B)
      top.io.modelsPresent.expect(true.B);d.l1InvalidateDone.ready.poke(true.B)
      val mappings=Vector(23,0,21,2,19,4,17,6,15,8,13,10)
      val totalBudget=1507328 // two524288 + masked196608 + two131072
      var totalCycles=0;var kernelStarts=0;var allWarpStarts=0
      for((e,epoch)<-exports.zipWithIndex) {
        val cycleBudget=e.cycleBudget
        val memory=mutable.Map.empty[BigInt,Int]
        def put(a: BigInt,v: BigInt): Unit=for(i<-0 until 4)memory(a+i)=((v>>(8*i))&255).toInt
        def read(a: BigInt,n: Int): BigInt=(0 until n).map(i=>BigInt(memory.getOrElse(a+i,0xa5))<<(8*i)).sum
        def blank(a: BigInt,n: Int): Unit=for(i<-0 until n)memory(a+i)=0
        def record(a: BigInt,fields: Seq[(Int,BigInt)]): Unit={blank(a,64);fields.foreach {case(i,v)=>put(a+4*i,v)}}
        val pc=BigInt(0x01000000);val kernargV=pc+0x1000;val storageTableV=pc+0x2000
        val uniformRootV=pc+0x3000;val uniformTableV=pc+0x4000;val defaultRecordV=uniformTableV+0x800
        val codeP=BigInt(0x00100000)+epoch*0x01000000;val kernargP=codeP+0x1000
        val storageTableP=codeP+0x2000;val uniformRootP=codeP+0x3000
        val uniformTableP=codeP+0x4000;val defaultRecordP=uniformTableP+0x800
        def dataV(b: Int): BigInt=BigInt(0x10000000)*(b+1)
        def dataP(b: Int): BigInt=BigInt(0x10000000)+epoch*0x01000000+b*0x10000
        def uniformV(s: Int): BigInt=if(s==0)BigInt(0x02000000)else if(s==12)(BigInt(1)<<32)-16384 else BigInt(0x04000000)+s*0x10000
        def uniformP(s: Int): BigInt=codeP+0x100000+s*0x10000
        val iRoot=BigInt(0x10000)+epoch*0x20000;val vRoot=iRoot+0x10000
        val iSatp=BigInt("80000000",16)|(BigInt(epoch+1)<<22)|(iRoot>>12)
        val vSatp=BigInt("80000000",16)|(BigInt(epoch+1)<<22)|(vRoot>>12)
        val pageTables=mutable.Set.empty[BigInt];val levels=mutable.Map.empty[(BigInt,BigInt),BigInt]
        for(root<-Seq(iRoot,vRoot)){blank(root,4096);pageTables+=root}
        def map(root: BigInt,virtual: BigInt,physical: BigInt,flags: Int): Unit={
          val level=levels.getOrElseUpdate((root,virtual>>22),{
            val a=root+4096*(1+levels.keys.count(_._1==root));blank(a,4096);pageTables+=a;a
          })
          put(root+(virtual>>22)*4,((level>>12)<<10)|1)
          put(level+((virtual>>12)&1023)*4,((physical>>12)<<10)|flags)
        }
        blank(codeP,4096);e.words.zipWithIndex.foreach {case(w,i)=>put(codeP+4*i,w)}
        e.kernarg.zipWithIndex.foreach {case(b,i)=>memory(kernargP+i)=b&255}
        map(iRoot,pc,codeP,0x4b);map(vRoot,kernargV,kernargP,0x43);map(vRoot,storageTableV,storageTableP,0x43)
        for(b<-0 until 4) {
          record(storageTableP+b*64,Seq[(Int,BigInt)](0->StorageDescriptorLayout.magic,1->64,2->1,
            3->e.n("context"),4->7,5->(epoch+1),6->b,7->1,8->1,9->1,
            10->dataV(b),11->(dataV(b)+e.n(s"binding.$b.bytes")),13->dataV(b),14->(dataV(b)+e.n(s"binding.$b.bytes"))))
          map(vRoot,dataV(b),dataP(b),0xc7)
          for(i<- -64 until 4160)memory(dataP(b)+i)=0xa5
          e.input(b).zipWithIndex.foreach {case(v,i)=>memory(dataP(b)+i)=v&255}
        }
        val uniformDescriptorLines=mutable.Set.empty[BigInt]
        val uniformDataLines=mutable.Map.empty[BigInt,Int]
        val uniformDataExpected=mutable.Map.empty[BigInt,Int].withDefaultValue(0)
        if(e.uniformEnabled) {
          map(vRoot,uniformRootV,uniformRootP,0x43);map(vRoot,uniformTableV,uniformTableP,0x43)
          val active=(1 to 12).filter(s=>e.uniform(s).nonEmpty)
          def packed(first: Int): BigInt=(0 until 6).map(i=>if(active.contains(first+i+1))BigInt(mappings(first+i))<<(5*i)else BigInt(0)).sum
          record(uniformRootP,Seq[(Int,BigInt)](0->ShaderUniformRootLayout.magic,1->64,2->1,
            3->e.n("context"),4->7,5->(epoch+1),6->2,7->active.map(s=>BigInt(1)<<(s-1)).sum,
            8->packed(0),9->packed(6),10->(if(active.nonEmpty)uniformTableV else BigInt(0)),
            11->(if(active.nonEmpty)uniformTableV+1536 else BigInt(0)),13->defaultRecordV,14->1))
          uniformDescriptorLines+=uniformRootP
          for(s<-0 until 13 if e.uniform(s).nonEmpty) {
            val at=if(s==0)defaultRecordP else uniformTableP+64*mappings(s-1)
            val logicalEnd=uniformV(s)+e.uniform(s).length
            record(at,Seq[(Int,BigInt)](0->ShaderUniformBindingLayout.magic,1->64,2->1,
              3->e.n("context"),4->7,5->(epoch+1),6->(if(s==0)31 else mappings(s-1)),7->4,
              8->uniformV(s),9->(logicalEnd&mask32),10->(logicalEnd>>32),11->uniformV(s),
              12->(logicalEnd&mask32),13->(logicalEnd>>32),14->1))
            uniformDescriptorLines+=at
            for(offset<-e.uniform(s).indices by 4096)map(vRoot,uniformV(s)+offset,uniformP(s)+offset,0x43)
            e.uniform(s).zipWithIndex.foreach {case(b,i)=>memory(uniformP(s)+i)=b&255}
            if(e.full)for(offset<-Seq(0,if(s==0)4032 else 16320))uniformDataExpected(uniformP(s)+offset)+=16
          }
          // Actual Mesa emits a vec4 indirect CONST load. Preserve all four
          // components even though the source result uses only its .y word.
          if(!e.full && e.healthy)for(i<-1 until 128 by 2)uniformDataExpected(uniformP(0)+((i*16)/64)*64)+=4
        }
        val expected=mutable.Map.empty[BigInt,BigInt]
        val sourceSlot=(0 until 4).find(b=>e.n(s"binding.$b.api")==0).get
        for(b<-0 until 4;i<-0 until e.items) {
          val api=e.n(s"binding.$b.api").toInt;var result=Option.empty[BigInt]
          if(e.full) {
            val a=checksum(e.n("epoch"));val changed=if(i%2==1)(a+17)&mask32 else a^0x77
            result=Some(if(api==0) {if(i%2==1)a^i else (a+i)&mask32} else changed^i^(api*0x10203))
          } else if(e.name=="nested" && api==0)
            result=Some(BigInt(if((i&8)==0)5 else if((i&4)==0)4 else if((i&2)==0)3 else if((i&1)==0)2 else 1))
          else if(e.healthy && e.name=="masked") {
            if(api==1) {val a=value(19,0,i*4+1);result=Some(if(i%2==0)BigInt(i+11)else if(a<BigInt("80000000",16)) (a+3)&mask32 else a^7)}
            if(api==2)result=Some(BigInt(if(i<31)"12345678"else"89abcdef",16))
          } else if(!e.healthy && api==1 && i%2==0)result=Some(BigInt(i+11))
          result.foreach(v=>expected(dataP(b)+i*4)=v&mask32)
        }
        val frozen=memory.toMap
        case class Reply(id: BigInt,address: BigInt,value: BigInt,due: Int,write: Option[(BigInt,BigInt,BigInt)])
        val pending=mutable.ArrayBuffer.empty[Reply];var offered=Option.empty[Reply]
        val outstanding=mutable.Set.empty[BigInt];var heldRequest=Option.empty[BigInt];var heldTrap=Option.empty[BigInt]
        val written=mutable.Set.empty[BigInt];val acknowledged=mutable.Set.empty[BigInt];val trapped=mutable.Set.empty[Int]
        val descriptors=mutable.Set.empty[Int];val inputLines=mutable.Set.empty[BigInt]
        var cycles=0;var warpStarts=0;var peakWarps=0;var priorActive=BigInt(0);var admission=false
        var heldTrapCycles=0;var requestStalls=0;var responseStalls=0;var walks=0;var codeReads=0;var kernargReads=0
        var storageReads=0;var uniformRootReads=0;var uniformBindingReads=0
        def drained: Boolean=pending.isEmpty && offered.isEmpty && outstanding.isEmpty && heldRequest.isEmpty &&
          !d.memoryRequest.valid.peek().litToBoolean && !d.memoryResponse.valid.peek().litToBoolean
        def tick(): Unit={
          assert(cycles<cycleBudget && totalCycles<totalBudget,s"${e.name} bounded cycle budget $cycles/$totalCycles")
          if(admission) {
            d.instructionSatp.expect(iSatp.U);d.vectorSatp.expect(vSatp.U)
            d.storageLease.get.expect(true.B);d.computeUniformLease.get.expect(e.uniformEnabled.B)
          }
          val active=d.active.peek().litValue;peakWarps=peakWarps.max(active.bitCount)
          warpStarts+=(active & ~priorActive).bitCount;priorActive=active
          d.globalAtomicRequest.valid.expect(false.B);d.texSample.valid.expect(false.B);d.vectorTexSample.valid.expect(false.B)
          if(d.trap.valid.peek().litToBoolean) {
            assert(!e.healthy);val t=d.trap.bits;val warp=t.warpId.peek().litValue.toInt
            t.pc.expect((pc+4*e.uniformWord).U);t.cause.expect(5.U);t.tval.expect(0.U);t.laneFaultMask.expect(2.U)
            val bits=t.peek().litValue;heldTrap.foreach(v=>assert(v==bits,"held trap changed"))
            d.trap.ready.poke((heldTrapCycles>=9).B)
            if(heldTrapCycles<9){heldTrap=Some(bits);heldTrapCycles+=1}
            else {assert(!trapped(warp));trapped+=warp;heldTrap=None}
          } else {assert(heldTrap.isEmpty,"held trap disappeared");d.trap.ready.poke(false.B)}
          d.memoryRequest.ready.poke((totalCycles%7!=0).B)
          if(offered.isEmpty && totalCycles%5!=0) {
            val ready=pending.indices.filter(i=>pending(i).due<=totalCycles)
            if(ready.nonEmpty)offered=Some(pending.remove(ready.maxBy(i=>pending(i).id)))
          }
          d.memoryResponse.valid.poke(offered.nonEmpty.B);d.memoryResponse.bits.fault.poke(false.B)
          offered.foreach(r=>{d.memoryResponse.bits.transactionId.poke(r.id.U);d.memoryResponse.bits.readData.poke(r.value.U)})
          if(offered.nonEmpty) {
            if(d.memoryResponse.ready.peek().litToBoolean) {
              val r=offered.get;assert(outstanding.remove(r.id))
              r.write.foreach {case(a,v,m)=>
                val first=m.lowestSetBit;assert(first>=0 && m==(BigInt(15)<<first))
                val word=a+first;assert(written(word) && !acknowledged(word));acknowledged+=word
                for(i<-0 until 64 if m.testBit(i))memory(a+i)=((v>>(8*i))&255).toInt
              };offered=None
            } else responseStalls+=1
          }
          if(d.memoryRequest.valid.peek().litToBoolean) {
            val q=d.memoryRequest.bits;val bits=q.peek().litValue
            heldRequest.foreach(v=>assert(v==bits,"held request changed"))
            if(d.memoryRequest.ready.peek().litToBoolean) {
              heldRequest=None
              val id=q.transactionId.peek().litValue;val a=q.address.peek().litValue
              val size=q.sizeLog2.peek().litValue.toInt;val wr=q.isWrite.peek().litToBoolean
              assert(!outstanding(id) && (id<4 || (id>=8 && id<=12)));outstanding+=id
              if(size==2){assert(!wr && (id<4 || id==10 || id==12) && (a&3)==0 && pageTables(a& ~BigInt(4095)));walks+=1}
              else {
                assert(size==6 && (a&63)==0)
                if(id==8){assert(!wr && a>=storageTableP && a<storageTableP+256);descriptors+=((a-storageTableP)/64).toInt}
                else if(id==9) {
                  q.cachePolicy.expect(CachePolicy.uncached)
                  if(wr) {
                    val m=q.byteMask.peek().litValue;val first=m.lowestSetBit;val word=a+first
                    assert(first>=0 && m==(BigInt(15)<<first) && expected.contains(word) && !written(word))
                    assert(((q.writeData.peek().litValue>>(8*first))&mask32)==expected(word));written+=word
                  }else {
                    assert((e.name=="masked" || !e.healthy) && a>=dataP(sourceSlot) && a<dataP(sourceSlot)+512)
                    inputLines+=a;storageReads+=1
                  }
                }else if(id==11) {
                  q.cachePolicy.expect(CachePolicy.uncached);assert(!wr && e.uniformEnabled)
                  if(a==uniformRootP)uniformRootReads+=1
                  else if(uniformDescriptorLines(a))uniformBindingReads+=1
                  else {assert(uniformDataExpected.contains(a),"inactive/invalid uniform reached data memory")
                    uniformDataLines(a)=uniformDataLines.getOrElse(a,0)+1}
                }else {
                  assert(id<4 && !wr)
                  if(a>=codeP && a<codeP+4096)codeReads+=1 else {assert(a==kernargP);kernargReads+=1}
                }
              }
              val write=if(wr)Some((a,q.writeData.peek().litValue,q.byteMask.peek().litValue))else None
              pending+=Reply(id,a,if(wr)BigInt(0)else read(a,if(size==2)4 else 64),totalCycles+(if(wr)17 else 3),write)
            }else{heldRequest=Some(bits);requestStalls+=1}
          }else assert(heldRequest.isEmpty,"held request withdrawn")
          top.clock.step();cycles+=1;totalCycles+=1
        }
        def until(label: String)(p: => Boolean): Unit=while(!p){assert(cycles<cycleBudget,s"${e.name}: $label");tick()}
        d.invalidateInstructionCache.poke(true.B);d.instructionTlbFlush.valid.poke(true.B);d.vectorTlbFlush.valid.poke(true.B);tick()
        d.invalidateInstructionCache.poke(false.B);d.instructionTlbFlush.valid.poke(false.B);d.vectorTlbFlush.valid.poke(false.B)
        d.instructionSatp.poke(iSatp.U);d.vectorSatp.poke(vSatp.U)
        val storage=d.storage.get;storage.enabled.poke(true.B);storage.table.version.poke(1.U)
        storage.table.base.poke(storageTableV.U);storage.table.limit.poke((storageTableV+256).U)
        storage.table.identity.context.poke(e.n("context").U);storage.table.identity.pass.poke(7.U)
        storage.table.identity.generation.poke((epoch+1).U)
        val uniforms=d.computeUniform.get;uniforms.enabled.poke(e.uniformEnabled.B)
        if(e.uniformEnabled) {
          uniforms.resources.version.poke(1.U);uniforms.resources.address.poke(uniformRootV.U)
          uniforms.identity.context.poke(e.n("context").U);uniforms.identity.pass.poke(7.U);uniforms.identity.generation.poke((epoch+1).U)
          uniforms.privateBase.poke(kernargV.U);uniforms.privateLimit.poke((kernargV+4096).U)
        }else zero(uniforms)
        d.kernel.bits.kernelPc.poke(pc.U);d.kernel.bits.kernargAddress.poke(kernargV.U)
        for(a<-0 until 3){d.kernel.bits.gridSize(a).poke(e.grid(a).U);d.kernel.bits.localSize(a).poke(e.local(a).U)}
        d.kernel.valid.poke(true.B);until("admission")(d.kernel.ready.peek().litToBoolean);tick()
        kernelStarts+=1;admission=true;d.kernel.valid.poke(false.B);zero(storage);zero(uniforms)
        until("completion")(d.completion.valid.peek().litToBoolean);d.completion.bits.success.expect(e.healthy.B)
        tick();assert(drained,"completion escaped offered/accepted memory drain")
        info(s"GALLIUM_CF_CU observed case=${e.name} peakWarps=$peakWarps warpStarts=$warpStarts walks=$walks codeReads=$codeReads kernargReads=$kernargReads writes=${written.size} acks=${acknowledged.size} descriptorSlots=${descriptors.toSeq.sorted.mkString(",")} storageReads=$storageReads uniformRootReads=$uniformRootReads uniformBindingReads=$uniformBindingReads uniformWordReads=${uniformDataLines.values.sum} traps=${trapped.size} requestStalls=$requestStalls cycles=$cycles")
        assert(peakWarps==8 && warpStarts==(e.grid.product*8).toInt && walks>0 && codeReads>0 && kernargReads==0 && requestStalls>0)
        assert(written.toSet==expected.keySet && acknowledged.toSet==expected.keySet && uniformDataLines.toMap==uniformDataExpected.toMap)
        val rawUniforms=e.checkedUniformLoads
        assert(uniformRootReads==rawUniforms && uniformBindingReads==rawUniforms)
        assert(storageReads==(if(e.name=="masked" || !e.healthy)64 else 0))
        val neededApis=if(e.full)Set(0,1,2,3)else if(e.name=="nested")Set(0)else if(e.healthy)Set(0,1,2)else Set(0,1)
        assert(descriptors.toSet==(0 until 4).filter(b=>neededApis(e.n(s"binding.$b.api").toInt)).toSet)
        if(storageReads>0)assert(inputLines.toSet==(0 until 8).map(w=>dataP(sourceSlot)+w*64).toSet)
        if(e.healthy)assert(trapped.isEmpty)else assert(trapped.toSet==(0 until 8).toSet && heldTrapCycles>=9)
        val golden=mutable.Map.empty[BigInt,Int]++frozen
        expected.foreach {case(a,v)=>for(i<-0 until 4)golden(a+i)=((v>>(8*i))&255).toInt}
        assert(memory.toMap==golden.toMap,"allocation guard, publication, input or output word changed")
        val held=d.completion.bits.peek().litValue;d.kernel.valid.poke(true.B)
        for(_<-0 until 13){
          d.completion.valid.expect(true.B);d.kernel.ready.expect(false.B);tick()
          d.completion.valid.expect(true.B);assert(d.completion.bits.peek().litValue==held && drained)
        }
        d.kernel.valid.poke(false.B);d.completion.ready.poke(true.B);tick();admission=false;d.completion.ready.poke(false.B)
        d.storageLease.get.expect(false.B);d.computeUniformLease.get.expect(false.B);assert(drained)
        allWarpStarts+=warpStarts
        info(s"GALLIUM_CF_CU case=${e.name} PASS codeWords=${e.codeWords} grid=${e.grid.mkString("x")} local=${e.local.mkString("x")} actualWarpStarts=$warpStarts lastWarpActive=${(e.local.product.toInt-1)%16+1} writes=${written.size} acks=${acknowledged.size} storageReads=$storageReads uniformWordReads=${uniformDataLines.values.sum} checkedUniformLoads=$rawUniforms traps=${trapped.size} heldCompletion=true stableActiveSatp=true responseHoldCycles=$responseStalls cycles=$cycles")
      }
      assert(kernelStarts==5 && allWarpStarts==56 && totalCycles<=totalBudget)
      info(s"GALLIUM_CF_CU PASS parents=5 workgroups=7 actualWarpStarts=$allWarpStarts healthyItems=762 nativeLanes=16 nativeWarps=8 realSram=true full13CBAnd4SSBO=true totalCycles=$totalCycles hostLinuxAPIQualified=false H7Complete=false")
    }
  }
}

object QualifyGalliumComputeControlFlow {
  def main(args: Array[String]): Unit={
    require(sys.props.get("opengpu.galliumComputeControlFlowQualification").contains("1"),"explicit local qualification only")
    require(args.length==6 && args(0)=="--artifacts" && args(2)=="--manifest-sha256" && args(4)=="--reports",
      "usage: --artifacts DIRECTORY --manifest-sha256 SHA256 --reports DIRECTORY")
    GalliumControlArtifacts.load(Paths.get(args(1)),args(3)) // before elaboration/cache lookup
    sys.props("opengpu.galliumControlArtifacts")=args(1);sys.props("opengpu.galliumControlArtifactSha256")=args(3)
    require(org.scalatest.tools.Runner.run(Array("-s",classOf[GalliumComputeControlFlowQualification].getName,"-oD","-u",args(5))),
      "Gallium compute control-flow qualification failed")
  }
}
