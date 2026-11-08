package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class PrimitiveBoundingBoxWindowSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  private def bits(v: Float): BigInt=BigInt(java.lang.Float.floatToRawIntBits(v)&0xffffffffL)
  private def fma(a: Float,b: Float,c: Float): Float=java.lang.Math.fma(a,b,c)
  private case class Rect(xmin: Int,ymin: Int,xmax: Int,ymax: Int,exact: Boolean,empty: Boolean=false)
  private def reference(min: Seq[Float],max: Seq[Float],vpX: Int,vpY: Int,vpW: Int,vpH: Int,
    kind: Int,size: Float): Rect={
    if(!(max(3)>0f))return Rect(0,0,0,0,true,true)
    val halfW=vpW.toFloat*0.5f;val halfH=vpH.toFloat*0.5f
    val originX=vpX.toFloat+halfW;val originY=vpY.toFloat+halfH
    val halfSize=if(kind<2)size*0.5f else 0f
    var minPx=Float.PositiveInfinity;var maxPx=Float.NegativeInfinity
    var minPy=Float.PositiveInfinity;var maxPy=Float.NegativeInfinity
    var inexact=false
    for(v<-0 until 16){
      val x=if((v&1)!=0)max(0)else min(0);val y=if((v&2)!=0)max(1)else min(1)
      val w=if((v&8)!=0)max(3)else min(3)
      if(!(w>0f))inexact=true
      else{
        val px=fma(x/w,halfW,originX);val py=fma(y/w,halfH,originY)
        if(px.isInfinite || py.isInfinite)inexact=true
        else{minPx=math.min(minPx,px);maxPx=math.max(maxPx,px);minPy=math.min(minPy,py);maxPy=math.max(maxPy,py)}
      }
    }
    val (loX,hiX,loY,hiY)=if(inexact){
      (vpX.toFloat-halfSize,originX+halfW+halfSize,vpY.toFloat-halfSize,originY+halfH+halfSize)
    }else(minPx-halfSize,maxPx+halfSize,minPy-halfSize,maxPy+halfSize)
    Rect(math.floor(loX.toDouble).toInt,math.floor(loY.toDouble).toInt,
      math.ceil(hiX.toDouble).toInt,math.ceil(hiY.toDouble).toInt,!inexact)
  }
  private class Driver(val d: PrimitiveBoundingBoxWindow) {
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    def run(min: Seq[Float],max: Seq[Float],vpX: Int,vpY: Int,vpW: Int,vpH: Int,kind: Int,size: Float,
      validBox: Boolean=true,status: Int=0,tag: Int=5): Rect={
      val q=d.io.in.bits;zero(q);q.tag.poke(tag.U);q.validBox.poke(validBox.B);q.kind.poke(kind.U)
      q.size.poke(bits(size).U);q.viewport.version.poke(1.U);q.viewport.x.poke(vpX.S);q.viewport.y.poke(vpY.S)
      q.viewport.extentX.poke(vpW.U);q.viewport.extentY.poke(vpH.U)
      q.viewport.nearDepth.poke(bits(0f).U);q.viewport.farDepth.poke(bits(1f).U)
      q.box.version.poke(1.U)
      for(i<-0 until 4){q.box.min(i).poke(bits(min(i)).U);q.box.max(i).poke(bits(max(i)).U)}
      d.io.in.valid.poke(true.B);var n=0;while(!d.io.in.ready.peek().litToBoolean && n<20){d.clock.step();n+=1}
      assert(d.io.in.ready.peek().litToBoolean);d.clock.step();d.io.in.valid.poke(false.B);zero(q)
      n=0;while(!d.io.out.valid.peek().litToBoolean && n<20000){d.clock.step();n+=1}
      assert(n<20000,s"bounding-box window timeout tag=$tag")
      d.io.out.bits.tag.expect(tag.U);d.io.out.bits.status.expect(status.U)
      val r=Rect(d.io.out.bits.xmin.peek().litValue.toInt,d.io.out.bits.ymin.peek().litValue.toInt,
        d.io.out.bits.xmax.peek().litValue.toInt,d.io.out.bits.ymax.peek().litValue.toInt,
        d.io.out.bits.exact.peek().litToBoolean,d.io.out.bits.empty.peek().litToBoolean)
      val held=d.io.out.bits.peek().litValue
      for(_<-0 until 3){d.clock.step();assert(d.io.out.bits.peek().litValue==held)}
      d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)
      r
    }
  }
  behavior of "PrimitiveBoundingBoxWindow"
  it should "project the box and expand point line and triangle window bounds" in {
    simulate(new PrimitiveBoundingBoxWindow) { d=>val t=new Driver(d)
      val fullMin=Seq(-1f,-1f,-1f,1f);val fullMax=Seq(1f,1f,1f,1f)
      assert(t.run(fullMin,fullMax,0,0,100,50,2,0f)==reference(fullMin,fullMax,0,0,100,50,2,0f))
      assert(t.run(fullMin,fullMax,10,20,64,32,2,0f)==reference(fullMin,fullMax,10,20,64,32,2,0f))
      assert(t.run(fullMin,fullMax,10,20,64,32,0,4f)==reference(fullMin,fullMax,10,20,64,32,0,4f))
      assert(t.run(fullMin,fullMax,10,20,64,32,1,3f)==reference(fullMin,fullMax,10,20,64,32,1,3f))
      assert(t.run(fullMin,fullMax,-10,-5,100,50,2,0f)==reference(fullMin,fullMax,-10,-5,100,50,2,0f))
      val asymMin=Seq(-0.5f,-0.25f,-1f,1f);val asymMax=Seq(0.5f,0.75f,1f,1f)
      assert(t.run(asymMin,asymMax,0,0,100,100,2,0f)==reference(asymMin,asymMax,0,0,100,100,2,0f))
      val wideMin=Seq(-1f,-1f,-1f,1f);val wideMax=Seq(0.5f,1f,1f,2f)
      assert(t.run(wideMin,wideMax,0,0,100,100,2,0f)==reference(wideMin,wideMax,0,0,100,100,2,0f))
      info("BOUNDING_BOX_WINDOW triangle=true point4=true line3=true negativeOrigin=true asymmetric=true varyingW=true")
    }
  }
  it should "fall back to the viewport when W is not positive and reject invalid state" in {
    simulate(new PrimitiveBoundingBoxWindow) { d=>val t=new Driver(d)
      val zeroWMin=Seq(-1f,-1f,-1f,0f);val zeroWMax=Seq(1f,1f,1f,1f)
      val fallback=t.run(zeroWMin,zeroWMax,0,0,100,50,2,0f)
      assert(!fallback.exact && fallback==reference(zeroWMin,zeroWMax,0,0,100,50,2,0f))
      val bad=t.run(Seq(-1f,-1f,-1f,1f),Seq(1f,1f,1f,1f),0,0,100,50,2,0f,validBox=false,status=1,tag=9)
      assert(bad==Rect(0,0,0,0,false))
      val nanBox=t.run(Seq(Float.NaN,-1f,-1f,1f),Seq(1f,1f,1f,1f),0,0,100,50,2,0f,status=1,tag=10)
      assert(nanBox==Rect(0,0,0,0,false))
      val ok=t.run(Seq(-1f,-1f,-1f,1f),Seq(1f,1f,1f,1f),0,0,100,50,2,0f,tag=11)
      assert(ok.exact && ok==Rect(0,0,100,50,true))
      val emptyBox=t.run(Seq(-1f,-1f,-1f,-1f),Seq(1f,1f,1f,0f),0,0,100,50,2,0f,tag=12)
      assert(emptyBox.empty && emptyBox.exact && emptyBox.xmin==0 && emptyBox.xmax==0)
      val clipped=t.run(Seq(-1f,-1f,-1f,0f),Seq(1f,1f,1f,1f),0,0,100,50,2,0f,tag=13)
      assert(!clipped.empty && !clipped.exact)
      info("BOUNDING_BOX_WINDOW_FALLBACK nonPositiveW=true exactFalse=true emptyVolume=true invalidState=true healthyReuse=true")
    }
  }
}
