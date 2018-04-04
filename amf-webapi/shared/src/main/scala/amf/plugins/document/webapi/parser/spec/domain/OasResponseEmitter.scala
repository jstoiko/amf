package amf.plugins.document.webapi.parser.spec.domain

import amf.core.annotations.SynthesizedField
import amf.core.emitter.BaseEmitters._
import amf.core.emitter.{EntryEmitter, SpecOrdering}
import amf.core.model.document.BaseUnit
import amf.core.model.domain.AmfScalar
import amf.core.parser.{Annotations, FieldEntry, Position, Value}
import amf.plugins.document.webapi.annotations.{DefaultPayload, EndPointBodyParameter}
import amf.plugins.document.webapi.contexts.OasSpecEmitterContext
import amf.plugins.document.webapi.parser.spec.declaration.{AnnotationsEmitter, OasSchemaEmitter}
import amf.plugins.document.webapi.parser.spec.toRaml
import amf.plugins.domain.webapi.metamodel.{PayloadModel, RequestModel, ResponseModel}
import amf.plugins.domain.webapi.models.{Payload, Response}
import org.yaml.model.YDocument.EntryBuilder
import amf.core.utils.Strings

import scala.collection.mutable

case class OasResponseEmitter(response: Response, ordering: SpecOrdering, references: Seq[BaseUnit])(
    implicit spec: OasSpecEmitterContext)
    extends EntryEmitter {
  override def emit(b: EntryBuilder): Unit = {
    val fs = response.fields

    sourceOr(
      response.annotations,
      b.complexEntry(
        ScalarEmitter(fs.entry(ResponseModel.Name).get.scalar).emit(_),
        p => {
          if (response.isLink) {
            spec.localReference(response).emit(p)
          } else {
            p.obj {
              b =>
                val result = mutable.ListBuffer[EntryEmitter]()

                fs.entry(ResponseModel.Description)
                  .orElse(Some(FieldEntry(ResponseModel.Description, Value(AmfScalar(""), Annotations())))) // this is mandatory in OAS 2.0
                  .map(f => result += ValueEmitter("description", f))
                fs.entry(RequestModel.Headers)
                  .map(f => result += RamlParametersEmitter("headers", f, ordering, references)(toRaml(spec)))

                val payloads = OasPayloads(response.payloads)

                payloads.default.foreach(payload => {
                  payload.fields
                    .entry(PayloadModel.MediaType)
                    .map(f => result += ValueEmitter("mediaType".asOasExtension, f))
                  payload.fields
                    .entry(PayloadModel.Schema)
                    .map { f =>
                      if (!f.value.value.annotations.contains(classOf[SynthesizedField])) {
                        result += OasSchemaEmitter(f, ordering, references)
                      }
                    }
                })

                if (payloads.other.nonEmpty)
                  result += OasPayloadsEmitter("responsePayloads".asOasExtension, payloads.other, ordering, references)

                fs.entry(ResponseModel.Examples)
                  .map(f => result += OasResponseExamplesEmitter("examples", f, ordering))
                result ++= AnnotationsEmitter(response, ordering).emitters

                traverse(ordering.sorted(result), b)
            }
          }
        }
      )
    )
  }

  override def position(): Position = pos(response.annotations)

}

case class OasPayloads(default: Option[Payload], other: Seq[Payload])

object OasPayloads {
  def apply(payloads: Seq[Payload], endpointPayloadEmitted: Boolean = false): OasPayloads = {
    val clean = payloads.filter(!_.annotations.contains(classOf[EndPointBodyParameter]))

    var default = clean.find(_.annotations.contains(classOf[DefaultPayload]))

    default = if (endpointPayloadEmitted) default else default.orElse(defaultPayload(clean))

    OasPayloads(default, clean.filter(_ != default.orNull))
  }

  def defaultPayload(payloads: Seq[Payload]): Option[Payload] =
    payloads
      .find(p => p.mediaType.isNullOrEmpty)
      .orElse(payloads.find(_.mediaType.is("application/json")))
      .orElse(payloads.headOption)
}
