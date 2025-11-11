package com.arny.ffmpegcompose.ui

import androidx.compose.runtime.Composable
import com.arkivanov.decompose.extensions.compose.stack.Children
import com.arny.ffmpegcompose.components.root.RootComponent
import com.arny.ffmpegcompose.ui.home.HomeScreen

@Composable
fun RootContent(component: RootComponent) {
    Children(
        stack = component.stack
    ) {
        when (val child = it.instance) {
            is RootComponent.Child.SetupChild -> SetupScreen(child.component)
            is RootComponent.Child.HomeChild -> HomeScreen(child.component)
        }
    }
}