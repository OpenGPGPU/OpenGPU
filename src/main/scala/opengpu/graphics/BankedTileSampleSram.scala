package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.core.memory.L2SramArray

class BankedTileSampleRead extends Bundle {
  val index=UInt(10.W)
  val ticket=UInt(8.W)
}
class BankedTileSampleReply extends BankedTileSampleRead {
  val color=UInt(32.W)
  val depthStencil=UInt(32.W)
}
class BankedTileSampleWrite extends Bundle {
  val index=UInt(10.W)
  val color=UInt(32.W)
  val depthStencil=UInt(32.W)
  val colorEnable=Bool()
  val depthStencilEnable=Bool()
}
private class BankedTileSampleOperation extends Bundle {
  val row=UInt(7.W)
  val write=Bool()
  val color=UInt(32.W)
  val depthStencil=UInt(32.W)
  val colorEnable=Bool()
  val depthStencilEnable=Bool()
}
class BankedTileSampleLegacyPort extends Bundle {
  val readEnable=Input(Bool());val readIndex=Input(UInt(10.W))
  val colorData=Output(UInt(32.W));val depthStencilData=Output(UInt(32.W))
  val writeEnable=Input(Bool());val writeIndex=Input(UInt(10.W))
  val colorEnable=Input(Bool());val depthStencilEnable=Input(Bool())
  val color=Input(UInt(32.W));val depthStencil=Input(UInt(32.W))
}

/** Draft H3 local storage service, not a complete pixel backend.
  * Eight single-port bank pairs hold one RGBA8+D24S8 tile at up to4x.
  * Each pair arbitrates one read or write/cycle; the two planes execute that
  * operation together, with independent full-word write enables. Four reads
  * and four writes may issue together when their bank sets are disjoint.
  *
  * The enclosing pixel backend owns sample-order hazards, valid/dirty bits,
  * clear substitution, load misses, blend/depth/stencil and pass cancellation.
  * It must reserve a sample through its final write fire. This service does
  * not allow conflicting RMW operations merely because ports are available.
  * Write fire commits at that edge; each read produces exactly one ordered
  * tagged reply on its originating port. Begin requires producer quiescence
  * and complete reply drain before the admitted sample mode changes.
  *
  * Each response path has one SRAM-result stage and two registered entries.
  * A stalled SRAM result is captured once, since another port can reuse its
  * bank and an inactive SyncReadMem output need not retain data. Queue ready
  * depends on registered occupancy, avoiding a response-ready/bank-ready loop.
  */
