package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.{CachePolicy,GpuConfig}
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}

/** Copy one bounded rectangular region between two resolved image levels.
  *
  * Both views must describe the same uncompressed format and the same target:
  * 2D, 2D array,3D or buffer. Array copies advance along `layer`;3D copies
  * advance along `z`; buffer copies advance along `x` over `bufferTexels`.
  * The caller guarantees that the source and destination byte spans do not
  * overlap; GLES leaves self-overlap undefined and this owner does not detect
  * it. At most4096 texels are copied per request. Packed texels of1,2,
  * 3,4,6,8,12 or16 bytes span at most two64-byte lines.
  */
class TextureImageRegionCopyRequest extends Bundle {
  val tag=UInt(32.W);val format=UInt(TextureImageFormat.width.W)
  val source=new TextureImageLevelView;val destination=new TextureImageLevelView
  val sourceX=UInt(14.W);val sourceY=UInt(14.W);val sourceZ=UInt(14.W);val sourceLayer=UInt(16.W)
  val destinationX=UInt(14.W);val destinationY=UInt(14.W)
  val destinationZ=UInt(14.W);val destinationLayer=UInt(16.W)
  val regionWidth=UInt(14.W);val regionHeight=UInt(14.W);val regionDepth=UInt(14.W);val regionLayers=UInt(16.W)
}
class TextureImageRegionCopyResult extends Bundle {
  val tag=UInt(32.W);val status=UInt(3.W)
  val texels=UInt(13.W);val bytes=UInt(32.W);val readLines=UInt(16.W);val writeLines=UInt(16.W)
  val faultSource=UInt(2.W);val faultAddressValid=Bool();val faultAddress=UInt(32.W)
}
object TextureImageRegionCopyStatus {
  val ok=0;val view=1;val coordinate=2;val bounds=3;val cancelled=4;val memory=5
}

