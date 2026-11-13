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

/** Independent stable task VMs, as the captured Gallium contexts differ.
  * All CU memory, private-cache invalidations and atomics use real shared L2.
  * This fixture does not qualify the command-system single-VM scheduler. */
private class ImageOrderingCuPair(config: GpuConfig) extends Module {
  val cus=Seq.fill(2)(Module(new GpuComputeUnit(config,finishOnTrap=true,
    textureAvailable=false,taskTextureAvailability=true,nativeResourcesAvailable=false,
    imageAvailable=true,storageAvailable=true,computeTextureAvailable=true,
    instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1)))
  val fabric=Module(new SharedComputeMemoryInterconnect(config,2,64,16))
  val l2=Module(new SharedL2Cache(config,sets=8,ways=2,banks=4,
    numComputeUnits=2,transactionsPerCu=16,maxOutstanding=32))
  val io=IO(new Bundle {
    val cu=Vec(2,chiselTypeOf(cus.head.io))
    val lowerRequest=Decoupled(new ComputeMemoryRequest(config,64,32))
    val lowerResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,32)))
    val drained=Output(Bool())
    val invalidated=Output(Vec(2,Bool()))
    val holdConsumerReplies=Input(Bool())
    val holdProducerReplies=Input(Bool())
    val consumerReplyWaiting=Output(Bool())
  })
  val replyIdle=Wire(Vec(2,Bool()))
  for(i<-0 until 2) {
    io.cu(i)<>cus(i).io
    fabric.io.cuRequest(i)<>cus(i).io.memoryRequest
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
  }
  l2.io.request<>fabric.io.memoryRequest;fabric.io.memoryResponse<>l2.io.response
  io.lowerRequest<>l2.io.memoryRequest;l2.io.memoryResponse<>io.lowerResponse
  l2.io.hostInvalidate.valid:=false.B;l2.io.hostInvalidate.bits:=0.U.asTypeOf(l2.io.hostInvalidate.bits)
  l2.io.hostInvalidateDone.ready:=true.B;l2.io.clearPerformanceCounters:=false.B
  io.drained:=l2.io.drained&&replyIdle.asUInt.andR
}

