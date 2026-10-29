package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class NativeTextureResponseBankSpec extends AnyFlatSpec {
  import WindowRecordTest._

  for (destinations <- Seq(1, 3)) {
    it should s"retain ordered bank-local responses with $destinations destinations through reuse" in {
      simulate(new NativeRgba8TextureSharedWindow(1, destinations, 4, 1)) { d =>
        zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
        d.io.allow.poke(true.B)
        d.io.child.foreach(_.request.ready.poke(true.B))
        val client = d.io.client(0)
        for (pass <- 0 until 2) {
          val issued = Array.fill[Option[(BigInt, BigInt)]](destinations)(None)
          def tick(): Unit = {
            for (n <- 0 until destinations) {
              val child = d.io.child(n)
              if (child.request.valid.peek().litToBoolean) {
                assert(issued(n).isEmpty)
                val row = child.request.bits.tag.peek().litValue
                val token = child.request.bits.coordinates(0).peek().litValue
                assert(row / 4 == n && row % 4 == 0)
                assert(token == pass * 16 + n + 1)
                issued(n) = Some((row, token))
              }
            }
            d.clock.step()
          }
          def until(condition: => Boolean): Unit = {
            var clocks = 0
            while (!condition && clocks < 128) { tick(); clocks += 1 }
            assert(condition, s"response bank stalled: destinations=$destinations pass=$pass")
          }
          client.response.ready.poke(false.B)
          for (n <- 0 until destinations) {
            zero(client.request.bits); identity(client.request.bits.lookup.table.identity)
            client.request.bits.tag.poke((0x4000 + pass * 16 + n).U)
            client.request.bits.coordinates(0).poke((pass * 16 + n + 1).U)
            d.io.target(0).poke(n.U); client.request.valid.poke(true.B)
            until(client.request.ready.peek().litToBoolean); tick()
            client.request.valid.poke(false.B)
          }
          until(issued.forall(_.nonEmpty))
          for (n <- 0 until destinations) {
            val child = d.io.child(n); val (row, token) = issued(n).get
            zero(child.response.bits); identity(child.response.bits.identity)
            child.response.bits.tag.poke(row.U)
            child.response.bits.rgba.zipWithIndex.foreach { case (v, lane) => v.poke((token * 16 + lane).U) }
            child.response.valid.poke(true.B); child.response.ready.expect(true.B)
          }
          tick(); d.io.child.foreach(_.response.valid.poke(false.B))
          until(client.response.valid.peek().litToBoolean)
          val held = client.response.bits.peek().litValue
          for (_ <- 0 until 4) {
            tick(); client.response.valid.expect(true.B)
            assert(client.response.bits.peek().litValue == held)
          }
          client.response.ready.poke(true.B)
          for (n <- 0 until destinations) {
            until(client.response.valid.peek().litToBoolean)
            client.response.bits.status.expect(0.U)
            client.response.bits.tag.expect((0x4000 + pass * 16 + n).U)
            client.response.bits.rgba.zipWithIndex.foreach { case (v, lane) =>
              v.expect(((pass * 16 + n + 1) * 16 + lane).U)
            }
            tick()
          }
          until(!d.io.busy.peek().litToBoolean)
          client.response.valid.expect(false.B)
        }
        info(s"NATIVE_SHARED_BANK_CONFIG destinations=$destinations equalLocalRows=true simultaneousReplies=true orderedTagsAndValues=true heldReplyImmutable=true reuse=true")
      }
    }
  }
}
