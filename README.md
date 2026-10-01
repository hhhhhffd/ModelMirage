# ModelMirage

Клиентский Fabric-мод для Minecraft **26.2**, JDK **25**. Выбираешь предмет, затем модель из GitHub-каталога. Подмена действует на все экземпляры выбранного типа предмета только в твоём клиенте; сервер получает обычные предметы.

## Использование

1. Установи ModelMirage и Fabric API для 26.2.
2. В мире нажми **Right Shift**.
3. Найди предмет по названию или ID, например `diamond_sword`.
4. Выбери модель. Мод скачает JSON и зависимые PNG, проверит размеры и SHA-256 и подготовит отдельный texture atlas.
5. Посмотри preview: перетаскивание вращает модель, колесо меняет масштаб. Нажми **Применить**. **Сброс** возвращает обычную модель предмета.

Загрузка проходит в фоне, без перезагрузки ресурспаков и её экрана. Выбор сохраняется в `config/modelmirage.json`. Проверенный каталог и файлы хранятся в `config/modelmirage/cache/`, поэтому ранее загруженные модели доступны без сети. Кнопка **Обновить** запрашивает каталог заново.

## Каталог

Официальный источник: [ModelMirage-catolog](https://github.com/hhhhhffd/ModelMirage-catolog). Сейчас в нём **Blood Axe Mace**. Модель и её текстура загружаются из каталога и не входят в JAR мода.

Поддерживаются Java item JSON: cuboid geometry, `minecraft:item/generated`, `minecraft:item/handheld`, display transforms, статические PNG и родительские модели. Собственные parents и текстуры перечисляются в записи каталога; vanilla parents доступны из ресурсов Minecraft. Блоки, armor, анимации PNG и сложные item definition graphs пока не поддерживаются. Формат и валидатор описаны в репозитории каталога.

Для своего источника передай JVM-параметр `-Dmodelmirage.catalogUrl=https://example.com/index.json`. HTTP разрешён только для localhost при разработке.

## Сборка

Нужен JDK 25:

```bash
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew build
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew runClient
```

Готовый JAR: `build/libs/ModelMirage-0.2.0.jar`.

## Проверка через Minecraft Mod MCP

Запусти `./scripts/setup-mcp.sh`: скрипт скачает проверенный JAR Minecraft Mod MCP 0.4.1 для Fabric 26.2 в `run/mods/`. Bridge для MCP-совместимых агентов описан в `.mcp.json`; ему нужен Node.js 20+. MCP не входит в релиз ModelMirage.

В текущей среде скриншоты через MCP работают, а команды нажатий и кликов не управляют GUI (`hook: false`). Для интерактивной проверки использованы Xvfb и `xdotool`.

Сценарии, результаты и скриншоты: [docs/TESTING.md](docs/TESTING.md).

## Права

Собственный код — MIT. GUI адаптирован из [AstolfoVisuals](https://github.com/SRS-0/AstolfoVisuals), включая его MSDF-шрифты. Использование этой работы и Blood Axe Mace разрешено владельцем проекта. Заимствования и исключения из MIT перечислены в [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
