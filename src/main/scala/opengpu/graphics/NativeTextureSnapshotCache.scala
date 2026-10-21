package opengpu.graphics

import chisel3._
import chisel3.util._

class NativeTextureSnapshotKey extends Bundle {
  val resources = new ShaderTextureResources
  val identity = new GraphicsWorkIdentity
  val unit = UInt(32.W)
  val lod = UInt(32.W)
}

class NativeTextureSnapshot extends Bundle {
  val key = new NativeTextureSnapshotKey
  val template = new NativeRgba8TextureLaneTemplate
}

/** Task-scoped checked nonmip snapshots. GXR1/GXB1/image/sampler metadata is
  * immutable until publication invalidation or the next trusted task launch.
  * Coordinates, offsets, component and warp are deliberately not cached.
  */
class NativeTextureSnapshotCache(entries: Int = 4) extends Module {
  require(entries > 0 && isPow2(entries))
  private val indexWidth = math.max(1, log2Ceil(entries))
  val io = IO(new Bundle {
    val lookup = Input(new NativeTextureSnapshotKey)
    val hit = Output(Bool())
    val template = Output(new NativeRgba8TextureLaneTemplate)
    val write = Flipped(Valid(new NativeTextureSnapshot))
    val clear = Input(Bool())
  })
  private val valid = RegInit(VecInit(Seq.fill(entries)(false.B)))
  private val snapshots = Reg(Vec(entries, new NativeTextureSnapshot))
  private val replacement = RegInit(0.U(indexWidth.W))
  private val hits = VecInit((0 until entries).map(i => valid(i) && snapshots(i).key.asUInt === io.lookup.asUInt))
  private val writeHits = VecInit((0 until entries).map(i => valid(i) && snapshots(i).key.asUInt === io.write.bits.key.asUInt))
  io.hit := hits.asUInt.orR && !io.clear
  io.template := Mux1H(hits, snapshots.map(_.template))
  assert(PopCount(hits) <= 1.U && PopCount(writeHits) <= 1.U, "checked snapshot key has duplicate live entries")
  private val free = PriorityEncoder(VecInit(valid.map(x => !x)).asUInt)
  private val selected = if (entries == 1) 0.U else
    Mux(writeHits.asUInt.orR, PriorityEncoder(writeHits.asUInt), Mux(valid.asUInt.andR, replacement, free))
  when(io.write.valid && !io.clear) {
    snapshots(selected) := io.write.bits
    valid(selected) := true.B
    when(!writeHits.asUInt.orR && valid.asUInt.andR) {
      replacement := (if (entries == 1) 0.U else replacement + 1.U)
    }
  }
  when(io.clear) { valid.foreach(_ := false.B); replacement := 0.U }
}
