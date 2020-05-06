package app.examples

import app.ContextEntities
import app.sources.ListSource
import cats.effect.IO
import fetch.{DataSource, Fetch}

object SimpleExample extends App with ContextEntities {

  val list             = List("a", "b", "c")
  val data: ListSource = new ListSource(list)

  Fetch.run(data.fetchElem(0)).unsafeRunSync          // INFO app.ListDataSource - Processing element from index 0
  Fetch.run(data.fetchElem(1)).unsafeRunSync          // INFO app.ListDataSource - Processing element from index 1
  Fetch.run(data.fetchElem(2)).unsafeRunSync          // INFO app.ListDataSource - Processing element from index 2
  println(Fetch.run(data.fetchElem(2)).unsafeRunSync) // Some(c)
  println(Fetch.run(data.fetchElem(3)).unsafeRunSync) // None

}
