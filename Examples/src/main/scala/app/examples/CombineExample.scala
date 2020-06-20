package app.examples

import app.ContextEntities
import app.sources.{ListSource, RandomSource}
import cats.effect.IO
import fetch.Fetch
import fetch.fetchM
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.instances.list._
import cats.syntax.traverse._

object CombineExample extends App with ContextEntities {


  val listSource = new ListSource(List("a", "b", "c"))
  val randomSource = new RandomSource()

  def fetchMulti: Fetch[IO, (Int, String)] =
    for {
      rnd <- Fetch(3, randomSource.source)  // Fetch[IO, Int]
      char <- Fetch(rnd, listSource.source)  // Fetch[IO, String]
    } yield (rnd, char)

  println(Fetch.run(fetchMulti).unsafeRunSync)  // например, (0,a)

  val listFetch: List[Fetch[IO, Int]] = List(
    Fetch(10, randomSource.source),
    Fetch(10, randomSource.source),
    Fetch(10, randomSource.source),
    Fetch(10, randomSource.source),
    Fetch(10, randomSource.source)
  )

  def fetchRandomInt(max: Int) = Fetch(max, randomSource.source)

  val fetchTrv: Fetch[IO, List[Int]]
    = List(10, 10, 10, 10, 10).traverse(fetchRandomInt)

  val fetchSeq: Fetch[IO, List[Int]] = listFetch.sequence

  println(Fetch.run(fetchTrv).unsafeRunSync)  // List(8, 8, 8, 8, 8)

  println("Дедупликация:")

  /** Дедупликация */
  def fetchMultiD: Fetch[IO, (Int, String, Int, String)] =
    for {
      rnd1 <- Fetch(3, randomSource.source)  // Fetch[IO, Int]
      char1 <- Fetch(rnd1, listSource.source)  // Fetch[IO, String]
      rnd2 <- Fetch(3, randomSource.source)  // Fetch[IO, Int]
      char2 <- Fetch(rnd2, listSource.source)  // Fetch[IO, String]
    } yield (rnd1, char1, rnd2, char2)

  println(Fetch.run(fetchMultiD).unsafeRunSync)
  //18:43:11.875 [scala-execution-context-global-14] INFO app.sources.RandomSource - Getting next random by max 3
  //18:43:11.876 [scala-execution-context-global-13] INFO app.sources.ListSource - Processing element from index 1
  //(1,b,1,b)

}
