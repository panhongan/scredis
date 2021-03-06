package scredis.io

import java.util.concurrent.locks.ReentrantLock

import akka.actor._
import scredis.exceptions._
import scredis.protocol._
import scredis.util.UniqueNameGenerator

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Try

/**
 * This trait represents a blocking connection to a `Redis` server.
 */
abstract class AkkaBlockingConnection(
  system: ActorSystem,
  host: String,
  port: Int,
  authOpt: Option[AuthConfig],
  database: Int,
  nameOpt: Option[String],
  decodersCount: Int,
  connectTimeout: FiniteDuration,
  maxWriteBatchSize: Int,
  tcpSendBufferSizeHint: Int,
  tcpReceiveBufferSizeHint: Int,
  akkaListenerDispatcherPath: String,
  akkaIODispatcherPath: String,
  akkaDecoderDispatcherPath: String,
  failCommandOnConnecting:Boolean
) extends AbstractAkkaConnection(
  system = system,
  host = host,
  port = port,
  authOpt = authOpt,
  database = database,
  nameOpt = nameOpt,
  decodersCount = decodersCount,
  receiveTimeoutOpt = None,
  connectTimeout = connectTimeout,
  maxWriteBatchSize = maxWriteBatchSize,
  tcpSendBufferSizeHint = tcpSendBufferSizeHint,
  tcpReceiveBufferSizeHint = tcpReceiveBufferSizeHint,
  akkaListenerDispatcherPath = akkaListenerDispatcherPath,
  akkaIODispatcherPath = akkaIODispatcherPath,
  akkaDecoderDispatcherPath = akkaDecoderDispatcherPath
) with BlockingConnection {
  
  private val lock = new ReentrantLock()
  
  protected val listenerActor = system.actorOf(
    Props(
      classOf[ListenerActor],
      host,
      port,
      authOpt,
      database,
      nameOpt,
      decodersCount,
      receiveTimeoutOpt,
      connectTimeout,
      maxWriteBatchSize,
      tcpSendBufferSizeHint,
      tcpReceiveBufferSizeHint,
      akkaIODispatcherPath,
      akkaDecoderDispatcherPath,
      failCommandOnConnecting
    ).withDispatcher(akkaListenerDispatcherPath),
    UniqueNameGenerator.getUniqueName(s"${nameOpt.getOrElse(s"$host-$port")}-listener-actor")
  )
  
  private def withLock[A](f: => A): A = {
    if (lock.tryLock) {
      try {
        f
      } finally {
        lock.unlock()
      }
    } else {
      throw RedisIOException("Trying to send request on a blocked connection")
    }
  }
  
  override protected[scredis] def sendBlocking[A](request: Request[A])(
    implicit timeout: Duration
  ): Try[A] = withLock {
    logger.debug(s"Sending blocking request: $request")
    updateState(request)
    val future = Protocol.send(request, listenerActor)
    Try(Await.result(future, timeout))
  }
  
}