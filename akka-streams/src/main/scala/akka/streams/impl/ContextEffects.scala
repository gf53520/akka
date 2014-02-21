package akka.streams.impl

import akka.streams.Operation.{ Sink, Source }
import rx.async.api.Producer
import scala.concurrent.ExecutionContext

/**
 * Additional Effects supplied by the context to allow additional executing additional effects
 * of link internal sources and sinks.
 */
trait ContextEffects {
  /**
   * Subscribe to the given source and once subscribed call the `sinkConstructor` with upstream
   * effects.
   */
  def subscribeTo[O](source: Source[O])(sinkConstructor: Upstream ⇒ SyncSink[O]): Effect
  def subscribeFrom[O](sink: Sink[O])(sourceConstructor: Downstream[O] ⇒ SyncSource): Effect

  def expose[O](source: Source[O]): Producer[O]

  def internalProducer[O](constructor: Downstream[O] ⇒ SyncSource): Producer[O]

  implicit def executionContext: ExecutionContext
  def runInContext(body: ⇒ Effect): Unit
}

/** Tries to implement ContextEffect methods generally */
abstract class AbstractContextEffects extends ContextEffects {
  def subscribeTo[O](source: Source[O])(sinkConstructor: Upstream ⇒ SyncSink[O]): Effect =
    // TODO: think about how to avoid redundant creation of closures
    //       e.g. by letting OperationImpl provide constructors from static info
    ConnectInternalSourceSink(OperationImpl.apply(_: Downstream[O], this, source), sinkConstructor)
}

case class ConnectInternalSourceSink[O](sourceConstructor: Downstream[O] ⇒ SyncSource, sinkConstructor: Upstream ⇒ SyncSink[O]) extends SingleStep {
  override def runOne(): Effect = {
    object LazyUpstream extends Upstream {
      var source: SyncSource = _
      val requestMore: Int ⇒ Effect = n ⇒ Effect.step(source.handleRequestMore(n), s"RequestMoreFromInternalSource($source)")
      val cancel: Effect = Effect.step(source.handleCancel(), s"Cancel internal source")
    }
    val sink = sinkConstructor(LazyUpstream)
    val downstream = BasicEffects.forSink(sink)
    val source = sourceConstructor(downstream)
    LazyUpstream.source = source
    sink.start() ~ source.start()
  }
}
