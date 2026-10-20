package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable
import scala.util.Random

class TextureCoordinatePolicySpec extends AnyFlatSpec {
  private case class Binding(base: Long,w: Int,h: Int,levels: Int,u: Int,v: Int,signed: Boolean) {
    def level(n: Int): (Long,Int,Int)={var a=base;var x=w;var y=h
      for(_<-0 until n){a+=x.toLong*y*4;x=(x/2).max(1);y=(y/2).max(1)};(a,x,y)}
    def texel(l: Int,x: Int,y: Int): BigInt=(0 until 4).foldLeft(BigInt(0)){(v,c)=>
      (v<<8)|BigInt((l*47+x*(c*2+13)+y*(c*7+17)+c*41)&255)}
    def read(address: Long): BigInt={
      (0 until levels).find(l=>{val(a,x,y)=level(l);address>=a && address<a+x.toLong*y*4}) match {
        case Some(l)=>val(a,x,_)=level(l);assert((address-a)%4==0);val i=(address-a)/4;texel(l,(i%x).toInt,(i/x).toInt)
        case None=>sys.error(s"texture request escaped captured allocation: $address binding=$this")
      }
    }
  }
  private def reference(b: Binding,u: Long,v: Long,mip: Int,fraction: Int): BigInt={
    def coordinate(raw: Long): Long=if(b.signed && (raw&0x80000000L)!=0)raw-0x100000000L else raw
    def axis(raw: Long,n: Int,mode: Int): (Int,Int,Int)={
      // Unfold the complete coordinate, then wrap integer texel indices. This
      // does not use the hardware's normalized-period reflection or products.
      val q=(coordinate(raw)*n>>8)-128;val i=Math.floorDiv(q,256L);val f=Math.floorMod(q,256L).toInt
      def wrap(x: Long): Int=mode match {
        case 0=>Math.floorMod(x,n.toLong).toInt
        case 1=>x.max(0).min(n-1).toInt
        case 2=>val p=Math.floorMod(x,2L*n);(if(p<n)p else 2L*n-1-p).toInt
      };(wrap(i),wrap(i+1),f)
    }
    def bilinear(l: Int): BigInt={val(_,w,h)=b.level(l);val(x0,x1,fx)=axis(u,w,b.u);val(y0,y1,fy)=axis(v,h,b.v)
      val words=Vector(b.texel(l,x0,y0),b.texel(l,x1,y0),b.texel(l,x0,y1),b.texel(l,x1,y1))
      val weights=Vector((256-fx)*(256-fy),fx*(256-fy),(256-fx)*fy,fx*fy)
      (0 until 4).foldLeft(BigInt(0)){(out,c)=>val shift=24-c*8
        (out<<8)|((words.zip(weights).map{case(w,n)=>((w>>shift)&255)*n}.sum>>16)&255)}
    }
    val l=mip.min(b.levels-1);val first=bilinear(l)
    if(fraction==0 || l==b.levels-1)first else {val second=bilinear(l+1)
      (0 until 4).foldLeft(BigInt(0)){(out,c)=>val shift=24-c*8
        (out<<8)|(((((first>>shift)&255)*(256-fraction)+((second>>shift)&255)*fraction)>>8)&255)}
    }
  }
  for(cache<-Seq(0,8))
  it should s"sample signed per-axis wrap policies with captured mip bindings and held replies cache=$cache" in {
    simulate(new TextureUnit(wordCacheEntries=cache,coordinatePolicyInput=true)){d=>
      PointLineReference.zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      var samples=0;var faults=0;var physicalReads=0;var reordered=0
      def run(b: Binding,u: Long,v: Long,mip: Int=0,fraction: Int=0,invalid: Boolean=false): Unit={
        d.io.texBase.poke(b.base.U);d.io.texWidth.poke(b.w.U);d.io.texHeight.poke(b.h.U);d.io.texMaxLevel.poke((b.levels-1).U)
        d.io.texFormat.poke(0.U);d.io.wrapMode.poke(false.B)
        val p=d.io.coordinatePolicy.get;p.wrapU.poke(b.u.U);p.wrapV.poke(b.v.U);p.signed.poke(b.signed.B)
        d.io.invalidateCache.foreach(_.poke(true.B))
        d.io.sample.bits.u.poke(u.U);d.io.sample.bits.v.poke(v.U);d.io.sample.bits.mipLevel.poke(mip.U);d.io.sample.bits.lodFrac.poke(fraction.U)
        d.io.sample.valid.poke(true.B);d.io.result.ready.poke(false.B)
        val pending=mutable.ArrayBuffer.empty[(Long,Int)];var reply: Option[Long]=None
        var accepted=false;var done=false;var cycle=0;var heldCycles=0;var reads=0
        val expected=if(invalid)BigInt(0)else reference(b,u,v,mip,fraction)
        while(!done && cycle<500){
          if(accepted){
            d.io.sample.valid.poke(false.B);d.io.invalidateCache.foreach(_.poke(false.B))
            d.io.texBase.poke("he0000000".U);d.io.texWidth.poke(2.U);d.io.texHeight.poke(2.U);d.io.texFormat.poke(1.U);d.io.texMaxLevel.poke(0.U)
            p.wrapU.poke(3.U);p.wrapV.poke(3.U);p.signed.poke((!b.signed).B)
          }
          val accept=d.io.sample.valid.peek().litToBoolean && d.io.sample.ready.peek().litToBoolean
          d.io.mem.req.ready.poke((cycle%5!=0).B)
          if(reply.isEmpty && cycle%3==0){
            val candidates=pending.indices.filter(i=>pending(i)._2<=cycle)
            if(candidates.nonEmpty){val i=candidates.last;if(i>0)reordered+=1;reply=Some(pending.remove(i)._1)}
          }
          d.io.mem.resp.valid.poke(reply.nonEmpty.B);reply.foreach{a=>d.io.mem.resp.bits.addr.poke(a.U);d.io.mem.resp.bits.data.poke(b.read(a).U);d.io.mem.resp.bits.write.poke(false.B)}
          val response=d.io.mem.resp.valid.peek().litToBoolean && d.io.mem.resp.ready.peek().litToBoolean
          if(d.io.mem.req.valid.peek().litToBoolean && d.io.mem.req.ready.peek().litToBoolean){
            val a=d.io.mem.req.bits.addr.peek().litValue.toLong;b.read(a);pending+=((a,cycle+2));reads+=1;physicalReads+=1
          }
          if(d.io.result.valid.peek().litToBoolean){
            d.io.result.bits.expect(expected.U);d.io.coordinateFault.get.expect(invalid.B)
            if(heldCycles==4){d.io.result.ready.poke(true.B);done=true}else heldCycles+=1
          }
          d.clock.step();if(accept)accepted=true;if(response)reply=None;cycle+=1
        }
        assert(done && accepted && pending.isEmpty && reply.isEmpty,s"sampler did not drain: $b cycle=$cycle")
        if(invalid){assert(reads==0);faults+=1}else samples+=1
      }
      val random=new Random(0x4d495252L+cache)
      val edges=Vector[Long](0,1,0x7fff,0x8000,0xffff,0x10000,0x10001,0x18000,0x1ffff,0x20000,0x20001,0x7fffffffL,0x80000000L,0xffff8000L,0xffffffffL)
      val pairs=edges.zipWithIndex.map{case(u,i)=>(u,edges((i*7+3)%edges.size))}++Vector.fill(32)((random.nextLong()&0xffffffffL,random.nextLong()&0xffffffffL))
      for((w,h)<-Vector((3,5),(1,3),(7,1),(257,129),(16383,1));wu<-0 to 2;wv<-0 to 2;signed<-Seq(false,true);(u,v)<-pairs)
        run(Binding(0x10000,w,h,1,wu,wv,signed),u,v)
      for(wu<-0 to 2;wv<-0 to 2;signed<-Seq(false,true);level<-0 to 3;fraction<-Seq(0,1,127,255))
        run(Binding(0x40000,7,5,4,wu,wv,signed),0xfffed234L,0x23567L,level,fraction)
      for((u,v)<-Seq((3,0),(0,3),(3,3)))run(Binding(0x80000,3,5,1,u,v,true),0xffffffffL,0x10000,invalid=true)
      run(Binding(0x80000,3,5,1,2,0,true),0xffffffffL,0x10000)
      assert(samples==4519 && faults==3 && reordered>0)
      info(s"TEXTURE_COORDINATE_POLICY cache=$cache samples=$samples faults=$faults reads=$physicalReads reordered=$reordered modesPerAxis=3 signedAndUnsigned=true NPOT=true fullCoordinates32=true mirroredFloorWeights=true mipTrilinear=true capturedBinding=true immutableResultFault=true noInvalidReads=true healthyReuse=true")
    }
  }
}
