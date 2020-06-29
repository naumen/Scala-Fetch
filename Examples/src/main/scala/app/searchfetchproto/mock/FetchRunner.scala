package app.searchfetchproto.mock

import app.ContextEntities
import app.searchfetchproto.{DocumentSearchExample, DocumentSearchResponse}
import cats.effect.IO
import fetch.Fetch

object FetchRunner extends App with ContextEntities {

  val fts = new FtsImpl
  val documentInfoRepo = new DocumentInfoRepoImpl
  val vectorSearch = new VectorSearchImpl
  val personRepo = new PersonRepoImpl


  val searchExample = new DocumentSearchExample(fts, documentInfoRepo, vectorSearch, personRepo)

  val fetch: Fetch[IO, DocumentSearchResponse] = searchExample.searchDocumentFetch("To be or not to be")

  val r: DocumentSearchResponse = Fetch.run(fetch).unsafeRunSync

  println()
  println(r.items.mkString(";\n"))

}


