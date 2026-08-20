package com.tempobox.common

import javax.inject.Qualifier

/**
 * Hilt qualifiers for coroutine dispatchers. Injecting dispatchers (instead of
 * referencing `Dispatchers.IO` directly) lets unit tests substitute a
 * `TestDispatcher` and control virtual time.
 *
 * Bound in the app module's `CoroutinesModule`.
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class IoDispatcher

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class DefaultDispatcher

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class MainDispatcher

/** Qualifier for an application-scoped CoroutineScope (SupervisorJob). */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class ApplicationScope
