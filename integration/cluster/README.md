# Изолированный кластер ZooKeeper 3.4.5

Здесь находится отдельный Docker-проект из трёх ZooKeeper-узлов. У него свой Maven POM, выделенная bridge-сеть и три отдельных именованных тома. Для сборки инфраструктурного образа корневой Maven-проект не используется.

## Где запускается Java

Контейнеры ZooKeeper используют JRE Eclipse Temurin 8 только для инфраструктурного сервиса. Это не Axiom JDK.

Blazegraph и River запускаются на Windows с установленным Axiom JDK 8u482. Контейнеры доступны приложению по строке подключения:

~~~text
127.0.0.1:12181,127.0.0.1:12182,127.0.0.1:12183
~~~

## Подготовить библиотеки

Из корня репозитория выполните отдельный POM. Maven скачает ZooKeeper 3.4.5 и его транзитивные runtime-зависимости из настроенного Maven-репозитория и положит JAR-файлы в integration/cluster/target/lib.

~~~powershell
mvn -f integration/cluster/pom.xml org.apache.maven.plugins:maven-dependency-plugin:2.10:copy-dependencies
~~~

Зависимости копируются только для ZooKeeper 3.4.5. Связей с модулями Blazegraph нет. POM исключает три недоступные в Maven Central legacy-зависимости Log4j: javax.jms:jms, com.sun.jdmk:jmxtools и com.sun.jmx:jmxri. Плагин также не копирует junit: jline 0.9.94 ошибочно помечает эту тестовую библиотеку как compile-зависимость. Библиотека Log4j и её ConsoleAppender остаются в classpath. Каталог target исключён локальным .gitignore этой папки.

## Проверить и запустить

Сначала проверьте итоговую Compose-конфигурацию:

~~~powershell
docker compose --project-name blazegraph-e0 --file integration/cluster/compose.yaml config
~~~

Затем соберите образ и запустите только проект blazegraph-e0:

~~~powershell
docker compose --project-name blazegraph-e0 --file integration/cluster/compose.yaml up --build --detach
~~~

Порты клиентов опубликованы только на 127.0.0.1: 12181, 12182 и 12183. Порты связи узлов 2888 и выбора лидера 3888 наружу не публикуются. Для доступности опубликованных loopback-портов используется отдельная bridge-сеть проекта без `internal: true`; поэтому контейнерам доступен исходящий сетевой трафик. Именованные тома будут называться blazegraph-e0_zk1-data, blazegraph-e0_zk2-data и blazegraph-e0_zk3-data.

Перед запуском JVM entrypoint каждого узла ждёт регистрации DNS-имён всех трёх Compose-сервисов. ZooKeeper разрешает адреса `server.x` при разборе `zoo.cfg`, поэтому эта задержка нужна, чтобы JVM не сохранила временный результат раннего DNS-запроса.

## Проверка узлов и клиентской сессии

Проверить состояние контейнеров и результат healthcheck:

~~~powershell
docker compose --project-name blazegraph-e0 --file integration/cluster/compose.yaml ps
~~~

Healthcheck отправляет 4LW-команду `stat` и требует ответ `Mode: leader` или `Mode: follower`. Если узел ещё находится в `LOOKING` или `stat` не вернул роль, контейнер остаётся unhealthy. Это проверяет участие узла в quorum; ответ `ruok`/`imok` подтверждал бы только работу процесса и мог давать false healthy до выбора лидера.

Для проверки клиентского подключения из Windows можно запустить встроенную консоль ZooKeeper на Axiom JDK:

~~~powershell
& 'C:\Users\Axiom\.jdks\axiomjdk-1.8.0_482\bin\java.exe' -cp 'integration\cluster\target\lib\*' org.apache.zookeeper.ZooKeeperMain -server '127.0.0.1:12181,127.0.0.1:12182,127.0.0.1:12183'
~~~

После появления приглашения консоли выполните quit. Для приложений используйте ту же строку из трёх адресов.

## Собрать логи

Вывести логи трёх узлов в терминал:

~~~powershell
docker compose --project-name blazegraph-e0 --file integration/cluster/compose.yaml logs --no-color --tail=200 zk1 zk2 zk3
~~~

