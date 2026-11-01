package org.stypox.dicio.skills.music

import org.dicio.skill.context.SkillContext
import org.stypox.dicio.R
import org.stypox.dicio.io.graphical.HeadlineSpeechSkillOutput
import org.stypox.dicio.util.getString

class MusicOutput(
    private val result: Result,
    private val query: String?,
) : HeadlineSpeechSkillOutput {
    enum class Result { PLAYING, SEARCHING, NOT_INSTALLED, NO_QUERY }

    override fun getSpeechOutput(ctx: SkillContext): String = when (result) {
        Result.PLAYING -> ctx.getString(R.string.skill_music_playing, query ?: "")
        Result.SEARCHING -> ctx.getString(R.string.skill_music_searching, query ?: "")
        Result.NOT_INSTALLED -> ctx.getString(R.string.skill_music_not_installed)
        Result.NO_QUERY -> ctx.getString(R.string.skill_music_no_query)
    }
}
