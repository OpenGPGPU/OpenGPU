package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.DoNotDiscover
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** Explicit component qualification: actual VS/GS, common durable bins, four
  * image owners and the real payload pool. This physical-address fixture does
  * not qualify the protected host path or the 16-lane/eight-warp product.
  */
@DoNotDiscover
class NativeLayeredFourImageSpec extends AnyFlatSpec {
  import WindowRecordTest.{zero,Memory}
  import TypedColorReference.{fp,encode}
  private val names=Vector("RGBA32F","RGBA32UI","RGBA32UI","RGBA8")
  private val sizes=Vector(16,16,16,4,4,1)
  private val targets=Set(0,8,16,24)
  private def addi(rd:Int,rs:Int,n:Int):BigInt=(BigInt(n&4095)<<20)|(BigInt(rs)<<15)|(BigInt(rd)<<7)|0x13
  private def vm(base:Int,v:Int,write:Boolean):BigInt=
    (BigInt(1)<<25)|(BigInt(base)<<15)|(BigInt(6)<<12)|(BigInt(v)<<7)|(if(write)0x27 else 7)
  private def geometry(fn:Int,rd:Int=0,rs1:Int=0,rs2:Int=0):BigInt=
    (BigInt(fn)<<25)|(BigInt(rs2)<<20)|(BigInt(rs1)<<15)|(BigInt(rd)<<7)|0x0b
  private val vs=Vector(BigInt("00241393",16),BigInt("007083b3",16),BigInt("c1027057",16),addi(6,7,112))++
    (0 until 4).flatMap(c=>Seq(addi(5,7,c*16),vm(5,1,false),vm(6,1,true),addi(6,6,16)))++Vector(BigInt("30500073",16))
  private val gs=(0 until 4).flatMap(c=>Seq(addi(1,0,0),addi(2,0,c),geometry(32,3,1,2),geometry(33,0,2,3))).toVector++
    Vector(addi(4,0,1),geometry(34,0,4),addi(4,0,93),geometry(38,0,4),geometry(35),geometry(36),BigInt("30500073",16))
  private def fragment(layer:Int):Vector[BigInt]={
    val inputs=if(layer==0)70 else 74
    val code=mutable.ArrayBuffer[BigInt](BigInt("00241393",16),BigInt("007083b3",16),BigInt("c1027057",16))
    def store(array:Int):Unit=code++=Seq(addi(6,7,array*16),vm(6,1,true))
    def constant(bits:BigInt,array:Int):Unit={
      code++=Seq(((((bits+0x800)>>12)&0xfffff)<<12)|(BigInt(13)<<7)|0x37,addi(13,13,(bits&4095).toInt),
        (BigInt(0x17)<<26)|(BigInt(1)<<25)|(BigInt(13)<<15)|(BigInt(4)<<12)|(BigInt(1)<<7)|0x57)
      store(array)
    }
    def copy(input:Int,output:Int):Unit={code++=Seq(addi(5,7,input*16),vm(5,1,false));store(output)}
    for(c<-0 until 4)constant(fp(if(layer==0)0.25 else 0.5),inputs+c)
    for(location<-1 to 2;c<-0 until 4){
      if(layer==1 && c==0)copy(if(location==1)72 else 73,inputs+location*4+c)
      else constant(if(c==0){if(location==1)7 else 0}else location*10+c,inputs+location*4+c)
    }
    for(c<-0 until 4)constant(fp(Vector(0.25,0.5,0.75,1.0)(c)),inputs+12+c)
    copy(2,inputs+16);constant(61,inputs+17)
    code+=BigInt("30500073",16);require(code.size<=512);code.toVector
  }
  private case class Plane(location:Int,resolve:Boolean,allocation:BigInt) {
    val samples=if(resolve)1 else 4
    val base=allocation+17
    val rowPitch=25*samples*sizes(location)+13
    val limit=base+4096+25*samples*sizes(location)
    val end=(limit+63)& ~BigInt(63)
    def address(layer:Int,x:Int,sample:Int):BigInt=base+layer*4096+(x*samples+sample)*sizes(location)
  }
  private class Driver(val d:NativeLayeredFourImageHarness) {
    zero(d.io);d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
    val memory=new Memory(d.io.memoryRequest.bits,d.io.memoryResponse.bits,d.io.memoryRequest.valid,d.io.memoryRequest.ready,
      d.io.memoryResponse.valid,d.io.memoryResponse.ready)
    val planes=(for(resolve<-Seq(false,true);i<-0 until 6)yield
      Plane(i,resolve,BigInt(if(resolve)0x2000000 else 0x1000000)+i*0x10000)).toVector
    private val immutable=mutable.Map.empty[BigInt,Int]
    private val guards=mutable.Map.empty[BigInt,Int]
    private val readable=mutable.ArrayBuffer.empty[(BigInt,BigInt)]
    private val writable=mutable.ArrayBuffer.empty[(BigInt,BigInt)]
    private val stateFields=TypedFragmentStateLayout.fields(new TypedFragmentState(d.fragments))
    private val held=mutable.Map.empty[String,BigInt]
    private val primitiveTuples=mutable.Set.empty[(Int,Int)]
    private val pixelTuples=mutable.Set.empty[(Int,Int)]
    private val layerTuples=mutable.Set.empty[(Int,Int)]
    private val tileTuples=mutable.Set.empty[Int]
    private var tileEngines=0;private var checkHealthy=true
    var launches=0;var geometryLaunches=0;var tiles=0;var layers=0
    var stores=0;var acknowledgements=0;var ordinal=0
    private def identity(i:GraphicsWorkIdentity):Unit={i.context.poke(7.U);i.pass.poke(8.U);i.generation.poke(9.U)}
    private def checkIdentity(i:GraphicsWorkIdentity):Unit={i.context.expect(7.U);i.pass.expect(8.U);i.generation.expect(9.U)}
    private def view(v:VertexInputView,base:Int,bytes:Int):Unit={
      v.base.poke(base.U);v.limit.poke((base+bytes).U)
      v.allocationBase.poke((base& ~63).U);v.allocationLimit.poke(((base+bytes+63)& ~63).U)
    }
    private def snapshot(base:BigInt,limit:BigInt):Unit=for(a<-base until limit)immutable(a)=memory.bytes.getOrElse(a,0)
    def install():Unit={
      def allocation(base:BigInt,limit:BigInt,write:Boolean):Unit={
        val span=(base,limit);readable+=span;if(write)writable+=span
      }
      for((base,words)<-Seq(0x1000->vs,0x900000->gs,0x8000->fragment(0),0x9000->fragment(1))){
        for((word,i)<-words.zipWithIndex)memory.putWord(base+i*4,word)
        val end=(base+words.size*4+63)& ~63
        snapshot(base,end);allocation(base,end,write=false)
      }
      for((x,i)<-Vector(0,8,16,24).zipWithIndex;c<-0 until 4)
        memory.putWord(0x20000+i*16+c*4,fp(Vector((2.0*x+1)/32-1,0.0,0.0,1.0)(c)))
      snapshot(0x20000,0x20040);allocation(0x20000,0x20040,write=false)
      allocation(0x200000,0x200000+64*d.recordBytes,write=true)
      allocation(0x400000,0x410000,write=true);allocation(0x500000,0x500100,write=true)
      allocation(0x300000,0x300000+1216,write=true)
      for(a<-0x300000 until 0x301000){memory.bytes(a)=0x6d;if(a>=0x300000+1200)guards(a)=0x6d}
      for(h<-0 until 2){
        val prefix=if(h==0)1408 else 1472
        val state=Map[String,BigInt]("version"->2,"engineCount"->4,
          "fragment.version"->(if(h==0)1 else 5),"fragment.identity.context"->7,
          "fragment.identity.pass"->8,"fragment.identity.generation"->9,
          "fragment.shaderPc"->(0x8000+h*4096),"fragment.varyingSlots"->16,
          "fragment.colorSlots"->4,"fragment.colorMask"->15,"fragment.sampleMode"->2,
          "fragment.sampler.texWidth"->1,"fragment.sampler.texHeight"->1,
          "tests.version"->1,"tests.depthEnable"->1,"tests.depthWrite"->1,"tests.depthFunction"->1,
          "blend.version"->2)++
          (0 until 4).map(i=>s"fragment.colorTypes[$i]"->BigInt(if(i==1 || i==2)1 else 0))++
          (0 until 4).map(i=>s"blend.buffers[$i]"->BigInt(15<<23))++
          (0 until 4).flatMap{e=>
            val base=0x600000+e*0x10000+h*4096
            for(a<-base until base+4096){memory.bytes(a)=0x6d;if(a>=base+prefix)guards(a)=0x6d}
            allocation(base,base+((prefix+63)& ~63),write=true)
            val name=if(e==0)"fragment.scratch" else s"scratch[${e-1}]"
            Seq(s"$name.base"->BigInt(base),s"$name.limit"->BigInt(base+prefix),
              s"$name.allocationBase"->BigInt(base),s"$name.allocationLimit"->BigInt(base+((prefix+63)& ~63)))
          }
        assert(state.keySet.subsetOf(stateFields.map(_.name).toSet))
        val base=0x700000+h*512
        for(w<-0 until 128)memory.putWord(base+w*4,0)
        for(f<-stateFields;w<-0 until f.words){val raw=state.getOrElse(f.name,BigInt(0))
          memory.putWord(base+(f.word+w)*4,(raw>>(32*w))&0xffffffffL)}
        for((word,i)<-Vector[BigInt](TypedFragmentStateLayout.magic,512,1,4,7,8,9,h).zipWithIndex)
          memory.putWord(base+i*4,word)
        snapshot(base,base+512);allocation(base,base+512,write=false)
      }
      for(p<-planes){allocation(p.allocation,p.end,write=true)
        for(a<-p.allocation until p.end)memory.bytes(a)=0xa7}
      memory.beforeRequest=r=>{
        if(r.write){for(b<-0 until 64 if r.mask.testBit(b)){
          val address=r.address+b
          assert(writable.exists{case(first,end)=>address>=first && address<end},
            s"write escaped all owned allocations: 0x${address.toString(16)}")}}
        else assert(readable.exists{case(first,end)=>r.address>=first && r.address+64<=end},
          s"read escaped all owned allocations: 0x${r.address.toString(16)}")
      }
    }
    def tick():Unit={
      memory.drive()
      def output(name:String,valid:Bool,ready:Bool,bits:Data):Unit={
        val v=valid.peek().litToBoolean;val r=ready.peek().litToBoolean
        val b=if(v || held.contains(name))bits.peek().litValue else BigInt(0)
        held.get(name).foreach(old=>assert(v && b==old,s"held $name changed"))
        if(v && !r)held(name)=b else held.remove(name)
      }
      for(e<-0 until 4){
        val p=d.io.primitiveDone(e);p.ready.poke((memory.cycle%3!=e%3).B);output(s"primitive$e",p.valid,p.ready,p.bits)
        val o=d.io.out(e);o.ready.poke((memory.cycle%5!=e).B);output(s"pixel$e",o.valid,o.ready,o.bits)
        val l=d.io.layerDone(e);l.ready.poke((memory.cycle%7!=e).B);output(s"layer$e",l.valid,l.ready,l.bits)
        if(checkHealthy && p.valid.peek().litToBoolean && p.ready.peek().litToBoolean){
          val r=p.bits;val sequence=r.source.reference.sequence.peek().litValue.toInt
          val tile=r.source.tile.peek().litValue.toInt;assert(sequence>=0 && sequence<8 && tile==sequence%4)
          checkIdentity(r.source.identity);r.status.expect(0.U);r.renderStarted.expect(true.B)
          r.source.reference.primitive.expect(sequence.U);r.source.reference.state.expect((sequence/4).U)
          r.source.reference.token.expect((200+sequence/4).U)
          assert(primitiveTuples.add((tile,sequence)),"duplicate original primitive completion")
        }
        if(checkHealthy && o.valid.peek().litToBoolean && o.ready.peek().litToBoolean){
          val r=o.bits;val layer=r.layer.peek().litValue.toInt;assert(layer==0 || layer==1)
          checkIdentity(r.pixels.identity);r.pixels.status.expect(0.U)
          r.pixels.testedSamples.expect(4.U);r.pixels.passingSamples.expect(4.U)
          assert(pixelTuples.add((e,layer)),"duplicate pixel retirement for an image layer")
          val primitive=r.pixels.primitiveId.peek().litValue.toInt
          assert(if(layer==0)primitive>=0 && primitive<4 else primitive==93)
        }
        if(l.valid.peek().litToBoolean && l.ready.peek().litToBoolean){layers+=1
          if(checkHealthy){val r=l.bits;val tile=r.image.tile.peek().litValue.toInt;val layer=r.layer.peek().litValue.toInt
            assert(tile>=0 && tile<4 && layer>=0 && layer<2);checkIdentity(r.image.identity);r.image.status.expect(0.U)
            assert(layerTuples.add((tile,layer)),"duplicate layer retirement")}}
        if(d.io.imageStore(e).peek().litToBoolean)stores|=1<<e
        if(d.io.imageAck(e).peek().litToBoolean)acknowledgements|=1<<e
      }
      d.io.tileDone.ready.poke((memory.cycle%3!=0).B)
      output("tile",d.io.tileDone.valid,d.io.tileDone.ready,d.io.tileDone.bits)
      if(d.io.tileDone.valid.peek().litToBoolean && d.io.tileDone.ready.peek().litToBoolean){tiles+=1
        if(checkHealthy){val r=d.io.tileDone.bits;val tile=r.image.tile.peek().litValue.toInt
          val engine=r.engine.peek().litValue.toInt;assert(tile>=0 && tile<4 && engine>=0 && engine<4)
          assert(tileTuples.add(tile),"duplicate tile retirement");tileEngines|=1<<engine
          r.renderStarted.expect(true.B);checkIdentity(r.image.identity);r.image.status.expect(0.U)
          r.image.replay.completedLayers.expect(2.U)}}
      for(i<-0 until 5){val task=d.io.task(i)
        if(task.valid.peek().litToBoolean){task.bits.owner.expect(i.U);task.bits.context.expect(7.U)
          assert(task.bits.handle.peek().litValue<d.native.payload.get.slots)}
      }
      if(d.io.launch.valid.peek().litToBoolean && d.io.launchPayload.valid.peek().litToBoolean){
        val binding=d.io.launchPayload.bits.peek().litValue
        assert(d.io.task.exists(t=>t.valid.peek().litToBoolean && t.bits.peek().litValue==binding),
          "shared-CU launch lost the live producer payload lease")
      }
      if(d.io.launch.valid.peek().litToBoolean){launches+=1;if(d.io.launch.bits.peek().litToBoolean)geometryLaunches+=1}
      assert(!d.io.trap.valid.peek().litToBoolean,"unexpected actual shader trap")
      memory.capture();d.clock.step()
    }
    def until(label:String)(condition: =>Boolean):Unit={var n=0;while(!condition && n<300000){tick();n+=1}
      assert(condition,s"$label stalled cycles=${memory.cycle} draws=$ordinal launches=$launches tiles=$tiles layers=$layers")}
    def begin(check:Boolean=true):Unit={
      checkHealthy=check;primitiveTuples.clear();pixelTuples.clear();layerTuples.clear();tileTuples.clear();tileEngines=0
      ordinal=0;tiles=0;layers=0;stores=0;acknowledgements=0
      val q=d.io.begin.bits;zero(q);q.version.poke(1.U)
      val a=q.bins.producer;val b=q.bins.bins
      a.version.poke(1.U);a.arena.version.poke(1.U);identity(a.arena.identity)
      a.arena.base.poke(0x200000.U);a.arena.limit.poke((0x200000+64*d.recordBytes).U)
      a.screenWidth.poke(25.U);a.screenHeight.poke(1.U);a.tileLog2.poke(3.U)
      b.version.poke(3.U);b.tileCount.poke(4.U);b.directoryBase.poke(0x500000.U);b.directoryLimit.poke(0x500100.U)
      b.pool.version.poke(1.U);identity(b.pool.identity);b.pool.base.poke(0x400000.U);b.pool.limit.poke(0x410000.U);b.pool.pageSizeLog2.poke(12.U)
      q.states.version.poke(1.U);identity(q.states.identity);q.states.base.poke(0x700000.U);q.states.limit.poke(0x700400.U)
      q.scratchStride.poke(4096.U)
      for(e<-0 until 4){q.scratch(e).base.poke((0x600000+e*0x10000).U);q.scratch(e).limit.poke((0x602000+e*0x10000).U)}
      q.image.version.poke(1.U);q.image.layers.poke(2.U)
      val im=q.image.image;im.version.poke(1.U);im.pixels.version.poke(1.U)
      val c=im.pixels.colors;c.version.poke(1.U);identity(c.identity);c.tileLog2.poke(3.U)
      c.tileWidth.poke(8.U);c.tileHeight.poke(1.U);c.sampleMode.poke(2.U);c.reservedBytes.poke(1280.U)
      for(i<-0 until 4){val v=c.colors(i);v.enabled.poke(true.B);v.format.poke(TypedColorFormat.id(names(i)).U)
        v.offset.poke((1280+i*4096).U);v.channelMask.poke(15.U);v.loadOp.poke(AttachmentLoadOp.clear.U)
        v.clear.zip(clear(i)).foreach{case(x,value)=>x.poke(value.U)}}
      im.pixels.depth.format.poke(TypedDepthFormat.d32f.U);im.pixels.depth.clearDepth.poke(fp(1).U)
      im.pixels.stencil.format.poke(TypedDepthFormat.s8.U);im.pixels.stencil.offset.poke(1024.U);im.pixels.stencil.clearStencil.poke(7.U)
      for(v<-Seq(im.pixels.depth,im.pixels.stencil))v.loadOp.poke(AttachmentLoadOp.clear.U)
      for(p<-planes){val v=if(p.resolve){im.resolves(p.location).enable.poke(true.B);im.resolves(p.location).destination}else im.views(p.location)
        v.base.poke(p.base.U);v.limit.poke(p.limit.U);v.allocationBase.poke(p.allocation.U);v.allocationLimit.poke(p.end.U)
        v.imageWidth.poke(25.U);v.imageHeight.poke(1.U);v.rowPitch.poke(p.rowPitch.U)
        v.readable.poke((!p.resolve).B);v.writable.poke(true.B);v.store.poke(true.B)
        (if(p.resolve)q.image.resolvePitch(p.location)else q.image.sourcePitch(p.location)).poke(4096.U)
      }
      d.io.begin.valid.poke(true.B);until("begin")(d.io.begin.ready.peek().litToBoolean);tick();d.io.begin.valid.poke(false.B);zero(q)
      until("draw admission")(d.io.draw.ready.peek().litToBoolean)
    }
    def draw(layer:Int):Unit={
      val q=d.io.draw.bits;zero(q);q.commandId.poke(4.U);q.taskId.poke(19.U)
      q.draw.version.poke(1.U);q.draw.state.poke(layer.U);q.draw.token.poke((200+layer).U)
      q.draw.raster.version.poke(1.U);q.draw.raster.frontFaceCCW.poke(true.B)
      q.draw.raster.pointSize.poke(fp(1).U);q.draw.raster.sampleMode.poke(2.U)
      val v=q.draw.vertex.geometry.vertex
      v.version.poke(1.U);v.shaderPc.poke(0x1000.U);v.attributeSlots.poke(1.U);v.varyingSlots.poke(16.U)
      v.input.version.poke(1.U);identity(v.input.identity);v.input.count.poke(4.U);v.input.instances.poke(1.U)
      view(v.input.bindings(0),0x20000,64);v.input.bindings(0).stride.poke(16.U)
      v.input.attributes(0).enabled.poke(true.B);v.input.attributes(0).components.poke(4.U);v.input.attributes(0).format.poke(VertexInputFormat.float32.U)
      view(v.scratch,0x300000,1200);v.sampler.texWidth.poke(1.U);v.sampler.texHeight.poke(1.U)
      val viewport=q.draw.vertex.viewport;viewport.version.poke(1.U);viewport.extentX.poke(32.U);viewport.extentY.poke(1.U);viewport.farDepth.poke(fp(1).U)
      q.geometry.valid.poke((layer==1).B);val g=q.geometry.bits;identity(g.identity)
      g.inputVertices.poke(1.U);g.invocations.poke(1.U);g.maxVertices.poke(170.U)
      g.inputComponents.poke(4.U);g.outputComponents.poke(4.U);g.builtinOutputMask.poke(3.U)
      g.vertexVaryingSlots.poke(16.U);g.fragmentVaryingSlots.poke(16.U);g.programVersion.poke(1.U);g.pc.poke(0x900000.U);view(g.code,0x900000,gs.size*4)
      for(c<-0 until 4){g.inputSources(c).poke(c.U);g.outputSources(c).valid.poke(true.B);g.outputSources(c).bits.poke(c.U)}
      d.io.draw.valid.poke(true.B);until("draw")(d.io.draw.ready.peek().litToBoolean);tick();d.io.draw.valid.poke(false.B);zero(q)
      until("draw completion")(d.io.drawCompletion.valid.peek().litToBoolean)
      val r=d.io.drawCompletion.bits;r.status.expect(0.U);r.ordinal.expect(ordinal.U)
      r.receivedParts.expect(4.U);r.admittedParts.expect(4.U);r.droppedParts.expect(0.U)
      val held=r.peek().litValue;for(_<-0 until 5){tick();d.io.drawCompletion.valid.expect(true.B);assert(r.peek().litValue==held)}
      d.io.drawCompletion.ready.poke(true.B);tick();d.io.drawCompletion.ready.poke(false.B);ordinal+=1
    }
    def end():Unit={d.io.end.valid.poke(true.B);d.io.end.bits.poke(true.B)
      until("end")(d.io.end.ready.peek().litToBoolean);tick();d.io.end.valid.poke(false.B)}
    private def clear(i:Int):Vector[BigInt]=if(i==1 || i==2)Vector(0,1,2,3).map(BigInt(_))else Vector.fill(4)(fp(0.125))
    private def packed(i:Int,layer:Int,x:Int):BigInt={
      val populated=targets(x)
      if(i==4){if(populated)fp(0.5)else fp(1)}
      else if(i==5)7
      else encode(names(i),if(!populated)clear(i)else i match {
        case 0=>Vector.fill(4)(fp(if(layer==0)0.25 else 0.5))
        case 1=>Vector(if(layer==0)7 else 93,11,12,13).map(BigInt(_))
        case 2=>Vector(layer,21,22,23).map(BigInt(_))
        case _=>Vector(0.25,0.5,0.75,1.0).map(fp)
      })._1
    }
    private def checkBacking():Unit={
      for(p<-planes){val expected=mutable.Map.empty[BigInt,Int]
        for(layer<-0 until 2;x<-0 until 25;s<-0 until p.samples;b<-0 until sizes(p.location))
          expected(p.address(layer,x,s)+b)=((packed(p.location,layer,x)>>(8*b))&255).toInt
        for(a<-p.allocation until p.end)assert(memory.bytes(a)==expected.getOrElse(a,0xa7),
          s"attachment mismatch location=${p.location} resolve=${p.resolve} address=0x${a.toString(16)} got=${memory.bytes(a)} expected=${expected.getOrElse(a,0xa7)}")
      }
      for((a,v)<-immutable)assert(memory.bytes.getOrElse(a,0)==v,s"immutable input changed 0x${a.toString(16)}")
      for((a,v)<-guards)assert(memory.bytes(a)==v,s"shader prefix escaped 0x${a.toString(16)}")
    }
    def finish(status:Int=0):Unit={
      until("pass completion")(d.io.completion.valid.peek().litToBoolean)
      val r=d.io.completion.bits;r.status.expect(status.U)
      assert(memory.pending.isEmpty && memory.reply.isEmpty)
      d.io.reservedBytes.expect(0.U);d.io.poolDrained.expect(true.B);d.io.owners.expect(0.U)
      if(status==0){r.draws.expect(2.U);r.admittedParts.expect(8.U);r.submittedGeometryPrimitives.expect(4.U)
        r.completedGeometryInvocations.expect(4.U);r.emittedGeometryPrimitives.expect(4.U)
        r.images.images.tiles.expect(4.U);assert(tiles==4 && layers==8 && stores==15 && acknowledgements==15)
        assert(tileTuples.toSet==(0 until 4).toSet && tileEngines==15)
        assert(layerTuples.toSet==(for(tile<-0 until 4;layer<-0 until 2)yield(tile,layer)).toSet)
        assert(pixelTuples.toSet==(for(engine<-0 until 4;layer<-0 until 2)yield(engine,layer)).toSet)
        assert(primitiveTuples.toSet==(0 until 8).map(sequence=>(sequence%4,sequence)).toSet)
        checkBacking()}
      val held=r.peek().litValue;for(_<-0 until 7){tick();d.io.completion.valid.expect(true.B);assert(r.peek().litValue==held);d.io.begin.ready.expect(false.B)}
      d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B)
      until("idle")(d.io.idle.peek().litToBoolean);assert(memory.pending.isEmpty && memory.reply.isEmpty)
    }
    def healthy():Unit={begin();draw(0);draw(1);end();finish()}
  }
  it should "execute mixed VS and GS through four real layered images and retain allocation replies across peer cancellation" in {
    require(sys.props.get("opengpu.nativeLayeredFourImageQualification").contains("1"),"select this local qualification explicitly")
    simulate(new NativeLayeredFourImageHarness){d=>val t=new Driver(d);t.install();t.healthy()
      t.begin(check=false);t.draw(0);t.draw(1)
      for(i<-1 to 4)d.io.holdPayloadReply(i).poke(true.B)
      t.end();t.until("four real held allocation replies")((1 to 4).forall(i=>d.io.payloadReplyHeld(i).peek().litToBoolean))
      val reserved=BigInt(4)*d.native.payload.get.slotBytes;d.io.reservedBytes.expect(reserved.U)
      val replies=(1 to 4).map{i=>val r=d.io.payloadReply(i)
        r.valid.expect(true.B);r.bits.operation.expect(ShaderPayloadControl.allocate.U)
        r.bits.resident.expect(true.B);r.bits.spill.expect(false.B);r.bits.fault.expect(false.B)
        r.bits.context.expect(7.U);r.bits.bytes.expect(1408.U);r.bits.peek().litValue}
      assert((1 to 4).map(i=>d.io.payloadReply(i).bits.handle.peek().litValue).distinct.size==4,
        "four image owners must hold distinct resident reservations")
      d.io.payloadCancel(2).poke(true.B);t.tick();d.io.payloadCancel(2).poke(false.B)
      for(_<-0 until 9){t.tick();d.io.completion.valid.expect(false.B);d.io.poolDrained.expect(false.B);d.io.reservedBytes.expect(reserved.U)
        for(i<-1 to 4){d.io.payloadReplyHeld(i).expect(true.B);d.io.payloadReply(i).valid.expect(true.B)
          assert(d.io.payloadReply(i).bits.peek().litValue==replies(i-1),"held actual allocation lease changed during peer cancel")}}
      for(i<-1 to 4)d.io.holdPayloadReply(i).poke(false.B)
      t.finish(4);t.healthy()
      assert(t.geometryLaunches==12)
      info(s"FOUR_IMAGE_COMPONENT frame=25x1 layers=2 samples=4 MRT=4 slots=16 directory=65536 payload=512KiB actual2CU4x1 cancellationNoResetReuse=true launches=${t.launches}")
    }
  }
}
