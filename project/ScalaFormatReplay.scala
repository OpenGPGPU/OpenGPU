import com.typesafe.config.{ConfigFactory, ConfigRenderOptions, ConfigUtil}
import sbt.IO

import java.io.File
import scala.collection.JavaConverters._

object ScalaFormatReplay {
  private def layoutOnly(key: String): Boolean =
    ConfigUtil.splitPath(key).asScala.toList match {
      case List("runner", "maxStateVisits")                 => true
      case List("runner", "optimizer", "maxVisitsPerToken") => true
      case List("fileOverride", _, "newlines", "source")    => true
      case _                                                => false
    }

  def prepareConfig(baseFile: File, candidateFile: File, output: File): Unit = {
    val base = ConfigFactory.parseFile(baseFile).resolve()
    val candidate = ConfigFactory.parseFile(candidateFile).resolve()
    val baseEntries = base.entrySet().asScala.filterNot(entry => layoutOnly(entry.getKey)).toSet
    val candidateEntries = candidate.entrySet().asScala.filterNot(entry => layoutOnly(entry.getKey)).toSet
    require(baseEntries == candidateEntries, "Formatting replay requires the base formatter version and rewrite rules")
    IO.write(output, candidate.root().render(ConfigRenderOptions.concise()))
  }
}
