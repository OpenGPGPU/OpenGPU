package opengpu.graphics

private[graphics] object NativeTextureFetchTestData {
  val root=BigInt(0x9000);val bindings=BigInt(0xb000);val images=BigInt(0x10000);val levels=BigInt(0x12000);val samplers=BigInt(0x14000)
  val packet=BigInt(0x80000)
  val defaultIdentity=Seq[BigInt](7,11,13)
  def words(put: (BigInt,BigInt)=>Unit,base: BigInt,data: Seq[BigInt],size: Int): Unit=
    data.padTo(size,BigInt(0)).zipWithIndex.foreach{case(v,n)=>put(base+n*4,v)}
  def roots(put: (BigInt,BigInt)=>Unit,stage: Int=0,id: Seq[BigInt]=defaultIdentity,address: BigInt=root,count: Int=32): Unit={
    words(put,address,Seq[BigInt](BigInt("47585201",16),128,1)++id++Seq[BigInt](stage,count,bindings,bindings+2048,0,images,images+4096,0,samplers,0,samplers+2048,0),32)
  }
  case class View(address: BigInt,format: Int,raw: BigInt,target: Int) {
    val name=TypedColorReference.names(format);val expected=TypedBlendReference.decode(name,raw)
  }
  def install(put: (BigInt,BigInt)=>Unit,byte: (BigInt,Int)=>Unit,unit: Int,format: Int=3,target: Int=0,stage: Int=0,
    id: Seq[BigInt]=defaultIdentity,raw: BigInt=BigInt("fedcba98765432100123456789abcdef",16)): View={
    val bytes=TypedColorReference.format(TypedColorReference.names(format)).bytes
    val base=BigInt(0x20000)+unit*4096;val data=base+60;val row=2*bytes+5;val plane=row+2*bytes
    val slice=if(target==3)plane+7 else 0;val layer=if(target==1 || target==2)plane+11 else 0
    val count=if(target==1)3 else 1;val depth=if(target==3)2 else 1
    words(put,bindings+unit*64,Seq[BigInt](BigInt("47584201",16),64,1)++id++Seq[BigInt](stage,unit,unit,unit,
      TypedColorReference.format(TypedColorReference.names(format)).kind,target),16)
    words(put,images+unit*128,Seq[BigInt](BigInt("47584901",16),128,1)++id++Seq[BigInt](unit,target,format,0,0,2,2,depth,count,
      base,base+4096,0,1,levels+unit*128,levels+(unit+1)*128,0,0),32)
    words(put,levels+unit*128,Seq[BigInt](BigInt("47584c01",16),128,1)++id++Seq[BigInt](0,target,0,base,base+4096,0,
      data,2,2,depth,count,row,0,slice,0,layer,0,0,bytes),32)
    words(put,samplers+unit*64,Seq[BigInt](BigInt("47585301",16),64,1)++id++Seq[BigInt](unit,0,0,0,0,0,0,0x41700000,0,3),16)
    val a=data+bytes+row+(if(target==3)slice else 0)+(if(target==1)2*layer else if(target==2)5*layer else 0)
    for(n<-0 until bytes)byte(a+n,((raw>>(8*n))&255).toInt)
    View(a,format,raw&((BigInt(1)<<(bytes*8))-1),target)
  }
  def coordinates(put: (BigInt,BigInt)=>Unit,target: Int=0,id: Seq[BigInt]=defaultIdentity,address: BigInt=packet,
      x: BigInt=1,y: BigInt=1,z: BigInt= -1,layer: BigInt= -1,face: BigInt= -1,level: BigInt=0): Unit={
    words(put,address,Seq[BigInt](BigInt("47585101",16),64,1)++id++Seq[BigInt](x,y,
      if(z<0){if(target==3)1 else 0}else z,if(layer<0){if(target==1)2 else 0}else layer,
      if(face<0){if(target==2)5 else 0}else face,level),16)
  }
  def scalar(component: Int,rd: Int=10,rs1: Int=5,rs2: Int=6): BigInt=
    (BigInt(2)<<25)|(BigInt(rs2)<<20)|(BigInt(rs1)<<15)|(BigInt(component)<<12)|(BigInt(rd)<<7)|0x0b
  def vector(component: Int,rd: Int=10,rs1: Int=5,rs2: Int=6,masked: Boolean=false): BigInt=
    (BigInt(2)<<26)|(if(masked)BigInt(0)else BigInt(1)<<25)|(BigInt(rs2)<<20)|(BigInt(rs1)<<15)|(BigInt(component)<<12)|(BigInt(rd)<<7)|0x2b
}
