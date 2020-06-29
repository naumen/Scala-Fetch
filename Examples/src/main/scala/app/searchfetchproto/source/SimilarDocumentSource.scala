package app.searchfetchproto.source

import app.searchfetchproto.{SimilarityItem, VectorSearch}
import app.searchfetchproto.Types.DocumentId
import cats.effect.{Concurrent, IO}
import com.typesafe.scalalogging.LazyLogging
import fetch.{Data, DataSource, Fetch}

class SimilarDocumentSource(vectorSearch: VectorSearch[IO], maxBatch: Option[Int] = None)(
    implicit cs: Concurrent[IO]
) extends Data[DocumentId, List[SimilarityItem]]
    with LazyLogging {

  override def name: String                   = "Similar Document Source"
  private def instance: SimilarDocumentSource = this

  def source: DataSource[IO, DocumentId, List[SimilarityItem]] =
    new DataSource[IO, DocumentId, List[SimilarityItem]] {
      override def data: Data[DocumentId, List[SimilarityItem]] = instance

      override def CF: Concurrent[IO] = cs

      override def maxBatchSize: Option[Int] = maxBatch

      override def fetch(id: DocumentId): IO[Option[List[SimilarityItem]]] = {
        vectorSearch
          .similar(id)
          .map(l => Option(l).filter(_.nonEmpty))
          .map { o =>
            logger.info(s"Fetching similar documents for ID: $id. It is: ${o.map(_.map(_.id))}")
            o
          }
      }
    }

  def fetchElem(id: DocumentId): Fetch[IO, Option[List[SimilarityItem]]] = Fetch.optional(id, source)
}
