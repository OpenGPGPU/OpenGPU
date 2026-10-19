package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.{CachePolicy,GpuConfig}
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}
import opengpu.util.{BlockPrefixAdder,CarrySaveMultiplier}

case class VertexInputFetchConfig(attributes: Int=16,bindings: Int=16) {
  require(attributes>=1 && attributes<=16 && bindings>=1 && bindings<=16)
  val attributeWidth: Int=math.max(1,log2Ceil(attributes))
  val bindingWidth: Int=math.max(1,log2Ceil(bindings))
}

object VertexInputFormat {
  val width=5
  val float32=0;val uint32=1;val sint32=2
  val uint16=3;val sint16=4;val uint8=5;val sint8=6
  val float16=7;val uint8Float=8;val sint8Float=9;val uint16Float=10;val sint16Float=11
  val uint32Float=12;val sint32Float=13;val unorm8=14;val snorm8=15;val unorm16=16;val snorm16=17
  val unorm32=18;val snorm32=19;val unorm2101010=20;val snorm2101010=21
  val uint2101010Float=22;val sint2101010Float=23;val fixed16_16=24
  val version=2
  val formats=Seq("FLOAT32"->float32,"UINT32"->uint32,"SINT32"->sint32,"UINT16"->uint16,"SINT16"->sint16,
    "UINT8"->uint8,"SINT8"->sint8,"FLOAT16"->float16,"UINT8_FLOAT"->uint8Float,"SINT8_FLOAT"->sint8Float,
    "UINT16_FLOAT"->uint16Float,"SINT16_FLOAT"->sint16Float,"UINT32_FLOAT"->uint32Float,"SINT32_FLOAT"->sint32Float,
    "UNORM8"->unorm8,"SNORM8"->snorm8,"UNORM16"->unorm16,"SNORM16"->snorm16,"UNORM32"->unorm32,"SNORM32"->snorm32,
    "UNORM2101010_REV"->unorm2101010,"SNORM2101010_REV"->snorm2101010,
    "UINT2101010_REV_FLOAT"->uint2101010Float,"SINT2101010_REV_FLOAT"->sint2101010Float,"FIXED16_16"->fixed16_16)
  require(formats.map(_._2)==(0 to fixed16_16))
  def packed(format: UInt): Bool=format>=unorm2101010.U && format<=sint2101010Float.U
  def floating(format: UInt): Bool=format===float32.U || format>=float16.U
  def sizeLog2(format: UInt): UInt=MuxLookup(format,2.U(2.W))(Seq(
    uint16,sint16,float16,uint16Float,sint16Float,unorm16,snorm16).map(_.U->1.U(2.W))++
    Seq(uint8,sint8,uint8Float,sint8Float,unorm8,snorm8).map(_.U->0.U(2.W)))
  def expand(word: UInt,format: UInt): UInt=MuxLookup(format,word)(Seq(
    uint16.U->Cat(0.U(16.W),word(15,0)),sint16.U->Cat(Fill(16,word(15)),word(15,0)),
    uint8.U->Cat(0.U(24.W),word(7,0)),sint8.U->Cat(Fill(24,word(7)),word(7,0))))
}

/** Allocation bounds cover complete cache lines. Logical bounds cover only
  * the application-visible bytes, including a final subword. Addresses are
  * virtual; the enclosing owner pins translation and published buffer contents
  * until completion is consumed. This is an internal stream contract, not a
  * memory descriptor layout or an advertised draw ABI.
  */
