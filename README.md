# Reactive Amazon SQS Extended Library

This project is a reactive implementation of the 
[Amazon SQS Extended Client Library for Java](https://github.com/awslabs/amazon-sqs-java-extended-client-lib) and is 
built on [Alpakka](https://github.com/akka/alpakka)'s SQS and S3 sources and sinks. 

This library provides flows for publishing consuming messages that exceed the 256 KB limit that SQS imposes:
* __Publishing__
  * `largeMessageStorageSupport`: Checks whether message is large and persists it in S3 and created a pointer in the SQS message
* __Consuming__
  * `largeMessageRetrievalSupport`: Checks whether the SQS message is an S3 pointer and retrieves it
  * `enrichMessageContent`: Used for acknowledging large messages and removing them from S3 (this is optional and is 
     used when the downstream wants to know about message metadata)
  * `largeMessageAckSupport`: removes the big message from S3

Here's an example of how to achieve at-least-once-delivery semantics with very large messages:

```scala
// Producer
import com.github.calvin.experiments.ReactiveExtendedLibrary._
import akkastreams._
import datatypes._
import akka.stream.scaladsl._
import akka.stream.alpakka.s3.scaladsl._
import akka.stream.alpakka.sqs._
import akka.stream.alpakka.sqs.MessageAction.Delete
import akka.stream.alpakka.sqs.scaladsl._
import com.amazonaws.services.sqs.model._
import scala.concurrent.Future
import akka.{Done, NotUsed}

val s3Client: S3Client = ???
val s3BucketName: String = ???
val sqsMessageSource: Source[Message, NotUsed] = ???
val sqsPublishSink: Sink[SendMessageRequest, Future[Done]] = ???
val ackFlow: Flow[MessageAction, SqsAckResult, NotUsed] = ???
val lotsOfContent: String = (1 to 16000).map(i => s"$i: hello world").mkString("\n")

val producerStream: Future[Done] = 
  Source(List("1", "2", lotsOfContent))
   .map(msg => new SendMessageRequest().withMessageBody(msg).withMessageGroupId("EXAMPLE"))
   .via(largeMessageStorageSupport(s3BucketName, "calvin")(s3Client))
   .runWith(sqsPublishSink)
   
// Consumer with at-least-once-delivery semantics (with respect to removing data from S3)
val consumerAtleastOnceStream: Future[Done] = sqsMessageSource
    .via(largeMessageRetrievalSupport()(s3Client))
    .map {
      case Left(ErrorAndMessage(error, message)) => message
      case Right(message) => message
    }
    .via(enrichMessageContent)
    .map(msg => Delete(msg))
    .via(ackFlow)
    .map(_.message)
    .via(largeMessageAckSupport()(s3Client))   
    .runWith(Sink.foreach(println))
```

The main problem with the original library is that messages on S3 are deleted before they are consumed on SQS which 
leads to a lot of problems. The implementation above deletes the message on S3 after it is removed from SQS

__NOTE__: Make sure to use the following SQS settings when consuming messages as message metadata is required:

```scala
import akka.stream.alpakka.sqs._
import akka.stream.alpakka.sqs.scaladsl._
import scala.concurrent.duration._

 val sqsSourceSettings =   SqsSourceSettings()
    .withAttributes(All :: Nil)
    .withMessageAttributes(MessageAttributeName("S3_LOCATION") :: MessageAttributeName("S3_KEY") :: MessageAttributeName("S3_BUCKET") :: Nil)
```