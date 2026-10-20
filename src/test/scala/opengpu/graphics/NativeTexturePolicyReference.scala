package opengpu.graphics

/** Independent unfurled integer-index sampler reference, including packed
  * byte filtering. Words are also finite floats for the native image probe.
  */
private[graphics] object NativeTexturePolicyReference {
  def word(x: Int,y: Int): BigInt=(BigInt(0x3f)<<24)|
    (BigInt((x*29+y*17+11)&127)<<16)|(BigInt((x*43+y*31+7)&255)<<8)|BigInt((x*13+y*47+19)&255)
  def sample(w: Int,h: Int,u: Long,v: Long,wrapU: Int,wrapV: Int,signed: Boolean): BigInt={
    def axis(raw: Long,n: Int,mode: Int): (Int,Int,Int)={
      val c=if(signed && (raw&0x80000000L)!=0)raw-0x100000000L else raw
      val q=(c*n>>8)-128;val i=Math.floorDiv(q,256L)
      def index(x: Long): Int=mode match {
        case 0=>Math.floorMod(x,n.toLong).toInt
        case 1=>x.max(0).min(n-1).toInt
        case 2=>val p=Math.floorMod(x,2L*n);(if(p<n)p else 2L*n-1-p).toInt
      };(index(i),index(i+1),Math.floorMod(q,256L).toInt)
    }
    val(x0,x1,fx)=axis(u,w,wrapU);val(y0,y1,fy)=axis(v,h,wrapV)
    val taps=Seq(word(x0,y0),word(x1,y0),word(x0,y1),word(x1,y1))
    val weights=Seq((256-fx)*(256-fy),fx*(256-fy),(256-fx)*fy,fx*fy)
    (0 until 4).foldLeft(BigInt(0)){(out,c)=>
      (out<<8)|(taps.zip(weights).map{case(a,n)=>((a>>(24-c*8))&255)*n}.sum>>16)}
  }
}
