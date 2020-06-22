package app.examples

import app.ContextEntities
import app.sources.ListSource
import cats.effect.IO
import fetch.{fetchM, DataSource, Fetch}
import cats.implicits._

object CombinatorsExample extends App with ContextEntities {
  val list                                = List("a", "b", "c")
  val data: ListSource                    = new ListSource(list)
  val source: DataSource[IO, Int, String] = data.source

  /** Никаких оптимизаций, запуск последовательно по одному */
  val t1: IO[(String, String)] = for {
    a <- Fetch.run(Fetch(1, source))
    b <- Fetch.run(Fetch(1, source))
  } yield (a, b)

  println(t1.unsafeRunSync)
  // 08:19:40.274 [scala-execution-context-global-13] INFO app.sources.ListSource - Processing element from index 1
  // 08:19:40.289 [scala-execution-context-global-14] INFO app.sources.ListSource - Processing element from index 1

  /** Произойдут все оптимизации: дедупликация + запуск пакетом */
  val f1: Fetch[IO, (String, String)] = for {
    a <- Fetch(1, source)
    b <- Fetch(1, source)
  } yield (a, b)

  val t2: IO[(String, String)] = Fetch.run(f1)

  println(t2.unsafeRunSync)
  // 08:21:37.290 [scala-execution-context-global-14] INFO app.sources.ListSource - Processing element from index 1

  val f3: List[Fetch[IO, String]] = List(
    Fetch(1, source),
    Fetch(2, source),
    Fetch(2, source)
  )

  val f31: Fetch[IO, List[String]] = f3.sequence
  val t3: IO[List[String]]         = Fetch.run(f31)

  val f4: List[Int] = List(
    1,
    2,
    2
  )

  val f41: Fetch[IO, List[String]] = f4.traverse(Fetch(_, source))
  val t4: IO[List[String]]         = Fetch.run(f41)

  val f5: (Fetch[IO, String], Fetch[IO, String]) = (Fetch(1, source), Fetch(2, source))
  val f51: Fetch[IO, (String, String)]           = f5.tupled
  val t5: IO[(String, String)]                   = Fetch.run(f51)

  val f6: (Int, Int)                = (1, 2)
  val f61: Fetch[IO, (Int, String)] = f6.traverse(Fetch(_, source))

  val f0: Fetch[IO, String]  = Fetch(1, source).flatMap(_ => Fetch(1, source))
  val f01: Fetch[IO, String] = Fetch(1, source) >> Fetch(1, source)
  val t0: IO[String]         = Fetch.run(f0)
}
