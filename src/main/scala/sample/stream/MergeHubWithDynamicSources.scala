package sample.stream

import akka.NotUsed
import akka.actor.{ActorSystem, Cancellable}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{MergeHub, RunnableGraph, Sink, Source}

import scala.concurrent.duration._

/**
  * Example taken from Doc:
  * http://doc.akka.io/docs/akka/current/scala/stream/stream-dynamic.html#dynamic-fan-in-and-fan-out-with-mergehub-broadcasthub-and-partitionhub
  *
  * The same principle for MergeHub.source can be found in WebsocketChatEcho
  * There it could be used as a "reverse proxy"
  */
object MergeHubWithDynamicSources {
  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()

  def main(args: Array[String]): Unit = {

    val consumer = Sink.foreach(println)

    // Attach a MergeHub Source to the consumer. This will materialize to a corresponding Sink
    val runnableGraph: RunnableGraph[Sink[String, NotUsed]] = MergeHub.source[String](perProducerBufferSize = 16).to(consumer)

    // By running/materializing the graph we get back a Sink, and hence now have access to feed elements into it
    // This Sink can then be materialized any number of times, and every element that enters the Sink will be consumed by our consumer
    val toConsumer: Sink[String, NotUsed] = runnableGraph.run()

    // Feed two dynamic fan-in sources into the hub
    Source.single("Hello!").runWith(toConsumer)
    Source.single("Hub!").runWith(toConsumer)

    // Feed another dynamic fan-in source
    val tickSource: Source[String, Cancellable] = Source.tick(1.seconds, 1.second, 1)
      .scan(0)(_ + _)
      .map(_.toString)
    tickSource.runWith(toConsumer)
  }
}
