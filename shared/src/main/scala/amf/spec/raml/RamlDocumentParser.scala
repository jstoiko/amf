package amf.spec.raml

import amf.common.Lazy
import amf.compiler.Root
import amf.document.{BaseUnit, Document}
import amf.domain.Annotation._
import amf.domain._
import amf.domain.extensions.CustomDomainProperty
import amf.metadata.document.BaseUnitModel
import amf.metadata.domain.EndPointModel.Path
import amf.metadata.domain.OperationModel.Method
import amf.metadata.domain._
import amf.metadata.domain.extensions.CustomDomainPropertyModel
import amf.model.{AmfArray, AmfElement, AmfScalar}
import amf.parser.{YMapOps, YValueOps}
import amf.shape.Shape
import amf.spec.common.{AnnotationParser, BaseSpecParser, SpecParserContext}
import amf.spec.{BaseUriSplitter, Declarations}
import amf.vocabulary.VocabularyMappings
import org.yaml.model._

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

/**
  * Raml 1.0 spec parser
  */
case class RamlDocumentParser(root: Root) extends RamlSpecParser with RamlSyntax {

  def parseDocument(): Document = {

    val document = Document().adopted(root.location)

    root.document.value.foreach(value => {
      val map = value.toMap

      val references = ReferencesParser("uses", map, root.references).parse()
      parseDeclarations(root, map, references.declarations)

      val api = parseWebApi(map, references.declarations).add(SourceVendor(root.vendor))
      document.withEncodes(api)

      val declarables = references.declarations.declarables()
      if (declarables.nonEmpty) document.withDeclares(declarables)
      if (references.references.nonEmpty) document.withReferences(references.solvedReferences())
    })
    document
  }

  def parseWebApi(map: YMap, declarations: Declarations): WebApi = {

    val api = WebApi(map).adopted(root.location)

    validateClosedShape(api.id, map, "webApi")

    map.key("title", entry => {
      val value = ValueNode(entry.value)
      api.set(WebApiModel.Name, value.string(), Annotations(entry))
    })

    map.key(
      "baseUriParameters",
      entry => {
        val parameters: Seq[Parameter] =
          ParametersParser(entry.value.value.toMap, api.withBaseUriParameter, declarations)
            .parse()
            .map(_.withBinding("path"))
        api.set(WebApiModel.BaseUriParameters, AmfArray(parameters, Annotations(entry.value)), Annotations(entry))
      }
    )

    map.key("description", entry => {
      val value = ValueNode(entry.value)
      api.set(WebApiModel.Description, value.string(), Annotations(entry))
    })

    map.key(
      "mediaType",
      entry => {
        val annotations = Annotations(entry)
        val value: AmfElement = entry.value.value match {
          case _: YScalar =>
            annotations += SingleValueArray()
            AmfArray(Seq(ValueNode(entry.value).string()))
          case _: YSequence =>
            ArrayNode(entry.value.value.toSequence).strings()
        }

        api.set(WebApiModel.ContentType, value, annotations)
        api.set(WebApiModel.Accepts, value, annotations)
      }
    )

    map.key("version", entry => {
      val value = ValueNode(entry.value)
      api.set(WebApiModel.Version, value.string(), Annotations(entry))
    })

    map.key("(termsOfService)", entry => {
      val value = ValueNode(entry.value)
      api.set(WebApiModel.TermsOfService, value.string(), Annotations(entry))
    })

    map.key(
      "protocols",
      entry => {
        entry.value.value match {
          case _: YScalar =>
            api.set(WebApiModel.Schemes, AmfArray(Seq(ValueNode(entry.value).string())), Annotations(entry))
          case _: YSequence =>
            val value = ArrayNode(entry.value.value.toSequence)
            api.set(WebApiModel.Schemes, value.strings(), Annotations(entry))
        }
      }
    )

    map.key(
      "(contact)",
      entry => {
        val organization: Organization = OrganizationParser(entry.value.value.toMap).parse()
        api.set(WebApiModel.Provider, organization, Annotations(entry))
      }
    )

    map.key(
      "(license)",
      entry => {
        val license: License = LicenseParser(entry.value.value.toMap).parse()
        api.set(WebApiModel.License, license, Annotations(entry))
      }
    )

    map.regex(
      "^/.*",
      entries => {
        val endpoints = mutable.ListBuffer[EndPoint]()
        entries.foreach(entry => EndpointParser(entry, api.withEndPoint, None, endpoints, declarations).parse())
        api.set(WebApiModel.EndPoints, AmfArray(endpoints))
      }
    )

    map.key(
      "baseUri",
      entry => {
        val value = ValueNode(entry.value)
        val uri   = BaseUriSplitter(value.string().value.toString)

        if (api.schemes.isEmpty && uri.protocol.nonEmpty) {
          api.set(WebApiModel.Schemes,
                  AmfArray(Seq(AmfScalar(uri.protocol)), Annotations(entry.value) += SynthesizedField()),
                  Annotations(entry))
        }

        if (uri.domain.nonEmpty) {
          api.set(WebApiModel.Host,
                  AmfScalar(uri.domain, Annotations(entry.value) += SynthesizedField()),
                  Annotations(entry))
        }

        if (uri.path.nonEmpty) {
          api.set(WebApiModel.BasePath,
                  AmfScalar(uri.path, Annotations(entry.value) += SynthesizedField()),
                  Annotations(entry))
        }
      }
    )

    map.key(
      "documentation",
      entry => {
        api.setArray(WebApiModel.Documentations,
                     UserDocumentationsParser(entry.value.value.toSequence, declarations).parse(),
                     Annotations(entry))
      }
    )

    AnnotationParser(() => api, map).parse()

    api
  }

  case class EndpointParser(entry: YMapEntry,
                            producer: String => EndPoint,
                            parent: Option[EndPoint],
                            collector: mutable.ListBuffer[EndPoint],
                            declarations: Declarations) {
    def parse(): Unit = {

      val path = parent.map(_.path).getOrElse("") + entry.key.value.toScalar.text

      val endpoint = producer(path).add(Annotations(entry))
      parent.map(p => endpoint.add(ParentEndPoint(p)))

      val map = entry.value.value.toMap

      validateClosedShape(endpoint.id, map, "endPoint")

      endpoint.set(Path, AmfScalar(path, Annotations(entry.key)))

      map.key("displayName", entry => {
        val value = ValueNode(entry.value)
        endpoint.set(EndPointModel.Name, value.string(), Annotations(entry))
      })

      map.key("description", entry => {
        val value = ValueNode(entry.value)
        endpoint.set(EndPointModel.Description, value.string(), Annotations(entry))
      })

      map.key(
        "uriParameters",
        entry => {
          val parameters: Seq[Parameter] =
            ParametersParser(entry.value.value.toMap, endpoint.withParameter, declarations)
              .parse()
              .map(_.withBinding("path"))
          endpoint.set(EndPointModel.UriParameters, AmfArray(parameters, Annotations(entry.value)), Annotations(entry))
        }
      )

      map.key(
        "type",
        entry =>
          ParametrizedDeclarationParser(entry.value.value, endpoint.withResourceType, declarations.resourceTypes)
            .parse()
      )

      map.key(
        "is",
        entry => {
          entry.value.value.toSequence.values.map(value =>
            ParametrizedDeclarationParser(value, endpoint.withTrait, declarations.traits).parse())
        }
      )

      map.regex(
        "get|patch|put|post|delete|options|head",
        entries => {
          val operations = mutable.ListBuffer[Operation]()
          entries.foreach(entry => {
            operations += OperationParser(entry, endpoint.withOperation, declarations).parse()
          })
          endpoint.set(EndPointModel.Operations, AmfArray(operations))
        }
      )

      collector += endpoint

      AnnotationParser(() => endpoint, map).parse()

      map.regex(
        "^/.*",
        entries => {
          entries.foreach(EndpointParser(_, producer, Some(endpoint), collector, declarations).parse())
        }
      )
    }
  }

  case class RequestParser(map: YMap, producer: () => Request, declarations: Declarations) {

    def parse(): Option[Request] = {
      val request = new Lazy[Request](producer)
      map.key(
        "queryParameters",
        entry => {

          val parameters: Seq[Parameter] =
            ParametersParser(entry.value.value.toMap, request.getOrCreate.withQueryParameter, declarations)
              .parse()
              .map(_.withBinding("query"))
          request.getOrCreate.set(RequestModel.QueryParameters,
                                  AmfArray(parameters, Annotations(entry.value)),
                                  Annotations(entry))
        }
      )

      map.key(
        "headers",
        entry => {
          val parameters: Seq[Parameter] =
            ParametersParser(entry.value.value.toMap, request.getOrCreate.withHeader, declarations)
              .parse()
              .map(_.withBinding("header"))
          request.getOrCreate.set(RequestModel.Headers,
                                  AmfArray(parameters, Annotations(entry.value)),
                                  Annotations(entry))
        }
      )

      map.key(
        "body",
        entry => {
          val payloads = mutable.ListBuffer[Payload]()

          RamlTypeParser(entry, shape => shape.withName("default").adopted(request.getOrCreate.id), declarations)
            .parse()
            .foreach(payloads += request.getOrCreate.withPayload(None).withSchema(_)) // todo

          entry.value.value.toMap
            .regex(
              ".*/.*",
              entries => {
                entries.foreach(entry => {
                  payloads += PayloadParser(entry, producer = request.getOrCreate.withPayload, declarations).parse()
                })
              }
            )
          if (payloads.nonEmpty)
            request.getOrCreate
              .set(RequestModel.Payloads, AmfArray(payloads, Annotations(entry.value)), Annotations(entry))
        }
      )

      AnnotationParser(() => request.getOrCreate, map).parse()

      request.option
    }
  }

  case class OperationParser(entry: YMapEntry, producer: (String) => Operation, declarations: Declarations) {

    def parse(): Operation = {

      val method = entry.key.value.toScalar.text

      val operation = producer(method).add(Annotations(entry))
      operation.set(Method, ValueNode(entry.key).string())

      entry.value.value match {
        // Empty operation
        case s: YScalar if s.text == "" => operation

        // Regular operation
        case map: YMap =>


          validateClosedShape(operation.id, map, "operation")

          map.key("displayName", entry => {
            val value = ValueNode(entry.value)
            operation.set(OperationModel.Name, value.string(), Annotations(entry))
          })

          map.key("description", entry => {
            val value = ValueNode(entry.value)
            operation.set(OperationModel.Description, value.string(), Annotations(entry))
          })

          map.key("(deprecated)", entry => {
            val value = ValueNode(entry.value)
            operation.set(OperationModel.Deprecated, value.boolean(), Annotations(entry))
          })

          map.key("(summary)", entry => {
            val value = ValueNode(entry.value)
            operation.set(OperationModel.Summary, value.string(), Annotations(entry))
          })

          map.key(
            "(externalDocs)",
            entry => {
              val creativeWork: CreativeWork = OasCreativeWorkParser(entry.value.value.toMap).parse()
              operation.set(OperationModel.Documentation, creativeWork, Annotations(entry))
            }
          )

          map.key(
            "protocols",
            entry => {
              val value = ArrayNode(entry.value.value.toSequence)
              operation.set(OperationModel.Schemes, value.strings(), Annotations(entry))
            }
          )

          map.key("(consumes)", entry => {
            val value = ArrayNode(entry.value.value.toSequence)
            operation.set(OperationModel.Accepts, value.strings(), Annotations(entry))
          })

          map.key("(produces)", entry => {
            val value = ArrayNode(entry.value.value.toSequence)
            operation.set(OperationModel.ContentType, value.strings(), Annotations(entry))
          })

          map.key(
            "is",
            entry => {
              val traits = entry.value.value.toSequence.nodes.map(value => {
                ParametrizedDeclarationParser(value.value, operation.withTrait, declarations.traits).parse()
              })
              if (traits.nonEmpty) operation.setArray(DomainElementModel.Extends, traits, Annotations(entry))
            }
          )

          RequestParser(map, () => operation.withRequest(), declarations)
            .parse()
            .map(operation.set(OperationModel.Request, _))

          map.key(
            "responses",
            entry => {
              entry.value.value.toMap.regex(
                "\\d{3}",
                entries => {
                  val responses = mutable.ListBuffer[Response]()
                  entries.foreach(entry => {
                    responses += ResponseParser(entry, operation.withResponse, declarations).parse()
                  })
                  operation.set(OperationModel.Responses,
                    AmfArray(responses, Annotations(entry.value)),
                    Annotations(entry))
                }
              )
            }
          )
          AnnotationParser(() => operation, map).parse()

          operation
      }
    }
  }

  case class ParametersParser(map: YMap, producer: String => Parameter, declarations: Declarations) {
    def parse(): Seq[Parameter] =
      map.entries
        .map(entry => ParameterParser(entry, producer, declarations).parse())
  }

  // todo review!
  case class PayloadParser(entry: YMapEntry, producer: (Option[String]) => Payload, declarations: Declarations) {
    def parse(): Payload = {

      val payload = producer(Some(ValueNode(entry.key).string().value.toString)).add(Annotations(entry))

      entry.value.value match {
        case map: YMap =>
          // TODO
          // Should we clean the annotations here so they are not parsed again in the shape?
          AnnotationParser(() => payload, map).parse()
        case _ =>
      }

      entry.value.tag.tagType match {
        case YType.Null =>
        case _ =>
          RamlTypeParser(entry, shape => shape.withName("schema").adopted(payload.id), declarations)
            .parse()
            .foreach(payload.withSchema)

      }
      payload
    }
  }

  case class ResponseParser(entry: YMapEntry, producer: (String) => Response, declarations: Declarations) {
    def parse(): Response = {

      val node = ValueNode(entry.key)

      val response = producer(node.string().value.toString).add(Annotations(entry))
      val map      = entry.value.value.toMap

      validateClosedShape(response.id, map, "response")

      response.set(ResponseModel.StatusCode, node.string())

      map.key("description", entry => {
        val value = ValueNode(entry.value)
        response.set(ResponseModel.Description, value.string(), Annotations(entry))
      })

      map.key(
        "headers",
        entry => {
          val parameters: Seq[Parameter] =
            ParametersParser(entry.value.value.toMap, response.withHeader, declarations)
              .parse()
              .map(_.withBinding("header"))
          response.set(RequestModel.Headers, AmfArray(parameters, Annotations(entry.value)), Annotations(entry))
        }
      )

      map.key(
        "body",
        entry => {
          val payloads = mutable.ListBuffer[Payload]()

          val payload = Payload()
          payload.adopted(response.id) // TODO review

          RamlTypeParser(entry, shape => shape.withName("default").adopted(payload.id), declarations)
            .parse()
            .foreach(payloads += payload.withSchema(_))

          entry.value.value match {
            case map: YMap =>
              map.regex(
                ".*/.*",
                entries => {
                  entries.foreach(entry => {
                    payloads += PayloadParser(entry, response.withPayload, declarations).parse()
                  })
                }
              )
            case _ =>
          }
          if (payloads.nonEmpty)
            response.set(RequestModel.Payloads, AmfArray(payloads, Annotations(entry.value)), Annotations(entry))
        }
      )

      AnnotationParser(() => response, map).parse()

      response
    }
  }

}

