package opengpu.core

import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import java.nio.file.{Files,Paths}
import java.util.Properties
import opengpu.config.GpuConfig
import opengpu.core.memory._
import opengpu.graphics.GraphicsStage
import opengpu.testutil.GpuSim._
import org.scalatest.DoNotDiscover
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class SharedPublicationSramModels extends BlackBox with HasBlackBoxInline {
  val io=IO(new Bundle{val present=Output(Bool())})
  private val directory=Paths.get(sys.props.getOrElse("opengpu.sramModels",
    "depends/asap7_sram_0p0/generated/verilog"))
  setInline("SharedPublicationSramModels.sv",
    "module SharedPublicationSramModels(output present); assign present=1'b1; endmodule\n"+
    Seq(32,64).map(w=>Files.readString(directory.resolve(s"srambank_64x4x${w}_6t122.v"))).mkString("\n"))
}

/** Independent stable task VMs, as the captured Gallium contexts differ.
  * All CU memory and private-cache invalidations use real shared L2.
  * This fixture does not qualify the command-system single-VM scheduler. */
private class SharedPublicationCuPair(config: GpuConfig) extends Module {
  require(config.lanes==16&&config.warps==8&&config.sharedMemoryBytes==16384)
  val cus=Seq.fill(2)(Module(new GpuComputeUnit(config,finishOnTrap=true,
    useBlackBoxes=true,enableFpuBackend=true,nativeExecutionProfile=true,
    textureAvailable=false,taskTextureAvailability=true,nativeResourcesAvailable=false,
    imageAvailable=true,storageAvailable=true,computeTextureAvailable=true,
    instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=16,vectorCacheWays=1)))
  val fabric=Module(new SharedComputeMemoryInterconnect(config,2,64,16))
  val l2=Module(new SharedL2Cache(config,sets=8,ways=2,banks=4,
    numComputeUnits=2,transactionsPerCu=16,maxOutstanding=32))
  val io=IO(new Bundle {
    val cu=Vec(2,chiselTypeOf(cus.head.io))
    val modelsPresent=Output(Bool())
    val requestFire=Output(Vec(2,Bool()))
    val requestAddress=Output(Vec(2,UInt(32.W)))
    val requestWrite=Output(Vec(2,Bool()))
    val lowerRequest=Decoupled(new ComputeMemoryRequest(config,64,32))
    val lowerResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,32)))
    val drained=Output(Bool())
    val invalidated=Output(Vec(2,Bool()))
    val invalidateAddress=Output(Vec(2,UInt(32.W)))
    val holdConsumerReplies=Input(Bool())
    val holdProducerReplies=Input(Bool())
    val consumerReplyWaiting=Output(Bool())
  })
  io.modelsPresent:=Module(new SharedPublicationSramModels).io.present
  val replyIdle=Wire(Vec(2,Bool()))
  for(i<-0 until 2) {
    io.cu(i)<>cus(i).io
    fabric.io.cuRequest(i)<>cus(i).io.memoryRequest
    io.requestFire(i):=fabric.io.cuRequest(i).fire
    io.requestAddress(i):=fabric.io.cuRequest(i).bits.address
    io.requestWrite(i):=fabric.io.cuRequest(i).bits.isWrite
    // Buffer an original L2 response before adversarial delivery delay. L2
    // remains free to service the producer and invalidate the consumer's L1.
    val replies=Module(new Queue(new ComputeMemoryResponse(64,16),8))
    replies.io.enq<>fabric.io.cuResponse(i)
    val hold=if(i==0)io.holdConsumerReplies else io.holdProducerReplies
    cus(i).io.memoryResponse.valid:=replies.io.deq.valid&& !hold
    cus(i).io.memoryResponse.bits:=replies.io.deq.bits
    replies.io.deq.ready:=cus(i).io.memoryResponse.ready&& !hold
    replyIdle(i):=replies.io.count===0.U
    if(i==0)io.consumerReplyWaiting:=replies.io.deq.valid&&hold
    cus(i).io.l1Invalidate<>l2.io.invalidate(i)
    l2.io.invalidateDone(i)<>cus(i).io.l1InvalidateDone
    l2.io.atomicRequest(i)<>cus(i).io.globalAtomicRequest
    cus(i).io.globalAtomicResponse<>l2.io.atomicResponse(i)
    io.invalidated(i):=l2.io.invalidate(i).fire
    io.invalidateAddress(i):=l2.io.invalidate(i).bits.lineAddress
  }
  l2.io.request<>fabric.io.memoryRequest;fabric.io.memoryResponse<>l2.io.response
  io.lowerRequest<>l2.io.memoryRequest;l2.io.memoryResponse<>io.lowerResponse
  l2.io.hostInvalidate.valid:=false.B;l2.io.hostInvalidate.bits:=0.U.asTypeOf(l2.io.hostInvalidate.bits)
  l2.io.hostInvalidateDone.ready:=true.B;l2.io.clearPerformanceCounters:=false.B
  io.drained:=l2.io.drained&&replyIdle.asUInt.andR
}

