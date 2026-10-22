package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.{CachePolicy,GpuConfig}
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}

/** One immutable kernel/test/blend state, with private scratch for up to four
  * concurrent tile engines. Engine0 uses fragment.scratch; engines1..3 use
  * scratch(0..2). Uniform tails must be published in every active view.
  */
class TypedFragmentState(f: FragmentShaderConfig) extends Bundle {
  val version=UInt(8.W);val engineCount=UInt(3.W)
  val fragment=new FragmentShaderDraw(f);val tests=new TypedLateTestState;val blend=new TypedBlendState
  val scratch=Vec(3,new VertexInputView)
}
object TypedFragmentStateLayout {
  val magic=BigInt("47465301",16);val words=128;val bytes=512;val engines=4
  def scratch(d: TypedFragmentState,e: Int): VertexInputView={require(e>=0 && e<engines);if(e==0)d.fragment.scratch else d.scratch(e-1)}
  def fields(d: TypedFragmentState): Seq[NativeTypedGraphicsLayout.Field]={
    var word=16
    val policyWords=Map("fragment.sampler.policyVersion"->120,"fragment.sampler.wrapU"->121,
      "fragment.sampler.wrapV"->122,"fragment.sampler.signedCoordinates"->123)
    val legacy=WindowPrimitiveRecordLayout.leaves(d).filterNot(x=>x._1=="fragment.centroidMask" || x._1.startsWith("fragment.resources.") || x._1.startsWith("fragment.uniforms.") || policyWords.contains(x._1)).map{case(n,x)=>val count=(x.getWidth+31)/32
      if(word%16+count>16)word=(word+15)/16*16
      val field=NativeTypedGraphicsLayout.Field(n,word,x);word+=count;field}
    require(word<=118,"legacy fragment-state fields must not overlap resource extensions")
    legacy++Seq(NativeTypedGraphicsLayout.Field("fragment.sampler.policyVersion",120,d.fragment.sampler.policyVersion),
      NativeTypedGraphicsLayout.Field("fragment.sampler.wrapU",121,d.fragment.sampler.wrapU),
      NativeTypedGraphicsLayout.Field("fragment.sampler.wrapV",122,d.fragment.sampler.wrapV),
      NativeTypedGraphicsLayout.Field("fragment.sampler.signedCoordinates",123,d.fragment.sampler.signedCoordinates),
      NativeTypedGraphicsLayout.Field("fragment.centroidMask",124,d.fragment.centroidMask))++
      ShaderTextureResources.fields(126,"fragment.resources.",d.fragment.resources)++
      ShaderUniformResources.fields(118,"fragment.uniforms.",d.fragment.uniforms)
  }
  def errors(d: TypedFragmentState,arena: PrimitiveStateDescriptor,f: FragmentShaderConfig,g: GraphicsConfig,c: GpuConfig): (Bool,Bool)={
    val checks=(0 until engines).map{e=>val view=scratch(d,e);val shader=WireDefault(d.fragment);shader.scratch:=view
      val (invalid,bounds)=FragmentShaderDescriptor.errors(shader,f,g,c)
      val alias=view.base<arena.limit && view.limit>arena.base
      val active=e.U<d.engineCount
      (active && (invalid || alias),active && bounds)}
    val aliases=(for(a<-0 until engines;b<-a+1 until engines)yield {
      val x=scratch(d,a);val y=scratch(d,b)
      a.U<d.engineCount && b.U<d.engineCount && x.base<y.limit && x.limit>y.base
    }).reduce(_ || _)
    val unused=d.scratch.zipWithIndex.map{case(v,i)=>(i+1).U<d.engineCount || !v.asUInt.orR}.reduce(_ && _)
    val invalid=d.version=/=1.U || d.engineCount===0.U || d.engineCount>engines.U || d.tests.version=/=1.U ||
      TypedBlendState.invalid(d.blend) || d.fragment.identity.asUInt=/=arena.identity.asUInt || !unused || aliases || checks.map(_._1).reduce(_ || _)
    (invalid,checks.map(_._2).reduce(_ || _))
  }
}
class TypedFragmentStateLookup extends Bundle {val arena=new PrimitiveStateDescriptor;val handle=UInt(32.W)}
class TypedFragmentStateResult(f: FragmentShaderConfig) extends Bundle {
  val identity=new GraphicsWorkIdentity;val handle=UInt(32.W);val status=UInt(3.W)
  val readLines=UInt(4.W);val hit=Bool();val stateValid=Bool();val state=new TypedFragmentState(f)
}
private class TypedFragmentStateHeader extends Bundle {val capacity=UInt(32.W);val identity=new GraphicsWorkIdentity;val handle=UInt(32.W)}

