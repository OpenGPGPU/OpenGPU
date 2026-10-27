package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._

/** Explicit local check of the full native pool; never a regular frame/CI test. */
object QualifyLazyNativePayload {
  private class NativePool extends BankedShaderPayloadStore(
    NativeShaderPayloadSizing.pool(owners=5,ports=12),useBlackBoxes=true,
    taskPins=GpuConfig.MobileComputeUnits+1+TypedVertexBinImagePipeline.payloadPins(4),
    lazyInitialization=true) {
    val modelsPresent=IO(Output(Bool()))
    modelsPresent:=Module(new TileSramSimulationResources).io.present
  }
  private case class Lease(handle: BigInt,generation: BigInt,context: BigInt,owner: Int)
  def main(args: Array[String]): Unit={
    require(args.isEmpty,"usage: QualifyLazyNativePayload")
    val p=NativeShaderPayloadSizing.pool(owners=5,ports=12)
    require(p.capacityBytes==512*1024 && p.physicalBytes==512*1024)
    simulate(new NativePool){d=>
      for(c<-d.io.control){c.valid.poke(false.B);c.bits.poke(0.U.asTypeOf(c.bits))}
      d.io.controlResponse.foreach(_.ready.poke(false.B))
      for(r<-d.io.request){r.valid.poke(false.B);r.bits.poke(0.U.asTypeOf(r.bits))}
      d.io.response.foreach(_.ready.poke(false.B))
      d.io.pins.foreach(_.foreach{pin=>pin.valid.poke(false.B);pin.bits.poke(0.U.asTypeOf(pin.bits))})
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def until(condition: => Boolean): Unit={
        var n=0;while(!condition && n<100){d.clock.step();n+=1};assert(condition,"native payload did not progress")
      }
      def control(owner: Int,operation: Int,previous: Lease=Lease(0,0,0,0),context: Int=0): Lease={
        val c=d.io.control(owner);c.bits.operation.poke(operation.U);c.bits.tag.poke(0.U)
        c.bits.handle.poke(previous.handle.U);c.bits.generation.poke(previous.generation.U)
        c.bits.context.poke((if(operation==ShaderPayloadControl.allocate)BigInt(context)else previous.context).U)
        c.bits.bytes.poke(p.maxPayloadBytes.U);c.bits.allowSpill.poke(false.B);c.valid.poke(true.B)
        until(c.ready.peek().litToBoolean);d.clock.step();c.valid.poke(false.B)
        val r=d.io.controlResponse(owner);until(r.valid.peek().litToBoolean)
        r.bits.fault.expect(false.B);r.bits.spill.expect(false.B)
        val l=Lease(r.bits.handle.peek().litValue,r.bits.generation.peek().litValue,r.bits.context.peek().litValue,owner)
        r.ready.poke(true.B);d.clock.step();r.ready.poke(false.B);l
      }
      val full=(BigInt(1)<<64)-1
      def access(l: Lease,offset: Int,write: Boolean=false,value: BigInt=0,mask: BigInt=full,expected: BigInt=0): Unit={
        val port=(l.owner+offset/64)%p.ports;val q=d.io.request(port)
        q.bits.handle.poke(l.handle.U);q.bits.generation.poke(l.generation.U);q.bits.context.poke(l.context.U)
        q.bits.owner.poke(l.owner.U);q.bits.tag.poke(17.U);q.bits.offset.poke(offset.U)
        q.bits.write.poke(write.B);q.bits.data.poke(value.U);q.bits.mask.poke(mask.U);q.valid.poke(true.B)
        until(q.ready.peek().litToBoolean);d.clock.step();q.valid.poke(false.B)
        val r=d.io.response(port);until(r.valid.peek().litToBoolean)
        r.bits.fault.expect(false.B);r.bits.tag.expect(17.U);r.bits.data.expect(expected.U)
        val held=r.bits.peek().litValue;d.clock.step(3);assert(r.bits.peek().litValue==held)
        r.ready.poke(true.B);d.clock.step();r.ready.poke(false.B)
      }
      val offsets=Seq(0,255*64,256*64,p.slotBytes-64).distinct
      val leases=Vector.tabulate(p.slots)(i=>control(i%p.owners,ShaderPayloadControl.allocate,context=100+i))
      d.io.reservedBytes.expect(p.reservedBytes.U)
      // Reach both sides of a macro boundary and the final reserved row in
      // every slot. A partial first write must zero every other byte.
      for((l,i)<-leases.zipWithIndex;offset<-offsets){
        access(l,offset)
        access(l,offset,write=true,value=BigInt(0x80+i)<<504,mask=BigInt(1)<<63)
        access(l,offset,expected=BigInt(0x80+i)<<504)
      }
      leases.foreach(l=>control(l.owner,ShaderPayloadControl.release,l))
      val reused=Vector.tabulate(p.slots)(i=>control(i%p.owners,ShaderPayloadControl.allocate,context=200+i))
      for(l<-reused;offset<-offsets)access(l,offset)
      reused.foreach(l=>control(l.owner,ShaderPayloadControl.release,l));d.io.drained.expect(true.B)
      println(s"LAZY_NATIVE_PAYLOAD PASS slots=${p.slots} reservedBytes=${p.reservedBytes} physicalBytes=${p.physicalBytes} macros=${p.segments*16} ports=${p.ports} pins=${d.io.pins.get.length} segmentEdges=true finalRows=true partialFirstWrite=true poisonedReuse=true heldReplies=true drained=true")
    }
  }
}
