/*
 * Copyright 2014, by Vladimir Kostyukov and Contributors.
 *
 * This file is a part of a Finch library that may be found at
 *
 *      https://github.com/vkostyukov/finch
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributor(s): -
 */

package io

import com.twitter.util.Future
import com.twitter.finagle.{Filter, Service}
import com.twitter.finagle.http.service.RoutingService
import com.twitter.finagle.http.path.Path
import com.twitter.finagle.builder.ServerBuilder
import scala.util.parsing.json.{JSONFormat, JSONType, JSONArray, JSONObject}
import java.net.InetSocketAddress
import org.jboss.netty.handler.codec.http.{HttpResponseStatus, HttpMethod}
import scala.util.Random
import com.twitter.finagle.http.{Http, Status, Version, Response, Request, RichHttp}
import javax.annotation.ParametersAreNonnullByDefault

/***
 * Hi! I'm Finch - a super-tiny library atop of Finagle that makes the
 * development of RESTFul API services more pleasant and slick.
 *
 * I'm built around three very simple building-blocks:
 *   1. ''HttpServiceOf[A]'' that maps ''HttpRequest'' to some response
 *      (both are just a special cases of Finagle's ''Service'')
 *   2. ''Facet[+A, -B]'' that transforms service's response ''A'' to ''B''
 *      (just a special case of Finagle's ''Filter'')
 *   3. ''ResourceOf[A]'' that provides route information about a particular resource
 *      (just a special case of ''PartialFunction'' from route to ''HttpService'')
 *   4. ''RestApiOf[A]'' that aggregates all the things together: resources and a set
 *      of rules (exposed as a combination of facets and filters) that transform the
 *      ''HttpResourceOf[A]'' to a ''HttpResource''.
 *
 * I'm trying to follow the principles of my elder brother and keep the things
 * as composable as possible.
 *
 *   (a) In order to mark the difference between filters and facets and show the
 *       direction of a data-flow, the facets are composed by ''afterThat'' operator
 *       within a reversed order:
 *
 *        '''val s = service afterThat facetA afterThat facetB'''

 *   (b) Resources might be treated as partial functions, so they may be composed
 *       together with ''orElse'' operator:
 *
 *        '''val r = resourceA orElse resourceB'''

 *   (c) Another useful resource operator is ''andThen'' that takes a function from
 *       ''HttpService'' to ''HttpService'' and returns a new resource within function
 *       applied to its every route endpoint.
 *
 *        '''val r = resource andThen { filter andThen _ }'''
 *
 *   (d) Resources may also be composed with filters by using the ''afterThat'' operator
 *       in a familiar way:
 *
 *        '''val r = authorize afterThat resource'''
 *
 *   (e) Primitive filters (that don't change anything) are composed with the same
 *       ''afterThat'' operator:
 *
 *        '''val f = filterA afterThat filterB afterThat filterC'''
 *
 * Have fun writing a reusable and scalable code with me!
 *
 * - https://github.com/vkostyukov/finch
 * - http://vkostyukov.ru
 */
