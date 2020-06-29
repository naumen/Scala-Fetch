# Fetch - библиотека для доступа к данным

https://www.47deg.com/blog/fetch-scala-library/

**Fetch** - это библиотека для организации доступа к данным из таких источников как файловые системы, БД и веб-сервисы. Fetch написана в функциональном стиле и основана на Cats и Cats Effect. Библиотека предназначена для композирования и оптимизации выполнения запросов к разным источникам данных. Она позволяет:

- Запрашивать данные из нескольких разных источников параллельно;
- Запрашивать данные из одного источника параллельно;
- Объединять запросы к одному источнику в один запрос;
- Производить дедупликацию запросов (в каждой из перечисленных ситуаций);
- Кэшировать результаты запросов;

Для этого в ней предоставляются средства, позволяющие писать чистый бизнес-код без засорения низкоуровневыми конструкциями для осуществления перечисленных оптимизаций.

В примерах используется последняя на момент написания **версия Fetch - 1.3.0**.

## Источник данных в Fetch

Для реализации доступа к какому-либо источнику через Fetch требуется реализовать для этого источника:

- Описание источника данных (`Data[I, A]`);
- Методы получения данных из источника (`DataSource[F[_], I, A]`).

`DataSource[F[_], I, A]` (где **I** - тип идентификатора, по которому требуется что-то получить (например, путь к файлу или ID в базе данных), **A** - тип результата, а **F** - тип эффекта) содержит эффективные методы для извлечения из него данных:

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

`data: Data[I, A]` - содержит ссылку на экземпляр `Data[I,A]`, который является описанием источника. `CF` - это "доказательство" того, что выбранный эффект имеет инстанс `Concurrent`. Метод `fetch` - это метод получения из ID самих данных. `batch` - это тоже метод получения данных, но он предназначен для *одновременного* получения из источника нескольких ID. Изначально он описан в терминах `fetch` и не требует реализации (просто запускает всю пачку ID параллельно), но часто его бывает полезно переопределить - многие источники данных позволяют получить больше одного элемента за раз (например, реляционные базы данных).

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

Обычно эти структуры совмещают - экземпляр DataSource вкладывают в класс-наследник Data. Это позволяет немного сжать код и хранить всё в одном месте:

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
}
```

Внутрь метода `fetch` помещён логгер. В будущем он поможет отслеживать вызовы этой функции. В `Fetch` присутствуют свои инструменты для отладки, но о них речь пойдёт в последней части статьи.

Сами по себе методы `DataSource` нельзя использовать напрямую. Нужно передавать их в специальные объекты `Fetch`. Эти объекты являются чем-то вроде "чертежей" для получения данных и содержат в себе идентификатор данных и источник. Затем их нужно передавать в методы объекта `Fetch` вроде `Fetch.run` (кроме `run` есть ещё несколько специальных методов, возвращающих кроме ответа дополнительные данные). Эти объекты создают из источника и ID эффект с ответом. В целом это может быть любой эффект `F`, для которого есть инстанс `Concurrent[F]`. Эта манипуляция выглядит следующим образом:

```scala
val list                                = List("a", "b", "c")
val data: ListSource                    = new ListSource(list)
val source: DataSource[IO, Int, String] = data.source

val fetchDataPlan: Fetch[IO, String] = Fetch(1, source)
val fetchData: IO[String]            = Fetch.run(fetchDataPlan)
val dataCalculated: String           = fetchData.unsafeRunSync // b
```

Оборачивание в специальный объект, хранящий ID и источник, позволяет выполнять библиотеке описанные выше оптимизации.

Полный пример:

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

Последний вызов выбросил исключение, хотя `data.list.lift(id)` в методе `fetch` успешно защищает от несуществующих индексов. Исключение бросается в ситуациях, когда `fetch` возвращает `None`. Это связано с тем, что источник не может возвращать `Option` или содержать `Option` в качестве одного из типов: `DataSource[F[_], I, A]`. Но запросить `Option` можно явно, создав объект Fetch не через метод `apply`, а через `optional`:

```scala
Fetch.run(Fetch.optional(3, source)).unsafeRunSync  // None
```

Разницу можно понять просто взглянув на типы:

```scala
val fApply: Fetch[IO, String]            = Fetch(3, source)
val fOptional: Fetch[IO, Option[String]] = Fetch.optional(3, source)
```

Иногда внутрь классов-наследников `Data` помещают специальный метод, избавляющий от необходимости вручную составлять объект `Fetch`. Он может быть как обычным, так и `optional`:

```scala
def fetchElem(id: Int) = Fetch.optional(id, source)
```

Теперь возможно переписать используемые методы на заранее подготовленный `fetchElem` в классе `ListSource`:

```scala
Fetch.run(data.fetchElem(0)).unsafeRunSync   // INFO app.ListDataSource - Processing element from index 0
Fetch.run(data.fetchElem(1)).unsafeRunSync // INFO app.ListDataSource - Processing element from index 1
Fetch.run(data.fetchElem(2)).unsafeRunSync // INFO app.ListDataSource - Processing element from index 2
println(Fetch.run(data.fetchElem(2)).unsafeRunSync) // Some(c)
println(Fetch.run(data.fetchElem(3)).unsafeRunSync) // None
```

## Кэширование

Fetch не кэширует результаты "из коробки":

```scala
def fetch(id: Int): Option[String] = {
  val run   = Fetch.run(data.fetchElem(id))
  run.unsafeRunSync
}

