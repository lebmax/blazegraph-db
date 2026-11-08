# Возврат receive-буферов после тестов WriteCacheService

## Воспроизведение и причина

На ревизии `10a785a` тест
`TestWORMWriteCacheService.test_writeCacheService_HA_WORM_2buffer_k3_size3`
успешно завершался по JUnit, но `DirectBufferPoolTestHelper` записывал
`nacquired=4, nreleased=2`. Число занятых буферов в самом пуле после теста
также увеличивалось на два. Это невозвращённые в пул буферы, а не только дисбаланс
диагностических счётчиков (**P≈0,99**).

Трассировка выделений и освобождений показала цепочку:

1. `WriteCacheService` получает два буфера; `close()` возвращает оба.
2. Два followers получают ещё по одному буферу в
   [`QuorumPipelineImpl.InnerEventHandler.setUpReceiveService()`](../../bigdata-core/bigdata/src/java/com/bigdata/ha/QuorumPipelineImpl.java).
3. В `finally` тест завершает три `MockQuorum` и fixture.
   `AbstractQuorum.terminate()` вызывает `client.terminate()` перед очисткой
   локального состояния quorum.
4. Тестовый `MyMockQuorumMember` наследовал
   `AbstractQuorumClient.terminate()`, который только очищает ссылку на quorum.
   Завершение send/receive services и возврат receive-буферов не выполнялись.
5. `DirectBufferPoolTestHelper` только записывает ERROR и сбрасывает счётчики
   операций; это не приводит к JUnit failure и не возвращает занятые буферы.

Сначала добавлена проверка числа занятых буферов до и после каждого теста.
Без исправления целевой метод упал три раза: `0 → 2`, `2 → 4`, `4 → 6`.
Последние два выполнения — автоматические повторы Surefire. Все три завершились
одинаковым нарушением инварианта. В их трассировке 12 выделений и 6 освобождений;
ни одного освобождения receive-буфера через pipeline teardown.

## Исправление

В [`TestWORMWriteCacheService`](../../bigdata-core-test/bigdata/src/test/com/bigdata/io/writecache/TestWORMWriteCacheService.java)
тестовый участник теперь переопределяет `terminate()`: ставит `PIPELINE_REMOVE`
в очередь своего pipeline и синхронно обрабатывает события через `processEvents()`.
Обработчик останавливает transport, ждёт завершения receive service и возвращает
его буфер в пул. Это происходит до очистки ссылки на quorum; вызов
`super.terminate()` гарантирован блоком `finally`.

`setUp()` сохраняет публичный `DirectBufferPool.INSTANCE.getAcquiredBufferCount()`.
`tearDown()` проверяет возвращение к исходному значению, затем в `finally`
выполняет базовую очистку. Проверка охватывает весь класс и использует
фактическое число занятых буферов, независимое от сброса диагностических счётчиков.
Она проверена в принятом в проекте последовательном режиме выполнения тестов.

В этих сценариях `doStressTest()` завершает запись и закрывает WriteCacheService
до остановки quorum. Исправление относится к жизненному циклу тестового
участника; реализация production pipeline и его публичные API не меняются.

## Проверки на Axiom JDK 8u482

Windows, Axiom JSC `1.8.0_482-b10`,
`C:\Users\Axiom\.jdks\axiomjdk-1.8.0_482`.
Артефакты сохранены вне `target`, под
`C:\Users\Axiom\OpenideProjects\blazegraph-artifacts\cluster`:

| Каталог | Проверка и результат |
| --- | --- |
| `20261108-buffer-balance-prework` | Исходный метод: JUnit 1/1, но `nacquired=4, nreleased=2`; проверка журнала обнаруживает дефект |
| `20261108-buffer-balance-red` | Только новый assert: один метод, три неуспешных выполнения, включая два автоповтора; накопление 2/4/6 занятых буферов |
| `20261108-buffer-balance-green` | Исправленный метод: 1/1; четыре выделения, четыре освобождения, включая оба receive-буфера |
| `20261108-buffer-balance-writecache-suite` | Write cache aggregate 32/32, DirectBufferPool 3/3, allocator 4/4; всего 39/39 |

Итоговый `20261108-buffer-balance-final/validation.json` перечисляет конкретные
XML, использованные для подсчёта. В адресных каталогах также сохранены старые
отчёты, скопированные из `target`; они не относятся к текущему запуску и не
включены в результаты выше.

В обоих положительных запусках ноль failures, errors, JUnit skips и событий
автоповтора. Нет сообщений о невозвращённых буферах или возврате через finalizer.
Целевой метод прошёл отдельно и внутри aggregate.

В исходном write cache aggregate три метода `*reorganizePipeline` завершаются
сразу через `skipReorganizePipelineTest()`. Они включены в JUnit-счётчик 39,
но не проверяют реорганизацию: всего выполнено 36 содержательных сценариев.
Обычные HA WORM/RW-тесты выполняются: `skipHATest()` возвращает `false`.
Сообщение `Bad data for address: 12800` принадлежит положительному тесту
`TestWriteCache.test_writeCacheChecksums`, намеренно записывающему неверную
контрольную сумму и проверяющему `ChecksumError`.

Команда расширенной проверки из корня репозитория после установки `JAVA_HOME`
и добавления его `bin` в `PATH`:

```powershell
mvn -B -ntp '-PDevelopment,cluster-integration' -pl bigdata-core-test '-Dtest=com.bigdata.io.writecache.TestAll,com.bigdata.io.TestDirectBufferPool,com.bigdata.io.TestDirectBufferPoolAllocator' -DfailIfNoTests=true test
```

Полный reactor и HA aggregate в рамках этого изменения не запускались.