abstract class RamlSpecParser extends BaseSpecParser {

  override implicit val spec = RamlSpecParserContext

  protected def parseDeclarations(root: Root, map: YMap, declarations: Declarations): Unit = {
    val parent = root.location + "#/declarations"
    parseTypeDeclarations(map, parent, declarations)
    parseAnnotationTypeDeclarations(map, parent, declarations)
    parseResourceTypeDeclarations("resourceTypes", map, parent, declarations)
    parseTraitDeclarations("traits", map, parent, declarations)
    parseParameterDeclarations("(parameters)", map, root.location + "#/parameters", declarations)
    declarations.resolve()
  }

  def parseAnnotationTypeDeclarations(map: YMap, customProperties: String, declarations: Declarations): Unit = {

    map.key(
      "annotationTypes",
      e => {
        e.value.value.toMap.entries.map(entry => {
          val typeName = entry.key.value.toScalar.text
          val customProperty = AnnotationTypesParser(entry,
                                                     customProperty =>
                                                       customProperty
                                                         .withName(typeName)
                                                         .adopted(customProperties),
                                                     declarations)
          declarations += customProperty.add(DeclaredElement())
        })
      }
    )
  }

  private def parseTypeDeclarations(map: YMap, parent: String, declarations: Declarations): Unit = {
    map.key(
      "types",
      e => {
        e.value.value.toMap.entries.foreach { entry =>
          RamlTypeParser(entry, shape => shape.withName(entry.key).adopted(parent), declarations).parse() match {
            case Some(shape) => declarations += shape.add(DeclaredElement())
            case None        => throw new Exception(s"Error parsing shape '$entry'")
          }
        }
      }
    )
  }

