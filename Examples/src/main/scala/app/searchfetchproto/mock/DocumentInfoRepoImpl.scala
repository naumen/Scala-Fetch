package app.searchfetchproto.mock

import app.searchfetchproto.{DocumentInfo, DocumentInfoRepo}
import app.searchfetchproto.Types.DocumentId
import cats.effect.IO

class DocumentInfoRepoImpl extends DocumentInfoRepo[IO] {

  private val docInfo = Map(
    "1" -> DocumentInfo("1", "Document 1", List(1)),
    "2" -> DocumentInfo("2", "Document 2", List(1,2)),
    "3" -> DocumentInfo("3", "Document 3", List(3,1)),
    "4" -> DocumentInfo("4", "Document 4", List(2,1)),
    "5" -> DocumentInfo("5", "Document 5", List(2)),
    "6" -> DocumentInfo("6", "Document 6", List(1,3))
  )

  override def findMany(ids: List[DocumentId]): IO[List[DocumentInfo]] = IO {
    for {
      id <- ids
    } yield docInfo(id)
  }

}
