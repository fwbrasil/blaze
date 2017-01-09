package org.http4s.blaze.pipeline

import java.util.Date
import java.util.concurrent.TimeoutException

import scala.concurrent.{Promise, Future}
import scala.concurrent.duration.Duration

import org.log4s.getLogger

import Command._
import org.http4s.blaze.util.Execution.directec
import org.http4s.blaze.util.Execution

/*         Stages are formed from the three fundamental types. They essentially form a
 *         double linked list. Commands can be sent in both directions while data is only
 *         requested from the end of the list, or Tail sections and returned as Futures
 *         which can get transformed down the stages.
 *
 *                    Class Structure
 *                 -------------------
 *                        Stage                       // Root trait
 *                       /     \
 *                  Head[O]   Tail[I]                 // Linked List traits
 *                 /      \  /       \
 *     HeadStage[O]   MidStage[I,O]   TailStage[I]    // Fundamental stage traits
 *
 *       -------------- Inbound -------------->
 *       < ------------ Outbound --------------
 *
 *
 */

sealed trait Stage {
  final protected val logger = getLogger

  def name: String

  /** Start the stage, allocating resources etc.
    *
    * This method should not effect other stages by sending commands etc unless it creates them.
    * It is not impossible that the stage will receive other commands besides [[Connected]]
    * before this method is called. It is not impossible for this method to be called multiple
    * times by misbehaving stages. It is therefore recommended that the method be idempotent.
    */
  protected def stageStartup(): Unit = logger.debug(s"${getClass.getSimpleName} starting up at ${new Date}")

  /** Shuts down the stage, deallocating resources, etc.
    *
    * This method will be called when the stages receives a [[Disconnected]] command unless the
    * `inboundCommand` method is overridden. It is not impossible that this will not be called
    * due to failure for other stages to propagate shutdown commands. Conversely, it is also
    * possible for this to be called more than once due to the reception of multiple disconnect
    * commands. It is therefore recommended that the method be idempotent.
    */
  protected def stageShutdown(): Unit = logger.debug(s"${getClass.getSimpleName} shutting down at ${new Date}")

  /** Handle basic startup and shutdown commands.
    * This should clearly be overridden in all cases except possibly TailStages
    *
    * @param cmd a command originating from the channel
    */
  def inboundCommand(cmd: InboundCommand): Unit = cmd match {
    case Connected     => stageStartup()
    case Disconnected  => stageShutdown()
    case _             => logger.warn(s"$name received unhandled inbound command: $cmd")
  }
}

sealed trait Tail[I, O] extends Stage {
  private[pipeline] var _prevStage: Head[I, O] = null

  def channelRead(size: Int = -1, timeout: Duration = Duration.Inf): Future[I] = {
    try {
      if (_prevStage != null) {
        val f = _prevStage.readRequest(size)
        checkTimeout(timeout, f)
      } else _stageDisconnected()
    }  catch { case t: Throwable => return Future.failed(t) }
  }

  def channelWrite(data: O): Future[Unit] = {
    if (_prevStage != null) {
      try _prevStage.writeRequest(data)
      catch { case t: Throwable => Future.failed(t) }
    } else _stageDisconnected()
  }

  final def channelWrite(data: O, timeout: Duration): Future[Unit] = {
    val f = channelWrite(data)
    checkTimeout(timeout, f)
  }

  def channelWrite(data: Seq[O]): Future[Unit] = {
    if (_prevStage != null) {
      try _prevStage.writeRequest(data)
      catch { case t: Throwable => Future.failed(t) }
    } else _stageDisconnected()
  }

  final def channelWrite(data: Seq[O], timeout: Duration): Future[Unit] = {
    val f = channelWrite(data)
    checkTimeout(timeout, f)
  }

  final def spliceBefore(stage: MidStage[I, O, I, O]): Unit = {
    if (_prevStage != null) {
      stage._prevStage = _prevStage
      stage._nextStage = this
      _prevStage._nextStage = stage
      _prevStage = stage
    } else {
      val e = new Exception("Cannot splice stage before a disconnected stage")
      logger.error(e)("")
      throw e
    }
  }

  final def sendOutboundCommand(cmd: OutboundCommand): Unit = {
    logger.debug(s"Stage ${getClass.getSimpleName} sending outbound command: $cmd")
    if (_prevStage != null) {
      try _prevStage.outboundCommand(cmd)
      catch { case t: Throwable => logger.error(t)("Outbound command caused an error") }
    } else {
      val e = new Exception("Cannot send outbound command on disconnected stage")
      logger.error(e)("")
      throw e
    }
  }

  final def findOutboundStage(name: String): Option[Stage] = {
    if (this.name == name) Some(this)
    else if (_prevStage == null) None
    else _prevStage match {
      case s: Tail[_, _] => s.findOutboundStage(name)
      case t          => if (t.name == name) Some(t) else None

    }
  }

  final def findOutboundStage[C <: Stage](clazz: Class[C]): Option[C] = {
    if (clazz.isAssignableFrom(this.getClass)) Some(this.asInstanceOf[C])
    else if (_prevStage == null) None
    else _prevStage match {
      case s: Tail[_, _] => s.findOutboundStage[C](clazz)
      case t =>
        if (clazz.isAssignableFrom(t.getClass)) Some(t.asInstanceOf[C])
        else None
    }
  }

  final def replaceInline(leafBuilder: LeafBuilder[I, O], startup: Boolean = true): this.type = {
    stageShutdown()

    if (this._prevStage == null) return this

    this match {
      case m: MidStage[_, _, _, _] =>
        m.sendInboundCommand(Disconnected)
        m._nextStage = null

      case _ => // NOOP, must be a TailStage
    }

    val prev = this._prevStage
    this._prevStage._nextStage = null
    this._prevStage = null

    prev match {
      case m: MidStage[_, I, _, _] => leafBuilder.prepend(m)
      case h: HeadStage[I, _]   => leafBuilder.base(h)
    }

    if (startup) prev.sendInboundCommand(Command.Connected)

    this
  }

