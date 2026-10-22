package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TextureImageAddressUnitSpec extends AnyFlatSpec {
  private case class View(target: Int=0,level: Int=0,allocationBase: BigInt=0x1000,
    allocationLimit: BigInt=0x10000,base: BigInt=0x103b,width: Int=7,height: Int=5,depth: Int=1,layers: Int=1,
    rowPitch: BigInt=33,slicePitch: BigInt=0,layerPitch: BigInt=0,block: Boolean=false,bytes: Int=4)
  private case class Request(view: View,x: BigInt=0,y: BigInt=0,z: BigInt=0,layer: BigInt=0,face: Int=0,tag: Int=0)
  private case class Result(tag: BigInt,level: Int,status: Int,address: BigInt=0,bytes: Int=0,bx: Int=0,by: Int=0)
  private val addressSpace=BigInt(1)<<32
  private def oracle(q: Request,p: TextureImageAddressConfig=TextureImageAddressConfig()): Result={
    val v=q.view;val array=v.target==1;val cube=v.target==2;val volume=v.target==3
    val badShape=v.width<1 || v.height<1 || v.depth<1 || v.layers<1 ||
      (if(volume)v.width>p.max3D || v.height>p.max3D || v.depth>p.max3D
       else v.width>p.max2D || v.height>p.max2D || v.depth!=1) ||
      (if(array)v.layers>p.maxLayers else v.layers!=1) || (cube && v.width!=v.height)
    val badEncoding=if(v.block)!Set(8,16)(v.bytes)else !Set(1,2,3,4,6,8,12,16)(v.bytes)
    val invalid=v.target<0 || v.target>3 || badShape || badEncoding ||
      (!volume && v.slicePitch!=0) || (!array && !cube && v.layerPitch!=0)
    val outside=q.x>=v.width || q.y>=v.height || (if(volume)q.z>=v.depth else q.z!=0) ||
      (if(array)q.layer>=v.layers else q.layer!=0) || (if(cube)q.face>=6 else q.face!=0)
    val blockSize=if(v.block)4 else 1
    val columns=(v.width+blockSize-1)/blockSize;val rows=(v.height+blockSize-1)/blockSize
    val rowBytes=BigInt(columns)*v.bytes
    val planeSpan=BigInt(rows-1)*v.rowPitch+rowBytes
    val lastPlane=if(cube)BigInt(5)*v.layerPitch else if(array)BigInt(v.layers-1)*v.layerPitch
      else if(volume)BigInt(v.depth-1)*v.slicePitch else BigInt(0)
    val end=v.base+lastPlane+planeSpan
    val offset=(q.x/blockSize)*v.bytes+(q.y/blockSize)*v.rowPitch+
      (if(volume)q.z*v.slicePitch else BigInt(0))+
      (if(array)q.layer*v.layerPitch else if(cube)BigInt(q.face)*v.layerPitch else BigInt(0))
    val bounds=v.rowPitch<rowBytes || (volume && v.slicePitch<planeSpan) ||
      ((array || cube) && v.layerPitch<planeSpan) || v.allocationBase>=v.allocationLimit ||
      v.allocationLimit>addressSpace || v.base<v.allocationBase || end>v.allocationLimit ||
      v.base+offset+v.bytes>v.allocationLimit || v.base+offset+v.bytes>addressSpace
    val status=if(invalid)1 else if(outside)2 else if(bounds)3 else 0
    if(status!=0)Result(q.tag,v.level,status)
    else Result(q.tag,v.level,0,v.base+offset,v.bytes,if(v.block)(q.x%4).toInt else 0,if(v.block)(q.y%4).toInt else 0)
  }
  private def poke(d: TextureImageAddressUnit,q: Request): Unit={
    val a=d.io.in.bits;val v=a.view;val t=q.view
    a.tag.poke(q.tag.U);a.x.poke(q.x.U);a.y.poke(q.y.U);a.z.poke(q.z.U);a.layer.poke(q.layer.U);a.face.poke(q.face.U)
    v.target.poke(t.target.U);v.level.poke(t.level.U);v.allocationBase.poke(t.allocationBase.U)
    v.allocationLimit.poke(t.allocationLimit.U);v.base.poke(t.base.U);v.texWidth.poke(t.width.U);v.texHeight.poke(t.height.U)
    v.texDepth.poke(t.depth.U);v.layers.poke(t.layers.U);v.rowPitch.poke(t.rowPitch.U);v.slicePitch.poke(t.slicePitch.U)
    v.layerPitch.poke(t.layerPitch.U);v.block4x4.poke(t.block.B);v.blockBytes.poke(t.bytes.U)
  }
  private def check(d: TextureImageAddressUnit,e: Result): Unit={
    val a=d.io.out.bits
    a.tag.expect(e.tag.U);a.level.expect(e.level.U);a.status.expect(e.status.U);a.address.expect(e.address.U)
    a.bytes.expect(e.bytes.U);a.blockX.expect(e.bx.U);a.blockY.expect(e.by.U)
  }
  private def initialize(d: TextureImageAddressUnit): Unit={
    PointLineReference.zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
  }
  private def sequential(d: TextureImageAddressUnit,qs: Seq[Request],p: TextureImageAddressConfig=TextureImageAddressConfig()): Unit={
    for((raw,index)<-qs.zipWithIndex){val q=raw.copy(tag=index);poke(d,q);d.io.in.valid.poke(true.B)
      var n=0;while(!d.io.in.ready.peek().litToBoolean && n<10){d.clock.step();n+=1};assert(n<10)
      d.clock.step();d.io.in.valid.poke(false.B);PointLineReference.zero(d.io.in.bits)
      n=0;while(!d.io.out.valid.peek().litToBoolean && n<10){d.clock.step();n+=1};assert(n<10)
      check(d,oracle(q,p));val held=d.io.out.bits.peek().litValue
      for(_<-0 until index%4){d.clock.step();d.io.out.valid.expect(true.B);assert(d.io.out.bits.peek().litValue==held)}
      d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)
    }
    d.io.busy.expect(false.B)
  }
  private def legalView(target: Int,size: Int,bytes: Int,block: Boolean): View={
    val height=if(target==2)size else (size/2+1).min(256)
    val columns=if(block)(size+3)/4 else size;val rows=if(block)(height+3)/4 else height
    val row=BigInt(columns*bytes+5);val plane=(rows-1)*row+columns*bytes
    val depth=if(target==3)size.min(256) else 1;val layers=if(target==1)256 else 1
    val slice=if(target==3)plane+17 else BigInt(0);val layer=if(target==1 || target==2)plane+29 else BigInt(0)
    val span=plane+(depth-1)*slice+(if(target==2)5 else layers-1)*layer
    View(target=target,level=size%16,width=size,height=height,depth=depth,layers=layers,
      rowPitch=row,slicePitch=slice,layerPitch=layer,block=block,bytes=bytes,allocationLimit=0x103b+span+11)
  }
  private def validRequests: Vector[Request]=(for {
    target<-0 to 3;bytes<-Vector(1,2,3,4,6,8,12,16);block<-Vector(false,true) if !block || Set(8,16)(bytes)
    size<-(if(target==3)Vector(1,7,256)else Vector(1,7,2048))
    edge<-0 to 2
  }yield {
    val v=legalView(target,size,bytes,block)
    Request(v,x=if(edge==0)0 else if(edge==1)v.width/2 else v.width-1,
      y=if(edge==0)0 else if(edge==1)v.height/2 else v.height-1,
      z=if(target==3 && edge>0)v.depth-1 else 0,layer=if(target==1 && edge>0)v.layers-1 else 0,
      face=if(target==2 && edge>0)5 else 0)
  }).toVector
  private def invalidRequests: Vector[Request]={
    val v=View();val q=Request(v)
    val malformed=Vector(v.copy(target=4),v.copy(target=7),v.copy(width=0),v.copy(height=0),v.copy(depth=0),v.copy(layers=0),
      v.copy(bytes=0),v.copy(bytes=5),v.copy(bytes=7),v.copy(bytes=31),v.copy(block=true,bytes=4),v.copy(slicePitch=1),
      v.copy(layerPitch=1),legalView(1,7,4,false).copy(layers=257),legalView(2,7,4,false).copy(height=6),
      legalView(3,256,4,false).copy(width=257),legalView(3,256,4,false).copy(depth=257),legalView(3,256,4,false).copy(height=257))
    val bounds=Vector(v.copy(rowPitch=27),v.copy(allocationLimit=v.base+3),v.copy(base=0xff0),v.copy(allocationBase=0x10000),
      v.copy(allocationLimit=addressSpace+1),v.copy(base=addressSpace-16,allocationLimit=addressSpace),
      v.copy(rowPitch=addressSpace,allocationLimit=addressSpace),
      legalView(3,7,4,false).copy(slicePitch=1),legalView(3,7,4,false).copy(slicePitch=addressSpace,allocationLimit=addressSpace),
      legalView(1,7,4,false).copy(layerPitch=1),legalView(1,7,4,false).copy(layerPitch=addressSpace,allocationLimit=addressSpace),
      legalView(2,7,4,false).copy(layerPitch=1),
      View(target=1,width=2048,height=2048,layers=256,rowPitch=8192,layerPitch=16777216,base=1,allocationBase=0,allocationLimit=addressSpace))
    malformed.map(Request(_))++bounds.map(Request(_))++Vector(q.copy(x=7),q.copy(y=5),q.copy(x=0xffffffffL),
      q.copy(y=0xffffffffL),q.copy(z=1),q.copy(layer=1),q.copy(face=1),
      Request(legalView(3,7,4,false),z=7),Request(legalView(3,7,4,false),z=0xffffffffL),
      Request(legalView(1,7,4,false),layer=256),Request(legalView(1,7,4,false),layer=0xffffffffL),
      Request(legalView(2,7,4,false),face=6),Request(legalView(2,7,4,false),face=7))
  }
  it should "address pitched image array cube and volume levels including packed and compressed block boundaries" in {
    simulate(new TextureImageAddressUnit()){d=>initialize(d)
      val boundary=validRequests.map{q=>val v=q.view;val used=v.allocationLimit-v.base-11
        val base=addressSpace-used;q.copy(view=v.copy(base=base,allocationBase=base-13,allocationLimit=addressSpace))}
      val facePixels=(for(face<-0 until 6;y<-0 until 7;x<-0 until 7)yield Request(legalView(2,7,16,true),x=x,y=y,face=face)).toVector
      val allLayers=(0 until 256).map(i=>Request(legalView(1,7,3,false),x=6,y=3,layer=i))
      val allSlices=(0 until 256).map(i=>Request(legalView(3,256,16,false),x=255,y=128,z=i))
      val large=legalView(0,16383,16,false).copy(height=16383)
      val largeSpan=BigInt(16382)*large.rowPitch+BigInt(16383)*16
      val largest=Request(large.copy(allocationLimit=large.base+largeSpan,level=15),x=16382,y=16382)
      val volumeBase=legalView(3,256,16,false).copy(height=256)
      val volumePlane=BigInt(255)*volumeBase.rowPitch+4096
      val volume=volumeBase.copy(slicePitch=volumePlane+17,
        allocationLimit=volumeBase.base+255*(volumePlane+17)+volumePlane)
      // One2048x2048 RGBA8 layer is16MiB;256 layers occupy exactly4GiB.
      val fullArray=View(target=1,width=2048,height=2048,layers=256,rowPitch=8192,layerPitch=16777216,
        base=0,allocationBase=0,allocationLimit=addressSpace)
      val requests=(validRequests++boundary++facePixels++allLayers++allSlices:+largest)++Vector(
        Request(volume,x=255,y=255,z=255),Request(fullArray,x=2047,y=2047,layer=255))
      requests.foreach(q=>assert(oracle(q).status==0,s"malformed intended-legal request: $q"));sequential(d,requests)
      info(s"TEXTURE_IMAGE_ADDRESS cases=${requests.size} targets=4 packedByteSizes=8 compressedBlocks=4x4 exact4GiBEnds=${boundary.size} dimension2048=true volume256=true arrayLayers256=true maxDimension16383=true pitchedGuards=true allSixFaces=true all256Layers=true all256Slices=true full256CubedVolume=true full2048SquaredArray256Layers=true immutableReplies=true")
    }
  }
  it should "reject malformed views out of range coordinates overlapping pitches and full level allocation escapes" in {
    simulate(new TextureImageAddressUnit()){d=>initialize(d)
      val bad=invalidRequests;assert(bad.forall(q=>oracle(q).status!=0))
      sequential(d,bad.flatMap(q=>Vector(q,Request(View()))))
      info(s"TEXTURE_IMAGE_ADDRESS_FAULT cases=${bad.size} statuses=${bad.map(q=>oracle(q).status).distinct.sorted.mkString(",")} zeroFaultAddresses=true noTruncatedCoordinateAliases=true wholeLevelBounds=true exactHealthyReuse=true")
    }
  }
  for((pipelineBounds,pipelineProducts)<-Seq((false,false),(true,false),(false,true),(true,true)))
  it should s"sustain one address each cycle and preserve every captured request through backpressure pipelineBounds=$pipelineBounds pipelineProducts=$pipelineProducts" in {
    simulate(new TextureImageAddressUnit(pipelineBounds=pipelineBounds,pipelineProducts=pipelineProducts)){d=>initialize(d)
      val good=validRequests;val bad=invalidRequests
      val requests=Vector.tabulate(512){i=>(if(i>=128 && i%7==0)bad(i%bad.size)else good(i%good.size)).copy(tag=1000+i)}.map(q=>q.copy(view=q.view.copy(level=(q.tag-1000)%16)))
      val expected=requests.map(oracle(_));var sent=0;var received=0;var cycle=0
      val inputCycles=scala.collection.mutable.ArrayBuffer.empty[Int];val outputCycles=scala.collection.mutable.ArrayBuffer.empty[Int]
      var held: Option[BigInt]=None
      while(received<requests.size && cycle<2000){
        val ready=cycle<144 || cycle%11<5;d.io.out.ready.poke(ready.B)
        d.io.in.valid.poke((sent<requests.size).B);if(sent<requests.size)poke(d,requests(sent))else PointLineReference.zero(d.io.in.bits)
        held.foreach{bits=>d.io.out.valid.expect(true.B);assert(d.io.out.bits.peek().litValue==bits)}
        val output=d.io.out.valid.peek().litToBoolean
        held=if(output && !ready)Some(d.io.out.bits.peek().litValue)else None
        if(output && ready){check(d,expected(received));outputCycles+=cycle;received+=1}
        if(sent<requests.size && d.io.in.ready.peek().litToBoolean){inputCycles+=cycle;sent+=1}
        d.clock.step();cycle+=1
      }
      assert(sent==512 && received==512);val latency=3+(if(pipelineBounds)1 else 0)+(if(pipelineProducts)1 else 0);assert(outputCycles.head-inputCycles.head==latency)
      assert(outputCycles.take(128)==(outputCycles.head until outputCycles.head+128))
      d.io.busy.expect(false.B)
      info(s"TEXTURE_IMAGE_ADDRESS_STREAM cases=$received latency=$latency pipelineBounds=$pipelineBounds pipelineProducts=$pipelineProducts steadyAddressInterval=1 elasticBackpressure=true faultRepliesOrdered=true immutableBindings=true")
    }
  }
  for((bounds,products)<-Seq((false,false),(true,false),(false,true),(true,true)))
  it should s"retain full pitch products without allocation or coordinate aliases bounds=$bounds products=$products" in {
    val p=TextureImageAddressConfig(maxLayers=65535)
    simulate(new TextureImageAddressUnit(p,pipelineBounds=bounds,pipelineProducts=products)){d=>initialize(d)
      val wide=View(target=1,width=1,height=1,layers=65535,rowPitch=4,layerPitch=64,base=0x1000,allocationLimit=0x800000)
      val pitch=(BigInt(1)<<32)+64
      val requests=Vector(Request(wide,layer=65534),
        Request(wide.copy(layerPitch=pitch,allocationLimit=addressSpace)),
        Request(View(width=1,height=2,rowPitch=pitch,allocationLimit=addressSpace)),
        Request(View(target=3,width=1,height=1,depth=2,rowPitch=4,slicePitch=pitch,allocationLimit=addressSpace)),
        Request(wide,layer=BigInt(1)<<16))
      assert(requests.map(oracle(_,p).status)==Vector(0,3,3,3,2))
      sequential(d,requests.flatMap(q=>Vector(q,Request(View()))),p)
    }
  }
  it should "enforce configured dimensions volume extent and array layers at their exact boundaries" in {
    val p=TextureImageAddressConfig(max2D=512,max3D=64,maxLayers=8)
    simulate(new TextureImageAddressUnit(p)){d=>initialize(d)
      val small=legalView(1,512,4,false).copy(layers=8)
      val volume=legalView(3,64,4,false)
      val requests=Vector(Request(small,layer=7),Request(small.copy(width=513)),Request(small.copy(layers=9)),
        Request(volume,z=63),Request(volume.copy(depth=65)),Request(volume.copy(width=65)),Request(volume.copy(height=65)),
        Request(legalView(2,512,8,true)),Request(legalView(2,513,8,true)))
      assert(requests.map(q=>oracle(q,p).status)==Vector(0,1,1,0,1,1,1,0,1))
      sequential(d,requests,p)
      info("TEXTURE_IMAGE_ADDRESS_LIMITS dimension512=true volume64=true layers8=true exactBoundaryAndPlusOne=true")
    }
  }
}
