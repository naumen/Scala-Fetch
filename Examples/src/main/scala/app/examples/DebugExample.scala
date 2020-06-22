package app.examples

import app.ContextEntities
import app.sources.{ListSource, RandomSource}
import cats.effect.IO
import fetch.{DataCache, DataSource, Fetch, InMemoryCache, Log}
import fetch.debug.describe
import fetch.fetchM
import cats.implicits._

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
  val batched: Fetch[IO, (String, String)] =
    (Fetch(3, listSource), Fetch(4, listSource)).tupled

  // #4
  val combined = (Fetch(5, listSource), Fetch(150, randomSource)).tupled

  /** End of fetches */
  val complicatedFetch: Fetch[IO, (String, Int)] =
    cached >> notCached >> random >> notCached >> batched >> combined

  val result: IO[(Log, (String, Int))] = Fetch.runLog(complicatedFetch, cacheF)
  val tuple: (Log, (String, Int))      = result.unsafeRunSync()

  println(tuple._2) // (f,17)
  println(describe(tuple._1))

  println(tuple._1)
  //Fetch execution 🕛 0.11 seconds
  //
  //  [Round 1] 🕛 0.06 seconds
  //    [Fetch one] From `My List of Data` with id 2 🕛
  //  [Round 2] 🕛 0.00 seconds
  //    [Fetch one] From `Random numbers generator` with id 10 🕛
  //  [Round 3] 🕛 0.01 seconds
  //    [Batch] From `My List of Data` with ids List(3, 4)
  //  [Round 4] 🕛 0.00 seconds
  //    [Fetch one] From `Random numbers generator` with id 150 🕛
  //    [Fetch one] From `My List of Data` with id 5 🕛

  // raw:
  // FetchLog(Queue(Round(List(Request(FetchOne(2,app.sources.ListSource@ea6147e),10767139,10767203))), Round(List(Request(FetchOne(10,app.sources.RandomSource@58b31054),10767211,10767213))), Round(List(Request(Batch(NonEmptyList(3, 4),app.sources.ListSource@ea6147e),10767234,10767242))), Round(List(Request(FetchOne(150,app.sources.RandomSource@58b31054),10767252,10767252), Request(FetchOne(5,app.sources.ListSource@ea6147e),10767252,10767252)))))

  println()
  println("Deduplicate")

  val deduplicate: Fetch[IO, (Int, String, Int, String)] = for {
    rnd1  <- Fetch(3, randomSource)  // Fetch[IO, Int]
    char1 <- Fetch(rnd1, listSource) // Fetch[IO, String]
    rnd2  <- Fetch(3, randomSource)  // Fetch[IO, Int]
    char2 <- Fetch(rnd2, listSource) // Fetch[IO, String]
  } yield (rnd1, char1, rnd2, char2)

  val result2: IO[(Log, (Int, String, Int, String))] = Fetch.runLog(deduplicate)
  val tuple2: (Log, (Int, String, Int, String)) = result2.unsafeRunSync()

  println(tuple2._2) // (f,17)
  println(describe(tuple2._1))

  println()

  val tupled = (Fetch(1, listSource), Fetch(2, randomSource)).tupled
  val res = Fetch.runLog(tupled).unsafeRunSync
  println(describe(res._1))



}
