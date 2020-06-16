package app.examples

import app.ContextEntities
import app.sources.{ListSource, RandomSource}
import cats.effect.IO
import com.github.benmanes.caffeine.cache.Cache
import com.github.blemale.scaffeine.Scaffeine
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.duration._
import fetch.{Data, DataCache, Fetch}

import scala.util.Try

case class ScaffeineCache() extends DataCache[IO] with LazyLogging {

  private val cache =
    Scaffeine()
      .recordStats()
      .expireAfterWrite(1.hour)
      .maximumSize(500)
      .build[(Data[Any, Any], Any), Any]()

  override def lookup[I, A](i: I, data: Data[I, A]): IO[Option[A]] = IO {
    cache
      .getIfPresent(data.asInstanceOf[Data[Any, Any]] -> i)
      .map { any =>
        val correct = any.asInstanceOf[A]
        logger.info(s"From cache: $i")
        correct
      }
  }

  override def insert[I, A](i: I, v: A, data: Data[I, A]): IO[DataCache[IO]] = {
    cache.put(data.asInstanceOf[Data[Any, Any]] -> i, v) // Unit
    IO(this)
  }

}

object CaffeineExample extends App with ContextEntities {
  val list  = List("a", "b", "c")
  val listSource  = new ListSource(list)
  val randomSource = new RandomSource()
  val cache = ScaffeineCache()

  /** Без кэширования */
  Fetch.run(listSource.fetchElem(1)).unsafeRunSync // Processing element from index 1
  Fetch.run(listSource.fetchElem(1)).unsafeRunSync // Processing element from index 1

  println()

  /** С кэшированием */
  Fetch.run(listSource.fetchElem(1), cache).unsafeRunSync // Processing element from index 1
  Fetch.run(listSource.fetchElem(1), cache).unsafeRunSync // From cache: 1

  /** Один кэш подходит разным источникам */
  Fetch.run(randomSource.fetchInt(2), cache).unsafeRunSync  // Getting next random by max 2
  Fetch.run(randomSource.fetchInt(2), cache).unsafeRunSync  // From cache: 2
}
