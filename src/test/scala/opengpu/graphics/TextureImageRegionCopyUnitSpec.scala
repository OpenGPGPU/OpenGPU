package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.{CachePolicy,GpuConfig}
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

private class RegionCopyHarness extends Module {
  private val config=GpuConfig(lanes=4,warps=2)
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new TextureImageRegionCopyRequest));val out=Decoupled(new TextureImageRegionCopyResult)
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,4))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,4)))
    val cancel=Input(Bool());val invalidate=Input(Bool());val busy=Output(Bool())
  })
  private val unit=Module(new TextureImageRegionCopyUnit(config))
  unit.io.in<>io.in;io.out<>unit.io.out;io.memoryRequest<>unit.io.memoryRequest
  unit.io.memoryResponse<>io.memoryResponse;unit.io.cancel:=io.cancel
  unit.io.invalidate:=io.invalidate;io.busy:=unit.io.busy
}

class TextureImageRegionCopyUnitSpec extends AnyFlatSpec {
  import WindowRecordTest._
  private case class View(target: Int=0,base: BigInt=0x100000,width: Int=8,height: Int=8,depth: Int=1,layers: Int=1,
    row: BigInt=64,slice: BigInt=0,layerPitch: BigInt=0,bytes: Int=1,bufferTexels: Int=0,bufferBytes: BigInt=0) {
    def allocationBase: BigInt=base& ~BigInt(0xfffff)
    def allocationLimit: BigInt=allocationBase+0x100000
    def address(x: Int,y: Int,z: Int=0,layer: Int=0): BigInt=
      base+x*bytes+y*row+z*slice+layer*layerPitch
  }
  private class Driver(val d: RegionCopyHarness) {
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    val memory=new WindowRecordTest.Memory(d.io.memoryRequest.bits,d.io.memoryResponse.bits,
      d.io.memoryRequest.valid,d.io.memoryRequest.ready,d.io.memoryResponse.valid,d.io.memoryResponse.ready)
    memory.beforeRequest=r=>{assert(r.address%64==0,"copy line address must be aligned")
      assert(d.io.memoryRequest.bits.sizeLog2.peek().litValue==6)
      assert(d.io.memoryRequest.bits.cachePolicy.peek().litValue==CachePolicy.uncached.litValue)
      if(r.write)assert(r.mask!=0,"write mask must select bytes")else assert(r.mask==(BigInt(1)<<64)-1)}
    var seenBase=0
    def tick(): Unit={memory.drive();memory.capture();d.clock.step()}
    def until(p: =>Boolean,limit: Int=20000): Unit={var n=0;while(!p && n<limit){tick();n+=1};assert(p,s"region copy timeout after $n cycles")}
    def payload(v: View,x: Int,y: Int,z: Int,layer: Int,value: BigInt): Unit={
      val a=v.address(x,y,z,layer);for(i<-0 until v.bytes)memory.bytes(a+i)=((value>>(8*i))&255).toInt}
    def query(source: View,destination: View,format: Int,sourceX: Int=0,sourceY: Int=0,sourceZ: Int=0,sourceLayer: Int=0,
      destinationX: Int=0,destinationY: Int=0,destinationZ: Int=0,destinationLayer: Int=0,
      width: Int=1,height: Int=1,depth: Int=1,layers: Int=1,tag: BigInt=0x434f5059L): Unit={
      val q=d.io.in.bits;zero(q);q.tag.poke(tag.U);q.format.poke(format.U)
      for((v,o)<-Seq(source->q.source,destination->q.destination)){
        o.target.poke(v.target.U);o.level.poke(0.U);o.allocationBase.poke(v.allocationBase.U)
        o.allocationLimit.poke(v.allocationLimit.U);o.base.poke(v.base.U)
        o.texWidth.poke(v.width.U);o.texHeight.poke(v.height.U);o.texDepth.poke(v.depth.U);o.layers.poke(v.layers.U)
        o.rowPitch.poke(v.row.U);o.slicePitch.poke(v.slice.U);o.layerPitch.poke(v.layerPitch.U)
        o.block4x4.poke(false.B);o.blockBytes.poke(v.bytes.U)
        o.bufferTexels.poke(v.bufferTexels.U);o.bufferBytes.poke(v.bufferBytes.U)
      }
      q.sourceX.poke(sourceX.U);q.sourceY.poke(sourceY.U);q.sourceZ.poke(sourceZ.U);q.sourceLayer.poke(sourceLayer.U)
      q.destinationX.poke(destinationX.U);q.destinationY.poke(destinationY.U)
      q.destinationZ.poke(destinationZ.U);q.destinationLayer.poke(destinationLayer.U)
      q.regionWidth.poke(width.U);q.regionHeight.poke(height.U);q.regionDepth.poke(depth.U);q.regionLayers.poke(layers.U)
      seenBase=memory.seen.size
      d.io.in.valid.poke(true.B);until(d.io.in.ready.peek().litToBoolean);tick();d.io.in.valid.poke(false.B);zero(q)
    }
    def result(status: Int=0,texels: Int,copiedBytes: Int): Unit={
      until(d.io.out.valid.peek().litToBoolean);val r=d.io.out.bits
      r.status.expect(status.U);r.texels.expect(texels.U);r.bytes.expect(copiedBytes.U)
      val delta=memory.seen.drop(seenBase)
      val writes=delta.count(_.write)
      r.readLines.expect((delta.size-writes).U)
      r.writeLines.expect(writes.U)
      assert(memory.pending.isEmpty && memory.reply.isEmpty)
      d.io.out.ready.poke(true.B);tick();d.io.out.ready.poke(false.B);d.io.busy.expect(false.B)
    }
    def expectCopy(source: View,destination: View,sourceX: Int,sourceY: Int,destinationX: Int,destinationY: Int,
      width: Int,height: Int,sourceZ: Int=0,sourceLayer: Int=0,destinationZ: Int=0,destinationLayer: Int=0,
      depth: Int=1,layers: Int=1): Unit={
      for(y<-0 until height;x<-0 until width;z<-0 until depth;l<-0 until layers){
        val sz=if(source.target==3)sourceZ+z else 0;val sl=if(source.target==1)sourceLayer+l else 0
        val dz=if(destination.target==3)destinationZ+z else 0;val dl=if(destination.target==1)destinationLayer+l else 0
        val a=source.address(sourceX+x,sourceY+y,sz,sl);val b=destination.address(destinationX+x,destinationY+y,dz,dl)
        for(i<-0 until source.bytes)assert(memory.bytes.getOrElse(b+i,0)==memory.bytes.getOrElse(a+i,0),
          s"copy byte mismatch x=$x y=$y z=$z layer=$l byte=$i")}
    }
  }
  behavior of "TextureImageRegionCopyUnit"
  it should "copy packed texels between independent 2D levels with exact line traffic" in {
    simulate(new RegionCopyHarness){d=>val t=new Driver(d)
      val format=TextureImageFormat.id("R8");val source=View(base=0x10003c,width=9,height=7,row=67)
      val destination=View(base=0x120005,width=10,height=7,row=71)
      for(y<-0 until source.height;x<-0 until source.width)t.payload(source,x,y,0,0,BigInt((x*37+y*101)&0xff))
      for(y<-0 until destination.height;x<-0 until destination.width)t.payload(destination,x,y,0,0,0xee)
      t.query(source,destination,format,sourceX=1,sourceY=2,destinationX=3,destinationY=1,width=7,height=5)
      t.result(0,35,35);t.expectCopy(source,destination,1,2,3,1,7,5)
      for(y<-0 until source.height;x<-0 until source.width){
        val a=source.address(x,y);assert(t.memory.bytes(a)==((x*37+y*101)&0xff),"source region changed")}
      info("IMAGE_REGION_COPY_2D texels=35 r8=true crossingLines=true exactLineTraffic=true sourceUnchanged=true")
    }
  }
  it should "copy four-byte and twelve-byte texels across rows and line boundaries" in {
    simulate(new RegionCopyHarness){d=>val t=new Driver(d)
      for((name,width,height,row)<-Seq(("RGBA8",5,4,37),("RGB32F",3,2,101))){
        val format=TextureImageFormat.id(name);val bytes=TextureImageFormat.formats(format).bytes
        val source=View(base=0x10003d,width=8,height=6,row=BigInt(row),bytes=bytes)
        val destination=View(base=0x140031,width=8,height=6,row=BigInt(row+5),bytes=bytes)
        for(y<-0 until source.height;x<-0 until source.width){
          var value=BigInt(0);for(i<-0 until bytes)value|=BigInt((x*53+y*29+i*7)&0xff)<<(8*i)
          t.payload(source,x,y,0,0,value);t.payload(destination,x,y,0,0,0xee)}
        t.query(source,destination,format,sourceX=1,sourceY=1,destinationX=2,destinationY=2,width=width,height=height)
        t.result(0,width*height,width*height*bytes);t.expectCopy(source,destination,1,1,2,2,width,height)
      }
      info("IMAGE_REGION_COPY_PACKED rgba8=true rgb32f=true rowPitchPadded=true crossLineBothWays=true")
    }
  }
  it should "copy 2D-array layers and 3D slices along their own pitches" in {
    simulate(new RegionCopyHarness){d=>val t=new Driver(d)
      val arrayFormat=TextureImageFormat.id("RGBA8")
      val arraySource=View(target=1,base=0x100011,width=6,height=5,layers=3,row=29,layerPitch=BigInt(5*29+13),bytes=4)
      val arrayDestination=View(target=1,base=0x150023,width=6,height=5,layers=3,row=31,layerPitch=BigInt(5*31+9),bytes=4)
      for(l<-0 until 3;y<-0 until 5;x<-0 until 6){
        var v=BigInt(0);for(i<-0 until 4)v|=BigInt((x+y*7+l*31+i*11)&0xff)<<(8*i)
        t.payload(arraySource,x,y,0,l,v);t.payload(arrayDestination,x,y,0,l,0xee)}
      t.query(arraySource,arrayDestination,arrayFormat,sourceX=1,sourceY=1,sourceLayer=0,
        destinationX=2,destinationY=0,destinationLayer=1,width=4,height=3,layers=2)
      t.result(0,24,96);t.expectCopy(arraySource,arrayDestination,1,1,2,0,4,3,sourceLayer=0,destinationLayer=1,layers=2)
      val volumeFormat=TextureImageFormat.id("R8")
      val volumeSource=View(target=3,base=0x18000f,width=6,height=5,depth=4,row=19,slice=BigInt(5*19+7))
      val volumeDestination=View(target=3,base=0x1a0007,width=6,height=5,depth=4,row=17,slice=BigInt(5*17+3))
      for(z<-0 until 4;y<-0 until 5;x<-0 until 6){
        t.payload(volumeSource,x,y,z,0,BigInt((x*13+y*41+z*97)&0xff));t.payload(volumeDestination,x,y,z,0,0xee)}
      t.query(volumeSource,volumeDestination,volumeFormat,sourceX=1,sourceY=2,sourceZ=0,
        destinationX=0,destinationY=1,destinationZ=1,width=5,height=3,depth=3)
      t.result(0,45,45);t.expectCopy(volumeSource,volumeDestination,1,2,0,1,5,3,sourceZ=0,destinationZ=1,depth=3)
      info("IMAGE_REGION_COPY_LAYERED arrayLayers2=true volumeSlices3=true perPlanePitch=true")
    }
  }
  it should "copy one-dimensional buffer texels including unaligned twelve-byte spans" in {
    simulate(new RegionCopyHarness){d=>val t=new Driver(d)
      for(name<-Seq("R8","RGBA8","RGB32F")){
        val format=TextureImageFormat.id(name);val bytes=TextureImageFormat.formats(format).bytes
        val count=9
        val source=View(target=5,base=0x10003f,width=1,height=1,row=0,bytes=bytes,
          bufferTexels=count,bufferBytes=BigInt(count*bytes))
        val destination=View(target=5,base=0x140013,width=1,height=1,row=0,bytes=bytes,
          bufferTexels=count,bufferBytes=BigInt(count*bytes))
        for(x<-0 until count){var v=BigInt(0);for(i<-0 until bytes)v|=BigInt((x*47+i*13)&0xff)<<(8*i)
          t.payload(source,x,0,0,0,v);t.payload(destination,x,0,0,0,0xee)}
        t.memory.seen.clear()
        t.query(source,destination,format,sourceX=1,destinationX=3,width=6)
        t.result(0,6,6*bytes);t.expectCopy(source,destination,1,0,3,0,6,1)
      }
      info("IMAGE_REGION_COPY_BUFFER r8=true rgba8=true rgb32f=true unalignedBases=true sixTexelsEach=true")
    }
  }
  it should "reject mismatched views formats targets and oversized regions before any memory access" in {
    simulate(new RegionCopyHarness){d=>val t=new Driver(d)
      val source=View(base=0x100000,width=8,height=8,row=64);val destination=View(base=0x120000,width=8,height=8,row=64)
      val format=TextureImageFormat.id("R8");val seen0=t.memory.seen.size
      t.query(source,destination.copy(target=2),format,width=2,height=2);t.result(1,0,0)
      t.query(source,destination,TextureImageFormat.id("RGBA8"),width=1,height=1);t.result(1,0,0)
      t.query(source,destination.copy(bytes=2),format,width=1,height=1);t.result(1,0,0)
      t.query(source,View(target=1,base=0x120000,width=8,height=8,layers=1,row=64,layerPitch=64),format,width=1,height=1);t.result(1,0,0)
      t.query(source,destination,format,sourceX=7,destinationX=7,width=2,height=1);t.result(2,0,0)
      t.query(source,destination,format,width=65,height=65);t.result(2,0,0)
      t.query(source,destination,format,sourceZ=1,width=1,height=1);t.result(2,0,0)
      t.query(source,destination,format,width=1,height=1,layers=2);t.result(2,0,0)
      val bufferSource=View(target=5,base=0x100000,width=1,height=1,row=0,bufferTexels=4,bufferBytes=4)
      t.query(bufferSource,View(target=5,base=0x120000,width=1,height=1,row=0,bufferTexels=4,bufferBytes=4),
        format,width=5);t.result(2,0,0)
      t.query(bufferSource,destination,format,width=1);t.result(1,0,0)
      assert(t.memory.seen.size==seen0,"rejected copies must not touch memory")
      info("IMAGE_REGION_COPY_REJECT views5=true bounds4=true targetMismatch=true noMemoryBeforeAdmission=true")
    }
  }
  it should "report source and destination line faults and reuse the owner" in {
    simulate(new RegionCopyHarness){d=>val t=new Driver(d)
      val format=TextureImageFormat.id("RGBA8");val source=View(base=0x10003c,width=4,height=4,row=19,bytes=4)
      val destination=View(base=0x130005,width=4,height=4,row=19,bytes=4)
      for(y<-0 until 4;x<-0 until 4){t.payload(source,x,y,0,0,BigInt(x+y*4));t.payload(destination,x,y,0,0,0xee)}
      def once(fault: BigInt,sourceFault: Boolean): Unit={
        t.memory.faultAt=Set(fault)
        t.query(source,destination,format,width=2,height=2);t.until(d.io.out.valid.peek().litToBoolean)
        val r=d.io.out.bits;r.status.expect(5.U);r.faultAddressValid.expect(true.B);r.faultAddress.expect(fault.U)
        r.faultSource.expect((if(sourceFault)1 else 2).U)
        t.memory.faultAt=Set.empty
        d.io.out.ready.poke(true.B);t.tick();d.io.out.ready.poke(false.B);t.memory.seen.clear()}
      once((source.address(0,0)>>6)<<6,sourceFault=true)
      once((destination.address(0,0)>>6)<<6,sourceFault=false)
      t.query(source,destination,format,width=2,height=2);t.result(0,4,16)
      t.expectCopy(source,destination,0,0,0,0,2,2)
      info("IMAGE_REGION_COPY_FAULT sourceLine=true destinationLine=true exactAddress=true healthyReuse=true")
    }
  }
  it should "hold completion under backpressure and cancel into a drained result" in {
    simulate(new RegionCopyHarness){d=>val t=new Driver(d)
      val format=TextureImageFormat.id("R8");val source=View(base=0x100000,width=4,height=4,row=64)
      val destination=View(base=0x120000,width=4,height=4,row=64)
      for(y<-0 until 4;x<-0 until 4)t.payload(source,x,y,0,0,BigInt(x*3+y))
      t.query(source,destination,format,width=3,height=3);t.until(d.io.out.valid.peek().litToBoolean)
      val held=d.io.out.bits.peek().litValue
      for(_<-0 until 5){t.tick();assert(d.io.out.bits.peek().litValue==held);d.io.in.ready.expect(false.B)}
      d.io.out.ready.poke(true.B);t.tick();d.io.out.ready.poke(false.B)
      t.memory.seen.clear()
      t.query(source,destination,format,width=3,height=3);d.io.cancel.poke(true.B)
      t.until(d.io.out.valid.peek().litToBoolean);d.io.out.bits.status.expect(4.U);d.io.out.bits.texels.expect(0.U)
      d.io.cancel.poke(false.B);d.io.out.ready.poke(true.B);t.tick();d.io.out.ready.poke(false.B)
      t.query(source,destination,format,width=1,height=1);t.result(0,1,1)
      info("IMAGE_REGION_COPY_HOLD immutable=true cancelStatus4=true drainedReuse=true")
    }
  }
}