fetch(1)  // INFO app.ListDataSource - Processing element from index 1
fetch(1)  // INFO app.ListDataSource - Processing element from index 1
fetch(1)  // INFO app.ListDataSource - Processing element from index 1
```

Для кэширования в Fetch существует специальный трейт `DataCache[F[_]]`. Сама библиотека предоставляет одну готовую имплементацию - иммутабельный кэш `InMemoryCache[F[_]: Monad](state: Map[(Data[Any, Any], DataSourceId), DataSourceResult])`. Им можно воспользоваться через методы его объекта-компаньона `from` (создать кэш из готовой коллекции) и `empty` (создать пустой кэш):

```scala
def from[F[_]: Monad, I, A](results: ((Data[I, A], I), A)*): InMemoryCache[F] 
def empty[F[_]: Monad]: InMemoryCache[F]
```

В обоих случаях в методах происходят преобразования, приводящие к тому, что кэш хранится в структуре данных типа `Map[(Data[Any, Any], DataSourceId), DataSourceResult]`. Дополнительные типы в этой мапе:

```scala
final class DataSourceId(val id: Any)         extends AnyVal
final class DataSourceResult(val result: Any) extends AnyVal
```

Получается, ключом этой мапы является кортеж `(Data[Any, Any], DataSourceId)`. Он содежит источник данных (любого типа) и какой-то ID (любого типа). Значением мапы является `DataSourceResult`, который содержит результат (тоже любого типа). Исходя из этого понятно, что в один кэш можно складывать результаты работы Fetch с самыми различными источниками данных. Ещё из этого следует, что конкретные типы при записи в кэш стираются - все данные имеют тип `Any` при хранении. Но они не остаются такими при извлечении. В случае с `InMemoryCache` извлечение из кэша происходит следующим образом:

```scala
def lookup[I, A](i: I, data: Data[I, A]): F[Option[A]] =
  Applicative[F].pure(
    state
      .get((data.asInstanceOf[Data[Any, Any]], new DataSourceId(i)))
      .map(_.result.asInstanceOf[A])
  )
```

Тут важно, что `Data` является частью ключа. Именно из переданного экземпляра `Data` берётся тип `A`, к которому методом `asInstanceOf[A]` приводится хранимый в кэше тип `Any` при запросе. Вставка в этот кэш работает на основе обычного метода map `updated`, который возвращает новую коллекцию при изменении.

Наверное, никому особо не будет дела до кэша в коллекции, которая является обычной мапой Scala - её можно и руками написать. Но используемые в ней приёмы пригодятся для подключения какой-нибудь специальной библиотеки для кэширования.

Пример использования созданного кэша методом `from`:

```scala
val cacheF: DataCache[IO] = InMemoryCache.from((data, 1) -> "b", (data, 2) -> "c")

Fetch.run(data.fetchElem(1), cacheF).unsafeRunSync  // взялось из кэша
Fetch.run(data.fetchElem(1), cacheF).unsafeRunSync  // прочитано из кэша
Fetch.run(data.fetchElem(1), cacheF).unsafeRunSync
Fetch.run(data.fetchElem(1), cacheF).unsafeRunSync
Fetch.run(data.fetchElem(0), cacheF).unsafeRunSync
Fetch.run(data.fetchElem(0), cacheF).unsafeRunSync

