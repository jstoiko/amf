package amf.dialects

import amf.client.model.document.Dialect
import amf.core.model.document.BaseUnit
import amf.core.rdf.RdfModel
import amf.core.remote.{Amf, Hint, Vendor, VocabularyYamlHint}
import amf.core.unsafe.PlatformSecrets
import amf.facades.{AMFCompiler, Validation}
import amf.io.BuildCycleTests
import org.scalatest.{Assertion, AsyncFunSuite}

import scala.concurrent.{ExecutionContext, Future}

class DialectInstancesRDFTest extends AsyncFunSuite with PlatformSecrets with BuildCycleTests {

  override implicit val executionContext: ExecutionContext = ExecutionContext.Implicits.global

  val basePath = "amf-client/shared/src/test/resources/vocabularies2/instances/"

  test("RDF 1 test") {
    withDialect("dialect1.raml", "example1.raml", "example1.ttl", VocabularyYamlHint, Amf)
  }



  /** Method for transforming parsed unit. Override if necessary. */
  def transformRdf(unit: BaseUnit, config: CycleConfig): RdfModel = {
    unit.toNativeRdfModel()
  }

  /** Method to render parsed unit. Override if necessary. */
  def renderRdf(unit: RdfModel, config: CycleConfig): Future[String] = {
    Future {
      unit.toN3().split("\n").sorted.mkString("\n")
    }
  }

  /** Compile source with specified hint. Render to temporary file and assert against golden. */
  final def cycleRdf(source: String,
                     golden: String,
                     hint: Hint = VocabularyYamlHint,
                     target: Vendor = Amf,
                     directory: String = basePath,
                     validation: Option[Validation] = None): Future[Assertion] = {

    val config = CycleConfig(source, golden, hint, target, directory)

    build(config, validation)
      .map(transformRdf(_, config))
      .flatMap(renderRdf(_, config))
      .flatMap(writeTemporaryFile(golden))
      .flatMap(assertDifferences(_, config.goldenPath))
  }

  protected def withDialect(dialect: String,
                            source: String,
                            golden: String,
                            hint: Hint,
                            target: Vendor,
                            directory: String = basePath) = {
    for {
      v         <- Validation(platform).map(_.withEnabledValidation(false))
      something <- AMFCompiler(s"file://$directory/$dialect", platform, VocabularyYamlHint, v).build()
      res       <- cycleRdf(source, golden, hint, target)
    } yield {
      res
    }
  }

}
