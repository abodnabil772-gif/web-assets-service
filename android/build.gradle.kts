// =========================================================================
// ⚡ مملكة ناصر دين الله الكلعي ⚡ - Uranium Supreme Root Build Configuration v8.0
// السيادة المطلقة على البناء والتجميع السيبراني
// =========================================================================

plugins {
    id("com.android.application") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.23" apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.buildDir)
}
