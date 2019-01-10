package com.github.calvin.experiments

import java.nio.charset.StandardCharsets

import akka.NotUsed
import akka.stream.ActorMaterializer
import akka.stream.alpakka.s3.scaladsl._
import akka.stream.scaladsl._
import akka.util.ByteString
import com.amazonaws.services.sqs.model.{Message, MessageAttributeValue, SendMessageRequest}
import io.circe.parser.decode
import squants.information.InformationConversions._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.hashing.MurmurHash3
import io.circe._
import io.circe.generic.auto._
import io.circe.parser._
import io.circe.syntax._

import scala.collection.JavaConverters._

object ReactiveExtendedLibrary {
  private val `256KiB` = 256.kibibytes

  private def utf8SizeInBytes(utf8Str: String): Int =
    utf8Str.getBytes(StandardCharsets.UTF_8).length

  object datatypes {
    final case class ExtendedMessage(s3Bucket: String, s3Key: String)
    object ExtendedMessage {
      implicit class ExtendedMessageOps(e: ExtendedMessage) {
        def handle: String = s"s3://${e.s3Bucket}/${e.s3Key}"
      }
    }

    final case class ExtendedMessageWithContent(s3Bucket: String, s3Key: String, content: String)
    final case class ErrorAndMessage(error: String, message: Message)
  }

  object akkastreams {
    import datatypes._

    def largeMessageRetrievalSupport(parallelism: Int = 1)(s3Client: S3Client)(implicit ec: ExecutionContext, mat: ActorMaterializer): Flow[Message, Either[ErrorAndMessage, Message], NotUsed] =
      Flow[Message].mapAsync(parallelism) { msg =>
        if (msg.getMessageAttributes.containsKey("S3_LOCATION")) {
          decode[ExtendedMessage](msg.getBody).fold(
            error => Future.successful(Left(ErrorAndMessage(error.getMessage, msg))),
            extendedMessage => {
              val (dataStream, futMetadata) = s3Client.download(extendedMessage.s3Bucket, extendedMessage.s3Key)
              futMetadata.flatMap { metadata: ObjectMetadata =>
                // file does not exist
                if (metadata.contentLength == 0) Future.successful(Left(ErrorAndMessage(s"Object in bucket=${extendedMessage.s3Bucket} with key=${extendedMessage.s3Key} does not exist in S3", msg)))
                else {
                  val futMessage: Future[String] =
                    dataStream
                      .toMat(Sink.fold(ByteString.empty)((acc: ByteString, next: ByteString) => acc ++ next))(Keep.right)
                      .run()
                      .map(_.utf8String)

                  futMessage
                    .map(msg.withBody)
                    .map(Right[ErrorAndMessage, Message])
                }
              }
            }
          )
        } else Future.successful(Right(msg))
      }

    def largeMessageStorageSupport(s3Bucket: String, s3KeyPrefix: String = "", parallelism: Int = 1)(s3Client: S3Client)(implicit ec: ExecutionContext, mat: ActorMaterializer): Flow[SendMessageRequest, SendMessageRequest, NotUsed] = {
      def persistToS3(content: String, bucket: String)(s3Client: S3Client): Future[ExtendedMessage] = {
        val msgHash = MurmurHash3.stringHash(content).toString
        val source = Source.single(ByteString(content))
        val sink: Sink[ByteString, Future[MultipartUploadResult]] = s3Client.multipartUpload(bucket = bucket, key = s"$s3KeyPrefix/$msgHash")
        val graph = source.toMat(sink)(Keep.right)
        graph.run().map(loc => ExtendedMessage(loc.bucket, loc.key))
      }

      Flow[SendMessageRequest].mapAsync(parallelism) { smr =>
        val content = smr.getMessageBody
        if (utf8SizeInBytes(content) >= `256KiB`.toBytes) {
          persistToS3(content, s3Bucket)(s3Client).map { extendedMsg =>
            smr.withMessageBody(extendedMsg.asJson.spaces2)
              .withMessageAttributes(
                Map(
                  "S3_LOCATION" -> new MessageAttributeValue().withDataType("String").withStringValue(extendedMsg.handle),
                  "S3_BUCKET" -> new MessageAttributeValue().withDataType("String").withStringValue(extendedMsg.s3Bucket),
                  "S3_KEY" -> new MessageAttributeValue().withDataType("String").withStringValue(extendedMsg.s3Key)
                ).asJava
              )

          }
        } else Future.successful(smr)
      }
    }

    def largeMessageAckSupport(parallelism: Int = 1)(s3Client: S3Client)(implicit ec: ExecutionContext): Flow[String, String, NotUsed] =
      Flow[String].mapAsync(parallelism) { msg =>
        decode[ExtendedMessage](msg).fold(
          _ => Future.successful(msg), // messages that are not in the large format type will just be passed through
          extendedMsg => s3Client.deleteObject(extendedMsg.s3Bucket, extendedMsg.s3Key).map(_ => msg) // delete the corresponding file on S3
        )
      }

    val enrichMessageContent: Flow[Message, Message, NotUsed] =
      Flow[Message].map { msg =>
        if (msg.getMessageAttributes.containsKey("S3_LOCATION")) {
          val s3Key = msg.getMessageAttributes.get("S3_KEY").getStringValue
          val s3Bucket = msg.getMessageAttributes.get("S3_BUCKET").getStringValue
          val extendedMessage = ExtendedMessageWithContent(s3Bucket, s3Key, msg.getBody)
          msg.withBody(extendedMessage.asJson.spaces2)
        } else msg
      }
  }
}