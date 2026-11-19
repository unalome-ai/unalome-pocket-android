package ai.unalome.pocket.modelcatalog

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Turns download and storage errors into plain words a first-time user can act on. Pure. */
object PlainErrorMessage {
    fun describe(error: Throwable): String = when (error) {
        is DownloadException.BadStatus -> httpStatus(error.status) ?: error.message!!
        is DownloadException.NotEnoughSpace ->
            "Not enough free storage: this model needs ${ByteFormat.file(error.needed)} and ${ByteFormat.file(error.available)} is free. " +
                "Free up some space and tap Try again."
        is DownloadException -> error.message!!
        is UnknownHostException, is ConnectException, is NoRouteToHostException ->
            "No internet connection. The download is paused where it stopped and continues when you tap Try again. " +
                "Chats with installed models work offline."
        is SocketTimeoutException, is SSLException ->
            "Hugging Face could not be reached. Check the connection and try again; the download continues where it stopped."
        is SocketException -> "The connection was lost. Tap Try again; the download continues where it stopped."
        is IOException -> if (isOutOfSpace(error)) {
            "This phone ran out of storage during the download. Free up some space and tap Try again: the download continues where it stopped."
        } else {
            "The download was interrupted. Try again; it continues where it stopped."
        }
        else -> error.message ?: "The download failed."
    }

    /** Hugging Face answers that need a plain explanation; `null` for the rest. */
    fun httpStatus(status: Int): String? = when (status) {
        401, 403 -> "This model needs a Hugging Face account or licence approval to download, which this app does not use."
        404 -> "This model file is no longer on Hugging Face."
        429 -> "Hugging Face is limiting downloads right now. Wait a few minutes and try again."
        in 500..599 -> "Hugging Face is having trouble right now. Try again later."
        else -> null
    }

    private fun isOutOfSpace(error: Throwable): Boolean {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause.message?.contains("ENOSPC") == true || cause.message?.contains("No space left") == true) return true
            cause = cause.cause
        }
        return false
    }
}
