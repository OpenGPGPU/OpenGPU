package opengpu.testutil

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Paths}
import java.util.Base64
import org.scalatest.Suite

/** List registered names without executing tests or elaborating their models. */
object CiSuiteCases {
  def main(args: Array[String]): Unit = {
    require(args.length >= 2, "CiSuiteCases <output.tsv> <suite>...")
    val output = Paths.get(args.head)
    Option(output.getParent).foreach(p => Files.createDirectories(p))
    val writer = Files.newBufferedWriter(output, UTF_8)
    val locations = Files.newBufferedWriter(Paths.get(args.head + ".locations.tsv"), UTF_8)
    try {
      args.tail.distinct.sorted.foreach { name =>
        val suite = Class.forName(name).getDeclaredConstructor().newInstance().asInstanceOf[Suite]
        val cases = suite.testNames.toVector.sorted
        require(cases.nonEmpty, s"Selected suite has no registered tests: $name")
        val encoded = cases.map(test => Base64.getEncoder.encodeToString(test.getBytes(UTF_8)))
        writer.write(name + "\t" + encoded.mkString(",") + "\n")
        cases.foreach { test =>
          suite.testDataFor(test).pos.foreach { pos =>
            def encode(value: String): String = Base64.getEncoder.encodeToString(value.getBytes(UTF_8))
            locations.write(s"$name\t${encode(test)}\t${encode(pos.fileName)}\t${pos.lineNumber}\n")
          }
        }
      }
    } finally { writer.close(); locations.close() }
  }
}
