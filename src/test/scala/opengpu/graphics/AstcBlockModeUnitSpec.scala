package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class AstcBlockModeUnitSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  private val scale=Vector(1,8,2,7,13,3,10,18,4,13,23,5)
  private val rounds=Vector(0,2,0,1,2,0,1,2,0,1,2,0)
  private case class Decoded(status: Int,void: Boolean,gridX: Int,gridY: Int,dual: Boolean,quant: Int,bits: Int,parts: Int)
  private def decode(block: BigInt,bw: Int,bh: Int): Decoded={
    val bm=(block&0x7ff).toInt
    val parts=((block>>11)&3).toInt+1
    if((bm&0x1ff)==0x1fc)return Decoded(0,true,0,0,false,0,0,parts)
    var base=(bm>>4)&1;var h=(bm>>9)&1;var d=(bm>>10)&1
    val a=(bm>>5)&3
    var gx=0;var gy=0;var invalid=false
    if((bm&3)!=0){
      base|=(bm&3)<<1
      val b=(bm>>7)&3
      (bm>>2)&3 match{
        case 0=>gx=b+4;gy=a+2
        case 1=>gx=b+8;gy=a+2
        case 2=>gx=a+2;gy=b+8
        case _=>val c=b&1;if((bm&0x100)!=0){gx=c+2;gy=a+2}else{gx=a+2;gy=c+6}
      }
    }else{
      base|=((bm>>2)&3)<<1
      if(((bm>>2)&3)==0)invalid=true
      else{
        val b=(bm>>9)&3
        (bm>>7)&3 match{
          case 0=>gx=12;gy=a+2
          case 1=>gx=a+2;gy=12
          case 2=>gx=a+6;gy=b+6;d=0;h=0
          case _=>(bm>>5)&3 match{
            case 0=>gx=6;gy=10
            case 1=>gx=10;gy=6
            case _=>invalid=true}
        }
      }
    }
    val count=gx*gy*(d+1);val quant=(base-2)+6*h;val dual=d!=0
    val bits=if(quant>=0 && quant<12)(scale(quant)*count+rounds(quant)*2)/(rounds(quant)*2+1)else 1024
    val legal= !invalid && quant>=0 && quant<12 && count<=64 && bits>=24 && bits<=96 &&
      gx<=bw && gy<=bh && !(dual && parts==4)
    if(legal)Decoded(0,false,gx,gy,dual,quant,bits,parts)else Decoded(1,false,0,0,false,0,0,0)
  }
  behavior of "AstcBlockModeUnit"
  it should "decode every 11-bit block mode against the reference rules" in {
    simulate(new AstcBlockModeUnit){d=>zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      var tag=0;var checked=0
      for((bw,bh)<-Seq((4,4),(5,4),(8,5),(12,12));bm<-0 until 2048){
        val block=BigInt(bm)
        val want=decode(block,bw,bh)
        tag=(tag+1)&0xff;val q=d.io.in.bits;zero(q);q.tag.poke(tag.U);q.block.poke(block.U)
        q.blockWidth.poke(bw.U);q.blockHeight.poke(bh.U)
        d.io.in.valid.poke(true.B);var n=0;while(!d.io.in.ready.peek().litToBoolean && n<20){d.clock.step();n+=1}
        assert(d.io.in.ready.peek().litToBoolean);d.clock.step();d.io.in.valid.poke(false.B);zero(q)
        n=0;while(!d.io.out.valid.peek().litToBoolean && n<50){d.clock.step();n+=1}
        assert(n<50,s"block mode timeout bm=$bm")
        val o=d.io.out.bits;o.tag.expect(tag.U)
        val got=Decoded(o.status.peek().litValue.toInt,o.voidExtent.peek().litToBoolean,
          o.gridX.peek().litValue.toInt,o.gridY.peek().litValue.toInt,o.dualPlane.peek().litToBoolean,
          o.quantMode.peek().litValue.toInt,o.weightBits.peek().litValue.toInt,o.partitions.peek().litValue.toInt)
        assert(got==want,s"bm=$bm footprint=${bw}x$bh got=$got want=$want")
        d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B);checked+=1
      }
      // Four partitions with a dual-plane mode must be rejected.
      val dualMode=(0 until 2048).find(bm=>decode(BigInt(bm),12,12).dual).get
      val q=d.io.in.bits;zero(q);q.tag.poke(200.U);q.block.poke((BigInt(dualMode)|(BigInt(3)<<11)).U)
      q.blockWidth.poke(12.U);q.blockHeight.poke(12.U);d.io.in.valid.poke(true.B);d.clock.step();d.io.in.valid.poke(false.B)
      var n=0;while(!d.io.out.valid.peek().litToBoolean && n<50){d.clock.step();n+=1}
      d.io.out.bits.status.expect(1.U);d.io.out.bits.dualPlane.expect(false.B)
      d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)
      info(s"ASTC_BLOCK_MODE modes2048=true footprints4=true voidExtent=true dualFourPartitionsRejected=true checked=$checked")
    }
  }
}
