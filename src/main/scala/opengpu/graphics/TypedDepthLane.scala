package opengpu.graphics

import chisel3._
import chisel3.util._

/** A depth/stencil word reply also reports first pass-local initialization. */
class TypedDepthWordResponse extends TypedTileWordResponse { val initialized=Bool() }

/** One pixel lane, with ordered samples and stencil-before-depth evaluation.
  * The shared word owner handles initialization and atomic masked updates.
  */
class TypedDepthLane(lane: Int) extends Module {
  require(lane>=0 && lane<4)
  val io=IO(new Bundle {
    val start=Input(Bool());val cancel=Input(Bool())
    val descriptor=Input(new TypedTilePixelDescriptor);val input=Input(new TypedTilePixelQuad)
    val request=Decoupled(new TypedTileWordRequest);val response=Flipped(Decoupled(new TypedDepthWordResponse))
    val done=Output(Bool());val fault=Output(Bool());val faultOffset=Output(UInt(17.W))
    val qualified=Output(UInt(4.W));val tested=Output(UInt(5.W));val passing=Output(UInt(5.W))
    val stencilRejected=Output(UInt(5.W));val depthRejected=Output(UInt(5.W))
    val depthUpdates=Output(UInt(5.W));val stencilUpdates=Output(UInt(5.W))
    val reads=Output(UInt(6.W));val fills=Output(UInt(6.W));val writes=Output(UInt(6.W))
  })
  private object State extends ChiselEnum {
    val idle,prepareIndex,sample,stencilRead,stencilTest,depthRead,evaluate,writePrimary,writeSecondary,advance,memoryWait,done=Value
  }
  private val phase=RegInit(State.idle);private val sample=RegInit(0.U(2.W))
  private val qualified=RegInit(0.U(4.W));private val tested=RegInit(0.U(5.W));private val passing=RegInit(0.U(5.W))
  private val stencilRejected=RegInit(0.U(5.W));private val depthRejected=RegInit(0.U(5.W))
  private val depthUpdates=RegInit(0.U(5.W));private val stencilUpdates=RegInit(0.U(5.W))
  private val reads=RegInit(0.U(6.W));private val fills=RegInit(0.U(6.W));private val writes=RegInit(0.U(6.W))
  private val fault=RegInit(false.B);private val faultOffset=RegInit(0.U(17.W))
  private val primaryWord=Reg(UInt(64.W));private val secondaryWord=Reg(UInt(64.W));private val primaryLoaded=RegInit(false.B)
  private val stencilPassed=Reg(Bool());private val oldStencil=Reg(UInt(8.W));private val newStencil=Reg(UInt(8.W))
  private val depthPlan=Reg(Bool());private val primaryStencilPlan=Reg(Bool());private val secondaryStencilPlan=Reg(Bool())
  private val offered=RegInit(false.B);private val pending=RegInit(false.B)
  private val request=Reg(new TypedTileWordRequest);private val continuation=Reg(State())
  private val readSecondary=Reg(Bool());private val updates=Reg(UInt(2.W))
  private val descriptor=io.descriptor;private val input=io.input;private val pixel=input.color.quad.lanes(lane)
  private val stencilSecondary= !TypedDepthFormat.stencil(descriptor.depth.format)
  private val doStencil=input.state.stencilEnable && (TypedDepthFormat.stencil(descriptor.depth.format) || descriptor.stencil.format===TypedDepthFormat.s8.U)
  private val doDepth=input.state.depthEnable && TypedDepthFormat.depth(descriptor.depth.format)
  private val face=Mux(input.color.quad.frontFacing,input.state.front,input.state.back)
  // Separate signed coordinate subtraction from row/sample address formation.
  // Preserve the original widths; preparation adds one setup clock per pixel.
  private val x=Reg(UInt(33.W));private val y=Reg(UInt(33.W))
  private val baseIndex=Reg(chiselTypeOf(((y<<descriptor.colors.tileLog2)+x)<<descriptor.colors.sampleMode))
  private val index=baseIndex+sample
  private def address(view: TypedDepthView): UInt=(view.offset+index*TypedDepthFormat.bytes(view.format))(16,0)
  // Capture sample addresses before reads, comparisons and masked write data
  // selection, reusing the existing sample-admission clock.
  private val primaryAddress=Reg(UInt(17.W));private val secondaryAddress=Reg(UInt(17.W))
  private val preparation=Module(new PreparedTypedDepths)
  preparation.io.in.valid:=io.start;preparation.io.in.bits.format:=descriptor.depth.format
  preparation.io.in.bits.tag:=0.U
  for(s<-0 until 4)preparation.io.in.bits.raw(s):=
    Mux(pixel.sampleDepthValid && !pixel.depthOverride,pixel.sampleDepth(s),pixel.depth)
  private val prepared=RegInit(false.B)
  when(preparation.io.out.valid){prepared:=true.B}
  when(io.start){prepared:=false.B}
  private val sourceDepth=preparation.io.out.bits.values(sample)
  when(phase===State.evaluate && doDepth){
    assert(prepared || preparation.io.out.valid,"sample depth used before vector preparation completed")
  }
  private val stop=io.cancel || fault
  io.done:=phase===State.done;io.fault:=fault;io.faultOffset:=faultOffset
  io.qualified:=qualified;io.tested:=tested;io.passing:=passing;io.stencilRejected:=stencilRejected;io.depthRejected:=depthRejected
  io.depthUpdates:=depthUpdates;io.stencilUpdates:=stencilUpdates;io.reads:=reads;io.fills:=fills;io.writes:=writes
  io.request.valid:=offered;io.request.bits:=request;io.response.ready:=pending
  when(io.request.fire){offered:=false.B;pending:=true.B}
  when(io.response.fire){
    pending:=false.B;phase:=continuation
    when(io.response.bits.initialized){fills:=fills+1.U}
    when(io.response.bits.fault){fault:=true.B;faultOffset:=request.address<<3}.otherwise{
      when(request.write){writes:=writes+1.U
        when(updates(0)){depthUpdates:=depthUpdates+1.U};when(updates(1)){stencilUpdates:=stencilUpdates+1.U}
      }.otherwise{reads:=reads+1.U
        when(readSecondary){secondaryWord:=io.response.bits.data}.otherwise{primaryWord:=io.response.bits.data;primaryLoaded:=true.B}}
    }
  }
  private def memory(write: Boolean,byteAddress: UInt,data: UInt,mask: UInt,next: State.Type,flags: UInt=0.U): Unit={
    assert(!offered && !pending)
    request.address:=byteAddress>>3;request.write:=write.B;request.data:=data;request.mask:=mask;request.tag:=0.U
    updates:=flags;offered:=true.B;continuation:=next;phase:=State.memoryWait
  }
  private def read(secondary: Bool,next: State.Type): Unit={
    readSecondary:=secondary;memory(false,Mux(secondary,secondaryAddress,primaryAddress),0.U,0.U,next)
  }
  private def advance(): Unit={
    when(sample===(1.U<<descriptor.colors.sampleMode)-1.U){phase:=State.done}
      .otherwise{sample:=sample+1.U;phase:=State.sample}
  }
  when(!stop){switch(phase){
    is(State.prepareIndex){
      baseIndex:=((y<<descriptor.colors.tileLog2)+x)<<descriptor.colors.sampleMode
      phase:=State.sample
    }
    is(State.sample){when(input.color.samples(lane)(sample)){
      primaryAddress:=address(descriptor.depth);secondaryAddress:=address(descriptor.stencil)
      tested:=tested+1.U;primaryLoaded:=false.B;primaryWord:=0.U;secondaryWord:=0.U;oldStencil:=0.U;stencilPassed:=true.B
      phase:=Mux(doStencil,State.stencilRead,State.depthRead)
    }.otherwise{advance()}}
    is(State.stencilRead){read(stencilSecondary,State.stencilTest)}
    is(State.stencilTest){
      val record=Mux(stencilSecondary,secondaryWord>>(secondaryAddress(2,0)<<3),primaryWord>>(primaryAddress(2,0)<<3))
      val format=Mux(stencilSecondary,descriptor.stencil.format,descriptor.depth.format)
      val old=(record>>TypedDepthFormat.stencilShift(format))(7,0)
      val pass=TypedDepthFormat.compare(face.reference&face.readMask,old&face.readMask,face.function)
      oldStencil:=old;stencilPassed:=pass
      when(!pass){stencilRejected:=stencilRejected+1.U;phase:=State.evaluate}.otherwise{phase:=State.depthRead}
    }
    is(State.depthRead){when(!doDepth || primaryLoaded){phase:=State.evaluate}.otherwise{read(false.B,State.evaluate)}}
    is(State.evaluate){
      val old=(primaryWord>>(primaryAddress(2,0)<<3))(31,0)&TypedDepthFormat.depthMask(descriptor.depth.format)
      val dp= !doDepth || !stencilPassed || TypedDepthFormat.compare(sourceDepth,old,input.state.depthFunction,TypedDepthFormat.floating(descriptor.depth.format))
      val pass=stencilPassed && dp
      val operation=Mux(!stencilPassed,face.fail,Mux(!dp,face.depthFail,face.depthPass))
      val next=TypedDepthFormat.operation(oldStencil,face.reference,operation)
      val masked=(next&face.writeMask)|(oldStencil& ~face.writeMask)
      val writeStencil=doStencil && masked=/=oldStencil
      newStencil:=masked;depthPlan:=doDepth && pass && input.state.depthWrite
      primaryStencilPlan:=writeStencil && !stencilSecondary;secondaryStencilPlan:=writeStencil && stencilSecondary
      when(pass){passing:=passing+1.U;qualified:=qualified|(1.U(4.W)<<sample)}
      when(stencilPassed && !dp){depthRejected:=depthRejected+1.U};phase:=State.writePrimary
    }
    is(State.writePrimary){when(depthPlan || primaryStencilPlan){
      val shift=TypedDepthFormat.stencilShift(descriptor.depth.format)
      val mask=Mux(depthPlan,TypedDepthFormat.depthMask(descriptor.depth.format).pad(64),0.U)|Mux(primaryStencilPlan,255.U(64.W)<<shift,0.U)
      val value=Mux(depthPlan,sourceDepth.pad(64),0.U)|Mux(primaryStencilPlan,newStencil.pad(64)<<shift,0.U)
      val offset=primaryAddress(2,0)<<3
      // Send only this sample's fields; neighboring lanes may share the word.
      memory(true,primaryAddress,(value<<offset)(63,0),(mask<<offset)(63,0),State.writeSecondary,Cat(primaryStencilPlan,depthPlan))
    }.otherwise{phase:=State.writeSecondary}}
    is(State.writeSecondary){when(secondaryStencilPlan){
      val offset=secondaryAddress(2,0)<<3
      memory(true,secondaryAddress,(newStencil.pad(64)<<offset)(63,0),(255.U(64.W)<<offset)(63,0),State.advance,2.U)
    }.otherwise{advance()}}
    is(State.advance){advance()}
  }}
  // Private offers remain stable until the shared owner accepts and replies.
  when(stop && !offered && !pending){phase:=State.done}
  when(io.start){
    assert(!offered && !pending)
    sample:=0.U;qualified:=0.U;tested:=0.U;passing:=0.U;stencilRejected:=0.U;depthRejected:=0.U
    x:=(pixel.x.pad(33)-descriptor.colors.originX.pad(33)).asUInt
    y:=(pixel.y.pad(33)-descriptor.colors.originY.pad(33)).asUInt
    depthUpdates:=0.U;stencilUpdates:=0.U;reads:=0.U;fills:=0.U;writes:=0.U;fault:=false.B;faultOffset:=0.U;phase:=State.prepareIndex
  }
}
