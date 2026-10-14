# client/screen/browse — екран модів (Forge ModListScreen)

Сервери, світи й форми сервера оформлює `client/screen/menu` (мікси зареєстровані в `maniacmod.menu.mixins.json`) (`ManiacMenuTheme`, `ManiacMenuCards`
+ мікси `*Maniac`). Цей пакет стилізує лише Forge `ModListScreen` і навмисно НЕ чіпає інші екрани —
інакше обидва набори малювали б одне й те саме поверх одного.

Мікси (`mixin/browse`, конфіг `maniacmod.browse.mixins.json`, `required: false`):
фон (`Screen.renderBackground`), кнопки (`AbstractButton.renderWidget`), рамка пошуку (`EditBox`),
список (`AbstractSelectionList`: без «землі», червона рамка), рядок мода (`ModEntry`),
панель інформації (`ScrollPanel.drawBackground`). Усе гейтиться `ManiacBrowseStyle.isStyled`.
