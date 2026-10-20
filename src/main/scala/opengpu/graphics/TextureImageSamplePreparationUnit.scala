package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}

class TextureImageSamplePreparationRequest extends Bundle {
  val images=new TextureImageLevelTable;val samplers=new TextureImageLevelTable
  val imageHandle=UInt(32.W);val samplerHandle=UInt(32.W);val expectedType=UInt(2.W);val target=UInt(3.W)
  val coordinates=Vec(3,UInt(32.W));val layer=UInt(32.W);val reference=UInt(32.W)
  val shadow=Bool();val applySwizzle=Bool();val tag=UInt(32.W)
}
class TextureImageSamplePreparationResult extends Bundle {
  val prepared=new TextureImageMipSampleRequest
  val cubeRatios=Vec(2,UInt(32.W));val cubeMajor=UInt(32.W)
  val status=UInt(3.W);val faultSource=UInt(3.W);val faultAddressValid=Bool();val faultAddress=UInt(32.W);val tag=UInt(32.W)
}
/** Admit immutable image/sampler state, resolve array layers and project cubes.
  * The output retains the base extent and every checked mip view so a native
  * parent can derive explicit/gradient/implicit lambda before invoking MIP
  * sampling. Publication and VM remain pinned by that parent until retirement.
  */
class TextureImageSamplePreparationUnit(config: GpuConfig=GpuConfig(),p: TextureImageAddressConfig=TextureImageAddressConfig()) extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new TextureImageSamplePreparationRequest));val out=Decoupled(new TextureImageSamplePreparationResult)
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,8));val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,8)))
    val cancel=Input(Bool());val invalidate=Input(Bool());val busy=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,check,offerAdmission,waitAdmission,geometry,offerCube,waitCube,finish,complete=Value}
  private val phase=RegInit(State.idle);private val request=Reg(new TextureImageSamplePreparationRequest)
  private val admitted=Reg(new TextureImageSamplerBindingResult);private val projected=Reg(new TextureCubeProjectionResult)
  private val result=Reg(new TextureImageSamplePreparationResult);private val status=RegInit(0.U(3.W));private val source=RegInit(0.U(3.W))
  private val faultValid=RegInit(false.B);private val faultAddress=Reg(UInt(32.W));private val layer=Reg(UInt(16.W))
  private val admissionStarted=RegInit(false.B);private val cubeStarted=RegInit(false.B);private val noFill=RegInit(false.B)
  private val active=phase=/=State.idle && phase=/=State.complete;private val stop=status=/=0.U || io.cancel
  private val admission=Module(new TextureImageSamplerBindingUnit(config,p));private val cube=Module(new TextureCubeProjectionUnit)
  admission.io.in.valid:=phase===State.offerAdmission;admission.io.in.bits:=0.U.asTypeOf(admission.io.in.bits)
  admission.io.in.bits.images:=request.images;admission.io.in.bits.samplers:=request.samplers
  admission.io.in.bits.imageHandle:=request.imageHandle;admission.io.in.bits.samplerHandle:=request.samplerHandle
  admission.io.in.bits.expectedType:=request.expectedType;admission.io.in.bits.selectBaseLevel:=true.B
  admission.io.in.bits.sampling:=true.B;admission.io.in.bits.shadow:=request.shadow;admission.io.in.bits.tag:=request.tag
  admission.io.out.ready:=phase===State.waitAdmission;admission.io.cancel:=admissionStarted && (io.cancel || status===4.U)
  admission.io.invalidate:=io.invalidate || stop || noFill;io.memoryRequest<>admission.io.memoryRequest;admission.io.memoryResponse<>io.memoryResponse
  cube.io.in.valid:=phase===State.offerCube;cube.io.in.bits.vector:=request.coordinates;cube.io.in.bits.tag:=request.tag
  cube.io.out.ready:=phase===State.waitCube;cube.io.cancel:=cubeStarted && (io.cancel || status===4.U)
  io.in.ready:=phase===State.idle && !io.cancel;io.out.valid:=phase===State.complete;io.out.bits:=result;io.busy:=phase=/=State.idle
  when(active && io.cancel && status===0.U){status:=4.U;source:=0.U;faultValid:=false.B}
  when(active && io.invalidate){noFill:=true.B}
  switch(phase){
    is(State.idle){when(io.in.fire){
      assert(!admission.io.busy && !cube.io.busy,"sample preparation abandoned child ownership")
      request:=io.in.bits;admitted:=0.U.asTypeOf(admitted);projected:=0.U.asTypeOf(projected);status:=0.U;source:=0.U
      faultValid:=false.B;faultAddress:=0.U;layer:=0.U;admissionStarted:=false.B;cubeStarted:=false.B;noFill:=io.invalidate;phase:=State.check
    }}
    is(State.check){when(stop){phase:=State.finish}.elsewhen(request.target>3.U){status:=1.U;source:=3.U;phase:=State.finish}
      .otherwise{phase:=State.offerAdmission}}
    is(State.offerAdmission){when(admission.io.in.fire){admissionStarted:=true.B;phase:=State.waitAdmission}}
    is(State.waitAdmission){when(admission.io.out.fire){
      admitted:=admission.io.out.bits;admissionStarted:=false.B;val code=admission.io.out.bits.status
      when((code=/=0.U && code=/=4.U) || !stop){status:=code
        when(code=/=0.U){source:=admission.io.out.bits.faultSource;faultValid:=admission.io.out.bits.faultAddressValid;faultAddress:=admission.io.out.bits.faultAddress}}
      phase:=Mux(stop || code=/=0.U,State.finish,State.geometry)
    }}
    is(State.geometry){
      val isCube=request.target===2.U;val volume=request.target===3.U;val array=request.target===1.U
      val badFloat=request.coordinates.zipWithIndex.map{case(v,i)=>(if(i==2)isCube||volume else true.B) && v(30,23)===255.U}.reduce(_ || _) ||
        (array && request.layer(30,23)===255.U)
      when(stop){phase:=State.finish}.elsewhen(admitted.image.view.target=/=request.target){status:=6.U;source:=3.U;phase:=State.finish}
        .elsewhen(badFloat){status:=5.U;source:=4.U;phase:=State.finish}
        .otherwise{layer:=Mux(array,TextureSampleSelection.layer(request.layer,admitted.image.view.layers),0.U)
          phase:=Mux(isCube,State.offerCube,State.finish)}
    }
    is(State.offerCube){when(cube.io.in.fire){cubeStarted:=true.B;phase:=State.waitCube}}
    is(State.waitCube){when(cube.io.out.fire){
      cubeStarted:=false.B;projected:=cube.io.out.bits;assert(cube.io.out.bits.tag===request.tag);val code=cube.io.out.bits.status
      when((code=/=0.U && code=/=4.U) || !stop){status:=code;when(code=/=0.U){source:=4.U}}
      phase:=State.finish
    }}
    is(State.finish){
      assert(!admission.io.busy && !cube.io.busy,"sample preparation completion escaped child drain")
      result:=0.U.asTypeOf(result);result.tag:=request.tag;result.status:=Mux(io.cancel && status===0.U,4.U,status)
      result.faultSource:=source;result.faultAddressValid:=faultValid;result.faultAddress:=Mux(faultValid,faultAddress,0.U)
      when(!stop){
        result.prepared.sample.image:=admitted.image;result.prepared.sample.sampler:=admitted.sampler
        result.prepared.sample.coordinates:=request.coordinates;result.prepared.sample.layer:=layer
        result.prepared.sample.shadow:=request.shadow;result.prepared.sample.reference:=request.reference
        result.prepared.sample.applySwizzle:=request.applySwizzle;result.prepared.sample.tag:=request.tag
        when(request.target===2.U){result.prepared.sample.coordinates(0):=projected.coordinates(0);result.prepared.sample.coordinates(1):=projected.coordinates(1)
          result.prepared.sample.coordinates(2):=0.U;result.prepared.sample.face:=projected.face
          result.cubeRatios:=projected.ratios;result.cubeMajor:=projected.major}
      };phase:=State.complete
    }
    is(State.complete){when(io.out.fire){phase:=State.idle}}
  }
  when(io.out.valid){assert(!admission.io.busy && !cube.io.busy && !io.memoryRequest.valid,"held prepared sample must own drained children")}
}
