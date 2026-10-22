package opengpu.graphics

import scala.collection.mutable

/** Shader prologue with an independent scalar check of lane-wise results.
  * x1/x8/x9 and v1 retain their ABI values. v0 becomes zero; the ordinary
  * native fixtures use unmasked instructions and re-establish their own VL.
  */
object NativeSimtProgram {
  val cease: BigInt=BigInt("30500073",16)
  private val join: BigInt=BigInt("205b",16)
  private def b(op: Int,a: Int,b: Int,n: Int,opcode: Int=0x5b): BigInt={
    require(n>= -4096 && n<=4094 && (n&1)==0)
    val x=n&8191
    (BigInt((x>>12)&1)<<31)|(BigInt((x>>5)&63)<<25)|(BigInt(b)<<20)|
      (BigInt(a)<<15)|(BigInt(op)<<12)|(BigInt((x>>1)&15)<<8)|(BigInt((x>>11)&1)<<7)|opcode
  }
  private def v(fn: Int,vd: Int,vs2: Int,vs1: Int,form: Int): BigInt=
    (BigInt(fn)<<26)|(BigInt(1)<<25)|(BigInt(vs2)<<20)|(BigInt(vs1&31)<<15)|
      (BigInt(form)<<12)|(BigInt(vd)<<7)|0x57
  private def immediate(vd: Int,n: Int): BigInt=v(0x17,vd,0,n,3)
  private class Code {
    val words=mutable.ArrayBuffer.empty[BigInt]
    val labels=mutable.Map.empty[String,Int]
    val fixups=mutable.ArrayBuffer.empty[(Int,String,Int=>BigInt)]
    def emit(word: BigInt): Unit={words+=word}
    def label(name: String): Unit={labels(name)=words.size*4}
    def edge(name: String)(make: Int=>BigInt): Unit={fixups+=((words.size,name,make));emit(0)}
    def sync(name: String): Unit=edge(name)(n=>b(3,0,0,n))
    def branch(op: Int,a: Int,c: Int,name: String): Unit=edge(name)(n=>b(op,a,c,n))
    def jump(name: String): Unit=edge(name){n=>
      val x=n&0x1fffff
      (BigInt((x>>20)&1)<<31)|(BigInt((x>>1)&1023)<<21)|
        (BigInt((x>>11)&1)<<20)|(BigInt((x>>12)&255)<<12)|0x6f
    }
    def check(): Unit={
      emit(v(0x02,27,26,0,2)) // vredor.vs v27,v26,v0, seed zero
      emit(v(0x10,14,27,0,2)) // vmv.x.s x14,v27
      edge("fail")(n=>b(1,14,0,n,0x63)) // independent scalar bne
    }
    def result: Vector[BigInt]={
      fixups.foreach{case(i,name,make)=>words(i)=make(labels(name)-i*4)}
      words.toVector
    }
  }
  def guarded(body: Seq[BigInt],corrupt: Boolean=false): Vector[BigInt]={
    require(body.last==cease)
    val c=new Code
    c.emit(BigInt("0104f057",16)) // vsetvli x0,x9,e32,m1,tu,mu
    c.emit(immediate(0,0))
    c.emit(immediate(21,0));c.emit(v(0,22,1,9,3)) // limit=localID+9
    c.sync("loopJoin");c.label("condition");c.branch(7,21,22,"loopJoin")
    c.emit(v(0,21,21,1,3));c.jump("condition")
    c.label("loopJoin");c.emit(join)
    c.emit(v(0x0b,26,21,22,0));c.check() // XOR counters with independent limits
    c.emit(v(0x09,24,1,1,3)) // parity=localID & 1
    c.sync("parityJoin");c.branch(0,24,0,"even")
    c.emit(immediate(25,1));c.emit(join)
    c.label("even");c.emit(immediate(25,0));c.emit(join)
    c.label("parityJoin");c.emit(join)
    if(corrupt)c.emit(v(0x0b,25,25,1,3))
    c.emit(v(0x0b,26,25,24,0));c.check()
    body.foreach(c.emit)
    c.label("fail");c.emit(BigInt("ffffffff",16));c.emit(cease)
    c.result
  }
  def overflow(depth: Int): Vector[BigInt]={
    require(depth>0 && depth<63)
    val entries=Vector.tabulate(depth+1)(i=>b(3,0,0,256-i*4))
    entries++Vector.fill(64-entries.size)(BigInt("13",16))++Vector(join,cease)
  }
  /** The second instruction-line request occurs after sync has committed.
    * Reset during that delayed request must discard the live scope marker.
    */
  val parkedScope: Vector[BigInt]=Vector(b(3,0,0,128))++
    Vector.fill(15)(BigInt("13",16))++Vector(BigInt("6f",16))++
    Vector.fill(15)(BigInt("13",16))++Vector(join,cease)
}
