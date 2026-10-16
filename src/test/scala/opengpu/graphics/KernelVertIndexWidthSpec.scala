package opengpu.graphics

import chisel3._
import opengpu.config.{CachePolicy, GpuConfig}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** The kernel is emulated only after every staged input has been checked.
  * Unique per-vertex values expose index aliasing in both staging and triangle
  * emission; full RAM checks protect inactive entries and uniforms.
  */
class KernelVertIndexWidthSpec extends AnyFlatSpec {
  behavior of "KernelVertStage index width"
  for((lanes,warps)<-Seq((1,3),(4,7),(16,7))) {
    val capacity=lanes*warps
    it should s"preserve every vertex and canary with $capacity slots" in {
      val config=GpuConfig(lanes=lanes,warps=warps)
      val eff=(capacity/3)*3
      val stride=capacity*4
      simulate(new KernelVertStage(config,GraphicsConfig(),standaloneKernel=false)) { dut =>
        def zeroInputs():Unit={
          dut.io.start.poke(false.B);dut.io.vertBufferBase.poke(0.U);dut.io.vertCount.poke(0.U)
          dut.io.vertStride.poke(0.U);dut.io.shaderPc.poke(0.U);dut.io.kernargBase.poke(0.U)
          dut.io.kernargBankStride.poke(0.U);dut.io.fragShaderPc.poke(0.U)
          dut.io.fragInputSkip.poke(0.U);dut.io.fragOutputSkip.poke(0.U)
          dut.io.fragKernarg.poke(0.U);dut.io.fragKernargBankStride.poke(0.U)
          dut.io.stateOverride.poke(false.B);dut.io.depthTestEnable.poke(false.B)
          dut.io.depthFunc.poke(0.U);dut.io.depthWriteEnable.poke(false.B)
          dut.io.blendEnable.poke(false.B);dut.io.blendCfgEnable.poke(false.B)
          dut.io.blendSrcFactor.poke(0.U);dut.io.blendDstFactor.poke(0.U);dut.io.blendEquation.poke(0.U)
          dut.io.stencilTestEnable.poke(false.B);dut.io.stencilFunc.poke(0.U)
          dut.io.stencilRef.poke(0.U);dut.io.stencilReadMask.poke(0.U);dut.io.stencilWriteMask.poke(0.U)
          dut.io.stencilFailOp.poke(0.U);dut.io.stencilZFailOp.poke(0.U);dut.io.stencilZPassOp.poke(0.U)
          dut.io.cullMode.poke(0.U);dut.io.texEnable.poke(false.B);dut.io.texWrapClamp.poke(false.B)
          dut.io.texMaxLevel.poke(0.U);dut.io.texLodBias.poke(0.S);dut.io.texMinLevel.poke(0.U)
          dut.io.scissorEnable.poke(false.B);dut.io.scissorMinX.poke(0.U);dut.io.scissorMinY.poke(0.U)
          dut.io.scissorMaxX.poke(0.U);dut.io.scissorMaxY.poke(0.U)
        }
        zeroInputs()
        dut.io.vertOut.ready.poke(false.B)
        dut.io.memReq.ready.poke(true.B)
        dut.io.memResp.valid.poke(false.B);dut.io.memResp.bits.poke(0.U.asTypeOf(dut.io.memResp.bits))
        dut.io.wordMemReq.ready.poke(false.B)
        dut.io.wordMemResp.valid.poke(false.B);dut.io.wordMemResp.bits.poke(0.U.asTypeOf(dut.io.wordMemResp.bits))
        dut.io.stagingInvalidate.ready.poke(false.B)
        dut.io.stagingInvalidateDone.valid.poke(false.B)
        dut.io.stagingInvalidateDone.bits.poke(0.U.asTypeOf(dut.io.stagingInvalidateDone.bits))
        dut.io.l1Invalidate.valid.poke(false.B);dut.io.l1Invalidate.bits.poke(0.U.asTypeOf(dut.io.l1Invalidate.bits))
        dut.io.l1InvalidateDone.ready.poke(true.B)
        dut.io.globalAtomicRequest.ready.poke(true.B)
        dut.io.globalAtomicResponse.valid.poke(false.B);dut.io.globalAtomicResponse.bits.poke(0.U.asTypeOf(dut.io.globalAtomicResponse.bits))
        dut.io.kernelLaunch.ready.poke(false.B)
        dut.io.kernelCompletion.valid.poke(false.B);dut.io.kernelCompletion.bits.poke(0.U.asTypeOf(dut.io.kernelCompletion.bits))
        dut.io.kernelTrap.valid.poke(false.B);dut.io.kernelTrap.bits.poke(0.U.asTypeOf(dut.io.kernelTrap.bits))
        dut.io.kernelSimtBranch.valid.poke(false.B);dut.io.kernelSimtBranch.bits.poke(0.U.asTypeOf(dut.io.kernelSimtBranch.bits))
        dut.io.kernelTexSample.valid.poke(false.B);dut.io.kernelTexSample.bits.poke(0.U.asTypeOf(dut.io.kernelTexSample.bits))
        dut.io.kernelTexWriteback.ready.poke(true.B)
        dut.io.kernelVectorTexSample.valid.poke(false.B);dut.io.kernelVectorTexSample.bits.poke(0.U.asTypeOf(dut.io.kernelVectorTexSample.bits))
        dut.io.kernelVectorTexWriteback.ready.poke(true.B)
        dut.reset.poke(true.B);dut.clock.step();dut.reset.poke(false.B)
        val scenarios=Seq((eff,0,0,true))
        var totalCycles=0
        for(((count,baseOffset,bankOffset,success),trial)<-scenarios.zipWithIndex) {
          val vb=0x10000L;val vertexStride=36+4*(trial%5)
          val base=0x40000L+baseOffset
          val bankStride=((16*stride+191)/64)*64+bankOffset
          val memory=mutable.LongMap.empty[Long]
          def original(a:Long):Long=((a*0x45d9f3bL)^(a>>2)^(trial.toLong*0x19377))&0xffffffffL
          def get(a:Long):Long=memory.getOrElse(a,original(a))
          def put(a:Long,value:Long):Unit=memory(a)=value&0xffffffffL
          def input(vertex:Int,field:Int):Long={
            val raw=(0x01000000L*(field+1)+vertex*0x101L+trial*17L)&0xffffffffL
            if(field<4 && vertex%5==0)raw^0x80000000L
            else if(field==7 && vertex%7==0)0xffffffffL-vertex else raw
          }
          def signed(value:Long):Long={
            val bits=value&0xffffffffL
            if((bits&0x80000000L)!=0)bits-(1L<<32) else bits
          }
          def output(vertex:Int,field:Int):Long=(field match {
            case 0=>input(vertex,1)+3
            case 1=>input(vertex,0)+5
            case 2=>input(vertex,2)+7
            case 3=>input(vertex,3)+9
            case 4=>0x20406080L+vertex*0x01010101L
            case 5=>(0x12340L+vertex)&0xffffffL
            case 6=>input(vertex,7)+11
            case 7=>input(vertex,6)+13
          })&0xffffffffL
          for(v<-0 until count;f<-0 until 8)put(vb+v*vertexStride+f*4,input(v,f))
          val untouched=memory.toMap
          val expected=mutable.LongMap.empty[Long]++memory
          val batches=if(count<3)0 else (count/3*3+eff-1)/eff
          val batchCounts=(0 until batches).map(b=>math.min(eff,count/3*3-b*eff))
          val bankBase=(b:Int)=>base+(if(b%2==1)bankStride else 0)
          for(b<-0 until batches) {
            for(v<-0 until batchCounts(b);f<-0 until 8)expected(bankBase(b)+f*stride+v*4)=input(b*eff+v,f)
            if(success)for(v<-0 until batchCounts(b);f<-0 until 8)
              expected(bankBase(b)+(f+8)*stride+v*4)=output(b*eff+v,f)&0xffffffffL
          }
          zeroInputs();dut.io.vertBufferBase.poke(vb.U);dut.io.vertCount.poke(count.U)
          dut.io.vertStride.poke(vertexStride.U);dut.io.shaderPc.poke(0x1000.U)
          dut.io.kernargBase.poke(base.U);dut.io.kernargBankStride.poke(bankStride.U)
          dut.io.fragShaderPc.poke(0x2000.U);dut.io.fragInputSkip.poke(21.U)
          dut.io.fragOutputSkip.poke(5.U);dut.io.fragKernarg.poke(0x80000.U)
          dut.io.fragKernargBankStride.poke(0x4000.U)
          dut.io.start.poke(true.B);dut.clock.step();dut.io.start.poke(false.B)
          // Descriptor pins may change after start; every batch must use the snapshot.
          zeroInputs()
          case class Pending(id:Int,address:Long,write:Boolean,data:BigInt,mask:BigInt,due:Int)
          val pending=mutable.ArrayBuffer.empty[Pending]
          var presenting:Option[Pending]=None
          var heldRequest:Option[BigInt]=None
          var heldTriangle:Option[BigInt]=None
          var invalidateDue:Option[Int]=None
          var completionDue:Option[Int]=None
          var launched=0;var emitted=0;var invalidations=0;var cycle=0;var peak=0
          val writes=mutable.ArrayBuffer.empty[(Int,Int)]
          val reads=mutable.ArrayBuffer.empty[(Int,Int)]
          while(!dut.io.done.peek().litToBoolean && cycle<150000) {
            dut.io.memReq.valid.expect(false.B)
            dut.io.wordMemReq.ready.poke((cycle%13<8).B)
            dut.io.stagingInvalidate.ready.poke((cycle%7==0).B)
            dut.io.kernelLaunch.ready.poke((cycle%11==0).B)
            dut.io.vertOut.ready.poke((cycle%17<10).B)
            dut.io.stagingInvalidateDone.valid.poke(invalidateDue.exists(_<=cycle).B)
            dut.io.stagingInvalidateDone.bits.lineAddress.poke(((BigInt(1)<<32)-1).U)
            dut.io.kernelCompletion.valid.poke(completionDue.exists(_<=cycle).B)
            dut.io.kernelCompletion.bits.success.poke(success.B)
            if(presenting.isEmpty) {
              val ready=pending.filter(_.due<=cycle)
              if(ready.nonEmpty)presenting=Some(if(cycle%2==0)ready.last else ready.head)
            }
            dut.io.wordMemResp.valid.poke(presenting.nonEmpty.B)
            presenting.foreach{p=>
              val data=(0 until 16).foldLeft(BigInt(0)){(acc,w)=>acc|(BigInt(get(p.address+w*4))<<(w*32))}
              dut.io.wordMemResp.bits.transactionId.poke(p.id.U)
              dut.io.wordMemResp.bits.readData.poke(data.U)
              dut.io.wordMemResp.bits.fault.poke(false.B)
            }
            val batch=math.min(launched,batches-1)
            if(dut.io.wordMemReq.valid.peek().litToBoolean) {
              val snapshot=dut.io.wordMemReq.bits.peek().litValue
              heldRequest.foreach(x=>assert(x==snapshot,"vertex request changed under backpressure"))
              if(!dut.io.wordMemReq.ready.peek().litToBoolean)heldRequest=Some(snapshot)
              else {
                heldRequest=None
                val req=dut.io.wordMemReq.bits
                val id=req.transactionId.peek().litValue.toInt
                assert(!pending.exists(_.id==id),"local staging ID reused before response")
                val a=req.address.peek().litValue.toLong
                val write=req.isWrite.peek().litToBoolean
                val mask=req.byteMask.peek().litValue
                assert(mask!=0);req.sizeLog2.expect(6.U);req.cachePolicy.expect(CachePolicy.uncached)
                val p=Pending(id,a,write,req.writeData.peek().litValue,mask,cycle+(if(id==0)17 else 3))
                if(write) {
                  val allowed=(for(f<-0 until 8;v<-0 until batchCounts(batch);b<-0 until 4)
                    yield bankBase(batch)+f*stride+v*4+b).toSet
                  for(b<-0 until 64 if mask.testBit(b))assert(allowed(a+b),s"write outside active input fields $a+$b")
                  writes+=((batch,1))
                } else if(a>=vb && a<vb+count*vertexStride) {
                  assert(mask.bitCount==4,"VB path must retain its exact word mask")
                } else reads+=((launched-1,1))
                pending+=p;peak=peak.max(pending.size);assert(pending.size<=2)
              }
            } else assert(heldRequest.isEmpty,"vertex request valid dropped under backpressure")
            if(dut.io.wordMemResp.valid.peek().litToBoolean && dut.io.wordMemResp.ready.peek().litToBoolean) {
              val p=presenting.get
              if(p.write)for(w<-0 until 16) {
                val nibble=(p.mask>>(w*4))&15
                assert(nibble==0 || nibble==15,"staging writes must contain whole words")
                if(nibble==15)put(p.address+w*4,((p.data>>(w*32))&0xffffffffL).toLong)
              }
              pending-=p;presenting=None
            }
            if(dut.io.stagingInvalidate.valid.peek().litToBoolean && dut.io.stagingInvalidate.ready.peek().litToBoolean) {
              assert(pending.isEmpty && heldRequest.isEmpty,"invalidation started before write acknowledgements drained")
              assert(invalidateDue.isEmpty);invalidateDue=Some(cycle+9);invalidations+=1
            }
            if(dut.io.stagingInvalidateDone.valid.peek().litToBoolean && dut.io.stagingInvalidateDone.ready.peek().litToBoolean)
              invalidateDue=None
            if(dut.io.kernelLaunch.valid.peek().litToBoolean && dut.io.kernelLaunch.ready.peek().litToBoolean) {
              assert(pending.isEmpty && invalidateDue.isEmpty)
              val b=launched;val n=batchCounts(b);val ka=bankBase(b)
              dut.io.kernelLaunch.kernargAddress.expect(ka.U);dut.io.kernelLaunch.localX.expect(n.U)
              for(f<-0 until 8;v<-0 until n)assert(get(ka+f*stride+v*4)==input(b*eff+v,f),s"input mismatch field=$f vertex=$v")
              // Independently emulate the kernel's output writes after checking every input.
              if(success)for(f<-0 until 8;v<-0 until n)put(ka+(8+f)*stride+v*4,output(b*eff+v,f))
              launched+=1;completionDue=Some(cycle+23)
            }
            if(dut.io.kernelCompletion.valid.peek().litToBoolean && dut.io.kernelCompletion.ready.peek().litToBoolean)
              completionDue=None
            if(dut.io.vertOut.valid.peek().litToBoolean) {
              val bits=dut.io.vertOut.bits.peek().litValue
              heldTriangle.foreach(x=>assert(x==bits,"triangle sidebands changed under backpressure"))
              if(dut.io.vertOut.ready.peek().litToBoolean) {
                assert(pending.isEmpty,"triangle emitted before readback responses drained")
                assert(success);dut.io.vertOut.bits.fragInputSkip.expect(21.U)
                dut.io.vertOut.bits.fragOutputSkip.expect(5.U)
                dut.io.vertOut.bits.shaderPc.expect(0x2000.U)
                for(v<-0 until 3) {
                  val vertex=emitted*3+v
                  dut.io.vertOut.bits.clip(v).x.expect(signed(output(vertex,0)).S(32.W))
                  dut.io.vertOut.bits.clip(v).y.expect(signed(output(vertex,1)).S(32.W))
                  dut.io.vertOut.bits.clip(v).z.expect(signed(output(vertex,2)).S(32.W))
                  dut.io.vertOut.bits.clip(v).w.expect(signed(output(vertex,3)).S(32.W))
                  val color=output(vertex,4)&0xffffffffL
                  dut.io.vertOut.bits.color(v).r.expect(((color>>24)&255).U)
                  dut.io.vertOut.bits.color(v).g.expect(((color>>16)&255).U)
                  dut.io.vertOut.bits.color(v).b.expect(((color>>8)&255).U)
                  dut.io.vertOut.bits.alpha(v).expect((color&255).U)
                  dut.io.vertOut.bits.depth(v).expect(signed(output(vertex,5)).S(32.W))
                  dut.io.vertOut.bits.uv(v).u.expect(output(vertex,6).U)
                  dut.io.vertOut.bits.uv(v).v.expect(output(vertex,7).U)
                }
                emitted+=1;heldTriangle=None
              } else heldTriangle=Some(bits)
            } else assert(heldTriangle.isEmpty)
            dut.clock.step();cycle+=1
          }
          assert(cycle<150000,s"stage timeout cap=$capacity trial=$trial")
          assert(pending.isEmpty && presenting.isEmpty && completionDue.isEmpty && invalidateDue.isEmpty)
          val expectedBatches=if(success)batches else math.min(1,batches)
          assert(launched==expectedBatches && invalidations==expectedBatches)
          assert(emitted==(if(success)count/3 else 0))
          for(a<-(memory.keySet++expected.keySet))assert(get(a)==expected.getOrElse(a,original(a)),s"RAM/canary mismatch $a")
          for((a,value)<-untouched)assert(get(a)==value,"VB or padding changed")
          totalCycles+=cycle
          println(s"VERTEX_INDEX_CASE slots=$capacity trial=$trial vertices=$count writes=${writes.size} reads=${reads.size} cycles=$cycle peak=$peak")
          dut.io.wordMemResp.valid.poke(false.B);dut.io.stagingInvalidateDone.valid.poke(false.B)
          dut.io.kernelCompletion.valid.poke(false.B);dut.clock.step(2)
        }
        println(s"VERTEX_INDEX_SUITE slots=$capacity draws=${scenarios.size} cycles=$totalCycles")
      }
    }
  }
}
