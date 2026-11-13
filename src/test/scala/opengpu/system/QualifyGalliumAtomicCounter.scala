package opengpu.system

import chisel3._
import chisel3.reflect.DataMirror
import java.nio.file.{Files,Paths}
import java.util.Properties
import opengpu.command.GpuCommandOpcode
import opengpu.config.GpuConfig
import opengpu.graphics.GraphicsStage
import opengpu.testutil.GpuSim._
import org.scalatest.DoNotDiscover
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** Unedited actual Mesa words and kernel records; only lower bytes/PTEs and
  * deliberate fault/permission inputs are modeled. No counter result is mocked. */
@DoNotDiscover
class GalliumAtomicCounterQualification extends AnyFlatSpec {
  private def zero(data:Data):Unit=data match {
    case r:Record=>r.elements.values.foreach(zero)
    case v:Vec[_]=>v.foreach(zero)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case _=>()
  }
  private val mask32=(BigInt(1)<<32)-1
  it should "execute real Mesa HW_ATOMIC counters with independent records on two CUs and shared L2" in {
    require(sys.props.get("opengpu.galliumAtomicCounterQualification").contains("1"),"explicit local qualification only")
    val directory=Paths.get(sys.props.getOrElse("opengpu.galliumAtomicCounterInputs",
      throw new IllegalArgumentException("supply actual Mesa/kernel publication inputs")))
    val names=Vector("increment","decrement","read","dynamic-increment","dynamic-decrement","dynamic-read","coexist","scalar")
    def word(bytes:Array[Byte],offset:Int):BigInt=(0 until 4).map(i=>BigInt(bytes(offset+i)&255)<<(8*i)).sum
    case class Input(name:String) {
      val properties=new Properties;val stream=Files.newInputStream(directory.resolve(s"$name.properties"))
      try properties.load(stream)finally stream.close()
      def n(key:String):Long=Option(properties.getProperty(key)).getOrElse(
        throw new IllegalArgumentException(s"missing $name $key")).toLong
      def bytes(suffix:String):Array[Byte]=Files.readAllBytes(directory.resolve(s"$name.$suffix"))
      val code=bytes("shader").grouped(4).map(b=>word(b,0)).toVector
      val function=if(name.endsWith("read"))17 else if(name.endsWith("decrement"))19 else 18
      val counterPc=code.indexWhere(w=>(w&127)==0x2b&&(w>>26)==function)
      require(code.nonEmpty&&code.size<=512&&counterPc>=0&&code.count(w=>(w&127)==0x2b&&Set(17,18,19)((w>>26).toInt))==1)
      val cease=code.indexOf(BigInt("30500073",16));require(cease>=0&&code.drop(cease+1).forall(_==0))
      val counter=bytes("counters");require(counter.length==64&&word(counter,0)==BigInt("47534201",16))
      require(word(counter,12)==n("context")&&word(counter,28)==1&&word(counter,32)==1&&word(counter,36)==1)
    }
    val inputs=names.map(name=>name->Input(name)).toMap
    val config=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2)
    simulate(new GpuSystem(config,numComputeUnits=2,commandIdWidth=4,transactionsPerCu=16,
      enableUnifiedCommands=true,storageAvailable=true,imageAvailable=true,
      computeUniformAvailable=true,computeTextureAvailable=true,atomicCounterAvailable=true,
      instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
      d.io.graphicsDrained.poke(true.B);d.io.graphicsShaderL1Invalidate.ready.poke(true.B)
      val memory=mutable.LongMap.empty[Int]
      def put(at:Long,value:BigInt):Unit=for(i<-0 until 4)memory(at+i)=((value>>(8*i))&255).toInt
      def get(at:Long,count:Int=4):BigInt=(0 until count).map(i=>BigInt(memory.getOrElse(at+i,0xa5))<<(8*i)).sum
      case class Reply(id:BigInt,at:Long,data:BigInt,mask:BigInt,write:Boolean,fault:Boolean,due:Int)
      val pending=mutable.Queue.empty[Reply]
      var cycle=0;var totalCommands=0;var totalTraps=0;var totalAmoWrites=0;var totalOutputs=0
      val scenarios=names.map(_->"healthy")++Vector("increment","decrement","read").flatMap(name=>
        Vector(name->"read-fault",name->"recover",name->"write-fault",name->"recover",name->"page-denied",name->"recover",name->"readonly",name->"recover"))++
        Vector("dynamic-increment"->"bounds","increment"->"recover")
      for(((name,mode),index)<-scenarios.zipWithIndex){
        val input=inputs(name);val phase=index+1;val root=0x01000000L+phase*0x100000L
        var nextPhysical=0x20000000L+phase*0x1000000L
        val pages=mutable.Map.empty[Long,Long];val levels=mutable.Map.empty[Long,Long]
        def blank(at:Long):Unit=for(i<-0 until 4096)memory(at+i)=0
        blank(root)
        def page(virtual:Long,writable:Boolean):Long={
          val physical=pages.getOrElseUpdate(virtual>>12,{val p=nextPhysical;nextPhysical+=4096;blank(p);p})
          val level=levels.getOrElseUpdate(virtual>>22,{
            val p=root+(levels.size+1)*4096L;blank(p);put(root+(virtual>>22)*4,(BigInt(p>>12)<<10)|1);p})
          put(level+((virtual>>12)&1023)*4,(BigInt(physical>>12)<<10)|(if(writable)0xc7 else 0x43))
          physical+(virtual&4095)
        }
        def translated(virtual:Long):Long=pages(virtual>>12)+(virtual&4095)
        def install(virtual:Long,bytes:Array[Byte],writable:Boolean=false):Unit=
          bytes.zipWithIndex.foreach{case(b,i)=>memory(page(virtual+i,writable))=b&255}
        val storageRoot=input.n("storage.root");val counterRoot=input.n("counter.root")
        install(storageRoot,input.bytes("storage"));install(counterRoot,input.counter)
        if(mode=="readonly")put(translated(counterRoot+36),0) // Deliberately invalid RW view, never a claimed kernel publication.
        if(input.n("image.root")!=0)install(input.n("image.root"),input.bytes("images"))
        if(input.n("uniform.root")!=0)install(input.n("uniform.root"),input.bytes("uniforms"))
        val segments=(0 until input.n("segments").toInt).map{s=>
          val bytes=input.bytes(s"memory$s");require(bytes.length==input.n(s"memory.$s.bytes"))
          val va=input.n(s"memory.$s.va");val slot=input.n(s"memory.$s.slot").toInt
          install(va,bytes,slot>=35);(va,slot,bytes.toVector)}.toVector
        val counterBase=word(input.counter,40).toLong;val counterCount=input.n("counter.count").toInt
        require(word(input.counter,44)==counterBase+counterCount*4&&word(input.counter,48)==0)
        val originalCounter=input.bytes("counter-data");require(originalCounter.length==input.n("counter.GEMbytes"))
        val originalVirtual=counterBase-input.n("counter.offset")
        install(originalVirtual,originalCounter,writable=true)
        if(mode=="page-denied")for(i<-0 until counterCount)page(counterBase+i*4,writable=false)
        if(mode=="bounds"){
          val uniformRoot=input.n("uniform.root");val binding=get(translated(uniformRoot+40)).toLong
          val value=get(translated(binding+32)).toLong
          put(translated(value),8) // Real immutable UBO payload supplied to the unchanged dynamic shader.
        }
        val pc=0x1000L+names.indexOf(name)*0x1000L
        input.code.zipWithIndex.foreach{case(w,i)=>put(pc+i*4,w)}
        val expected=mutable.Map.empty[Long,BigInt]
        for(i<-0 until counterCount)expected(translated(counterBase+i*4))=get(translated(counterBase+i*4))
        val counterLines=expected.keys.map(_ & ~63L).toSet
        val outputs=segments.filter(_._2>=35).map{case(va,slot,original)=>(translated(va),slot,original)}
        val counterVisits=mutable.Map.empty[Long,Int].withDefaultValue(0)
        val returned=mutable.ArrayBuffer.empty[BigInt];val written=mutable.Set.empty[(Int,Int)]
        val trapped=mutable.Set.empty[Int];val heldTraps=mutable.Map.empty[Int,(BigInt,Int)]
        var offered=Option.empty[BigInt];var overlap=false;var amoWrites=0;var faultInjected=false;var deniedReads=0
        def update(old:BigInt):BigInt=(if(input.function==18)old+1 else if(input.function==19)old-1 else old)&mask32
        def tick():Unit={
          overlap ||= d.io.busyComputeUnits.peek().litValue==3
          d.io.memoryRequest.ready.poke((cycle%7<4).B)
          val reply=pending.headOption.filter(_.due<=cycle);d.io.memoryResponse.valid.poke(reply.nonEmpty.B)
          reply.foreach{r=>d.io.memoryResponse.bits.transactionId.poke(r.id.U)
            d.io.memoryResponse.bits.readData.poke(r.data.U);d.io.memoryResponse.bits.fault.poke(r.fault.B)}
          if(reply.nonEmpty&&d.io.memoryResponse.ready.peek().litToBoolean){val r=pending.dequeue()
            if(r.write&& !r.fault){
              if(counterLines(r.at)){
                val byte=(0 until 64).find(r.mask.testBit).get;val at=r.at+byte
                val old=expected(at);expected(at)=update(old);counterVisits(at)+=1
                returned+=(if(input.function==19)expected(at)else old)
              }
              for(i<-0 until 64 if r.mask.testBit(i))memory(r.at+i)=((r.data>>(8*i))&255).toInt
            }
          }
          val q=d.io.memoryRequest
          offered.foreach(bits=>{q.valid.expect(true.B);assert(q.bits.peek().litValue==bits)})
          if(q.valid.peek().litToBoolean){
            if(q.ready.peek().litToBoolean){
              val at=q.bits.address.peek().litValue.toLong;val id=q.bits.transactionId.peek().litValue
              val write=q.bits.isWrite.peek().litToBoolean;val mask=q.bits.byteMask.peek().litValue;val data=q.bits.writeData.peek().litValue
              assert(!pending.exists(_.id==id),"lower ID reused before acknowledgement")
              val counter=counterLines(at)
              if(counter&&(mode=="page-denied"||mode=="bounds"||mode=="readonly"))deniedReads+=1
              if(write){
                val byte=(0 until 64).find(mask.testBit).get
                assert(mask==(BigInt(15)<<byte)&&byte%4==0,"counter/storage write escaped a word mask")
                if(counter){
                  assert(!pending.exists(r=>r.write&&r.at==at),"same-line RMW bypassed delayed acknowledgement")
                  assert(expected.contains(at+byte));assert(((data>>(8*byte))&mask32)==update(expected(at+byte)))
                  amoWrites+=1
                }else{
                  val output=outputs.indexWhere{case(base,_,original)=>at>=base&&at<base+original.size}
                  assert(output>=0,"read-only publication or unrelated backing modified")
                  val item=((at+byte-outputs(output)._1)/4).toInt
                  assert(item<8&& !written((output,item)));written+=((output,item))
                }
              }
              val fault=counter&& !faultInjected&&((mode=="read-fault"&& !write)||(mode=="write-fault"&&write))
              if(fault)faultInjected=true
              pending.enqueue(Reply(id,at,if(write)data else get(at,64),mask,write,fault,cycle+(if(write)37 else 7)))
              offered=None
            }else offered=Some(q.bits.peek().litValue)
          }
          for(cu<-0 until 2){val trap=d.io.trap(cu);trap.ready.poke(false.B)
            if(trap.valid.peek().litToBoolean){
              assert(mode!="healthy"&&mode!="recover");val bits=trap.bits.peek().litValue
              val held=heldTraps.getOrElseUpdate(cu,(bits,0));assert(held._1==bits)
              trap.bits.pc.expect((pc+input.counterPc*4).U);trap.bits.laneFaultMask.expect(1.U)
              val read=input.function==17
              trap.bits.cause.expect((if(mode=="page-denied"){if(read)13 else 15}else if(read)5 else 7).U)
              trap.bits.tval.expect((if(mode=="bounds"||mode=="readonly")0L else counterBase+28).U)
              if(held._2>=7){assert(!trapped(cu));trap.ready.poke(true.B);trapped+=cu}else heldTraps(cu)=(bits,held._2+1)
            }
          }
          d.clock.step();cycle+=1
        }
        def until(label:String)(condition: => Boolean):Unit={val begin=cycle
          while(!condition&&cycle-begin<120000)tick()
          assert(condition,s"$label stalled $name $mode phase=$phase cycle=$cycle pending=${pending.size}")}
        d.io.vmContextIdle.expect(true.B);d.io.vectorSatp.poke(((BigInt(1)<<31)|BigInt(root>>12)).U)
        val command=d.io.gpuCommand.bits;zero(command);command.commandId.poke((phase%16).U);command.opcode.poke(GpuCommandOpcode.kernel)
        command.launch.kernelPc.poke(pc.U)
        for(axis<-0 until 3){command.launch.gridSize(axis).poke((if(axis==0)2 else 1).U);command.launch.localSize(axis).poke((if(axis==0)4 else 1).U)}
        val storage=command.storage.get;storage.enabled.poke(true.B);storage.table.version.poke(1.U)
        storage.table.base.poke(storageRoot.U);storage.table.limit.poke((storageRoot+256).U);storage.table.identity.context.poke(input.n("context").U)
        val counter=command.atomicCounter.get;counter.enabled.poke(true.B);counter.table.version.poke(1.U)
        counter.table.base.poke(counterRoot.U);counter.table.limit.poke((counterRoot+64).U);counter.table.identity.context.poke(input.n("context").U)
        if(input.n("image.root")!=0){val image=command.images.get;image.enabled.poke(true.B);image.stage.poke(GraphicsStage.compute.U)
          image.table.version.poke(1.U);image.table.base.poke(input.n("image.root").U);image.table.limit.poke((input.n("image.root")+1024).U)
          image.table.identity.context.poke(input.n("context").U)}
        if(input.n("uniform.root")!=0){val uniform=command.computeUniform.get;uniform.enabled.poke(true.B);uniform.resources.version.poke(1.U)
          uniform.resources.address.poke(input.n("uniform.root").U);uniform.identity.context.poke(input.n("context").U)}
        d.io.gpuCommand.valid.poke(true.B);until("admission")(d.io.gpuCommand.ready.peek().litToBoolean);tick()
        d.io.gpuCommand.valid.poke(false.B);zero(command)
        until("completion")(d.io.gpuCompletion.valid.peek().litToBoolean)
        val success=mode=="healthy"||mode=="recover"
        d.io.gpuCompletion.bits.success.expect(success.B);d.io.gpuCompletion.bits.commandId.expect((phase%16).U)
        val held=d.io.gpuCompletion.bits.peek().litValue
        for(_<-0 until 9){d.io.gpuCompletion.valid.expect(true.B);assert(d.io.gpuCompletion.bits.peek().litValue==held)
          d.io.storageLease.get.expect(true.B);d.io.atomicCounterLease.get.expect(true.B)
          if(input.n("image.root")!=0)d.io.imageLease.get.expect(true.B)
          if(input.n("uniform.root")!=0)d.io.computeUniformLease.get.expect(true.B);tick()}
        d.io.gpuCompletion.ready.poke(true.B);tick();d.io.gpuCompletion.ready.poke(false.B)
        until("drain")(d.io.vmContextIdle.peek().litToBoolean&&pending.isEmpty)
        assert(offered.isEmpty&&overlap)
        if(success){
          assert(amoWrites==8&&trapped.isEmpty&&returned.size==8)
          // The intended source indices are independent of the observed RMW
          // addresses. A wrong index/permutation must not redefine the oracle.
          for(i<-0 until counterCount){
            val visits=if(name.startsWith("dynamic-"))1 else if(i==(if(name=="scalar")0 else 7))8 else 0
            assert(counterVisits(translated(counterBase+i*4))==visits,s"$name $mode counter index $i access count")
          }
        }
        else if(mode=="read-fault"||mode=="write-fault")assert(faultInjected&&trapped.size==1&&returned.size==4)
        else assert(amoWrites==0&&written.isEmpty&&trapped.size==2&&deniedReads==0)
        for((at,value)<-expected)assert(get(at)==value,s"$name $mode final counter mismatch")
        for((base,slot,original)<-outputs){
          val output=outputs.indexWhere(_._2==slot);val actual=written.filter(_._1==output).toVector.map{case(_,item)=>get(base+item*4)}
          assert(actual.sorted==returned.sorted,s"$name $mode slot=$slot returned value multiset mismatch")
          if(success&&name.startsWith("dynamic-"))for(item<-0 until 8){
            val initial=word(originalCounter,input.n("counter.offset").toInt+item*4)
            val want=if(input.function==19)(initial-1)&mask32 else initial
            assert(get(base+item*4)==want,s"$name $mode slot=$slot lane/item=$item exact return mismatch")
          }
          for(i<-original.indices if !written((output,i/4)))assert(memory(base+i)==(original(i)&255),s"$name $mode output guard")
        }
        for(i<-originalCounter.indices if !expected.keys.exists(at=>translated(originalVirtual+i)>=at&&translated(originalVirtual+i)<at+4))
          assert(memory(translated(originalVirtual+i))==(originalCounter(i)&255),s"$name $mode counter guard")
        for(_<-0 until 12){d.io.memoryRequest.valid.expect(false.B);tick()}
        d.io.atomicCounterLease.get.expect(false.B);d.io.storageLease.get.expect(false.B)
        totalCommands+=1;totalTraps+=trapped.size;totalAmoWrites+=amoWrites;totalOutputs+=written.size
      }
      info(s"MESA_COUNTER_L2_PASS operations=3 commands=$totalCommands traps=$totalTraps atomicWrites=$totalAmoWrites outputs=$totalOutputs CUs=2 Sv32=true delayedAck=37 exactReturns=true independentCounter=true fourSsboFourImages=true noResetRecovery=true cycles=$cycle")
    }
  }
}
object QualifyGalliumAtomicCounter {
  def main(args:Array[String]):Unit={
    require(args.isEmpty,"supply actual inputs via opengpu.galliumAtomicCounterInputs")
    require(org.scalatest.tools.Runner.run(Array("-s",classOf[GalliumAtomicCounterQualification].getName,"-oD")),
      "actual Mesa atomic-counter qualification failed")
  }
}
