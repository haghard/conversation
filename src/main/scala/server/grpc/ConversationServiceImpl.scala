package server.grpc

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.stream.ActorAttributes.MaxFixedBufferSize
import akka.stream.Attributes.InputBuffer
import akka.stream.scaladsl.MergeHub.DrainingControl
import akka.stream.{ KillSwitches, OverflowStrategy, SharedKillSwitch, UniqueKillSwitch }
import akka.stream.scaladsl.{ BroadcastHub, Flow, Keep, MergeHub, Sink, Source }
import com.typesafe.config.ConfigFactory
import org.slf4j.Logger
import scala2.AppConfig

import scala.concurrent.duration.*
import scala.concurrent.{ Await, ExecutionContext, Future, Promise }
import scala.util.{ Failure, Success }

object ConversationServiceImpl:

  val knownUsers = Set("BpB1m-4nZG6rMyPRVO8m7AbPvpjU7YUZIFy4ab1ve4M", "f1wzkLLuzOW3i6lZX7bAIiPZQOkRTRvFx8aCilAGsMA")

  def allocate(
      appConf: AppConfig
    )(using system: ActorSystem[Nothing]
    ): (((Sink[ClientCmd, NotUsed], DrainingControl), UniqueKillSwitch), Source[ServerCmd, NotUsed]) =
    MergeHub
      .sourceWithDraining[ClientCmd](appConf.bufferSize)
      .map(msg => ServerCmd(msg.content, msg.userInfo))
      .backpressureTimeout(3.seconds) //
      .viaMat(KillSwitches.single)(Keep.both)
      .toMat(BroadcastHub.sink[ServerCmd](4))(Keep.both)
      .run()

class ConversationServiceImpl(
    srcHub: Source[ServerCmd, NotUsed],
    sinkHub: Sink[ClientCmd, NotUsed],
  )(using system: ActorSystem[?])
    extends server.grpc.ConversationService:

  given ec: ExecutionContext = system.executionContext
  given logger: Logger = system.log

  /*override def sayHello(request: HelloRequest): Future[HelloReply] =
    system.log.info("GOT sayHello {}", request.toProtoString)
    Future.successful(HelloReply(s"Hello, ${request.name}"))*/

  def auth(in: AuthReq): Future[AuthReply] = ???

  override def post(in: Source[ClientCmd, NotUsed]): Source[ServerCmd, NotUsed] =
    // val (q, sink) = Sink.queue[HelloRequest](12).preMaterialize()
    /*src.runWith(Sink.foreach { r =>
      if(!queue.offer(r).isEnqueued) {
        throw new Exception("Overflow!!")
      }
    }).onComplete(r => println(s"disconnect: $r"))(sys.executionContext)*/

    val client = Promise[(String, Option[Throwable])]()
    client
      .future
      .onComplete {
        case Success((usr, maybeErr)) =>
          maybeErr.fold(logger.info(s"{} got disconnected", usr)) { err =>
            logger.error(s"$usr got disconnected. Error", err)
          }
        case Failure(ex) =>
          logger.error("Error", ex)
      }

    val auth = Promise[Source[ServerCmd, NotUsed]]()
    val sink =
      Sink.fromMaterializer { (mat, atts) =>
        // atts.get[InputBuffer], atts.get[MaxFixedBufferSize]
        Sink
          .foreach[(Seq[ClientCmd], Source[ClientCmd, NotUsed])] {
            case (authMsg, source) =>
              authMsg.headOption match
                case None =>
                  logger.info("Authorization error")
                  auth.trySuccess(Source.empty[ServerCmd])
                  source.runWith(Sink.cancelled[ClientCmd])
                case Some(authMsg) =>
                  val usr = authMsg.userInfo.name
                  if (ConversationServiceImpl.knownUsers.contains(usr))
                    logger.info(s"authorized {}", usr)
                    source
                      .via(
                        Flow[ClientCmd].addAttributes(atts).watchTermination() { (_, d) =>
                          d.onComplete {
                            case Success(_)  => client.trySuccess((usr, None))
                            case Failure(ex) => client.trySuccess((usr, Some(ex)))
                          }
                        }
                      )
                      .runWith(sinkHub.addAttributes(atts))(mat)
                    auth.trySuccess(srcHub)
                  else
                    logger.info(s"unauthorized {}", authMsg.userInfo.name)
                    auth.trySuccess(Source.empty[ServerCmd])
                    source.runWith(Sink.cancelled[ClientCmd])
          }
          .addAttributes(atts)
          .mapMaterializedValue(_ => auth.future)
      }

    Source
      .futureSource(in.prefixAndTail(1).runWith(sink).flatten)
      .mapMaterializedValue { auth =>
        // auth.onComplete(r => println(s"Auth:ok: $r"))
        akka.NotUsed
      }

  // src.to(sinkHub).run()
  // src.toMat(sinkHub)(Keep.both).run()

  // src.runWith(sinkHub)
  // srcHub

end ConversationServiceImpl
