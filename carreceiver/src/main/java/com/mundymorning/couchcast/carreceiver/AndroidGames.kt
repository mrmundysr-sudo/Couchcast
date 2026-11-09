package com.mundymorning.couchcast.carreceiver

import android.content.Context

/** Games are web apps packaged under assets/games/<folder>/index.html. Drop another folder in to add one. */
object AndroidGames {
    fun list(ctx: Context): List<GameInfo> {
        val am = ctx.assets
        return (am.list("games") ?: emptyArray())
            .filter { GameCatalog.isSafeId(it) && am.list("games/$it")?.contains("index.html") == true }
            .sorted()
            .map { id ->
                val html = try { am.open("games/$id/index.html").use { String(it.readBytes(), Charsets.UTF_8) } } catch (_: Exception) { "" }
                GameInfo(id, GameCatalog.titleOf(html, id))
            }
    }
}
