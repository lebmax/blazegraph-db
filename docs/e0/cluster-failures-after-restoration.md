# Сбои RDF scale-out после восстановления

## Артефакт

Источник результата — `TEST-com.bigdata.rdf.store.TestScaleOutTripleStoreWithJiniFederation.xml` из каталога `C:\Users\Axiom\OpenideProjects\blazegraph-artifacts\cluster\20261108-rdf-destroy-green\scaleout-rdf\bigdata-jini-test`. XML содержит 261 test case: **9 failures, 41 errors, 0 skipped**, время — 649,608 с. Сводки ниже основаны на deepest cause или assertion, сохранённых в XML; для этих записей уверенность в классификации — **P≈1,00**. Отсутствующий в XML вложенный cause помечен отдельно.

## Все 50 неуспешных случаев

### Vocabulary и axioms отсутствуют после повторного открытия — 5 errors

- **Error** `com.bigdata.rdf.lexicon.TestVocabulary.test_NoVocabulary` — `No vocabulary defined?` при чтении повторно открытого store.
- **Error** `com.bigdata.rdf.lexicon.TestVocabulary.test_RdfsVocabulary` — `No vocabulary defined?` при чтении повторно открытого store.
- **Error** `com.bigdata.rdf.axioms.TestAxioms.test_NoAxioms` — `No axioms defined?` при чтении повторно открытого store.
- **Error** `com.bigdata.rdf.axioms.TestAxioms.test_OwlAxioms` — `No axioms defined?` при чтении повторно открытого store.
- **Error** `com.bigdata.rdf.axioms.TestAxioms.test_RdfsAxioms` — `No axioms defined?` при чтении повторно открытого store.

