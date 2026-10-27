package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class MergeRoundingHarness(legacy: Boolean) extends Module {
  val io=IO(new Bundle {
    val valid=Input(Bool());val a=Input(UInt(8.W));val b=Input(UInt(8.W))
    val ready=Output(Bool());val resultValid=Output(Bool());val color=Output(UInt(32.W))
  })
  private val p=Module(new TileSampleMergePipeline)
  p.io.in.valid:=io.valid;p.io.in.bits:=0.U.asTypeOf(p.io.in.bits)
  p.io.in.bits.state.colorMask:=15.U
  if(legacy) {
    p.io.in.bits.sourceColor:=Cat(io.a,io.a,io.a,1.U(8.W))
    p.io.in.bits.oldColor:=Cat(io.b,io.b,io.b,127.U(8.W))
    p.io.in.bits.state.blendEnable:=true.B
  }else {
    p.io.in.bits.sourceColor:=Cat(io.a,io.a,io.a,255.U(8.W))
    p.io.in.bits.oldColor:=io.b
    p.io.in.bits.state.blendCfgEnable:=true.B
    p.io.in.bits.state.blendSrcFactor:=8.U
  }
  p.io.out.ready:=true.B
  io.ready:=p.io.in.ready;io.resultValid:=p.io.out.valid;io.color:=p.io.out.bits.color
}

