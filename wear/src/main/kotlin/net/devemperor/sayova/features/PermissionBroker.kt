package net.devemperor.sayova.features

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import net.devemperor.sayova.core.tools.WatchFeature

/**
 * Lets a conversation ask for a permission when the model first needs a watch feature. Only an
 * activity can show Android's permission dialog, so the request waits here until MainActivity, while
 * on screen, shows it and reports the answer.
 */
object PermissionBroker {

    class Request(val feature: WatchFeature, val permissions: List<String>) {
        internal val answer = CompletableDeferred<Boolean>()
    }

    private val _pending = MutableStateFlow<Request?>(null)
    val pending: StateFlow<Request?> = _pending.asStateFlow()

    /** Set by MainActivity while it is started; with the screen off there is no one to ask. */
    @Volatile var hostVisible = false

    enum class Answer { GRANTED, DENIED, NOT_ON_SCREEN }

    /** Asks the user to allow [feature]; at once [Answer.NOT_ON_SCREEN] while Sayova is not on screen. */
    suspend fun request(feature: WatchFeature): Answer {
        if (!hostVisible) return Answer.NOT_ON_SCREEN
        val request = Request(feature, WatchFeatures.permissions(feature))
        _pending.value = request
        return try {
            if (withTimeoutOrNull(TIMEOUT_MS) { request.answer.await() } == true) Answer.GRANTED else Answer.DENIED
        } finally {
            _pending.compareAndSet(request, null)
        }
    }

    fun answer(request: Request, granted: Boolean) {
        request.answer.complete(granted)
        _pending.compareAndSet(request, null)
    }

    private const val TIMEOUT_MS = 60_000L
}
