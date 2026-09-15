package com.unbound.rpg

import android.app.Application
import com.unbound.rpg.domain.AppContainer

class UnboundApplication : Application() {
    /**
     * Built lazily: creating the container must not open the database or touch the Keystore, so
     * cold start stays fast (§122).
     */
    val container: AppContainer by lazy { AppContainer(this) }
}
