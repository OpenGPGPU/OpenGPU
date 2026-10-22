package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class PostTransformVertexCacheSpec extends AnyFlatSpec {
  import WindowRecordTest._
  for((varyings,entries,blackBoxes)<-Seq((2,4,false),(16,4,false),(16,16,false),(16,16,true)))
  it should s"retain exact full-width outputs, instance keys and held responses across replacement and clear varyings=$varyings entries=$entries blackBoxes=$blackBoxes" in {
    val p=VertexShaderStageConfig(varyings=varyings)
    simulate(if(blackBoxes)new PostTransformVertexCache(p,entries,useSramBlackBoxes=true){
      val macroModelPresent=IO(Output(Bool()))
      private val resources=Module(new TileSramSimulationResources)
      macroModelPresent:=resources.io.present
    }else new PostTransformVertexCache(p,entries)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val random=new scala.util.Random(0x5238+varyings)
      case class Value(point: BigInt,written: Boolean,position: Vector[BigInt],varyings: Vector[BigInt])
      def value(): Value=Value(BigInt(32,random),random.nextBoolean(),Vector.fill(4)(BigInt(32,random)),Vector.fill(4*p.varyings)(BigInt(32,random)))
      def until(condition: =>Boolean): Unit={var cycles=0;while(!condition && cycles<512){d.clock.step();cycles+=1};assert(condition)}
      def key(k: VertexReuseKey,id: BigInt,instance: BigInt,base: BigInt): Unit={
        k.vertexId.poke(id.U);k.instanceId.poke(instance.U);k.baseInstance.poke(base.U)
      }
      def insert(id: BigInt,instance: BigInt,base: BigInt,v: Value): Unit={
        val r=d.io.update.bits;zero(r)
        r.vertexId.poke(id.U);r.instanceId.poke(instance.U);r.baseInstance.poke(base.U)
        r.pointSize.poke(v.point.U);r.pointSizeWritten.poke(v.written.B)
        r.position.zip(v.position).foreach{case(a,b)=>a.poke(b.U)}
        r.varyings.flatten.zip(v.varyings).foreach{case(a,b)=>a.poke(b.U)}
        d.io.update.valid.poke(true.B);until(d.io.update.ready.peek().litToBoolean);d.clock.step()
        d.io.update.valid.poke(false.B);zero(r);until(!d.io.busy.peek().litToBoolean)
      }
      def lookup(id: BigInt,instance: BigInt,base: BigInt,expected: Option[Value],allowMiss: Boolean=false): Boolean={
        key(d.io.lookup.bits,id,instance,base);d.io.lookup.valid.poke(true.B)
        until(d.io.lookup.ready.peek().litToBoolean);d.clock.step();d.io.lookup.valid.poke(false.B);zero(d.io.lookup.bits)
        until(d.io.result.valid.peek().litToBoolean)
        val hit=d.io.result.bits.hit.peek().litToBoolean
        if(!allowMiss)d.io.result.bits.hit.expect(expected.nonEmpty.B)
        (if(hit)expected else None) match {
          case Some(v)=>
            val r=d.io.result.bits.outputs;r.pointSize.expect(v.point.U);r.pointSizeWritten.expect(v.written.B)
            r.position.zip(v.position).foreach{case(a,b)=>a.expect(b.U)}
            r.varyings.flatten.zip(v.varyings).foreach{case(a,b)=>a.expect(b.U)}
          case None=>assert(d.io.result.bits.outputs.peek().litValue==0)
        }
        val held=d.io.result.bits.peek().litValue
        for(_<-0 until 7){d.io.lookup.valid.poke(true.B);d.io.update.valid.poke(true.B);d.clock.step()
          d.io.lookup.ready.expect(false.B);d.io.update.ready.expect(false.B);assert(d.io.result.bits.peek().litValue==held)}
        d.io.lookup.valid.poke(false.B);d.io.update.valid.poke(false.B)
        d.io.result.ready.poke(true.B);d.clock.step();d.io.result.ready.poke(false.B);d.io.busy.expect(false.B);hit
      }
      val values=Vector.fill(entries)(value())
      lookup(0,0,0,None)
      for(i<-0 until entries)insert(BigInt("ffff0000",16)+i,BigInt("80000001",16),BigInt("fffffffe",16),values(i))
      for(i<-0 until entries)lookup(BigInt("ffff0000",16)+i,BigInt("80000001",16),BigInt("fffffffe",16),Some(values(i)))
      lookup(BigInt("ffff0000",16),1,BigInt("fffffffe",16),None)
      lookup(BigInt("ffff0000",16),BigInt("80000001",16),BigInt("7ffffffe",16),None)
      val replacement=value();insert(BigInt("ffff0001",16),BigInt("80000001",16),BigInt("fffffffe",16),replacement)
      for(i<-0 until entries)lookup(BigInt("ffff0000",16)+i,BigInt("80000001",16),BigInt("fffffffe",16),Some(if(i==1)replacement else values(i)))
      val extra=value();insert(17,3,9,extra);lookup(17,3,9,Some(extra))
      val retained=(0 until entries).count(i=>lookup(BigInt("ffff0000",16)+i,BigInt("80000001",16),BigInt("fffffffe",16),
        Some(if(i==1)replacement else values(i)),allowMiss=true))
      assert(retained==entries-1,"replacement must evict exactly one distinct key")
      if(entries==16 && varyings==16)assert(PostTransformVertexCache.storageBytes(entries,varyings)==8192)
      d.io.clear.poke(true.B);d.clock.step();d.io.clear.poke(false.B)
      lookup(17,3,9,None);lookup(BigInt("ffff0001",16),BigInt("80000001",16),BigInt("fffffffe",16),None)
      insert(17,3,9,replacement);lookup(17,3,9,Some(replacement))
    }
  }
}
