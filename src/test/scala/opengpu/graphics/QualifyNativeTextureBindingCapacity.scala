package opengpu.graphics

import chisel3._
import opengpu.testutil.GraphicsTestResolution
import opengpu.testutil.GpuSim._
import org.scalatest.DoNotDiscover
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private[graphics] object NativeTextureBindingCapacityProgram {
  private def addi(rd: Int,rs: Int,n: Int): BigInt=
    (BigInt(n&4095)<<20)|(BigInt(rs)<<15)|(BigInt(rd)<<7)|0x13
  private def address(rd: Int,rs: Int,n: Int): Seq[BigInt]={
    val hi=(n+2048)>>12;val lo=n-(hi<<12)
    if(hi==0)Seq(addi(rd,rs,lo))else Seq((BigInt(hi)<<12)|(BigInt(rd)<<7)|0x37,
      (BigInt(rd)<<20)|(BigInt(rs)<<15)|(BigInt(rd)<<7)|0x33,addi(rd,rd,lo))
  }
  private def memory(base: Int,v: Int,write: Boolean): BigInt=
    (BigInt(1)<<25)|(BigInt(base)<<15)|(BigInt(6)<<12)|(BigInt(v)<<7)|(if(write)0x27 else 7)
  private def broadcast(v: Int,x: Int): BigInt=
    (BigInt(0x17)<<26)|(BigInt(1)<<25)|(BigInt(x)<<15)|(BigInt(4)<<12)|(BigInt(v)<<7)|0x57
  def code(stage: Int,attributes: Int,varyings: Int,bad: Boolean): Vector[BigInt]={
    val stride=512;val inputs=if(stage==0)4*attributes+3 else 6+4*varyings
    val code=mutable.ArrayBuffer[BigInt](BigInt("00241393",16),BigInt("007083b3",16),BigInt("c1087057",16))
    def load(array: Int,v: Int): Unit={code++=address(5,7,array*stride);code+=memory(5,v,false)}
    def store(array: Int,v: Int): Unit={code++=address(6,7,array*stride);code+=memory(6,v,true)}
    load(if(stage==0)4*attributes else 6,6)
    if(stage==0){store(inputs,6);for(c<-1 until 4){load(c,1);store(inputs+c,1)}}
    if(bad){code+=addi(13,0,32);code+=broadcast(6,13)}else{
      code+=addi(13,0,31)
      code+=(BigInt(0x09)<<26)|(BigInt(1)<<25)|(BigInt(6)<<20)|(BigInt(13)<<15)|(BigInt(4)<<12)|(BigInt(6)<<7)|0x57
    }
    if(stage==0)store(inputs+8,6)
    code++=Seq((BigInt(0x3f400)<<12)|(BigInt(12)<<7)|0x37,broadcast(10,12),broadcast(11,0),
      addi(13,0,1),broadcast(5,13))
    for(pair<-0 until 8){val v=if(pair==0)10 else 11
      code+=(BigInt(3)<<26)|(BigInt(1)<<25)|(BigInt(v)<<20)|(BigInt(v)<<15)|(BigInt(pair)<<12)|0x2b}
    for(c<-0 until 4){
      code+=(BigInt(4)<<26)|(BigInt(1)<<25)|(BigInt(5)<<20)|(BigInt(6)<<15)|(BigInt(c)<<12)|(BigInt(7)<<7)|0x2b
      store(inputs+(if(stage==0)4 else 0)+c,7)
    }
    if(stage==1){
      store(inputs+4,11)
      code++=Seq(addi(14,0,5),broadcast(12,14));store(inputs+5,12)
    }
    code+=BigInt("30500073",16);code.toVector
  }
}

/** Explicit full-thread stage-capacity qualification; never auto-discovered. */
@DoNotDiscover
class NativeTextureBindingCapacityQualification extends AnyFlatSpec {
  import WindowRecordTest.{zero,identity}
  private val profiles=Vector("local","spill")
  private val selected=sys.props.get("opengpu.textureBindingPayload").map{p=>require(profiles.contains(p));Vector(p)}.getOrElse(profiles)
  for(profile<-selected)
  it should s"retain thirty-two distinct texture bindings per concurrent native CU stage payload=$profile" in {
    require(!GraphicsTestResolution.productQualification)
    simulate(new NativeUniformConcurrentHarness(false)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
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
          NativeTextureFetchTestData.install(put,(a,b)=>bytes(a)=b,unit,3,0,stage,workIdentity,raw,offset)
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
      def tick(): Unit={
        assert(cycle<2000000,"full native binding qualification exceeded its correctness guard")
        val owned=d.io.owned.map(_.peek().litValue)
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
      val spill=profile=="spill";val attributes=if(spill)16 else 2;val fragmentVaryings=if(spill)16 else 1
      val vertexPrefix=d.vertex.prefixBytes(128,attributes,2);val fragmentPrefix=d.fragment.prefixBytes(128,fragmentVaryings,1)
      assert((vertexPrefix>16384)==spill && (fragmentPrefix>16384)==spill)
      for((mode,iteration)<-Vector("healthy","bad-vertex","reuse","bad-fragment","reuse").zipWithIndex){
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
        until(d.io.idle.peek().litToBoolean);assert(pending.isEmpty && offered.isEmpty)
        d.io.poolDrained.expect(true.B);d.io.reservedBytes.expect(0.U)
      }
      assert(overlappingRoots.size==2)
      for(stage<-0 until 2;unit<-0 until 32)assert(dataLines(views(stage)(unit).address& ~BigInt(63)),s"binding was not fetched stage=$stage unit=$unit")
      assert(dataLines(BigInt("ffffffc0",16)),"exclusive4GiB table boundary was not exercised")
      info(s"NATIVE_TEXTURE_BINDING_CAPACITY payload=$profile stages=2 CUs=2 lanes=16 warps=8 invocationsPerStage=128 bindingsPerStage=32 distinctBindings=64 drawsPerStage=5 exactAllComponents=true concurrentOwnership=true upperBindingTableBoundary4GiB=true invalid32Faults=true peerProgress=true heldCompletion=true scratchAndPayloadDrain=true healthyReuse=true APIQualified=false clockQualified=false FPSQualified=false")
    }
  }
}

object QualifyNativeTextureBindingCapacity {
  def main(args: Array[String]): Unit={
    require(args.length==2 && args(0)=="--reports" && !GraphicsTestResolution.productQualification)
    val suite=new NativeTextureBindingCapacityQualification
    val count=if(sys.props.contains("opengpu.textureBindingPayload"))1 else 2
    require(suite.testNames.size==count)
    require(org.scalatest.tools.Runner.run(Array("-s",classOf[NativeTextureBindingCapacityQualification].getName,"-oD","-u",args(1))))
    val report=scala.xml.XML.loadFile(new java.io.File(args(1),"TEST-"+classOf[NativeTextureBindingCapacityQualification].getName+".xml"))
    val cases=report \\ "testcase"
    require((report \ "@tests").text==count.toString && cases.size==count && cases.map(n=>(n \ "@name").text).toSet==suite.testNames.toSet &&
      Seq("errors","failures","skipped").forall(a=>report.attribute(a).forall(_.text=="0")) && Seq("failure","error","skipped").forall(n=>(report \\ n).isEmpty))
    println(s"NATIVE_TEXTURE_BINDING_CAPACITY PASS cases=$count APIQualified=false clockQualified=false FPSQualified=false")
  }
}