package object finch {
  type HttpRequest = Request
  type HttpResponse = Response
  type JsonResponse = JSONType

  /**
   * Alters any object within a ''toFuture'' method.
   *
   * @param any an object to be altered
   *
   * @tparam A an object type
   */
  implicit class _AnyToFuture[A](val any: A) extends AnyVal {

    /**
     * Converts this ''any'' object into a ''Future''
     *
     * @return an object wrapped with ''Future''
     */
    def toFuture: Future[A] = Future.value(any)
  }

  /**
   * Alters any throwable with a ''toFutureException'' method.
   *
   * @param t a throwable to be altered
   */
  implicit class _ThrowableToFutureException(val t: Throwable) extends AnyVal {

    /**
     * Converts this throwable object into a ''Future'' exception.
     *
     * @return an exception wrapped with ''Future''
     */
    def toFutureException[A]: Future[A] = Future.exception(t)
  }

  /**
   * Alters underlying filter within ''afterThat'' methods composing a filter
   * with a given resource or withing a next filter.
   *
   * @param filter a filter to be altered
   */
  implicit class _FilterAndThen[ReqIn <: HttpRequest, ReqOut <: HttpRequest, RepIn, RepOut](
      val filter: Filter[ReqIn, RepOut, ReqOut, RepIn]) extends AnyVal {

    /**
     * Composes this filter within a given resource ''thatResource''.
     *
     * @param resource a resource to compose
     *
     * @return a resource composed with filter
     */
    def andThen(resource: Endpoint[ReqOut, RepIn]) =
      resource andThen { service =>
        filter andThen service
      }
  }

  /**
   * Alters underlying service within ''afterThat'' method composing a service
   * with a given filter.
   *
   * @param service a service to be altered
   *
   * @tparam RepIn a input response type
   */
  implicit class _ServiceAfterThat[Req <: HttpRequest, RepIn](service: Service[Req, RepIn]) {

    /**
     * Composes this service with a given facet-with-request ''facet''.
     *
     * @param facet a facet to compose
     * @tparam RepOut an output response type
     *
     * @return a new service composed with facet.
     */
    def afterThat[ReqIn >: Req <: HttpRequest, RepOut](facet: FacetWithRequest[ReqIn, RepIn, RepOut]) =
      new Service[Req, RepOut] {
        def apply(req: Req) = service(req) flatMap { facet(req)(_) }
      }
  }

  /**
   * Alters underlying json object within finagled methods.
   *
   * @param json a json object to be altered
   */
  implicit class _JsonObjectOps(val json: JSONObject) extends AnyVal {

    /**
     * Retrieves the typed ''A'' value associated with a given ''tag'' in this
     * json object
     *
     * @param path a tag
     * @tparam A a value type
     *
     * @return a value associated with a tag
     */
    def get[A](path: String) = getOption[A](path).get

    /**
     * Retrieves the typed ''A'' option of a value associated with a given ''tag''
     * in this json object.
     *
     * @param path a path
     * @tparam A a value type
     *
     * @return an option of a value associated with a tag
     */
    def getOption[A](path: String) = {
      def loop(path: List[String], j: JSONObject): Option[A] = path match {
        case tag :: Nil => j.obj.get(tag) map { _.asInstanceOf[A] }
        case tag :: tail => j.obj.get(tag) match {
          case Some(jj: JSONObject) => loop(tail, jj)
          case _ => None
        }
      }

      loop(path.split('.').toList, json)
    }

    /**
     * Put scala doc here.
     *
     * @param fn
     * @return
     */
    def within(fn: Map[String, Any] => Map[String, Any]) = JSONObject(fn(json.obj))

    /**
     * Removes all null-value properties from this json object.
     *
     * @return a compacted json object
     */
    def compacted = {
      def loop(obj: Map[String, Any]): Map[String, Any] = obj.flatMap {
        case (t, JsonNull) => Map.empty[String, Any]
        case (tag, j: JSONObject) =>
          val o = loop(j.obj)
          if (o.isEmpty) Map.empty[String, Any]
          else Map(tag -> JSONObject(o))
        case (tag, value) => Map(tag -> value)
      }

      JSONObject(loop(json.obj))
    }
  }

  /**
   * Alters underlying json array within finagled methods.
   *
   * @param json a json array to alter
   */
  implicit class _JsonArrayOps(val json: JSONArray) extends AnyVal {

    /**
     * Maps this json array into a json array with all the items mapped
     * via pure function ''fn''.
     *
     * @param fn a pure function to map items
     *
     * @return a json array with items mapped
     */
    def within(fn: List[Any] => List[Any]) = JSONArray(fn(json.list))
  }

  /**
   * An ''HttpService'' with specified response type ''Rep''.
   *
   * @tparam Rep the response type
   */
  trait HttpServiceOf[+Rep] extends Service[HttpRequest, Rep]

  /**
   * A pure ''HttpService''.
   */
  trait HttpService extends HttpServiceOf[HttpResponse]

  /**
   * A ''Facet'' that has a request available.
   *
   * @tparam Req the request type
   * @tparam RepIn the input response type
   * @tparam RepOut the output response type
   */
  trait FacetWithRequest[Req <: HttpRequest, -RepIn, +RepOut] { self =>

    /**
     * Converts given pair ''req'' and ''rep'' of type ''RepIn'' to type ''RepOut''.
     *
     * @param req the request
     * @param rep the response to convert
     *
     * @return a converted response
     */
    def apply(req: Req)(rep: RepIn): Future[RepOut]

    /**
     * Composes this facet-with-request with given ''next'' facet.
     *
     * @param next the facet to compose with
     * @tparam Rep the response type
     *
     * @return a composed facet-with-request
     */
    def afterThat[ReqIn >: Req <: HttpRequest, Rep](next: FacetWithRequest[ReqIn, RepOut, Rep]) =
      new FacetWithRequest[Req, RepIn, Rep] {
        def apply(req: Req)(rep: RepIn) = self(req)(rep) flatMap { next(req)(_) }
      }
  }

  /**
   * Facet implements Filter interface but has a different meaning. Facets are
   * converts services responses from ''RepIn'' to ''RepOut''.
   *
   * @tparam RepIn the input response type
   * @tparam RepOut the output response type
   */
  trait Facet[-RepIn, +RepOut] extends FacetWithRequest[HttpRequest, RepIn, RepOut] {

    /**
     * Converts given ''rep'' from ''RepIn'' to ''RepOut'' type.
     *
     * @param rep the response to convert
     *
     * @return a converted response
     */
    def apply(rep: RepIn): Future[RepOut]

    def apply(req: HttpRequest)(rep: RepIn) = apply(rep)
  }

  object JsonNull {
    override def toString = null
  }

  object JsonObject {
    def apply(args: (String, Any)*) = {
      def loop(path: List[String], value: Any): Map[String, Any] = path match {
        case tag :: Nil => Map(tag -> value)
        case tag :: tail => Map(tag -> JSONObject(loop(tail, value)))
      }

      val jsonSeq = args.flatMap {
        case (path, value) =>
          Seq(JSONObject(loop(path.split('.').toList, if (value == null) JsonNull else value)))
      }

      jsonSeq.foldLeft(JsonObject.empty) { mergeRight }
    }

    def empty = JSONObject(Map.empty[String, Any])
    def unapply(outer: Any): Option[JSONObject] = outer match {
      case inner: JSONObject => Some(inner)
      case _ => None
    }

    def mergeRight(a: JSONObject, b: JSONObject) = mergeLeft(b, a)
    def mergeLeft(a: JSONObject, b: JSONObject): JSONObject = {
      def loop(aa: Map[String, Any], bb: Map[String, Any]): Map[String, Any] =
        if (aa.isEmpty) bb
        else if (bb.isEmpty) aa
        else {
          val (tag, value) = aa.head
          if (!bb.contains(tag)) loop(aa.tail, bb + (tag -> value))
          else (value, bb(tag)) match {
            case (ja: JSONObject, jb: JSONObject) =>
              loop(aa.tail, bb + (tag -> JSONObject(loop(ja.obj, jb.obj))))
            case (_, _) => loop(aa.tail, bb + (tag -> value))
          }
        }

      JSONObject(loop(a.obj, b.obj))
    }
  }

  /**
   * A companion object for ''JSONArray''.
   */
  object JsonArray {
    def apply(args: Any*) = JSONArray(args.toList)
    def empty = JSONArray(List.empty[Any])
    def unapply(outer: Any): Option[JSONArray] = outer match {
      case inner: JSONArray => Some(inner)
      case _ => None
    }
    def concat(a: JSONArray, b: JSONArray) = JSONArray(a.list ::: b.list)
  }

  /**
   * A json formatter that primary escape special chars.
   */
  trait JsonFormatter extends JSONFormat.ValueFormatter { self =>
    def apply(x: Any) = x match {
      case s: String => "\"" + formatString(s) + "\""
      case o: JSONObject => o.toString(self)
      case a: JSONArray => a.toString(self)
      case other => other.toString
    }

    def formatString(s: String) = s flatMap { escapeOrSkip(_) }

    def escapeOrSkip: PartialFunction[Char, String] = escapeChar orElse {
      case c => c.toString
    }

    /**
     * A partial function that defines a set of rules on how to escape the
     * special characters in a string.
     *
     * @return an escaped char represented as a string
     */
    def escapeChar: PartialFunction[Char, String]
  }

  object DefaultJsonFormatter extends JsonFormatter {
    def escapeChar = {
      case '"'  => "\\\""
      case '\\' => "\\\\"
      case '\b' => "\\b"
      case '\f' => "\\f"
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
    }
  }

  /**
   * A facet that turns a ''JsonResponse'' into an ''HttpResponse''.
   */
  class TurnJsonIntoHttpWithFormatter(formatter: JsonFormatter = DefaultJsonFormatter)
      extends Facet[JsonResponse, HttpResponse] {

    def apply(rep: JsonResponse) = {
      val reply = Response(Version.Http11, Status.Ok)
      reply.setContentTypeJson()
      reply.setContentString(rep.toString(formatter))

      reply.toFuture
    }
  }

  /**
   * A facet that turns a ''JsonResponse'' into an ''HttpResponse''.
   */
  object TurnJsonIntoHttp extends TurnJsonIntoHttpWithFormatter

  /**
   * A facet that turns a ''JsonResponse'' into an ''HttpResponse'' with http status.
   *
   * @param statusTag the status tag identifier
   */
  class TurnJsonIntoHttpWithStatusFromTag(
    statusTag: String = "status",
    formatter: JsonFormatter = DefaultJsonFormatter) extends Facet[JsonResponse, HttpResponse] {

    def apply(rep: JsonResponse) = {
      val status = rep match {
        case JsonObject(o) =>
          HttpResponseStatus.valueOf(o.getOption[Int](statusTag).getOrElse(200))
        case _ => Status.Ok
      }

      val reply = Response(Version.Http11, status)
      reply.setContentTypeJson()
      reply.setContentString(rep.toString(DefaultJsonFormatter))

      reply.toFuture
    }
  }

  /**
   * A facet that turns a ''JsonResponse'' to an ''HttpResponse'' with http status.
   */
  object TurnJsonIntoHttpWithStatus extends TurnJsonIntoHttpWithStatusFromTag

  /**
   * A REST API resource that primary defines a ''route''.
   *
   * @tparam Rep a response type
   */
  trait Endpoint[Req <: HttpRequest, Rep] { self =>

    /**
     * @return a route of this resource
     */
    def route: PartialFunction[(HttpMethod, Path), Service[Req, Rep]]

    /**
     * Combines this resource with ''that'' resource. A new resource
     * contains routes of both this and ''that'' resources.
     *
     * @param that the resource to be combined with
     *
     * @return a new resource
     */
    def orElse(that: Endpoint[Req, Rep]): Endpoint[Req, Rep] = orElse(that.route)

    /**
     * Combines this resource with ''that'' partial function that defines
     * a route. A new resource contains routes of both this resource and ''that''
     * partial function
     *
     * @param that the partial function to be combined with
     *
     * @return a new resource
     */
    def orElse(that: PartialFunction[(HttpMethod, Path), Service[Req, Rep]]): Endpoint[Req, Rep] =
      new Endpoint[Req, Rep] {
        def route = self.route orElse that
      }

    /**
     * Applies given function ''fn'' to every route's endpoints of this resource.
     *
     * @param fn the function to be applied
     *
     * @return a new resource
     */
    def andThen[ReqOut <: HttpRequest, RepOut](fn: Service[Req, Rep] => Service[ReqOut, RepOut]) =
      new Endpoint[ReqOut, RepOut] {
        def route = self.route andThen fn
      }

    /**
     * Applies given ''facet'' to this resource.
     *
     * @param facet a facet to apply
     * @tparam RepOut a response type of a new resource
     *
     * @return a new resource
     */
    def afterThat[ReqIn >: Req <: HttpRequest, RepOut](facet: FacetWithRequest[ReqIn, Rep, RepOut]) =
      andThen { service =>
        new Service[Req, RepOut] {
          def apply(req: Req) = service(req) flatMap { facet(req)(_) }
        }
      }
  }

  /**
   * A default REST resource.
   */
  trait EndpointOf[Rep] extends Endpoint[HttpRequest, Rep]

  /**
   * A base class for ''RestApi'' backend.
   */
  abstract class Api[Req <: HttpRequest, Rep] extends App {

    /**
     * @return a resource of this API
     */
    def endpoint: Endpoint[Req, Rep]

    /**
     * Sends a request ''req'' to this API.

     * @param req the request to send
     * @return a response wrapped with ''Future''
     */
    def apply(req: Req): Future[Rep] =
      endpoint.route(req.method -> Path(req.path))(req)

    /**
     * @return a name of this Finch instance
     */
    def name = "Finch-" + new Random().alphanumeric.take(20).mkString

    /**
     * Exposes given ''resource'' at specified ''port'' and serves the requests.
     *
     * @param port the socket port number to listen
     * @param fn the function that transforms a resource type to ''HttpResponse''
     */
    def exposeAt(port: Int)(fn: Endpoint[Req, Rep] => Endpoint[HttpRequest, HttpResponse]): Unit = {

      val httpResource = fn(endpoint)

      val service = new RoutingService[HttpRequest](
        new PartialFunction[HttpRequest, Service[HttpRequest, HttpResponse]] {
          def apply(req: HttpRequest) = httpResource.route(req.method -> Path(req.path))
          def isDefinedAt(req: HttpRequest) = httpResource.route.isDefinedAt(req.method -> Path(req.path))
        })

      ServerBuilder()
        .codec(RichHttp[HttpRequest](Http()))
        .bindTo(new InetSocketAddress(port))
        .name(name)
        .build(service)
    }
  }

  /**
   * A default REST API backend.
   */
  abstract class ApiOf[Rep] extends Api[HttpRequest, Rep]

  class RequestReaderError(m: String) extends Exception(m)
  class ParamNotFound(param: String) extends RequestReaderError("Param '" + param + "' not found in the request.")
  class ValidationFailed(rule: String) extends RequestReaderError("Request validation failed: '" + rule + "'.")

  trait FutureRequestReader[A] { self =>
    def apply(req: HttpRequest): Future[A]

    def flatMap[B](fn: A => FutureRequestReader[B]) = new FutureRequestReader[B] {
      def apply(req: HttpRequest) = self(req) flatMap { fn(_)(req) }
    }

    def map[B](fn: A => B) = new FutureRequestReader[B] {
      def apply(req: HttpRequest) = self(req) map fn
    }
  }

  trait RequestReader[A] { self =>
    def apply(req: HttpRequest): A

    def flatMap[B](fn: A => RequestReader[B]) = new RequestReader[B] {
      def apply(req: HttpRequest) = fn(self(req))(req)
    }

    def map[B](fn: A => B) = new RequestReader[B] {
      def apply(req: HttpRequest) = fn(self(req))
    }
  }

  private[this] object StringToNumberOrFail {
    def apply[A](rule: String)(number: => A) = new FutureRequestReader[A] {
      def apply(req: HttpRequest) =
        try number.toFuture
        catch { case _: NumberFormatException => new ValidationFailed(rule).toFutureException }
    }
  }

  private[this] object SomeStringToSomeNumber {
    def apply[A](fn: String => A)(o: Option[String]) = o.flatMap { s =>
      try Some(fn(s))
      catch { case _: NumberFormatException => None }
    }
  }

  private[this] object StringsToNumbers {
    def apply[A](fn: String => A)(l: List[String]) = l.flatMap { s =>
      try List(fn(s))
      catch { case _: NumberFormatException => Nil }
    }
  }

  object RequiredParam {
    def apply(param: String) = new FutureRequestReader[String] {
      def apply(req: HttpRequest) = req.params.get(param) match {
        case Some("") => new ValidationFailed(param + " should not be empty").toFutureException
        case Some(value) => value.toFuture
        case None => new ParamNotFound(param).toFutureException
      }
    }
  }

  object RequiredIntParam {
    def apply(param: String) = for {
      s <- RequiredParam(param)
      n <- StringToNumberOrFail(param + " should be integer")(s.toInt)
    } yield n
  }

  object RequiredLongParam {
    def apply(param: String) = for {
      s <- RequiredParam(param)
      n <- StringToNumberOrFail(param + " should be long")(s.toLong)
    } yield n
  }

  object RequiredBooleanParam {
    def apply(param: String) = for {
      s <- RequiredParam(param)
      n <- StringToNumberOrFail(param + " should be boolean")(s.toBoolean)
    } yield n
  }

  object OptionalParam {
    def apply(param: String) = new FutureRequestReader[Option[String]] {
      def apply(req: HttpRequest) = req.params.get(param).toFuture
    }
  }

  object OptionalIntParam {
    def apply(param: String) = for {
      o <- OptionalParam(param)
    } yield SomeStringToSomeNumber(_.toInt)(o)
  }

  object OptionalLongParam {
    def apply(param: String) = for {
      o <- OptionalParam(param)
    } yield SomeStringToSomeNumber(_.toLong)(o)
  }

  object OptionalBooleanParam {
    def apply(param: String) = for {
      o <- OptionalParam(param)
    } yield SomeStringToSomeNumber(_.toBoolean)(o)
  }

  object Param {
    def apply(param: String) = new RequestReader[Option[String]] {
      def apply(req: HttpRequest) = req.params.get(param)
    }
  }

  object IntParam {
    def apply(param: String) = for {
      o <- Param(param)
    } yield SomeStringToSomeNumber(_.toInt)(o)
  }

  object LongParam {
    def apply(param: String) = for {
      o <- Param(param)
    } yield SomeStringToSomeNumber(_.toLong)(o)
  }

  object BooleanParam {
    def apply(param: String) = for {
      o <- Param(param)
    } yield SomeStringToSomeNumber(_.toBoolean)(o)
  }

  object ValidationRule {
    def apply(rule: String)(predicate: => Boolean) = new FutureRequestReader[Unit] {
      def apply(req: HttpRequest) =
       if (predicate) Future.Done
       else new ValidationFailed(rule).toFutureException
    }
  }

  object RequiredParams {
    def apply(param: String) = new FutureRequestReader[List[String]] {
      def apply(req: HttpRequest) = req.params.getAll(param).toList.flatMap(_.split(",")) match {
        case Nil => new ParamNotFound(param).toFutureException
        case unfiltered => unfiltered.filter(_ != "") match {
          case Nil => new ValidationFailed(param + " should not be empty").toFutureException
          case filtered => filtered.toFuture
        }
      }
    }
  }

  object RequiredIntParams {
    def apply(param: String) = for {
      ss <- RequiredParams(param)
      ns <- StringToNumberOrFail(param + " should be integer")(ss.map { _.toInt })
    } yield ns
  }

  object RequiredLongParams {
    def apply(param: String) = for {
      ss <- RequiredParams(param)
      ns <- StringToNumberOrFail(param + " should be integer")(ss.map { _.toLong })
    } yield ns
  }

  object RequiredBooleanParams {
    def apply(param: String) = for {
      ss <- RequiredParams(param)
      ns <- StringToNumberOrFail(param + " should be integer")(ss.map { _.toBoolean })
    } yield ns
  }

  object OptionalParams {
    def apply(param: String) = new FutureRequestReader[List[String]] {
      def apply(req: HttpRequest) = req.params.getAll(param).toList.flatMap(_.split(",")).toFuture
    }
  }

  object OptionalIntParams {
    def apply(param: String) = for {
      l <- OptionalParams(param)
    } yield StringsToNumbers(_.toInt)(l)
  }

  object OptionalLongParams {
    def apply(param: String) = for {
      l <- OptionalParams(param)
    } yield StringsToNumbers(_.toLong)(l)
  }

  object OptionalBooleanParams {
    def apply(param: String) = for {
      l <- OptionalParams(param)
    } yield StringsToNumbers(_.toBoolean)(l)
  }

  object Params {
    def apply(param: String) = new RequestReader[List[String]] {
      def apply(req: HttpRequest) = req.params.getAll(param).toList.flatMap(_.split(","))
    }
  }

  object IntParams {
    def apply(param: String) = for {
      l <- Params(param)
    } yield StringsToNumbers(_.toInt)(l)
  }

  object LongParams {
    def apply(param: String) = for {
      l <- Params(param)
    } yield StringsToNumbers(_.toLong)(l)
  }

  object BooleanParams {
    def apply(param: String) = for {
      l <- Params(param)
    } yield StringsToNumbers(_.toBoolean)(l)
  }
}