class VertexInputView extends Bundle {
  val allocationBase=UInt(32.W)
  val allocationLimit=UInt(33.W)
  val base=UInt(32.W)
  val limit=UInt(33.W)
}
class VertexInputBinding extends VertexInputView {
  /** Zero stride repeats one element. Divisor zero selects vertex indexing. */
  val stride=UInt(32.W)
  val divisor=UInt(32.W)
}
class VertexInputAttribute(p: VertexInputFetchConfig) extends Bundle {
  val enabled=Bool()
  val bindingIndex=UInt(p.bindingWidth.W)
  val offset=UInt(32.W)
  val components=UInt(3.W)
  val format=UInt(VertexInputFormat.width.W)
  /** Disabled arrays emit all four supplied values without memory access. */
  val constant=Vec(4,UInt(32.W))
}
class VertexInputDraw(p: VertexInputFetchConfig) extends Bundle {
  val version=UInt(8.W)
  val identity=new GraphicsWorkIdentity
  val count=UInt(32.W)
  val instances=UInt(32.W)
  val firstVertex=UInt(32.W)
  val firstIndex=UInt(32.W)
  val baseVertex=SInt(32.W)
  val baseInstance=UInt(32.W)
  /** 0: arrays; 1/2/3: unsigned 8/16/32-bit elements. */
  val indexFormat=UInt(2.W)
  val restart=Bool()
  val indices=new VertexInputView
  val bindings=Vec(p.bindings,new VertexInputBinding)
  val attributes=Vec(p.attributes,new VertexInputAttribute(p))
}
class VertexInputRecord(p: VertexInputFetchConfig) extends Bundle {
  val identity=new GraphicsWorkIdentity
  val ordinal=UInt(32.W)
  val vertexId=UInt(32.W)
  /** Draw-relative instance ID; base instance is forwarded separately. */
  val instanceId=UInt(32.W)
  val baseInstance=UInt(32.W)
  val restart=Bool()
  val values=Vec(p.attributes,Vec(4,UInt(32.W)))
}
object VertexInputStatus {
  val width=3
  val success=0;val invalidDescriptor=1;val bounds=2;val memoryFault=3;val cancelled=4
}
class VertexInputCompletion extends Bundle {
  val identity=new GraphicsWorkIdentity
  val status=UInt(VertexInputStatus.width.W)
  val vertices=UInt(64.W)
  val restarts=UInt(64.W)
  val indexLines=UInt(64.W)
  val attributeLines=UInt(64.W)
  val reusedReads=UInt(64.W)
  val faultAddress=UInt(32.W)
  val faultAttributeValid=Bool()
  val faultAttribute=UInt(4.W)
  val ordinal=UInt(32.W)
  val instanceId=UInt(32.W)
}

/** Bounded indexed/instanced input acquisition. One privately reserved record
  * and one outstanding line avoid output/memory credit cycles. Separate index
  * and attribute response registers reuse adjacent subwords; both are cleared
  * at every draw. These are not a post-transform vertex cache.
  *
  * Fixed restart is detected before signed base-vertex addition. Instanced
  * bindings fetch baseInstance + floor(instanceId/divisor), using per-binding
  * counters rather than a combinational divider. Address products have three
  * registered cuts. Narrow integer formats expand to canonical 32-bit values;
  * float32 bits are preserved. Input version 2 additionally accepts converted
  * integer, normalized, half, fixed and packed REV components as FP32 bits.
  * Cancel retains offered memory requests and records through their handshakes
  * and drains every accepted response before a held completion permits reuse.
  */
