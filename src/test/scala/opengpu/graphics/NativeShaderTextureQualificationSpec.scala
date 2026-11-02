package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._

/** Exhaustive format and wide-lane qualification, run locally. */
class NativeShaderTextureQualificationSpec extends NativeShaderTextureFixture {
  import WindowRecordTest._
  import NativeTextureFetchTestData._
  for(stage<-0 to 1)
  it should s"fetch every typed component and target through independent stage bindings with stage=$stage" in {
    simulate(new NativeShaderTextureUnit(GpuConfig(lanes=4,warps=2),stage)){d=>val t=new Driver(d);roots(t.put,stage)
      var checks=0
      for(target<-0 to 3;format<-0 until 54 if !(target==3 && ImageFormatReference.format(ImageFormatReference.names(format)).depth)){val unit=(format+target)%32;val v=install(t.put,t.byte,unit,format,target,stage)
        coordinates(t.put,target);d.io.invalidate.poke(true.B);t.tick();d.io.invalidate.poke(false.B)
        for(c<-0 until 4){t.scalarRequest(c,unit);t.scalarResult(v.expected(c));checks+=1}}
      assert(checks==844)
      info(s"NATIVE_TYPED_TEXTURE stage=$stage targets4=true formats54=true componentChecks=$checks bindingSlots32=true twoPhysicalCredits=true fullRawTypes=true heldResults=true snapshotPoisoned=true")
    }
  }
  for(stage<-0 to 1)
  it should s"decode every compressed format through native binding selection and block coordinates stage=$stage" in {
    simulate(new NativeShaderTextureUnit(GpuConfig(lanes=4,warps=2),stage)){d=>val t=new Driver(d);roots(t.put,stage)
      var checks=0
      for(format<-54 until 64;target<-0 to 2){val unit=(format+target)%32;val v=install(t.put,t.byte,unit,format,target,stage)
        d.io.invalidate.poke(true.B);t.tick();d.io.invalidate.poke(false.B)
        for(y<-0 until 4;x<-0 until 4){coordinates(t.put,target,x=x,y=y)
          val expected=CompressedImageReference.decode(format,v.raw,x,y)
          for(c<-0 until 4){t.scalarRequest(c,unit);t.scalarResult(expected(c));checks+=1}}
        val planes=if(target==1)3 else if(target==2)6 else 1
        for(plane<-0 until planes){val y=if(target==2)6 else 4
          coordinates(t.put,target,x=6,y=y,layer=if(target==1)plane else 0,face=if(target==2)plane else 0)
          val expected=CompressedImageReference.decode(format,v.raw,2,y&3)
          for(c<-0 until 4){t.scalarRequest(c,unit);t.scalarResult(expected(c));checks+=1}}
        coordinates(t.put,target,x=7);t.scalarRequest(0,unit);t.scalarResult(fault=true)
      }
      for(format<-54 until 64){install(t.put,t.byte,0,format,3,stage);coordinates(t.put,3)
        d.io.invalidate.poke(true.B);t.tick();d.io.invalidate.poke(false.B);val before=t.memory.seen.size
        t.scalarRequest(0);t.scalarResult(fault=true)
        assert(!t.memory.seen.drop(before).exists(r=>r.address>=0x20000 && r.address<0x21000),"compressed3D must fail before data")}
      info(s"NATIVE_COMPRESSED_TEXTURE stage=$stage formats10=true targets2DArrayCube=true blockCoordinates16=true NPOT7x5And7x7=true allLayersAndFaces=true outOfExtentRejected=true volumeRejected=true componentChecks=$checks")
    }
  }
  for(stage<-0 to 1)
  it should s"apply image-view swizzle after every native format decode with stage=$stage" in {
    simulate(new NativeShaderTextureUnit(GpuConfig(lanes=4,warps=2),stage)){d=>val t=new Driver(d);roots(t.put,stage);var cases=0
      for(format<-0 until 64;selectors<-Seq(Seq(3,2,1,0),Seq(5,4,0,5),Seq(0,0,0,0))){
        val unit=format%32;val target=if(format>=49)format%3 else format%4
        val v=install(t.put,t.byte,unit,format,target,stage);coordinates(t.put,target)
        t.put(images+unit*128+23*4,1);selectors.zipWithIndex.foreach{case(s,c)=>t.put(images+unit*128+(24+c)*4,s)}
        d.io.invalidate.poke(true.B);t.tick();d.io.invalidate.poke(false.B)
        val kind=if(format>=54)0 else ImageFormatReference.format(ImageFormatReference.names(format)).kind
        val decoded=if(format>=54)CompressedImageReference.decode(format,v.raw,1,1)else v.expected
        val options=decoded++Seq(BigInt(0),if(kind==0)BigInt(0x3f800000L)else BigInt(1))
        for(c<-0 until 4){t.scalarRequest(c,unit);t.scalarResult(options(selectors(c)));cases+=1}
      }
      info(s"NATIVE_IMAGE_SWIZZLE stage=$stage formats64=true maps3=true componentChecks=$cases rawSignedUnsignedFloatAndCompressed=true capturedResult=true")
    }
  }
  for(lanes<-Seq(16,32))
  it should s"preserve explicit fetch vector width and high masked lanes for lanes=$lanes" in {
    simulate(new NativeShaderTextureUnit(GpuConfig(lanes=lanes,warps=2))){d=>val t=new Driver(d);roots(t.put)
      val views=(0 until lanes).map(u=>install(t.put,t.byte,u,10,raw=BigInt(128+u)))
      val pointers=(0 until lanes).map(u=>packet+u*64)
      for(a<-pointers)coordinates(t.put,address=a)
      val all=(BigInt(1)<<lanes)-1;val mask=BigInt("a5a5a5a5",16)&all
      t.vectorRequest(0,mask,(0 until lanes),pointers)
      t.vectorResult((0 until lanes).map(u=>if(mask.testBit(u))views(u).expected(0)else BigInt(0xabc00000L+u)))
      t.vectorRequest(3,all,(0 until lanes),pointers);t.vectorResult(views.map(_.expected(3)))
      t.scalarRequest(0,lanes-1,pointers.last);t.scalarResult(views.last.expected(0))
      val compressed=(0 until lanes).map(u=>install(t.put,t.byte,u,54+u%10,raw=BigInt("fedcba98765432100123456789abcdef",16)+u))
      d.io.invalidate.poke(true.B);t.tick();d.io.invalidate.poke(false.B)
      t.vectorRequest(0,mask,(0 until lanes),pointers)
      t.vectorResult((0 until lanes).map(u=>if(mask.testBit(u))compressed(u).expected(0)else BigInt(0xabc00000L+u)))
      t.vectorRequest(3,all,(0 until lanes),pointers);t.vectorResult(compressed.map(_.expected(3)))
      info(s"NATIVE_TYPED_TEXTURE_WIDTH lanes=$lanes explicitFetchNoDerivatives=true highMaskBits=true allLaneBindings=true inactiveOldVd=true scalarLastBinding=true twoCredits=true")
    }
  }

}
