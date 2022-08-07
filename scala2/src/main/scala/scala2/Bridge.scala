package scala2

final case class AppConfig(
                            port: Int,
                            bannedUsers: List[String],
                            bannedHosts: List[String],
                            bufferSize: Int,
                            jksPath: String,
                            jksPsw: String
                          )

object Bridge {

  def readAppConfig(systemName: String): AppConfig = {
    import pureconfig.generic.auto.exportReader
    pureconfig.ConfigSource.default.at(systemName).loadOrThrow[AppConfig]
  }
}

