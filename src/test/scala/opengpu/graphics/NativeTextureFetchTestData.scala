package opengpu.graphics

private[graphics] object NativeTextureFetchTestData {
  val root=BigInt(0x9000);val bindings=BigInt(0xb000);val images=BigInt(0x10000);val levels=BigInt(0x12000);val samplers=BigInt(0x14000)
  val packet=BigInt(0x80000)
  val defaultIdentity=Seq[BigInt](7,11,13)
  def words(put: (BigInt,BigInt)=>Unit,base: BigInt,data: Seq[BigInt],size: Int): Unit=
    data.padTo(size,BigInt(0)).zipWithIndex.foreach{case(v,n)=>put(base+n*4,v)}
  def roots(put: (BigInt,BigInt)=>Unit,stage: Int=0,id: Seq[BigInt]=defaultIdentity,address: BigInt=root,count: Int=32,offset: BigInt=0): Unit={
    words(put,address+offset,Seq[BigInt](BigInt("47585201",16),128,1)++id++Seq[BigInt](stage,count,bindings+offset,bindings+offset+2048,0,images+offset,images+offset+4096,0,samplers+offset,0,samplers+offset+2048,0),32)
  }
  case class View(address: BigInt,format: Int,raw: BigInt,target: Int,swizzle: Seq[Int]=Seq.empty) {
    val name=if(format<54)ImageFormatReference.names(format)else CompressedImageReference.names(format-54)
    private val decoded=if(format<54)ImageFormatReference.decode(name,raw)else CompressedImageReference.decode(format,raw)
    private val kind=if(format<54)ImageFormatReference.format(name).kind else 0
    private val options=decoded++Seq(BigInt(0),if(kind==0)BigInt(0x3f800000L)else BigInt(1))
    val expected=if(swizzle.isEmpty)decoded else swizzle.map(options)
  }
  def install(put: (BigInt,BigInt)=>Unit,byte: (BigInt,Int)=>Unit,unit: Int,format: Int=3,target: Int=0,stage: Int=0,
    id: Seq[BigInt]=defaultIdentity,raw: BigInt=BigInt("fedcba98765432100123456789abcdef",16),offset: BigInt=0,swizzle: Seq[Int]=Seq.empty): View={
    require(swizzle.isEmpty || (swizzle.length==4 && swizzle.forall(s=>s>=0 && s<=5)))
    val compressed=format>=54
    val bytes=if(compressed)CompressedImageReference.format(format).bytes else ImageFormatReference.format(ImageFormatReference.names(format)).bytes
    val width=if(compressed)7 else 2;val height=if(compressed){if(target==2)7 else 5}else 2
    val base=offset+BigInt(0x20000)+unit*4096;val data=base+60;val row=2*bytes+5;val plane=row+2*bytes
    val slice=if(target==3)plane+7 else 0;val layer=if(target==1 || target==2)plane+11 else 0
    val count=if(target==1)3 else 1;val depth=if(target==3)2 else 1
    words(put,bindings+offset+unit*64,Seq[BigInt](BigInt("47584201",16),64,1)++id++Seq[BigInt](stage,unit,unit,unit,
      (if(compressed)0 else ImageFormatReference.format(ImageFormatReference.names(format)).kind),target),16)
    words(put,images+offset+unit*128,Seq[BigInt](BigInt("47584901",16),128,1)++id++Seq[BigInt](unit,target,format,0,0,width,height,depth,count,
      base,base+4096,0,1,levels+offset+unit*128,levels+offset+(unit+1)*128,0,0),32)
    if(swizzle.nonEmpty){put(images+offset+unit*128+23*4,1)
      swizzle.zipWithIndex.foreach{case(s,c)=>put(images+offset+unit*128+(24+c)*4,s)}}
    words(put,levels+offset+unit*128,Seq[BigInt](BigInt("47584c01",16),128,1)++id++Seq[BigInt](0,target,0,base,base+4096,0,
      data,width,height,depth,count,row,0,slice,0,layer,0,(if(compressed)1 else 0),bytes),32)
    words(put,samplers+offset+unit*64,Seq[BigInt](BigInt("47585301",16),64,1)++id++Seq[BigInt](unit,0,0,0,0,0,0,0x41700000,0,3),16)
    val a=data+(if(compressed)0 else bytes+row)+(if(target==3)slice else 0)+(if(target==1)2*layer else if(target==2)5*layer else 0)
    if(compressed){
      val planes=if(target==1)3 else if(target==2)6 else if(target==3)2 else 1
      for(plane<-0 until planes;by<-0 until 2;bx<-0 until 2;n<-0 until bytes){
        val selected=data+plane*(if(target==3)slice else layer)
        byte(selected+by*row+bx*bytes+n,((raw>>(8*n))&255).toInt)
      }
    }else for(n<-0 until bytes)byte(a+n,((raw>>(8*n))&255).toInt)
    View(a,format,raw&((BigInt(1)<<(bytes*8))-1),target,swizzle)
  }
  def coordinates(put: (BigInt,BigInt)=>Unit,target: Int=0,id: Seq[BigInt]=defaultIdentity,address: BigInt=packet,
      x: BigInt=1,y: BigInt=1,z: BigInt= -1,layer: BigInt= -1,face: BigInt= -1,level: BigInt=0,offset: BigInt=0): Unit={
    words(put,address+offset,Seq[BigInt](BigInt("47585101",16),64,1)++id++Seq[BigInt](x,y,
      if(z<0){if(target==3)1 else 0}else z,if(layer<0){if(target==1)2 else 0}else layer,
      if(face<0){if(target==2)5 else 0}else face,level),16)
  }
  def scalar(component: Int,rd: Int=10,rs1: Int=5,rs2: Int=6): BigInt=
    (BigInt(2)<<25)|(BigInt(rs2)<<20)|(BigInt(rs1)<<15)|(BigInt(component)<<12)|(BigInt(rd)<<7)|0x0b
  def vector(component: Int,rd: Int=10,rs1: Int=5,rs2: Int=6,masked: Boolean=false): BigInt=
    (BigInt(2)<<26)|(if(masked)BigInt(0)else BigInt(1)<<25)|(BigInt(rs2)<<20)|(BigInt(rs1)<<15)|(BigInt(component)<<12)|(BigInt(rd)<<7)|0x2b
}
