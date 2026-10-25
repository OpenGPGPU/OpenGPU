package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

private class TypedImageAdmissionOracle extends Module {
  private val p=TypedTileColorConfig(65536)
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new TypedTileImageDescriptor));val out=Decoupled(new TypedImageAdmissionResult)
    val cancel=Input(Bool());val busy=Output(Bool());val originalInvalid=Output(Bool())
  })
  private val staged=Module(new StagedTypedImageAdmission(p))
  staged.io.in<>io.in;io.out<>staged.io.out;staged.io.cancel:=io.cancel;io.busy:=staged.io.busy
  io.originalInvalid:=TypedTileImageRules.invalid(io.in.bits,p)
}

class StagedTypedImageAdmissionSpec extends AnyFlatSpec {
  private class Driver(val d: TypedImageAdmissionOracle) {
    private def zero(x: Data): Unit=x match {
      case r: Record=>r.elements.values.foreach(zero);case v: Vec[_]=>v.foreach(zero)
      case b: Bool=>b.poke(false.B);case s: SInt=>s.poke(0.S);case u: UInt=>u.poke(0.U);case _=>()
    }
    d.io.in.valid.poke(false.B);d.io.out.ready.poke(false.B);d.io.cancel.poke(false.B)
    zero(d.io.in.bits);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    var checked=0
    private var expectedOrigins=Vector.empty[BigInt]
    def bind(v: TypedImageView,base: BigInt,bytes: Int=1,mode: Int=0,width: Int=16,height: Int=16): Unit={
      val pitch=width*(1<<mode)*bytes+3;val end=base+(height-1)*pitch+width*(1<<mode)*bytes
      v.base.poke(base.U);v.limit.poke(end.U);v.allocationBase.poke((base& ~BigInt(63)).U)
      v.allocationLimit.poke(((end+63)& ~BigInt(63)).U)
      v.imageWidth.poke(width.U);v.imageHeight.poke(height.U);v.rowPitch.poke(pitch.U)
      v.readable.poke(true.B);v.writable.poke(true.B);v.store.poke(true.B)
    }
    def prepare(names: Seq[String]=Seq("R8"),mode: Int=0): Unit={
      zero(d.io.in.bits);val b=d.io.in.bits;b.version.poke(1.U);b.pixels.version.poke(1.U)
      val c=b.pixels.colors;c.version.poke(1.U);c.tileLog2.poke(3.U);c.tileWidth.poke(1.U);c.tileHeight.poke(1.U);c.sampleMode.poke(mode.U)
      for((name,i)<-names.zipWithIndex){
        val f=TypedColorFormat.formats(TypedColorFormat.id(name));val v=c.colors(i)
        v.enabled.poke(true.B);v.format.poke(TypedColorFormat.id(name).U);v.offset.poke((i*4096).U)
        v.loadOp.poke(1.U);v.channelMask.poke(15.U);bind(b.views(i),BigInt(0x10000+i*0x10000),f.bytes,mode)
      }
    }
    def resolve(i: Int,base: BigInt=BigInt(0x900000),bytes: Int=1): Unit={
      d.io.in.bits.resolves(i).enable.poke(true.B);bind(d.io.in.bits.resolves(i).destination,base,bytes)
    }
    def start(): Boolean={
      val expected=d.io.originalInvalid.peek().litToBoolean
      expectedOrigins=if(expected)Vector.empty else {
        val b=d.io.in.bits;val c=b.pixels.colors
        val en=c.colors.map(_.enabled.peek().litToBoolean).toVector++Vector(b.pixels.depth.format.peek().litValue!=0,b.pixels.stencil.format.peek().litValue!=0)
        val loads=c.colors.map(_.loadOp.peek().litValue).toVector++Vector(b.pixels.depth.loadOp.peek().litValue,b.pixels.stencil.loadOp.peek().litValue)
        val bytes=Vector.tabulate(6){i=>if(!en(i))0 else if(i<4)TypedColorFormat.formats(c.colors(i).format.peek().litValue.toInt).bytes
          else TypedDepthReference.bytes((if(i==4)b.pixels.depth else b.pixels.stencil).format.peek().litValue.toInt)}
        Vector.tabulate(12){i=>val j=i%6;val resolve=i>=6
          val used=if(resolve)b.resolves(j).enable.peek().litToBoolean else en(j) && (b.views(j).store.peek().litToBoolean || loads(j)==0)
          if(!used)BigInt(0)else {
            val v=if(resolve)b.resolves(j).destination else b.views(j)
            val scale=if(resolve)1 else 1<<c.sampleMode.peek().litValue.toInt
            v.base.peek().litValue+c.originX.peek().litValue*scale*bytes(j)+c.originY.peek().litValue*v.rowPitch.peek().litValue
          }
        }
      }
      d.io.in.ready.expect(true.B);d.io.in.valid.poke(true.B);d.clock.step();d.io.in.valid.poke(false.B)
      // Later work must use only the captured descriptor.
      zero(d.io.in.bits)
      expected
    }
    def finish(expected: Boolean,cancelled: Boolean=false): Int={
      var cycles=0
      while(!d.io.out.valid.peek().litToBoolean && cycles<1024){d.clock.step();cycles+=1}
      assert(cycles<1024,"admission did not retire its bounded descriptor")
      d.io.out.bits.invalid.expect(expected.B);d.io.out.bits.cancelled.expect(cancelled.B)
      if(!expected && !cancelled)expectedOrigins.zipWithIndex.foreach{case(v,i)=>d.io.out.bits.origins(i).expect(v.U)}
      val held=d.io.out.bits.peek().litValue
      d.io.cancel.poke(true.B);d.clock.step(3);d.io.cancel.poke(false.B)
      assert(d.io.out.bits.peek().litValue==held);d.io.in.ready.expect(false.B)
      d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)
      d.io.busy.expect(false.B);checked+=1;cycles
    }
    def check(expected: Boolean): Unit={assert(start()==expected,"independent case disagrees with original rules");finish(expected)}
    def wide(w: BigInt,h: BigInt,pitch: BigInt,base: BigInt=BigInt(0)): Boolean={
      prepare();val v=d.io.in.bits.views(0)
      v.base.poke(base.U);v.limit.poke((BigInt(1)<<32).U);v.allocationBase.poke(0.U);v.allocationLimit.poke((BigInt(1)<<32).U)
      v.imageWidth.poke(w.U);v.imageHeight.poke(h.U);v.rowPitch.poke(pitch.U)
      w==0 || h==0 || w>pitch || base+(h-1)*pitch+w>(BigInt(1)<<32)
    }
  }

  it should "match complete original rules and unbounded arithmetic at all supported formats and exclusive allocation limits" in {
    simulate(new TypedImageAdmissionOracle){d=>val t=new Driver(d)
      for(f<-TypedColorFormat.formats;mode<-0 to 2){
        t.prepare(Seq(f.name),mode);d.io.in.bits.pixels.colors.originX.poke(3.S);d.io.in.bits.pixels.colors.originY.poke(5.S);t.check(false)
      }
      for(format<-1 to 6;mode<-0 to 2){
        t.prepare(Seq.empty,mode);val z=d.io.in.bits.pixels.depth
        z.format.poke(format.U);z.loadOp.poke(1.U)
        val bytes=TypedDepthReference.bytes(format);d.io.in.bits.pixels.colors.reservedBytes.poke((64*(1<<mode)*bytes).U)
        t.bind(d.io.in.bits.views(4),0x50000,bytes,mode);t.check(false)
      }
      val max=(BigInt(1)<<32)-1
      val limits=Seq((BigInt(1),max,BigInt(1),BigInt(0)),(BigInt(1),BigInt(2),BigInt(1),max-1),
        (BigInt(1),BigInt(2),BigInt(1),max),(max,max,max,BigInt(0)),(BigInt(1),max,BigInt(2),BigInt(0)),
        (BigInt(0),BigInt(1),BigInt(1),BigInt(0)),(BigInt(1),BigInt(0),BigInt(1),BigInt(0)))
      for((w,h,p,b)<-limits){val bad=t.wide(w,h,p,b);t.check(bad)}
      val rng=new scala.util.Random(0x33ad017)
      for(i<-0 until 128){
        val w=if(i%3==0)BigInt(32,rng)else BigInt(1+rng.nextInt(32))
        val h=if(i%2==0)BigInt(32,rng)else BigInt(1+rng.nextInt(32))
        val p=if(i%3==1)BigInt(1+rng.nextInt(64))else BigInt(32,rng)
        val b=if(i%4==0)BigInt(32,rng)else BigInt(0)
        val bad=t.wide(w,h,p,b);t.check(bad)
      }
      // Wide image coordinates exercise numeric products without rendering a large frame.
      for(_<-0 until 64){
        val pitch=BigInt(1+rng.nextInt(65535));val w=BigInt(1+rng.nextInt(pitch.toInt));val base=BigInt(rng.nextInt(65536))
        val h=((BigInt(1)<<32)-base-w)/pitch+1
        assert(!t.wide(w,h,pitch,base))
        d.io.in.bits.pixels.colors.originX.poke((BigInt(31,rng)%w).S)
        d.io.in.bits.pixels.colors.originY.poke((BigInt(31,rng)%h).S);t.check(false)
      }
      t.prepare();d.io.in.bits.views(0).readable.poke(false.B);t.check(false)
      t.prepare();d.io.in.bits.pixels.colors.colors(0).loadOp.poke(0.U);d.io.in.bits.views(0).readable.poke(false.B);t.check(true)
      t.prepare();d.io.in.bits.views(0).writable.poke(false.B);t.check(true)
      t.prepare();d.io.in.bits.views(0).store.poke(false.B);d.io.in.bits.views(0).limit.poke(0.U);t.check(false)
      t.prepare();t.resolve(0);d.io.in.bits.resolves(0).destination.writable.poke(false.B);t.check(true)
      t.prepare();t.resolve(0);d.io.in.bits.resolves(0).destination.store.poke(false.B);t.check(true)
      t.prepare();d.io.in.bits.pixels.colors.originX.poke((-1).S);t.check(true)
      t.prepare();d.io.in.bits.version.poke(2.U);t.check(true)
      t.prepare(Seq("R8","R8"));t.bind(d.io.in.bits.views(1),0x10000);t.check(true)
      t.prepare(Seq("R8","R8"));t.bind(d.io.in.bits.views(1),0x10000)
      d.io.in.bits.views(1).store.poke(false.B);d.io.in.bits.pixels.colors.colors(1).loadOp.poke(0.U);t.check(false)
      t.prepare(Seq("R8","R8"));t.bind(d.io.in.bits.views(1),0x10000)
      d.io.in.bits.views(1).store.poke(false.B);d.io.in.bits.pixels.colors.colors(1).loadOp.poke(0.U);t.resolve(1);t.check(true)
      t.prepare(Seq("R8","R8"));t.bind(d.io.in.bits.views(1),0x10000)
      for(i<-0 to 1){d.io.in.bits.views(i).store.poke(false.B);d.io.in.bits.pixels.colors.colors(i).loadOp.poke(0.U)}
      t.resolve(1);t.check(false)
      t.prepare();t.resolve(0,0x10000);t.check(true)
      t.prepare(Seq("R8","R8"));t.resolve(0);t.resolve(1);t.check(true)
      t.prepare();val end=d.io.in.bits.views(0).limit.peek().litValue;t.resolve(0,end);t.check(false)
      // Exercise source and resolve cursor endpoints including depth/stencil.
      t.prepare(Seq.fill(4)("RGBA8"),2);val b=d.io.in.bits
      b.pixels.depth.format.poke(3.U);b.pixels.depth.loadOp.poke(1.U)
      b.pixels.stencil.format.poke(6.U);b.pixels.stencil.offset.poke(1024.U);b.pixels.stencil.loadOp.poke(1.U)
      b.pixels.colors.reservedBytes.poke(1280.U)
      for(i<-0 until 4)b.pixels.colors.colors(i).offset.poke((1280+i*1024).U)
      t.bind(b.views(4),0x50000,4,2);t.bind(b.views(5),0x60000,1,2)
      for(i<-0 until 6)t.resolve(i,BigInt(0x900000+i*0x10000),if(i==5)1 else 4)
      t.check(false)
      // Twelve disjoint large views validate the bounded dual-product walk.
      t.prepare(Seq.fill(4)("R8"));val all=d.io.in.bits;val h=(BigInt(1)<<27)-1
      all.pixels.depth.format.poke(1.U);all.pixels.depth.loadOp.poke(1.U)
      all.pixels.stencil.format.poke(6.U);all.pixels.stencil.offset.poke(128.U);all.pixels.stencil.loadOp.poke(1.U)
      all.pixels.colors.reservedBytes.poke(192.U);all.pixels.colors.originY.poke(((BigInt(1)<<26)+3).S)
      for(i<-0 until 4)all.pixels.colors.colors(i).offset.poke((192+i*64).U)
      var cursor=BigInt(0)
      for(i<-0 until 12){
        val bytes=if(i%6==4)2 else 1;val v=if(i<6)all.views(i)else all.resolves(i-6).destination
        if(i>=6)all.resolves(i-6).enable.poke(true.B)
        val end=cursor+h*bytes
        v.base.poke(cursor.U);v.limit.poke(end.U);v.allocationBase.poke((cursor& ~BigInt(63)).U)
        v.allocationLimit.poke(((end+63)& ~BigInt(63)).U);v.imageWidth.poke(1.U);v.imageHeight.poke(h.U);v.rowPitch.poke(bytes.U)
        v.readable.poke(true.B);v.writable.poke(true.B);v.store.poke(true.B);cursor=((end+63)& ~BigInt(63))+64
      }
      assert(!t.start());val twelvePlaneClocks=t.finish(false);assert(twelvePlaneClocks>512 && twelvePlaneClocks<1024)
      info(s"TYPED_IMAGE_ORIGIN twelvePlaneClocks=$twelvePlaneClocks checkedPhysicalOrigins=true largeCoordinates=true noLargeFrameTraversal=true")
      info(s"TYPED_IMAGE_ADMISSION checked=${t.checked} colors36=true samples1x2x4=true depthStencil=true unboundedEndOracle=true exclusive4GiB=true allAliasClasses=true sixSourcesAndSixResolves=true liveOperandIsolation=true")
    }
  }

  it should "drain cancellation at every long-product offset and reset each owned stage before healthy reuse" in {
    simulate(new TypedImageAdmissionOracle){d=>val t=new Driver(d)
      val max=(BigInt(1)<<32)-1
      def long(): Unit={assert(!t.wide(1,max,1));d.io.in.bits.pixels.colors.originY.poke(BigInt("7ffffffe",16).S)}
      long();assert(!t.start());val latency=t.finish(false)
      for(offset<-0 to latency){
        long();assert(!t.start());d.clock.step(offset)
        val held=d.io.out.valid.peek().litToBoolean
        d.io.cancel.poke(true.B);d.clock.step();d.io.cancel.poke(false.B)
        t.finish(false,cancelled = !held)
      }
      for(offset<-Seq(0,1,2,3,4,5,6,8,16,24,32,48,56,64,72,latency-1).distinct){
        long();t.start();d.clock.step(offset)
        d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
        d.io.in.ready.expect(true.B);d.io.out.valid.expect(false.B)
        t.prepare();t.check(false)
      }
      info(s"TYPED_IMAGE_ADMISSION_LIFETIME productLatency=$latency cancellationOffsets=${latency+1} resetStages=true heldResultImmutable=true boundedRetirement=true healthyReuse=true")
    }
  }
}
