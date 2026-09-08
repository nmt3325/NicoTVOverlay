package dev.nicotv.comment
import dev.nicotv.core.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.OkHttpClient
// Compile-only worktree contract placeholders; replace before final integration.
class NicoLiveCommentSource(client: OkHttpClient = OkHttpClient()) : CommentSource {
 override fun stream(station: Station): Flow<StreamEvent> = flow { emit(StreamEvent.State(ConnectionState.IDLE, "実装準備中", CommentOrigin.NICONICO)) }
}
class NxJikkyoCommentSource(client: OkHttpClient = OkHttpClient()) : CommentSource {
 override fun stream(station: Station): Flow<StreamEvent> = flow { emit(StreamEvent.State(ConnectionState.IDLE, "実装準備中", CommentOrigin.NX_JIKKYO)) }
}