  /** Arranges a timeout for a write request */
  private def checkTimeout[T](timeout: Duration, f: Future[T]): Future[T] = {
    if (timeout.isFinite()) {
      val p = Promise[T]
      scheduleTimeout(p, f, timeout)
      p.future
    }
    else f
  }

  ///////////////////////////////////////////////////////////////////
  /** Schedules a timeout and sets it to race against the provided future
    * @param p Promise[T] to be completed by whichever comes first:
    *          the timeout or resolution of the Future[T] f
    * @param f Future the timeout is racing against
    * @param timeout time from now which is considered a timeout
    * @tparam T type of result expected
    */
  private def scheduleTimeout[T](p: Promise[T], f: Future[T], timeout: Duration) {
    val r = new Runnable {
      def run() {
        val a = new TimeoutException("Read request timed out")
        p.tryFailure(a)
      }
    }

    val ecf = Execution.scheduler.schedule(r, timeout)

    f.onComplete { t =>
      ecf.cancel()
      p.tryComplete(t)
    }(Execution.directec)
  }

  private def _stageDisconnected(): Future[Nothing] =
    Future.failed(new Exception(s"This stage '$name' isn't connected!"))
}

sealed trait Head[I, O] extends Stage {
  private[pipeline] var _nextStage: Tail[I, O] = null

  def readRequest(size: Int): Future[I]

  def writeRequest(data: O): Future[Unit]

  /** A simple default that serializes the write requests into the
    * single element form. It almost certainly should be overwritten
    * @param data sequence of elements which are to be written
    * @return Future which will resolve when pipeline is ready for more data or fails
    */
  def writeRequest(data: Seq[O]): Future[Unit] = {
    data.foldLeft[Future[Unit]](Future.successful(())){ (f, d) =>
      f.flatMap(_ => writeRequest(d))(directec)
    }
  }

  final def sendInboundCommand(cmd: InboundCommand): Unit = {
    logger.debug(s"Stage ${getClass.getSimpleName} sending inbound command: $cmd")
    if (_nextStage != null) {
      try _nextStage.inboundCommand(cmd)
      catch { case t: Throwable => outboundCommand(Error(t)) }
    } else {
      val e = new Exception("Cannot send inbound command on disconnected stage")
      logger.error(e)("")
      throw e
    }
  }

  /** Receives inbound commands
    * Override to capture commands. */
  override def inboundCommand(cmd: InboundCommand): Unit = {
    super.inboundCommand(cmd)
    sendInboundCommand(cmd)
  }

  /** Receives outbound commands
    * Override to capture commands. */
  def outboundCommand(cmd: OutboundCommand): Unit = cmd match {
    case Connect    => stageStartup()
    case Disconnect => stageShutdown()
    case Error(e)   => logger.error(e)(s"$name received unhandled error command")
    case cmd        => logger.warn(s"$name received unhandled outbound command: $cmd")
  }

  final def spliceAfter(stage: MidStage[I, O, I, O]): Unit = {
    if (_nextStage != null) {
      stage._nextStage = _nextStage
      stage._prevStage = this
      _nextStage._prevStage = stage
      _nextStage = stage
    } else {
      val e = new Exception("Cannot splice stage after disconnected stage")
      logger.error(e)("")
      throw e
    }
  }

  final def findInboundStage(name: String): Option[Stage] = {
    if (this.name == name) Some(this)
    else if (_nextStage == null) None
    else _nextStage match {
      case s: MidStage[_, _, _, _] => s.findInboundStage(name)
      case t: TailStage[_, _]   => if (t.name == name) Some(t) else None
    }
  }

  final def findInboundStage[C <: Stage](clazz: Class[C]): Option[C] = {
    if (clazz.isAssignableFrom(this.getClass)) Some(this.asInstanceOf[C])
    else if (_nextStage == null) None
    else _nextStage match {
      case s: MidStage[_, _, _, _] => s.findInboundStage[C](clazz)
      case t: TailStage[_, _] =>
        if (clazz.isAssignableFrom(t.getClass)) Some(t.asInstanceOf[C])
        else None
    }
  }
}

/** The three fundamental stage types */
trait TailStage[I, O] extends Tail[I, O]

trait HeadStage[I, O] extends Head[I, O]

trait MidStage[I1, O1, I2, O2] extends Tail[I1, O1] with Head[I2, O2] {

  // Overrides to propagate commands.
  override def outboundCommand(cmd: OutboundCommand): Unit = {
    super.outboundCommand(cmd)
    sendOutboundCommand(cmd)
  }

  final def replaceInline(stage: MidStage[I1, O1, I2, O2]): this.type = {
    stageShutdown()

    if (_nextStage == null || _prevStage == null) return this

    _nextStage._prevStage = stage
    _prevStage._nextStage = stage

    this._nextStage = null
    this._prevStage = null
    this
  }

  final def replaceNext(stage: LeafBuilder[I2, O2]): Tail[I2, O2] = _nextStage.replaceInline(stage)

  final def removeStage(implicit ev: MidStage[I1,O1,I2,O2] =:= MidStage[I1, O1, I1, O1]): Unit = {
    stageShutdown()

    if (_prevStage != null && _nextStage != null) {
      val me: MidStage[I1, O1, I1, O1] = ev(this)
      _prevStage._nextStage = me._nextStage
      me._nextStage._prevStage = me._prevStage

      _nextStage = null
      _prevStage = null
    }
    else logger.warn(s"Cannot remove a disconnected stage ${this.getClass.getSimpleName}")
  }
}
