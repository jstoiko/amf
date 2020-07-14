package amf.plugins.document.webapi.parser.spec

/**
  * Created by pedro.colunga on 11/9/17.
  */
trait SpecSyntax {
  val nodes: Map[String, Set[String]]

  def overrideSyntax(syntax: SpecSyntax): SpecSyntax = {
    val oldNodes = nodes
    new SpecSyntax {
      override val nodes: Map[String, Set[String]] = syntax.nodes ++ oldNodes
    }
  }
}
