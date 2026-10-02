# Проверка ModelMirage 0.3.0

Среда: Minecraft 26.2, Fabric Loader 0.19.5, Fabric API 0.161.0+26.2,
OpenJDK 25, Linux/Xvfb/llvmpipe. Проверено 2026-10-02.

## Сборка и состав JAR

- `./gradlew build --no-daemon`: успешно. Автоматических unit tests в моде нет (`test NO-SOURCE`).
- Проверен состав `ModelMirage-0.3.0.jar`: GUI shaders/fonts, HUD, модельный загрузчик и notices включены.
- Остались четыре HUD-модуля: Crosshair, Interface, NoRender (только scoreboard/boss bar), BoyKisser.
- Mixins: три исходных для runtime-моделей и KeyboardVisualMixin для клавиш HUD.
- Удалены world/player/effect renderers, шейдеры рук и предметов, связанные mixins и ресурсы.
- MusicService, music-bridge, MediaPlayerInfo и вложенных библиотек в релизе нет.
- Blood Axe Mace JSON/PNG загружаются из каталога и не включены в JAR.
- `git diff --check`: успешно.

## Проверка в Minecraft

Скриншоты сделаны установленным Minecraft Mod MCP 0.4.1 через
`screenshot_to_file`. Его input hook не управляет GUI; нажатия и клики
выполнялись через `xdotool` в отдельном тестовом клиенте.

| Сценарий | Результат |
| --- | --- |
| Right Shift | Открывается нативное меню с разделами Модели / Применённые / HUD |
| Blood Axe Mace на diamond sword | Выбор и preview восстановлены, модель отображается в руке и hotbar |
| Сброс / Применить в новом меню | Запись для diamond sword удаляется и восстанавливается; подмена mace сохраняется |
| Runtime loader | В логе 948 faces, без нового resource reload |
| HUD → модуль → переключатели панелей | Состояния меняются, настройки автоматически сохраняются |
| Панели в мире | Реальные FPS/ping, координаты, инвентарь, броня и список активных модулей |
| Редактор HUD | Панели выбираются и перетаскиваются; положение записано в hud.json |
| Esc из редактора HUD | Возврат в прежнее меню |
| Оформление → анимации, масштаб | Значения меняются, записаны в appearance.json; Esc возвращает в меню |
| Полный перезапуск клиента | HUD-настройки и подмена модели восстановлены |

![Выбор модели](screenshots/model-selector.png)

![HUD](screenshots/hud.png)

## Ограничения проверки

В этой проверке не создавались отдельные multiplayer-серверы. Boss bar /
scoreboard suppression и параметры прицела проверены сборкой и подключением
Fabric HUD registry, без отдельных игровых сценариев. Проверки загрузчика
каталога (checksum, generated items, offline fallback, F3+T) выполнены при
подготовке 0.2.0; его код в 0.3.0 не менялся.

При закрытии тестового клиента Minecraft Mod MCP 0.4.1 оставляет
HTTP-Dispatcher и pool threads; shutdown watchdog создаёт crash report.
В его JAR обнаружен McpHttpServer, использующий JDK HttpServer; связь
зависания с MCP следует из оставшихся потоков. MCP не входит в релиз.
Предупреждения Realms/authentication, OpenAL/flite и Xvfb cursor относятся
к среде разработки. Серый overlay в верхнем углу создаёт dev-мод MCP.

## Повторная проверка агентом

1. `./scripts/setup-mcp.sh` и `./gradlew runClient`.
2. Открыть отдельный тестовый мир с cheats, выдать `mace` и `diamond_sword`.
3. Right Shift → Модели → выбрать предмет → Blood Axe Mace → preview → Применить / Сброс.
4. HUD → Панели HUD → включить панели; открыть Редактор HUD, перетащить панель и нажать Esc.
5. Проверить Оформление, сохранение и восстановление после перезапуска.
6. Сделать MCP screenshots, проверить `run/logs/latest.log` и отсутствие resource reload при выборе модели.