// INFO app.ListDataSource - Processing element from index 0
// INFO app.ListDataSource - Processing element from index 0
```

Видно, что кэширование работает, хотя кэш и не пополняется новыми элементами. Уже ясно, что это происходит из-за устройства кэша - иммутабельная мапа возвращает новую коллекцию при любом изменении. Это означает, что кэшем нужно *управлять вручную*. Для работы с этим поведением существует метод `Fetch.runCache`, который возвращает кортеж типа `(обновленный кэш, результат)`:

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

### Пример: использование Caffeine для кэширования

Для подключения собственной библиотеки кэширования нужно лишь имплементировать трейт `DataCache`. Полученный класс позволит использовать библиотеку в любых вызовах Fetch. Следующим образом можно реалиховать `DataCache` для известной Java-библиотеки **Caffeine** (а точнее - для её обёртки на Scala **Scaffeine**):

```scala
class ScaffeineCache extends DataCache[IO] with LazyLogging {

  private val cache =
    Scaffeine()
      .recordStats()
      .expireAfterWrite(1.hour)
      .maximumSize(500)
      .build[(Data[Any, Any], Any), Any]()

  override def lookup[I, A](i: I, data: Data[I, A]): IO[Option[A]] = IO {
    cache
      .getIfPresent(data.asInstanceOf[Data[Any, Any]] -> i)
      .map { any =>
        val correct = any.asInstanceOf[A]
        logger.info(s"From cache: $i")
        correct
      }
  }

  override def insert[I, A](i: I, v: A, data: Data[I, A]): IO[DataCache[IO]] = {
    cache.put(data.asInstanceOf[Data[Any, Any]] -> i, v) // Unit
    IO(this)
  }

}
```

Здесь используются все те же приёмы, что и в `InMemoryCache`. Так как `Scaffeine` работает с типизированными кэшами - кэш создаётся с типами `Any`: `build[(Data[Any, Any], Any), Any]()`. Затем запись и получение данных из него производится с использованием `asInstanceOf`:

```scala
val list  = List("a", "b", "c")
val listSource  = new ListSource(list)
val source = listSource.source
val randomSource = new RandomSource()
val cache = new ScaffeineCache()

/** Без кэширования */
Fetch.run(Fetch(1, source)).unsafeRunSync // Processing element from index 1
Fetch.run(Fetch(1, source)).unsafeRunSync // Processing element from index 1

println()

/** С кэшированием */
Fetch.run(Fetch(1, source), cache).unsafeRunSync // Processing element from index 1
Fetch.run(Fetch(1, source), cache).unsafeRunSync // From cache: 1
Fetch.run(Fetch("a", source), cache).unsafeRunSync // type mismatch

/** Один кэш подходит разным источникам */
Fetch.run(randomSource.fetchInt(2), cache).unsafeRunSync  // Getting next random by max 2
Fetch.run(randomSource.fetchInt(2), cache).unsafeRunSync  // From cache: 2
```

Можно заметить несколько вещей. 
Во-первых, при попытке использовать кэш с неправильным типом ID (чтобы попытаться нарушить работу `asInstanceOf`) произойдёт `type mismatch` по причине создания объекта `Fetch` с ID и Source, не подходящими друг другу по типам. 
Во-вторых, один и тот же кэш действительно можно использовать для разных источников.
Наконец, благодаря внутреннему устройству Caffeine, нам не нужно вручную управлять изменениями кэша - мы просто передаём одну и ту же ссылку в каждый вызов. Несмотря на это, трейт `DataCache` всё равно требует возвращать ссылку на кэш в методе `insert`.

## Комбинаторы

В Fetch возможно использовать различные комбинаторы из Scala и Cats. Смысл этих комбинаторов - преобразовать тип `List[Fetch[_,_]]` в `Fetch[_, List[_]]`, который затем передаётся в `Fetch.run`. Это необходимо для осуществления всех видов оптимизаций Fetch - объединения запросов, комбинирования, дедупликации, запусках в параллели. Оптимизации Fetch распространяются только на то, что передано внутри объекта `Fetch` в текущем `Fetch.run` (единственное исключение - кэширование, которое работает между запросами).

Например, возможно использовать `flatMap`, `sequence` и `traverse` из Cats. 

Важно правильно использовать комбинаторы. Например:

```scala
val t1: IO[(String, String)] = for {
  a <- Fetch.run(Fetch(1, source))
  b <- Fetch.run(Fetch(1, source))
} yield (a, b)
```

В этом примере for использован неверно - мы не получили `Fetch[_, List[_]]` или подобный тип, все объекты `Fetch` были переданы в `run` отдельно, поэтому они и выполнены будут отдельно. Правильно будет использовать `for` следующим образом:

```scala
val f1: Fetch[IO, (String, String)] = for {
  a <- Fetch(1, source)
  b <- Fetch(1, source)
} yield (a,b)

