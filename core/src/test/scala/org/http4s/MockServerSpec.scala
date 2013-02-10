package org.http4s

import scala.language.implicitConversions
import concurrent.Future
import scala.language.reflectiveCalls

import concurrent.{Promise, Future, Await}
import scala.concurrent.duration._

import org.specs2.mutable.Specification
import play.api.libs.iteratee._
import org.specs2.time.NoTimeConversions
import scala.io.Codec

import Writable._
import Bodies._
import java.nio.charset.Charset

class MockServerSpec extends Specification with NoTimeConversions {
  import scala.concurrent.ExecutionContext.Implicits.global

  def stringHandler(req: Request[Raw], maxSize: Int = Integer.MAX_VALUE)(f: String => Responder[Raw]): Future[Responder[Raw]] = {
    val it = Traversable.takeUpTo[Chunk](maxSize)
      .transform(Iteratee.consume[Chunk]().asInstanceOf[Iteratee[Chunk, Chunk]].map {
        bs => new String(bs, req.charset)
      })
      .flatMap(Iteratee.eofOrElse(Responder(statusLine = StatusLine.RequestEntityTooLarge, body = EmptyBody)))
      .map(_.right.map(f).merge)
    req.body.run(it)
  }

  val server = new MockServer({
    case req if req.requestMethod == Method.Post && req.pathInfo == "/echo" =>
      Future.successful(Responder(body = req.body))

    case req if req.requestMethod == Method.Post && req.pathInfo == "/sum" =>
      stringHandler(req, 16) { s =>
        val sum = s.split('\n').map(_.toInt).sum
        Responder[Raw](body = Enumerator(sum.toString.getBytes))
      }

    case req if req.pathInfo == "/fail" =>
      Future.successful(Responder(statusLine = StatusLine.InternalServerError, body = EmptyBody))
  })

  def response(req: Request[Raw]): MockServer.Response = {
    Await.result(server(req), 5 seconds)
  }

  "A mock server" should {
    "handle matching routes" in {
      val req = Request[Raw](requestMethod = Method.Post, pathInfo = "/echo",
        body = Enumerator("one", "two", "three").map(_.getBytes))
      new String(response(req).body) should_==("onetwothree")
    }

    "runs a sum" in {
      val req = Request[Raw](requestMethod = Method.Post, pathInfo = "/sum",
        body = Enumerator("1\n", "2\n3", "\n4").map(_.getBytes))
      new String(response(req).body) should_==("10")
    }

    "runs too large of a sum" in {
      val req = Request[Raw](requestMethod = Method.Post, pathInfo = "/sum",
        body = Enumerator("12345678\n901234567").map(_.getBytes))
      response(req).statusLine should_==(StatusLine.RequestEntityTooLarge)
    }

    "fall through to not found" in {
      val req = Request[Raw](pathInfo = "/bielefield", body = EmptyBody)
      response(req).statusLine should_== StatusLine.NotFound
    }

    "handle exceptions" in {
      val req = Request[Raw](pathInfo = "/fail", body = EmptyBody)
      response(req).statusLine should_== StatusLine.InternalServerError
    }
  }
}
