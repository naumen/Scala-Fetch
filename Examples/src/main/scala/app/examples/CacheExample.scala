package app.examples

import app.ContextEntities
import app.sources.ListSource
import cats.effect.IO
import fetch.{DataCache, Fetch, InMemoryCache}

object CacheExample extends App with ContextEntities {

  val list = List("a", "b", "c")
  val data = new ListSource(list)

  def fetch(id: Int): Option[String] = {
    val run = Fetch.run(data.fetchElem(id))
    run.unsafeRunSync
  }

  fetch(1)
  fetch(1)
  fetch(1)

  println(s"\nCached:")

  /** Готовый наполненный кэш */
  val cacheF: DataCache[IO] = InMemoryCache.from((data, 1) -> "b", (data, 2) -> "c")
  Fetch.run(data.fetchElem(1), cacheF).unsafeRunSync
  Fetch.run(data.fetchElem(1), cacheF).unsafeRunSync
  Fetch.run(data.fetchElem(1), cacheF).unsafeRunSync
  Fetch.run(data.fetchElem(1), cacheF).unsafeRunSync
  Fetch.run(data.fetchElem(1), cacheF).unsafeRunSync
  Fetch.run(data.fetchElem(1), cacheF).unsafeRunSync

  println("\nCached from empty:")

  /** Пустой новый кэш */
  var cache: DataCache[IO] = InMemoryCache.empty

  def cachedRun(id: Int): Option[String] = {
    val (c, r) = Fetch.runCache(data.fetchElem(id), cache).unsafeRunSync
    cache = c // Пример ручного управления кэшем
    r
  }

  cachedRun(1)
  cachedRun(1)
  cachedRun(2)
  cachedRun(2)
  cachedRun(4)
  cachedRun(4)

  /**
  Processing element from index 1
  Processing element from index 2
  Processing element from index 4
  Processing element from index 4
   */

}