@DoNotDiscover
class GalliumImageOrderingQualification extends AnyFlatSpec {
  private def zero(data: Data): Unit=data match {
    case r: Record=>r.elements.values.foreach(zero)
    case v: Vec[_]=>v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case _=>()
  }
  it should "publish coherent Mesa image writes to warmed ordinary image and texture consumers without replacing their tasks" in {
    require(sys.props.get("opengpu.galliumImageOrderingQualification").contains("1"),"explicit local qualification only")
    val directory=Paths.get(sys.props.getOrElse("opengpu.galliumImageOrderingInputs",
      throw new IllegalArgumentException("actual Mesa/kernel publication inputs required")))
    case class Input(name: String) {
      val properties=new Properties;val stream=Files.newInputStream(directory.resolve(s"$name.properties"))
      try properties.load(stream)finally stream.close()
      def n(key: String): Long=Option(properties.getProperty(key)).getOrElse(
        throw new IllegalArgumentException(s"missing $name $key")).toLong
      def bytes(suffix: String): Array[Byte]=Files.readAllBytes(directory.resolve(s"$name.$suffix"))
      val code=bytes("shader").grouped(4).map(b=>(0 until 4).map(i=>BigInt(b(i)&255)<<(i*8)).sum).toVector
      val cease=code.indexOf(BigInt("30500073",16))
      require(code.nonEmpty&&code.size<=512&&cease>=0&&code.drop(cease+1).forall(_==0),
        s"invalid actual published instruction/padding extent $name")
    }
    val producers=(1 to 2).map(i=>i->Input(s"producer-$i")).toMap
    val consumers=Map("ordinary"->Input("image-consumer"),"image"->Input("image-consumer"),
      "texture"->Input("texture-consumer"))
    simulate(new ImageOrderingCuPair(GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2))) { d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
      val memory=mutable.LongMap.empty[Int]
      def put(at: Long,value: BigInt): Unit=for(i<-0 until 4)memory(at+i)=((value>>(8*i))&255).toInt
      def get(at: Long,count: Int=4): BigInt=(0 until count).map(i=>BigInt(memory.getOrElse(at+i,0xa5))<<(8*i)).sum
      case class Reply(id: BigInt,at: Long,data: BigInt,mask: BigInt,write: Boolean,fault: Boolean,due: Int)
      val pending=mutable.ArrayBuffer.empty[Reply]
      var held=Option.empty[Reply];var offered=Option.empty[BigInt]
      var cycle=0;var kernels=0;var faults=0;var outputs=0;var invalidations=0
      for((kind,kindIndex)<-Vector("ordinary","image","texture").zipWithIndex) {
        val sharedPhysical=0x50000000L+kindIndex*0x100000L;val outputPhysical=sharedPhysical+0x1000
        var oldEpoch=0
        for((epoch,mode,round)<-Vector((1,"healthy",0),(2,"healthy",1),(1,"write-fault",2),(1,"recover",3))) {
          val producer=producers(epoch);val consumer=consumers(kind);val fault=mode=="write-fault"
          require(pending.isEmpty&&held.isEmpty&&offered.isEmpty)
          var producerPublished=false;var dataAcknowledged=false;var checkpoint=false
          var oldRead=false;var polls=0;var newRead=false;var writes=0;var faultReply=false
          var delayedConsumerCycles=0
          val traps=mutable.Set.empty[Int];val done=mutable.Set.empty[Int]
          val heldTraps=mutable.Map.empty[Int,(BigInt,Int)]
          val completions=mutable.Map.empty[Int,(BigInt,Int)]
          val physicalForHandle=mutable.Map.empty[Long,Long]
          val inputs=Vector(consumer,producer)
          val roots=Vector.tabulate(2)(i=>0x1000000L+kindIndex*0x100000L+round*0x20000L+i*0x8000L)
          val translations=Vector.tabulate(2){cu=>
            val input=inputs(cu);val root=roots(cu);val pages=mutable.Map.empty[Long,Long]
            val levels=mutable.Map.empty[Long,Long];var next=0x30000000L+kindIndex*0x1000000L+round*0x200000L+cu*0x100000L
            def blank(at: Long): Unit=for(i<-0 until 4096)memory(at+i)=0
            blank(root)
            def map(virtual: Long,physical: Long,writable: Boolean): Unit={
              val vpn=virtual>>12;val ppn=physical & ~4095L
              pages.get(vpn).foreach(p=>require(p==ppn,s"conflicting alias page $virtual"));pages(vpn)=ppn
              val level=levels.getOrElseUpdate(virtual>>22,{
                val p=root+(levels.size+1)*4096L;blank(p);put(root+(virtual>>22)*4,(BigInt(p>>12)<<10)|1);p})
              put(level+((virtual>>12)&1023)*4,(BigInt(ppn>>12)<<10)|(if(writable)0xc7 else 0x43))
            }
            def install(virtual: Long,bytes: Array[Byte],physical: Option[Long],writable: Boolean,copy: Boolean=true): Unit={
              bytes.indices.foreach{i=>
                val at=virtual+i;val pa=physical.map(_+i).getOrElse({
                  val page=pages.getOrElse(at>>12,{val p=next;next+=4096;blank(p);p});page+(at&4095)})
                map(at,pa,writable);if(copy)memory(pa)=bytes(i)&255
              }
            }
            for((field,suffix)<-Vector("image.root"->"images","texture.root"->"textures","storage.root"->"storage"))
              install(input.n(field),input.bytes(suffix),None,false)
            for(segment<-0 until input.n("segments").toInt) {
              val handle=input.n(s"memory.$segment.handle");val slot=input.n(s"memory.$segment.slot").toInt
              val bytes=input.bytes(s"memory$segment");val offset=input.n(s"memory.$segment.offset")
              require(bytes.length==input.n(s"memory.$segment.bytes")&&offset==0)
              val pa=physicalForHandle.getOrElseUpdate(handle,
                if(handle==1)sharedPhysical else if(handle==2)outputPhysical else sharedPhysical+0x10000+handle*0x1000)
              install(input.n(s"memory.$segment.va"),bytes,Some(pa),slot>=35,
                copy=if(handle==1)round==0&&cu==0 else if(handle==2)cu==0 else true)
            }
            (va: Long)=>pages(va>>12)+(va&4095)
          }
          // The original mutable output index remains one for both accesses.
          for(i<-0 until 128)put(outputPhysical+i*4,if(i==2)1 else BigInt("a5a5a5a5",16))
          assert(get(sharedPhysical+4)==oldEpoch*7&&get(sharedPhysical+68)==oldEpoch)
          val sharedVirtual=consumer.n((0 until consumer.n("segments").toInt).find(i=>consumer.n(s"memory.$i.slot")==39)
            .map(i=>s"memory.$i.va").get)
          def load(rd: Int,offset: Int): BigInt=(BigInt(offset)<<20)|(BigInt(5)<<15)|(BigInt(2)<<12)|(BigInt(rd)<<7)|3
          def bne(rs1: Int,rs2: Int,offset: Int): BigInt={val v=offset&8191
            (BigInt(v>>12)<<31)|(BigInt((v>>5)&63)<<25)|(BigInt(rs2)<<20)|(BigInt(rs1)<<15)|0x1000|
              (BigInt((v>>1)&15)<<8)|(BigInt((v>>11)&1)<<7)|0x63}
          val ordinary=Vector((BigInt(sharedVirtual)&BigInt("fffff000",16))|(BigInt(5)<<7)|0x37,
            load(11,4),(BigInt(epoch)<<20)|(BigInt(9)<<7)|0x13,load(10,68),bne(10,9,-4),
            BigInt("0330000f",16),load(12,4),BigInt("30500073",16))
          val codes=Vector(if(kind=="ordinary")ordinary else consumer.code,producer.code)
          val pcs=Vector.tabulate(2)(i=>0x1000L+kindIndex*0x10000L+round*0x4000L+i*0x2000L)
          for(cu<-0 until 2; i<-codes(cu).indices)put(pcs(cu)+i*4,codes(cu)(i))
          val faultPc=pcs(1)+producer.code.indexWhere(w=>(w&127)==0x2b&&(w>>26)==10)*4
          val startInvalidations=invalidations
          def tick(): Unit={
            d.io.holdConsumerReplies.poke((kind!="ordinary"&&checkpoint&& !producerPublished).B)
            d.io.holdProducerReplies.poke((!fault&& !(if(kind=="ordinary")oldRead&&polls>=2 else checkpoint)).B)
            if(d.io.consumerReplyWaiting.peek().litToBoolean)delayedConsumerCycles+=1
            d.io.lowerRequest.ready.poke((cycle%7<4).B)
            if(held.isEmpty){
              val index=pending.indexWhere(_.due<=cycle)
              if(index>=0)held=Some(pending.remove(index))
            }
            d.io.lowerResponse.valid.poke(held.nonEmpty.B)
            held.foreach{r=>d.io.lowerResponse.bits.transactionId.poke(r.id.U)
              d.io.lowerResponse.bits.readData.poke(r.data.U);d.io.lowerResponse.bits.fault.poke(r.fault.B)}
            val accepted=held.nonEmpty&&d.io.lowerResponse.ready.peek().litToBoolean
            if(accepted){val r=held.get
              if(r.write){
                if(r.at==sharedPhysical){assert(r.mask==0xf0&& !producerPublished)
                  if(r.fault){assert(fault);faultReply=true}else{dataAcknowledged=true;assert(((r.data>>32)&0xffffffffL)==epoch*7)}}
                else if(r.at==sharedPhysical+64){assert(r.mask==0xf0&&dataAcknowledged&& !fault)
                  assert(((r.data>>32)&0xffffffffL)==epoch);producerPublished=true}
                else{assert(r.at==outputPhysical&& !fault);if(r.mask==15)assert(checkpoint)
                  else assert(r.mask==0xf0&&producerPublished)
                  outputs+=1}
                if(!r.fault)for(i<-0 until 64 if r.mask.testBit(i))memory(r.at+i)=((r.data>>(i*8))&255).toInt
              }
              held=None
            }
            for(cu<-0 until 2){
              if(d.io.invalidated(cu).peek().litToBoolean)invalidations+=1
              val port=d.io.cu(cu)
              if(cu==0&&kind=="ordinary"&&port.committedWriteback.valid.peek().litToBoolean){
                val rd=port.committedWriteback.bits.rd.peek().litValue.toInt;val value=port.committedWriteback.bits.data.peek().litValue
                if(rd==11){assert(value==oldEpoch*7&& !oldRead);oldRead=true}
                if(rd==10){if(value==oldEpoch)polls+=1 else assert(value==epoch&&producerPublished)}
                if(rd==12){assert(value==epoch*7&&producerPublished&& !newRead);newRead=true;outputs+=1}
              }
              port.trap.ready.poke(false.B)
              if(port.trap.valid.peek().litToBoolean){assert(fault&&cu==1&&faultReply&& !producerPublished)
                port.trap.bits.pc.expect(faultPc.U);port.trap.bits.cause.expect(7.U)
                port.trap.bits.tval.expect((producer.n((0 until producer.n("segments").toInt).find(i=>producer.n(s"memory.$i.slot")==39)
                  .map(i=>s"memory.$i.va").get)+4).U)
                port.trap.bits.activeMask.expect(1.U);port.trap.bits.laneFaultMask.expect(1.U)
                val previous=heldTraps.getOrElse(cu,(port.trap.bits.peek().litValue,0))
                assert(port.trap.bits.peek().litValue==previous._1)
                heldTraps(cu)=(previous._1,previous._2+1)
                if(previous._2>=7){port.trap.ready.poke(true.B);traps+=cu}
              }
              port.completion.ready.poke(false.B)
              if(port.completion.valid.peek().litToBoolean){
                port.completion.bits.success.expect((!fault).B)
                val previous=completions.getOrElse(cu,(port.completion.bits.peek().litValue,0))
                assert(port.completion.bits.peek().litValue==previous._1)
                completions(cu)=(previous._1,previous._2+1)
                port.imageLease.get.expect((cu==1||kind=="image").B)
                port.storageLease.get.expect((cu==0&&kind!="ordinary").B)
                port.computeTextureLease.get.expect((cu==0&&kind=="texture").B)
                if(previous._2>=9){port.completion.ready.poke(true.B);done+=cu}
              }
            }
            val request=d.io.lowerRequest
            offered.foreach{bits=>request.valid.expect(true.B);assert(request.bits.peek().litValue==bits)}
            if(request.valid.peek().litToBoolean&&request.ready.peek().litToBoolean){
              val q=request.bits;val at=q.address.peek().litValue.toLong;val write=q.isWrite.peek().litToBoolean
              val mask=q.byteMask.peek().litValue
              if(write){assert(at==sharedPhysical||at==sharedPhysical+64||at==outputPhysical)
                if(at==outputPhysical){assert(!fault&&kind!="ordinary");if(mask==15){
                  assert(!checkpoint&&((q.writeData.peek().litValue)&0xffffffffL)==oldEpoch*7);checkpoint=true
                }else assert(producerPublished)}
                if(at==sharedPhysical+64)assert(dataAcknowledged&& !fault,"image flag overtook data acknowledgement")
                writes+=1}
              pending+=Reply(q.transactionId.peek().litValue,at,if(write)q.writeData.peek().litValue else get(at,64),
                mask,write,write&&at==sharedPhysical&&fault,cycle+(if(write)73 else 7))
            }
            offered=if(request.valid.peek().litToBoolean&& !request.ready.peek().litToBoolean)Some(request.bits.peek().litValue)else None
            d.clock.step();cycle+=1
          }
          def until(label: String)(condition: =>Boolean): Unit={val start=cycle
            while(!condition&&cycle-start<16000)tick()
            assert(condition,s"$kind/$mode $label stalled cycle=$cycle pending=$pending checkpoint=$checkpoint published=$producerPublished done=$done")}
          for(cu<-0 until 2){val port=d.io.cu(cu);zero(port)
            port.vectorSatp.poke(((BigInt(1)<<31)|BigInt(roots(cu)>>12)).U)
            val input=inputs(cu)
            val image=port.images.get;image.enabled.poke((cu==1||kind=="image").B);image.stage.poke(GraphicsStage.compute.U)
            image.table.version.poke(1.U);image.table.base.poke(input.n("image.root").U)
            image.table.limit.poke((input.n("image.root")+1024).U);image.table.identity.context.poke(input.n("context").U)
            val storage=port.storage.get;storage.enabled.poke((cu==0&&kind!="ordinary").B)
            storage.table.version.poke(1.U);storage.table.base.poke(input.n("storage.root").U)
            storage.table.limit.poke((input.n("storage.root")+256).U);storage.table.identity.context.poke(input.n("context").U)
            val texture=port.computeTexture.get;texture.enabled.poke((cu==0&&kind=="texture").B)
            texture.resources.version.poke(1.U);texture.resources.address.poke(input.n("texture.root").U)
            texture.identity.context.poke(input.n("context").U)
            port.kernel.bits.kernelPc.poke(pcs(cu).U)
            for(axis<-0 until 3){port.kernel.bits.gridSize(axis).poke(1.U);port.kernel.bits.localSize(axis).poke(1.U)}
          }
          for(cu<- (if(fault)Vector(1)else Vector(0,1))){val port=d.io.cu(cu)
            port.kernel.valid.poke(true.B);until("admit")(port.kernel.ready.peek().litToBoolean);tick()
            port.kernel.valid.poke(false.B);kernels+=1}
          until("complete")(done.size==(if(fault)1 else 2))
          until("drain")(d.io.drained.peek().litToBoolean&&pending.isEmpty&&held.isEmpty&&offered.isEmpty)
          if(fault){assert(traps==Set(1)&&writes==1&& !producerPublished&& !dataAcknowledged);faults+=1}
          else{assert(traps.isEmpty&&dataAcknowledged&&producerPublished&&writes==(if(kind=="ordinary")2 else 4))
            if(kind=="ordinary")assert(oldRead&&polls>=2&&newRead&&invalidations>startInvalidations)
            else{assert(checkpoint&&delayedConsumerCycles>0&&get(outputPhysical)==oldEpoch*7&&get(outputPhysical+4)==epoch*7)}
            oldEpoch=epoch}
          assert(get(sharedPhysical+4)==oldEpoch*7&&get(sharedPhysical+68)==oldEpoch)
          if(fault||kind=="ordinary")for(i<-0 until 128)
            assert(get(outputPhysical+i*4)==(if(i==2)BigInt(1)else BigInt("a5a5a5a5",16)))
          for(i<-0 until 512 if !((i>=4&&i<8)||(i>=68&&i<72)))assert(memory(sharedPhysical+i)==0x5a)
          for(i<-3 until 128)assert(get(outputPhysical+i*4)==BigInt("a5a5a5a5",16))
          for(_<-0 until 12){d.io.lowerRequest.valid.expect(false.B);tick()}
        }
      }
      info(s"IMAGE_ORDERING_L2_PASS kernels=$kernels imageWriteFaults=$faults outputs=$outputs CUs=2 realMesa=true originalRecords=true sharedPhysicalAliases=true stableIndependentSv32=true delayedWriteAck=73 warmedOrdinaryImageTexture=true noResetRecovery=true invalidations=$invalidations cycles=$cycle")
    }
  }
}

object QualifyGalliumImageOrdering {
  def main(args: Array[String]): Unit=require(org.scalatest.tools.Runner.run(Array(
    "-oD","-s","opengpu.core.GalliumImageOrderingQualification")),"Gallium image ordering qualification failed")
}
