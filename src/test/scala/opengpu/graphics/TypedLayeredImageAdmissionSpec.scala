package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

private class TypedLayeredImageAdmissionHarness extends Module {
  private val storage=NativeBinnedGraphicsConfig.fourEngine.storage
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new TypedLayeredImageDescriptor))
    val out=Decoupled(new TypedLayeredImageAdmissionResult)
    val cancel=Input(Bool());val busy=Output(Bool());val layer=Input(UInt(32.W))
    val selected=Output(new TypedTileImageDescriptor);val selectedValid=Output(Bool());val selectedInvalid=Output(Bool())
  })
  private val admission=Module(new TypedLayeredImageAdmission(storage))
  admission.io.in<>io.in;io.out<>admission.io.out;admission.io.cancel:=io.cancel;io.busy:=admission.io.busy
  private val (valid,selected)=TypedLayeredImageSelection(io.in.bits,io.layer)
  io.selectedValid:=valid;io.selected:=selected;io.selectedInvalid:=TypedTileImageRules.invalid(selected,storage)
}

class TypedLayeredImageAdmissionSpec extends AnyFlatSpec {
  private class Driver(val d:TypedLayeredImageAdmissionHarness) {
    def zero(x:Data): Unit=x match {
      case r:Record=>r.elements.values.foreach(zero);case v:Vec[_]=>v.foreach(zero)
      case b:Bool=>b.poke(false.B);case s:SInt=>s.poke(0.S);case u:UInt=>u.poke(0.U);case _=>()
    }
    zero(d.io.in.bits);d.io.in.valid.poke(false.B);d.io.out.ready.poke(false.B)
    d.io.cancel.poke(false.B);d.io.layer.poke(0.U)
    d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    private def bytes(i:Int): Int={
      val image=d.io.in.bits.image
      if(i<4)TypedColorFormat.formats(image.pixels.colors.colors(i).format.peek().litValue.toInt).bytes
      else TypedDepthReference.bytes((if(i==4)image.pixels.depth else image.pixels.stencil).format.peek().litValue.toInt)
    }
    def prepare(names:Seq[String]=Seq("R8"),layers:Int=256,mode:Int=0): Unit={
      zero(d.io.in.bits);val q=d.io.in.bits;q.version.poke(1.U);q.layers.poke(layers.U)
      val b=q.image;b.version.poke(1.U);b.pixels.version.poke(1.U)
      val c=b.pixels.colors;c.version.poke(1.U);c.tileLog2.poke(3.U);c.tileWidth.poke(1.U);c.tileHeight.poke(1.U)
      c.sampleMode.poke(mode.U);c.identity.context.poke(7.U);c.identity.pass.poke(8.U);c.identity.generation.poke(9.U)
      for((name,i)<-names.zipWithIndex){
        val a=c.colors(i);a.enabled.poke(true.B);a.format.poke(TypedColorFormat.id(name).U)
        a.offset.poke((i*4096).U);a.channelMask.poke(15.U);a.loadOp.poke(AttachmentLoadOp.clear.U)
        bind(i,BigInt(0x100000+i*0x1000000),layers,mode)
      }
    }
    def bind(i:Int,base:BigInt,layers:Int,mode:Int,resolve:Boolean=false,pitchOverride:Option[BigInt]=None): Unit={
      val q=d.io.in.bits;val image=q.image;val size=bytes(i)
      val row=BigInt(8*(1<<mode)*size+3);val span=7*row+8*(1<<mode)*size
      val pitch=pitchOverride.getOrElse((span+63)& ~BigInt(63))
      val end=base+(layers-1)*pitch+span
      val v=if(resolve){image.resolves(i).enable.poke(true.B);image.resolves(i).destination}else image.views(i)
      (if(resolve)q.resolvePitch(i)else q.sourcePitch(i)).poke(pitch.U)
      v.base.poke(base.U);v.limit.poke(end.U);v.allocationBase.poke((base& ~BigInt(63)).U)
      v.allocationLimit.poke(((end+63)& ~BigInt(63)).U);v.imageWidth.poke(8.U);v.imageHeight.poke(8.U)
      v.rowPitch.poke(row.U);v.readable.poke(true.B);v.writable.poke(true.B);v.store.poke(true.B)
    }
    def start(): Unit={d.io.in.ready.expect(true.B);d.io.in.valid.poke(true.B);d.clock.step();d.io.in.valid.poke(false.B)}
    def finish(invalid:Boolean=false,cancelled:Boolean=false): Unit={
      var cycles=0;while(!d.io.out.valid.peek().litToBoolean && cycles<40000){d.clock.step();cycles+=1}
      assert(cycles<40000,"layered admission exceeded its complete 12-plane merge bound")
      d.io.out.bits.invalid.expect(invalid.B);d.io.out.bits.cancelled.expect(cancelled.B)
      d.io.out.bits.identity.context.expect(7.U);d.io.out.bits.identity.pass.expect(8.U);d.io.out.bits.identity.generation.expect(9.U)
      val held=d.io.out.bits.peek().litValue;d.io.cancel.poke(true.B);d.clock.step(3);d.io.cancel.poke(false.B)
      assert(d.io.out.bits.peek().litValue==held,"held layered admission changed through cancel")
      d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B);d.io.busy.expect(false.B)
    }
    def check(invalid:Boolean=false): Unit={start();finish(invalid)}
    def selections(): Unit={
      val q=d.io.in.bits;val count=q.layers.peek().litValue.toInt
      for(layer<-0 until count){d.io.layer.poke(layer.U);d.io.selectedValid.expect(true.B);d.io.selectedInvalid.expect(false.B)
        for(i<-0 until 6){
          val active=if(i<4)q.image.pixels.colors.colors(i).enabled.peek().litToBoolean
            else (if(i==4)q.image.pixels.depth else q.image.pixels.stencil).format.peek().litValue!=0
          val views=Seq((q.image.views(i),q.sourcePitch(i),d.io.selected.views(i),active,false),
            (q.image.resolves(i).destination,q.resolvePitch(i),d.io.selected.resolves(i).destination,q.image.resolves(i).enable.peek().litToBoolean,true))
          for((v,pitch,out,used,resolve)<-views if used){
            // BigInt endpoint arithmetic is independent of the hardware merge.
            val scale=if(resolve)1 else 1<<q.image.pixels.colors.sampleMode.peek().litValue.toInt
            val base=v.base.peek().litValue+layer*pitch.peek().litValue
            val end=base+(v.imageHeight.peek().litValue-1)*v.rowPitch.peek().litValue+v.imageWidth.peek().litValue*scale*bytes(i)
            out.base.expect(base.U);out.limit.expect(end.U)
            out.allocationBase.expect(v.allocationBase.peek().litValue.U);out.allocationLimit.expect(v.allocationLimit.peek().litValue.U)
          }
        }
      }
      for(layer<-Seq(BigInt(count),BigInt(256),BigInt(0x10000),BigInt("ffffffff",16))){
        d.io.layer.poke(layer.U);d.io.selectedValid.expect(false.B)
      }
    }
  }
  it should "admit and select all 256 layers without changing typed formats samples or allocation endpoints" in {
    simulate(new TypedLayeredImageAdmissionHarness){d=>val t=new Driver(d)
      for(f<-TypedColorFormat.formats;mode<-0 to 2){t.prepare(Seq(f.name),mode=mode);t.check();t.selections()}
      t.prepare(Seq.fill(4)("RGBA32UI"),mode=2)
      val image=d.io.in.bits.image;image.pixels.depth.format.poke(3.U);image.pixels.depth.loadOp.poke(AttachmentLoadOp.clear.U)
      image.pixels.stencil.format.poke(6.U);image.pixels.stencil.offset.poke(1024.U);image.pixels.stencil.loadOp.poke(AttachmentLoadOp.clear.U)
      image.pixels.colors.reservedBytes.poke(1280.U)
      for(i<-0 until 4)image.pixels.colors.colors(i).offset.poke((1280+i*4096).U)
      for(i<-4 until 6)t.bind(i,BigInt(0x100000+i*0x1000000),256,2)
      for(i<-0 until 6)t.bind(i,BigInt(0x8000000+i*0x1000000),256,0,resolve=true)
      t.check();t.selections()
      t.prepare();val v=d.io.in.bits.image.views(0);val span=v.limit.peek().litValue-v.base.peek().litValue
      val base=(BigInt(1)<<32)-span;v.base.poke(base.U);v.limit.poke((BigInt(1)<<32).U)
      v.allocationBase.poke((base& ~BigInt(63)).U);v.allocationLimit.poke((BigInt(1)<<32).U)
      t.check();t.selections();v.limit.poke(((BigInt(1)<<32)-1).U);t.check(invalid=true)
      t.prepare();t.start();t.zero(d.io.in.bits);t.finish()
      t.prepare();t.start();d.clock.step(3);d.io.cancel.poke(true.B);d.clock.step();d.io.cancel.poke(false.B)
      t.finish(cancelled=true);t.prepare();t.check()
    }
  }
  it should "check exact cross-layer intervals including legal interleaving and late source preservation" in {
    simulate(new TypedLayeredImageAdmissionHarness){d=>val t=new Driver(d)
      // R8 planes each occupy 85 bytes; two series alternate at pitch 256.
      t.prepare(Seq("R8","R8"));t.bind(0,0x100000,256,0,pitchOverride=Some(BigInt(256)))
      t.bind(1,0x100080,256,0,pitchOverride=Some(BigInt(256)));t.check();t.selections()
      t.bind(1,0x100100,256,0,pitchOverride=Some(BigInt(256)));t.check(invalid=true)
      // The first collision can involve layer 255, after all earlier layers
      // of the first attachment have been proven disjoint.
      t.bind(1,BigInt(0x100000)+255*256+1,256,0,pitchOverride=Some(BigInt(256)));t.check(invalid=true)
      val rng=new scala.util.Random(0x1a9e256)
      for(_<-0 until 64){
        val count=1+rng.nextInt(256);val base=BigInt(0x100000)
        val other=base+rng.nextInt(4096);val pitch=85+rng.nextInt(300);val otherPitch=85+rng.nextInt(300)
        t.prepare(Seq("R8","R8"),layers=count)
        t.bind(0,base,count,0,pitchOverride=Some(BigInt(pitch)))
        t.bind(1,other,count,0,pitchOverride=Some(BigInt(otherPitch)))
        // Enumerate every interval pair in the independent scalar oracle;
        // this deliberately does not reproduce the hardware merge algorithm.
        val aliases=(0 until count).exists(x=>(0 until count).exists{y=>
          val a=base+x*pitch;val b=other+y*otherPitch;a<b+85 && b<a+85})
        t.check(invalid=aliases)
      }
      t.prepare();t.bind(0,0x100000,256,0,resolve=true);t.check(invalid=true)
      t.prepare(Seq("R8","R8"));t.bind(1,0x100000,256,0)
      d.io.in.bits.image.views(1).store.poke(false.B)
      d.io.in.bits.image.pixels.colors.colors(1).loadOp.poke(AttachmentLoadOp.load.U);t.check()
      t.bind(1,0x8000000,256,0,resolve=true);t.check(invalid=true)
      t.prepare();d.io.in.bits.sourcePitch(0).poke(1.U);t.check(invalid=true)
      for(count<-Seq(0,257,511)){t.prepare();d.io.in.bits.layers.poke(count.U);t.check(invalid=true)}
      t.prepare();d.io.in.bits.image.views(0).writable.poke(false.B);t.check(invalid=true)
      // Clear/discard has no backing dependency and remains memoryless.
      t.prepare();t.zero(d.io.in.bits.image.views(0));t.check()
      // All twelve streams are interleaved across 256 layers. This exercises
      // every alias class and the complete staged merge latency bound.
      t.prepare(Seq.fill(4)("R8"));val image=d.io.in.bits.image
      image.pixels.depth.format.poke(1.U);image.pixels.depth.loadOp.poke(AttachmentLoadOp.clear.U)
      image.pixels.stencil.format.poke(6.U);image.pixels.stencil.offset.poke(128.U);image.pixels.stencil.loadOp.poke(AttachmentLoadOp.clear.U)
      image.pixels.colors.reservedBytes.poke(192.U)
      for(i<-0 until 4)image.pixels.colors.colors(i).offset.poke((192+i*4096).U)
      for(i<-0 until 6){
        t.bind(i,BigInt(0x100000+i*256),256,0,pitchOverride=Some(BigInt(4096)))
        t.bind(i,BigInt(0x100000+(i+6)*256),256,0,resolve=true,pitchOverride=Some(BigInt(4096)))
      }
      t.check();t.selections()
      t.prepare();t.check()
    }
  }
}
