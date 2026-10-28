package opengpu.graphics

import chisel3._
import chisel3.util._

class TileBinRenderPassDescriptor extends Bundle {
  val version = UInt(8.W)
  val replay = new ResolvedTileBinDescriptor
  /** Shared image views; tile origin is the grid origin. Per-tile extents
    * are generated from extentW/H, including partial right/bottom tiles.
    */
  val attachments = new TileRenderPassDescriptor
  val screenW = UInt(16.W)
  val screenH = UInt(16.W)
  val extentW = UInt(16.W)
  val extentH = UInt(16.W)
  /** Programmable mode partitions this arena into disjoint engine windows.
    * State record kernarg addresses are byte offsets within each window.
    */
  val shaderBase = UInt(32.W)
  val shaderLimit = UInt(33.W)
  val shaderEngineStride = UInt(32.W)
}

class TileBinRenderPassAdmissionResult(tileSlots: Int,residentDescriptors: Boolean=true) extends Bundle {
  val status = UInt(4.W)
  val tiles = Vec(if (residentDescriptors) tileSlots else 0,new TileRenderPassDescriptor)
}

/** Validate the whole disjoint tile grid before bin writes or attachment
  * traffic. Reuse the same full-precision admission check as the SRAM owner.
  * Bin/record arenas must also be disjoint from each attachment allocation.
  */
