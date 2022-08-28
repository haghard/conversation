package server.grpc

import akka.actor.CoordinatedShutdown
import akka.actor.CoordinatedShutdown.*
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse }
import com.typesafe.config.ConfigFactory

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }
import scala.concurrent.duration.*

import shared.Exts.*

object Bootstrap:

  case object BindFailure extends Reason

  def run(args: Array[String]): Unit =

    val appConf = scala2.Bridge.readAppConfig("conversation")
    given sys: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "conversation", ConfigFactory.load())
    given ec: ExecutionContext = sys.executionContext

    val host = Try(args(0)).getOrElse("127.0.0.1")
    val port = Try(args(1).toInt).getOrElse(appConf.port)

    val logger = sys.log
    val shutdown = CoordinatedShutdown(sys)

    val terminationDeadline =
      sys.settings.config.getDuration("akka.coordinated-shutdown.default-phase-timeout").asScala

    val (((sinkHub, dc), ks), srcHub) = ConversationServiceImpl.allocate(appConf)

    val grpcService: HttpRequest => Future[HttpResponse] =
      server.grpc.ConversationServiceHandler.withServerReflection(new ConversationServiceImpl(srcHub, sinkHub))

    Http()(sys)
      .newServerAt(host, port)
      .bind(grpcService)
      // .failed.foreach()
      .onComplete {
        case Failure(ex) =>
          logger.error(s"Shutting down because can't bind to $host:$port", ex)
          shutdown.run(Bootstrap.BindFailure)
        case Success(binding) =>
          logger.info("★ ★ ★ ★ ★ ★ ★ ★ ★ ActorSystem({}) tree ★ ★ ★ ★ ★ ★ ★ ★ ★", sys.name)
          logger.info(sys.printTree)

          shutdown.addTask(PhaseBeforeServiceUnbind, "before-unbind") { () =>
            Future {
              dc.drainAndComplete()
              logger.info("★ ★ ★ before-unbind [draining existing connections]  ★ ★ ★")
            }.flatMap { _ =>
              akka.pattern.after(1.seconds) {
                logger.info("★ ★ ★ before-unbind [drain-sharding.shutdown] ★ ★ ★")
                ks.shutdown()
                akka.pattern.after(1.seconds)(Future.successful(akka.Done))
              }
            }
          // 1 + 1 < 5
          }

          // Next 2 tasks(PhaseServiceUnbind, PhaseServiceRequestsDone) makes sure that during shutdown
          // no more requests are accepted and
          // all in-flight requests have been processed

          shutdown.addTask(PhaseServiceUnbind, "http-unbind") { () =>
            // No new connections are accepted. Existing connections are still allowed to perform request/response cycles
            binding.unbind().map { done =>
              logger.info("★ ★ ★ CoordinatedShutdown [http-api.unbind] ★ ★ ★")
              done
            }
          }

          // graceful termination request being handled on this connection
          shutdown.addTask(PhaseServiceRequestsDone, "http-terminate") { () =>
            /** It doesn't accept new connection but it drains the existing connections Until the `terminationDeadline`
              * all the req that have been accepted will be completed and only than the shutdown will continue
              */

            binding.terminate(terminationDeadline).map { _ =>
              logger.info("★ ★ ★ CoordinatedShutdown [http-api.terminate]  ★ ★ ★")
              akka.Done
            }
          }

          // forcefully kills connections that are still open
          shutdown.addTask(PhaseServiceStop, "close.connections") { () =>
            Http().shutdownAllConnectionPools().map { _ =>
              logger.info("★ ★ ★ CoordinatedShutdown [close.connections] ★ ★ ★")
              akka.Done
            }
          }

          shutdown.addTask(PhaseActorSystemTerminate, "system.term") { () =>
            Future.successful {
              logger.info("★ ★ ★ CoordinatedShutdown [close.connections] ★ ★ ★")
              akka.Done
            }
          }
      }

    // TODO: for local debug only !!!!!!!!!!!!!!!!!!!
    val _ = scala.io.StdIn.readLine()
    sys.log.warn("★ ★ ★ ★ ★ ★  Shutting down ... ★ ★ ★ ★ ★ ★")
    sys.terminate()
    scala
      .concurrent
      .Await
      .result(
        sys.whenTerminated,
        terminationDeadline,
      )
