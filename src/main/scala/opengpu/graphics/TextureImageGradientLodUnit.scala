package opengpu.graphics

import chisel3._
import chisel3.util._

class TextureImageGradientLodRequest extends Bundle {
  val prepared=new TextureImageSamplePreparationResult
  val vector=Vec(3,UInt(32.W));val gradients=Vec(2,Vec(3,UInt(32.W)))
  val extended=Bool();val wideGradients=Vec(2,Vec(3,new TextureGradientScalar))
}
class TextureImageGradientLodResult extends Bundle {
  val resolved=new TextureImageMipSampleRequest
  val status=UInt(3.W);val faultSource=UInt(3.W);val faultAddressValid=Bool();val faultAddress=UInt(32.W);val tag=UInt(32.W)
}
/** Resolve gradient lambda against the admitted base extent. Cube derivatives
  * use the original direction and selected-face Jacobian; array layer gradients
  * and unused Z components never participate. The owner retains the immutable
  * admission result until all arithmetic drains. Publication/VM leases stay
  * with the enclosing native/sample owner until final texel retirement.
  */
class TextureImageGradientLodUnit extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new TextureImageGradientLodRequest));val out=Decoupled(new TextureImageGradientLodResult)
    val cancel=Input(Bool());val busy=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,check,convert,offerCube,waitCube,offerLod,waitLod,finish,complete=Value}
  private val phase=RegInit(State.idle);private val request=Reg(new TextureImageGradientLodRequest);private val result=Reg(new TextureImageGradientLodResult)
  private val gradients=Reg(Vec(2,Vec(3,new TextureGradientScalar)));private val lod=Reg(UInt(32.W))
  private val status=RegInit(0.U(3.W));private val source=RegInit(0.U(3.W));private val faultValid=RegInit(false.B);private val faultAddress=Reg(UInt(32.W))
  private val cubeStarted=RegInit(false.B);private val lodStarted=RegInit(false.B)
  private val active=phase=/=State.idle && phase=/=State.complete;private val stop=status=/=0.U || io.cancel
  private val sample=request.prepared.prepared.sample;private val target=sample.image.view.target;private val volume=target===3.U;private val cubeTarget=target===2.U
  private val cube=Module(new TextureCubeGradientUnit);private val logarithm=Module(new TextureGradientLodUnit)
  cube.io.in.valid:=phase===State.offerCube;cube.io.in.bits.vector:=request.vector;cube.io.in.bits.gradients:=request.gradients;cube.io.in.bits.tag:=request.prepared.tag
  cube.io.in.bits.extended:=request.extended;cube.io.in.bits.wideGradients:=request.wideGradients
  cube.io.out.ready:=phase===State.waitCube;cube.io.cancel:=cubeStarted && (io.cancel || status===4.U)
  logarithm.io.in.valid:=phase===State.offerLod;logarithm.io.in.bits.gradients:=gradients
  logarithm.io.in.bits.extents:=VecInit(Seq(sample.image.view.texWidth,sample.image.view.texHeight,sample.image.view.texDepth))
  logarithm.io.in.bits.dimensions:=Mux(volume,3.U,2.U);logarithm.io.in.bits.tag:=request.prepared.tag
  logarithm.io.out.ready:=phase===State.waitLod;logarithm.io.cancel:=lodStarted && (io.cancel || status===4.U)
  io.in.ready:=phase===State.idle && !io.cancel;io.out.valid:=phase===State.complete;io.out.bits:=result;io.busy:=phase=/=State.idle
  when(active && io.cancel && status===0.U){status:=4.U;source:=0.U;faultValid:=false.B}
  switch(phase){
    is(State.idle){when(io.in.fire){
      assert(!cube.io.busy && !logarithm.io.busy,"gradient LOD admission abandoned arithmetic")
      request:=io.in.bits;status:=0.U;source:=0.U;faultValid:=false.B;faultAddress:=0.U;gradients:=0.U.asTypeOf(gradients);lod:=0.U
      cubeStarted:=false.B;lodStarted:=false.B;phase:=State.check
    }}
    is(State.check){
      val admitted=sample.image.validBinding && sample.image.status===0.U && sample.sampler.validSampler && sample.sampler.status===0.U &&
        sample.image.level===sample.image.baseLevel && sample.image.view.level===sample.image.baseLevel && sample.tag===request.prepared.tag && target<=3.U
      val badGradient= !request.extended && request.gradients.flatMap(_.zipWithIndex.map{case(v,a)=>(if(a==2)volume || cubeTarget else true.B) && v(30,23)===255.U}).reduce(_ || _)
      val badWide=request.extended && request.wideGradients.flatMap(_.zipWithIndex.map{case(v,a)=>
        (if(a==2)volume || cubeTarget else true.B) && (v.exponent<(-149).S || v.exponent>129.S ||
          Mux(v.significand===0.U,v.exponent=/=0.S,!v.significand(23))) }).reduce(_ || _)
      when(stop){phase:=State.finish}.elsewhen(request.prepared.status=/=0.U){status:=request.prepared.status;source:=request.prepared.faultSource
        faultValid:=request.prepared.faultAddressValid;faultAddress:=request.prepared.faultAddress;phase:=State.finish}
        .elsewhen(!admitted){status:=1.U;source:=3.U;phase:=State.finish}
        .elsewhen(badGradient){status:=5.U;source:=4.U;phase:=State.finish}
        .elsewhen(badWide){status:=6.U;source:=3.U;phase:=State.finish}
        .otherwise{phase:=Mux(cubeTarget,State.offerCube,State.convert)}
    }
    is(State.convert){
      for(r<-0 until 2;a<-0 until 3){val v=ClipFloat.fromFp32(request.gradients(r)(a))
        gradients(r)(a).negative:=v.negative;gradients(r)(a).exponent:=v.exponent;gradients(r)(a).significand:=v.significand
        when(request.extended){gradients(r)(a):=request.wideGradients(r)(a)}
        if(a==2){when(!volume){gradients(r)(a):=0.U.asTypeOf(new TextureGradientScalar)}}
      };phase:=Mux(stop,State.finish,State.offerLod)
    }
    is(State.offerCube){when(cube.io.in.fire){cubeStarted:=true.B;phase:=State.waitCube}}
    is(State.waitCube){when(cube.io.out.fire){
      cubeStarted:=false.B;val r=cube.io.out.bits;assert(r.tag===request.prepared.tag)
      when((r.status=/=0.U && r.status=/=4.U) || !stop){status:=r.status;when(r.status=/=0.U){source:=Mux(r.status===5.U,4.U,3.U)}}
      val matched=r.face===sample.face && r.major===request.prepared.cubeMajor
      when(stop || r.status=/=0.U){phase:=State.finish}.elsewhen(!matched){status:=6.U;source:=3.U;phase:=State.finish}.otherwise{
        for(row<-0 until 2;axis<-0 until 2)gradients(row)(axis):=r.gradients(row)(axis)
        phase:=State.offerLod
      }
    }}
    is(State.offerLod){when(logarithm.io.in.fire){lodStarted:=true.B;phase:=State.waitLod}}
    is(State.waitLod){when(logarithm.io.out.fire){
      lodStarted:=false.B;val r=logarithm.io.out.bits;assert(r.tag===request.prepared.tag)
      when((r.status=/=0.U && r.status=/=4.U) || !stop){status:=r.status;when(r.status=/=0.U){source:=Mux(r.status===5.U,4.U,3.U)}}
      lod:=r.lod;phase:=State.finish
    }}
    is(State.finish){
      assert(!cube.io.busy && !logarithm.io.busy,"gradient LOD completion escaped arithmetic drain")
      result:=0.U.asTypeOf(result);result.status:=Mux(io.cancel && status===0.U,4.U,status);result.tag:=request.prepared.tag
      result.faultSource:=source;result.faultAddressValid:=faultValid;result.faultAddress:=Mux(faultValid,faultAddress,0.U)
      when(!stop){result.resolved:=request.prepared.prepared;result.resolved.lod:=lod};phase:=State.complete
    }
    is(State.complete){when(io.out.fire){phase:=State.idle}}
  }
  when(io.out.valid){assert(!cube.io.busy && !logarithm.io.busy,"held gradient LOD must own drained children")}
}
