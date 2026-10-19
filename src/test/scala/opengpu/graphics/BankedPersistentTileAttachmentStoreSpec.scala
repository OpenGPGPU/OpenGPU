package opengpu.graphics

import chisel3._
import chisel3.util.HasBlackBoxInline
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable
import java.nio.file.{Files,Paths}

private class BankedPersistentMacroResources extends BlackBox with HasBlackBoxInline {
  val io=IO(new Bundle {val present=Output(Bool())})
  setInline("BankedPersistentMacroResources.sv",
    "module BankedPersistentMacroResources(output present); assign present=1'b1; endmodule\n"+
      Files.readString(Paths.get("depends/asap7_sram_0p0/generated/verilog/srambank_64x4x32_6t122.v")))
}
private class MacroBankedPersistentStore extends PersistentTileAttachmentStore(
  useSramBlackBoxes=true,storeCredits=8,bankedSamples=true) {
  val macroModelPresent=IO(Output(Bool()))
  private val resources=Module(new BankedPersistentMacroResources)
  macroModelPresent:=resources.io.present
}

class BankedPersistentTileAttachmentStoreSpec extends AnyFlatSpec {
  import TilePassTestData._
  import PersistentTileAttachmentStoreTestSupport._
  private case class Quad(index: Vector[Int],color: Vector[Long],depth: Vector[Int],ticket: Int,
    mask: Int=15,func: Int=6,colorMask: Int=15,stencilFunc: Int=6,stencilRef: Int=0,stencilReadMask: Int=255)
  private case class Completion(ticket: Int,mask: Int,passed: Int,color: Int,depth: Int)
  private def compare(a: Int,b: Int,func: Int): Boolean=func match {
    case 0=>a<b;case 1=>a<=b;case 2=>a>b;case 3=>a>=b
    case 4=>a==b;case 5=>a!=b;case 6=>true;case 7=>false
  }
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
  private class QuadDriver(d: PersistentTileAttachmentStore) extends Driver(d,8) {
    val color=mutable.Map.empty[Int,Long];val depth=mutable.Map.empty[Int,Long]
    val colorDirty=mutable.Set.empty[Int];val depthDirty=mutable.Set.empty[Int]
    val expected=mutable.Queue.empty[Completion]
    val admissions=mutable.ArrayBuffer.empty[Int];val commits=mutable.ArrayBuffer.empty[(Int,Int)]
    var offered=Option.empty[Quad];var held=Option.empty[BigInt]
    var quadReady=true;var allowCanceled=false
    override def begin(p: Pass): Unit = {
      assert(expected.isEmpty);super.begin(p)
      color.clear();depth.clear();colorDirty.clear();depthDirty.clear()
      for(y <- 0 until p.height;x <- 0 until p.width;s <- 0 until p.samples) {
        val i=(y*16+x)*p.samples+s
        color(i)=if(p.colorLoad==AttachmentLoadOp.load)memory(p.address(x,y,s,false))
          else if(p.colorLoad==AttachmentLoadOp.clear)p.colorClear else 0L
        depth(i)=if(p.depthLoad==AttachmentLoadOp.load)memory(p.address(x,y,s,true))
          else if(p.depthLoad==AttachmentLoadOp.clear)p.depthClear else 0L
      }
    }
    def applyQuad(q: Quad): Completion = {
      var pm=0;var cm=0;var zm=0
      for(p <- 0 until 4 if (q.mask&(1<<p))!=0) {
        val i=q.index(p);val old=decode(color(i),pass.format);val ds=depth(i);val z=(ds&0xffffffL).toInt
        val stencil=(ds>>>24).toInt
        val sp=compare(q.stencilRef&q.stencilReadMask,stencil&q.stencilReadMask,q.stencilFunc)
        val passes=sp && compare(q.depth(p),z,q.func)
        val nextStencil=if(!sp)stencil else if(passes)math.min(255,stencil+1)else 255-stencil
        val zw=passes || nextStencil!=stencil
        if(passes) {
          pm|=1<<p
          if(q.colorMask!=0) {
            cm|=1<<p;var out=0L
            val sa=(q.color(p)&255).toInt
            for(c <- 0 until 4) {
              val sc=((q.color(p)>>>(8*c))&255).toInt;val dc=((old>>>(8*c))&255).toInt
              val v=if((q.colorMask&(1<<c))==0)dc
                else if(c==0)sa+(dc*(255-sa)+127)/255 else(sc*sa+dc*(255-sa)+127)/255
              out|=v.toLong<<(c*8)
            }
            color(i)=encode(out,pass.format);colorDirty+=i
          }
        }
        if(zw){zm|=1<<p;depth(i)=(nextStencil.toLong<<24)|(if(passes)q.depth(p).toLong else z.toLong);depthDirty+=i}
      }
      Completion(q.ticket,q.mask,pm,cm,zm)
    }
    override def tick(): Unit = {d.io.quadCompletion.get.ready.poke(quadReady.B);super.tick()}
    override def beforeClock(): Unit = {
      val out=d.io.quadCompletion.get
      held.foreach { v=>out.valid.expect(true.B);assert(out.bits.peek().litValue==v,"held quad completion changed") }
      if(out.valid.peek().litToBoolean) {
        assert(expected.nonEmpty);val e=expected.front
        out.bits.ticket.expect(e.ticket.U);out.bits.mask.expect(e.mask.U)
        if(!allowCanceled) {
          out.bits.canceled.expect(0.U);out.bits.testsPass.expect(e.passed.U)
          out.bits.colorWrites.expect(e.color.U);out.bits.depthStencilWrites.expect(e.depth.U)
        }else {
          val canceled=out.bits.canceled.peek().litValue.toInt
          assert((canceled& ~e.mask)==0)
          assert((canceled&out.bits.colorWrites.peek().litValue.toInt)==0)
          assert((canceled&out.bits.depthStencilWrites.peek().litValue.toInt)==0)
        }
        if(quadReady){expected.dequeue();held=None}else held=Some(out.bits.peek().litValue)
      }
      val fire=d.io.quad.get.valid.peek().litToBoolean && d.io.quad.get.ready.peek().litToBoolean
      d.io.acceptedSamples.get.expect((if(fire)Integer.bitCount(offered.get.mask)else 0).U)
      if(fire){expected.enqueue(applyQuad(offered.get));admissions+=cycle}
      val committed=d.io.committedSamples.get.peek().litValue.toInt
      if(committed!=0)commits+=((cycle,committed))
    }
    def send(q: Quad): Unit = {
      offered=Some(q);val in=d.io.quad.get;val b=in.bits
      b.ticket.poke(q.ticket.U);b.mask.poke(q.mask.U)
      for(p <- 0 until 4){b.index(p).poke(q.index(p).U);b.color(p).poke(q.color(p).U);b.depth(p).poke(q.depth(p).U)}
      val c=b.state;c.poke(0.U.asTypeOf(c));c.depthTestEnable.poke(true.B);c.depthFunc.poke(q.func.U)
      c.depthWriteEnable.poke(true.B);c.stencilTestEnable.poke(true.B);c.stencilFunc.poke(q.stencilFunc.U)
      c.stencilRef.poke(q.stencilRef.U);c.stencilReadMask.poke(q.stencilReadMask.U)
      c.stencilWriteMask.poke(255.U);c.stencilZPassOp.poke(3.U)
      c.stencilZFailOp.poke(5.U);c.blendEnable.poke(true.B);c.colorMask.poke(q.colorMask.U)
      in.valid.poke(true.B);waitFor(in.ready.peek().litToBoolean);tick();in.valid.poke(false.B);offered=None
    }
    def drainQuads(): Unit = {waitFor(expected.isEmpty);tick()}
    def clearOracle(x0: Int,y0: Int,x1: Int,y1: Int,cm: Long,dm: Long,cv: Long,dv: Long): Unit = {
      for(y <- y0 until y1;x <- x0 until x1;s <- 0 until pass.samples) {
        val i=(y*16+x)*pass.samples+s
        if(cm!=0){color(i)=(color(i)&(0xffffffffL^cm))|(cv&cm);colorDirty+=i}
        if(dm!=0){depth(i)=(depth(i)&(0xffffffffL^dm))|(dv&dm);depthDirty+=i}
      }
    }
    def checkStored(): Unit = {
      var words=0
      for(z <- Seq(false,true);y <- 0 until pass.height;x <- 0 until pass.width;s <- 0 until pass.samples) {
        val i=(y*16+x)*pass.samples+s
        val store=if(z)pass.depthStore else pass.colorStore
        val clear=(if(z)pass.depthLoad else pass.colorLoad)==AttachmentLoadOp.clear
        val dirty=(if(z)depthDirty else colorDirty).contains(i)
        if(store && (clear||dirty)) {
          assert(memory(pass.address(x,y,s,z))==(if(z)depth(i)else color(i)),s"stored sample mismatch i=$i depth=$z")
          words+=1
        }
      }
      if(pass.resolve)for(y <- 0 until pass.height;x <- 0 until pass.width) {
        var value=0L
        for(c <- 0 until 4) {
          val sum=(0 until pass.samples).map(s=>((color((y*16+x)*pass.samples+s)>>>(8*c))&255).toInt).sum
          val result=(sum+(if(pass.samples==1)0 else pass.samples/2))/pass.samples
          value|=result.toLong<<(8*c)
        }
        assert(memory(pass.resolveAddress(x,y))==value,s"resolve mismatch x=$x y=$y");words+=1
      }
      assert(requests.count(_.write)==words,s"unexpected exported words ${requests.count(_.write)} vs $words")
    }
  }
  private def quad(n: Int,mode: Int,mask: Int=15,func: Int=6,colorMask: Int=15): Quad = {
    val number=n%64;val x=(number%8)*2;val y=(number/8)*2;val sample=(n/64)%(1<<mode)
    Quad(Vector(y*16+x,y*16+x+1,(y+1)*16+x,(y+1)*16+x+1).map(p=>(p<<mode)|sample),
      Vector.tabulate(4)(p=>(0x21436587L+n*0x010305L+p*0x080503L)&0xffffffffL),
      Vector.tabulate(4)(p=>(n*8191+p*37+1)&0xffffff),n&255,mask,func,colorMask)
  }
  for(macros <- Seq(false,true)) {
  it should s"refill cold color only for passing depth stencil lanes and retain rejected colors for a later draw macros=$macros" in {
    simulate(if(macros)new MacroBankedPersistentStore else new PersistentTileAttachmentStore(storeCredits=8,bankedSamples=true)) { dut=>
      val d=new QuadDriver(dut)
      for(mode <- 0 until 3;stencilCase <- Seq(false,true);func <- 0 until 8) {
        val p=Pass(mode=mode,width=8,height=8,colorLoad=0,depthLoad=0,id=110+mode)
        d.requests.clear()
        for(z <- Seq(false,true);y <- 0 until p.height;x <- 0 until p.width;s <- 0 until p.samples) {
          val i=p.index(x,y,s)
          d.memory(p.address(x,y,s,z))=if(z)((31L+(i%4))<<24)|(0x400000L+i*17)
            else(0x12345678L+i*0x010307L)&0xffffffffL
        }
        val original=quad(0,mode)
        val q=original.copy(func=if(stencilCase)6 else func,
          stencilFunc=if(stencilCase)func else 6,stencilRef=32,
          depth=original.index.zipWithIndex.map{case(i,lane)=>0x400000+i*17+(lane%3)-1})
        val passed=q.index.zipWithIndex.filter {case(i,lane)=>
          compare(q.stencilRef,31+i%4,q.stencilFunc) && compare(q.depth(lane),0x400000+i*17,q.func)}
          .map(_._1).toSet
        d.begin(p);d.send(q);d.drainQuads()
        def address(i: Int,z: Boolean): Long={val pixel=i>>mode;p.address(pixel%16,pixel/16,i%p.samples,z)}
        val wanted=q.index.map(i=>address(i,true)).toSet++passed.map(i=>address(i,false))
        assert(d.requests.forall(!_.write) && d.requests.map(_.address).toSet==wanted &&
          d.requests.size==wanted.size,s"cold refill mismatch mode=$mode stencil=$stencilCase func=$func")
        // Rejected color words remain cold. A later passing blend must fetch
        // those original values rather than use the probe's zero placeholder.
        d.send(original.copy(ticket=1));d.drainQuads()
        assert(d.requests.size==8 && d.requests.map(_.address).distinct.size==8)
        d.end();d.complete();d.checkStored()
      }
      println(s"BANKED_DEFERRED_COLOR macros=$macros comparisons=48 laterPassingDraws=48")
    }
  }
  it should s"drain cancellation around provisional cold reads and held completions before healthy reuse macros=$macros" in {
    simulate(if(macros)new MacroBankedPersistentStore else new PersistentTileAttachmentStore(storeCredits=8,bankedSamples=true)) { dut=>
      val d=new QuadDriver(dut)
      for(delay <- 0 to 20) {
        val p=Pass(width=8,height=8,colorLoad=0,depthLoad=0,id=130+delay)
        d.requests.clear();d.allowCanceled=true;d.quadReady=false
        d.begin(p);d.send(quad(0,0,func=7))
        d.waitFor(d.requests.count(r=> !r.write && r.address>=p.depthBase)==4)
        for(_ <- 0 until delay)d.tick()
        dut.io.pass.cancel.poke(true.B);d.quadReady=true
        d.complete(TilePassStatus.cancelled)
        assert(d.expected.isEmpty && d.requests.forall(r=> !r.write && r.address>=p.depthBase),
          "rejected/canceled cold samples fetched or exported color")
        dut.io.pass.cancel.poke(false.B);d.allowCanceled=false
        d.requests.clear();d.begin(Pass(width=8,height=8,id=160+delay))
        d.send(quad(0,0));d.drainQuads();d.end();d.complete();d.checkStored()
      }
      println(s"BANKED_DEFERRED_CANCEL macros=$macros phases=21 healthyReuse=21")
    }
  }
  }
  for(macros <- Seq(false,true))it should s"merge lazy-clear quads at four commits per cycle then preserve partial clears and MSAA resolve macros=$macros" in {
    simulate(if(macros)new MacroBankedPersistentStore else new PersistentTileAttachmentStore(storeCredits=8,bankedSamples=true)) { dut=>
      val d=new QuadDriver(dut)
      val p=Pass(mode=2,width=16,height=16,colorStore=false,resolve=true)
      d.begin(p)
      for(i <- 0 until 128)d.send(quad(i,2))
      d.drainQuads()
      assert(d.admissions.size==128 && d.admissions.sliding(2).forall(v=>v(1)==v(0)+1))
      assert(d.commits.size==128 && d.commits.forall(_._2==4) && d.commits.sliding(2).forall(v=>v(1)._1==v(0)._1+1))
      assert(d.requests.isEmpty,"lazy clear/sample merge leaked external attachment traffic")
      println(s"BANKED_PERSISTENT macros=$macros quads=128 samples=512 consecutiveCommitCycles=128")
      d.submitClear(1,1,6,5,0xff00ff00L,0xff000000L,0xaabbccddL,0x21000000L)
      d.clearDone();d.clearOracle(1,1,6,5,0xff00ff00L,0xff000000L,0xaabbccddL,0x21000000L)
      for(i <- 0 until 32)d.send(quad(i%8,2,mask=if(i%3==0)5 else 15,func=if(i%2==0)0 else 7,colorMask=i%16))
      d.drainQuads();assert(d.requests.isEmpty)
      d.end();d.complete();d.checkStored()
    }
  }
  it should "fill each missing old plane once while overlapping samples wait and export only dirty load-start words" in {
    simulate(new PersistentTileAttachmentStore(storeCredits=8,bankedSamples=true)) { dut=>
      val d=new QuadDriver(dut)
      for(mode <- 0 until 3) {
        val p=Pass(mode=mode,width=8,height=8,originX=24,originY=24,tileLog2=3,colorLoad=0,depthLoad=0,id=40+mode)
        d.requests.clear()
        for(z <- Seq(false,true);y <- 0 until p.height;x <- 0 until p.width;s <- 0 until p.samples)
          d.memory(p.address(x,y,s,z))=if(z)0x7fabcdefL+s else(0x12345678L+x*257+y*65537+s)&0xffffffffL
        d.begin(p)
        for(i <- 0 until 24) {
          d.send(quad(0,mode,func=if(i%4==0)0 else 6,colorMask=i%16).copy(ticket=i))
          if(i==0) {
            // Hold a newer scalar request while the accepted quad still
            // needs both planes. Its reservation must not block that refill.
            d.issue(0,0);d.response(d.color(0))
          }
        }
        d.drainQuads()
        assert(d.requests.size==8 && d.requests.forall(!_.write) && d.requests.map(_.address).distinct.size==8,
          "repeated resident quad must load only its four color and four depth words")
        d.end();d.complete();d.checkStored()
      }
    }
  }
  it should "hold a clear boundary until quad completions drain and cancel or fault an outstanding quad refill before reuse" in {
    simulate(new PersistentTileAttachmentStore(storeCredits=8,bankedSamples=true)) { dut=>
      val d=new QuadDriver(dut)
      d.begin(Pass(width=8,height=8));d.quadReady=false;d.send(quad(0,0))
      val c=dut.io.pass.clear.bits;c.poke(0.U.asTypeOf(c));c.maxX.poke(1.U);c.maxY.poke(1.U)
      c.colorMask.poke(0xffffffffL.U);c.colorValue.poke(0x89abcdefL.U)
      dut.io.pass.clearDone.ready.poke(false.B)
      dut.io.pass.clear.valid.poke(true.B)
      for(_ <- 0 until 15){d.tick();dut.io.pass.clear.ready.expect(false.B)}
      d.quadReady=true;d.waitFor(dut.io.pass.clear.ready.peek().litToBoolean);d.tick()
      dut.io.pass.clear.valid.poke(false.B);d.clearDone();d.clearOracle(0,0,1,1,0xffffffffL,0,0x89abcdefL,0)
      d.end();d.complete();d.checkStored()
      for(fault <- Seq(false,true)) {
        d.requests.clear();d.allowCanceled=true
        d.begin(Pass(width=8,height=8,colorLoad=0,depthLoad=0,id=if(fault)52 else 51))
        d.deliverResponses=false;d.send(quad(0,0));d.waitFor(d.requests.nonEmpty)
        dut.io.pass.begin.ready.expect(false.B)
        if(fault)d.faultNextResponse=true else dut.io.pass.cancel.poke(true.B)
        for(_ <- 0 until 5){d.tick();dut.io.pass.completion.valid.expect(false.B)}
        d.deliverResponses=true
        d.complete(if(fault)TilePassStatus.memoryFault else TilePassStatus.cancelled)
        assert(d.expected.isEmpty && d.requests.forall(!_.write))
        dut.io.pass.cancel.poke(false.B);d.allowCanceled=false
      }
      d.requests.clear();d.begin(Pass(width=8,height=8,id=53))
      d.send(quad(0,0));d.drainQuads();d.end();d.complete();d.checkStored()
    }
  }
  it should "apply native channel masks before all five packed memory orders and retain the admitted format through resolve" in {
    simulate(new PersistentTileAttachmentStore(storeCredits=8,bankedSamples=true)) { dut=>
      val d=new QuadDriver(dut)
      for(format <- 0 to 4) {
        val p=Pass(mode=format%3,width=8,height=8,format=format,resolve=true,id=60+format)
        d.requests.clear();d.begin(p)
        pokeDescriptor(dut.io.pass.begin.bits,p.copy(format=(format+1)%5))
        for(i <- 0 until 16)d.send(quad(0,p.mode,colorMask=i).copy(ticket=i))
        d.drainQuads();assert(d.requests.isEmpty);d.end();d.complete();d.checkStored()
      }
    }
  }
  it should "capture scalar sidebands and queue one newer request without changing a held older response" in {
    simulate(new PersistentTileAttachmentStore(storeCredits=8,bankedSamples=true)) { dut=>
      val d=new QuadDriver(dut)
      val p=Pass(mode=2,width=8,height=8,originX=24,originY=24,tileLog2=3,id=70)
      d.begin(p)
      val index=(2*16+1)*p.samples+3;val value=0x98765432L
      d.issue(1,2,3,write=true,data=value)
      dut.io.wordIndex.poke(p.index(7,7,0).U);dut.io.depthPlane.poke(true.B)
      dut.io.om.req.bits.addr.poke(0.U);dut.io.om.req.bits.data.poke(0.U)
      d.response(0);d.color(index)=value;d.colorDirty+=index
      d.issue(1,2,3)
      dut.io.wordIndex.poke(0.U);dut.io.depthPlane.poke(true.B)
      d.waitFor(dut.io.om.resp.valid.peek().litToBoolean)
      dut.io.om.resp.bits.addr.expect(p.address(1,2,3,false).U)
      val held=dut.io.om.resp.bits.peek().litValue
      val depthIndex=(3*16+2)*p.samples+1;val ds=0x81234567L
      d.issue(2,3,1,depth=true,write=true,data=ds)
      assert(dut.io.om.resp.bits.peek().litValue==held,"buffered newer scalar request overwrote the held older reply")
      dut.io.wordIndex.poke(0.U);dut.io.depthPlane.poke(false.B)
      dut.io.om.req.bits.addr.poke(0.U);dut.io.om.req.bits.data.poke(0.U)
      d.response(value);d.response(0);d.depth(depthIndex)=ds;d.depthDirty+=depthIndex
      d.end();d.complete();d.checkStored()
    }
  }
  it should "retain every row-pitch bit and high-address carry in staged quad refills at all sample modes" in {
    simulate(new PersistentTileAttachmentStore(storeCredits=8,bankedSamples=true)) { dut=>
      val d=new QuadDriver(dut)
      for(mode <- 0 until 3;pitch <- Seq(0x07000000L,0x07001234L)) {
        val p=Pass(mode=mode,width=2,height=16,originX=0,originY=0,pitch=pitch,
          colorBase=0,depthBase=0x80000000L,colorLoad=0,depthLoad=0,allocationBytes=0x70000000L,id=75+mode)
        d.requests.clear()
        for(z <- Seq(false,true);y <- 0 until p.height;x <- 0 until p.width;s <- 0 until p.samples)
          d.memory(p.address(x,y,s,z))=if(z)0x7fabcdefL else(0x11223344L+y*257+x+s)&0xffffffffL
        d.begin(p);d.send(quad(56+64*(p.samples-1),mode));d.drainQuads()
        assert(d.requests.size==8 && d.requests.forall(!_.write))
        val wanted=(for(z <- Seq(false,true);y <- Seq(14,15);x <- Seq(0,1))yield p.address(x,y,p.samples-1,z)).toSet
        assert(d.requests.map(_.address).toSet==wanted,"staged refill truncated a pitch product or address carry")
        d.end();d.complete();d.checkStored()
      }
    }
  }
  it should "drain cancellation across the refill preparation and offered-read phases before admitting a new owner" in {
    simulate(new PersistentTileAttachmentStore(storeCredits=8,bankedSamples=true)) { dut=>
      val d=new QuadDriver(dut)
      for(delay <- 0 to 10) {
        d.requests.clear();d.allowCanceled=true;d.deliverResponses=false
        d.begin(Pass(mode=delay%3,width=8,height=8,colorLoad=0,depthLoad=0,id=80+delay))
        d.send(quad(0,delay%3))
        for(_ <- 0 until delay)d.tick()
        dut.io.pass.cancel.poke(true.B);d.deliverResponses=true
        d.complete(TilePassStatus.cancelled)
        assert(d.expected.isEmpty && d.requests.forall(!_.write))
        dut.io.pass.cancel.poke(false.B);d.allowCanceled=false
      }
      d.requests.clear();d.begin(Pass(width=8,height=8,id=91))
      d.send(quad(0,0));d.drainQuads();d.end();d.complete();d.checkStored()
    }
  }
}
