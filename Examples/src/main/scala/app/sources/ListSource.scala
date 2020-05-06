package app.sources

import cats.data.NonEmptyList
import cats.effect.{Concurrent, ContextShift, IO}
import com.typesafe.scalalogging.LazyLogging
import fetch.{Data, DataSource, Fetch}

class ListSource(list: List[String], maxBatch: Option[Int] = None)(implicit cf: ContextShift[IO])
    extends Data[Int, String]
    with LazyLogging {

  override def name: String        = "My List of Data"
  private def instance: ListSource = this

  def source: DataSource[IO, Int, String] = new DataSource[IO, Int, String] {
    override def data: Data[Int, String] = instance

    override def CF: Concurrent[IO] = Concurrent[IO]

    override def maxBatchSize: Option[Int] = maxBatch

    override def batch(ids: NonEmptyList[Int]): IO[Map[Int, String]] = {
      logger.info(s"IDs fetching in batch: $ids")
      super.batch(ids)
    }

    override def fetch(id: Int): IO[Option[String]] =
      CF.delay {
        logger.info(s"Processing element from index $id")
        list.lift(id)
      }
  }

  def fetchElem(id: Int): Fetch[IO, Option[String]] = Fetch.optional(id, source)
}
