import cats.data.NonEmptyList
import cats.effect.{Concurrent, ContextShift, IO}
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import fetch.{Data, DataSource, Fetch, _}

class DocumentSearchExample(
    fts: Fts[IO],
    documentInfoRepo: DocumentInfoRepo[IO],
    vectorSearch: VectorSearch[IO],
    personRepo: PersonRepo[IO]
)(
    implicit cs: ContextShift[IO]
) {

  val infoSource    = new DocumentInfoSource(documentInfoRepo, 16.some)
  val personSource  = new PersonSource(personRepo, 16.some)
  val similarSource = new SimilarDocumentSource(vectorSearch, 16.some)

  def documentItemFetch(id: DocumentId): Fetch[IO, DocumentItem] =
    for {
      infoOpt <- infoSource.fetchElem(id)
      p       <- infoOpt.traverse(i => i.authors.traverse(personSource.fetchElem).map(_.flatten))
    } yield DocumentItem(id, infoOpt.map(_.info), p.getOrElse(List.empty[Person]))

  def fetchSimilarItems(id: DocumentId): Fetch[IO, List[DocumentSimilarItem]] =
    similarSource
      .fetchElem(id)
      .map(_.getOrElse(List.empty[SimilarityItem]))
      .flatMap {
        _.traverse { si =>
          documentItemFetch(si.id).map { di =>
            DocumentSimilarItem(di, si.similarity)
          }
        }
      }

  def searchDocumentFetch(query: String): Fetch[IO, DocumentSearchResponse] =
    for {
      docs <- Fetch.liftF(fts.search(query))
      items <- docs.ids.traverse { id =>
                (documentItemFetch(id), fetchSimilarItems(id)).tupled.map(r => DocumentSearchItem(r._1, r._2))
              }
    } yield DocumentSearchResponse(items)

}

/*Model*/

type DocumentId = String
type PersonId = String

case class FtsResponse(ids: List[DocumentId])

case class SimilarityItem(id: DocumentId, similarity: Double)

case class DocumentInfo(id: DocumentId, info: String, authors: List[PersonId])

case class Person(id: PersonId, fullTitle: String)

/*Response*/
case class DocumentSearchResponse(
    items: List[DocumentSearchItem]
)

case class DocumentItem(id: DocumentId, info: Option[String], authors: List[Person])

case class DocumentSimilarItem(
    item: DocumentItem,
    similarity: Double
)

case class DocumentSearchItem(
    item: DocumentItem,
    similar: List[DocumentSimilarItem]
)

trait Fts[F[_]] {

  def search(query: String): F[FtsResponse]

}

trait VectorSearch[F[_]] {

  def similar(id: DocumentId): F[List[SimilarityItem]]

}

trait DocumentInfoRepo[F[_]] {

  def findMany(ids: List[DocumentId]): F[List[DocumentInfo]]
}

trait PersonRepo[F[_]] {

  def findMany(ids: List[PersonId]): F[List[Person]]

}

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

      override def fetch(id: DocumentId): IO[Option[List[SimilarityItem]]] =
        vectorSearch.similar(id).map(l => Option(l).filter(_.nonEmpty))
    }

  def fetchElem(id: DocumentId): Fetch[IO, Option[List[SimilarityItem]]] = Fetch.optional(id, source)
}

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

class PersonSource(personRepo: PersonRepo[IO], maxBatch: Option[Int] = None)(
    implicit cs: Concurrent[IO]
) extends Data[PersonId, Person]
    with LazyLogging {

  override def name: String          = "Document Info Source"
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