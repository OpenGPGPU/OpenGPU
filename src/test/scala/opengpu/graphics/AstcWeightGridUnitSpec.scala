package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class AstcWeightGridUnitSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  private val ranges=AstcWeightUnquantTables.maps
  private def reverse128(v: BigInt): BigInt={
    var out=BigInt(0);for(i<-0 until 128 if v.testBit(i))out|=BigInt(1)<<(127-i);out
  }
  private def reference(block: BigInt,gridX: Int,gridY: Int,dual: Boolean,quant: Int): (Int,Vector[Int])={
    val count=gridX*gridY*(if(dual)2 else 1)
    if(quant>11 || count<=0 || count>64)return(1,Vector.fill(64)(0))
    val bits=Seq(1,0,2,0,1,3,1,2,4,2,3,5)(quant)
    val trits=Seq(0,1,0,0,1,0,0,1,0,0,1,0)(quant)
    val quints=Seq(0,0,0,1,0,0,1,0,0,1,0,0)(quant)
    val stream=reverse128(block&((BigInt(1)<<128)-1))
    var off=0;var lc=0;var hc=0;val res=Array.fill(count)(0);val tq=Array.fill(22)(0)
    def read(n: Int): Int={val v=((stream>>off)&((BigInt(1)<<n)-1)).toInt;off+=n;v}
    for(i<-0 until count){
      res(i)=read(bits)
      if(trits!=0){
        val br=Seq(2,2,1,2,1)(lc);val sh=Seq(0,2,4,5,7)(lc);val nxt=Seq(1,2,3,4,0)(lc);val inc=lc==4
        tq(hc)|=read(br)<<sh;if(inc)hc+=1;lc=nxt
      }
      if(quints!=0){
        val br=Seq(3,2,2)(lc);val sh=Seq(0,3,5)(lc);val nxt=Seq(1,2,0)(lc);val inc=lc==2
        tq(hc)|=read(br)<<sh;if(inc)hc+=1;lc=nxt
      }
    }
    def tritAt(block: Int,k: Int): Int={
      val T=block&0xff;val special=((T>>2)&7)==7
      val c=if(special)(((T>>5)&7)<<2)|(T&3) else T&0x1f
      val t4=if(special)2 else if(((T>>5)&3)==3)2 else (T>>7)&1
      val t3=if(special)2 else if(((T>>5)&3)==3)(T>>7)&1 else (T>>5)&3
      val t2=if((c&3)==3)2 else if(((c>>2)&3)==3)2 else (c>>4)&1
      val t1=if((c&3)==3)(c>>4)&1 else if(((c>>2)&3)==3)2 else (c>>2)&3
      val t0=if((c&3)==3)(((c>>3)&1)<<1)|(((c>>2)&1) & ~((c>>3)&1)) else if(((c>>2)&3)==3)c&3 else (((c>>1)&1)<<1)|((c&1) & ~((c>>1)&1))
      Seq(t0,t1,t2,t3,t4)(k)
    }
    def quintAt(block: Int,k: Int): Int={
      val Q=block&0x7f;val special=((Q>>1)&3)==3 && ((Q>>5)&3)==0
      val q2=if(special)(((Q&1)<<2) | ((((Q>>4)&1) & ~(Q&1))<<1) | (((Q>>3)&1) & ~(Q&1))) else if(((Q>>1)&3)==3)4 else (Q>>5)&3
      val c=if(special)0 else if(((Q>>1)&3)==3)((((Q>>3)&3)<<3)|(((~(Q>>5))&3)<<1)|(Q&1)) else Q&0x1f
      val q1=if(special)4 else if((c&7)==5)4 else (c>>3)&3
      val q0=if(special)4 else if((c&7)==5)(c>>3)&3 else c&7
      Seq(q0,q1,q2)(k)
    }
    val weights=(0 until count).map{i=>
      val v=if(trits!=0)((tritAt(tq(i/5),i%5)<<bits)|res(i))
        else if(quints!=0)((quintAt(tq(i/3),i%3)<<bits)|res(i)) else res(i)
      if(v>=ranges(quant).size)throw new RuntimeException(s"quant=$quant i=$i v=$v bits=$bits trits=$trits quints=$quints block=$block")
      ranges(quant)(v)}
    (0,(weights++Vector.fill(64-count)(0)).toVector)
  }
  behavior of "AstcWeightGridUnit"
  it should "decode every quantization method across grids and random blocks" in {
    simulate(new AstcWeightGridUnit){d=>zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val random=new scala.util.Random(0x41535443)
      var tag=0;var checked=0
      for(quant<-0 until 12;grid<-Seq((4,4,false),(5,4,false),(8,5,false),(8,8,false),(4,4,true),(8,4,true))){
        val (gx,gy,dual)=grid
        for(_<-0 until 6){
          val block=BigInt(128,random)
          val n=gx*gy*(if(dual)2 else 1)
          val need=((Seq(1,8,2,7,13,3,10,18,4,13,23,5)(quant)*n)+Seq(1,5,1,3,5,1,3,5,1,3,5,1)(quant)-1)/Seq(1,5,1,3,5,1,3,5,1,3,5,1)(quant)
          if(need>120) ()
          else{
          val want=reference(block,gx,gy,dual,quant)
          tag=(tag+1)&0xff;val q=d.io.in.bits;zero(q);q.tag.poke(tag.U);q.block.poke(block.U)
          q.gridX.poke(gx.U);q.gridY.poke(gy.U);q.dualPlane.poke(dual.B);q.quant.poke(quant.U)
          d.io.in.valid.poke(true.B);var n=0;while(!d.io.in.ready.peek().litToBoolean && n<20){d.clock.step();n+=1}
          assert(d.io.in.ready.peek().litToBoolean);d.clock.step();d.io.in.valid.poke(false.B);zero(q)
          n=0;while(!d.io.out.valid.peek().litToBoolean && n<500){d.clock.step();n+=1}
          assert(n<500,s"weight grid timeout quant=$quant grid=$grid")
          val o=d.io.out.bits;o.tag.expect(tag.U);o.status.expect(want._1.U)
          o.count.expect((gx*gy*(if(dual)2 else 1)).U)
          val got=(0 until 64).map(i=>o.weights(i).peek().litValue.toInt)
          assert(got==want._2.toVector,s"weights differ quant=$quant grid=$grid check=$checked block=$block")
          d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B);checked+=1
          }
        }
      }
      info(s"ASTC_WEIGHT_GRID quant12=true grids6=true randomBlocks=$checked mapTables12=true")
    }
  }
}
