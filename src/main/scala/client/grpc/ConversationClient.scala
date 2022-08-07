package client.grpc

import akka.actor.Cancellable
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.grpc.GrpcClientSettings
import akka.stream.scaladsl.Source
import com.google.protobuf.UnsafeByteOperations
import com.typesafe.config.ConfigFactory
import server.grpc.{ ClientCmd, ConversationServiceClient, ServerCmd }
import shared.SymmetricCryptography

import java.util.concurrent.ThreadLocalRandom
import scala.concurrent.duration.*
import scala.concurrent.{ ExecutionContext, Future, Promise, blocking }
import shared.Exts.*
import shared.SymmetricCryptography.Encrypter

object ConversationClient:

  @main def main(args: String*): Unit =

    given sys: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "client", ConfigFactory.load())
    given ec: ExecutionContext = sys.executionContext

    val conf = GrpcClientSettings.fromConfig("server.grpc.ConversationService").withUserAgent("a2")
    val client = ConversationServiceClient(conf)

    val appConf = scala2.Bridge.readAppConfig("conversation")
    val crypto = SymmetricCryptography.getCryptography(appConf.jksPath, appConf.jksPsw)

    // List("a", "b").foreach(streamingBroadcast(_))
    streamingBroadcast(conf.userAgent.getOrElse("anon"), crypto.enc)

    def streamingBroadcast(name: String, enc: Encrypter): Unit =
      println(s"Performing streaming requests: $name")
      val line =
        "ch.qos.logback.[jar:oject-template/target/bg-jobs/sbt_b72aa391/job-17/target/37c71bef/8be77975/r!/logback.xml"

      val requests: Source[ClientCmd, akka.NotUsed] =
        // val requests: Source[ClientCmd, Cancellable] =
        Source
          .tick(1.second, 1.seconds, ())
          .zipWithIndex
          .map { case (_, i) => i }
          .takeWhile(_ < 100)
          .map(i => ClientCmd(name, UnsafeByteOperations.unsafeWrap(enc.encrypt(s"$line-$i"))))
          .mapMaterializedValue { c =>
            // sys.scheduler.scheduleOnce(30.seconds, () => c.cancel())
            akka.NotUsed
          }

      val responseStream: Source[ServerCmd, akka.NotUsed] =
        client.post(requests)

      val done: Future[akka.Done] =
        responseStream.runForeach { reply =>
          println(s"$name: ${crypto.dec.decrypt(reply.content)}")
        }

      done.onComplete { _ =>
        client.close().onComplete { _ =>
          sys.log.warn("★ ★ ★ ★ ★ ★  Completed ★ ★ ★ ★ ★ ★")
          sys.terminate()
        }
      }

    val terminationDeadline =
      sys.settings.config.getDuration("akka.coordinated-shutdown.default-phase-timeout").asScala
    val _ = scala.io.StdIn.readLine()
    sys.log.warn("★ ★ ★ ★ ★ ★  Stopped ★ ★ ★ ★ ★ ★")
    sys.terminate()
    scala
      .concurrent
      .Await
      .result(
        sys.whenTerminated,
        terminationDeadline,
      )