Сохранить текущие логи в игнорируемый каталог target:

~~~powershell
docker compose --project-name blazegraph-e0 --file integration/cluster/compose.yaml logs --no-color --no-log-prefix > integration\cluster\target\zookeeper.log 2>&1
~~~

## Остановить только этот проект

Приостановить только контейнеры этого проекта, сохранив их:

~~~powershell
docker compose --project-name blazegraph-e0 --file integration/cluster/compose.yaml stop
~~~

Возобновить их можно командой `docker compose --project-name blazegraph-e0 --file integration/cluster/compose.yaml start`. Если нужно удалить только контейнеры и сеть этого проекта, используйте `down`; именованные тома сохранятся. Не добавляйте `--volumes`, если нужно оставить данные ZooKeeper.

## Версия инфраструктурного образа

Dockerfile использует официальный eclipse-temurin:8-jre-jammy с зафиксированным manifest digest для linux/amd64. Локальные сведения о манифесте и журналы проверки находятся в игнорируемом каталоге recovery. Приложения Blazegraph и River этот образ не используют.

## Запустить тесты Blazegraph на Axiom JDK

После подготовки JAR-файлов и сборки ZooKeeper-образа выполните из корня репозитория:

~~~powershell
./integration/cluster/Run-ClusterTests.ps1 -TestSet All
~~~

Runner сначала выполняет `install -DskipTests` для текущих модулей и зависимостей.
Затем поднимает JVM River lookup/ClassServer на Axiom JDK и последовательно запускает
ZooKeeper/quorum, Jini, RDF scale-out и HA. `-TestSet Smoke` ограничивает HA одним
сценарием с тремя journal JVM и restart follower C; варианты `ZooKeeper`, `ScaleOut`,
`HA` выбирают полные соответствующие наборы, `Jini` — агрегат и проверки генерации
конфигурации без RDF suite, `HAProbe` — только этот HA smoke.
`-TestSet RDF` запускает только RDF scale-out suite. Для адресного HA-запуска
используйте, например:

~~~powershell
./integration/cluster/Run-ClusterTests.ps1 -TestSet HA -HATest 'com.bigdata.journal.jini.ha.TestHAJournalServer#testRestartA'
~~~

В Surefire 2.18.1 несколько методов одного класса нужно объединять в одной
записи: `Class#methodOne+methodTwo`. При смешивании классов и методов указывайте
`Class#*` для всего класса. Смесь записей с `#` и без `#` в этой версии приводит
к `NullPointerException` в JUnit4Provider; итоговое число случаев проверяйте по XML.

По умолчанию используется Axiom JDK `C:\Users\Axiom\.jdks\axiomjdk-1.8.0_482`.
Параметры `-JavaHome`, `-Maven`, `-EvidenceDirectory` меняют пути. Каталог evidence
должен быть новым или пустым и находиться вне каталогов `target`: повторное
использование результатов отвергается. `-SkipBuild`
допустим после успешной подготовки тех же исходников через `install`.
Две копии runner одновременно запускать нельзя: у стенда общие порты и ZooKeeper.
Именованный mutex блокирует второй runner в той же Windows-сессии; посторонние
launcher-процессы этот mutex не используют. После завершения runner восстанавливает
исходные `JAVA_HOME`, `PATH` и `CLASSPATH` вызывающего PowerShell.

Логи, XML и `phases.json` сохраняются вне `target`, в соседний с репозиторием каталог
`blazegraph-artifacts/cluster`. River завершается в `finally`; ZooKeeper остаётся
запущенным. Runner откажется занимать уже используемые порты River 4160/23333.
Отсутствие XML, JUnit skips и записи повторов делают фазу неуспешной. Исходные
known-bad методы с ранним `return` перечислены в отчёте отдельно.
Для локальных тестов используется unicast discovery через `jini://localhost/`;
исходные настройки multicast сохранены в конфигурациях как значения по умолчанию.

Происхождение восстановленных модулей и ограничения покрытия описаны в
[отчёте E0](../../docs/e0/2026-11-08-cluster-restoration.md).
