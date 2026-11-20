package info.cemu.cemu.settings.general

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import info.cemu.cemu.common.settings.GamePadPosition
import info.cemu.cemu.common.ui.components.Button
import info.cemu.cemu.common.ui.components.Header
import info.cemu.cemu.common.ui.components.ScreenContent
import info.cemu.cemu.common.ui.components.SingleSelection
import info.cemu.cemu.common.ui.components.Toggle
import info.cemu.cemu.common.ui.localization.tr
import info.cemu.cemu.nativeinterface.NativeSettings

@Composable
fun GeneralSettingsScreen(
    navigateBack: () -> Unit,
    goToGamePathsSettings: () -> Unit,
    viewModel: GeneralSettingsViewModel = viewModel(),
) {
    val context = LocalContext.current

    val emulationSettings by viewModel.emulationSettings.collectAsState()
    val guiSettings by viewModel.guiSettings.collectAsState()

    ScreenContent(
        appBarText = tr("General settings"),
        navigateBack = navigateBack,
    ) {
        Button(
            label = tr("Add game path"),
            description = tr("Add the root directory of your game(s). It will scan all directories in it for games"),
            onClick = dropUnlessResumed { goToGamePathsSettings() },
        )
        SingleSelection(
            label = tr("Language"),
            choice = guiSettings.language,
            onChoiceChanged = { viewModel.setLanguage(language = it, context) },
            choiceToString = { viewModel.languageToDisplayNameMap[it] ?: it },
            choices = viewModel.languages,
        )
        SingleSelection(
            label = tr("Console language"),
            initialChoice = NativeSettings::getConsoleLanguage,
            onChoiceChanged = NativeSettings::setConsoleLanguage,
            choiceToString = { consoleLanguageToString(it) },
            choices = listOf(
                NativeSettings.ConsoleLanguage.JAPANESE,
                NativeSettings.ConsoleLanguage.ENGLISH,
                NativeSettings.ConsoleLanguage.FRENCH,
                NativeSettings.ConsoleLanguage.GERMAN,
                NativeSettings.ConsoleLanguage.ITALIAN,
                NativeSettings.ConsoleLanguage.SPANISH,
                NativeSettings.ConsoleLanguage.CHINESE,
                NativeSettings.ConsoleLanguage.KOREAN,
                NativeSettings.ConsoleLanguage.DUTCH,
                NativeSettings.ConsoleLanguage.PORTUGUESE,
                NativeSettings.ConsoleLanguage.RUSSIAN,
                NativeSettings.ConsoleLanguage.TAIWANESE,
            ),
        )

        SingleSelection(
            label = tr("GamePad position"),
            choice = emulationSettings.gamePadPosition,
            onChoiceChanged = { viewModel.setGamePadPosition(it) },
            choiceToString = { gamePadPositionToString(it) },
            choices = GamePadPosition.entries,
        )
        Header(tr("Experimental"))
        Toggle(
            label = tr("Emulate vertex attribute divisors"),
            description = tr("For GPUs/drivers without this feature (most Adreno drivers). Can fix objects that are missing or without colors, for example the glider in Mario Kart 8"),
            initialCheckedState = { NativeSettings.getExperimentalOption(0) },
            onCheckedChanged = { NativeSettings.setExperimentalOption(0, it) },
        )
        Toggle(
            label = tr("Retry failed pipelines"),
            description = tr("When the driver refuses to create a graphics pipeline, try again without the pipeline cache"),
            initialCheckedState = { NativeSettings.getExperimentalOption(1) },
            onCheckedChanged = { NativeSettings.setExperimentalOption(1, it) },
        )
        Toggle(
            label = tr("Map null page"),
            description = tr("Reads through a null pointer return zeros instead of closing the game. May let games that crash at a button press or a loading screen go on. Takes effect the next time a game starts"),
            initialCheckedState = { NativeSettings.getExperimentalOption(3) },
            onCheckedChanged = { NativeSettings.setExperimentalOption(3, it) },
        )
        Toggle(
            label = tr("Detailed log"),
            description = tr("Log unsupported API calls and files that games look for and do not find. Useful when reporting problems"),
            initialCheckedState = { NativeSettings.getExperimentalOption(2) },
            onCheckedChanged = { NativeSettings.setExperimentalOption(2, it) },
        )
    }
}

private fun gamePadPositionToString(position: GamePadPosition) = when (position) {
    GamePadPosition.ABOVE -> tr("Above")
    GamePadPosition.BELOW -> tr("Below")
    GamePadPosition.LEFT -> tr("Left")
    GamePadPosition.RIGHT -> tr("Right")
}

private fun consoleLanguageToString(channels: Int): String = when (channels) {
    NativeSettings.ConsoleLanguage.JAPANESE -> tr("Japanese")
    NativeSettings.ConsoleLanguage.ENGLISH -> tr("English")
    NativeSettings.ConsoleLanguage.FRENCH -> tr("French")
    NativeSettings.ConsoleLanguage.GERMAN -> tr("German")
    NativeSettings.ConsoleLanguage.ITALIAN -> tr("Italian")
    NativeSettings.ConsoleLanguage.SPANISH -> tr("Spanish")
    NativeSettings.ConsoleLanguage.CHINESE -> tr("Chinese")
    NativeSettings.ConsoleLanguage.KOREAN -> tr("Korean")
    NativeSettings.ConsoleLanguage.DUTCH -> tr("Dutch")
    NativeSettings.ConsoleLanguage.PORTUGUESE -> tr("Portuguese")
    NativeSettings.ConsoleLanguage.RUSSIAN -> tr("Russian")
    NativeSettings.ConsoleLanguage.TAIWANESE -> tr("Taiwanese")
    else -> throw IllegalArgumentException("Invalid console language: $channels")
}