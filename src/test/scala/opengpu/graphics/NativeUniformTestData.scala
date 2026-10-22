package opengpu.graphics

/** Independent memory records and instruction words for native uniform tests. */
private[graphics] object NativeUniformTestData {
  val root=BigInt(0x24000);val table=BigInt(0x25000);val defaultView=BigInt(0x25800)
  def base(index: Int): BigInt=BigInt(0x30000)+index*16384
  def value(index: Int,offset: Int): BigInt=(BigInt(0xa1000000L)^(BigInt(index)<<16)^offset)&0xffffffffL
  def queries(stage: Int): Seq[(Int,Int)]=(0 until 12).map(_->16380)++Seq(31->0,31->(Seq(4096,3584)(stage)-4))
  def expected(stage: Int): Seq[BigInt]=queries(stage).map{case(block,offset)=>value(if(block==31)31 else stage*12+block,offset)}
  private def header(magic: BigInt): Vector[BigInt]=Vector[BigInt](magic,64,1,7,8,9)++Vector.fill(10)(BigInt(0))
  private def put33(v: Vector[BigInt],word: Int,n: BigInt): Vector[BigInt]=v.updated[BigInt](word,n&0xffffffffL).updated[BigInt](word+1,n>>32)
  def fixture(stage: Int,defaultBase: BigInt,put: (BigInt,BigInt)=>Unit): Unit={
    def record(address: BigInt,words: Seq[BigInt]): Unit=words.zipWithIndex.foreach{case(w,i)=>put(address+4*i,w)}
    val map=(0 until 12).map(i=>BigInt(stage*12+i))
    record(root,put33(header(BigInt("47555201",16)).updated[BigInt](6,stage).updated[BigInt](7,0xfff)
      .updated[BigInt](8,(0 until 6).map(i=>map(i)<<(5*i)).sum).updated[BigInt](9,(0 until 6).map(i=>map(i+6)<<(5*i)).sum)
      .updated[BigInt](10,table).updated[BigInt](13,defaultView).updated[BigInt](14,1),11,table+1536))
    def view(index: Int,data: BigInt,bytes: Int): Vector[BigInt]=
      put33(put33(header(BigInt("47554201",16)).updated[BigInt](6,index).updated[BigInt](7,3).updated[BigInt](8,data)
        .updated[BigInt](11,data& ~BigInt(63)).updated[BigInt](14,1),9,data+bytes),12,(data+bytes+63)& ~BigInt(63))
    for(index<-0 until 24){record(table+index*64,view(index,base(index),16384));put(base(index),value(index,0));put(base(index)+16380,value(index,16380))}
    val bytes=Seq(4096,3584)(stage);record(defaultView,view(31,defaultBase,bytes))
    for(offset<-0 until bytes by 4)put(defaultBase+offset,value(31,offset))
  }
  private def addi(rd: Int,rs: Int,n: Int): BigInt=(BigInt(n&4095)<<20)|(BigInt(rs)<<15)|(BigInt(rd)<<7)|0x13
  private def address(rd: Int,base: Int,n: Int): Seq[BigInt]={
    val hi=(n+2048)>>12;val lo=n-(hi<<12)
    if(hi==0)Seq(addi(rd,base,lo))else Seq((BigInt(hi)<<12)|(BigInt(rd)<<7)|0x37,
      (BigInt(rd)<<20)|(BigInt(base)<<15)|(BigInt(rd)<<7)|0x33,addi(rd,rd,lo))
  }
  private def broadcast(v: Int,x: Int): BigInt=(BigInt(0x17)<<26)|(BigInt(1)<<25)|(BigInt(x)<<15)|(BigInt(4)<<12)|(BigInt(v)<<7)|0x57
  def instructions(stage: Int,stride: Int,outputArray: Int,overrideQueries: Option[Seq[(Int,Int)]]=None): Seq[BigInt]={
    (overrideQueries.getOrElse(queries(stage))).zipWithIndex.flatMap{case((block,offset),i)=>
      val setup=Seq(addi(14,0,block))++address(15,0,offset)
      val load=if(i%2==0)Seq((BigInt(7)<<25)|(BigInt(15)<<20)|(BigInt(14)<<15)|(BigInt(16)<<7)|0x0b,broadcast(5,16))
        else Seq(broadcast(3,14),broadcast(4,15),(BigInt(7)<<26)|(BigInt(1)<<25)|(BigInt(4)<<20)|(BigInt(3)<<15)|(BigInt(5)<<7)|0x2b)
      setup++load++address(6,7,(outputArray+i)*stride)++Seq((BigInt(1)<<25)|(BigInt(6)<<15)|(BigInt(6)<<12)|(BigInt(5)<<7)|0x27)
    }
  }
}