val t2: IO[(String, String)] = Fetch.run(f1)

```

Теперь мы получили Fetch от кортежа. Это значит, что при передаче в `Fetch.run` произойдут оптимизации:

- Дедупликация;
- Объединение (если в кортеже есть запросы к одинаковому источнику);
- Комбинирование (если в кортеже есть запросы к разным источникам).

В зависимости от настроек DataSource и реализации метода batch объединение может происходить в параллели. 

Того же эффекта позволяют добиться методы из cats. Если описывать просто, метод `sequence` создаёт из коллекции `List[F[_]]` коллекцию `F[List[_]]`. Метод `traverse` делает почти то же самое: из коллекции `List[_]` делает `F[List[_]]`. Наконец, `tupled` делает из кортежа эффектов один эффект со значением кортежа:

```scala

val f3: List[Fetch[IO, String]] = List(
  Fetch(1, source),
  Fetch(2, source),
  Fetch(2, source)
)

val f31: Fetch[IO, List[String]] = f3.sequence
val t3: IO[List[String]]         = Fetch.run(f31)


val f4: List[Int] = List(
  1,
  2,
  2
)

val f41: Fetch[IO, List[String]] = f4.traverse(Fetch(_, source))
val t4: IO[List[String]]         = Fetch.run(f41)

val f5: (Fetch[IO, String], Fetch[IO, String]) = (Fetch(1, source), Fetch(2, source))
val f51: Fetch[IO, (String, String)]           = f5.tupled
val t5: IO[(String, String)]                   = Fetch.run(f51)

val f6: (Int, Int)                = (1, 2)
val f61: Fetch[IO, (Int, String)] = f6.traverse(Fetch(_, source))
```

Не стоит забывать и о `flatMap`:

```scala
val f0: Fetch[IO, String]  = Fetch(1, source).flatMap(_ => Fetch(1, source))
val t0: IO[String]         = Fetch.run(f0)
```

Как видно из типов, совершенно неважно, каким методом был получен `Fetch` от нескольких запросов. Возможно использовать любые подходящие комбинаторы cats. Комбинирование объектов Fetch - главный приём для работы с библиотекой, ведь без него никакие оптимизации работать не будут. Кроме того, именно от выбранного метода комбинирования зависит, будут ли запросы выполнены последовательно или параллельно.

## Объединение запросов (Batching)

Fetch может объединять запросы к одному источнику в один запрос. Например:

```scala
import fetch.fetchM  // инстансы Fetch для синтаксиса Cats

val tuple: Fetch[IO, (Option[String], Option[String])] = (data.fetchElem(0), data.fetchElem(1)).tupled

