package dev.skillbill.desktop.core.ui.di

/** Lives as long as one desktop window is not disposed. Hiding a window keeps it; disposing it or exiting the app ends it. */
abstract class WindowScope private constructor()
