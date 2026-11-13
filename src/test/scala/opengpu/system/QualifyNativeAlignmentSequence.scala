package opengpu.system

import java.nio.file.Paths
import opengpu.graphics.{NativeProductFrameInputs,NativeProductWorkloads}
import opengpu.testutil.{NativeFrameMemoryCodec,PagedFrameMemory}

/** An independent byte oracle for the compiled compute/native sequence.
  * It neither executes a GPU nor substitutes software results for RTL outputs.
  */
private[system] object NativeAlignmentSequenceData {
  val lowStart = 0x1000L; val lowEnd = 0x70000L
  val groups = 4; val items = 128; val guard = BigInt("a5a5a5a5",16)
  def output(epoch:Int):Long = { require(epoch>=0 && epoch<2); 0x20000L+epoch*0x10000L }
  def pc(epoch:Int):Long = { require(epoch>=0 && epoch<2); 0x1000L+epoch*0x1000L }
  private def add(rd:Int,a:Int,b:Int):BigInt = BigInt((b<<20)|(a<<15)|(rd<<7)|0x33)
  private def shift(rd:Int,source:Int,amount:Int):BigInt = BigInt((amount<<20)|(source<<15)|(1<<12)|(rd<<7)|0x13)
  private def bits(value:Float):BigInt = BigInt(java.lang.Float.floatToRawIntBits(value).toLong & 0xffffffffL)
  def program(epoch:Int):Vector[BigInt] = {
    require(epoch>=0 && epoch<2)
    Vector(shift(10,2,9),shift(11,8,2),add(10,10,11),add(10,10,1),BigInt("0104f057",16),
      BigInt(((3+8*epoch)<<20)|(2<<15)|(12<<7)|0x13),
      BigInt((1<<25)|(1<<20)|(12<<15)|(4<<12)|(2<<7)|0x57),
      BigInt((1<<25)|(10<<15)|(6<<12)|(2<<7)|0x27),
      BigInt("00020737",16),add(14,14,10),BigInt((14<<15)|(2<<12)|(1<<7)|7),
      BigInt("00108153",16),BigInt("000016b7",16),add(15,10,13),
      BigInt((2<<20)|(15<<15)|(2<<12)|0x27),BigInt("30500073",16))
  }
  def seed(memory:PagedFrameMemory):Unit = {
    memory.mapRange(lowStart,lowEnd,0xa5)
    for (epoch<-0 until 2) {
      program(epoch).zipWithIndex.foreach { case (value,index) => memory.writeWord(pc(epoch)+index*4,value) }
      for (group<-0 until groups;warp<-0 until 8)
        memory.writeWord(output(epoch)+0x20000+group*512+warp*64,
          bits(1.0f+(group*8+warp+epoch*32)/16.0f))
    }
  }
  def completed(initial:PagedFrameMemory,count:Int):PagedFrameMemory = {
    require(count>=1 && count<=2)
    val result=initial.snapshot()
    for(epoch<-0 until count;item<-0 until groups*items) {
      result.writeWord(output(epoch)+item*4,BigInt(item%128+item/128+3+epoch*8))
      if(item%16==0)result.writeWord(output(epoch)+0x1000+item*4,
        bits(2.0f+((item/128)*8+(item%128)/16+epoch*32)/8.0f))
    }
    result
  }
  def verify(scene:NativeProductWorkloads.Scene,actual:PagedFrameMemory,count:Int):Unit = {
    val input=NativeProductFrameInputs.encodeApiExtent(scene)
    seed(input.memory)
    val expected=completed(input.memory,count)
    val mutableNames=Set("vertex-scratch","fragment-scratch","primitive-arena","bin-pool","bin-directory")
    val writable=input.regions.filter(r=>mutableNames(r.name) || r.name.startsWith("image-"))
      .map(r=>(r.base,r.limit))
    require(NativeFrameMemoryCodec.unchangedOutside(expected,actual,writable),
      s"${scene.id}/epoch$count compute output, read-only data, or allocation metadata changed")
    NativeProductFrameInputs.verify(scene,input.copy(memory=actual))
  }
}

object VerifyNativeAlignmentSequence {
  def main(args:Array[String]):Unit = {
    require(args.length==2 && args(0)=="--output","usage: --output DIR")
    val output=Paths.get(args(1))
    for(scene<-NativeProductWorkloads.suite(32,32);epoch<-0 until 2) {
      val actual=NativeFrameMemoryCodec.read(output.resolve(s"${scene.id}.after-native-$epoch.memory"))
      NativeAlignmentSequenceData.verify(scene,actual,epoch+1)
      println(s"NATIVE_ALIGNMENT_SEQUENCE_IMAGE scene=${scene.id} epoch=$epoch exactImagesComputeAndReadOnlyGuards=true")
    }
    println("NATIVE_ALIGNMENT_SEQUENCE PASS scenes=6 commands=24 resets=6 resetsBetweenCommands=0 computeUnits=2 lanes=16 warps=8 nativeEngines=4 frame=32x32 clockQualified=false fpsQualified=false")
  }
}
