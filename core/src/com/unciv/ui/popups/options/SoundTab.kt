package com.unciv.ui.popups.options

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.models.UncivSound
import com.unciv.ui.components.widgets.UncivSlider
import com.unciv.ui.audio.MusicTrackChooserFlags
import com.unciv.ui.components.extensions.MusicControls
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick
import com.unciv.utils.Concurrency
import com.unciv.utils.DEFAULT_MUSIC_DOWNLOAD_UNAVAILABLE
import com.unciv.utils.launchOnGLThread

internal class SoundTab(
    optionsPopup: OptionsPopup
) : OptionsPopupTab(optionsPopup), MusicControls {
    override fun lateInitialize() {
        val music = game.musicController

        section("Volume") {
            addSoundEffectsVolumeSlider(settings)
            addCitySoundsVolumeSlider(settings)
            if (music.isVoicesAvailable()) addVoicesVolumeSlider(settings, music)
            if (music.isMusicAvailable()) addMusicVolumeSlider(settings, music)
        }
        section("Music playback") {
            if (music.isMusicAvailable()) {
                addMusicPauseSlider(settings, music)
                addMusicCurrentlyPlaying(music)
                addSimplePlayerControls(music).row()
            }
            if (!music.isDefaultFileAvailable()) {
                if (game.platformCapabilities.defaultMusicDownload) addDownloadMusic()
                else add(DEFAULT_MUSIC_DOWNLOAD_UNAVAILABLE.toLabel(Color.GRAY).apply { wrap = true })
                    .colspan(2).minWidth(0f).growX().padTop(20f).row()
            }
        }

        super.lateInitialize()
    }

    override fun Table.addVolumeSlider(text: String, initial: Float, silent: Boolean, onChange: (Float) -> Unit) {
        val label = add(text.toLabel()).left()
        if (optionsPopup.useSidebar) label.colspan(2).pad(8f, 12f, 0f, 12f).row()
        val slider = UncivSlider(0f, 1f, 0.05f, initial = initial,
            sound = if (silent) UncivSound.Silent else UncivSound.Slider,
            getTipText = UncivSlider::formatPercent, onChange = onChange)
        val cell = add(slider).pad(5f)
        if (optionsPopup.useSidebar) cell.colspan(2).growX().pad(8f, 12f, 12f, 12f)
        cell.row()
    }

    private fun Table.addDownloadMusic() {
        val downloadMusicButton = "Download music".toTextButton()
        add(downloadMusicButton).colspan(2).padTop(20f).row()
        val errorTable = Table()
        add(errorTable).colspan(2).row()

        downloadMusicButton.onClick {
            downloadMusicButton.disable()
            errorTable.clear()
            errorTable.add("Downloading...".toLabel())

            // So the whole game doesn't get stuck while downloading the file
            Concurrency.run("MusicDownload") {
                try {
                    game.musicController.downloadDefaultFile()
                    launchOnGLThread {
                        replacePage { optionsPopup -> SoundTab(optionsPopup) }
                        game.musicController.chooseTrack(flags = MusicTrackChooserFlags.setPlayDefault)
                    }
                } catch (_: Exception) {
                    launchOnGLThread {
                        errorTable.clear()
                        errorTable.add("Could not download music!".toLabel(Color.RED))
                    }
                }
            }
        }
    }
}
