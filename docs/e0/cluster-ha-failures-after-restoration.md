# Сбои HA после восстановления

## Артефакт и границы вывода

Источник — `TEST-com.bigdata.journal.jini.ha.TestAll.xml` в `C:\Users\Axiom\OpenideProjects\blazegraph-artifacts\cluster\20261108-ha-full-2\ha\bigdata-ha-test`. В XML записано **150 тестов: 22 сбоя, 17 ошибок, 0 пропусков**, длительность — **3 427,378 с**. Ниже перечислены все 39 неуспешных тестовых случаев ровно один раз. Классификация сообщения и самой глубокой причины по XML имеет **P≈1,00**.

Это исходный результат до исправления URI файлов Windows в `getFoafFileUrl()` и последующих точечных исправлений. Наличие сбоя или ошибки само по себе не доказывает дефект ядра: часть случаев связана с общей настройкой тестового стенда или с ожиданиями самих тестов. Там, где XML не содержит причины, это отмечено отдельно.

## Все 39 неуспешных случаев

После исправлений адресный набор из 42 случаев получил пять failures и два
comparator errors. Последующий запуск с исправленным comparator подтвердил
успех двух DumpLogDigests cases и новой unit-регрессии; пять остальных случаев
повторно упали. Актуальный список остатка и границы поэтапной проверки приведены
в [основном отчёте](2026-11-08-cluster-restoration.md#оставшиеся-ha-сбои).

### Ошибка разбора URI файла Windows в SPARQL LOAD — 12 ошибок

Самая глубокая причина — `com.bigdata.rdf.sail.sparql.ast.ParseException: Encountered "<" at line 2, column 6`. XML показывает запрос `LOAD <file:C:\Users\Axiom\OpenideProjects\blazegraph-db\bigdata-ha-test\src\test\resources\data\foaf\data-*.nq.gz>` с разделителями пути Windows. Это наблюдение объясняет отказ парсера; оно не устанавливает остальные возможные последствия для каждого теста. **P≈0,99** для связи ошибки с переданным URI.

- **Ошибка** `com.bigdata.journal.jini.ha.TestHA1SnapshotPolicy.testA_snapshot_running_2nd_snapshot_same_future`
- **Ошибка** `com.bigdata.journal.jini.ha.TestHA3JournalServer.testABCMultiLoadFollowerReadsLargeLoad`
- **Ошибка** `com.bigdata.journal.jini.ha.TestHA3JournalServer.testABCMultiLoadFollowerReads`
- **Ошибка** `com.bigdata.journal.jini.ha.TestHA3JournalServer.testStartAB_C_LiveResync`
- **Ошибка** `com.bigdata.journal.jini.ha.TestHA3JournalServer.testABC_LargeLoad`
- **Ошибка** `com.bigdata.journal.jini.ha.TestHA3JournalServer.testABC_LargeLoad_concurrentStatusRequests`
- **Ошибка** `com.bigdata.journal.jini.ha.TestHA3JournalServer.testABCMultiTransactionFollowerReads`
- **Ошибка** `com.bigdata.journal.jini.ha.TestHA3JustKills.testABC_awaitKBCreate_killC_LiveLoadRemainsMet`
- **Ошибка** `com.bigdata.journal.jini.ha.TestHA3JustKills.testABC_awaitKBCreate_killB_LiveLoadRemainsMet`
- **Ошибка** `com.bigdata.journal.jini.ha.TestHA3SnapshotPolicy.testAB_snapshot_running_2nd_snapshot_same_future`
- **Ошибка** `com.bigdata.journal.jini.ha.StressTestHA3JournalServer.testABCMultiLoadFollowerReadsLargeLoad`
- **Ошибка** `com.bigdata.journal.jini.ha.StressTestHA3JournalServer.testABCMultiLoadFollowerReads`

Общая задача загрузки формирует эти запросы в [`AbstractHA3JournalServerTestCase.LargeLoadTask`](../../bigdata-ha-test/src/test/java/com/bigdata/journal/jini/ha/AbstractHA3JournalServerTestCase.java#L3115). Самые глубокие кадры стека парсера в XML — `SyntaxTreeBuilder.Load` и `SyntaxTreeBuilder.IRIref`.

### Дайджест HALog вернулся вместо ожидаемого отсутствия HALog — 12 сбоев

Все 12 проверок сообщают `HALog exists`; в 11 случаях указан `commitCounter=1`, а в `TestHA3RestorePolicy` — `commitCounter=9`. [`assertHALogNotFound()`](../../bigdata-ha-test/src/test/java/com/bigdata/journal/jini/ha/AbstractHAJournalServerTestCase.java#L969) запрашивает дайджест и завершает проверку ошибкой, если запрос возвращает результат; ожидаемым признаком отсутствующего журнала считается `IOException`, содержащий `FileNotFoundException`. Следовательно, XML подтверждает, что запрос дайджеста для проверяемого счётчика вернул результат, но не объясняет, почему он вернулся. **P≈1,00** для этого пути проверки.

- **Сбой** `com.bigdata.journal.jini.ha.TestHA3JournalServer.testStartAB_C_Rebuild` — счётчик 1.
- **Сбой** `com.bigdata.journal.jini.ha.TestHA3JournalServer.testStartAB_C_MultiTransactionResync_0tx_then_500ms_delay` — счётчик 1.
- **Сбой** `com.bigdata.journal.jini.ha.TestHA3JournalServer.testStartAB_C` — счётчик 1.
- **Сбой** `com.bigdata.journal.jini.ha.TestHA3JournalServer.testQuorumABC_failC` — счётчик 1.
- **Сбой** `com.bigdata.journal.jini.ha.TestHA3JournalServer.testQuorumABC_killC` — счётчик 1.
- **Сбой** `com.bigdata.journal.jini.ha.TestHA3JournalServer.testStartAB_C_MultiTransactionResync_5tx_then_50ms_delay` — счётчик 1.
- **Сбой** `com.bigdata.journal.jini.ha.TestHA3JournalServer.testStartAB_C_MultiTransactionResync_5tx_then_500ms_delay` — счётчик 1.
- **Сбой** `com.bigdata.journal.jini.ha.TestHA3JournalServer.testStartAB_C_Resync` — счётчик 1.
- **Сбой** `com.bigdata.journal.jini.ha.TestHA3JournalServer.testStartAB_C_MultiTransactionResync_5tx_then_1500ms_delay` — счётчик 1.
- **Сбой** `com.bigdata.journal.jini.ha.TestHA3JournalServer.testStartAB_C_MultiTransactionResync_2tx_then_3000ms_delay` — счётчик 1.
- **Сбой** `com.bigdata.journal.jini.ha.TestHA3JournalServer.testStartAB_C_MultiTransactionResync_5tx_then_200ms_delay` — счётчик 1.
- **Сбой** `com.bigdata.journal.jini.ha.TestHA3RestorePolicy.testAB_restorePolicy` — счётчик 9.

Последующий анализ исходников выявил, что в `HAJournal.computeHALogDigest()` исходный код не закрывал `IHALogReader`; теперь reader закрывается в `finally` ([`HAJournal.java`](../../bigdata-ha/src/main/java/com/bigdata/journal/jini/ha/HAJournal.java#L1624)). Это согласуется с возвращённым дайджестом и последующими проверками HALog; **P≈0,99** для этой причины, связанной с временем жизни ресурса. Приведённый выше XML получен до этой правки и не показывает результат повторного прогона.

### FutureTask загрузки завершился раньше ожидаемого интервала — 10 сбоев

В XML у `AssertionFailedError` пустое сообщение; стек заканчивается на `Assert.assertFalse`. В соответствующих участках тестов после запуска загрузки проверяется `assertFalse(ft.isDone())` ([`TestHA3JournalServer`](../../bigdata-ha-test/src/test/java/com/bigdata/journal/jini/ha/TestHA3JournalServer.java#L2807), [`TestHA3JustKills`](../../bigdata-ha-test/src/test/java/com/bigdata/journal/jini/ha/TestHA3JustKills.java#L110)). Наблюдаемое условие — FutureTask уже завершился к моменту этой проверки. Ошибка разбора LOAD из предыдущей группы может объяснить раннее завершение, но XML не содержит причины FutureTask для этих 10 проверок; связь между группами — предположение (**P≈0,85**), а не доказанная причина каждого случая.

- **Сбой** `com.bigdata.journal.jini.ha.TestHA3JournalServer.testABC_LiveLoadRemainsMet_fail_B`
- **Сбой** `com.bigdata.journal.jini.ha.TestHA3JournalServer.testABC_LiveLoadRemainsMet_fail_C`
- **Сбой** `com.bigdata.journal.jini.ha.TestHA3JournalServer.testABC_LiveLoadRemainsMet_restart_B_fullyMetAfterLOAD`
- **Сбой** `com.bigdata.journal.jini.ha.TestHA3JournalServer.testABC_LiveLoadRemainsMet_restart_C_fullyMetDuringLOAD`
- **Сбой** `com.bigdata.journal.jini.ha.TestHA3JournalServer.testABC_LiveLoadRemainsMet_restart_B_fullyMetDuringLOAD`
- **Сбой** `com.bigdata.journal.jini.ha.TestHA3JournalServer.testABC_LiveLoadRemainsMet_restart_C_fullyMetDuringLOAD_restart_B_fullyMetDuringLOAD`
- **Сбой** `com.bigdata.journal.jini.ha.TestHA3JournalServer.testABC_LiveLoadRemainsMet_restart_B_fullyMetDuringLOAD_restartC_fullyMetDuringLOAD`
- **Сбой** `com.bigdata.journal.jini.ha.TestHA3JournalServer.testABC_LiveLoadRemainsMet_restart_C_fullyMetAfterLOAD`
- **Сбой** `com.bigdata.journal.jini.ha.TestHA3JustKills.testABC_LiveLoadRemainsMet_kill_B`
- **Сбой** `com.bigdata.journal.jini.ha.TestHA3JustKills.testABC_LiveLoadRemainsMet_kill_C`

### Процесс в тесте остановки не завершился к моменту проверки — 3 ошибки

Самые глубокие вложенные проверки сообщают `B is still running.` или `C is still running.` в момент проверки остановки процесса. Это описание зафиксированного сбоя, но не объяснение, почему завершение не произошло. **P≈1,00** для указанного состояния процесса.

- **Ошибка** `com.bigdata.journal.jini.ha.TestHA3JustKills.testABC_LiveLoadRemainsMet_kill_B_duringIncrementalReplication` — процесс B ещё работает.
- **Ошибка** `com.bigdata.journal.jini.ha.TestHA3JustKills.testABC_LiveLoadRemainsMet_kill_C_duringIncrementalReplication` — процесс C ещё работает.
- **Ошибка** `com.bigdata.journal.jini.ha.TestHA3JustKills.testABC_LiveLoadRemainsMet_C_kills_B_duringIncrementalReplication` — процесс B ещё работает.

### Ошибка компаратора DumpLogDigests сохранена как дайджест ошибки — 2 ошибки

В обеих ошибках XML есть вложенный `AssertionError` в `HALogInfo.compareTo`; указан `digest=4552524f52` (строка `ERROR` в шестнадцатеричном представлении). `DumpLogDigests.GetLogInfo` перехватывает исключение и сохраняет байты строки `ERROR` ([`DumpLogDigests.java`](../../bigdata-ha/src/main/java/com/bigdata/journal/jini/ha/DumpLogDigests.java#L402)). Сохранённые журналы процессов содержат более глубокий стек:

- Простой сценарий: `C:\Users\Axiom\OpenideProjects\blazegraph-artifacts\cluster\20261108-ha-full-2\ha-service-files\TestHA3DumpLogs.testSimpleDumpLogs\B\halog-B.txt`, строки 563–571. `HALogInfo.compareTo` выбрасывает `AssertionError` на строке 451; вызов идёт через `ConcurrentSkipListSet.add` и `ConcurrentSkipListMap.doPut`. У обоих аргументов компаратора `commitCounter=5`.
- Пакетный сценарий: `C:\Users\Axiom\OpenideProjects\blazegraph-artifacts\cluster\20261108-ha-full-2\ha-service-files\TestHA3DumpLogs.testBatchDumpLogs\B\halog-B.txt`, строки 15758–15767. В том же пути стека сравниваются аргументы с `commitCounter=2`.

В исходной версии компаратор проверял, что сравниваемые записи имеют разные счётчики commit. Сохранённые журналы показывают, что упорядоченная коллекция JDK 8 может вызвать компаратор для записей с одинаковым счётчиком; они не доказывают, что это происходит только при первой вставке. Текущая реализация сравнивает значения через `Long.compare(commitCounter, other.commitCounter)` ([`DumpLogDigests.HALogInfo.compareTo`](../../bigdata-ha/src/main/java/com/bigdata/journal/jini/ha/DumpLogDigests.java#L448)). Отдельная unit-регрессия компаратора воспроизводит сбой до правки и проходит после неё; это не повторный прогон этих интеграционных HA-тестов. Данные указывают на срабатывание проверки в компараторе, а не на блокировку файла в Windows (**P≈0,99**).

- **Ошибка** `com.bigdata.journal.jini.ha.TestHA3DumpLogs.testBatchDumpLogs`
- **Ошибка** `com.bigdata.journal.jini.ha.TestHA3DumpLogs.testSimpleDumpLogs`
