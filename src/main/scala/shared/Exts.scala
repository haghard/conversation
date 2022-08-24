package shared

import java.io.{ ByteArrayInputStream, ByteArrayOutputStream }
import java.time.Duration as JavaDuration
import java.util.zip.{ ZipEntry, ZipInputStream, ZipOutputStream, GZIPInputStream, GZIPOutputStream }
import scala.annotation.tailrec
import scala.concurrent.duration.{ FiniteDuration, NANOSECONDS }
import scala.util.control.NonFatal

object Exts:

  extension (duration: JavaDuration) def asScala: FiniteDuration = FiniteDuration(duration.toNanos, NANOSECONDS)

  extension (bytes: Array[Byte])
    def zip(): Array[Byte] =
      val outBts = new ByteArrayOutputStream(bytes.size)
      val outZip = new ZipOutputStream(outBts)
      outZip.setLevel(9)
      try
        outZip.putNextEntry(new ZipEntry("timeline.dat"))
        outZip.write(bytes)
        outZip.closeEntry()
        outBts.flush()
        outBts.toByteArray
      catch case NonFatal(ex) => throw new Exception(s"Zip error", ex)
      finally
        outZip.finish()
        outZip.close()

    def unzip(): Array[Byte] =
      val buffer = new Array[Byte](1024 * 1)
      val in = new ZipInputStream(new ByteArrayInputStream(bytes))
      val out = new ByteArrayOutputStream

      @tailrec def readWriteChunk(): Unit =
        in.read(buffer) match
          case -1 => ()
          case n =>
            out.write(buffer, 0, n)
            readWriteChunk()

      in.getNextEntry()
      try readWriteChunk()
      finally in.close()
      out.toByteArray

    def gzip(): Array[Byte] =
      val bos = new ByteArrayOutputStream(bytes.size)
      val gzip = new GZIPOutputStream(bos)
      try gzip.write(bytes)
      catch { case NonFatal(ex) => throw new Exception(s"GZip error", ex) }
      finally gzip.close()
      bos.toByteArray

    def unGzip(): Array[Byte] =
      val buffer = new Array[Byte](1024 * 1)
      val out = new ByteArrayOutputStream()
      val in = new GZIPInputStream(new ByteArrayInputStream(bytes))

      @tailrec def readChunk(): Unit =
        in.read(buffer) match
          case -1 => ()
          case n =>
            out.write(buffer, 0, n)
            readChunk()

      try readChunk()
      catch { case NonFatal(ex) => throw new Exception("unGzip error", ex) }
      finally in.close()
      out.toByteArray