/** Bounded GFS1 state fetch with four independently tagged reads and one
  * immutable-state cache entry. Identity, header, canonical padding, every
  * active engine's descriptor/scratch bounds and pairwise scratch separation
  * are checked before publication. No partial/faulted state can launch a shader.
  * Invalidate at every pass binding, including same-address/generation reuse.
  * An invalidate during a fill prevents that fill from becoming a cache entry.
  */
class TypedFragmentStateReader(config: GpuConfig=GpuConfig(),f: FragmentShaderConfig=FragmentShaderConfig(),g: GraphicsConfig=GraphicsConfig()) extends Module {
  require(config.xLen==32 && f.colors==4)
  val io=IO(new Bundle {
    val lookup=Flipped(Decoupled(new TypedFragmentStateLookup));val result=Decoupled(new TypedFragmentStateResult(f))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,4));val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,4)))
    val invalidate=Input(Bool());val cancel=Input(Bool());val busy=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,check,fetch,verify,finish,complete=Value}
  private val phase=RegInit(State.idle);private val descriptor=Reg(new TypedFragmentStateLookup)
  private val snapshot=Reg(new TypedFragmentState(f));private val header=Reg(new TypedFragmentStateHeader)
  private val result=Reg(new TypedFragmentStateResult(f));private val status=RegInit(0.U(3.W))
  private val issued=RegInit(0.U(4.W));private val lines=RegInit(0.U(4.W));private val address=Reg(UInt(33.W))
  private val live=RegInit(VecInit(Seq.fill(4)(false.B)));private val pending=RegInit(VecInit(Seq.fill(4)(false.B)))
  private val lineMap=Reg(Vec(4,UInt(3.W)));private val offered=RegInit(false.B)
  private val request=Reg(new ComputeMemoryRequest(config,64,4));private val badEncoding=RegInit(false.B)
  private val noFill=RegInit(false.B);private val hit=RegInit(false.B)
  private val cached=RegInit(false.B);private val cachedKey=Reg(new TypedFragmentStateLookup);private val cachedState=Reg(new TypedFragmentState(f))
  private val fields=TypedFragmentStateLayout.fields(snapshot)
  NativeTypedGraphicsLayout.check(snapshot,fields,TypedFragmentStateLayout.words)
  private val headerFields=Seq(NativeTypedGraphicsLayout.Field("header.capacity",3,header.capacity),
    NativeTypedGraphicsLayout.Field("header.context",4,header.identity.context),NativeTypedGraphicsLayout.Field("header.pass",5,header.identity.pass),
    NativeTypedGraphicsLayout.Field("header.generation",6,header.identity.generation),NativeTypedGraphicsLayout.Field("header.handle",7,header.handle))
  private val stop=status=/=0.U || io.cancel
  io.lookup.ready:=phase===State.idle && !io.cancel;io.busy:=phase=/=State.idle
  io.result.valid:=phase===State.complete;io.result.bits:=result
  io.memoryRequest.valid:=offered;io.memoryRequest.bits:=request
  private val tag=io.memoryResponse.bits.transactionId
  io.memoryResponse.ready:=pending(tag)
  private val receive=io.memoryResponse.fire && !io.memoryResponse.bits.fault && !stop
  private val malformed=NativeTypedGraphicsLayout.receive(fields++headerFields,TypedFragmentStateLayout.words,TypedFragmentStateLayout.magic,
    lineMap(tag),io.memoryResponse.bits.readData,receive)
  when(io.invalidate){cached:=false.B;when(phase=/=State.idle && phase=/=State.complete){noFill:=true.B}}
  when(io.cancel && phase=/=State.idle && phase=/=State.complete && status===0.U){status:=4.U;cached:=false.B}
  when(io.memoryRequest.fire){offered:=false.B;pending(request.transactionId):=true.B}
  when(io.memoryResponse.fire){live(tag):=false.B;pending(tag):=false.B;lines:=lines+1.U
    when(io.memoryResponse.bits.fault){cached:=false.B;when(status===0.U && !io.cancel){status:=3.U}}
    when(receive && malformed){badEncoding:=true.B}}
  private val free= ~live.asUInt;private val freeTag=PriorityEncoder(free)
  when(phase===State.fetch && !stop && !offered && issued<8.U && free.orR){
    request:=0.U.asTypeOf(request);request.address:=(address+(issued<<6))(31,0)
    request.byteMask:=Fill(64,1.U(1.W));request.sizeLog2:=6.U;request.cachePolicy:=CachePolicy.uncached;request.transactionId:=freeTag
    live(freeTag):=true.B;lineMap(freeTag):=issued(2,0);issued:=issued+1.U;offered:=true.B
  }
  switch(phase){
    is(State.idle){when(io.lookup.fire){descriptor:=io.lookup.bits;snapshot:=0.U.asTypeOf(snapshot);header:=0.U.asTypeOf(header)
      status:=0.U;issued:=0.U;lines:=0.U;badEncoding:=false.B;noFill:=io.invalidate;hit:=false.B;phase:=State.check}}
    is(State.check){
      val a=descriptor.arena;val start=a.base.pad(64)+(descriptor.handle.pad(64)<<9);val end=start+TypedFragmentStateLayout.bytes.U
      val legal=a.version===1.U && a.base(5,0)===0.U && a.limit(5,0)===0.U && a.limit> a.base &&
        a.limit<=(BigInt(1)<<32).U && end<=a.limit
      val cacheHit=cached && !io.invalidate && !noFill && cachedKey.asUInt===descriptor.asUInt
      address:=start(32,0)
      when(stop){phase:=State.finish}.elsewhen(!legal){status:=1.U;phase:=State.finish}
        .elsewhen(cacheHit){snapshot:=cachedState;hit:=true.B;phase:=State.finish}
        .otherwise{cached:=false.B;phase:=State.fetch}
    }
    is(State.fetch){when((stop || issued===8.U) && !live.asUInt.orR && !offered){phase:=Mux(stop,State.finish,State.verify)}}
    is(State.verify){
      val (invalid,bounds)=TypedFragmentStateLayout.errors(snapshot,descriptor.arena,f,g,config)
      when(!stop){when(badEncoding || header.capacity=/=TypedFragmentStateLayout.engines.U || header.identity.asUInt=/=descriptor.arena.identity.asUInt || header.handle=/=descriptor.handle){status:=7.U}
        .elsewhen(invalid){status:=1.U}.elsewhen(bounds){status:=2.U}}
      phase:=State.finish
    }
    is(State.finish){result:=0.U.asTypeOf(result);result.identity:=descriptor.arena.identity;result.handle:=descriptor.handle
      result.status:=Mux(io.cancel && status===0.U,4.U,status);result.readLines:=lines;result.hit:=hit
      when(!stop){result.stateValid:=true.B;result.state:=snapshot
        when(!noFill && !io.invalidate){cachedKey:=descriptor;cachedState:=snapshot;cached:=true.B}}
      phase:=State.complete
    }
    is(State.complete){when(io.result.fire){phase:=State.idle}}
  }
  when(io.result.valid){assert(!live.asUInt.orR && !pending.asUInt.orR && !offered,"state publication escaped tagged read drain")}
}