  def parseParameterDeclarations(key: String,
                                 map: YMap,
                                 parentPath: String,
                                 declarations: Declarations): Unit = {
    map.key(
      key,
      entry => {
        entry.value.value.toMap.entries.foreach(e => {
          val parameter = ParameterParser(e, (name) => Parameter().withId(parentPath + "/" + name).withName(name), declarations).parse()
          if (Option(parameter.binding).isEmpty) {
            throw new Exception("Missing binding information in declared parameter")
          }
          declarations.registerParameter(parameter.add(DeclaredElement()), Payload().withSchema(parameter.schema))
        })
      }
    )
  }

  case class ParameterParser(entry: YMapEntry, producer: String => Parameter, declarations: Declarations) extends RamlTypeSyntax {

    def parse(): Parameter = {

      val name = entry.key.value.toScalar.text
      val parameter = producer(name).add(Annotations(entry)) // TODO parameter id is using a name that is not final.
      entry.value.value match {
        case ref: YScalar if declarations.findParameter(ref.text).isDefined =>
          declarations.findParameter(ref.text).get.link(ref.text, Annotations(entry)).asInstanceOf[Parameter].withName(name)

        case ref: YScalar if declarations.findType(ref.text).isDefined =>
          val schema = declarations.findType(ref.text).get.link[Shape](ref.text, Annotations(entry)).withName("schema").adopted(parameter.id)
          parameter.withSchema(schema)

        case ref: YScalar if wellKnownType(ref.text) =>
          val schema = parseWellKnownTypeRef(ref.text).withName("schema").adopted(parameter.id)
          parameter.withSchema(schema)

        case _: YScalar =>
          throw new Exception("Cannot declare unresolved parameter")

        case map: YMap =>
          val map = entry.value.value.toMap

          map.key("required", entry => {
            val value = ValueNode(entry.value)
            parameter.set(ParameterModel.Required, value.boolean(), Annotations(entry) += ExplicitField())
          })

          if (parameter.fields.entry(ParameterModel.Required).isEmpty) {
            val required = !name.endsWith("?")

            parameter.set(ParameterModel.Required, required)
            parameter.set(ParameterModel.Name, if (required) name else name.stripSuffix("?"))
          }

          map.key("description", entry => {
            val value = ValueNode(entry.value)
            parameter.set(ParameterModel.Description, value.string(), Annotations(entry))
          })

          map.key("(binding)", entry => {
            val value = ValueNode(entry.value)
            val annotations: Annotations = Annotations(entry) += ExplicitField()
            parameter.set(ParameterModel.Binding, value.string(), annotations)
          })

          RamlTypeParser(entry, shape => shape.withName("schema").adopted(parameter.id), declarations)
            .parse()
            .foreach(parameter.set(ParameterModel.Schema, _, Annotations(entry)))

          AnnotationParser(() => parameter, map).parse()

          parameter
      }
    }
  }


