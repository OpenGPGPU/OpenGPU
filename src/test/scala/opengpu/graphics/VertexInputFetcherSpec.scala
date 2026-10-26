package opengpu.graphics

import chisel3._
import opengpu.config.CachePolicy
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class VertexInputFetcherSpec extends AnyFlatSpec {
  private val u32=(BigInt(1)<<32)-1
  private case class View(allocationBase: BigInt,allocationLimit: BigInt,base: BigInt,limit: BigInt)
  private case class Buffer(view: View,stride: BigInt,divisor: BigInt=0)
  private case class Attribute(binding: Int=0,components: Int=4,format: Int=0,offset: Int=0,
    enabled: Boolean=true,constant: Vector[BigInt]=Vector(0,0,0,BigInt("3f800000",16)))
  private case class Draw(count: Int=3,instances: Int=1,firstVertex: BigInt=0,firstIndex: BigInt=0,
    baseVertex: BigInt=0,baseInstance: BigInt=0,indexFormat: Int=0,restart: Boolean=false,
    indices: View=View(0,0,0,0),buffers: Vector[Buffer]=Vector.empty,
    attributes: Vector[Attribute]=Vector.empty,generation: Int=11,version: Int=1)
  private case class Record(ordinal: Int,vertex: BigInt,instance: Int,baseInstance: BigInt,
    restart: Boolean,values: Vector[Vector[BigInt]],generation: Int)
  private case class Summary(status: Int,vertices: BigInt,restarts: BigInt,indexLines: BigInt,
    attributeLines: BigInt,hits: BigInt,address: BigInt,attributeValid: Boolean,attribute: Int)
  private def view(base: BigInt,bytes: Int=256): View=View(base,base+bytes,base,base+bytes)
  private def size(format: Int): Int=VertexInputReference.bytes(format)

  private class Driver(val d: VertexInputFetcher,val p: VertexInputFetchConfig) {
    case class Reply(address: BigInt,due: Int,fault: Boolean)
    val memory=mutable.Map.empty[BigInt,Int]
    val requests=mutable.ArrayBuffer.empty[BigInt]
    val outputs=mutable.ArrayBuffer.empty[Record]
    var windows=Vector.empty[View]
    var cycle=0;var pending=Option.empty[Reply]
    var holdRequests=false;var holdResponses=false;var holdOutputs=false
    var faultLine=Option.empty[BigInt]
    private var generation=0
    private var heldRequest=Option.empty[BigInt];private var heldOutput=Option.empty[BigInt]
    private var heldCompletion=Option.empty[BigInt]
    d.io.draw.valid.poke(false.B);d.io.draw.bits.poke(0.U.asTypeOf(d.io.draw.bits))
    d.io.cancel.poke(false.B);d.io.out.ready.poke(false.B);d.io.completion.ready.poke(false.B)
    d.io.memoryRequest.ready.poke(false.B);d.io.memoryResponse.valid.poke(false.B)
    d.io.memoryResponse.bits.poke(0.U.asTypeOf(d.io.memoryResponse.bits))
    d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    def put(at: BigInt,value: BigInt,bytes: Int=4): Unit=
      for(i<-0 until bytes)memory(at+i)=((value>>(8*i))&255).toInt
    def get(at: BigInt,bytes: Int): BigInt=
      (0 until bytes).foldLeft(BigInt(0))((v,i)=>v|(BigInt(memory.getOrElse(at+i,0))<<(8*i)))
    def attribute(a: Draw,i: Int): Attribute=a.attributes.lift(i).getOrElse(Attribute(enabled=false))
    def expected(a: Draw): Vector[Record]=(for(instance<-0 until a.instances;ordinal<-0 until a.count)yield {
      val index=if(a.indexFormat==0)a.firstVertex+ordinal else
        get(a.indices.base+(a.firstIndex+ordinal)*(1<<(a.indexFormat-1)),1<<(a.indexFormat-1))
      val restart=a.indexFormat!=0 && a.restart && index==((BigInt(1)<<(8*(1<<(a.indexFormat-1))))-1)
      val vertex=if(restart)BigInt(0)else if(a.indexFormat==0)index else index+a.baseVertex
      val values=Vector.tabulate(p.attributes) { i=>
        val field=attribute(a,i)
        if(restart)Vector.fill(4)(BigInt(0))else if(!field.enabled)field.constant else {
          val binding=a.buffers(field.binding);val bytes=size(field.format)
          val element=if(binding.divisor==0)vertex else a.baseInstance+BigInt(instance)/binding.divisor
          Vector.tabulate(4) { c=>
            if(c>=field.components)if(c==3){if(field.format==0 || field.format>=7)BigInt("3f800000",16)else BigInt(1)}else BigInt(0)
            else {
              val raw=get(binding.view.base+element*binding.stride+field.offset+(if(VertexInputReference.packed(field.format))0 else c*bytes),bytes)
              VertexInputReference.decode(raw,field.format,c)
            }
          }
        }
      }
      Record(ordinal,vertex,instance,a.baseInstance,restart,values,a.generation)
    }).toVector
    private def pokeView(port: VertexInputView,v: View): Unit={
      port.allocationBase.poke(v.allocationBase.U);port.allocationLimit.poke(v.allocationLimit.U)
      port.base.poke(v.base.U);port.limit.poke(v.limit.U)
    }
    def start(a: Draw): Unit={
      d.io.draw.ready.expect(true.B);outputs.clear();requests.clear();faultLine=None;generation=a.generation
      d.io.draw.bits.poke(0.U.asTypeOf(d.io.draw.bits));val b=d.io.draw.bits
      b.version.poke(a.version.U);b.identity.context.poke(3.U);b.identity.pass.poke(7.U);b.identity.generation.poke(a.generation.U)
      b.count.poke(a.count.U);b.instances.poke(a.instances.U);b.firstVertex.poke(a.firstVertex.U)
      b.firstIndex.poke(a.firstIndex.U);b.baseVertex.poke(a.baseVertex.S);b.baseInstance.poke(a.baseInstance.U)
      b.indexFormat.poke(a.indexFormat.U);b.restart.poke(a.restart.B);pokeView(b.indices,a.indices)
      for((buffer,i)<-a.buffers.zipWithIndex) {
        pokeView(b.bindings(i),buffer.view);b.bindings(i).stride.poke(buffer.stride.U);b.bindings(i).divisor.poke(buffer.divisor.U)
      }
      for(i<-0 until p.attributes) {
        val field=attribute(a,i);val port=b.attributes(i)
        port.enabled.poke(field.enabled.B);port.bindingIndex.poke(field.binding.U)
        port.components.poke(field.components.U);port.format.poke(field.format.U);port.offset.poke(field.offset.U)
        field.constant.zipWithIndex.foreach { case (v,k)=>port.constant(k).poke(v.U) }
      }
      windows=a.buffers.map(_.view)++(if(a.indexFormat!=0)Vector(a.indices)else Vector.empty)
      d.io.draw.valid.poke(true.B);step();d.io.draw.valid.poke(false.B)
      // Every subsequent fetch and record must use the accepted snapshot.
      d.io.draw.bits.poke(0.U.asTypeOf(d.io.draw.bits))
    }
    def step(): Unit={
      val requestReady= !holdRequests && cycle%5!=1
      val outputReady= !holdOutputs && cycle%7>=2
      d.io.memoryRequest.ready.poke(requestReady.B);d.io.out.ready.poke(outputReady.B)
      val response=pending.filter(r=>r.due<=cycle && !holdResponses)
      d.io.memoryResponse.valid.poke(response.nonEmpty.B)
      response.foreach { r=>
        d.io.memoryResponse.bits.readData.poke(get(r.address,64).U)
        d.io.memoryResponse.bits.fault.poke(r.fault.B);d.io.memoryResponse.bits.transactionId.poke(0.U)
      }
      def stable(valid: Bool,bits: Data,old: Option[BigInt]): Unit=old.foreach { value=>
        valid.expect(true.B);assert(bits.peek().litValue==value,"an irrevocable offer changed")
      }
      stable(d.io.memoryRequest.valid,d.io.memoryRequest.bits,heldRequest)
      stable(d.io.out.valid,d.io.out.bits,heldOutput)
      stable(d.io.completion.valid,d.io.completion.bits,heldCompletion)
      heldRequest=if(d.io.memoryRequest.valid.peek().litToBoolean && !requestReady)Some(d.io.memoryRequest.bits.peek().litValue)else None
      heldOutput=if(d.io.out.valid.peek().litToBoolean && !outputReady)Some(d.io.out.bits.peek().litValue)else None
      heldCompletion=if(d.io.completion.valid.peek().litToBoolean && !d.io.completion.ready.peek().litToBoolean)
        Some(d.io.completion.bits.peek().litValue)else None
      if(response.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean)pending=None
      if(d.io.memoryRequest.valid.peek().litToBoolean && requestReady) {
        val r=d.io.memoryRequest.bits;val at=r.address.peek().litValue
        assert(pending.isEmpty);assert(at%64==0 && windows.exists(v=>at>=v.allocationBase && at+64<=v.allocationLimit))
        r.isWrite.expect(false.B);r.sizeLog2.expect(6.U);r.transactionId.expect(0.U);r.cachePolicy.expect(CachePolicy.cached)
        assert(Set(1,2,4)(r.byteMask.peek().litValue.bitCount))
        requests+=at;pending=Some(Reply(at,cycle+3+(at/64%4).toInt,faultLine.contains(at)))
      }
      if(d.io.out.valid.peek().litToBoolean && outputReady) {
        val r=d.io.out.bits;r.identity.context.expect(3.U);r.identity.pass.expect(7.U)
        outputs+=Record(r.ordinal.peek().litValue.toInt,r.vertexId.peek().litValue,r.instanceId.peek().litValue.toInt,
          r.baseInstance.peek().litValue,r.restart.peek().litToBoolean,
          r.values.map(_.map(_.peek().litValue).toVector).toVector,r.identity.generation.peek().litValue.toInt)
      }
      d.clock.step();cycle+=1
    }
    def until(condition: => Boolean): Unit={
      val start=cycle;while(!condition && cycle-start<30000)step()
      assert(condition,s"vertex fetch stalled after ${cycle-start} cycles")
    }
    def summary(expectedStatus: Int): Summary={
      until(d.io.completion.valid.peek().litToBoolean)
      d.io.out.valid.expect(false.B);assert(pending.isEmpty);d.io.memoryRequest.valid.expect(false.B)
      d.io.completion.bits.status.expect(expectedStatus.U);d.io.completion.bits.identity.context.expect(3.U)
      d.io.completion.bits.identity.pass.expect(7.U);d.io.completion.bits.identity.generation.expect(generation.U)
      val r=d.io.completion.bits
      val value=Summary(r.status.peek().litValue.toInt,r.vertices.peek().litValue,r.restarts.peek().litValue,
        r.indexLines.peek().litValue,r.attributeLines.peek().litValue,r.reusedReads.peek().litValue,
        r.faultAddress.peek().litValue,r.faultAttributeValid.peek().litToBoolean,r.faultAttribute.peek().litValue.toInt)
      for(_<-0 until 5){d.io.draw.ready.expect(false.B);step()}
      value
    }
    def consume(): Unit={
      d.io.completion.ready.poke(true.B);step();d.io.completion.ready.poke(false.B);d.io.busy.expect(false.B)
    }
  }

  it should "fetch all six product recipes from their uploaded vertex descriptors" in {
    val p=VertexInputFetchConfig(3,1)
    simulate(new VertexInputFetcher(p=p)){d=>val t=new Driver(d,p)
      for(scene<-NativeProductWorkloads.suite(32,32);draw<-scene.draws){
        val upload=NativeProductVertexInputs.encode(draw,0x10000)
        upload.bytes.zipWithIndex.foreach{case(value,i)=>t.put(upload.base+i,value & 255,1)}
        val prefix="draw.vertex.geometry.vertex.input."
        def f(key: String): BigInt=upload.fields(prefix+key)
        val fields=Vector.tabulate(3){slot=>val key=s"attributes[$slot]."
          Attribute(binding=f(key+"bindingIndex").toInt,components=f(key+"components").toInt,
            format=f(key+"format").toInt,offset=f(key+"offset").toInt)}
        val command=Draw(count=f("count").toInt,
          buffers=Vector(Buffer(View(f("bindings[0].allocationBase"),f("bindings[0].allocationLimit"),
            f("bindings[0].base"),f("bindings[0].limit")),f("bindings[0].stride"))),attributes=fields)
        t.start(command);t.summary(0)
        val expected=draw.triangles.flatMap(v=>Vector(v.a,v.b,v.c)).map{v=>
          Vector(Vector(v.x,v.y,v.z,v.w),Vector(v.u,v.v,0f,1f),draw.rgba).map(
            _.map(x=>BigInt(java.lang.Float.floatToRawIntBits(x).toLong & 0xffffffffL)))}
        assert(t.outputs.map(_.values).toVector==expected,s"${scene.id} GPU-fetched inputs differ")
        t.consume()
      }
    }
  }

  it should "retain indexed cache ownership when cancellation coincides with private address capture" in {
    val p=VertexInputFetchConfig(2,2)
    simulate(new VertexInputFetcher(p=p)){d=>val t=new Driver(d,p)
      val values=Vector(3,1,4,2,3,0,4,1)
      for(i<-0 until 8)t.put(0x9000+i*4,0x1200+i)
      for(format<-1 to 3;cut<-0 until 24){
        val bytes=1<<(format-1)
        values.zipWithIndex.foreach{case(value,i)=>t.put(0x8000+i*bytes,value,bytes)}
        val command=Draw(count=8,instances=2,indexFormat=format,indices=view(0x8000,64),
          buffers=Vector(Buffer(view(0x9000),4)),attributes=Vector(Attribute(components=1,format=1)),generation=100+format*100+cut)
        t.start(command);t.until(t.outputs.nonEmpty)
        for(_<-0 until cut)t.step()
        d.io.cancel.poke(true.B);t.step();d.io.cancel.poke(false.B)
        t.summary(VertexInputStatus.cancelled)
        assert(t.outputs.toVector==t.expected(command).take(t.outputs.size),s"indexed cancellation changed an accepted record format=$format cut=$cut")
        t.consume()
        val healthy=command.copy(count=3,instances=1,generation=500+format*100+cut)
        t.start(healthy);t.summary(0);assert(t.outputs.toVector==t.expected(healthy));t.consume()
      }
      info("VERTEX_INDEX_CAPTURE cancellationCuts72=true indexWidths8_16_32=true cachedPreparation=true acceptedPrefixStable=true ownedRequestsDrained=true healthyReuse=true noNewPhase=true")
    }
  }

  it should "fetch all sixteen attribute slots with typed defaults, signed expansion and draw snapshots" in {
    val p=VertexInputFetchConfig()
    simulate(new VertexInputFetcher(p=p)) { d=>
      val t=new Driver(d,p)
      val buffers=Vector.tabulate(16)(i=>Buffer(view(0x10000+i*0x1000),if(i<=2 || i==15)20 else if(i<=4)10 else 5))
      val fields=Vector.tabulate(16) { i=>
        if(i<=6 || i==15)Attribute(i,if(i==15)1 else Vector(3,2,4,3,2,4,1)(i),if(i==15)1 else i,size(if(i==15)1 else i))
        else Attribute(enabled=false,constant=Vector.tabulate(4)(c=>BigInt(0x11000000L+i*16+c)))
      }
      for(i<-0 until 16 if fields(i).enabled;v<-0 until 8;c<-0 until fields(i).components) {
        val f=fields(i);val raw=if(f.format==0)BigInt(0x3f000000L+v*65536+c*4096)
          else if(f.format==2 || f.format==4 || f.format==6)BigInt(-100+v*3+c)
          else BigInt(0x800000c0L+v*7+c)
        t.put(buffers(i).view.base+v*buffers(i).stride+f.offset+c*size(f.format),raw,size(f.format))
      }
      val command=Draw(count=4,firstVertex=1,buffers=buffers,attributes=fields)
      t.start(command);val result=t.summary(0);assert(t.outputs.toVector==t.expected(command))
      assert(result.vertices==4 && result.restarts==0 && result.indexLines==0 && result.hits>0)
      t.consume()
      t.put(buffers(15).view.base+buffers(15).stride+fields(15).offset,0x12345678)
      val next=command.copy(count=1,generation=12)
      t.start(next);t.summary(0);assert(t.outputs.toVector==t.expected(next));assert(t.requests.nonEmpty);t.consume()
      val probe=command.copy(count=1,generation=13,
        attributes=fields.zipWithIndex.map { case (a,i)=>if(i==15)a else a.copy(enabled=false) })
      t.start(probe);t.summary(0);assert(t.outputs.toVector==t.expected(probe));t.consume()
      t.put(buffers(15).view.base+buffers(15).stride+fields(15).offset,BigInt("87654321",16))
      val refreshed=probe.copy(generation=14)
      t.start(refreshed);t.summary(0);assert(t.outputs.toVector==t.expected(refreshed));assert(t.requests.nonEmpty);t.consume()
      info("VERTEX_INPUT attributes=16 formats=7 snapshots=true defaults=true signedExpansion=true freshDrawRefetch=true")
    }
  }

  it should "fetch byte halfword and word indices with restart before bias and independent instance divisors" in {
    val p=VertexInputFetchConfig(4,3)
    simulate(new VertexInputFetcher(p=p)) { d=>
      val t=new Driver(d,p)
      val buffers=Vector(Buffer(view(0x1000),16),Buffer(view(0x2000),4,2),Buffer(view(0x3000),4,1))
      val fields=Vector(Attribute(0),Attribute(1,1,1),Attribute(2,1,1),Attribute(enabled=false))
      for(v<-0 until 8;c<-0 until 4)t.put(0x1000+v*16+c*4,0x3f000000L+v*256+c)
      for(v<-0 until 8){t.put(0x2000+v*4,0x100+v);t.put(0x3000+v*4,0x200+v)}
      for(indexFormat<-1 to 3) {
        val bytes=1<<(indexFormat-1);val sentinel=(BigInt(1)<<(bytes*8))-1
        val indices=Vector(BigInt(99),BigInt(3),BigInt(1),sentinel,BigInt(3),BigInt(2),BigInt(1))
        indices.zipWithIndex.foreach { case (v,i)=>t.put(0x4000+i*bytes,v,bytes) }
        val command=Draw(count=6,instances=3,firstIndex=1,baseVertex= -1,baseInstance=2,
          indexFormat=indexFormat,restart=true,indices=view(0x4000),buffers=buffers,attributes=fields,generation=20+indexFormat)
        t.start(command);val result=t.summary(0);assert(t.outputs.toVector==t.expected(command))
        assert(result.vertices==15 && result.restarts==3 && result.indexLines==1 && result.hits>0);t.consume()
        val noRestart=command.copy(instances=1,restart=false)
        t.start(noRestart);val bad=t.summary(VertexInputStatus.bounds)
        assert(bad.vertices==2 && bad.restarts==0 && bad.attributeValid);t.consume()
      }
      for(divisor<-Vector(BigInt(3),u32)) {
        val command=Draw(count=2,instances=5,firstIndex=1,baseVertex=1,baseInstance=2,indexFormat=3,
          indices=view(0x4000),buffers=buffers.updated(1,buffers(1).copy(divisor=divisor)),attributes=fields)
        t.start(command);val result=t.summary(0);assert(t.outputs.toVector==t.expected(command))
        assert(result.vertices==10 && result.indexLines==1);t.consume()
      }
      info("VERTEX_INPUT indices=8,16,32 restartBeforeBase=true repeatedIndices=true baseInstance=2 divisors=1,2,3,4294967295 instances=3,5")
    }
  }

  it should "fetch version two converted layouts across lines with indexed instances packed words and typed defaults" in {
    val p=VertexInputFetchConfig()
    simulate(new VertexInputFetcher(p=p)) { d=>
      val t=new Driver(d,p)
      var records=0
      for((formats,group)<-(7 to 24).grouped(16).zipWithIndex) {
        val buffers=formats.indices.map { i=>
          val base=BigInt(0x10000+group*0x20000+i*0x1000)
          Buffer(View(base,base+512,base+60,base+512),16,if(i%2==0)0 else 2)
        }.toVector
        val fields=formats.zipWithIndex.map { case(f,i)=>Attribute(i,if(VertexInputReference.packed(f))4 else 1+i%4,f) }.toVector
        for((f,i)<-formats.zipWithIndex;element<-0 until 6) {
          if(VertexInputReference.packed(f))t.put(buffers(i).view.base+16*element,
            (BigInt(0x80000000L)|(BigInt(1023-element)<<20)|(BigInt(511+element)<<10)|BigInt(element+1)))
          else for(c<-0 until fields(i).components) {
            val raw=if(f==7)BigInt(Vector(0x0001,0x3c00,0xbc00,0x7bff,0x7c00,0x7e00)((element+c)%6))
              else ((BigInt(1)<<(8*size(f)-1))+element*37+c*73)&((BigInt(1)<<(8*size(f)))-1)
            t.put(buffers(i).view.base+16*element+c*size(f),raw,size(f))
          }
        }
        val indices=View(0x90000,0x90100,0x9003e,0x90100)
        Vector(2,0,3).zipWithIndex.foreach{case(n,i)=>t.put(indices.base+2*i,n,2)}
        val command=Draw(count=3,instances=3,baseVertex=1,baseInstance=1,indexFormat=2,indices=indices,
          buffers=buffers,attributes=fields,generation=45+group,version=2)
        t.start(command);val result=t.summary(VertexInputStatus.success)
        assert(t.outputs.toVector==t.expected(command));assert(result.vertices==9 && result.attributeLines>0 && result.hits>0)
        records+=t.outputs.size;t.consume()
      }
      val packed=Draw(count=1,version=2,buffers=Vector(Buffer(View(0xa0000,0xa0040,0xa003c,0xa0040),0)),
        attributes=Vector(Attribute(0,4,20)))
      t.put(0xa003c,BigInt("bff80401",16));t.start(packed);t.summary(VertexInputStatus.success)
      assert(t.outputs.toVector==t.expected(packed) && t.requests.toVector==Vector(BigInt(0xa0000)));t.consume()
      val short=packed.copy(buffers=Vector(Buffer(View(0xa0000,0xa0040,0xa003c,0xa003f),0)))
      t.start(short);t.summary(VertexInputStatus.bounds);assert(t.requests.isEmpty && t.outputs.isEmpty);t.consume()
      t.start(packed);t.summary(VertexInputStatus.success);assert(t.outputs.toVector==t.expected(packed));t.consume()
      t.start(packed)
      t.until(t.requests.nonEmpty && t.pending.isEmpty)
      d.io.cancel.poke(true.B);t.summary(VertexInputStatus.cancelled)
      assert(t.outputs.isEmpty && t.requests.toVector==Vector(BigInt(0xa0000)))
      d.io.cancel.poke(false.B);t.consume()
      t.start(packed);t.summary(VertexInputStatus.success);assert(t.outputs.toVector==t.expected(packed));t.consume()
      info(s"VERTEX_INPUT convertedFormats=18 version2=true records=$records indexedInstances=true crossLine=true packedSingleWord=true exactLogicalBounds=true cancelConversion=true defaults=true healthyReuse=true")
    }
  }

  it should "reject invalid layouts overflow and logical bounds before issuing invalid memory accesses and recover faults" in {
    val p=VertexInputFetchConfig(4,3)
    simulate(new VertexInputFetcher(p=p)) { d=>
      val t=new Driver(d,p)
      val good=Draw(count=1,buffers=Vector(Buffer(view(0x1000),16)),attributes=Vector(Attribute(0,1,1)))
      t.put(0x1000,0x12345678)
      val invalid=(7 to 15).map(f=>good.copy(attributes=Vector(Attribute(0,1,f)))).toVector++Vector(
        good.copy(attributes=Vector(Attribute(3,1,1))),good.copy(attributes=Vector(Attribute(0,0,1))),
        good.copy(attributes=Vector(Attribute(0,5,1))),good.copy(attributes=Vector(Attribute(0,1,3,1))),
        good.copy(buffers=Vector(Buffer(view(0x1000),3))),
        good.copy(buffers=Vector(Buffer(View(0x1001,0x1100,0x1004,0x1100),16))),
        good.copy(indexFormat=2,indices=View(0x4000,0x4100,0x4001,0x4100)),
        good.copy(version=0),good.copy(version=3))++
        (25 to 31).map(f=>good.copy(version=2,attributes=Vector(Attribute(0,1,f))))++
        Vector(20,21,22,23).flatMap(f=>Vector(1,2,3).map(c=>good.copy(version=2,attributes=Vector(Attribute(0,c,f)))))
      for(command<-invalid) {t.start(command);t.summary(VertexInputStatus.invalidDescriptor);assert(t.requests.isEmpty && t.outputs.isEmpty);t.consume()}
      val bounds=Vector(good.copy(firstVertex=u32,count=2),
        good.copy(firstVertex=2,buffers=Vector(Buffer(view(0x1000),BigInt(1)<<31))),
        good.copy(firstVertex=65536,buffers=Vector(Buffer(view(0x1000),65536))),
        good.copy(firstVertex=BigInt(1)<<31,buffers=Vector(Buffer(view(0x1000),2)),attributes=Vector(Attribute(0,1,5))),
        good.copy(indexFormat=3,indices=view(0x4000),firstIndex=63,count=2),
        good.copy(indexFormat=3,indices=view(0x4000),count=1<<30),
        good.copy(buffers=Vector(Buffer(View(0x1000,0x1100,0x1000,0x1003),16))))
      for(command<-bounds){t.start(command);t.summary(VertexInputStatus.bounds);assert(t.requests.isEmpty && t.outputs.isEmpty);t.consume()}
      val partial=good.copy(buffers=Vector(Buffer(View(0x1000,0x1100,0x1000,0x1007),16)),attributes=Vector(Attribute(0,2,1)))
      t.start(partial);val partialResult=t.summary(VertexInputStatus.bounds)
      assert(partialResult.attributeValid && partialResult.attribute==0 && t.requests.size==1 && t.outputs.isEmpty);t.consume()
      for((raw,bias)<-Vector((BigInt(0),BigInt(-1)),(u32,BigInt(1)))) {
        t.put(0x4000,raw)
        t.start(good.copy(indexFormat=3,indices=view(0x4000),baseVertex=bias))
        val result=t.summary(VertexInputStatus.bounds);assert(!result.attributeValid && t.outputs.isEmpty && t.requests.toVector==Vector(BigInt(0x4000)));t.consume()
      }
      val instanceOverflow=good.copy(instances=2,baseInstance=u32,buffers=Vector(Buffer(view(0x1000),0,1)))
      t.start(instanceOverflow);val overflow=t.summary(VertexInputStatus.bounds);assert(overflow.vertices==1 && overflow.attributeValid);t.consume()
      t.start(good.copy(count=0));val empty=t.summary(0);assert(empty.vertices==0 && t.requests.isEmpty);t.consume()
      t.start(good.copy(instances=0));t.summary(0);assert(t.requests.isEmpty && t.outputs.isEmpty);t.consume()
      t.start(good);t.faultLine=Some(0x1000);val fault=t.summary(VertexInputStatus.memoryFault)
      assert(fault.attributeValid && fault.address==0x1000 && t.outputs.isEmpty);t.consume()
      t.start(good.copy(indexFormat=1,indices=view(0x4000)));t.faultLine=Some(0x4000)
      assert(!t.summary(VertexInputStatus.memoryFault).attributeValid && t.outputs.isEmpty);t.consume()
      val secondAttribute=good.copy(buffers=Vector(Buffer(view(0x1000),16),Buffer(view(0x2000),16)),
        attributes=Vector(Attribute(0,1,1),Attribute(1,1,1)))
      t.start(secondAttribute);t.faultLine=Some(0x2000);val secondFault=t.summary(VertexInputStatus.memoryFault)
      assert(secondFault.attributeValid && secondFault.attribute==1 && t.requests.size==2 && t.outputs.isEmpty);t.consume()
      t.start(good);t.summary(0);assert(t.outputs.toVector==t.expected(good));t.consume()
      info(s"VERTEX_INPUT invalidLayouts=${invalid.size} boundFailures=${bounds.size+4} memoryFaults=3 highProductLimbs=true partialAttributeSuppressed=true empty=true healthyReuse=true")
    }
  }

  it should "retain a 4 GiB exclusive bound and the final naturally aligned byte or word" in {
    val p=VertexInputFetchConfig(2,2)
    simulate(new VertexInputFetcher(p=p)) { d=>
      val t=new Driver(d,p);val end=BigInt(1)<<32;val high=View(end-64,end,end-4,end)
      t.put(end-4,0);t.put(0x1000,0x12345678)
      val indexed=Draw(count=1,indexFormat=3,indices=high,buffers=Vector(Buffer(view(0x1000),4)),attributes=Vector(Attribute(0,1,1)))
      t.start(indexed);t.summary(0);assert(t.outputs.toVector==t.expected(indexed));assert(t.requests.contains(end-64));t.consume()
      for((format,bytes,value)<-Vector((5,1,BigInt(0x9a)),(0,4,BigInt("3f800000",16)))) {
        t.put(end-bytes,value,bytes)
        val command=Draw(count=1,buffers=Vector(Buffer(View(end-64,end,end-bytes,end),0)),attributes=Vector(Attribute(0,1,format)))
        t.start(command);t.summary(0);assert(t.outputs.toVector==t.expected(command));assert(t.requests.toVector==Vector(end-64));t.consume()
      }
      info("VERTEX_INPUT exclusive4GiB=true finalByte=true finalWord=true noWrappedRequests=true")
    }
  }

  it should "retain offered reads records and completions through cancellation and drain late faults before reuse" in {
    val p=VertexInputFetchConfig(2,2)
    simulate(new VertexInputFetcher(p=p)) { d=>
      val t=new Driver(d,p);val command=Draw(count=1,buffers=Vector(Buffer(view(0x1000),16)),attributes=Vector(Attribute()))
      for(i<-0 until 4)t.put(0x1000+i*4,0x3f000000+i)
      for(offered<-Vector(true,false)) {
        t.holdRequests=offered;t.holdResponses=true;t.start(command)
        if(offered)t.until(d.io.memoryRequest.valid.peek().litToBoolean)else t.until(t.pending.nonEmpty)
        d.io.cancel.poke(true.B);t.step();d.io.cancel.poke(false.B)
        for(_<-0 until 5){d.io.completion.valid.expect(false.B);t.step()}
        t.holdRequests=false;t.until(t.pending.nonEmpty)
        t.pending=t.pending.map(_.copy(fault=true));t.holdResponses=false
        t.summary(VertexInputStatus.cancelled);assert(t.requests.size==1 && t.outputs.isEmpty);t.consume()
      }
      t.holdOutputs=true;t.start(command);t.until(d.io.out.valid.peek().litToBoolean)
      val held=d.io.out.bits.peek().litValue
      d.io.cancel.poke(true.B);t.step();d.io.cancel.poke(false.B)
      for(_<-0 until 5){d.io.out.valid.expect(true.B);assert(d.io.out.bits.peek().litValue==held);d.io.completion.valid.expect(false.B);t.step()}
      t.holdOutputs=false;t.summary(VertexInputStatus.cancelled);assert(t.outputs.size==1);t.consume()
      t.start(command);t.summary(0);val done=d.io.completion.bits.peek().litValue
      d.io.cancel.poke(true.B);d.io.draw.valid.poke(true.B)
      for(_<-0 until 5){d.io.draw.ready.expect(false.B);t.step();assert(d.io.completion.bits.peek().litValue==done)}
      d.io.cancel.poke(false.B);d.io.draw.valid.poke(false.B);t.consume()
      t.start(command.copy(generation=99));t.summary(0);assert(t.outputs.toVector==t.expected(command.copy(generation=99)));t.consume()
      info("VERTEX_INPUT cancelOffered=true cancelAccepted=true cancelHeldRecord=true heldCompletion=true lateFaultDrain=true healthyReuse=true")
    }
  }

  it should "drain every captured conversion cut after cancellation before admitting a healthy draw" in {
    val p=VertexInputFetchConfig(1,1)
    simulate(new VertexInputFetcher(p=p)){d=>val t=new Driver(d,p)
      t.put(0x1000,BigInt("ffffffff",16))
      val command=Draw(count=1,version=2,buffers=Vector(Buffer(view(0x1000),4)),
        attributes=Vector(Attribute(components=1,format=12)))
      for(cut<-0 to 7){
        val canceled=command.copy(generation=80+cut)
        t.start(canceled);t.until(t.requests.nonEmpty && t.pending.isEmpty)
        for(_<-0 until cut)t.step()
        val offered=t.outputs.nonEmpty || d.io.out.valid.peek().litToBoolean
        if(cut<7)assert(!offered,"a conversion escaped before its five pipeline cuts")
        d.io.cancel.poke(true.B);t.step();d.io.cancel.poke(false.B)
        t.summary(VertexInputStatus.cancelled)
        assert(t.outputs.toVector==(if(offered)t.expected(canceled)else Vector.empty) && t.requests.size==1,
          s"cancellation lost an offered record or leaked an unoffered conversion at cut $cut")
        t.consume()
        val healthy=command.copy(generation=100+cut)
        t.start(healthy);t.summary(0);assert(t.outputs.toVector==t.expected(healthy));t.consume()
      }
    }
  }

  it should "preserve subword indices and attributes at the minimum configured stream width" in {
    val p=VertexInputFetchConfig(1,1)
    simulate(new VertexInputFetcher(p=p)) { d=>
      val t=new Driver(d,p)
      for(i<-0 until 4){t.put(0x1000+i,0x80+i,1);t.put(0x2000+i,i,1)}
      val command=Draw(count=4,indexFormat=1,indices=view(0x2000),
        buffers=Vector(Buffer(view(0x1000),1)),attributes=Vector(Attribute(0,1,5)))
      t.start(command);val result=t.summary(0);assert(t.outputs.toVector==t.expected(command))
      assert(result.vertices==4 && result.indexLines==1 && result.attributeLines==1);t.consume()
      info("VERTEX_INPUT attributes=1 bindings=1 subword=true indexLines=1 attributeLines=1")
    }
  }

  it should "route arbitrary attribute bindings and reuse interleaved bytes across instances" in {
    val p=VertexInputFetchConfig(4,3)
    simulate(new VertexInputFetcher(p=p)) { d=>
      val t=new Driver(d,p)
      for(v<-0 until 4) {
        for(c<-0 until 3)t.put(0x1000+v*16+c*4,0x3f800000L+v*65536+c*4096)
        for(c<-0 until 4)t.put(0x1000+v*16+12+c,0x80+v*4+c,1)
      }
      val unused=Buffer(View(0,0,0,0),0)
      val command=Draw(count=4,instances=2,buffers=Vector(unused,unused,Buffer(View(0x1000,0x1100,0x1000,0x1040),16)),
        attributes=Vector(Attribute(2,3,0),Attribute(2,4,5,12),Attribute(2,1,1,12),
          Attribute(3,0,15,enabled=false,constant=Vector(5,6,7,8).map(BigInt(_)))))
      t.start(command);val result=t.summary(0);assert(t.outputs.toVector==t.expected(command))
      assert(result.vertices==8 && result.indexLines==0 && result.attributeLines==1 && result.hits==63);t.consume()
      info("VERTEX_INPUT interleaved=true nonIdentityBindings=true unusedBindingsIgnored=true disabledFormatsIgnored=true attributeLines=1 reusedReads=63")
    }
  }
}
