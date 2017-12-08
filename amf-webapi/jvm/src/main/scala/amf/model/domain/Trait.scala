package amf.model.domain

import amf.plugins.domain.webapi.models.templates

case class Trait(private[amf] val trt: templates.Trait) extends AbstractDeclaration(trt) {
  override def linkTarget: Option[DomainElement with Linkable] =
    trt.linkTarget.map({ case l: templates.Trait => Trait(l) })

  override def linkCopy(): DomainElement with Linkable = Trait(trt.linkCopy())
}
