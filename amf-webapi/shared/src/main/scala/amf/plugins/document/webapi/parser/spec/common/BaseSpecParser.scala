package amf.plugins.document.webapi.parser.spec.common

import amf.core.annotations.{DomainExtensionAnnotation, ExplicitField, SingleValueArray}
import amf.core.metamodel.Type.ArrayLike
import amf.core.metamodel.{Field, Obj, Type}
import amf.core.model.domain.extensions.DomainExtension
import amf.core.model.domain.{ArrayNode => _, ScalarNode => _, _}
import amf.core.parser._
import amf.plugins.document.webapi.contexts.WebApiContext
import amf.plugins.document.webapi.parser.spec.common.WellKnownAnnotation.isRamlAnnotation
import amf.plugins.features.validation.ParserSideValidations
import org.yaml.model._

import scala.collection.mutable.ListBuffer

trait WebApiBaseSpecParser extends BaseSpecParser with SpecParserOps

trait SpecParserOps {

  protected def checkBalancedParams(path: String, value: YNode, node: String, property: String, ctx: WebApiContext) = {
    val pattern1 = "\\{[^\\}]*\\{".r
    val pattern2 = "\\}[^\\{]*\\}".r
    if (pattern1.findFirstMatchIn(path).nonEmpty || pattern2.findFirstMatchIn(path).nonEmpty) {
      ctx.violation(
        ParserSideValidations.PathTemplateUnbalancedParameters.id(),
        node,
        Some(property),
        "Invalid path template syntax",
        value
      )
    }
  }

  class ObjectField(target: Target, field: Field)(implicit iv: WebApiContext) extends Function1[YMapEntry, Unit] {

    // Custom annotations
    private val custom: Annotations = Annotations()
    // Entry annotations
    private val annotations: Annotations = Annotations()

    private var mapped: Option[YNode => AmfElement]    = None
    private var typed: Option[TypedNode => AmfElement] = None

    private var single    = false
    private var entries   = false
    private var annotated = false
    private var opt       = false

    /** Allow scalar-valued annotations. */
    def allowingAnnotations: ObjectField = {
      annotated = true
      this
    }

    /** Allow parsing a single node when expecting array. */
    def allowingSingleValue: ObjectField = {
      single = true
      this
    }

    /** Allow parsing map node entries as array elements when expecting array. */
    def treatMapAsArray: ObjectField = {
      entries = true
      this
    }

    /** Optional element. Do not fail on YType.Null. */
    def optional: ObjectField = {
      opt = true
      this
    }

    def string: ObjectField = {
      typed = Some(_.string())
      this
    }

    def negated: ObjectField = {
      typed = Some(_.negated())
      this
    }

    def using(fn: (YNode) => AmfElement): ObjectField = {
      mapped = Some(fn)
      this
    }

    def explicit: ObjectField = withAnnotation(ExplicitField())

    def withAnnotation(a: Annotation): ObjectField = {
      annotations += a
      this
    }

    override def apply(entry: YMapEntry): Unit = {
      val node = entry.value
      entry.value.tagType match {
        case YType.Null if opt =>
        case _ =>
          val value = field.`type` match {
            case _: Obj if mapped.isDefined => mapped.get(node)
            case ArrayLike(element)         => parseArray(node, element)
            case element                    => parseScalar(node, element)
          }
          value.annotations ++= custom
          target.foreach(_.set(field, value, Annotations(entry) ++= annotations))
      }
    }

    private def parseScalar(node: YNode, element: Type): AmfElement = {
      val scalar = if (annotated) parseScalarValued(node) else ScalarNode(node)
      element match {
        case _ if typed.isDefined => typed.get(scalar)
        case Type.Int             => scalar.integer()
        case Type.Bool            => scalar.boolean()
        case _                    => scalar.text()
      }
    }

    private def parseArray(node: YNode, element: Type): AmfElement = {
      val array = createArrayNode(node)
      element match {
        case _: Obj if mapped.isDefined => array.obj(mapped.get)
        case _ if typed.isDefined       => typed.get(array)
        case Type.Int                   => array.integer()
        case Type.Bool                  => array.boolean()
        case _                          => array.text()
      }
    }

    private def createArrayNode(node: YNode): ArrayNode = {
      if (single) SingleArrayNode(node)
      else if (entries) MapArrayNode(node)
      else ArrayNode(node)
    }

