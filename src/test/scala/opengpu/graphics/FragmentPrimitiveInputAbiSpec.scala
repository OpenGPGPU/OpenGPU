package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.system.SharedCuShaderConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

private class FragmentPrimitiveAdmissionHarness extends Module {
  private val c=GpuConfig(lanes=16,warps=8)
  private val f=FragmentShaderConfig()
  val io=IO(new Bundle {
    val draw=Input(new FragmentShaderDraw(f));val geometryValid=Input(Bool())
    val control=Input(UInt(32.W));val coverage=Input(UInt(4.W));val enabled=Input(Bool())
    val admitted=Output(Vec(8,Bool()));val replay=Output(Vec(8,Bool()));val bounds=Output(Bool())
    val samplePlane=Output(Bool());val geometry=Output(Bool());val primitive=Output(Bool())
    val metadataWords=Output(UInt(2.W));val invalidControl=Output(Bool());val mask=Output(UInt(4.W))
  })
  for(i<-0 until 8){
    val (invalid,bounds)=FragmentShaderDescriptor.errors(io.draw,f,GraphicsConfig(),c,
      geometryInput=(i&1)!=0,sampleShading=(i&2)!=0,primitiveIdInput=(i&4)!=0)
    io.admitted(i):= !invalid && !bounds
    io.replay(i):=FragmentInputAbi.replaySupported(io.draw.version,io.geometryValid,
      geometryInput=(i&1)!=0,sampleShading=(i&2)!=0,primitiveIdInput=(i&4)!=0)
    if(i==7)io.bounds:=bounds
  }
  io.samplePlane:=FragmentInputAbi.hasSample(io.draw.version)
  io.geometry:=FragmentInputAbi.hasGeometry(io.draw.version)
  io.primitive:=FragmentInputAbi.hasPrimitiveId(io.draw.version)
  io.metadataWords:=FragmentInputAbi.metadataWords(io.draw.version)
  io.invalidControl:=FragmentSampleAbi.invalidControl(io.control,io.draw.version)
  io.mask:=FragmentSampleAbi.effectiveMask(io.coverage,io.control,io.draw.version,io.enabled)
}

class FragmentPrimitiveInputAbiSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  it should "keep all seven old layouts and independently gate primitive, GS, sample planes and output masks" in {
    // Independent literal ABI table: inputs +16 colors +depth/control, S=512.
    val totals=Vector(88,90,88,90,92,93,95,91,94)
    val f=FragmentShaderConfig()
    for(v<-1 to 9)assert(f.prefixBytes(128,16,4,v)==totals(v-1)*512)
    assert(!FragmentInputAbi.header("GPU_").contains("VERSION_PRIMITIVE"))
    simulate(new FragmentPrimitiveAdmissionHarness){d=>
      zero(d.io);val q=d.io.draw
      q.shaderPc.poke(0x1000.U);q.varyingSlots.poke(16.U);q.colorSlots.poke(4.U);q.colorMask.poke(15.U)
      q.scratch.allocationBase.poke(0x100000.U);q.scratch.base.poke(0x100000.U)
      q.scratch.allocationLimit.poke(0x110000.U);q.scratch.limit.poke(0x110000.U)
      for(v<-0 to 10){q.version.poke(v.U)
        val sample=Set(6,7,9)(v);val geometry=Set(5,7)(v);val primitive=Set(5,7,8,9)(v)
        d.io.samplePlane.expect(sample.B);d.io.geometry.expect(geometry.B);d.io.primitive.expect(primitive.B)
        d.io.metadataWords.expect((if(geometry)2 else if(primitive)1 else 0).U)
        for(sideband<-Seq(false,true)){d.io.geometryValid.poke(sideband.B)
          for(i<-0 until 8){val legal=v match {
            case x if x>=1 && x<=4=>true
            case 5=>(i&1)!=0;case 6=>(i&2)!=0;case 7=>(i&3)==3
            case 8=>(i&4)!=0;case 9=>(i&6)==6;case _=>false
          }
            d.io.admitted(i).expect(legal.B)
            d.io.replay(i).expect((legal && (!geometry || sideband)).B)
          }
        }
        for(control<-Seq(BigInt(61),BigInt(61|64|(7<<7)),BigInt(1<<11))){
          d.io.control.poke(control.U);d.io.coverage.poke(10.U);d.io.enabled.poke(true.B)
          val maskControl=Set(6,7,8,9)(v)
          d.io.invalidControl.expect(((control>>(if(maskControl)11 else 6))!=0).B)
          val expected=10 & (if(maskControl && control.testBit(6))((control>>7)&15).toInt else 15)
          d.io.mask.expect(expected.U);d.io.enabled.poke(false.B);d.io.mask.expect(0.U)
        }
      }
      for(v<-Seq(8,9)){q.version.poke(v.U);val prefix=totals(v-1)*512
        q.scratch.limit.poke((0x100000+prefix-1).U);d.io.bounds.expect(true.B)
        q.scratch.limit.poke((0x100000+prefix).U);d.io.bounds.expect(false.B);d.io.admitted(6).expect(true.B)
      }
      // Neither old sample permission nor GS provenance grants ordinary input.
      q.version.poke(8.U);d.io.admitted(3).expect(false.B)
      q.version.poke(7.U);d.io.geometryValid.poke(false.B);d.io.replay(7).expect(false.B)
    }
  }

}

