package info.cemu.cemu.games.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import info.cemu.cemu.inspector.InspectorReport
import info.cemu.cemu.nativeinterface.NativeGameTitles
import info.cemu.cemu.nativeinterface.NativeGameTitles.Game
import info.cemu.cemu.nativeinterface.NativeSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class GamesListViewModel : ViewModel() {
    private var gamePaths = NativeSettings.getGamesPaths().toSet()

    private val _filterText = MutableStateFlow("")
    val filterText = _filterText.asStateFlow()
    fun setFilterText(filterText: String) {
        _filterText.value = filterText
    }

    private val _games = MutableStateFlow<Set<Game>>(emptySet())
    val games: StateFlow<List<Game>> = combine(_filterText, _games) { filter, games ->
        if (filter.isBlank()) {
            games
        } else {
            games.filter { it.name?.contains(filter, true) ?: false }
        }
    }.map {
        it.sorted()
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
    )

    init {
        NativeGameTitles.setGameTitleLoadedCallback(NativeGameTitles.GameTitleLoadedCallback { game: Game ->
            if (_games.value.any { it.titleId == game.titleId || it.path == game.path })
                return@GameTitleLoadedCallback

            _games.value += game
        })
        refreshGames()
    }

    private val _inspectorProgress = MutableStateFlow(-1)

    /** progress (0-100) of the inspector report of the last game session, -1 when it is not being made */
    val inspectorProgress: StateFlow<Int> = _inspectorProgress.asStateFlow()

    fun generateInspectorReportIfNeeded(userDataFolder: File) {
        if (_inspectorProgress.value >= 0 || !NativeSettings.getExperimentalOption(4)) {
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            if (!InspectorReport.isReportNeeded(userDataFolder)) {
                return@launch
            }
            _inspectorProgress.value = 0
            try {
                InspectorReport.generate(userDataFolder) { _inspectorProgress.value = it }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                // the report is a diagnostic aid: a failure must not disturb the menu
            } finally {
                _inspectorProgress.value = -1
            }
        }
    }

    fun removeShadersForGame(game: Game) {
        NativeGameTitles.removeShaderCacheFilesForTitle(game.titleId)
    }

    fun setGameTitleFavorite(game: Game, isFavorite: Boolean) {
        if (!_games.value.contains(game)) {
            return
        }
        NativeGameTitles.setGameTitleFavorite(game.titleId, isFavorite)
        _games.value = _games.value.toMutableSet().apply {
            remove(game)
            add(game.copy(isFavorite = isFavorite))
        }
    }

    override fun onCleared() {
        NativeGameTitles.setGameTitleLoadedCallback(null)
    }

    fun gamePathsHaveChanged(): Boolean {
        val newGamePaths = NativeSettings.getGamesPaths().toSet()
        if (newGamePaths != gamePaths) {
            gamePaths = newGamePaths
            return true
        }
        return false
    }

    fun refreshGames() {
        _games.value = emptySet()
        NativeGameTitles.reloadGameTitles()
    }
}
