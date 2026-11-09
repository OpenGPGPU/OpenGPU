package opengpu.graphics

import chisel3._
import chisel3.util._

/** General ASTC LDR 2D block decoder.
  *
  * Chains the validated block-mode, weight-grid, color-endpoint format,
  * color-integer, per-partition assembly, partition-pattern, weight-infill and
  * endpoint owners. It decodes one block into up to 144 UNORM8 texels,
  * handling void-extent constant colors, partitions and dual-plane weights.
  * HDR/reserved blocks and malformed sequences publish the invalid status with
  * zero texels. sRGB blocks select the half-step endpoint expansion.
  */
object AstcLdrGeneralStatus {val ok=0;val invalid=1}

class AstcLdrGeneralRequest extends Bundle {
  val tag=UInt(8.W);val block=UInt(128.W)
  val blockWidth=UInt(4.W);val blockHeight=UInt(4.W);val srgb=Bool()
}
class AstcLdrGeneralResult extends Bundle {
  val tag=UInt(8.W);val status=UInt(2.W);val texels=UInt(8.W)
  val rgba8=Vec(144,Vec(4,UInt(8.W)))
}

class AstcLdrGeneralBlockUnit extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new AstcLdrGeneralRequest))
    val out=Decoupled(new AstcLdrGeneralResult)
    val busy=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,modeSend,modeWait,gridSend,gridWait,
    fmtSend,fmtWait,colorSend,colorWait,asmSend,asmWait,texInit,partSend,partWait,
    infillSend,infillWait,epSend,epWait,respond=Value}
  private val phase=RegInit(State.idle)
  private val request=Reg(new AstcLdrGeneralRequest)
  private val result=Reg(new AstcLdrGeneralResult)
  private val block=Reg(UInt(128.W));private val bw=Reg(UInt(4.W));private val bh=Reg(UInt(4.W))
  private val srgb=Reg(Bool());private val partitions=Reg(UInt(3.W));private val dual=Reg(Bool())
  private val gridX=Reg(UInt(4.W));private val gridY=Reg(UInt(4.W));private val weightBits=Reg(UInt(7.W))
  private val formats=Reg(Vec(4,UInt(4.W)));private val endpoints=Reg(Vec(4,Vec(8,UInt(8.W))))
  private val plane2=Reg(UInt(2.W));private val partitionIndex=Reg(UInt(10.W))
  private val weights=Reg(Vec(64,UInt(7.W)))
  private val x=Reg(UInt(4.W));private val y=Reg(UInt(4.W))
  private val partIndex=Reg(UInt(2.W));private val weight0=Reg(UInt(7.W));private val weight1=Reg(UInt(7.W))
  private val status=Reg(UInt(2.W))

  io.in.ready:=phase===State.idle;io.out.valid:=phase===State.respond;io.out.bits:=result
  io.busy:=phase=/=State.idle

  private val mode=Module(new AstcBlockModeUnit)
  private val grid=Module(new AstcWeightGridUnit)
  private val fmt=Module(new AstcColorEndpointFormatUnit)
  private val ci=Module(new AstcColorIntegerUnit)
  private val asm=Module(new AstcEndpointAssemblyUnit)
  private val part=Module(new AstcPartitionUnit)
  private val infill=Module(new AstcWeightInfillUnit)
  private val ep=Module(new AstcLdrEndpointUnit)

  mode.io.in.valid:=phase===State.modeSend
  mode.io.in.bits.tag:=request.tag;mode.io.in.bits.block:=block
  mode.io.in.bits.blockWidth:=bw;mode.io.in.bits.blockHeight:=bh
  mode.io.out.ready:=phase===State.modeWait

  grid.io.in.valid:=phase===State.gridSend
  grid.io.in.bits.tag:=request.tag;grid.io.in.bits.block:=block
  grid.io.in.bits.gridX:=gridX;grid.io.in.bits.gridY:=gridY
  grid.io.in.bits.dualPlane:=dual;grid.io.in.bits.quant:=mode.io.out.bits.quantMode
  grid.io.out.ready:=phase===State.gridWait

  fmt.io.in.valid:=phase===State.fmtSend
  fmt.io.in.bits.tag:=request.tag;fmt.io.in.bits.block:=block
  fmt.io.in.bits.partitions:=partitions;fmt.io.in.bits.weightBits:=weightBits
  fmt.io.in.bits.dualPlane:=dual
  fmt.io.out.ready:=phase===State.fmtWait

  ci.io.in.valid:=phase===State.colorSend
  ci.io.in.bits.tag:=request.tag;ci.io.in.bits.block:=block
  ci.io.in.bits.integerCount:=fmt.io.out.bits.integerCount
  ci.io.in.bits.colorBits:=fmt.io.out.bits.colorBits
  ci.io.in.bits.bitOffset:=fmt.io.out.bits.colorOffset
  ci.io.out.ready:=phase===State.colorWait

  asm.io.in.valid:=phase===State.asmSend
  asm.io.in.bits.tag:=request.tag;asm.io.in.bits.partitions:=partitions
  asm.io.in.bits.formats:=formats;asm.io.in.bits.values:=ci.io.out.bits.values
  asm.io.out.ready:=phase===State.asmWait

  part.io.in.valid:=phase===State.partSend
  part.io.in.bits.tag:=request.tag;part.io.in.bits.seed:=partitionIndex
  part.io.in.bits.partitions:=partitions;part.io.in.bits.blockWidth:=bw;part.io.in.bits.blockHeight:=bh
  part.io.in.bits.x:=x;part.io.in.bits.y:=y;part.io.in.bits.z:=0.U
  part.io.out.ready:=phase===State.partWait

  infill.io.in.valid:=phase===State.infillSend
  infill.io.in.bits.tag:=request.tag
  infill.io.in.bits.blockWidth:=bw;infill.io.in.bits.blockHeight:=bh
  infill.io.in.bits.gridX:=gridX;infill.io.in.bits.gridY:=gridY
  infill.io.in.bits.weights:=weights;infill.io.in.bits.dualPlane:=dual
  infill.io.in.bits.x:=x;infill.io.in.bits.y:=y
  infill.io.out.ready:=phase===State.infillWait

  ep.io.in.valid:=phase===State.epSend
  ep.io.in.bits.tag:=request.tag;ep.io.in.bits.cem:=formats(partIndex)
  ep.io.in.bits.values:=endpoints(partIndex)
  ep.io.in.bits.weight:=weight0;ep.io.in.bits.dualPlane:=dual
  ep.io.in.bits.weight2:=weight1;ep.io.in.bits.plane2Component:=plane2
  ep.io.in.bits.srgb:=srgb
  ep.io.out.ready:=phase===State.epWait

  private val lastTexel=x===bw-1.U && y===bh-1.U
  private val voidLdr= !block(9)
  private val voidReserved=block(11) && block(10)
  private def fillVoid(): Unit={
    val r=(block(79,64)>>8)(7,0);val g=(block(95,80)>>8)(7,0)
    val b=(block(111,96)>>8)(7,0);val a=(block(127,112)>>8)(7,0)
    for(i<-0 until 144)result.rgba8(i):=VecInit(r,g,b,a)
    result.texels:=bw*bh
  }
  private def fail(): Unit={status:=AstcLdrGeneralStatus.invalid.U;result.status:=AstcLdrGeneralStatus.invalid.U;phase:=State.respond}
  private def storeTexel(): Unit={
    val idx=(y*bw+x)(7,0)
    for(c<-0 until 4)result.rgba8(idx)(c):=ep.io.out.bits.rgba8(c)
  }

  when(io.in.fire){
    request:=io.in.bits;block:=io.in.bits.block;bw:=io.in.bits.blockWidth;bh:=io.in.bits.blockHeight
    srgb:=io.in.bits.srgb;result:=0.U.asTypeOf(result);result.tag:=io.in.bits.tag
    status:=0.U;phase:=State.modeSend
  }
  switch(phase){
    is(State.modeSend){phase:=State.modeWait}
    is(State.modeWait){when(mode.io.out.fire){
      partitions:=mode.io.out.bits.partitions;dual:=mode.io.out.bits.dualPlane
      gridX:=mode.io.out.bits.gridX;gridY:=mode.io.out.bits.gridY
      weightBits:=mode.io.out.bits.weightBits
      when(mode.io.out.bits.status=/=0.U){fail()}
      .elsewhen(mode.io.out.bits.voidExtent){
        when(voidLdr && voidReserved){fillVoid();result.status:=0.U;phase:=State.respond}
        .otherwise{fail()}
      }.otherwise{phase:=State.gridSend}
    }}
    is(State.gridSend){phase:=State.gridWait}
    is(State.gridWait){when(grid.io.out.fire){
      when(grid.io.out.bits.status=/=0.U){fail()}.otherwise{weights:=grid.io.out.bits.weights;phase:=State.fmtSend}
    }}
    is(State.fmtSend){phase:=State.fmtWait}
    is(State.fmtWait){when(fmt.io.out.fire){
      formats:=fmt.io.out.bits.formats;plane2:=fmt.io.out.bits.plane2Component
      partitionIndex:=fmt.io.out.bits.partitionIndex
      val fmtIn=fmt.io.out.bits.formats
      def cemOk(f: UInt)=f===0.U||f===1.U||f===4.U||f===5.U||f===6.U||f===8.U||f===9.U||f===10.U||f===12.U||f===13.U
      val cemsOk=(0 until 4).map{i=>Mux(fmt.io.out.bits.partitions>i.U,cemOk(fmtIn(i)),true.B)}.reduce((a,b)=>a&&b)
      when(fmt.io.out.bits.status=/=0.U || !cemsOk){fail()}.otherwise{phase:=State.colorSend}
    }}
    is(State.colorSend){phase:=State.colorWait}
    is(State.colorWait){when(ci.io.out.fire){
      when(ci.io.out.bits.status=/=0.U){fail()}.otherwise{phase:=State.asmSend}
    }}
    is(State.asmSend){phase:=State.asmWait}
    is(State.asmWait){when(asm.io.out.fire){
      endpoints:=asm.io.out.bits.endpoints
      when(asm.io.out.bits.status=/=0.U){fail()}
      .otherwise{x:=0.U;y:=0.U;phase:=State.texInit}
    }}
    is(State.texInit){phase:=State.partSend}
    is(State.partSend){phase:=State.partWait}
    is(State.partWait){when(part.io.out.fire){
      when(part.io.out.bits.status=/=0.U){fail()}.otherwise{partIndex:=part.io.out.bits.index;phase:=State.infillSend}
    }}
    is(State.infillSend){phase:=State.infillWait}
    is(State.infillWait){when(infill.io.out.fire){
      when(infill.io.out.bits.status=/=0.U){fail()}
      .otherwise{weight0:=infill.io.out.bits.weight;weight1:=infill.io.out.bits.weight2;phase:=State.epSend}
    }}
    is(State.epSend){phase:=State.epWait}
    is(State.epWait){when(ep.io.out.fire){
      when(ep.io.out.bits.status=/=0.U){fail()}
      .otherwise{
        storeTexel()
        when(lastTexel){result.texels:=bw*bh;result.status:=0.U;phase:=State.respond}
        .otherwise{
          when(x===bw-1.U){x:=0.U;y:=y+1.U}.otherwise{x:=x+1.U}
          phase:=State.partSend
        }
      }
    }}
    is(State.respond){when(io.out.fire){phase:=State.idle}}
  }
}
