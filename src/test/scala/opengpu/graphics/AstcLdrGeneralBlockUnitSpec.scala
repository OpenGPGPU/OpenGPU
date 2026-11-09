package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class AstcLdrGeneralBlockUnitSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  private val wScale=Vector(1,8,2,7,13,3,10,18,4,13,23,5)
  private val wRounds=Vector(0,2,0,1,2,0,1,2,0,1,2,0)
  private val levels=Seq(2,3,4,5,6,8,10,12,16,20,24,32,40,48,64,80,96,128,160,192,256)
  private val bitsPer=Seq(1,0,2,0,1,3,1,2,4,2,3,5,3,4,6,4,5,7,5,6,8)
  private val tritPer=Seq(0,1,0,0,1,0,0,1,0,0,1,0,0,1,0,0,1,0,0,1,0)
  private val quintPer=Seq(0,0,0,1,0,0,1,0,0,1,0,0,1,0,0,1,0,0,1,0,0)
  private val infillScale=Map(4->342,5->256,6->205,7->171,8->146,9->128,10->114,11->102,12->93)
  private def rev(v: BigInt): BigInt={var o=BigInt(0);for(i<-0 until 128 if v.testBit(i))o|=BigInt(1)<<(127-i);o}
  private def need(n: Int,q: Int): Int=(n*bitsPer(q))+(if(tritPer(q)!=0)(n*8+4)/5 else 0)+(if(quintPer(q)!=0)(n*7+2)/3 else 0)
  private def hash52(p0: Long): Long={var p=p0&0xffffffffL
    p^=p>>>15;p=(p-(p<<17))&0xffffffffL;p=(p+(p<<7))&0xffffffffL;p=(p+(p<<4))&0xffffffffL
    p^=p>>>5;p=(p+(p<<16))&0xffffffffL;p^=p>>>7;p^=p>>>3;p^=(p<<6)&0xffffffffL;p^=p>>>17;p&0xffffffffL}
  private def partition(seed0: Int,pc: Int,bw: Int,bh: Int,x0: Int,y0: Int): Int={
    if(pc==1)return 0
    val small=if(bw*bh<31)1 else 0
    val x=if(small==1)x0*2 else x0;val y=if(small==1)y0*2 else y0
    val seed=(seed0+(pc-1)*1024)&0xffffffffL;val r=hash52(seed)
    def nib(s: Int)=((r>>>s)&0xF).toInt
    var s1=nib(0);var s2=nib(4);var s3=nib(8);var s4=nib(12);var s5=nib(16);var s6=nib(20)
    var s7=nib(24);var s8=nib(28);var s9=nib(18);var s10=nib(22);var s11=nib(26)
    var s12=(((r>>>30)|(r<<2))&0xF).toInt
    s1*=s1;s2*=s2;s3*=s3;s4*=s4;s5*=s5;s6*=s6;s7*=s7;s8*=s8;s9*=s9;s10*=s10;s11*=s11;s12*=s12
    val (sh1,sh2)=if((seed&1)!=0){(if((seed&2)!=0)4 else 5,if(pc==3)6 else 5)}else{(if(pc==3)6 else 5,if((seed&2)!=0)4 else 5)}
    val sh3=if((seed&0x10)!=0)sh1 else sh2
    s1>>=sh1;s2>>=sh2;s3>>=sh1;s4>>=sh2;s5>>=sh1;s6>>=sh2;s7>>=sh1;s8>>=sh2
    s9>>=sh3;s10>>=sh3;s11>>=sh3;s12>>=sh3
    val a=(s1*x+s2*y+((r>>>14)&0xffffffffL).toInt)&0x3F;val b=(s3*x+s4*y+((r>>>10)&0xffffffffL).toInt)&0x3F
    val c0=(s5*x+s6*y+((r>>>6)&0xffffffffL).toInt)&0x3F;val d0=(s7*x+s8*y+((r>>>2)&0xffffffffL).toInt)&0x3F
    val c=if(pc<3)0 else c0;val d=if(pc<4)0 else d0
    if(a>=b && a>=c && a>=d)0 else if(b>=c && b>=d)1 else if(c>=d)2 else 3
  }
  private def infill(weights: Seq[Int],bw: Int,bh: Int,gx: Int,gy: Int,x: Int,y: Int,plane: Int,interleaved: Boolean): Int={
    val ds=infillScale(bw);val dt=infillScale(bh)
    val gs=(ds*x*(gx-1)+32)>>6;val gt=(dt*y*(gy-1)+32)>>6
    val js=gs>>4;val fs=gs&15;val jt=gt>>4;val ft=gt&15
    def w(i: Int)=if(i<64)weights(i)else 0
    val step=if(interleaved)2 else 1;val base=if(interleaved)plane else 0
    val v0=js*step+jt*gx*step+base
    val p00=w(v0);val p01=w(v0+step);val p10=w(v0+gx*step);val p11=w(v0+gx*step+step)
    val w11=(fs*ft+8)>>4;val w10=ft-w11;val w01=fs-w11;val w00=16-fs-ft+w11
    (p00*w00+p01*w01+p10*w10+p11*w11+8)>>4
  }
  private def transfer(a: Int,b: Int): (Int,Int)={
    val b2=(b>>1)|(a&0x80);var a2=(a>>1)&0x3F;if((a2&0x20)!=0)a2-=0x40;(a2,b2)
  }
  private def blue(r: Int,g: Int,b: Int,a: Int)=Vector((r+b)>>1,(g+b)>>1,b,a)
  private def c8(v: Int)=math.max(0,math.min(255,v))
  private def endpoint(cem: Int,v: Seq[Int],w0: Int,w1: Int,dual: Boolean,comp: Int,srgb: Boolean): Vector[Int]={
    val legal=Set(0,1,4,5,6,8,9,10,12,13)
    if(!legal(cem))return Vector(0,0,0,0)
    val (t0a,_)=transfer(v(0),v(1));val (t1a,_)=transfer(v(2),v(3));val (t2a,_)=transfer(v(4),v(5));val (t3a,_)=transfer(v(6),v(7))
    val s0=v(0)+v(2)+v(4);val s1=v(1)+v(3)+v(5)
    var (a,b): (Vector[Int],Vector[Int])=cem match{
      case 0=>(Vector(v(0),v(0),v(0),255),Vector(v(1),v(1),v(1),255))
      case 1=>{val l0=(v(0)>>2)|(v(1)&0xC0);val l1=math.min(255,l0+(v(1)&0x3F));(Vector(l0,l0,l0,255),Vector(l1,l1,l1,255))}
      case 4=>(Vector(v(0),v(0),v(0),v(2)),Vector(v(1),v(1),v(1),v(3)))
      case 5=>(Vector(v(0),v(0),v(0),v(2)),Vector(v(0)+t0a,v(0)+t0a,v(0)+t0a,v(2)+t1a))
      case 6=>(Vector((v(0)*v(3))>>8,(v(1)*v(3))>>8,(v(2)*v(3))>>8,255),Vector(v(0),v(1),v(2),255))
      case 8=>if(s1>=s0)(Vector(v(0),v(2),v(4),255),Vector(v(1),v(3),v(5),255))else(blue(v(1),v(3),v(5),255),blue(v(0),v(2),v(4),255))
      case 9=>if(t0a+t1a+t2a>=0)(Vector(v(0),v(2),v(4),255),Vector(v(0)+t0a,v(2)+t1a,v(4)+t2a,255))else(blue(v(0)+t0a,v(2)+t1a,v(4)+t2a,255),blue(v(0),v(2),v(4),255))
      case 10=>(Vector((v(0)*v(3))>>8,(v(1)*v(3))>>8,(v(2)*v(3))>>8,v(4)),Vector(v(0),v(1),v(2),v(5)))
      case 12=>if(s1>=s0)(Vector(v(0),v(2),v(4),v(6)),Vector(v(1),v(3),v(5),v(7)))else(blue(v(1),v(3),v(5),v(7)),blue(v(0),v(2),v(4),v(6)))
      case _=>if(t0a+t1a+t2a>=0)(Vector(v(0),v(2),v(4),v(6)),Vector(v(0)+t0a,v(2)+t1a,v(4)+t2a,v(6)+t3a))else(blue(v(0)+t0a,v(2)+t1a,v(4)+t2a,v(6)+t3a),blue(v(0),v(2),v(4),v(6)))
    }
    if(cem==5||cem==9||cem==13){a=a.map(c8);b=b.map(c8)}
    def interp(w: Int,ch: Int)={
      val ca=if(srgb && ch<3)(a(ch)<<8)|0x80 else (a(ch)<<8)|a(ch)
      val cb=if(srgb && ch<3)(b(ch)<<8)|0x80 else (b(ch)<<8)|b(ch)
      ((ca*(64-w)+cb*w+32)>>6)
    }
    val base=(0 until 4).map(c=>interp(w0,c)).toVector
    if(dual){val second=interp(w1,comp);base.updated(comp,second)}else base
  }
  private case class Mode(ok: Boolean,void: Boolean,gx: Int,gy: Int,dual: Boolean,quant: Int,bits: Int,parts: Int)
  private def decodeMode(block: BigInt,bw: Int,bh: Int): Mode={
    val bm=(block&0x7ff).toInt;val parts=((block>>11)&3).toInt+1
    if((bm&0x1ff)==0x1fc)return Mode(true,true,0,0,false,0,0,parts)
    var base=(bm>>4)&1;var h=(bm>>9)&1;var d=(bm>>10)&1
    val a=(bm>>5)&3;var gx=0;var gy=0;var bad=false
    if((bm&3)!=0){
      base|=(bm&3)<<1;val b=(bm>>7)&3
      (bm>>2)&3 match{
        case 0=>gx=b+4;gy=a+2;case 1=>gx=b+8;gy=a+2;case 2=>gx=a+2;gy=b+8
        case _=>val c=b&1;if((bm&0x100)!=0){gx=c+2;gy=a+2}else{gx=a+2;gy=c+6}}
    }else{
      base|=((bm>>2)&3)<<1
      if(((bm>>2)&3)==0)bad=true else{
        val b=(bm>>9)&3
        (bm>>7)&3 match{
          case 0=>gx=12;gy=a+2;case 1=>gx=a+2;gy=12
          case 2=>gx=a+6;gy=b+6;d=0;h=0
          case _=>(bm>>5)&3 match{case 0=>gx=6;gy=10;case 1=>gx=10;gy=6;case _=>bad=true}}}
    }
    val count=gx*gy*(d+1);val q=(base-2)+6*h;val dual=d!=0
    val bits=if(q>=0&&q<12)(wScale(q)*count+wRounds(q)*2)/(wRounds(q)*2+1)else 1024
    val ok= !bad && q>=0 && q<12 && count<=64 && bits>=24 && bits<=96 && gx<=bw && gy<=bh && !(dual&&parts==4)
    Mode(ok,false,gx,gy,dual,q,bits,if(ok)parts else 0)
  }
  private def decodeGrid(block: BigInt,gx: Int,gy: Int,dual: Boolean,q: Int): (Boolean,Seq[Int])={
    val count=gx*gy*(if(dual)2 else 1)
    if(q>11||count<=0||count>64)return(false,Seq.fill(64)(0))
    val bits=Seq(1,0,2,0,1,3,1,2,4,2,3,5)(q);val trits=Seq(0,1,0,0,1,0,0,1,0,0,1,0)(q);val quints=Seq(0,0,0,1,0,0,1,0,0,1,0,0)(q)
    val st=rev(block&((BigInt(1)<<128)-1));var off=0;var lc=0;var hc=0;val res=Array.fill(count)(0);val tq=Array.fill(22)(0)
    def rd(n: Int): Int={val v=((st>>off)&((BigInt(1)<<n)-1)).toInt;off+=n;v}
    for(i<-0 until count){
      res(i)=rd(bits)
      if(trits!=0){val br=Seq(2,2,1,2,1)(lc);val sh=Seq(0,2,4,5,7)(lc);val nx=Seq(1,2,3,4,0)(lc);val inc=lc==4;tq(hc)|=rd(br)<<sh;if(inc)hc+=1;lc=nx}
      if(quints!=0){val br=Seq(3,2,2)(lc);val sh=Seq(0,3,5)(lc);val nx=Seq(1,2,0)(lc);val inc=lc==2;tq(hc)|=rd(br)<<sh;if(inc)hc+=1;lc=nx}
    }
    def tritAt(blk: Int,k: Int): Int={val T=blk&0xff;val sp=((T>>2)&7)==7
      val c=if(sp)(((T>>5)&7)<<2)|(T&3)else T&0x1f
      val t4=if(sp)2 else if(((T>>5)&3)==3)2 else (T>>7)&1
      val t3=if(sp)2 else if(((T>>5)&3)==3)(T>>7)&1 else (T>>5)&3
      val t2=if((c&3)==3)2 else if(((c>>2)&3)==3)2 else (c>>4)&1
      val t1=if((c&3)==3)(c>>4)&1 else if(((c>>2)&3)==3)2 else (c>>2)&3
      val t0=if((c&3)==3)(((c>>3)&1)<<1)|(((c>>2)&1)& ~((c>>3)&1))else if(((c>>2)&3)==3)c&3 else (((c>>1)&1)<<1)|((c&1)& ~((c>>1)&1))
      Seq(t0,t1,t2,t3,t4)(k)}
    def quintAt(blk: Int,k: Int): Int={val Q=blk&0x7f;val sp=((Q>>1)&3)==3&&((Q>>5)&3)==0
      val q2=if(sp)(((Q&1)<<2)|((((Q>>4)&1)& ~(Q&1))<<1)|(((Q>>3)&1)& ~(Q&1)))else if(((Q>>1)&3)==3)4 else (Q>>5)&3
      val c=if(sp)0 else if(((Q>>1)&3)==3)((((Q>>3)&3)<<3)|(((~(Q>>5))&3)<<1)|(Q&1))else Q&0x1f
      val q1=if(sp)4 else if((c&7)==5)4 else (c>>3)&3
      val q0=if(sp)4 else if((c&7)==5)(c>>3)&3 else c&7
      Seq(q0,q1,q2)(k)}
    val map=AstcWeightUnquantTables.maps(q)
    val out=(0 until count).map{i=>val v=if(trits!=0)((tritAt(tq(i/5),i%5)<<bits)|res(i))else if(quints!=0)((quintAt(tq(i/3),i%3)<<bits)|res(i))else res(i);map(v)}
    (true,(out++Seq.fill(64-count)(0)).toSeq)
  }
  private case class Fmt(ok: Boolean,index: Int,formats: Vector[Int],count: Int,bits: Int,offset: Int,plane2: Int)
  private def read(block: BigInt,n: Int,pos: Int)=((block>>pos)&((BigInt(1)<<n)-1)).toInt
  private def decodeFormat(block: BigInt,pc: Int,wbits: Int,dual: Boolean): Fmt={
    if(pc<1||pc>4||wbits<0||wbits>96)return Fmt(false,0,Vector.fill(4)(0),0,0,0,0)
    var below=128-wbits;var hs=0;var idx=0;var f=Array.fill(4)(0)
    if(pc==1)f(0)=read(block,4,13) else{
      idx=read(block,10,13);hs=3*pc-4;below-=hs
      if(below<0)return Fmt(false,0,Vector.fill(4)(0),0,0,0,0)
      val et=read(block,6,23)|(read(block,hs,below)<<6);val base=et&3
      if(base==0){f=Array.fill(4)((et>>2)&0xF);below+=hs;hs=0}
      else{var bp=2;for(i<-0 until pc){f(i)=(((et>>bp)&1)+(base-1))<<2;bp+=1};for(i<-0 until pc){f(i)|=(et>>bp)&3;bp+=2}}
    }
    val count=(0 until pc).map(i=>2*((f(i)>>2)+1)).sum
    val bits=math.max(0,(if(pc==1)111 else 99)-wbits-hs-(if(dual)2 else 0))
    val p2=if(dual&&below>=2)read(block,2,below-2)else 0
    Fmt(count<=18 && (!dual||below>=2),idx,f.toVector,count,bits,if(pc==1)17 else 29,p2)
  }
  private case class Col(ok: Boolean,values: Vector[Int])
  private def decodeColor(block: BigInt,count: Int,bits: Int,off: Int): Col={
    if(count<2||count>18||(count&1)!=0||bits<0||bits>=128)return Col(false,Vector.fill(18)(0))
    var q = -1;var qi=levels.size-1
    while(q<0 && qi>=0){if(need(count,qi)<=bits)q=qi;qi-=1}
    if(q<4)return Col(false,Vector.fill(18)(0))
    if(off+need(count,q)>128)return Col(false,Vector.fill(18)(0))
    val st=rev(block&((BigInt(1)<<128)-1));var pos=off;var lc=0;var hc=0;val res=Array.fill(count)(0);val tq=Array.fill(22)(0)
    def rd(n: Int): Int={val v=((st>>pos)&((BigInt(1)<<n)-1)).toInt;pos+=n;v}
    for(i<-0 until count){
      res(i)=rd(bitsPer(q))
      if(tritPer(q)!=0){val br=Seq(2,2,1,2,1)(lc);val sh=Seq(0,2,4,5,7)(lc);val nx=Seq(1,2,3,4,0)(lc);val inc=lc==4;tq(hc)|=rd(br)<<sh;if(inc)hc+=1;lc=nx}
      if(quintPer(q)!=0){val br=Seq(3,2,2)(lc);val sh=Seq(0,3,5)(lc);val nx=Seq(1,2,0)(lc);val inc=lc==2;tq(hc)|=rd(br)<<sh;if(inc)hc+=1;lc=nx}
    }
    def tritAt(blk: Int,k: Int): Int={val T=blk&0xff;val sp=((T>>2)&7)==7
      val c=if(sp)(((T>>5)&7)<<2)|(T&3)else T&0x1f
      val t4=if(sp)2 else if(((T>>5)&3)==3)2 else (T>>7)&1
      val t3=if(sp)2 else if(((T>>5)&3)==3)(T>>7)&1 else (T>>5)&3
      val t2=if((c&3)==3)2 else if(((c>>2)&3)==3)2 else (c>>4)&1
      val t1=if((c&3)==3)(c>>4)&1 else if(((c>>2)&3)==3)2 else (c>>2)&3
      val t0=if((c&3)==3)(((c>>3)&1)<<1)|(((c>>2)&1)& ~((c>>3)&1))else if(((c>>2)&3)==3)c&3 else (((c>>1)&1)<<1)|((c&1)& ~((c>>1)&1))
      Seq(t0,t1,t2,t3,t4)(k)}
    def quintAt(blk: Int,k: Int): Int={val Q=blk&0x7f;val sp=((Q>>1)&3)==3&&((Q>>5)&3)==0
      val q2=if(sp)(((Q&1)<<2)|((((Q>>4)&1)& ~(Q&1))<<1)|(((Q>>3)&1)& ~(Q&1)))else if(((Q>>1)&3)==3)4 else (Q>>5)&3
      val c=if(sp)0 else if(((Q>>1)&3)==3)((((Q>>3)&3)<<3)|(((~(Q>>5))&3)<<1)|(Q&1))else Q&0x1f
      val q1=if(sp)4 else if((c&7)==5)4 else (c>>3)&3
      val q0=if(sp)4 else if((c&7)==5)(c>>3)&3 else c&7
      Seq(q0,q1,q2)(k)}
    val map=AstcColorUnquantTables.maps(q-4)
    val vals=(0 until count).map{i=>val v=if(tritPer(q)!=0)((tritAt(tq(i/5),i%5)<<bitsPer(q))|res(i))else if(quintPer(q)!=0)((quintAt(tq(i/3),i%3)<<bitsPer(q))|res(i))else res(i);map(v)}
    Col(true,(vals++Vector.fill(18-count)(0)).toVector)
  }
  private def assemble(pc: Int,f: Vector[Int],v: Vector[Int]): (Vector[Vector[Int]],Boolean)={
    val counts=(0 until 4).map(i=>if(i<pc)2*((f(i)>>2)+1) else 0)
    if(pc<1||pc>4||counts.sum>18)return(Vector.fill(4)(Vector.fill(8)(0)),false)
    var o=0;val eps=(0 until 4).map{i=>if(i<pc){val st=o;o+=counts(i);(0 until 8).map{j=>if(j<counts(i))v(st+j)else 0}.toVector}else Vector.fill(8)(0)}
    (eps.toVector,true)
  }
  private case class Dec(status: Int,texels: Int,rgba: Vector[Vector[Int]])
  private def oracle(block: BigInt,bw: Int,bh: Int,srgb: Boolean): Dec={
    val m=decodeMode(block,bw,bh)
    if(!m.ok)return Dec(1,0,Vector.fill(144)(Vector.fill(4)(0)))
    if(m.void){
      val ok=block.testBit(9)==false && block.testBit(11) && block.testBit(10)
      if(!ok)return Dec(1,0,Vector.fill(144)(Vector.fill(4)(0)))
      val r=((block>>64)&0xffff).toInt>>8;val g=((block>>80)&0xffff).toInt>>8
      val b=((block>>96)&0xffff).toInt>>8;val a=((block>>112)&0xffff).toInt>>8
      return Dec(0,bw*bh,Vector.fill(144)(Vector(r,g,b,a)))
    }
    val (gok,weights)=decodeGrid(block,m.gx,m.gy,m.dual,m.quant)
    if(!gok)return Dec(1,0,Vector.fill(144)(Vector.fill(4)(0)))
    val f=decodeFormat(block,m.parts,m.bits,m.dual)
    val legalCem=Set(0,1,4,5,6,8,9,10,12,13)
    if(!f.ok || (0 until m.parts).exists((i: Int) => !legalCem(f.formats(i))))return Dec(1,0,Vector.fill(144)(Vector.fill(4)(0)))
    val c=decodeColor(block,f.count,f.bits,f.offset)
    if(!c.ok)return Dec(1,0,Vector.fill(144)(Vector.fill(4)(0)))
    val (eps,aok)=assemble(m.parts,f.formats,c.values)
    if(!aok)return Dec(1,0,Vector.fill(144)(Vector.fill(4)(0)))
    val out=Array.fill(144)(Vector.fill(4)(0))
    for(y<-0 until bh;x<-0 until bw){
      val p=partition(f.index,m.parts,bw,bh,x,y)
      val w0=infill(weights,bw,bh,m.gx,m.gy,x,y,0,m.dual)
      val w1=if(m.dual)infill(weights,bw,bh,m.gx,m.gy,x,y,1,true)else 0
      val v=endpoint(f.formats(p),eps(p),w0,w1,m.dual,f.plane2,srgb)
      out(y*bw+x)=v.map(_>>8)
    }
    Dec(0,bw*bh,out.toVector)
  }
  behavior of "AstcLdrGeneralBlockUnit"
  it should "decode random LDR blocks through the whole pipeline" in {
    simulate(new AstcLdrGeneralBlockUnit){d=>zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val random=new scala.util.Random(0x47454e42);var tag=0;var checks=0;var decoded=0
      for(_<-0 until 120){
        val bw=Seq(4,5,6,8,10,12)(random.nextInt(6));val bh=Seq(4,5,6,8,10,12)(random.nextInt(6))
        val block=BigInt(128,random);val srgb=random.nextBoolean()
        val want=oracle(block,bw,bh,srgb)
        tag=(tag+1)&0xff;val q=d.io.in.bits;zero(q);q.tag.poke(tag.U);q.block.poke(block.U)
        q.blockWidth.poke(bw.U);q.blockHeight.poke(bh.U);q.srgb.poke(srgb.B)
        d.io.in.valid.poke(true.B);var n=0;while(!d.io.in.ready.peek().litToBoolean && n<20){d.clock.step();n+=1}
        assert(d.io.in.ready.peek().litToBoolean);d.clock.step();d.io.in.valid.poke(false.B);zero(q)
        n=0;while(!d.io.out.valid.peek().litToBoolean && n<60000){d.clock.step();n+=1}
        assert(n<60000,s"general block timeout ${bw}x$bh")
        val o=d.io.out.bits;o.tag.expect(tag.U)
        val status=o.status.peek().litValue.toInt
        if(want.status==1)assert(status==1,s"block=$block ${bw}x$bh expected invalid got status=$status")
        else{
          assert(status==0,s"block=$block ${bw}x$bh expected ok")
          assert(o.texels.peek().litValue.toInt==want.texels)
          for(i<-0 until bw*bh;c<-0 until 4){
            val got=o.rgba8(i)(c).peek().litValue.toInt
            assert(got==want.rgba(i)(c),s"block=$block ${bw}x$bh texel=$i c=$c got=$got want=${want.rgba(i)(c)} srgb=$srgb")
          }
          decoded+=1
        }
        d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B);checks+=1
      }
      var structured=0
      for(_<-0 until 80){
        val bw=Seq(4,5,6,8,10,12)(random.nextInt(6));val bh=Seq(4,5,6,8,10,12)(random.nextInt(6))
        val bm=(0 until 2048).find{m=>val d=decodeMode(BigInt(m),bw,bh);d.ok && !d.void && d.parts==1 && !d.dual}.getOrElse{
          val d=decodeMode(BigInt(0),bw,bh);throw new RuntimeException("no single-partition mode")}
        val cem=Seq(0,1,4,5,6,8,9,10,12,13)(random.nextInt(10))
        val block=(BigInt(128,random) & ~((BigInt(1)<<17)-1)) | BigInt(bm) | (BigInt(cem)<<13)
        val srgb=random.nextBoolean()
        val want=oracle(block,bw,bh,srgb)
        tag=(tag+1)&0xff;val q=d.io.in.bits;zero(q);q.tag.poke(tag.U);q.block.poke(block.U)
        q.blockWidth.poke(bw.U);q.blockHeight.poke(bh.U);q.srgb.poke(srgb.B)
        d.io.in.valid.poke(true.B);var n=0;while(!d.io.in.ready.peek().litToBoolean && n<20){d.clock.step();n+=1}
        assert(d.io.in.ready.peek().litToBoolean);d.clock.step();d.io.in.valid.poke(false.B);zero(q)
        n=0;while(!d.io.out.valid.peek().litToBoolean && n<60000){d.clock.step();n+=1}
        assert(n<60000);val o=d.io.out.bits
        if(want.status==1)assert(o.status.peek().litValue.toInt==1,s"structured bm=$bm cem=$cem ${bw}x$bh expected invalid")
        else{
          assert(o.status.peek().litValue.toInt==0,s"structured bm=$bm cem=$cem ${bw}x$bh expected ok")
          assert(o.texels.peek().litValue.toInt==want.texels)
          for(i<-0 until bw*bh;c<-0 until 4){
            val got=o.rgba8(i)(c).peek().litValue.toInt
            assert(got==want.rgba(i)(c),s"structured bm=$bm cem=$cem ${bw}x$bh texel=$i c=$c got=$got want=${want.rgba(i)(c)} srgb=$srgb")
          }
          decoded+=1
        }
        d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B);checks+=1;structured+=1
      }
      var multi=0
      for(_<-0 until 70){
        val bw=Seq(6,8,10,12)(random.nextInt(4));val bh=Seq(6,8,10,12)(random.nextInt(4))
        val pc=2+random.nextInt(3);val pidx=random.nextInt(1024)
        val fmtChoice=if(pc==4)Seq(0,1,4,5)(random.nextInt(4))else Seq(0,1,4,5,6,8,9)(random.nextInt(7))
        val partbits=BigInt(pc-1)<<11
        val bm=(0 until 2048).find{m=>val d=decodeMode(BigInt(m)|partbits,bw,bh);d.ok && !d.void && !d.dual && d.parts==pc}.getOrElse(-1)
        if(bm>=0){
          val encoded=BigInt((fmtChoice<<2)&0x3F)<<23
          val block=(BigInt(128,random) & ~((BigInt(1)<<29)-1)) | BigInt(bm) | partbits | (BigInt(pidx)<<13) | encoded
          val srgb=random.nextBoolean()
          val want=oracle(block,bw,bh,srgb)
          tag=(tag+1)&0xff;val q=d.io.in.bits;zero(q);q.tag.poke(tag.U);q.block.poke(block.U)
          q.blockWidth.poke(bw.U);q.blockHeight.poke(bh.U);q.srgb.poke(srgb.B)
          d.io.in.valid.poke(true.B);var n=0;while(!d.io.in.ready.peek().litToBoolean && n<20){d.clock.step();n+=1}
          assert(d.io.in.ready.peek().litToBoolean);d.clock.step();d.io.in.valid.poke(false.B);zero(q)
          n=0;while(!d.io.out.valid.peek().litToBoolean && n<60000){d.clock.step();n+=1}
          assert(n<60000);val o=d.io.out.bits
          if(want.status==1)assert(o.status.peek().litValue.toInt==1,s"multi pc=$pc fmt=$fmtChoice ${bw}x$bh expected invalid")
          else{
            assert(o.status.peek().litValue.toInt==0,s"multi pc=$pc fmt=$fmtChoice ${bw}x$bh expected ok")
            assert(o.texels.peek().litValue.toInt==want.texels)
            for(i<-0 until bw*bh;c<-0 until 4){
              val got=o.rgba8(i)(c).peek().litValue.toInt
              assert(got==want.rgba(i)(c),s"multi pc=$pc fmt=$fmtChoice ${bw}x$bh texel=$i c=$c got=$got want=${want.rgba(i)(c)}")
            }
            decoded+=1
          }
          d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B);checks+=1;multi+=1
        }
      }
      var duals=0
      for(_<-0 until 50){
        val bw=Seq(6,8,10,12)(random.nextInt(4));val bh=Seq(6,8,10,12)(random.nextInt(4))
        val pc=2+random.nextInt(2);val pidx=random.nextInt(1024)
        val fmtChoice=Seq(0,1,4,5,6,8)(random.nextInt(6))
        val partbits=BigInt(pc-1)<<11
        val bm=(0 until 2048).find{m=>val d=decodeMode(BigInt(m)|partbits,bw,bh);d.ok && !d.void && d.dual && d.parts==pc}.getOrElse(-1)
        if(bm>=0){
          val encoded=BigInt((fmtChoice<<2)&0x3F)<<23
          val block=(BigInt(128,random) & ~((BigInt(1)<<29)-1)) | BigInt(bm) | partbits | (BigInt(pidx)<<13) | encoded
          val srgb=random.nextBoolean()
          val want=oracle(block,bw,bh,srgb)
          tag=(tag+1)&0xff;val q=d.io.in.bits;zero(q);q.tag.poke(tag.U);q.block.poke(block.U)
          q.blockWidth.poke(bw.U);q.blockHeight.poke(bh.U);q.srgb.poke(srgb.B)
          d.io.in.valid.poke(true.B);var n=0;while(!d.io.in.ready.peek().litToBoolean && n<20){d.clock.step();n+=1}
          assert(d.io.in.ready.peek().litToBoolean);d.clock.step();d.io.in.valid.poke(false.B);zero(q)
          n=0;while(!d.io.out.valid.peek().litToBoolean && n<60000){d.clock.step();n+=1}
          assert(n<60000);val o=d.io.out.bits
          if(want.status==1)assert(o.status.peek().litValue.toInt==1,s"dual pc=$pc fmt=$fmtChoice ${bw}x$bh expected invalid")
          else{
            assert(o.status.peek().litValue.toInt==0,s"dual pc=$pc fmt=$fmtChoice ${bw}x$bh expected ok")
            for(i<-0 until bw*bh;c<-0 until 4){
              val got=o.rgba8(i)(c).peek().litValue.toInt
              assert(got==want.rgba(i)(c),s"dual pc=$pc fmt=$fmtChoice ${bw}x$bh texel=$i c=$c got=$got want=${want.rgba(i)(c)}")
            }
            decoded+=1
          }
          d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B);checks+=1;duals+=1
        }
      }
      info(s"ASTC_GENERAL_BLOCK blocks=$checks decoded=$decoded structured=$structured multi=$multi dual=$duals footprints=true")
    }
  }
  it should "decode LDR void-extent colors and reject HDR void blocks" in {
    simulate(new AstcLdrGeneralBlockUnit){d=>val t=new Driver(d)
      def vblock(r: Int,g: Int,b: Int,a: Int,hdr: Boolean=false,reserved: Boolean=true): BigInt={
        var w=BigInt(0);w|=BigInt(r)<<64;w|=BigInt(g)<<80;w|=BigInt(b)<<96;w|=BigInt(a)<<112
        w|=((BigInt(1)<<52)-1)<<12;w|=(if(reserved)BigInt(0xc00)else BigInt(0x400));w|=(if(hdr)BigInt(0x200)else BigInt(0))
        w|0x1fc}
      val red=vblock(0xffff,0,0,0xffff)
      val got=t.run(red,4,4,false,1)
      assert(got.status==0 && got.texels==16 && got.rgba.take(16).forall(_==Vector(255,0,0,255)))
      val hdr=t.run(vblock(1,2,3,4,hdr=true),4,4,false,2)
      assert(hdr.status==1 && hdr.texels==0)
      val res=t.run(vblock(1,2,3,4,reserved=false),4,4,false,3)
      assert(res.status==1 && res.texels==0)
      info("ASTC_GENERAL_VOID ldr=true hdrRejected=true reservedRejected=true texels=16")
    }
  }
  private class Driver(val d: AstcLdrGeneralBlockUnit) {
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    def run(block: BigInt,bw: Int,bh: Int,srgb: Boolean,tag: Int): Dec={
      val q=d.io.in.bits;zero(q);q.tag.poke(tag.U);q.block.poke(block.U)
      q.blockWidth.poke(bw.U);q.blockHeight.poke(bh.U);q.srgb.poke(srgb.B)
      d.io.in.valid.poke(true.B);var n=0;while(!d.io.in.ready.peek().litToBoolean && n<20){d.clock.step();n+=1}
      assert(d.io.in.ready.peek().litToBoolean);d.clock.step();d.io.in.valid.poke(false.B);zero(q)
      n=0;while(!d.io.out.valid.peek().litToBoolean && n<60000){d.clock.step();n+=1}
      assert(n<60000,s"general block timeout")
      val o=d.io.out.bits;o.tag.expect(tag.U)
      val r=Dec(o.status.peek().litValue.toInt,o.texels.peek().litValue.toInt,
        (0 until 144).map(i=>(0 until 4).map(c=>o.rgba8(i)(c).peek().litValue.toInt).toVector).toVector)
      d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B);r
    }
  }
}
