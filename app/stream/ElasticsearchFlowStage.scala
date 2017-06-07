package stream

import java.io.ByteArrayOutputStream

import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import org.apache.http.entity.StringEntity
import org.apache.http.message.BasicHeader
import org.elasticsearch.client.{Response, ResponseListener, RestClient}
import spray.json._
import stream.ElasticsearchFlowStage._

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.Future

//#sink-settings
final case class ElasticsearchSinkSettings(bufferSize: Int = 10)

//#sink-settings

final case class IncomingMessage[T](id: Option[String], source: T)

trait MessageWriter[T] {
  def convert(message: T): String
}

class ElasticsearchFlowStage[T](indexName: String,
                                typeName: String,
                                client: RestClient,
                                settings: ElasticsearchSinkSettings,
                                writer: MessageWriter[T]
                               ) extends GraphStage[FlowShape[IncomingMessage[T], Future[Response]]] {

  private val in = Inlet[IncomingMessage[T]]("messages")
  private val out = Outlet[Future[Response]]("result")
  override val shape = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with ResponseListener with InHandler with OutHandler {

      private var state: State = Idle
      private val queue = new mutable.Queue[IncomingMessage[T]]()
      private val failureHandler = getAsyncCallback[Throwable](handleFailure)
      private val responseHandler = getAsyncCallback[Response](handleResponse)

      override def preStart(): Unit =
        pull(in)

      private def tryPull(): Unit =
        if (queue.size < settings.bufferSize && !isClosed(in) && !hasBeenPulled(in)) {
          pull(in)
        }

      private def handleFailure(exception: Throwable): Unit =
        failStage(exception)

      private def handleSuccess(): Unit =
        completeStage()

      private def handleResponse(response: Response): Unit = {
        val results = {
          val out = new ByteArrayOutputStream()
          try {
            response.getEntity.writeTo(out)
            new String(out.toByteArray, "UTF-8").split("\n")
          } finally {
            out.close()
          }
        }

        // If some commands in bulk request failed, this stage fails.
        val errors = results.filter { result =>
          !result.parseJson.asJsObject.fields.get("errors").exists(_ == JsBoolean(false))
        }
        if (errors.nonEmpty) {
          failStage(new IllegalStateException(errors.mkString("\n")))
        }

        val messages = (1 to settings.bufferSize).flatMap { _ =>
          queue.dequeueFirst(_ => true)
        }

        if (messages.isEmpty) {
          state match {
            case Finished => handleSuccess()
            case _ => state = Idle
          }
        } else {
          sendBulkUpdateRequest(messages)
        }

        push(out, Future.successful(response))
      }

      override def onFailure(exception: Exception): Unit = failureHandler.invoke(exception)

      override def onSuccess(response: Response): Unit = responseHandler.invoke(response)

      private def sendBulkUpdateRequest(messages: Seq[IncomingMessage[T]]): Unit = {
        val json = messages
          .map { message =>
            JsObject(
              "index" -> JsObject(
                Seq(
                  Option("_index" -> JsString(indexName)),
                  Option("_type" -> JsString(typeName)),
                  message.id.map { id =>
                    "_id" -> JsString(id)
                  }
                ).flatten: _*
              )
            ).toString + "\n" + writer.convert(message.source)
          }
          .mkString("", "\n", "\n")

        client.performRequestAsync(
          "POST",
          "/_bulk",
          Map[String, String]().asJava,
          new StringEntity(json),
          this,
          new BasicHeader("Content-Type", "application/x-ndjson")
        )
      }

      setHandlers(in, out, this)

      override def onPull(): Unit = tryPull()

      override def onPush(): Unit = {
        val message = grab(in)
        queue.enqueue(message)

        state match {
          case Idle => {
            state = Sending
            val messages = (1 to settings.bufferSize).flatMap { _ =>
              queue.dequeueFirst(_ => true)
            }
            sendBulkUpdateRequest(messages)
          }
          case _ => ()
        }

        tryPull()
      }

      override def onUpstreamFailure(exception: Throwable): Unit =
        handleFailure(exception)

      override def onUpstreamFinish(): Unit =
        state match {
          case Idle => handleSuccess()
          case Sending => state = Finished
          case Finished => ()
        }
    }

}

object ElasticsearchFlowStage {

  private sealed trait State

  private case object Idle extends State

  private case object Sending extends State

  private case object Finished extends State

}
