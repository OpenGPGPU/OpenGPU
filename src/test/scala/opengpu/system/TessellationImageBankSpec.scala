package opengpu.system

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.graphics._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TessellationImageBankOwnerSpec extends AnyFlatSpec {
  it should "retain a classified owner through delayed drain and retirement without resetting between domains" in {
    simulate(new TessellationImageBankOwner){d=>
      d.io.offer.foreach(_.poke(false.B));d.io.retired.poke(false.B);d.io.drained.poke(true.B)
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      for(domain<-Seq(0,2,1,2,0,1)){
        d.io.active.expect(false.B);d.io.grant.foreach(_.expect(false.B))
        d.io.offer(domain).poke(true.B);d.clock.step()
        d.io.active.expect(true.B);d.io.domain.expect(domain.U)
        for(i<-0 until 3)d.io.grant(i).expect((i==domain).B)
        // A held classified offer and backpressured old responses do not
        // release the owner. Nor does becoming idle before public retirement.
        d.io.drained.poke(false.B);d.clock.step(3)
        d.io.offer(domain).poke(false.B);d.io.offer((domain+1)%3).poke(true.B)
        d.clock.step(3);d.io.domain.expect(domain.U)
        d.io.offer((domain+1)%3).poke(false.B)
        d.io.drained.poke(true.B);d.clock.step(3);d.io.active.expect(true.B)
        d.io.retired.poke(true.B);d.clock.step();d.io.retired.poke(false.B)
        d.io.active.expect(false.B)
      }
    }
  }
}

/** Keep the complete production interface inside the harness. Exporting every
  * individual leaf to DPI creates thousands of redundant C++ accessor bodies.
  */
private class TessellationImageBankMuxHarness(usePayload:Boolean,images:Boolean=false) extends Module {
  val io=IO(new Bundle {
    val active=Input(Bool());val domain=Input(UInt(2.W));val pattern=Input(UInt(16.W))
    val matches=Output(Bool());val checkedLeaves=Output(UInt(32.W))
  })
  private val cfg=GpuConfig(lanes=4,warps=2)
  private val p=VertexShaderStageConfig(VertexInputFetchConfig(2,2),2)
  private val payload=if(usePayload)Some(ShaderPayloadConfig(capacityBytes=4096,maxPayloadBytes=1024,owners=5,ports=8))else None
  private val mux=Module(new TessellationImageBankMux(cfg,p,SharedCuShaderConfig(2,taskIdWidth=4),4,payload,fragmentImageAvailable=images))
  mux.io.active:=io.active;mux.io.domain:=io.domain
  private def leaves(d:Data,path:String=""):Vector[(String,Data)]=d match {
    case r:Record=>r.elements.toVector.flatMap{case(n,v)=>leaves(v,s"$path.$n")}
    case v:Vec[_]=>v.zipWithIndex.toVector.flatMap{case(x,i)=>leaves(x,s"$path[$i]")}
    case _:UInt | _:SInt | _:EnumType=>Vector(path->d)
    case other=>throw new IllegalArgumentException(s"unsupported test leaf $other")
  }
  private def pattern(d:Data,name:String,tag:Int):UInt={
    val shift=(name.hashCode+tag)&15
    val rotated=(chisel3.util.Cat(io.pattern,io.pattern)>>shift)(15,0)
    val chunks=(0 until (d.getWidth+15)/16).map(i=>rotated ^ ((i*0x9e37)&0xffff).U(16.W))
    chisel3.util.Cat(chunks.reverse)(d.getWidth-1,0)
  }
  private val checks=scala.collection.mutable.ArrayBuffer.empty[Bool]
  for(e<-0 until 4){
    val bank=leaves(mux.io.bank(e)).toMap
    val clients=mux.io.clients.map(c=>leaves(c(e)).toMap)
    for((name,port)<-bank){
      if(DataMirror.directionOf(port)==ActualDirection.Input){
        val value=pattern(port,name,12+e);port:=value.asTypeOf(port)
        for(domain<-0 until 3){
          checks+=clients(domain)(name).asUInt===Mux(io.active && io.domain===domain.U,value,0.U)
        }
      }else{
        val values=(0 until 3).map{domain=>
          val client=clients(domain)(name);val value=pattern(client,name,e*3+domain)
          client:=value.asTypeOf(client);value
        }
        val selected=chisel3.util.Mux1H((0 until 3).map(d=>(io.active && io.domain===d.U)->values(d)))
        checks+=port.asUInt===selected
      }
    }
  }
  io.matches:=checks.reduce(_ && _);io.checkedLeaves:=checks.size.U
}

class TessellationImageBankMuxSpec extends AnyFlatSpec {
  for(usePayload<-Seq(false,true);images<-Seq(false,true))
  it should s"route every engine/control/reply leaf exclusively to one retained domain with payload=$usePayload images=$images" in {
    simulate(new TessellationImageBankMuxHarness(usePayload,images)){d=>
      d.io.active.poke(false.B);d.io.domain.poke(0.U);d.io.pattern.poke(0.U)
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      assert(d.io.checkedLeaves.peek().litValue>1000,"the full four-engine interface must be checked")
      for(pattern<-Seq(BigInt(0),BigInt(65535))++(0 until 16).map(BigInt(1)<<_);
          selected<-Seq(-1,0,2,1,0,-1,2)){
        d.io.pattern.poke(pattern.U);d.io.active.poke((selected>=0).B);d.io.domain.poke(math.max(0,selected).U)
        d.io.matches.expect(true.B);d.clock.step(2);d.io.matches.expect(true.B)
      }
    }
  }
}
