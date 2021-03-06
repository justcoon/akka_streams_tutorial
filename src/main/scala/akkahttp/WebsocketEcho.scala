package akkahttp

import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.ws._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.directives.WebSocketDirectives
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import com.github.andyglow.websocket._

import scala.concurrent.{Future, Promise}
import scala.language.postfixOps
import scala.util.{Failure, Success}


trait ClientCommon {
  implicit val system = ActorSystem("my-system")
  implicit val materializer = ActorMaterializer()
  implicit val executionContext = system.dispatcher

  val printSink: Sink[Message, Future[Done]] =
    Sink.foreach {
      //see https://github.com/akka/akka-http/issues/65
      case TextMessage.Strict(text)             => println(s"Client recieved Strict: $text")
      case TextMessage.Streamed(textStream)     => textStream.runFold("")(_ + _).onComplete(value => println(s"Client recieved Streamed: ${value.get}"))
      case BinaryMessage.Strict(binary)         => //do nothing
      case BinaryMessage.Streamed(binaryStream) => binaryStream.runWith(Sink.ignore)
    }

  //see http://doc.akka.io/docs/akka-http/10.0.9/scala/http/client-side/websocket-support.html#half-closed-client-websockets
  val helloSource = Source(List(TextMessage("world one"), TextMessage("world two")))
    .concatMat(Source.maybe[Message])(Keep.right)

}

object WebsocketEcho extends WebSocketDirectives with ClientCommon {


  def main(args: Array[String]) {
    val (address, port) = ("127.0.0.1", 6000)
    server(address, port)
    for ( a <- 1 to 10) clientNettyBased(address, port)
    for ( a <- 1 to 10) clientSingleWebSocketRequest(address, port)
    for ( a <- 1 to 10) clientWebSocketClientFlow(address, port)
  }

  private def server(address: String, port: Int) = {

    def echoFlow: Flow[Message, Message, Any] =
      Flow[Message].mapConcat {
        case tm: TextMessage =>
          println(s"Server recieved: $tm")
          TextMessage(Source.single("Hello ") ++ tm.textStream ++ Source.single("!")) :: Nil
        case bm: BinaryMessage =>
          // ignore binary messages but drain content to avoid the stream being clogged
          bm.dataStream.runWith(Sink.ignore)
          Nil
      }

    val websocketRoute: Route =
      path("echo") {
        handleWebSocketMessages(echoFlow)
      }

    val bindingFuture = Http().bindAndHandle(websocketRoute, address, port)
    bindingFuture.onComplete {
      case Success(b) =>
        println("Server started, listening on: " + b.localAddress)
      case Failure(e) =>
        println(s"Server could not bind to $address:$port. Exception message: ${e.getMessage}")
        system.terminate()
    }
  }

  private def clientNettyBased(address: String, port: Int) = {

    // see https://github.com/andyglow/websocket-scala-client
    val cli = WebsocketClient[String](s"ws://$address:$port/echo") {
      case str => println(s"Client recieved String: $str")
    }
    val ws = cli.open()
    ws ! "world one"
    ws ! "world two"
  }

  private def clientSingleWebSocketRequest(address: String, port: Int) = {

    // flow to use (note: not re-usable!)
    val webSocketFlow: Flow[Message, Message, Promise[Option[Message]]] =
      Flow.fromSinkAndSourceMat(
        printSink,
        helloSource)(Keep.right)

    val (upgradeResponse, closed) =
    Http().singleWebSocketRequest(WebSocketRequest(s"ws://$address:$port/echo"), webSocketFlow)

    val connected = upgradeResponse.map { upgrade =>
      // status code 101 (Switching Protocols) indicates that server support WebSockets
      if (upgrade.response.status == StatusCodes.SwitchingProtocols) {
        Done
      } else {
        throw new RuntimeException(s"Connection failed: ${upgrade.response.status}")
      }
    }

    connected.onComplete(println)
    closed.future.foreach(_ => println("closed"))
  }

  private def clientWebSocketClientFlow(address: String, port: Int) = {

    // flow to use (note: not re-usable!)
    val webSocketFlow: Flow[Message, Message, Future[WebSocketUpgradeResponse]] = Http().webSocketClientFlow(WebSocketRequest(s"ws://$address:$port/echo"))

    val (upgradeResponse, closed) =
    helloSource
      .viaMat(webSocketFlow)(Keep.right) // keep the materialized Future[WebSocketUpgradeResponse]
      .toMat(printSink)(Keep.both) // also keep the Future[Done]
      .run()


    val connected = upgradeResponse.flatMap { upgrade =>
      // status code 101 (Switching Protocols) indicates that server support WebSockets
      if (upgrade.response.status == StatusCodes.SwitchingProtocols) {
        Future.successful(Done)
      } else {
        throw new RuntimeException(s"Connection failed: ${upgrade.response.status}")
      }
    }

    connected.onComplete(println)
    closed.foreach(_ => println("closed"))
  }
}
