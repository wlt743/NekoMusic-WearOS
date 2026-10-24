package com.neko.music.wear.data

import kotlinx.serialization.Serializable

@Serializable
data class Music(
    val id: Int,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val duration: Int = 0,
    val coverFilePath: String? = null,
) {
    val coverUrl: String get() = ApiConfig.coverUrl(id)
}

@Serializable
data class MusicListResponse(
    val success: Boolean = false,
    val message: String = "",
    val data: List<Music> = emptyList(),
)

@Serializable
data class AudioUrlData(val url: String? = null)

@Serializable
data class AudioUrlResponse(
    val success: Boolean = false,
    val message: String = "",
    val data: AudioUrlData? = null,
)

@Serializable
data class MusicInfo(
    val id: Int = 0,
    val lyrics: String? = null,
)

@Serializable
data class MusicInfoResponse(
    val success: Boolean = false,
    val message: String = "",
    val data: MusicInfo? = null,
)

@Serializable
data class UserData(
    val id: Int = 0,
    val nickname: String = "",
    val email: String = "",
    val isVip: Boolean = false,
)



@Serializable
data class FavoriteMusic(
    val id: Int,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val duration: Int = 0,
    val cover: String = "",
)

@Serializable
data class FavoriteListResponse(
    val success: Boolean = false,
    val favorites: List<FavoriteMusic> = emptyList(),
)

@Serializable
data class PlaylistInfo(
    val id: Int,
    val name: String = "",
    val description: String? = null,
    val musicCount: Int = 0,
    val coverPath: String? = null,
    val firstMusicCover: String? = null,
)

@Serializable
data class PlaylistListResponse(
    val success: Boolean = false,
    val message: String = "",
    val playlists: List<PlaylistInfo>? = null,
    val results: List<PlaylistInfo>? = null,
)

@Serializable
data class PlaylistMusic(
    val id: Int,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val duration: Int = 0,
    val coverPath: String? = null,
)

@Serializable
data class PlaylistMusicListResponse(
    val success: Boolean = false,
    val message: String = "",
    val musicList: List<PlaylistMusic>? = null,
)


@Serializable
data class QrCreateData(
    val sessionId: String = "",
    val qrImage: String = "",
    val expiresIn: Int = 0,
    val deliveryKey: String = "",
)

@Serializable
data class QrCreateResponse(
    val success: Boolean = false,
    val message: String = "",
    val data: QrCreateData? = null,
)

@Serializable
data class QrClaimData(
    val status: String = "",
    val token: String? = null,
    val user: UserData? = null,
)

@Serializable
data class QrClaimResponse(
    val success: Boolean = false,
    val message: String = "",
    val data: QrClaimData? = null,
)

@Serializable
data class QrClaimRequest(
    val sessionId: String,
    val deliveryKey: String,
)
