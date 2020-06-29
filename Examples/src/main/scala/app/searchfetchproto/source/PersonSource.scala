package app.searchfetchproto.source

import app.searchfetchproto.Types.PersonId
import app.searchfetchproto.{Person, PersonRepo}
import cats.data.NonEmptyList
import cats.effect.{Concurrent, IO}
import com.typesafe.scalalogging.LazyLogging
import fetch.{Data, DataSource, Fetch}

class PersonSource(personRepo: PersonRepo[IO], maxBatch: Option[Int] = None)(
    implicit cs: Concurrent[IO]
) extends Data[PersonId, Person]
    with LazyLogging {

  override def name: String          = "Persons source"
  private def instance: PersonSource = this

  def source: DataSource[IO, PersonId, Person] = new DataSource[IO, PersonId, Person] {
    override def data: Data[PersonId, Person] = instance

    override def CF: Concurrent[IO] = cs

    override def maxBatchSize: Option[Int] = maxBatch

    override def batch(ids: NonEmptyList[PersonId]): IO[Map[PersonId, Person]] = {
      logger.info(s"Person IDs fetching in batch: $ids")
      personRepo.findMany(ids.toList).map {
        _.map(di => di.id -> di).toMap
      }
    }

    override def fetch(id: PersonId): IO[Option[Person]] = batch(NonEmptyList.one(id)).map(_.get(id))
  }

  def fetchElem(id: PersonId): Fetch[IO, Option[Person]] = Fetch.optional(id, source)
}
