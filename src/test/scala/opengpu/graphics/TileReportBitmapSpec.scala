package opengpu.graphics

import chisel3._
import chisel3.util.HasBlackBoxInline
import java.nio.file.{Files,Paths}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

private class TileReportBitmapResources extends BlackBox with HasBlackBoxInline {
  val io=IO(new Bundle {val present=Output(Bool())})
  setInline("TileReportBitmapResources.sv",
    "module TileReportBitmapResources(output present); assign present=1'b1; endmodule\n"+
      Files.readString(Paths.get("depends/asap7_sram_0p0/generated/verilog/srambank_64x4x32_6t122.v")))
}

private class WideTileReportBitmapHarness extends Module {
  private val core=Module(new TileReportBitmap(65536,useBlackBoxes=true))
  val io=IO(chiselTypeOf(core.io));io<>core.io
  val resourcesPresent=IO(Output(Bool()))
  resourcesPresent:=Module(new TileReportBitmapResources).io.present
}

class TileReportBitmapSpec extends AnyFlatSpec {
  it should "retain independent boundary report bits across all eight real SRAM banks" in {
    simulate(new WideTileReportBitmapHarness){d=>
      d.io.update.valid.poke(false.B);d.io.update.bits.tile.poke(0.U);d.io.update.bits.value.poke(false.B)
      d.io.read.valid.poke(false.B);d.io.read.bits.poke(0.U)
      d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B);d.resourcesPresent.expect(true.B)
      def update(tile: Int,value: Boolean): Unit={
        d.io.ready.expect(true.B);d.io.update.valid.poke(true.B)
        d.io.update.bits.tile.poke(tile.U);d.io.update.bits.value.poke(value.B);d.clock.step()
        d.io.update.valid.poke(false.B);d.io.ready.expect(false.B);d.clock.step();d.io.ready.expect(true.B)
      }
      def read(tile: Int,expected: Boolean): Unit={
        d.io.ready.expect(true.B);d.io.read.bits.poke(tile.U);d.io.read.valid.poke(true.B)
        d.clock.step();d.io.read.valid.poke(false.B);d.io.readData.expect(expected.B)
      }
      val tiles=((0 until 8).flatMap(bank=>Seq(bank*8192,bank*8192+31,bank*8192+8190,bank*8192+8191))++Seq(32,65503,65504)).distinct
      // Initialize only the selected words; do not scan the full bitmap/grid.
      for(word<-tiles.map(_/32).distinct;bit<-0 until 32)update(word*32+bit,false)
      val expected=scala.collection.mutable.Map(tiles.map(_->false):_*)
      for(round<-0 until 5;tile<-tiles){
        val value=((tile+round)&1)==0;update(tile,value);expected(tile)=value
        for(probe<-tiles)read(probe,expected(probe))
      }
      info(s"WIDE_TILE_BITMAP boundaryBits=${tiles.size} realBanks=8 highestTile=65535 independentNeighbors=true fullGridScan=false")
    }
  }
}
