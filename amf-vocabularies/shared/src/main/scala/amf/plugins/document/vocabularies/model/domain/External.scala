package amf.plugins.document.vocabularies.model.domain

import amf.client.model.StrField
import amf.core.metamodel.Obj
import amf.core.model.domain.DomainElement
import amf.core.parser.{Annotations, Fields}
import amf.core.utils.Strings
import amf.plugins.document.vocabularies.metamodel.domain.ExternalModel
import amf.plugins.document.vocabularies.metamodel.domain.ExternalModel._
import org.yaml.model.YMap

case class External(fields: Fields, annotations: Annotations) extends DomainElement {

  def alias: StrField = fields.field(Alias)
  def base: StrField  = fields.field(Base)

  def withAlias(alias: String): External = set(Alias, alias)
  def withBase(base: String): External   = set(Base, base)

  override def adopted(parent: String): External.this.type = alias.option() match {
    case Some(alias) => withId(parent + "/externals/" + alias.urlEncoded)
    case None        => throw new Exception("Cannot set ID of external without alias")
  }
  override def meta: Obj = ExternalModel

}

object External {

  def apply(): External = apply(Annotations())

  def apply(ast: YMap): External = apply(Annotations(ast))

  def apply(annotations: Annotations): External = External(Fields(), annotations)
}