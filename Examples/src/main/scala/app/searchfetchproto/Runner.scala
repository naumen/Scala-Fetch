package app.searchfetchproto

import app.ContextEntities
import cats.Monoid
import cats.effect.IO
import fetch.Fetch
import fetch.fetchM
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.instances.list._
import cats.syntax.traverse._
import cats.syntax.apply._
import fs2.{Chunk, Stream}

import scala.collection.MapView // for tupled

object Runner extends App with ContextEntities {

  // Псевдо-сервисы
  val elasticsearchSource = new ElasticsearchSource().source
  val documentsSource     = new GenericSource("Document URL").source
  val authorsSource       = new GenericSource("Author").source
  val annotationsSource   = new GenericSource("Annotation").source

  val fetch: Fetch[IO, (List[Answer], List[Answer], List[Answer])] = for {

    searchResults <- Fetch("to be or not to be", elasticsearchSource)

    documents: Fetch[IO, List[Answer]]   = searchResults.traverse(r => Fetch(r, documentsSource))
    authors: Fetch[IO, List[Answer]]     = searchResults.traverse(r => Fetch(r, authorsSource))
    annotations: Fetch[IO, List[Answer]] = searchResults.traverse(r => Fetch(r, annotationsSource))

    inParallel <- (documents, authors, annotations).tupled

  } yield inParallel

  val result: (List[Answer], List[Answer], List[Answer]) = Fetch.run(fetch).unsafeRunSync

  val comb: MapView[Int, String] =
    result.productIterator
      .asInstanceOf[Iterator[List[Answer]]]
      .toList
      .map(_.distinct)
      .foldRight(List.empty[Answer])((e, a) => e ++ a)
      .groupBy(_.id)
      .view
      .mapValues(_.map(_.content).mkString(", "))

  println(comb.mkString("\n"))

  println("Streaming example: ")

  def streamResults(ids: List[Int]): IO[Vector[(Chunk[Answer], Chunk[Answer], Chunk[Answer])]] =
    Stream
      .emits[IO, Int](ids)
      .chunkLimit(3)
      .evalMap { groupByThree =>
        val documents   = groupByThree.traverse(Fetch(_, documentsSource))
        val authors     = groupByThree.traverse(Fetch(_, authorsSource))
        val annotations = groupByThree.traverse(Fetch(_, annotationsSource))
        println()
        Fetch.run((documents, authors, annotations).tupled)
      }
      .compile
      .toVector

  val fetchStream: IO[Vector[(Chunk[Answer], Chunk[Answer], Chunk[Answer])]] = for {
    searchResults <- Fetch.run(Fetch("to be or not to be", elasticsearchSource))
    result        <- streamResults(searchResults)
  } yield result

  val streamResult = fetchStream.unsafeRunSync

  val combStream =
    streamResult
      .map(t => t._1.toList ++ t._2.toList ++ t._3.toList)
      .toList
      .flatten
      .groupBy(_.id)
      .view
      .mapValues(_.map(_.content).mkString(", "))

  println(combStream.mkString("\n"))
}
