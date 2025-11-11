package com.arny.ffmpegcompose.components.root

// components/root/RootComponent.kt

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.stack.*
import com.arkivanov.decompose.value.Value
import com.arny.ffmpegcompose.components.home.HomeComponent
import com.arny.ffmpegcompose.components.setup.DefaultSetupComponent
import com.arny.ffmpegcompose.components.setup.SetupComponent
import com.arny.ffmpegcompose.data.config.ConfigManager
import com.arny.ffmpegcompose.data.models.FFmpegManager
import kotlinx.serialization.Serializable

interface RootComponent {
    val stack: Value<ChildStack<*, Child>>

    sealed class Child {
        class SetupChild(val component: SetupComponent) : Child()
        class HomeChild(val component: HomeComponent) : Child()
    }
}

class DefaultRootComponent(
    componentContext: ComponentContext,
    private val configManager: ConfigManager,
    private val ffmpegManager: FFmpegManager,
    private val homeComponentFactory: (ComponentContext) -> HomeComponent
) : RootComponent, ComponentContext by componentContext {

    private val navigation = StackNavigation<Config>()

    override val stack: Value<ChildStack<*, RootComponent.Child>> =
        childStack(
            source = navigation,
            serializer = Config.serializer(),
            initialConfiguration = if (configManager.isFfmpegConfigured()) {
                Config.Home
            } else {
                Config.Setup
            },
            handleBackButton = true,
            childFactory = ::child,
        )

    private fun child(config: Config, componentContext: ComponentContext): RootComponent.Child =
        when (config) {
            is Config.Setup -> RootComponent.Child.SetupChild(setupComponent(componentContext))
            is Config.Home -> RootComponent.Child.HomeChild(homeComponent(componentContext))
        }

    private fun setupComponent(componentContext: ComponentContext): SetupComponent =
        DefaultSetupComponent(
            componentContext = componentContext,
            ffmpegManager = ffmpegManager,
            configManager = configManager,
            onComplete = {
                navigation.replaceCurrent(Config.Home)
            }
        )

    private fun homeComponent(componentContext: ComponentContext): HomeComponent =
        homeComponentFactory(componentContext)

    @Serializable
    sealed interface Config {
        @Serializable
        data object Setup : Config

        @Serializable
        data object Home : Config
    }
}