Fetch.run(tuple).unsafeRunSync()  // (Some(a),Some(b))
```

По умолчанию метод `batch` в `DataSource` описан в терминах `fetch` и запрашивает идентификаторы у источника в параллели. Его можно переопределить. Например, для реляционной базы данных там может быть SQL-запрос для получения сразу множества ключей.

Поместим логгер в `batch` в источнике в `ListSource`:

```scala
override def batch(ids: NonEmptyList[Int]): IO[Map[Int, String]] = {
  logger.info(s"IDs fetching in batch: $ids")
  super.batch(ids)
}
```

Выполняем пакетный запрос через уже известный `traverse`:

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

## Комбинирование данных из разных источников

Кобминирование данных из разных источников осуществляется во время вызова данных из разных источников в одном `Fetch.run`. В целом, этот механизм идентичен объединенным запросам к одному источнику, просто внутри объектов Fetch источники будут указаны разные. 

Предположим, у нас есть дополнительный источник, который выдает случайные целочисленные до какой-то границы (он будет возвращать случайные числа на один и тот же ID, что делает идентификатор бесполезным, поэтому называть его источником в терминах Fetch нельзя, но для примера возьмем его):

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

Мы можем запросить эти данные в одном Fetch вместе с запросом из listSource:

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

Таким образом можно получить унифицированные интерфейсы доступа к различным источникам данных. Независимо от низкоуровневой реализации (база данных, файловая система, веб-ресурс), внешне источники данных одинаковы. Данные из них можно запрашивать вместе в одном контексте без совершения каких-либо дополнительных преобразований.

## Параллельное и последовательное выполнение

В целом, возможность параллельного запуска зависит от использованного комбинатора. Например, `for` и `flatMap` предполагают только последовательный запуск (мы ведь можем передавать переменные в следующие этапы вычисления). `sequence` и `traverse` позволяют выполнять запросы к одному источнику параллельно и объединённо. С другой стороны, если два разных источника имеют одинаковые типы - `sequence` и `traverse` можно использовать для параллельного чтения из них. Но лучше в этой ситуации подходит метод `tupled`, который позволяет делать параллельные запросы к разным источникам с разными типами. Оценить получившуюся последовательность запуска помогут методы дебага, описанные ниже.


## Дедупликация запросов

В обоих описанных случаях - при комбинировании и при объединении - всегда происходит дедупликация запросов. Это похоже на кэширование - один и тот же ID у одного источника запрашивается только единожды, но происходит только в пределах одного запроса. Кэширование же позволяет накапливать и передавать историю результатов между запросами.

Дедупликация в объединенных запросах:

```scala
val list = List("a", "b", "c", "d", "e", "f", "g", "h", "i")
val data = new ListSource(list, 2.some)

val tupleD: Fetch[IO, (Option[String], Option[String])] = (data.fetchElem(0), data.fetchElem(0)).tupled
Fetch.run(tupleD).unsafeRunSync()
//INFO app.sources.ListSource - Processing element from index 0
//(Some(a),Some(a))
```

Дедупликация в комбинированных:

```scala
def fetchMultiD: Fetch[IO, (Int, String, Int, String)] =
  for {
    rnd1 <- Fetch(3, randomSource.source)  // Fetch[IO, Int]
    char1 <- Fetch(rnd1, listSource.source)  // Fetch[IO, String]
    rnd2 <- Fetch(3, randomSource.source)  // Fetch[IO, Int]
    char2 <- Fetch(rnd2, listSource.source)  // Fetch[IO, String]
  } yield (rnd1, char1, rnd2, char2)

println(Fetch.run(fetchMultiD).unsafeRunSync)
//18:43:11.875 [scala-execution-context-global-14] INFO app.sources.RandomSource - Getting next random by max 3
//18:43:11.876 [scala-execution-context-global-13] INFO app.sources.ListSource - Processing element from index 1
//(1,b,1,b)
```

## Обработка исключений

В Fetch предоставлены инструменты для работы с иключениями: 

- Общий трейт `FetchException`;
- Специальное исключение `MissingIdentity` для несуществующих в источнике ID;
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
// Fetch execution 0.00 seconds
//
//    [Round 1] 0.00 seconds
//      [Fetch one] From `My List of Data` with id 5 0.00 seconds
```

