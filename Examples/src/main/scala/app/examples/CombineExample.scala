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


}
