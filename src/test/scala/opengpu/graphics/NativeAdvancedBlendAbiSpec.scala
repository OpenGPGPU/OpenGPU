package opengpu.graphics

import java.nio.file.Files
import opengpu.elaboration.{EmitComputeResourceAbi,EmitNativeDescriptorPreflightAbi,EmitNativeAdvancedBlendAbi,EmitNativeTypedGraphicsAbi,EmitTypedFragmentStateAbi}
import org.scalatest.flatspec.AnyFlatSpec
import scala.sys.process._

class NativeAdvancedBlendAbiSpec extends AnyFlatSpec {
  it should "derive complete custom discovery while keeping the alignment v2 product frozen" in {
    val off=opengpu.system.NativeAlignmentHostProfile.target
    assert(!off.native.storage.nativeAdvancedDraws && off.requiredDiscovery.size==72)
    assert(off.requiredDiscovery.map(_._1).distinct.size==72)
    assert(off.requiredDiscovery.toMap.apply(0x368)==BigInt(0x7fff))
    assert(!off.requiredDiscovery.exists{case(address,_)=>address>=0x4c0 && address<0x4e0})
    val on=off.copy(native=off.native.copy(storage=off.native.storage.copy(
      advancedBlend=true,nativeAdvancedDraws=true)))
    assert(on.requiredDiscovery.size==88 && on.requiredDiscovery.map(_._1).distinct.size==88)
    assert(on.requiredDiscovery.toMap.apply(0x368)==BigInt(0x1ffff))
    assert(on.requiredDiscovery.take(72).toMap.updated(0x368,BigInt(0x7fff))==off.requiredDiscovery.toMap)
    val gab=Seq[BigInt](BigInt("47414201",16),8,8,0x7fff,BigInt("f00001ff",16),15,7,0)
    assert(on.requiredDiscovery.slice(72,80)==gab.zipWithIndex.map{case(word,index)=>(0x4c0+4*index,word)})
    val gnp=Seq[BigInt](BigInt("474e5001",16),8,512,2048,2048,512,15,0)
    assert(on.requiredDiscovery.drop(80)==gnp.zipWithIndex.map{case(word,index)=>(0x4e0+4*index,word)})
    val scanned=off.copy(native=off.native.copy(preflightDescriptors=true))
    assert(scanned.requiredDiscovery.size==80 && scanned.requiredDiscovery.toMap.apply(0x368)==BigInt(0x17fff))
    assert(scanned.requiredDiscovery.drop(72)==on.requiredDiscovery.drop(80))
    val protectedSources=on.copy(nativeProtectedSources=true)
    assert(protectedSources.requiredDiscovery.size==96 &&
      protectedSources.requiredDiscovery.map(_._1).distinct.size==96)
    assert(protectedSources.requiredDiscovery.toMap.apply(0x368)==BigInt(0x3ffff))
    assert(protectedSources.requiredDiscovery.take(88).toMap.updated(0x368,BigInt(0x1ffff))==on.requiredDiscovery.toMap)
    val gsl=Seq[BigInt](BigInt("47534c01",16),8,31,32,1<<20,BigInt("04051918",16),0x320,0x138)
    assert(protectedSources.requiredDiscovery.drop(88)==gsl.zipWithIndex.map{case(word,index)=>(0x300+4*index,word)})
    assert(!off.nativeProtectedSources)
    intercept[IllegalArgumentException](off.copy(nativeProtectedSources=true))
    intercept[IllegalArgumentException](opengpu.system.ComputeResourceCapabilities.words(
      opengpu.config.ComputeResourceProfile(off.execution,off.computeUnits,off.axiDataBytes),
      nativeProtectedSourcesAvailable=true))
    for(missing<-0 until 9){
      val cause=intercept[IllegalArgumentException]{
        circt.stage.ChiselStage.emitCHIRRTL(new opengpu.system.GpuHostAxi(
          unifiedCommandMmio=missing!=0,protectVmState=missing!=1,externalBinnedRender=missing!=2,
          nativeVertexInput=missing!=3,nativeCompletionInfo=missing!=4,
          nativeDescriptorPreflight=missing!=5,nativeUniformResources=missing!=6,
          shaderExecutionLimits=missing!=7,version=if(missing==8)1 else 2,
          computeResources=Some(opengpu.config.ComputeResourceProfile(off.execution,off.computeUnits,off.axiDataBytes)),
          nativeProtectedSources=true))
      }
      assert(cause.getMessage.contains("native protected sources require"))
    }
    assert(opengpu.system.NativeAlignmentHostProfile.id=="two-cu-vga60-v2")
    assert(!opengpu.system.NativeAlignmentHostProfile.nativeAdvancedBlend)
  }
  it should "publish a separate strict transport contract without changing frozen GDB1" in {
    assert(NativeAdvancedBlendCapabilities.words==Seq[BigInt](BigInt("47414201",16),8,8,0x7fff,BigInt("f00001ff",16),15,7,0))
    assert(NativeDrawBufferBlendCapabilities.words==Seq[BigInt](BigInt("47444201",16),8,6,4,0x7fff,0x1f,0x7f,0))
    EmitNativeAdvancedBlendAbi.main(Array("--check"));EmitNativeDescriptorPreflightAbi.main(Array("--check"));EmitComputeResourceAbi.main(Array("--check"))
    EmitNativeTypedGraphicsAbi.main(Array("--check"));EmitTypedFragmentStateAbi.main(Array("--check"))
    val directory=Files.createTempDirectory("opengpu-native-advanced-");val binary=directory.resolve("discovery")
    try{
      for(name<-Seq("discovery","gfs")){
        assert(Seq("cc","-std=c11","-O2","-Wall","-Wextra","-Werror",
          s"driver/tests/opengpu_native_advanced_blend_${name}_test.c","-o",binary.toString).! == 0)
        assert(Seq(binary.toString).! == 0)
      }
      assert(Seq("cc","-std=c11","-O2","-Wall","-Wextra","-Werror",
        "driver/tests/opengpu_native_preflight_discovery_test.c","-o",binary.toString).! == 0)
      assert(Seq(binary.toString).! == 0)
      assert(Seq("cc","-std=c11","-Wall","-Wextra","-Werror","-D__KERNEL__","-nostdinc",
        "-fsyntax-only","driver/tests/opengpu_native_advanced_blend_kernel_abi_test.c").! == 0)
    }finally{Files.deleteIfExists(binary);Files.deleteIfExists(directory)}
  }
}
