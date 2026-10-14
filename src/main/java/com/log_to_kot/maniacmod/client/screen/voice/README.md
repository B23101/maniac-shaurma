# client/screen/voice — голосові меню в стилі мода (заміна меню Simple Voice Chat)

Штатні екрани SVC підміняються екранами мода. Усі налаштування ті самі й пишуться в ТІ САМІ конфіги SVC
(`voicechat-client.properties`, `voicechat-volumes.properties`) — нічого не губиться і не дублюється.

## Що підміняється
| Штатний екран SVC | Екран мода |
|---|---|
| `VoiceChatScreen` (клавіша V) | `ManiacVoiceMenuScreen` |
| `VoiceChatSettingsScreen` | `ManiacVoiceSettingsScreen` |
| `SelectMicrophoneScreen` / `SelectSpeakerScreen` | `ManiacVoiceDeviceScreen` |
| `AdjustVolumesScreen` | `ManiacVoiceVolumesScreen` |

Екрани груп (`GroupScreen`, `JoinGroupScreen`, `CreateGroupScreen`, `EnterPasswordScreen`) лишаються штатними.
Кожен екран SVC, що лишився штатним, один раз пишеться в лог: `[maniacmod] SVC screen left vanilla ...`.

## Файли
| Файл | Роль |
|---|---|
| `ManiacVoiceInterceptor` | `ScreenEvent.Opening` → підміна за ІМЕНЕМ класу (без посилань на типи SVC) |
| `SvcBridge` | **єдине** місце, що торкається внутрішніх класів SVC; ламається після оновлення SVC — чиниться тут |
| `VoiceScreenBase` | фон `HorrorBackdrop`, панель, анімація появи/закриття, каскад рядків, прокрутка, футер |
| `VoiceWidgets` | кнопка, перемикач, цикл-кнопка, повзунок, картка пристрою, рядок гучності |
| `ManiacVoice*Screen` | самі екрани |

## SVC опційний
Без SVC перехоплювач нічого не робить, а `SvcBridge` і екрани не вантажаться. SVC — `compileOnly`
(`libs/voicechat-forge-1.20.1.jar`), у jar мода не потрапляє.

## Тест мікрофона
Використовується ШТАТНА логіка SVC: його `MicTestButton` створюється прихованим (`visible = false`), а його
`render` викликається щокадру — це «тримає живим» потік тесту. Малюємо рівень сигналу самі.

## Тексти
Власні ключі `maniacvoice.*` лежать в окремому неймспейсі `assets/maniacvoice/lang/` — так не чіпаємо
основні `uk_ua.json`/`en_us.json`. Назви режимів («голосом»/PTT, тип аудіо) беруться з перекладу самого SVC.

## Вимкнення
`-Dmaniacmod.voiceUi=false` — повертає штатні меню SVC без перезбірки.

## Нове налаштування
Новий рядок у `build()` потрібного екрана + (якщо це новий конфіг-параметр SVC) метод у `SvcBridge`
+ два ключі `maniacvoice.*` в обох lang.
