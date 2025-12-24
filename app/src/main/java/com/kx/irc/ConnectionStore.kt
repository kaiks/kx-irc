package com.kx.irc

import android.content.Context

class ConnectionStore(context: Context) {
    private val prefs = context.getSharedPreferences("kx_irc_prefs", Context.MODE_PRIVATE)

    fun load(): IrcConfig {
        return IrcConfig(
            host = prefs.getString("host", "").orEmpty(),
            port = prefs.getInt("port", 6697),
            useTls = prefs.getBoolean("useTls", false),
            nick = prefs.getString("nick", "").orEmpty(),
            username = prefs.getString("username", "").orEmpty(),
            realName = prefs.getString("realName", "").orEmpty(),
            channels = prefs.getString("channels", "").orEmpty(),
            serverPassword = prefs.getString("serverPassword", "").orEmpty()
        )
    }

    fun save(config: IrcConfig) {
        prefs.edit()
            .putString("host", config.host)
            .putInt("port", config.port)
            .putBoolean("useTls", config.useTls)
            .putString("nick", config.nick)
            .putString("username", config.username)
            .putString("realName", config.realName)
            .putString("channels", config.channels)
            .putString("serverPassword", config.serverPassword)
            .apply()
    }
}