  case class UsageParser(map: YMap, baseUnit: BaseUnit) {
    def parse(): Unit = {
      map.key("usage", entry => {
        val value = ValueNode(entry.value)
        baseUnit.set(BaseUnitModel.Usage, value.string(), Annotations(entry))
      })
    }
  }

  case class UserDocumentationsParser(seq: YSequence, declarations: Declarations) {
    def parse(): Seq[CreativeWork] = {
      val results = ListBuffer[CreativeWork]()

      seq.nodes
        .foreach(n =>
          n.value match {
            case m: YMap => results += RamlCreativeWorkParser(m, withExtention = true).parse()
            case scalar: YScalar =>
              declarations.findDocumentations(scalar.text) match {
                case Some(doc) =>
                  results += doc.link(scalar.text, Annotations()).asInstanceOf[CreativeWork]
                case _ => throw new IllegalArgumentException(s"not supported scalar $scalar.text for documentation")
              }
        })

      results
    }
  }

  object AnnotationTypesParser {
    def apply(ast: YMapEntry,
              adopt: (CustomDomainProperty) => Unit,
              declarations: Declarations): CustomDomainProperty =
      ast.value.value match {
        case map: YMap => AnnotationTypesParser(ast, ast.key.value.toScalar.text, map, adopt, declarations).parse()
        case scalar: YScalar =>
          LinkedAnnotationTypeParser(ast, ast.key.value.toScalar.text, scalar, adopt, declarations).parse()
        case _ => throw new IllegalArgumentException("Invalid value Ypart type for annotation types parser")
      }

  }

