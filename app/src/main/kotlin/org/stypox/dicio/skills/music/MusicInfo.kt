package org.stypox.dicio.skills.music

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.R
import org.stypox.dicio.sentences.Sentences

object MusicInfo : SkillInfo("music") {
    override fun name(context: Context) =
        context.getString(R.string.skill_name_music)

    override fun sentenceExample(context: Context) =
        context.getString(R.string.skill_sentence_example_music)

    @Composable
    override fun icon() =
        rememberVectorPainter(Icons.Default.MusicNote)

    override fun build(ctx: SkillContext): Skill<*>? {
        val data = Sentences.Music[ctx.sentencesLanguage] ?: return null
        return MusicSkill(MusicInfo, data)
    }
}
