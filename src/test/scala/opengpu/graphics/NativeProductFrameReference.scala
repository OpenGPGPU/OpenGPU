package opengpu.graphics

import opengpu.testutil.GraphicsTestResolution
import scala.collection.mutable

object NativeProductFrameReference {
  final case class Scissor(minX: Int,minY: Int,maxX: Int,maxY: Int) {
    require(minX>=0 && minY>=0 && maxX>=minX && maxY>=minY && maxX<=65535 && maxY<=65535)
    def contains(x: Int,y: Int): Boolean=x>=minX && x<maxX && y>=minY && y<maxY
  }
  def apiExtent(scene: NativeProductWorkloads.Scene,scissors: Map[Int,Scissor]=Map.empty): NativeProductFrameReference =
    new NativeProductFrameReference(scene,2048,2048,scissors,true)
  final case class Pixel(color: Vector[Int], depthStencil: Vector[Int],
    resolvedColor: Int, coveredSamples: Int, passedSamples: Int)
}

/** CPU image oracle, independent of DUT outputs. Reuses the existing exact
  * numeric references, with its own sample traversal and ordered pixel storage.
  * Retains geometry/tile indices and one pixel, never a full-frame pixel array.
  * A reference instance is intended for one verification thread.
  */
final class NativeProductFrameReference private (val scene: NativeProductWorkloads.Scene,
  maximumWidth: Int,maximumHeight: Int,scissors: Map[Int,NativeProductFrameReference.Scissor],sparseBackground: Boolean) {
  def this(scene: NativeProductWorkloads.Scene)=
    this(scene,GraphicsTestResolution.width,GraphicsTestResolution.height,Map.empty,false)
  import NativeProductWorkloads._
  import Fp32ViewportReference.{fp, number}
  require(scene.width <= maximumWidth && scene.height <= maximumHeight,
    "frame reference exceeds the selected qualification extent")
  require(scissors.forall{case(i,s)=>i>=0 && i<scene.draws.size && s.maxX<=scene.width && s.maxY<=scene.height})

  import NativeProductFrameReference.Pixel
  private final class Prepared(val draw: Draw, val drawIndex: Int, val triangle: TriangleRasterReference.Triangle,
    val xy: Vector[(Long, Long)], val area: Long)
  private val tileSide = 16
  private val columns = (scene.width + tileSide - 1) / tileSide
  private val bins = mutable.Map.empty[Int, mutable.ArrayBuffer[Prepared]]
  private val offsets = if (scene.samples == 1) Vector((128, 128))
    else Vector((64, 64), (192, 64), (64, 192), (192, 192))

  private def cross(a: (Long, Long), b: (Long, Long), x: Long, y: Long): Long =
    (a._2 - b._2) * x + (b._1 - a._1) * y + a._1 * b._2 - b._1 * a._2
  private def edges(p: Prepared, x: Long, y: Long): Vector[BigInt] =
    Vector.tabulate(3)(j => BigInt(cross(p.xy((j + 1) % 3), p.xy((j + 2) % 3), x, y)))
  private def covered(p: Prepared, x: Long, y: Long): Boolean = {
    val sign = if (p.area > 0) 1L else -1L
    (0 until 3).forall { j =>
      val a = p.xy((j + 1) % 3); val b = p.xy((j + 2) % 3)
      val e = cross(a, b, x, y) * sign
      val topLeft = (a._2 - b._2) * sign < 0 ||
        (a._2 == b._2 && (b._1 - a._1) * sign < 0)
      e > 0 || (e == 0 && topLeft)
    }
  }

  // The index preserves draw/primitive/clip-part order in every tile. All
  // coordinates are clipped first, bounding signed edge products well below64b.
  for ((draw,drawIndex) <- scene.draws.zipWithIndex; t <- draw.triangles) {
    val input = Vector(t.a, t.b, t.c).zipWithIndex.map { case (v, i) =>
      PrimitiveClipReference.V(Vector(v.x, v.y, v.z, v.w).map(x => fp(x.toDouble)),
        Vector(fp(v.u.toDouble), fp(v.v.toDouble), BigInt(0), BigInt(0)), i, i)
    }
    val parts = PrimitiveClipReference.clip(input, 2, 0, 1, 2).parts
    for (part <- parts) {
      val projected = part.map(v => Fp32ViewportReference.project(v.position,
        Fp32ViewportReference.View(width = scene.width, height = scene.height), Fp32ViewportConfig()))
      require(projected.forall(_.valid), "unrepresentable workload viewport coordinate")
      val xy = projected.map(v => (v.x.toLong, v.y.toLong))
      val area = cross(xy(1), xy(2), xy(0)._1, xy(0)._2)
      if (area != 0) {
        val triangle = TriangleRasterReference.Triangle(projected.map(v => (v.x, v.y)),
          FragmentInterpolationReference.Data(projected.map(_.reciprocal),
            projected.map(_.depth), part.map(_.varyings), 0, 1))
        val p = new Prepared(draw, drawIndex, triangle, xy, area)
        val x0 = math.max(0, xy.map(_._1).min / 256).toInt
        val y0 = math.max(0, xy.map(_._2).min / 256).toInt
        val x1 = math.min(scene.width - 1L, xy.map(_._1).max / 256).toInt
        val y1 = math.min(scene.height - 1L, xy.map(_._2).max / 256).toInt
        for (ty <- y0 / tileSide to y1 / tileSide; tx <- x0 / tileSide to x1 / tileSide)
          bins.getOrElseUpdate(ty * columns + tx, mutable.ArrayBuffer.empty) += p
      }
    }
  }

  private def shade(p: Prepared, x: Int, y: Int): Vector[BigInt] = {
    val sample = FragmentInterpolationReference.Sample(edges(p, x.toLong * 256 + 128,
      y.toLong * 256 + 128), BigInt(p.area), x = x, y = y)
    val lane = FragmentInterpolationReference.lane(p.triangle.values, sample)
    require(lane.valid, "invalid perspective denominator in workload shader")
    val texel = if (p.draw.textured) {
      def repeat(raw: BigInt, extent: Int): Int = {
        val coordinate = number(raw)
        require(java.lang.Double.isFinite(coordinate), "nonfinite workload texture coordinate")
        val cell = math.floor(coordinate * extent)
        (((cell % extent) + extent) % extent).toInt
      }
      val u = repeat(lane.values(0), textureWidth)
      val v = repeat(lane.values(1), textureHeight)
      TypedBlendReference.decode("RGBA8", BigInt(textureRgba8(v * textureWidth + u).toLong & 0xffffffffL))
    } else Vector.fill(4)(fp(1))
    Vector.tabulate(4)(c => fp(number(texel(c)) * p.draw.rgba(c).toDouble))
  }

  private val clearPixel=Pixel(Vector.fill(scene.samples)(0),Vector.fill(scene.samples)(0xffffff),0,0,0)
  private var last: Option[((Int, Int), Pixel)] = None
  def pixel(x: Int, y: Int): Pixel = {
    require(x >= 0 && x < scene.width && y >= 0 && y < scene.height)
    last match {
      case Some(((px, py), value)) if px == x && py == y => return value
      case _ => ()
    }
    val tile=(y / tileSide)*columns+x / tileSide
    if(sparseBackground && !bins.contains(tile))return clearPixel
    val color = Array.fill(scene.samples)(BigInt(0))
    val depth = Array.fill(scene.samples)(0xffffff)
    var tested = 0; var passed = 0
    for (p <- bins.getOrElse((y / tileSide) * columns + x / tileSide, mutable.ArrayBuffer.empty)) {
      val active = offsets.indices.filter { s =>
        scissors.get(p.drawIndex).forall(_.contains(x,y)) &&
          covered(p, x.toLong * 256 + offsets(s)._1, y.toLong * 256 + offsets(s)._2)
      }
      if (active.nonEmpty) {
        val rgba = shade(p, x, y)
        for (s <- active) {
          tested += 1
          val z = SampleDepthReference.linear(edges(p, x.toLong * 256 + offsets(s)._1,
            y.toLong * 256 + offsets(s)._2), BigInt(p.area), p.triangle.values.depth)
          val encoded = TypedDepthReference.encode(z, 4).toInt
          if (!p.draw.depthTest || encoded < depth(s)) {
            passed += 1
            val blended = TypedBlendReference.blend("RGBA8", rgba, color(s),
              TypedBlendReference.State(sa = 1, da = 5, enable = p.draw.blend))
            color(s) = TypedColorReference.encode("RGBA8", blended)._1
            if (p.draw.depthWrite) depth(s) = encoded
          }
        }
      }
    }
    val resolve = TypedResolveReference.resolve("RGBA8", color.toVector, if (scene.samples == 1) 0 else 2)
    val result = Pixel(color.toVector.map(_.toInt), depth.toVector, resolve.toInt, tested, passed)
    last = Some(((x, y), result)); result
  }
  def storedColor(x: Int, y: Int, sample: Int): BigInt = {
    require(sample >= 0 && sample < scene.samples)
    BigInt(pixel(x, y).color(sample).toLong & 0xffffffffL)
  }
  def storedDepthStencil(x: Int, y: Int, sample: Int): BigInt = {
    require(sample >= 0 && sample < scene.samples)
    BigInt(pixel(x, y).depthStencil(sample).toLong & 0xffffffffL)
  }
  def resolvedColor(x: Int, y: Int): BigInt = BigInt(pixel(x, y).resolvedColor.toLong & 0xffffffffL)
}
