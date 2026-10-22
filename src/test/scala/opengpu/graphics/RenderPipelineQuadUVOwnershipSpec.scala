package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** Exercise the production UV handoff with a real CU reading its staged U/V,
  * a pending second draw, distinct target/kernarg owners, and held responses.
  * Each small triangle occupies one quad, avoiding unrelated legacy single-bank
  * L1 coherence assumptions between batches of the same draw.
  */
class RenderPipelineQuadUVOwnershipSpec extends AnyFlatSpec {
  it should "shade two pending draw owners from captured unsigned UVs at every sample mode" in {
    val gfx = GraphicsConfig(screenWidth = 4, screenHeight = 4)
    val gpu = GpuConfig(lanes = 4, warps = 2)
    val colors = Vector(0x81abcdefL, 0xfedcba97L)
    val vDepths = Vector(0x00345678, 0x00654321)
    val pc = 0x1000
    def addi(rd: Int, rs: Int, imm: Int): Int = ((imm & 0xfff) << 20) | (rs << 15) | (rd << 7) | 0x13
    def vle(rs: Int, vd: Int): Int = (1 << 25) | (6 << 12) | (vd << 7) | (rs << 15) | 7
    def vse(rs: Int, vs: Int): Int = (1 << 25) | (6 << 12) | (vs << 7) | (rs << 15) | 0x27
    val program = Vector((2 << 20) | (8 << 15) | (1 << 12) | (5 << 7) | 0x13,
      (5 << 20) | (1 << 15) | (5 << 7) | 0x33,
      (3 << 30) | (0x10 << 20) | (4 << 15) | (7 << 12) | 0x57,
      addi(6,5,128), vle(6,2), addi(6,5,192), vse(6,2),
      addi(6,5,160), vle(6,3), addi(6,5,224), vse(6,3), 0x30500073)
    for (mode <- 0 until 3) {
      val memory = mutable.LongMap.empty[Long]
      def word(a: Long): Long = memory.getOrElse(a, 0L)
      def write(a: Long, value: Long): Unit = memory(a) = value & 0xffffffffL
      def line(a: Long): BigInt = (0 until 16).map(i => BigInt(word(a+i*4)) << (i*32)).sum
      def lineWrite(a: Long, data: BigInt, mask: BigInt): Unit = {
        for (i <- 0 until 64 if mask.testBit(i)) {
          val address = a + (i / 4) * 4
          val shift = (i % 4) * 8
          write(address, (word(address) & ~(255L << shift)) | (((data >> (i*8)).toLong & 255L) << shift))
        }
      }
      program.zipWithIndex.foreach { case (inst,i) => write(pc+i*4,inst.toLong) }
      val samples = 1 << mode
      for (owner <- 0 until 2; i <- 0 until 16*samples)
        write(0x9000 + owner*0x2000 + i*4,0x00ffffff)
      simulate(new RenderPipeline(gfx,gpu,fragCore=true)) { d =>
        d.io.draw.valid.poke(false.B)
        d.io.texMem.req.ready.poke(true.B); d.io.texMem.resp.valid.poke(false.B)
        d.io.kernelL1Invalidate.valid.poke(false.B); d.io.kernelL1InvalidateDone.ready.poke(true.B)
        d.io.kernelGlobalAtomicRequest.ready.poke(true.B); d.io.kernelGlobalAtomicResponse.valid.poke(false.B)
        d.io.texEnable.poke(false.B); d.io.sampleMode.poke(mode.U)
        d.io.stride.poke((4*samples*4).U)
        d.io.depthTestEnable.poke(true.B); d.io.depthWriteEnable.poke(true.B)
        d.io.depthFunc.poke(0.U); d.io.cullMode.poke(0.U)
        d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
        val lines = mutable.Queue.empty[(BigInt,BigInt)]
        val words = mutable.Queue.empty[(BigInt,BigInt)]
        val pixels = mutable.Queue.empty[(Boolean,Long,Long)]
        var admitted = 0; var cycles = 0; var done = false
        var pixelVisible = false; var lineVisible = false; var wordVisible = false
        val admissions = mutable.ArrayBuffer.empty[Int]
        while (!done && cycles < 30000) {
          d.io.draw.valid.poke((admitted < 2).B)
          if (admitted < 2) {
            val owner = admitted
            val draw = d.io.draw.bits.asInstanceOf[SceneTriangle]
            draw.poke(0.U.asTypeOf(draw))
            draw.shaderPc.poke(pc.U); draw.shaderKernarg.poke((0x6000+owner*0x1000).U)
            val vertices = Vector((-65536,-65536),(0,-65536),(-65536,0))
            for (i <- 0 until 3) {
              draw.clip(i).x.poke(vertices(i)._1.S); draw.clip(i).y.poke(vertices(i)._2.S)
              draw.clip(i).w.poke(65536.S); draw.alpha(i).poke(255.U); draw.depth(i).poke((0x10+owner).S)
              draw.uv(i).u.poke(colors(owner).U); draw.uv(i).v.poke(vDepths(owner).U)
            }
            d.io.colorBase.poke((0x8000+owner*0x2000).U)
            d.io.depthBase.poke((0x9000+owner*0x2000).U)
          }
          d.io.mem.req.ready.poke((cycles % 13 < 9).B)
          if (pixels.nonEmpty && cycles % 17 >= 7) pixelVisible = true
          d.io.mem.resp.valid.poke((pixels.nonEmpty && pixelVisible).B)
          pixels.headOption.foreach { case (wr,a,v) =>
            d.io.mem.resp.bits.write.poke(wr.B); d.io.mem.resp.bits.addr.poke(a.U); d.io.mem.resp.bits.data.poke(v.U)
          }
          if (d.io.mem.resp.valid.peek().litToBoolean && d.io.mem.resp.ready.peek().litToBoolean) { pixels.dequeue(); pixelVisible = false }
          if (d.io.mem.req.valid.peek().litToBoolean && d.io.mem.req.ready.peek().litToBoolean) {
            val a=d.io.mem.req.bits.addr.peek().litValue.toLong
            val wr=d.io.mem.req.bits.write.peek().litToBoolean
            if(wr) write(a,d.io.mem.req.bits.data.peek().litValue.toLong)
            pixels.enqueue((wr,a,word(a)))
          }
          // Hold both data channels for long intervals; responses retain their
          // accepted IDs/data and never reread mutable memory on retirement.
          d.io.kernelMemReq.ready.poke((cycles % 11 < 8).B)
          if (lines.nonEmpty && cycles % 19 >= 9) lineVisible = true
          d.io.kernelMemResp.valid.poke((lines.nonEmpty && lineVisible).B)
          lines.headOption.foreach { case (id,v) =>
            d.io.kernelMemResp.bits.transactionId.poke(id.U); d.io.kernelMemResp.bits.readData.poke(v.U)
            d.io.kernelMemResp.bits.fault.poke(false.B)
          }
          if(d.io.kernelMemResp.valid.peek().litToBoolean && d.io.kernelMemResp.ready.peek().litToBoolean) { lines.dequeue(); lineVisible = false }
          if(d.io.kernelMemReq.valid.peek().litToBoolean && d.io.kernelMemReq.ready.peek().litToBoolean) {
            val req=d.io.kernelMemReq.bits; val a=req.address.peek().litValue.toLong
            val wr=req.isWrite.peek().litToBoolean
            if(wr) lineWrite(a,req.writeData.peek().litValue,req.byteMask.peek().litValue)
            lines.enqueue((req.transactionId.peek().litValue,if(wr) BigInt(0) else line(a)))
          }
          d.io.kernelWordMemReq.ready.poke((cycles % 7 < 5).B)
          if (words.nonEmpty && cycles % 23 >= 11) wordVisible = true
          d.io.kernelWordMemResp.valid.poke((words.nonEmpty && wordVisible).B)
          words.headOption.foreach { case (id,v) =>
            d.io.kernelWordMemResp.bits.transactionId.poke(id.U); d.io.kernelWordMemResp.bits.readData.poke(v.U)
            d.io.kernelWordMemResp.bits.fault.poke(false.B)
          }
          if(d.io.kernelWordMemResp.valid.peek().litToBoolean && d.io.kernelWordMemResp.ready.peek().litToBoolean) { words.dequeue(); wordVisible = false }
          if(d.io.kernelWordMemReq.valid.peek().litToBoolean && d.io.kernelWordMemReq.ready.peek().litToBoolean) {
            val req=d.io.kernelWordMemReq.bits; val a=req.address.peek().litValue.toLong
            val wr=req.isWrite.peek().litToBoolean
            if(wr) lineWrite(a,req.writeData.peek().litValue,req.byteMask.peek().litValue)
            words.enqueue((req.transactionId.peek().litValue,if(wr) BigInt(0) else line(a)))
          }
          if(d.io.draw.valid.peek().litToBoolean && d.io.draw.ready.peek().litToBoolean) {
            admitted+=1; admissions+=cycles
          }
          done=admitted==2 && d.io.done.peek().litToBoolean
          d.clock.step(); cycles+=1
        }
        assert(done && admitted==2,s"mode=$mode draws=$admitted cycles=$cycles")
        assert(admissions(1)-admissions(0)>146,"the pending draw must not replace a delayed UV owner")
      }
      for (owner <- 0 until 2; y <- 0 until 4; x <- 0 until 4;
           ((sx,sy),sample) <- Msaa.positions(mode).zipWithIndex) {
        val covered=4*x+sx>0 && 4*y+sy>0 && 4*(x+y)+sx+sy<=8
        val offset=((y*4+x)*samples+sample)*4
        val color=word(0x8000+owner*0x2000+offset)
        val depth=word(0x9000+owner*0x2000+offset)
        assert(color==(if(covered) colors(owner) else 0L),s"mode=$mode owner=$owner xy=$x,$y sample=$sample color=$color")
        val expected=if(!covered) 0x00ffffff else if(mode==0) vDepths(owner) else 0x10+owner
        assert(depth==expected,s"mode=$mode owner=$owner xy=$x,$y sample=$sample depth=$depth expected=$expected")
      }
      info(s"QUAD_UV_RENDER_OWNER mode=$mode draws=2 realCU=true unsignedHighUV=true pendingNextDraw=true disjointTargets=true disjointKernargs=true heldMemory=true exactTopLeftSamples=true")
    }
  }
}
