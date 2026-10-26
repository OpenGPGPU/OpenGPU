package opengpu.graphics

import org.scalatest.flatspec.AnyFlatSpec

class NativeProductSubmissionSpec extends AnyFlatSpec {
  private def field(s: NativeProductSubmission, base: Long,
    fields: Seq[NativeTypedGraphicsLayout.Field], name: String): BigInt = {
    val f = fields.find(_.name == name).get
    (0 until f.words).foldLeft(BigInt(0))((v, i) => v | (s.memory.readWord(base + (f.word + i) * 4) << (i * 32)))
  }
  behavior of "native product GPU-memory submissions"
  it should "upload all six scene geometries, shader PCs and immutable fragment states" in {
    val jf = NativeBinnedGraphicsLayout.jobFields(new NativeBinnedGraphicsJobRecord)
    val df = NativeBinnedGraphicsLayout.drawFields(new NativeBinnedGraphicsDrawRecord)
    val sf = TypedFragmentStateLayout.fields(new TypedFragmentState(FragmentShaderConfig()))
    for (scene <- NativeProductWorkloads.suite(32, 32)) {
      val s = new NativeProductSubmission(scene)
      assert(s.memory.readWord(s.job.base) == BigInt("474e4a01", 16))
      assert(field(s, s.job.base, jf, "job.drawCount") == scene.draws.size)
      assert(field(s, s.job.base, jf, "job.drawStride") == 2048)
      for ((d, h) <- scene.draws.zipWithIndex) {
        val base = s.draws.base + h * 2048
        assert(field(s, base, df, "draw.vertex.geometry.vertex.input.count") == d.triangles.size * 3)
        assert(field(s, base, df, "draw.vertex.geometry.vertex.shaderPc") == s.vertexCode.base)
        assert(field(s, base, df, "draw.vertex.geometry.vertex.input.attributes[1].offset") == 16)
        for ((v, n) <- d.triangles.flatMap(t => Vector(t.a, t.b, t.c)).zipWithIndex;
          (f, c) <- Vector(v.x, v.y, v.z, v.w, v.u, v.v).zipWithIndex)
          assert(s.memory.readWord(s.vertices(h).base + n * 32 + c * 4) == Fp32ViewportReference.fp(f.toDouble))
        val state = s.states.base + h * 512
        assert(field(s, state, sf, "fragment.shaderPc") == (if (d.textured) s.texturedCode.base else s.flatCode.base))
        assert(field(s, state, sf, "tests.depthEnable") == (if (d.depthTest) 1 else 0))
        assert(field(s, state, sf, "blend.enable") == (if (d.blend) 1 else 0))
        assert(field(s, state, sf, "fragment.colorMask") == 1)
        for (r <- s.fragmentScratch; (f, c) <- d.rgba.zipWithIndex)
          assert(s.memory.readWord(r.base + h * s.fragmentStride + NativeProductShaderPrograms.fragmentPrefixBytes(128) + c * 4) == Fp32ViewportReference.fp(f.toDouble))
      }
      for ((v, i) <- NativeProductWorkloads.textureRgba8.zipWithIndex)
        assert(s.memory.readWord(s.textureData + i * 4) == BigInt(v.toLong & 0xffffffffL))
    }
  }
  it should "separate all allocations and map every used superpage without materializing empty arenas" in {
    val s = new NativeProductSubmission(NativeProductWorkloads.scene("small-triangles", 64, 48))
    assert(s.regions.sliding(2).forall(pair => pair(0).limit <= pair(1).base))
    for (r <- s.regions; base <- (r.base & ~0x3fffffL) until r.limit by 0x400000L)
      assert(s.memory.readWord(0x30000 + (base >> 22) * 4) == ((BigInt(base) >> 12) << 10 | 0xcf))
    assert(s.primitiveArena.limit - s.primitiveArena.base >= s.scene.primitiveCount.toLong * 8 * s.recordBytes)
    assert(s.memory.allocatedPageBytes < 512 * 1024)
    assert(s.planes.filter(s.active).map(p => (p.location, p.resolve)).toSet == Set((0, false), (4, false)))
    val msaa = new NativeProductSubmission(NativeProductWorkloads.scene("msaa-4x", 32, 32))
    assert(msaa.planes.filter(msaa.active).map(p => (p.location, p.resolve)).toSet == Set((0, false), (4, false), (0, true)))
  }
  it should "reject unexecuted image results and writes to immutable inputs" in {
    val scene = NativeProductWorkloads.scene("opaque-overdraw", 17, 19)
    val s = new NativeProductSubmission(scene)
    intercept[AssertionError] { s.verifyImages(new NativeProductFrameReference(scene)) }
    intercept[IllegalArgumentException] { s.writeLine(s.vertexCode.base, 0, 1) }
    intercept[IllegalArgumentException] { s.readLine(0x1000) }
    s.writeLine(s.completion.base, 0x12345678, 15)
    assert(s.memory.readWord(s.completion.base) == 0x12345678)
  }

}
