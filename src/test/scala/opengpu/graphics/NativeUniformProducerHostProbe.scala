package opengpu.graphics

import chisel3._
import opengpu.system.GpuHostSystemAxi
import opengpu.testutil.GpuSim._
import scala.collection.mutable

/** A real CU copies full-width root/data updates from a CPU-prepared packet.
  * The CPU supplies producer inputs; only shader stores change the targets.
  */
private[graphics] class NativeUniformProducerHostProbe(d: GpuHostSystemAxi,memory: mutable.Map[BigInt,Int],
    read: BigInt=>BigInt,write: (BigInt,BigInt,BigInt)=>Unit,pa: BigInt=>BigInt)
    extends NativeIndirectDrawHostProbe(d,memory,read,write,pa) {
  private val r=GpuCommandMmioRegs
  private var producer=Option.empty[(Int,Seq[(BigInt,BigInt)])]
  def produceUpdates(id: Int,pc: BigInt,packet: BigInt,updates: Seq[(BigInt,BigInt)],token: Int): Unit={
    require(producer.isEmpty && updates.nonEmpty && updates.size<=32)
    producer=Some(id->updates)
    def word(a: BigInt,v: BigInt): Unit=for(b<-0 until 4)memory(pa(a)+b)=((v>>(8*b))&255).toInt
    def load(rd: Int,offset: Int): BigInt=(BigInt(offset)<<20)|(BigInt(1)<<15)|(BigInt(2)<<12)|(BigInt(rd)<<7)|3
    updates.zipWithIndex.foreach{case((address,value),i)=>word(packet+8*i,address);word(packet+8*i+4,value)}
    val code=updates.indices.flatMap(i=>Seq(load(5,8*i),load(6,8*i+4),BigInt("0062a023",16))):+BigInt("30500073",16)
    code.zipWithIndex.foreach{case(w,i)=>word(pc+4*i,w)}
    d.io.m_axi_arready.poke(false.B);d.io.m_axi_awready.poke(false.B);d.io.m_axi_wready.poke(false.B)
    d.io.m_axi_rvalid.poke(false.B);d.io.m_axi_bvalid.poke(false.B)
    for((register,value)<-Seq(r.COMMAND_ID->BigInt(id),r.OPCODE->BigInt(0),r.KERNEL_PC->pc,r.KERNARG->packet,
      r.FLAGS->BigInt(4),r.WAIT_EVENT->BigInt(0),r.SIGNAL_EVENT->BigInt(token)))axiWrite(d,register,value.toInt)
    for(register<-Seq(r.GRID_X,r.GRID_Y,r.GRID_Z,r.LOCAL_X,r.LOCAL_Y,r.LOCAL_Z))axiWrite(d,register,1)
    axiWrite(d,r.SUBMIT,1)
    // Leave the producer blocked on memory while publication and rendering
    // queue behind it. Image.run services all three commands together.
  }
  override def publicationComplete: Boolean=producer.isEmpty && super.publicationComplete
  override def consumePublication(): Boolean={
    producer match {
      case Some((id,updates)) if d.io.m_irq.peek().litToBoolean=>
        val result=axiRead(d,r.COMPLETION)
        assert((result&255)==id && ((result>>8)&7)==0 && ((result>>11)&15)==0 && result.testBit(15))
        for((address,value)<-updates){val actual=(0 until 4).foldLeft(BigInt(0))((v,b)=>v|(BigInt(memory(pa(address)+b))<<(8*b)))
          assert(actual==(value&0xffffffffL),s"GPU uniform publication did not store ${address.toString(16)}")}
        axiWrite(d,r.COMPLETION_POP,1);axiWrite(d,RenderHostRegs.IRQ,3);producer=None
      case None=>super.consumePublication()
      case _=>()
    }
    !publicationComplete
  }
}
