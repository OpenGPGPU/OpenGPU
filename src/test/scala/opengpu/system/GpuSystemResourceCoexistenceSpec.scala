package opengpu.system

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.command.GpuCommandOpcode
import opengpu.config.{GpuConfig, MobileGpuConfig}
import opengpu.graphics.TextureImageFormat
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class GpuSystemResourceCoexistenceSpec extends AnyFlatSpec {
  protected def fullProfile: Boolean = false
  protected def textureEnabled: Boolean = false
  protected def counterEnabled: Boolean = false
  private def zero(data: Data): Unit = data match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s.poke(0.S)
    case _ => ()
  }
  private def uniform(rd: Int, vs1: Int, vs2: Int): BigInt =
    (BigInt(7) << 26) | (BigInt(1) << 25) | (BigInt(vs2) << 20) |
      (BigInt(vs1) << 15) | (BigInt(rd) << 7) | 0x2b
  private def storage(fn: Int, rd: Int = 0, vs1: Int = 0, vs2: Int = 0): BigInt =
    (BigInt(fn) << 26) | (BigInt(1) << 25) | (BigInt(vs2) << 20) |
      (BigInt(vs1) << 15) | (BigInt(rd) << 7) | 0x2b
  private def image(fn: Int, pair: Int = 0, vs1: Int = 0, vs2: Int = 0): BigInt =
    (BigInt(fn) << 26) | (BigInt(1) << 25) | (BigInt(vs2) << 20) |
      (BigInt(vs1) << 15) | (BigInt(pair) << 12) | 0x2b
  private def vector(fn: Int, form: Int, rd: Int, vs1: Int, vs2: Int = 0): BigInt =
    (BigInt(fn) << 26) | (BigInt(1) << 25) | (BigInt(vs2) << 20) |
      (BigInt(vs1) << 15) | (BigInt(form) << 12) | (BigInt(rd) << 7) | 0x57

  it should "execute uniform, storage and image resources together in direct and indirect kernels" in {
    val config = if(fullProfile) MobileGpuConfig().execution else GpuConfig(lanes = 4, warps = 2, l2Sets = 8, l2Ways = 2)
    val cuCount=if(fullProfile) 4 else 2
    val threads=if(fullProfile) config.lanes*config.warps else config.lanes
    val warps=threads/config.lanes
    val groups=if(fullProfile) 4 else 3
    simulate(new GpuSystem(config, numComputeUnits = cuCount, commandIdWidth = 4,
      transactionsPerCu = 16, enableUnifiedCommands = true, enableIndirectCompute = true, storageAvailable = true, imageAvailable = true, computeUniformAvailable = true, computeTextureAvailable = textureEnabled, atomicCounterAvailable = counterEnabled,
      instructionCacheSets = (if(fullProfile) 64 else 4), instructionCacheWays = (if(fullProfile) 2 else 1),
      vectorCacheSets = (if(fullProfile) 64 else 4), vectorCacheWays = (if(fullProfile) 2 else 1))) { d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      d.io.graphicsDrained.poke(true.B)
      d.io.graphicsShaderL1Invalidate.ready.poke(true.B)
      d.io.trap.foreach(_.ready.poke(true.B))
      d.io.memoryRequest.ready.poke(true.B)
      val memory = mutable.Map.empty[BigInt, Int]
      def put(a: BigInt, value: BigInt): Unit =
        for(i <- 0 until 4) memory(a + i) = ((value >> (8 * i)) & 255).toInt
      def get(a: BigInt, bytes: Int = 4): BigInt =
        (0 until bytes).map(i => BigInt(memory.getOrElse(a + i, 0xa5)) << (8 * i)).sum
      val format=TextureImageFormat.id("RGBA32UI")
      val control=(format << 6) | 40
      val textureValues=mutable.Map.empty[BigInt,(Int,Int)]
      for(id <- 1 to 2) {
        val root = 0x6000 + id * 0x2000; val table = root + 64
        def record(address: Int, magic: BigInt, fields: Seq[(Int,BigInt)]): Unit = {
          val words = Array.fill[BigInt](16)(0)
          words(0)=magic; words(1)=64; words(2)=1; words(3)=7; words(4)=11; words(5)=13
          fields.foreach { case(i,v) => words(i)=v }
          words.zipWithIndex.foreach { case(v,i) => put(address+i*4,v) }
        }
        val low=(0 until 6).map(i=>BigInt(i)<<(5*i)).sum
        val high=(0 until 6).map(i=>BigInt(i+6)<<(5*i)).sum
        val defaultView=table+1536; val defaultData=0x1f000+id*0x1000
        record(root,BigInt("47555201",16),Seq(6->2,7->0xfff,8->low,9->high,10->table,11->(table+1536),13->defaultView,14->1))
        record(defaultView,BigInt("47554201",16),Seq(6->31,7->4,8->defaultData,9->(defaultData+4096),
          11->defaultData,12->(defaultData+4096),14->1))
        put(defaultData+4092,id*100+77)
        for (block <- 0 until 12) {
          val data=0x20000+id*0x40000+block*0x4000
          record(table+block*64,BigInt("47554201",16),Seq(6->block,7->4,8->data,9->(data+16384),
            11->data,12->(data+16384),14->1))
          put(data,id*20+block)
          put(data+16380,id*20+block)
        }
        if(counterEnabled) {
          val counterTable=0x17000+id*64;val counterData=0x1a0000+id*0x1000
          record(counterTable,BigInt("47534201",16),Seq(6->0,7->1,8->1,9->1,
            10->counterData,11->(counterData+32),13->counterData,14->(counterData+64)))
          for(index<-0 until 8) put(counterData+index*4,id*100000+index*10000)
        }
        val storageTable=0x18000+id*0x1000
        val imageTable=0x1b000+id*0x1000
        for (unit <- 0 until 4) {
          val storageData=0x120000+id*0x10000+unit*0x1000
          val imageData=0x150000+id*0x10000+unit*0x1000
          record(storageTable+unit*64,BigInt("47534201",16),Seq(6->unit,7->1,8->1,9->1,
            10->storageData,11->(storageData+4096),13->storageData,14->(storageData+4096)))
          val words=Array.fill[BigInt](64)(0)
          for((i,v)<-Seq[(Int,BigInt)](0->BigInt("47495501",16),1->256,2->1,3->7,4->11,5->13,
            6->unit,7->1,8->1,9->4,10->1,11->1,12->format,13->format,14->1,17->15,18->1,
            22->imageData,23->(imageData+4096),25->(imageData+63),26->threads,27->1,28->1,29->1,30->(threads*16),37->16)) words(i)=v
          words.zipWithIndex.foreach{case(v,i)=>put(imageTable+unit*256+i*4,v)}
          for(i<-0 until 4096) { memory(BigInt(storageData+i))=0xa5; memory(BigInt(imageData+i))=0xa5 }
        }
        val textureOffset=BigInt(id)*0x400000
        if(textureEnabled) {
          val fixture=opengpu.graphics.ComputeTextureCoexistenceFixture
          def byte(a:BigInt,v:Int):Unit={memory(a)=v}
          fixture.roots(put,offset=textureOffset)
          fixture.coordinates(put,offset=textureOffset)
          for(unit<-0 until 16) {
            val view=fixture.value(put,byte,unit,offset=textureOffset,
              raw=BigInt(0xff102000L+id*32+unit))
            assert(!textureValues.contains(view))
            textureValues(view)=(id,unit)
          }
        }
        val program=Vector(BigInt("0104f057",16),BigInt("00004537",16),BigInt("ffc50513",16),vector(23,4,2,10)) ++ (0 until 12).flatMap { block =>
          Vector(vector(23,3,3,block),uniform(10+block,3,2))
        } ++ Vector(BigInt("01f00513",16),vector(23,4,3,10),BigInt("000015b7",16),
          BigInt("ffc58593",16),vector(23,4,2,11),uniform(26,3,2),vector(23,3,2,0)) ++
          (0 until 4).flatMap { unit =>
          Vector(vector(23,3,3,unit),vector(23,3,4,id*2+unit),storage(14,vs1=4),
            storage(16,vs1=3,vs2=2),storage(15,rd=22+unit,vs1=3,vs2=2))
        } ++ Vector(BigInt(((control+0x800)& ~0xfff) | (10 << 7) | 0x37),
          BigInt(((control & 0xfff).toLong << 20) | (10 << 15) | (10 << 7) | 0x13),vector(23,4,3,10)) ++
          (0 until 4).flatMap { unit =>
            Vector(vector(23,3,8,unit),image(8,vs1=8,vs2=3),image(8,pair=1,vs1=2,vs2=2)) ++
              (0 until 4).map(c=>vector(0,3,4+c,id*2+unit*2+c,1)) ++
              Vector(image(8,pair=2,vs1=4,vs2=5),image(8,pair=3,vs1=6,vs2=7),image(10,vs1=1,vs2=2))
          } ++ (if(textureEnabled) {
            val packet=opengpu.graphics.ComputeTextureCoexistenceFixture.packet+textureOffset
            Vector((packet & ~BigInt(0xfff)) | (BigInt(10)<<7) | 0x37,vector(23,4,2,10)) ++
              (0 until 16).flatMap(unit=>Vector(vector(23,3,3,unit),storage(2,rd=27,vs1=2,vs2=3)))
          } else Vector.empty) ++ (if(counterEnabled) {
            (0 until 8).flatMap(index=>Vector(vector(23,3,2,index),storage(18,rd=28,vs1=2)))
          } else Vector.empty) ++ Vector(BigInt("30500073",16))
        program.zipWithIndex.foreach { case(v,i) => put(id*0x1000+i*4,v) }
      }
      // The second command obtains its real grid dimensions from GPU memory.
      // Contradictory submitted dimensions below must never reach a CU.
      put(0x3000, groups); put(0x3004, 1); put(0x3008, 1)
      var indirectArgumentReads = 0
      case class Reply(id: BigInt, address: BigInt, data: BigInt, mask: BigInt,
                       write: Boolean, due: Int)
      val pending = mutable.Queue.empty[Reply]
      var cycle = 0
      var writes = 0
      val recentRequests = mutable.Queue.empty[(BigInt,BigInt,Boolean)]
      var simultaneous = false
      val loaded = mutable.Map.empty[(Int,Int),Int].withDefaultValue(0)
      val storageLoaded = mutable.Map.empty[(Int,Int),Int].withDefaultValue(0)
      val defaultLoaded = mutable.Map.empty[Int,Int].withDefaultValue(0)
      val textureLoaded = mutable.Map.empty[(Int,Int),Int].withDefaultValue(0)
      val counterValues = mutable.Map.empty[(Int,Int),mutable.ArrayBuffer[BigInt]]
      val completions = mutable.Set.empty[Int]
      def tick(): Unit = {
        simultaneous ||= d.io.busyComputeUnits.peek().litValue == (BigInt(1)<<cuCount)-1
        for(cu <- 0 until cuCount) {
          val wb = d.io.committedVectorWriteback(cu)
          if(wb.valid.peek().litToBoolean) {
            val rd = wb.bits.vd.peek().litValue.toInt
            if(rd >= 10 && rd < 22) {
              val unit = rd - 10; val value = wb.bits.data(0).peek().litValue.toInt
              assert(value == 20 + unit || value == 40 + unit)
              for(lane <- 0 until config.lanes) wb.bits.data(lane).expect(value.U)
              loaded((unit,value)) += 1
            }
            if(rd >= 22 && rd < 26) {
              val unit=rd-22; val value=wb.bits.data(0).peek().litValue.toInt
              assert(value==2+unit || value==4+unit)
              for(lane<-0 until config.lanes) wb.bits.data(lane).expect(value.U)
              storageLoaded((unit,value))+=1
            }
            if(rd==26) {
              val value=wb.bits.data(0).peek().litValue.toInt
              assert(value==177 || value==277)
              for(lane<-0 until config.lanes) wb.bits.data(lane).expect(value.U)
              defaultLoaded(value)+=1
            }
            if(counterEnabled && rd==28) {
              for(lane<-0 until config.lanes) {
                val value=wb.bits.data(lane).peek().litValue;val id=(value/100000).toInt
                val index=((value%100000)/10000).toInt
                assert(Set(1,2)(id) && index>=0 && index<8,s"unexpected counter result $value")
                counterValues.getOrElseUpdate((id,index),mutable.ArrayBuffer.empty[BigInt])+=value
              }
            }
            if(textureEnabled && rd==27) {
              val value=wb.bits.data(0).peek().litValue
              assert(textureValues.contains(value),s"unexpected texture value $value")
              for(lane<-0 until config.lanes)wb.bits.data(lane).expect(value.U)
              textureLoaded(textureValues(value))+=1
            }
          }
        }
        d.io.memoryRequest.ready.poke((cycle % 5 != 0).B)
        val reply = pending.headOption.filter(_.due <= cycle)
        d.io.memoryResponse.valid.poke(reply.nonEmpty.B)
        reply.foreach { r =>
          d.io.memoryResponse.bits.transactionId.poke(r.id.U)
          d.io.memoryResponse.bits.readData.poke(r.data.U)
        }
        if(reply.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean) {
          val r = pending.dequeue()
          if(r.write) for(i <- 0 until 64 if r.mask.testBit(i))
            memory(r.address + i) = ((r.data >> (8 * i)) & 255).toInt
        }
        if(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean) {
          val q = d.io.memoryRequest.bits
          val a = q.address.peek().litValue; val write = q.isWrite.peek().litToBoolean
          recentRequests.enqueue((a,q.transactionId.peek().litValue,write))
          if(recentRequests.size>20) recentRequests.dequeue()
          if(!write && a == 0x3000) indirectArgumentReads += 1
          if(write) writes += 1
          pending.enqueue(Reply(q.transactionId.peek().litValue, a,
            if(write) q.writeData.peek().litValue else get(a, 64), q.byteMask.peek().litValue,
            write, cycle + (if(write) 19 else 3)))
        }
        if(d.io.gpuCompletion.valid.peek().litToBoolean && d.io.gpuCompletion.ready.peek().litToBoolean) {
          d.io.gpuCompletion.bits.success.expect(true.B)
          val id = d.io.gpuCompletion.bits.commandId.peek().litValue.toInt
          assert(!completions(id)); completions += id
        }
        d.io.trap.zipWithIndex.foreach { case(t,cu) =>
          assert(!t.valid.peek().litToBoolean,
            s"CU $cu trap cycle=$cycle pc=0x${t.bits.pc.peek().litValue.toString(16)} " +
              s"warp=${t.bits.warpId.peek().litValue} lanes=0x${t.bits.laneFaultMask.peek().litValue.toString(16)} " +
              s"cause=${t.bits.cause.peek().litValue} tval=0x${t.bits.tval.peek().litValue.toString(16)} " +
              s"writes=$writes recentRequests=${recentRequests.mkString(",")}")
        }
        d.clock.step(); cycle += 1
      }
      def until(label: String)(condition: => Boolean): Unit = {
        var n = 0
        while(!condition && n < 1000000) { tick(); n += 1 }
        assert(condition, s"$label stalled after $cycle cycles")
      }
      for(id <- 1 to 2) {
        val command = d.io.gpuCommand.bits
        zero(command); command.commandId.poke(id.U)
        command.opcode.poke(if(id == 1) GpuCommandOpcode.kernel else GpuCommandOpcode.kernelIndirect)
        if(id == 2) { command.sourceAddress.poke(0x3000.U); command.bytes.poke(12.U) }
        command.launch.kernelPc.poke((id * 0x1000).U)
        for(axis <- 0 until 3) {
          command.launch.gridSize(axis).poke((if(id == 2) 0xdead else if(axis == 0) groups else 1).U)
          command.launch.localSize(axis).poke((if(axis == 0) threads else 1).U)
        }
        val task = command.computeUniform.get; task.enabled.poke(true.B)
        task.resources.version.poke(1.U); task.resources.address.poke((0x6000 + id * 0x2000).U)
        task.identity.context.poke(7.U); task.identity.pass.poke(11.U)
        task.identity.generation.poke(13.U)
        val storageTask=command.storage.get; storageTask.enabled.poke(true.B)
        storageTask.table.version.poke(1.U); storageTask.table.base.poke((0x18000+id*0x1000).U)
        storageTask.table.limit.poke((0x18100+id*0x1000).U)
        storageTask.table.identity.context.poke(7.U); storageTask.table.identity.pass.poke(11.U)
        storageTask.table.identity.generation.poke(13.U)
        val imageTask=command.images.get; imageTask.enabled.poke(true.B)
        imageTask.table.version.poke(1.U); imageTask.table.base.poke((0x1b000+id*0x1000).U)
        imageTask.table.limit.poke((0x1b400+id*0x1000).U)
        imageTask.table.identity.context.poke(7.U); imageTask.table.identity.pass.poke(11.U)
        imageTask.table.identity.generation.poke(13.U)
        if(counterEnabled) {
          val counter=command.atomicCounter.get;counter.enabled.poke(true.B);counter.table.version.poke(1.U)
          counter.table.base.poke((0x17000+id*64).U);counter.table.limit.poke((0x17000+(id+1)*64).U)
          counter.table.identity.context.poke(7.U);counter.table.identity.pass.poke(11.U);counter.table.identity.generation.poke(13.U)
        }
        if(textureEnabled) {
          val texture=command.computeTexture.get;texture.enabled.poke(true.B);texture.resources.version.poke(1.U)
          texture.resources.address.poke((opengpu.graphics.ComputeTextureCoexistenceFixture.root+BigInt(id)*0x400000).U)
          texture.identity.context.poke(7.U);texture.identity.pass.poke(11.U);texture.identity.generation.poke(13.U)
        }
        d.io.gpuCommand.valid.poke(true.B)
        until("command admission")(d.io.gpuCommand.ready.peek().litToBoolean)
        tick(); d.io.gpuCommand.valid.poke(false.B)
      }
      zero(d.io.gpuCommand.bits)
      d.io.computeUniformLease.get.expect(true.B)
      d.io.storageLease.get.expect(true.B); d.io.imageLease.get.expect(true.B)
      if(textureEnabled)d.io.computeTextureLease.get.expect(true.B)
      if(counterEnabled)d.io.atomicCounterLease.get.expect(true.B)
      until("first held public completion")(d.io.gpuCompletion.valid.peek().litToBoolean)
      val held = d.io.gpuCompletion.bits.peek().litValue
      for(_ <- 0 until 13) {
        tick(); assert(d.io.gpuCompletion.bits.peek().litValue == held)
        d.io.computeUniformLease.get.expect(true.B)
        d.io.storageLease.get.expect(true.B); d.io.imageLease.get.expect(true.B)
        if(textureEnabled)d.io.computeTextureLease.get.expect(true.B)
        if(counterEnabled)d.io.atomicCounterLease.get.expect(true.B)
      }
      d.io.gpuCompletion.ready.poke(true.B)
      until("both uniform kernels retire")(completions.size == 2)
      until("lower-memory drain")(pending.isEmpty && d.io.vmContextIdle.peek().litToBoolean)
      val expectedLoads = (for(unit <- 0 until 12; id <- 1 to 2) yield (unit,id*20+unit) -> (groups*warps)).toMap
      assert(loaded.toMap == expectedLoads, s"unexpected uniform load results: $loaded")
      val expectedStorage=(for(unit<-0 until 4; id<-1 to 2) yield (unit,id*2+unit)->(groups*warps)).toMap
      assert(storageLoaded.toMap==expectedStorage,s"unexpected storage load results: $storageLoaded")
      assert(defaultLoaded.toMap==Map(177->(groups*warps),277->(groups*warps)),s"unexpected default loads: $defaultLoaded")
      if(textureEnabled) {
        val expected=(for(id<-1 to 2;unit<-0 until 16)yield (id,unit)->(groups*warps)).toMap
        assert(textureLoaded.toMap==expected,s"unexpected texture loads: $textureLoaded")
      }
      if(counterEnabled) for(id<-1 to 2;index<-0 until 8) {
        val values=counterValues.getOrElse((id,index),mutable.ArrayBuffer.empty[BigInt]).sorted
        val expected=(0 until groups*threads).map(n=>BigInt(id*100000+index*10000+n))
        assert(values==expected,s"counter $id index $index differs: $values")
      }
      assert(simultaneous && writes > 0 && indirectArgumentReads == 1,
        s"expected all $cuCount CUs busy and one indirect argument read: writes=$writes arguments=$indirectArgumentReads")
      d.io.computeUniformLease.get.expect(false.B)
      d.io.storageLease.get.expect(false.B); d.io.imageLease.get.expect(false.B)
      if(textureEnabled)d.io.computeTextureLease.get.expect(false.B)
      if(counterEnabled)d.io.atomicCounterLease.get.expect(false.B)
      for(id<-1 to 2; unit<-0 until 4) {
        val storageData=0x120000+id*0x10000+unit*0x1000
        assert(get(storageData)==id*2+unit,s"storage $id unit $unit differs")
        assert(get(storageData+4)==BigInt("a5a5a5a5",16),s"storage $id unit $unit crossed its word")
        val data=0x150000+id*0x10000+unit*0x1000
        for(i<-0 until 4096) {
          val offset=i-63
          val expected=if(offset>=0 && offset<threads*16) {
            val lane=offset/16; val component=(offset%16)/4; val byte=offset%4
            ((BigInt(lane+id*2+unit*2+component) >> (byte*8)) & 255).toInt
          } else 0xa5
          assert(memory(BigInt(data+i))==expected,s"image $id unit $unit byte $i differs")
        }
      }
      for(id <- 1 to 2; block <- 0 until 12) {
        val data = 0x20000 + id * 0x40000 + block * 0x4000
        assert(get(data) == id*20+block, s"uniform $id block $block was modified")
        assert(get(data+16380) == id*20+block, s"uniform $id block $block end was modified")
      }
      for(id<-1 to 2) assert(get(0x1f000+id*0x1000+4092)==id*100+77,s"default uniform $id was modified")
    }
  }
}
