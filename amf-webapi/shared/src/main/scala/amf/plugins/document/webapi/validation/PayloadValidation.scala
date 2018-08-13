package amf.plugins.document.webapi.validation

import amf.ProfileNames
import amf.client.plugins.{AMFPayloadValidationPlugin, AMFPlugin, PayloadErrorHandler, PayloadParsingResult}
import amf.core.benchmark.ExecutionLog
import amf.core.model.document.{Module, PayloadFragment}
import amf.core.model.domain._
import amf.core.parser.{ParserContext, YNodeLikeOps}
import amf.core.services.{DefaultValidationOptions, RuntimeValidator}
import amf.core.validation.SeverityLevels._
import amf.core.validation._
import amf.core.validation.core.{PropertyConstraint, ValidationProfile, ValidationSpecification}
import amf.core.vocabulary.Namespace
import amf.internal.environment.Environment
import amf.plugins.document.webapi.contexts.PayloadContext
import amf.plugins.document.webapi.parser.spec.common.DataNodeParser
import amf.plugins.domain.shapes.models.{AnyShape, ScalarShape, SchemaShape}
import amf.plugins.domain.webapi.unsafe.JsonSchemaSecrets
import org.yaml.model._
import org.yaml.parser.{JsonParser, YamlParser}

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

case class PayloadValidation(validationCandidates: Seq[ValidationCandidate],
                             validations: EffectiveValidations = EffectiveValidations())
    extends WebApiValidations
    with JsonSchemaSecrets {

  val profiles: ListBuffer[ValidationProfile]                  = ListBuffer[ValidationProfile]()
  val validationsCache: mutable.Map[String, ValidationProfile] = mutable.Map()

  def validate(): Future[AMFValidationReport] = {
    // return validateWithShacl()
    jsonSchemaValidator.validate(validationCandidates,
                                 profiles.headOption.getOrElse(ValidationProfile(ProfileNames.AMF, None)))
  }

  def validateWithShacl(): Future[AMFValidationReport] = {
    validationCandidates.foreach { vc =>
      val dataNode = vc.payload.encodes
      addProfileTargets(dataNode, vc.shape)
    }

    val bu = Module().withId("http://test.com/payload").withDeclares(validationCandidates.map(_.payload.encodes))

    val finalValidations = EffectiveValidations()
    profiles.foreach { p =>
      finalValidations.someEffective(p)
    }

    ExecutionLog.log(s"PayloadValidation#validate: Validating payload for ${validationCandidates.size} candidates")
    RuntimeValidator.shaclValidation(bu, finalValidations, DefaultValidationOptions) map { report =>
      val results = report.results
        .map(r => buildPayloadValidationResult(bu, r, finalValidations))
        .filter(_.isDefined)
        .map(_.get)
      AMFValidationReport(
        conforms = !results.exists(_.level == VIOLATION),
        model = bu.id,
        profile = profiles.head.name,
        results = results
      )
    }
  }

  protected def addProfileTargets(dataNode: DataNode, shape: Shape): Unit = {
    val localProfile              = profileForShape(shape, dataNode)
    val entryValidation           = localProfile.validations.head
    val entryValidationWithTarget = entryValidation.copy(targetInstance = Seq(dataNode.id))
    //val restValidations           = localProfile.validations.tail

//      var finalValidations          = Seq(entryValidationWithTarget) ++ restValidations

    val targetValidations = new mutable.LinkedHashMap[String, ValidationSpecification]()
    targetValidations.put(entryValidationWithTarget.id, entryValidationWithTarget)
    for (v <- localProfile.validations.tail) targetValidations.put(v.id, v)

    val finalValidations = processTargets(entryValidation, dataNode, targetValidations)

    profiles += localProfile.copy(validations = finalValidations.values.toSeq)
  }

  protected def profileForShape(shape: Shape, dataNode: DataNode): ValidationProfile = {
    if (shape.isInstanceOf[AnyShape] && shape.asInstanceOf[AnyShape].supportsInheritance) {
      new AMFShapeValidations(shape).profile(dataNode)
    } else {
      validationsCache.get(shape.id) match {
        case Some(profile) => profile
        case None =>
          val profile = new AMFShapeValidations(shape).profile(dataNode)
          validationsCache.put(shape.id, profile)
          profile
      }
    }
  }

  def matchPatternedProperty(p: PropertyConstraint, propName: String): Boolean = {
    p.patternedProperty match {
      case Some(pattern) => pattern.r.findFirstIn(propName).isDefined
      case None          => false
    }
  }

  // Recursively traverse the tree of shape nodes and data nodes setting the target of the
  // shape validation to point to the matching node in the data nodes tree
  // We will also set pattern matching properties here.
  protected def processTargets(
      validation: ValidationSpecification,
      node: DataNode,
      validations: mutable.Map[String, ValidationSpecification]): mutable.Map[String, ValidationSpecification] =
    node match {
      case obj: ObjectNode  => processObjectNode(obj, validation, node, validations)
      case array: ArrayNode => processArrayNode(array, validation, node, validations)
      case _: ScalarNode    => filterValidations(validation, node, validations)
    }

  private def processObjectNode(obj: ObjectNode,
                                validation: ValidationSpecification,
                                node: DataNode,
                                validations: mutable.Map[String, ValidationSpecification]) = {
    // non pattern properties have precedence over pattern properties => let's sort them
    val (np, p) = validation.propertyConstraints
      .filterNot(p => p.ramlPropertyId startsWith Namespace.Rdf.base)
      .partition(_.patternedProperty.isEmpty)
    val allProperties = np ++ p

    var validationsAcc = validations

    for {
      (propName, nodes) <- obj.properties
      pc                <- allProperties
      if pc.ramlPropertyId.endsWith(s"#$propName") || matchPatternedProperty(pc, propName)
      itemsValidationId <- pc.node
      (id, v)           <- validationsAcc
      if id == itemsValidationId
    } {
      validationsAcc = processTargets(v, nodes, validationsAcc)
    }
    filterValidations(validation, node, validationsAcc)
  }

  private def processArrayNode(array: ArrayNode,
                               validation: ValidationSpecification,
                               node: DataNode,
                               validations: mutable.Map[String, ValidationSpecification]) = {
    var validationsAcc = validations
    for {
      pc <- validation.propertyConstraints
      if pc.ramlPropertyId == (Namespace.Rdf + "member").iri()
      itemsValidationId <- pc.node
      (id, v)           <- validationsAcc
      if id == itemsValidationId
      memberShape <- array.members
    } {
      validationsAcc = processTargets(v, memberShape, validationsAcc)
    }
    filterValidations(validation, node, validationsAcc)
  }

  private def filterValidations(validation: ValidationSpecification,
                                node: DataNode,
                                validations: mutable.Map[String, ValidationSpecification]) = {
    val newValidation = validation.withTarget(node.id)
    validations -= newValidation.id += ((newValidation.id, newValidation))
  }
}

