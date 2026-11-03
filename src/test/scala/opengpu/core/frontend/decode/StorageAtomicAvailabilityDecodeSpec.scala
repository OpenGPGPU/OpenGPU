package opengpu.core.frontend.decode

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class StorageAtomicAvailabilityDecodeSpec extends AnyFlatSpec {
  for (available <- Seq(false,true); imageAtomic <- Seq(false,true)) {
    it should s"gate storage atomic encodings independently of image atomics available=$available image=$imageAtomic" in {
      simulate(new FullInstructionDecoder(GpuConfig(),textureAvailable=false,nativeResourcesAvailable=false,
        imageAvailable=true,storageAvailable=true,imageAtomicAvailable=imageAtomic,storageAtomicAvailable=available)) { d =>
        d.io.imageEnabled.get.poke(true.B)
        for(enabled<-Seq(false,true);fn<-24 to 26;form<-0 until 8;vd<-Seq(0,7);vm<-Seq(0,1)){
          d.io.storageEnabled.get.poke(enabled.B)
          val instruction=(BigInt(fn)<<26)|(BigInt(vm)<<25)|(BigInt(2)<<20)|(BigInt(1)<<15)|
            (BigInt(form)<<12)|(BigInt(vd)<<7)|0x2b
          val encoding=(fn==24 && form==0 && vd==0)||fn==25||(fn==26 && form<2)
          val legal=available && enabled && encoding
          d.io.instruction.poke(instruction.U);d.io.decoded.legal.expect(legal.B)
          if(legal){val v=d.io.decoded.vector;v.unit.expect(VectorUnit.texture)
            v.readsVs1.expect(true.B);v.readsVs2.expect(true.B)
            v.writesVd.expect((fn!=24).B);v.writesScalar.expect((fn==24).B)}
        }
      }
    }
  }
}