Напишем сложный запрос со всеми изученными функциями Fetch (`>>` в cats это альяс для `flatMap(_ => ...)`):

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
  val complicatedFetch: Fetch[IO, (String, Int)] = cached >> notCached >> random >> notCached >> batched >> combined
  val result: IO[(Log, (String, Int))]           = Fetch.runLog(complicatedFetch, cacheF)
  val tuple: (Log, (String, Int))                = result.unsafeRunSync()

  println(tuple._2) // (f,17)
  println(describe(tuple._1))
  println(tuple._1)


  //Fetch execution 0.11 seconds
  //
  //  [Round 1] 0.06 seconds
  //    [Fetch one] From `My List of Data` with id 2 0.06 seconds
  //  [Round 2] 0.00 seconds
  //    [Fetch one] From `Random numbers generator` with id 10 0.00 seconds
  //  [Round 3]  0.01 seconds
  //    [Batch] From `My List of Data` with ids List(3, 4)  0.01 seconds
  //  [Round 4]  0.00 seconds
  //    [Fetch one] From `Random numbers generator` with id 150  0.00 seconds
  //    [Fetch one] From `My List of Data` with id 5  0.00 seconds

  // raw:
  // FetchLog(Queue(Round(List(Request(FetchOne(2,app.sources.ListSource@ea6147e),10767139,10767203))), Round(List(Request(FetchOne(10,app.sources.RandomSource@58b31054),10767211,10767213))), Round(List(Request(Batch(NonEmptyList(3, 4),app.sources.ListSource@ea6147e),10767234,10767242))), Round(List(Request(FetchOne(150,app.sources.RandomSource@58b31054),10767252,10767252), Request(FetchOne(5,app.sources.ListSource@ea6147e),10767252,10767252)))))
}
```

Можно сделать несколько наблюдений:

- `cached` вообще не появляется в логах, ведь он не считается никогда;
- `notCached` посчитался лишь раз. Cработала оптимизация и повторный запрос дедуплицировался;
- `batch` явно прописывается как запрос пакетом;
- Комбинированный запрос к нескольким источникам выглядит в логах как один раунд с несколькими запросами.

Действия в пределах одного раунда происходят параллельно. Сами по себе раунды разделены методом `>>`, который предполагает только последовательный запуск. А вот в раундах 3 и 4 использован метод `tupled`, который и позволяет запускать запросы параллельно. В первом случае это запрос к одному источнику, который был объединён. Во втором случае этот запрос был к разным источникам, поэтому он был запущен параллельно.

## Пример: запрос данных из реальных источников

Часто бывают ситуации, когда нужно предоставить пользователю выдачу на основании нескольких источников, данные из которых должны быть скомбинированы. Например, поисковая выдача по документам может для одного запроса обратиться к нескольким сервисам:

- Полнотекстовый поиск для получения ID документов по какому-то поисковому запросу;
- Сервис информации о документе;
- Сервис получения имени автора документа из его ID;
- Сервис похожих документов.

Тогда сервис поиска документов может быть построен следующим образом ([полный пример](./Examples/src/main/scala/app/searchfetchproto/)):

```scala
/*Model*/

type DocumentId = String
type PersonId = String

case class FtsResponse(ids: List[DocumentId])

case class SimilarityItem(id: DocumentId, similarity: Double)

case class DocumentInfo(id: DocumentId, info: String, authors: List[PersonId])

case class Person(id: PersonId, fullTitle: String)

/*Response*/
case class DocumentSearchResponse(
    items: List[DocumentSearchItem]
)

case class DocumentItem(id: DocumentId, info: Option[String], authors: List[Person])

case class DocumentSimilarItem(
    item: DocumentItem,
    similarity: Double
)

case class DocumentSearchItem(
    item: DocumentItem,
    similar: List[DocumentSimilarItem]
)


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

  def documentItemFetch(id: DocumentId): Fetch[IO, DocumentItem] =
    for {
      infoOpt <- infoSource.fetchElem(id)
      p       <- infoOpt.traverse(i => i.authors.traverse(personSource.fetchElem).map(_.flatten))
    } yield DocumentItem(id, infoOpt.map(_.info), p.getOrElse(List.empty[Person]))

  def fetchSimilarItems(id: DocumentId): Fetch[IO, List[DocumentSimilarItem]] =
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
```

На вход `DocumentSearchExample` получает три репозитория. Это сами сервисы с кодом, который обращается непосредственно к хранилищам данных (например, там может быть код обращения к БД на Doobie). Далее они передаются в обёртки Fetch - `DocumentInfoSource`, `PersonSource` и `SimilarDocumentSource`, которые позволяют оптимизировать запросы к ним. 

Метод `searchDocumentFetch` служит для произведения полнотекстового поиска по запросу `query`. Сам по себе поиск выполняется отдельно от остальных запросов, поэтому для него используется сервис напрямую, без обёртки в Fetch. Сигнатура `search`:

```scala
def search(query: String): F[FtsResponse]
```

Поэтому метод `liftF` позволяет получить тип `Fetch[F, FtsResponse]`, который можно использовать в for наряду с остальными Fetch. Дляее для списка ID запрашиваются данные документа и ID похожих документов. Кортеж элементов Fetch соединён через `tupled`, что позволяет сделать эти вызовы параллельно, ведь они не зависят друг от друга. Наконец, возвращённые в этом вызове значения маппятся в тип `DocumentSearchItem`. 

Метод `fetchSimilarItems` получет из `similarSource` ID похожих документов, а затем получает их информацию из метода `documentItemFetch`. В этот же метод обращаемся и при получении данных оригинальных документов. Он получает информацию о документах `DocumentInfo` по ID, а затем и имена авторов, указанных в информации.

Использование свойств монад позволяет композировать множество вызовов в один объект вместо вложенных вроде `Fetch[IO, Fetch[...]]` благодаря методам `map` и `flatMap`:

```scala

