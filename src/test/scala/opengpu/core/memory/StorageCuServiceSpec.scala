package opengpu.core.memory

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class StorageCuServiceSpec extends AnyFlatSpec {
  behavior of "StorageCuService"
  for (translated <- Seq(false, true)) {
    it should s"retain its task publication and drain a checked store with Sv32=$translated" in {
      simulate(new StorageCuService(GpuConfig(lanes = 2, warps = 1))) { d =>
        d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
        d.io.kernelStart.poke(false.B); d.io.kernelCompletion.poke(false.B)
        d.io.flush.valid.poke(false.B); d.io.flush.bits.poke(0.U.asTypeOf(d.io.flush.bits))
        d.io.cancel.poke(false.B); d.io.in.valid.poke(false.B); d.io.out.ready.poke(false.B)
        d.io.memoryRequest.ready.poke(false.B); d.io.memoryResponse.valid.poke(false.B)
        d.io.memoryResponse.bits.poke(0.U.asTypeOf(d.io.memoryResponse.bits))
        d.io.task.enabled.poke(true.B); d.io.task.table.version.poke(1.U)
        d.io.task.table.base.poke(0x4000.U); d.io.task.table.limit.poke(0x4100.U)
        d.io.task.table.identity.context.poke(7.U); d.io.task.table.identity.pass.poke(11.U)
        d.io.task.table.identity.generation.poke(13.U)
        d.io.satp.poke((if (translated) BigInt("80000080", 16) else BigInt(0)).U)
        d.io.in.valid.poke(true.B); d.io.kernelStart.poke(true.B)
        d.io.in.ready.expect(false.B); d.clock.step()
        d.io.in.valid.poke(false.B); d.io.kernelStart.poke(false.B)
        d.io.task.enabled.poke(false.B); d.io.task.table.base.poke(0.U); d.io.satp.poke(0.U)
        d.io.enabled.expect(true.B)
        d.io.in.bits.bindingIndex.poke(3.U); d.io.in.bits.offset.poke(12.U)
        d.io.in.bits.write.poke(true.B); d.io.in.bits.data.poke("h12345678".U)
        d.io.in.bits.byteMask.poke(15.U); d.io.in.bits.tag.poke(99.U)
        d.io.in.valid.poke(true.B); d.io.in.ready.expect(true.B); d.clock.step(); d.io.in.valid.poke(false.B)
        val words = Array.fill[BigInt](16)(0)
        for ((n,v) <- Seq[(Int, BigInt)](0 -> StorageDescriptorLayout.magic, 1 -> 64, 2 -> 1,
          3 -> 7, 4 -> 11, 5 -> 13, 6 -> 3, 7 -> 1, 8 -> 1, 9 -> 1,
          10 -> 0x10000, 11 -> 0x10100, 13 -> 0x10000, 14 -> 0x10100)) words(n) = v
        val record = words.zipWithIndex.map { case(v,n) => v << (32*n) }.sum
        val physical = if (translated) 0x400000 else 0
        var walks = 0; var descriptors = 0; var writes = 0
        var pending: Option[(BigInt, BigInt)] = None
        var cycle = 0
        while (!d.io.out.valid.peek().litToBoolean && cycle < 300) {
          d.io.memoryRequest.ready.poke((cycle % 3 != 0).B)
          d.io.memoryResponse.valid.poke(pending.nonEmpty.B)
          pending.foreach { case(id,data) =>
            d.io.memoryResponse.bits.transactionId.poke(id.U); d.io.memoryResponse.bits.readData.poke(data.U)
          }
          val taken = pending.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean
          val req = if (d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean) {
            assert(pending.isEmpty || taken)
            val r = d.io.memoryRequest.bits; val id = r.transactionId.peek().litValue
            val address = r.address.peek().litValue.toInt
            val data = if (id == 2) {
              assert(translated && address == 0x80000); walks += 1
              (BigInt(0x400) << 10) | 0xcf
            } else if (id == 0) {
              assert(address == physical + 0x40c0); descriptors += 1; record
            } else {
              assert(id == 1 && address == physical + 0x10000); writes += 1
              r.isWrite.expect(true.B); r.byteMask.expect((BigInt(15) << 12).U)
              r.writeData.expect((BigInt("12345678",16) << 96).U); BigInt(0)
            }
            Some(id -> data)
          } else None
          if (taken) pending = None
          d.clock.step(); cycle += 1
          req.foreach(r => pending = Some(r))
        }
        assert(d.io.out.valid.peek().litToBoolean && pending.isEmpty)
        d.io.out.bits.tag.expect(99.U); d.io.out.bits.bindingIndex.expect(3.U)
        d.io.out.bits.status.expect(StorageWordStatus.ok.U); d.io.out.bits.source.expect(2.U)
        assert(descriptors == 1 && writes == 1 && (if (translated) walks > 0 else walks == 0),
          s"descriptors=$descriptors writes=$writes walks=$walks")
        val held = d.io.out.bits.peek().litValue; d.clock.step(3); assert(d.io.out.bits.peek().litValue == held)
        d.io.out.ready.poke(true.B); d.clock.step(); d.io.out.ready.poke(false.B)
        d.io.busy.expect(false.B); d.io.kernelCompletion.poke(true.B); d.clock.step()
      }
    }
  }
  it should "reject a translated write before physical data traffic when the page is read only" in {
    simulate(new StorageCuService(GpuConfig(lanes = 2, warps = 1))) { d =>
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      d.io.kernelStart.poke(false.B); d.io.kernelCompletion.poke(false.B)
      d.io.flush.valid.poke(false.B); d.io.flush.bits.poke(0.U.asTypeOf(d.io.flush.bits))
      d.io.cancel.poke(false.B); d.io.in.valid.poke(false.B); d.io.out.ready.poke(false.B)
      d.io.memoryRequest.ready.poke(true.B); d.io.memoryResponse.valid.poke(false.B)
      d.io.memoryResponse.bits.poke(0.U.asTypeOf(d.io.memoryResponse.bits))
      d.io.task.enabled.poke(true.B); d.io.task.table.version.poke(1.U)
      d.io.task.table.base.poke(0x4000.U); d.io.task.table.limit.poke(0x4100.U)
      d.io.task.table.identity.context.poke(7.U); d.io.task.table.identity.pass.poke(11.U)
      d.io.task.table.identity.generation.poke(13.U); d.io.satp.poke(BigInt("80000080",16).U)
      d.io.kernelStart.poke(true.B); d.clock.step(); d.io.kernelStart.poke(false.B)
      d.io.in.bits.bindingIndex.poke(0.U); d.io.in.bits.offset.poke(0.U)
      d.io.in.bits.write.poke(true.B); d.io.in.bits.data.poke(1.U)
      d.io.in.bits.byteMask.poke(15.U); d.io.in.bits.tag.poke(1.U)
      d.io.in.valid.poke(true.B); d.clock.step(); d.io.in.valid.poke(false.B)
      val words = Array.fill[BigInt](16)(0)
      for ((n,v) <- Seq[(Int, BigInt)](0 -> StorageDescriptorLayout.magic, 1 -> 64, 2 -> 1,
        3 -> 7, 4 -> 11, 5 -> 13, 6 -> 0, 7 -> 1, 8 -> 1, 9 -> 1,
        10 -> 0x10000, 11 -> 0x10100, 13 -> 0x10000, 14 -> 0x10100)) words(n) = v
      val record = words.zipWithIndex.map { case(v,n) => v << (32*n) }.sum
      var pending: Option[(BigInt,BigInt)] = None; var walks=0; var descriptors=0; var dataRequests=0; var cycles=0
      while (!d.io.out.valid.peek().litToBoolean && cycles < 300) {
        d.io.memoryResponse.valid.poke(pending.nonEmpty.B)
        pending.foreach { case(id,data) => d.io.memoryResponse.bits.transactionId.poke(id.U); d.io.memoryResponse.bits.readData.poke(data.U) }
        val taken=pending.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean
        val req=if (d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean) {
          assert(pending.isEmpty || taken)
          val r=d.io.memoryRequest.bits;val id=r.transactionId.peek().litValue;val a=r.address.peek().litValue
          val data=if(id==2){assert(a==0x80000);walks+=1;(BigInt(0x400)<<10)|0xc3}
            else if(id==0){assert(a==0x404000);descriptors+=1;record}
            else {dataRequests+=1;BigInt(0)}
          Some(id->data)
        } else None
        if(taken)pending=None
        d.clock.step();cycles+=1;req.foreach(x=>pending=Some(x))
      }
      d.io.out.valid.expect(true.B);d.io.out.bits.source.expect(2.U)
      d.io.out.bits.status.expect(StorageWordStatus.memoryFault.U)
      d.io.out.bits.faultAddressValid.expect(true.B);d.io.out.bits.faultAddress.expect(0x10000.U)
      assert(walks>0 && descriptors==1 && dataRequests==0 && pending.isEmpty)
    }
  }

}
