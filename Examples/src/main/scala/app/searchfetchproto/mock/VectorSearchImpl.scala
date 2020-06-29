package app.searchfetchproto.mock

import app.searchfetchproto.Types.DocumentId
import app.searchfetchproto.{SimilarityItem, VectorSearch}
import cats.effect.IO

class VectorSearchImpl extends VectorSearch[IO] {

  private val similars = Map(
    "1" -> List(SimilarityItem("2", 0.7), SimilarityItem("3", 0.6)),
    "2" -> List(SimilarityItem("1", 0.7)),
    "3" -> List(SimilarityItem("1", 0.6)),
    "4" -> List(),
    "5" -> List(SimilarityItem("6", 0.5)),
    "6" -> List(SimilarityItem("5", 0.5))
  )

  override def similar(id: DocumentId): IO[List[SimilarityItem]] = IO {
    similars(id)
  }
}