class BankedTileSampleSram(useBlackBoxes: Boolean=false,writePorts: Int=4,
  legacyPort: Boolean=false) extends Module {
  require(writePorts>=4 && writePorts<=8)
  val io=IO(new Bundle {
    val begin=Input(Bool())
    val sampleMode=Input(UInt(2.W))
    val read=Vec(4,Flipped(Decoupled(new BankedTileSampleRead)))
    val response=Vec(4,Decoupled(new BankedTileSampleReply))
    val write=Vec(writePorts,Flipped(Decoupled(new BankedTileSampleWrite)))
    /** Exclusive one-cycle attachment scanner/word compatibility access.
      * The caller must drain quad producers first. Refill uses a tagged write
      * port instead, since quad reservations can remain live during a miss.
      */
    val legacy=if(legacyPort) Some(new BankedTileSampleLegacyPort) else None
    val drained=Output(Bool())
    /** Raw operations; these are not complete pixel-update counters. */
    val readIssues=Output(UInt(3.W))
    val writeIssues=Output(UInt(log2Ceil(writePorts+1).W))
  })
  private val bound=RegInit(false.B)
  private val mode=RegInit(0.U(2.W))
  private val colors=Seq.fill(8)(Module(new L2SramArray(128,32,useBlackBoxes)))
  private val depths=Seq.fill(8)(Module(new L2SramArray(128,32,useBlackBoxes)))
  private val arbiters=Seq.fill(8)(Module(new RRArbiter(new BankedTileSampleOperation,4+writePorts)))
  private val replies=Seq.fill(4)(Module(new Queue(new BankedTileSampleReply,2)))

  private def location(index: UInt): (UInt,UInt) = {
    val sample=Mux(mode===2.U,index(1,0),Mux(mode===1.U,Cat(0.U(1.W),index(0)),0.U(2.W)))
    val pixel=Mux(mode===2.U,index>>2,Mux(mode===1.U,index>>1,index))
    val x=pixel(3,0);val y=pixel(7,4)
    // The x1 bank bit alternates full-width row-major quad pairs, including
    // row boundaries. Sample bits spread one pixel's four samples as well.
    val bank=Cat(x(1),y(0)^sample(1),x(0)^sample(0))
    val row=Cat(y(3,1),x(3,2),sample)
    (bank,row)
  }
  private def legalIndex(index: UInt): Bool =
    mode===2.U || Mux(mode===1.U,!index(9),!index(9,8).orR)

  private val pending=RegInit(VecInit(Seq.fill(4)(false.B)))
  private val captured=RegInit(VecInit(Seq.fill(4)(false.B)))
  private val resultBank=Reg(Vec(4,UInt(3.W)))
  private val resultIndex=Reg(Vec(4,UInt(10.W)))
  private val resultTicket=Reg(Vec(4,UInt(8.W)))
  private val heldColor=Reg(Vec(4,UInt(32.W)))
  private val heldDepth=Reg(Vec(4,UInt(32.W)))
  private val readLocation=(0 until 4).map(p=>location(io.read(p).bits.index))
  private val writeLocation=(0 until writePorts).map(p=>location(io.write(p).bits.index))
  private val readReady=Wire(Vec(4,Vec(8,Bool())))
  private val writeReady=Wire(Vec(writePorts,Vec(8,Bool())))
  private val canRead=(0 until 4).map(p=> !pending(p) || replies(p).io.enq.ready)
  private val enabled=bound && !io.begin
  private val legacyRead=io.legacy.map(p=>enabled && p.readEnable).getOrElse(false.B)
  private val legacyWrite=io.legacy.map(p=>enabled && p.writeEnable).getOrElse(false.B)
  private val legacyReadLocation=location(io.legacy.map(_.readIndex).getOrElse(0.U(10.W)))
  private val legacyWriteLocation=location(io.legacy.map(_.writeIndex).getOrElse(0.U(10.W)))

  for(bank <- 0 until 8) {
    val arb=arbiters(bank)
    for(port <- 0 until 4) {
      val read=arb.io.in(port)
      read.valid:=enabled && canRead(port) && io.read(port).valid && readLocation(port)._1===bank.U
      read.bits:=0.U.asTypeOf(read.bits)
      read.bits.row:=readLocation(port)._2
      readReady(port)(bank):=read.ready
    }
    for(port <- 0 until writePorts) {
      val write=arb.io.in(port+4)
      write.valid:=enabled && io.write(port).valid && writeLocation(port)._1===bank.U
      write.bits.row:=writeLocation(port)._2
      write.bits.write:=true.B
      write.bits.color:=io.write(port).bits.color
      write.bits.depthStencil:=io.write(port).bits.depthStencil
      write.bits.colorEnable:=io.write(port).bits.colorEnable
      write.bits.depthStencilEnable:=io.write(port).bits.depthStencilEnable
      writeReady(port)(bank):=write.ready
    }
    val directRead=legacyRead && legacyReadLocation._1===bank.U
    val directWrite=legacyWrite && legacyWriteLocation._1===bank.U
    arb.io.out.ready:= !directRead && !directWrite
    val read=arb.io.out.fire && !arb.io.out.bits.write
    val write=arb.io.out.fire && arb.io.out.bits.write
    for(memory <- Seq(colors(bank),depths(bank))) {
      memory.io.readEnable:=read || directRead
      memory.io.readAddress:=Mux(directRead,legacyReadLocation._2,arb.io.out.bits.row)
      memory.io.writeAddress:=Mux(directWrite,legacyWriteLocation._2,arb.io.out.bits.row)
    }
    colors(bank).io.writeEnable:=(write && arb.io.out.bits.colorEnable) ||
      (directWrite && io.legacy.map(_.colorEnable).getOrElse(false.B))
    colors(bank).io.writeData:=Mux(directWrite,io.legacy.map(_.color).getOrElse(0.U),arb.io.out.bits.color)
    depths(bank).io.writeEnable:=(write && arb.io.out.bits.depthStencilEnable) ||
      (directWrite && io.legacy.map(_.depthStencilEnable).getOrElse(false.B))
    depths(bank).io.writeData:=Mux(directWrite,io.legacy.map(_.depthStencil).getOrElse(0.U),arb.io.out.bits.depthStencil)
  }

  private val colorData=VecInit(colors.map(_.io.readData))
  private val depthData=VecInit(depths.map(_.io.readData))
  io.legacy.foreach { p=>
    val bank=RegEnable(legacyReadLocation._1,legacyRead)
    p.colorData:=colorData(bank);p.depthStencilData:=depthData(bank)
    assert(!(p.readEnable && p.writeEnable),"legacy attachment access is single-port")
    when(p.readEnable || p.writeEnable) {
      assert(!VecInit(io.read.map(_.valid)).asUInt.orR && !VecInit(io.write.map(_.valid)).asUInt.orR,
        "legacy attachment access requires quiescent tagged producers")
    }
    when(io.begin){assert(!p.readEnable && !p.writeEnable,"legacy producer must be quiescent at begin")}
  }
  for(port <- 0 until 4) {
    val queue=replies(port)
    io.response(port) <> queue.io.deq
    io.read(port).ready:=enabled && canRead(port) && readReady(port)(readLocation(port)._1)
    queue.io.enq.valid:=pending(port)
    queue.io.enq.bits.index:=resultIndex(port)
    queue.io.enq.bits.ticket:=resultTicket(port)
    queue.io.enq.bits.color:=Mux(captured(port),heldColor(port),colorData(resultBank(port)))
    queue.io.enq.bits.depthStencil:=Mux(captured(port),heldDepth(port),depthData(resultBank(port)))
    when(pending(port) && !captured(port) && !queue.io.enq.ready) {
      heldColor(port):=colorData(resultBank(port))
      heldDepth(port):=depthData(resultBank(port))
      captured(port):=true.B
    }
    when(queue.io.enq.fire) { pending(port):=false.B;captured(port):=false.B }
    when(io.read(port).fire) {
      assert(legalIndex(io.read(port).bits.index),"sample index exceeds the admitted sample mode")
      pending(port):=true.B;captured(port):=false.B
      resultBank(port):=readLocation(port)._1
      resultIndex(port):=io.read(port).bits.index
      resultTicket(port):=io.read(port).bits.ticket
    }
  }
  for(port <- 0 until writePorts) {
    io.write(port).ready:=enabled && writeReady(port)(writeLocation(port)._1)
    when(io.write(port).fire) {
      assert(legalIndex(io.write(port).bits.index),"sample index exceeds the admitted sample mode")
    }
  }
  io.drained:= !pending.asUInt.orR && !VecInit(replies.map(_.io.deq.valid)).asUInt.orR
  io.readIssues:=PopCount(VecInit(io.read.map(_.fire)))
  io.writeIssues:=PopCount(VecInit(io.write.map(_.fire)))
  when(io.begin) {
    assert(io.drained,"banked sample replies must drain before changing tile ownership")
    assert(!VecInit(io.read.map(_.valid)).asUInt.orR && !VecInit(io.write.map(_.valid)).asUInt.orR,
      "the producer must be quiescent at tile begin")
    assert(io.sampleMode<=2.U,"unsupported sample mode")
    mode:=io.sampleMode
    bound:=io.sampleMode<=2.U
  }
}
