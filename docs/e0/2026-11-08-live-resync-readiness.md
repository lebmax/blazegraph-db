# Готовность C после LiveResync

## Причина падения

`TestHA3JournalServer.testStartAB_C_LiveResync` повторно воспроизвёл падение
в `assertReady()` на ревизии `b736a25`. После добавления диагностического
сообщения assertion указал `Service serverC is NotReady`.

Последовательность в
[`doCastLeadersVoteAndServiceJoin()`](../../bigdata-ha/src/main/java/com/bigdata/journal/jini/ha/HAJournalServer.java):

1. C публикует `serviceJoin()` в ZooKeeper.
2. Тестовый `awaitFullyMetQuorum()` наблюдает три joined-сервиса и возвращается.
3. C ещё ожидает, пока лидер подтвердит service join и передаст release time.
4. После ответа лидера C вызывает `journal.setQuorumToken(token)` и получает
   локальные `HAReady` и `HAStatus=Follower`.

Завершение LOAD на лидере не является барьером для шага 4. Если Future уже
завершён, `ft.get()` возвращается сразу, и прежний `assertReady()` может
прочитать `NotReady` между шагами 2 и 4. Это гонка синхронизации теста
с допустимым переходом готовности сервиса (**P≈0,99**).

В диагностическом запуске C наблюдает свой service join в `13:04:03,124`,
а ответ лидера и `HAReady=0, HAStatus=Follower` — в `13:04:03,132`. В XML
того же запуска зафиксирован ответ `serverC is NotReady` при проверке.
Последующие переходы A в `Error/NotReady` в старых логах происходили после
начала остановки сервисов; они не устанавливают причину этого assertion.

## Исправление

После ожидания полного quorum тест вызывает существующий `awaitHAStatus()`
с ожидаемыми ролями `Leader, Follower, Follower` для A/B/C. Аналогичный барьер
уже используется в соседних MultiTransactionResync-сценариях. Бюджет этого
helper — 5 с; постоянно неготовый сервис или неверная роль приводят к падению.

Сохраняются `assertReady()`, сравнение бинарных digest журналов, следующая
транзакция и проверка удаления HALog. В сообщение `assertReady()` добавлены
имя сервиса и прочитанный статус без дополнительного RPC. Порядок service
join и публикации готовности в сервере не меняется: ожидание ответа лидера
перед `setQuorumToken()` необходимо для протокола согласования release time.

## Проверка на Axiom JDK 8u482

Использована Axiom JSC `1.8.0_482-b10` из
`C:\Users\Axiom\.jdks\axiomjdk-1.8.0_482` на Windows. Модуль `bigdata-ha-test`
пересобран и установлен Maven с профилями `Development,cluster-integration`.
Проверки выполняются штатным `integration/cluster/Run-ClusterTests.ps1`;
автоматические повторы в кластерном профиле выключены.

| Артефакты запуска | Tests | Failures | Errors | Retry events |
| --- | ---: | ---: | ---: | ---: |
| `20261108-live-resync-red-1`: исходный сценарий | 1 | 1 | 0 | 0 |
| `20261108-live-resync-diagnostic`: только сообщение с именем сервиса | 1 | 1 | 0 | 0 |
| `20261108-live-resync-green-1`: после барьера готовности | 1 | 0 | 0 | 0 |
| `20261108-live-resync-family-green`: расширенный адресный набор | 16 | 0 | 0 | 0 |

В расширенный набор входят `LiveResync`, шесть вариантов
`MultiTransactionResync`, `testStartAB_C_Resync`, `testStartAB_C_Rebuild`,
`testStartAB_C`, `testStartABC_restartC` и пять `TestHALogPurge` cases.
Итого после исправления выполнено 17 запусков 16 различных случаев;
`LiveResync` прошёл дважды, отдельно и в наборе. В обоих положительных
запусках ноль skipped и retry events. Состав и версия JDK проверены по свежим
Surefire XML.

XML, Maven logs и версии JDK сохранены вне `target`, под
`C:\Users\Axiom\OpenideProjects\blazegraph-artifacts\cluster`.
Для двух отрицательных запусков там же сохранены service logs A/B/C.

Полный HA aggregate в рамках этого изменения не запускается. Четыре
ранее обнаруженных падения `awaitFullyMetDuringLOAD2()` из
[отчёта восстановления](2026-11-08-cluster-restoration.md) этим изменением
не считаются исправленными. Исходно отключённые known-bad suites не включаются.

Последующий [разбор rejoin во время LOAD](2026-11-08-load-rejoin-synchronization.md)
содержит результаты отдельного исправления четырёх оставшихся сценариев.
