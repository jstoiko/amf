package amf.resolution.pipelines

import amf.ProfileNames
import amf.document.BaseUnit
import amf.resolution.stages._

class AmfResolutionPipeline extends ResolutionPipeline {
  val references = new ReferenceResolutionStage(ProfileNames.AMF)
  val shapes     = new ShapeNormalizationStage(ProfileNames.AMF)
  val parameters = new ParametersNormalizationStage(ProfileNames.AMF)
  val security   = new SecurityResolutionStage(ProfileNames.AMF)
  val mediaTypes = new MediaTypeResolutionStage(ProfileNames.AMF)
  val examples   = new ExamplesResolutionStage(ProfileNames.AMF)

  override def resolve(model: BaseUnit): BaseUnit = {
    withModel(model) { () =>
      commonSteps()
      step(parameters)
      step(mediaTypes)
      step(examples)
    }
  }

  protected def commonSteps(): Unit = {
    step(references)
    step(shapes)
    step(security)
  }
}
