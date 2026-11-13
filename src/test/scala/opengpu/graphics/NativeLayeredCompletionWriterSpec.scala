package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class NativeLayeredCompletionWriterSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  private val p=VertexShaderStageConfig()
  // Keep the complete wire capacity, using short aliases for the simulator's
  // DPI names. The byte oracle consumes the published schema, not RTL fields.
  private class Harness extends Module {
    val w=Module(new NativeLayeredCompletionWriter(GpuConfig(),p,32))
    val io=IO(new Bundle {
      val in=chiselTypeOf(w.io.in);val f=Input(chiselTypeOf(w.io.frame))
      val v=Input(new NativeLayeredCompletionView);val valid=Input(Bool())
      val out=chiselTypeOf(w.io.out);val req=chiselTypeOf(w.io.memoryRequest)
      val reply=chiselTypeOf(w.io.memoryResponse);val busy=Output(Bool())
    })
    w.io.in<>io.in;w.io.frame:=io.f;w.io.view:=io.v;w.io.viewValid:=io.valid
    io.out<>w.io.out;io.req<>w.io.memoryRequest;w.io.memoryResponse<>io.reply;io.busy:=w.io.busy
  }
  private case class Field(name:String,word:Int,bits:Int,signed:Boolean)
  private val schema={val source=scala.io.Source.fromFile("docs/abi/native-layered-graphics-v2.json")
    val text=try source.mkString finally source.close()
    val body=text.substring(text.indexOf("\"completion\":{\"magic\""))
    val field="""\{"name":"([^"]+)","word":(\d+),"bits":(\d+),"signed":(true|false)\}""".r
    field.findAllMatchIn(body).map(m=>Field(m.group(1),m.group(2).toInt,m.group(3).toInt,m.group(4)=="true")).toVector}
  it should "publish every full-width GNY2 word only through acknowledged writes and retain fault ownership for reuse" in {
    simulate(new Harness){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def until(label:String)(ready: =>Boolean):Unit={var n=0;while(!ready && n<2000){d.clock.step();n+=1};assert(ready,label)}
      def publish(base:BigInt,faultLine:Int = -1,enabled:Boolean=true):Unit={
        zero(d.io.in.bits);zero(d.io.f);zero(d.io.v);d.io.valid.poke(enabled.B)
        val coarse=Map[String,BigInt]("commandId"->0xfedcba98L,"identity.context"->7,"identity.pass"->8,
          "identity.generation"->9,"descriptorReadBytes"->((BigInt(1)<<63)+123),"drawReadBytes"->((BigInt(1)<<60)+64),
          "issuedDraws"->0xf1234567L,"completedDraws"->0xf1234567L,"status"->(if(enabled)0 else 1))
        def put(leaf:Data,bits:BigInt):Unit=leaf match {case s:SInt=>s.poke(bits.S);case u:UInt=>u.poke(bits.U)}
        for((name,leaf)<-WindowPrimitiveRecordLayout.leaves(d.io.in.bits))coarse.get(name).foreach(put(leaf,_))
        val frame=WindowPrimitiveRecordLayout.leaves(d.io.f).zipWithIndex.map{case((name,leaf),i)=>
          val width=leaf.getWidth;val raw=((BigInt(1)<<(width-1))|(BigInt(i+1)*0x102030405L))&((BigInt(1)<<width)-1)
          val value=leaf match{case _:SInt if raw.testBit(width-1)=>raw-(BigInt(1)<<width);case _=>raw}
          put(leaf,value);("frame."+name)->raw}.toMap
        d.io.v.base.poke(base.U);d.io.v.limit.poke((base+16384).U)
        d.io.v.allocationBase.poke(base.U);d.io.v.allocationLimit.poke((base+16384).U);d.io.v.writable.poke(true.B)
        val expected=Array.fill[BigInt](4096)(0);expected(0)=0x474e5902;expected(1)=16384;expected(2)=2
        val values=coarse++frame
        for(f<-schema){val n=(f.bits+31)/32;val raw=values.getOrElse(f.name,BigInt(0))
          val extended=if(f.signed && raw.testBit(f.bits-1))raw|(((BigInt(1)<<(32*n-f.bits))-1)<<f.bits)else raw
          for(i<-0 until n)expected(f.word+i)=(extended>>(32*i))&0xffffffffL}
        d.io.in.valid.poke(true.B);until("writer acceptance")(d.io.in.ready.peek().litToBoolean)
        d.clock.step();d.io.in.valid.poke(false.B)
        // A different submitting context and backing view cannot redirect the
        // already acquired completion owner, even while the output is held.
        zero(d.io.in.bits);zero(d.io.f);zero(d.io.v)
        if(enabled){val last=if(faultLine>=0)faultLine else 255
          for(line<-0 to last){until("completion write offer")(d.io.req.valid.peek().litToBoolean)
            d.io.out.valid.expect(false.B);d.io.req.bits.address.expect((base+64*line).U)
            d.io.req.bits.isWrite.expect(true.B);d.io.req.bits.byteMask.expect(((BigInt(1)<<64)-1).U)
            d.io.req.bits.transactionId.expect(0.U);d.io.req.bits.sizeLog2.expect(6.U)
            val word=(0 until 16).foldLeft(BigInt(0))((b,i)=>b|(expected(line*16+i)<<(32*i)))
            d.io.req.bits.writeData.expect(word.U)
            val held=d.io.req.bits.peek().litValue
            for(_<-0 until (if(line==0 || line==last)7 else 1)){d.clock.step();d.io.req.valid.expect(true.B)
              assert(d.io.req.bits.peek().litValue==held);d.io.out.valid.expect(false.B);d.io.in.ready.expect(false.B)}
            d.io.req.ready.poke(true.B);d.clock.step();d.io.req.ready.poke(false.B)
            for(_<-0 until (if(line==last)11 else 2)){d.clock.step();d.io.out.valid.expect(false.B);d.io.req.valid.expect(false.B)}
            zero(d.io.reply.bits);d.io.reply.bits.fault.poke((line==faultLine).B);d.io.reply.valid.poke(true.B)
            d.io.reply.ready.expect(true.B);d.clock.step();d.io.reply.valid.poke(false.B)
          }
        }
        until("held coarse completion")(d.io.out.valid.peek().litToBoolean)
        d.io.out.bits.commandId.expect(0xfedcba98L.U);d.io.out.bits.identity.context.expect(7.U)
        d.io.out.bits.status.expect((if(faultLine>=0)3 else if(enabled)0 else 1).U)
        if(faultLine>=0){d.io.out.bits.faultStage.expect(2.U);d.io.out.bits.faultAddress.expect((base+64*faultLine).U)}
        val out=d.io.out.bits.peek().litValue;d.io.in.valid.poke(true.B)
        for(_<-0 until 11){d.clock.step();assert(d.io.out.bits.peek().litValue==out)
          d.io.req.valid.expect(false.B);d.io.in.ready.expect(false.B)}
        d.io.in.valid.poke(false.B);d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B);d.io.busy.expect(false.B)
      }
      publish(BigInt("ffffc000",16));publish(0x40000,faultLine=173);publish(0x80000,enabled=false);publish(0xc0000)
    }
  }
}
