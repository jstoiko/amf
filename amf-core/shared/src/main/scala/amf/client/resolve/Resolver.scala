package amf.client.resolve

import amf.client.convert.CoreClientConverters._
import amf.client.model.document.BaseUnit
import amf.core.resolution.pipelines.ResolutionPipeline
import amf.core.services.RuntimeResolver

import scala.scalajs.js.annotation.JSExportAll

@JSExportAll
class Resolver(vendor: String) {
  def resolve(unit: BaseUnit): BaseUnit = RuntimeResolver.resolve(vendor, unit, ResolutionPipeline.DEFAULT_PIPELINE)
  def resolve(unit: BaseUnit, pipelineId: String) = RuntimeResolver.resolve(vendor, unit, pipelineId)
}
