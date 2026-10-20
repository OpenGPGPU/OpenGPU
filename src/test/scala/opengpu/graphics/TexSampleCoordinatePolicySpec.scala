package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class TexSampleCoordinatePolicySpec extends AnyFlatSpec {
  behavior of "Texture instruction coordinate policy"
  private case class Binding(base: Long=0x20000L,w: Int=7,h: Int=5,u: Int=2,v: Int=0,
    signed: Boolean=true,minimum: Int=0,maximum: Int=0,bias: Int=0,format: Int=0) {
    def fault: Boolean=w==0 || h==0 || u==3 || v==3 || minimum>maximum
    def level(n: Int): Binding={var a=base;var x=w;var y=h
      for(_<-0 until n){a+=x.toLong*y*4;x=(x/2).max(1);y=(y/2).max(1)}
      copy(base=a,w=x,h=y)
    }
  }
  private def rawWord(a: Long,operation: Int): BigInt=(0 until 4).foldLeft(BigInt(0)){(v,c)=>
    (v<<8)|BigInt((((a>>2)*(13+c*6))^(a>>(7+c))^(operation*43+c*71+19))&255)
  }
  private def decoded(a: Long,operation: Int,format: Int): BigInt={
    val word=rawWord(a,operation);val b=(0 until 4).map(i=>(word>>(i*8))&255)
    val channels=format match {
      case 1=>Seq(b(0),b(1),b(2),b(3))
      case 2=>Seq(b(2),b(1),b(0),b(3))
      case 3=>Seq(b(0),b(1),b(2),BigInt(255))
      case 4=>Seq(b(2),b(1),b(0),BigInt(255))
      case _=>Seq(b(3),b(2),b(1),b(0))
    };channels.foldLeft(BigInt(0))((v,c)=>(v<<8)|c)
  }
  private def coordinate(raw: Long,signed: Boolean): Long=
    if(signed && (raw&0x80000000L)!=0)raw-0x100000000L else raw
  private def bilinear(b: Binding,u: Long,v: Long,operation: Int): BigInt={
    def axis(raw: Long,n: Int,mode: Int): (Int,Int,Int)={
      val q=(coordinate(raw,b.signed)*n>>8)-128;val i=Math.floorDiv(q,256L)
      def wrap(x: Long): Int=mode match {
        case 0=>Math.floorMod(x,n.toLong).toInt
        case 1=>x.max(0).min(n-1).toInt
        case 2=>val p=Math.floorMod(x,2L*n);(if(p<n)p else 2L*n-1-p).toInt
      };(wrap(i),wrap(i+1),Math.floorMod(q,256L).toInt)
    }
    val(x0,x1,fx)=axis(u,b.w,b.u);val(y0,y1,fy)=axis(v,b.h,b.v)
    val words=Seq((x0,y0),(x1,y0),(x0,y1),(x1,y1)).map{case(x,y)=>decoded(b.base+(y.toLong*b.w+x)*4,operation,b.format)}
    val weights=Seq((256-fx)*(256-fy),fx*(256-fy),(256-fx)*fy,fx*fy)
    (0 until 4).foldLeft(BigInt(0)){(out,c)=>
      (out<<8)|(words.zip(weights).map{case(w,n)=>((w>>(24-c*8))&255)*n}.sum>>16)
    }
  }
  private def sample(b: Binding,u: Long,v: Long,lod: (Int,Int),operation: Int): BigInt={
    val(level,fraction)=lod;val first=bilinear(b.level(level),u,v,operation)
    if(fraction==0 || level==b.maximum)first else {
      val second=bilinear(b.level(level+1),u,v,operation)
      (0 until 4).foldLeft(BigInt(0)){(out,c)=>val shift=24-c*8
        (out<<8)|(((((first>>shift)&255)*(256-fraction)+((second>>shift)&255)*fraction)>>8)&255)
      }
    }
  }
  private def lod(b: Binding,u: Seq[Long],v: Seq[Long]): (Int,Int)={
    if(b.minimum==b.maximum)return(b.minimum,0)
    val edges=Seq((1,0),(3,2),(2,0),(3,1))
    val rho=(edges.map{case(a,c)=>BigInt((coordinate(u(a),b.signed)-coordinate(u(c),b.signed)).abs)*b.w}++
      edges.map{case(a,c)=>BigInt((coordinate(v(a),b.signed)-coordinate(v(c),b.signed)).abs)*b.h}).max
    val automatic=(rho.bitLength-1-16).max(0)
    val biased=automatic+b.bias;val level=biased.max(b.minimum).min(b.maximum)
    val normal=rho>>automatic
    val fraction=if(biased<b.minimum || biased>b.maximum || normal<=65536 || level==b.maximum)0 else
      math.floor(math.log((65536+((normal>>8)&255).toDouble*256)/65536)/math.log(2)*256).toInt
    (level,fraction)
  }
  for(lanes<-Seq(1,2,4,8,16,32))
  it should s"capture signed scalar vector prepared and implicit LOD policies with $lanes lanes" in {
    val config=GpuConfig(lanes=lanes,warps=2)
    simulate(new TexSampleUnit(config,coordinatePolicyInput=true)){d=>
      PointLineReference.zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val full=(BigInt(1)<<lanes)-1
      val sparse=(0 until lanes).filter(_%3!=0).foldLeft(BigInt(0))((m,i)=>m.setBit(i))
      var operation=0;var vectors=0;var scalars=0;var faults=0;var empty=0;var reads=0;var reordered=0;var mipCases=0
      val uv=Vector[Long](0xffffffffL,0,0x7fff,0x8000,0xffff8000L,0x10000,0x10001,0x7fffffffL,0x80000000L)
      def run(b0: Binding,vector: Boolean=true,mask: BigInt=full,
        us: Seq[Long]=Vector.empty,vs: Seq[Long]=Vector.empty): Unit={
        val b=b0.copy(format=operation%5)
        val u=if(us.nonEmpty)us else Vector.tabulate(lanes)(i=>uv((i+operation)%uv.size))
        val v=if(vs.nonEmpty)vs else Vector.tabulate(lanes)(i=>uv((i*5+operation+3)%uv.size))
        val old=Vector.tabulate(lanes)(i=>BigInt("dead0000",16)+operation*64+i)
        val partialQuad=vector && lanes<4 && b.minimum!=b.maximum
        val fault=(b.fault || partialQuad) && (!vector || mask!=0)
        val pc=0x400+operation*4
        d.io.texBase.poke(b.base.U);d.io.texWidth.poke(b.w.U);d.io.texHeight.poke(b.h.U)
        d.io.texFormat.poke(b.format.U);d.io.wrapClamp.poke(false.B)
        d.io.texMaxLevel.poke(b.maximum.U);d.io.minLevel.poke(b.minimum.U);d.io.lodBias.poke(b.bias.S)
        d.io.coordinatePolicy.get.wrapU.poke(b.u.U);d.io.coordinatePolicy.get.wrapV.poke(b.v.U)
        d.io.coordinatePolicy.get.signed.poke(b.signed.B)
        d.io.vectorIn.bits.poke(0.U.asTypeOf(d.io.vectorIn.bits));d.io.in.bits.poke(0.U.asTypeOf(d.io.in.bits))
        d.io.vectorIn.bits.issued.decode.warpId.poke(1.U);d.io.vectorIn.bits.issued.decode.pc.poke(pc.U)
        d.io.vectorIn.bits.issued.decode.activeMask.poke(full.U)
        d.io.vectorIn.bits.issued.decode.instruction.poke("h062081ab".U)
        d.io.vectorIn.bits.executionMask.poke(mask.U)
        for(i<-0 until lanes){d.io.vectorIn.bits.issued.vs1Data(i).poke(u(i).U);d.io.vectorIn.bits.issued.vs2Data(i).poke(v(i).U);d.io.vectorIn.bits.issued.oldVdData(i).poke(old(i).U)}
        d.io.in.bits.decode.warpId.poke(1.U);d.io.in.bits.decode.pc.poke(pc.U)
        d.io.in.bits.decode.activeMask.poke(full.U);d.io.in.bits.decode.instruction.poke("h0020828b".U)
        d.io.in.bits.rs1Data.poke(u.head.U);d.io.in.bits.rs2Data.poke(v.head.U)
        d.io.vectorIn.valid.poke(vector.B);d.io.in.valid.poke((!vector).B)
        if(vector)d.io.vectorIn.ready.expect(true.B)else d.io.in.ready.expect(true.B)
        d.io.vectorCommit.ready.poke(false.B);d.io.commit.ready.poke(false.B)
        d.clock.step();d.io.vectorIn.valid.poke(false.B);d.io.in.valid.poke(false.B)
        d.io.vectorIn.bits.poke(0.U.asTypeOf(d.io.vectorIn.bits));d.io.in.bits.poke(0.U.asTypeOf(d.io.in.bits))
        // Every control is allowed to change immediately after instruction admission.
        d.io.texBase.poke("he0000000".U);d.io.texWidth.poke(0.U);d.io.texHeight.poke(0.U)
        d.io.texFormat.poke(((b.format+1)%5).U);d.io.wrapClamp.poke(true.B)
        d.io.texMaxLevel.poke(0.U);d.io.minLevel.poke(15.U);d.io.lodBias.poke(15.S)
        d.io.coordinatePolicy.get.wrapU.poke(3.U);d.io.coordinatePolicy.get.wrapV.poke(3.U)
        d.io.coordinatePolicy.get.signed.poke((!b.signed).B)
        val expected=Vector.tabulate(lanes){i=>
          if(vector && !mask.testBit(i))old(i)
          else if(fault)BigInt(0)
          else {
            val selected=if(!vector)(b.bias.max(b.minimum).min(b.maximum),0)
              else if(b.minimum==b.maximum)(b.minimum,0)
              else lod(b,u.slice(i/4*4,i/4*4+4),v.slice(i/4*4,i/4*4+4))
            sample(b,u(i),v(i),selected,operation)
          }
        }
        val pending=mutable.ArrayBuffer.empty[(Long,Int)];var reply: Option[Long]=None
        var cycle=0;val before=reads
        def valid: Boolean=if(vector)d.io.vectorCommit.valid.peek().litToBoolean else d.io.commit.valid.peek().litToBoolean
        while(!valid && cycle<5000){
          d.io.mem.req.ready.poke((cycle%5!=0).B)
          if(reply.isEmpty && cycle%3==0){
            val eligible=pending.indices.filter(i=>pending(i)._2<=cycle)
            if(eligible.nonEmpty){val i=eligible.last;if(i>0)reordered+=1;reply=Some(pending.remove(i)._1)}
          }
          d.io.mem.resp.valid.poke(reply.nonEmpty.B)
          reply.foreach{a=>d.io.mem.resp.bits.addr.poke(a.U);d.io.mem.resp.bits.data.poke(rawWord(a,operation).U);d.io.mem.resp.bits.write.poke(false.B)}
          val response=reply.nonEmpty && d.io.mem.resp.ready.peek().litToBoolean
          if(d.io.mem.req.valid.peek().litToBoolean && d.io.mem.req.ready.peek().litToBoolean){
            val a=d.io.mem.req.bits.addr.peek().litValue.toLong
            assert(!fault && (!vector || mask!=0))
            assert((0 to b.maximum).exists{l=>val n=b.level(l);a>=n.base && a<n.base+n.w.toLong*n.h*4 && (a-n.base)%4==0},s"read escaped captured binding $a $b")
            pending+=((a,cycle+3));reads+=1
          }
          d.clock.step();if(response)reply=None;cycle+=1
        }
        assert(valid && pending.isEmpty && reply.isEmpty,s"texture instruction failed to drain: lanes=$lanes op=$operation")
        d.io.mem.resp.valid.poke(false.B)
        def check(): Unit={
          d.io.coordinateFault.get.expect(fault.B)
          if(vector){
            d.io.vectorCommit.valid.expect(true.B);d.io.vectorCommit.bits.writesVd.expect((!fault).B)
            d.io.vectorCommit.bits.writeback.warpId.expect(1.U);d.io.vectorCommit.bits.writeback.vd.expect(3.U)
            d.io.vectorCommit.bits.pc.expect(pc.U);d.io.vectorCommit.bits.warpActiveMask.expect(full.U)
            for(i<-0 until lanes)d.io.vectorCommit.bits.writeback.data(i).expect(expected(i).U)
          }else{
            d.io.commit.valid.expect(true.B);d.io.commit.bits.writeRd.expect((!fault).B)
            d.io.commit.bits.warpId.expect(1.U);d.io.commit.bits.rd.expect(5.U)
            d.io.commit.bits.nextPc.expect((pc+4).U);d.io.commit.bits.activeMask.expect(full.U)
            d.io.commit.bits.data.expect(expected.head.U)
          }
        }
        val held=if(vector)d.io.vectorCommit.bits.peek().litValue else d.io.commit.bits.peek().litValue
        for(_<-0 until 5){check();assert((if(vector)d.io.vectorCommit.bits.peek().litValue else d.io.commit.bits.peek().litValue)==held)
          d.io.in.ready.expect(false.B);d.io.vectorIn.ready.expect(false.B);d.io.mem.req.valid.expect(false.B);d.clock.step()}
        if(vector)d.io.vectorCommit.ready.poke(true.B)else d.io.commit.ready.poke(true.B)
        d.clock.step();d.io.vectorCommit.ready.poke(false.B);d.io.commit.ready.poke(false.B)
        if(fault){assert(reads==before);faults+=1};if(vector && mask==0){assert(reads==before);empty+=1}
        if(vector)vectors+=1 else scalars+=1
        operation+=1
      }
      for(signed<-Seq(false,true);u<-0 to 2;v<-0 to 2){
        val b=Binding(u=u,v=v,signed=signed)
        run(b,mask=Seq(full,sparse,BigInt(1)<<(lanes-1))(operation%3))
        run(b,vector=false)
      }
      run(Binding(w=16383,h=16383,u=0,v=2));run(Binding(w=16383,h=16382,u=2,v=1,signed=false))
      run(Binding(w=1,h=1,u=1,v=2));run(Binding(w=7,h=5,minimum=2,maximum=2))
      val invalid=Seq(Binding(u=3),Binding(v=3),Binding(w=0),Binding(h=0),Binding(minimum=2,maximum=1))
      for(b<-invalid){run(b);run(b,vector=false)}
      run(Binding(u=3,w=0),mask=0);run(Binding(),mask=0);run(Binding());run(Binding(),vector=false)
      run(Binding(maximum=3,bias=2),vector=false)
      run(Binding(minimum=1,maximum=3,bias= -16),vector=false)
      if(lanes<4){run(Binding(maximum=1));run(Binding(maximum=1),mask=0)}
      if(lanes>=4){
        def repeated(q: Seq[Long]): Seq[Long]=Vector.tabulate(lanes)(i=>q(i%4))
        val crossing=repeated(Seq(0xffffe000L,0x4000L,0xffffe000L,0x4000L))
        val zero=Seq.fill(lanes)(0L)
        run(Binding(w=4,h=4,maximum=3),us=crossing,vs=zero);mipCases+=1
        run(Binding(w=4,h=4,signed=false,maximum=3),us=crossing,vs=zero);mipCases+=1
        val endpoints=repeated(Seq(0x80000000L,0x7fffffffL,0x80000000L,0x7fffffffL))
        run(Binding(w=16383,h=1,maximum=15,bias= -16),us=endpoints,vs=zero);mipCases+=1
        val unsigned=repeated(Seq(0L,0xffffffffL,0L,0xffffffffL))
        run(Binding(w=16383,h=1,signed=false,maximum=15,bias= -16),us=unsigned,vs=zero);mipCases+=1
        run(Binding(w=8192,h=1,maximum=4,bias=15),us=endpoints,vs=zero);mipCases+=1
        val manyU=Vector.tabulate(lanes){i=> val q=i/4;val step=Seq(0x6000L,0x10000L,0x30000L,0x70000L)(q%4)
          (0xffff0000L+(if(i%2==1)step else 0))&0xffffffffL}
        val manyV=Vector.tabulate(lanes){i=>if(i%4>=2)0x10000L else 0xffff8000L}
        run(Binding(w=17,h=9,maximum=4,bias= -2),mask=full,us=manyU,vs=manyV);mipCases+=1
        run(Binding(w=17,h=9,maximum=4,minimum=1,bias= -16),mask=BigInt(1),us=manyU,vs=manyV);mipCases+=1
      }
      assert(faults==(if(lanes<4)11 else 10) && empty>=2)
      info(s"TEXTURE_EXECUTE_POLICY lanes=$lanes vectors=$vectors scalars=$scalars faults=$faults emptyVectors=$empty reads=$reads reordered=$reordered implicitLodCases=$mipCases partialQuadFaults=${if(lanes<4)1 else 0} capturedAllBindings=true signedCrossing=true fullGradientBeforeBias=true mixedAxisModes=9 formats=5 heldCommitFault=true suppressFaultWrites=true noInvalidReads=true rebindingInvalidates=true healthyReuse=true")
    }
  }
  it should "arbitrate scalar and vector owners while retaining each captured policy and held fault" in {
    val config=GpuConfig();val lanes=config.lanes;val full=(BigInt(1)<<lanes)-1
    simulate(new TexSampleUnit(config,coordinatePolicyInput=true)){d=>
      PointLineReference.zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val bindings=Vector(Binding(w=3,h=5),Binding(base=0xe0000000L,u=3),
        Binding(w=3,h=5,u=1,v=2,signed=false,format=2),Binding(base=0xe0000000L,w=0),Binding(w=3,h=5,u=0,v=1,format=4))
      val u=Vector.tabulate(lanes)(i=>Vector[Long](0xffffffffL,0x8000L,0x10001L,0L)(i%4))
      val v=Vector.tabulate(lanes)(i=>Vector[Long](0x8000L,0xffffffffL,0x10000L,0x7fffffffL)(i%4))
      case class Owner(vector: Boolean,index: Int,operation: Int)
      val expected=mutable.Queue.empty[Owner]
      val pending=mutable.ArrayBuffer.empty[(Long,Int)];var reply: Option[Long]=None
      var accepted=0;var completed=0;var vectorAccepted=0;var scalarAccepted=0;var cycle=0;var hold=0;var reads=0
      var held: Option[(BigInt,Boolean)]=None
      val order=new StringBuilder
      while(completed<5 && cycle<4000){
        val b=bindings(accepted.min(4))
        d.io.texBase.poke(b.base.U);d.io.texWidth.poke(b.w.U);d.io.texHeight.poke(b.h.U);d.io.texFormat.poke(b.format.U)
        d.io.minLevel.poke(0.U);d.io.texMaxLevel.poke(0.U);d.io.lodBias.poke(0.S);d.io.wrapClamp.poke(false.B)
        d.io.coordinatePolicy.get.wrapU.poke(b.u.U);d.io.coordinatePolicy.get.wrapV.poke(b.v.U);d.io.coordinatePolicy.get.signed.poke(b.signed.B)
        d.io.vectorIn.bits.poke(0.U.asTypeOf(d.io.vectorIn.bits));d.io.in.bits.poke(0.U.asTypeOf(d.io.in.bits))
        d.io.vectorIn.bits.issued.decode.instruction.poke("h062081ab".U)
        d.io.vectorIn.bits.issued.decode.pc.poke((0x800+vectorAccepted*4).U)
        d.io.vectorIn.bits.issued.decode.warpId.poke((config.warps-1).U);d.io.vectorIn.bits.issued.decode.activeMask.poke(full.U)
        d.io.vectorIn.bits.executionMask.poke(full.U)
        for(i<-0 until lanes){d.io.vectorIn.bits.issued.vs1Data(i).poke(u(i).U);d.io.vectorIn.bits.issued.vs2Data(i).poke(v(i).U)}
        d.io.in.bits.decode.instruction.poke("h0020828b".U);d.io.in.bits.decode.pc.poke((0x900+scalarAccepted*4).U)
        d.io.in.bits.decode.warpId.poke((config.warps-2).U);d.io.in.bits.decode.activeMask.poke(full.U)
        d.io.in.bits.rs1Data.poke(u.head.U);d.io.in.bits.rs2Data.poke(v.head.U)
        d.io.vectorIn.valid.poke((vectorAccepted<3).B);d.io.in.valid.poke((scalarAccepted<2).B)
        d.io.commit.ready.poke(false.B);d.io.vectorCommit.ready.poke(false.B)
        d.io.mem.req.ready.poke((cycle%5!=0).B)
        if(reply.isEmpty && cycle%3==0){val eligible=pending.indices.filter(i=>pending(i)._2<=cycle)
          if(eligible.nonEmpty)reply=Some(pending.remove(eligible.last)._1)}
        d.io.mem.resp.valid.poke(reply.nonEmpty.B)
        reply.foreach{a=>assert(expected.nonEmpty);d.io.mem.resp.bits.addr.poke(a.U)
          d.io.mem.resp.bits.data.poke(rawWord(a,expected.front.operation).U);d.io.mem.resp.bits.write.poke(false.B)}
        val response=reply.nonEmpty && d.io.mem.resp.ready.peek().litToBoolean
        val vecFire=d.io.vectorIn.valid.peek().litToBoolean && d.io.vectorIn.ready.peek().litToBoolean
        val scalarFire=d.io.in.valid.peek().litToBoolean && d.io.in.ready.peek().litToBoolean
        assert(!(vecFire && scalarFire))
        if(vecFire || scalarFire){
          expected.enqueue(Owner(vecFire,if(vecFire)vectorAccepted else scalarAccepted,accepted))
          order.append(if(vecFire)'V'else'S');accepted+=1
          if(vecFire)vectorAccepted+=1 else scalarAccepted+=1
        }
        if(d.io.mem.req.valid.peek().litToBoolean && d.io.mem.req.ready.peek().litToBoolean){
          val owner=expected.front;val n=bindings(owner.operation);val a=d.io.mem.req.bits.addr.peek().litValue.toLong
          assert(!n.fault && a>=n.base && a<n.base+n.w.toLong*n.h*4 && (a-n.base)%4==0)
          pending+=((a,cycle+3));reads+=1
        }
        var retired=false
        if(d.io.commit.valid.peek().litToBoolean || d.io.vectorCommit.valid.peek().litToBoolean){
          val owner=expected.front;val n=bindings(owner.operation)
          assert(pending.isEmpty && reply.isEmpty)
          d.io.coordinateFault.get.expect(n.fault.B)
          val bits=if(owner.vector)d.io.vectorCommit.bits.peek().litValue else d.io.commit.bits.peek().litValue
          held.foreach{case(value,fault)=>assert(bits==value && fault==n.fault)}
          held=Some((bits,n.fault))
          if(owner.vector){d.io.vectorCommit.valid.expect(true.B);d.io.vectorCommit.bits.writesVd.expect((!n.fault).B)
            d.io.vectorCommit.bits.pc.expect((0x800+owner.index*4).U)
            d.io.vectorCommit.bits.writeback.warpId.expect((config.warps-1).U)
            for(i<-0 until lanes)d.io.vectorCommit.bits.writeback.data(i).expect((if(n.fault)BigInt(0)else sample(n,u(i),v(i),(0,0),owner.operation)).U)
          }else{d.io.commit.valid.expect(true.B);d.io.commit.bits.writeRd.expect((!n.fault).B)
            d.io.commit.bits.nextPc.expect((0x904+owner.index*4).U)
            d.io.commit.bits.warpId.expect((config.warps-2).U)
            d.io.commit.bits.data.expect((if(n.fault)BigInt(0)else sample(n,u.head,v.head,(0,0),owner.operation)).U)}
          if(hold==5){
            if(owner.vector)d.io.vectorCommit.ready.poke(true.B)else d.io.commit.ready.poke(true.B)
            retired=true
          }else hold+=1
        }else assert(held.isEmpty)
        d.clock.step();if(response)reply=None
        if(retired){expected.dequeue();completed+=1;hold=0;held=None}
        cycle+=1
      }
      assert(accepted==5 && completed==5 && expected.isEmpty && pending.isEmpty && reply.isEmpty)
      assert(order.toString=="VSVSV")
      d.io.in.valid.poke(false.B);d.io.vectorIn.valid.poke(false.B);d.io.commit.ready.poke(false.B);d.io.vectorCommit.ready.poke(false.B)
      d.io.mem.resp.valid.poke(false.B);d.clock.step()
      d.io.commit.valid.expect(false.B);d.io.vectorCommit.valid.expect(false.B)
      info(s"TEXTURE_EXECUTE_POLICY_ARBITRATION lanes=$lanes warps=${config.warps} owners=$order instructions=5 faults=2 reads=$reads capturedPerAdmission=true heldCompletion=true noFaultWrites=true healthyReuse=true")
    }
  }
}
