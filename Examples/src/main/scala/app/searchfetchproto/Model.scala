package app.searchfetchproto

object Types {
  type DocumentId = String
  type PersonId   = Int
}

import Types._

/*Services*/
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

/*Model*/
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