similarSource
  .fetchElem(id)
  .map(_.getOrElse(List.empty[SimilarityItem]))  // Fetch
  .flatMap { 
    _.traverse { si =>
      documentItemFetch(si.id).map { di =>  // Fetch
        DocumentSimilarItem(di, si.similarity)
      }
    }
  }
```

Для примера реализуем все репозитории на простых коллекциях:

```scala
private val docInfo = Map(
  "1" -> DocumentInfo("1", "Document 1", List(1)),
  "2" -> DocumentInfo("2", "Document 2", List(1,2)),
  "3" -> DocumentInfo("3", "Document 3", List(3,1)),
  "4" -> DocumentInfo("4", "Document 4", List(2,1)),
  "5" -> DocumentInfo("5", "Document 5", List(2)),
  "6" -> DocumentInfo("6", "Document 6", List(1,3))
)

private val similars = Map(
  "1" -> List(SimilarityItem("2", 0.7), SimilarityItem("3", 0.6)),
  "2" -> List(SimilarityItem("1", 0.7)),
  "3" -> List(SimilarityItem("1", 0.6)),
  "4" -> List(),
  "5" -> List(SimilarityItem("6", 0.5)),
  "6" -> List(SimilarityItem("5", 0.5))
)

private val persons = Map(
  1 -> Person(1, "Rick Deckard"),
  2 -> Person(2, "Roy Batty"),
  3 -> Person(3, "Joe")
)
```

Заметно, что все документы в качестве похожих имеют ссылки друг на друга и пересекающихся авторов. Однако, при запуске видно, что каждый документ и автор были запрошены лишь раз:

```
INFO app.searchfetchproto.source.SimilarDocumentSource - Fetching similar documents for ID: 2. It is: Some(List(1))
INFO app.searchfetchproto.source.SimilarDocumentSource - Fetching similar documents for ID: 4. It is: None
INFO app.searchfetchproto.source.SimilarDocumentSource - Fetching similar documents for ID: 3. It is: Some(List(1))
INFO app.searchfetchproto.source.SimilarDocumentSource - Fetching similar documents for ID: 1. It is: Some(List(2, 3))
INFO app.searchfetchproto.source.SimilarDocumentSource - Fetching similar documents for ID: 6. It is: Some(List(5))
INFO app.searchfetchproto.source.SimilarDocumentSource - Fetching similar documents for ID: 5. It is: Some(List(6))
INFO app.searchfetchproto.source.DocumentInfoSource - Document IDs fetching in batch: NonEmptyList(4, 5, 2, 3, 6, 1)
INFO app.searchfetchproto.source.PersonSource - Person IDs fetching in batch: NonEmptyList(1, 2, 3)
```

На самом деле, поиск похожих документов тоже параллельно, ведь для него не описано отдельного метода batch:

```scala
[Round 1]  0.12 seconds
    [Batch] From `Similar Document Source` with ids List(4, 5, 2, 3, 6, 1) 0.06 seconds
    [Batch] From `Document Info Source` with ids List(4, 5, 2, 3, 6, 1) 0.12 seconds
  [Round 2]  0.00 seconds
    [Batch] From `Persons source` with ids List(1, 2, 3)  0.00 seconds
```


## Выводы

Использование **Fetch** позволяет писать чистый композируемый код для эффективного доступа к источникам данных в функциональном стиле. Дополнительные конструкции вроде кэширования, дедупликации, объединения запросов и комбинирования писать не требуются, такой функционал предоставляется самой библиотекой. Благодаря использованию стека cats, **Fetch** может быть интегрирована в большое количество существующих программ на Scala и отлично уживается с библиотеками вроде Doobie и fs2.

### Аналоги

- ZQuery - судя по описанию, делает всё то же самое, но на стеке ZIO вместо Cats;
- Clump - видимо, предшественник Fetch, заброшенный в 2015;
- Haxl - то же самое на Haskell.

ZQuery и Fetch являются имплементациями статьи https://simonmar.github.io/bib/papers/haxl-icfp14.pdf , абстрактно описывающей (с привязкой к Хаскеллю) возможность программировать доступ к источникам данных на аппликативных функторах.
