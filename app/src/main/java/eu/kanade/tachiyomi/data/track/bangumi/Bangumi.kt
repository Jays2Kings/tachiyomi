package eu.kanade.tachiyomi.data.track.bangumi

import android.content.Context
import android.graphics.Color
import androidx.annotation.StringRes
import com.google.gson.Gson
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.TrackService
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.data.track.myanimelist.MyAnimeList
import eu.kanade.tachiyomi.data.track.updateNewTrackInfo
import timber.log.Timber
import uy.kohesive.injekt.injectLazy

class Bangumi(private val context: Context, id: Int) : TrackService(id) {

    @StringRes
    override fun nameRes() = R.string.bangumi

    private val gson: Gson by injectLazy()

    private val interceptor by lazy { BangumiInterceptor(this, gson) }

    private val api by lazy { BangumiApi(client, interceptor) }

    override fun getScoreList(): List<String> {
        return IntRange(0, 10).map(Int::toString)
    }

    override fun displayScore(track: Track): String {
        return track.score.toInt().toString()
    }

    override suspend fun update(track: Track, setToReadStatus: Boolean): Track {
        if (setToReadStatus && track.status == PLANNING && track.last_chapter_read != 0) {
            track.status = READING
        }
        if (track.total_chapters != 0 && track.last_chapter_read == track.total_chapters) {
            track.status = COMPLETED
        }
        return api.updateLibManga(track)
    }

    override suspend fun add(track: Track): Track {
        track.score = DEFAULT_SCORE.toFloat()
        track.status = DEFAULT_STATUS
        updateNewTrackInfo(track, PLANNING)
        api.addLibManga(track)
        return update(track)
    }

    override suspend fun bind(track: Track): Track {
        val statusTrack = api.statusLibManga(track)
        val remoteTrack = api.findLibManga(track)
        return if (statusTrack != null && remoteTrack != null) {
            track.copyPersonalFrom(remoteTrack)
            track.library_id = remoteTrack.library_id
            track.status = remoteTrack.status
            track.last_chapter_read = remoteTrack.last_chapter_read
            refresh(track)
        } else {
            add(track)
        }
    }

    override suspend fun search(query: String): List<TrackSearch> {
        return api.search(query)
    }

    override suspend fun refresh(track: Track): Track {
        val statusTrack = api.statusLibManga(track)
        track.copyPersonalFrom(statusTrack!!)
        val remoteTrack = api.findLibManga(track)
        if (remoteTrack != null) {
            track.total_chapters = remoteTrack.total_chapters
            track.status = remoteTrack.status
        }
        return track
    }

    override fun getLogo() = R.drawable.ic_tracker_bangumi

    override fun getLogoColor() = Color.rgb(240, 145, 153)

    override fun getStatusList(): List<Int> {
        return listOf(READING, COMPLETED, ON_HOLD, DROPPED, PLANNING)
    }

    override fun isCompletedStatus(index: Int) = getStatusList()[index] == COMPLETED

    override fun completedStatus(): Int = MyAnimeList.COMPLETED

    override fun getStatus(status: Int): String = with(context) {
        when (status) {
            READING -> getString(R.string.reading)
            COMPLETED -> getString(R.string.completed)
            ON_HOLD -> getString(R.string.on_hold)
            DROPPED -> getString(R.string.dropped)
            PLANNING -> getString(R.string.plan_to_read)
            else -> ""
        }
    }

    override fun getGlobalStatus(status: Int): String = with(context) {
        when (status) {
            READING -> getString(R.string.reading)
            PLANNING -> getString(R.string.plan_to_read)
            COMPLETED -> getString(R.string.completed)
            ON_HOLD -> getString(R.string.on_hold)
            DROPPED -> getString(R.string.dropped)
            else -> ""
        }
    }

    override suspend fun login(username: String, password: String): Boolean = login(password)

    suspend fun login(code: String): Boolean {
        try {
            val oauth = api.accessToken(code)
            interceptor.newAuth(oauth)
            saveCredentials(oauth.user_id.toString(), oauth.access_token)
            return true
        } catch (e: Exception) {
            Timber.e(e)
            logout()
        }
        return false
    }

    fun saveToken(oauth: OAuth?) {
        val json = gson.toJson(oauth)
        preferences.trackToken(this).set(json)
    }

    fun restoreToken(): OAuth? {
        return try {
            gson.fromJson(preferences.trackToken(this).get(), OAuth::class.java)
        } catch (e: Exception) {
            null
        }
    }

    override fun logout() {
        super.logout()
        preferences.trackToken(this).delete()
    }

    companion object {
        const val PLANNING = 1
        const val COMPLETED = 2
        const val READING = 3
        const val ON_HOLD = 4
        const val DROPPED = 5

        const val DEFAULT_STATUS = READING
        const val DEFAULT_SCORE = 0
    }
}