/** Explicit local qualification: the complete stage retains the original
  * texture/service closure and may exceed a bounded component-test heap. */
class FragmentPrimitiveInputPackingSpec extends AnyFlatSpec {
  import WindowRecordTest.{zero,Memory}
  private def fp(v:Float):BigInt=BigInt(java.lang.Float.floatToRawIntBits(v).toLong&0xffffffffL)
  for(abi<-Seq(8,9))
  it should s"pack actual ABI$abi quad metadata through acknowledged memory without a GS sideband" in {
    // No CU or instruction interpreter: this peer returns only the original
    // tagged completion after independently checking the stage's packed bytes.
    // The separate full-CU qualification must prove shader loads/readback.
    val c=GpuConfig(lanes=16,warps=8)
    simulate(new FragmentShaderStage(c,FragmentShaderConfig(),SharedCuShaderConfig(2),
        sampleShading=true,primitiveIdInput=true)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val mem=new Memory(d.io.memoryRequest.bits,d.io.memoryResponse.bits,d.io.memoryRequest.valid,
        d.io.memoryRequest.ready,d.io.memoryResponse.valid,d.io.memoryResponse.ready)
      var invalidate=Option.empty[BigInt]
      def tick():Unit={
        d.io.shared.invalidate.ready.poke(invalidate.isEmpty.B)
        d.io.shared.invalidateDone.valid.poke(invalidate.nonEmpty.B)
        invalidate.foreach(a=>d.io.shared.invalidateDone.bits.lineAddress.poke(a.U))
        val done=invalidate.nonEmpty && d.io.shared.invalidateDone.ready.peek().litToBoolean
        val accepted=d.io.shared.invalidate.valid.peek().litToBoolean && d.io.shared.invalidate.ready.peek().litToBoolean
        val address=if(accepted)d.io.shared.invalidate.bits.lineAddress.peek().litValue else BigInt(0)
        mem.drive();mem.capture();d.clock.step()
        if(done)invalidate=None
        if(accepted)invalidate=Some(address)
      }
      def until(p: =>Boolean):Unit={var n=0;while(!p && n<4000){tick();n+=1};assert(p,s"ABI$abi stage phase timeout after$n")}
      def identity(i:GraphicsWorkIdentity):Unit={i.context.poke(7.U);i.pass.poke(8.U);i.generation.poke(9.U)}
      val inputs=if(abi==8)73 else 76;val prefix=(inputs+18)*512;val base=BigInt(0x100000)
      val primitive=BigInt("fedcba98",16)
      // Reuse the same stage and original backing through1x/2x/4x and a fault.
      for((mode,wrongSample)<-Seq(0->false,1->false,2->false,2->true,2->false)){
        val id=if(abi==9)(1<<mode)-1 else 0
        val q=d.io.draw.bits;zero(q);q.version.poke(abi.U);identity(q.identity)
        q.shaderPc.poke(0x1000.U);q.varyingSlots.poke(16.U);q.colorSlots.poke(4.U)
        q.scratch.base.poke(base.U);q.scratch.limit.poke((base+prefix).U)
        q.scratch.allocationBase.poke(base.U);q.scratch.allocationLimit.poke((base+65536).U);q.sampleMode.poke(mode.U)
        d.io.draw.valid.poke(true.B);until(d.io.draw.ready.peek().litToBoolean);tick();d.io.draw.valid.poke(false.B)
        val quads=if(wrongSample)1 else 32
        for(qi<-0 until quads){
          val r=d.io.in.bits;zero(r);identity(r.values.identity);r.values.primitiveId.poke((primitive-qi*131).U)
          r.values.sequence.poke((19+qi).U);r.values.varyingSlots.poke(16.U);r.frontFacing.poke(true.B);r.sampleMode.poke(mode.U)
          r.values.perSample.poke(((abi==9) != wrongSample).B);r.values.sampleId.poke(id.U)
          for(l<-0 until 4){val a=r.values.lanes(l);val live=l==0 || l==2
            a.x.poke((-4+2*qi+l%2).S);a.y.poke((6+l/2).S);a.covered.poke(live.B)
            a.coverageMask.poke((if(!live)0 else if(abi==9)1<<id else (1<<(1<<mode))-1).U)
            a.perspectiveValid.poke(true.B);a.fragCoordW.poke(fp(2f).U);a.windowDepth.poke(fp(.5f).U)
            a.reciprocalW.significand.poke((1<<23).U);a.reciprocalW.exponent.poke(1.S)
            a.sampleLocationValid.poke((abi==9).B);a.sampleLocation.poke(id.U)
            a.pointCoord(0).poke(fp(.25f).U);a.pointCoord(1).poke(fp(.75f).U)
            for(v<-0 until 16;k<-0 until 4)a.varyings(v)(k).poke((0x10000+qi*0x1000+v*16+k*4+l).U)
          }
          d.io.in.valid.poke(true.B);until(d.io.in.ready.peek().litToBoolean);tick();d.io.in.valid.poke(false.B)
        }
        identity(d.io.inputEnd.bits.identity);d.io.inputEnd.bits.quads.poke(quads.U);d.io.inputEnd.valid.poke(true.B)
        until(d.io.inputEnd.ready.peek().litToBoolean);tick();d.io.inputEnd.valid.poke(false.B)
        if(!wrongSample){
          until(d.io.shared.launch.valid.peek().litToBoolean)
          assert(mem.pending.isEmpty && mem.reply.isEmpty,"launch preceded a real input-store acknowledgement")
          d.io.shared.launch.bits.launch.localSize(0).expect(128.U)
          d.io.shared.launch.bits.launch.invocations.helpers.expect(BigInt("aa"*16,16).U)
          for(qi<-0 until quads;l<-0 until 4){val lane=4*qi+l
            assert(mem.word(base+72*512+4*lane)==primitive-qi*131,s"primitive ABI$abi lane$lane")
            assert(mem.word(base+70*512+4*lane)==fp(.25f) && mem.word(base+71*512+4*lane)==fp(.75f))
            for(v<-0 until 16;k<-0 until 4)assert(mem.word(base+(6+4*v+k)*512+4*lane)==0x10000+qi*0x1000+v*16+k*4+l)
            if(abi==9){assert(mem.word(base+73*512+4*lane)==id)
              assert(mem.word(base+74*512+4*lane)==fp(if(mode==0).5f else .75f))
              assert(mem.word(base+75*512+4*lane)==fp(if(mode==0).5f else .75f))}
          }
          val launch=d.io.shared.launch.bits.peek().litValue
          for(_<-0 until 3){tick();d.io.shared.launch.valid.expect(true.B);assert(d.io.shared.launch.bits.peek().litValue==launch)}
          d.io.shared.launch.ready.poke(true.B);tick();d.io.shared.launch.ready.poke(false.B)
          d.io.shared.completion.bits.commandId.poke(0.U);d.io.shared.completion.bits.success.poke(true.B)
          d.io.shared.completion.valid.poke(true.B);until(d.io.shared.completion.ready.peek().litToBoolean)
          tick();d.io.shared.completion.valid.poke(false.B)
          until(d.io.out.valid.peek().litToBoolean);d.io.out.bits.primitiveId.expect(primitive.U)
          val held=d.io.out.bits.peek().litValue;for(_<-0 until 3){tick();assert(d.io.out.bits.peek().litValue==held)}
          for(qi<-0 until quads){until(d.io.out.valid.peek().litToBoolean)
            d.io.out.bits.primitiveId.expect((primitive-qi*131).U)
            d.io.out.ready.poke(true.B);tick();d.io.out.ready.poke(false.B)}
        }
        until(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.status.expect((if(wrongSample)6 else 0).U)
        val held=d.io.completion.bits.peek().litValue;d.io.cancel.poke(true.B)
        for(_<-0 until 3){tick();d.io.completion.valid.expect(true.B);assert(d.io.completion.bits.peek().litValue==held)}
        assert(mem.pending.isEmpty && mem.reply.isEmpty)
        d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B);d.io.cancel.poke(false.B)
      }
    }
  }
}
