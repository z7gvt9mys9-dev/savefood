package ru.savefood.app.core.common
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import java.io.IOException
import ru.savefood.app.R
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
        400 -> AppStrings.get(R.string.error_invalid_request)
        401 -> AppStrings.get(R.string.error_session_expired)
        403 -> AppStrings.get(R.string.error_forbidden)
        404 -> AppStrings.get(R.string.error_not_found)
        409 -> AppStrings.get(R.string.error_conflict)
        422 -> AppStrings.get(R.string.error_invalid_fields)
        429 -> AppStrings.get(R.string.error_too_many_requests)
        in 500..599 -> AppStrings.get(R.string.error_server)
        else -> AppStrings.get(R.string.error_request_code, code)
    }
    ApiResult.Error(msg, code)
} catch (e: IOException) {
    ApiResult.Error(AppStrings.get(R.string.error_no_connection))
} catch (e: UserVisibleException) {
    ApiResult.Error(e.message ?: AppStrings.get(R.string.error_unknown))
} catch (e: Exception) {
    ApiResult.Error(AppStrings.get(R.string.error_unknown))
}
