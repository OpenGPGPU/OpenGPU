package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TextureImageAddressUnitSpec extends AnyFlatSpec {
  private case class View(target: Int=0,level: Int=0,allocationBase: BigInt=0x1000,
    allocationLimit: BigInt=0x10000,base: BigInt=0x103b,width: Int=7,height: Int=5,depth: Int=1,layers: Int=1,
    rowPitch: BigInt=33,slicePitch: BigInt=0,layerPitch: BigInt=0,block: Boolean=false,bytes: Int=4,
    bufferTexels: BigInt=0,bufferBytes: BigInt=0,samples: Int=0,samplePitch: BigInt=0)
  private case class Request(view: View,x: BigInt=0,y: BigInt=0,z: BigInt=0,layer: BigInt=0,face: Int=0,tag: Int=0,sample: Int=0)
  private case class Result(tag: BigInt,level: Int,status: Int,address: BigInt=0,bytes: Int=0,bx: Int=0,by: Int=0)
  private val addressSpace=BigInt(1)<<32
  private def oracle(q: Request,p: TextureImageAddressConfig=TextureImageAddressConfig()): Result={
    if(q.view.target==5){
      val v=q.view
      val shape=v.level==0 && v.width==1 && v.height==1 && v.depth==1 && v.layers==1 &&
        v.rowPitch==0 && v.slicePitch==0 && v.layerPitch==0 && !v.block && Set(1,2,4,8,12,16)(v.bytes) &&
        v.bufferBytes>0 && v.bufferBytes<=addressSpace && v.bufferTexels>0
      val count=if(v.bytes>0)(v.bufferBytes/v.bytes).min(BigInt(p.maxBufferTexels))else BigInt(0)
      val invalid= !shape || v.bufferTexels!=count
      val outside=q.x>=v.bufferTexels || q.y!=0 || q.z!=0 || q.layer!=0 || q.face!=0
      val selected=v.base+q.x*v.bytes
      val bounds=v.allocationBase>=v.allocationLimit || v.allocationLimit>addressSpace || v.base<v.allocationBase ||
        v.base+v.bufferBytes>v.allocationLimit || selected+v.bytes>v.allocationLimit || selected+v.bytes>addressSpace
      val status=if(invalid)1 else if(outside)2 else if(bounds)3 else 0
      return if(status!=0)Result(q.tag,v.level,status)else Result(q.tag,v.level,0,selected,v.bytes)
    }
    val v=q.view;val array=v.target==1;val cube=v.target==2;val volume=v.target==3;val cubeArray=v.target==4
    val msaa=v.target==6 || v.target==7;val msArray=v.target==7
    val layered=array || cube || cubeArray || msArray;val squareCube=cube || cubeArray
    val cubePlanes=BigInt(v.layers)*TextureImageTarget.cubeFaces
    val badShape=v.width<1 || v.height<1 || v.depth<1 || v.layers<1 ||
      (if(volume)v.width>p.max3D || v.height>p.max3D || v.depth>p.max3D
       else v.width>p.max2D || v.height>p.max2D || v.depth!=1) ||
      (if(array || cubeArray || msArray)v.layers>p.maxLayers || (cubeArray && cubePlanes>((BigInt(1)<<16)-1))
       else v.layers!=1) || (squareCube && v.width!=v.height)
    val badEncoding=if(v.block)!Set(8,16)(v.bytes)else !Set(1,2,3,4,6,8,12,16)(v.bytes)
    val msShape=if(msaa)(v.samples==2 || v.samples==4) && v.samplePitch>0 && v.level==0 && !v.block
      else v.samples==0 && v.samplePitch==0
    val invalid=v.target<0 || v.target>7 || badShape || badEncoding || !msShape || v.bufferTexels!=0 || v.bufferBytes!=0 ||
      (!volume && v.slicePitch!=0) || (!layered && v.layerPitch!=0)
    val outside=q.x>=v.width || q.y>=v.height || (if(volume)q.z>=v.depth else q.z!=0) ||
      (if(array || cubeArray || msArray)q.layer>=v.layers else q.layer!=0) ||
      (if(squareCube)q.face>=TextureImageTarget.cubeFaces else q.face!=0) ||
      (if(msaa)q.sample>=v.samples else q.sample!=0)
    val blockSize=if(v.block)4 else 1
    val columns=(v.width+blockSize-1)/blockSize;val rows=(v.height+blockSize-1)/blockSize
    val rowBytes=BigInt(columns)*v.bytes
    val planeSpan=BigInt(rows-1)*v.rowPitch+rowBytes
    val lastSample=if(msaa)BigInt(v.samples-1)*v.samplePitch else BigInt(0)
    val lastPlane=if(cubeArray)(cubePlanes-1)*v.layerPitch
      else if(cube)BigInt(TextureImageTarget.cubeFaces-1)*v.layerPitch
      else if(array || msArray)BigInt(v.layers-1)*v.layerPitch
      else if(volume)BigInt(v.depth-1)*v.slicePitch else BigInt(0)
    val end=v.base+lastPlane+planeSpan+lastSample
    val planeIndex=if(cubeArray)q.layer*TextureImageTarget.cubeFaces+q.face
      else if(array || msArray)q.layer else if(cube)BigInt(q.face) else BigInt(0)
    val offset=(q.x/blockSize)*v.bytes+(q.y/blockSize)*v.rowPitch+
      (if(volume)q.z*v.slicePitch else BigInt(0))+planeIndex*v.layerPitch+
      (if(msaa)BigInt(q.sample)*v.samplePitch else BigInt(0))
    val msBounds=msaa && (v.samplePitch<planeSpan || (msArray && v.layerPitch<v.samplePitch*v.samples))
    val bounds=msBounds || v.rowPitch<rowBytes || (volume && v.slicePitch<planeSpan) ||
      (layered && v.layerPitch<planeSpan) || v.allocationBase>=v.allocationLimit ||
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
    v.bufferTexels.poke(t.bufferTexels.U);v.bufferBytes.poke(t.bufferBytes.U)
    v.samples.poke(t.samples.U);v.samplePitch.poke(t.samplePitch.U);a.sample.poke(q.sample.U)
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
  private def legalView(target: Int,size: Int,bytes: Int,block: Boolean,layerCount: Int= -1): View={
    val height=if(target==2 || target==4)size else (size/2+1).min(256)
    val columns=if(block)(size+3)/4 else size;val rows=if(block)(height+3)/4 else height
    val row=BigInt(columns*bytes+5);val plane=(rows-1)*row+columns*bytes
    val depth=if(target==3)size.min(256) else 1
    val layers=if(layerCount>=0)layerCount else if(target==1)256 else if(target==4)4 else 1
    val slice=if(target==3)plane+17 else BigInt(0)
    val layer=if(target==1 || target==2 || target==4)plane+29 else BigInt(0)
    val planes=if(target==4)layers*TextureImageTarget.cubeFaces else if(target==2)TextureImageTarget.cubeFaces else layers
    val span=plane+(depth-1)*slice+(planes-1)*layer
    View(target=target,level=size%16,width=size,height=height,depth=depth,layers=layers,
      rowPitch=row,slicePitch=slice,layerPitch=layer,block=block,bytes=bytes,allocationLimit=0x103b+span+11)
  }
  private def validRequests: Vector[Request]=(for {
    target<-0 to 4;bytes<-Vector(1,2,3,4,6,8,12,16);block<-Vector(false,true) if !block || Set(8,16)(bytes)
    size<-(if(target==3)Vector(1,7,256)else Vector(1,7,2048))
    edge<-0 to 2
  }yield {
    val v=legalView(target,size,bytes,block)
    Request(v,x=if(edge==0)0 else if(edge==1)v.width/2 else v.width-1,
      y=if(edge==0)0 else if(edge==1)v.height/2 else v.height-1,
      z=if(target==3 && edge>0)v.depth-1 else 0,
      layer=if((target==1 || target==4) && edge>0)v.layers-1 else 0,
      face=if((target==2 || target==4) && edge>0)5 else 0)
  }).toVector
  private def invalidRequests: Vector[Request]={
    val v=View();val q=Request(v)
    val malformed=Vector(v.copy(target=5),v.copy(target=7),v.copy(width=0),v.copy(height=0),v.copy(depth=0),v.copy(layers=0),
      v.copy(bytes=0),v.copy(bytes=5),v.copy(bytes=7),v.copy(bytes=31),v.copy(block=true,bytes=4),v.copy(slicePitch=1),
      v.copy(layerPitch=1),legalView(1,7,4,false).copy(layers=257),legalView(2,7,4,false).copy(height=6),
      legalView(4,7,4,false).copy(height=6),legalView(4,7,4,false).copy(layers=257),
      legalView(3,256,4,false).copy(width=257),legalView(3,256,4,false).copy(depth=257),legalView(3,256,4,false).copy(height=257))
    val bounds=Vector(v.copy(rowPitch=27),v.copy(allocationLimit=v.base+3),v.copy(base=0xff0),v.copy(allocationBase=0x10000),
      v.copy(allocationLimit=addressSpace+1),v.copy(base=addressSpace-16,allocationLimit=addressSpace),
      v.copy(rowPitch=addressSpace,allocationLimit=addressSpace),
      legalView(3,7,4,false).copy(slicePitch=1),legalView(3,7,4,false).copy(slicePitch=addressSpace,allocationLimit=addressSpace),
      legalView(1,7,4,false).copy(layerPitch=1),legalView(1,7,4,false).copy(layerPitch=addressSpace,allocationLimit=addressSpace),
      legalView(2,7,4,false).copy(layerPitch=1),legalView(4,7,4,false).copy(layerPitch=1),
      View(target=1,width=2048,height=2048,layers=256,rowPitch=8192,layerPitch=16777216,base=1,allocationBase=0,allocationLimit=addressSpace))
    malformed.map(Request(_))++bounds.map(Request(_))++Vector(q.copy(x=7),q.copy(y=5),q.copy(x=0xffffffffL),
      q.copy(y=0xffffffffL),q.copy(z=1),q.copy(layer=1),q.copy(face=1),
      Request(legalView(3,7,4,false),z=7),Request(legalView(3,7,4,false),z=0xffffffffL),
      Request(legalView(1,7,4,false),layer=256),Request(legalView(1,7,4,false),layer=0xffffffffL),
      Request(legalView(2,7,4,false),face=6),Request(legalView(2,7,4,false),face=7),
      Request(legalView(4,7,4,false),layer=4),Request(legalView(4,7,4,false),face=6),
      Request(legalView(4,7,4,false),layer=0xffffffffL))
  }
  it should "address pitched image array cube and volume levels including packed and compressed block boundaries" in {
    simulate(new TextureImageAddressUnit()){d=>initialize(d)
      val boundary=validRequests.map{q=>val v=q.view;val used=v.allocationLimit-v.base-11
        val base=addressSpace-used;q.copy(view=v.copy(base=base,allocationBase=base-13,allocationLimit=addressSpace))}
      val facePixels=(for(face<-0 until 6;y<-0 until 7;x<-0 until 7)yield Request(legalView(2,7,16,true),x=x,y=y,face=face)).toVector
      val cubeArrayPixels=(for(layer<-0 until 4;face<-0 until 6;y<-0 until 7;x<-0 until 7)yield
        Request(legalView(4,7,16,true),x=x,y=y,layer=layer,face=face)).toVector
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
      val requests=(validRequests++boundary++facePixels++cubeArrayPixels++allLayers++allSlices:+largest)++Vector(
        Request(volume,x=255,y=255,z=255),Request(fullArray,x=2047,y=2047,layer=255),
        Request(legalView(4,7,4,false),x=6,y=3,layer=3,face=5))
      requests.foreach(q=>assert(oracle(q).status==0,s"malformed intended-legal request: $q"));sequential(d,requests)
      info(s"TEXTURE_IMAGE_ADDRESS cases=${requests.size} targets=5 cubeArray=true packedByteSizes=8 compressedBlocks=4x4 exact4GiBEnds=${boundary.size} dimension2048=true volume256=true arrayLayers256=true maxDimension16383=true pitchedGuards=true allSixFaces=true cubeArrayFourCubes=true all256Layers=true all256Slices=true full256CubedVolume=true full2048SquaredArray256Layers=true immutableReplies=true")
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
      val cubeArray=legalView(4,512,4,false,layerCount=8)
      val requests=Vector(Request(small,layer=7),Request(small.copy(width=513)),Request(small.copy(layers=9)),
        Request(volume,z=63),Request(volume.copy(depth=65)),Request(volume.copy(width=65)),Request(volume.copy(height=65)),
        Request(legalView(2,512,8,true)),Request(legalView(2,513,8,true)),
        Request(cubeArray,layer=7,face=5),Request(cubeArray.copy(layers=9)),Request(cubeArray.copy(height=513)))
      assert(requests.map(q=>oracle(q,p).status)==Vector(0,1,1,0,1,1,1,0,1,0,1,1))
      sequential(d,requests,p)
      info("TEXTURE_IMAGE_ADDRESS_LIMITS dimension512=true volume64=true layers8=true cubeArray=true exactBoundaryAndPlusOne=true")
    }
  }
  it should "address multisample sample planes with sample and layer bounds" in {
    simulate(new TextureImageAddressUnit){d=>initialize(d)
      val base=View(target=6,level=0,width=4,height=4,depth=1,layers=1,rowPitch=16,slicePitch=0,layerPitch=0,
        bytes=4,samples=4,samplePitch=64,allocationBase=0x1000,base=0x103b,allocationLimit=0x103b+1024)
      val array=base.copy(target=7,layers=3,layerPitch=4*64)
      val requests=for(s<-0 until 4;x<-Seq(0,3);y<-Seq(0,3))yield Request(base,x=x,y=y,sample=s)
      val arrayRequests=for(l<-0 until 3;s<-Seq(0,3);x<-Seq(0,3))yield Request(array,x=x,y=0,layer=l,sample=s)
      sequential(d,requests++arrayRequests)
      val rejected=Seq(
        Request(base.copy(samples=3)),
        Request(base.copy(samples=0,samplePitch=0)),
        Request(base.copy(samplePitch=0)),
        Request(base.copy(level=1)),
        Request(base.copy(block=true,bytes=8)),
        Request(base,sample=4),
        Request(array.copy(layerPitch=128)),
        Request(array,layer=3),
        Request(array,layer=0,sample=4))
      sequential(d,rejected)
      info("IMAGE_ADDRESS_MSAA samples2x4=true samplePlanePitch=true arrayLayers=true sampleBounds=true")
    }
  }
  it should "address buffer ranges beyond image dimensions with full count products and exclusive 4GiB ends" in {
    simulate(new TextureImageAddressUnit(pipelineBounds=true,pipelineProducts=true)){d=>initialize(d)
      val buffers=Vector(1,2,4,8,12,16).map{bytes=>
        val count=BigInt(65536);val range=count*bytes+bytes-1
        View(target=5,allocationBase=0,allocationLimit=addressSpace,base=addressSpace-range,
          width=1,height=1,rowPitch=0,bytes=bytes,bufferTexels=count,bufferBytes=range)
      }
      val valid=buffers.flatMap(v=>Vector(Request(v),Request(v,x=16384),Request(v,x=65535)))
      val full=buffers.head.copy(base=0,bufferTexels=1<<27,bufferBytes=addressSpace)
      val last=full.copy(base=addressSpace-16,bytes=16,bufferTexels=1,bufferBytes=16)
      val bad=buffers.flatMap(v=>Vector(Request(v,x=65536),Request(v,x=0xffffffffL),Request(v,y=1),
        Request(v.copy(bufferBytes=65536*v.bytes-1)),Request(v.copy(rowPitch=1)),Request(v.copy(level=1))))++
        Vector(Request(full.copy(base=1)),Request(full.copy(bufferTexels=(1<<27)-1)),
          Request(last.copy(bufferTexels=0)),Request(last.copy(bytes=3)),Request(last.copy(block=true)),
          Request(View(bufferTexels=1)),Request(last,z=1),Request(last,layer=1),Request(last,face=1))
      val good=valid++Vector(Request(full,x=(1<<27)-1),Request(last))
      assert(good.forall(q=>oracle(q).status==0) && bad.forall(q=>oracle(q).status!=0))
      sequential(d,good++bad.flatMap(q=>Vector(q,Request(last))))
      info("BUFFER_TEXTURE_ADDRESS count65536=true independent2DLimit=true rangeFloor=true clampedCount=true fullByteRange=true exact4GiB=true byteSizes1_2_4_8_12_16=true heldFaultsAndHealthyReuse=true")
    }
  }
}