    private def parseScalarValued(node: YNode) = {
      val result = RamlScalarNode(node)
      custom ++= Annotations(collectDomainExtensions(null, result).map(DomainExtensionAnnotation))
      result
    }

    private def collectDomainExtensions(parent: String, n: ScalarNode): Seq[DomainExtension] = {
      n match {
        case n: RamlScalarValuedNode =>
          AnnotationParser.parseExtensions(s"$parent/annotation", n.obj)
        case n: DefaultScalarNode =>
          Nil
      }
    }
  }

  implicit class FieldOps(field: Field)(implicit iv: WebApiContext) {
    def in(elem: DomainElement): ObjectField = in(SingleTarget(elem))
    def in(target: Target): ObjectField      = new ObjectField(target, field)
  }

  trait Target {
    def foreach(fn: DomainElement => Unit)
  }

  private case class SingleTarget(elem: DomainElement) extends Target {
    override def foreach(fn: DomainElement => Unit): Unit = fn(elem)
  }

  case object EmptyTarget extends Target {
    override def foreach(fn: DomainElement => Unit): Unit = {}
  }
}

/** Scalar valued raml node (based on obj node). */
private case class RamlScalarValuedNode(obj: YMap, scalar: Option[ScalarNode])(implicit iv: WebApiContext)
    extends ScalarNode {

  override def string(): AmfScalar  = as(_.string())
  override def text(): AmfScalar    = as(_.text())
  override def integer(): AmfScalar = as(_.integer())
  override def boolean(): AmfScalar = as(_.boolean())
  override def negated(): AmfScalar = as(_.negated())

  private def as(fn: ScalarNode => AmfScalar) = scalar.map(fn).getOrElse(AmfScalar(null))
}

private case class RamlSingleArrayNode(node: YNode)(implicit iv: WebApiContext) extends ArrayNode {

  override def string(): AmfArray                     = as(ScalarNode(node).string())
  override def text(): AmfArray                       = as(ScalarNode(node).text())
  override def integer(): AmfArray                    = as(ScalarNode(node).integer())
  override def boolean(): AmfArray                    = as(ScalarNode(node).boolean())
  override def negated(): AmfArray                    = as(ScalarNode(node).negated())
  override def obj(fn: YNode => AmfElement): AmfArray = as(fn(node))

  private def as(element: AmfElement) =
    AmfArray(Seq(element), Annotations(node.value) += SingleValueArray())
}

object SingleArrayNode {
  def apply(node: YNode)(implicit iv: WebApiContext): ArrayNode = {
    node.value match {
      case seq: YSequence => ArrayNode(seq)
      case _              => RamlSingleArrayNode(node)
    }
  }
}

/** Map array node. */
case class MapEntriesArrayNode(obj: YMap)(override implicit val iv: IllegalTypeHandler) extends BaseArrayNode {

  override def nodes: (Seq[YNode], YPart) = {
    val maps = obj.entries.map(e => YNode(YMap(IndexedSeq(e))))
    (maps, obj)
  }
}

object MapArrayNode {
  def apply(node: YNode)(implicit iv: WebApiContext): ArrayNode = MapEntriesArrayNode(node.as[YMap])(iv)
}

object RamlScalarNode {
  def apply(node: YNode)(implicit iv: WebApiContext): ScalarNode = {
    node.value match {
      case obj: YMap => createScalarValuedNode(obj)
      case _         => ScalarNode(node)
    }
  }

  private def createScalarValuedNode(obj: YMap)(implicit iv: WebApiContext): RamlScalarValuedNode = {
    var values = ListBuffer[YMapEntry]()

    obj.entries.foreach { entry =>
      entry.key.value match {
        case scalar: YScalar =>
          scalar.text match {
            case "value"                      => values += entry
            case key if isRamlAnnotation(key) => // Valid annotation ;)
            case _                            => unexpected(entry.key)
          }
        case _ => unexpected(entry.key)
      }
    }

    if (values.nonEmpty) {
      values.tail.foreach(d => iv.violation(s"Duplicated key 'value'.", d))
    }

    RamlScalarValuedNode(obj, values.headOption.map(entry => ScalarNode(entry.value)))
  }

  private def unexpected(key: YNode)(implicit iv: WebApiContext): Unit =
    iv.violation(s"Unexpected key '$key'. Options are 'value' or annotations \\(.+\\)", key)
}
