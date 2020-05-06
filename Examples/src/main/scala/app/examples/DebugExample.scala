package app.examples

import app.ContextEntities
import app.sources.{ListSource, RandomSource}
import cats.effect.IO
import fetch.{DataCache, DataSource, Fetch, InMemoryCache, Log}
import fetch.debug.describe
import cats.syntax.apply._
import fetch.fetchM
import cats.syntax.flatMap._

object DebugExample extends App with ContextEntities {
  val list = List("a", "b", "c", "d", "e", "f", "g", "h")

  val listData                                = new ListSource(list)
  val listSource: DataSource[IO, Int, String] = listData.source
  val randomSource                            = new RandomSource().source

  val cacheF: DataCache[IO] = InMemoryCache.from((listData, 1) -> "b")

  // нет среди раундов вообще
  val cached = Fetch(1, listSource)

  // только #1, больше не повторяется
  val notCached = Fetch(2, listSource)

  // #2
  val random = Fetch(10, randomSource)

  // #3
  val batched: Fetch[IO, (String, String)] = (Fetch(3, listSource), Fetch(4, listSource)).tupled

  // #4
  val combined = (Fetch(5, listSource), Fetch(150, randomSource)).tupled

  /** End of fetches */
  val complicatedFetch: Fetch[IO, (String, Int)] = cached >> notCached >> notCached >> random >> batched >> combined
  val result: IO[(Log, (String, Int))]           = Fetch.runLog(complicatedFetch, cacheF)
  val tuple: (Log, (String, Int))                = result.unsafeRunSync()
  println(tuple._2) // (f,17)
  println(describe(tuple._1))

  println(tuple._1)
  //Fetch execution 🕛 0.11 seconds
  //
  //  [Round 1] 🕛 0.06 seconds
  //    [Fetch one] From `My List of Data` with id 2 🕛 0.06 seconds
  //  [Round 2] 🕛 0.00 seconds
  //    [Fetch one] From `Random numbers generator` with id 10 🕛 0.00 seconds
  //  [Round 3] 🕛 0.01 seconds
  //    [Batch] From `My List of Data` with ids List(3, 4) 🕛 0.01 seconds
  //  [Round 4] 🕛 0.00 seconds
  //    [Fetch one] From `Random numbers generator` with id 150 🕛 0.00 seconds
  //    [Fetch one] From `My List of Data` with id 5 🕛 0.00 seconds

  // raw:
  // FetchLog(Queue(Round(List(Request(FetchOne(2,app.sources.ListSource@ea6147e),10767139,10767203))), Round(List(Request(FetchOne(10,app.sources.RandomSource@58b31054),10767211,10767213))), Round(List(Request(Batch(NonEmptyList(3, 4),app.sources.ListSource@ea6147e),10767234,10767242))), Round(List(Request(FetchOne(150,app.sources.RandomSource@58b31054),10767252,10767252), Request(FetchOne(5,app.sources.ListSource@ea6147e),10767252,10767252)))))
}
