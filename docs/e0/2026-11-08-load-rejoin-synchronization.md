# Rejoin во время одной LOAD

## Воспроизведение и причины

На ревизии `a5845ca` все четыре целевых сценария
`TestHA3JournalServer.testABC_LiveLoadRemainsMet_restart_*fullyMetDuringLOAD*`
падают при проверке `awaitFullyMetDuringLOAD2()`. В диагностическом запуске
перед входом в helper все четыре Future уже завершены; `ft.get()` успешно
возвращается. Это успешное окончание LOAD, а не скрытая ошибка запроса.

В первом сценарии POST начинается на 9 760 мс, B останавливается на 10 372 мс,
ответ HTTP 200 приходит на 14 395 мс, а перезапуск B начинается на 14 420 мс.
Сервис снова работает на 17 797 мс; diagnostic helper записан на 18 525 мс.
Эти времена отсчитаны от начала тестовой JVM. Конечный FOAF-набор успевает
закончиться раньше rejoin; продолжительность данных не обеспечивает нужную
синхронизацию (**P≈0,99**).

Управляемая LOAD сохранила требуемое перекрытие и выявила два дефекта
[`QuorumPipelineImpl`](../../bigdata-core/bigdata/src/java/com/bigdata/ha/QuorumPipelineImpl.java):

- `elideEvents()` объединяет `C → null` и `null → C` в `C → C`.
  `pipelineChange()` раньше считал равные UUID отсутствием изменений.
  После рестарта это сохраняло старый RMI proxy. После reset у B мог остаться
  `receiveService` с `addrNext=null`; повторное событие `C → C` игнорировалось,
  и B продолжал принимать блоки, но не передавал их C. В трассировке видны оба
  объединённых события и отсутствие входящих блоков на перезапущенном C при
  продолжающихся отправках лидера (**P≈0,99**).
- `isProblemServiceOurNeighbor()` обращался к массиву соседей без проверки
  `null`. `getPipelinePriorAndNext()` возвращает `null`, если локальный узел
  уже удалён из pipeline. Запрос reset в этом допустимом состоянии вызывал
  `NullPointerException`; это воспроизведено и service log, и отдельным unit test.

## Исправление и сохранённые проверки

