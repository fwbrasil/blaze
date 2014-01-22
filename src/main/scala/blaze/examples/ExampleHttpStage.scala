package blaze
package examples

import java.nio.ByteBuffer

import scala.concurrent.Future
import blaze.pipeline.stages.http.{HttpResponse, HttpStage}
import blaze.http_parser.BaseExceptions.BadRequest

/**
 * @author Bryce Anderson
 *         Created on 1/5/14
 */
class ExampleHttpStage(maxRequestLength: Int) extends HttpStage(maxRequestLength) {
  def handleRequest(method: String, uri: String, headers: Seq[(String, String)], body: ByteBuffer): Future[HttpResponse] = {

    if (uri.endsWith("error")) Future.failed(new BadRequest("You requested an error!"))
    else {
      val body = ByteBuffer.wrap(s"Hello world!\nRequest URI: $uri".getBytes())
      Future.successful(HttpResponse("OK", 200, Nil, body))
    }
  }
}