class TileSampleMergePipelineSpec extends AnyFlatSpec {
  private case class State(dTest: Boolean=false,dFunc: Int=0,dWrite: Boolean=false,
    sTest: Boolean=false,sFunc: Int=6,sRef: Int=0,sRead: Int=255,sWrite: Int=255,
    fail: Int=0,zfail: Int=0,zpass: Int=0,legacy: Boolean=false,general: Boolean=false,
    sf: Int=1,df: Int=0,eq: Int=0,mask: Int=15)
  private case class Sample(index: Int,ticket: Int,source: Long,depth: Int,old: Long,ds: Long,state: State)
  private case class Result(index: Int,ticket: Int,color: Long,ds: Long,cwrite: Boolean,zwrite: Boolean,pass: Boolean)
  private def compare(a: Long,b: Long,f: Int): Boolean=f match {
    case 0=>a<b;case 1=>a<=b;case 2=>a>b;case 3=>a>=b
    case 4=>a==b;case 5=>a!=b;case 6=>true;case 7=>false
  }
  private def op(value: Int,reference: Int,code: Int): Int=code match {
    case 0=>value;case 1=>0;case 2=>reference;case 3=>math.min(255,value+1)
    case 4=>math.max(0,value-1);case 5=>255-value;case 6=>(value+1)&255;case 7=>(value+255)&255
  }
  private def reference(a: Sample): Result = {
    val s=a.state;val oldStencil=((a.ds>>>24)&255).toInt;val oldDepth=a.ds&0xffffffL
    val sp= !s.sTest || compare(s.sRef&s.sRead,oldStencil&s.sRead,s.sFunc)
    val dp= !s.dTest || compare(a.depth,oldDepth,s.dFunc)
    val pass=sp && dp
    val operation=if(!sp)s.fail else if(!dp)s.zfail else s.zpass
    val changedStencil=if(s.sTest)(op(oldStencil,s.sRef,operation)&s.sWrite)|(oldStencil&(255^s.sWrite))else oldStencil
    val changed=s.sTest && changedStencil!=oldStencil
    val write=if(pass)s.dWrite||changed else changed
    val nextStencil=if(s.dTest||s.sTest)changedStencil else 0
    val nextDepth=if(pass&&s.dWrite)a.depth.toLong else oldDepth
    val ds=if(write)(nextStencil.toLong<<24)|nextDepth else a.ds
    val sa=(a.source&255).toInt;val da=(a.old&255).toInt
    def factor(code: Int,sc: Int,dc: Int): Int=code match {
      case 0=>0;case 1=>255;case 2=>sc;case 3=>255-sc;case 4=>sa;case 5=>255-sa
      case 6=>dc;case 7=>255-dc;case 8=>da;case 9=>255-da;case 10=>math.min(sa,255-da);case _=>0
    }
    var color=0L
    for(c <- 0 until 4) {
      val sc=((a.source>>>(c*8))&255).toInt;val dc=((a.old>>>(c*8))&255).toInt
      val merged=if(s.general) {
        val src=(sc*factor(s.sf,sc,dc)+127)/255
        val dst=(dc*factor(s.df,sc,dc)+127)/255
        s.eq match {
          case 1=>math.max(0,src-dst);case 2=>math.max(0,dst-src)
          case 3=>math.min(sc,dc);case 4=>math.max(sc,dc);case _=>math.min(255,src+dst)
        }
      }else if(s.legacy) {
        if(c==0)sa+(dc*(255-sa)+127)/255 else (sc*sa+dc*(255-sa)+127)/255
      }else sc
      val value=if(pass && (s.mask&(1<<c))!=0)merged else dc
      color|=value.toLong<<(8*c)
    }
    Result(a.index,a.ticket,color,ds,pass&&s.mask!=0,write,pass)
  }
  private def poke(d: TileSampleMergePipeline,a: Sample): Unit = {
    val b=d.io.in.bits;val s=a.state;val c=b.state
    b.index.poke(a.index.U);b.ticket.poke(a.ticket.U);b.sourceColor.poke(a.source.U)
    b.sourceDepth.poke(a.depth.U);b.oldColor.poke(a.old.U);b.oldDepthStencil.poke(a.ds.U)
    c.depthTestEnable.poke(s.dTest.B);c.depthFunc.poke(s.dFunc.U);c.depthWriteEnable.poke(s.dWrite.B)
    c.stencilTestEnable.poke(s.sTest.B);c.stencilFunc.poke(s.sFunc.U);c.stencilRef.poke(s.sRef.U)
    c.stencilReadMask.poke(s.sRead.U);c.stencilWriteMask.poke(s.sWrite.U)
    c.stencilFailOp.poke(s.fail.U);c.stencilZFailOp.poke(s.zfail.U);c.stencilZPassOp.poke(s.zpass.U)
    c.blendEnable.poke(s.legacy.B);c.blendCfgEnable.poke(s.general.B)
    c.blendSrcFactor.poke(s.sf.U);c.blendDstFactor.poke(s.df.U);c.blendEquation.poke(s.eq.U);c.colorMask.poke(s.mask.U)
  }
  private def run(d: TileSampleMergePipeline,samples: Vector[Sample],stall: Boolean): Unit = {
    d.io.in.valid.poke(false.B);d.io.in.bits.poke(0.U.asTypeOf(d.io.in.bits));d.io.out.ready.poke(false.B)
    d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    val expected=mutable.Queue.empty[(Result,Int)]
    var next=0;var retired=0;var cycle=0
    var input=Option.empty[Sample];var held=Option.empty[BigInt]
    while((next<samples.size || input.nonEmpty || expected.nonEmpty) && cycle<samples.size*8+100) {
      if(input.isEmpty && next<samples.size && (!stall || cycle%4!=1))input=Some(samples(next))
      d.io.in.valid.poke(input.nonEmpty.B);input.foreach(poke(d,_))
      d.io.out.ready.poke((!stall || (cycle>=12 && cycle%7!=3 && cycle%11!=5)).B)
      held.foreach { old=>d.io.out.valid.expect(true.B);assert(d.io.out.bits.peek().litValue==old,"held merge result changed") }
      if(d.io.out.valid.peek().litToBoolean) {
        assert(expected.nonEmpty,"unowned merge result")
        val(e,due)=expected.front;assert(cycle>=due)
        val b=d.io.out.bits
        b.index.expect(e.index.U);b.ticket.expect(e.ticket.U);b.color.expect(e.color.U)
        b.depthStencil.expect(e.ds.U);b.colorWrite.expect(e.cwrite.B);b.depthStencilWrite.expect(e.zwrite.B);b.testsPass.expect(e.pass.B)
        if(d.io.out.ready.peek().litToBoolean){expected.dequeue();retired+=1;held=None}
        else held=Some(b.peek().litValue)
      }else assert(held.isEmpty)
      if(input.nonEmpty && d.io.in.ready.peek().litToBoolean) {
        expected.enqueue((reference(input.get),cycle+3));input=None;next+=1
      }
      d.clock.step();cycle+=1
      d.io.drained.expect(expected.isEmpty.B)
    }
    d.io.in.valid.poke(false.B)
    assert(next==samples.size && retired==samples.size && expected.isEmpty)
    d.io.out.valid.expect(false.B);d.io.drained.expect(true.B)
    if(!stall)assert(cycle==samples.size+3,"resident merge stream must sustain one result/cycle")
  }

