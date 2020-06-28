package app.searchfetchproto

case class Answer(id: Int, content: String)

import java.nio.charset.StandardCharsets

import cats.data.NonEmptyList
import cats.effect.{Concurrent, ContextShift, IO}
import com.typesafe.scalalogging.LazyLogging
import fetch.{Data, DataSource, Fetch}
import cats.syntax.option._

import scala.util.Random

class GenericSource(override val name: String)(implicit cf: ContextShift[IO])
    extends Data[Int, Answer]
    with LazyLogging {

  private def instance: GenericSource = this

  def source: DataSource[IO, Int, Answer] = new DataSource[IO, Int, Answer] {
    override def data: Data[Int, Answer] = instance

    override def CF: Concurrent[IO] = Concurrent[IO]

    override def maxBatchSize: Option[Int] = 3.some

    override def batch(ids: NonEmptyList[Int]): IO[Map[Int, Answer]] = {
      logger.info(s"IDs fetching in batch for $name: $ids")
      super.batch(ids)
    }

    override def fetch(id: Int): IO[Option[Answer]] =
      CF.delay {

        val strAscii = Random.alphanumeric.take(Random.nextInt(15)).mkString
        Answer(id, s"$name: $strAscii").some
      }
  }
}
