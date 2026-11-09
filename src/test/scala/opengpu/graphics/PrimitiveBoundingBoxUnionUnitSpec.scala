package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class PrimitiveBoundingBoxUnionUnitSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  private def bits(v: Float)=BigInt(java.lang.Float.floatToRawIntBits(v)&0xffffffffL)
  private def f(b: BigInt)=java.lang.Float.intBitsToFloat(b.toInt)
  behavior of "PrimitiveBoundingBoxUnionUnit"
  it should "union finite primitive boxes and reject NaN or malformed sets" in {
    simulate(new PrimitiveBoundingBoxUnionUnit){d=>zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val random=new scala.util.Random(0x42424f58);var tag=0
      def run(count: Int,valid: Int,boxes: Seq[(Seq[Float],Seq[Float])]): (Int,Boolean,Vector[BigInt],Vector[BigInt])={
        tag=(tag+1)&0xff;val q=d.io.in.bits;zero(q);q.tag.poke(tag.U);q.count.poke(count.U);q.valid.poke(valid.U)
        for(i<-0 until 8){
          val (mn,mx)=if(i<boxes.size)boxes(i) else (Seq.fill(4)(0f),Seq.fill(4)(0f))
          for(c<-0 until 4){q.boxes(i).min(c).poke(bits(mn(c)).U);q.boxes(i).max(c).poke(bits(mx(c)).U)}
          q.boxes(i).version.poke(1.U)}
        d.io.in.valid.poke(true.B);var n=0;while(!d.io.in.ready.peek().litToBoolean && n<20){d.clock.step();n+=1}
        assert(d.io.in.ready.peek().litToBoolean);d.clock.step();d.io.in.valid.poke(false.B);zero(q)
        n=0;while(!d.io.out.valid.peek().litToBoolean && n<20){d.clock.step();n+=1}
        assert(n<20);val o=d.io.out.bits;o.tag.expect(tag.U)
        val r=(o.status.peek().litValue.toInt,o.empty.peek().litToBoolean,
          (0 until 4).map(c=>o.min(c).peek().litValue).toVector,(0 until 4).map(c=>o.max(c).peek().litValue).toVector)
        d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B);r}
      def ref(count: Int,valid: Int,boxes: Seq[(Seq[Float],Seq[Float])]): (Int,Boolean,Vector[BigInt],Vector[BigInt])={
        if(count<1 || count>8 || (valid>>count)!=0)(1,true,Vector.fill(4)(BigInt(0)),Vector.fill(4)(BigInt(0)))
        else{
          val active=(0 until count).filter(i=>((valid>>i)&1)!=0)
          val bad=active.exists(i=>boxes(i)._1.exists(_.isNaN)||boxes(i)._2.exists(_.isNaN))
          if(bad)(1,true,Vector.fill(4)(BigInt(0)),Vector.fill(4)(BigInt(0)))
          else if(active.isEmpty)(0,true,Vector.fill(4)(BigInt(0)),Vector.fill(4)(BigInt(0)))
          else{
            val mn=(0 until 4).map(c=>active.map(i=>boxes(i)._1(c)).min)
            val mx=(0 until 4).map(c=>active.map(i=>boxes(i)._2(c)).max)
            (0,false,mn.map(bits).toVector,mx.map(bits).toVector)}}}
      val pool=Seq(-2f,-1f,-0.5f,0f,0.25f,1f,2f,Float.PositiveInfinity,Float.NegativeInfinity)
      var checks=0
      for(_<-0 until 200){
        val count=1+random.nextInt(8);val nvalid=random.nextInt(count+1)
        val valid=(0 until nvalid).map(_=>random.nextInt(count)).foldLeft(0)((a,i)=>a|(1<<i))
        val boxes=(0 until count).map{_=>
          val mn=Seq.fill(4)(pool(random.nextInt(pool.size)))
          val mx=mn.map(v=>math.max(v,pool(random.nextInt(pool.size))))
          (mn,mx)}
        val got=run(count,valid,boxes)
        assert(got==ref(count,valid,boxes),s"count=$count valid=$valid boxes=$boxes got=$got")
        checks+=1}
      val nanBox=Seq((Seq(Float.NaN,0f,0f,0f),Seq(1f,1f,1f,1f)))
      assert(run(1,1,nanBox)._1==1)
      assert(run(0,0,Seq())._1==1)
      assert(run(9,0,Seq())._1==1)
      assert(run(2,4,Seq((Seq(0f,0f,0f,0f),Seq(1f,1f,1f,1f)),(Seq(2f,2f,2f,2f),Seq(3f,3f,3f,3f))))._1==1)
      assert(run(2,0,Seq((Seq(0f,0f,0f,0f),Seq(1f,1f,1f,1f)),(Seq(2f,2f,2f,2f),Seq(3f,3f,3f,3f))))==(0,true,Vector.fill(4)(BigInt(0)),Vector.fill(4)(BigInt(0))))
      info(s"BOUNDING_BOX_UNION checks=$checks finiteUnion=true nanRejected=true empty=true malformedRejected=true")
    }
  }
}