class TextureImageRegionCopyUnit(config: GpuConfig=GpuConfig(),p: TextureImageAddressConfig=TextureImageAddressConfig()) extends Module {
  private val maxTexels=4096
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new TextureImageRegionCopyRequest));val out=Decoupled(new TextureImageRegionCopyResult)
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,4))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,4)))
    val cancel=Input(Bool());val invalidate=Input(Bool());val busy=Output(Bool())
  })
  private object State extends ChiselEnum {
    val idle,check,offerAddresses,waitAddresses,offerRead,waitRead,offerRead2,waitRead2,
      offerWrite,waitWrite,offerWrite2,waitWrite2,advance,finish,complete=Value
  }
  private val phase=RegInit(State.idle)
  private val request=Reg(new TextureImageRegionCopyRequest)
  private val result=Reg(new TextureImageRegionCopyResult)
  private val status=RegInit(0.U(3.W));private val faultSource=RegInit(0.U(2.W))
  private val faultValid=RegInit(false.B);private val faultAddress=Reg(UInt(32.W))
  private val x=Reg(UInt(14.W));private val y=Reg(UInt(14.W));private val plane=Reg(UInt(16.W))
  private val texels=RegInit(0.U(13.W));private val bytes=RegInit(0.U(32.W))
  private val readLines=RegInit(0.U(16.W));private val writeLines=RegInit(0.U(16.W))
  private val sourceAddress=Reg(UInt(32.W));private val destinationAddress=Reg(UInt(32.W))
  private val texelBytes=Reg(UInt(5.W))
  private val line1=Reg(UInt(512.W));private val line2=Reg(UInt(512.W));private val crossing=RegInit(false.B)
  private val offered=RegInit(false.B);private val pending=RegInit(false.B)
  private val memory=Reg(new ComputeMemoryRequest(config,64,4))

  private val sourceUnit=Module(new TextureImageAddressUnit(p,pipelineBounds=true))
  private val destinationUnit=Module(new TextureImageAddressUnit(p,pipelineBounds=true))

  private val active=phase=/=State.idle && phase=/=State.complete
  private val stop=status=/=0.U || io.cancel
  private val array=request.source.target===TextureImageTarget.array2D.U
  private val volume=request.source.target===TextureImageTarget.image3D.U
  private val buffer=request.source.target===TextureImageTarget.buffer.U
  private val outerCount=Mux(array,request.regionLayers,request.regionDepth)
  private val texelSize=TextureImageFormat.bytes(request.format)
  private val packed=Seq(1,2,3,4,6,8,12,16).map(n=>texelSize===n.U).reduce(_ || _)
  private val targetLegal=(request.source.target===TextureImageTarget.image2D.U ||
    request.source.target===TextureImageTarget.array2D.U || volume || buffer) &&
    request.destination.target===request.source.target
  private val viewLegal=TextureImageFormat.valid(request.format) &&
    !TextureImageFormat.block4x4(request.format) && packed &&
    !request.source.block4x4 && !request.destination.block4x4 &&
    request.source.blockBytes===texelSize && request.destination.blockBytes===texelSize &&
    request.source.samples===0.U && request.destination.samples===0.U
  private val shapeLegal=request.regionWidth=/=0.U && request.regionHeight=/=0.U &&
    Mux(buffer,request.regionHeight===1.U && request.regionDepth===1.U && request.regionLayers===1.U,
      Mux(volume,request.regionLayers===1.U && request.regionDepth=/=0.U,
        Mux(array,request.regionDepth===1.U && request.regionLayers=/=0.U,
          request.regionDepth===1.U && request.regionLayers===1.U)))
  private val extents=request.regionWidth*request.regionHeight*Mux(array,request.regionLayers,request.regionDepth)
  private def within(origin: UInt,extent: UInt,limit: UInt): Bool=
    (origin.pad(32)+&extent.pad(32))<=limit.pad(32)
  private val sourceBounded=Mux(buffer,within(request.sourceX,request.regionWidth,request.source.bufferTexels),
      within(request.sourceX,request.regionWidth,request.source.texWidth)) &&
    within(request.sourceY,request.regionHeight,request.source.texHeight) &&
    Mux(volume,within(request.sourceZ,request.regionDepth,request.source.texDepth),
      Mux(array,within(request.sourceLayer,request.regionLayers,request.source.layers),
        request.sourceZ===0.U && request.sourceLayer===0.U)) &&
    (volume || array || request.sourceLayer===0.U) && (volume || request.sourceZ===0.U)
  private val destinationBounded=Mux(buffer,within(request.destinationX,request.regionWidth,request.destination.bufferTexels),
      within(request.destinationX,request.regionWidth,request.destination.texWidth)) &&
    within(request.destinationY,request.regionHeight,request.destination.texHeight) &&
    Mux(volume,within(request.destinationZ,request.regionDepth,request.destination.texDepth),
      Mux(array,within(request.destinationLayer,request.regionLayers,request.destination.layers),
        request.destinationZ===0.U && request.destinationLayer===0.U)) &&
    (volume || array || request.destinationLayer===0.U) && (volume || request.destinationZ===0.U)

  private val sourceX=request.sourceX.pad(32)+&x.pad(32)
  private val sourceY=request.sourceY.pad(32)+&y.pad(32)
  private val destinationX=request.destinationX.pad(32)+&x.pad(32)
  private val destinationY=request.destinationY.pad(32)+&y.pad(32)
  sourceUnit.io.in.valid:=phase===State.offerAddresses
  sourceUnit.io.in.bits:=0.U.asTypeOf(sourceUnit.io.in.bits)
  sourceUnit.io.in.bits.tag:=request.tag;sourceUnit.io.in.bits.view:=request.source
  sourceUnit.io.in.bits.x:=sourceX(31,0);sourceUnit.io.in.bits.y:=sourceY(31,0)
  sourceUnit.io.in.bits.z:=Mux(volume,(request.sourceZ.pad(32)+&plane.pad(32))(31,0),0.U)
  sourceUnit.io.in.bits.layer:=Mux(array,(request.sourceLayer.pad(32)+&plane.pad(32))(31,0),0.U)
  destinationUnit.io.in.valid:=phase===State.offerAddresses
  destinationUnit.io.in.bits:=0.U.asTypeOf(destinationUnit.io.in.bits)
  destinationUnit.io.in.bits.tag:=request.tag;destinationUnit.io.in.bits.view:=request.destination
  destinationUnit.io.in.bits.x:=destinationX(31,0);destinationUnit.io.in.bits.y:=destinationY(31,0)
  destinationUnit.io.in.bits.z:=Mux(volume,(request.destinationZ.pad(32)+&plane.pad(32))(31,0),0.U)
  destinationUnit.io.in.bits.layer:=Mux(array,(request.destinationLayer.pad(32)+&plane.pad(32))(31,0),0.U)
  sourceUnit.io.out.ready:=phase===State.waitAddresses && destinationUnit.io.out.valid
  destinationUnit.io.out.ready:=phase===State.waitAddresses && sourceUnit.io.out.valid

  private def fail(code: UInt,where: UInt,addressValid: Bool=false.B,address: UInt=0.U): Unit={
    when(status===0.U && !io.cancel){status:=code;faultSource:=where;faultValid:=addressValid;faultAddress:=address}
  }
  private val sourceOffset=sourceAddress(5,0)
  private val destinationOffset=destinationAddress(5,0)
  private val destinationCrossing=(destinationOffset+&texelBytes)>64.U
  private val destinationFirst=Mux(destinationCrossing,64.U-destinationOffset,texelBytes.pad(7))
  private val lineWindow=(line1>>(sourceOffset<<3))(127,0)
  private val value=lineWindow |
    Mux(crossing,(line2<<((64.U-sourceOffset)<<3))(127,0),0.U(128.W))
  private val firstData=(value<<(destinationOffset<<3))(511,0)
  private val secondData=(value>>(destinationFirst<<3)).pad(512)(511,0)
  private def lowMask(bits: UInt): UInt=(((BigInt(1)<<64)-1).U>>(64.U-bits))(63,0)
  private val firstMask=(lowMask(destinationFirst)<<destinationOffset)(63,0)
  private val secondMask=lowMask(texelBytes-destinationFirst)

  private def offer(line: UInt,isWrite: Bool,data: UInt,mask: UInt): Unit={
    memory:=0.U.asTypeOf(memory);memory.address:=line;memory.sizeLog2:=6.U
    memory.cachePolicy:=CachePolicy.uncached;memory.byteMask:=mask;memory.writeData:=data;memory.isWrite:=isWrite
    offered:=true.B
  }
  private def memoryFault(line: UInt,where: UInt): Unit={
    fail(TextureImageRegionCopyStatus.memory.U,where,true.B,line);phase:=State.finish
  }

  switch(phase){
    is(State.idle){when(io.in.fire){
      assert(!offered && !pending && !sourceUnit.io.busy && !destinationUnit.io.busy,
        "region copy abandoned address or memory ownership")
      request:=io.in.bits;status:=0.U;faultSource:=0.U;faultValid:=false.B;faultAddress:=0.U
      texels:=0.U;bytes:=0.U;readLines:=0.U;writeLines:=0.U;line1:=0.U;line2:=0.U
      crossing:=false.B;x:=0.U;y:=0.U;plane:=0.U;phase:=State.check
    }}
    is(State.check){
      when(stop){phase:=State.finish}
        .elsewhen(!(viewLegal && targetLegal)){fail(TextureImageRegionCopyStatus.view.U,0.U);phase:=State.finish}
        .elsewhen(!(shapeLegal && extents<=(maxTexels.U) && extents=/=0.U && sourceBounded && destinationBounded)){
          fail(TextureImageRegionCopyStatus.coordinate.U,0.U);phase:=State.finish}
        .otherwise{phase:=State.offerAddresses}
    }
    is(State.offerAddresses){when(sourceUnit.io.in.fire && destinationUnit.io.in.fire){phase:=State.waitAddresses}}
    is(State.waitAddresses){when(sourceUnit.io.out.valid && destinationUnit.io.out.valid){
      val sourceStatus=sourceUnit.io.out.bits.status;val destinationStatus=destinationUnit.io.out.bits.status
      when(sourceStatus=/=0.U){fail(sourceStatus,1.U);phase:=State.finish}
        .elsewhen(destinationStatus=/=0.U){fail(destinationStatus,2.U);phase:=State.finish}
        .otherwise{
          sourceAddress:=sourceUnit.io.out.bits.address;destinationAddress:=destinationUnit.io.out.bits.address
          texelBytes:=sourceUnit.io.out.bits.bytes
          crossing:=(sourceUnit.io.out.bits.address(5,0)+&sourceUnit.io.out.bits.bytes)>64.U
          phase:=State.offerRead
        }
    }}
    is(State.offerRead){
      when(stop){phase:=State.finish}.otherwise{
        offer(((sourceAddress>>6)<<6)(31,0),false.B,0.U,Fill(64,1.U(1.W)));phase:=State.waitRead}
    }
    is(State.waitRead){when(io.memoryResponse.fire){
      when(io.memoryResponse.bits.fault){memoryFault(memory.address,1.U)}
        .otherwise{line1:=io.memoryResponse.bits.readData;readLines:=readLines+1.U
          phase:=Mux(crossing,State.offerRead2,State.offerWrite)}
    }}
    is(State.offerRead2){
      when(stop){phase:=State.finish}.otherwise{
        offer((((sourceAddress>>6)<<6)+64.U)(31,0),false.B,0.U,Fill(64,1.U(1.W)));phase:=State.waitRead2}
    }
    is(State.waitRead2){when(io.memoryResponse.fire){
      when(io.memoryResponse.bits.fault){memoryFault(memory.address,1.U)}
        .otherwise{line2:=io.memoryResponse.bits.readData;readLines:=readLines+1.U;phase:=State.offerWrite}
    }}
    is(State.offerWrite){
      when(stop){phase:=State.finish}.otherwise{
        offer(((destinationAddress>>6)<<6)(31,0),true.B,firstData,firstMask);phase:=State.waitWrite}
    }
    is(State.waitWrite){when(io.memoryResponse.fire){
      when(io.memoryResponse.bits.fault){memoryFault(memory.address,2.U)}
        .otherwise{writeLines:=writeLines+1.U;phase:=Mux(destinationCrossing,State.offerWrite2,State.advance)}
    }}
    is(State.offerWrite2){
      when(stop){phase:=State.finish}.otherwise{
        offer((((destinationAddress>>6)<<6)+64.U)(31,0),true.B,secondData,secondMask);phase:=State.waitWrite2}
    }
    is(State.waitWrite2){when(io.memoryResponse.fire){
      when(io.memoryResponse.bits.fault){memoryFault(memory.address,2.U)}
        .otherwise{writeLines:=writeLines+1.U;phase:=State.advance}
    }}
    is(State.advance){
      texels:=texels+1.U;bytes:=(bytes+texelBytes)(31,0)
      when(x+1.U===request.regionWidth){x:=0.U
        when(y+1.U===request.regionHeight){y:=0.U
          when(plane+1.U===outerCount){phase:=State.finish}.otherwise{plane:=plane+1.U;phase:=State.offerAddresses}
        }.otherwise{y:=y+1.U;phase:=State.offerAddresses}
      }.otherwise{x:=x+1.U;phase:=State.offerAddresses}
    }
    is(State.finish){
      assert(!offered && !pending && !sourceUnit.io.busy && !destinationUnit.io.busy,
        "region copy completion escaped drain")
      result:=0.U.asTypeOf(result);result.tag:=request.tag
      result.status:=Mux(io.cancel && status===0.U,TextureImageRegionCopyStatus.cancelled.U,status)
      result.texels:=texels;result.bytes:=bytes;result.readLines:=readLines;result.writeLines:=writeLines
      result.faultSource:=faultSource;result.faultAddressValid:=faultValid
      result.faultAddress:=Mux(faultValid,faultAddress,0.U)
      phase:=State.complete
    }
    is(State.complete){when(io.out.fire){phase:=State.idle}}
  }
  when(active && io.cancel && status===0.U){status:=TextureImageRegionCopyStatus.cancelled.U;faultSource:=0.U}
  when(offered && io.memoryRequest.fire){offered:=false.B;pending:=true.B}
  when(io.memoryResponse.fire){pending:=false.B}
  io.in.ready:=phase===State.idle && !io.cancel
  io.out.valid:=phase===State.complete;io.out.bits:=result;io.busy:=phase=/=State.idle
  io.memoryRequest.valid:=offered;io.memoryRequest.bits:=memory
  io.memoryResponse.ready:=pending
}
