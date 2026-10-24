package app.sona.music

/** Follow the player's fixed traversal order; duplicate songs remain separate queue entries. */
internal fun queueOrder(count: Int, first: Int, next: (Int) -> Int): List<Int> {
    val order = ArrayList<Int>(count)
    val visited = BooleanArray(count)
    var index = first
    while (index in 0 until count && !visited[index]) {
        order += index
        visited[index] = true
        index = next(index)
    }
    // A temporarily incomplete timeline must not hide tracks from the queue.
    return if (order.size == count) order else (0 until count).toList()
}
