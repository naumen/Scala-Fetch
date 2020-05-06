package app.sources

import cats.effect.{Concurrent, ContextShift, IO}
import cats.syntax.option._
import com.typesafe.scalalogging.LazyLogging
import fetch.{Data, DataSource, Fetch}

class RandomSource(implicit cf: ContextShift[IO]) extends Data[Int, Int] with LazyLogging {

  override def name: String          = "Random numbers generator"
  private def instance: RandomSource = this

  def source: DataSource[IO, Int, Int] = new DataSource[IO, Int, Int] {
    override def data: Data[Int, Int] = instance

    override def CF: Concurrent[IO] = Concurrent[IO]

    override def fetch(max: Int): IO[Option[Int]] =
      CF.delay {
        logger.info(s"Getting next random by max $max")
        scala.util.Random.nextInt(max).some
      }
  }

  def fetchInt(id: Int): Fetch[IO, Option[Int]] = Fetch.optional(id, source)
}
