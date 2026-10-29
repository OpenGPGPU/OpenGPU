package opengpu.core.memory

import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.core.backend.issue.FpuMemoryUnit
import opengpu.core.execute.memory.SharedAtomicExecuteStage
import opengpu.dispatch.WorkgroupDispatcher
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

private class HelperScalarPolicyHarness(floating: Boolean) extends Module {
  private val config=GpuConfig(lanes=4,warps=2)
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new Bundle {val address=UInt(32.W);val write=Bool();val active=UInt(4.W);val warp=UInt(1.W)}))
    val policies=Input(Vec(2,new WarpInvocationPolicy(config)))
    val request=Decoupled(new VectorCacheLineRequest(config));val response=Flipped(Decoupled(new VectorCacheLineResponse))
    val out=Decoupled(new Bundle {val active=UInt(4.W);val data=UInt(32.W);val load=Bool()})
    val fault=Decoupled(new ScalarMemoryFault(config))
  })
  if(floating){
    val m=Module(new FpuMemoryUnit(config,helperInvocations=true))
    m.io.invocations.get:=io.policies;m.io.in.valid:=io.in.valid;io.in.ready:=m.io.in.ready
    m.io.in.bits:=0.U.asTypeOf(m.io.in.bits);m.io.in.bits.decode.warpId:=io.in.bits.warp
    m.io.in.bits.decode.pc:=0x100.U;m.io.in.bits.decode.activeMask:=io.in.bits.active
    m.io.in.bits.decode.instruction:=Mux(io.in.bits.write,0x2027.U,0x2007.U)
    m.io.in.bits.decode.decoded.memoryWrite:=io.in.bits.write;m.io.in.bits.decode.decoded.memoryRead:= !io.in.bits.write
    m.io.in.bits.scalarRs1Data:=io.in.bits.address;m.io.in.bits.rs2Data:="h11223344".U
    io.request<>m.io.cacheRequest;m.io.cacheResponse<>io.response;io.fault<>m.io.fault
    io.out.valid:=m.io.commit.valid;m.io.commit.ready:=io.out.ready
    io.out.bits.active:=m.io.commit.bits.decode.activeMask;io.out.bits.data:=m.io.commit.bits.loadData;io.out.bits.load:=m.io.commit.bits.isLoad
  }else{
    val m=Module(new ScalarMemoryUnit(config,helperInvocations=true))
    m.io.invocations.get:=io.policies;m.io.in.valid:=io.in.valid;io.in.ready:=m.io.in.ready
    m.io.in.bits:=0.U.asTypeOf(m.io.in.bits);m.io.in.bits.decode.warpId:=io.in.bits.warp
    m.io.in.bits.decode.pc:=0x100.U;m.io.in.bits.decode.activeMask:=io.in.bits.active
    m.io.in.bits.decode.instruction:=Mux(io.in.bits.write,0x2023.U,0x2003.U)
    m.io.in.bits.decode.decoded.memoryWrite:=io.in.bits.write;m.io.in.bits.decode.decoded.memoryRead:= !io.in.bits.write
    m.io.in.bits.rs1Data:=io.in.bits.address;m.io.in.bits.rs2Data:="h11223344".U
    io.request<>m.io.cacheRequest;m.io.cacheResponse<>io.response;io.fault<>m.io.fault
    io.out.valid:=m.io.commit.valid;m.io.commit.ready:=io.out.ready
    io.out.bits.active:=m.io.commit.bits.activeMask;io.out.bits.data:=m.io.commit.bits.data;io.out.bits.load:=m.io.commit.bits.writeRd
  }
}