object PayloadValidatorPlugin extends AMFPayloadValidationPlugin {

  override def canValidate(shape: Shape, env: Environment): Boolean = {
    shape match {
      case _: SchemaShape => false
      case _: AnyShape    => true
      case _              => false
    }
  }

  override val ID: String = "AMF Payload Validation"

  override def dependencies(): Seq[AMFPlugin] = Nil

  override def init(): Future[AMFPlugin] = Future.successful(this)

  override val payloadMediaType: Seq[String] = Seq("application/json", "application/yaml", "text/vnd.yaml")

  val defaultCtx = new PayloadContext("", Nil, ParserContext())

  override def parsePayloadWithErrorHandler(payload: String,
                                            mediaType: String,
                                            env: Environment,
                                            shape: Shape): PayloadParsingResult = {
    val fragment     = PayloadFragment(payload, mediaType)
    val errorHandler = new PayloadErrorHandler

    val parser = mediaType match {
      case "application/json" => JsonParser(payload)(errorHandler)
      case _                  => YamlParser(payload)(errorHandler)
    }
    parser.parse(keepTokens = true).collectFirst({ case doc: YDocument => doc.node }) match {
      case Some(node: YNode) if node.toOption[YScalar].isDefined && isString(shape) =>
        fragment.withEncodes(ScalarNode(payload, None))
      case Some(node: YNode) =>
        fragment.withEncodes(DataNodeParser(node, parent = Option(fragment.id))(defaultCtx).parse())
      case None => fragment.withEncodes(ScalarNode(payload, None))
    }
    PayloadParsingResult(fragment, errorHandler.getErrors)
  }

  override def parsePayload(payload: String, mediaType: String, env: Environment, shape: Shape): PayloadFragment = {
    val fragment = PayloadFragment(payload, mediaType)

    val parser = mediaType match {
      case "application/json" => JsonParser(payload)
      case _                  => YamlParser(payload)
    }
    parser.parse(keepTokens = true).collectFirst({ case doc: YDocument => doc.node }) match {
      case Some(node: YNode) if node.toOption[YScalar].isDefined && isString(shape) =>
        fragment.withEncodes(ScalarNode(payload, None))
      case Some(node: YNode) =>
        fragment.withEncodes(DataNodeParser(node, parent = Option(fragment.id))(defaultCtx).parse())
      case None => fragment.withEncodes(ScalarNode(payload, None))
    }
    fragment
  }

  private def isString(shape: Shape): Boolean = shape match {
    case s: ScalarShape => s.dataType.option().getOrElse("").equals((Namespace.Xsd + "string").iri())
    case _              => false
  }

  override def validateSet(set: ValidationShapeSet, env: Environment): Future[AMFValidationReport] =
    PayloadValidation(set.candidates).validate()
}
