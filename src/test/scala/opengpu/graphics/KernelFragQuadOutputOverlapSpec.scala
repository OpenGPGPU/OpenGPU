package opengpu.graphics
import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class KernelFragQuadOutputOverlapSpec extends AnyFlatSpec {
  for ((lanes,warps) <- Seq((4,2),(16,8))) {
    it should s"retain different output contracts across overlapping $lanes x $warps banks" in {
      val cap=lanes*warps
      simulate(new KernelFragStage(GpuConfig(lanes=lanes,warps=warps),GraphicsConfig(),4,quadExports=true)) { d =>
        def zero(x: Data): Unit = x match {
          case r: Record => r.elements.values.foreach(zero)
          case v: Vec[_] => v.foreach(zero)
          case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input => b.poke(false.B)
          case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input => s.poke(0.S)
          case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input => u.poke(0.U)
          case _ => ()
        }
        zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
        d.io.shaderPc.poke(0x1000.U);d.io.kernargBase.poke(0x8000.U);d.io.kernargBankStride.poke(0x2000.U)
        d.io.inputSkip.poke(15.U);d.io.kernelLaunch.ready.poke(true.B);d.io.drawRetire.ready.poke(true.B)
        d.io.texMem.req.ready.poke(true.B);d.io.kernelTexWriteback.ready.poke(true.B);d.io.kernelVectorTexWriteback.ready.poke(true.B)
        val mem=mutable.LongMap[BigInt]()
        def put(a:Long,v:BigInt):Unit={val b=a& ~63L;val shift=(a&63).toInt*8;mem(b)=(mem.getOrElse(b,BigInt(0)) & ~(BigInt("ffffffff",16)<<shift))|((v&BigInt("ffffffff",16))<<shift)}
        def word(a:Long):BigInt=(mem.getOrElse(a& ~63L,BigInt(0))>>((a&63).toInt*8))&BigInt("ffffffff",16)
        def canary(bank:Int,slice:Int,i:Int):BigInt=BigInt(0x5a000000L+bank*0x100000+slice*0x10000+i)
        def metadata(which:Int,mask:Int):Unit={d.io.outputSkip.poke(mask.U);d.io.abi1.poke((which==0).B)}
        def rasterDepth(which:Int,i:Int):Int = if(i%3==1) -(which*1000+100+i) else which*1000+100+i
        def quad(which:Int,q:Int):Unit={
          for(k<-0 until 4){val i=q*4+k;val p=d.io.fragIn.bits.lanes(k)
            p.x.poke((which*1000+i+1).S);p.y.poke((i+1).S);p.depth.poke(rasterDepth(which,i).S)
            for(s<-0 until 4)d.io.fragDepths(k)(s).poke((which*100000+i*16+s+7).U)
            p.covered.poke((i!=cap-1).B);p.coverageMask.poke(1.U);p.color.r.poke(1.U);p.color.g.poke(2.U);p.color.b.poke(3.U);p.alpha.poke(255.U)
            d.io.fragUv(k).u.poke((i+10).U);d.io.fragUv(k).v.poke((i+20).U)
          }
        }
        case class Reply(id:Int,addr:Long,write:Boolean,data:BigInt,mask:BigInt,due:Int)
        for (firstMask<-Seq(15,0)) {
          val masks=Seq(firstMask,firstMask^15)
          for(bank<-0 until 2;slice<-0 until 10;i<-0 until cap)put(0x8000L+bank*0x2000+slice*4L*cap+i*4,canary(bank,slice,i))
          metadata(0,masks(0));d.io.wordMemReq.ready.poke(false.B);d.io.quadOut.get.ready.poke(false.B)
          for(q<-0 until cap/4){quad(0,q);d.io.fragIn.valid.poke(true.B);d.io.fragIn.ready.expect(true.B);d.clock.step()}
          d.io.fragIn.valid.poke(false.B);d.io.flush.poke(true.B);d.clock.step();d.io.flush.poke(false.B)
          val pending=mutable.ArrayBuffer.empty[Reply];var cycle=0;var launched=0;var completed=0;var retired=0;var fed=0;var flushed=false;var overlap=false;var emitted=0;var held=Option.empty[BigInt]
          val expected=for(which<-0 until 2;i<-0 until cap if i!=cap-1 && (masks(which)==15 || i%5!=0))yield (which,i)
          val bases=mutable.ArrayBuffer.empty[Long]
          while(retired<2 && cycle<12000){
            d.io.wordMemReq.ready.poke((cycle%9>1).B);d.io.quadOut.get.ready.poke((cycle%7>1).B)
            val feeding=launched==1 && fed<cap/4
            if(feeding){metadata(1,masks(1));quad(1,fed)}else{d.io.outputSkip.poke(10.U);d.io.abi1.poke(false.B)}
            d.io.fragIn.valid.poke(feeding.B)
            val feedFire=feeding&&d.io.fragIn.ready.peek().litToBoolean
            d.io.flush.poke((launched==1 && fed==cap/4 && !flushed).B)
            val flushNow=d.io.flush.peek().litToBoolean
            d.io.kernelCompletion.valid.poke((launched>completed && (completed>0 || flushed)).B);d.io.kernelCompletion.bits.success.poke(true.B)
            val reply=pending.indexWhere(_.due<=cycle);d.io.wordMemResp.valid.poke((reply>=0).B)
            if(reply>=0){val p=pending(reply);d.io.wordMemResp.bits.transactionId.poke(p.id.U);d.io.wordMemResp.bits.readData.poke(mem.getOrElse(p.addr,BigInt(0)).U);d.io.wordMemResp.bits.fault.poke(false.B)}
            val replyFire=reply>=0 && d.io.wordMemResp.ready.peek().litToBoolean
            val reqFire=d.io.wordMemReq.valid.peek().litToBoolean&&d.io.wordMemReq.ready.peek().litToBoolean
            if(d.io.kernelLaunch.valid.peek().litToBoolean){
              assert(pending.isEmpty && launched<2)
              if(launched==1)assert(flushed&&completed==1,"second bank launched before overlap was exercised")
              val base=d.io.kernelLaunch.kernargAddress.peek().litValue.toLong;bases+=base;val bank=((base-0x8000)/0x2000).toInt
              for(i<-0 until cap){
                for(slice<-Seq(7,8))assert(word(base+slice*4L*cap+i*4)==(if(masks(launched)==15)canary(bank,slice,i)else BigInt((if(slice==7)rasterDepth(launched,i) else if(i==cap-1)0 else 1).toLong & 0xffffffffL)),"overlapped bank initialized with another draw's mask")
                put(base+24L*cap+i*4,BigInt(if(launched==0)"112233ff" else "aabbccff",16))
                if(masks(launched)==0){put(base+28L*cap+i*4,launched*1000+300+i);put(base+32L*cap+i*4,if(i%5==0)0 else 3)}
              }
              launched+=1
            }
            if(d.io.kernelCompletion.valid.peek().litToBoolean&&d.io.kernelCompletion.ready.peek().litToBoolean)completed+=1
            val packet=d.io.quadOut.get
            d.io.out.valid.expect(false.B)
            held.foreach(v=>{packet.valid.expect(true.B);assert(packet.bits.peek().litValue==v,"stalled overlapped quad changed owner")})
            if(packet.valid.peek().litToBoolean && packet.ready.peek().litToBoolean) {
              for(p<-0 until 4 if packet.bits(p).coverageMask.peek().litValue!=0) {
                val (which,i)=expected(emitted);val out=packet.bits(p)
                out.x.expect((which*1000+i+1).U);out.coverageMask.expect(1.U)
                out.color.expect(BigInt(if(which==0)"112233ff" else "aabbccff",16).U)
                val overrideDepth=which==1 || masks(which)==0
                val selected=if(masks(which)==15)rasterDepth(which,i) else which*1000+300+i
                for(s<-0 until 4)out.depths(s).expect((if(overrideDepth)selected.toLong&0xffffffL else which*100000L+i*16+s+7).U)
                emitted+=1
              }
            }
            held=if(packet.valid.peek().litToBoolean && !packet.ready.peek().litToBoolean)Some(packet.bits.peek().litValue)else None
            if(d.io.drawRetire.valid.peek().litToBoolean)retired+=1
            if(replyFire){val p=pending.remove(reply);if(p.write)for(b<-0 until 64 if p.mask.testBit(b)){val shift=b*8;mem(p.addr)=(mem.getOrElse(p.addr,BigInt(0))& ~(BigInt(255)<<shift))|(((p.data>>shift)&255)<<shift)}}
            if(reqFire){val p=d.io.wordMemReq.bits;val id=p.transactionId.peek().litValue.toInt;assert(!pending.exists(_.id==id));pending+=Reply(id,p.address.peek().litValue.toLong,p.isWrite.peek().litToBoolean,p.writeData.peek().litValue,p.byteMask.peek().litValue,cycle+4)}
            if(feedFire){assert(completed==0,"producer did not overlap the first kernel");fed+=1;overlap=true}
            if(flushNow)flushed=true
            d.clock.step();cycle+=1
          }
          assert(retired==2&&emitted==expected.size&&overlap&&bases.distinct.size==2)
          for(which<-0 until 2 if masks(which)==15; slice<-Seq(7,8);i<-0 until cap){val bank=((bases(which)-0x8000)/0x2000).toInt;assert(word(bases(which)+slice*4L*cap+i*4)==canary(bank,slice,i))}
          d.io.fragIn.valid.poke(false.B);d.io.flush.poke(false.B);d.io.kernelCompletion.valid.poke(false.B);d.io.wordMemResp.valid.poke(false.B);d.clock.step(2);d.io.drained.expect(true.B)
          println(s"QUAD_OUTPUT_OVERLAP cap=$cap first=$firstMask second=${masks(1)} PASS")
        }
      }
    }
  }
}