class TileBinRenderPassAdmission(config: GraphicsConfig,tileSlots: Int,
  programmable: Boolean = false,engines: Int = 2,pagedCapacity: Int = 0,
  pageBytes: Int = 4096) extends Module {
  require(pageBytes>=64 && pageBytes<=65536 && (pageBytes & (pageBytes-1))==0)
  require(tileSlots >= 1 && tileSlots <= 64)
  private val maximumTiles=if (pagedCapacity>0) pagedCapacity else tileSlots
  require(maximumTiles>=tileSlots && maximumTiles<=65536)
  private val slotBits = log2Ceil(tileSlots).max(1)
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new TileBinRenderPassDescriptor))
    val out = Decoupled(new TileBinRenderPassAdmissionResult(tileSlots,pagedCapacity==0))
    val cancel = Input(Bool())
  })
  private val Seq(idle,dimensions,shape,offer,waitTile,done,poolSegments) = Enum(7)
  private val phase = RegInit(idle)
  private val snapshot = Reg(new TileBinRenderPassDescriptor)
  private val segmentIndex=Reg(UInt(3.W))
  private val previousLimit=Reg(UInt(33.W))
  private val result = Reg(new TileBinRenderPassAdmissionResult(tileSlots,pagedCapacity==0))
  private val cancelled = RegInit(false.B)
  private val columns = Reg(UInt(17.W)); private val rows = Reg(UInt(17.W))
  private val endX = Reg(UInt(17.W)); private val endY = Reg(UInt(17.W))
  private val x = Reg(UInt(17.W)); private val y = Reg(UInt(17.W))
  private val column = Reg(UInt(17.W))
  private val index = Reg(UInt(log2Ceil(maximumTiles+1).max(1).W))
  private val tileSize = Mux(snapshot.attachments.tileLog2 === 3.U,8.U,16.U)
  private val tile = WireDefault(snapshot.attachments)
  tile.originX := x; tile.originY := y
  tile.tileWidth := Mux(endX-x < tileSize,endX-x,tileSize)
  tile.tileHeight := Mux(endY-y < tileSize,endY-y,tileSize)
  private val checker = Module(new TileRenderPassAdmission(config.maxSampleCount,
    config.screenWidth,config.screenHeight))
  private val stop = cancelled || io.cancel
  io.in.ready := phase === idle && !io.cancel
  io.out.valid := phase === done
  io.out.bits := result
  checker.io.in.valid := phase === offer && !stop
  checker.io.in.bits := tile
  checker.io.out.ready := phase === waitTile
  when(io.in.fire) {
    snapshot := io.in.bits
    cancelled := false.B
    result := 0.U.asTypeOf(result)
    phase := dimensions
  }
  when(io.cancel && phase =/= idle && phase =/= done) { cancelled := true.B }
  private def finish(status: UInt): Unit = { result.status := status; phase := done }
  when(phase === dimensions) {
    val w = snapshot.extentW +& (tileSize-1.U)
    val h = snapshot.extentH +& (tileSize-1.U)
    columns := Mux(snapshot.attachments.tileLog2 === 3.U,w >> 3,w >> 4)
    rows := Mux(snapshot.attachments.tileLog2 === 3.U,h >> 3,h >> 4)
    endX := snapshot.attachments.originX +& snapshot.extentW
    endY := snapshot.attachments.originY +& snapshot.extentH
    phase := shape
  }
  when(phase === shape) {
    val b = snapshot.replay.bins.pool
    val r = snapshot.replay.records
    val a = snapshot.attachments
    def disjoint(base: UInt,limit: UInt,otherBase: UInt,otherLimit: UInt): Bool =
      base.pad(33) === limit || otherBase.pad(33) === otherLimit ||
        limit <= otherBase.pad(33) || otherLimit <= base.pad(33)
    val views = Seq((a.color.base,a.color.limit),(a.depth.base,a.depth.limit)) ++
      Seq((a.resolveBase,a.resolveLimit))
    val viewSeparation=disjoint(a.color.base,a.color.limit,a.depth.base,a.depth.limit) &&
      (!a.resolve || (disjoint(a.resolveBase,a.resolveLimit,a.color.base,a.color.limit) &&
        disjoint(a.resolveBase,a.resolveLimit,a.depth.base,a.depth.limit)))
    val separated = views.zipWithIndex.map { case ((base,limit),i) =>
      (if (i == 2) !a.resolve else false.B) ||
        (disjoint(b.base,b.limit,base,limit) && disjoint(r.base,r.limit,base,limit))
    }.reduce(_ && _)
    val d=snapshot.replay.bins
    val directoryValid=if (pagedCapacity==0) true.B else {
      val ranges=Seq((b.base,b.limit),(r.base,r.limit)) ++ views
      d.directoryBase(5,0)===0.U && d.directoryLimit<=(BigInt(1)<<32).U &&
        d.directoryBase.pad(33)+(d.tileCount.pad(33)<<6)<=d.directoryLimit &&
        ranges.zipWithIndex.map { case ((base,limit),i)=>
          (if (i==4) !a.resolve else false.B) || disjoint(d.directoryBase,d.directoryLimit,base,limit)
        }.reduce(_ && _)
    }
    val count = columns * rows
    val arenasValid = snapshot.replay.version === 1.U && (snapshot.replay.bins.version === (if (pagedCapacity>0) 2.U else 1.U) || snapshot.replay.bins.version===3.U) &&
      (!snapshot.replay.bins.resume || snapshot.replay.bins.version===3.U) &&
      BinPagePoolRules.headerLegal(b,pageBytes) && r.version === 1.U && r.identity.asUInt === b.identity.asUInt &&
      b.base.pad(33) <= b.limit && b.limit <= (BigInt(1)<<32).U &&
      r.base.pad(33) <= r.limit && r.limit <= (BigInt(1)<<32).U
    val shaderEnd = snapshot.shaderBase.pad(35) + snapshot.shaderEngineStride.pad(35)*engines.U
    val shaderValid = if (!programmable) true.B else {
      val ranges = Seq((b.base,b.limit),(r.base,r.limit)) ++ views ++
        (if (pagedCapacity>0) Seq((d.directoryBase,d.directoryLimit)) else Seq.empty)
      snapshot.shaderBase(5,0) === 0.U && snapshot.shaderEngineStride(5,0) === 0.U &&
        snapshot.shaderEngineStride > 0.U && snapshot.shaderLimit <= (BigInt(1)<<32).U &&
        shaderEnd <= snapshot.shaderLimit && ranges.zipWithIndex.map { case ((base,limit),i) =>
          (if (i == 4) !a.resolve else false.B) ||
            disjoint(snapshot.shaderBase,shaderEnd,base,limit)
        }.reduce(_ && _)
    }
    val legal = snapshot.version === 1.U && arenasValid && directoryValid && shaderValid && a.identity.asUInt === b.identity.asUInt &&
      snapshot.screenW > 0.U && snapshot.screenH > 0.U &&
      snapshot.screenW <= config.screenWidth.U && snapshot.screenH <= config.screenHeight.U &&
      snapshot.extentW > 0.U && snapshot.extentH > 0.U &&
      endX <= snapshot.screenW && endY <= snapshot.screenH &&
      count > 0.U && count <= maximumTiles.U && count === snapshot.replay.bins.tileCount && separated && viewSeparation
    when(stop) { finish(PrimitiveBinStatus.cancelled.U) }
      .elsewhen(!legal) { finish(PrimitiveBinStatus.invalidDescriptor.U) }
      .otherwise {
        index := 0.U; column := 0.U
        x := a.originX; y := a.originY
        segmentIndex:=0.U; previousLimit:=b.initialLimit
        phase := Mux(b.version===2.U,poolSegments,offer)
      }
  }
  when(phase === poolSegments) {
    val b=snapshot.replay.bins.pool
    val valid=BinPagePoolRules.segmentLegal(b,segmentIndex,previousLimit,pageBytes)
    val last=Mux(segmentIndex<b.spillCount,b.spills(segmentIndex).limit,previousLimit)
    when(stop) { finish(PrimitiveBinStatus.cancelled.U) }
      .elsewhen(!valid || (segmentIndex===(BinPagePoolRules.maxSpills-1).U && last=/=b.limit)) {
        finish(PrimitiveBinStatus.invalidDescriptor.U)
      }.elsewhen(segmentIndex===(BinPagePoolRules.maxSpills-1).U) { phase:=offer }
      .otherwise { segmentIndex:=segmentIndex+1.U; previousLimit:=last }
  }
  when(phase === offer) {
    when(stop) { finish(PrimitiveBinStatus.cancelled.U) }
      .elsewhen(checker.io.in.fire) {
        if (pagedCapacity==0) {
          if (tileSlots == 1) result.tiles(0) := tile
          else result.tiles(index(slotBits-1,0)) := tile
        }
        phase := waitTile
      }
  }
  when(checker.io.out.fire) {
    when(stop) { finish(PrimitiveBinStatus.cancelled.U) }
      .elsewhen(!checker.io.out.bits.valid) { finish(PrimitiveBinStatus.invalidDescriptor.U) }
      .elsewhen(index+1.U === snapshot.replay.bins.tileCount) { finish(0.U) }
      .otherwise {
        index := index+1.U
        when(column+1.U === columns) {
          column := 0.U; x := snapshot.attachments.originX; y := y+tileSize
        }.otherwise { column := column+1.U; x := x+tileSize }
        phase := offer
      }
  }
  when(io.out.fire) { phase := idle; cancelled := false.B }
}