`ControlledLoadSource` отдаёт настоящий HTTP-поток N-Triples. Он продолжает
выдавать уникальные записи и не посылает EOF до вызова `finish()` из теста.
Пауза между небольшими порциями ограничивает объём данных; корректность
синхронизации определяется разрешением EOF, а не длительностью паузы.
Используется [HTTP server API JDK 8](https://docs.oracle.com/javase/8/docs/jre/api/net/httpserver/spec/com/sun/net/httpserver/HttpExchange.html).

Только четыре целевых сценария заменяют четвёртый источник `LargeLoadTask`
на этот поток. Первые три FOAF-файла и остальные операции того же SPARQL
UPDATE сохранены. Тест ждёт реального запроса к источнику, затем выполняет
один или два рестарта. Все шесть проверок «полный quorum при незавершённом
Future», неизменность token и ожидаемые перестановки pipeline сохранены.
После последнего checkpoint тест разрешает EOF, ждёт успешного Future и
сравнивает digest журналов A/B/C. Для сценария C это также восстанавливает
ранее закомментированное ожидание окончания LOAD.

Источник и задача закрываются через try-with-resources; при ошибке задача
отменяется, HTTP server и его executor останавливаются. Источник ограничен
deadline и сообщает ошибки производителя даже после HTTP 200. Deadline
проверяется между записями; при блокирующем сетевом write прекращение
обеспечивает `close()` при выходе теста по его timeout.

В pipeline сохраняется no-op только для `null → null`. Равный ненулевой UUID
теперь проходит существующий путь повторного lookup proxy и перенастройки
транспорта. Отсутствие локального узла означает отсутствие соседей, поэтому
reset не должен ждать их удаления или разыменовывать `null`.

## Проверки на Axiom JDK 8u482

Использованы Windows и Axiom JSC `1.8.0_482-b10` из
`C:\Users\Axiom\.jdks\axiomjdk-1.8.0_482`. Артефакты хранятся вне `target`,
в `C:\Users\Axiom\OpenideProjects\blazegraph-artifacts\cluster`.

| Запуск | Tests | Failures | Errors | Retry events |
| --- | ---: | ---: | ---: | ---: |
| `20261108-during-load-red-1`: первый адресный запуск | 4 | 1 | 3 | 0 |
| `20261108-during-load-diagnostic`: повтор после восстановления классов | 4 | 4 | 0 | 0 |
| `20261108-during-load-green-1`: управляемая LOAD, до исправлений pipeline | 4 | 0 | 2 | 0 |
| `20261108-during-load-pipeline-c-check`: только исправление null-соседей | 1 | 0 | 1 | 0 |
| `20261108-during-load-resync-trace`: трассировка с тем же кодом | 1 | 0 | 1 | 0 |
| `20261108-during-load-reconnect-c-green`: оба исправления pipeline | 1 | 0 | 0 | 0 |
| `20261108-during-load-family-green`: расширенный HA-набор | 19 | 0 | 0 | 0 |
| `20261108-during-load-core-maven-green`: reset и передача WORM/RW | 3 | 0 | 0 | 0 |

Расширенный набор содержит четыре целевых сценария, два варианта
`fullyMetAfterLOAD`, `fail_B`, `fail_C`, `testStartAB_C_LiveResync`,
`testStartAB_C_MultiTransactionResync_5tx_then_50ms_delay`, `testABC_LargeLoad`,
три `TestControlledLoadSource` и пять `TestHALogPurge` cases. Все шесть rejoin
checkpoints четырёх целевых методов выполнены при незавершённой LOAD; после
разрешения EOF успешно завершились Future и сравнения журналов. Сценарий C
дополнительно прошёл отдельно. В положительных HA-запусках ноль skipped.

Core-проверка через Maven включает `test_resetPipeline_whenNotInPipeline`,
`test_writeCacheService_HA_WORM_2buffer_k3_size3` и
`test_writeCacheService_HA_RW_2buffer_k3_size3`. Все три тела выполняются;
результат — 3/3 без skipped и повторов. Вместе с расширенным HA-набором это
22 разных прошедших теста; с отдельным повтором C — 23 выполнения этих тестов.
В core-логе `DirectBufferPoolTestHelper` сообщает `nacquired=4, nreleased=2`
для WORM-теста. Helper только пишет ERROR и сбрасывает счётчики, поэтому это
не JUnit failure. Причина этого дисбаланса в рамках данного исправления не
установлена; успешный JUnit-результат не подтверждает отсутствие утечек буферов.

В последующем [разборе жизненного цикла буферов](2026-11-08-write-cache-buffer-lifecycle.md)
найдена и исправлена причина дисбаланса в тестовом HA-участнике.

Имя каталога `green-1` не означает успешность: в нём прошли B и B→C,
а C и C→B исчерпали timeout. Исправления одного NPE было недостаточно.
В первом запуске три startup errors возникли после исчезновения `.class`
при параллельном запуске JDT LS; они не проверили LOAD/rejoin. После остановки
языкового сервера и чистой пересборки все четыре исходных сбоя воспроизвелись.

Дополнительно выполнены три unit test `TestControlledLoadSource`: реальный
RDF-поток и явный EOF, ошибка deadline после HTTP 200, закрытие активного
читателя и listening socket. Регрессия
`TestWORMWriteCacheService.test_resetPipeline_whenNotInPipeline` даёт NPE со
старым `QuorumPipelineImpl` и проходит с исправленным. Для отрицательного
контроля исходный класс скомпилирован отдельно от рабочего `target`.

Экспериментальный mock relay probe не различил старый и исправленный код:
оба запуска упёрлись в неподдержанный `MockHAPipelineGlue.resetPipeline()`.
Он не добавлен в штатный набор; исходник и оба лога сохранены в
`20261108-during-load-relay-regression`. Проверка rejoin выполняется реальными
HA-процессами. Два старых core-теста `*reorganizePipeline` возвращаются сразу
через `skipReorganizePipelineTest()` и не считаются выполненным сценарием.

Полный HA aggregate и полный штатный reactor в рамках этого изменения не
запускаются. Новый HA aggregate содержит 162 случая; кластерный runner — 487.
Эти числа отражают состав, а не результат полного запуска.
