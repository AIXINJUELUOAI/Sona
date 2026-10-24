package app.sona.music

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.time.LocalTime
import kotlinx.coroutines.delay

internal fun greetingForHour(hour: Int): String = when (hour) {
    in 5..10 -> "早上好"
    in 11..13 -> "中午好"
    in 14..18 -> "下午好"
    else -> "晚上好"
}

@Composable
internal fun rememberGreeting(): String {
    var greeting by remember { mutableStateOf(greetingForHour(LocalTime.now().hour)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                greeting = greetingForHour(LocalTime.now().hour)
                delay(1000L * (60 - LocalTime.now().second))
            }
        }
    }
    return greeting
}
