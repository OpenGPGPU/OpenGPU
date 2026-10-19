package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import scala.collection.mutable

private[graphics] object DoubleBufferedTileAttachmentStoreTestSupport {
  import TilePassTestData._
  case class Request(tid: Int,address: Long,write: Boolean,data: BigInt,mask: BigInt,due: Int)
  case class Result(id: Int,status: Int,loads: Long,stores: Long,resolves: Long)

  class Driver(val d: DoubleBufferedTileAttachmentStore,window: Int=2,latency: Int=2,writeLimit: Int=100,maxWait: Int=20000) {
    private val perSlot=window+1
    val memory=mutable.LongMap.empty[Long]
    val pending=mutable.ArrayBuffer.empty[Request]
    val acceptedWrites=mutable.ArrayBuffer.empty[Request]
    val live=mutable.Set.empty[Int]
    var returning=Option.empty[Request]
    var responseData=BigInt(0)
    var responseFault=false
    var holdRequests=false
    val holdWrites=Array(false,false)
    var faultSlot=Option.empty[Int]
    val reads=Array(0,0); val writes=Array(0,0); val writeWords=Array(0,0)
    var cycle=0
    private var held=Option.empty[BigInt]
    private val heldSlots=Array.fill[Option[BigInt]](2)(None)
    private var nextSlot=0
    d.io.pass.begin.valid.poke(false.B); d.io.pass.begin.bits.poke(0.U.asTypeOf(d.io.pass.begin.bits))
    d.io.pass.end.valid.poke(false.B); d.io.pass.end.bits.poke(true.B)
    d.io.pass.clear.valid.poke(false.B); d.io.pass.clear.bits.poke(0.U.asTypeOf(d.io.pass.clear.bits))
    d.io.pass.clearDone.ready.poke(true.B); d.io.pass.completion.ready.poke(false.B)
    d.io.pass.cancel.poke(false.B); d.io.pass.memoryFault.poke(false.B)
    d.io.fragmentX.poke(0.U); d.io.fragmentY.poke(0.U)
    d.io.producerDrained.poke(true.B); d.io.omDrained.poke(true.B); d.io.executionFault.poke(false.B)
    d.io.wordIndex.poke(0.U); d.io.depthPlane.poke(false.B)
    d.io.om.req.valid.poke(false.B); d.io.om.req.bits.poke(0.U.asTypeOf(d.io.om.req.bits))
    d.io.om.resp.ready.poke(false.B)
    d.io.quad.foreach { q=>q.valid.poke(false.B);q.bits.poke(0.U.asTypeOf(q.bits)) }
    d.io.quadCompletion.foreach(_.ready.poke(true.B))
    d.io.mem.req.ready.poke(false.B); d.io.mem.resp.valid.poke(false.B)
    d.io.mem.resp.bits.poke(0.U.asTypeOf(d.io.mem.resp.bits))
    d.io.memoryRequest.ready.poke(false.B); d.io.memoryResponse.valid.poke(false.B)
    d.io.memoryResponse.bits.poke(0.U.asTypeOf(d.io.memoryResponse.bits))
    d.io.slotMemoryRequest.foreach(_.foreach(_.ready.poke(false.B)))
    d.io.slotMemoryResponse.foreach(_.foreach { r=>r.valid.poke(false.B);r.bits.poke(0.U.asTypeOf(r.bits)) })
    d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
    def beforeClock(): Unit = ()
    def step(): Unit = {
      val writeLive=pending.count(_.write)+returning.count(_.write)
      val external=d.io.slotMemoryRequest.nonEmpty
      val slot=if(external) {
        val ports=d.io.slotMemoryRequest.get
        for(s <- 0 until 2) {
          ports(s).ready.poke(false.B)
          heldSlots(s).foreach { bits=>ports(s).valid.expect(true.B);assert(ports(s).bits.peek().litValue==bits) }
        }
        (0 until 2).map(n=>(nextSlot+n)%2).find(s=>ports(s).valid.peek().litToBoolean &&
          !(ports(s).bits.isWrite.peek().litToBoolean && writeLive>=writeLimit)).getOrElse(nextSlot)
      }else 0
      val request=if(external)d.io.slotMemoryRequest.get(slot)else d.io.memoryRequest
      val ready= !holdRequests && cycle%5!=2 &&
        !(request.bits.isWrite.peek().litToBoolean && writeLive>=writeLimit)
      request.ready.poke(ready.B)
      if(external) {
        d.io.memoryRequest.valid.expect(false.B)
        for(s <- 0 until 2) {
          val p=d.io.slotMemoryRequest.get(s)
          heldSlots(s)=if(p.valid.peek().litToBoolean && !p.ready.peek().litToBoolean)Some(p.bits.peek().litValue)else None
        }
      }else {
        held.foreach { bits=>request.valid.expect(true.B);assert(request.bits.peek().litValue==bits) }
        held=if(request.valid.peek().litToBoolean && !ready)Some(request.bits.peek().litValue)else None
      }
      if (returning.isEmpty) {
        val i=pending.lastIndexWhere(r=>r.due<=cycle && !(r.write && holdWrites(r.tid/perSlot)))
        if (i>=0) {
          returning=Some(pending.remove(i))
          val r=returning.get
          responseData=(0 until 16).foldLeft(BigInt(0))((v,n)=>v|
            (BigInt(memory.getOrElse(r.address+n*4L,0xdeadbeefL))<<(32*n)))
          responseFault=faultSlot.contains(r.tid/perSlot)
          if (responseFault) faultSlot=None
        }
      }
      d.io.slotMemoryResponse.foreach(_.foreach(_.valid.poke(false.B)))
      val response=if(external)d.io.slotMemoryResponse.get(returning.map(_.tid/perSlot).getOrElse(0))else d.io.memoryResponse
      response.valid.poke(returning.nonEmpty.B)
      returning.foreach { r=>
        response.bits.transactionId.poke((if(external)r.tid%perSlot else r.tid).U)
        response.bits.readData.poke(responseData.U(512.W))
        response.bits.fault.poke(responseFault.B)
      }
      if (returning.nonEmpty && response.ready.peek().litToBoolean) {
        val r=returning.get
        if (r.write && !responseFault) for (n <- 0 until 16 if ((r.mask>>(4*n))&15)==15)
          memory(r.address+n*4L)=((r.data>>(32*n))&0xffffffffL).toLong
        assert(live.remove(r.tid)); returning=None
      }
      if (request.valid.peek().litToBoolean && ready) {
        val b=request.bits; val localTid=b.transactionId.peek().litValue.toInt
        if(external){assert(localTid<perSlot);nextSlot=(slot+1)%2}
        val tid=localTid+(if(external)slot*perSlot else 0)
        assert(live.add(tid)); assert(tid<2*perSlot)
        val write=b.isWrite.peek().litToBoolean
        if (write) { writes(tid/perSlot)+=1; writeWords(tid/perSlot)+=b.byteMask.peek().litValue.bitCount/4 } else reads(tid/perSlot)+=1
        val beat=Request(tid,b.address.peek().litValue.toLong,write,b.writeData.peek().litValue,
          b.byteMask.peek().litValue,cycle+latency+tid%4)
        pending+=beat
        if(write)acceptedWrites+=beat
      }
      d.io.mem.req.valid.expect(false.B)
      beforeClock()
      d.clock.step(); cycle+=1
    }
    def until(p: => Boolean,max: Int=maxWait): Unit = {
      val start=cycle
      while (!p && cycle-start<max) step()
      assert(p,s"stalled at $cycle live=$live pending=$pending")
    }
    def begin(p: Pass): Unit = {
      pokeDescriptor(d.io.pass.begin.bits,p); d.io.pass.begin.valid.poke(true.B)
      until(d.io.pass.begin.ready.peek().litToBoolean); step(); d.io.pass.begin.valid.poke(false.B)
      d.io.pass.begin.bits.poke(0.U.asTypeOf(d.io.pass.begin.bits))
      until(d.io.pass.end.ready.peek().litToBoolean || d.io.pass.completion.valid.peek().litToBoolean)
    }
    def om(p: Pass,x: Int,y: Int,depth: Boolean,data: Option[Long]=None,sample: Int=0): Long = {
      d.io.producerDrained.poke(false.B); d.io.omDrained.poke(false.B)
      d.io.fragmentX.poke((p.originX+x).U); d.io.fragmentY.poke((p.originY+y).U)
      d.io.wordIndex.poke(p.index(x,y,sample).U); d.io.depthPlane.poke(depth.B)
      d.io.om.req.bits.addr.poke(p.address(x,y,sample,depth).U)
      d.io.om.req.bits.write.poke(data.nonEmpty.B); d.io.om.req.bits.data.poke(data.getOrElse(0L).U)
      d.io.om.req.valid.poke(true.B); until(d.io.om.req.ready.peek().litToBoolean)
      step(); d.io.om.req.valid.poke(false.B)
      until(d.io.om.resp.valid.peek().litToBoolean)
      val result=d.io.om.resp.bits.data.peek().litValue.toLong
      d.io.om.resp.bits.addr.expect(p.address(x,y,sample,depth).U)
      d.io.om.resp.ready.poke(true.B); step(); d.io.om.resp.ready.poke(false.B)
      d.io.producerDrained.poke(true.B); d.io.omDrained.poke(true.B)
      result
    }
    def end(): Unit = {
      d.io.pass.end.valid.poke(true.B); until(d.io.pass.end.ready.peek().litToBoolean)
      step(); d.io.pass.end.valid.poke(false.B)
    }
    def retire(): Result = {
      until(d.io.pass.completion.valid.peek().litToBoolean)
      val bits=d.io.pass.completion.bits.peek().litValue
      val counts=d.io.completionBytes.peek().litValue
      val result=Result(d.io.pass.completion.bits.identity.pass.peek().litValue.toInt,
        d.io.pass.completion.bits.status.peek().litValue.toInt,
        d.io.completionBytes.loadBytes.peek().litValue.toLong,
        d.io.completionBytes.storeBytes.peek().litValue.toLong,
        d.io.completionBytes.resolveBytes.peek().litValue.toLong)
      for (_ <- 0 until 4) {
        step(); d.io.pass.completion.valid.expect(true.B)
        assert(d.io.pass.completion.bits.peek().litValue==bits && d.io.completionBytes.peek().litValue==counts)
      }
      d.io.pass.completion.ready.poke(true.B); step(); d.io.pass.completion.ready.poke(false.B)
      result
    }
  }

}
