package app.searchfetchproto.mock

import app.searchfetchproto.{Person, PersonRepo}
import app.searchfetchproto.Types.PersonId
import cats.effect.IO

class PersonRepoImpl extends PersonRepo[IO]{

  private val persons = Map(
    1 -> Person(1, "Rick Deckard"),
    2 -> Person(2, "Roy Batty"),
    3 -> Person(3, "Joe")
  )

  override def findMany(ids: List[PersonId]): IO[List[Person]] = IO {
    for {
      id <- ids
    } yield persons(id)
  }
}
