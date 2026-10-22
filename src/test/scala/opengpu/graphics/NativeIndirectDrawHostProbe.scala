package opengpu.graphics

import chisel3._
import opengpu.system.{GpuHostSystemAxi,GpuHostTestSupport}
import opengpu.testutil.GpuSim._
import scala.collection.mutable

/** Actual CU stores publish the packed draw record; the CPU never expands its
  * counts into the native draw descriptor. The render consumes the producer's
  * event token through a queued cache-publication command before rendering.
  */
private[graphics] class NativeIndirectDrawHostProbe(d: GpuHostSystemAxi,memory: mutable.Map[BigInt,Int],
    read: BigInt=>BigInt,write: (BigInt,BigInt,BigInt)=>Unit,pa: BigInt=>BigInt) extends GpuHostTestSupport {
  private val r=GpuCommandMmioRegs
  def park(): Unit={
    d.io.m_axi_arready.poke(false.B);d.io.m_axi_awready.poke(false.B);d.io.m_axi_wready.poke(false.B)
    d.io.m_axi_rvalid.poke(false.B);d.io.m_axi_bvalid.poke(false.B)
  }
  def word(address: BigInt,value: BigInt): Unit=for(b<-0 until 4)memory(address+b)=((value>>(8*b))&255).toInt
  def initializeArguments(address: BigInt,values: Seq[Int],cpu: Boolean=false): Unit={
    for(b<- -4 until values.size*4+4)memory(pa(address+b))=0xa7
    values.zipWithIndex.foreach{case(v,i)=>word(pa(address+4*i),if(cpu)BigInt(v)else BigInt(0))}
  }
  def produce(id: Int,pc: BigInt,address: BigInt,values: Seq[Int],token: Int): Unit={
    def addi(n: Int): BigInt=(BigInt(n&4095)<<20)|(BigInt(5)<<7)|0x13
    def store(offset: Int): BigInt=(BigInt(5)<<20)|(BigInt(1)<<15)|(BigInt(2)<<12)|(BigInt(offset&31)<<7)|0x23
    require(values.forall(v=>v>= -2048 && v<=2047) && values.size<=5)
    val code=values.zipWithIndex.flatMap{case(v,i)=>Seq(addi(v),store(4*i))}:+BigInt("30500073",16)
    code.zipWithIndex.foreach{case(v,i)=>word(pa(pc+4*i),v)}
    park()
    for((register,value)<-Seq(r.COMMAND_ID->BigInt(id),r.OPCODE->BigInt(0),r.KERNEL_PC->pc,r.KERNARG->address,
      r.FLAGS->BigInt(4),r.WAIT_EVENT->BigInt(0),r.SIGNAL_EVENT->BigInt(token)))axiWrite(d,register,value.toInt)
    for(register<-Seq(r.GRID_X,r.GRID_Y,r.GRID_Z,r.LOCAL_X,r.LOCAL_Y,r.LOCAL_Z))axiWrite(d,register,1)
    axiWrite(d,r.SUBMIT,1)
    serviceMemoryMaster(d,read,writeAckDelay=7,readResponseDelay=3,maxCycles=100000)(write){d.io.m_irq.peek().litToBoolean}
    val completion=axiRead(d,r.COMPLETION)
    assert((completion&255)==id && ((completion>>8)&7)==0 && ((completion>>11)&15)==0 && completion.testBit(15))
    for((value,i)<-values.zipWithIndex){
      val actual=(0 until 4).foldLeft(BigInt(0))((v,b)=>v|(BigInt(memory(pa(address+4*i+b)))<<(8*b)))
      assert(actual==(BigInt(value)&0xffffffffL),s"GPU did not publish indirect word $i")
    }
    checkGuards(address,values.size)
    axiWrite(d,r.COMPLETION_POP,1);axiWrite(d,RenderHostRegs.IRQ,3)
  }
  private var publication=Option.empty[Int]
  def publicationComplete: Boolean=publication.isEmpty
  def publish(id: Int,token: Int,address: BigInt,words: Int): Unit={
    require(publication.isEmpty);publication=Some(id);park()
    val begin=address& ~BigInt(63);val end=(address+words*4+63)& ~BigInt(63)
    for((register,value)<-Seq(r.COMMAND_ID->BigInt(id),r.OPCODE->BigInt(5),r.SOURCE->pa(begin),
      r.BYTES->(end-begin),r.FLAGS->BigInt(2),r.WAIT_EVENT->BigInt(token)))axiWrite(d,register,value.toInt)
    axiWrite(d,r.SUBMIT,1)
    // Native render envelopes deliberately have no event flags. Their existing
    // dependency-idle gate waits for this queued publication to finish.
    axiWrite(d,r.FLAGS,0)
  }
  def consumePublication(): Boolean={
    publication.foreach{id=>if(d.io.m_irq.peek().litToBoolean){
      val c=axiRead(d,r.COMPLETION)
      assert((c&255)==id && ((c>>8)&7)==5 && ((c>>11)&15)==0 && c.testBit(15),"cache publication did not complete before rendering")
      axiWrite(d,r.COMPLETION_POP,1);axiWrite(d,RenderHostRegs.IRQ,3);publication=None
    }}
    publication.nonEmpty
  }
  def noWait(): Unit={park();axiWrite(d,r.FLAGS,0)}
  def checkGuards(address: BigInt,words: Int): Unit=
    for(offset<-Seq(-4,-3,-2,-1,words*4,words*4+1,words*4+2,words*4+3))assert(memory(pa(address+offset))==0xa7)
}
