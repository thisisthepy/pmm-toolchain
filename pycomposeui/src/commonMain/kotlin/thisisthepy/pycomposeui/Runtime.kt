package thisisthepy.pycomposeui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers


/*
 * Description: Composable Wrapper contains Kotlin Composable can be invoked by Python functions
 */
@JvmName("composableWrapper")
@Composable
fun composableWrapper(content: @Composable (args: Array<Any>) -> Any, args: Array<Any>): Any {
    return content(args)
}


@JvmName("rememberCoroutineScopeWrapper")
@Composable
fun rememberCoroutineScopeWrapper(): CoroutineScope {
    return rememberCoroutineScope()
}

@JvmName("defaultCoroutineScopeWrapper")
@Composable
fun defaultCoroutineScopeWrapper(): CoroutineScope {
    return CoroutineScope(Dispatchers.Default)
}

@JvmName("mainCoroutineScopeWrapper")
@Composable
fun mainCoroutineScopeWrapper(): CoroutineScope {
    return CoroutineScope(Dispatchers.Main)
}

@JvmName("ioCoroutineScopeWrapper")
@Composable
fun ioCoroutineScopeWrapper(): CoroutineScope {
    return CoroutineScope(Dispatchers.IO)
}

@JvmName("uncondifiedCoroutineScopeWrapper")
@Composable
fun uncondifiedCoroutineScopeWrapper(): CoroutineScope {
    return CoroutineScope(Dispatchers.Unconfined)
}