**Статическая трассировка этих пяти случаев.** Тесты передают настроенные свойства в `getStore(properties)`: vocabulary-кейсы задают классы vocabulary/axioms, а axiom-кейсы — класс axioms ([TestVocabulary](../../bigdata-rdf-test/src/test/java/com/bigdata/rdf/lexicon/TestVocabulary.java#L71), [TestAxioms](../../bigdata-rdf-test/src/test/java/com/bigdata/rdf/axioms/TestAxioms.java#L96)). Прокси делегирует тот же объект свойств без преобразования ([ProxyTestCase.getStore](../../bigdata-rdf-test/src/test/java/com/bigdata/rdf/store/ProxyTestCase.java#L175)); Jini delegate передаёт его конструктору `ScaleOutTripleStore`, затем вызывает `create()` ([Jini delegate](../../bigdata-jini-test/src/test/java/com/bigdata/rdf/store/TestScaleOutTripleStoreWithJiniFederation.java#L199)). Это не показывает потерю настроек на границе `getStore(properties)`.

Все пять тестов далее проверяют `store.isStable()`. У `ScaleOutTripleStore.isStable()` безусловный результат `true`, поэтому они идут в `reopenStore()`; номера строк XML указывают именно на последующее чтение (`TestVocabulary.java:157/228`, `TestAxioms.java:163/242/331`). Delegate закрывает store, отключает и повторно подключает клиента, а затем создаёт новый объект для того же namespace с `store.getProperties()` и вызывает `init()` ([reopenStore](../../bigdata-jini-test/src/test/java/com/bigdata/rdf/store/TestScaleOutTripleStoreWithJiniFederation.java#L229), [ScaleOutTripleStore.isStable](../../bigdata-core/bigdata-rdf/src/java/com/bigdata/rdf/store/ScaleOutTripleStore.java#L101)). При `create()` vocabulary и axioms записываются в metadata map глобального row store ([AbstractTripleStore.create](../../bigdata-core/bigdata-rdf/src/java/com/bigdata/rdf/store/AbstractTripleStore.java#L1921)). У нового объекта `getVocabulary()` и `getAxioms()` читают соответствующие записи из `getBareProperties()` и выбрасывают исключение, если значение отсутствует ([getVocabulary](../../bigdata-core/bigdata-rdf/src/java/com/bigdata/rdf/store/AbstractTripleStore.java#L2160), [getAxioms](../../bigdata-core/bigdata-rdf/src/java/com/bigdata/rdf/store/AbstractTripleStore.java#L2103)).

Вывод с **P≈0,95**: наблюдаемый сбой находится на пути повторной инициализации/доступа к сохранённым metadata после reconnect, а не при передаче исходных свойств в `getStore(properties)`. Почему эти записи отсутствуют в `getBareProperties()` после `init()`, по этому XML и статической трассировке **неизвестно**; они не устанавливают, не были ли значения сохранены или не стали видны новому объекту.

### Ошибки RMI-сериализации — 13 errors

- **Error** `com.bigdata.rdf.lexicon.TestSubjectCentricFullTextIndex.test_MultiSubject` и `com.bigdata.rdf.lexicon.TestSubjectCentricFullTextIndex.test_SingleSubject` — deepest cause `java.io.NotSerializableException: com.bigdata.rdf.lexicon.LexiconRelation` при маршалинге аргументов RMI.
- **Error** `com.bigdata.rdf.spo.TestSPORelation.test_runRule` — deepest cause `java.io.NotSerializableException: com.bigdata.rdf.spo.TestSPORelation`; отправка `JoinTaskFactoryTask` завершается `Could not submit`.
- **Error** `com.bigdata.rdf.rules.TestDatabaseAtOnceClosure.test_fixedPoint_TestOwlSameAs_Fast_Pipeline`, `com.bigdata.rdf.rules.TestDatabaseAtOnceClosure.test_fixedPoint_SampleData_Full_PipelineJoins`, `com.bigdata.rdf.rules.TestDatabaseAtOnceClosure.test_fixedPoint_Small_Full_PipelineJoins`, `com.bigdata.rdf.rules.TestDatabaseAtOnceClosure.test_fixedPoint_LUBM_U1_As_Fast_PipelineJoins`, `com.bigdata.rdf.rules.TestDatabaseAtOnceClosure.test_fixedPoint_LUBM_U1_As_Full_PipelineJoins`, `com.bigdata.rdf.rules.TestDatabaseAtOnceClosure.test_fixedPoint_TestOwlSameAs_Full_PipelineJoins`, `com.bigdata.rdf.rules.TestDatabaseAtOnceClosure.test_fixedPoint_SampleData_Fast_PipelineJoins` и `com.bigdata.rdf.rules.TestDatabaseAtOnceClosure.test_fixedPoint_Small_Fast_PipelineJoins` — deepest cause `java.io.NotSerializableException: com.bigdata.rdf.spo.SPORelation` при маршалинге аргументов RMI.
- **Error** `com.bigdata.rdf.rules.TestBackchainTypeResourceIterator.test_backchain_foo_type_resource` и `com.bigdata.rdf.rules.TestBackchainTypeResourceIterator.test_noneBound` — deepest cause `java.io.NotSerializableException: com.bigdata.rdf.spo.SPORelation` при маршалинге аргументов RMI.

### Локальный BTree приводится к IClientIndex — 11 случаев

Deepest cause XML — `ClassCastException: com.bigdata.btree.BTree cannot be cast to com.bigdata.service.ndx.IClientIndex`.

- **Error:** `com.bigdata.rdf.store.TestDataLoader.test_durableQueues01_incrementalCommit_batchClosure`, `com.bigdata.rdf.store.TestDataLoader.test_DataLoader_loadResource01`, `com.bigdata.rdf.store.TestDataLoader.test_durableQueues02_batchCommit_batchClosure`, `com.bigdata.rdf.store.TestDataLoader.test_durableQueues02_batchCommit_incrementalClosure`.
- **Error:** `com.bigdata.rdf.rules.TestTruthMaintenance.test_closurecorrectness`, `com.bigdata.rdf.rules.TestTruthMaintenance.test_retractWhenStatementSupportsExplicitStatement`, `com.bigdata.rdf.rules.TestTruthMaintenance.test_retractAll_01`, `com.bigdata.rdf.rules.TestTruthMaintenance.test_downgradeExplicitToInference`, `com.bigdata.rdf.rules.TestTruthMaintenance.test_infiniteloop`, `com.bigdata.rdf.rules.TestTruthMaintenance.test_assertAll_01`.
- **Failure:** `com.bigdata.rdf.rules.TestTruthMaintenance.test_stress` — assertion `Not expecting` wraps the same deepest `ClassCastException`.

### Ошибки асинхронной загрузки RDF — 10 errors

- **Error:** `com.bigdata.rdf.rio.TestAsynchronousStatementBufferFactory.test_loadAndVerify_bsbm_pc100_quadsMode`, `com.bigdata.rdf.rio.TestAsynchronousStatementBufferFactory.test_loadAndVerify_sampleData`, `com.bigdata.rdf.rio.TestAsynchronousStatementBufferFactory.test_loadAndVerify_bsbm_pc100` и `com.bigdata.rdf.rio.TestAsynchronousStatementBufferFactory.test_loadAndVerify_sampleData_quadsMode` — deepest cause `RuntimeException: Too many errors`.
- **Error:** `com.bigdata.rdf.rio.TestAsynchronousStatementBufferFactory.test_loadAndVerify_little_trig_quadsMode` и `com.bigdata.rdf.rio.TestAsynchronousStatementBufferFactory.test_loadAndVerify_little_ttl_quadsMode` — `nerrors expected:<0> but was:<7>`.
- **Error:** `com.bigdata.rdf.rio.TestAsynchronousStatementBufferFactory.test_loadAndVerify_smallWithBlobs_quadsMode` — `nerrors expected:<0> but was:<5>`.
- **Error:** `com.bigdata.rdf.rio.TestAsynchronousStatementBufferFactory.test_loadAndVerify_small_quadsMode` — `nerrors expected:<0> but was:<3>`.
- **Error:** `com.bigdata.rdf.rio.TestAsynchronousStatementBufferFactory.test_loadAndVerify_small` и `com.bigdata.rdf.rio.TestAsynchronousStatementBufferFactory.test_loadAndVerify_smallWithBlobs` — deepest cause `NullPointerException`; XML не содержит сообщения с деталями.

### Результат отличается от ожидаемого — 8 failures

- **Failure:** `com.bigdata.rdf.store.TestTripleStore.test_addRemove_sesameAPI` — на позиции 0 ожидался объект `.../B`, получен `.../C`.
- **Failure:** `com.bigdata.rdf.store.TestTripleStore.test_rangeCounts_toldBNodes` — ожидалось 3 blank nodes, получен счётчик 1.
- **Failure:** `com.bigdata.rdf.store.TestTripleStore.test_getStatementsUsingTriplePatterns` — на позиции 1 ожидался subject `.../B`, получен `.../C`.
- **Failure:** `com.bigdata.rdf.store.TestBulkFilter.test_filterOut` — ожидалось ещё одно statement, отсутствует указанный в assertion tuple из `TermId`.
- **Failure:** `com.bigdata.rdf.rules.TestRuleFastClosure_11_13.test_RuleFastForwardClosure11`, `com.bigdata.rdf.rules.TestRuleFastClosure_11_13.test_RuleFastForwardClosure13` и `com.bigdata.rdf.rules.TestRuleOwlHasValue.test_OwlHasValue` — ожидался `mutationCount(Insert)=1`, фактически 0.
- **Failure:** `com.bigdata.rdf.rules.TestSlice.test_slice` — фактические bindings по переменным `x`, `y`, `z` не совпадают с ожидаемыми `TermId`.

### ZooKeeper lock при очистке — 2 errors

- **Error:** `com.bigdata.rdf.rules.TestModelsEqual.test_modelsEqual` — XML stack проходит через `AbstractTripleStore.__tearDownUnitTest()` → `destroy()` → `acquireExclusiveLock()` → `ZooResourceLockService.acquireLock()` → `ZooKeeperAccessor.AwaitConnectedTask.call()`; сообщение — `test108`.
- **Error:** `com.bigdata.rdf.rules.TestCompareFullAndFastClosure.test_compareEntailments` — тот же путь очистки; сообщение — `test155`.

Для этих двух случаев XML не содержит вложенного exception/cause ниже `AwaitConnectedTask.call()`. Точная причина ошибки получения ZooKeeper connection неизвестна; приписывать её недоступности сервиса или конкретному сетевому событию по этому артефакту нельзя.

### Неподдерживаемая операция full-text index — 1 error

- **Error:** `com.bigdata.rdf.lexicon.TestFullTextIndex.test_rebuildIndex` — `UnsupportedOperationException` из `ClientIndexView.getResourceMetadata()`.

## Сводка причин

Числа по группам дают ровно 50: 5 отсутствующих vocabulary/axioms после reopen, 13 ошибок сериализации, 11 ошибок приведения индекса, 10 ошибок RDF loading, 8 несовпадений результатов, 2 ошибки ZK lock при cleanup и 1 `UnsupportedOperationException`. Для оставшихся 45 случаев XML показывает перечисленные deepest causes/assertions, но этот отчёт не устанавливает более глубокие причины и не предлагает исправления. Кроме явно неизвестных nested causes для двух cleanup errors, причина двух `NullPointerException` при загрузке также не детализирована в XML.
