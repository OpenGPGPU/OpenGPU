package opengpu.system

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class TessellationThreeDomainMemoryFabricSpec extends AnyFlatSpec {
  private def zero(d:Data):Unit=d match {
    case r:Record=>r.elements.values.foreach(zero);case v:Vec[_]=>v.foreach(zero)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case _=>()
  }
  it should "route distinct IDs and retain reordered replies across backpressure" in {
    simulate(new TessellationThreeDomainMemoryFabric(GpuConfig(lanes=4,warps=2),2,2,2)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val inputs=Seq(d.io.isolineRequest,d.io.triangleRequest,d.io.quadRequest)
      val outputs=Seq(d.io.isolineResponse,d.io.triangleResponse,d.io.quadResponse)
      val expected=Seq(1,3,5);val pending=mutable.Set(0,1,2);val seen=mutable.Map.empty[Int,BigInt]
      for(i<-0 until 3){inputs(i).bits.transactionId.poke(1.U);inputs(i).bits.address.poke((0x1000+i*0x1000).U)}
      var cycle=0
      while((pending.nonEmpty || seen.size<3) && cycle<100){
        for(i<-0 until 3)inputs(i).valid.poke(pending(i).B)
        d.io.memoryRequest.ready.poke((cycle>=6 && cycle%3!=0).B)
        val accepted=pending.filter(i=>inputs(i).ready.peek().litToBoolean).toSeq
        if(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean){
          val id=d.io.memoryRequest.bits.transactionId.peek().litValue.toInt
          assert(!seen.contains(id));seen(id)=d.io.memoryRequest.bits.address.peek().litValue
        }
        d.clock.step();pending--=accepted;cycle+=1
      }
      assert(cycle<100 && seen.toMap==Map(1->BigInt(0x1000),3->BigInt(0x2000),5->BigInt(0x3000)))
      inputs.foreach(_.valid.poke(false.B));d.io.drained.expect(false.B)
      for(i<-Seq(2,0,1)){
        d.io.memoryResponse.bits.transactionId.poke(expected(i).U);d.io.memoryResponse.bits.readData.poke((0x90+i).U)
        d.io.memoryResponse.bits.fault.poke((i==2).B);d.io.memoryResponse.valid.poke(true.B)
        for(_<-0 until 3){
          outputs(i).valid.expect(true.B);outputs(i).bits.transactionId.expect(1.U)
          outputs(i).bits.readData.expect((0x90+i).U);outputs(i).bits.fault.expect((i==2).B)
          for(other<-0 until 3 if other!=i)outputs(other).valid.expect(false.B)
          d.io.memoryResponse.ready.expect(false.B);d.io.drained.expect(false.B);d.clock.step()
        }
        outputs(i).ready.poke(true.B);d.io.memoryResponse.ready.expect(true.B);d.clock.step()
        outputs(i).ready.poke(false.B);d.io.memoryResponse.valid.poke(false.B)
      }
      d.clock.step();d.io.drained.expect(true.B)
    }
  }
}
