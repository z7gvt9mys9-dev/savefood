package ru.savefood.app.core.common
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import java.io.IOException
/** Lightweight result type for repository calls — keeps ViewModels free of try/catch. */
sealed interface ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>
    data class Error(val message: String, val code: Int? = null) : ApiResult<Nothing>
}
suspend fun <T> safeApiCall(block: suspend () -> T): ApiResult<T> = try {
    ApiResult.Success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: HttpException) {
    val code = e.code()
    val msg = when (code) {
        401 -> "Сессия истекла. Войдите снова."
        403 -> "Доступ запрещён."
        404 -> "Не найдено."
        429 -> "Слишком много запросов. Попробуйте позже."
        in 500..599 -> "Ошибка сервера. Попробуйте позже."
        else -> "Ошибка запроса ($code)."
    }
    ApiResult.Error(msg, code)
} catch (e: IOException) {
    ApiResult.Error("Нет соединения с сервером.")
} catch (e: Exception) {
    ApiResult.Error(e.message ?: "Неизвестная ошибка.")
}