  case class LinkedAnnotationTypeParser(ast: YPart,
                                        annotationName: String,
                                        scalar: YScalar,
                                        adopt: (CustomDomainProperty) => Unit,
                                        declarations: Declarations) {
    def parse(): CustomDomainProperty = {
      declarations
        .findAnnotation(scalar.text)
        .map { a =>
          val copied: CustomDomainProperty = a.link(scalar.text, Annotations(ast))
          adopt(copied.withName(annotationName))
          copied
        }
        .getOrElse(throw new UnsupportedOperationException("Could not find declared annotation link in references"))
    }
  }

  case class AnnotationTypesParser(ast: YPart,
                                   annotationName: String,
                                   map: YMap,
                                   adopt: (CustomDomainProperty) => Unit,
                                   declarations: Declarations) extends RamlSyntax {
    def parse(): CustomDomainProperty = {

      val custom = CustomDomainProperty(ast)
      custom.withName(annotationName)
      adopt(custom)

      validateClosedShape(custom.id, map, "annotation")

      map.key(
        "allowedTargets",
        entry => {
          val annotations = Annotations(entry)
          val targets: AmfArray = entry.value.value match {
            case _: YScalar =>
              annotations += SingleValueArray()
              AmfArray(Seq(ValueNode(entry.value).string()))
            case sequence: YSequence =>
              ArrayNode(sequence).strings()
          }

          val targetUris = targets.values.map({
            case s: AmfScalar =>
              VocabularyMappings.ramlToUri.get(s.toString) match {
                case Some(uri) => AmfScalar(uri, s.annotations)
                case None      => s
              }
            case nodeType => AmfScalar(nodeType.toString, nodeType.annotations)
          })

          custom.set(CustomDomainPropertyModel.Domain, AmfArray(targetUris), annotations)
        }
      )

      map.key("displayName", entry => {
        val value = ValueNode(entry.value)
        custom.set(CustomDomainPropertyModel.DisplayName, value.string(), Annotations(entry))
      })

      map.key("description", entry => {
        val value = ValueNode(entry.value)
        custom.set(CustomDomainPropertyModel.Description, value.string(), Annotations(entry))
      })

      map.key(
        "type",
        entry => {
          RamlTypeParser(entry, shape => shape.adopted(custom.id), declarations)
            .parse()
            .foreach({ shape =>
              custom.set(CustomDomainPropertyModel.Schema, shape, Annotations(entry))
            })
        }
      )

      AnnotationParser(() => custom, map).parse()

      custom
    }
  }
}

object RamlSpecParserContext extends SpecParserContext {

  override def link(node: YNode): Either[String, YNode] = {
    node match {
      case _ if isInclude(node) => Left(node.value.toScalar.text)
      case _                    => Right(node)
    }
  }

  private def isInclude(node: YNode) = {
    node.tagType == YType.Unknown && node.tag.text == "!include"
  }
}
