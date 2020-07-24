package app.searchfetchproto.mock

import app.searchfetchproto.{Fts, FtsResponse}
import cats.effect.IO

class FtsImpl extends Fts[IO] {
  override def search(query: String): IO[FtsResponse] = IO(FtsResponse(List("1", "2", "3", "4", "5", "6")))
}
