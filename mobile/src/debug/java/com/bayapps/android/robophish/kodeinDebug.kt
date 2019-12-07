package com.bayapps.android.robophish

import org.kodein.di.Kodein
import org.kodein.di.generic.bind
import org.kodein.di.generic.instance
import org.kodein.di.generic.singleton

val debugModule = Kodein.Module("Debug Module") {
    bind<AppInitializer>() with singleton { DebugAppInitializer(instance()) }
}

val buildSpecificModules = listOf(debugModule)
