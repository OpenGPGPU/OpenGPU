package opengpu.graphics

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class RasterShaderAttributeOwnershipSpec extends AnyFlatSpec {
  private def zero(data: Data): Unit = data match {
    case r: Record=>r.elements.values.foreach(zero)
    case v: Vec[_]=>v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case _=>()
  }
  for(quad<-Seq(false,true)) {
    it should s"own draw attributes and completion through queued arithmetic and held outputs quad=$quad" in {
      val config=GraphicsConfig(screenWidth=32,screenHeight=32)
      val pixels=(3 until 30).flatMap(y=>(3 until 30).collect{case x if x+y<=32=>(x,y)}).toSet
      val expected=if(quad)pixels.map{case(x,y)=>(x& ~1,y& ~1)}.toVector.sortBy{case(x,y)=>(y,x)}
        else pixels.toVector.sortBy{case(x,y)=>(y,x)}
      simulate(new RasterShader(config,quad)) { d=>
        zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
        Seq(d.io.draw.bits.v0,d.io.draw.bits.v1,d.io.draw.bits.v2).zip(Seq((2,2),(30,2),(2,30))).foreach{case(p,(x,y))=>
          p.x.poke(config.toFixed(x).S);p.y.poke(config.toFixed(y).S)
        }
        def attrs(r: Int,g: Int,b: Int,a: Int,z: Int,mode: Int): Unit={
          for(v<-0 until 3){d.io.colors(v).r.poke(r.U);d.io.colors(v).g.poke(g.U);d.io.colors(v).b.poke(b.U)
            d.io.alphas(v).poke(a.U);d.io.depths(v).poke(z.S)}
          d.io.sampleMode.poke(mode.U)
        }
        var total=0
        for(draw<-0 until 2) {
          val (r,g,b,a,z)=if(draw==0)(200,100,50,128,1234)else(90,80,70,64,4321)
          attrs(r,g,b,a,z,0)
          d.io.draw.ready.expect(true.B);d.io.done.expect(true.B);d.io.draw.valid.poke(true.B)
          d.clock.step();d.io.draw.valid.poke(false.B)
          // Every live attribute and sample mode changes immediately after
          // admission. A pending next draw must wait for the final output.
          attrs(9,8,7,3,9999,2)
          d.io.draw.valid.poke((draw==0).B)
          var received=0;var cycle=0;var held: Option[(BigInt,BigInt)]=None
          val seen=mutable.ArrayBuffer.empty[(Int,Int)];val covered=mutable.Set.empty[(Int,Int)];val cycles=mutable.ArrayBuffer.empty[Int]
          while(received<expected.size && cycle<6000) {
            val ready=if(draw==0)cycle>=300 && cycle%7>=2 else true
            d.io.pixel.ready.poke((!quad && ready).B);d.io.quad.ready.poke((quad && ready).B)
            d.io.done.expect(false.B);d.io.draw.ready.expect(false.B)
            val valid=if(quad)d.io.quad.valid.peek().litToBoolean else d.io.pixel.valid.peek().litToBoolean
            val value=if(quad)d.io.quad.bits.peek().litValue else d.io.pixel.bits.peek().litValue
            val depths=if(quad)d.io.quadDepths.peek().litValue else d.io.depthSamples.peek().litValue
            held.foreach{case(v,s)=>assert(valid && value==v && depths==s)}
            if(valid) {
              val lanes=if(quad)(0 until 4).map(d.io.quad.bits.lanes(_))else Seq(d.io.pixel.bits)
              val origin=(lanes.head.x.peek().litValue.toInt,lanes.head.y.peek().litValue.toInt)
              assert(origin==expected(received),s"draw$draw beat$received got=$origin expected=${expected(received)}")
              for((p,l)<-lanes.zipWithIndex) {
                val xy=(p.x.peek().litValue.toInt,p.y.peek().litValue.toInt)
                if(quad)assert(xy==(origin._1+(l&1),origin._2+(l>>1)))
                p.covered.expect(pixels(xy).B);p.coverageMask.expect((if(pixels(xy))1 else 0).U)
                p.color.r.expect(r.U);p.color.g.expect(g.U);p.color.b.expect(b.U);p.alpha.expect(a.U);p.depth.expect(z.S)
                for(s<-0 until 4){
                  val sample=if(quad)d.io.quadDepths(l)(s)else d.io.depthSamples(s)
                  sample.expect((if(s==0)z else 0).U)
                }
                if(ready && pixels(xy))covered+=xy
              }
              if(ready){seen+=origin;cycles+=cycle;received+=1;total+=1;held=None}else held=Some((value,depths))
            }
            d.clock.step();cycle+=1
          }
          assert(received==expected.size && seen.toVector==expected && covered.toSet==pixels)
          d.io.draw.valid.poke(false.B);d.io.done.expect(true.B);d.io.draw.ready.expect(true.B)
          if(draw==1 && quad)for(i<-1 until seen.size if seen(i)._2==seen(i-1)._2 && seen(i)._1==seen(i-1)._1+2)
            assert(cycles(i)==cycles(i-1)+1,"adjacent visible quads lost II1")
        }
        d.io.pixel.ready.poke(true.B);d.io.quad.ready.poke(true.B)
        for(_<-0 until 8){d.io.done.expect(true.B);d.io.pixel.valid.expect(false.B);d.io.quad.valid.expect(false.B);d.clock.step()}
        info(s"RASTER_ATTRIBUTE_OWNER quad=$quad draws=2 packets=$total exactCoverage=true attributesCaptured=true sampleModeCaptured=true heldPayloadDepthsStable=true nextDrawBlocked=true doneAfterLastOutput=true healthyDrawReuse=true")
      }
    }
  }
}
