# Fetch - библиотека для доступа к данным

https://www.47deg.com/blog/fetch-scala-library/

**Fetch** - это библиотека для упрощения и оптимизации доступа к данным из таких источников как файловые системы, БД и веб-сервисы. Fetch основана на Cats и Cats Effect. Библиотека умеет: 

- Запрашивать данные из нескольких источников одновременно;
- Объединять запросы к одному источнику в один запрос;
- Оптимизировать запросы;
- Кэшировать запросы.

Для этого в ней предоставляются средства, позволяющие писать чистый код без засорения конструкциями для кэширования/объединения запросов и т.п.

## Структура источника

Для реализации доступа к какому-либо источнику через Fetch требуется реализовать для этого источника:

- Описание источника (`Data[I, A]`);
- Методы источника (`DataSource[F[_], I, A]`).

`DataSource[F[_], I, A]` (где **I** - тип идентификатора, по которому требуется что-то получить (например, путь к файлу), **A** - тип результата, а **F** - тип эффекта) содержит эффективные методы для доступа к источнику:

```scala
/**
 * A `DataSource` is the recipe for fetching a certain identity `I`, which yields
 * results of type `A` performing an effect of type `F[_]`.
 */
trait DataSource[F[_], I, A] {
  def data: Data[I, A]

  implicit def CF: Concurrent[F]

  /** Fetch one identity, returning a None if it wasn't found.
   */
  def fetch(id: I): F[Option[A]]

  /** Fetch many identities, returning a mapping from identities to results. If an
   * identity wasn't found, it won't appear in the keys.
   */
  def batch(ids: NonEmptyList[I]): F[Map[I, A]] =
    FetchExecution.parallel(
      ids.map(id => fetch(id).tupleLeft(id))
    ).map(_.collect { case (id, Some(x)) => id -> x }.toMap)

  def maxBatchSize: Option[Int] = None

  def batchExecution: BatchExecution = InParallel
}
```

`data: Data[I, A]` - содержит ссылку на экземпляр `Data[I,A]`, который является описанием источника.

Простейший пример оборачивания листа в термины Fetch:

```scala
class ListData(val list: List[String]) extends Data[Int, String] {
  override def name: String = "My List of Data"
}

class ListDataSource(list: ListData)(implicit cs: ContextShift[IO])
    extends DataSource[IO, Int, String]
    with LazyLogging {

  override def data: ListData = list

  /*implicit дает Stack overflow, видимо он начинает крутить сам себя */
  override def CF: Concurrent[IO] = Concurrent[IO]

  override def fetch(id: Int): IO[Option[String]] =
    CF.delay {
      logger.info(s"Processing element from index $id")
      data.list.lift(id)
    }
}
```

Обычно DataSource вкладывают в Data для упрощения работы и сжатия кода:

```scala
class ListSource(list: List[String])(implicit cf: ContextShift[IO]) extends Data[Int, String] with LazyLogging {
  override def name: String        = "My List of Data"
  private def instance: ListSource = this

  def source = new DataSource[IO, Int, String] {
    override def data: Data[Int, String] = instance

    override def CF: Concurrent[IO] = Concurrent[IO]

    override def fetch(id: Int): IO[Option[String]] =
      CF.delay {
        logger.info(s"Processing element from index $id")
        list.lift(id)
      }
  }

  def fetchElem(id: Int) = Fetch.optional(id, source)
}
```

К `fetchElem` вернемся позже.

Этими классами нельзя пользоваться напрямую (просто вызывать `fetch`). Их нужно оборачивать в специальные объекты `Fetch` и вызывать специальными методами:

```scala
object Example extends App {

  implicit val ec: ExecutionContext = global
  implicit val cs: ContextShift[IO] = IO.contextShift(ec)  // для Fetch.run и ListDataSource
  implicit val timer: Timer[IO]     = IO.timer(ec) // для Fetch.run

  val list = List("a", "b", "c")
  val data   = new ListSource(list)
  val source = data.source

  Fetch.run(Fetch(0, source)).unsafeRunSync  
  // INFO ListDataSource - Processing element from index 0

  Fetch.run(Fetch(1, source)).unsafeRunSync  
  // INFO ListDataSource - Processing element from index 1

  Fetch.run(Fetch(2, source)).unsafeRunSync  
  // INFO ListDataSource - Processing element from index 2

  Fetch.run(Fetch(3, source)).unsafeRunSync  
  // INFO ListDataSource - Processing element from index 3
  // Exception in thread "main" fetch.package$MissingIdentity
}
```


