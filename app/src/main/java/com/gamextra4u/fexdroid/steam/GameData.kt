package com.gamextra4u.fexdroid.steam

data class SteamGame(
    val appId: Int,
    val name: String,
    val displayName: String,
    val contentDir: String,
    val installDir: String,
    val isInstalled: Boolean,
    val sizeOnDisk: Long = 0L,
    val isFavorite: Boolean = false,
    val playtime: Long = 0L,
    val lastPlayedAt: Long = 0L,
    val hasCloudSaves: Boolean = false,
    val cloudSaveSize: Long = 0L,
    val updateState: GameUpdateState = GameUpdateState.None,
    val downloadProgress: DownloadProgress = DownloadProgress(0L, 0L, 0f)
)

enum class GameUpdateState {
    None,
    UpdateAvailable,
    Downloading,
    Installing,
    ReadyToPlay
}

data class DownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long,
    val progress: Float
) {
    val isDownloading: Boolean = progress in 0f..0.99f
    val isCompleted: Boolean = progress >= 1f
}

data class GameLibraryStats(
    val totalGames: Int,
    val installedGames: Int,
    val totalSize: Long,
    val updatesAvailable: Int,
    val gamesWithCloudSaves: Int
)

enum class GameFilterType {
    All,
    Installed,
    NotInstalled,
    Favorites,
    NeedingUpdate,
    WithCloudSaves
}

enum class GameSortType {
    Name,
    InstallDate,
    PlayTime,
    Size,
    RecentlyPlayed
}
