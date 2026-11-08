package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class AstcLdrEndpointUnitSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  private def transfer(a: Int,b: Int): (Int,Int)={
    val b2=(b>>1)|(a&0x80)
    var a2=(a>>1)&0x3F
    if((a2&0x20)!=0)a2-=0x40
    (a2,b2)
  }
  private def blue(r: Int,g: Int,b: Int,a: Int): Vector[Int]=Vector((r+b)>>1,(g+b)>>1,b,a)
  private def clamp8(v: Int): Int=math.max(0,math.min(255,v))
  private def reference(cem: Int,v: Seq[Int],w: Int): (Int,Seq[Int])={
    val legal=Set(0,1,4,5,6,8,9,10,12,13)
    if(!legal(cem))return(1,Vector(0,0,0,0))
    val (t0a,t0b)=transfer(v(0),v(1))
    val (t1a,t1b)=transfer(v(2),v(3))
    val (t2a,t2b)=transfer(v(4),v(5))
    val (t3a,t3b)=transfer(v(6),v(7))
    val s0=v(0)+v(2)+v(4);val s1=v(1)+v(3)+v(5)
    var (a,b)=cem match{
      case 0=>(Vector(v(0),v(0),v(0),255),Vector(v(1),v(1),v(1),255))
      case 1=>{val l0=(v(0)>>2)|(v(1)&0xC0);val l1=math.min(255,l0+(v(1)&0x3F));(Vector(l0,l0,l0,255),Vector(l1,l1,l1,255))}
      case 4=>(Vector(v(0),v(0),v(0),v(2)),Vector(v(1),v(1),v(1),v(3)))
      case 5=>(Vector(v(0),v(0),v(0),v(2)),Vector(v(0)+t0a,v(0)+t0a,v(0)+t0a,v(2)+t1a))
      case 6=>(Vector((v(0)*v(3))>>8,(v(1)*v(3))>>8,(v(2)*v(3))>>8,255),Vector(v(0),v(1),v(2),255))
      case 8=>if(s1>=s0)(Vector(v(0),v(2),v(4),255),Vector(v(1),v(3),v(5),255))
        else(blue(v(1),v(3),v(5),255),blue(v(0),v(2),v(4),255))
      case 9=>if(t0a+t1a+t2a>=0)(Vector(v(0),v(2),v(4),255),Vector(v(0)+t0a,v(2)+t1a,v(4)+t2a,255))
        else(blue(v(0)+t0a,v(2)+t1a,v(4)+t2a,255),blue(v(0),v(2),v(4),255))
      case 10=>(Vector((v(0)*v(3))>>8,(v(1)*v(3))>>8,(v(2)*v(3))>>8,v(4)),Vector(v(0),v(1),v(2),v(5)))
      case 12=>if(s1>=s0)(Vector(v(0),v(2),v(4),v(6)),Vector(v(1),v(3),v(5),v(7)))
        else(blue(v(1),v(3),v(5),v(7)),blue(v(0),v(2),v(4),v(6)))
      case _=>if(t0a+t1a+t2a>=0)(Vector(v(0),v(2),v(4),v(6)),Vector(v(0)+t0a,v(2)+t1a,v(4)+t2a,v(6)+t3a))
        else(blue(v(0)+t0a,v(2)+t1a,v(4)+t2a,v(6)+t3a),blue(v(0),v(2),v(4),v(6)))
    }
    if(cem==5 || cem==9 || cem==13){a=a.map(clamp8);b=b.map(clamp8)}
    val out=(0 until 4).map{c=>val ca=(a(c)<<8)|a(c);val cb=(b(c)<<8)|b(c);((ca*(64-w)+cb*w+32)>>6)&0xffff}
    (0,out)
  }
  behavior of "AstcLdrEndpointUnit"
  it should "decode every LDR endpoint mode and interpolate by weight" in {
    simulate(new AstcLdrEndpointUnit){d=>zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val random=new scala.util.Random(0x454e4450);var tag=0;var checked=0
      for(cem<-Seq(0,1,4,5,6,8,9,10,12,13)){
        for(_<-0 until 12){
          val v=Seq.fill(8)(random.nextInt(256));val w=Seq(0,1,7,32,63,64)(random.nextInt(6))
          val want=reference(cem,v,w)
          tag=(tag+1)&0xff;val q=d.io.in.bits;zero(q);q.tag.poke(tag.U);q.cem.poke(cem.U);q.weight.poke(w.U)
          v.zipWithIndex.foreach{case(x,i)=>q.values(i).poke(x.U)}
          d.io.in.valid.poke(true.B);var n=0;while(!d.io.in.ready.peek().litToBoolean && n<20){d.clock.step();n+=1}
          assert(d.io.in.ready.peek().litToBoolean);d.clock.step();d.io.in.valid.poke(false.B);zero(q)
          n=0;while(!d.io.out.valid.peek().litToBoolean && n<30){d.clock.step();n+=1}
          assert(n<30);val o=d.io.out.bits;o.tag.expect(tag.U);o.status.expect(want._1.U)
          for(c<-0 until 4){val got=o.rgba(c).peek().litValue;assert(got==BigInt(want._2(c)),s"cem=$cem v=$v w=$w c=$c got=$got want=${want._2(c)}")}
          d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B);checked+=1
        }
      }
      for(cem<-Seq(2,3,7,11,14,15)){
        tag=(tag+1)&0xff;val q=d.io.in.bits;zero(q);q.tag.poke(tag.U);q.cem.poke(cem.U);q.weight.poke(32.U)
        d.io.in.valid.poke(true.B);d.clock.step();d.io.in.valid.poke(false.B)
        var n=0;while(!d.io.out.valid.peek().litToBoolean && n<30){d.clock.step();n+=1}
        d.io.out.bits.status.expect(1.U);d.io.out.bits.rgba.foreach(_.expect(0.U))
        d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)
      }
      info(s"ASTC_ENDPOINTS ldrModes10=true randomChecks=$checked hdrRejected=true unorm16Interpolation=true")
    }
  }
  it should "apply the second weight to the selected dual-plane component" in {
    simulate(new AstcLdrEndpointUnit){d=>zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val random=new scala.util.Random(0x4455414c);var tag=0;var checked=0
      for(cem<-Seq(0,1,4,5,6,8,9,10,12,13);_<-0 until 8){
        val v=Seq.fill(8)(random.nextInt(256));val w0=random.nextInt(65);val w1=random.nextInt(65)
        val comp=random.nextInt(4)
        val want0=reference(cem,v,w0)._2;val want1=reference(cem,v,w1)._2
        val want=(0 until 4).map(c=>if(c==comp)want1(c) else want0(c))
        tag=(tag+1)&0xff;val q=d.io.in.bits;zero(q);q.tag.poke(tag.U);q.cem.poke(cem.U)
        q.weight.poke(w0.U);q.weight2.poke(w1.U);q.dualPlane.poke(true.B);q.plane2Component.poke(comp.U)
        v.zipWithIndex.foreach{case(x,i)=>q.values(i).poke(x.U)}
        d.io.in.valid.poke(true.B);var n=0;while(!d.io.in.ready.peek().litToBoolean && n<20){d.clock.step();n+=1}
        assert(d.io.in.ready.peek().litToBoolean);d.clock.step();d.io.in.valid.poke(false.B);zero(q)
        n=0;while(!d.io.out.valid.peek().litToBoolean && n<30){d.clock.step();n+=1}
        assert(n<30);val o=d.io.out.bits;o.status.expect(0.U)
        for(c<-0 until 4){val got=o.rgba(c).peek().litValue;assert(got==BigInt(want(c)),s"cem=$cem c=$c comp=$comp got=$got want=${want(c)}")}
        d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B);checked+=1
      }
      info(s"ASTC_ENDPOINT_DUAL checks=$checked components=4 weights=true")
    }
  }
}
