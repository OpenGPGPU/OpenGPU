package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.core.memory.L2SramArray

case class TypedTileColorConfig(bytes: Int=32768) {
  require(bytes>=1024 && bytes<=65536 && isPow2(bytes))
  val words=bytes/8;val addressBits=log2Ceil(words)
}
class TypedTileColorView extends Bundle {
  val enabled=Bool();val format=UInt(TypedColorFormat.width.W);val offset=UInt(16.W)
  val channelMask=UInt(4.W);val loadOp=UInt(2.W);val clear=Vec(4,UInt(32.W))
}
class TypedTileColorDescriptor extends Bundle {
  val version=UInt(8.W);val identity=new GraphicsWorkIdentity;val originX=SInt(32.W);val originY=SInt(32.W)
  val tileLog2=UInt(3.W);val tileWidth=UInt(5.W);val tileHeight=UInt(5.W);val sampleMode=UInt(2.W)
  /** Reserved prefix for other owner data, such as depth/stencil. */
  val reservedBytes=UInt(17.W);val colors=Vec(4,new TypedTileColorView)
}
class TypedTileColorQuad extends Bundle {
  val quad=new FragmentShaderQuad(FragmentShaderConfig(),GraphicsConfig())
  /** Visibility already qualified by the late depth/stencil owner. */
  val samples=Vec(4,UInt(4.W));val blend=new TypedBlendState
}
class TypedTileColorQuadCompletion extends Bundle {
  val identity=new GraphicsWorkIdentity;val sequence=UInt(64.W);val primitiveId=UInt(32.W);val instanceId=UInt(32.W);val partIndex=UInt(3.W)
  val status=UInt(3.W);val touchedSamples=UInt(5.W);val completedLocations=UInt(7.W);val wordWrites=UInt(8.W);val writtenBits=UInt(14.W)
  val faultOffset=UInt(17.W)
}
class TypedTileColorCompletion extends Bundle {
  val identity=new GraphicsWorkIdentity;val status=UInt(3.W);val inputQuads=UInt(99.W);val wordWrites=UInt(110.W);val faultOffset=UInt(17.W)
}
class TypedTileColorMemoryRequest(p: TypedTileColorConfig) extends Bundle {
  val address=UInt(p.addressBits.W);val write=Bool();val fill=Bool();val data=UInt(64.W);val byteMask=UInt(8.W)
}
class TypedTileColorMemoryReply extends Bundle {
  val data=UInt(64.W);val available=Bool();val dirty=Bool();val fault=Bool()
}
class TypedTileColorRefill(p: TypedTileColorConfig) extends Bundle {val address=UInt(p.addressBits.W)}
class TypedTileColorRefillReply extends Bundle {val data=UInt(64.W);val fault=Bool()}

