package io.hydrosphere.serving.manager.domain.model_version

import java.time.Instant

import io.circe.generic.JsonCodec
import io.hydrosphere.serving.manager.domain.application.Application
import io.hydrosphere.serving.manager.domain.contract.Contract
import io.hydrosphere.serving.manager.domain.host_selector.HostSelector
import io.hydrosphere.serving.manager.domain.image.DockerImage
import io.hydrosphere.serving.manager.domain.model.Model

@JsonCodec
case class ModelVersionView(
    id: Long,
    created: Instant,
    finished: Option[Instant],
    modelVersion: Long,
    modelContract: Contract,
    model: Model,
    status: String,
    metadata: Map[String, String],
    applications: List[String],
    image: Option[DockerImage],
    runtime: Option[DockerImage],
    hostSelector: Option[HostSelector],
    isExternal: Boolean
)

object ModelVersionView {
  def fromVersion(amv: ModelVersion, applications: List[Application]): ModelVersionView =
    amv match {
      case internalMV: ModelVersion.Internal =>
        ModelVersionView(
          id = internalMV.id,
          image = Some(internalMV.image),
          created = internalMV.created,
          finished = internalMV.finished,
          modelVersion = internalMV.modelVersion,
          modelContract = internalMV.modelContract,
          runtime = Some(internalMV.runtime),
          model = internalMV.model,
          hostSelector = internalMV.hostSelector,
          status = internalMV.status.toString,
          applications = applications.map(_.name),
          metadata = internalMV.metadata,
          isExternal = false
        )
      case ModelVersion.External(id, created, modelVersion, modelContract, model, metadata) =>
        ModelVersionView(
          id = id,
          image = None,
          created = created,
          finished = Some(created),
          modelVersion = modelVersion,
          modelContract = modelContract,
          runtime = None,
          model = model,
          hostSelector = None,
          status = ModelVersionStatus.Released.toString,
          applications = Nil,
          metadata = metadata,
          isExternal = true
        )
    }
}