class VertexInputFetcher(config: GpuConfig=GpuConfig(),p: VertexInputFetchConfig=VertexInputFetchConfig()) extends Module {
  require(config.xLen==32)
  val io=IO(new Bundle {
    val draw=Flipped(Decoupled(new VertexInputDraw(p)))
    val out=Decoupled(new VertexInputRecord(p))
    val completion=Decoupled(new VertexInputCompletion)
    val cancel=Input(Bool())
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,1))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,1)))
    val busy=Output(Bool())
  })
  private val Seq(idle,validate,vertex,index,waitIndex,attribute,middle,multiply,addressBase,
    address,waitAttribute,convertAttribute,publish,emit,advance,drain,finish,complete)=Enum(18)
  private val phase=RegInit(idle)
  private val draw=Reg(new VertexInputDraw(p))
  private val record=Reg(new VertexInputRecord(p))
  private val result=Reg(new VertexInputCompletion)
  private val status=RegInit(0.U(VertexInputStatus.width.W))
  private val ordinal=RegInit(0.U(32.W));private val instance=RegInit(0.U(32.W))
  private val attr=RegInit(0.U(p.attributeWidth.W));private val component=RegInit(0.U(2.W))
  private val instanceElement=Reg(Vec(p.bindings,UInt(33.W)))
  private val ratePosition=Reg(Vec(p.bindings,UInt(32.W)))
  private val vertices=RegInit(0.U(64.W));private val restarts=RegInit(0.U(64.W))
  private val indexLines=RegInit(0.U(64.W));private val attributeLines=RegInit(0.U(64.W))
  private val reusedReads=RegInit(0.U(64.W))
  private val faultAddress=Reg(UInt(32.W));private val faultAttributeValid=Reg(Bool())
  private val faultAttribute=Reg(UInt(4.W))
  private val outputValid=RegInit(false.B)
  private val offerValid=RegInit(false.B);private val pending=RegInit(false.B)
  private val request=Reg(new ComputeMemoryRequest(config,64,1))
  private val requestByte=Reg(UInt(32.W));private val requestAttribute=Reg(Bool())
  private val indexValid=RegInit(false.B);private val indexAddress=Reg(UInt(32.W));private val indexData=Reg(UInt(512.W))
  private val attrValid=RegInit(false.B);private val attrAddress=Reg(UInt(32.W));private val attrData=Reg(UInt(512.W))
  private val active=phase=/=idle && phase=/=complete
  private val stop=status=/=0.U || io.cancel
  io.busy:=phase=/=idle
  io.draw.ready:=phase===idle && !io.cancel
  io.out.valid:=outputValid;io.out.bits:=record
  io.completion.valid:=phase===complete;io.completion.bits:=result
  io.memoryRequest.valid:=offerValid;io.memoryRequest.bits:=request
  io.memoryResponse.ready:=pending && io.memoryResponse.bits.transactionId===0.U
  when(io.memoryResponse.valid) {
    assert((pending || io.memoryRequest.fire) && io.memoryResponse.bits.transactionId===0.U,"vertex input response must retain its owned transaction")
  }
  when(io.memoryRequest.fire) {
    offerValid:=false.B;pending:=true.B
    when(requestAttribute){attributeLines:=attributeLines+1.U}.otherwise{indexLines:=indexLines+1.U}
  }
  when(io.memoryResponse.fire){pending:=false.B}
  when(io.out.fire) {
    outputValid:=false.B
    when(record.restart){restarts:=restarts+1.U}.otherwise{vertices:=vertices+1.U}
  }
  private def fail(code: Int,at: UInt,isAttribute: Bool=false.B): Unit={
    when(status===0.U && !io.cancel) {
      status:=code.U;faultAddress:=at;faultAttributeValid:=isAttribute;faultAttribute:=attr
    }
    phase:=drain
  }
  private def validView(v: VertexInputView): Bool=
    v.allocationBase(5,0)===0.U && v.allocationLimit(5,0)===0.U &&
      v.allocationLimit<= (BigInt(1)<<32).U && v.allocationLimit>v.allocationBase &&
      v.base>=v.allocationBase && v.limit>=v.base && v.limit<=v.allocationLimit
  private def nextAttribute(): Unit={
    when(attr===(p.attributes-1).U){phase:=publish}.otherwise{attr:=attr+1.U;phase:=attribute}
  }
  private def queue(at: UInt,isAttribute: Boolean,bytesLog2: UInt): Unit={
    request:=0.U.asTypeOf(request)
    request.address:=Cat(at(31,6),0.U(6.W));request.sizeLog2:=6.U
    request.byteMask:=MuxLookup(bytesLog2,15.U(64.W))(Seq(0.U->1.U(64.W),1.U->3.U(64.W)))<<at(5,0)
    request.cachePolicy:=CachePolicy.cached
    requestByte:=at;requestAttribute:=isAttribute.B;offerValid:=true.B
    phase:=(if(isAttribute)waitAttribute else waitIndex)
  }
  private val currentAttribute=draw.attributes(attr)
  private val attributeWord=Reg(UInt(32.W))
  private val bindingIndex=Mux(currentAttribute.bindingIndex<p.bindings.U,currentAttribute.bindingIndex,0.U)
  private val binding=draw.bindings(bindingIndex)
  private val selectedElement=Mux(binding.divisor===0.U,Cat(0.U(1.W),record.vertexId),instanceElement(bindingIndex))
  private val e=selectedElement(31,0);private val stride=binding.stride
  private val partialLL=CarrySaveMultiplier(e(15,0),stride(15,0),false,false)
  private val partialLH=CarrySaveMultiplier(e(15,0),stride(31,16),false,false)
  private val partialHL=CarrySaveMultiplier(e(31,16),stride(15,0),false,false)
  private val partialHH=CarrySaveMultiplier(e(31,16),stride(31,16),false,false)
  private val size=VertexInputFormat.sizeLog2(currentAttribute.format)
  private val indexSize=Mux(draw.indexFormat===1.U,0.U(2.W),Mux(draw.indexFormat===2.U,1.U(2.W),2.U(2.W)))
  private def consumeIndex(word: UInt,at: UInt): Unit={
    val value=MuxLookup(draw.indexFormat,word)(Seq(1.U->Cat(0.U(24.W),word(7,0)),2.U->Cat(0.U(16.W),word(15,0))))
    val sentinel=MuxLookup(draw.indexFormat,"hffffffff".U(32.W))(Seq(1.U->255.U(32.W),2.U->65535.U(32.W)))
    val biased=value.zext+&draw.baseVertex
    when(draw.restart && value===sentinel) {
      record.restart:=true.B;record.vertexId:=0.U;record.values:=0.U.asTypeOf(record.values)
      phase:=publish
    }.elsewhen(biased<0.S || biased.asUInt>"hffffffff".U) {
      fail(VertexInputStatus.bounds,at)
    }.otherwise {
      record.vertexId:=biased.asUInt;attr:=0.U;phase:=attribute
    }
  }
  private def consumeAttribute(word: UInt): Unit={
    attributeWord:=word;phase:=convertAttribute
  }
  private val ll=Reg(UInt(32.W));private val lh=Reg(UInt(32.W))
  private val hl=Reg(UInt(32.W));private val hh=Reg(UInt(32.W))
  private val mid=Reg(UInt(33.W));private val product=Reg(UInt(64.W))
  private val baseOffset=Reg(UInt(33.W));private val elementAddress=Reg(UInt(65.W))
  private val join=Module(new BlockPrefixAdder(64))
  join.io.a:=Cat(hh,ll);join.io.b:=Cat(0.U(15.W),mid,0.U(16.W));join.io.carryIn:=false.B
  private val wideAddress=elementAddress+&Mux(VertexInputFormat.packed(currentAttribute.format),0.U,component<<size)
  private val byteAddress=wideAddress(31,0)
  private val lineAddress=Cat(byteAddress(31,6),0.U(6.W))
  private val attributeInBounds=wideAddress>=binding.base &&
    wideAddress+(1.U(4.W)<<size)<=binding.limit && wideAddress<(BigInt(1)<<32).U &&
    lineAddress>=binding.allocationBase && (lineAddress+&64.U)<=binding.allocationLimit
  private val indexByteWide=draw.indices.base+&((draw.firstIndex+&ordinal)<<indexSize)
  private val indexByte=indexByteWide(31,0)
  private val indexLine=Cat(indexByte(31,6),0.U(6.W))
  private val indexInBounds=indexByteWide>=draw.indices.base && indexByteWide+(1.U(4.W)<<indexSize)<=draw.indices.limit &&
    indexByteWide<(BigInt(1)<<32).U && indexLine>=draw.indices.allocationBase && (indexLine+&64.U)<=draw.indices.allocationLimit

  when(!stop || phase===idle || phase===drain || phase===finish || phase===complete) {
  switch(phase) {
    is(idle) {
      when(io.draw.fire) {
        draw:=io.draw.bits;status:=0.U;ordinal:=0.U;instance:=0.U;attr:=0.U
        vertices:=0.U;restarts:=0.U;indexLines:=0.U;attributeLines:=0.U;reusedReads:=0.U
        faultAddress:=0.U;faultAttributeValid:=false.B;faultAttribute:=0.U
        indexValid:=false.B;attrValid:=false.B
        for(i<-0 until p.bindings){instanceElement(i):=io.draw.bits.baseInstance;ratePosition(i):=0.U}
        phase:=validate
      }
    }
    is(validate) {
      val work=draw.count=/=0.U && draw.instances=/=0.U
      val attributesValid=draw.attributes.map { a=>
        val b=draw.bindings(Mux(a.bindingIndex<p.bindings.U,a.bindingIndex,0.U))
        val mask=(1.U(4.W)<<VertexInputFormat.sizeLog2(a.format))-1.U
        !a.enabled || (a.bindingIndex<p.bindings.U && a.components>=1.U && a.components<=4.U &&
          a.format<=Mux(draw.version===2.U,VertexInputFormat.fixed16_16.U,VertexInputFormat.sint8.U) &&
          (!VertexInputFormat.packed(a.format) || a.components===4.U) &&
          ((b.base+&a.offset)&mask)===0.U && (b.stride&mask)===0.U)
      }.reduce(_ && _)
      val bindingsValid=(0 until p.bindings).map { i=>
        val used=draw.attributes.map(a=>a.enabled && a.bindingIndex===i.U).reduce(_ || _)
        !used || !work || validView(draw.bindings(i))
      }.reduce(_ && _)
      val indexUsed=work && draw.indexFormat=/=0.U
      val indexMask=(1.U(4.W)<<indexSize)-1.U
      val layout=(draw.version===1.U || draw.version===2.U) && (!draw.restart || draw.indexFormat=/=0.U) && attributesValid && bindingsValid &&
        (!indexUsed || (validView(draw.indices) && (draw.indices.base&indexMask)===0.U))
      val indexEnd=draw.indices.base+&((draw.firstIndex+&draw.count)<<indexSize)
      val arraysEnd=draw.firstVertex+&draw.count
      when(!layout){fail(VertexInputStatus.invalidDescriptor,0.U)}
        .elsewhen(work && Mux(draw.indexFormat===0.U,arraysEnd>(BigInt(1)<<32).U,indexEnd>draw.indices.limit)) {
          fail(VertexInputStatus.bounds,draw.indices.base)
        }.otherwise{phase:=Mux(work,vertex,finish)}
    }
    is(vertex) {
      record:=0.U.asTypeOf(record);record.identity:=draw.identity;record.ordinal:=ordinal
      record.instanceId:=instance;record.baseInstance:=draw.baseInstance
      attr:=0.U
      when(draw.indexFormat===0.U){record.vertexId:=draw.firstVertex+ordinal;phase:=attribute}
        .otherwise{phase:=index}
    }
    is(index) {
      when(!indexInBounds){fail(VertexInputStatus.bounds,indexByte)}
        .elsewhen(indexValid && indexAddress===indexLine) {
          requestByte:=indexByte;reusedReads:=reusedReads+1.U
          consumeIndex((indexData>>(indexByte(5,0)<<3))(31,0),indexByte)
        }.otherwise{queue(indexByte,false,indexSize)}
    }
    is(waitIndex) {
      when(io.memoryResponse.fire && !stop) {
        when(io.memoryResponse.bits.fault){fail(VertexInputStatus.memoryFault,requestByte)}
          .otherwise {
            indexValid:=true.B;indexAddress:=request.address;indexData:=io.memoryResponse.bits.readData
            consumeIndex((io.memoryResponse.bits.readData>>(requestByte(5,0)<<3))(31,0),requestByte)
          }
      }
    }
    is(attribute) {
      when(!currentAttribute.enabled){record.values(attr):=currentAttribute.constant;nextAttribute()}
        .otherwise {
          for(k<-0 until 4)record.values(attr)(k):=(if(k==3)Mux(VertexInputFormat.floating(currentAttribute.format),"h3f800000".U,1.U)else 0.U)
          when(selectedElement(32)){fail(VertexInputStatus.bounds,binding.base,true.B)}
            .otherwise {
              ll:=partialLL;lh:=partialLH;hl:=partialHL;hh:=partialHH
              baseOffset:=binding.base+&currentAttribute.offset;component:=0.U;phase:=middle
            }
        }
    }
    is(middle){mid:=lh+&hl;phase:=multiply}
    is(multiply){product:=join.io.sum(63,0);phase:=addressBase}
    is(addressBase){elementAddress:=product+&baseOffset;phase:=address}
    is(address) {
      when(!attributeInBounds){fail(VertexInputStatus.bounds,byteAddress,true.B)}
        .elsewhen(attrValid && attrAddress===lineAddress) {
          reusedReads:=reusedReads+1.U;consumeAttribute((attrData>>(byteAddress(5,0)<<3))(31,0))
        }.otherwise{queue(byteAddress,true,size)}
    }
    is(convertAttribute) {
      record.values(attr)(component):=VertexInputConversion(attributeWord,currentAttribute.format,component)
      when(component===currentAttribute.components-1.U){nextAttribute()}
        .otherwise{component:=component+1.U;phase:=address}
    }
    is(waitAttribute) {
      when(io.memoryResponse.fire && !stop) {
        when(io.memoryResponse.bits.fault){fail(VertexInputStatus.memoryFault,requestByte,true.B)}
          .otherwise {
            attrValid:=true.B;attrAddress:=request.address;attrData:=io.memoryResponse.bits.readData
            consumeAttribute((io.memoryResponse.bits.readData>>(requestByte(5,0)<<3))(31,0))
          }
      }
    }
    is(publish){outputValid:=true.B;phase:=emit}
    is(emit){when(io.out.fire){phase:=advance}}
    is(advance) {
      when(ordinal===draw.count-1.U) {
        when(instance===draw.instances-1.U){phase:=finish}
          .otherwise {
            instance:=instance+1.U;ordinal:=0.U;phase:=vertex
            for(i<-0 until p.bindings) {
              when(draw.bindings(i).divisor=/=0.U) {
                when(ratePosition(i)===draw.bindings(i).divisor-1.U) {
                  ratePosition(i):=0.U;instanceElement(i):=instanceElement(i)+1.U
                }.otherwise{ratePosition(i):=ratePosition(i)+1.U}
              }
            }
          }
      }.otherwise{ordinal:=ordinal+1.U;phase:=vertex}
    }
    is(drain){when(!offerValid && !pending && !outputValid){phase:=finish}}
    is(finish) {
      result.identity:=draw.identity;result.status:=Mux(io.cancel && status===0.U,VertexInputStatus.cancelled.U,status)
      result.vertices:=vertices;result.restarts:=restarts
      result.indexLines:=indexLines;result.attributeLines:=attributeLines;result.reusedReads:=reusedReads
      result.faultAddress:=faultAddress;result.faultAttributeValid:=faultAttributeValid;result.faultAttribute:=faultAttribute
      result.ordinal:=ordinal;result.instanceId:=instance;phase:=complete
    }
    is(complete){when(io.completion.fire){phase:=idle}}
  }
  }
  when(active && stop && phase=/=drain && phase=/=finish) {phase:=drain}
  when(active && io.cancel && status===0.U) {
    status:=VertexInputStatus.cancelled.U;faultAddress:=0.U;faultAttributeValid:=false.B
  }
}