/** Shared preflight before any color or depth/stencil side effect. */
object TypedTileColorRules {
  /** Byte repetition for the supported 1/2/3/4/6/8/12/16-byte encodings.
    * Base-four digits have weight one modulo three. Reduce their bounded sum,
    * then combine its residue with the low one/two bits for six/twelve bytes.
    * This avoids a general variable divider on the row-to-clear-data path.
    */
  def clearIndex(offset: UInt,bytes: UInt,b: Int): UInt={
    def sumTree(xs: Seq[UInt]): UInt=if(xs.size==1)xs.head else{
      val (a,c)=xs.splitAt(xs.size/2);sumTree(a)+&sumTree(c)
    }
    val digits=(0 until offset.getWidth by 2).map(i=>offset(math.min(i+1,offset.getWidth-1),i))
    val sum=sumTree(digits);val maximum=digits.map(d=>(1<<d.getWidth)-1).sum
    val r3=MuxCase(sum(1,0),(1 to maximum/3).reverse.map(k=>(sum>=(k*3).U)->(sum-(k*3).U)(1,0)))
    val r6=Mux(r3(0)===offset(0),r3,r3+&3.U)
    val step=(r3-offset(1,0))(1,0)
    val r12=(r3+&(step+&(step<<1)))(3,0)
    val start=MuxLookup(bytes,0.U(4.W))(Seq(1.U->0.U,2.U->offset(0),3.U->r3,4.U->offset(1,0),
      6.U->r6,8.U->offset(2,0),12.U->r12,16.U->offset(3,0)))
    val increment=MuxLookup(bytes,0.U(4.W))(Seq(1,2,3,4,6,8,12,16).map(n=>n.U->(b%n).U(4.W)))
    val total=start+&increment
    Mux(total>=bytes,total-bytes,total)(3,0)
  }
  def ends(d: TypedTileColorDescriptor): Seq[UInt]=d.colors.map(v=>v.offset+&((1.U(18.W)<<((d.tileLog2<<1)+&d.sampleMode))*TypedColorFormat.bytes(v.format)))
  def invalid(d: TypedTileColorDescriptor,p: TypedTileColorConfig): Bool={
    val edge=1.U(6.W)<<d.tileLog2;val end=ends(d)
    val layout=d.colors.zipWithIndex.map{case(v,i)=> !v.enabled || (TypedColorFormat.valid(v.format) && v.offset(5,0)===0.U &&
      v.offset>=d.reservedBytes && end(i)<=p.bytes.U && v.loadOp<=2.U)}.reduce(_ && _)
    val overlap=(for(a<-0 until 4;b<-a+1 until 4)yield d.colors(a).enabled && d.colors(b).enabled &&
      d.colors(a).offset<end(b) && d.colors(b).offset<end(a)).reduce(_ || _)
    d.version=/=1.U || (d.tileLog2=/=3.U && d.tileLog2=/=4.U) || d.sampleMode>2.U || d.tileWidth===0.U || d.tileHeight===0.U ||
      d.tileWidth>edge || d.tileHeight>edge || d.reservedBytes>p.bytes.U || d.reservedBytes(5,0)=/=0.U || !layout || overlap
  }
  def invalidQuad(input: TypedTileColorQuad,d: TypedTileColorDescriptor): Bool={
    val q=input.quad;val bad=WireDefault(q.identity.asUInt=/=d.identity.asUInt || q.sampleMode=/=d.sampleMode || q.colorSlots>4.U)
    for(c<-0 until 4)when(d.colors(c).enabled && c.U<q.colorSlots && q.colorTypes(c)=/=TypedColorFormat.kind(d.colors(c).format)){bad:=true.B}
    for(l<-0 until 4){val v=q.lanes(l);val x=v.x.pad(33)-d.originX.pad(33);val y=v.y.pad(33)-d.originY.pad(33)
      when((input.samples(l)& ~v.coverageMask).orR || (input.samples(l)>>(1.U<<d.sampleMode)).orR ||
        (v.coverageMask>>(1.U<<d.sampleMode)).orR || ((v.helper || v.discarded) && (input.samples(l).orR || v.coverageMask.orR || v.colorMask.orR)) ||
        (v.colorMask>>q.colorSlots).orR || v.x.pad(33)=/=q.lanes(0).x.pad(33)+(l%2).S || v.y.pad(33)=/=q.lanes(0).y.pad(33)+(l/2).S ||
        (input.samples(l).orR && (x<0.S || y<0.S || x>=d.tileWidth.zext || y>=d.tileHeight.zext))){bad:=true.B}
    }
    bad || q.lanes(0).x(0) || q.lanes(0).y(0) || TypedBlendState.invalid(input.blend)
  }
}
/** Serialized correctness profile for resident raw typed MRT colors. The late
  * visibility owner submits qualified sample masks and initializes/refills load
  * data. Clear/discard tags supply nonresident words, independent component masks
  * preserve packed neighbors, and one quad holds all sample/location order until
  * its completion is consumed. Refills are bounded and irrevocable once offered.
  * Blending serializes destination gather, FP32 arithmetic and typed packing.
  * This service does not implement depth/stencil tests or external image
  * transfer/publication; those owners remain explicit integration requirements.
  */
