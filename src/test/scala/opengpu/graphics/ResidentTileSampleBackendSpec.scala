package opengpu.graphics

import chisel3._
import chisel3.util.HasBlackBoxInline
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable
import java.nio.file.{Files,Paths}

private class ResidentSampleMacroResources extends BlackBox with HasBlackBoxInline {
  val io=IO(new Bundle {val present=Output(Bool())})
  setInline("ResidentSampleMacroResources.sv",
    "module ResidentSampleMacroResources(output present); assign present=1'b1; endmodule\n"+
      Files.readString(Paths.get("depends/asap7_sram_0p0/generated/verilog/srambank_64x4x32_6t122.v")))
}
private class MacroResidentTileSamples extends ResidentTileSampleBackend(true) {
  val macroModelPresent=IO(Output(Bool()))
  private val resources=Module(new ResidentSampleMacroResources)
  macroModelPresent:=resources.io.present
}

class ResidentTileSampleBackendSpec extends AnyFlatSpec {
  private case class State(df: Int=6,stencil: Boolean=true,sf: Int=6,ref: Int=0x53,readMask: Int=255,
    writeMask: Int=255,fail: Int=0,zfail: Int=0,zpass: Int=3,depthWrite: Boolean=true,
    blend: Int=1,srcFactor: Int=4,dstFactor: Int=5,equation: Int=0,mask: Int=15)
  private case class Quad(index: Vector[Int],color: Vector[Long],depth: Vector[Int],state: State,mask: Int,ticket: Int)
  private case class Value(color: Long,ds: Long,pass: Boolean,cw: Boolean,zw: Boolean)
  private def cmp(a: Int,b: Int,f: Int): Boolean=f match {
    case 0=>a<b;case 1=>a<=b;case 2=>a>b;case 3=>a>=b;case 4=>a==b;case 5=>a!=b;case 6=>true;case 7=>false
  }
  private def reference(color: Long,ds: Long,src: Long,z: Int,s: State): Value = {
    val st=(ds>>>24).toInt;val oldZ=(ds&0xffffffL).toInt
    val sp= !s.stencil || cmp(s.ref&s.readMask,st&s.readMask,s.sf)
    val dp=cmp(z,oldZ,s.df);val pass=sp&&dp
    val opcode=if(!sp)s.fail else if(!dp)s.zfail else s.zpass
    val next=opcode match {
      case 0=>st;case 1=>0;case 2=>s.ref;case 3=>math.min(255,st+1);case 4=>math.max(0,st-1)
      case 5=>255-st;case 6=>(st+1)&255;case 7=>(st+255)&255
    }
    val newSt=if(s.stencil)(next&s.writeMask)|(st&(255^s.writeMask))else st
    val zw=(pass&&s.depthWrite)||(newSt!=st)
    val newDs=if(zw)(newSt.toLong<<24)|(if(pass&&s.depthWrite)z else oldZ).toLong else ds
    val sa=(src&255).toInt;val da=(color&255).toInt
    def factor(code: Int,sc: Int,dc: Int): Int=code match {
      case 0=>0;case 1=>255;case 2=>sc;case 3=>255-sc;case 4=>sa;case 5=>255-sa
      case 6=>dc;case 7=>255-dc;case 8=>da;case 9=>255-da;case 10=>math.min(sa,255-da);case _=>0
    }
    var out=0L
    for(c <- 0 until 4) {
      val sc=((src>>>(8*c))&255).toInt;val dc=((color>>>(8*c))&255).toInt
      val value=if(!pass || (s.mask&(1<<c))==0)dc else if(s.blend==0)sc else if(s.blend==1) {
        if(c==0)sa+(dc*(255-sa)+127)/255 else(sc*sa+dc*(255-sa)+127)/255
      }else {
        val a=(sc*factor(s.srcFactor,sc,dc)+127)/255;val b=(dc*factor(s.dstFactor,sc,dc)+127)/255
        s.equation match {
          case 1=>math.max(0,a-b);case 2=>math.max(0,b-a);case 3=>math.min(sc,dc);case 4=>math.max(sc,dc)
          case _=>math.min(255,a+b)
        }
      }
      out|=value.toLong<<(c*8)
    }
    Value(out,newDs,pass,pass&&s.mask!=0,zw)
  }
  private class Driver(val d: ResidentTileSampleBackend) {
    val memory=mutable.Map.empty[Int,(Long,Long)]
    private val expected=mutable.Queue.empty[Quad]
    private var held=Option.empty[BigInt]
    var cycle=0;var accepted=0;var completed=0;var committed=0;var canceledSamples=0
    val commitCycles=mutable.ArrayBuffer.empty[(Int,Int)]
    var ready=true
    d.io.begin.poke(false.B);d.io.sampleMode.poke(0.U);d.io.cancel.poke(false.B)
    d.io.in.valid.poke(false.B);d.io.in.bits.poke(0.U.asTypeOf(d.io.in.bits))
    d.io.out.ready.poke(true.B);d.io.maintenance.poke(false.B)
    for(p <- 0 until 4) {
      d.io.read(p).valid.poke(false.B);d.io.read(p).bits.poke(0.U.asTypeOf(d.io.read(p).bits))
      d.io.write(p).valid.poke(false.B);d.io.write(p).bits.poke(0.U.asTypeOf(d.io.write(p).bits))
      d.io.response(p).ready.poke(true.B)
    }
    d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    def begin(mode: Int): Unit = {
      d.io.drained.expect(true.B);d.io.in.valid.poke(false.B);d.io.cancel.poke(false.B)
      d.io.sampleMode.poke(mode.U);d.io.begin.poke(true.B);d.clock.step();d.io.begin.poke(false.B)
      memory.clear();d.io.maintenance.poke(true.B)
      for(i <- 0 until 256*(1<<mode)) {
        val c=(0x23456789L^(i*0x010305L))&0xffffffffL;val z=0x7f800000L+i
        val w=d.io.write(0);w.valid.poke(true.B);w.bits.index.poke(i.U)
        w.bits.color.poke(c.U);w.bits.depthStencil.poke(z.U)
        w.bits.colorEnable.poke(true.B);w.bits.depthStencilEnable.poke(true.B)
        w.ready.expect(true.B);d.clock.step();memory(i)=(c,z)
      }
      d.io.write(0).valid.poke(false.B);d.io.maintenance.poke(false.B)
    }
    def poke(q: Quad): Unit = {
      val b=d.io.in.bits;val c=b.state;val s=q.state
      b.mask.poke(q.mask.U);b.ticket.poke(q.ticket.U)
      for(p <- 0 until 4){b.index(p).poke(q.index(p).U);b.color(p).poke(q.color(p).U);b.depth(p).poke(q.depth(p).U)}
      c.depthTestEnable.poke(true.B);c.depthFunc.poke(s.df.U);c.depthWriteEnable.poke(s.depthWrite.B)
      c.stencilTestEnable.poke(s.stencil.B);c.stencilFunc.poke(s.sf.U);c.stencilRef.poke(s.ref.U)
      c.stencilReadMask.poke(s.readMask.U);c.stencilWriteMask.poke(s.writeMask.U)
      c.stencilFailOp.poke(s.fail.U);c.stencilZFailOp.poke(s.zfail.U);c.stencilZPassOp.poke(s.zpass.U)
      c.blendEnable.poke((s.blend==1).B);c.blendCfgEnable.poke((s.blend==2).B)
      c.blendSrcFactor.poke(s.srcFactor.U);c.blendDstFactor.poke(s.dstFactor.U)
      c.blendEquation.poke(s.equation.U);c.colorMask.poke(s.mask.U)
    }
    def step(q: Option[Quad]=None,cancel: Boolean=false): Boolean = {
      d.io.in.valid.poke(q.nonEmpty.B);q.foreach(poke)
      d.io.cancel.poke(cancel.B);d.io.out.ready.poke(ready.B)
      held.foreach(v=>{d.io.out.valid.expect(true.B);assert(d.io.out.bits.peek().litValue==v,"held completion changed")})
      if(d.io.out.valid.peek().litToBoolean) {
        assert(expected.nonEmpty);val q=expected.front;val b=d.io.out.bits
        b.ticket.expect(q.ticket.U);b.mask.expect(q.mask.U)
        if(ready) {
          var pm=0;var cm=0;var zm=0
          val canceled=b.canceled.peek().litValue.toInt
          assert((canceled& ~q.mask)==0)
          for(p <- 0 until 4 if (q.mask&(1<<p))!=0) {
            val old=memory(q.index(p));val value=reference(old._1,old._2,q.color(p),q.depth(p),q.state)
            if((canceled&(1<<p))==0) {
              if(value.pass)pm|=1<<p;if(value.cw)cm|=1<<p;if(value.zw)zm|=1<<p
              memory(q.index(p))=(if(value.cw)value.color else old._1,if(value.zw)value.ds else old._2)
            }
          }
          b.testsPass.expect(pm.U);b.colorWrites.expect(cm.U);b.depthStencilWrites.expect(zm.U)
          canceledSamples+=Integer.bitCount(canceled);expected.dequeue();completed+=1;held=None
        }else held=Some(b.peek().litValue)
      }
      val fire=q.nonEmpty && d.io.in.ready.peek().litToBoolean
      d.io.acceptedSamples.expect((if(fire)Integer.bitCount(q.get.mask)else 0).U)
      if(fire){expected.enqueue(q.get);accepted+=1}
      val writes=d.io.committedSamples.peek().litValue.toInt
      committed+=writes
      if(writes!=0)commitCycles+=((cycle,writes))
      d.clock.step();cycle+=1
      fire
    }
    def run(quads: Vector[Quad],stalls: Boolean): Vector[Int] = {
      val times=mutable.ArrayBuffer.empty[Int];var next=0;val start=cycle
      while((next<quads.size || expected.nonEmpty) && cycle-start<quads.size*20+100) {
        ready= !stalls || (cycle%9!=4 && cycle%13!=7)
        if(step(quads.lift(next))){times+=cycle-1;next+=1}
      }
      d.io.in.valid.poke(false.B);assert(next==quads.size && expected.isEmpty,"quad backend did not drain")
      d.io.drained.expect(true.B);times.toVector
    }
    def checkMemory(): Unit = {
      d.io.in.valid.poke(false.B);d.io.maintenance.poke(true.B)
      for((i,value) <- memory.toVector.sortBy(_._1)) {
        val r=d.io.read(0);r.valid.poke(true.B);r.bits.index.poke(i.U);r.bits.ticket.poke((i&255).U)
        r.ready.expect(true.B);d.clock.step();r.valid.poke(false.B)
        var wait=0
        while(!d.io.response(0).valid.peek().litToBoolean && wait<10){d.clock.step();wait+=1}
        val b=d.io.response(0);b.valid.expect(true.B);b.bits.index.expect(i.U);b.bits.ticket.expect((i&255).U)
        b.bits.color.expect(value._1.U);b.bits.depthStencil.expect(value._2.U);d.clock.step()
      }
      d.io.drained.expect(true.B);d.io.maintenance.poke(false.B)
    }
  }
  private def quad(n: Int,mode: Int,s: State=State(),mask: Int=15): Quad = {
    val q=n%64;val x=(q%8)*2;val y=(q/8)*2;val sample=(n/64)%(1<<mode)
    val index=Vector((y*16+x),(y*16+x+1),((y+1)*16+x),((y+1)*16+x+1)).map(p=>(p<<mode)|sample)
    Quad(index,Vector.tabulate(4)(p=>(0x20406080L+n*0x010203L+p*0x030507L)&0xffffffffL),
      Vector.tabulate(4)(p=>(n*8191+p*777)&0xffffff),s,mask,n&255)
  }
  for(macros <- Seq(false,true))it should s"sustain four complete resident updates per cycle with depth stencil and blending macros=$macros" in {
    simulate(if(macros)new MacroResidentTileSamples else new ResidentTileSampleBackend()) { d=>
      val t=new Driver(d);t.begin(2)
      val times=t.run(Vector.tabulate(128)(i=>quad(i,2)),stalls=false)
      assert(times.sliding(2).forall(v=>v(1)==v(0)+1),"conflict-free quad admission must sustain four samples/cycle")
      assert(t.committed==512 && t.canceledSamples==0)
      assert(t.commitCycles.size==128 && t.commitCycles.forall(_._2==4))
      assert(t.commitCycles.sliding(2).forall(v=>v(1)._1==v(0)._1+1),"actual SRAM writes must sustain four complete samples/cycle")
      println(s"H3 resident complete RMW macros=$macros quads=128 samples=512 consecutiveAdmissions=${times.last-times.head+1} consecutiveWriteCycles=${t.commitCycles.size} cycles=${t.cycle}")
      t.checkMemory()
    }
  }
  it should "serialize overlapping samples across sparse quads and preserve changing state through completion backpressure" in {
    simulate(new ResidentTileSampleBackend()) { d=>
      val t=new Driver(d);val random=new scala.util.Random(0x514d4552)
      for(mode <- 0 until 3) {
        t.begin(mode)
        val packets=Vector.tabulate(256) { i=>
          val s=State(df=random.nextInt(8),sf=random.nextInt(8),ref=random.nextInt(256),
            readMask=random.nextInt(256),writeMask=random.nextInt(256),fail=random.nextInt(8),zfail=random.nextInt(8),zpass=random.nextInt(8),
            depthWrite=random.nextBoolean(),blend=random.nextInt(3),srcFactor=random.nextInt(16),dstFactor=random.nextInt(16),
            equation=random.nextInt(8),mask=random.nextInt(16))
          quad(i%5,mode,s,if(i%17==0)0 else random.nextInt(16)).copy(ticket=i&255)
        }
        t.run(packets,stalls=true);t.checkMemory()
      }
    }
  }
  it should "drain cancellation with committed masks and immutable held completions then permit rebinding" in {
    simulate(new ResidentTileSampleBackend()) { d=>
      val t=new Driver(d);t.begin(0)
      for(i <- 0 until 7)assert(t.step(Some(quad(i,0))))
      t.ready=false;t.step(cancel=true)
      for(_ <- 0 until 12)t.step(cancel=true)
      t.ready=true
      for(_ <- 0 until 20)t.step(cancel=true)
      d.io.drained.expect(true.B);assert(t.completed==7 && t.canceledSamples>0)
      t.checkMemory();t.begin(1)
      t.run(Vector.tabulate(20)(i=>quad(i%3,1)),stalls=true);t.checkMemory()
    }
  }
}
