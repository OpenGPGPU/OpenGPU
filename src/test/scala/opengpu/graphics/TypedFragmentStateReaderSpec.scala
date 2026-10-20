package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class TypedFragmentStateReaderSpec extends AnyFlatSpec {
  import WindowRecordTest.{zero,Memory}
  private val cfg=GpuConfig(lanes=4,warps=2)
  private val f=FragmentShaderConfig(2,4);private val g=GraphicsConfig(screenWidth=16,screenHeight=16)
  private class Driver(val d: TypedFragmentStateReader) {
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    val memory=new Memory(d.io.memoryRequest.bits,d.io.memoryResponse.bits,d.io.memoryRequest.valid,d.io.memoryRequest.ready,
      d.io.memoryResponse.valid,d.io.memoryResponse.ready)
    val returned=mutable.ArrayBuffer.empty[BigInt];var peak=0
    val fields=TypedFragmentStateLayout.fields(d.io.result.bits.state)
    def tick(): Unit={memory.drive();if(memory.reply.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean)returned+=memory.reply.get.address
      peak=peak.max(memory.pending.size+memory.reply.size);memory.capture();d.clock.step()}
    def until(condition: =>Boolean,limit: Int=5000): Unit={var n=0;while(!condition && n<limit){tick();n+=1};assert(condition,s"state reader timeout n=$n")}
    def contents: Map[String,BigInt]=Map[String,BigInt](
      "version"->1,"engineCount"->2,"fragment.version"->2,"fragment.identity.context"->7,"fragment.identity.pass"->8,"fragment.identity.generation"->9,
      "fragment.shaderPc"->0x1000,"fragment.varyingSlots"->2,"fragment.colorSlots"->4,"fragment.colorMask"->15,
      "fragment.allowDepthOverride"->1,"fragment.sampleMode"->2,"fragment.sampler.bias"-> -5,
      "fragment.scratch.base"->0x100000,"fragment.scratch.limit"->0x104000,"fragment.scratch.allocationBase"->0x100000,"fragment.scratch.allocationLimit"->0x104000,
      "scratch[0].base"->0x120000,"scratch[0].limit"->0x124000,"scratch[0].allocationBase"->0x120000,"scratch[0].allocationLimit"->0x124000,
      "tests.version"->1,"tests.depthEnable"->1,"tests.depthWrite"->1,"tests.depthFunction"->1,"tests.stencilEnable"->1,
      "tests.front.function"->6,"tests.front.reference"->17,"tests.front.readMask"->255,"tests.front.writeMask"->255,"tests.front.depthPass"->2,
      "tests.back.function"->6,"tests.back.reference"->5,"blend.enable"->1,"blend.version"->1,"blend.srcRGB"->1,"blend.srcAlpha"->1,
      "blend.constant[0]"->BigInt("3f000000",16),"blend.constant[1]"->BigInt("3e800000",16),"blend.constant[3]"->BigInt("3f800000",16))
    def install(base: BigInt=0x2000,handle: BigInt=0,values: Map[String,BigInt]=contents): Unit={
      val address=base+handle*512;for(w<-0 until 128)memory.putWord(address+w*4,0)
      for((v,w)<-Vector[BigInt](TypedFragmentStateLayout.magic,512,1,4,7,8,9,handle).zipWithIndex)memory.putWord(address+w*4,v)
      for(field<-fields){val raw=values.getOrElse(field.name,BigInt(0))&((BigInt(1)<<(field.words*32))-1)
        for(w<-0 until field.words)memory.putWord(address+(field.word+w)*4,(raw>>(w*32))&0xffffffffL)}
    }
    def lookup(base: BigInt=0x2000,limit: BigInt=0x2200,handle: BigInt=0,version: Int=1): Unit={
      val q=d.io.lookup.bits;q.arena.version.poke(version.U);q.arena.identity.context.poke(7.U);q.arena.identity.pass.poke(8.U);q.arena.identity.generation.poke(9.U)
      q.arena.base.poke(base.U);q.arena.limit.poke(limit.U);q.handle.poke(handle.U);d.io.lookup.valid.poke(true.B)
      until(d.io.lookup.ready.peek().litToBoolean);tick();d.io.lookup.valid.poke(false.B)
    }
    def check(values: Map[String,BigInt]=contents): Unit={
      for(field<-fields){val raw=values.getOrElse(field.name,BigInt(0))&((BigInt(1)<<field.width)-1)
        val expected=field.value match{case _: SInt if raw.testBit(field.width-1)=>raw-(BigInt(1)<<field.width);case _=>raw}
        assert(field.value.peek().litValue==expected,s"state field ${field.name}")}
    }
    def result(status: Int,hit: Boolean=false): Unit={until(d.io.result.valid.peek().litToBoolean);d.io.result.bits.status.expect(status.U)
      d.io.result.bits.hit.expect(hit.B);d.io.result.bits.stateValid.expect((status==0).B)
      if(status!=0)assert(WindowRecordTest.values(d.io.result.bits.state).values.forall(_==0))
      assert(memory.pending.isEmpty && memory.reply.isEmpty);d.io.result.ready.poke(true.B);tick();d.io.result.ready.poke(false.B)}
    def invalidate(): Unit={d.io.invalidate.poke(true.B);tick();d.io.invalidate.poke(false.B)}
  }
  behavior of "TypedFragmentStateReader"
  it should "decode centroid versions and all sixty four qualifier bits without shifting legacy state" in {
    simulate(new TypedFragmentStateReader(cfg,FragmentShaderConfig(),g)){d=>val t=new Driver(d)
      val mask=(BigInt(1)<<63)|(BigInt(1)<<32)|1
      assert(t.fields.find(_.name=="fragment.centroidMask").get.word==124)
      for(version<-Seq(3,4)){
        val values=t.contents++Map("fragment.version"->BigInt(version),"fragment.varyingSlots"->BigInt(16),"fragment.centroidMask"->mask)
        t.install(values=values);t.invalidate();t.lookup();t.until(d.io.result.valid.peek().litToBoolean);t.check(values);t.result(0)
      }
      for(change<-Seq(Map("fragment.version"->BigInt(1),"fragment.centroidMask"->BigInt(1)),
        Map("fragment.version"->BigInt(2),"fragment.centroidMask"->BigInt(1)),
        Map("fragment.version"->BigInt(3),"fragment.centroidMask"->BigInt(256)),Map("fragment.version"->BigInt(5)))){
        t.install(values=t.contents++change);t.invalidate();t.lookup();t.result(1)
      }
      val bounded=t.contents++Map("fragment.version"->BigInt(4),"fragment.varyingSlots"->BigInt(16),
        "fragment.centroidMask"->mask,"fragment.scratch.limit"->BigInt(0x100000+2816))
      t.install(values=bounded);t.invalidate();t.lookup();t.result(2)
      t.install();t.invalidate();t.lookup();t.until(d.io.result.valid.peek().litToBoolean);t.check();t.result(0)
      info("CENTROID_STATE versions3And4=true maskBits64=true highWord=true oldVersionsRequireZero=true activeSlotBounds=true pointCoordPrefixBounds=true exactLegacyReuse=true")
    }
  }
  it should "fetch four reordered tags publish complete state and reuse only an exact immutable cache key" in {
    simulate(new TypedFragmentStateReader(cfg,f,g)){d=>val t=new Driver(d);t.install();t.memory.holdResponses=true;t.lookup()
      t.until(t.memory.seen.size==4);for(_<-0 until 8)t.tick();d.io.result.valid.expect(false.B)
      d.io.lookup.bits.arena.identity.context.poke(99.U);d.io.lookup.bits.handle.poke(17.U)
      t.memory.holdResponses=false;t.until(d.io.result.valid.peek().litToBoolean);t.check();d.io.result.bits.readLines.expect(8.U)
      assert(t.returned.head==0x20c0 && t.peak==4);t.result(0)
      val count=t.memory.seen.size;t.lookup();t.until(d.io.result.valid.peek().litToBoolean);t.check();d.io.result.bits.readLines.expect(0.U)
      val held=d.io.result.bits.peek().litValue;d.io.cancel.poke(true.B)
      for(_<-0 until 6){t.tick();assert(d.io.result.bits.peek().litValue==held)}
      t.result(0,hit=true);d.io.cancel.poke(false.B);assert(t.memory.seen.size==count)
      val changed=t.contents.updated("fragment.shaderPc",BigInt(0x3000));t.install(values=changed);t.invalidate();t.lookup()
      t.until(d.io.result.valid.peek().litToBoolean);t.check(changed);t.result(0);assert(t.memory.seen.size==count+8)
      info(s"TYPED_FRAGMENT_STATE tags=4 reordered=true fields=${t.fields.size} bytes=512 cacheHit=true explicitInvalidation=true heldResultStable=true")
    }
  }
  it should "accept a final state ending at 4 GiB and reject invalid envelopes without memory traffic" in {
    simulate(new TypedFragmentStateReader(cfg,f,g)){d=>val t=new Driver(d);val high=(BigInt(1)<<32)-512;t.install(base=high)
      t.lookup(high,BigInt(1)<<32);t.until(d.io.result.valid.peek().litToBoolean);t.check();t.result(0)
      val count=t.memory.seen.size
      for((base,limit,handle,version)<-Seq((high,BigInt(1)<<32,BigInt(1),1),(BigInt(0x2001),BigInt(0x2240),BigInt(0),1),
        (BigInt(0x2000),BigInt(0x21c0),BigInt(0),1),(BigInt(0x2000),BigInt(0x2200),BigInt("ffffffff",16),1),
        (BigInt(0x2000),BigInt(0x2200),BigInt(0),0))){t.lookup(base,limit,handle,version);t.result(1)}
      assert(t.memory.seen.size==count)
    }
  }
  it should "reject reserved words malformed headers and noncanonical signed or unsigned padding" in {
    simulate(new TypedFragmentStateReader(cfg,f,g)){d=>val t=new Driver(d)
      val bias=t.fields.find(_.name=="fragment.sampler.bias").get.word
      val version=t.fields.find(_.name=="fragment.version").get.word
      for((word,bits)<-Seq(0->BigInt(0),1->BigInt(64),3->BigInt(2),4->BigInt(17),7->BigInt(1),8->BigInt(1),127->BigInt(1),
        bias->BigInt(27),version->BigInt(0x102))){t.install();t.memory.putWord(0x2000+word*4,bits);t.lookup();t.result(7)}
      t.install();t.lookup();t.result(0)
    }
  }
  it should "validate every active engine scratch view and reject aliasing inactive garbage and shader bounds" in {
    simulate(new TypedFragmentStateReader(cfg,f,g)){d=>val t=new Driver(d)
      val invalid=Seq(
        Map("engineCount"->BigInt(1)),Map("scratch[1].base"->BigInt(0x130000)),
        Map("scratch[0].base"->BigInt(0x100000),"scratch[0].limit"->BigInt(0x104000),"scratch[0].allocationBase"->BigInt(0x100000),"scratch[0].allocationLimit"->BigInt(0x104000)),
        Map("fragment.scratch.base"->BigInt(0x2000),"fragment.scratch.limit"->BigInt(0x4000),"fragment.scratch.allocationBase"->BigInt(0x2000),"fragment.scratch.allocationLimit"->BigInt(0x4000)),
        Map("fragment.identity.pass"->BigInt(9)),Map("fragment.colorTypes[0]"->BigInt(3)),Map("tests.version"->BigInt(0)))
      for(change<-invalid){t.install(values=t.contents++change);t.lookup();t.result(1)}
      t.install(values=t.contents.updated("scratch[0].limit",BigInt(0x120040)));t.lookup();t.result(2)
      t.install();t.lookup();t.result(0)
    }
  }
  it should "drain faulted and cancelled requests before reuse without caching partial state" in {
    simulate(new TypedFragmentStateReader(cfg,f,g)){d=>val t=new Driver(d)
      for(line<-Seq(0,3,7)){t.install();t.invalidate();t.memory.faultAt=Set(BigInt(0x2000+line*64));t.lookup();t.result(3)
        t.memory.faultAt=Set.empty;t.lookup();t.until(d.io.result.valid.peek().litToBoolean);t.check();t.result(0)}
      t.invalidate();t.memory.holdRequests=true;t.lookup();t.until(d.io.memoryRequest.valid.peek().litToBoolean)
      val held=d.io.memoryRequest.bits.peek().litValue;d.io.cancel.poke(true.B)
      for(_<-0 until 6){t.tick();d.io.memoryRequest.valid.expect(true.B);assert(d.io.memoryRequest.bits.peek().litValue==held);d.io.result.valid.expect(false.B)}
      t.memory.holdResponses=true;t.memory.holdRequests=false;t.until(t.memory.pending.nonEmpty)
      for(_<-0 until 6){t.tick();d.io.result.valid.expect(false.B)}
      t.memory.holdResponses=false;t.result(4);d.io.cancel.poke(false.B);t.lookup();t.result(0)
      t.invalidate();t.memory.holdResponses=true;val count=t.memory.seen.size;t.lookup();t.until(t.memory.seen.size==count+4)
      d.io.cancel.poke(true.B);for(_<-0 until 6)t.tick();d.io.result.valid.expect(false.B)
      t.memory.holdResponses=false;t.result(4);d.io.cancel.poke(false.B);t.lookup();t.result(0)
    }
  }
  it should "prevent an invalidated in-flight fill from repopulating the cache" in {
    simulate(new TypedFragmentStateReader(cfg,f,g)){d=>val t=new Driver(d);t.install();t.memory.holdResponses=true;t.lookup()
      t.until(t.memory.seen.size==4);t.invalidate();t.memory.holdResponses=false;t.result(0)
      val count=t.memory.seen.size;t.lookup();t.result(0);assert(t.memory.seen.size==count+8)
      t.lookup();t.result(0,hit=true)
    }
  }
}
