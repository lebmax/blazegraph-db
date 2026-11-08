# Восстановление HA и scale-out на Java 8

Исходники найдены в официальной истории `blazegraph/database`: последняя ревизия
перед удалением enterprise-модулей —
[`e755674414edd08296ac2ce8bdebe8d6e1ae40b6`](https://github.com/blazegraph/database/tree/e755674414edd08296ac2ce8bdebe8d6e1ae40b6).
Коммит
[`3fac76dce122650d3aba90c1a58df473889f7395`](https://github.com/blazegraph/database/commit/3fac76dce122650d3aba90c1a58df473889f7395)
удалил их при переносе enterprise-функций в отдельный репозиторий. Продолжение
этого отдельного репозитория не найдено. Восстановлена доступная версия
`1.6.0-master-SNAPSHOT` с адаптацией к текущему ядру `2.1.6-SNAPSHOT`.

Восстановлены `bigdata-zookeeper`, `bigdata-jini`, `bigdata-ha` и соответствующие
три тестовых модуля. Jini используется через Apache River; имена пакетов
`net.jini` и Maven-координаты библиотеки сохранены. Исходные GPLv2-заголовки
сохранены. [Манифест](cluster-upstream.json) содержит SHA-256 **исходных** 363 файлов
до локальных адаптаций; он не является контрольной суммой адаптированного checkout.

[Apache River](https://river.apache.org/) реализует Jini; официальный сайт также
сообщает о завершении проекта и переводе в Attic. Здесь сохранена совместимая
версия River `2.2.1`. Выбор дальнейшей поддержки или замены зависимости относится
к следующему этапу развития, а не к восстановлению исходного стенда.

## Сборка и окружение

Модули включаются только профилем `cluster-integration`. Его следует указывать
вместе с `Development`, поскольку явный профиль отключает Maven-профиль,
активный по умолчанию:

```powershell
$env:JAVA_HOME = 'C:\Users\Axiom\.jdks\axiomjdk-1.8.0_482'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
mvn '-PDevelopment,cluster-integration' -pl 'bigdata-ha-test,bigdata-zookeeper-test' -am -DskipTests install
```

Приложения Blazegraph, River lookup и тестовые JVM запускаются на Windows с
Axiom JDK `1.8.0_482-b10`. ZooKeeper `3.4.5` работает в трёх Docker-контейнерах с
инфраструктурным Temurin JRE 8. Этот контейнерный JRE не является Axiom JDK.
Клиентские адреса: `127.0.0.1:12181`, `127.0.0.1:12182`, `127.0.0.1:12183`.

[Инструкция стенда](../../integration/cluster/README.md) описывает подготовку
ZooKeeper. Для полного запуска:

```powershell
./integration/cluster/Run-ClusterTests.ps1 -TestSet All
```

Runner последовательно собирает текущие зависимости, запускает отдельную JVM
River и выбирает suite entry points без повторного включения вложенных
Jini-агрегатов. XML, логи и версия JVM сохраняются вне `target`, по умолчанию в
соседнем каталоге `blazegraph-artifacts/cluster`. Ошибка одного набора не отменяет
запуск последующих. Автоматические повторы отключены. River завершается через
сохранённый процесс; ZooKeeper остаётся доступен до явного scoped `compose stop`
или `compose down`.

## Адаптации

- POM используют текущий parent и WAR, версии River разрешаются и после `install`.
  Старые Ant-команды автоматического запуска и остановки общих сервисов удалены.
- Один connect string ансамбля передаётся клиенту теста и дочерним HA JVM.
  Проверка доступности использует явно заданный loopback-адрес.
- Surefire двух модулей, запускающих JVM, предоставляет полный test classpath.
  Корневая настройка Surefire для существующих модулей сохранена.
- В локальном runner выбран unicast discovery. В первом HA запуске `jstack`
  зафиксировал задержку на `MulticastSocket.setNetworkInterface` внутри River;
  серверы B/C не успевали зарегистрироваться за 20 s. Параметр `test.jini.groups`
  позволяет задать multicast-группы отдельно; без него сохранены исходные группы.
- Windows hard kill использует сохранённый `Process` в родительском тесте.
  Сериализуемый replication callback завершает конкретный PID через `taskkill`.
  Защита от повторного срабатывания не позволяет завершать тот же PID при каждом
  чтении частей одного replicated payload. Локальный regression test воспроизвёл
  два вызова вместо одного до исправления и проходит после него.
  Unix-сигналы сохранены; недоступный на Windows SIGQUIT не считается успешным.
- Настроен запуск/остановка только Docker-проекта `blazegraph-e0` для сценариев
  отказа ZooKeeper. Healthcheck требует роль leader/follower, одного `ruok`
  недостаточно для подтверждения quorum.
- `JiniServiceConfiguration` корректно записывает пустой список discovery groups.
  Раньше обращение к последнему элементу пустого массива давало
  `ArrayIndexOutOfBoundsException`. Проверены null, пустой и непустой списки через
  чтение сгенерированной River configuration.
- `JiniFederation.destroy()` сначала уничтожает удалённые сервисы и собственный
  zroot, затем закрывает discovery и ZooKeeper. Исходный порядок закрывал их
  первым и приводил к `NullPointerException`. Регрессия проверяет удаление
  регистрации DataServer и zroot, отключение клиента и повторный `destroy()`.
- При restart A/B/C старый listener остаётся доступен до остановки старого
  процесса; новый listener устанавливается непосредственно перед новым start.
- `HAJournal.computeHALogDigest()` закрывает reader в `finally`. Исходный
  метод оставлял открытые HALog handles после вычисления digest; на Windows
  последующее удаление этих файлов не выполнялось. Падение `HALog exists`
  воспроизвелось и после исправления URI, до добавления `close()`.
- `DumpLogDigests.HALogInfo.compareTo()` возвращает `0` для равных commit
  counters. Исходный comparator запрещал равенство assertion и возвращал `1`;
  `ConcurrentSkipListSet` при допустимом сравнении равных ключей завершался с
  `AssertionError`. Регрессия проверяет порядок и устранение дубликатов counters;
  до исправления падает с `-ea`, после `Long.compare` проходит.
- URI для HA `LOAD` строится через `File.toURI().toASCIIString()`. Исходная
  склейка `"file:" + getAbsolutePath()` создавала на Windows URI с обратными
  слешами. Две локальные проверки воспроизвели `URISyntaxException` до исправления
  и подтвердили round trip через `URI`/`File` и разбор SPARQL после него; охвачены
  существующий ресурс и имя с пробелом, `#` и Unicode.
- В HTTP-клиенте найден отдельный дефект: успешный SPARQL UPDATE оставлял тело
  ответа непрочитанным и мог исчерпать пул соединений. Исправление
  `af01261` читает ответ и освобождает его во всех ветках. Два локальных HTTP-теста
  проверяют последовательные UPDATE при пуле из одного соединения и восстановление
  после HTTP 500. Проверка HA status также освобождает ответ и ограничивает ожидание.

## Объём проверок

[Инвентарь](cluster-test-inventory.md) отделяет штатные агрегаты, standalone RDF
suite, ручные launcher-тесты и исходно отключённые known-bad сценарии. Через
`suite().countTestCases()` на Axiom JDK 8u482 подтверждены текущие размеры:
ZooKeeper 18, quorum 23, Jini 19, RDF scale-out 261, HA 154. Дополнительные
четыре проверки `TestServiceConfiguration` выбираются runner отдельно.

Первичные проверки: все шесть модулей собираются, локальные HA index tests
проходят 10/10. После настройки discovery проходит
`TestHA3JournalServer.testStartABC_restartC`: три отдельных journal JVM,
пять транзакций, сравнение commit counters и повторное включение follower C.
Это отдельный smoke-сценарий, не результат полного HA-агрегата.

## Подтверждённые результаты

Все приведённые test JVM используют `C:\Users\Axiom\.jdks\axiomjdk-1.8.0_482\jre`;
версия — Axiom JSC `1.8.0_482-b10`. Числа из XML — наблюдаемые результаты,
P≈1,00. Они не означают подтверждение всех режимов и всех нагрузок.

| Набор | Tests | Failures | Errors | Retry events |
| --- | ---: | ---: | ---: | ---: |
| ZooKeeper и quorum | 41 | 0 | 0 | 0 |
| Jini и генерация configuration | 23 | 0 | 0 | 0 |
| RDF scale-out | 261 | 9 | 41 | 0 |
| Штатный WebApp aggregate после HTTP-исправления | 737 | 0 | 0 | 0 |
| HA: restart A и snapshot stress/restore | 2 | 0 | 0 | 0 |
| Полный исходный HA aggregate до исправлений URI/digest/comparator | 150 | 22 | 17 | 0 |
| Три HA helper regressions через Maven | 3 | 0 | 0 | 0 |
| Адресный HA после URI/kill/reader fixes, до comparator fix | 42 | 5 | 2 | 0 |
| Финальный HA recheck: пять падений и три comparator cases | 8 | 5 | 0 | 0 |

Дополнительно standalone JUnit-запуски подтверждают четыре локальные регрессии:
одна проверка однократного kill trigger и две проверки file URI. До исправлений
они воспроизводили соответственно лишний kill и два `URISyntaxException`, после
исправлений проходят. Четвёртая проверяет comparator `HALogInfo`. Эти запуски
не поднимают HA services.

Артефакты находятся вне `target`, под
`C:\Users\Axiom\OpenideProjects\blazegraph-artifacts\cluster`:

- `20261108-smoke-1/zookeeper` — 41 ZooKeeper/quorum test case;
- `20261108-jini-destroy-green/scaleout` — 23 Jini/configuration test cases;
- `20261108-rdf-destroy-green/scaleout-rdf` — 261 RDF test case; слово `green`
  в имени каталога относится к устранённому destroy blocker, набор остаётся красным;
- `20261108-webapp-client-verify` — 737 WebApp test cases;
- `20261108-ha-restart-stress-fixed/ha` — два адресных HA-сценария;
- `20261108-kill-trigger-red`, `20261108-kill-trigger-green` — kill regression;
- `20261108-ha-file-uri-red`, `20261108-ha-file-uri-green` — URI regressions;
- `20261108-ha-helper-maven` — три helper cases через Maven;
- `20261108-dump-comparator-red`, `20261108-dump-comparator-green` — comparator.
- `20261108-ha-affected-after-digest-close` — адресные 42 случая;
- `20261108-ha-final-recheck` — финальные восемь случаев и проверка cleanup runner.

[Все 50 оставшихся RDF-сбоев](cluster-failures-after-restoration.md) перечислены
по именам и наблюдаемым причинам. Первый RDF-запуск получал 172 errors из-за
общего `JiniFederation.destroy()`; устранение этого blocker позволило увидеть
отдельные ошибки сохранения metadata, RMI serialization, типов индексов,
загрузки, результатов запросов и cleanup. Для части случаев более глубокая
причина пока неизвестна.

Первый полный HA-запуск `20261108-full-1` остановлен после сохранения thread dumps:
ожидание HTTP status блокировало продолжение. Он **не является завершённым
прогоном**. Независимый HTTP regression воспроизвёл исчерпание соединения до
`af01261`; после исправления прошли WebApp aggregate и два указанных HA-сценария.

Полный HA aggregate `20261108-ha-full-2` завершён: 150 случаев, 22 failures,
17 errors, 0 skipped и 0 retry events. Он стартовал до добавления новых unit cases
и исправлений file URI, kill trigger, закрытия digest reader и comparator.
[Все 39 неуспешных случаев](cluster-ha-failures-after-restoration.md) отделены от
результатов последующих исправлений. Сохранены XML и 2 064 диагностических файла
из service directories.

Адресный `20261108-ha-uri-kill-affected` использовал некорректный для Surefire 2.18.1
селектор: смесь whole-class и method entries, а также повторные записи одного
класса. Он выполнил только шесть случаев: два прошли, один воспроизвёл
`HALog exists`, три дали `NullPointerException` в JUnit4Provider. Эти provider
errors не считаются новыми продуктовыми сбоями или проверкой всех целевых методов.
Корректный селектор группирует методы как `Class#methodOne+methodTwo` и использует
`Class#*` для всего класса. Его фактический охват проверяется по XML.

Адресный `20261108-ha-affected-after-digest-close` выполнил все 39 ранее неуспешных
сценариев и три unit cases: 35 прошли, остались пять failures и два comparator
errors. Он включал исправления URI, kill trigger и закрытия reader, но стартовал
до исправления comparator. Все семь `TestHA3JustKills` прошли; `TestHA3JournalServer`
дал 20 успешных случаев из 25, включая большинство проверок удаления HALog.

В `20261108-ha-final-recheck` после исправления comparator прошли оба интеграционных
`TestHA3DumpLogs` и новый `TestDumpLogDigests`: 3/3. Те же пять оставшихся HA-случаев
снова упали; автоматически прошедших после повтора среди них нет. Итог этого
запуска — восемь случаев, пять failures, ноль errors/skips/retry events.

Таким образом, из 39 исходных неуспешных HA-сценариев 34 прошли после изменений,
пять остаются открытыми. Дополнительно прошли четыре новые локальные регрессии.
Это поэтапная проверка: полный агрегат запускался на 150 исходных случаях,
после изменений выполнялись адресные наборы; текущий состав агрегата — 154 случая.

## Оставшиеся HA-сбои

Все пять находятся в `com.bigdata.journal.jini.ha.TestHA3JournalServer`:

- `testStartAB_C_MultiTransactionResync_5tx_then_50ms_delay` — `HALog exists`,
  commit counter 1 на follower B; повтор воспроизвёл сбой. Закрытие digest reader
  устранило остальные аналогичные исходные падения, но не объясняет этот остаток.
- `testABC_LiveLoadRemainsMet_restart_C_fullyMetDuringLOAD`;
- `testABC_LiveLoadRemainsMet_restart_B_fullyMetDuringLOAD`;
- `testABC_LiveLoadRemainsMet_restart_B_fullyMetDuringLOAD_restartC_fullyMetDuringLOAD`;
- `testABC_LiveLoadRemainsMet_restart_C_fullyMetDuringLOAD_restart_B_fullyMetDuringLOAD`.

В последних четырёх не проходит `assertTrue(awaitFullyMetDuringLOAD2(...))`:
helper наблюдает завершённый Future раньше полного quorum. В адресном логе для
каждой соответствующей LOAD-запросу записи есть HTTP 200. Эти наблюдения дают
основание исследовать взаимное время завершения LOAD и rejoin (**P≈0,95**), но
не доказывают дефект репликации или только недостаточную длительность нагрузки.
Точный механизм пока неизвестен. Для HALog-сценария нужны отдельные проверки
жизненного цикла reader и асинхронного purge; повтор не позволяет списать его
на единичный сбой окружения.

Ни один результат после изменения кода не считается автоматическим повтором
того же неизменённого теста. Автоматические повторы в кластерном профиле выключены;
отдельные запуски и диагностические остановки перечислены явно.

## Ограничения и следующий шаг

Четыре исходных known-bad test bodies возвращаются сразу и входят в счётчики
JUnit как прошедшие; HA5 и group commit suites исходно отключены. Это перечислено
в [инвентаре](cluster-test-inventory.md). Восстановление запуска не закрывает E0
до разбора оставшихся ошибок и согласования покрытия этих режимов.

Следующим отдельным шагом для HA следует разобрать оставшийся HALog purge и
четыре ожидания quorum во время LOAD. Для RDF scale-out следует воспроизвести сохранение
vocabulary/axioms через reconnect и проверить RMI serialization задач. Остальные
группы требуют разделить дефекты распределённого выполнения и неподходящие для
этого режима предположения общих тестов. Их причины нельзя установить только
по счётчикам ошибок.

Старый полный baseline и рабочие файлы отчёта E0, существовавшие до восстановления,
сохранены без изменений. Полный штатный реактор без кластерного профиля здесь не
запускался заново; проверен затронутый WebApp aggregate. Восстановленные кластерные
агрегаты не входили в прежний штатный reactor, поэтому их обнаруженные сбои сами
по себе не доказывают регресс относительно прежнего baseline.

## Проверки файлов и runner

Все 363 файла исходного снимка сверены с upstream-манифестом; адаптированы 32.
Разница сохранена в `20261108-restoration-final/upstream-adaptations.diff` рядом
с артефактами. Сравнение адаптаций с upstream через `git diff --check` не выявило
whitespace errors. Историческое форматирование неизменённых upstream-файлов
массово не переписывалось.

Восемь POM разобраны как XML, PowerShell script проходит parser, локальные ссылки
пяти новых Markdown-документов разрешаются. `.sh` закреплены как LF. Runner
проверен на отказ для непустого evidence directory и пути внутри `target`, а
также на отказ второй копии при занятом mutex. `caller-environment.json` финального
запуска подтверждает восстановление `JAVA_HOME`, `PATH`, `CLASSPATH`, текущего
каталога и освобождение mutex после Maven failure. ZooKeeper остаётся запущенным
согласно контракту runner.
