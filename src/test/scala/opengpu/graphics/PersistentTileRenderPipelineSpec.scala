package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class PersistentTileRenderPipelineSpec extends AnyFlatSpec {
  import TilePassTestData._
  behavior of "render-pass attachment lifetime"
  private val configuredCredits = sys.props.get("opengpu.tileStoreCredits").map(_.toInt).getOrElse(1)
  private val configuredBanked = sys.props.get("opengpu.bankedRenderer").contains("true")
  private val qualifyTransfers = sys.props.get("opengpu.tileTransferQualification").contains("1")
  require(!qualifyTransfers || !sys.env.get("CI").contains("true"),
    "tile-transfer performance qualification must run locally, outside CI")

  private case class Result(color: Seq[Long], depth: Seq[Long], reads: Int, writes: Int,
    cycles: Int, drawCycles: Int, peakOutstanding: Int)
  private case class Response(write: Boolean, address: Long, data: Long, due: Int)

  private def render(persistent: Boolean, mode: Int, load: Boolean, tileLog2: Int = 4,
    storeCredits: Int = configuredCredits, memoryLatency: Int = 0, memoryWindow: Int = 8,
    texture: Boolean=false): Result = {
    val p = Pass(mode = mode, width = if (tileLog2 == 3) 8 else 13,
      height = if (tileLog2 == 3) 8 else 9, tileLog2 = tileLog2,
      originX = if (tileLog2 == 3) 8 else 0, originY = if (tileLog2 == 3) 8 else 16,
      colorLoad = if (load) AttachmentLoadOp.load else AttachmentLoadOp.clear,
      depthLoad = if (load) AttachmentLoadOp.load else AttachmentLoadOp.clear)
    val cfg = GraphicsConfig(screenWidth = 32, screenHeight = 32,
      tileSize = if (persistent) 16 else 0, tileAttachments = persistent)
    var result = Result(Nil, Nil, 0, 0, 0, 0, 0)
    val banked=persistent && configuredBanked
    simulate(new RenderPipeline(cfg, persistentAttachments = persistent, tileStoreCredits = storeCredits,
      bankedAttachments=banked)) { dut =>
      val memory = mutable.Map.empty[Long, Long].withDefaultValue(0xdeadbeefL)
      for (y <- 0 until p.height; x <- 0 until p.width; s <- 0 until p.samples) {
        memory(p.address(x, y, s, depth = false)) = p.colorClear
        memory(p.address(x, y, s, depth = true)) = p.depthClear
      }
      val rng = new scala.util.Random(12345)
      val queue = mutable.ArrayBuffer.empty[Response]
      var response: Option[Response] = None
      val textureQueue=mutable.ArrayBuffer.empty[Response]
      var textureResponse=Option.empty[Response]
      var acceptedSamples=0L;var committedSamples=0L;var fullQuadCycles=0
      for(n <- 0 until 16)memory(0x80000L+n*4)=((0x204060ffL+n*0x07030100L)&0xffffffffL)
      var cycles = 0
      var reads = 0
      var writes = 0
      var readBytes = 0L
      var writeBytes = 0L
      var outstanding = 0
      var peakOutstanding = 0
      dut.io.draw.valid.poke(false.B)
      dut.io.colorFormat.poke(PixelFormat.native.U)
      dut.io.colorBase.poke(p.colorBase.U)
      dut.io.depthBase.poke(p.depthBase.U)
      dut.io.stride.poke(p.pitch.U)
      dut.io.screenW.poke(32.U)
      dut.io.screenH.poke(32.U)
      dut.io.sampleMode.poke(mode.U)
      dut.io.depthTestEnable.poke(false.B)
      dut.io.depthFunc.poke(0.U)
      dut.io.depthWriteEnable.poke(false.B)
      dut.io.blendCfgEnable.poke(false.B)
      dut.io.blendSrcFactor.poke(0.U)
      dut.io.blendDstFactor.poke(0.U)
      dut.io.blendEquation.poke(0.U)
      dut.io.stencilTestEnable.poke(false.B)
      dut.io.stencilFunc.poke(6.U)
      dut.io.stencilRef.poke(0.U)
      dut.io.stencilReadMask.poke(255.U)
      dut.io.stencilWriteMask.poke(255.U)
      dut.io.stencilFailOp.poke(0.U)
      dut.io.stencilZFailOp.poke(0.U)
      dut.io.stencilZPassOp.poke(0.U)
      dut.io.cullMode.poke(0.U)
      dut.io.texEnable.poke(texture.B)
      dut.io.texFormat.poke(0.U)
      dut.io.texBase.poke(0x80000.U)
      dut.io.texWidth.poke(4.U)
      dut.io.texHeight.poke(4.U)
      dut.io.texWrapClamp.poke(true.B)
      dut.io.texMaxLevel.poke(0.U)
      dut.io.texMem.req.ready.poke(true.B)
      dut.io.texMem.resp.valid.poke(false.B)
      dut.io.mem.req.ready.poke(false.B)
      dut.io.mem.resp.valid.poke(false.B)
      dut.io.tilePass.foreach { port =>
        pokeDescriptor(port.begin.bits, p)
        port.begin.valid.poke(false.B)
        port.end.valid.poke(false.B)
        port.end.bits.poke(true.B)
        port.clear.valid.poke(false.B)
        port.clear.bits.poke(0.U.asTypeOf(new TileAttachmentClear))
        port.clearDone.ready.poke(true.B)
        port.cancel.poke(false.B)
        port.memoryFault.poke(false.B)
        port.completion.ready.poke(false.B)
      }
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      def tick(): Unit = {
        dut.io.texMem.req.ready.poke((cycles%5!=1).B)
        if(textureResponse.isEmpty) {
          val i=textureQueue.lastIndexWhere(_.due<=cycles)
          if(i>=0)textureResponse=Some(textureQueue.remove(i))
        }
        dut.io.texMem.resp.valid.poke(textureResponse.nonEmpty.B)
        textureResponse.foreach { r=>
          dut.io.texMem.resp.bits.addr.poke(r.address.U)
          dut.io.texMem.resp.bits.write.poke(false.B)
          dut.io.texMem.resp.bits.data.poke(r.data.U)
        }
        val textureFire=textureResponse.nonEmpty && dut.io.texMem.resp.ready.peek().litToBoolean
        if(dut.io.texMem.req.valid.peek().litToBoolean && dut.io.texMem.req.ready.peek().litToBoolean) {
          val address=dut.io.texMem.req.bits.addr.peek().litValue.toLong
          dut.io.texMem.req.bits.write.expect(false.B)
          textureQueue+=Response(false,address,memory(address),cycles+3+(cycles%7))
        }
        if(banked) {
          acceptedSamples+=dut.io.tileAcceptedSamples.get.peek().litValue.toLong
          val n=dut.io.tileCommittedSamples.get.peek().litValue.toInt
          committedSamples+=n
          if(n==4)fullQuadCycles+=1
        }
        dut.io.mem.req.ready.poke((if (memoryLatency > 0) outstanding < memoryWindow
          else rng.nextInt(4) != 0).B)
        if (response.isEmpty) {
          val due = queue.indices.filter(i => queue(i).due <= cycles)
          if (due.nonEmpty) response = Some(queue.remove(due(rng.nextInt(due.size))))
        }
        dut.io.mem.resp.valid.poke(response.nonEmpty.B)
        response.foreach { r =>
          dut.io.mem.resp.bits.write.poke(r.write.B)
          dut.io.mem.resp.bits.addr.poke(r.address.U)
          dut.io.mem.resp.bits.data.poke(r.data.U)
        }
        val responseFire = response.nonEmpty && dut.io.mem.resp.ready.peek().litToBoolean
        if (dut.io.mem.req.valid.peek().litToBoolean && dut.io.mem.req.ready.peek().litToBoolean) {
          val address = dut.io.mem.req.bits.addr.peek().litValue.toLong
          val write = dut.io.mem.req.bits.write.peek().litToBoolean
          if (write) {
            memory(address) = dut.io.mem.req.bits.data.peek().litValue.toLong
            writes += 1
          } else reads += 1
          outstanding += 1
          queue += Response(write, address, memory(address), cycles +
            (if (memoryLatency > 0) memoryLatency else 1 + rng.nextInt(12)))
        }
        dut.io.tileEvents.foreach { events =>
          readBytes += events.loadBytes.peek().litValue.toLong
          writeBytes += events.storeBytes.peek().litValue.toLong
        }
        dut.clock.step()
        cycles += 1
        if (responseFire) { response = None; outstanding -= 1 }
        if(textureFire)textureResponse=None
        peakOutstanding = math.max(peakOutstanding, outstanding)
      }
      def waitFor(condition: => Boolean): Unit = {
        val start = cycles
        while (!condition && cycles - start < 100000) tick()
        assert(condition, s"render timeout: persistent=$persistent mode=$mode load=$load reads=$reads writes=$writes")
      }

      dut.io.tilePass.foreach { port =>
        port.begin.valid.poke(true.B)
        port.begin.ready.expect(true.B)
        tick()
        port.begin.valid.poke(false.B)
        // Every attachment and viewport field must come from the admitted
        // snapshot even when later host state is inconsistent with it.
        pokeDescriptor(port.begin.bits, p.copy(colorBase = 0x30000, id = 99))
        dut.io.colorBase.poke(0x30000.U)
        dut.io.depthBase.poke(0x40000.U)
        dut.io.stride.poke(64.U)
        dut.io.sampleMode.poke(0.U)
        dut.io.colorFormat.poke(PixelFormat.bgra.U)
        dut.io.screenW.poke(16.U)
        dut.io.screenH.poke(16.U)
      }

      val draw = dut.io.draw.bits.asInstanceOf[SceneTriangle]
      for (drawIndex <- 0 until 4) {
        draw.poke(0.U.asTypeOf(new SceneTriangle(cfg)))
        draw.stateOverride.poke(true.B)
        draw.texEnable.poke(texture.B)
        draw.depthTestEnable.poke(true.B)
        draw.depthFunc.poke(0.U)
        draw.depthWriteEnable.poke(true.B)
        draw.blendCfgEnable.poke(true.B)
        draw.blendSrcFactor.poke(4.U)
        draw.blendDstFactor.poke(5.U)
        draw.stencilTestEnable.poke(true.B)
        draw.stencilFunc.poke(6.U)
        draw.stencilReadMask.poke(255.U)
        draw.stencilWriteMask.poke(15.U)
        draw.stencilZFailOp.poke(5.U)
        draw.stencilZPassOp.poke(3.U)
        // Baseline intersects the same tile rectangle explicitly; the pass
        // path must derive that intersection from its descriptor.
        draw.scissorEnable.poke((!persistent || drawIndex == 3).B)
        draw.scissorMinX.poke((p.originX + (if (drawIndex == 3) 2 else 0)).U)
        draw.scissorMinY.poke((p.originY + (if (drawIndex == 3) 1 else 0)).U)
        draw.scissorMaxX.poke((p.originX + p.width - (if (drawIndex == 3) 3 else 0)).U)
        draw.scissorMaxY.poke((p.originY + p.height - (if (drawIndex == 3) 2 else 0)).U)
        val vertices = Seq((-1, -1), (1, -1), (-1, 1))
        for (i <- 0 until 3) {
          draw.clip(i).x.poke((vertices(i)._1 * 65536).S)
          draw.clip(i).y.poke((vertices(i)._2 * 65536).S)
          draw.clip(i).w.poke(65536.S)
          draw.color(i).r.poke((if (drawIndex % 2 == 0) 220 else 30).U)
          draw.color(i).g.poke((if (drawIndex % 2 == 0) 40 else 210).U)
          draw.color(i).b.poke((40 + drawIndex * 30).U)
          draw.alpha(i).poke((80 + drawIndex * 35).U)
          draw.depth(i).poke((Seq(0x700000, 0x300000, 0x500000, 0x100000)(drawIndex)+i*0x1000).S)
          draw.uv(i).u.poke((if(i==1)65536 else 0).U)
          draw.uv(i).v.poke((if(i==2)65536 else 0).U)
        }
        waitFor(dut.io.draw.ready.peek().litToBoolean)
        dut.io.draw.valid.poke(true.B)
        tick()
        dut.io.draw.valid.poke(false.B)
        // Queue the clear while the preceding draw is still running. Its
        // valid signal must stop new draws without blocking older OM work.
        if (drawIndex != 1 || !persistent) waitFor(dut.io.done.peek().litToBoolean)
        if (drawIndex == 1) {
          if (persistent) {
            val port = dut.io.tilePass.get
            val c = port.clear.bits
            c.minX.poke(2.U); c.minY.poke(2.U); c.maxX.poke(7.U); c.maxY.poke(6.U)
            c.colorMask.poke(0x00ff00ffL.U); c.depthMask.poke(0xff000000L.U)
            c.colorValue.poke(0xaabbccddL.U); c.depthValue.poke(0x03000000L.U)
            port.clearDone.ready.poke(false.B)
            port.clear.valid.poke(true.B)
            waitFor(port.clear.ready.peek().litToBoolean)
            tick()
            port.clear.valid.poke(false.B)
            waitFor(port.clearDone.valid.peek().litToBoolean)
            port.clearDone.bits.expect(true.B)
            port.clearDone.ready.poke(true.B)
            tick()
          } else {
            // Independent reference update after prior draws have retired.
            for (y <- 2 until 6; x <- 2 until 7; s <- 0 until p.samples) {
              val color = p.address(x, y, s, depth = false)
              val depth = p.address(x, y, s, depth = true)
              memory(color) = (memory(color) & 0xff00ff00L) | 0x00bb00ddL
              memory(depth) = (memory(depth) & 0x00ffffffL) | 0x03000000L
            }
          }
        }
        waitFor(dut.io.done.peek().litToBoolean)
        if (persistent) {
          assert(writes == 0, "a draw boundary prematurely stored tile attachments")
          if (!load) assert(reads == 0, "clear-start pass read old attachment contents")
        }
      }

      val drawCycles = cycles
      dut.io.tilePass.foreach { port =>
        waitFor(port.end.ready.peek().litToBoolean)
        port.end.valid.poke(true.B)
        tick()
        port.end.valid.poke(false.B)
        waitFor(port.completion.valid.peek().litToBoolean)
        port.completion.bits.status.expect(TilePassStatus.success.U)
        port.completion.bits.identity.pass.expect(p.id.U)
        assert(readBytes == reads * 4L && writeBytes == writes * 4L)
        for (_ <- 0 until 4) { tick(); dut.io.draw.ready.expect(false.B) }
        port.completion.ready.poke(true.B)
        tick()
      }
      assert(queue.isEmpty && response.isEmpty)
      assert(textureQueue.isEmpty && textureResponse.isEmpty)
      if(banked) {
        assert(acceptedSamples>20 && committedSamples>0 && committedSamples<=acceptedSamples)
        if(!texture)assert(fullQuadCycles>0,"renderer never performed a four-sample SRAM commit")
        info(s"BANKED_RENDER mode=$mode load=$load texture=$texture accepted=$acceptedSamples committed=$committedSamples fullQuadCycles=$fullQuadCycles drawCycles=$drawCycles")
      }
      val color = for (y <- 0 until p.height; x <- 0 until p.width; s <- 0 until p.samples)
        yield memory(p.address(x, y, s, depth = false))
      val depth = for (y <- 0 until p.height; x <- 0 until p.width; s <- 0 until p.samples)
        yield memory(p.address(x, y, s, depth = true))
      assert(color.count(_ != p.colorClear) > 20)
      assert(depth.exists(v => (v >>> 24) != (p.depthClear >>> 24)), "stencil was not exercised")
      assert(memory(p.address(p.width, 0, 0, depth = false)) == 0xdeadbeefL)
      result = Result(color, depth, reads, writes, cycles, drawCycles, peakOutstanding)
    }
    result
  }

  for (mode <- (if(configuredBanked)Seq(0,1,2)else Seq(0,2)); load <- Seq(false, true)) {
    it should s"match ordered blending and depth/stencil across four draws at ${1 << mode}x with load=$load" in {
      val reference = render(persistent = false, mode, load)
      val candidate = render(persistent = true, mode, load)
      assert(candidate.color == reference.color)
      assert(candidate.depth == reference.depth)
      assert(candidate.reads < reference.reads && candidate.writes < reference.writes)
      if(configuredBanked && !load)assert(candidate.drawCycles<reference.drawCycles)
      info(s"mode=$mode load=$load reference reads/writes/cycles=${reference.reads}/${reference.writes}/${reference.cycles}; " +
        s"persistent=${candidate.reads}/${candidate.writes}/${candidate.cycles}; credits=$configuredCredits; " +
        s"referenceDrawCycles=${reference.drawCycles} candidateDrawCycles=${candidate.drawCycles}")
    }
  }
  if(configuredBanked)for(mode <- Seq(0,2)) {
    it should s"match textured sparse quads with per-sample depths at ${1<<mode}x" in {
      val reference=render(persistent=false,mode,load=false,tileLog2=3,texture=true)
      val candidate=render(persistent=true,mode,load=false,tileLog2=3,texture=true)
      assert(candidate.color==reference.color && candidate.depth==reference.depth)
    }
  }
  for (mode <- Seq(0, 2)) {
    it should s"match an 8x8 tile at an unaligned-to-16 origin at ${1 << mode}x" in {
      val reference = render(persistent = false, mode, load = false, tileLog2 = 3)
      val candidate = render(persistent = true, mode, load = false, tileLog2 = 3)
      assert(candidate.color == reference.color && candidate.depth == reference.depth)
      assert(candidate.reads == 0)
    }
  }
  // Credit sweeps rebuild the complete renderer; run them explicitly outside CI.
  if (qualifyTransfers) {
    it should "reduce pass-store latency with concurrent credits at fixed memory latency and bandwidth" in {
      val serial = render(persistent = true, mode = 2, load = false, storeCredits = 1, memoryLatency = 32)
      val concurrent = render(persistent = true, mode = 2, load = false, storeCredits = 8, memoryLatency = 32)
      assert(concurrent.color == serial.color && concurrent.depth == serial.depth)
      assert(concurrent.reads == serial.reads && concurrent.writes == serial.writes)
      assert(serial.peakOutstanding == 1 && concurrent.peakOutstanding > 1 && concurrent.peakOutstanding <= 8)
      assert(serial.drawCycles == concurrent.drawCycles,
        "clear-start transfer comparison changed the pre-store draw work")
      assert(concurrent.cycles < serial.cycles)
      info(s"fixed32 word latency, external window=8; serial cycles=${serial.cycles}, " +
        s"concurrent=${concurrent.cycles}, words=${concurrent.reads + concurrent.writes}, " +
        s"peak=${concurrent.peakOutstanding}, draw cycles=${concurrent.drawCycles}")
    }
    it should "size transfer credits against a fixed external window rather than unlimited memory" in {
      val results = Seq(2, 4, 8, 16, 32).map { credits =>
        credits -> render(persistent = true, mode = 2, load = false,
          storeCredits = credits, memoryLatency = 32, memoryWindow = 8)
      }
      val reference = results.head._2
      for ((credits, result) <- results) {
        assert(result.color == reference.color && result.depth == reference.depth)
        assert(result.reads == reference.reads && result.writes == reference.writes)
        assert(result.drawCycles == reference.drawCycles && result.peakOutstanding <= 8)
        info(s"fixed32 word latency, external window=8; credits=$credits cycles=${result.cycles}, " +
          s"draw cycles=${result.drawCycles}, peak=${result.peakOutstanding}")
      }
      assert(results.toMap.apply(8).cycles < results.toMap.apply(2).cycles)
    }
  }
}