@DoNotDiscover
class GalliumSharedPublicationQualification extends AnyFlatSpec {
  private def zero(data:Data):Unit=data match {
    case r:Record=>r.elements.values.foreach(zero)
    case v:Vec[_]=>v.foreach(zero)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case _=>()
  }
  private val mask32=(BigInt(1)<<32)-1
  private def expected(epoch:Int,local:Int):BigInt=
    ((0 until 32).map(stripe=>BigInt(if(epoch==1)"a1000000"else "a2000000",16)+(local^16)+stripe*128).sum)&mask32
  it should "publish a full shared cross-warp result to an unchanged warmed consumer on another CU" in {
    require(sys.props.get("opengpu.galliumSharedPublicationQualification").contains("1"),"explicit local qualification only")
    val directory=Paths.get(sys.props.getOrElse("opengpu.galliumSharedPublicationInputs",
      throw new IllegalArgumentException("actual Mesa/kernel inputs required")))
    case class Input(name:String){
      val properties=new Properties;val stream=Files.newInputStream(directory.resolve(s"$name.properties"))
      try properties.load(stream)finally stream.close()
      def n(key:String):Long=Option(properties.getProperty(key)).getOrElse(throw new IllegalArgumentException(s"$name missing $key")).toLong
      def bytes(suffix:String):Array[Byte]=Files.readAllBytes(directory.resolve(s"$name.$suffix"))
      val code=bytes("shader").grouped(4).map(b=>(0 until 4).map(i=>BigInt(b(i)&255)<<(8*i)).sum).toVector
      val cease=code.indexOf(BigInt("30500073",16));require(cease>=0&&code.size<=512&&code.drop(cease+1).forall(_==0))
      if(name.startsWith("producer"))require(cease+1==503&&code.count(_==BigInt("0000000b",16))==3)
      def segment(slot:Int):Int=(0 until n("segments").toInt).find(i=>n(s"memory.$i.slot")==slot).get
      def va(slot:Int):Long=n(s"memory.${segment(slot)}.va")
    }
    val producers=(1 to 2).map(epoch=>epoch->Input(s"producer-$epoch")).toMap
    val consumers=Map("ordinary"->Input("image-consumer"),"image"->Input("image-consumer"),"texture"->Input("texture-consumer"))
    simulate(new SharedPublicationCuPair(GpuConfig(l2Sets=8,l2Ways=2))){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B);d.io.modelsPresent.expect(true.B)
      val memory=mutable.LongMap.empty[Int]
      def put(at:Long,value:BigInt):Unit=for(i<-0 until 4)memory(at+i)=((value>>(8*i))&255).toInt
      def get(at:Long,count:Int=4):BigInt=(0 until count).map(i=>BigInt(memory.getOrElse(at+i,0xa5))<<(8*i)).sum
      case class Reply(id:BigInt,at:Long,data:BigInt,mask:BigInt,write:Boolean,fault:Boolean,due:Int)
      val pending=mutable.ArrayBuffer.empty[Reply];var held=Option.empty[Reply];var offered=Option.empty[BigInt]
      var cycle=0;var kernels=0;var faults=0;var consumerValues=0;var invalidations=0;var globalWrites=0
      for((kind,kindIndex)<-Vector("ordinary","image","texture").zipWithIndex){
        val sharedPhysical=0x50000000L+kindIndex*0x100000L;val payload=sharedPhysical+256
        val outputPhysical=sharedPhysical+0x1000;var oldEpoch=0
        for((epoch,mode,round)<-Vector((1,"healthy",0),(2,"healthy",1),(1,"write-fault",2),(1,"recover",3))){
          val producer=producers(epoch);val consumer=consumers(kind);val fault=mode=="write-fault"
          require(pending.isEmpty&&held.isEmpty&&offered.isEmpty)
          val physicalForHandle=mutable.Map.empty[Long,Long];val inputs=Vector(consumer,producer)
          val roots=Vector.tabulate(2)(cu=>0x1000000L+kindIndex*0x100000L+round*0x20000L+cu*0x8000L)
          for(cu<-0 until 2){
            val input=inputs(cu);val root=roots(cu);val pages=mutable.Map.empty[Long,Long];val levels=mutable.Map.empty[Long,Long]
            var next=0x30000000L+kindIndex*0x1000000L+round*0x200000L+cu*0x100000L
            def blank(at:Long):Unit=for(i<-0 until 4096)memory(at+i)=0
            blank(root)
            def map(virtual:Long,physical:Long,writable:Boolean):Unit={
              val vpn=virtual>>12;val ppn=physical & ~4095L
              pages.get(vpn).foreach(p=>require(p==ppn,s"conflicting physical alias page $virtual"));pages(vpn)=ppn
              val level=levels.getOrElseUpdate(virtual>>22,{
                val p=root+(levels.size+1)*4096L;blank(p);put(root+(virtual>>22)*4,(BigInt(p>>12)<<10)|1);p})
              put(level+((virtual>>12)&1023)*4,(BigInt(ppn>>12)<<10)|(if(writable)0xc7 else 0x43))
            }
            def install(virtual:Long,bytes:Array[Byte],physical:Option[Long],writable:Boolean,copy:Boolean=true):Unit=
              bytes.indices.foreach{i=>val at=virtual+i
                val pa=physical.map(_+i).getOrElse{
                  val p=pages.getOrElse(at>>12,{val value=next;next+=4096;blank(value);value});p+(at&4095)}
                map(at,pa,writable);if(copy)memory(pa)=bytes(i)&255}
            for((field,suffix)<-Vector("image.root"->"images","texture.root"->"textures","storage.root"->"storage") if input.n(field)!=0)
              install(input.n(field),input.bytes(suffix),None,false)
            for(segment<-0 until input.n("segments").toInt){
              val handle=input.n(s"memory.$segment.handle");val slot=input.n(s"memory.$segment.slot").toInt
              val bytes=input.bytes(s"memory$segment");val offset=input.n(s"memory.$segment.offset")
              require(bytes.length==input.n(s"memory.$segment.bytes")&&(offset==0||offset==256))
              val base=physicalForHandle.getOrElseUpdate(handle,
                if(handle==1)sharedPhysical else if(handle==2)outputPhysical else sharedPhysical+0x10000+handle*0x1000)
              install(input.n(s"memory.$segment.va"),bytes,Some(base+offset),slot>=35,
                copy=if(handle==1)round==0&&cu==0 else if(handle==2)cu==0 else true)
            }
          }
          for(i<-0 until 128)put(outputPhysical+i*4,if(i==2)0 else BigInt("a5a5a5a5",16))
          val oldValue=if(oldEpoch==0)BigInt(0)else expected(oldEpoch,0)
          assert(get(payload)==oldValue&&get(payload+512)==oldEpoch)
          val sharedVirtual=consumer.va(if(kind=="texture")3 else 39)
          def load(rd:Int,offset:Int):BigInt=(BigInt(offset)<<20)|(BigInt(5)<<15)|(BigInt(2)<<12)|(BigInt(rd)<<7)|3
          def bne(rs1:Int,rs2:Int,offset:Int):BigInt={val v=offset&8191
            (BigInt(v>>12)<<31)|(BigInt((v>>5)&63)<<25)|(BigInt(rs2)<<20)|(BigInt(rs1)<<15)|0x1000|
              (BigInt((v>>1)&15)<<8)|(BigInt((v>>11)&1)<<7)|0x63}
          // This native ordinary consumer witnesses the private L1 path.
          // The image and texture consumers use the original actual Mesa words.
          val ordinary=Vector((BigInt(sharedVirtual)&BigInt("fffff000",16))|(BigInt(5)<<7)|0x37,
            load(11,256),load(13,256),(BigInt(epoch)<<20)|(BigInt(9)<<7)|0x13,load(10,768),bne(10,9,-4),
            BigInt("0330000f",16),load(12,256),BigInt("30500073",16))
          val codes=Vector(if(kind=="ordinary")ordinary else consumer.code,producer.code)
          val pcs=Vector.tabulate(2)(cu=>0x1000L+kindIndex*0x10000L+round*0x4000L+cu*0x2000L)
          for(cu<-0 until 2;i<-codes(cu).indices)put(pcs(cu)+i*4,codes(cu)(i))
          val faultPc=pcs(1)+producer.code.indexWhere(w=>(w&127)==0x2b&&(w>>26)==16)*4
          var producerPublished=false;var checkpoint=false;var oldRead=false;var oldCachedRead=false;var newRead=false;var polls=0;var ordinaryDataRequests=0;var warmRequests=0
          var faultReply=false;var delayedConsumerCycles=0;var dataInvalidated=false
          val dataAcknowledged=mutable.Set.empty[Int];val flagsAcknowledged=mutable.Set.empty[Int]
          val writesOffered=mutable.Set.empty[Int];val traps=mutable.Set.empty[Int];val done=mutable.Set.empty[Int]
          val heldTraps=mutable.Map.empty[Int,(BigInt,Int)];val completions=mutable.Map.empty[Int,(BigInt,Int)]
          val startInvalidations=invalidations
          val priorActive=Array.fill[BigInt](2)(0);val priorWaiting=Array.fill[BigInt](2)(0)
          val warpStarts=Array.fill(2)(0);val peakWarps=Array.fill(2)(0);val barriersCompleted=Array.fill(2)(0)
          val survivingWords=(16 until 128).toSet
          def publicationDataReady(item:Int):Unit={
            if(fault){
              // Accepted precise traps retire only their warp. The seven peer
              // warps remain resident and may complete their own stores/barrier.
              assert(faultReply&&traps==Set(1)&&item>=144&&dataAcknowledged.toSet==survivingWords,
                s"$kind/$mode peer publication preceded surviving data acknowledgements")
              assert((d.io.cu(1).active.peek().litValue&1)==0)
            }else assert(dataAcknowledged.size==128,
              s"$kind/$mode epoch=$epoch publication preceded all 128 data acknowledgements")
          }
          def tick():Unit={
            d.io.holdProducerReplies.poke(false.B)
            d.io.holdConsumerReplies.poke((kind!="ordinary"&&checkpoint&& !producerPublished).B)
            if(d.io.consumerReplyWaiting.peek().litToBoolean)delayedConsumerCycles+=1
            d.io.lowerRequest.ready.poke((cycle%7<4).B)
            if(held.isEmpty){val i=pending.indexWhere(_.due<=cycle);if(i>=0)held=Some(pending.remove(i))}
            d.io.lowerResponse.valid.poke(held.nonEmpty.B)
            held.foreach{r=>d.io.lowerResponse.bits.transactionId.poke(r.id.U);d.io.lowerResponse.bits.readData.poke(r.data.U)
              d.io.lowerResponse.bits.fault.poke(r.fault.B)}
            if(held.nonEmpty&&d.io.lowerResponse.ready.peek().litToBoolean){val r=held.get
              if(r.write){
                val byte=(0 until 64).find(r.mask.testBit).get;val at=r.at+byte
                if(at>=payload&&at<payload+1024){
                  val item=((at-payload)/4).toInt
                  assert(((r.data>>(8*byte))&mask32)==(if(item<128)expected(epoch,item)else BigInt(epoch)))
                  if(r.fault){assert(fault&&item==0);faultReply=true}
                  else if(item<128){assert(!dataAcknowledged(item));dataAcknowledged+=item}
                  else{publicationDataReady(item);flagsAcknowledged+=item-128
                    if(item==128)producerPublished=true}
                }else{assert(r.at==outputPhysical&& !fault);consumerValues+=1}
                if(!r.fault)for(i<-0 until 64 if r.mask.testBit(i))memory(r.at+i)=((r.data>>(8*i))&255).toInt
              };held=None
            }
            if(kind=="ordinary"&&d.io.requestFire(0).peek().litToBoolean&& !d.io.requestWrite(0).peek().litToBoolean&&
              d.io.requestAddress(0).peek().litValue==payload)ordinaryDataRequests+=1
            for(cu<-0 until 2){
              if(d.io.invalidated(cu).peek().litToBoolean){invalidations+=1
                if(cu==0&&d.io.invalidateAddress(cu).peek().litValue==payload)dataInvalidated=true}
              val port=d.io.cu(cu)
              val active=port.active.peek().litValue;val waiting=port.barrierWaiting.peek().litValue
              warpStarts(cu)+=(active & ~priorActive(cu)).bitCount;peakWarps(cu)=peakWarps(cu).max(active.bitCount)
              if(priorWaiting(cu)!=0&&waiting==0)barriersCompleted(cu)+=1
              priorActive(cu)=active;priorWaiting(cu)=waiting
              if(cu==0&&kind=="ordinary"&&port.committedWriteback.valid.peek().litToBoolean){
                val rd=port.committedWriteback.bits.rd.peek().litValue.toInt;val value=port.committedWriteback.bits.data.peek().litValue
                if(rd==11){assert(value==oldValue&& !oldRead&&ordinaryDataRequests<=1);warmRequests=ordinaryDataRequests;oldRead=true}
                if(rd==13){assert(value==oldValue&&oldRead&& !oldCachedRead&&ordinaryDataRequests==warmRequests);oldCachedRead=true}
                if(rd==10){if(value==oldEpoch)polls+=1 else assert(value==epoch&&producerPublished)}
                if(rd==12){assert(value==expected(epoch,0)&&producerPublished&& !newRead);newRead=true;consumerValues+=1}
              }
              port.trap.ready.poke(false.B)
              if(port.trap.valid.peek().litToBoolean){
                assert(fault&&cu==1&&faultReply&& !producerPublished)
                port.trap.bits.warpId.expect(0.U);assert((port.active.peek().litValue&1)!=0)
                port.trap.bits.pc.expect(faultPc.U);port.trap.bits.cause.expect(7.U);port.trap.bits.tval.expect(producer.va(35).U)
                port.trap.bits.activeMask.expect(65535.U);port.trap.bits.laneFaultMask.expect(1.U)
                val previous=heldTraps.getOrElse(cu,(port.trap.bits.peek().litValue,0));assert(port.trap.bits.peek().litValue==previous._1)
                heldTraps(cu)=(previous._1,previous._2+1);if(previous._2>=7){port.trap.ready.poke(true.B);traps+=cu}
              }
              port.completion.ready.poke(false.B)
              if(port.completion.valid.peek().litToBoolean){
                port.completion.bits.success.expect((!fault).B)
                val previous=completions.getOrElse(cu,(port.completion.bits.peek().litValue,0));assert(port.completion.bits.peek().litValue==previous._1)
                completions(cu)=(previous._1,previous._2+1)
                port.storageLease.get.expect((cu==1||kind!="ordinary").B)
                port.imageLease.get.expect((cu==0&&kind=="image").B);port.computeTextureLease.get.expect((cu==0&&kind=="texture").B)
                if(previous._2>=9){port.completion.ready.poke(true.B);done+=cu}
              }
            }
            val request=d.io.lowerRequest;offered.foreach{bits=>request.valid.expect(true.B);assert(request.bits.peek().litValue==bits)}
            if(request.valid.peek().litToBoolean&&request.ready.peek().litToBoolean){
              val q=request.bits;val at=q.address.peek().litValue.toLong;val write=q.isWrite.peek().litToBoolean
              val mask=q.byteMask.peek().litValue;val data=q.writeData.peek().litValue
              var failed=false
              if(write){
                val byte=(0 until 64).find(mask.testBit).get;assert(byte%4==0&&mask==(BigInt(15)<<byte))
                val address=at+byte
                if(address>=payload&&address<payload+1024){
                  val item=((address-payload)/4).toInt;assert(!writesOffered(item));writesOffered+=item;globalWrites+=1
                  if(item>=128)publicationDataReady(item)
                  failed=fault&&item==0
                }else{assert(at==outputPhysical&& !fault&&kind!="ordinary")
                  if(mask==15){assert(!checkpoint&&(data&mask32)==oldValue);checkpoint=true}else assert(mask==0xf0&&producerPublished)}
              }
              pending+=Reply(q.transactionId.peek().litValue,at,if(write)data else get(at,64),mask,write,failed,cycle+(if(write)73 else 7))
            }
            offered=if(request.valid.peek().litToBoolean&& !request.ready.peek().litToBoolean)Some(request.bits.peek().litValue)else None
            d.clock.step();cycle+=1
          }
          def until(label:String)(condition: => Boolean):Unit={val start=cycle
            while(!condition&&cycle-start<160000)tick()
            assert(condition,s"$kind/$mode $label stalled cycle=$cycle pending=${pending.size} checkpoint=$checkpoint flags=${flagsAcknowledged.size} data=${dataAcknowledged.size} done=$done")}
          for(cu<-0 until 2){val port=d.io.cu(cu);zero(port);val input=inputs(cu)
            port.vectorSatp.poke(((BigInt(1)<<31)|BigInt(roots(cu)>>12)).U)
            val storage=port.storage.get;storage.enabled.poke((cu==1||kind!="ordinary").B);storage.table.version.poke(1.U)
            storage.table.base.poke(input.n("storage.root").U);storage.table.limit.poke((input.n("storage.root")+256).U)
            storage.table.identity.context.poke(input.n("context").U)
            val image=port.images.get;image.enabled.poke((cu==0&&kind=="image").B);image.stage.poke(GraphicsStage.compute.U)
            image.table.version.poke(1.U);image.table.base.poke(input.n("image.root").U);image.table.limit.poke((input.n("image.root")+1024).U)
            image.table.identity.context.poke(input.n("context").U)
            val texture=port.computeTexture.get;texture.enabled.poke((cu==0&&kind=="texture").B);texture.resources.version.poke(1.U)
            texture.resources.address.poke(input.n("texture.root").U);texture.identity.context.poke(input.n("context").U)
            port.kernel.bits.kernelPc.poke(pcs(cu).U)
            for(axis<-0 until 3){port.kernel.bits.gridSize(axis).poke(1.U);port.kernel.bits.localSize(axis).poke((if(cu==1&&axis==0)128 else 1).U)}
          }
          def launch(cu:Int):Unit={val port=d.io.cu(cu);port.kernel.valid.poke(true.B)
            until("admit")(port.kernel.ready.peek().litToBoolean);tick();port.kernel.valid.poke(false.B);kernels+=1}
          if(!fault){launch(0);until("original consumer warmed")(if(kind=="ordinary")oldRead&&oldCachedRead&&polls>=2 else checkpoint)}
          launch(1);until("complete")(done.size==(if(fault)1 else 2))
          until("drain")(d.io.drained.peek().litToBoolean&&pending.isEmpty&&held.isEmpty&&offered.isEmpty)
          assert(warpStarts(1)==8&&peakWarps(1)==8)
          if(fault){
            assert(barriersCompleted(1)==3&&traps==Set(1)&& !producerPublished)
            assert(dataAcknowledged.toSet==survivingWords&&flagsAcknowledged.toSet==survivingWords)
            assert(writesOffered.toSet==(survivingWords++survivingWords.map(_+128)+0))
            for(local<-0 until 128){
              val writtenEpoch=if(local<16)oldEpoch else epoch
              assert(get(payload+local*4)==expected(writtenEpoch,local))
              assert(get(payload+512+local*4)==writtenEpoch)
            }
            faults+=1
          }
          else{
            assert(barriersCompleted(1)==3)
            assert(traps.isEmpty&&producerPublished&&dataAcknowledged.size==128&&flagsAcknowledged.size==128&&writesOffered.size==256)
            for(local<-0 until 128){assert(get(payload+local*4)==expected(epoch,local));assert(get(payload+512+local*4)==epoch)}
            if(kind=="ordinary")assert(oldRead&&oldCachedRead&&polls>=2&&newRead&&ordinaryDataRequests==warmRequests+1&&dataInvalidated&&invalidations>startInvalidations)
            else assert(checkpoint&&delayedConsumerCycles>0&&get(outputPhysical)==oldValue&&get(outputPhysical+4)==expected(epoch,0))
            oldEpoch=epoch
          }
          assert(get(payload)==(if(oldEpoch==0)BigInt(0)else expected(oldEpoch,0))&&get(payload+512)==oldEpoch)
          for(i<-0 until 1536 if i<256||i>=1280)assert(memory(sharedPhysical+i)==0x5a,"shared source GEM guard modified")
          for(i<-0 until 128 if fault||kind=="ordinary"||i>=2)assert(get(outputPhysical+i*4)==(if(i==2)BigInt(0)else BigInt("a5a5a5a5",16)))
          for(_<-0 until 12){d.io.lowerRequest.valid.expect(false.B);tick()}
          for(cu<-0 until 2){d.io.cu(cu).active.expect(0.U);d.io.cu(cu).barrierWaiting.expect(0.U)}
        }
      }
      info(s"SHARED_PUBLICATION_L2_PASS kernels=$kernels faults=$faults consumerValues=$consumerValues globalWrites=$globalWrites sharedBytes=16384 threads=128 warps=8 CUs=2 threeConvergedBarriers=true delayedAck=73 originalWarmedConsumers=true noResetRecovery=true invalidations=$invalidations cycles=$cycle")
    }
  }
}
object QualifyGalliumSharedPublication {
  def main(args:Array[String]):Unit=require(org.scalatest.tools.Runner.run(Array(
    "-oD","-s",classOf[GalliumSharedPublicationQualification].getName)),"actual Mesa shared-publication qualification failed")
}
