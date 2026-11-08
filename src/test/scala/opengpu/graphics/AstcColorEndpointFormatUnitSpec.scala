package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class AstcColorEndpointFormatUnitSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  private def read(block: BigInt,n: Int,pos: Int): Int=((block>>pos)&((BigInt(1)<<n)-1)).toInt
  private case class Ref(status: Int,index: Int,formats: Vector[Int],matched: Boolean,
    count: Int,bits: Int,offset: Int,plane2: Int,below: Int)
  private def reference(block: BigInt,pc: Int,wbits: Int,dual: Boolean): Ref={
    if(pc<1 || pc>4 || wbits<0 || wbits>96)return Ref(1,0,Vector.fill(4)(0),false,0,0,0,0,0)
    var below=128-wbits;var highSize=0;var idx=0;var matched=false
    var formats=Array.fill(4)(0)
    if(pc==1)formats(0)=read(block,4,13)
    else{
      idx=read(block,10,13);highSize=3*pc-4;below-=highSize
      if(below<0)return Ref(1,0,Vector.fill(4)(0),false,0,0,0,0,0)
      val et=read(block,6,23)|(read(block,highSize,below)<<6)
      val base=et&3
      if(base==0){
        formats=Array.fill(4)((et>>2)&0xF);matched=true;below+=highSize;highSize=0
      }else{
        var bp=2
        for(i<-0 until pc){formats(i)=((((et>>bp)&1)+(base-1))<<2);bp+=1}
        for(i<-0 until pc){formats(i)|=(et>>bp)&3;bp+=2}
      }
    }
    val count=(0 until pc).map(i=>2*((formats(i)>>2)+1)).sum
    val bits=math.max(0,(if(pc==1)111 else 99)-wbits-highSize-(if(dual)2 else 0))
    val offset=if(pc==1)17 else 29
    val plane2=if(dual && below>=2)read(block,2,below-2) else 0
    val ok=count<=18 && (!dual || below>=2)
    Ref(if(ok)0 else 1,idx,formats.toVector,matched,count,bits,offset,plane2,below)
  }
  private class Driver(val d: AstcColorEndpointFormatUnit) {
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    def run(block: BigInt,pc: Int,wbits: Int,dual: Boolean,tag: Int): Ref={
      val q=d.io.in.bits;zero(q);q.tag.poke(tag.U);q.block.poke(block.U)
      q.partitions.poke(pc.U);q.weightBits.poke(wbits.U);q.dualPlane.poke(dual.B)
      d.io.in.valid.poke(true.B);var n=0
      while(!d.io.in.ready.peek().litToBoolean && n<20){d.clock.step();n+=1}
      assert(d.io.in.ready.peek().litToBoolean);d.clock.step();d.io.in.valid.poke(false.B);zero(q)
      n=0;while(!d.io.out.valid.peek().litToBoolean && n<50){d.clock.step();n+=1}
      assert(n<50,s"format timeout pc=$pc wbits=$wbits")
      val o=d.io.out.bits;o.tag.expect(tag.U)
      val r=Ref(o.status.peek().litValue.toInt,o.partitionIndex.peek().litValue.toInt,
        (0 until 4).map(i=>o.formats(i).peek().litValue.toInt).toVector,
        o.matched.peek().litToBoolean,o.integerCount.peek().litValue.toInt,
        o.colorBits.peek().litValue.toInt,o.colorOffset.peek().litValue.toInt,
        o.plane2Component.peek().litValue.toInt,o.belowWeightsPos.peek().litValue.toInt)
      val held=o.peek().litValue
      for(_<-0 until 2){d.clock.step();assert(o.peek().litValue==held)}
      d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)
      r
    }
  }
  behavior of "AstcColorEndpointFormatUnit"
  it should "derive single and multi partition endpoint formats" in {
    simulate(new AstcColorEndpointFormatUnit){d=>val t=new Driver(d)
      val random=new scala.util.Random(0x43454d46)
      var checks=0;var valid=0
      for(_<-0 until 500){
        val pc=1+random.nextInt(4)
        val wbits=Seq(24,32,45,61,80,96)(random.nextInt(6))
        val dual=random.nextBoolean() && pc<4
        val block=BigInt(128,random)
        val want=reference(block,pc,wbits,dual);val tag=checks&0xFF
        val got=t.run(block,pc,wbits,dual,tag)
        if(want.status==0)assert(got==want,s"pc=$pc wbits=$wbits dual=$dual block=$block got=$got want=$want")
        else assert(got.status==1,s"pc=$pc wbits=$wbits dual=$dual expected invalid got=$got")
        if(want.status==0)valid+=1
        checks+=1
      }
      info(s"ASTC_ENDPOINT_FORMAT randomBlocks=$checks valid=$valid")
    }
  }
  it should "decode shared and non-shared encoded types and reject bad sizing" in {
    simulate(new AstcColorEndpointFormatUnit){d=>val t=new Driver(d)
      var seed=BigInt("a5a5a5a5",16)
      def blockWith(et: BigInt,pc: Int,wbits: Int): BigInt={
        val low=et&0x3F;val high=et>>6;val hs=3*pc-4;val below=128-wbits-hs
        seed=seed*1103515245+12345
        (seed&((BigInt(1)<<128)-1)) | (low<<23) | (high<<below)
      }
      for(pc<-2 to 4;base<-1 to 3;dual<-Seq(false,true)){
        if(!(dual && pc==4)){
          val shared=base==0
          val et=if(shared)(BigInt(8+pc)&0xF)<<2 else{
            var v=BigInt(base);var bp=2
            for(i<-0 until pc){v|=BigInt((i+base)&1)<<bp;bp+=1}
            for(i<-0 until pc){v|=BigInt((3-i)&3)<<bp;bp+=2}
            v}
          val block=blockWith(et,pc,40);val want=reference(block,pc,40,dual)
          val got=t.run(block,pc,40,dual,pc*10+base+(if(dual)1 else 0))
          if(want.status==0)assert(got==want,s"pc=$pc base=$base dual=$dual got=$got want=$want")
          else assert(got.status==1,s"pc=$pc base=$base dual=$dual expected invalid got=$got")
        }
      }
      val block=BigInt(128,new scala.util.Random(7))
      assert(t.run(block,1,97,false,90).status==1)
      assert(t.run(block,5,40,false,91).status==1)
      info("ASTC_ENDPOINT_FORMAT_SHARED types=2to4 shared=true nonshared=true sizingRejected=true")
    }
  }
}
