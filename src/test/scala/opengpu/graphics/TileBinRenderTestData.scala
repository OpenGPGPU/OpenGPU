package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._

private[graphics] object TileBinRenderTestData {
  val binBase = 0x10000L
  val recordBase = 0x20000L
  val firstHandle = 0x80000000L
  val colorBase = 0x40000L
  val depthBase = 0x50000L
  val resolveBase = 0x60000L
  val colorClear = 0x10203040L
  val depthClear = 0x5affffffL
  val flags = 1L | 2 | 128 | (1L<<16) | (1L<<17)
  val blend = 1L | (4L<<4) | (5L<<8)
  val stencilOps = 6L | (5L<<6) | (3L<<9)
  val stencilValues = 0x0fff03L
  case class Draw(color: Long,depth: Long,uv: Vector[(Long,Long)] = Vector.fill(3)((0L,0L)),
    clip: Vector[(Long,Long,Long,Long)] = Vector((-65536L,-65536L,0L,65536L),
      (65536L,-65536L,0L,65536L),(-65536L,65536L,0L,65536L)),
    scissor: Option[(Int,Int,Int,Int)] = None)
  val draws = Vector(Draw(0xdc282850L,0x700000),Draw(0x1ed23c73L,0x300000),
    Draw(0x6464d296L,0x500000))
  def primitive(d: Draw): Vector[Long] = {
    val p = Array.fill[Long](32)(0)
    p(0)=TileBinDrawLayout.primitiveMagic.toLong
    d.scissor.foreach { case (x0,y0,x1,y1) =>
      p(1)=1; p(2)=x0.toLong|(y0.toLong<<16); p(3)=x1.toLong|(y1.toLong<<16)
    }
    for (v <- 0 until 3) {
      val c=d.clip(v)
      Vector(c._1,c._2,c._3,c._4).zipWithIndex.foreach { case (value,k) => p(4+4*v+k)=value&0xffffffffL }
      p(16+v)=d.color; p(19+v)=d.depth&0xffffffffL
      p(22+2*v)=d.uv(v)._1; p(23+2*v)=d.uv(v)._2
    }
    p.toVector
  }
  def state: Vector[Long] = {
    val s = Array.fill[Long](16)(0)
    s(0)=TileBinDrawLayout.stateMagic.toLong; s(1)=flags
    s(3)=blend; s(4)=stencilOps; s(5)=stencilValues
    s(10)=1|(1L<<16)
    s.toVector
  }
  def pokeResolved(b: ResolvedPrimitiveBinReference,p: Seq[Long],s: Seq[Long],tile: Int = 0): Unit = {
    b.source.identity.context.poke(3.U); b.source.identity.pass.poke(7.U)
    b.source.identity.generation.poke(11.U); b.source.tile.poke(tile.U)
    b.source.ordinal.poke(5.U); b.source.reference.primitive.poke(firstHandle.U)
    b.source.reference.state.poke((firstHandle+32).U); b.source.reference.sequence.poke(19.U)
    b.source.reference.token.poke(29.U)
    p.zipWithIndex.foreach { case (w,i) => b.primitive(i).poke(w.U) }
    s.zipWithIndex.foreach { case (w,i) => b.state(i).poke(w.U) }
  }
  def pokeDraw(b: SceneTriangle,d: Draw,extentW: Int,extentH: Int): Unit = {
    b.poke(0.U.asTypeOf(b))
    for (v <- 0 until 3) {
      val c=d.clip(v)
      b.clip(v).x.poke(c._1.S); b.clip(v).y.poke(c._2.S)
      b.clip(v).z.poke(c._3.S); b.clip(v).w.poke(c._4.S)
      b.color(v).r.poke(((d.color>>24)&255).U); b.color(v).g.poke(((d.color>>16)&255).U)
      b.color(v).b.poke(((d.color>>8)&255).U); b.alpha(v).poke((d.color&255).U)
      b.depth(v).poke(d.depth.S)
      b.uv(v).u.poke(d.uv(v)._1.U); b.uv(v).v.poke(d.uv(v)._2.U)
    }
    b.stateOverride.poke(true.B); b.depthTestEnable.poke(true.B); b.depthFunc.poke(0.U)
    b.depthWriteEnable.poke(true.B); b.blendEnable.poke(true.B); b.blendCfgEnable.poke(true.B)
    b.blendSrcFactor.poke(4.U); b.blendDstFactor.poke(5.U); b.blendEquation.poke(0.U)
    b.stencilTestEnable.poke(true.B); b.stencilFunc.poke(6.U)
    b.stencilRef.poke(3.U); b.stencilReadMask.poke(255.U); b.stencilWriteMask.poke(15.U)
    b.stencilZFailOp.poke(5.U); b.stencilZPassOp.poke(3.U)
    val (x0,y0,x1,y1)=d.scissor.getOrElse((0,0,extentW,extentH))
    b.scissorEnable.poke(true.B); b.scissorMinX.poke(x0.U); b.scissorMinY.poke(y0.U)
    b.scissorMaxX.poke(x1.U); b.scissorMaxY.poke(y1.U)
  }
  def pokeBinding(b: TileBinRenderPassDescriptor,screenW: Int = 16,screenH: Int = 8,
    extentW: Int = 16,extentH: Int = 8,mode: Int = 0,resolve: Boolean = false,
    originX: Int = 0,originY: Int = 0): Unit = {
    b.poke(0.U.asTypeOf(b)); b.version.poke(1.U)
    b.screenW.poke(screenW.U); b.screenH.poke(screenH.U)
    b.extentW.poke(extentW.U); b.extentH.poke(extentH.U)
    val p=b.replay
    p.version.poke(1.U); p.firstHandle.poke(firstHandle.U)
    p.bins.version.poke(1.U); p.bins.pool.version.poke(1.U)
    p.bins.pool.identity.context.poke(3.U); p.bins.pool.identity.pass.poke(7.U)
    p.bins.pool.identity.generation.poke(11.U)
    p.bins.pool.base.poke(binBase.U); p.bins.pool.limit.poke((binBase+4096).U)
    p.bins.pool.pageSizeLog2.poke(7.U)
    p.bins.tileCount.poke((((extentW+7)/8)*((extentH+7)/8)).U)
    p.records.version.poke(1.U); p.records.identity.context.poke(3.U)
    p.records.identity.pass.poke(7.U); p.records.identity.generation.poke(11.U)
    p.records.base.poke(recordBase.U); p.records.limit.poke((recordBase+4096).U)
    val pitch=screenW*(1<<mode)*4
    TilePassTestData.pokeDescriptor(b.attachments,TilePassTestData.Pass(mode=mode,
      width=8,height=8,originX=originX,originY=originY,pitch=pitch,colorBase=colorBase,
      depthBase=depthBase,colorClear=colorClear,depthClear=depthClear,tileLog2=3,
      resolve=resolve,resolveBase=resolveBase,resolvePitch=screenW*4))
  }
}
