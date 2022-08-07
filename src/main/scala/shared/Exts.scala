package shared

import java.time.Duration as JavaDuration
import scala.concurrent.duration.{ FiniteDuration, NANOSECONDS }

object Exts:

  extension (duration: JavaDuration) def asScala: FiniteDuration = FiniteDuration(duration.toNanos, NANOSECONDS)
