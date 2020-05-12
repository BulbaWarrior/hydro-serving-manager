package io.hydrosphere.serving.manager.domain.servable

import cats.effect.concurrent.{Deferred, Ref}
import cats.effect.implicits._
import cats.effect.{Concurrent, Fiber, Timer}
import cats.implicits._
import fs2.concurrent.Queue
import io.hydrosphere.serving.manager.util.UnsafeLogging

import scala.concurrent.duration._

trait ServableMonitor[F[_]] {

  /***
    * Sets a monitor checking the availability of Servable
    *
    * @param servable to be monitored
    * @return a deferred that will be completed when Servable reaches one of final states.
    */
  def monitor(servable: Servable): F[Deferred[F, Servable]]
}

object ServableMonitor extends UnsafeLogging {
  final case class CancellableMonitor[F[_]](mon: ServableMonitor[F], fiber: Fiber[F, Unit])
  type MonitoringEntry[F[_]] = (Servable, Deferred[F, Servable])

  def withQueue[F[_]](
      queue: Queue[F, MonitoringEntry[F]],
      monitorSleep: FiniteDuration,
      maxTimeout: FiniteDuration
  )(implicit
      F: Concurrent[F],
      timer: Timer[F],
      probe: ServableProbe[F],
      servableRepository: ServableRepository[F]
  ): F[CancellableMonitor[F]] =
    for {
      deathNoteRef <- Ref.of(Map.empty[String, FiniteDuration])
      fbr <-
        monitoringLoop(monitorSleep, maxTimeout, queue, deathNoteRef)
          .handleError(x => logger.error(s"Error in monitoring loop", x))
          .foreverM[Unit]
          .start
      mon = new ServableMonitor[F] {
        override def monitor(servable: Servable): F[Deferred[F, Servable]] =
          for {
            deferred <- Deferred[F, Servable]
            _        <- queue.offer1(servable, deferred)
            _        <- F.delay(logger.debug(s"Offered to the monitoring queue: ${servable.fullName}"))
          } yield deferred
      }
    } yield CancellableMonitor(mon, fbr)

  def default[F[_]](
      probe: ServableProbe[F],
      servableRepository: ServableRepository[F]
  )(implicit
      F: Concurrent[F],
      timer: Timer[F]
  ): F[CancellableMonitor[F]] =
    for {
      queue <- Queue.unbounded[F, MonitoringEntry[F]]
      res <-
        withQueue(queue, 2.seconds, 1.minute)(
          F,
          timer,
          probe,
          servableRepository
        ) //TODO(bulat) refactor
    } yield res

  private def monitoringLoop[F[_]](
      monitorSleep: FiniteDuration,
      maxTimeout: FiniteDuration,
      queue: Queue[F, MonitoringEntry[F]],
      deathNoteRef: Ref[F, Map[String, FiniteDuration]]
  )(implicit
      F: Concurrent[F],
      timer: Timer[F],
      probe: ServableProbe[F],
      servableRepository: ServableRepository[F]
  ): F[Unit] =
    for {
      (servable, deferred) <- queue.dequeue1
      name = servable.fullName
      deathNote   <- deathNoteRef.get
      probeResult <- probe.probe(servable)
      updatedServable = servable.copy(
        status = probeResult.getStatus,
        host = probeResult.getHost,
        port = probeResult.getPort,
        msg = probeResult.message
      )
      _ <- F.delay(logger.debug(s"Probed: ${updatedServable.fullName}"))
      _ <- probeResult.getStatus match {
        case Servable.Status.Serving =>
          servableRepository.upsert(updatedServable) >>
            deferred.complete(updatedServable) >>
            deathNoteRef.set(deathNote - name).void

        case Servable.Status.NotServing =>
          servableRepository.upsert(updatedServable) >>
            deferred.complete(updatedServable) >>
            deathNoteRef.set(deathNote - name).void

        case Servable.Status.Starting =>
          queue.offer1(updatedServable, deferred) >>
            deathNoteRef.set(deathNote - name) >>
            timer.sleep(monitorSleep).void

        case Servable.Status.NotAvailable =>
          deathNote.get(name) match {
            case Some(timeInTheList) =>
              if (timeInTheList >= maxTimeout) {
                val invalidServable = updatedServable
                  .copy(
                    status = Servable.Status.NotServing,
                    msg = s"Ping timeout exceeded. Info: ${probeResult.message}",
                    host = probeResult.getHost,
                    port = probeResult.getPort
                  )

                deathNoteRef.set(deathNote - name) >>
                  servableRepository.upsert(invalidServable) >>
                  deferred.complete(invalidServable).void
              } else
                deathNoteRef.set(deathNote + (name -> (timeInTheList + monitorSleep))) >>
                  queue.offer1(updatedServable, deferred) >>
                  timer.sleep(monitorSleep).void
            case None =>
              deathNoteRef.set(deathNote + (name -> monitorSleep)) >>
                queue.offer1(updatedServable, deferred) >>
                timer.sleep(monitorSleep).void
          }
      }
    } yield ()
}
