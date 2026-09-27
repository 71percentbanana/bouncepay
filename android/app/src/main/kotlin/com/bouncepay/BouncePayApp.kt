package com.bouncepay

import android.app.Application
import android.content.Context
import com.bouncepay.mesh.MeshNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Owns the one mesh node for the process.
 *
 * It lives here rather than in the activity so rotating the screen or leaving
 * the app does not tear down the radio: the foreground service keeps the
 * process alive, and the activity just watches.
 */
class BouncePayApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val mesh: MeshNode by lazy { MeshNode(this, appScope) }
}

val Context.mesh: MeshNode get() = (applicationContext as BouncePayApp).mesh
