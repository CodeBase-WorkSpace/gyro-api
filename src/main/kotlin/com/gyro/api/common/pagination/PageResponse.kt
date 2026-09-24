package com.gyro.api.common.pagination

data class PageResponse<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val totalItems: Long,
    val totalPages: Int,
)

data class PageRequestValues(
    val page: Int,
    val size: Int,
)

object Pagination {
    const val DEFAULT_PAGE = 0
    const val DEFAULT_SIZE = 20
    const val MAX_SIZE = 50

    fun normalize(
        page: Int?,
        size: Int?,
    ): PageRequestValues {
        val normalizedPage = page ?: DEFAULT_PAGE
        val normalizedSize = size ?: DEFAULT_SIZE

        require(normalizedPage >= 0) { "Page must be zero or greater." }
        require(normalizedSize in 1..MAX_SIZE) { "Size must be between 1 and $MAX_SIZE." }

        return PageRequestValues(
            page = normalizedPage,
            size = normalizedSize,
        )
    }
}
