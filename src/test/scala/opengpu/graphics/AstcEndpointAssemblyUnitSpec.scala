package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class AstcEndpointAssemblyUnitSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  private case class Ref(status: Int,offsets: Vector[Int],counts: Vector[Int],endpoints: Vector[Vector[Int]])
  private def reference(pc: Int,formats: Seq[Int],values: Seq[Int]): Ref={
    val counts=(0 until 4).map(i=>if(i<pc)2*((formats(i)>>2)+1) else 0)
    val total=counts.sum
    if(pc<1 || pc>4 || total>18)return Ref(1,Vector.fill(4)(0),Vector.fill(4)(0),Vector.fill(4)(Vector.fill(8)(0)))
    var off=0;val offsets=(0 until 4).map{i=>if(i<pc){val o=off;off+=counts(i);o}else 0}
    val eps=(0 until 4).map{i=>if(i<pc)(0 until 8).map{j=>if(j<counts(i))values(offsets(i)+j) else 0}.toVector else Vector.fill(8)(0)}.toVector
    Ref(0,offsets.toVector,counts.toVector,eps)
  }
  private class Driver(val d: AstcEndpointAssemblyUnit) {
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    def run(pc: Int,formats: Seq[Int],values: Seq[Int],tag: Int): Ref={
      val q=d.io.in.bits;zero(q);q.tag.poke(tag.U);q.partitions.poke(pc.U)
      formats.zipWithIndex.foreach{case(f,i)=>q.formats(i).poke(f.U)}
      values.zipWithIndex.foreach{case(v,i)=>q.values(i).poke(v.U)}
      d.io.in.valid.poke(true.B);var n=0
      while(!d.io.in.ready.peek().litToBoolean && n<20){d.clock.step();n+=1}
      assert(d.io.in.ready.peek().litToBoolean);d.clock.step();d.io.in.valid.poke(false.B);zero(q)
      n=0;while(!d.io.out.valid.peek().litToBoolean && n<50){d.clock.step();n+=1}
      assert(n<50,s"assembly timeout pc=$pc")
      val o=d.io.out.bits;o.tag.expect(tag.U)
      val r=Ref(o.status.peek().litValue.toInt,
        (0 until 4).map(i=>o.offsets(i).peek().litValue.toInt).toVector,
        (0 until 4).map(i=>o.counts(i).peek().litValue.toInt).toVector,
        (0 until 4).map(i=>(0 until 8).map(j=>o.endpoints(i)(j).peek().litValue.toInt).toVector).toVector)
      val held=o.peek().litValue
      for(_<-0 until 2){d.clock.step();assert(o.peek().litValue==held)}
      d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)
      r
    }
  }
  behavior of "AstcEndpointAssemblyUnit"
  it should "route endpoint values into per-partition slots" in {
    simulate(new AstcEndpointAssemblyUnit){d=>val t=new Driver(d)
      val random=new scala.util.Random(0x4153454d);var tag=0;var checks=0
      def one(pc: Int,formats: Seq[Int]): Unit={
        val values=Seq.fill(18)(random.nextInt(256))
        val want=reference(pc,formats,values);val got=t.run(pc,formats,values,tag&0xFF)
        if(want.status==0)assert(got==want,s"pc=$pc formats=$formats got=$got want=$want")
        else assert(got.status==1,s"pc=$pc formats=$formats expected invalid got=$got")
        tag+=1;checks+=1
      }
      for(f0<-0 until 16;f1<-0 until 16)one(2,Seq(f0,f1))
      for(f0<-0 until 16;f1<-0 until 16;f2<-0 until 16)one(3,Seq(f0,f1,f2))
      for(_<-0 until 2000)one(4,Seq.fill(4)(random.nextInt(16)))
      info(s"ASTC_ENDPOINT_ASSEMBLY checks=$checks pc2=256 pc3=4096 pc4=2000")
    }
  }
  it should "reject partition counts outside one to four and oversized sequences" in {
    simulate(new AstcEndpointAssemblyUnit){d=>val t=new Driver(d)
      assert(t.run(0,Seq.fill(4)(15),Seq.fill(18)(7),1).status==1)
      assert(t.run(5,Seq.fill(4)(15),Seq.fill(18)(7),2).status==1)
      assert(t.run(4,Seq.fill(4)(15),Seq.fill(18)(7),3).status==1)
      assert(t.run(4,Seq.fill(4)(7),Seq.fill(18)(7),4).status==0)
      assert(t.run(4,Seq.fill(4)(3),Seq.fill(18)(7),5).status==0)
      info("ASTC_ENDPOINT_ASSEMBLY_INVALID counts=true total=true healthyReuse=true")
    }
  }
}
