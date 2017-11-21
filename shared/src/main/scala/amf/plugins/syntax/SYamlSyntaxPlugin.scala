package amf.plugins.syntax

import amf.compiler.ParsedDocument
import amf.framework.plugins.AMFSyntaxPlugin
import org.yaml.model.{YComment, YDocument, YMap, YNode}
import org.yaml.parser.YamlParser
import org.yaml.render.{JsonRender, YamlRender}

class SYamlSyntaxPlugin extends AMFSyntaxPlugin {

  override val ID = "SYaml"

  override def supportedMediaTypes() = Seq(
    "application/yaml",
    "application/x-yaml",
    "text/yaml",
    "text/x-yaml",
    "application/json",
    "text/json",
    "application/raml"
  )

  override def parse(mediaType: String, text: CharSequence) = {
    val parser = YamlParser(text)
    val parts = parser.parse(true)

    if (parts.exists(v => v.isInstanceOf[YDocument])) {
      parts collectFirst { case d: YDocument => d } map { document =>
        val comment = parts collectFirst { case c: YComment => c }
        ParsedDocument(comment, document)
      }
    } else {
      parts collectFirst { case d: YComment => d } map { comment =>
        ParsedDocument(Some(comment), YDocument(IndexedSeq(YNode(YMap()))))
      }
    }
  }

  override def unparse(mediaType: String, ast: YDocument) = {
    val format = mediaType match {
      case "application/yaml"   => "yaml"
      case "application/x-yaml" => "yaml"
      case "text/yaml"          => "yaml"
      case "text/x-yaml"        => "yaml"
      case "application/json"   => "json"
      case "text/json"          => "json"
      case _                    => "yaml"
    }

    if (format == "yaml") {
      Some(YamlRender.render(ast))
    } else {
      Some(JsonRender.render(ast))
    }
  }
}
