import scala2.Bridge
import server.grpc.Bootstrap

import scala.util.Try

object Main:

  def main(args: Array[String]): Unit =
    Bootstrap.run(args)