Вызовы возвращают монады, которые указаны в типе `DataSource`. В целом это может быть любая монада `F`, для которой есть инстанс `Concurrent[F]`. `Concurrent` - это тайпкласс из Cats Effect. 

Последний вызов выбросил исключение, хотя `data.list.lift(id)` в методе `fetch` успешно защищает от несуществующих индексов. Исключение бросается в ситуациях, когда `fetch` возвращает `None`. Это связано с тем, что источник не может возвращать `Option` или содержать `Option` в качестве одного из типов: `DataSource[F[_], I, A]`. Но запросить `Option` можно явно, создав объект Fetch не через метод `apply`, а через `optional`:

```scala
Fetch.run(Fetch.optional(3, source)).unsafeRunSync  // None
```

Разница очевидна:

```scala
val fApply: Fetch[IO, String]            = Fetch(3, source)
val fOptional: Fetch[IO, Option[String]] = Fetch.optional(3, source)
```

Теперь возможно переписать используемые методы на заранее подготовленный `fetchElem` в классе `ListSource`:

```scala
Fetch.run(data.fetchElem(0)).unsafeRunSync   // INFO app.ListDataSource - Processing element from index 0
Fetch.run(data.fetchElem(1)).unsafeRunSync // INFO app.ListDataSource - Processing element from index 1
Fetch.run(data.fetchElem(2)).unsafeRunSync // INFO app.ListDataSource - Processing element from index 2
println(Fetch.run(data.fetchElem(2)).unsafeRunSync) // Some(c)
println(Fetch.run(data.fetchElem(3)).unsafeRunSync) // None
```

С такими методами нет необходимости хранить в классе SimpleExample переменную `val source = data.source`.

## Кэширование

Fetch не кэширует результаты "из коробки":

```scala
def fetch(id: Int): Option[String] = {
  val run   = Fetch.run(data.fetchElem(id))
  run.unsafeRunSync
}

fetch(1)
fetch(1)
fetch(1)

// INFO app.ListDataSource - Processing element from index 1
// INFO app.ListDataSource - Processing element from index 1
// INFO app.ListDataSource - Processing element from index 1
```

Для кэширования в Fetch существует специальный трейт `DataCache[F[_]]`. Сама библиотека предоставляет одну готовую имплементацию - иммутабельный кэш `InMemoryCache[F[_]: Monad](state: Map[(Data[Any, Any], DataSourceId), DataSourceResult])`. Им можно воспользоваться через методы его объекта-компаньона `from` и `empty`:

```scala
val cacheF: DataCache[IO] = InMemoryCache.from((data, 1) -> "b", (data, 2) -> "c")

Fetch.run(data.fetchElem(1), cacheF).unsafeRunSync
Fetch.run(data.fetchElem(1), cacheF).unsafeRunSync
Fetch.run(data.fetchElem(1), cacheF).unsafeRunSync
Fetch.run(data.fetchElem(1), cacheF).unsafeRunSync
Fetch.run(data.fetchElem(0), cacheF).unsafeRunSync
Fetch.run(data.fetchElem(0), cacheF).unsafeRunSync

// INFO app.ListDataSource - Processing element from index 0
// INFO app.ListDataSource - Processing element from index 0
```

Видно, что кэширование работает, хотя сам по себе кэш не пополняется. Кэш иммутабелен - сама по себе вставка в InMemoryCache возвращает копию кэша с новым значением, а не изменяет существующий. Это означает, что кэшем нужно *управлять вручную*. Для работы с этим поведением существует метод `Fetch.runCache`, который возвращает кортеж типа `(обновленный кэш, результат)`:

```scala
var cache: DataCache[IO] = InMemoryCache.empty

def cachedRun(id: Int): Option[String] = {
  val (c, r) = Fetch.runCache(Fetch.optional(id, source), cache).unsafeRunSync
  cache = c  // Пример ручного управления кэшем
  r
}

cachedRun(1)
cachedRun(1)
cachedRun(2)
cachedRun(2)
cachedRun(4)
cachedRun(4)

// INFO app.ListDataSource - Processing element from index 1
// INFO app.ListDataSource - Processing element from index 2
// INFO app.ListDataSource - Processing element from index 4
// INFO app.ListDataSource - Processing element from index 4
```

