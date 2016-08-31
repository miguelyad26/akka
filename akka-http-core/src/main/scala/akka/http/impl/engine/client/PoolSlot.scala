/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.client

import akka.event.LoggingAdapter
import akka.http.impl.engine.client.PoolConductor.{ ConnectEagerlyCommand, DispatchCommand, SlotCommand }
import akka.http.impl.engine.client.PoolSlot.SlotEvent.ConnectedEagerly
import akka.http.scaladsl.model.{ HttpEntity, HttpRequest, HttpResponse }
import akka.stream._
import akka.stream.scaladsl._
import akka.stream.stage.GraphStageLogic.EagerTerminateOutput
import akka.stream.stage.{ GraphStage, GraphStageLogic, InHandler, OutHandler }

import scala.concurrent.Future
import scala.language.existentials
import scala.util.{ Failure, Success }

private object PoolSlot {
  import PoolFlow.{ RequestContext, ResponseContext }

  sealed trait ProcessorOut
  final case class ResponseDelivery(response: ResponseContext) extends ProcessorOut
  sealed trait RawSlotEvent extends ProcessorOut
  sealed trait SlotEvent extends RawSlotEvent
  object SlotEvent {
    final case class RequestCompletedFuture(future: Future[RequestCompleted]) extends RawSlotEvent
    final case class RetryRequest(rc: RequestContext) extends RawSlotEvent
    final case class RequestCompleted(slotIx: Int) extends SlotEvent
    final case class Disconnected(slotIx: Int, failedRequests: Int) extends SlotEvent
    /**
     * Slot with id "slotIx" has responded to request from PoolConductor and connected immediately
     * Ordinary connections from slots don't produce this event
     */
    final case class ConnectedEagerly(slotIx: Int) extends SlotEvent
  }

  def apply(slotIx: Int, connectionFlow: Flow[HttpRequest, HttpResponse, Any])(implicit m: Materializer): Graph[FanOutShape2[SlotCommand, ResponseContext, RawSlotEvent], Any] = {
    val log = ActorMaterializerHelper.downcast(m).logger
    new SlotProcessor(slotIx, connectionFlow, log)
  }

  /**
   * To the outside it provides a stable flow stage, consuming `SlotCommand` instances on its
   * input (ActorSubscriber) side and producing `ProcessorOut` instances on its output
   * (ActorPublisher) side.
   * The given `connectionFlow` is materialized into a running flow whenever required.
   * Completion and errors from the connection are not surfaced to the outside (unless we are
   * shutting down completely).
   */
  private class SlotProcessor(slotIx: Int, connectionFlow: Flow[HttpRequest, HttpResponse, Any], log: LoggingAdapter)(implicit fm: Materializer)
    extends GraphStage[FanOutShape2[SlotCommand, ResponseContext, RawSlotEvent]] {

    val in: Inlet[SlotCommand] = Inlet("SlotProcessor.in")
    val out0: Outlet[ResponseContext] = Outlet("SlotProcessor.responsesOut")
    val out1: Outlet[RawSlotEvent] = Outlet("SlotProcessor.eventsOut")

    override def shape: FanOutShape2[SlotCommand, ResponseContext, RawSlotEvent] = new FanOutShape2(in, out0, out1)

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) with InHandler { self ⇒
      private val inflightRequests = new java.util.ArrayDeque[RequestContext]()

      private var connectionFlowSource: SubSourceOutlet[HttpRequest] = _
      private var connectionFlowSink: SubSinkInlet[HttpResponse] = _

      private def connectionOutFlowHandler = new OutHandler {
        override def onPull(): Unit = {
          if (isAvailable(in)) grab(in) match {
            case DispatchCommand(rc) ⇒
              inflightRequests.add(rc)
              connectionFlowSource.push(rc.request)
            case x ⇒
              log.error("invalid command {} when connected", x)
          }
        }

        override def onDownstreamFinish(): Unit = {
          connectionFlowSource.complete()

          if (inflightRequests.isEmpty) {
            push(out1, SlotEvent.Disconnected(slotIx, 0))

            setHandler(in, self)
          }
        }
      }

      private def connectionInFlowHandler = new InHandler {
        override def onPush(): Unit = {
          val response: HttpResponse = connectionFlowSink.grab()

          val requestContext = inflightRequests.pop
          val (entity, whenCompleted) = HttpEntity.captureTermination(response.entity)
          val delivery = ResponseDelivery(ResponseContext(requestContext, Success(response withEntity entity)))
          import fm.executionContext
          val requestCompleted = SlotEvent.RequestCompletedFuture(whenCompleted.map(_ ⇒ SlotEvent.RequestCompleted(slotIx)))
          push(out0, delivery.response)
          push(out1, requestCompleted)

          connectionFlowSink.pull()
        }

        override def onUpstreamFailure(ex: Throwable): Unit = {
          val it = inflightRequests.descendingIterator()
          while (it.hasNext) {
            val rc = it.next()
            if (rc.retriesLeft == 0) emit(out0, ResponseContext(rc, Failure(ex)))
            else emit(out1, SlotEvent.RetryRequest(rc.copy(retriesLeft = rc.retriesLeft - 1)))
          }
          emit(out1, SlotEvent.Disconnected(slotIx, inflightRequests.size))
          inflightRequests.clear()

          connectionFlowSource.complete()
          setHandler(in, self)
        }
      }

      private lazy val connected = new InHandler {
        override def onPush(): Unit = {
          if (connectionFlowSource.isAvailable) {
            grab(in) match {
              case DispatchCommand(rc: RequestContext) ⇒
                inflightRequests.add(rc)
                connectionFlowSource.push(rc.request)
              case x ⇒
                log.error("invalid command {} when connected", x)
            }
          }
        }
      }

      // unconnected
      override def onPush(): Unit = {
        def createSubSourceOutlets() = {
          connectionFlowSource = new SubSourceOutlet[HttpRequest]("RequestSource")
          connectionFlowSource.setHandler(connectionOutFlowHandler)

          connectionFlowSink = new SubSinkInlet[HttpResponse]("ResponseSink")
          connectionFlowSink.setHandler(connectionInFlowHandler)

          setHandler(in, connected)
        }

        grab(in) match {
          case ConnectEagerlyCommand ⇒
            createSubSourceOutlets()

            Source.fromGraph(connectionFlowSource.source)
              .via(connectionFlow).runWith(Sink.fromGraph(connectionFlowSink.sink))(subFusingMaterializer)

            connectionFlowSink.pull()

            push(out1, ConnectedEagerly(slotIx))

          case DispatchCommand(rc: RequestContext) ⇒
            createSubSourceOutlets()

            inflightRequests.add(rc)

            Source.single(rc.request).concat(Source.fromGraph(connectionFlowSource.source))
              .via(connectionFlow).runWith(Sink.fromGraph(connectionFlowSink.sink))(subFusingMaterializer)

            connectionFlowSink.pull()
        }
      }

      setHandler(in, this)

      setHandler(out0, EagerTerminateOutput)
      setHandler(out1, new OutHandler {
        override def onPull(): Unit = pull(in)
      })
    }

  }

  final class UnexpectedDisconnectException(msg: String, cause: Throwable) extends RuntimeException(msg, cause) {
    def this(msg: String) = this(msg, null)
  }
}