class TypedTileColorStore(p: TypedTileColorConfig=TypedTileColorConfig(),useBlackBoxes: Boolean=false) extends Module {
  val io=IO(new Bundle {
    val begin=Flipped(Decoupled(new TypedTileColorDescriptor));val end=Flipped(Decoupled(Bool()))
    val in=Flipped(Decoupled(new TypedTileColorQuad));val out=Decoupled(new TypedTileColorQuadCompletion)
    val completion=Decoupled(new TypedTileColorCompletion);val cancel=Input(Bool());val cancelSource=Output(Bool());val busy=Output(Bool())
    val maintenance=Input(Bool());val memory=Flipped(Decoupled(new TypedTileColorMemoryRequest(p)));val reply=Decoupled(new TypedTileColorMemoryReply)
    val refill=Decoupled(new TypedTileColorRefill(p));val refillReply=Flipped(Decoupled(new TypedTileColorRefillReply))
  })
  private object State extends ChiselEnum {
    val idle,validate,clearNext,clearEncode,active,checkQuad,lane,nextLocation,encode,waitEncode,sample,word,read,readReply,refillWait,write,
      blendReadWord,blendGather,blendOffer,blendWait,blendEncode,locationDone,quadDone,emit,maintenance,maintenanceRead,maintenanceWrite,maintenanceReply,finish,complete=Value
  }
  private val phase=RegInit(State.idle);private val descriptor=Reg(new TypedTileColorDescriptor);private val input=Reg(new TypedTileColorQuad)
  private val output=Reg(new TypedTileColorQuadCompletion);private val result=Reg(new TypedTileColorCompletion)
  private val passStatus=RegInit(0.U(3.W));private val quadStatus=RegInit(0.U(3.W));private val faultOffset=RegInit(0.U(17.W))
  private val valid=RegInit(0.U(p.words.W));private val dirty=RegInit(0.U(p.words.W))
  private val clearValues=Reg(Vec(4,UInt(128.W)));private val spans=Reg(Vec(4,UInt(17.W)))
  private val color=RegInit(0.U(2.W));private val lane=RegInit(0.U(2.W));private val sample=RegInit(0.U(2.W));private val word=RegInit(0.U(2.W))
  private val encoded=Reg(UInt(128.W));private val encodedMask=Reg(UInt(128.W));private val address=Reg(UInt(17.W))
  private val writeData=Reg(UInt(64.W));private val writeMask=Reg(UInt(64.W));private val oldData=Reg(UInt(64.W));private val row=Reg(UInt(p.addressBits.W))
  private val quadOwned=RegInit(false.B);private val codecOffered=RegInit(false.B);private val codecPending=RegInit(false.B)
  private val codecRequest=Reg(new TypedColorEncodeRequest);private val codec=Module(new TypedColorEncoder)
  private val blender=Module(new TypedColorBlend);private val blendOffered=RegInit(false.B);private val blendPending=RegInit(false.B)
  private val destinationWords=Reg(Vec(3,UInt(64.W)));private val blendingRead=RegInit(false.B)
  private val blendActive=input.blend.enable && TypedColorFormat.kind(descriptor.colors(color).format)===0.U
  private val gathered=Cat(destinationWords.reverse)>>(address(2,0)<<3)
  blender.io.in.valid:=blendOffered;blender.io.in.bits.source:=input.quad.lanes(lane).colors(color)
  blender.io.in.bits.destination:=gathered(127,0);blender.io.in.bits.format:=descriptor.colors(color).format
  blender.io.in.bits.state:=input.blend;blender.io.in.bits.tag:=color
  blender.io.out.ready:=blendPending
  when(blender.io.in.fire){blendOffered:=false.B;blendPending:=true.B}
  when(blender.io.out.fire){blendPending:=false.B}
  private val refillOffered=RegInit(false.B);private val refillPending=RegInit(false.B)
  private val touched=RegInit(0.U(16.W));private val locations=RegInit(0.U(7.W));private val writes=RegInit(0.U(8.W));private val bits=RegInit(0.U(14.W))
  private val totalQuads=RegInit(0.U(99.W));private val totalWrites=RegInit(0.U(110.W))
  private val maintenanceRequest=Reg(new TypedTileColorMemoryRequest(p));private val maintenanceResult=Reg(new TypedTileColorMemoryReply)
  private val maintenanceOwned=RegInit(false.B)
  private val running=phase=/=State.idle && phase=/=State.complete
  private val stop=passStatus=/=0.U || io.cancel
  blender.io.cancel:=stop && !blendOffered
  private val memoryBanks=Seq.fill((p.words+255)/256)(Module(new L2SramArray(256,64,useBlackBoxes)))
  private val readEnable=WireDefault(false.B);private val writeEnable=WireDefault(false.B)
  private val memoryAddress=WireDefault(row);private val memoryWriteData=WireDefault(writeData)
  private val readBank=RegEnable(memoryAddress.pad(9)>>8,readEnable)
  for((m,i)<-memoryBanks.zipWithIndex){
    m.io.readEnable:=readEnable && (memoryAddress.pad(9)>>8)===i.U;m.io.writeEnable:=writeEnable && (memoryAddress.pad(9)>>8)===i.U
    m.io.readAddress:=memoryAddress;m.io.writeAddress:=memoryAddress;m.io.writeData:=memoryWriteData
  }
  private val memoryReadData=if(memoryBanks.size==1)memoryBanks.head.io.readData else VecInit(memoryBanks.map(_.io.readData))(readBank)
  private def mark(address: UInt): UInt=UIntToOH(address,p.words)
  private def fail(code: UInt,offset: UInt): Unit={when(passStatus===0.U && !io.cancel){passStatus:=code;faultOffset:=offset;when(quadOwned){quadStatus:=code}}}
  private def virtualWord(address: UInt): (Bool,UInt,Bool)={
    val available=WireDefault(false.B);val value=WireDefault(0.U(64.W));val load=WireDefault(false.B)
    for(c<-0 until 4){val v=descriptor.colors(c);val offset=(address<<3)-v.offset
      val selected=v.enabled && (address<<3)>=v.offset && (address<<3)<(v.offset+&spans(c))
      // Repetition continues across64-bit boundaries for3/6/12-byte pixels.
      val bytes=Mux(TypedColorFormat.valid(v.format),TypedColorFormat.bytes(v.format),1.U);val data=Wire(Vec(8,UInt(8.W)))
      for(b<-0 until 8){val index=TypedTileColorRules.clearIndex(offset,bytes,b);data(b):=(clearValues(c)>>(index<<3))(7,0)}
      when(selected){available:=v.loadOp=/=AttachmentLoadOp.load.U;load:=v.loadOp===AttachmentLoadOp.load.U
        value:=Mux(v.loadOp===AttachmentLoadOp.clear.U,data.asUInt,0.U)}
    }
    (available,value,load)
  }
  codec.io.in.valid:=codecOffered;codec.io.in.bits:=codecRequest;codec.io.out.ready:=codecPending
  when(codec.io.in.fire){codecOffered:=false.B;codecPending:=true.B}
  when(codec.io.out.fire){codecPending:=false.B}
  io.refill.valid:=refillOffered;io.refill.bits.address:=row
  when(io.refill.fire){refillOffered:=false.B;refillPending:=true.B}
  io.refillReply.ready:=refillPending
  when(io.refillReply.fire){refillPending:=false.B}
  io.begin.ready:=phase===State.idle && !io.cancel;io.busy:=phase=/=State.idle;io.cancelSource:=running && stop
  io.in.ready:=phase===State.active && !io.maintenance && !stop
  io.end.ready:=phase===State.active && !Mux(io.maintenance,io.memory.valid,io.in.valid) && !stop
  io.memory.ready:=phase===State.active && io.maintenance && !stop
  io.reply.valid:=phase===State.maintenanceReply;io.reply.bits:=maintenanceResult
  io.out.valid:=phase===State.emit;io.out.bits:=output
  io.completion.valid:=phase===State.complete;io.completion.bits:=result
  private def encode(values: Vec[UInt],format: UInt,mask: UInt): Unit={
    codecRequest.rgba:=values;codecRequest.format:=format;codecRequest.colorType:=TypedColorFormat.kind(format);codecRequest.mask:=mask;codecRequest.tag:=color
    codecOffered:=true.B
  }
  private def nextLocation(): Unit={
    when(color===3.U){when(lane===3.U){phase:=State.quadDone}.otherwise{lane:=lane+1.U;phase:=State.lane}}
      .otherwise{color:=color+1.U;phase:=State.nextLocation}
  }
  private def formQuadResult(): Unit={
    output:=0.U.asTypeOf(output);output.identity:=input.quad.identity;output.sequence:=input.quad.sequence;output.primitiveId:=input.quad.primitiveId
    output.instanceId:=input.quad.instanceId;output.partIndex:=input.quad.partIndex;output.status:=Mux(io.cancel && quadStatus===0.U,4.U,quadStatus)
    output.touchedSamples:=PopCount(touched);output.completedLocations:=locations;output.wordWrites:=writes;output.writtenBits:=bits;output.faultOffset:=faultOffset
    phase:=State.emit
  }
  private val shiftedData=encoded.pad(192)<<(address(2,0)<<3);private val shiftedMask=encodedMask.pad(192)<<(address(2,0)<<3)
  private val byteCount=TypedColorFormat.bytes(descriptor.colors(color).format)
  private val wordCount=((address(2,0)+&byteCount+7.U)>>3)
  when(running && io.cancel && passStatus===0.U){passStatus:=4.U;when(quadOwned){quadStatus:=4.U}}
  when(!stop || phase===State.idle || phase===State.emit || phase===State.maintenanceReply || phase===State.finish || phase===State.complete){
    switch(phase){
      is(State.idle){when(io.begin.fire){descriptor:=io.begin.bits;valid:=0.U;dirty:=0.U;passStatus:=0.U;quadStatus:=0.U;faultOffset:=0.U
        totalQuads:=0.U;totalWrites:=0.U;quadOwned:=false.B;maintenanceOwned:=false.B;phase:=State.validate}}
      is(State.validate){
        val ends=TypedTileColorRules.ends(descriptor)
        when(TypedTileColorRules.invalid(descriptor,p)){fail(1.U,0.U);phase:=State.finish}
        .otherwise{for(c<-0 until 4)spans(c):=ends(c)-descriptor.colors(c).offset;color:=0.U;phase:=State.clearNext}
      }
      is(State.clearNext){val v=descriptor.colors(color)
        when(v.enabled && v.loadOp===AttachmentLoadOp.clear.U){encode(v.clear,v.format,15.U);phase:=State.clearEncode}
          .otherwise{clearValues(color):=0.U;when(color===3.U){phase:=State.active}.otherwise{color:=color+1.U}}
      }
      is(State.clearEncode){when(codec.io.out.fire){assert(codec.io.out.bits.status===0.U);clearValues(color):=codec.io.out.bits.value
        when(color===3.U){phase:=State.active}.otherwise{color:=color+1.U;phase:=State.clearNext}}}
      is(State.active){
        when(io.in.fire){input:=io.in.bits;quadOwned:=true.B;quadStatus:=0.U;touched:=0.U;locations:=0.U;writes:=0.U;bits:=0.U
          totalQuads:=totalQuads+1.U;phase:=State.checkQuad}
        when(io.memory.fire){maintenanceRequest:=io.memory.bits;maintenanceOwned:=true.B;row:=io.memory.bits.address;phase:=State.maintenance}
        when(io.end.fire){when(!io.end.bits){fail(5.U,0.U)};phase:=State.finish}
      }
      is(State.checkQuad){
        when(TypedTileColorRules.invalidQuad(input,descriptor)){fail(6.U,0.U);phase:=State.quadDone}
          .otherwise{lane:=0.U;phase:=State.lane}
      }
      is(State.lane){color:=0.U;phase:=State.nextLocation}
      is(State.nextLocation){val v=descriptor.colors(color)
        when(input.samples(lane).orR && v.enabled && input.quad.lanes(lane).colorMask(color) && v.channelMask.orR){
          encode(input.quad.lanes(lane).colors(color),v.format,v.channelMask);phase:=State.waitEncode
        }.otherwise{nextLocation()}
      }
      is(State.waitEncode){when(codec.io.out.fire){assert(codec.io.out.bits.status===0.U)
        encoded:=codec.io.out.bits.value;encodedMask:=codec.io.out.bits.mask;sample:=0.U
        when(codec.io.out.bits.mask.orR){phase:=State.sample}.otherwise{nextLocation()}}}
      is(State.sample){
        when(input.samples(lane)(sample)){
          val x=(input.quad.lanes(lane).x.pad(33)-descriptor.originX.pad(33)).asUInt
          val y=(input.quad.lanes(lane).y.pad(33)-descriptor.originY.pad(33)).asUInt
          val pixel=(y<<descriptor.tileLog2)+x
          address:=descriptor.colors(color).offset+((pixel<<descriptor.sampleMode)+sample)*byteCount
          word:=0.U;phase:=Mux(blendActive,State.blendReadWord,State.word)
        }.otherwise{when(sample===(1.U<<descriptor.sampleMode)-1.U){nextLocation()}.otherwise{sample:=sample+1.U}}
      }
      is(State.word){
        val index=(address>>3)+word
        val mask=(shiftedMask>>(word<<6))(63,0)
        when(!mask.orR){when(word+1.U===wordCount){phase:=State.locationDone}.otherwise{word:=word+1.U}}
          .otherwise{row:=index;writeData:=(shiftedData>>(word<<6))(63,0);writeMask:=mask
            when(blendActive){oldData:=destinationWords(word);phase:=State.write}.otherwise{blendingRead:=false.B;phase:=State.read}}
      }
      is(State.read){
        val (available,value,load)=virtualWord(row)
        when(valid(row)){readEnable:=true.B;phase:=State.readReply}
          .elsewhen(available){oldData:=value;phase:=Mux(blendingRead,State.blendGather,State.write)}
          .elsewhen(load){refillOffered:=true.B;phase:=State.refillWait}
          .otherwise{fail(3.U,row<<3);phase:=State.quadDone}
      }
      is(State.readReply){oldData:=memoryReadData;phase:=Mux(blendingRead,State.blendGather,State.write)}
      is(State.refillWait){when(io.refillReply.fire){when(io.refillReply.bits.fault){fail(3.U,row<<3);phase:=State.quadDone}
        .otherwise{oldData:=io.refillReply.bits.data;phase:=Mux(blendingRead,State.blendGather,State.write)}}}
      is(State.blendReadWord){row:=(address>>3)+word;blendingRead:=true.B;phase:=State.read}
      is(State.blendGather){destinationWords(word):=oldData
        when(word+1.U===wordCount){phase:=State.blendOffer}.otherwise{word:=word+1.U;phase:=State.blendReadWord}}
      is(State.blendOffer){blendOffered:=true.B;phase:=State.blendWait}
      is(State.blendWait){when(blender.io.out.fire){assert(blender.io.out.bits.status===0.U)
        encode(blender.io.out.bits.rgba,descriptor.colors(color).format,descriptor.colors(color).channelMask);phase:=State.blendEncode}}
      is(State.blendEncode){when(codec.io.out.fire){assert(codec.io.out.bits.status===0.U)
        encoded:=codec.io.out.bits.value;encodedMask:=codec.io.out.bits.mask;word:=0.U;phase:=State.word}}
      is(State.write){writeEnable:=true.B;memoryWriteData:=(oldData& ~writeMask)|(writeData&writeMask)
        valid:=valid|mark(row);dirty:=dirty|mark(row);writes:=writes+1.U;bits:=bits+PopCount(writeMask);totalWrites:=totalWrites+1.U
        touched:=touched|(1.U(16.W)<<((lane<<2)+sample))
        when(word+1.U===wordCount){phase:=State.locationDone}.otherwise{word:=word+1.U;phase:=State.word}
      }
      is(State.locationDone){locations:=locations+1.U
        when(sample===(1.U<<descriptor.sampleMode)-1.U){nextLocation()}.otherwise{sample:=sample+1.U;phase:=State.sample}}
      is(State.quadDone){formQuadResult()}
      is(State.emit){when(io.out.fire){quadOwned:=false.B;phase:=Mux(stop,State.finish,State.active)}}
      is(State.maintenance){val (available,value,_)=virtualWord(row)
        maintenanceResult:=0.U.asTypeOf(maintenanceResult);maintenanceResult.dirty:=dirty(row)
        when(maintenanceRequest.fill && (valid(row) || !maintenanceRequest.write || maintenanceRequest.byteMask=/=255.U)){
          maintenanceResult.fault:=true.B;phase:=State.maintenanceReply
        }.elsewhen(valid(row)){readEnable:=true.B;phase:=State.maintenanceRead}
          .otherwise{oldData:=value;maintenanceResult.available:=available
            when(maintenanceRequest.write){when(available || maintenanceRequest.byteMask===255.U){phase:=State.maintenanceWrite
              val mask=Cat((0 until 8).reverse.map(b=>Fill(8,maintenanceRequest.byteMask(b))))
              writeData:=(value& ~mask)|(maintenanceRequest.data&mask)
            }.otherwise{maintenanceResult.fault:=true.B;phase:=State.maintenanceReply}}
            .otherwise{maintenanceResult.data:=value;phase:=State.maintenanceReply}}
      }
      is(State.maintenanceRead){
        maintenanceResult.available:=true.B;maintenanceResult.data:=memoryReadData
        when(maintenanceRequest.write){val mask=Cat((0 until 8).reverse.map(b=>Fill(8,maintenanceRequest.byteMask(b))))
          writeData:=(memoryReadData& ~mask)|(maintenanceRequest.data&mask)}
        phase:=Mux(maintenanceRequest.write,State.maintenanceWrite,State.maintenanceReply)
      }
      is(State.maintenanceWrite){
        writeEnable:=true.B;valid:=valid|mark(row)
        when(!maintenanceRequest.fill){dirty:=dirty|mark(row)}
        phase:=State.maintenanceReply
      }
      is(State.maintenanceReply){when(io.reply.fire){maintenanceOwned:=false.B;phase:=Mux(stop,State.finish,State.active)}}
      is(State.finish){result.identity:=descriptor.identity;result.status:=Mux(io.cancel && passStatus===0.U,4.U,passStatus)
        result.inputQuads:=totalQuads;result.wordWrites:=totalWrites;result.faultOffset:=faultOffset;phase:=State.complete}
      is(State.complete){when(io.completion.fire){phase:=State.idle}}
    }
  }
  when(running && stop && phase=/=State.emit && phase=/=State.maintenanceReply && phase=/=State.finish){
    when(!codecOffered && !codecPending && !refillOffered && !refillPending && !blendOffered && !blendPending){
      when(quadOwned){formQuadResult()}.elsewhen(maintenanceOwned){maintenanceResult:=0.U.asTypeOf(maintenanceResult);maintenanceResult.fault:=true.B;phase:=State.maintenanceReply}
        .otherwise{phase:=State.finish}
    }
  }
}
