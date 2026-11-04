package opengpu.core.memory

import chisel3._
import opengpu.config.GpuConfig
import opengpu.graphics.ShaderPayloadConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class VectorShaderPayloadRouterSpec extends AnyFlatSpec {
  private val p=ShaderPayloadConfig(capacityBytes=4096,maxPayloadBytes=1152,owners=3,ports=2)
  private val line=(0 until 16).foldLeft(BigInt(0))((v,w)=>v|((BigInt("7fc01234",16)+w*0x10007)<<(32*w)))
  private def initialize(d: VectorShaderPayloadRouter): Unit={
    d.io.in.valid.poke(false.B);d.io.in.bits.poke(0.U.asTypeOf(d.io.in.bits));d.io.out.ready.poke(false.B)
    d.io.fallbackRequest.ready.poke(false.B);d.io.fallbackResponse.valid.poke(false.B)
    d.io.fallbackResponse.bits.poke(0.U.asTypeOf(d.io.fallbackResponse.bits))
    d.io.payload.request.ready.poke(false.B);d.io.payload.response.valid.poke(false.B)
    d.io.payload.response.bits.poke(0.U.asTypeOf(d.io.payload.response.bits));d.io.payload.cancel.poke(false.B)
    bind(d);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
  }
  private def bind(d: VectorShaderPayloadRouter,base: BigInt=0x8000,bytes: Int=256): Unit={
    val b=d.io.payload.task.bits;d.io.payload.task.valid.poke(true.B)
    b.base.poke(base.U);b.bytes.poke(bytes.U);b.handle.poke(2.U);b.generation.poke(17.U)
    b.context.poke(23.U);b.owner.poke(1.U)
  }
  private def input(d: VectorShaderPayloadRouter,n: Int,address: BigInt,mask: BigInt,write: Boolean=false,size: Int=2): Unit={
    val in=d.io.in.bits;in.warpId.poke(1.U);in.elementSize.poke(size.U)
    in.laneMask.poke(mask.U);in.isStore.poke(write.B)
    for(l<-0 until n){in.addresses(l).poke(((address+(BigInt(l)<<size))&BigInt("ffffffff",16)).U)
      in.writeData(l).poke((BigInt("dead0000",16)+l*0x101).U)}
  }
  private def finish(d: VectorShaderPayloadRouter): Unit={
    d.io.out.valid.expect(true.B);d.io.payload.drained.expect(false.B)
    val held=d.io.out.bits.peek().litValue
    d.io.payload.cancel.poke(true.B)
    for(_<-0 until 4){d.clock.step();d.io.out.valid.expect(true.B);assert(d.io.out.bits.peek().litValue==held)}
    d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)
    d.io.payload.cancel.poke(false.B);d.io.payload.drained.expect(true.B)
  }
  for(n<-Seq(4,16))it should s"serve exact resident words and byte masks through held offers and replies at lanes=$n" in {
    simulate(new VectorShaderPayloadRouter(GpuConfig(lanes=n,warps=2),p)){d=>
      initialize(d)
      for(offset<- (if(n==4)Seq(0,4,16,48)else Seq(0));write<-Seq(false,true);mask<-Seq((BigInt(1)<<n)-1,BigInt(5),BigInt(1)<<(n-1))){
        bind(d);input(d,n,0x8000+offset,mask,write);d.io.in.valid.poke(true.B)
        d.io.in.ready.expect(true.B);d.io.fallbackRequest.valid.expect(false.B);d.clock.step();d.io.in.valid.poke(false.B)
        bind(d,0x9000,64);d.io.payload.task.bits.context.poke(99.U)
        val q=d.io.payload.request;q.valid.expect(true.B);q.bits.offset.expect(0.U)
        q.bits.handle.expect(2.U);q.bits.context.expect(23.U);q.bits.generation.expect(17.U);q.bits.owner.expect(1.U)
        q.bits.write.expect(write.B)
        val expectedMask=(0 until n).filter(mask.testBit).flatMap(l=>(0 until 4).map(b=>offset+4*l+b)).foldLeft(BigInt(0))((m,b)=>m.setBit(b))
        q.bits.mask.expect(expectedMask.U)
        for(l<-0 until n if mask.testBit(l);b<-0 until 4){
          val actual=(q.bits.data.peek().litValue>>(8*(offset+4*l+b)))&255
          assert(actual==((BigInt("dead0000",16)+l*0x101)>>(8*b)&255))
        }
        val held=q.bits.peek().litValue
        for(_<-0 until 3){d.clock.step();q.valid.expect(true.B);assert(q.bits.peek().litValue==held)}
        q.ready.poke(true.B);d.clock.step();q.ready.poke(false.B)
        d.clock.step(2);val r=d.io.payload.response;r.valid.poke(true.B)
        r.bits.handle.poke(2.U);r.bits.generation.poke(17.U);r.bits.context.poke(23.U);r.bits.tag.poke(0.U)
        r.bits.data.poke(line.U);r.bits.fault.poke(false.B);r.ready.expect(true.B)
        d.clock.step();r.valid.poke(false.B);d.io.out.valid.expect(true.B)
        d.io.out.bits.faultMask.expect(0.U);d.io.out.bits.pageFault.expect(false.B)
        for(l<-0 until n){val expected=if(write || !mask.testBit(l))BigInt(0)else(line>>(8*(offset+4*l)))&BigInt("ffffffff",16)
          d.io.out.bits.readData(l).expect(expected.U)}
        finish(d)
      }
    }
  }
  it should "retain every general request and held fallback offer across binding changes" in {
    val n=4
    simulate(new VectorShaderPayloadRouter(GpuConfig(lanes=n,warps=2),p)){d=>
      initialize(d)
      val modes=Seq("disabled","unaligned","cross-line","narrow-byte","narrow-half","strided","mixed-prefix","outside","empty","bad-base","bad-size","bad-handle","bad-owner","overflow")
      for(mode<-modes){
        bind(d);input(d,n,if(mode=="cross-line")0x8034 else if(mode=="unaligned")0x8002 else if(mode=="outside")0x9000 else 0x8000,if(mode=="empty")0 else 15)
        mode match {
          case "disabled"=>d.io.payload.task.valid.poke(false.B)
          case "narrow-byte"=>input(d,n,0x8000,15,size=0)
          case "narrow-half"=>input(d,n,0x8000,15,size=1)
          case "strided"=>d.io.in.bits.addresses(1).poke(0x8008.U)
          case "mixed-prefix"=>d.io.payload.task.bits.bytes.poke(10.U)
          case "bad-base"=>d.io.payload.task.bits.base.poke(0x8004.U)
          case "bad-size"=>d.io.payload.task.bits.bytes.poke(0.U)
          case "bad-handle"=>d.io.payload.task.bits.handle.poke(3.U)
          case "bad-owner"=>d.io.payload.task.bits.owner.poke(3.U)
          case "overflow"=>bind(d,BigInt("ffffffc0",16),65);input(d,n,BigInt("ffffffc0",16),15)
          case _=>()
        }
        d.io.in.valid.poke(true.B);val held=d.io.in.bits.peek().litValue
        if(mode=="disabled"){
          d.io.fallbackRequest.valid.expect(true.B);d.io.in.ready.expect(false.B);d.clock.step()
          bind(d) // A stalled unbound offer cannot turn into resident service.
        }else{
          d.io.in.ready.expect(true.B);d.io.fallbackRequest.valid.expect(false.B)
          d.clock.step();d.io.in.valid.poke(false.B);bind(d);d.clock.step()
        }
        for(_<-0 until 3){d.io.fallbackRequest.valid.expect(true.B);assert(d.io.fallbackRequest.bits.peek().litValue==held)
          d.io.payload.request.valid.expect(false.B);d.clock.step()}
        d.io.fallbackRequest.ready.poke(true.B)
        if(mode=="disabled")d.io.in.ready.expect(true.B)
        d.clock.step()
        d.io.in.valid.poke(false.B);d.io.fallbackRequest.ready.poke(false.B)
        val r=d.io.fallbackResponse;r.valid.poke(true.B);r.bits.faultMask.poke(5.U);r.bits.pageFault.poke(true.B)
        for(l<-0 until n)r.bits.readData(l).poke((l+123).U)
        d.io.out.valid.expect(true.B);d.io.out.bits.faultMask.expect(5.U);d.io.out.bits.pageFault.expect(true.B)
        for(l<-0 until n)d.io.out.bits.readData(l).expect((l+123).U)
        finish(d);r.valid.poke(false.B)
      }
    }
  }
  it should "poison canceled and faulted resident accesses, retain offered requests, and preserve the final addressable word" in {
    simulate(new VectorShaderPayloadRouter(GpuConfig(lanes=4,warps=2),p)){d=>
      initialize(d)
      for(mode<-Seq("accept","offer","accepted","fault","last-address","healthy")){
        val last=mode=="last-address";val base=if(last)BigInt("ffffffc0",16)else BigInt(0x8000)
        val address=base+(if(last)48 else 0);val mask=if(last)BigInt(9)else BigInt(5)
        bind(d,base,64);input(d,4,address,mask)
        d.io.payload.cancel.poke((mode=="accept").B);d.io.in.valid.poke(true.B);d.io.in.ready.expect(true.B)
        d.clock.step();d.io.in.valid.poke(false.B);d.io.payload.cancel.poke(false.B)
        if(mode!="accept"){
          val q=d.io.payload.request;q.valid.expect(true.B);val held=q.bits.peek().litValue
          d.io.payload.cancel.poke((mode=="offer").B);d.clock.step();d.io.payload.cancel.poke(false.B)
          q.valid.expect(true.B);assert(q.bits.peek().litValue==held);q.ready.poke(true.B);d.clock.step();q.ready.poke(false.B)
          d.io.payload.cancel.poke((mode=="accepted").B);d.clock.step();d.io.payload.cancel.poke(false.B)
          val r=d.io.payload.response;r.valid.poke(true.B);r.bits.handle.poke(2.U);r.bits.generation.poke(17.U)
          r.bits.context.poke(23.U);r.bits.tag.poke(0.U);r.bits.data.poke(line.U);r.bits.fault.poke((mode=="fault").B)
          r.ready.expect(true.B);d.clock.step();r.valid.poke(false.B)
        }else d.io.payload.request.valid.expect(false.B)
        d.io.out.valid.expect(true.B)
        val bad=Set("accept","offer","accepted","fault")(mode)
        d.io.out.bits.faultMask.expect((if(bad)5 else 0).U);d.io.out.bits.pageFault.expect(false.B)
        for(l<-0 until 4){val expected=if(bad || !mask.testBit(l))BigInt(0)else(line>>(8*((if(last)48 else 0)+4*l)))&BigInt("ffffffff",16)
          d.io.out.bits.readData(l).expect(expected.U)}
        finish(d)
      }
    }
  }
  it should "retain the complete 32-lane vector on the general path" in {
    simulate(new VectorShaderPayloadRouter(GpuConfig(lanes=32,warps=2),p)){d=>
      initialize(d);input(d,32,0x8000,(BigInt(1)<<32)-1)
      d.io.in.valid.poke(true.B);d.io.fallbackRequest.valid.expect(false.B);d.io.in.ready.expect(true.B)
      val original=d.io.in.bits.peek().litValue
      d.clock.step();d.io.in.valid.poke(false.B);d.clock.step()
      d.io.fallbackRequest.valid.expect(true.B)
      assert(d.io.fallbackRequest.bits.peek().litValue==original)
      d.io.fallbackRequest.ready.poke(true.B);d.clock.step();d.io.fallbackRequest.ready.poke(false.B)
      d.io.payload.request.valid.expect(false.B)
      val r=d.io.fallbackResponse;r.valid.poke(true.B);r.bits.faultMask.poke(0.U);r.bits.pageFault.poke(false.B)
      for(l<-0 until 32)r.bits.readData(l).poke((0x123400+l).U)
      d.io.out.valid.expect(true.B);for(l<-0 until 32)d.io.out.bits.readData(l).expect((0x123400+l).U)
      finish(d)
    }
  }
  it should "retain a cancel pulse before and after offering a captured general vector" in {
    simulate(new VectorShaderPayloadRouter(GpuConfig(lanes=4,warps=2),p)){d=>
      initialize(d)
      for(cut<-Seq("classify","offer","accepted")){
        bind(d);input(d,4,0x8000,5,write=true,size=1)
        d.io.in.valid.poke(true.B);d.io.in.ready.expect(true.B);d.clock.step();d.io.in.valid.poke(false.B)
        if(cut=="classify"){
          d.io.payload.cancel.poke(true.B);d.clock.step();d.io.payload.cancel.poke(false.B)
          d.io.fallbackRequest.valid.expect(false.B);d.io.payload.request.valid.expect(false.B)
        }else{
          d.clock.step();val q=d.io.fallbackRequest;q.valid.expect(true.B)
          val held=q.bits.peek().litValue
          if(cut=="offer"){
            d.io.payload.cancel.poke(true.B);d.clock.step();d.io.payload.cancel.poke(false.B)
            q.valid.expect(true.B);assert(q.bits.peek().litValue==held)
          }
          q.ready.poke(true.B);d.clock.step();q.ready.poke(false.B)
          if(cut=="accepted"){
            d.io.payload.cancel.poke(true.B);d.clock.step();d.io.payload.cancel.poke(false.B)
          }
          d.io.fallbackCanceled.expect(true.B);d.clock.step(3);d.io.fallbackCanceled.expect(true.B)
          val r=d.io.fallbackResponse;r.bits.poke(0.U.asTypeOf(r.bits));r.bits.faultMask.poke(5.U);r.valid.poke(true.B)
        }
        d.io.out.valid.expect(true.B);d.io.out.bits.faultMask.expect(5.U)
        finish(d);d.io.fallbackResponse.valid.poke(false.B);d.io.fallbackCanceled.expect(false.B)
      }
    }
  }
}
