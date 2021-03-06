package amf.compiler

import amf.client.parse.DefaultParserErrorHandler
import amf.core.model.document.BaseUnit
import amf.core.parser.errorhandler.{ParserErrorHandler, UnhandledParserErrorHandler}
import amf.core.remote.{Cache, Hint}
import amf.core.unsafe.PlatformSecrets
import amf.facades.{AMFCompiler, Validation}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

trait CompilerTestBuilder extends PlatformSecrets {

  protected def build(url: String,
                      hint: Hint,
                      cache: Option[Cache] = None,
                      validation: Option[Validation] = None,
                      eh: Option[ParserErrorHandler] = None): Future[BaseUnit] =
    compiler(url, hint, cache, resolveValidation(validation), eh).flatMap(_.build())

  private def resolveValidation(validation: Option[Validation]) = validation match {
    case Some(v) => Future { v }
    case _       => Validation(platform)
  }

  protected def compiler(url: String, hint: Hint): Future[AMFCompiler] =
    compiler(url, hint, validation = Validation(platform))

  protected def compiler(url: String,
                         hint: Hint,
                         cache: Option[Cache] = None,
                         validation: Future[Validation],
                         eh: Option[ParserErrorHandler] = None): Future[AMFCompiler] =
    validation.map(v =>
      AMFCompiler(url, platform, hint, cache = cache, eh = eh.getOrElse(DefaultParserErrorHandler.withRun())))
}
