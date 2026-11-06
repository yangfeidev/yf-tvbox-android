package me.bsdnme.tvbox.core

/**
 * 内置默认源地址。
 *
 * 这些是使用者自己的短链反代服务（Cloudflare Worker `tv-source-switcher`）：
 * - [BOX]：单仓订阅，标准 TVBox JSON
 * - [WAREHOUSE]：多仓订阅，需要在导入时选择子仓库
 * - [LIVE]：直播列表，Worker 已过滤掉 rtp 组播，只留公网可播的 http(s) 流
 *
 * 首次启动会自动导入 [BOX] 并写入 [LIVE]；
 * 其余地址在设置页以快捷胶囊形式提供，避免用户手输。
 * 用户随时可以在设置页改成自己的地址。
 */
object DefaultSources {
    const val BOX = "https://tv.bsdnme.me/box"
    const val WAREHOUSE = "https://tv.bsdnme.me/dc"
    const val LIVE = "https://tv.bsdnme.me/live"
}
