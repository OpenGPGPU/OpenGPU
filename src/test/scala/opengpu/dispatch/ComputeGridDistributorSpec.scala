package opengpu.dispatch

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class ComputeGridDistributorSpec extends AnyFlatSpec {
  private def zero(data: Data): Unit = data match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input => u.poke(0.U)
    case _ => ()
  }
  private def task(d: ComputeGridDistributor,id: Int,grid: Seq[BigInt]): Unit = {
    d.io.launch.bits.commandId.poke(id.U)
    d.io.launch.bits.launch.kernelPc.poke((0x1000+id*4).U)
    d.io.launch.bits.launch.kernargAddress.poke((0x8000+id*64).U)
    for(i<-0 until 3){d.io.launch.bits.launch.gridSize(i).poke(grid(i).U);d.io.launch.bits.launch.localSize(i).poke(Seq(4,4,8)(i).U)}
  }
  it should "distribute default workgroups with private tags, reordered retirement and held parent reservations" in {
    simulate(new ComputeGridDistributor(GpuConfig(),4,8)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val shapes=Map(77->Seq(2,2,2),88->Seq(3,1,1))
      val admitted=mutable.Set.empty[Int];val retired=mutable.Set.empty[Int]
      val seen=mutable.Map.empty[Int,Vector[Seq[Int]]].withDefaultValue(Vector.empty)
      case class Child(parent: Int,due: Int)
      val active=mutable.Map.empty[Int,Child]
      var held=Option.empty[BigInt];var childHeld=Option.empty[(BigInt,BigInt)]
      var peak=0;var simultaneous=0
      for(cycle<-0 until 300){
        val next=if(!admitted(77))Some(77)else if(!admitted(88))Some(88)else None
        d.io.launch.valid.poke(next.nonEmpty.B);task(d,next.getOrElse(77),shapes(next.getOrElse(77)).map(BigInt(_)))
        d.io.childLaunch.ready.poke((cycle%7!=0).B);d.io.completion.ready.poke((cycle>=200).B)
        val reply=active.toSeq.filter(_._2.due<=cycle).sortBy(-_._1).headOption
        d.io.childCompletion.valid.poke(reply.nonEmpty.B)
        reply.foreach{case(tag,_)=>d.io.childCompletion.bits.commandId.poke(tag.U);d.io.childCompletion.bits.success.poke(true.B)}
        if(next.nonEmpty && d.io.launch.ready.peek().litToBoolean)admitted+=next.get
        val issue=d.io.childLaunch.valid.peek().litToBoolean && d.io.childLaunch.ready.peek().litToBoolean
        if(d.io.childLaunch.valid.peek().litToBoolean){
          val value=(d.io.childLaunch.bits.peek().litValue,d.io.childGroup.peek().litValue)
          childHeld.foreach(v=>assert(v==value,"held child offer changed"));childHeld=if(issue)None else Some(value)
        }else assert(childHeld.isEmpty)
        val finish=reply.nonEmpty && d.io.childCompletion.ready.peek().litToBoolean
        if(issue && finish)simultaneous+=1
        for((tag,c)<-active)d.io.childParentIds(tag).expect(c.parent.U)
        if(finish)active.remove(reply.get._1)
        if(issue){
          val tag=d.io.childLaunch.bits.commandId.peek().litValue.toInt
          val id=(d.io.childLaunch.bits.launch.kernelPc.peek().litValue.toInt-0x1000)/4
          val xyz=(0 until 3).map(d.io.childGroup.groupId(_).peek().litValue.toInt)
          assert(!active.contains(tag) && admitted(id));d.io.childGroup.singleGroup.expect(true.B)
          d.io.childLaunch.bits.launch.kernargAddress.expect((0x8000+id*64).U)
          for(i<-0 until 3){d.io.childLaunch.bits.launch.gridSize(i).expect(shapes(id)(i).U);d.io.childLaunch.bits.launch.localSize(i).expect(Seq(4,4,8)(i).U)}
          seen(id)=seen(id):+xyz;active(tag)=Child(id,cycle+13+tag%3)
        }
        peak=math.max(peak,active.size)
        if(d.io.completion.valid.peek().litToBoolean){
          val value=d.io.completion.bits.peek().litValue;held.foreach(v=>assert(v==value))
          val id=d.io.completion.bits.commandId.peek().litValue.toInt
          assert(!active.values.exists(_.parent==id) && seen(id).size==shapes(id).product)
          d.io.completion.bits.success.expect(true.B)
          if(cycle<200){task(d,id,Seq(1,1,1).map(BigInt(_)));d.io.launch.ready.expect(false.B)}
          if(cycle>=200){assert(!retired(id));retired+=id;held=None}else held=Some(value)
        }
        d.clock.step()
      }
      for((id,s)<-shapes){val expected=(for(z<-0 until s(2);y<-0 until s(1);x<-0 until s(0))yield Seq(x,y,z)).toVector;assert(seen(id)==expected)}
      assert(retired==shapes.keySet && active.isEmpty && peak==4 && simultaneous>0,s"retired=$retired active=$active peak=$peak simultaneous=$simultaneous")
      info("COMPUTE_GRID default16x8=true parents2=true XYZ=true peakChildren4=true reorderedAndSimultaneous=true heldOffersAndResults=true parentIDsReserved=true")
    }
  }
  it should "drain a held offer after a real fault without completing or recycling its parent early" in {
    simulate(new ComputeGridDistributor(GpuConfig(lanes=4,warps=2),2,8)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def until(p: => Boolean): Unit={var n=0;while(!p && n<100){d.clock.step();n+=1};assert(p)}
      task(d,19,Seq.fill(3)(BigInt("ffffffff",16)));d.io.launch.valid.poke(true.B)
      d.io.launch.ready.expect(true.B);d.clock.step();d.io.launch.valid.poke(false.B)
      until(d.io.childLaunch.valid.peek().litToBoolean)
      val first=d.io.childLaunch.bits.commandId.peek().litValue;d.io.childGroup.groupId.foreach(_.expect(0.U))
      d.io.childLaunch.ready.poke(true.B);d.clock.step();d.io.childLaunch.ready.poke(false.B)
      until(d.io.childLaunch.valid.peek().litToBoolean);d.io.childGroup.groupId(0).expect(1.U)
      val second=d.io.childLaunch.bits.commandId.peek().litValue;assert(first!=second)
      val offer=d.io.childLaunch.bits.peek().litValue;val group=d.io.childGroup.peek().litValue
      d.io.childCompletion.valid.poke(true.B);d.io.childCompletion.bits.commandId.poke(first.U)
      d.io.childCompletion.bits.success.poke(false.B);d.io.childCompletion.ready.expect(true.B)
      d.clock.step();d.io.childCompletion.valid.poke(false.B)
      task(d,19,Seq(1,1,1).map(BigInt(_)));d.io.launch.valid.poke(true.B)
      for(_<-0 until 9){d.io.completion.valid.expect(false.B);d.io.launch.ready.expect(false.B);assert(d.io.childLaunch.bits.peek().litValue==offer && d.io.childGroup.peek().litValue==group);d.clock.step()}
      d.io.launch.valid.poke(false.B);d.io.childLaunch.ready.poke(true.B);d.clock.step()
      for(_<-0 until 7){d.io.childLaunch.valid.expect(false.B);d.io.completion.valid.expect(false.B);d.clock.step()}
      d.io.childCompletion.valid.poke(true.B);d.io.childCompletion.bits.commandId.poke(second.U)
      d.io.childCompletion.bits.success.poke(true.B);d.clock.step();d.io.childCompletion.valid.poke(false.B)
      until(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.commandId.expect(19.U);d.io.completion.bits.success.expect(false.B)
      d.io.launch.valid.poke(true.B);d.io.launch.ready.expect(false.B)
      for(_<-0 until 5){d.io.childLaunch.valid.expect(false.B);d.clock.step()}
      d.io.completion.ready.poke(true.B);d.clock.step();d.io.completion.ready.poke(false.B)
      d.io.launch.ready.expect(true.B);d.clock.step();d.io.launch.valid.poke(false.B)
      until(d.io.childLaunch.valid.peek().litToBoolean);val healthy=d.io.childLaunch.bits.commandId.peek().litValue
      d.io.childGroup.groupId.foreach(_.expect(0.U));d.clock.step()
      d.io.childCompletion.valid.poke(true.B);d.io.childCompletion.bits.commandId.poke(healthy.U);d.io.childCompletion.bits.success.poke(true.B)
      d.clock.step();d.io.childCompletion.valid.poke(false.B)
      until(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.success.expect(true.B)
      info("COMPUTE_GRID fullUint32Axes=true realFault=true heldOfferDrains=true noEarlyParentOrChildReuse=true failStop=true healthyReuse=true")
    }
  }
  it should "compose the last workgroup admission with a peer fault on the same edge" in {
    simulate(new ComputeGridDistributor(GpuConfig(lanes=4,warps=2),2,8)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def until(p: => Boolean): Unit={var n=0;while(!p && n<100){d.clock.step();n+=1};assert(p)}
      task(d,31,Seq(2,1,1).map(BigInt(_)));d.io.launch.valid.poke(true.B)
      d.io.launch.ready.expect(true.B);d.clock.step();d.io.launch.valid.poke(false.B)
      until(d.io.childLaunch.valid.peek().litToBoolean)
      val first=d.io.childLaunch.bits.commandId.peek().litValue
      d.io.childLaunch.ready.poke(true.B);d.clock.step();d.io.childLaunch.ready.poke(false.B)
      until(d.io.childLaunch.valid.peek().litToBoolean)
      val second=d.io.childLaunch.bits.commandId.peek().litValue
      d.io.childGroup.groupId(0).expect(1.U);assert(first!=second)
      d.io.childLaunch.ready.poke(true.B);d.io.childCompletion.valid.poke(true.B)
      d.io.childCompletion.bits.commandId.poke(first.U);d.io.childCompletion.bits.success.poke(false.B)
      d.io.childLaunch.valid.expect(true.B);d.io.childCompletion.ready.expect(true.B)
      d.clock.step();d.io.childCompletion.valid.poke(false.B)
      for(_<-0 until 9){d.io.completion.valid.expect(false.B);d.io.childLaunch.valid.expect(false.B);d.clock.step()}
      d.io.childCompletion.valid.poke(true.B);d.io.childCompletion.bits.commandId.poke(second.U)
      d.io.childCompletion.bits.success.poke(true.B);d.io.childCompletion.ready.expect(true.B)
      d.clock.step();d.io.childCompletion.valid.poke(false.B)
      until(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.commandId.expect(31.U);d.io.completion.bits.success.expect(false.B)
      info("COMPUTE_GRID sameEdgeLastAdmissionAndRealFault=true pendingOwnershipRetained=true actualLastReplyRequired=true oneFailedParent=true")
    }
  }
}
