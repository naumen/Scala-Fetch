package app.searchfetchproto.mock

import app.ContextEntities
import app.searchfetchproto.{DocumentSearchExample, DocumentSearchResponse}
import cats.effect.IO
import fetch.{Fetch, Log}
import fetch.debug.describe

object FetchRunner extends App with ContextEntities {

  val fts = new FtsImpl
  val documentInfoRepo = new DocumentInfoRepoImpl
  val vectorSearch = new VectorSearchImpl
  val personRepo = new PersonRepoImpl


  val searchExample = new DocumentSearchExample(fts, documentInfoRepo, vectorSearch, personRepo)

  val fetch: Fetch[IO, DocumentSearchResponse] = searchExample.searchDocumentFetch("To be or not to be")

  val r: (Log, DocumentSearchResponse) = Fetch.runLog(fetch).unsafeRunSync

  println(r._2.items.mkString(";\n"))
  println(describe(r._1))

}


