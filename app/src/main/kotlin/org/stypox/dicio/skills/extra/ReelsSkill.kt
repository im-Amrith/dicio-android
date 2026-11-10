package org.stypox.dicio.skills.extra

import android.content.Context
import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Swipe
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import kotlinx.coroutines.delay
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.dicio.skill.standard.StandardRecognizerSkill
import org.stypox.dicio.io.graphical.Headline
import org.stypox.dicio.io.graphical.HeadlineSpeechSkillOutput
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.sentences.Sentences.Reels
import org.stypox.dicio.skills.music.SpotifyPlayPresser

object ReelsInfo : SkillInfo("reels") {
    override fun name(context: Context) = "Instagram reels"
    override fun sentenceExample(context: Context) = "Next reel"
    @Composable override fun icon() = rememberVectorPainter(Icons.Default.Swipe)
    override fun build(ctx: SkillContext): Skill<*>? =
        Sentences.Reels[ctx.sentencesLanguage]?.let { ReelsSkill(this, it) }
}

/**
 * Controls Instagram reels with the "Jarvis app control" accessibility helper: swipes to the next
 * or previous reel, taps to pause or resume, and moves the progress bar 5 seconds when Instagram
 * exposes it. Jarvis first brings Instagram back to the front, since saying "Hey Jarvis" may have
 * opened Jarvis on top of it.
 */
class ReelsSkill(info: SkillInfo, data: StandardRecognizerData<Reels>) :
    StandardRecognizerSkill<Reels>(info, data) {

    override suspend fun generateOutput(ctx: SkillContext, inputData: Reels): SkillOutput {
        val helper = SpotifyPlayPresser.instance
            ?: return SpokenOutput(
                "To control reels, turn on Jarvis app control in Settings, Accessibility, Installed apps"
            )
        if (!bringInstagramToFront(ctx.android, helper)) {
            return SpokenOutput("Open a reel in Instagram first")
        }

        val done = when (inputData) {
            // swiping up shows the next reel, swiping down the previous one
            is Reels.Next -> helper.swipeVertical(0.75f, 0.25f)
            is Reels.Previous -> helper.swipeVertical(0.25f, 0.75f)
            is Reels.Pause, is Reels.Resume -> helper.tapCenter()
            is Reels.Forward -> helper.seekBy(SEEK_SECONDS)
            is Reels.Rewind -> helper.seekBy(-SEEK_SECONDS)
        }

        val label = when (inputData) {
            is Reels.Next -> "Next reel"
            is Reels.Previous -> "Previous reel"
            is Reels.Pause -> "Paused"
            is Reels.Resume -> "Playing"
            is Reels.Forward -> "Forward $SEEK_SECONDS seconds"
            is Reels.Rewind -> "Back $SEEK_SECONDS seconds"
        }
        if (!done) {
            return SpokenOutput(
                if (inputData is Reels.Forward || inputData is Reels.Rewind) {
                    "Instagram isn't letting me move through this reel"
                } else {
                    "I couldn't do that in Instagram"
                }
            )
        }
        // stay silent so Jarvis's voice doesn't talk over the reel; just show what happened
        return object : HeadlineSpeechSkillOutput {
            override fun getSpeechOutput(ctx: SkillContext) = ""
            @Composable override fun GraphicalOutput(ctx: SkillContext) = Headline(label)
        }
    }

    private suspend fun bringInstagramToFront(context: Context, helper: SpotifyPlayPresser): Boolean {
        if (helper.foregroundPackage() == INSTAGRAM) return true
        // reopening Instagram resumes it where it was, on the same reel
        val launch = context.packageManager.getLaunchIntentForPackage(INSTAGRAM) ?: return false
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        repeat(10) {
            delay(200)
            if (helper.foregroundPackage() == INSTAGRAM) {
                delay(400) // let the reel settle before touching it
                return true
            }
        }
        return false
    }

    companion object {
        private const val INSTAGRAM = "com.instagram.android"
        private const val SEEK_SECONDS = 5
    }
}
