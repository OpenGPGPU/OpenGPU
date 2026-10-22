package opengpu.core

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** Real instruction fetch, decode, vector RF, scheduler, physical stack and
  * WarpSystemControl. No external simtBranch stimulus or manufactured masks.
  */
class GpuComputeUnitInstructionSimtSpec extends AnyFlatSpec {
  private def zero(data: Data): Unit = data match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input => u.poke(0.U)
    case _ => ()
  }
  private val cease=BigInt("30500073",16)
  private val join=BigInt("205b",16)
  private def branch(op: Int,a: Int,b: Int,offset: Int): BigInt = {
    require(offset>= -4096 && offset<=4094 && (offset&1)==0)
    val n=offset&8191
    (BigInt((n>>12)&1)<<31)|(BigInt((n>>5)&63)<<25)|(BigInt(b)<<20)|
      (BigInt(a)<<15)|(BigInt(op)<<12)|(BigInt((n>>1)&15)<<8)|(BigInt((n>>11)&1)<<7)|0x5b
  }
  private def add(vd: Int,vs2: Int,imm: Int): BigInt =
    (BigInt(1)<<25)|(BigInt(vs2)<<20)|(BigInt(imm&31)<<15)|(BigInt(3)<<12)|(BigInt(vd)<<7)|0x57
  private def moveImmediate(vd: Int,imm: Int): BigInt =
    BigInt("5e003057",16)|(BigInt(imm&31)<<15)|(BigInt(vd)<<7)
  private def moveScalar(vd: Int,rs: Int): BigInt =
    BigInt("5e004057",16)|(BigInt(rs)<<15)|(BigInt(vd)<<7)
  private class Program(val base: Int) {
    val words=mutable.ArrayBuffer.empty[BigInt]
    val labels=mutable.Map.empty[String,Int]
    val fixups=mutable.ArrayBuffer.empty[(Int,String,Int=>BigInt)]
    def emit(word: BigInt): Unit={words+=word}
    def label(name: String): Unit={labels(name)=base+words.size*4}
    def edge(name: String)(make: Int=>BigInt): Unit={fixups+=((words.size,name,make));emit(0)}
    def sync(name: String): Unit=edge(name)(n=>branch(3,0,0,n))
    def b(op: Int,a: Int,b: Int,name: String): Unit=edge(name)(n=>branch(op,a,b,n))
    def jump(name: String): Unit=edge(name) { offset =>
      val n=offset&0x1fffff
      (BigInt((n>>20)&1)<<31)|(BigInt((n>>1)&1023)<<21)|
        (BigInt((n>>11)&1)<<20)|(BigInt((n>>12)&255)<<12)|0x6f
    }
    def result: Map[Int,BigInt] = {
      fixups.foreach { case(i,name,make)=>words(i)=make(labels(name)-(base+i*4)) }
      words.zipWithIndex.map{case(w,i)=>(base+i*4,w)}.toMap
    }
  }
  for((lanes,warps,cacheSets,cacheWays)<-Seq((4,2,4,1),(16,8,64,2))) {
    it should s"execute nested vector branches and bounded long loops, attribute faults and reuse the CU lanes=$lanes warps=$warps" in {
      val config=GpuConfig(lanes=lanes,warps=warps)
      simulate(new GpuComputeUnit(config,instructionCacheSets=cacheSets,instructionCacheWays=cacheWays,
        vectorCacheSets=cacheSets,vectorCacheWays=cacheWays,finishOnTrap=true)) { d =>
        zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
        d.io.memoryRequest.ready.poke(true.B);d.io.l1InvalidateDone.ready.poke(true.B)
        d.io.globalAtomicRequest.ready.poke(true.B)
        val nested=new Program(0x1000)
        nested.emit(BigInt("0104f057",16)) // vsetvli x0,x9,e32,m1,tu,mu
        nested.emit(moveImmediate(0,0)) // zero v0 cannot mask a SIMT comparison
        nested.emit(moveScalar(2,8));nested.emit(add(2,2,lanes/2))
        nested.emit(moveScalar(5,8));nested.emit(add(5,5,lanes/4))
        nested.emit(moveImmediate(3,0))
        nested.sync("outerJoin");nested.b(6,1,2,"outerTaken")
        nested.emit(add(3,1,10));nested.emit(join)
        nested.label("outerTaken");nested.sync("innerJoin");nested.b(6,1,5,"innerTaken")
        nested.emit(add(3,1,2));nested.emit(join)
        nested.label("innerTaken");nested.emit(add(3,1,1));nested.emit(join)
        nested.label("innerJoin");nested.emit(join);nested.emit(join)
        nested.label("outerJoin");nested.emit(join);nested.emit(add(4,3,1));nested.emit(cease)
        val loop=new Program(0x1800)
        loop.emit(BigInt("0104f057",16));loop.emit(moveImmediate(0,0));loop.emit(moveImmediate(2,0))
        loop.emit(add(3,1,15));for(_<-0 until 3)loop.emit(add(3,3,15));loop.emit(add(3,3,5))
        loop.sync("loopJoin");loop.label("condition");loop.b(7,2,3,"loopJoin")
        loop.emit(add(2,2,1));loop.jump("condition")
        loop.label("loopJoin");loop.emit(join);loop.emit(add(4,2,0));loop.emit(cease)
        val overflow=(0 to config.simtStackDepth).map { i=>
          val pc=0x2000+i*4;pc->branch(3,0,0,0x2100-pc)
        }.toMap
        val program=nested.result++loop.result++overflow++Map(
          0x2400->branch(0,1,1,4), // branch without an established scope
          0x2800->branch(3,0,0,0x80),0x2804->branch(0,1,1,2),
          0x3000->BigInt("00700293",16),0x3004->cease)
        case class Reply(id: BigInt,data: BigInt,due: Int)
        val pending=mutable.ArrayBuffer.empty[Reply]
        var cycle=0;var traps=0;var scalarWrites=0
        var allowFault=false
        var expected: Option[Int=>BigInt]=None
        val observed=mutable.Map.empty[Int,Vector[BigInt]]
        def tick(): Unit={
          val candidates=pending.indices.filter(i=>pending(i).due<=cycle)
          val selected=if(candidates.isEmpty)None else Some(if((cycle&1)==0)candidates.last else candidates.head)
          d.io.memoryResponse.valid.poke(selected.nonEmpty.B)
          selected.foreach { i=>
            d.io.memoryResponse.bits.transactionId.poke(pending(i).id.U)
            d.io.memoryResponse.bits.readData.poke(pending(i).data.U)
          }
          if(selected.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean)pending.remove(selected.get)
          if(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean) {
            val q=d.io.memoryRequest.bits;val address=q.address.peek().litValue.toInt
            assert(!q.isWrite.peek().litToBoolean,"register-only shader issued a memory write")
            assert(program.keys.exists(p=>p/64==address/64),s"unexpected instruction line 0x${address.toHexString}")
            val data=(0 until 16).foldLeft(BigInt(0))((v,i)=>v|(program.getOrElse(address+i*4,cease)<<(i*32)))
            pending+=Reply(q.transactionId.peek().litValue,data,cycle+5+(cycle%3))
          }
          if(d.io.trap.valid.peek().litToBoolean) {
            assert(allowFault,s"unexpected trap cause=${d.io.trap.bits.cause.peek().litValue} pc=${d.io.trap.bits.pc.peek().litValue}")
            if(d.io.trap.ready.peek().litToBoolean)traps+=1
          }
          if(d.io.committedVectorWriteback.valid.peek().litToBoolean &&
              d.io.committedVectorWriteback.bits.vd.peek().litValue==4) {
            val w=d.io.committedVectorWriteback.bits.warpId.peek().litValue.toInt
            val data=Vector.tabulate(lanes)(i=>d.io.committedVectorWriteback.bits.data(i).peek().litValue)
            assert(!observed.contains(w),s"duplicate final vector for warp $w")
            expected.foreach(f=>assert(data==Vector.tabulate(lanes)(i=>f(w*lanes+i)),s"wrong warp $w vector: $data"))
            observed(w)=data
          }
          if(d.io.committedWriteback.valid.peek().litToBoolean &&
              d.io.committedWriteback.bits.rd.peek().litValue==5 &&
              d.io.committedWriteback.bits.data.peek().litValue==7)scalarWrites+=1
          d.clock.step();cycle+=1
        }
        def until(condition: => Boolean): Unit={
          var n=0;while(!condition && n<200000){tick();n+=1}
          assert(condition,s"stalled cycle=$cycle active=${d.io.active.peek().litValue} blocked=${d.io.blocked.peek().litValue}")
        }
        def launch(pc: Int,threads: Int): Unit={
          d.io.kernel.bits.poke(0.U.asTypeOf(d.io.kernel.bits))
          d.io.kernel.bits.kernelPc.poke(pc.U);d.io.kernel.bits.kernargAddress.poke(0x8000.U)
          for(i<-0 until 3){d.io.kernel.bits.gridSize(i).poke(1.U);d.io.kernel.bits.localSize(i).poke((if(i==0)threads else 1).U)}
          d.io.kernel.valid.poke(true.B);until(d.io.kernel.ready.peek().litToBoolean)
          tick();d.io.kernel.valid.poke(false.B)
        }
        def complete(success: Boolean): Unit={
          until(d.io.completion.valid.peek().litToBoolean)
          d.io.completion.bits.success.expect(success.B);d.io.active.expect(0.U)
          val held=d.io.completion.bits.peek().litValue
          for(_<-0 until 7){d.io.completion.valid.expect(true.B);assert(d.io.completion.bits.peek().litValue==held);tick()}
          d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B)
        }
        expected=Some(id=>BigInt(id+(if(id%lanes<lanes/4)2 else if(id%lanes<lanes/2)3 else 11)))
        launch(0x1000,lanes*warps);complete(true);assert(observed.size==warps && traps==0)
        observed.clear();expected=Some(id=>BigInt(id+65))
        launch(0x1800,lanes*warps);complete(true);assert(observed.size==warps && traps==0)
        observed.clear();expected=None;allowFault=true
        for((pc,cause,at,tval)<-Seq(
          (0x2000,26,0x2000+4*config.simtStackDepth,0x2000+4*config.simtStackDepth),
          (0x2400,27,0x2400,0x2400),(0x2800,0,0x2804,0x2806))) {
          launch(pc,1);until(d.io.trap.valid.peek().litToBoolean)
          d.io.trap.bits.cause.expect(cause.U);d.io.trap.bits.pc.expect(at.U)
          d.io.trap.bits.tval.expect(tval.U);d.io.trap.bits.activeMask.expect(1.U)
          d.io.trap.bits.laneFaultMask.expect(1.U)
          val held=d.io.trap.bits.peek().litValue
          for(_<-0 until 9){assert(d.io.trap.bits.peek().litValue==held);d.io.completion.valid.expect(false.B);tick()}
          d.io.trap.ready.poke(true.B);tick();d.io.trap.ready.poke(false.B);complete(false)
        }
        allowFault=false;launch(0x3000,1);complete(true)
        assert(traps==3 && scalarWrites==1 && pending.isEmpty)
        info(s"CU_INSTRUCTION_SIMT lanes=$lanes warps=$warps nativeVectorRF=true nestedScopes=true fullResidency=true " +
          "divergentLoopsBeyondDepth=true heldOriginalFaults=true failedCompletions=true healthyReuse=true externalBranchPortUnused=true")
      }
    }
  }
}
