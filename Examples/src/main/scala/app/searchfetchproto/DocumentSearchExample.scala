package app.searchfetchproto

import app.searchfetchproto.Types.DocumentId
import app.searchfetchproto.source.{DocumentInfoSource, PersonSource, SimilarDocumentSource}
import cats.effect.{ContextShift, IO}
import cats.implicits._
import fetch.{Fetch, _}

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

  private def documentItemFetch(id: DocumentId): Fetch[IO, DocumentItem] =
    for {
      infoOpt <- infoSource.fetchElem(id)
      p       <- infoOpt.traverse(i => i.authors.traverse(personSource.fetchElem).map(_.flatten))
    } yield DocumentItem(id, infoOpt.map(_.info), p.getOrElse(List.empty[Person]))

  private def fetchSimilarItems(id: DocumentId): Fetch[IO, List[DocumentSimilarItem]] =
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
