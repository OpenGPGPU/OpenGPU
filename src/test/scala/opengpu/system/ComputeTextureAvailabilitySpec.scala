package opengpu.system

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.dispatch.KernelCommandStatus
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** Compute tasks have no sampler owner, including on a graphics-capable CU. */
class ComputeTextureAvailabilitySpec extends AnyFlatSpec {
  private def zero(data: Data): Unit = data match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case _ => ()
  }
  private val scalarWords = Seq("0200050b", "0400050b", "0600000b", "0800050b", "0a00050b", "0c00050b", "0c00150b")
  private val vectorWords = Seq("0600052b", "0a00052b", "0e00002b", "1200052b", "1600052b", "1a00052b", "1a00152b")
  for (vector <- Seq(false, true); graphics <- Seq(false, true)) {
    it should s"fault all unavailable texture families without a quota and reuse the compute system vector=$vector graphics=$graphics" in {
      val config = GpuConfig(lanes=4, warps=2, l2Sets=8, l2Ways=2)
      simulate(new GpuSystem(config, numComputeUnits=1,
        instructionCacheSets=4, instructionCacheWays=1, vectorCacheSets=4, vectorCacheWays=1,
        enableGraphicsKernels=graphics)) { d =>
        zero(d.io); d.io.graphicsDrained.poke(true.B)
        d.io.graphicsHostResponse.ready.poke(true.B); d.io.graphicsShaderResponse.ready.poke(true.B)
        d.io.graphicsShaderL1Invalidate.ready.poke(true.B); d.io.graphicsShaderAtomicResponse.ready.poke(true.B)
        d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
        val memory = mutable.LongMap.empty[Int]
        def word(address: Long, value: BigInt): Unit =
          for (i <- 0 until 4) memory(address+i) = ((value >> (i*8)) & 255).toInt
        def read(address: Long, bytes: Int): BigInt =
          (0 until bytes).map(i => BigInt(memory.getOrElse(address+i,0)) << (i*8)).sum
        case class Reply(id: BigInt, address: Long, data: BigInt, mask: BigInt, writing: Boolean, due: Int)
        val pending = mutable.Queue.empty[Reply]
        val traps = mutable.ArrayBuffer.empty[(BigInt,BigInt,BigInt,BigInt,BigInt)]
        var cycle = 0; var stores = 0
        def tick(): Unit = {
          d.io.memoryRequest.ready.poke((cycle%7 != 0).B)
          val reply = pending.headOption.filter(_.due <= cycle)
          d.io.memoryResponse.valid.poke(reply.nonEmpty.B)
          reply.foreach { r =>
            d.io.memoryResponse.bits.transactionId.poke(r.id.U)
            d.io.memoryResponse.bits.readData.poke((if (r.writing) BigInt(0) else r.data).U)
            d.io.memoryResponse.bits.fault.poke(false.B)
          }
          val responseFire = reply.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean
          val request = if (d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean) {
            val q=d.io.memoryRequest.bits; val address=q.address.peek().litValue.toLong
            Some(Reply(q.transactionId.peek().litValue,address,
              if(q.isWrite.peek().litToBoolean) q.writeData.peek().litValue else read(address,64),
              q.byteMask.peek().litValue,q.isWrite.peek().litToBoolean,cycle+5))
          } else None
          for (port <- d.io.trap if port.valid.peek().litToBoolean && port.ready.peek().litToBoolean) {
            val q=port.bits; traps += ((q.warpId.peek().litValue,q.pc.peek().litValue,
              q.cause.peek().litValue,q.tval.peek().litValue,q.activeMask.peek().litValue))
          }
          if (responseFire) {
            val r=pending.dequeue()
            if(r.writing) {
              for(i <- 0 until 64 if r.mask.testBit(i)) memory(r.address+i)=((r.data>>(i*8))&255).toInt
              stores += 1
            }
          }
          d.clock.step(); cycle += 1; request.foreach(pending.enqueue(_))
        }
        def until(label: String)(condition: => Boolean): Unit = {
          val start=cycle
          while(!condition && cycle-start<1800) tick()
          assert(condition,s"$label: compute-only texture instruction did not retire; cycle=$cycle busy=${d.io.busyComputeUnits.peek().litValue} traps=$traps")
        }
        def launch(pc: Int, items: Int): Unit = {
          d.io.command.bits.poke(0.U.asTypeOf(d.io.command.bits))
          d.io.command.bits.commandId.poke(7.U); d.io.command.bits.launch.kernelPc.poke(pc.U)
          d.io.command.bits.launch.kernargAddress.poke(0x8000.U)
          for(i <- 0 until 3) {
            d.io.command.bits.launch.gridSize(i).poke(1.U)
            d.io.command.bits.launch.localSize(i).poke((if(i==0) items else 1).U)
          }
          d.io.command.valid.poke(true.B); until("command admission")(d.io.command.ready.peek().litToBoolean)
          tick(); d.io.command.valid.poke(false.B)
        }
        def completion(success: Boolean): Unit = {
          until("command completion")(d.io.commandCompletion.valid.peek().litToBoolean)
          d.io.commandCompletion.bits.commandId.expect(7.U)
          d.io.commandCompletion.bits.success.expect(success.B)
          d.io.commandCompletion.bits.status.expect(if(success) KernelCommandStatus.success else KernelCommandStatus.executionFailed)
          val held=d.io.commandCompletion.bits.peek().litValue
          for(_ <- 0 until 9) {
            d.io.vmContextIdle.expect(false.B); assert(d.io.commandCompletion.bits.peek().litValue==held); tick()
          }
          d.io.commandCompletion.ready.poke(true.B); tick(); d.io.commandCompletion.ready.poke(false.B)
          until("VM context retirement")(d.io.vmContextIdle.peek().litToBoolean)
        }
        val cease=BigInt("30500073",16)
        val selected=if(vector) vectorWords else scalarWords
        for((hex,index) <- selected.zipWithIndex) {
          val pc=0x10000+index*0x200; val opcode=BigInt(hex,16)
          val prefix=if(vector) Seq(BigInt("0104f057",16)) else Seq.empty[BigInt]
          (prefix ++ Seq(opcode,cease)).zipWithIndex.foreach { case(v,i) => word(pc+4*i,v) }
          val before=traps.size; val oldStores=stores
          d.io.trap.foreach(_.ready.poke(false.B)); launch(pc,8)
          until("attributed illegal instruction")(d.io.trap(0).valid.peek().litToBoolean)
          val offered=d.io.trap(0).bits; offered.cause.expect(2.U); offered.pc.expect((pc+4*prefix.size).U)
          offered.tval.expect(opcode.U); offered.activeMask.expect(15.U); offered.laneFaultMask.expect(15.U)
          val held=offered.peek().litValue
          for(_ <- 0 until 9) { assert(offered.peek().litValue==held); d.io.commandCompletion.valid.expect(false.B); tick() }
          d.io.trap.foreach(_.ready.poke(true.B)); completion(false)
          val faults=traps.drop(before)
          assert(faults.map(_._1).toSet==Set(BigInt(0),BigInt(1)) && faults.size==2)
          assert(faults.forall(t => t._2==pc+4*prefix.size && t._3==2 && t._4==opcode && t._5==15))
          assert(stores==oldStores && pending.isEmpty)
          // Same public ID, healthy integer/store successor, real ACK and guards.
          val healthyPc=pc+0x100
          Seq(BigInt("05a00513",16),BigInt("00a0a023",16),cease).zipWithIndex.foreach { case(v,i) => word(healthyPc+4*i,v) }
          for(i <- -4 until 8) memory(0x8000L+i)=0xa5
          launch(healthyPc,1); completion(true)
          assert(read(0x8000,4)==0x5a && traps.size==before+2 && stores==oldStores+1)
          assert((-4 until 0).forall(i => memory(0x8000L+i)==0xa5) && (4 until 8).forall(i => memory(0x8000L+i)==0xa5))
        }
        info(s"COMPUTE_TEXTURE_AVAILABILITY vector=$vector graphics=$graphics families7=true quotasDisabled=true faults14=true heldTrapsAndCompletions=true healthySuccessors7=true exactStoresAndGuards=true samePublicIDReuse=true")
      }
    }
  }
}
