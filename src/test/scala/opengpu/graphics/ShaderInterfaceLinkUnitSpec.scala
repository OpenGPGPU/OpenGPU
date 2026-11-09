package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class ShaderInterfaceLinkUnitSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  private case class Slot(valid: Boolean,loc: Int,kind: Int=0,comp: Int=1,array: Int=0,interp: Int=0)
  private case class Ref(status: Int,matched: Int,missing: Int,dup: Int,vdup: Int,mismatch: Int)
  private def ref(vn: Int,vs: Seq[Slot],fn: Int,fs: Seq[Slot]): Ref={
    val inCounts=vn<=16 && fn<=16
    def act(n: Int,s: Seq[Slot])= (0 until n).map(i=>s(i).valid)
    val vAct=act(vn,vs);val fAct=act(fn,fs)
    val extra=(0 until vn).exists((i: Int) => !vs(i).valid) || (0 until fn).exists((i: Int) => !fs(i).valid)
    def dup(n: Int,s: Seq[Slot],a: Seq[Boolean])=(0 until n).map(i=>(0 until i).exists((j: Int) =>a(j)&&a(i)&&s(j).loc==s(i).loc))
    val vd=dup(vn,vs,vAct);val fd=dup(fn,fs,fAct)
    val matched=(0 until fn).map{f=>fAct(f)&&(0 until vn).exists((v: Int) =>vAct(v)&&vs(v).loc==fs(f).loc)}
    val equal=(0 until fn).map{f=>(0 until vn).exists((v: Int) =>vAct(v)&&vs(v).loc==fs(f).loc&&
      vs(v).kind==fs(f).kind&&vs(v).comp==fs(f).comp&&vs(v).array==fs(f).array&&vs(v).interp==fs(f).interp)}
    val missing=(0 until fn).map{f=>fAct(f)&& !matched(f)}
    val mismatch=(0 until fn).map{f=>fAct(f)&&matched(f)&& !equal(f)}
    val legal=inCounts&& !extra&& !(vd++fd).exists(identity)&& !(missing++mismatch).exists(identity)
    def mask(bits: Seq[Boolean])=bits.zipWithIndex.foldLeft(0)((a,b)=>if(b._1)a|(1<<b._2)else a)
    Ref(if(legal)0 else 1,mask(matched),mask(missing),mask(fd),mask(vd),mask(mismatch))
  }
  private class Driver(val d: ShaderInterfaceLinkUnit) {
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    def run(vn: Int,vs: Seq[Slot],fn: Int,fs: Seq[Slot],tag: Int=1): Ref={
      val q=d.io.in.bits;zero(q);q.tag.poke(tag.U);q.vertexCount.poke(vn.U);q.fragmentCount.poke(fn.U)
      def poke(slots: Seq[Slot],vec: Vec[ShaderInterfaceSlot]): Unit=
        slots.zipWithIndex.foreach{case(s,i)=>val e=vec(i);e.valid.poke(s.valid.B);e.location.poke(s.loc.U)
          e.kind.poke(s.kind.U);e.components.poke(s.comp.U);e.arraySize.poke(s.array.U);e.interpolation.poke(s.interp.U)}
      poke(vs,q.vertex);poke(fs,q.fragment)
      d.io.in.valid.poke(true.B);var n=0;while(!d.io.in.ready.peek().litToBoolean && n<20){d.clock.step();n+=1}
      assert(d.io.in.ready.peek().litToBoolean);d.clock.step();d.io.in.valid.poke(false.B);zero(q)
      n=0;while(!d.io.out.valid.peek().litToBoolean && n<20){d.clock.step();n+=1}
      assert(n<20);val o=d.io.out.bits;o.tag.expect(tag.U)
      val r=Ref(o.status.peek().litValue.toInt,o.matchedMask.peek().litValue.toInt,o.missingMask.peek().litValue.toInt,
        o.duplicateMask.peek().litValue.toInt,o.vertexDuplicateMask.peek().litValue.toInt,o.mismatchMask.peek().litValue.toInt)
      d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B);r}
  }
  behavior of "ShaderInterfaceLinkUnit"
  it should "match fragment inputs to unique vertex outputs" in {
    simulate(new ShaderInterfaceLinkUnit){d=>val t=new Driver(d)
      val vs=Seq(Slot(true,0,0,2),Slot(true,1,1,4,2,1),Slot(true,5,0,3),Slot(false,0))
      val fs=Seq(Slot(true,0,0,2),Slot(true,1,1,4,2,1))
      assert(t.run(3,vs,2,fs)==ref(3,vs,2,fs))
      assert(t.run(3,vs,2,fs).status==0)
      assert(t.run(4,vs,2,fs)==ref(4,vs,2,fs))
      val missing=Seq(Slot(true,0,0,2),Slot(true,3,0,1))
      assert(t.run(3,vs,2,missing)==ref(3,vs,2,missing))
      val kind=Seq(Slot(true,0,1,2),Slot(true,1,1,4,2,1))
      assert(t.run(3,vs,2,kind)==ref(3,vs,2,kind))
      val interp=Seq(Slot(true,0,0,2),Slot(true,1,1,4,2,0))
      assert(t.run(3,vs,2,interp)==ref(3,vs,2,interp))
      val dupV=Seq(Slot(true,0,0,2),Slot(true,0,0,2),Slot(true,5,0,3),Slot(false,0))
      assert(t.run(3,dupV,2,fs)==ref(3,dupV,2,fs))
      val dupF=Seq(Slot(true,0,0,2),Slot(true,0,0,2))
      assert(t.run(3,vs,2,dupF)==ref(3,vs,2,dupF))
      val extra=Seq(Slot(true,0,0,2),Slot(false,0))
      assert(t.run(2,extra,1,Seq(Slot(true,0,0,2)))==ref(2,extra,1,Seq(Slot(true,0,0,2))))
      assert(t.run(17,vs,2,fs).status==1)
      info("SHADER_INTERFACE_LINK matched=true extraVertex=true missing=true kind=true interp=true duplicates=true invalidCounts=true")
    }
  }
}
