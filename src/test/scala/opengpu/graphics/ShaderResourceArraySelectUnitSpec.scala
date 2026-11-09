package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class ShaderResourceArraySelectUnitSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  private case class Ref(status: Int,slot: Int,oor: Boolean,unbound: Boolean,divergent: Boolean)
  private def ref(base: Int,len: Int,index: Long,limit: Int,mask: Int,cls: Int,uniform: Boolean): Ref={
    val inArray=index>=0 && index<len
    val inStage=base.toLong+index<limit
    val bound=inArray && index<16 && ((mask>>index.toInt)&1)!=0
    val divergent=cls==0 && !uniform
    val oor= !inArray || !inStage
    val legal=len>=1 && len<=16 && !divergent && !oor && bound
    Ref(if(legal)0 else 1,if(legal)(base+index).toInt&0xff else 0,oor,inArray&&inStage&& !bound,divergent)
  }
  private class Driver(val d: ShaderResourceArraySelectUnit) {
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    def run(base: Int,len: Int,index: Long,limit: Int,mask: Int,cls: Int,uniform: Boolean,tag: Int=1): Ref={
      val q=d.io.in.bits;zero(q);q.tag.poke(tag.U);q.baseSlot.poke(base.U);q.arrayLength.poke(len.U)
      q.index.poke(index.U);q.stageLimit.poke(limit.U);q.boundMask.poke(mask.U)
      q.resourceClass.poke(cls.U);q.indexUniform.poke(uniform.B)
      d.io.in.valid.poke(true.B);var n=0;while(!d.io.in.ready.peek().litToBoolean && n<20){d.clock.step();n+=1}
      assert(d.io.in.ready.peek().litToBoolean);d.clock.step();d.io.in.valid.poke(false.B);zero(q)
      n=0;while(!d.io.out.valid.peek().litToBoolean && n<20){d.clock.step();n+=1}
      assert(n<20);val o=d.io.out.bits;o.tag.expect(tag.U)
      val r=Ref(o.status.peek().litValue.toInt,o.slot.peek().litValue.toInt,o.outOfRange.peek().litToBoolean,
        o.unbound.peek().litToBoolean,o.divergentSampler.peek().litToBoolean)
      d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B);r}
  }
  behavior of "ShaderResourceArraySelectUnit"
  it should "select bound dynamically uniform resource array entries" in {
    simulate(new ShaderResourceArraySelectUnit){d=>val t=new Driver(d)
      assert(t.run(4,4,2,32,0xffff,0,true)==ref(4,4,2,32,0xffff,0,true))
      assert(t.run(4,4,2,32,0xffff,0,true).slot==6)
      assert(t.run(4,4,15,32,0xffff,2,false)==ref(4,4,15,32,0xffff,2,false))
      assert(t.run(4,4,1,32,0xfffd,0,true)==ref(4,4,1,32,0xfffd,0,true))
      assert(t.run(4,4,4,32,0xffff,0,true)==ref(4,4,4,32,0xffff,0,true))
      assert(t.run(4,4,1,5,0xffff,0,true)==ref(4,4,1,5,0xffff,0,true))
      assert(t.run(4,4,1,32,0xffff,0,false)==ref(4,4,1,32,0xffff,0,false))
      assert(t.run(4,4,1,32,0xffff,1,false)==ref(4,4,1,32,0xffff,1,false))
      assert(t.run(4,0,0,32,0xffff,0,true)==ref(4,0,0,32,0xffff,0,true))
      assert(t.run(4,17,0,32,0xffff,0,true)==ref(4,17,0,32,0xffff,0,true))
      assert(t.run(250,4,10,255,0xffff,0,true)==ref(250,4,10,255,0xffff,0,true))
      assert(t.run(4,4,16,32,0xffff,0,true)==ref(4,4,16,32,0xffff,0,true))
      val random=new scala.util.Random(0x52415345)
      var checks=0
      for(_<-0 until 300){
        val len=random.nextInt(20);val base=random.nextInt(256);val index=random.nextInt(20).toLong
        val limit=random.nextInt(256);val mask=random.nextInt(1<<16);val cls=random.nextInt(4);val uni=random.nextBoolean()
        val got=t.run(base,len,index,limit,mask,cls,uni)
        assert(got==ref(base,len,index,limit,mask,cls,uni),s"base=$base len=$len idx=$index limit=$limit mask=$mask cls=$cls uni=$uni got=$got")
        checks+=1}
      info(s"RESOURCE_ARRAY_SELECT checks=$checks bounds=true unbound=true samplerUniform=true")
    }
  }
}
