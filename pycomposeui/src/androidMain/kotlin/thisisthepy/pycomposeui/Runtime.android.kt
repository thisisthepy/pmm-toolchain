package thisisthepy.pycomposeui

import android.annotation.SuppressLint
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.chaquo.python.PyObject


/*
 * Description: Composable Template contains Python codes can be invoked by Kotlin functions
 */
@JvmName("composableInvocationWrapper")
@Composable
fun composableInvocationWrapper(content: PyObject): @Composable () -> Unit {
    return { content.call() }
}

@JvmName("composableInvocationWrapperWithParam")
@Composable
fun composableInvocationWrapperWithParam(content: PyObject): @Composable (List<Any>) -> Unit {
    return { args -> content.call(*args.toTypedArray()) }
}


@JvmName("functionInvocationWrapper")
fun functionInvocationWrapper(content: PyObject): () -> Any {
    return { content.call() }
}

@JvmName("functionInvocationWrapperWithParam")
fun functionInvocationWrapperWithParam(content: PyObject): (List<Any>) -> Any {
    return { args -> content.call(*args.toTypedArray()) }
}


@SuppressLint("MutableCollectionMutableState")
@JvmName("rememberSaveableWrapper")
@Composable
fun rememberSaveableWrapper(init: PyObject, type: PyObject): MutableState<out Any> = when(type.toString()) {
    "int" -> {
        rememberSaveable { mutableIntStateOf(init.toInt()) }
    }
    "long" -> {
        rememberSaveable { mutableLongStateOf(init.toLong()) }
    }
    "boolean" -> {
        rememberSaveable { mutableStateOf(init.toBoolean()) }
    }
    "float" -> {
        rememberSaveable { mutableDoubleStateOf(init.toDouble()) }
    }
    "str" -> {
        rememberSaveable { mutableStateOf(init.toString()) }
    }
    else -> {
        println("Warning: A value that cannot be converted to a primitive type is passed as a argument to the rememberSaveable function." +
                "An exception may occur if CustomSaver is not specified... : Any - $init")
        rememberSaveable { mutableStateOf(init) }
    }
}


@JvmName("coroutineLauncherWrapper")
fun coroutineLauncherWrapper(scope: CoroutineScope, runner: PyObject) {
    scope.launch {
        runner.call()
    }
}
