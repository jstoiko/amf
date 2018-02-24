package amf.plugins.document.vocabularies2.metamodel.domain

import amf.core.metamodel.Field
import amf.core.metamodel.Type.{Iri, Str, Array}
import amf.core.metamodel.domain.DomainElementModel
import amf.core.model.domain.AmfObject
import amf.core.vocabulary.{Namespace, ValueType}
import amf.plugins.document.vocabularies2.model.domain.NodeMapping

object NodeMappingModel extends DomainElementModel {

  val Name            = Field(Str, Namespace.Schema + "name")
  val NodeTypeMapping = Field(Iri, Namespace.Shacl + "targetClass")
  val PropertiesMapping = Field(Array(PropertyMappingModel), Namespace.Shacl + "property")

  override def fields: List[Field] = NodeTypeMapping :: Name :: PropertiesMapping :: DomainElementModel.fields

  override def modelInstance: AmfObject = NodeMapping()

  override val `type`: List[ValueType] = Namespace.Meta + "NodeMapping" :: Namespace.Shacl + "Shape" :: DomainElementModel.`type`
}
