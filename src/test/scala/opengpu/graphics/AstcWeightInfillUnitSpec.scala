package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class AstcWeightInfillUnitSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  private val scales=Map(4->342,5->256,6->205,7->171,8->146,9->128,10->114,11->102,12->93)
  private def reference(weights: Seq[Int],bw: Int,bh: Int,gx: Int,gy: Int,x: Int,y: Int): (Int,Int)={
    if(bw<4 || bw>12 || bh<4 || bh>12 || gx<1 || gx>bw || gy<1 || gy>bh || gx*gy>64 || x<0 || x>=bw || y<0 || y>=bh)return(1,0)
    val ds=scales(bw);val dt=scales(bh)
    val cs=ds*x;val ct=dt*y
    val gs=(cs*(gx-1)+32)>>6;val gt=(ct*(gy-1)+32)>>6
    val js=gs>>4;val fs=gs&15;val jt=gt>>4;val ft=gt&15
    def w(i: Int): Int=if(i<64)weights(i)else 0
    val v0=js+jt*gx
    val p00=w(v0);val p01=w(v0+1);val p10=w(v0+gx);val p11=w(v0+gx+1)
    val w11=(fs*ft+8)>>4;val w10=ft-w11;val w01=fs-w11;val w00=16-fs-ft+w11
    (0,(p00*w00+p01*w01+p10*w10+p11*w11+8)>>4)
  }
  behavior of "AstcWeightInfillUnit"
  it should "bilinearly infill every footprint and grid position" in {
    simulate(new AstcWeightInfillUnit){d=>zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val random=new scala.util.Random(0x494e4649)
      var tag=0;var checked=0
      for(bw<-4 to 12;bh<-4 to 12 if bw*bh<=64;gx<-Seq(1,2,3,bw).filter(_<=bw).distinct;gy<-Seq(1,2,3,bh).filter(_<=bh).distinct if gx*gy<=64){
        val weights=Seq.fill(64)(random.nextInt(65))
        for(x<-Seq(0,bw/2,bw-1).distinct;y<-Seq(0,bh/2,bh-1).distinct){
          val want=reference(weights,bw,bh,gx,gy,x,y)
          tag=(tag+1)&0xff;val q=d.io.in.bits;zero(q);q.tag.poke(tag.U);q.blockWidth.poke(bw.U);q.blockHeight.poke(bh.U)
          q.gridX.poke(gx.U);q.gridY.poke(gy.U);q.x.poke(x.U);q.y.poke(y.U)
          weights.zipWithIndex.foreach{case(v,i)=>q.weights(i).poke(v.U)}
          d.io.in.valid.poke(true.B);var n=0;while(!d.io.in.ready.peek().litToBoolean && n<20){d.clock.step();n+=1}
          assert(d.io.in.ready.peek().litToBoolean);d.clock.step();d.io.in.valid.poke(false.B);zero(q)
          n=0;while(!d.io.out.valid.peek().litToBoolean && n<30){d.clock.step();n+=1}
          assert(n<30);val o=d.io.out.bits;o.tag.expect(tag.U);o.status.expect(want._1.U);o.weight.expect(want._2.U)
          d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B);checked+=1
        }
      }
      // Invalid shapes are rejected before publishing a weight.
      for((bw,bh,gx,gy,x,y)<-Seq((3,4,1,1,0,0),(4,4,5,1,0,0),(4,4,0,1,0,0),(4,4,2,2,4,0),(12,12,12,12,0,0))){
        tag=(tag+1)&0xff;val q=d.io.in.bits;zero(q);q.tag.poke(tag.U);q.blockWidth.poke(bw.U);q.blockHeight.poke(bh.U)
        q.gridX.poke(gx.U);q.gridY.poke(gy.U);q.x.poke(x.U);q.y.poke(y.U)
        d.io.in.valid.poke(true.B);d.clock.step();d.io.in.valid.poke(false.B)
        var n=0;while(!d.io.out.valid.peek().litToBoolean && n<30){d.clock.step();n+=1}
        d.io.out.bits.status.expect(1.U);d.io.out.bits.weight.expect(0.U)
        d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)
      }
      info(s"ASTC_WEIGHT_INFILL checks=$checked footprints4to12=true dualPlanePending=true invalidShapesRejected=true")
    }
  }
}
