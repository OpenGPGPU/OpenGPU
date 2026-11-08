package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class AstcColorIntegerUnitSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  private val levels=Seq(2,3,4,5,6,8,10,12,16,20,24,32,40,48,64,80,96,128,160,192,256)
  private val bitsPer=Seq(1,0,2,0,1,3,1,2,4,2,3,5,3,4,6,4,5,7,5,6,8)
  private val tritPer=Seq(0,1,0,0,1,0,0,1,0,0,1,0,0,1,0,0,1,0,0,1,0)
  private val quintPer=Seq(0,0,0,1,0,0,1,0,0,1,0,0,1,0,0,1,0,0,1,0,0)
  private def needed(n: Int,q: Int): Int={
    (n*bitsPer(q)) + (if(tritPer(q)!=0)(n*8+4)/5 else 0) + (if(quintPer(q)!=0)(n*7+2)/3 else 0)
  }
  private val quantTable: Seq[Seq[Int]]=Seq(
    Seq(
      Seq(-1, -1, 0, 0, 2, 3, 5, 6, 8, 9, 11, 12, 14, 15, 17, 18),
      Seq(20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20),
      Seq(20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20),
      Seq(20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20),
      Seq(20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20),
      Seq(20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20),
      Seq(20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20),
      Seq(20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20),
    ),
    Seq(
      Seq(-1, -1, -1, -1, 0, 0, 0, 1, 2, 2, 3, 4, 5, 5, 6, 7),
      Seq(8, 8, 9, 10, 11, 11, 12, 13, 14, 14, 15, 16, 17, 17, 18, 19),
      Seq(20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20),
      Seq(20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20),
      Seq(20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20),
      Seq(20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20),
      Seq(20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20),
      Seq(20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20),
    ),
    Seq(
      Seq(-1, -1, -1, -1, -1, -1, 0, 0, 0, 0, 1, 1, 2, 2, 3, 3),
      Seq(4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11),
      Seq(12, 12, 13, 13, 14, 14, 15, 15, 16, 16, 17, 17, 18, 18, 19, 19),
      Seq(20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20),
      Seq(20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20),
      Seq(20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20),
      Seq(20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20),
      Seq(20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20),
    ),
    Seq(
      Seq(-1, -1, -1, -1, -1, -1, -1, -1, 0, 0, 0, 0, 0, 1, 1, 1),
      Seq(2, 2, 2, 3, 3, 4, 4, 4, 5, 5, 5, 6, 6, 7, 7, 7),
      Seq(8, 8, 8, 9, 9, 10, 10, 10, 11, 11, 11, 12, 12, 13, 13, 13),
      Seq(14, 14, 14, 15, 15, 16, 16, 16, 17, 17, 17, 18, 18, 19, 19, 19),
      Seq(20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20),
      Seq(20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20),
      Seq(20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20),
      Seq(20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20),
    ),
    Seq(
      Seq(-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 0, 0, 0, 0, 0, 0),
      Seq(1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 4, 4, 4, 4, 5, 5),
      Seq(5, 5, 6, 6, 7, 7, 7, 7, 8, 8, 8, 8, 9, 9, 10, 10),
      Seq(10, 10, 11, 11, 11, 11, 12, 12, 13, 13, 13, 13, 14, 14, 14, 14),
      Seq(15, 15, 16, 16, 16, 16, 17, 17, 17, 17, 18, 18, 19, 19, 19, 19),
      Seq(20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20),
      Seq(20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20),
      Seq(20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20),
    ),
    Seq(
      Seq(-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 0, 0, 0, 0),
      Seq(0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3),
      Seq(4, 4, 4, 4, 5, 5, 5, 5, 6, 6, 6, 6, 7, 7, 7, 7),
      Seq(8, 8, 8, 8, 9, 9, 9, 9, 10, 10, 10, 10, 11, 11, 11, 11),
      Seq(12, 12, 12, 12, 13, 13, 13, 13, 14, 14, 14, 14, 15, 15, 15, 15),
      Seq(16, 16, 16, 16, 17, 17, 17, 17, 18, 18, 18, 18, 19, 19, 19, 19),
      Seq(20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20),
      Seq(20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20),
    ),
    Seq(
      Seq(-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 0, 0),
      Seq(0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 2, 2, 2, 2),
      Seq(2, 3, 3, 3, 3, 4, 4, 4, 4, 4, 5, 5, 5, 5, 5, 6),
      Seq(6, 6, 6, 7, 7, 7, 7, 7, 8, 8, 8, 8, 8, 9, 9, 9),
      Seq(9, 10, 10, 10, 10, 10, 11, 11, 11, 11, 11, 12, 12, 12, 12, 13),
      Seq(13, 13, 13, 13, 14, 14, 14, 14, 14, 15, 15, 15, 15, 16, 16, 16),
      Seq(16, 16, 17, 17, 17, 17, 17, 18, 18, 18, 18, 19, 19, 19, 19, 19),
      Seq(20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20),
    ),
    Seq(
      Seq(-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      Seq(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1),
      Seq(2, 2, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 4, 4),
      Seq(5, 5, 5, 5, 5, 5, 6, 6, 6, 6, 7, 7, 7, 7, 7, 7),
      Seq(8, 8, 8, 8, 8, 8, 9, 9, 9, 9, 10, 10, 10, 10, 10, 10),
      Seq(11, 11, 11, 11, 11, 11, 12, 12, 12, 12, 13, 13, 13, 13, 13, 13),
      Seq(14, 14, 14, 14, 14, 14, 15, 15, 15, 15, 16, 16, 16, 16, 16, 16),
      Seq(17, 17, 17, 17, 17, 17, 18, 18, 18, 18, 19, 19, 19, 19, 19, 19),
    ),
    Seq(
      Seq(-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      Seq(-1, -1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1),
      Seq(1, 1, 1, 1, 2, 2, 2, 2, 2, 2, 3, 3, 3, 3, 3, 4),
      Seq(4, 4, 4, 4, 4, 4, 5, 5, 5, 5, 5, 5, 6, 6, 6, 6),
      Seq(6, 7, 7, 7, 7, 7, 7, 7, 8, 8, 8, 8, 8, 8, 9, 9),
      Seq(9, 9, 9, 10, 10, 10, 10, 10, 10, 10, 11, 11, 11, 11, 11, 11),
      Seq(12, 12, 12, 12, 12, 13, 13, 13, 13, 13, 13, 13, 14, 14, 14, 14),
      Seq(14, 14, 15, 15, 15, 15, 15, 16, 16, 16, 16, 16, 16, 16, 17, 17),
    ),
  ).map(_.flatten)
  private def reverse128(v: BigInt): BigInt={
    var out=BigInt(0);for(i<-0 until 128 if v.testBit(i))out|=BigInt(1)<<(127-i);out
  }
  private def decode(block: BigInt,count: Int,colorBits: Int,bitOffset: Int): (Int,Int,Vector[Int])={
    if(count<2 || count>18 || (count&1)!=0 || colorBits<0 || colorBits>=128)return(1,0,Vector.fill(18)(0))
    val q=quantTable(count/2-1)(colorBits)
    if(q<4)return(1,0,Vector.fill(18)(0))
    val need=needed(count,q)
    if(bitOffset+need>128)return(1,q,Vector.fill(18)(0))
    val bits=bitsPer(q);val trits=tritPer(q)!=0;val quints=quintPer(q)!=0
    val stream=reverse128(block);var off=bitOffset;var lc=0;var hc=0
    val res=Array.fill(count)(0);val tq=Array.fill(22)(0)
    def read(n: Int): Int={val v=((stream>>off)&((BigInt(1)<<n)-1)).toInt;off+=n;v}
    for(i<-0 until count){
      res(i)=read(bits)
      if(trits){
        val br=Seq(2,2,1,2,1)(lc);val sh=Seq(0,2,4,5,7)(lc);val nxt=Seq(1,2,3,4,0)(lc);val inc=lc==4
        tq(hc)|=read(br)<<sh;if(inc)hc+=1;lc=nxt
      }
      if(quints){
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
    val map=AstcColorUnquantTables.maps(q-4)
    val vals=(0 until count).map{i=>
      val v=if(trits)((tritAt(tq(i/5),i%5)<<bits)|res(i))
        else if(quints)((quintAt(tq(i/3),i%3)<<bits)|res(i)) else res(i)
      if(v>=map.size)throw new RuntimeException(s"q=$q i=$i v=$v")
      map(v)}
    (0,q,(vals++Vector.fill(18-count)(0)).toVector)
  }
  private class Driver(val d: AstcColorIntegerUnit) {
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    def run(block: BigInt,count: Int,colorBits: Int,bitOffset: Int,tag: Int):
        (Int,Int,Vector[Int])={
      val q=d.io.in.bits;zero(q);q.tag.poke(tag.U);q.block.poke(block.U)
      q.integerCount.poke(count.U);q.colorBits.poke(colorBits.U);q.bitOffset.poke(bitOffset.U)
      d.io.in.valid.poke(true.B);var n=0
      while(!d.io.in.ready.peek().litToBoolean && n<20){d.clock.step();n+=1}
      assert(d.io.in.ready.peek().litToBoolean);d.clock.step();d.io.in.valid.poke(false.B);zero(q)
      n=0;while(!d.io.out.valid.peek().litToBoolean && n<500){d.clock.step();n+=1}
      assert(n<500,s"color integer timeout count=$count bits=$colorBits")
      val o=d.io.out.bits;o.tag.expect(tag.U)
      val r=(o.status.peek().litValue.toInt,o.quant.peek().litValue.toInt,
        (0 until 18).map(i=>o.values(i).peek().litValue.toInt).toVector)
      val held=o.peek().litValue
      for(_<-0 until 2){d.clock.step();assert(o.peek().litValue==held)}
      d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)
      r
    }
  }
  behavior of "AstcColorIntegerUnit"
  it should "select every quantization level in the reference bit table" in {
    simulate(new AstcColorIntegerUnit){d=>val t=new Driver(d)
      var checks=0;var valid=0
      for(r<-1 to 9;bits<-0 until 128){
        val count=2*r;val exp=quantTable(r-1)(bits)
        val (status,quant,_)=t.run(BigInt(128,new scala.util.Random(bits+r)),count,bits,0,tag=(checks&0xFF))
        if(exp<4){assert(status==1,s"count=$count bits=$bits expected invalid got=$status q=$quant")}
        else{assert(status==0,s"count=$count bits=$bits expected quant=$exp");assert(quant==exp,s"count=$count bits=$bits got=$quant want=$exp");valid+=1}
        checks+=1
      }
      info(s"ASTC_COLOR_QUANT rows=9 bits=128 checks=$checks valid=$valid")
    }
  }
  it should "decode scrambled endpoint integers over random blocks" in {
    simulate(new AstcColorIntegerUnit){d=>val t=new Driver(d)
      val random=new scala.util.Random(0x434f4c52)
      var checked=0
      for(_<-0 until 400){
        val count=2*(1+random.nextInt(9))
        val bits=random.nextInt(128)
        val q=quantTable(count/2-1)(bits)
        val block=BigInt(128,random)
        val off=if(q<4)random.nextInt(128) else{val need=needed(count,q);if(128-need<0)-1 else random.nextInt(128-need+1)}
        if(off>=0){
          val want=decode(block,count,bits,off)
          val got=t.run(block,count,bits,off,tag=(checked&0xFF))
          assert(got._1==want._1,s"count=$count bits=$bits off=$off status")
          if(want._1==0){assert(got._2==want._2,s"count=$count bits=$bits off=$off quant");assert(got._3==want._3,s"count=$count bits=$bits off=$off values")}
          checked+=1
        }
      }
      info(s"ASTC_COLOR_INTEGER randomBlocks=$checked")
    }
  }
  it should "reject invalid counts and bit ranges" in {
    simulate(new AstcColorIntegerUnit){d=>val t=new Driver(d)
      assert(t.run(BigInt(1),0,60,17,1)._1==1)
      assert(t.run(BigInt(1),3,60,17,2)._1==1)
      assert(t.run(BigInt(1),20,60,17,3)._1==1)
      assert(t.run(BigInt(1),4,1,17,4)._1==1)
      assert(t.run(BigInt(1),4,120,127,5)._1==1)
      assert(t.run(BigInt(1),4,60,17,6)._1==0)
      info("ASTC_COLOR_INTEGER_INVALID count=true odd=true noQuant=true overrun=true healthyReuse=true")
    }
  }
  it should "keep the scrambled maps permutations of uniform color levels" in {
    for((q,row)<-levels.drop(4).zip(AstcColorUnquantTables.maps)){
      assert(row.size==q,s"q=$q size=${row.size}")
      assert(row.forall(v=>v>=0 && v<=255),s"q=$q range")
      assert(row.sorted==row.sorted.distinct && row.sorted.head==0 && row.sorted.last==255,s"q=$q endpoints")
    }
    info(s"ASTC_COLOR_MAPS levels=${AstcColorUnquantTables.maps.size} values=${AstcColorUnquantTables.maps.map(_.size).sum}")
  }
}
