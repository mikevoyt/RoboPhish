package com.bayapps.android.robophish

import org.kodein.di.Kodein
import org.kodein.di.generic.bind
import org.kodein.di.generic.instance
import org.kodein.di.generic.singleton

val releaseModule = Kodein.Module("Release Module") {
    bind<AppInitializer>() with singleton { ReleaseAppInitializer() }
}

val buildSpecificModules = listOf(releaseModule)