class HelperInvocationPolicySpec extends AnyFlatSpec {
  private val config=GpuConfig(lanes=4,warps=2)
  private def zero(data: Data): Unit=data match {
    case r: Record=>r.elements.values.foreach(zero)
    case v: Vec[_]=>v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case _=>()
  }
  for(floating<-Seq(false,true))it should s"suppress helper-only external stores before faults and preserve private accesses with floating=$floating" in {
    simulate(new HelperScalarPolicyHarness(floating)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      case class Access(address: BigInt,write: Boolean=true,active: Int=15,helpers: Int=15,
        base: BigInt=0x8000,bytes: BigInt=16,kind: Int=0)
      val cases=Seq(Access(0x4000),Access(0x4001),Access(0x800c,kind=1),Access(0x800e),Access(0x8001,kind=2),
        Access(0x4000,active=1,helpers=5),Access(0x4000,active=2,helpers=5,kind=1),Access(0x4000,write=false,kind=1),
        Access(0x4000,helpers=0,kind=1),Access(BigInt("fffffffc",16),base=BigInt("fffffff8",16),bytes=16),
        Access(BigInt("fffffffc",16),base=BigInt("fffffff0",16),bytes=16,kind=1),
        Access(config.sharedMemoryBase,base=config.sharedMemoryBase-4,bytes=8),Access(0x8000,bytes=0),Access(0x4001,write=false,kind=2))
      for((a,index)<-cases.zipWithIndex){
        zero(d.io.policies);val p=d.io.policies(index%2);p.helpers.poke(a.helpers.U);p.privateBase.poke(a.base.U);p.privateBytes.poke(a.bytes.U)
        d.io.in.bits.address.poke(a.address.U);d.io.in.bits.write.poke(a.write.B);d.io.in.bits.active.poke(a.active.U);d.io.in.bits.warp.poke((index%2).U)
        d.io.in.valid.poke(true.B);d.io.in.ready.expect(true.B);d.clock.step();d.io.in.valid.poke(false.B)
        // The admitted instruction keeps its policy even if the context input changes.
        zero(d.io.policies)
        var n=0
        while(!d.io.request.valid.peek().litToBoolean && !d.io.out.valid.peek().litToBoolean && !d.io.fault.valid.peek().litToBoolean && n<15){d.clock.step();n+=1}
        if(a.kind==1){
          d.io.request.valid.expect(true.B);d.io.fault.valid.expect(false.B)
          val held=d.io.request.bits.peek().litValue
          for(_<-0 until 5){d.clock.step();assert(d.io.request.bits.peek().litValue==held);d.io.in.ready.expect(false.B)}
          d.io.request.bits.isStore.expect(a.write.B);d.io.request.bits.warpId.expect((index%2).U)
          d.io.request.ready.poke(true.B);d.clock.step();d.io.request.ready.poke(false.B)
          val offset=(a.address&63).toInt
          d.io.response.bits.readData.poke((BigInt("12345678",16)<<(offset*8)).U);d.io.response.valid.poke(true.B)
          d.io.response.ready.expect(true.B);d.clock.step();d.io.response.valid.poke(false.B)
        }
        if(a.kind==2){
          d.io.request.valid.expect(false.B);d.io.fault.valid.expect(true.B);d.io.fault.bits.activeMask.expect(a.active.U)
          for(_<-0 until 4)d.clock.step();d.io.fault.ready.poke(true.B);d.clock.step();d.io.fault.ready.poke(false.B)
        }else{
          d.io.request.valid.expect(false.B);d.io.fault.valid.expect(false.B);d.io.out.valid.expect(true.B)
          d.io.out.bits.active.expect(a.active.U);d.io.out.bits.load.expect((!a.write).B)
          if(!a.write)d.io.out.bits.data.expect("h12345678".U)
          val held=d.io.out.bits.peek().litValue;for(_<-0 until 6){d.clock.step();assert(d.io.out.bits.peek().litValue==held)}
          d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)
        }
      }
      info(s"HELPER_POLICY floating=$floating cases=${cases.size} externalStoresSuppressed=true privateBoundary=true wrapRejected=true sharedExceptionRejected=true helperLoadsExecute=true divergentActiveMasks=true snapshots=true heldCompletions=true")
    }
  }
  it should "mask vector store lanes after address generation without masking helper execution or loads" in {
    simulate(new VectorMemoryUnit(config,helperInvocations=true)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def run(base: BigInt,helpers: Int,active: Int,write: Boolean,expectedMask: Int,
          privateBase: BigInt=0x8000,privateBytes: BigInt=16,faultMask: Int=0,
          element: Int=2,stride: Option[Int]=None,indices: Option[Vector[Int]]=None): Unit={
        val p=d.io.invocations.get(1);p.helpers.poke(helpers.U);p.privateBase.poke(privateBase.U);p.privateBytes.poke(privateBytes.U)
        val q=d.io.in.bits;zero(q);q.warpId.poke(1.U);q.pc.poke(0x100.U);q.warpActiveMask.poke(active.U);q.activeMask.poke(active.U)
        q.baseAddress.poke(base.U);q.elementSize.poke(element.U);q.isStore.poke(write.B);q.vd.poke(3.U)
        q.strided.poke(stride.nonEmpty.B);q.stride.poke(stride.getOrElse(0).U);q.indexed.poke(indices.nonEmpty.B)
        for(i<-0 until 4)q.indexData(i).poke(indices.map(_(i)).getOrElse(0).U)
        for(i<-0 until 4){q.storeData(i).poke((100+i).U);q.oldVd(i).poke(99.U)}
        d.io.in.valid.poke(true.B);d.io.in.ready.expect(true.B);d.clock.step();d.io.in.valid.poke(false.B);zero(d.io.invocations.get)
        if(expectedMask!=0 || !write){
          d.io.memoryRequest.valid.expect(true.B);d.io.memoryRequest.bits.laneMask.expect(expectedMask.U)
          for(i<-0 until 4){val offset=indices.map(_(i)).getOrElse(stride.getOrElse(1<<element)*i);d.io.memoryRequest.bits.addresses(i).expect(((base+offset)&0xffffffffL).U)}
          val held=d.io.memoryRequest.bits.peek().litValue;for(_<-0 until 5){d.clock.step();assert(d.io.memoryRequest.bits.peek().litValue==held)}
          d.io.memoryRequest.ready.poke(true.B);d.clock.step();d.io.memoryRequest.ready.poke(false.B)
          for(i<-0 until 4)d.io.memoryResponse.bits.readData(i).poke((11+i).U)
          d.io.memoryResponse.bits.faultMask.poke(faultMask.U);d.io.memoryResponse.bits.pageFault.poke(false.B)
          d.io.memoryResponse.valid.poke(true.B);d.io.memoryResponse.ready.expect(true.B);d.clock.step();d.io.memoryResponse.valid.poke(false.B)
        }
        var n=0;while(!d.io.out.valid.peek().litToBoolean && n<10){d.io.memoryRequest.valid.expect(false.B);d.clock.step();n+=1}
        d.io.out.valid.expect(true.B);d.io.out.bits.warpActiveMask.expect(active.U);d.io.out.bits.faultMask.expect((faultMask&expectedMask).U)
        d.io.out.bits.writesVd.expect((!write).B)
        if(!write)for(i<-0 until 4)d.io.out.bits.data(i).expect((if((active&(1<<i))!=0)11+i else 99).U)
        val held=d.io.out.bits.peek().litValue;for(_<-0 until 4){d.clock.step();assert(d.io.out.bits.peek().litValue==held)}
        d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)
      }
      run(0x8008,10,15,true,7,faultMask=8);run(0x8008,15,15,true,3)
      run(0x4000,15,15,true,0);run(0x4000,15,15,false,15)
      run(0x8000,15,1,true,1);run(BigInt("fffffffc",16),15,1,true,0,BigInt("fffffff8",16),16)
      run(config.sharedMemoryBase,15,15,true,0,config.sharedMemoryBase,16)
      run(0x4000,0,15,true,15)
      run(0x8000,15,15,true,3,stride=Some(8))
      run(0x8000,15,15,true,5,indices=Some(Vector(0,20,4,28)))
      run(0x8000,15,15,true,1,privateBytes=3,element=1)
      run(0x8000,15,15,true,7,privateBytes=3,element=0)
      info("HELPER_POLICY vectorCases=12 indexedAndStrided=true byteHalfwordBoundaries=true mixedPrivateExternal=true helpersLoad=true architecturalMasksPreserved=true suppressedFaultMask=true zeroStoreNoRequest=true snapshots=true heldReplies=true")
    }
  }
  it should "suppress helper-only local and global atomics while retiring the instruction normally" in {
    simulate(new SharedAtomicExecuteStage(config,helperInvocations=true)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      for(op<-0 until 10;local<-Seq(false,true);helperOnly<-Seq(false,true)){
        val p=d.io.invocations.get(1);p.helpers.poke(5.U);p.privateBase.poke(0x8000.U);p.privateBytes.poke(32.U)
        val address: BigInt=if(local)BigInt(config.sharedMemoryBase)else BigInt(0x8000)
        val q=d.io.in.bits;zero(q);q.decode.warpId.poke(1.U);q.decode.pc.poke(0x100.U)
        q.compareData.poke(19.U);q.decode.activeMask.poke((if(helperOnly)1 else 2).U);q.decode.decoded.atomicOp.poke(op.U);q.decode.decoded.rd.poke(5.U)
        q.rs1Data.poke(address.U);q.rs2Data.poke(7.U);d.io.in.valid.poke(true.B);d.clock.step();d.io.in.valid.poke(false.B)
        zero(d.io.invocations.get)
        if(!helperOnly){
          val req=if(local)d.io.atomicRequest else d.io.globalAtomicRequest
          val resp=if(local)d.io.atomicResponse else d.io.globalAtomicResponse
          req.valid.expect(true.B);req.bits.operation.expect(op.U);req.bits.address.expect(address.U);req.bits.compareValue.expect(19.U)
          val held=req.bits.peek().litValue;for(_<-0 until 4){d.clock.step();assert(req.bits.peek().litValue==held)}
          req.ready.poke(true.B);d.clock.step();req.ready.poke(false.B)
          resp.bits.oldValue.poke(23.U);resp.bits.fault.poke(false.B);resp.valid.poke(true.B);d.clock.step();resp.valid.poke(false.B)
        }
        d.io.atomicRequest.valid.expect(false.B);d.io.globalAtomicRequest.valid.expect(false.B);d.io.fault.valid.expect(false.B)
        d.io.out.valid.expect(true.B);d.io.out.bits.activeMask.expect((if(helperOnly)1 else 2).U);d.io.out.bits.data.expect((if(helperOnly)0 else 23).U)
        val held=d.io.out.bits.peek().litValue;for(_<-0 until 5){d.clock.step();assert(d.io.out.bits.peek().litValue==held)}
        d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)
      }
      val p=d.io.invocations.get(0);p.helpers.poke(15.U)
      d.io.in.bits.decode.warpId.poke(0.U);d.io.in.bits.decode.activeMask.poke(15.U);d.io.in.bits.rs1Data.poke(0x4001.U)
      d.io.in.valid.poke(true.B);d.clock.step();d.io.in.valid.poke(false.B)
      d.io.out.valid.expect(true.B);d.io.out.bits.data.expect(0.U);d.io.fault.valid.expect(false.B)
      info("HELPER_POLICY atomicCases=41 operations=10 sharedAndGlobal=true privateAtomicSuppressed=true misalignmentSuppressed=true architecturalRetirement=true snapshots=true heldReplies=true")
    }
  }
  for((lanes,warps,count)<-Seq((4,3,9),(16,8,128)))
  it should s"split trusted helper masks across workgroups and tail warps with lanes=$lanes warps=$warps" in {
    val c=GpuConfig(lanes=lanes,warps=warps)
    simulate(new WorkgroupDispatcher(c)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val bitmap=(0 until lanes*warps).filter(i=>i%3!=1).foldLeft(BigInt(0))((m,i)=>m.setBit(i))
      val q=d.io.workgroup.bits;q.kernelPc.poke(0x1000.U);q.kernargAddress.poke(0x8000.U)
      q.invocations.helpers.poke(bitmap.U);q.invocations.privateBase.poke(0x8000.U);q.invocations.privateBytes.poke(4096.U)
      for(i<-0 until 3){q.localSize(i).poke((if(i==0)count else 1).U);q.gridSize(i).poke(1.U)}
      d.io.workgroup.valid.poke(true.B);d.clock.step();d.io.workgroup.valid.poke(false.B);q.invocations.helpers.poke(0.U)
      val total=(count+lanes-1)/lanes
      for(w<-0 until total){
        var n=0;while(!d.io.warp.valid.peek().litToBoolean && n<10){d.clock.step();n+=1};d.io.warp.valid.expect(true.B)
        val active=(BigInt(1)<<math.min(lanes,count-w*lanes))-1
        d.io.warp.bits.activeMask.expect(active.U);d.io.warp.bits.invocations.helpers.expect(((bitmap>>(w*lanes))&active).U)
        d.io.warp.bits.invocations.privateBase.expect(0x8000.U);d.io.warp.bits.invocations.privateBytes.expect(4096.U)
        val held=d.io.warp.bits.peek().litValue;for(_<-0 until 4){d.clock.step();assert(d.io.warp.bits.peek().litValue==held)}
        d.io.warp.ready.poke(true.B);d.clock.step();d.io.warp.ready.poke(false.B)
      }
      d.io.warpCompletion.valid.poke(true.B);d.io.warpCompletion.bits.success.poke(true.B);d.clock.step(total);d.io.warpCompletion.valid.poke(false.B)
      d.io.completion.valid.expect(true.B);d.io.completion.bits.success.expect(true.B)
      info(s"HELPER_POLICY lanes=$lanes warps=$warps count=$count helperBits=${lanes*warps} exactWarpSlices=true tailMask=true heldLaunch=true snapshots=true")
    }
  }
}