Видно, что неудачные результаты не кэшируются. При этом сам кэш не имеет явного типа - один кэш можно использовать для многих источников. 

Опять же, специальный метод позволит 



### Пример: обертка для кэша Akka Play

Для организации собственного кэша нужно имплементировать тип `DataCache`. Например, вот так выглядит обертка для модуля кэширования Akka Play `AsyncCacheApi` для библиотеки Caffeine (полный пример: https://github.com/DenisNovac/akka-play-integrations/tree/master/play-fetch-cache):

```scala
case class CaffeineAkkaCache(asyncAkkaCache: AsyncCacheApi, expiration: FiniteDuration)(
    implicit val ec: ExecutionContext,
    implicit val cs: ContextShift[IO]
) extends DataCache[IO] with LazyLogging {

  override def lookup[I, A](i: I, data: Data[I, A]): IO[Option[A]] = {
    logger.debug(s"Searching in cache $i")
    val l: Future[Option[A]] = asyncAkkaCache.get(i.toString)
    IO.fromFuture(IO(l))
  }

  override def insert[I, A](i: I, v: A, data: Data[I, A]): IO[DataCache[IO]] = {
    logger.debug(s"Inserting to cache $i")
    val f: Future[Done] = asyncAkkaCache.set(i.toString, v, expiration) // Результат от апи Play вернуть не получится
    this.pure[IO]
  }
}
```

Преимущество этого кэша в том, что им не нужно управлять вручную. Хотя он и возвращает ссылку на себя, результаты в нем сохраняются через API Akka, управлять самой ссылкой нет необходимости - достаточно просто передавать его в метод `Fetch.run`. 

В `insert` можно заложить и собственные механизмы изменения кэша изнутри чтобы избавиться от вызовов `runCache` (например, хранить кэш в монаде `Ref`). 

Сам кэш можно завернуть в модуль Play и таким образом использовать во всей программе:

```scala
class CaffeineFetchBinder extends Module {

  override def bindings(environment: Environment, configuration: Configuration): collection.Seq[Binding[_]] =
    Seq(bind[CacheModule].to[CaffeineFetchModule].eagerly())
}

trait CacheModule {}

@Singleton
class CaffeineFetchModule @Inject() (
    @NamedCache("fetch-cache") fetchCache: AsyncCacheApi
)(implicit val ec: ExecutionContext)
    extends CacheModule
    with LazyLogging {

  implicit val cs: ContextShift[IO] = IO.contextShift(ec)
  val cache: DataCache[IO] = CaffeineAkkaCache(fetchCache, 1.hour)
}
```

## Объединение запросов (Batching)

Fetch может объединенять запросы к одному источнику в один запрос. Чтобы показать библиотеке, что запросы можно выполнять независимо - их нужно связать аппликативным оператором. После этого их можно передавать в `Fetch.run` как раньше. Например:

```scala
import fetch.fetchM  // инстансы Fetch для синтаксиса Cats

val tuple: Fetch[IO, (Option[String], Option[String])] = (data.fetchElem(0), data.fetchElem(1)).tupled

Fetch.run(tuple).unsafeRunSync()  // (Some(a),Some(b))
```

По умолчанию метод `batch` в `DataSource` описан в терминах `fetch` и запрашивает идентификаторы у источника в параллели. Его можно переопределить. Например, для БД там может быть SQL-запрос для получения сразу множества ключей.

Поместим логгер в `batch` в `source` в `ListSource`:

```scala
override def batch(ids: NonEmptyList[Int]): IO[Map[Int, String]] = {
  logger.info(s"Ids fetching: $ids")
  super.batch(ids)
}
```

Выполнить запрос пакетом можно и таким способом:

```scala
import fetch.fetchM 

def findMany: Fetch[IO, List[Option[String]]] =
  List(0, 1, 2, 3, 4, 5).traverse(data.fetchElem)

Fetch.run(findMany).unsafeRunSync
// INFO app.ListSource - IDs fetching in batch: NonEmptyList(0, 5, 1, 2, 3, 4)
```

Можно ограничить размер, переопределив метод `maxBatchSize`:

```scala
override def maxBatchSize: Option[Int] = 2.some  // defaults to None

// INFO app.ListSource - IDs fetching in batch: NonEmptyList(0, 5)
// INFO app.ListSource - IDs fetching in batch: NonEmptyList(1, 2)
// INFO app.ListSource - IDs fetching in batch: NonEmptyList(3, 4)
```

По умолчанию такие запросы выполняются параллельно, но их можно выполнять и последовательно. Для этого надо переопределить метод `batchExecution`

```scala
override def batchExecution: BatchExecution = Sequentially // defaults to `InParallel`
```

Кроме того, Fetch распознает одинаковые запросы среди объединенных и игнорирует их, но сохраняет семантику запроса в ответе:

```scala
val tupleD: Fetch[IO, (Option[String], Option[String])] = (data.fetchElem(0), data.fetchElem(0)).tupled
println(Fetch.run(tupleD).unsafeRunSync())  // (Some(a),Some(a))
// INFO app.ListSource - Processing element from index 0
```

Индекс запросился только один раз, но в ответе вернулся кортеж из двух значений.

## Комбинирование данных из разных источников

Кобминирование данных из разных источников осуществляется во время вызова данных из разных источников в одном `Fetch.run`. В целом, этот механизм соответствует объединенным запросы к одному источнику, но внутри объектов Fetch источники будут указаны разные.

Предположим, у нас есть дополнительный источник, который выдает рандомные целочисленные до какой-то границы:

```scala
class RandomSource(implicit cf: ContextShift[IO]) extends Data[Int, Int] with LazyLogging {

  override def name: String          = "Random numbers generator"
  private def instance: RandomSource = this

  def source: DataSource[IO, Int, Int] = new DataSource[IO, Int, Int] {
    override def data: Data[Int, Int] = instance

    override def CF: Concurrent[IO] = Concurrent[IO]

    override def fetch(max: Int): IO[Option[Int]] =
      CF.delay {
        logger.info(s"Getting next random by max $max")
        scala.util.Random.nextInt(max).some
      }
  }
}
```

Мы можем запросить эти данные в одном Fetch. Например, последовательно через for:

```scala
val listSource = new ListSource(List("a", "b", "c"))
val randomSource = new RandomSource()

def fetchMulti: Fetch[IO, (Int, String)] =
  for {
    rnd <- Fetch(3, randomSource.source)  // Fetch[IO, Int]
    char <- Fetch(rnd, listSource.source)  // Fetch[IO, String]
  } yield (rnd, char)

println(Fetch.run(fetchMulti).unsafeRunSync)  // например, (0,a)
```

## Комбинаторы

Помимо `flatMap` в Fetch возможно использовать и другие комбинаторы. Например, `sequqnce` и `traverse` (последний уже был затронут выше) из Cats.

У `sequence` суть та же, что у traverse. Fetch.run умеет запускать `List[Fetch[_]]`, чем мы и пользуемся. `traverse` является смесью map и sequence, поэтому внутри Fetch они работают одинаково (типы тоже одинаковые, это видно из примеров).

```scala
def fetchRandomInt(max: Int) = Fetch(max, randomSource.source)

val listFetch: List[Fetch[IO, Int]] = List(
  Fetch(10, randomSource.source),
  Fetch(10, randomSource.source),
  Fetch(10, randomSource.source),
  Fetch(10, randomSource.source),
  Fetch(10, randomSource.source)
)

val fetchTuple: Fetch[IO, (Option[String], Option[String])] = 
  (data.fetchElem(0), data.fetchElem(1)).tupled

val fetchTrv: Fetch[IO, List[Int]]
  = List(10, 10, 10, 10, 10).traverse(fetchRandomInt)

val fetchSeq: Fetch[IO, List[Int]] = listFetch.sequence

println(Fetch.run(fetchSeq).unsafeRunSync)  // List(8, 8, 8, 8, 8)

```

Этот запрос выполняется с ошибкой. Например, в ответе можно увидеть `List(4, 4, 4, 4, 4)`. Это связано с оптимизацией получения данных по одинаковому ID, которая в данном случае не нужна.

## Обработка исключений

В Fetch предоставлены: 

- Общий трейт `FetchException`;
- Специальное исключение `MissingIdentity` для несуществующих в источнике ID:
- Общее исключение `UnhandledException` для любых остальных исключений.

Мы уже запускали Fetch с результатом `Option`, но есть более безопасная альтернатива, возвращающая `Either`:

```scala

// val i: String = Fetch.run(Fetch(5, data.source)).unsafeRunSync // Exception in thread "main" fetch.package$MissingIdentity

val i: Either[Throwable, String] = Fetch.run(Fetch(5, data.source)).attempt.unsafeRunSync // Left(fetch.package$MissingIdentity)

```

## Дебаг Fetch

Fetch предоставляет средства дебага в виде метода `Fetch.runLog`, возвращающего объект `FetchLog` с историей работы. В отдельной библиотеке fetch-debug предоставлен метод `describe`, который красиво обрабатывает Throwable и Log. 

Пример вывода описания throwable:

```scala
// libraryDependencies += "com.47deg" %% "fetch-debug" % "1.3.0"
import fetch.debug.describe
val t: Either[Throwable, (Log, String)] = Fetch.runLog(Fetch(5, data.source)).attempt.unsafeRunSync
println(t.fold(describe, identity))
// [ERROR] Identity with id `5` for data source `My List of Data` not found, fetch interrupted after 1 rounds
//Fetch execution 🕛 0.00 seconds
//
//    [Round 1] 🕛 0.00 seconds
//      [Fetch one] From `My List of Data` with id 5 🕛 0.00 seconds
```

Напишем сложный запрос со всеми изученными функциями Fetch:

```scala
object DebugExample extends App with ContextEntities {
  val list = List("a", "b", "c", "d", "e", "f", "g", "h")

  val listData                                = new ListSource(list)
  val listSource: DataSource[IO, Int, String] = listData.source
  val randomSource                            = new RandomSource().source

  val cacheF: DataCache[IO] = InMemoryCache.from((listData, 1) -> "b")

  // нет среди раундов вообще
  val cached = Fetch(1, listSource)

  // только #1, больше не повторяется
  val notCached = Fetch(2, listSource)

  // #2
  val random = Fetch(10, randomSource)

  // #3
  val batched: Fetch[IO, (String, String)] = (Fetch(3, listSource), Fetch(4, listSource)).tupled

  // #4
  val combined = (Fetch(5, listSource), Fetch(150, randomSource)).tupled

  /** End of fetches */
  val complicatedFetch: Fetch[IO, (String, Int)] = cached >> notCached >> notCached >> random >> batched >> combined
  val result: IO[(Log, (String, Int))]           = Fetch.runLog(complicatedFetch, cacheF)
  val tuple: (Log, (String, Int))                = result.unsafeRunSync()

  println(tuple._2) // (f,17)
  println(describe(tuple._1))
  println(tuple._1)


  //Fetch execution 🕛 0.11 seconds
  //
  //  [Round 1] 🕛 0.06 seconds
  //    [Fetch one] From `My List of Data` with id 2 🕛 0.06 seconds
  //  [Round 2] 🕛 0.00 seconds
  //    [Fetch one] From `Random numbers generator` with id 10 🕛 0.00 seconds
  //  [Round 3] 🕛 0.01 seconds
  //    [Batch] From `My List of Data` with ids List(3, 4) 🕛 0.01 seconds
  //  [Round 4] 🕛 0.00 seconds
  //    [Fetch one] From `Random numbers generator` with id 150 🕛 0.00 seconds
  //    [Fetch one] From `My List of Data` with id 5 🕛 0.00 seconds

  // raw:
  // FetchLog(Queue(Round(List(Request(FetchOne(2,app.sources.ListSource@ea6147e),10767139,10767203))), Round(List(Request(FetchOne(10,app.sources.RandomSource@58b31054),10767211,10767213))), Round(List(Request(Batch(NonEmptyList(3, 4),app.sources.ListSource@ea6147e),10767234,10767242))), Round(List(Request(FetchOne(150,app.sources.RandomSource@58b31054),10767252,10767252), Request(FetchOne(5,app.sources.ListSource@ea6147e),10767252,10767252)))))
}
```

Можно сделать несколько наблюдений:

- `cached` вообще не появляется в логах, ведь он не считается никогда;
- `notCached` посчитался лишь раз. Видимо, в методах `>>` где-то сработала оптимизация и повторный запрос дедуплицировался;
- `batch` явно прописывается;
- Комбинированный запрос к нескольким источникам выглядит в логах как один раунд с несколькими запросами.

