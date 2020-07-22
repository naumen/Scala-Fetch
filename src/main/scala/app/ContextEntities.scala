package app

import cats.effect.{ContextShift, IO, Timer}

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.global

trait ContextEntities {
  implicit val ec: ExecutionContext = global
  implicit val cs: ContextShift[IO] = IO.contextShift(ec) // для Fetch.run и app.ListDataSource
  implicit val timer: Timer[IO]     = IO.timer(ec) // для Fetch.run
}
