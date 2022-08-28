package client.grpc

import akka.actor.Cancellable
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.grpc.GrpcClientSettings
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.google.protobuf.UnsafeByteOperations
import com.typesafe.config.ConfigFactory
import server.grpc.{ ClientCmd, ConversationServiceClient, ServerCmd }
import shared.{ ChatUser, ChatUserSnapshot }

import java.util.concurrent.{ ConcurrentHashMap, ThreadLocalRandom }
import scala.concurrent.duration.*
import scala.concurrent.{ ExecutionContext, Future, Promise, blocking }
import shared.Exts.*

import java.nio.charset.StandardCharsets
import java.security.PublicKey
import shared.RsaEncryption

object ConversationClient:

  @main def main(args: String*): Unit =

    given sys: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "client", ConfigFactory.load())
    given ec: ExecutionContext = sys.executionContext
    val logger = sys.log

    val userName = "a1"
    val conf = GrpcClientSettings.fromConfig("server.grpc.ConversationService").withUserAgent(userName)
    val client = ConversationServiceClient(conf)

    // val appConf = scala2.Bridge.readAppConfig("conversation")
    // val crypto = SymmetricCryptography.getCryptography(appConf.jksPath, appConf.jksPsw)

    val file = s"./users/$userName"

    /*
    val user = ChatUser.generate()
    ChatUser.backup(user, file).foreach(_.getFileName)
     */
    val user = ChatUser
      .loadFromDisk(scala.io.Source.fromFile(file))
      .getOrElse(throw new Exception(s"Failed to recover $userName"))
    val users = new ConcurrentHashMap[String, java.security.interfaces.RSAPublicKey]()
    users.put(user.handle.toString, user.pub)

    // List("a", "b").foreach(streamingBroadcast(_))
    streamingBroadcast(conf.userAgent.getOrElse("anon") /*, crypto.enc*/ )

    def streamingBroadcast(name: String /*, enc: Encrypter*/ ): Unit =
      logger.warn(s"Performing streaming requests: $name")

      val line = "sdfhsdfh_sdfh_sdfhsdf_S_fsdfHSD" + ThreadLocalRandom.current().nextLong()

      val requests: Source[ClientCmd, akka.NotUsed] =
        // val requests: Source[ClientCmd, Cancellable] =
        Source
          .tick(1.second, 1.seconds, ())
          .zipWithIndex
          .map { case (_, i) => i }
          .takeWhile(_ < 100)
          // .map(i => ClientCmd(name, UnsafeByteOperations.unsafeWrap(enc.encrypt(s"$line-$i"))))
          .map { _ =>
            // message per user
            var content: scala.collection.immutable.Map[String, com.google.protobuf.ByteString] = Map.empty
            users.forEach { (sender, senderPubKey) =>
              content = content + (sender ->
                UnsafeByteOperations.unsafeWrap(
                  RsaEncryption
                    .encryptAndSend(userName, /*user.priv, sender,*/ senderPubKey, line)
                    .getBytes(StandardCharsets.UTF_8)
                    // .zip()
                ))
            }
            // TODO:
            // 1. Add compression/decompression      (+)
            // 2. Remove userInfo from each message  (-)
            val cmd = ClientCmd(
              content,
              server
                .grpc
                .UserInfo(
                  user.handle.toString,
                  UnsafeByteOperations.unsafeWrap(user.asX509.getBytes(StandardCharsets.UTF_8)),
                ),
            )
            println(cmd.serializedSize)
            cmd
          }
          .mapMaterializedValue { c =>
            // sys.scheduler.scheduleOnce(30.seconds, () => c.cancel())
            akka.NotUsed
          }

      val responseStream: Source[ServerCmd, akka.NotUsed] =
        client.post(requests)

      /*val done: Future[akka.Done] =
        responseStream.runForeach { reply =>
          println(s"$name: ${crypto.dec.decrypt(reply.content)}")
        }*/

      val done: Future[akka.Done] =
        responseStream.runForeach { serverCmd =>
          val sender = serverCmd.userInfo.name
          ChatUser.recoverFromPubKey(serverCmd.userInfo.pubKey.toStringUtf8) match
            case Some(pubKey) =>
              users.putIfAbsent(sender, pubKey)
            case None =>
              logger.warn("★ ★ ★ Got invalid PubKey ★ ★ ★")

          serverCmd.content.get(user.handle.toString).foreach { msgBts =>
            logger.info(
              s"$sender: ${RsaEncryption.receiveAndDecrypt(new String(msgBts.toByteArray /*.unzip()*/, StandardCharsets.UTF_8), user.priv, user.pub)}"
            )
          }
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
