package shared

import java.math.BigInteger
import java.security.{ KeyFactory, KeyPairGenerator, MessageDigest, PrivateKey, PublicKey, SecureRandom, Signature }
import java.security.interfaces.{ RSAPrivateCrtKey, RSAPublicKey }
import java.security.spec.{ RSAKeyGenParameterSpec, RSAPrivateCrtKeySpec, RSAPublicKeySpec }
import java.util
import org.spongycastle.crypto.engines.RSAEngine
import org.spongycastle.crypto.signers.PSSSigner
import org.spongycastle.crypto.digests.SHA256Digest

import java.security.interfaces.{ RSAPrivateCrtKey, RSAPublicKey }
import org.spongycastle.crypto.params.{ ParametersWithRandom, RSAKeyParameters, RSAPrivateCrtKeyParameters }

import java.io.{ BufferedInputStream, ByteArrayInputStream, ByteArrayOutputStream, InputStream }
import java.nio.charset.StandardCharsets
import java.util.{ Base64, UUID }
import scala.util.{ Try, Using }
import compiletime.asMatchable
import java.security.interfaces.RSAKey
import javax.crypto.Cipher
import scala.Console.println
import spray.json.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{ Files, Path, Paths }

def sha256(bts: Array[Byte]): Array[Byte] =
  MessageDigest.getInstance("SHA-256").digest(bts)

def base64Encode(bs: Array[Byte]): String =
  new String(Base64.getUrlEncoder.withoutPadding.encode(bs))

def base64Decode(s: String): Option[Array[Byte]] =
  Try(Base64.getUrlDecoder.decode(s)).toOption

abstract class Base64EncodedBytes:
  def bytes: Array[Byte]

  final override def toString: String =
    base64Encode(bytes)

  override def equals(that: Any): Boolean = that.asMatchable match
    case bs: Base64EncodedBytes => bs.bytes.sameElements(bytes)
    case _                      => false

  override def hashCode(): Int = util.Arrays.hashCode(bytes)

class Handle protected (val bytes: Array[Byte]) extends Base64EncodedBytes

object Handle:
  def apply(bs: Array[Byte]): Try[Handle] = Try(new Handle(bs))

  def fromEncoded(s: String): Option[Handle] = base64Decode(s).map(new Handle(_))

  private def ofBigEndianBytes(bs: Array[Byte]): Option[BigInt] =
    if (bs.isEmpty) None else Some(BigInt(0.toByte +: bs))

  private def toBigEndianBytes(bi: BigInt): Array[Byte] =
    val bs = bi.toByteArray
    if (bs.length > 1 && bs.head == 0.toByte) bs.tail else bs

  def ofModulus(n: BigInt): Handle =
    new Handle(sha256(toBigEndianBytes(n)))

  def ofKey(k: RSAKey): Handle = ofModulus(k.getModulus)

object RsaEncryption:
  val cipherName = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
  val algorithm = "SHA256withRSA"
  val cipher = Cipher.getInstance(cipherName)
  val sigAlg = Signature.getInstance(algorithm)

  @scala.annotation.tailrec
  def readChunk(
      in: ByteArrayInputStream,
      out: ByteArrayOutputStream,
      buffer: Array[Byte],
    ): Unit =
    in.read(buffer) match
      case -1 => ()
      case n =>
        out.write(buffer, 0, n)
        readChunk(in, out, buffer)

  def encryptAndSend(
      sender: String,
      // senderPrivKey: PrivateKey,
      // receiver: String,
      receiverPubKey: PublicKey,
      msg: String,
    ): String =

    // encrypt using receiver's public key
    cipher.init(Cipher.ENCRYPT_MODE, receiverPubKey)

    val msgBts = s"$sender:$msg".getBytes(StandardCharsets.UTF_8)
    val cipherBts =
      val in = new ByteArrayInputStream(msgBts)
      val out = new ByteArrayOutputStream()
      try readChunk(in, out, Array.ofDim[Byte](128))
      catch case scala.util.control.NonFatal(ex) => throw new Exception("Encryption error", ex)
      finally
        in.close()
        out.flush()
        out.close()
      out.toByteArray

    base64Encode(cipherBts)

    // sigh using sender's private key
    /*sigAlg.initSign(senderPrivKey)
    sigAlg.update(msgBts)
    val signatureBts = sigAlg.sign
    (base64Encode(cipherBts), base64Encode(signatureBts))*/

  def receiveAndDecrypt(
      content: String,
      // sign: String,
      receiverPrivKey: PrivateKey,
      senderPubKey: PublicKey,
    ): String =
    // decrypt using receiver's private key
    cipher.init(Cipher.DECRYPT_MODE, receiverPrivKey)

    val encBts = base64Decode(content).get

    // To use doFinal data must not be longer than 190 bytes
    val decryptedBts =
      val in = new ByteArrayInputStream(encBts)
      val out = new ByteArrayOutputStream()
      try readChunk(in, out, Array.ofDim[Byte](128))
      catch
        case scala.util.control.NonFatal(ex) =>
          throw new Exception("Decryption error", ex)
      finally
        in.close()
        out.flush()
        out.close()
      out.toByteArray
    new String(decryptedBts, StandardCharsets.UTF_8)

    // Verify signature using sender's public key
    // sigAlg.initVerify(senderPubKey)
    // sigAlg.update(decryptedBts)
    // check that the message came from a sender associated with senderPubKey
    // base64Decode(sign).foreach(println(sigAlg.verify(_)))
    // new String(decryptedBts, StandardCharsets.UTF_8)

