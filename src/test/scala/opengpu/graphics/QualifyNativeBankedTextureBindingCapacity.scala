package opengpu.graphics

import chisel3._
import opengpu.command.GpuCommandOpcode
import opengpu.testutil.GraphicsTestResolution
import opengpu.testutil.GpuSim._
import org.scalatest.DoNotDiscover
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** Explicit full-thread stage-capacity qualification; never auto-discovered. */
@DoNotDiscover
class NativeBankedTextureBindingCapacityQualification extends AnyFlatSpec {
  import WindowRecordTest.{zero,identity}
  private val profiles=Vector("local","spill")
  private val selected=sys.props.get("opengpu.textureBindingPayload").map{p=>require(profiles.contains(p));Vector(p)}.getOrElse(profiles)
  for(profile<-selected)
  it should s"service thirty-two distinct bindings per native stage through both banked texture clusters payload=$profile" in {
    require(!GraphicsTestResolution.productQualification)
    simulate(new NativeBankedTextureBindingHarness){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      d.io.sramModelsPresent.expect(true.B)
      val bytes=mutable.Map.empty[BigInt,Int]
      def put(a: BigInt,v: BigInt): Unit=for(b<-0 until 4)bytes(a+b)=((v>>(8*b))&255).toInt
      def word(a: BigInt): BigInt=(0 until 4).foldLeft(BigInt(0))((v,b)=>v|(BigInt(bytes.getOrElse(a+b,0))<<(8*b)))
      val workIdentity=Seq[BigInt](7,11,13)
      val views=(0 until 2).map{stage=>
        val offset=BigInt(stage*0x40000);val table=(BigInt(1)<<32)-4096+stage*2048
        NativeTextureFetchTestData.roots(put,stage,workIdentity,offset=offset)
        val installed=(0 until 32).map{unit=>
          val raw=BigInt((unit*7+1+stage*101)&255)|(BigInt((unit*3+19+stage*83)&255)<<8)|
            (BigInt((unit*5+37+stage*61)&255)<<16)|(BigInt(255)<<24)
          val format=TextureImageFormat.id("RGBA8")
          val installed=NativeTextureFetchTestData.install(put,(a,b)=>bytes(a)=b,unit,format,0,stage,workIdentity,raw,offset)
          val data=offset+0x20000+unit*4096+60
          // Word-aligned RGBA8 taps are eligible for the banked numeric path.
          // Keep all four texels identical so the existing independent decode
          // oracle applies to exact-center bilinear sampling as well.
          put(NativeTextureFetchTestData.levels+offset+unit*128+17*4,16)
          put(NativeTextureFetchTestData.levels+offset+unit*128+18*4,0)
          put(NativeTextureFetchTestData.samplers+offset+unit*64+7*4,TextureSamplerState.linear)
          put(NativeTextureFetchTestData.samplers+offset+unit*64+8*4,TextureSamplerState.linear)
          for(y<-0 until 2;x<-0 until 2)put(data+y*16+x*4,raw)
          installed.copy(address=data+20)
        }
        for(n<-0 until 512)put(table+n*4,word(NativeTextureFetchTestData.bindings+offset+n*4))
        put(NativeTextureFetchTestData.root+offset+8*4,table)
        val end=table+2048;put(NativeTextureFetchTestData.root+offset+9*4,end&0xffffffffL)
        put(NativeTextureFetchTestData.root+offset+10*4,end>>32)
        assert(installed.map(_.expected).distinct.size==32)
        installed
      }
      case class Reply(id: BigInt,data: BigInt,due: Int)
      val pending=mutable.ArrayBuffer.empty[Reply];var offered=Option.empty[Reply]
      val dataLines=mutable.Set.empty[BigInt];val overlappingRoots=mutable.Set.empty[BigInt]
      var cycle=0;var overlap=0;var vertices=0;var quads=0
      var badVertex=false;var badFragment=false
      var blockerRunning=false
      case class TextureOwner(tag: BigInt,unit: Int)
      val owners=Vector.fill(2)(mutable.Queue.empty[TextureOwner])
      val serviced=Vector.fill(2)(mutable.Set.empty[Int])
      val drawServiced=Vector.fill(2)(mutable.Set.empty[Int])
      val clusterReads=Array(0,0)
      val selectedClusters=mutable.Set.empty[Int]
      val baseToUnit=(0 until 2).map(stage=>(0 until 32).map(unit=>
        (BigInt(stage*0x40000)+0x20000+unit*4096+60)->unit).toMap)
      def tick(): Unit={
        assert(cycle<2000000,"full native binding qualification exceeded its correctness guard")
        d.io.trapped.expect(false.B)
        if(blockerRunning){d.io.blockerBusy.expect(true.B);d.io.gpuCompletion.valid.expect(false.B)}
        val owned=d.io.owned.map(_.peek().litValue)
        for(stage<-0 until 2){
          if(d.io.accepted(stage).valid.peek().litToBoolean){
            val q=d.io.accepted(stage).bits
            q.linear.expect(true.B);q.format.expect(TextureImageFormat.id("RGBA8").U)
            assert(owned(stage)==2 || owned(stage)==4,s"unexpected actual CU ownership: ${owned(stage)}")
            val unit=baseToUnit(stage).getOrElse(q.view.base.peek().litValue,
              fail("banked request did not retain its selected binding"))
            selectedClusters+=(if(owned(stage)==2)0 else 1)
            owners(stage).enqueue(TextureOwner(q.tag.peek().litValue,unit))
          }
          if(d.io.retired(stage).valid.peek().litToBoolean){
            assert(owners(stage).nonEmpty,"banked response lacks a request owner")
            val owner=owners(stage).dequeue();val r=d.io.retired(stage).bits
            r.tag.expect(owner.tag.U);r.status.expect(0.U);r.fallback.expect(false.B)
            r.identity.context.expect(7.U);r.identity.pass.expect(11.U);r.identity.generation.expect(13.U)
            for(c<-0 until 4)r.rgba(c).expect(views(stage)(owner.unit).expected(c).U)
            serviced(stage)+=owner.unit;drawServiced(stage)+=owner.unit
          }
        }
        for(cluster<-0 until 2 if d.io.clusterRead(cluster).peek().litToBoolean)clusterReads(cluster)+=1
        if(owned.forall(_!=0)){assert((owned(0)&owned(1))==0);overlap+=1}
        d.io.vertexOut.ready.poke((cycle%11<8).B);d.io.fragmentOut.ready.poke((cycle%13<9).B)
        if(d.io.vertexOut.valid.peek().litToBoolean && d.io.vertexOut.ready.peek().litToBoolean){
          assert(!badVertex);val r=d.io.vertexOut.bits;r.ordinal.expect(vertices.U);r.vertexId.expect(vertices.U)
          r.position(0).expect(vertices.U)
          for(c<-0 until 4)r.varyings(0)(c).expect(views(0)(vertices%32).expected(c).U)
          r.varyings(1)(0).expect((vertices%32).U);vertices+=1
        }
        if(d.io.fragmentOut.valid.peek().litToBoolean && d.io.fragmentOut.ready.peek().litToBoolean){
          assert(!badFragment);val r=d.io.fragmentOut.bits;r.sequence.expect(quads.U)
          for((lane,i)<-r.lanes.zipWithIndex){
            for(c<-0 until 4)lane.colors(0)(c).expect(views(1)((quads*4+i)%32).expected(c).U)
            lane.discarded.expect(false.B);lane.helper.expect(false.B)
          };quads+=1
        }
        d.io.memoryRequest.ready.poke((cycle%7<5).B)
        if(offered.isEmpty){val due=pending.indices.filter(i=>pending(i).due<=cycle)
          if(due.nonEmpty)offered=Some(pending.remove(if(cycle%2==0)due.last else due.head))}
        d.io.memoryResponse.valid.poke(offered.nonEmpty.B)
        offered.foreach{r=>d.io.memoryResponse.bits.transactionId.poke(r.id.U);d.io.memoryResponse.bits.readData.poke(r.data.U);d.io.memoryResponse.bits.fault.poke(false.B)}
        if(offered.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean)offered=None
        if(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean){
          val r=d.io.memoryRequest.bits;val a=r.address.peek().litValue;val write=r.isWrite.peek().litToBoolean
          assert(a%64==0 && a>=0 && a<(BigInt(1)<<32))
          if(write){val mask=r.byteMask.peek().litValue;val value=r.writeData.peek().litValue
            for(b<-0 until 64 if mask.testBit(b))bytes(a+b)=((value>>(8*b))&255).toInt
          }else{
            dataLines+=a
            if(owned.forall(_!=0) && Set(BigInt(0x9000),BigInt(0x49000))(a))overlappingRoots+=a
          }
          val data=if(write)BigInt(0)else(0 until 64).foldLeft(BigInt(0))((v,b)=>v|(BigInt(bytes.getOrElse(a+b,0))<<(8*b)))
          pending+=Reply(r.transactionId.peek().litValue,data,cycle+3+cycle%5)
        }
        d.clock.step();cycle+=1
      }
      def until(condition: =>Boolean): Unit=while(!condition)tick()
      def view(v: VertexInputView,base: BigInt,size: Int): Unit={
        v.base.poke(base.U);v.limit.poke((base+size).U);v.allocationBase.poke(base.U)
        v.allocationLimit.poke(((base+size+63)& ~BigInt(63)).U)
      }
      // Occupy a real CU with a hot instruction-cache loop. This creates
      // genuine cross-pair placement without forged ownership or held memory
      // replies. The blocker is intentionally reset only after all five draws
      // and every graphics/payload/texture/memory owner have drained.
      val blockerPc=BigInt(0x7e0000)
      put(blockerPc,BigInt("0000006f",16)) // jal x0,0
      val command=d.io.gpuCommand.bits;zero(command)
      command.commandId.poke(91.U);command.opcode.poke(GpuCommandOpcode.kernel)
      command.launch.kernelPc.poke(blockerPc.U);command.launch.kernargAddress.poke(0x300000.U)
      command.launch.gridSize.foreach(_.poke(1.U));command.launch.localSize.foreach(_.poke(1.U))
      d.io.gpuCommand.valid.poke(true.B)
      until(d.io.gpuCommand.ready.peek().litToBoolean);tick();d.io.gpuCommand.valid.poke(false.B)
      until(d.io.blockerBusy.peek().litToBoolean);blockerRunning=true
      val spill=profile=="spill";val attributes=if(spill)16 else 2;val fragmentVaryings=if(spill)16 else 1
      val vertexPrefix=d.vertex.prefixBytes(128,attributes,2);val fragmentPrefix=d.fragment.prefixBytes(128,fragmentVaryings,1)
      assert((vertexPrefix>16384)==spill && (fragmentPrefix>16384)==spill)
      for((mode,iteration)<-Vector("healthy","bad-vertex","reuse","bad-fragment","reuse").zipWithIndex){
        drawServiced.foreach(_.clear())
        vertices=0;quads=0;overlap=0;badVertex=mode=="bad-vertex";badFragment=mode=="bad-fragment"
        d.io.start.poke(false.B)
        val vpc=0x810000+iteration*0x1000;val fpc=0x820000+iteration*0x1000
        NativeTextureBindingCapacityProgram.code(0,attributes,2,badVertex).zipWithIndex.foreach{case(w,i)=>put(vpc+4*i,w)}
        NativeTextureBindingCapacityProgram.code(1,attributes,fragmentVaryings,badFragment).zipWithIndex.foreach{case(w,i)=>put(fpc+4*i,w)}
        val v=d.io.vertexDraw.bits;zero(v);v.version.poke(1.U);v.input.version.poke(1.U);identity(v.input.identity)
        v.input.count.poke(128.U);v.input.instances.poke(1.U);v.shaderPc.poke(vpc.U)
        view(v.scratch,0x100000,vertexPrefix);v.attributeSlots.poke(attributes.U);v.varyingSlots.poke(2.U);v.varyingMask.poke(31.U)
        v.sampler.texWidth.poke(1.U);v.sampler.texHeight.poke(1.U);v.resources.version.poke(1.U);v.resources.address.poke(0x9000.U)
        val f=d.io.fragmentDraw.bits;zero(f);f.version.poke(1.U);identity(f.identity);f.shaderPc.poke(fpc.U)
        view(f.scratch,0x200000,fragmentPrefix);f.varyingSlots.poke(fragmentVaryings.U);f.colorSlots.poke(1.U);f.colorMask.poke(1.U)
        f.sampler.texWidth.poke(1.U);f.sampler.texHeight.poke(1.U);f.resources.version.poke(1.U);f.resources.address.poke(0x49000.U)
        d.io.vertexDraw.valid.poke(true.B);d.io.fragmentDraw.valid.poke(true.B)
        until(d.io.vertexDraw.ready.peek().litToBoolean && d.io.fragmentDraw.ready.peek().litToBoolean);tick()
        d.io.vertexDraw.valid.poke(false.B);d.io.fragmentDraw.valid.poke(false.B)
        v.resources.version.poke(3.U);v.resources.address.poke(0xffffffffL.U);f.resources.version.poke(3.U);f.resources.address.poke(0xffffffffL.U)
        for(q<-0 until 32){val in=d.io.in.bits;zero(in);identity(in.values.identity);in.values.sequence.poke(q.U)
          in.values.varyingSlots.poke(fragmentVaryings.U)
          for((lane,i)<-in.values.lanes.zipWithIndex){
            lane.x.poke((q*2+i%2).S);lane.y.poke((i/2).S);lane.covered.poke(true.B);lane.coverageMask.poke(1.U)
            lane.windowDepth.poke(0x3f000000.U);lane.fragCoordW.poke(0x3f800000.U);lane.perspectiveValid.poke(true.B)
            lane.reciprocalW.significand.poke((1<<23).U);lane.reciprocalW.exponent.poke(0.S)
            lane.varyings(0)(0).poke((q*4+i).U)
          }
          d.io.in.valid.poke(true.B);until(d.io.in.ready.peek().litToBoolean);tick();d.io.in.valid.poke(false.B)
        }
        identity(d.io.inputEnd.bits.identity);d.io.inputEnd.bits.quads.poke(32.U);d.io.inputEnd.valid.poke(true.B)
        until(d.io.inputEnd.ready.peek().litToBoolean);tick();d.io.inputEnd.valid.poke(false.B)
        until(d.io.queued.forall(_.peek().litToBoolean));d.io.start.poke(true.B)
        until(d.io.vertexCompletion.valid.peek().litToBoolean && d.io.fragmentCompletion.valid.peek().litToBoolean)
        d.io.vertexCompletion.bits.status.expect((if(badVertex)3 else 0).U)
        d.io.fragmentCompletion.bits.status.expect((if(badFragment)3 else 0).U)
        if(!badVertex){
          d.io.vertexCompletion.bits.localBatches.expect((if(spill)0 else 1).U)
          d.io.vertexCompletion.bits.spilledBatches.expect((if(spill)1 else 0).U)
        }
        if(!badFragment){
          d.io.fragmentCompletion.bits.localBatches.expect((if(spill)0 else 1).U)
          d.io.fragmentCompletion.bits.spilledBatches.expect((if(spill)1 else 0).U)
        }
        assert(vertices==(if(badVertex)0 else 128) && quads==(if(badFragment)0 else 32) && overlap>0)
        val heldV=d.io.vertexCompletion.bits.peek().litValue;val heldF=d.io.fragmentCompletion.bits.peek().litValue
        for(_<-0 until 9){tick();assert(d.io.vertexCompletion.bits.peek().litValue==heldV && d.io.fragmentCompletion.bits.peek().litValue==heldF)}
        d.io.vertexCompletion.ready.poke(true.B);d.io.fragmentCompletion.ready.poke(true.B);tick()
        d.io.vertexCompletion.ready.poke(false.B);d.io.fragmentCompletion.ready.poke(false.B)
        until(d.io.graphicsIdle.peek().litToBoolean && pending.isEmpty && offered.isEmpty)
        assert(owners.forall(_.isEmpty))
        for(stage<-0 until 2 if !(if(stage==0)badVertex else badFragment))
          assert(drawServiced(stage).toSet==(0 until 32).toSet,s"missing nonfallback banked bindings stage=$stage mode=$mode")
        d.io.poolDrained.expect(true.B);d.io.reservedBytes.expect(0.U)
      }
      assert(overlappingRoots.size==2)
      for(stage<-0 until 2;unit<-0 until 32)assert(dataLines(views(stage)(unit).address& ~BigInt(63)),s"binding was not fetched stage=$stage unit=$unit")
      assert(dataLines(BigInt("ffffffc0",16)),"exclusive4GiB table boundary was not exercised")
      assert(serviced.forall(_.toSet==(0 until 32).toSet))
      assert(selectedClusters.toSet==Set(0,1) && clusterReads.forall(_>0),"both physical texture clusters must issue data reads")
      blockerRunning=false
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      until(d.io.idle.peek().litToBoolean)
      d.io.poolDrained.expect(true.B);d.io.reservedBytes.expect(0.U)
      info(s"NATIVE_BANKED_TEXTURE_BINDING_CAPACITY payload=$profile stages=2 CUs=4 clusters=2 textureClients=5 rowsPerCluster=128 nonfallbackBindings=64 lanes=16 warps=8 invocationsPerStage=128 bindingsPerStage=32 distinctBindings=64 drawsPerStage=5 exactAllComponents=true concurrentOwnership=true upperBindingTableBoundary4GiB=true invalid32Faults=true peerProgress=true heldCompletion=true scratchAndPayloadDrain=true healthyReuse=true APIQualified=false clockQualified=false FPSQualified=false")
    }
  }
}

object QualifyNativeBankedTextureBindingCapacity {
  def main(args: Array[String]): Unit={
    require(args.length==2 && args(0)=="--reports" && !GraphicsTestResolution.productQualification)
    val suite=new NativeBankedTextureBindingCapacityQualification
    val count=if(sys.props.contains("opengpu.textureBindingPayload"))1 else 2
    require(suite.testNames.size==count)
    require(org.scalatest.tools.Runner.run(Array("-s",classOf[NativeBankedTextureBindingCapacityQualification].getName,"-oD","-u",args(1))))
    val report=scala.xml.XML.loadFile(new java.io.File(args(1),"TEST-"+classOf[NativeBankedTextureBindingCapacityQualification].getName+".xml"))
    val cases=report \\ "testcase"
    require((report \ "@tests").text==count.toString && cases.size==count && cases.map(n=>(n \ "@name").text).toSet==suite.testNames.toSet &&
      Seq("errors","failures","skipped").forall(a=>report.attribute(a).forall(_.text=="0")) && Seq("failure","error","skipped").forall(n=>(report \\ n).isEmpty))
    println(s"NATIVE_BANKED_TEXTURE_BINDING_CAPACITY PASS cases=$count APIQualified=false clockQualified=false FPSQualified=false")
  }
}
