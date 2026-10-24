package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TypedDepthLaneSpec extends AnyFlatSpec {
  it should "preserve packed neighbors across generic depth stencil formats MSAA and delayed word replies" in {
    simulate(new TypedDepthLane(0)){d=>
      import TypedColorReference.fp
      import TypedDepthReference.{encode,compare,operation}
      val full=(BigInt(1)<<64)-1
      val layouts=Vector((1,0),(2,0),(3,0),(4,0),(5,0),(1,6),(0,6))
      def bytes(f: Int): Int=Vector(0,2,4,4,4,8,1)(f)
      d.io.start.poke(false.B);d.io.cancel.poke(false.B)
      d.io.request.ready.poke(false.B);d.io.response.valid.poke(false.B)
      d.io.response.bits.poke(0.U.asTypeOf(d.io.response.bits))
      d.io.descriptor.poke(0.U.asTypeOf(d.io.descriptor));d.io.input.poke(0.U.asTypeOf(d.io.input))
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      for((format,secondary)<-layouts;mode<-0 to 2;front<-Seq(true,false)){
        val n=1<<mode;val baseSample=18*n;val depth=format>=1 && format<=5
        val stencil=format==4 || format==5 || secondary==6
        val data=scala.collection.mutable.Map.empty[Int,BigInt].withDefaultValue(BigInt("d36bc29174ef08a5",16))
        def get(a: Int,b: Int): BigInt=(data(a/8)>>(8*(a%8)))&((BigInt(1)<<(8*b))-1)
        def put(a: Int,b: Int,v: BigInt): Unit={val mask=((BigInt(1)<<(8*b))-1)<<(8*(a%8))
          data(a/8)=(data(a/8)&(full^mask))|(v<<(8*(a%8))&mask)}
        for(s<-0 until n){
          if(depth)put(64+(baseSample+s)*bytes(format),if(format==1)2 else 4,encode(fp(0.5),format))
          if(stencil)put((if(secondary==6)512+baseSample+s else 64+(baseSample+s)*bytes(format)+(if(format==4)3 else 4)),1,0xa5)
        }
        val expected=scala.collection.mutable.Map.empty[Int,BigInt]++data
        d.io.descriptor.poke(0.U.asTypeOf(d.io.descriptor));d.io.input.poke(0.U.asTypeOf(d.io.input))
        val desc=d.io.descriptor;val in=d.io.input
        desc.colors.tileLog2.poke(3.U);desc.colors.sampleMode.poke(mode.U)
        val originX=if(front)Int.MinValue+2 else Int.MaxValue-5
        val originY=if(front)Int.MaxValue-5 else Int.MinValue+2
        desc.colors.originX.poke(originX.S);desc.colors.originY.poke(originY.S)
        in.color.quad.lanes(0).x.poke((originX+2).S);in.color.quad.lanes(0).y.poke((originY+2).S)
        desc.depth.format.poke(format.U);desc.depth.offset.poke(64.U)
        desc.stencil.format.poke(secondary.U);desc.stencil.offset.poke(512.U)
        in.color.samples(0).poke(((1<<n)-1).U);in.color.quad.frontFacing.poke(front.B)
        in.state.depthEnable.poke(depth.B);in.state.depthWrite.poke(true.B);in.state.depthFunction.poke(0.U)
        in.state.stencilEnable.poke(stencil.B)
        for((f,isFront)<-Seq((in.state.front,true),(in.state.back,false))){
          f.function.poke((if(isFront)6 else 7).U);f.reference.poke(7.U)
          f.readMask.poke(255.U);f.writeMask.poke(60.U)
          f.fail.poke(1.U);f.depthFail.poke(5.U);f.depthPass.poke(2.U)
        }
        val zs=Vector(fp(0.25),fp(0.75),fp(0.5),fp(0.125))
        in.color.quad.lanes(0).sampleDepthValid.poke(true.B)
        in.color.quad.lanes(0).depthOverride.poke((!front).B)
        in.color.quad.lanes(0).depth.poke(fp(0.25).U)
        for(s<-0 until 4)in.color.quad.lanes(0).sampleDepth(s).poke(zs(s).U)
        var passes=0;var zreject=0;var supdates=0;var zupdates=0;var qualified=0
        for(s<-0 until n){
          val sp= !stencil || front
          val z=if(front)zs(s)else fp(0.25)
          val zp= !depth || compare(encode(z,format),encode(fp(0.5),format),0,format==3 || format==5)
          if(sp && zp){passes+=1;qualified|=1<<s}
          if(sp && !zp)zreject+=1
          if(depth && sp && zp){put(64+(baseSample+s)*bytes(format),if(format==1)2 else 4,encode(z,format));zupdates+=1}
          if(stencil){val op=if(!sp)1 else if(!zp)5 else 2
            val next=(operation(0xa5,7,op)&60)|(0xa5&195)
            if(next!=0xa5){put(if(secondary==6)512+baseSample+s else 64+(baseSample+s)*bytes(format)+(if(format==4)3 else 4),1,next);supdates+=1}}
        }
        val wanted=data.toMap;data.clear();data++=expected
        d.io.start.poke(true.B);d.clock.step();d.io.start.poke(false.B)
        var cycle=0;var pending: Option[(Int,BigInt,BigInt)]=None;var held: Option[BigInt]=None
        while(!d.io.done.peek().litToBoolean && cycle<500){
          d.io.request.ready.poke((cycle%4!=0).B)
          val reply=pending.filter(_._1<=cycle);d.io.response.valid.poke(reply.nonEmpty.B)
          reply.foreach{case(_,tag,value)=>d.io.response.bits.tag.poke(tag.U);d.io.response.bits.data.poke(value.U)}
          held.foreach(v=>{d.io.request.valid.expect(true.B);assert(d.io.request.bits.peek().litValue==v)})
          if(reply.nonEmpty && d.io.response.ready.peek().litToBoolean)pending=None
          if(d.io.request.valid.peek().litToBoolean){val r=d.io.request.bits
            if(d.io.request.ready.peek().litToBoolean){assert(pending.isEmpty)
              val a=r.address.peek().litValue.toInt;val write=r.write.peek().litToBoolean
              if(write){val mask=r.mask.peek().litValue;data(a)=(data(a)&(full^mask))|(r.data.peek().litValue&mask)}
              pending=Some((cycle+3+cycle%3,r.tag.peek().litValue,if(write)BigInt(0)else data(a)));held=None
            }else held=Some(r.peek().litValue)
          }
          d.clock.step();cycle+=1
        }
        assert(cycle<500 && pending.isEmpty);d.io.response.valid.poke(false.B)
        d.io.fault.expect(false.B);d.io.tested.expect(n.U);d.io.passing.expect(passes.U)
        d.io.qualified.expect(qualified.U);d.io.depthRejected.expect(zreject.U)
        d.io.stencilRejected.expect((if(stencil && !front)n else 0).U)
        d.io.depthUpdates.expect(zupdates.U);d.io.stencilUpdates.expect(supdates.U)
        assert(data.toMap==wanted,s"packed depth/stencil mismatch format=$format secondary=$secondary mode=$mode front=$front")
      }
      info("GENERIC_DEPTH_LANE layouts=7 modes=3 faces=2 exactImages=42 signedOriginExtremes=true delayedReplies=true heldOffers=true")
    }
  }
  it should "drain an offered read on cancel and count initialization even when its final reply faults" in {
    simulate(new TypedDepthLane(0)){d=>
      d.io.start.poke(false.B);d.io.cancel.poke(false.B)
      d.io.descriptor.poke(0.U.asTypeOf(d.io.descriptor));d.io.input.poke(0.U.asTypeOf(d.io.input))
      d.io.request.ready.poke(false.B);d.io.response.valid.poke(false.B)
      d.io.response.bits.poke(0.U.asTypeOf(d.io.response.bits))
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      d.io.descriptor.colors.tileLog2.poke(3.U);d.io.descriptor.depth.format.poke(TypedDepthFormat.d16.U)
      d.io.input.state.depthEnable.poke(true.B);d.io.input.color.samples(0).poke(1.U)
      d.io.start.poke(true.B);d.clock.step();d.io.start.poke(false.B)
      var cycles=0
      while(!d.io.request.valid.peek().litToBoolean && cycles<20){d.clock.step();cycles+=1}
      assert(d.io.request.valid.peek().litToBoolean);d.io.request.bits.write.expect(false.B)
      val held=d.io.request.bits.peek().litValue;d.io.cancel.poke(true.B)
      for(_<-0 until 6){d.clock.step();d.io.request.valid.expect(true.B);assert(d.io.request.bits.peek().litValue==held);d.io.done.expect(false.B)}
      d.io.request.ready.poke(true.B);d.clock.step();d.io.request.ready.poke(false.B)
      d.io.response.bits.initialized.poke(true.B);d.io.response.bits.fault.poke(true.B)
      d.io.response.valid.poke(true.B);d.io.response.ready.expect(true.B);d.clock.step();d.io.response.valid.poke(false.B)
      d.clock.step();d.io.done.expect(true.B);d.io.fills.expect(1.U);d.io.reads.expect(0.U);d.io.writes.expect(0.U)
      d.io.fault.expect(true.B);d.io.qualified.expect(0.U)
    }
  }
}