object ChatUser:

  private val ALG = "RSA"
  private val publicExponent = new BigInteger((Math.pow(2, 16) + 1).toInt.toString)

  private val settings =
    JsonParserSettings.default.withMaxNumberCharacters(1500)

  def generate(keySize: Int = 2048): ChatUser =
    val kpg = KeyPairGenerator.getInstance(ALG)
    kpg.initialize(new RSAKeyGenParameterSpec(keySize, publicExponent), new SecureRandom())
    val kp = kpg.generateKeyPair
    ChatUser(kp.getPublic.asInstanceOf[RSAPublicKey], kp.getPrivate.asInstanceOf[RSAPrivateCrtKey])

  def recoverFromPubKey(pubKeyStr: String): Option[RSAPublicKey] =
    base64Decode(pubKeyStr).map(bts =>
      KeyFactory
        .getInstance(ALG)
        .generatePublic(new java.security.spec.X509EncodedKeySpec(bts))
        .asInstanceOf[RSAPublicKey]
    )

  def backup(chatUser: ChatUser, filename: String): Try[Path] =
    Try(Files.write(Paths.get(filename), chatUser.toString().getBytes(UTF_8)))

  def loadFromDisk(s: scala.io.Source): Option[ChatUser] =
    for {
      str <- Try(s.mkString).toOption
      a <- parse(str.parseJson(settings).convertTo[ChatUserSnapshot]).toOption
    } yield a

  private def parse(a: ChatUserSnapshot): Try[ChatUser] = Try {
    val kf = KeyFactory.getInstance(ALG)
    ChatUser(
      kf.generatePublic(new RSAPublicKeySpec(a.n.bigInteger, a.e.bigInteger)).asInstanceOf[RSAPublicKey],
      kf.generatePrivate(
        new RSAPrivateCrtKeySpec(
          a.n.bigInteger,
          a.e.bigInteger,
          a.d.bigInteger,
          a.p.bigInteger,
          a.q.bigInteger,
          a.dp.bigInteger,
          a.dq.bigInteger,
          a.qi.bigInteger,
        )
      ).asInstanceOf[RSAPrivateCrtKey],
    )
  }

final case class ChatUser(val pub: RSAPublicKey, val priv: RSAPrivateCrtKey):
  val handle = Handle.ofKey(pub)

  // public key
  val asX509: String = base64Encode(pub.getEncoded)
  // private key
  val asPKCS8: Array[Byte] = priv.getEncoded

  def toSnapshot(): ChatUserSnapshot =
    ChatUserSnapshot(
      pub.getPublicExponent,
      pub.getModulus,
      priv.getPrivateExponent,
      priv.getPrimeP,
      priv.getPrimeQ,
      priv.getPrimeExponentP,
      priv.getPrimeExponentQ,
      priv.getCrtCoefficient,
    )

  override def toString() =
    toSnapshot().toJson.prettyPrint

case class ChatUserSnapshot(
    e: BigInt,
    n: BigInt,
    d: BigInt,
    p: BigInt,
    q: BigInt,
    dp: BigInt,
    dq: BigInt,
    qi: BigInt)

object ChatUserSnapshot extends DefaultJsonProtocol:

  implicit object UserSnapshotJsonFormat extends JsonFormat[ChatUserSnapshot]:
    override def write(c: ChatUserSnapshot) = JsObject(
      "e" -> JsNumber(c.e.toString),
      "n" -> JsNumber(c.n.toString),
      "d" -> JsNumber(c.d.toString),
      "p" -> JsNumber(c.p.toString),
      "q" -> JsNumber(c.q.toString),
      "dp" -> JsNumber(c.dp.toString),
      "dq" -> JsNumber(c.dq.toString),
      "qi" -> JsNumber(c.qi.toString),
    )

    override def read(json: JsValue): ChatUserSnapshot =
      json.asJsObject.getFields("e", "n", "d", "p", "q", "dp", "dq", "qi") match
        case Seq(
               JsNumber(e),
               JsNumber(n),
               JsNumber(d),
               JsNumber(p),
               JsNumber(q),
               JsNumber(dp),
               JsNumber(dq),
               JsNumber(qi),
             ) =>
          ChatUserSnapshot(
            e.toBigInt,
            n.toBigInt,
            d.toBigInt,
            p.toBigInt,
            q.toBigInt,
            dp.toBigInt,
            dq.toBigInt,
            qi.toBigInt,
          )
