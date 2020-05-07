package app.examples

import app.sources.ListSource

import fetch.Fetch
import fetch.fetchM

import cats.effect.{ContextShift, IO, Timer}
import cats.syntax.option._
import cats.syntax.apply._
import cats.instances.list._
import cats.syntax.traverse._
import fetch.fetchM
import cats.syntax.flatMap._
import cats.syntax.functor._

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.global

object BatchingExample extends App {
  implicit val ec: ExecutionContext = global
  implicit val cs: ContextShift[IO] = IO.contextShift(ec) // для Fetch.run и app.ListDataSource
  implicit val timer: Timer[IO]     = IO.timer(ec) // для Fetch.run

  val list = List("a", "b", "c", "d", "e", "f", "g", "h", "i")
  val data = new ListSource(list, 2.some)

  println("tuple")
  // Два запроса выполнятся пачкой
  val tuple: Fetch[IO, (Option[String], Option[String])] = (data.fetchElem(0), data.fetchElem(1)).tupled
  println(Fetch.run(tuple).unsafeRunSync()) // (Some(a),Some(b))

  /** Дедупликация*/
  println("tupleD")
  val tupleD: Fetch[IO, (Option[String], Option[String])] = (data.fetchElem(0), data.fetchElem(0)).tupled
  Fetch.run(tupleD).unsafeRunSync()
  //INFO app.sources.ListSource - Processing element from index 0
  //(Some(a),Some(a))

  println("for")

  val l: Fetch[IO, (Option[String], Option[String])] = for {
    d1 <- data.fetchElem(0)
    d2 <- data.fetchElem(0)
  } yield (d1, d2)
  Fetch.run(l).unsafeRunSync()
  //INFO app.sources.ListSource - Processing element from index 0
  //(Some(a),Some(a))

  println("traverse")

  def findMany: Fetch[IO, List[Option[String]]] =
    List(0, 1, 2, 3, 4, 5).traverse(data.fetchElem)

  Fetch.run(findMany).unsafeRunSync

}
