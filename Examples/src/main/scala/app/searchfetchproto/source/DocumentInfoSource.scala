package app.searchfetchproto.source

import app.searchfetchproto.{DocumentInfo, DocumentInfoRepo}
import app.searchfetchproto.Types.DocumentId
import cats.data.NonEmptyList
import cats.effect.{Concurrent, IO}
import com.typesafe.scalalogging.LazyLogging
import fetch.{Data, DataSource, Fetch}

class DocumentInfoSource(documentInfoRepo: DocumentInfoRepo[IO], maxBatch: Option[Int] = None)(
    implicit cs: Concurrent[IO]
) extends Data[DocumentId, DocumentInfo]
    with LazyLogging {

  override def name: String                = "Document Info Source"
  private def instance: DocumentInfoSource = this

  def source: DataSource[IO, DocumentId, DocumentInfo] = new DataSource[IO, DocumentId, DocumentInfo] {
    override def data: Data[DocumentId, DocumentInfo] = instance

    override def CF: Concurrent[IO] = cs

    override def maxBatchSize: Option[Int] = maxBatch

    override def batch(ids: NonEmptyList[DocumentId]): IO[Map[DocumentId, DocumentInfo]] = {
      logger.info(s"Document IDs fetching in batch: $ids")
      documentInfoRepo.findMany(ids.toList).map {
        _.map(di => di.id -> di).toMap
      }
    }

    override def fetch(id: DocumentId): IO[Option[DocumentInfo]] = batch(NonEmptyList.one(id)).map(_.get(id))
  }

  def fetchElem(id: DocumentId): Fetch[IO, Option[DocumentInfo]] = Fetch.optional(id, source)
}
