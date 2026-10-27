package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class MacroBankedDoubleStore(external: Boolean=false) extends DoubleBufferedTileAttachmentStore(GpuConfig(lanes=4,warps=2),
  screenWidth=64,screenHeight=64,storeCredits=8,useSramBlackBoxes=true,bankedSamples=true,externalArbitration=external) {
  val macroModelPresent=IO(Output(Bool()))
  private val resources=Module(new TileSramSimulationResources)
  macroModelPresent:=resources.io.present
}

class BankedDoubleBufferedTileAttachmentStoreSpec extends AnyFlatSpec {
  import TilePassTestData._
  import DoubleBufferedTileAttachmentStoreTestSupport._
  private def store(macros: Boolean=false,external: Boolean=false): DoubleBufferedTileAttachmentStore=
    if(macros)new MacroBankedDoubleStore(external) else new DoubleBufferedTileAttachmentStore(GpuConfig(lanes=4,warps=2),
      screenWidth=64,screenHeight=64,storeCredits=8,bankedSamples=true,externalArbitration=external)
  private case class Quad(index: Vector[Int],color: Vector[Long],depth: Vector[Int],ticket: Int,mask: Int=15)
  private case class Expected(ticket: Int,mask: Int)
  private def decode(w: Long,f: Int): Long = {
    if(f==0)w else {
      val b=Vector.tabulate(4)(i=>(w>>>(8*i))&255)
      val r=if(f==2||f==4)b(2)else b(0);val blue=if(f==2||f==4)b(0)else b(2)
      (r<<24)|(b(1)<<16)|(blue<<8)|(if(f>=3)255L else b(3))
    }
  }
  private def encode(w: Long,f: Int): Long = {
    if(f==0)w else {
      val r=(w>>>24)&255;val g=(w>>>16)&255;val b=(w>>>8)&255;val a=if(f>=3)255L else w&255
      val bytes=if(f==2||f==4)Vector(b,g,r,a)else Vector(r,g,b,a)
      bytes.zipWithIndex.map{case(v,i)=>v<<(8*i)}.reduce(_|_)
    }
  }
  private class QuadDriver(dut: DoubleBufferedTileAttachmentStore) extends Driver(dut,window=8,latency=12,maxWait=60000) {
    val colors=mutable.Map.empty[(Int,Int),Long];val depths=mutable.Map.empty[(Int,Int),Long]
    val expected=mutable.Queue.empty[Expected]
    val admissions=mutable.ArrayBuffer.empty[Int];val commits=mutable.ArrayBuffer.empty[(Int,Int)]
    val reportedBytes=Array.fill(3)(0L)
    var pass=Pass();var input=Option.empty[Quad];var heldQuad=Option.empty[BigInt]
    var quadReady=true;var allowCanceled=false;var ends=0
    override def begin(p: Pass): Unit = {
      assert(expected.isEmpty);super.begin(p);pass=p
      for(y <- 0 until p.height;x <- 0 until p.width;s <- 0 until p.samples) {
        val i=(y*16+x)*p.samples+s
        colors((p.id,i))=if(p.colorLoad==0)memory.getOrElse(p.address(x,y,s,false),0xdeadbeefL)else if(p.colorLoad==1)p.colorClear else 0L
        depths((p.id,i))=if(p.depthLoad==0)memory.getOrElse(p.address(x,y,s,true),0xdeadbeefL)else if(p.depthLoad==1)p.depthClear else 0L
      }
    }
    private def update(q: Quad): Unit = {
      for(p <- 0 until 4 if (q.mask&(1<<p))!=0) {
        val key=(pass.id,q.index(p));val old=decode(colors(key),pass.format);val src=q.color(p);val alpha=(src&255).toInt
        var result=0L
        for(c <- 0 until 4) {
          val s=((src>>>(c*8))&255).toInt;val d=((old>>>(c*8))&255).toInt
          val v=if(c==0)alpha+(d*(255-alpha)+127)/255 else(s*alpha+d*(255-alpha)+127)/255
          result|=v.toLong<<(8*c)
        }
        colors(key)=encode(result,pass.format)
        depths(key)=(math.min(255,(depths(key)>>>24).toInt+1).toLong<<24)|q.depth(p).toLong
      }
    }
    override def step(): Unit = {d.io.quadCompletion.get.ready.poke(quadReady.B);super.step()}
    override def beforeClock(): Unit = {
      reportedBytes(0)+=d.io.events.loadBytes.peek().litValue.toLong
      reportedBytes(1)+=d.io.events.storeBytes.peek().litValue.toLong
      reportedBytes(2)+=d.io.events.resolveBytes.peek().litValue.toLong
      val out=d.io.quadCompletion.get
      heldQuad.foreach(v=>{out.valid.expect(true.B);assert(out.bits.peek().litValue==v,"quad completion lost its selected owner")})
      if(out.valid.peek().litToBoolean) {
        assert(expected.nonEmpty);val e=expected.front
        out.bits.ticket.expect(e.ticket.U);out.bits.mask.expect(e.mask.U)
        if(!allowCanceled) {
          out.bits.canceled.expect(0.U);out.bits.testsPass.expect(e.mask.U)
          out.bits.colorWrites.expect(e.mask.U);out.bits.depthStencilWrites.expect(e.mask.U)
        }else {
          val c=out.bits.canceled.peek().litValue.toInt
          assert((c& ~e.mask)==0 && (c&out.bits.colorWrites.peek().litValue.toInt)==0 &&
            (c&out.bits.depthStencilWrites.peek().litValue.toInt)==0)
        }
        if(quadReady){expected.dequeue();heldQuad=None}else heldQuad=Some(out.bits.peek().litValue)
      }
      val fire=d.io.quad.get.valid.peek().litToBoolean && d.io.quad.get.ready.peek().litToBoolean
      d.io.acceptedSamples.get.expect((if(fire)Integer.bitCount(input.get.mask)else 0).U)
      if(fire){update(input.get);expected.enqueue(Expected(input.get.ticket,input.get.mask));admissions+=cycle}
      val n=d.io.committedSamples.get.peek().litValue.toInt
      if(n!=0)commits+=((cycle,n))
      if(d.io.pass.end.valid.peek().litToBoolean && d.io.pass.end.ready.peek().litToBoolean)ends+=1
    }
    def send(q: Quad): Unit = {
      input=Some(q);val in=d.io.quad.get;val b=in.bits
      b.ticket.poke(q.ticket.U);b.mask.poke(q.mask.U)
      for(p <- 0 until 4){b.index(p).poke(q.index(p).U);b.color(p).poke(q.color(p).U);b.depth(p).poke(q.depth(p).U)}
      val c=b.state;c.poke(0.U.asTypeOf(c));c.colorMask.poke(15.U)
      c.depthTestEnable.poke(true.B);c.depthFunc.poke(6.U);c.depthWriteEnable.poke(true.B)
      c.stencilTestEnable.poke(true.B);c.stencilFunc.poke(6.U);c.stencilReadMask.poke(255.U)
      c.stencilWriteMask.poke(255.U);c.stencilZPassOp.poke(3.U);c.blendEnable.poke(true.B)
      in.valid.poke(true.B);until(in.ready.peek().litToBoolean);step();in.valid.poke(false.B);input=None
    }
    def drainQuads(): Unit = {until(expected.isEmpty);step();d.io.localDrained.get.expect(true.B)}
    def check(p: Pass): Unit = {
      for(z <- Seq(false,true);y <- 0 until p.height;x <- 0 until p.width;s <- 0 until p.samples) {
        val key=(p.id,(y*16+x)*p.samples+s)
        val want=if(z)depths(key)else colors(key)
        assert(memory.getOrElse(p.address(x,y,s,z),0xdeadbeefL)==want,s"wrong stored owner id=${p.id} depth=$z x=$x y=$y sample=$s")
      }
    }
  }
  private def quad(n: Int,mode: Int,seed: Int=0,mask: Int=15): Quad = {
    val q=n%64;val x=q%8*2;val y=q/8*2;val sample=n/64%(1<<mode)
    Quad(Vector(y*16+x,y*16+x+1,(y+1)*16+x,(y+1)*16+x+1).map(p=>(p<<mode)|sample),
      Vector.tabulate(4)(p=>(0x23456789L+n*0x010305L+seed*0x030709L+p*0x050307L)&0xffffffffL),
      Vector.tabulate(4)(p=>(n*8191+seed*37+p+1)&0xffffff),(n+seed*13)&255,mask)
  }
  for((macros,fault,external) <- Seq((false,false,false),(true,false,false),(false,true,false),(false,false,true),(true,true,true)))it should s"merge cold next-slot quads while old stores wait and retain exact slot bytes macros=$macros fault=$fault external=$external" in {
    simulate(store(macros,external)) { dut=>
      val d=new QuadDriver(dut)
      val first=Pass(mode=2,width=16,height=16,originX=0,originY=0,pitch=512,id=7)
      val second=Pass(mode=1,width=8,height=8,originX=0,originY=0,pitch=512,tileLog2=3,id=8,
        colorBase=0x40000,depthBase=0x50000,colorLoad=0,depthLoad=0,format=PixelFormat.bgra)
      for(z <- Seq(false,true);y <- 0 until second.height;x <- 0 until second.width;s <- 0 until second.samples)
        d.memory(second.address(x,y,s,z))=if(z)0x7fabcdefL else(0x12345678L+y*257+x+s)&0xffffffffL
      d.holdWrites(0)=true;d.begin(first)
      for(n <- 0 until 128)d.send(quad(n,2))
      d.drainQuads()
      assert(d.admissions.size==128 && d.admissions.sliding(2).forall(v=>v(1)==v(0)+1))
      assert(d.commits.size==128 && d.commits.forall(_._2==4) && d.commits.sliding(2).forall(v=>v(1)._1==v(0)._1+1))
      d.end();d.until(d.writes(0)>0);d.begin(second)
      d.send(quad(0,1,seed=1));d.drainQuads()
      assert(d.reads(1)==4 && d.live.exists(_/9==0),"next-slot quad loads did not progress independently of old stores")
      d.quadReady=false;d.send(quad(0,1,seed=2));d.until(dut.io.quadCompletion.get.valid.peek().litToBoolean)
      if(fault) {
        d.faultSlot=Some(0);d.holdWrites(0)=false;d.until(d.faultSlot.isEmpty)
        for(_ <- 0 until 12)d.step()
      }
      d.quadReady=true;d.drainQuads();d.end()
      d.until(d.writeWords(1)==8 && !d.live.exists(_/9==1))
      if(!fault){assert(d.live.exists(_/9==0));dut.io.pass.completion.valid.expect(false.B)}
      d.holdWrites(0)=false
      val old=d.retire();val next=d.retire()
      assert(d.reportedBytes.toVector==Vector(old.loads+next.loads,old.stores+next.stores,old.resolves+next.resolves),
        "aggregate byte diagnostics lost or duplicated service across slot completion")
      assert(old.id==7 && old.status==(if(fault)TilePassStatus.memoryFault else TilePassStatus.success))
      assert(old.loads==0 && old.stores==d.writeWords(0)*4L && old.resolves==0)
      assert(next==Result(8,TilePassStatus.success,32,32,0));d.check(second)
      if(!fault){assert(old.stores==8192);d.check(first)}
      assert(d.live.isEmpty && d.pending.isEmpty);dut.io.occupied.expect(0.U)
      println(s"BANKED_DOUBLE macros=$macros fault=$fault external=$external residentSamples=512 consecutiveCommitCycles=128 nextLoads=32 nextStores=32")
    }
  }
  for(external <- Seq(false,true))it should s"hold end and pulsed cancellation until selected quad completions drain before slot reuse external=$external" in {
    simulate(store(external=external)) { dut=>
      val d=new QuadDriver(dut)
      for((cancel,round) <- Seq(false,true).zipWithIndex) {
        val first=Pass(mode=round,width=8,height=8,originX=0,originY=0,pitch=512,tileLog2=3,
          id=20+round*2,colorStore=false,depthStore=false)
        val next=first.copy(originX=8,id=first.id+1,colorStore=true,depthStore=true)
        d.begin(first);d.quadReady=false;d.send(quad(0,first.mode,seed=round))
        d.until(dut.io.quadCompletion.get.valid.peek().litToBoolean)
        val endCount=d.ends
        if(cancel){dut.io.pass.cancel.poke(true.B);d.step();dut.io.pass.cancel.poke(false.B)}
        else dut.io.pass.end.valid.poke(true.B)
        pokeDescriptor(dut.io.pass.begin.bits,next);dut.io.pass.begin.valid.poke(true.B)
        for(_ <- 0 until 8) {
          dut.io.localDrained.get.expect(false.B);dut.io.pass.begin.ready.expect(false.B)
          if(!cancel)dut.io.pass.end.ready.expect(false.B)
          d.step()
        }
        dut.io.pass.begin.valid.poke(false.B);d.quadReady=true
        if(!cancel){d.until(d.ends>endCount);dut.io.pass.end.valid.poke(false.B)}else d.drainQuads()
        d.begin(next);d.send(quad(0,next.mode,seed=round+3));d.drainQuads();d.end()
        assert(d.retire()==Result(first.id,if(cancel)TilePassStatus.cancelled else TilePassStatus.success,0,0,0))
        assert(d.retire()==Result(next.id,TilePassStatus.success,0,512L*next.samples,0));d.check(next)
      }
    }
  }
  it should "finish already produced quads when end arrives before the upstream producer drains" in {
    simulate(store(external=true)) { dut=>
      val d=new QuadDriver(dut);val p=Pass(width=8,height=8,originX=0,originY=0,pitch=128,tileLog2=3,id=30)
      d.begin(p);dut.io.producerDrained.poke(false.B);dut.io.pass.end.valid.poke(true.B)
      d.send(quad(0,0));d.drainQuads();dut.io.pass.end.ready.expect(false.B)
      dut.io.producerDrained.poke(true.B);val count=d.ends
      d.until(d.ends>count);dut.io.pass.end.valid.poke(false.B)
      assert(d.retire()==Result(30,TilePassStatus.success,0,512,0));d.check(p)
    }
  }
  it should "drain a canceled cold quad read before releasing its slot and reuse it after both owners retire" in {
    simulate(store(external=true)) { dut=>
      val d=new QuadDriver(dut);val p=Pass(width=8,height=8,originX=0,originY=0,pitch=128,tileLog2=3,id=40,colorLoad=0,depthLoad=0)
      d.begin(p);d.allowCanceled=true;d.send(quad(0,0));d.until(d.reads(0)>0)
      dut.io.pass.cancel.poke(true.B);d.step();dut.io.pass.cancel.poke(false.B)
      d.until(d.expected.isEmpty);val failed=d.retire()
      assert(failed.id==40 && failed.status==TilePassStatus.cancelled && failed.stores==0)
      d.allowCanceled=false
      val next=p.copy(id=41,colorLoad=1,depthLoad=1)
      d.begin(next);d.send(quad(0,0));d.drainQuads();d.end()
      assert(d.retire()==Result(41,TilePassStatus.success,0,512,0));d.check(next)
    }
  }
}
