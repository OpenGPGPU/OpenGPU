package opengpu.system

import java.nio.file.{Files, Paths}
import org.scalatest.flatspec.AnyFlatSpec

class TessellationJointJobAbiSpec extends AnyFlatSpec {
  it should "use the committed GNQ4/5/6 and GVM2 JSON field offsets" in {
    val text = Files.readString(Paths.get("docs/abi/protected-tessellation-joint-v2.json"))
    val fields = "\"name\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"word\"\\s*:\\s*(\\d+)".r
    def extract(s: String) = fields.findAllMatchIn(s).map(m => m.group(1) -> m.group(2).toInt).toSeq
    val directory = text.indexOf("  \"directory\": {")
    val entries = text.indexOf("\"entryFields\": [", directory)
    assert(directory > 0 && entries > directory)
    assert(extract(text.substring(0, directory)) == TessellationJointJobLayout.jobFields)
    assert(extract(text.substring(entries)) == TessellationJointJobLayout.entryFields)
    val magic =
      "\"magic\"\\s*:\\s*\"(0x[0-9a-f]+)\"".r.findAllMatchIn(text).map(m => BigInt(m.group(1).drop(2), 16)).toSeq
    assert(magic == TessellationJointJobLayout.jobMagics :+ TessellationJointJobLayout.directoryMagic)
    val sequence = "\"sequenceMagic\"\\s*:\\s*\"(0x[0-9a-f]+)\"".r
      .findAllMatchIn(text)
      .map(m => BigInt(m.group(1).drop(2), 16))
      .toSeq
    assert(sequence == TessellationJointJobLayout.sequenceMagics)
  }
}
