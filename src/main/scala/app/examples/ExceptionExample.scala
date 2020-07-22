package app.examples

import app.ContextEntities
import app.sources.ListSource
import fetch.{Fetch, Log}


object ExceptionExample extends App with ContextEntities {

  val list             = List("a", "b", "c")
  val data: ListSource = new ListSource(list)

  /**
  val i: String = Fetch.run(Fetch(5, data.source)).unsafeRunSync // Exception in thread "main" fetch.package$MissingIdentity
    */
  // Более безопасный запуск завернёт исключение в Either
  val i1: Either[Throwable, String] = Fetch.run(Fetch(1, data.source)).attempt.unsafeRunSync // Right(b)
  val i2: Either[Throwable, String] = Fetch.run(Fetch(5, data.source)).attempt.unsafeRunSync // Left(fetch.package$MissingIdentity)

  // libraryDependencies += "com.47deg" %% "fetch-debug" % "1.3.0"
  import fetch.debug.describe
  val t: Either[Throwable, (Log, String)] = Fetch.runLog(Fetch(5, data.source)).attempt.unsafeRunSync
  println(t.fold(describe, identity))
  // [ERROR] Identity with id `5` for data source `My List of Data` not found, fetch interrupted after 1 rounds
  //Fetch execution 🕛 0.00 seconds
  //
  //    [Round 1] 🕛 0.00 seconds
  //      [Fetch one] From `My List of Data` with id 5 🕛 0.00 seconds

  println(Fetch.run(Fetch(5, data.source)).attempt.unsafeRunSync.fold(describe, identity))
  // Без runLog
  // [ERROR] Identity with id `5` for data source `My List of Data` not found, fetch interrupted after 0 rounds
}
