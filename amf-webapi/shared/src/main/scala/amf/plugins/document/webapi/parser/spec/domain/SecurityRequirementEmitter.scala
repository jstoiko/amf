package amf.plugins.document.webapi.parser.spec.domain

import amf.core.emitter.BaseEmitters.{ScalarEmitter, pos, sourceOr, traverse}
import amf.core.emitter.{EntryEmitter, PartEmitter, SpecOrdering}
import amf.core.model.domain.{AmfElement, AmfScalar, Linkable}
import amf.core.parser.{FieldEntry, Position}
import amf.core.remote.Vendor
import amf.core.utils._
import amf.plugins.document.webapi.contexts.SpecEmitterContext
import amf.plugins.document.webapi.parser.spec.oas.OasSecuritySchemeTypeMapping
import amf.plugins.domain.webapi.metamodel.security.ParametrizedSecuritySchemeModel
import amf.plugins.domain.webapi.models.security.{
  OAuth2Settings,
  OpenIdConnectSettings,
  ParametrizedSecurityScheme,
  SecurityRequirement
}
import org.yaml.model.YDocument.{EntryBuilder, PartBuilder}

case class SecurityRequirementsEmitter(key: String, f: FieldEntry, ordering: SpecOrdering)(
    implicit spec: SpecEmitterContext)
    extends SingleValueArrayEmitter {

  override type Element = SecurityRequirement

  override def emit(scheme: SecurityRequirement): PartEmitter =
    spec.factory.securityRequirementEmitter(scheme, ordering)

  override protected def collect(elements: Seq[AmfElement]): Seq[SecurityRequirement] =
    f.array.values.collect { case p: SecurityRequirement => p }
}

case class OasWithExtensionsSecurityRequirementsEmitter(key: String, f: FieldEntry, ordering: SpecOrdering)(
    implicit spec: SpecEmitterContext)
    extends EntryEmitter {

  override def emit(b: EntryBuilder): Unit = {
    sourceOr(
      f.value, {
        val (validOas, invalidOas) =
          collect(f.array.values).partition(r => allSchemesAreValidInOas(r.schemes, spec.vendor))
        if (validOas.nonEmpty) b.entry(key, _.list(traverse(ordering.sorted(validOas.map(emit)), _)))
        if (invalidOas.nonEmpty)
          b.entry(key.asOasExtension, _.list(traverse(ordering.sorted(invalidOas.map(emit)), _)))
      }
    )
  }

  def allSchemesAreValidInOas(schemes: Seq[ParametrizedSecurityScheme], vendor: Vendor): Boolean = {
    schemes.forall(s => {
      s.scheme match {
        case linkable: Linkable if linkable.isLink => return true
        case _                                     =>
      }
      Option(s.scheme).exists { s =>
        {
          val schemeType = s.`type`.option().getOrElse("")
          OasSecuritySchemeTypeMapping.validTypesFor(vendor).contains(schemeType)
        }
      }
    })
  }

  def emit(scheme: SecurityRequirement): PartEmitter =
    spec.factory.securityRequirementEmitter(scheme, ordering)

  protected def collect(elements: Seq[AmfElement]): Seq[SecurityRequirement] =
    f.array.values.collect { case p: SecurityRequirement => p }

  override def position(): Position = pos(f.value.annotations)
}

abstract class SecurityRequirementEmitter(securityRequirement: SecurityRequirement, ordering: SpecOrdering)
    extends PartEmitter

case class OasSecurityRequirementEmitter(requirement: SecurityRequirement, ordering: SpecOrdering)
    extends SecurityRequirementEmitter(requirement, ordering) {

  override def emit(b: PartBuilder): Unit = {
    b.obj { eb =>
      requirement.schemes.foreach { parametrizedScheme =>
        val fs = parametrizedScheme.fields
        fs.entry(ParametrizedSecuritySchemeModel.Settings) match {
          case Some(f) =>
            val scopes = f.element match {
              case settings: OAuth2Settings =>
                settings.flows.headOption.toList
                  .flatMap { flow =>
                    flow.scopes.map(s => ScalarEmitter(AmfScalar(s.name.value(), s.annotations)))
                  }
              case settings: OpenIdConnectSettings =>
                settings.scopes.map(s => ScalarEmitter(AmfScalar(s.name.value(), s.annotations)))
              case _ => // we cant emit, if its not 2.0 isnt valid in oas.
                Nil
            }

            eb.entry(parametrizedScheme.name.value(), _.list(traverse(ordering.sorted(scopes), _)))

          case None =>
            eb.entry(parametrizedScheme.name.value(), _.list(_ => {}))
        }
      }
    }
  }

  override def position(): Position = pos(requirement.annotations)
}

case class RamlSecurityRequirementEmitter(requirement: SecurityRequirement, ordering: SpecOrdering)(
    implicit spec: SpecEmitterContext)
    extends SecurityRequirementEmitter(requirement, ordering) {
  override def emit(b: PartBuilder): Unit = {
    requirement.schemes.foreach { scheme =>
      RamlParametrizedSecuritySchemeEmitter(scheme, ordering).emit(b)
    }
  }

  override def position(): Position = pos(requirement.annotations)
}
