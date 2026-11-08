# Состав кластерных проверок

Размеры текущих агрегатов проверены через `suite().countTestCases()` на Axiom
JDK 8u482. Вызов подсчёта не запускает тесты. Результаты запусков приведены
в [отчёте восстановления](2026-11-08-cluster-restoration.md) и
[проверке ожидания HALog purge](2026-11-08-halog-purge-wait.md).

| Entry point | Случаев | Что проверяет |
| --- | ---: | --- |
| `com.bigdata.zookeeper.TestAll` | 18 | Сессии, ephemeral nodes, watchers, locks, queues, elections |
| `com.bigdata.quorum.zk.TestAll` | 23 | ZooKeeper-backed quorum, singleton и HA3 |
| `com.bigdata.jini.TestAll` | 19 | Конфигурация, клиенты и запуск сервисов |
| `com.bigdata.rdf.store.TestScaleOutTripleStoreWithJiniFederation` | 261 | Triple store, загрузка RDF и inference через federation |
| `com.bigdata.journal.jini.ha.TestAll` | 159 | Journal, replication, HA1/2/3, snapshot/restore, failover, load balancers, stress и локальные регрессии |

Дополнительно runner выбирает `com.bigdata.jini.start.config.TestServiceConfiguration`:
один исходный тест и три новых регрессионных теста генерации River configuration.
Они не входят в корневой Jini-агрегат. Итого текущий runner выбирает **484** случая.

## Maven selection

Корневой [pom.xml](../../pom.xml) выбирает `**/TestAll.java` и исключает
`com/bigdata/TestAll.java`. Это правило наследуют восстановленные модули.
Поэтому обычный запуск не выбирает все классы с префиксом `Test`.

В Jini под шаблон попадают корневой и вложенные агрегаты. Исходный состав
давал 40 запусков при 19 уникальных случаях. После добавления трёх проверок
генерации конфигурации и проверки destroy — 45 при 23 уникальных случаях. Runner выбирает корневой агрегат и
`TestServiceConfiguration` напрямую, чтобы не повторять вложенные suites.
RDF scale-out suite запускается отдельно.

## Границы scale-out проверки

`TestBigdataClient.test_registerIndex2()` регистрирует две партиции на двух
DataServices, проверяет метаданные, записывает 1 000 записей и читает их обратно.
`JiniServicesHelper` запускает два DataServer, MetadataServer, TransactionServer,
LoadBalancerServer и ClientServer в потоках одной JVM. Общение идёт через River
прокси; это не многопроцессное развёртывание DataServices.

`TestServiceStarter.test_startServer()` и
`TestServiceConfigurationMonitoring.test_logicalServiceWatcher()` запускают
дочерние JVM TransactionServer. RDF proxy suite создаёт federation для каждого
делегированного теста и выполняет `TestTripleStoreBasics.suite()` и RDF rules suite.
ZooKeeper и River lookup должны быть запущены отдельно.

## Границы HA проверки

HA-тесты запускают journal services отдельными JVM. Исходный агрегат содержит 23 конкретных
suite-класса: HALog/snapshot indices, raw transfers, HA1/2/3, snapshot/restore,
смену лидера, принудительные остановки, отмену запросов, четыре load balancer
варианта и `StressTestHA3JournalServer`. Добавлены девять локальных unit test cases:
`TestHA3JustKills.TestKillTrigger`, `TestHAFileUrls`, `TestDumpLogDigests` и
пять проверок `TestHALogPurge`.
Они не запускают сервисы.

Исходный `runKnownBadTests=false` сохранён. Три включённых метода сразу возвращаются
без выполнения сценария и видны JUnit как прошедшие:

- `TestHA3ChangeLeader.testStartABC_KillLeader_RandomTrans`;
- `TestHAJournalServerOverride.testABC_stopZookeeper_failA_startZookeeper_quorumMeetsAgainOnNewToken`;
- `TestHAJournalServerOverride.testAB_stopZookeeper_failB_startZookeeper_quorumBreaksThenMeets`.

В ZooKeeper suite так же устроен `TestHierarchicalZNodeWatcher.test_noticeCreate`.
Эти четыре случая входят в численные итоги, но не подтверждают соответствующие
сценарии. Нулевой XML-счётчик skipped не означает, что их тела выполнились.

HA5 suites включаются только при `runKnownBadTests=true`; исходный комментарий
указывает известные проблемы и issue #722. HA1/HA3 group commit suites исходно
закомментированы по #1156/#1189. Имена `_test...` не выбираются JUnit 3.

`TestJiniCoreServicesProcessHelper` — отдельный ручной/debug launcher, отсутствующий
в агрегате; `TestMappedRDFDataLoadMaster` отключён в master-suite с комментарием
о незавершающемся CI-прогоне. Они не считаются проверенными этим runner.
