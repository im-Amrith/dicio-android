package org.stypox.dicio.ai

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.R

/**
 * Not a real skill (it is never in the skill list): used to show the AI brain's name and icon in
 * the conversation while it is thinking and next to its answers.
 */
object AiInfo : SkillInfo("ai_brain") {
    override fun name(context: Context) = context.getString(R.string.ai_brain_name)

    override fun sentenceExample(context: Context) = ""

    @Composable
    override fun icon() = rememberVectorPainter(Icons.Default.AutoAwesome)

    override fun build(ctx: SkillContext): Skill<*>? = null
}
