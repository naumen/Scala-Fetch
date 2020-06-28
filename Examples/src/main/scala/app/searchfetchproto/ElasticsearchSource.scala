package app.searchfetchproto

import cats.effect.{Concurrent, ContextShift, IO}
import com.typesafe.scalalogging.LazyLogging
import fetch.{Data, DataSource, Fetch}
import cats.syntax.option._

import scala.util.Random

/**
  * Симуляция сервиса поиска - возвращает какой-то набор ID на строковый запрос
  * @param cf
  */
class ElasticsearchSource(implicit cf: ContextShift[IO]) extends Data[String, List[Int]] with LazyLogging {

  override def name: String                 = "ES prototype"
  private def instance: ElasticsearchSource = this

  def source: DataSource[IO, String, List[Int]] = new DataSource[IO, String, List[Int]] {

    private val ids = 1 to 100

    override def data: Data[String, List[Int]] = instance

    override def CF: Concurrent[IO] = Concurrent[IO]

    override def fetch(query: String): IO[Option[List[Int]]] =
      CF.delay {
        logger.info(s"Searching query: $query")
        // Обеспечим случайное наличие дубликатов
        (ids.take(query.length) ++ ids.take(Random.nextInt(ids.length))).toList.some
      }
  }
}