  it should "match independent integer depth stencil blend and mask semantics under changing state and stalled replies" in {
    val rng=new scala.util.Random(0x4d455247)
    def word(): Long=rng.nextLong()&0xffffffffL
    val cases=mutable.ArrayBuffer.empty[Sample]
    def add(s: State,src: Long=0x81fe037fL,depth: Int=0x123456,old: Long=0x20d090c0L,ds: Long=0x7f123456L): Unit=
      cases+=Sample(cases.size%1024,cases.size%256,src,depth,old,ds,s)
    for(df <- 0 until 8;sf <- 0 until 8;z <- Seq(0,0x123456,0xffffff);st <- Seq(0,1,127,254,255))
      add(State(dTest=true,dFunc=df,dWrite=true,sTest=true,sFunc=sf,sRef=127,fail=(df+sf)%8,zfail=df,zpass=sf),
        ds=(st.toLong<<24)|z)
    for(op <- 0 until 8;stored <- Seq(0,1,127,254,255);mask <- Seq(0,0x55,0xff)) {
      add(State(sTest=true,sFunc=7,sRef=0xb7,sWrite=mask,fail=op),ds=(stored.toLong<<24)|0x123456L)
      add(State(dTest=true,dFunc=7,sTest=true,sFunc=6,sRef=0xb7,sWrite=mask,zfail=op),ds=(stored.toLong<<24)|0x123456L)
      add(State(dTest=false,dWrite=false,sTest=true,sFunc=6,sRef=0xb7,sWrite=mask,zpass=op),ds=(stored.toLong<<24)|0x123456L)
    }
    for(sf <- 0 until 16;df <- 0 until 16;eq <- 0 until 8)
      add(State(legacy=true,general=true,sf=sf,df=df,eq=eq),word(),rng.nextInt(1<<24),word(),word())
    for(mask <- 0 until 16;legacy <- Seq(false,true);general <- Seq(false,true))
      add(State(dWrite=true,legacy=legacy,general=general,sf=4,df=5,mask=mask))
    for(_ <- 0 until 2000) {
      val s=State(rng.nextBoolean(),rng.nextInt(8),rng.nextBoolean(),rng.nextBoolean(),rng.nextInt(8),
        rng.nextInt(256),rng.nextInt(256),rng.nextInt(256),rng.nextInt(8),rng.nextInt(8),rng.nextInt(8),
        rng.nextBoolean(),rng.nextBoolean(),rng.nextInt(16),rng.nextInt(16),rng.nextInt(8),rng.nextInt(16))
      add(s,word(),rng.nextInt(1<<24),word(),word())
    }
    simulate(new TileSampleMergePipeline)(d=>run(d,cases.toVector,stall=true))
  }

  it should "sustain one complete arithmetic result per cycle with per-sample state and tickets" in {
    val samples=Vector.tabulate(512)(i=>Sample(i,i&255,(0x18273645L+i*0x010203L)&0xffffffffL,i*8191,
      (0xfedcba98L-i*0x010101L)&0xffffffffL,0x7f123456L,
      State(dTest=true,dFunc=6,dWrite=true,sTest=true,sFunc=6,sRef=i&255,zpass=i&7,
        legacy=i%3==0,general=i%3==1,sf=i%11,df=(i+3)%11,eq=i%5,mask=i%16)))
    simulate(new TileSampleMergePipeline)(d=>run(d,samples,stall=false))
  }

  it should "buffer two result credits without consumer-ready feedback into input admission" in {
    simulate(new TileSampleMergePipeline) { d=>
      d.io.in.valid.poke(false.B);d.io.in.bits.poke(0.U.asTypeOf(d.io.in.bits));d.io.out.ready.poke(false.B)
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val samples=Vector.tabulate(4)(i=>Sample(i,i,0x12345670L+i,0x123450+i,
        0x8899aabbL,0x7fabcdefL,State(dWrite=true)))
      for(s <- samples) {
        poke(d,s);d.io.in.valid.poke(true.B);d.io.in.ready.expect(true.B);d.clock.step()
      }
      d.io.in.valid.poke(false.B);d.io.in.ready.expect(false.B)
      d.io.out.ready.poke(true.B)
      d.io.in.ready.expect(false.B) // Registered capacity changes only on the edge.
      for(s <- samples) {
        d.io.out.valid.expect(true.B);d.io.out.bits.ticket.expect(s.ticket.U)
        d.io.out.bits.color.expect(reference(s).color.U)
        d.clock.step()
      }
      d.io.out.valid.expect(false.B);d.io.drained.expect(true.B)
    }
  }

  for(legacy <- Seq(false,true))it should s"exhaust rounded multiplication or convex-sum domains without a divider mismatch legacy=$legacy" in {
    simulate(new MergeRoundingHarness(legacy)) { d=>
      d.io.valid.poke(false.B);d.io.a.poke(0.U);d.io.b.poke(0.U)
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val count=65536
      for(cycle <- 0 until count+3) {
        d.io.valid.poke((cycle<count).B)
        if(cycle<count){d.io.a.poke((cycle>>8).U);d.io.b.poke((cycle&255).U);d.io.ready.expect(true.B)}
        d.io.resultValid.expect((cycle>=3).B)
        if(cycle>=3) {
          val n=cycle-3;val a=n>>8;val b=n&255
          val channel=if(legacy)(a+b*254+127)/255 else(a*b+127)/255
          val alpha=if(legacy)128 else b
          val color=(channel.toLong<<24)|(channel.toLong<<16)|(channel.toLong<<8)|alpha
          d.io.color.expect(color.U)
        }
        d.clock.step()
      }
      d.io.valid.poke(false.B);d.io.resultValid.expect(false.B)
    }
  }
}
