package opengpu.system

import chisel3._
import chisel3.reflect.DataMirror
import java.nio.file.{Files, Paths}
import java.util.Properties
import opengpu.command.GpuCommandOpcode
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.DoNotDiscover
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** Actual Mesa words and kernel-published records. Only bytes/PTEs are modeled. */
@DoNotDiscover
class GalliumTextureNamespaceQualification extends AnyFlatSpec {
  private def zero(data: Data): Unit = data match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s.poke(0.S)
    case _ => ()
  }
  private val mask32 = (BigInt(1) << 32) - 1
  private val names = Vector("full32", "low16", "middle16", "high16", "mixed16", "rgba")
  private def units(name: String): Vector[Int] = name match {
    case "full32" => (16 until 48).toVector
    case "low16" => (0 until 16).toVector
    case "middle16" => (16 until 32).toVector
    case "high16" => (32 until 48).toVector
    case "mixed16" => Vector.tabulate(16)(i => (17*i)%48)
    case "rgba" => Vector(47)
  }
  private def sample(epoch: Int, unit: Int, item: Int): BigInt =
    (BigInt(0x713a2b09L) ^ BigInt(epoch)*0x1020304 ^ BigInt(unit)*0x718293 ^ BigInt(item)*0x9137) & mask32
  private def expected(name: String, epoch: Int, api: Int, item: Int): BigInt = {
    if (name == "rgba") return (sample(epoch, 47, item) ^ BigInt(api)*0x11557799) & mask32
    var value = BigInt(0x713a2b09L)
    for ((unit,index) <- units(name).zipWithIndex) value = ((if (name == "full32") value else value*33) ^
      (if (name == "full32") BigInt(unit+8)<<(if(index%3==0)1+index%7 else 0) else sample(epoch,unit,item))) & mask32
    if (name == "full32") value = (value*33 ^ sample(epoch,47,item)) & mask32
    (value ^ BigInt(api)*0x10203) & mask32
  }

  it should "execute retained global texture remapping and preserve dead-result typed fetch faults on two CUs" in {
    require(sys.props.get("opengpu.galliumTextureNamespaceQualification").contains("1"), "explicit qualification only")
    val directory = Paths.get(sys.props.getOrElse("opengpu.galliumTextureNamespaceInputs",
      throw new IllegalArgumentException("supply actual Mesa/kernel publication inputs")))
    case class Input(name: String, epoch: Int) {
      val prefix = s"$name-epoch$epoch"
      val properties = new Properties
      val stream = Files.newInputStream(directory.resolve(s"$prefix.properties"))
      try properties.load(stream) finally stream.close()
      def n(key: String): Long = Option(properties.getProperty(key)).getOrElse(
        throw new IllegalArgumentException(s"missing $prefix $key")).toLong
      def bytes(suffix: String): Array[Byte] = Files.readAllBytes(directory.resolve(s"$prefix.$suffix"))
      require(n("active") == units(name).size && n("epoch") == epoch)
      require((0 until n("active").toInt).map(i => n(s"sampler.$i.api").toInt).toSet == units(name).toSet)
      require((0 until 4).map(i => n(s"storage.$i.api")).toSet == Set(0L,1L,2L,3L))
    }
    val inputs = (for (name <- names; epoch <- 1 to 2) yield Input(name,epoch)).toVector
    def words(bytes: Array[Byte]): Vector[BigInt] = {
      require(bytes.nonEmpty && bytes.length%4 == 0 && bytes.length <= 2048)
      bytes.grouped(4).map(b => (0 until 4).map(i => BigInt(b(i)&255) << (8*i)).sum).toVector
    }
    val dead = words(Files.readAllBytes(directory.resolve("unused-fetch.shader")))
    val bounds = words(Files.readAllBytes(directory.resolve("unused-fault.shader")))
    require(Seq(dead,bounds).forall(w => w.count(x => (x&127)==0x2b && (x>>26)==5)==1),
      "a dead result must retain its complete checked typed fetch")
    val config = GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2)
    simulate(new GpuSystem(config,numComputeUnits=2,commandIdWidth=4,transactionsPerCu=16,
      enableUnifiedCommands=true,storageAvailable=true,computeTextureAvailable=true,
      instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1)) { d =>
      zero(d.io);d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
      d.io.graphicsDrained.poke(true.B);d.io.graphicsShaderL1Invalidate.ready.poke(true.B)
      val memory = mutable.LongMap.empty[Int]
      def put(at: Long, value: BigInt): Unit = for (i <- 0 until 4) memory(at+i)=((value>>(8*i))&255).toInt
      def get(at: Long, count: Int = 4): BigInt =
        (0 until count).map(i => BigInt(memory.getOrElse(at+i,0xa5))<<(8*i)).sum
      var cycle=0;var totalTraps=0;var totalWrites=0;var totalOutputs=0
      case class Reply(id: BigInt, at: Long, data: BigInt, mask: BigInt, write: Boolean, fault: Boolean, due: Int)
      val pending=mutable.Queue.empty[Reply]
      val scenarios=inputs.map(i => (i,"healthy")) ++ Vector(
        (inputs.find(i => i.name=="full32" && i.epoch==1).get,"bounds"),
        (inputs.find(i => i.name=="rgba" && i.epoch==1).get,"tail-fault"),
        (inputs.find(i => i.name=="rgba" && i.epoch==2).get,"recover"))
      for (((input,mode),index) <- scenarios.zipWithIndex) {
        val phase=index+1;val root=0x01000000L+phase*0x100000L
        var nextPhysical=0x20000000L+phase*0x1000000L
        val pages=mutable.Map.empty[Long,Long];val levels=mutable.Map.empty[Long,Long]
        def blank(at: Long): Unit = for (i <- 0 until 4096) memory(at+i)=0
        blank(root)
        def page(virtual: Long,writable: Boolean): Long = {
          val vpn=virtual>>12
          val physical=pages.getOrElseUpdate(vpn,{val p=nextPhysical;nextPhysical+=4096;blank(p);p})
          val level=levels.getOrElseUpdate(virtual>>22,{
            val p=root+(levels.size+1)*4096L;blank(p);put(root+(virtual>>22)*4,(BigInt(p>>12)<<10)|1);p})
          put(level+((virtual>>12)&1023)*4,(BigInt(physical>>12)<<10)|(if(writable)0xc7 else 0x43))
          physical+(virtual&4095)
        }
        def translated(virtual: Long): Long = pages(virtual>>12)+(virtual&4095)
        def install(virtual: Long,bytes: Array[Byte],writable: Boolean=false): Unit =
          bytes.zipWithIndex.foreach { case(b,i) => memory(page(virtual+i,writable))=b&255 }
        val textureRoot=input.n("texture.root");val storageRoot=input.n("storage.root")
        install(textureRoot,input.bytes("textures"));install(storageRoot,input.bytes("storage"))
        for (segment <- 0 until input.n("segments").toInt) {
          val bytes=input.bytes(s"memory$segment");require(bytes.length==input.n(s"memory.$segment.bytes"))
          val slot=input.n(s"memory.$segment.slot").toInt
          install(input.n(s"memory.$segment.va"),bytes,slot>=35 && slot<=38)
        }
        val code=if(mode=="bounds")bounds else if(mode=="tail-fault")dead else words(input.bytes("shader"))
        val pc=0x1000L+(if(mode=="bounds")6 else if(mode=="tail-fault")7 else names.indexOf(input.name))*0x1000L
        for(i <- 0 until 512)put(pc+i*4,0)
        code.zipWithIndex.foreach { case(w,i) => put(pc+i*4,w) }
        require(code.contains(BigInt("30500073",16)))
        val firstFetch=code.indexWhere(w => (w&127)==0x2b && (w>>26)==5)
        val outputs=(0 until 4).map { native =>
          val at=storageRoot+native*64
          val base=get(translated(at+40)).toLong
          require(get(translated(at+44))==base+1024)
          (translated(base),input.n(s"storage.$native.api").toInt)
        }.toVector
        val imageTable=get(translated(textureRoot+44)).toLong
        val level=get(translated(imageTable+76)).toLong
        val texel=get(translated(level+48)).toLong
        val tailV=(texel & ~63L)+64;val tailP=translated(tailV)
        if(mode=="tail-fault")require((texel&63)==60,
          "component zero must fit before the line boundary; only later RGBA components cross it")
        val trapped=mutable.Set.empty[Int];val heldTraps=mutable.Map.empty[Int,(BigInt,Int)]
        val written=mutable.Set.empty[(Int,Int)]
        var offered=Option.empty[BigInt];var overlap=false;var writes=0;var faultedReads=0
        def tick(): Unit = {
          overlap ||= d.io.busyComputeUnits.peek().litValue==3
          d.io.memoryRequest.ready.poke((cycle%7<4).B)
          val reply=pending.headOption.filter(_.due<=cycle)
          d.io.memoryResponse.valid.poke(reply.nonEmpty.B)
          reply.foreach { r => d.io.memoryResponse.bits.transactionId.poke(r.id.U)
            d.io.memoryResponse.bits.readData.poke(r.data.U);d.io.memoryResponse.bits.fault.poke(r.fault.B) }
          if(reply.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean) {
            val r=pending.dequeue()
            if(r.write)for(i <- 0 until 64 if r.mask.testBit(i))memory(r.at+i)=((r.data>>(8*i))&255).toInt
          }
          val q=d.io.memoryRequest
          offered.foreach(bits => {q.valid.expect(true.B);assert(q.bits.peek().litValue==bits)})
          if(q.valid.peek().litToBoolean) {
            if(q.ready.peek().litToBoolean) {
              val at=q.bits.address.peek().litValue.toLong;val id=q.bits.transactionId.peek().litValue
              val write=q.bits.isWrite.peek().litToBoolean;val mask=q.bits.byteMask.peek().litValue
              val data=q.bits.writeData.peek().litValue
              assert(!pending.exists(_.id==id),"lower credit reused before acknowledgement")
              if(write) {
                assert(mode=="healthy"||mode=="recover","faulted unused fetch reached a younger store")
                val output=outputs.indexWhere { case(base,_) => at>=base && at<base+1024 }
                assert(output>=0,"read-only compiler publication/source modified")
                val byte=(0 until 64).find(mask.testBit).get
                assert(mask==(BigInt(15)<<byte) && byte%4==0)
                val item=((at+byte-outputs(output)._1)/4).toInt
                assert(item<8 && !written((output,item)));written+=((output,item));writes+=1
              }
              val fault=mode=="tail-fault" && !write && at==tailP
              if(fault)faultedReads+=1
              pending.enqueue(Reply(id,at,if(write)data else get(at,64),mask,write,fault,
                cycle+(if(write)37 else 7)));offered=None
            } else offered=Some(q.bits.peek().litValue)
          }
          for(cu <- 0 until 2) {
            val trap=d.io.trap(cu);trap.ready.poke(false.B)
            if(trap.valid.peek().litToBoolean) {
              assert(mode=="bounds"||mode=="tail-fault")
              val bits=trap.bits.peek().litValue;val held=heldTraps.getOrElseUpdate(cu,(bits,0))
              assert(held._1==bits);trap.bits.pc.expect((pc+firstFetch*4).U)
              // The existing texture owner attributes a failed vector commit
              // to its full active warp; it has no per-lane fault-mask port.
              trap.bits.laneFaultMask.expect(15.U);trap.bits.cause.expect(5.U)
              trap.bits.tval.expect((if(mode=="bounds")0L else tailV).U)
              if(held._2>=7){assert(!trapped(cu));trap.ready.poke(true.B);trapped+=cu}
              else heldTraps(cu)=(bits,held._2+1)
            }
          }
          d.clock.step();cycle+=1
        }
        def until(label: String)(condition: => Boolean): Unit = {
          val begin=cycle;while(!condition && cycle-begin<120000)tick()
          assert(condition,s"$label stalled ${input.prefix} $mode cycle=$cycle pending=${pending.size}")
        }
        d.io.vmContextIdle.expect(true.B);d.io.vectorSatp.poke((BigInt(1)<<31|BigInt(root>>12)).U)
        val command=d.io.gpuCommand.bits;zero(command)
        command.commandId.poke((phase%16).U);command.opcode.poke(GpuCommandOpcode.kernel)
        command.launch.kernelPc.poke(pc.U)
        for(axis <- 0 until 3) {command.launch.gridSize(axis).poke((if(axis==0)2 else 1).U)
          command.launch.localSize(axis).poke((if(axis==0)4 else 1).U)}
        val texture=command.computeTexture.get;texture.enabled.poke(true.B)
        texture.resources.version.poke(1.U);texture.resources.address.poke(textureRoot.U)
        texture.identity.context.poke(input.n("context").U);texture.identity.pass.poke(0.U);texture.identity.generation.poke(0.U)
        val storage=command.storage.get;storage.enabled.poke(true.B);storage.table.version.poke(1.U)
        storage.table.base.poke(storageRoot.U);storage.table.limit.poke((storageRoot+256).U)
        storage.table.identity.context.poke(input.n("context").U)
        storage.table.identity.pass.poke(0.U);storage.table.identity.generation.poke(0.U)
        d.io.gpuCommand.valid.poke(true.B);until("admission")(d.io.gpuCommand.ready.peek().litToBoolean)
        tick();d.io.gpuCommand.valid.poke(false.B);zero(command)
        until("completion")(d.io.gpuCompletion.valid.peek().litToBoolean)
        val success=mode=="healthy"||mode=="recover"
        d.io.gpuCompletion.bits.success.expect(success.B);d.io.gpuCompletion.bits.commandId.expect((phase%16).U)
        val held=d.io.gpuCompletion.bits.peek().litValue
        for(_ <- 0 until 9) {d.io.gpuCompletion.valid.expect(true.B);assert(d.io.gpuCompletion.bits.peek().litValue==held)
          d.io.storageLease.get.expect(true.B);d.io.computeTextureLease.get.expect(true.B);tick()}
        d.io.gpuCompletion.ready.poke(true.B);tick();d.io.gpuCompletion.ready.poke(false.B)
        until("drain")(d.io.vmContextIdle.peek().litToBoolean && pending.isEmpty)
        assert(offered.isEmpty && overlap)
        if(success) {
          assert(writes==32 && trapped.isEmpty)
          for((base,api) <- outputs;item <- 0 until 256)
            assert(get(base+item*4)==(if(item<8)expected(input.name,input.epoch,api,item)else BigInt("a5a5a5a5",16)),
              s"${input.prefix} $mode output api=$api item=$item")
          totalOutputs+=32
        } else {
          assert(writes==0 && trapped.size==2)
          if(mode=="tail-fault")assert(faultedReads>0)
          for((base,_) <- outputs;item <- 0 until 256)assert(get(base+item*4)==BigInt("a5a5a5a5",16))
        }
        for(_ <- 0 until 12){d.io.memoryRequest.valid.expect(false.B);tick()}
        totalTraps+=trapped.size;totalWrites+=writes
      }
      info(s"TEXTURE_NAMESPACE_L2_PASS globalUnits=48 nativeSlots=32 realMesaSelections=6 commands=${scenarios.size} outputs=$totalOutputs lowerWrites=$totalWrites preciseTraps=$totalTraps CUs=2 Sv32=true delayedWriteAck=37 fullRGBA=true unusedFetchBoundsAndTailFault=true noResetRecovery=true cycles=$cycle")
    }
  }
}

object QualifyGalliumTextureNamespace {
  def main(args: Array[String]): Unit = {
    val passed=org.scalatest.tools.Runner.run(Array(
      "-oD", "-s", "opengpu.system.GalliumTextureNamespaceQualification"))
    require(passed,"Gallium texture namespace qualification failed")
  }
}
